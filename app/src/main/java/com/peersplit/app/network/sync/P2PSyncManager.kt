package com.peersplit.app.network.sync

import android.content.Context
import android.util.Log
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
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
import java.util.Collections

/**
 * Orchestrates zero-server P2P synchronization.
 * Handles End-to-End Encryption of blocks and propagates them via decentralized Nostr relays.
 * Features automatic out-of-order block buffering and replay.
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
    private val pendingBlocks = Collections.synchronizedMap(mutableMapOf<String, MutableList<Block>>())

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
            try {
                val groups = database.groupDao().getAllGroups().first()
                groups.forEach { group ->
                    Log.d(TAG, "Subscribing to existing group ${group.id}")
                    relayPool.subscribeGroup(group.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to groups: ${e.message}")
            }
        }
    }

    /**
     * Subscribes to updates for a newly created or joined group.
     */
    fun monitorGroup(groupId: String) {
        Log.d(TAG, "Monitoring group: $groupId")
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
                Log.d(TAG, "Broadcasted block #${block.index} (${block.blockType}) for group ${block.groupId}")
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

            // Buffer block and drain chain
            synchronized(pendingBlocks) {
                val list = pendingBlocks.getOrPut(groupId) { mutableListOf() }
                if (list.none { it.blockHash == block.blockHash }) {
                    list.add(block)
                }
            }

            drainPendingBlocks(groupId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming peer event: ${e.message}", e)
        }
    }

    private suspend fun drainPendingBlocks(groupId: String) {
        var progress = true
        while (progress) {
            progress = false
            val existingBlocks = database.blockDao().getBlocksForGroupSync(groupId)
            val existingHashes = existingBlocks.map { it.blockHash }.toSet()

            val candidates = synchronized(pendingBlocks) {
                pendingBlocks[groupId]?.filter { it.blockHash !in existingHashes }?.sortedBy { it.index } ?: emptyList()
            }

            for (candidate in candidates) {
                val latest = existingBlocks.lastOrNull()?.toDomainBlock()

                val canAppend = when {
                    existingBlocks.isEmpty() && candidate.index == 0L -> true
                    latest != null && candidate.index == latest.index + 1 && candidate.prevHash == latest.blockHash -> true
                    else -> false
                }

                if (canAppend) {
                    database.blockDao().insertBlock(BlockEntity.fromDomainBlock(candidate, isVerified = true))
                    processBlockSideEffects(groupId, candidate)
                    Log.d(TAG, "Successfully committed block #${candidate.index} (${candidate.blockType}) to group $groupId")
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf { it.blockHash == candidate.blockHash }
                    }
                    progress = true
                    break // break inner loop to re-fetch latest from DB
                }
            }
        }
    }

    private suspend fun processBlockSideEffects(groupId: String, block: Block) {
        when (block.blockType) {
            BlockType.GENESIS -> {
                val payload = json.decodeFromString(GroupGenesisPayload.serializer(), block.payloadJson)
                payload.initialMembers.forEach { member ->
                    database.memberDao().insertMember(
                        MemberEntity(groupId = groupId, pubkey = member.pubkey, displayName = member.displayName, upiId = member.upiId)
                    )
                }
                Log.d(TAG, "Processed Genesis side effects for group $groupId (${payload.initialMembers.size} members)")
            }
            BlockType.MEMBER_JOIN -> {
                val payload = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                database.memberDao().insertMember(
                    MemberEntity(groupId = groupId, pubkey = payload.pubkey, displayName = payload.displayName, upiId = payload.upiId)
                )
                Log.d(TAG, "Processed Member Join: ${payload.displayName} joined group $groupId")
            }
            else -> {
                // Expenses and settlements are reflected dynamically from the block ledger queries
            }
        }
    }
}
