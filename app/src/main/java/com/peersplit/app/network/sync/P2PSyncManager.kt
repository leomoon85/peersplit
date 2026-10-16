package com.peersplit.app.network.sync

import android.content.Context
import android.util.Log
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.GroupGenesisPayload
import com.peersplit.app.blockchain.MemberJoinedPayload
import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.network.nostr.NostrEvent
import com.peersplit.app.network.nostr.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Orchestrates zero-server P2P synchronization.
 * Handles End-to-End Encryption of blocks and propagates them via decentralized Nostr relays.
 */
class P2PSyncManager(
    private val context: Context,
    private val database: AppDatabase,
    private val relayPool: RelayPool = RelayPool()
) {
    companion object {
        private const val TAG = "P2PSyncManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }

    fun initialize() {
        relayPool.connectAll()

        // Listen for incoming peer blocks
        scope.launch {
            relayPool.incomingEvents.collect { event ->
                handleIncomingEvent(event)
            }
        }

        // Subscribe to all currently stored groups
        scope.launch {
            val groups = database.groupDao().getAllGroups().first()
            groups.forEach { group ->
                relayPool.subscribeGroup(group.id)
            }
        }
    }

    /**
     * Subscribes to updates for a newly created or joined group.
     */
    fun monitorGroup(groupId: String) {
        relayPool.subscribeGroup(groupId)
    }

    /**
     * Broadcasts a newly minted block to the peer network.
     * The block is encrypted with the group's shared secret key before transmission.
     */
    fun broadcastBlock(block: Block, groupKeyBase64: String) {
        scope.launch {
            try {
                val groupKey = EncryptionUtil.keyFromBase64(groupKeyBase64)
                val blockJson = json.encodeToString(Block.serializer(), block)
                val encryptedContent = EncryptionUtil.encryptString(blockJson, groupKey)

                val identity = KeyManager.getOrCreateIdentity(context)
                val privKeyHex = KeyManager.getPrivateKeyHex(context)

                val tags = listOf(
                    listOf("t", "peersplit"),
                    listOf("g", block.groupId),
                    listOf("idx", block.index.toString())
                )

                val event = NostrEvent.createAndSign(
                    pubkey = identity.publicKeyHex,
                    privateKeyHex = privKeyHex,
                    kind = NostrEvent.KIND_PEERSPLIT_BLOCK,
                    tags = tags,
                    content = encryptedContent
                )

                relayPool.broadcast(event)
                Log.d(TAG, "Broadcasted block #${block.index} for group ${block.groupId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast block: ${e.message}", e)
            }
        }
    }

    private suspend fun handleIncomingEvent(event: NostrEvent) {
        try {
            // Find groupId tag
            val groupTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" } ?: return
            val groupId = groupTag[1]

            // Look up group encryption key locally
            val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return
            val groupKey = EncryptionUtil.keyFromBase64(groupEntity.groupKeyBase64)

            // Decrypt block payload
            val blockJson = EncryptionUtil.decryptString(event.content, groupKey)
            val block = json.decodeFromString(Block.serializer(), blockJson)

            // Cryptographic verification
            if (!block.verifyIntegrity()) {
                Log.w(TAG, "Rejected incoming block #${block.index}: invalid signature or hash")
                return
            }

            // Check if already in DB
            val existingBlocks = database.blockDao().getBlocksForGroupSync(groupId)
            if (existingBlocks.any { it.blockHash == block.blockHash }) {
                return // Already have this block
            }

            // Validate hash link to preceding block
            if (existingBlocks.isNotEmpty()) {
                val latest = existingBlocks.last().toDomainBlock()
                if (block.index == latest.index + 1 && block.prevHash == latest.blockHash) {
                    database.blockDao().insertBlock(BlockEntity.fromDomainBlock(block, isVerified = true))
                    processBlockSideEffects(groupId, block)
                    Log.d(TAG, "Successfully appended peer block #${block.index} to group $groupId")
                } else {
                    Log.w(TAG, "Fork or out-of-order block received: index ${block.index}, expected ${latest.index + 1}")
                }
            } else if (block.index == 0L) {
                // First block (Genesis)
                database.blockDao().insertBlock(BlockEntity.fromDomainBlock(block, isVerified = true))
                processBlockSideEffects(groupId, block)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming peer event: ${e.message}", e)
        }
    }

    private suspend fun processBlockSideEffects(groupId: String, block: Block) {
        when (block.blockType) {
            BlockType.GENESIS -> {
                val payload = json.decodeFromString(GroupGenesisPayload.serializer(), block.payloadJson)
                payload.initialMembers.forEach { member ->
                    database.memberDao().insertMember(
                        MemberEntity(groupId = groupId, pubkey = member.pubkey, displayName = member.displayName)
                    )
                }
            }
            BlockType.MEMBER_JOIN -> {
                val payload = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                database.memberDao().insertMember(
                    MemberEntity(groupId = groupId, pubkey = payload.pubkey, displayName = payload.displayName)
                )
            }
            else -> {
                // Expenses and settlements are reflected dynamically from the block ledger
            }
        }
    }
}
