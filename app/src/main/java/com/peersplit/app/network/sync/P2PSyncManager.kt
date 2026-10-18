package com.peersplit.app.network.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.BlockchainEngine
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
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.network.nostr.NostrEvent
import com.peersplit.app.network.nostr.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
internal data class PersistedPendingSyncState(
    val pendingKeys: Map<String, List<NostrEvent>> = emptyMap(),
    val pendingCiphertext: List<PersistedCiphertextEntry> = emptyList(),
    val earlyGroupEvents: Map<String, List<NostrEvent>> = emptyMap()
)

@Serializable
internal data class PersistedCiphertextEntry(
    val groupId: String,
    val epoch: Int,
    val event: NostrEvent
)

data class SyncFailureEvent(
    val timestampMillis: Long = System.currentTimeMillis(),
    val groupId: String?,
    val operationId: String?,
    val errorCode: String,
    val errorMessage: String
)

data class OperationDeliveryStatus(
    val operationId: String,
    val isRelayAcked: Boolean,
    val relayAckCount: Int,
    val isPeerAccepted: Boolean,
    val status: String
)

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
        private const val MAX_PENDING_BLOCKS_PER_GROUP = 500
        private const val MAX_EARLY_GROUPS = 25
        private const val MAX_EARLY_EVENTS_PER_GROUP = 100
        private const val MAX_PENDING_KEYS_PER_TRANSITION = 20
        private const val MAX_PENDING_CIPHERTEXT_PER_EPOCH = 100
        private const val MAX_PENDING_TRANSITIONS = 50
        private const val MAX_PENDING_EPOCHS = 50
        private const val PENDING_STATE_FILE = "pending_sync_events.json"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingBlocks = Collections.synchronizedMap(mutableMapOf<String, MutableList<Block>>())
    private val earlyEvents = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<NostrEvent>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<NostrEvent>>?): Boolean {
                return size > MAX_EARLY_GROUPS
            }
        }
    )
    private val pendingKeysByTransition = ConcurrentHashMap<String, MutableList<NostrEvent>>()
    private val pendingCiphertextByEpoch = ConcurrentHashMap<Pair<String, Int>, MutableList<NostrEvent>>()
    private val incomingProcessingQueue = ConcurrentLinkedQueue<NostrEvent>()
    private val isDrainingProcessingQueue = AtomicBoolean(false)

    private val eventIdToBlockHash = ConcurrentHashMap<String, String>()
    private val eventIdToOpId = ConcurrentHashMap<String, String>()
    private val syncMutex = Mutex()
    private val groupReconcileLocks = ConcurrentHashMap<String, Mutex>()

    private val _syncFailuresFlow = kotlinx.coroutines.flow.MutableSharedFlow<SyncFailureEvent>(replay = 5, extraBufferCapacity = 64)
    val syncFailuresFlow: kotlinx.coroutines.flow.SharedFlow<SyncFailureEvent> = _syncFailuresFlow

    private fun savePendingSyncState() {
        try {
            val keysMap = pendingKeysByTransition.mapValues { synchronized(it.value) { it.value.toList() } }
            val cipherList = pendingCiphertextByEpoch.flatMap { (k, events) ->
                synchronized(events) { events.map { PersistedCiphertextEntry(k.first, k.second, it) } }
            }
            val earlyMap = synchronized(earlyEvents) {
                earlyEvents.mapValues { it.value.toList() }
            }
            val state = PersistedPendingSyncState(
                pendingKeys = keysMap,
                pendingCiphertext = cipherList,
                earlyGroupEvents = earlyMap
            )
            val jsonStr = json.encodeToString(PersistedPendingSyncState.serializer(), state)
            val file = java.io.File(context.filesDir, PENDING_STATE_FILE)
            val tmpFile = java.io.File(context.filesDir, "$PENDING_STATE_FILE.tmp")
            tmpFile.writeText(jsonStr)
            tmpFile.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save pending sync state: ${e.message}")
        }
    }

    private fun loadPendingSyncState() {
        try {
            val file = java.io.File(context.filesDir, PENDING_STATE_FILE)
            if (!file.exists()) return
            val jsonStr = file.readText()
            val state = json.decodeFromString(PersistedPendingSyncState.serializer(), jsonStr)
            state.pendingKeys.forEach { (transitionId, events) ->
                val list = pendingKeysByTransition.computeIfAbsent(transitionId.lowercase()) { mutableListOf() }
                synchronized(list) {
                    for (ev in events) {
                        if (list.none { it.id == ev.id }) list.add(ev)
                    }
                }
            }
            state.pendingCiphertext.forEach { entry ->
                val key = entry.groupId to entry.epoch
                val list = pendingCiphertextByEpoch.computeIfAbsent(key) { mutableListOf() }
                synchronized(list) {
                    if (list.none { it.id == entry.event.id }) list.add(entry.event)
                }
            }
            synchronized(earlyEvents) {
                state.earlyGroupEvents.forEach { (groupId, events) ->
                    val list = earlyEvents.getOrPut(groupId) { mutableListOf() }
                    for (ev in events) {
                        if (list.none { it.id == ev.id }) list.add(ev)
                    }
                }
            }
            Log.d(TAG, "Restored pending sync state: ${pendingKeysByTransition.size} transitions, ${pendingCiphertextByEpoch.size} epochs")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load pending sync state: ${e.message}")
        }
    }

    private fun bufferEarlyEvent(groupId: String, event: NostrEvent) {
        synchronized(earlyEvents) {
            val list = earlyEvents.getOrPut(groupId) { mutableListOf() }
            if (list.none { it.id == event.id }) {
                if (list.size >= MAX_EARLY_EVENTS_PER_GROUP) {
                    list.removeAt(0)
                }
                list.add(event)
            }
        }
        savePendingSyncState()
    }

    private fun bufferPendingKeyEvent(transitionOpId: String, event: NostrEvent) {
        val normId = transitionOpId.trim().lowercase()
        if (pendingKeysByTransition.size >= MAX_PENDING_TRANSITIONS && !pendingKeysByTransition.containsKey(normId)) {
            val oldest = pendingKeysByTransition.keys.firstOrNull()
            if (oldest != null) pendingKeysByTransition.remove(oldest)
        }
        val list = pendingKeysByTransition.computeIfAbsent(normId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.id == event.id }) {
                if (list.size >= MAX_PENDING_KEYS_PER_TRANSITION) list.removeAt(0)
                list.add(event)
            }
        }
        savePendingSyncState()
    }

    private fun bufferPendingCiphertext(groupId: String, epoch: Int, event: NostrEvent) {
        val key = groupId to epoch
        if (pendingCiphertextByEpoch.size >= MAX_PENDING_EPOCHS && !pendingCiphertextByEpoch.containsKey(key)) {
            val oldest = pendingCiphertextByEpoch.keys.firstOrNull()
            if (oldest != null) pendingCiphertextByEpoch.remove(oldest)
        }
        val list = pendingCiphertextByEpoch.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.id == event.id }) {
                if (list.size >= MAX_PENDING_CIPHERTEXT_PER_EPOCH) list.removeAt(0)
                list.add(event)
            }
        }
        savePendingSyncState()
    }

    private fun drainPendingCiphertext(groupId: String, epoch: Int): List<NostrEvent> {
        val list = pendingCiphertextByEpoch.remove(groupId to epoch) ?: return emptyList()
        savePendingSyncState()
        return synchronized(list) { list.toList() }
    }

    suspend fun onOperationsReconciled(groupId: String) {
        val readyEvents = mutableListOf<NostrEvent>()
        for (transitionOpId in pendingKeysByTransition.keys.toList()) {
            val opEntity = database.signedOperationDao().getOperationByIdSync(transitionOpId) ?: continue
            if (opEntity.groupId != groupId) continue

            when (opEntity.status) {
                com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_ACCEPTED -> {
                    val events = pendingKeysByTransition.remove(transitionOpId)
                    if (events != null) {
                        Log.d(TAG, "Transition op $transitionOpId ACCEPTED; unblocking ${events.size} key events")
                        synchronized(events) {
                            readyEvents.addAll(events)
                        }
                    }
                }
                com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_REJECTED -> {
                    val events = pendingKeysByTransition.remove(transitionOpId)
                    Log.w(TAG, "Transition op $transitionOpId REJECTED; dropping ${events?.size ?: 0} pending key events")
                }
            }
        }
        if (readyEvents.isNotEmpty()) {
            savePendingSyncState()
            enqueueEvents(readyEvents)
        }
    }

    fun enqueueEvent(event: NostrEvent) {
        incomingProcessingQueue.add(event)
        drainProcessingQueue()
    }

    fun enqueueEvents(events: Collection<NostrEvent>) {
        if (events.isEmpty()) return
        incomingProcessingQueue.addAll(events)
        drainProcessingQueue()
    }

    private fun drainProcessingQueue() {
        if (!isDrainingProcessingQueue.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val nextEvent = incomingProcessingQueue.poll() ?: break
                    try {
                        processSingleEvent(nextEvent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing event ${nextEvent.id}: ${e.message}", e)
                    }
                }
            } finally {
                isDrainingProcessingQueue.set(false)
                if (!incomingProcessingQueue.isEmpty() && isDrainingProcessingQueue.compareAndSet(false, true)) {
                    drainProcessingQueue()
                }
            }
        }
    }

    /**
     * Acquires the ledger synchronization lock so local block creation and incoming P2P sync
     * never race on the current chain tip.
     */
    suspend fun <T> withLedgerLock(action: suspend () -> T): T = syncMutex.withLock {
        action()
    }

    suspend fun getOperationDeliveryStatus(operationId: String): OperationDeliveryStatus {
        val outbox = database.outboxDao().getOutboxByOperationIdSync(operationId)
        val op = database.signedOperationDao().getOperationByIdSync(operationId)
        val relayAcks = database.outboxDao().countAcceptedRelaysForOperationSync(operationId)
        val isRelayAcked = relayAcks > 0 || outbox?.status == com.peersplit.app.data.local.entity.OutboxOperationEntity.STATUS_ACKED_ALL
        val isPeerAccepted = op?.status == com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_ACCEPTED
        return OperationDeliveryStatus(
            operationId = operationId,
            isRelayAcked = isRelayAcked,
            relayAckCount = relayAcks,
            isPeerAccepted = isPeerAccepted,
            status = op?.status ?: (outbox?.status ?: "UNKNOWN")
        )
    }

    fun initialize() {
        loadPendingSyncState()
        relayPool.connectAll()
        try {
            OutboxRetryWorker.schedule(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule OutboxRetryWorker: ${e.message}")
        }

        // Listen for incoming peer blocks and V2 operations
        scope.launch {
            relayPool.incomingEvents.collect { event ->
                enqueueEvent(event)
            }
        }

        // Check for already-satisfied pending triggers on start/restart
        scope.launch {
            try {
                val groups = database.groupDao().getGroupsSync()
                for (g in groups) {
                    onOperationsReconciled(g.id)
                }
                val readyEpochs = mutableListOf<Pair<String, Int>>()
                for (key in pendingCiphertextByEpoch.keys) {
                    val (gId, ep) = key
                    val keyEntity = database.groupKeyDao().getKeySync(gId, ep)
                    val effective = keyEntity?.groupKeyBase64 ?: if (ep == 0) database.groupDao().getGroupByIdSync(gId)?.groupKeyBase64 else null
                    if (effective != null) {
                        readyEpochs.add(key)
                    }
                }
                for (key in readyEpochs) {
                    val unblocked = drainPendingCiphertext(key.first, key.second)
                    enqueueEvents(unblocked)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking pending triggers on startup: ${e.message}")
            }
        }

        // Listen for relay OK acknowledgments to mark durable outbox blocks and operations as synced
        scope.launch {
            relayPool.ackedEventIds.collect { eventId ->
                // 1. Check V1 Block outbox
                val blockHash = eventIdToBlockHash.remove(eventId)
                if (!blockHash.isNullOrBlank()) {
                    try {
                        database.blockDao().markBlockSynced(blockHash)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to mark block synced for ack $eventId: ${e.message}")
                    }
                }

                // 2. Check V2 Operation outbox
                val opId = eventIdToOpId.remove(eventId) ?: database.outboxDao().getOutboxByEventIdSync(eventId)?.operationId
                if (!opId.isNullOrBlank()) {
                    try {
                        database.outboxDao().insertRelayDelivery(
                            com.peersplit.app.data.local.entity.RelayDeliveryEntity(
                                operationId = opId,
                                relayUrl = "nostr_pool",
                                nostrEventId = eventId,
                                accepted = true
                            )
                        )
                        database.outboxDao().updateOutboxRetryState(
                            operationId = opId,
                            attemptCount = 1,
                            nextAttemptAtMillis = 0L,
                            status = com.peersplit.app.data.local.entity.OutboxOperationEntity.STATUS_ACKED_ALL
                        )
                        Log.d(TAG, "Relay ACK verified for V2 operation $opId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process outbox ack for op $opId: ${e.message}")
                    }
                }
            }
        }

        // Subscribe to all currently stored groups and flush any unsynced outbox blocks/operations
        scope.launch {
            try {
                val groups = database.groupDao().getAllGroups().first()
                groups.forEach { group ->
                    Log.d(TAG, "Subscribing to existing group ${group.id}")
                    relayPool.subscribeGroup(group.id)
                }
                flushUnsyncedBlocks()
                flushOutboxOperations()
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to groups: ${e.message}")
            }
        }
    }

    /**
     * Flushes any locally created blocks that have not yet been acknowledged by a relay.
     */
    suspend fun flushUnsyncedBlocks() = withContext(Dispatchers.IO) {
        try {
            val unsynced = database.blockDao().getUnsyncedBlocksSync()
            if (unsynced.isEmpty()) return@withContext
            val groupsById = database.groupDao().getGroupsSync().associateBy { it.id }
            for (entity in unsynced) {
                val group = groupsById[entity.groupId] ?: continue
                broadcastBlock(entity.toDomainBlock(), group.groupKeyBase64)
            }
            Log.d(TAG, "Flushed ${unsynced.size} unsynced block(s) from durable outbox")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing unsynced blocks: ${e.message}")
        }
    }

    /**
     * Flushes any due V2 outbox operations with bounded exponential backoff and jitter.
     */
    suspend fun flushOutboxOperations() = withContext(Dispatchers.IO) {
        try {
            relayPool.connectAll()
            val now = System.currentTimeMillis()
            val due = database.outboxDao().getDueOutboxOperationsSync(now, limit = 32)
            if (due.isEmpty()) return@withContext

            for (outboxItem in due) {
                val event = try {
                    json.decodeFromString(NostrEvent.serializer(), outboxItem.signedEventJson)
                } catch (_: Exception) { null } ?: continue

                eventIdToOpId[outboxItem.nostrEventId] = outboxItem.operationId
                val published = relayPool.broadcast(event)

                val nextAttempt = if (published) {
                    now + 60_000L // check back in 1 min if no ACK
                } else {
                    // Exponential backoff: 2s, 4s, 8s, 16s, up to 60s + jitter
                    val backoffSecs = (2 shl (outboxItem.attemptCount.coerceAtMost(5))).toLong()
                    val jitter = (0..2000).random()
                    now + (backoffSecs * 1000L) + jitter
                }

                database.outboxDao().updateOutboxRetryState(
                    operationId = outboxItem.operationId,
                    attemptCount = outboxItem.attemptCount + 1,
                    nextAttemptAtMillis = nextAttempt,
                    status = if (published) com.peersplit.app.data.local.entity.OutboxOperationEntity.STATUS_ACKED_PARTIAL
                             else com.peersplit.app.data.local.entity.OutboxOperationEntity.STATUS_QUEUED
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in flushOutboxOperations: ${e.message}")
        }
    }

    /**
     * Constructs a durable outbox operation entry ready for immediate atomic persistence.
     */
    fun buildOutboxOperation(
        operation: com.peersplit.app.protocol.SignedOperation,
        groupKeyBase64: String
    ): com.peersplit.app.data.local.entity.OutboxOperationEntity {
        val epochKeyEntity = database.groupKeyDao().getKeySync(operation.groupId, operation.keyEpoch)
        val effectiveKeyBase64 = if (operation.keyEpoch > 0) {
            epochKeyEntity?.groupKeyBase64 ?: throw IllegalStateException("Missing encryption key for epoch ${operation.keyEpoch} in group ${operation.groupId}")
        } else {
            epochKeyEntity?.groupKeyBase64 ?: groupKeyBase64
        }
        val groupKey = EncryptionUtil.keyFromBase64(effectiveKeyBase64)
        val operationJson = com.peersplit.app.protocol.OperationEntityMapper.json.encodeToString(
            com.peersplit.app.protocol.SignedOperation.serializer(),
            operation
        )
        val wireEnvelope = com.peersplit.app.protocol.WireOperationEnvelope(
            version = operation.protocolVersion,
            groupId = operation.groupId,
            operationId = operation.operationId,
            keyEpoch = operation.keyEpoch,
            serializedOperationJson = operationJson
        )
        val wireJson = json.encodeToString(com.peersplit.app.protocol.WireOperationEnvelope.serializer(), wireEnvelope)
        val encryptedContent = EncryptionUtil.encryptString(wireJson, groupKey)

        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)

        val tags = listOf(
            listOf("t", "peersplit_${operation.groupId}"),
            listOf("t", "peersplit_v2"),
            listOf("g", operation.groupId),
            listOf("op", operation.operationId),
            listOf("v", operation.protocolVersion.toString()),
            listOf("epoch", operation.keyEpoch.toString())
        )

        val event = NostrEvent.createAndSign(
            pubkey = identity.publicKeyHex,
            privateKeyHex = privKeyHex,
            kind = NostrEvent.KIND_PEERSPLIT_BLOCK,
            tags = tags,
            content = encryptedContent
        )

        val eventJson = json.encodeToString(NostrEvent.serializer(), event)
        return com.peersplit.app.data.local.entity.OutboxOperationEntity(
            operationId = operation.operationId,
            groupId = operation.groupId,
            nostrEventId = event.id,
            signedEventJson = eventJson,
            keyEpoch = operation.keyEpoch,
            attemptCount = 0,
            nextAttemptAtMillis = System.currentTimeMillis() + 5000L,
            status = com.peersplit.app.data.local.entity.OutboxOperationEntity.STATUS_QUEUED
        )
    }

    /**
     * Broadcasts a V2 SignedOperation by persisting it in the durable outbox and transmitting
     * the encrypted wire envelope via Nostr.
     */
    suspend fun broadcastOperation(
        operation: com.peersplit.app.protocol.SignedOperation,
        groupKeyBase64: String
    ) {
        try {
            val outboxItem = buildOutboxOperation(operation, groupKeyBase64)
            database.outboxDao().insertOutboxOperation(outboxItem)

            val event = json.decodeFromString(NostrEvent.serializer(), outboxItem.signedEventJson)
            eventIdToOpId[event.id] = operation.operationId
            relayPool.broadcast(event)
            Log.d(TAG, "Broadcasted V2 operation ${operation.operationId.take(8)} for group ${operation.groupId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast V2 operation: ${e.message}", e)
            _syncFailuresFlow.tryEmit(
                SyncFailureEvent(
                    groupId = operation.groupId,
                    operationId = operation.operationId,
                    errorCode = "BROADCAST_FAILED",
                    errorMessage = e.message ?: "Unknown broadcast error"
                )
            )
        }
    }

    /**
     * Transmits a pre-committed outbox operation directly to relays.
     */
    suspend fun transmitOutboxOperation(outboxOp: com.peersplit.app.data.local.entity.OutboxOperationEntity) {
        try {
            val event = json.decodeFromString(NostrEvent.serializer(), outboxOp.signedEventJson)
            eventIdToOpId[event.id] = outboxOp.operationId
            relayPool.broadcast(event)
            Log.d(TAG, "Transmitted outbox V2 operation ${outboxOp.operationId.take(8)} for group ${outboxOp.groupId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transmit outbox V2 operation: ${e.message}", e)
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
            savePendingSyncState()
            enqueueEvents(buffered)
            drainPendingBlocks(groupId)
            reconcileV2Group(groupId)
        }
    }

    /**
     * Actively refreshes subscription and optionally broadcasts local chain for a group.
     */
    fun syncGroupNow(groupId: String, broadcastChain: Boolean = false) {
        scope.launch {
            relayPool.connectAll()
            relayPool.subscribeGroup(groupId)
            if (broadcastChain) {
                broadcastGroupChain(groupId)
            } else {
                flushUnsyncedBlocks()
                flushOutboxOperations()
            }
            drainPendingBlocks(groupId)
            reconcileV2Group(groupId)
        }
    }

    /**
     * Syncs all stored groups across all connected relays.
     * Re-establishes socket connections if dropped and subscribes to group feeds.
     */
    fun syncAllGroupsNow(broadcastChains: Boolean = false) {
        scope.launch {
            try {
                relayPool.connectAll()
                val groups = database.groupDao().getAllGroups().first()
                groups.forEach { group ->
                    relayPool.subscribeGroup(group.id)
                    if (broadcastChains) {
                        broadcastGroupChain(group.id)
                    }
                    drainPendingBlocks(group.id)
                    reconcileV2Group(group.id)
                }
                if (!broadcastChains) {
                    flushUnsyncedBlocks()
                    flushOutboxOperations()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncAllGroupsNow: ${e.message}")
            }
        }
    }

    /**
     * Reconciles all stored V2 operations for a group and materializes projections atomically.
     * Uses per-group synchronization to avoid race conditions between local writes and remote sync.
     */
    suspend fun reconcileV2Group(groupId: String, forceRebuild: Boolean = false): Boolean {
        val lock = groupReconcileLocks.computeIfAbsent(groupId) { Mutex() }
        return lock.withLock {
            try {
                val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return@withLock false
                val opEntities = database.signedOperationDao().getOperationsForGroupSync(groupId)
                if (opEntities.isEmpty()) return@withLock false

                val domainOps = opEntities.map { com.peersplit.app.protocol.OperationEntityMapper.toDomain(it) }
                val meta = database.groupProtocolDao().getGroupProtocolSync(groupId)
                val trustedGenesis = com.peersplit.app.reconciliation.TrustedGenesis(
                    groupId = groupId,
                    groupName = groupEntity.name,
                    currency = groupEntity.currency,
                    creatorPublicKey = groupEntity.creatorPubkey,
                    genesisOperationId = meta?.genesisOperationId
                )

                val result = com.peersplit.app.reconciliation.DefaultLedgerReconciliationEngine.reconcile(
                    trustedGenesis = trustedGenesis,
                    operations = domainOps
                )

                val applied = com.peersplit.app.reconciliation.ReconciliationProjector.persistReconciliationResult(
                    database = database,
                    result = result,
                    forceRebuild = forceRebuild
                )
                onOperationsReconciled(groupId)
                Log.d(TAG, "Reconciled V2 group $groupId: applied=$applied, ${result.acceptedOperations.size} accepted ops, digest=${result.stateDigest.take(8)}")
                applied
            } catch (e: Exception) {
                Log.e(TAG, "Error reconciling V2 group $groupId: ${e.message}", e)
                false
            }
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

                if (eventIdToBlockHash.size > 2048) {
                    eventIdToBlockHash.clear()
                }
                eventIdToBlockHash[event.id] = block.blockHash

                val published = relayPool.broadcast(event)
                if (published) {
                    database.blockDao().markBlockSynced(block.blockHash)
                }
                Log.d(TAG, "Broadcasted block #${block.index} (${block.blockType}) for group ${block.groupId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast block: ${e.message}", e)
            }
        }
    }

    suspend fun handleIncomingEvent(event: NostrEvent) {
        processSingleEvent(event)
    }

    private suspend fun processSingleEvent(event: NostrEvent) {
        try {
            // 1. Verify outer Nostr event envelope integrity & signature first
            if (!event.verify()) {
                Log.w(TAG, "Rejected incoming NostrEvent ${event.id.take(8)}: invalid envelope signature or id")
                return
            }

            // 2. Find groupId tag from either "g" or "t" ("peersplit_<groupId>")
            val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
                ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "t" && it[1].startsWith("peersplit_") }
                    ?.get(1)?.removePrefix("peersplit_")
                ?: return

            // 3. Look up group encryption key locally
            val groupEntity = database.groupDao().getGroupByIdSync(groupId)
            if (groupEntity == null) {
                bufferEarlyEvent(groupId, event)
                return
            }
            val isKeyEvent = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "peersplit_key" }
            if (isKeyEvent) {
                val envelope = com.peersplit.app.crypto.KeyDistributionEnvelope.fromJson(event.content)
                if (envelope == null) {
                    Log.w(TAG, "Rejected key event ${event.id.take(8)}: missing or invalid KeyDistributionEnvelope")
                    return
                }

                if (!envelope.verifySignature()) {
                    Log.w(TAG, "Rejected key event ${event.id.take(8)}: invalid Ed25519 signature on KeyDistributionEnvelope")
                    return
                }

                if (envelope.groupId != groupId) {
                    Log.w(TAG, "Rejected key event ${event.id.take(8)}: envelope groupId mismatch (${envelope.groupId} != $groupId)")
                    return
                }

                val outerEpoch = event.tags.firstOrNull { it.size >= 2 && it[0] == "epoch" }?.get(1)?.toIntOrNull()
                if (outerEpoch != null && outerEpoch != envelope.epoch) {
                    Log.w(TAG, "Rejected key event ${event.id.take(8)}: epoch tag mismatch ($outerEpoch != ${envelope.epoch})")
                    return
                }

                val identity = KeyManager.getOrCreateIdentity(context)
                val myPubkey = identity.publicKeyHex.lowercase()
                if (!envelope.recipientPublicKey.equals(myPubkey, ignoreCase = true)) {
                    Log.d(TAG, "Ignoring key event: recipient (${envelope.recipientPublicKey}) is not this device ($myPubkey)")
                    return
                }

                val outerRecipient = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)?.lowercase()
                if (outerRecipient != null && !outerRecipient.equals(envelope.recipientPublicKey, ignoreCase = true)) {
                    Log.w(TAG, "Rejected key event: outer recipient tag mismatch ($outerRecipient != ${envelope.recipientPublicKey})")
                    return
                }

                val epoch = envelope.epoch
                val transitionOpId = envelope.transitionOperationId
                val transitionOpEntity = database.signedOperationDao().getOperationByIdSync(transitionOpId)

                if (transitionOpEntity == null) {
                    bufferPendingKeyEvent(transitionOpId, event)
                    Log.d(TAG, "Buffered key event for group $groupId epoch $epoch pending arrival of transition op $transitionOpId")
                    return
                }

                if (transitionOpEntity.status == com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_REJECTED) {
                    Log.w(TAG, "Dropped key event for group $groupId epoch $epoch: transition op $transitionOpId is REJECTED")
                    return
                }

                if (transitionOpEntity.status != com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_ACCEPTED) {
                    bufferPendingKeyEvent(transitionOpId, event)
                    Log.d(TAG, "Buffered key event for group $groupId epoch $epoch pending acceptance of transition op $transitionOpId")
                    return
                }

                if (transitionOpEntity.groupId != groupId) {
                    Log.w(TAG, "Rejected key event: transition op $transitionOpId belongs to group ${transitionOpEntity.groupId}, not $groupId")
                    return
                }

                val transitionOp = com.peersplit.app.protocol.OperationEntityMapper.toDomain(transitionOpEntity)
                val removalPayload = transitionOp.payload as? com.peersplit.app.protocol.OperationPayload.MemberRemoved
                if (removalPayload == null || removalPayload.newKeyEpoch != epoch) {
                    Log.w(TAG, "Rejected key event: transition op $transitionOpId is not MemberRemoved for epoch $epoch")
                    return
                }
                if (!transitionOp.authorPublicKey.equals(envelope.authorPublicKey, ignoreCase = true)) {
                    Log.w(TAG, "Rejected key event: transition op author mismatch (${transitionOp.authorPublicKey} != ${envelope.authorPublicKey})")
                    return
                }

                // Verify author authority in the group (must be group creator or active admin)
                val isCreator = groupEntity.creatorPubkey.equals(envelope.authorPublicKey, ignoreCase = true)
                if (!isCreator) {
                    val existingOps = database.signedOperationDao().getOperationsForGroupSync(groupId)
                    val domainOps = existingOps.map { com.peersplit.app.protocol.OperationEntityMapper.toDomain(it) }
                    val protoMeta = database.groupProtocolDao().getGroupProtocolSync(groupId)
                    val currentRecon = com.peersplit.app.reconciliation.DefaultLedgerReconciliationEngine.reconcile(
                        com.peersplit.app.reconciliation.TrustedGenesis(
                            groupId = groupId,
                            groupName = groupEntity.name,
                            currency = groupEntity.currency,
                            creatorPublicKey = groupEntity.creatorPubkey,
                            genesisOperationId = protoMeta?.genesisOperationId
                        ),
                        domainOps
                    )
                    val authorRole = currentRecon.activeMembers[envelope.authorPublicKey.lowercase()]?.role
                    if (authorRole != com.peersplit.app.protocol.ProtocolMemberInfo.ROLE_ADMIN) {
                        Log.w(TAG, "Rejected key event: author ${envelope.authorPublicKey} is not an active admin or creator of group $groupId")
                        return
                    }
                }

                try {
                    val myPrivHex = KeyManager.getPrivateKeyHex(context)
                    val keyBase64 = com.peersplit.app.crypto.KeyExchangeUtil.decryptFromPeer(
                        ciphertext = envelope.keyCiphertext,
                        myPrivHex = myPrivHex,
                        theirPubHex = envelope.authorPublicKey
                    )
                    com.peersplit.app.crypto.KeyRotationManager.storeEpochKey(
                        database = database,
                        groupId = groupId,
                        epoch = epoch,
                        keyBase64 = keyBase64,
                        authorizedMembers = emptyList()
                    )
                    Log.i(TAG, "Successfully received and stored rotated key for group $groupId epoch $epoch")

                    val unblockedCiphertext = drainPendingCiphertext(groupId, epoch)
                    enqueueEvents(unblockedCiphertext)
                    reconcileV2Group(groupId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt and store epoch key for group $groupId epoch $epoch: ${e.message}", e)
                }
                return
            }

            val epochTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "epoch" }?.get(1)?.toIntOrNull() ?: 0
            val epochKeyEntity = database.groupKeyDao().getKeySync(groupId, epochTag)
            val effectiveKeyBase64 = epochKeyEntity?.groupKeyBase64 ?: if (epochTag == 0) groupEntity.groupKeyBase64 else null

            if (effectiveKeyBase64 == null) {
                Log.w(TAG, "Quarantined event ${event.id.take(8)}: Key for epoch $epochTag in group $groupId is not available on this device; buffering")
                bufferPendingCiphertext(groupId, epochTag, event)
                return
            }
            val groupKey = EncryptionUtil.keyFromBase64(effectiveKeyBase64)

            // 4. Decrypt payload
            val decryptedJson = EncryptionUtil.decryptString(event.content, groupKey)

            // Check if this is a V2 operation or legacy V1 block
            val isV2 = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "peersplit_v2" } ||
                       event.tags.any { it.size >= 2 && it[0] == "op" }

            if (isV2) {
                try {
                    val wireEnv = json.decodeFromString(com.peersplit.app.protocol.WireOperationEnvelope.serializer(), decryptedJson)
                    val op = com.peersplit.app.protocol.OperationEntityMapper.json.decodeFromString(
                        com.peersplit.app.protocol.SignedOperation.serializer(),
                        wireEnv.serializedOperationJson
                    )

                    if (op.groupId != groupId) {
                        Log.w(TAG, "Rejected incoming V2 op ${op.operationId}: groupId mismatch (${op.groupId} != $groupId)")
                        return
                    }

                    if (!com.peersplit.app.protocol.OperationVerifier.verify(op)) {
                        Log.w(TAG, "Rejected incoming V2 op ${op.operationId}: cryptographic verification or structural validation failed")
                        _syncFailuresFlow.tryEmit(
                            SyncFailureEvent(
                                groupId = groupId,
                                operationId = op.operationId,
                                errorCode = "VERIFICATION_FAILED",
                                errorMessage = "Cryptographic signature or canonical hash verification failed"
                            )
                        )
                        return
                    }

                    val entity = com.peersplit.app.protocol.OperationEntityMapper.toEntity(op)
                    val inserted = database.signedOperationDao().insertOperationIgnore(entity)
                    if (inserted != -1L) {
                        val deps = com.peersplit.app.protocol.OperationEntityMapper.toDependencyEntities(op)
                        if (deps.isNotEmpty()) {
                            database.signedOperationDao().insertDependenciesIgnore(deps)
                        }
                        Log.d(TAG, "Stored incoming V2 operation ${op.operationId.take(8)} for group $groupId")
                        reconcileV2Group(groupId)
                    } else {
                        Log.d(TAG, "V2 operation ${op.operationId.take(8)} already exists in local DB")
                    }
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Quarantined malformed V2 message for group $groupId: ${e.message}", e)
                    _syncFailuresFlow.tryEmit(
                        SyncFailureEvent(
                            groupId = groupId,
                            operationId = null,
                            errorCode = "MALFORMED_V2_MESSAGE",
                            errorMessage = e.message ?: "Malformed V2 message"
                        )
                    )
                    // Priority 3 & 5: NEVER fall back to V1 decoding for a message tagged as V2!
                    return
                }
            }

            // Priority 3: Legacy Protocol Isolation
            // If V2 is activated for this group, REJECT/QUARANTINE all incoming legacy V1 events
            val protoMeta = database.groupProtocolDao().getGroupProtocolSync(groupId)
            val isV2Active = protoMeta != null && protoMeta.protocolVersion >= com.peersplit.app.protocol.ProtocolVersion.V2_SIGNED_OPERATIONS
            if (isV2Active) {
                Log.w(TAG, "Quarantined incoming V1 event ${event.id.take(8)}: Group $groupId is active on V2 protocol")
                return
            }

            // 5. Decrypt block payload
            val block = json.decodeFromString(Block.serializer(), decryptedJson)

            // 6. Enforce cross-group replay protection, cryptographic integrity, and domain payload schema
            if (block.groupId != groupId) {
                Log.w(TAG, "Rejected incoming block #${block.index}: groupId mismatch (${block.groupId} != $groupId)")
                return
            }
            if (!block.verifyIntegrity()) {
                Log.w(TAG, "Rejected incoming block #${block.index}: invalid signature or hash")
                return
            }
            if (!BlockchainEngine.validateBlockPayload(block)) {
                Log.w(TAG, "Rejected incoming block #${block.index} (${block.blockType}): invalid domain payload")
                return
            }

            // 6. Buffer block (with bounded capacity) and drain chain
            synchronized(pendingBlocks) {
                val list = pendingBlocks.getOrPut(groupId) { mutableListOf() }
                if (list.none { it.blockHash == block.blockHash }) {
                    if (list.size >= MAX_PENDING_BLOCKS_PER_GROUP) {
                        list.removeAt(0)
                    }
                    list.add(block)
                }
            }

            drainPendingBlocks(groupId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming peer event: ${e.message}", e)
        }
    }

    /**
     * Verifies that the block's chain signer and origin author are authorized members of the group.
     */
    fun isAuthorizedBlockSigner(
        block: Block,
        groupEntity: GroupEntity,
        knownMemberPubkeys: Set<String>
    ): Boolean {
        if (block.blockType == BlockType.GENESIS || block.index == 0L) {
            return block.creatorPubkey == groupEntity.creatorPubkey &&
                (block.originPubkey.isNullOrBlank() || block.originPubkey == groupEntity.creatorPubkey)
        }

        val authorizedSet = knownMemberPubkeys + groupEntity.creatorPubkey

        if (block.blockType == BlockType.MEMBER_JOIN) {
            val joinPayload = try {
                json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
            } catch (_: Exception) {
                return false
            }
            val validForJoin = authorizedSet + joinPayload.pubkey
            val creatorOk = block.creatorPubkey in validForJoin
            val originOk = block.originPubkey.isNullOrBlank() || block.originPubkey in validForJoin
            return creatorOk && originOk
        }

        val creatorOk = block.creatorPubkey in authorizedSet
        val originOk = block.originPubkey.isNullOrBlank() || block.originPubkey in authorizedSet
        return creatorOk && originOk
    }

    /**
     * Collects all known member pubkeys from both the members table and the canonical block chain.
     */
    private suspend fun getKnownMemberPubkeys(groupId: String, existingBlocks: List<Block>): Set<String> {
        val pubkeys = mutableSetOf<String>()
        database.memberDao().getMembersForGroupSync(groupId).forEach { pubkeys.add(it.pubkey) }
        for (b in existingBlocks) {
            pubkeys.add(b.creatorPubkey)
            b.originPubkey?.takeIf { it.isNotBlank() }?.let { pubkeys.add(it) }
            when (b.blockType) {
                BlockType.GENESIS -> {
                    try {
                        val p = json.decodeFromString(GroupGenesisPayload.serializer(), b.payloadJson)
                        p.initialMembers.forEach { pubkeys.add(it.pubkey) }
                    } catch (_: Exception) {}
                }
                BlockType.MEMBER_JOIN -> {
                    try {
                        val p = json.decodeFromString(MemberJoinedPayload.serializer(), b.payloadJson)
                        pubkeys.add(p.pubkey)
                    } catch (_: Exception) {}
                }
                else -> {}
            }
        }
        return pubkeys
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

    /**
     * Checks if a group has transitioned to V2 signed-operation protocol.
     */
    suspend fun isV2GroupActive(groupId: String): Boolean {
        val meta = database.groupProtocolDao().getGroupProtocolSync(groupId)
        return meta != null && meta.protocolVersion >= com.peersplit.app.protocol.ProtocolVersion.V2_SIGNED_OPERATIONS
    }

    private suspend fun drainPendingBlocks(groupId: String) = syncMutex.withLock {
        // Priority 3: Legacy Protocol Isolation
        // Ensure V2 groups never execute V1 fork-rebasing logic.
        val protoMeta = database.groupProtocolDao().getGroupProtocolSync(groupId)
        if (protoMeta != null && protoMeta.protocolVersion >= com.peersplit.app.protocol.ProtocolVersion.V2_SIGNED_OPERATIONS) {
            Log.d(TAG, "Skipping V1 fork-rebasing: Group $groupId is operating on V2 protocol")
            synchronized(pendingBlocks) {
                pendingBlocks.remove(groupId)
            }
            return@withLock
        }

        val localIdentity = KeyManager.getOrCreateIdentity(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return@withLock

        var progress = true
        while (progress) {
            progress = false
            val existingEntities = database.blockDao().getBlocksForGroupSync(groupId)
            val existingBlocks = existingEntities.map { it.toDomainBlock() }
            val existingHashes = existingBlocks.map { it.blockHash }.toSet()
            val existingLogicalIds = existingBlocks.map { logicalBlockId(it) }.toSet()
            val knownMembers = getKnownMemberPubkeys(groupId, existingBlocks)

            // Remove any pending candidates whose exact hash or semantic logical ID is already in our chain,
            // or whose payload/integrity is invalid
            synchronized(pendingBlocks) {
                pendingBlocks[groupId]?.removeIf {
                    it.blockHash in existingHashes ||
                        logicalBlockId(it) in existingLogicalIds ||
                        !it.verifyIntegrity() ||
                        !BlockchainEngine.validateBlockPayload(it)
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
                    existingBlocks.isEmpty() && candidate.index == 0L && candidate.prevHash == Block.GENESIS_PREV_HASH -> true
                    latest != null && candidate.index == latest.index + 1 && candidate.prevHash == latest.blockHash -> true
                    else -> false
                }
                if (canAppend && isAuthorizedBlockSigner(candidate, groupEntity, knownMembers)) {
                    var shouldBroadcastChain = false
                    database.withTransaction {
                        database.blockDao().insertBlock(
                            BlockEntity.fromDomainBlock(candidate, isVerified = true, isSynced = true)
                        )
                        shouldBroadcastChain = applyBlockSideEffectsInternal(groupId, candidate)
                    }
                    if (shouldBroadcastChain) {
                        broadcastGroupChain(groupId)
                    }
                    Log.d(TAG, "Committed block #${candidate.index} (${candidate.blockType}) to group $groupId")
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf { it.blockHash == candidate.blockHash }
                    }
                    appendedDirect = true
                    progress = true
                    break
                } else if (canAppend) {
                    Log.w(TAG, "Dropped unauthorized block #${candidate.index} from ${candidate.creatorPubkey.take(8)}")
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf { it.blockHash == candidate.blockHash }
                    }
                }
            }
            if (appendedDirect) continue

            // Step 2: Check if a candidate forks at an existing index k <= latest.index with matching prevHash
            var resolvedFork = false
            for (candidate in candidates) {
                if (candidate.index <= 0L) continue
                val existingAtK = existingBlocks.find { it.index == candidate.index } ?: continue
                if (candidate.prevHash == existingAtK.prevHash && candidate.blockHash != existingAtK.blockHash) {
                    if (!isAuthorizedBlockSigner(candidate, groupEntity, knownMembers)) {
                        synchronized(pendingBlocks) {
                            pendingBlocks[groupId]?.removeIf { it.blockHash == candidate.blockHash }
                        }
                        continue
                    }
                    if (isPeerCanonicalWinner(candidate, existingAtK)) {
                        // Peer block at index k is earlier/canonical -> rollback local blocks >= k into pendingBlocks
                        val displaced = existingBlocks.filter { it.index >= candidate.index }
                        database.withTransaction {
                            database.blockDao().deleteBlocksFromIndex(groupId, candidate.index)
                            database.blockDao().insertBlock(
                                BlockEntity.fromDomainBlock(candidate, isVerified = true, isSynced = true)
                            )
                            rebuildGroupMembersFromChain(groupId, groupEntity)
                        }
                        synchronized(pendingBlocks) {
                            val list = pendingBlocks.getOrPut(groupId) { mutableListOf() }
                            list.removeIf { it.blockHash == candidate.blockHash }
                            for (d in displaced) {
                                if (list.none { it.blockHash == d.blockHash } && list.size < MAX_PENDING_BLOCKS_PER_GROUP) {
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
            // that were built on a losing/stale branch. Rebase the earliest unmerged block onto the current tip,
            // preserving the original author's pubkey, timestamp, and cryptographic signature!
            if (latest != null) {
                val nextToRebase = candidates
                    .filter {
                        it.blockType != BlockType.GENESIS &&
                            logicalBlockId(it) !in existingLogicalIds &&
                            isAuthorizedBlockSigner(it, groupEntity, knownMembers)
                    }
                    .minWithOrNull(compareBy<Block> { it.timestamp }.thenBy { it.index }.thenBy { it.blockHash })

                if (nextToRebase != null) {
                    val privKeyHex = KeyManager.getPrivateKeyHex(context)
                    val rebasedTimestamp = maxOf(nextToRebase.timestamp, latest.timestamp + 1)
                    val preservedOriginPubkey = nextToRebase.originPubkey?.takeIf { it.isNotBlank() }
                        ?: nextToRebase.creatorPubkey
                    val preservedOriginTimestamp = if (nextToRebase.originTimestamp > 0L) {
                        nextToRebase.originTimestamp
                    } else {
                        nextToRebase.timestamp
                    }
                    val preservedOriginSignature = nextToRebase.originSignature?.takeIf { it.isNotBlank() }

                    val rebasedBlock = Block.createAndSign(
                        index = latest.index + 1,
                        groupId = groupId,
                        prevHash = latest.blockHash,
                        timestamp = rebasedTimestamp,
                        creatorPubkey = localIdentity.publicKeyHex,
                        privateKeyHex = privKeyHex,
                        blockType = nextToRebase.blockType,
                        payloadJson = nextToRebase.payloadJson,
                        originPubkey = preservedOriginPubkey,
                        originTimestamp = preservedOriginTimestamp,
                        originSignature = preservedOriginSignature
                    )
                    database.withTransaction {
                        database.blockDao().insertBlock(
                            BlockEntity.fromDomainBlock(rebasedBlock, isVerified = true, isSynced = false)
                        )
                        applyBlockSideEffectsInternal(groupId, rebasedBlock)
                    }
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.removeIf {
                            it.blockHash == nextToRebase.blockHash || logicalBlockId(it) == logicalBlockId(nextToRebase)
                        }
                    }
                    // Broadcast our full chain so the peer converges to this exact rebased tip
                    broadcastGroupChain(groupId)
                    Log.i(TAG, "Rebased block (${rebasedBlock.blockType}) onto #${rebasedBlock.index} for group $groupId")
                    progress = true
                } else {
                    // Drop any remaining candidates that failed authorization or cannot be rebased
                    synchronized(pendingBlocks) {
                        pendingBlocks[groupId]?.clear()
                    }
                    break
                }
            } else {
                break
            }
        }
    }

    /**
     * Rebuilds the group's members table deterministically from the canonical block chain after a fork rollback,
     * preserving any local contact (phone/UPI) enrichments.
     */
    private suspend fun rebuildGroupMembersFromChain(groupId: String, groupEntity: GroupEntity) {
        val previousMembers = database.memberDao().getMembersForGroupSync(groupId).associateBy { it.pubkey }
        database.memberDao().deleteMembersForGroup(groupId)

        // Ensure group creator is always present as baseline
        val creatorPrev = previousMembers[groupEntity.creatorPubkey]
        database.memberDao().insertMember(
            MemberEntity(
                groupId = groupId,
                pubkey = groupEntity.creatorPubkey,
                displayName = creatorPrev?.displayName ?: "Creator",
                upiId = creatorPrev?.upiId,
                phone = creatorPrev?.phone
            )
        )

        val canonicalBlocks = database.blockDao().getBlocksForGroupSync(groupId).map { it.toDomainBlock() }
        for (block in canonicalBlocks) {
            applyBlockSideEffectsInternal(groupId, block)
        }
    }

    suspend fun processBlockSideEffects(groupId: String, block: Block) {
        val shouldBroadcast = applyBlockSideEffectsInternal(groupId, block)
        if (shouldBroadcast) {
            broadcastGroupChain(groupId)
        }
    }

    private suspend fun applyBlockSideEffectsInternal(groupId: String, block: Block): Boolean {
        when (block.blockType) {
            BlockType.GENESIS -> {
                val payload = json.decodeFromString(GroupGenesisPayload.serializer(), block.payloadJson)
                payload.initialMembers.forEach { member ->
                    database.memberDao().insertMember(
                        MemberEntity(
                            groupId = groupId,
                            pubkey = member.pubkey,
                            displayName = member.displayName,
                            upiId = member.upiId,
                            phone = KeyManager.normalizePhone(member.phone).takeIf { it.isNotBlank() }
                        )
                    )
                }
                Log.d(TAG, "Processed Genesis side effects for group $groupId (${payload.initialMembers.size} members)")
                return false
            }
            BlockType.MEMBER_JOIN -> {
                val payload = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                val existingMembers = database.memberDao().getMembersForGroupSync(groupId)
                val expectedGhostForName = HashUtil.sha256Hex("ghost:$groupId:${payload.displayName.trim().lowercase()}")
                val isJoiningRealMember = payload.pubkey != expectedGhostForName && !payload.pubkey.startsWith("ghost:")
                val incomingPhone = KeyManager.normalizePhone(payload.phone)

                // If a real member is joining and claiming a ghost member (or matches phone / name), remove the ghost row
                var matchedGhost: MemberEntity? = null
                if (isJoiningRealMember) {
                    if (!payload.claimedGhostPubkey.isNullOrBlank()) {
                        matchedGhost = existingMembers.firstOrNull { it.pubkey == payload.claimedGhostPubkey }
                        database.memberDao().deleteMember(groupId, payload.claimedGhostPubkey)
                    }
                    existingMembers.filter { m ->
                        val mGhostHash = HashUtil.sha256Hex("ghost:$groupId:${m.displayName.trim().lowercase()}")
                        val mPhone = KeyManager.normalizePhone(m.phone)
                        val isGhost = m.pubkey == mGhostHash || m.pubkey.startsWith("ghost:")
                        val phoneMatches = incomingPhone.length >= 10 && incomingPhone == mPhone
                        val nameMatches = m.displayName.equals(payload.displayName.trim(), ignoreCase = true)
                        isGhost && (m.pubkey == payload.claimedGhostPubkey || phoneMatches || nameMatches)
                    }.forEach { ghost ->
                        if (matchedGhost == null) matchedGhost = ghost
                        database.memberDao().deleteMember(groupId, ghost.pubkey)
                    }
                }

                // Preserve existing UPI ID and phone if the new payload doesn't specify one
                val prevMember = existingMembers.firstOrNull { it.pubkey == payload.pubkey } ?: matchedGhost
                val resolvedUpi = payload.upiId?.takeIf { it.isNotBlank() } ?: prevMember?.upiId
                val resolvedPhone = incomingPhone.takeIf { it.isNotBlank() } ?: prevMember?.phone

                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = groupId,
                        pubkey = payload.pubkey,
                        displayName = payload.displayName,
                        upiId = resolvedUpi,
                        phone = resolvedPhone
                    )
                )
                Log.d(TAG, "Processed Member Join: ${payload.displayName} joined group $groupId (claimed=${payload.claimedGhostPubkey})")

                // When a new peer joins, push our full block chain so they immediately receive all group history
                val localIdentity = KeyManager.getOrCreateIdentity(context)
                return isJoiningRealMember && payload.pubkey != localIdentity.publicKeyHex
            }
            else -> {
                // Expenses and settlements are reflected dynamically from the block ledger queries
                return false
            }
        }
    }
}
