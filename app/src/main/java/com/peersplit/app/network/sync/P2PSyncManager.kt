package com.peersplit.app.network.sync

import android.content.Context
import android.util.Log
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.ExpensePayload
import com.peersplit.app.blockchain.ExpenseVoidPayload
import com.peersplit.app.blockchain.GroupGenesisPayload
import com.peersplit.app.blockchain.MemberJoinedPayload
import com.peersplit.app.blockchain.SettlementPayload
import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.HashUtil
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.Collections

/**
 * Orchestrates zero-server P2P synchronization.
 * Handles End-to-End Encryption of blocks and propagates them via decentralized Nostr relays.
 * Features automatic out-of-order block buffering, multi-block fork resolution, and rebase.
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
    private val earlyEvents = Collections.synchronizedMap(mutableMapOf<String, MutableList<NostrEvent>>())
    private val syncMutex = Mutex()

    fun initialize() {
        relayPool.connectAll()

        // Listen for incoming peer blocks
        scope.launch {
            relayPool.incomingEvents.collect { event ->
                handleIncomingEvent(event)
            }
        }

        // Subscribe to all currently stored groups and broadcast local blocks
        scope.launch {
            try {
                val groups = database.groupDao().getAllGroups().first()
                groups.forEach { group ->
                    Log.d(TAG, "Subscribing to existing group ${group.id}")
                    relayPool.subscribeGroup(group.id)
                    broadcastGroupChain(group.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to groups: ${e.message}")
            }
        }
    }

    /**
     * Subscribes to updates for a newly created or joined group and processes any early events.
     */
    fun monitorGroup(groupId: String) {
        Log.d(TAG, "Monitoring group: $groupId")
        relayPool.subscribeGroup(groupId)
        scope.launch {
            val buffered = synchronized(earlyEvents) {
                earlyEvents.remove(groupId) ?: emptyList()
            }
            for (ev in buffered) {
                handleIncomingEvent(ev)
            }
            drainPendingBlocks(groupId)
        }
    }

    /**
     * Actively refreshes subscription and broadcasts local chain for a group.
     */
    fun syncGroupNow(groupId: String) {
        scope.launch {
            relayPool.subscribeGroup(groupId)
            broadcastGroupChain(groupId)
            drainPendingBlocks(groupId)
        }
    }

    /**
     * Broadcasts all blocks in the local chain for a group so newly joined or reconnected peers catch up.
     */
    fun broadcastGroupChain(groupId: String) {
        scope.launch {
            try {
                val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return@launch
                val blocks = database.blockDao().getBlocksForGroupSync(groupId)
                blocks.forEach { entity ->
                    broadcastBlock(entity.toDomainBlock(), groupEntity.groupKeyBase64)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast group chain: ${e.message}")
            }
        }
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
                    listOf("t", "peersplit_${block.groupId}"),
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
            // Find groupId tag from either "g" or "t" ("peersplit_<groupId>")
            val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "t" && it[1].startsWith("peersplit_") }
                    ?.get(1)?.removePrefix("peersplit_")
                ?: return

            // Look up group encryption key locally
            val groupEntity = database.groupDao().getGroupByIdSync(groupId)
            if (groupEntity == null) {
                synchronized(earlyEvents) {
                    val list = earlyEvents.getOrPut(groupId) { mutableListOf() }
                    if (list.none { it.id == event.id }) {
                        list.add(event)
                    }
                }
                return
            }
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

    /**
     * Computes a deterministic semantic identity for a block so rebased blocks are never duplicated.
     */
    fun logicalBlockId(block: Block): String {
        return try {
            when (block.blockType) {
                BlockType.GENESIS -> "GENESIS:${block.groupId}"
                BlockType.MEMBER_JOIN -> {
                    val p = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                    "JOIN:${p.pubkey}"
                }
                BlockType.EXPENSE -> {
                    val p = json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
                    "EXP:${p.expenseId}"
                }
                BlockType.EXPENSE_VOID -> {
                    val p = json.decodeFromString(ExpenseVoidPayload.serializer(), block.payloadJson)
                    "VOID:${p.targetExpenseId}"
                }
                BlockType.SETTLEMENT -> {
                    val p = json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
                    "STL:${p.settlementId}"
                }
            }
        } catch (e: Exception) {
            "HASH:${block.blockHash}"
        }
    }

    private fun isPeerCanonicalWinner(peer: Block, local: Block): Boolean {
        return if (peer.timestamp != local.timestamp) {
            peer.timestamp < local.timestamp
        } else {
            peer.blockHash < local.blockHash
        }
    }

    private suspend fun drainPendingBlocks(groupId: String) = syncMutex.withLock {
        val localIdentity = KeyManager.getOrCreateIdentity(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return@withLock

        var progress = true
        while (progress) {
            progress = false
            val existingEntities = database.blockDao().getBlocksForGroupSync(groupId)
            val existingBlocks = existingEntities.map { it.toDomainBlock() }
            val existingHashes = existingBlocks.map { it.blockHash }.toSet()
            val existingLogicalIds = existingBlocks.map { logicalBlockId(it) }.toSet()

            // Remove any pending candidates whose exact hash or semantic logical ID is already in our chain
            synchronized(pendingBlocks) {
                pendingBlocks[groupId]?.removeIf {
                    it.blockHash in existingHashes || logicalBlockId(it) in existingLogicalIds
                }
            }

            val candidates = synchronized(pendingBlocks) {
                pendingBlocks[groupId]?.sortedWith(
                    compareBy<Block> { it.index }.thenBy { it.timestamp }.thenBy { it.blockHash }
                ) ?: emptyList()
            }

            if (candidates.isEmpty()) break

            val latest = existingBlocks.lastOrNull()

            // Step 1: Fast path — append any candidate that directly extends our current tip
            var appendedDirect = false
            for (candidate in candidates) {
                val canAppend = when {
                    existingBlocks.isEmpty() && candidate.index == 0L -> true
                    latest != null && candidate.index == latest.index + 1 && candidate.prevHash == latest.blockHash -> true
                    else -> false
                }
                if (canAppend) {
                    database.blockDao().insertBlock(BlockEntity.fromDomainBlock(candidate, isVerified = true))
                    processBlockSideEffects(groupId, candidate)
                    Log.d(TAG, "Committed block #${candidate.index} (${candidate.blockType}) to group $groupId")
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf { it.blockHash == candidate.blockHash }
                    }
                    appendedDirect = true
                    progress = true
                    break
                }
            }
            if (appendedDirect) continue

            // Step 2: Check if a candidate forks at an existing index k <= latest.index with matching prevHash
            var resolvedFork = false
            for (candidate in candidates) {
                if (candidate.index <= 0L) continue
                val existingAtK = existingBlocks.find { it.index == candidate.index } ?: continue
                if (candidate.prevHash == existingAtK.prevHash && candidate.blockHash != existingAtK.blockHash) {
                    if (isPeerCanonicalWinner(candidate, existingAtK)) {
                        // Peer block at index k is earlier/canonical -> rollback local blocks >= k into pendingBlocks
                        val displaced = existingBlocks.filter { it.index >= candidate.index }
                        database.blockDao().deleteBlocksFromIndex(groupId, candidate.index)
                        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(candidate, isVerified = true))
                        processBlockSideEffects(groupId, candidate)
                        synchronized(pendingBlocks) {
                            val list = pendingBlocks.getOrPut(groupId) { mutableListOf() }
                            list.removeIf { it.blockHash == candidate.blockHash }
                            for (d in displaced) {
                                if (list.none { it.blockHash == d.blockHash }) {
                                    list.add(d)
                                }
                            }
                        }
                        Log.i(TAG, "Fork at #${candidate.index}: peer block won; queued ${displaced.size} displaced block(s) for rebase")
                        resolvedFork = true
                        progress = true
                        break
                    }
                }
            }
            if (resolvedFork) continue

            // Step 3: Remaining candidates in pendingBlocks are valid signed blocks (verified by groupKey + Ed25519)
            // that were built on a losing/stale branch. Rebase the earliest unmerged block onto the current tip
            // so zero member joins or expenses are ever lost!
            if (latest != null) {
                val nextToRebase = candidates
                    .filter { it.blockType != BlockType.GENESIS && logicalBlockId(it) !in existingLogicalIds }
                    .minWithOrNull(compareBy<Block> { it.timestamp }.thenBy { it.index }.thenBy { it.blockHash })

                if (nextToRebase != null) {
                    val privKeyHex = KeyManager.getPrivateKeyHex(context)
                    val rebasedTimestamp = maxOf(nextToRebase.timestamp, latest.timestamp + 1)
                    val rebasedBlock = Block.createAndSign(
                        index = latest.index + 1,
                        groupId = groupId,
                        prevHash = latest.blockHash,
                        timestamp = rebasedTimestamp,
                        creatorPubkey = localIdentity.publicKeyHex,
                        blockType = nextToRebase.blockType,
                        payloadJson = nextToRebase.payloadJson,
                        privateKeyHex = privKeyHex
                    )
                    database.blockDao().insertBlock(BlockEntity.fromDomainBlock(rebasedBlock, isVerified = true))
                    processBlockSideEffects(groupId, rebasedBlock)
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf {
                            it.blockHash == nextToRebase.blockHash || logicalBlockId(it) == logicalBlockId(nextToRebase)
                        }
                    }
                    // Broadcast our full chain so the peer converges to this exact rebased tip
                    broadcastGroupChain(groupId)
                    Log.i(TAG, "Rebased block (${rebasedBlock.blockType}) onto #${rebasedBlock.index} for group $groupId")
                    progress = true
                }
            }
        }
    }

    suspend fun processBlockSideEffects(groupId: String, block: Block) {
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
                val existingMembers = database.memberDao().getMembersForGroupSync(groupId)
                val expectedGhostForName = HashUtil.sha256Hex("ghost:$groupId:${payload.displayName.trim().lowercase()}")
                val isJoiningRealMember = payload.pubkey != expectedGhostForName && !payload.pubkey.startsWith("ghost:")

                // If a real member is joining and claiming a ghost member (or matches a ghost member's name), remove the ghost row
                if (isJoiningRealMember) {
                    if (!payload.claimedGhostPubkey.isNullOrBlank()) {
                        database.memberDao().deleteMember(groupId, payload.claimedGhostPubkey)
                    }
                    existingMembers.filter { m ->
                        val mGhostHash = HashUtil.sha256Hex("ghost:$groupId:${m.displayName.trim().lowercase()}")
                        (m.pubkey == mGhostHash || m.pubkey.startsWith("ghost:")) &&
                            (m.pubkey == payload.claimedGhostPubkey || m.displayName.equals(payload.displayName.trim(), ignoreCase = true))
                    }.forEach { ghost ->
                        database.memberDao().deleteMember(groupId, ghost.pubkey)
                    }
                }

                // Preserve existing UPI ID if the new payload doesn't specify one
                val previousUpi = existingMembers.firstOrNull {
                    it.pubkey == payload.pubkey || it.pubkey == payload.claimedGhostPubkey
                }?.upiId

                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = groupId,
                        pubkey = payload.pubkey,
                        displayName = payload.displayName,
                        upiId = payload.upiId ?: previousUpi
                    )
                )
                Log.d(TAG, "Processed Member Join: ${payload.displayName} joined group $groupId (claimed=${payload.claimedGhostPubkey})")

                // When a new peer joins, push our full block chain so they immediately receive all group history
                val localIdentity = KeyManager.getOrCreateIdentity(context)
                if (isJoiningRealMember && payload.pubkey != localIdentity.publicKeyHex) {
                    broadcastGroupChain(groupId)
                }
            }
            else -> {
                // Expenses and settlements are reflected dynamically from the block ledger queries
            }
        }
    }
}
