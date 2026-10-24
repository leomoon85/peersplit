package com.peersplit.app.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.DebtSettlementEngine
import com.peersplit.app.blockchain.ExpensePayload
import com.peersplit.app.blockchain.ExpenseVoidPayload
import com.peersplit.app.blockchain.GroupGenesisPayload
import com.peersplit.app.blockchain.MemberInfo
import com.peersplit.app.blockchain.MemberJoinedPayload
import com.peersplit.app.blockchain.SettlementPayload
import com.peersplit.app.blockchain.SettlementSuggestion
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.blockchain.ValidationResult
import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.ExpenseProjectionEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import com.peersplit.app.data.local.entity.OutboxOperationEntity
import com.peersplit.app.network.nostr.NostrEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.peersplit.app.data.model.ExpenseItem
import com.peersplit.app.data.model.Group
import com.peersplit.app.data.model.Member
import com.peersplit.app.network.sync.P2PSyncManager
import com.peersplit.app.protocol.MembershipInvitationGrant
import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.protocol.OperationEntityMapper
import com.peersplit.app.protocol.OperationId
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.OperationVerifier
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation
import com.peersplit.app.reconciliation.V1ToV2MigrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class GroupDetail(
    val group: Group,
    val members: List<Member>,
    val expenses: List<ExpenseItem>,
    val settlements: List<SettlementSuggestion>,
    val blocks: List<Block>,
    val isLedgerIntact: Boolean
)

@Serializable
data class GroupInviteData(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val groupKeyBase64: String,
    val creatorPubkey: String,
    val creatorDisplayName: String = "Creator",
    val genesisBlockJson: String? = null,
    val blocksJson: List<String> = emptyList(),
    val invitationGrantJson: String? = null,
    val protocolVersion: Int = 1,
    val operationsJson: List<String> = emptyList()
)

data class GroupInvitePreview(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val creatorDisplayName: String,
    val unclaimedGhostMembers: List<MemberInfo>,
    val expenseCount: Int
)

class ExpenseRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val syncManager: P2PSyncManager
) {
    companion object {
        private const val TAG = "ExpenseRepository"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val expensePayloadCache = ConcurrentHashMap<String, ExpensePayload>()
    private val settlementPayloadCache = ConcurrentHashMap<String, SettlementPayload>()
    private val voidPayloadCache = ConcurrentHashMap<String, ExpenseVoidPayload>()
    private val memberJoinPayloadCache = ConcurrentHashMap<String, MemberJoinedPayload>()
    private val ghostHashCache = ConcurrentHashMap<String, String>()
    private val groupLocks = ConcurrentHashMap<String, Mutex>()
    private fun getGroupMutex(groupId: String): Mutex = groupLocks.computeIfAbsent(groupId) { Mutex() }

    private fun decodeExpense(block: Block): ExpensePayload? {
        expensePayloadCache[block.blockHash]?.let { return it }
        return try {
            val parsed = json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
            expensePayloadCache[block.blockHash] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSettlement(block: Block): SettlementPayload? {
        settlementPayloadCache[block.blockHash]?.let { return it }
        return try {
            val parsed = json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
            settlementPayloadCache[block.blockHash] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeVoid(block: Block): ExpenseVoidPayload? {
        voidPayloadCache[block.blockHash]?.let { return it }
        return try {
            val parsed = json.decodeFromString(ExpenseVoidPayload.serializer(), block.payloadJson)
            voidPayloadCache[block.blockHash] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeMemberJoin(block: Block): MemberJoinedPayload? {
        memberJoinPayloadCache[block.blockHash]?.let { return it }
        return try {
            val parsed = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
            memberJoinPayloadCache[block.blockHash] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun isGhostPubkey(groupId: String, pubkey: String, displayName: String): Boolean {
        if (pubkey.startsWith("ghost:")) return true
        val cacheKey = "ghost:$groupId:${displayName.trim().lowercase()}"
        val expectedHash = ghostHashCache.getOrPut(cacheKey) {
            HashUtil.sha256Hex(cacheKey)
        }
        return pubkey == expectedHash
    }

    /**
     * Builds a comprehensive map of [ghostPubkey -> realMemberPubkey] from:
     * 1. Explicit `claimedGhostPubkey` recorded in MEMBER_JOIN blocks on the chain.
     * 2. Matching complete normalized phone number between an offline ghost member and a real member.
     * 3. Matching displayName between an offline ghost member and a real member.
     */
    private fun buildAliasMap(
        groupId: String,
        memberEntities: List<MemberEntity>,
        blocks: List<Block>,
        currentIdentityPubkey: String,
        currentIdentityName: String,
        currentIdentityPhone: String? = null
    ): Map<String, String> {
        val aliasMap = mutableMapOf<String, String>()

        // 1. Explicit claims recorded in MEMBER_JOIN blocks
        for (block in blocks) {
            if (block.blockType == BlockType.MEMBER_JOIN) {
                val payload = decodeMemberJoin(block)
                if (payload != null && !payload.claimedGhostPubkey.isNullOrBlank() && payload.claimedGhostPubkey != payload.pubkey) {
                    aliasMap[payload.claimedGhostPubkey] = payload.pubkey
                }
            }
        }

        // 2. Real members claiming ghost members by complete phone number FIRST, then by displayName
        val realMembers = memberEntities.filter { !isGhostPubkey(groupId, it.pubkey, it.displayName) }
        val ghostMembers = memberEntities.filter { isGhostPubkey(groupId, it.pubkey, it.displayName) }
        val normMyPhone = KeyManager.normalizePhone(currentIdentityPhone)

        for (ghost in ghostMembers) {
            if (ghost.pubkey in aliasMap) continue
            val normGhostPhone = KeyManager.normalizePhone(ghost.phone)
            if (com.peersplit.app.util.PhoneNumbers.canMatch(normGhostPhone)) {
                val matchingByPhone = realMembers.firstOrNull {
                    KeyManager.normalizePhone(it.phone) == normGhostPhone
                }
                if (matchingByPhone != null) {
                    aliasMap[ghost.pubkey] = matchingByPhone.pubkey
                    continue
                } else if (normMyPhone == normGhostPhone) {
                    aliasMap[ghost.pubkey] = currentIdentityPubkey
                    continue
                }
            }

            val matchingReal = realMembers.firstOrNull {
                it.displayName.trim().equals(ghost.displayName.trim(), ignoreCase = true)
            }
            if (matchingReal != null) {
                aliasMap[ghost.pubkey] = matchingReal.pubkey
            } else if (ghost.displayName.trim().equals(currentIdentityName.trim(), ignoreCase = true)) {
                aliasMap[ghost.pubkey] = currentIdentityPubkey
            }
        }

        return aliasMap
    }

    private val groupDetailCache = ConcurrentHashMap<String, GroupDetail>()

    fun getCachedGroupDetail(groupId: String): GroupDetail? = groupDetailCache[groupId]

    val migrationManager = V1ToV2MigrationManager(context, database, syncManager)

    suspend fun migrateGroupToV2(groupId: String): V1ToV2MigrationManager.MigrationResult =
        migrationManager.migrateGroupToV2(groupId)

    suspend fun getDagHeads(groupId: String): List<String> {
        val meta = database.groupProtocolDao().getGroupProtocolSync(groupId)
        if (meta != null && meta.headOperationIdsJson.isNotBlank() && meta.headOperationIdsJson != "[]") {
            try {
                val heads = json.decodeFromString<List<String>>(meta.headOperationIdsJson)
                if (heads.isNotEmpty()) {
                    return heads.take(16).sorted()
                }
            } catch (_: Exception) {}
        }
        val ops = database.signedOperationDao().getAcceptedOperationsForGroupSync(groupId)
        if (ops.isEmpty()) return emptyList()
        val allParents = ops.flatMap {
            try {
                json.decodeFromString<List<String>>(it.parentsJson)
            } catch (_: Exception) { emptyList() }
        }.toSet()
        val heads = ops.map { it.operationId }.filter { it !in allParents }
        return if (heads.isEmpty()) {
            listOf(ops.maxByOrNull { it.logicalClock }?.operationId ?: ops.last().operationId)
        } else {
            heads.take(16).sorted()
        }
    }

    suspend fun getNextClock(groupId: String, parents: List<String>): Long {
        if (parents.isEmpty()) return 1L
        val ops = database.signedOperationDao().getAcceptedOperationsForGroupSync(groupId)
        val opMap = ops.associateBy { it.operationId }
        val maxParentClock = parents.mapNotNull { opMap[it]?.logicalClock }.maxOrNull() ?: 0L
        return maxParentClock + 1L
    }

    suspend fun getActiveEpochAndKey(groupId: String, fallbackKeyBase64: String): Pair<Int, String> {
        val currentEpoch = database.groupProtocolDao().getGroupProtocolSync(groupId)?.currentKeyEpoch ?: 0
        val activeKey = if (currentEpoch > 0) {
            com.peersplit.app.crypto.KeyRotationManager.getKeyForEpoch(database, groupId, currentEpoch)
                ?: fallbackKeyBase64
        } else {
            fallbackKeyBase64
        }
        return Pair(currentEpoch, activeKey)
    }

    suspend fun commitOperationDurableAtomic(
        op: SignedOperation,
        keyBase64: String,
        extraWork: (suspend () -> Unit)? = null
    ): OutboxOperationEntity {
        val outboxOp = syncManager.buildOutboxOperation(op, keyBase64)
        database.withTransaction {
            database.signedOperationDao().insertOperationIgnore(
                OperationEntityMapper.toEntity(op, SignedOperationEntity.STATUS_ACCEPTED)
            )
            val deps = OperationEntityMapper.toDependencyEntities(op)
            if (deps.isNotEmpty()) {
                database.signedOperationDao().insertDependenciesIgnore(deps)
            }
            database.outboxDao().insertOutboxOperation(outboxOp)
            extraWork?.invoke()
        }
        return outboxOp
    }

    /**
     * Observes all groups with dynamic user balance computation using bulk SQLite queries (no N+1 loop)
     * and pre-populates [groupDetailCache] so opening any group is instantaneous (0ms spinner).
     */
    fun getGroups(): Flow<List<Group>> {
        return combine(
            database.groupDao().getAllGroups(),
            database.memberDao().getAllMembersFlow(),
            database.blockDao().getAllBlocksFlow(),
            database.projectionDao().getAllBalanceProjectionsFlow(),
            database.projectionDao().getAllExpenseProjectionsFlow(),
            database.outboxDao().getAllOutstandingOutboxFlow()
        ) { array: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val groupEntities = array[0] as List<GroupEntity>
            @Suppress("UNCHECKED_CAST")
            val allMembers = array[1] as List<MemberEntity>
            @Suppress("UNCHECKED_CAST")
            val allBlocks = array[2] as List<BlockEntity>
            @Suppress("UNCHECKED_CAST")
            val allBalanceProjections = array[3] as List<BalanceProjectionEntity>
            @Suppress("UNCHECKED_CAST")
            val allExpenseProjections = array[4] as List<ExpenseProjectionEntity>
            @Suppress("UNCHECKED_CAST")
            val allPendingOutbox = array[5] as List<OutboxOperationEntity>

            val identity = KeyManager.getOrCreateIdentity(context)
            val membersByGroup = allMembers.groupBy { it.groupId }
            val blocksByGroup = allBlocks.groupBy { it.groupId }
            val balancesByGroup = allBalanceProjections.groupBy { it.groupId }
            val expensesByGroup = allExpenseProjections.groupBy { it.groupId }
            val outboxByGroup = allPendingOutbox.groupBy { it.groupId }

            groupEntities.map { entity ->
                val rawMembers = membersByGroup[entity.id].orEmpty()
                val rawBlocks = blocksByGroup[entity.id].orEmpty()
                val rawBalances = balancesByGroup[entity.id].orEmpty()
                val rawExpenses = expensesByGroup[entity.id].orEmpty()
                val outstanding = outboxByGroup[entity.id].orEmpty()
                val failedCount = outstanding.count { it.status == OutboxOperationEntity.STATUS_FAILED_PERMANENT }
                val pendingCount = outstanding.size - failedCount
                val detail = computeGroupDetail(
                    groupEntity = entity,
                    memberEntities = rawMembers,
                    blockEntities = rawBlocks,
                    identity = identity,
                    balProjections = rawBalances,
                    expProjections = rawExpenses
                )
                detail.group.copy(pendingSyncCount = pendingCount, failedSyncCount = failedCount)
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Observes a specific group's detail view, member list, and settlements.
     */
    fun getGroupDetail(groupId: String): Flow<GroupDetail?> {
        return combine(
            database.groupDao().getGroupById(groupId),
            database.memberDao().getMembersForGroup(groupId),
            database.blockDao().getBlocksForGroup(groupId),
            database.projectionDao().getExpenseProjectionsForGroupFlow(groupId),
            database.projectionDao().getBalanceProjectionsForGroupFlow(groupId),
            database.signedOperationDao().getOperationsForGroupFlow(groupId)
        ) { array: Array<Any?> ->
            val groupEntity = array[0] as GroupEntity?
            if (groupEntity == null) {
                groupDetailCache.remove(groupId)
                return@combine null
            }
            @Suppress("UNCHECKED_CAST")
            val memberEntities = array[1] as List<MemberEntity>
            @Suppress("UNCHECKED_CAST")
            val blockEntities = array[2] as List<BlockEntity>
            @Suppress("UNCHECKED_CAST")
            val expProjections = array[3] as List<ExpenseProjectionEntity>
            @Suppress("UNCHECKED_CAST")
            val balProjections = array[4] as List<BalanceProjectionEntity>
            @Suppress("UNCHECKED_CAST")
            val opEntities = array[5] as List<SignedOperationEntity>

            val identity = KeyManager.getOrCreateIdentity(context)
            val detail = computeGroupDetail(
                groupEntity = groupEntity,
                memberEntities = memberEntities,
                blockEntities = blockEntities,
                identity = identity,
                expProjections = expProjections,
                balProjections = balProjections,
                operationEntities = opEntities
            )
            groupDetailCache[groupId] = detail
            detail
        }.flowOn(Dispatchers.Default)
    }

    private fun computeGroupDetail(
        groupEntity: GroupEntity,
        memberEntities: List<MemberEntity>,
        blockEntities: List<BlockEntity>,
        identity: KeyManager.UserIdentity,
        expProjections: List<ExpenseProjectionEntity> = emptyList(),
        balProjections: List<BalanceProjectionEntity> = emptyList(),
        operationEntities: List<SignedOperationEntity> = emptyList()
    ): GroupDetail {
        val groupId = groupEntity.id
        val domainBlocks = blockEntities.map { it.toDomainBlock() }
        val isV2 = expProjections.isNotEmpty() || balProjections.isNotEmpty()

        val aliasMap: Map<String, String> = if (isV2) {
            // Strictly ledger-authorized ghost claims from accepted operations.
            // Never reassign balances or identities based on phone or name heuristics.
            val v2Aliases = mutableMapOf<String, String>()
            for (opEntity in operationEntities) {
                if (opEntity.status == SignedOperationEntity.STATUS_ACCEPTED) {
                    try {
                        val payload = OperationEntityMapper.json.decodeFromString(OperationPayload.serializer(), opEntity.payloadJson)
                        when (payload) {
                            is OperationPayload.MemberAdded -> {
                                val claimed = payload.claimedGhostPubkey
                                if (!claimed.isNullOrBlank() && claimed != payload.memberPublicKey) {
                                    v2Aliases[claimed] = payload.memberPublicKey
                                }
                            }
                            is OperationPayload.GenesisCreated -> {
                                for (m in payload.initialMembers) {
                                    if (!m.claimedGhostPubkey.isNullOrBlank() && m.claimedGhostPubkey != m.publicKey) {
                                        v2Aliases[m.claimedGhostPubkey] = m.publicKey
                                    }
                                }
                            }
                            is OperationPayload.LegacyV1Checkpoint -> {
                                payload.ghostAliases.forEach { (ghost, real) ->
                                    if (ghost.isNotBlank() && real.isNotBlank() && ghost != real) {
                                        v2Aliases[ghost] = real
                                    }
                                }
                                for (m in payload.members) {
                                    if (!m.claimedGhostPubkey.isNullOrBlank() && m.claimedGhostPubkey != m.publicKey) {
                                        v2Aliases[m.claimedGhostPubkey] = m.publicKey
                                    }
                                }
                            }
                            else -> {}
                        }
                    } catch (_: Exception) {}
                }
            }
            v2Aliases
        } else {
            buildAliasMap(
                groupId = groupId,
                memberEntities = memberEntities,
                blocks = domainBlocks,
                currentIdentityPubkey = identity.publicKeyHex,
                currentIdentityName = identity.displayName,
                currentIdentityPhone = identity.phone
            )
        }
        val resolvePubkey = { pk: String -> aliasMap[pk] ?: pk }

        // Build deduplicated members list (merging any claimed ghost member into the real member)
        val membersByResolvedPubkey = linkedMapOf<String, MemberEntity>()
        for (m in memberEntities) {
            val resolved = resolvePubkey(m.pubkey)
            val existing = membersByResolvedPubkey[resolved]
            if (existing == null) {
                membersByResolvedPubkey[resolved] = m.copy(pubkey = resolved)
            } else {
                val isCurrentReal = m.pubkey == resolved
                val chosenName = if (isCurrentReal) m.displayName else existing.displayName
                val chosenUpi = m.upiId?.takeIf { it.isNotBlank() } ?: existing.upiId
                val chosenPhone = m.phone?.takeIf { it.isNotBlank() } ?: existing.phone
                membersByResolvedPubkey[resolved] = existing.copy(
                    pubkey = resolved,
                    displayName = chosenName,
                    upiId = chosenUpi,
                    phone = chosenPhone
                )
            }
        }

        val memberMap = mutableMapOf<String, String>()
        for ((resolvedPk, mEntity) in membersByResolvedPubkey) {
            memberMap[resolvedPk] = mEntity.displayName
        }
        for ((ghostPk, realPk) in aliasMap) {
            memberMap[ghostPk] = memberMap[realPk] ?: "Member"
        }

        val netBalances: Map<String, Double>
        val suggestions: List<SettlementSuggestion>
        val isStructuralValid: Boolean
        val expenseItems: List<ExpenseItem>

        if (isV2) {
            val opSignatures = operationEntities.associate { it.operationId to it.signature }
            val minorSumsByResolved = mutableMapOf<String, Long>()
            val currencyByResolved = mutableMapOf<String, String>()
            for (bal in balProjections) {
                val resolved = resolvePubkey(bal.memberPublicKey)
                minorSumsByResolved[resolved] = (minorSumsByResolved[resolved] ?: 0L) + bal.balanceMinor
                currencyByResolved[resolved] = bal.currency
            }
            netBalances = minorSumsByResolved.mapValues { (resolved, totalMinor) ->
                MoneyMath.toMajorUnits(totalMinor, currencyByResolved[resolved])
            }
            suggestions = DebtSettlementEngine.simplifyDebts(netBalances)
            isStructuralValid = operationEntities.isNotEmpty() && operationEntities.all { it.status == SignedOperationEntity.STATUS_ACCEPTED }

            expenseItems = expProjections.mapNotNull { proj ->
                try {
                    val splitsMap = try {
                        json.decodeFromString<Map<String, Long>>(proj.splitsJson)
                    } catch (_: Exception) { emptyMap() }
                    val payersMap = try {
                        json.decodeFromString<Map<String, Long>>(proj.payersJson)
                    } catch (_: Exception) { emptyMap() }

                    val resolvedSplits = splitsMap.map { (pk, minor) ->
                        SplitItem(resolvePubkey(pk), MoneyMath.toMajorUnits(minor, proj.currency))
                    }
                    val resolvedPayers = payersMap.map { (pk, minor) ->
                        SplitItem(resolvePubkey(pk), MoneyMath.toMajorUnits(minor, proj.currency))
                    }
                    val totalAmt = MoneyMath.toMajorUnits(proj.amountMinor, proj.currency)
                    val resolvedPrimaryPayer = resolvePubkey(proj.paidBy)
                    val resolvedPayerName = if (resolvedPayers.size > 1) {
                        resolvedPayers.joinToString(" + ") { p -> memberMap[p.userPubkey] ?: "Member" }
                    } else {
                        memberMap[resolvedPrimaryPayer] ?: "Member"
                    }

                    val userSplit = resolvedSplits.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                    val userPaid = if (resolvedPayers.size > 1) {
                        resolvedPayers.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                    } else if (resolvedPrimaryPayer == identity.publicKeyHex) {
                        totalAmt
                    } else {
                        0.0
                    }
                    val share = if (proj.isVoided) 0.0 else (userPaid - userSplit)

                    val actualSig = opSignatures[proj.currentRevisionOperationId]
                        ?: opSignatures[proj.originOperationId]
                        ?: proj.originOperationId

                    ExpenseItem(
                        id = proj.expenseId,
                        description = proj.description,
                        totalAmount = totalAmt,
                        currency = proj.currency,
                        payerPubkey = resolvedPrimaryPayer,
                        payerName = resolvedPayerName,
                        timestamp = proj.expenseDateMillis,
                        userShare = share,
                        blockIndex = proj.logicalClock,
                        category = proj.category,
                        note = proj.note,
                        splitType = proj.splitType,
                        splits = resolvedSplits,
                        payers = resolvedPayers,
                        blockHash = proj.currentRevisionOperationId,
                        signature = actualSig,
                        isVoided = proj.isVoided,
                        editedFromExpenseId = null
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedWith(compareByDescending<ExpenseItem> { it.timestamp }.thenByDescending { it.blockIndex })
        } else {
            val expensesPayloads = extractExpenses(domainBlocks)
            val settlementPayloads = extractSettlements(domainBlocks)
            val voidedIds = extractVoidedExpenseIds(domainBlocks)

            netBalances = DebtSettlementEngine.calculateNetBalances(
                expensesPayloads,
                settlementPayloads,
                voidedIds,
                aliasMap
            )
            suggestions = DebtSettlementEngine.simplifyDebts(netBalances)
            isStructuralValid = BlockchainEngine.verifyStructuralChain(domainBlocks)

            val replacedExpenseIds = domainBlocks
                .filter { it.blockType == BlockType.EXPENSE }
                .mapNotNull { decodeExpense(it)?.editedFromExpenseId }
                .toSet()

            expenseItems = domainBlocks.filter { it.blockType == BlockType.EXPENSE || it.blockType == BlockType.SETTLEMENT }.mapNotNull { block ->
                try {
                    if (block.blockType == BlockType.EXPENSE) {
                        val exp = decodeExpense(block) ?: return@mapNotNull null
                        if (exp.expenseId in replacedExpenseIds) return@mapNotNull null
                        val isVoided = exp.expenseId in voidedIds
                        val resolvedSplits = exp.splits.map { it.copy(userPubkey = resolvePubkey(it.userPubkey)) }
                        val resolvedPayers = exp.payers.map { it.copy(userPubkey = resolvePubkey(it.userPubkey)) }
                        val resolvedPrimaryPayer = resolvePubkey(exp.payerPubkey)

                        val userSplit = resolvedSplits.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                        val userPaid = if (resolvedPayers.size > 1) {
                            resolvedPayers.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                        } else if (resolvedPrimaryPayer == identity.publicKeyHex) {
                            exp.totalAmount
                        } else {
                            0.0
                        }
                        val share = if (isVoided) 0.0 else (userPaid - userSplit)

                        val resolvedPayerName = if (resolvedPayers.size > 1) {
                            resolvedPayers.joinToString(" + ") { p -> memberMap[p.userPubkey] ?: "Member" }
                        } else {
                            memberMap[resolvedPrimaryPayer] ?: "Member"
                        }

                        ExpenseItem(
                            id = exp.expenseId,
                            description = exp.description,
                            totalAmount = exp.totalAmount,
                            currency = exp.currency,
                            payerPubkey = resolvedPrimaryPayer,
                            payerName = resolvedPayerName,
                            timestamp = exp.expenseDateMillis ?: block.timestamp,
                            userShare = share,
                            blockIndex = block.index,
                            category = exp.category,
                            note = exp.note,
                            splitType = exp.splitType,
                            splits = resolvedSplits,
                            payers = resolvedPayers,
                            blockHash = block.blockHash,
                            signature = block.signature,
                            isVoided = isVoided,
                            editedFromExpenseId = exp.editedFromExpenseId
                        )
                    } else { // BlockType.SETTLEMENT
                        val stl = decodeSettlement(block) ?: return@mapNotNull null
                        val payerPub = resolvePubkey(stl.payerPubkey)
                        val payeePub = resolvePubkey(stl.payeePubkey)
                        val payerName = memberMap[payerPub] ?: "Member"
                        val payeeName = memberMap[payeePub] ?: "Member"
                        val userShare = if (payerPub == identity.publicKeyHex) {
                            stl.amount // user paid
                        } else if (payeePub == identity.publicKeyHex) {
                            -stl.amount // user received
                        } else {
                            0.0
                        }

                        ExpenseItem(
                            id = stl.settlementId,
                            description = "$payerName paid $payeeName",
                            totalAmount = stl.amount,
                            currency = stl.currency,
                            payerPubkey = payerPub,
                            payerName = payerName,
                            timestamp = block.timestamp,
                            userShare = userShare,
                            blockIndex = block.index,
                            category = "SETTLEMENT",
                            note = stl.note,
                            splitType = "SETTLEMENT",
                            splits = listOf(SplitItem(payeePub, stl.amount)),
                            payers = listOf(SplitItem(payerPub, stl.amount)),
                            blockHash = block.blockHash,
                            signature = block.signature,
                            isVoided = false
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }.sortedWith(compareByDescending<ExpenseItem> { it.timestamp }.thenByDescending { it.blockIndex })
        }

        val members = membersByResolvedPubkey.values.map { memberEntity ->
            Member(
                pubkey = memberEntity.pubkey,
                displayName = memberEntity.displayName,
                isCurrentUser = memberEntity.pubkey == identity.publicKeyHex,
                netBalance = netBalances[memberEntity.pubkey] ?: 0.0,
                upiId = memberEntity.upiId,
                phone = memberEntity.phone
            )
        }

        val group = Group(
            id = groupEntity.id,
            name = groupEntity.name,
            currency = groupEntity.currency,
            creatorPubkey = groupEntity.creatorPubkey,
            groupKeyBase64 = groupEntity.groupKeyBase64,
            memberCount = members.size,
            userNetBalance = netBalances[identity.publicKeyHex] ?: 0.0,
            blockCount = if (isV2) expenseItems.size.toLong() else domainBlocks.size.toLong(),
            isLedgerValid = isStructuralValid,
            isSecret = groupEntity.isSecret,
            secretPinHash = groupEntity.secretPinHash,
            isPersonalSolo = groupEntity.isPersonalSolo,
            monthlyBudget = groupEntity.monthlyBudget,
            isArchived = groupEntity.isArchived,
            createdAt = groupEntity.createdAt
        )

        return GroupDetail(
            group = group,
            members = members,
            expenses = expenseItems,
            settlements = suggestions,
            blocks = domainBlocks,
            isLedgerIntact = isStructuralValid
        )
    }

    /**
     * Creates a new Group with its Genesis block and broadcasts to Nostr.
     */
    suspend fun createGroup(
        name: String,
        currency: String = "INR",
        isSecret: Boolean = false,
        secretPin: String? = null,
        isPersonalSolo: Boolean = false,
        monthlyBudget: Double = 0.0,
        initialMemberNames: List<String> = emptyList()
    ): String {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)

        // Generate 256-bit AES symmetric group key
        val groupKeyBytes = EncryptionUtil.generateGroupKey()
        val groupKeyBase64 = EncryptionUtil.keyToBase64(groupKeyBytes)

        val (groupId, genesisBlock) = BlockchainEngine.createGenesisBlock(
            groupName = name.trim(),
            currency = currency.trim(),
            creatorPubkey = identity.publicKeyHex,
            creatorDisplayName = identity.displayName,
            privateKeyHex = privKeyHex,
            creatorUpiId = identity.upiId,
            creatorPhone = identity.phone
        )

        val pinHash = if (!secretPin.isNullOrBlank()) KeyManager.hashSecretPin(secretPin) else null

        // Store Group and Genesis Block locally under ledger lock
        val (genesisOp, genesisOutboxOp) = syncManager.withLedgerLock {
            // Mint V2 GenesisCreated SignedOperation
            val genesisPayload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = name.trim(),
                currency = currency.trim(),
                creatorPublicKey = identity.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(
                        publicKey = identity.publicKeyHex,
                        displayName = identity.displayName,
                        role = ProtocolMemberInfo.ROLE_ADMIN,
                        upiId = identity.upiId,
                        phone = identity.phone
                    )
                )
            )

            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = emptyList(),
                logicalClock = 1L,
                payload = genesisPayload
            )

            val outboxOp = syncManager.buildOutboxOperation(op, groupKeyBase64)

            database.withTransaction {
                database.groupDao().insertGroup(
                    GroupEntity(
                        id = groupId,
                        name = name.trim(),
                        currency = currency.trim(),
                        creatorPubkey = identity.publicKeyHex,
                        groupKeyBase64 = groupKeyBase64,
                        isSecret = isSecret,
                        secretPinHash = pinHash,
                        isPersonalSolo = isPersonalSolo,
                        monthlyBudget = monthlyBudget
                    )
                )

                database.blockDao().insertBlock(
                    BlockEntity.fromDomainBlock(genesisBlock, isVerified = true, isSynced = false)
                )
                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = groupId,
                        pubkey = identity.publicKeyHex,
                        displayName = identity.displayName,
                        upiId = identity.upiId,
                        phone = identity.phone
                    )
                )

                database.signedOperationDao().insertOperationIgnore(
                    OperationEntityMapper.toEntity(op, SignedOperationEntity.STATUS_ACCEPTED)
                )
                database.outboxDao().insertOutboxOperation(outboxOp)

                database.groupProtocolDao().upsertGroupProtocol(
                    GroupProtocolEntity(
                        groupId = groupId,
                        protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                        genesisOperationId = op.operationId,
                        latestStateDigest = "",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            Pair(op, outboxOp)
        }

        // Broadcast to P2P relay network
        syncManager.monitorGroup(groupId)
        syncManager.broadcastBlock(genesisBlock, groupKeyBase64)
        syncManager.transmitOutboxOperation(genesisOutboxOp)
        syncManager.reconcileV2Group(groupId)

        // Add any initial friends added during group creation (supports "Name" or "Name|Phone" or "Name|Phone|UpiId")
        if (!isPersonalSolo) {
            for (friendEntry in initialMemberNames) {
                val parts = friendEntry.split("|")
                val cleanName = parts.getOrNull(0)?.trim().orEmpty()
                val cleanPhone = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val cleanUpi = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                if (cleanName.isNotBlank()) {
                    addOfflineMember(groupId, cleanName, cleanUpi, cleanPhone)
                }
            }
        }

        return groupId
    }

    /**
     * Adds an offline / non-app member ("Ghost Member") directly to the group's blockchain ledger.
     */
    suspend fun addOfflineMember(
        groupId: String,
        displayName: String,
        upiId: String? = null,
        phone: String? = null
    ) {
        if (displayName.isBlank()) return
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return

        val cleanName = displayName.trim()
        val cleanPhone = KeyManager.normalizePhone(phone).takeIf { it.isNotBlank() }
        val cleanUpi = upiId?.let { com.peersplit.app.util.UpiAddress.requireValidOrEmpty(it) }?.takeIf { it.isNotBlank() }
        // Generate deterministic 64-char hex pubkey identifier for offline member
        val ghostPubkey = HashUtil.sha256Hex("ghost:$groupId:${cleanName.lowercase()}")

        val (joinBlock, memberOutboxOp) = syncManager.withLedgerLock<Pair<Block, OutboxOperationEntity>?> {
            val latestBlock = database.blockDao().getLatestBlockForGroup(groupId)?.toDomainBlock()
                ?: return@withLedgerLock null

            val block = BlockchainEngine.createMemberJoinBlock(
                groupId = groupId,
                prevBlock = latestBlock,
                newMemberPubkey = ghostPubkey,
                newMemberName = cleanName,
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex,
                upiId = cleanUpi,
                phone = cleanPhone
            )

            // V2 MemberAdded SignedOperation
            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)
            val (currentEpoch, activeKey) = getActiveEpochAndKey(groupId, groupEntity.groupKeyBase64)
            val memberPayload = OperationPayload.MemberAdded(
                memberPublicKey = ghostPubkey,
                displayName = cleanName,
                role = ProtocolMemberInfo.ROLE_GHOST,
                upiId = cleanUpi,
                phone = cleanPhone
            )

            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                payload = memberPayload,
                keyEpoch = currentEpoch
            )
            val outboxOp = commitOperationDurableAtomic(op, activeKey) {
                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = groupId,
                        pubkey = ghostPubkey,
                        displayName = cleanName,
                        upiId = cleanUpi,
                        phone = cleanPhone
                    )
                )
                database.blockDao().insertBlock(
                    BlockEntity.fromDomainBlock(block, isVerified = true, isSynced = false)
                )
            }

            Pair(block, outboxOp)
        } ?: return

        syncManager.broadcastBlock(joinBlock, groupEntity.groupKeyBase64)
        syncManager.transmitOutboxOperation(memberOutboxOp)
        syncManager.reconcileV2Group(groupId)
    }

    /**
     * Administratively removes a member from the group.
     * Advances the group encryption key epoch using KeyRotationManager to ensure forward secrecy,
     * authors a V2 MemberRemoved signed operation, and atomically commits the operation and outbox record.
     */
    suspend fun removeMember(groupId: String, memberPubkey: String, reason: String = "Removed by admin"): Result<Unit> = withContext(Dispatchers.IO) {
        val groupEntity = database.groupDao().getGroupByIdSync(groupId)
            ?: return@withContext Result.failure(IllegalArgumentException("Group not found: $groupId"))
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val myPubkey = identity.publicKeyHex.lowercase()
        val isCreator = groupEntity.creatorPubkey.lowercase() == myPubkey
        val protoMeta = database.groupProtocolDao().getGroupProtocolSync(groupId)

        // Validate caller authority before generating or committing rotation state (R1)
        if (!isCreator) {
            val existingOps = database.signedOperationDao().getOperationsForGroupSync(groupId)
            val domainOps = existingOps.map { OperationEntityMapper.toDomain(it) }
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
            val callerRole = currentRecon.activeMembers[myPubkey]?.role
            if (callerRole != ProtocolMemberInfo.ROLE_ADMIN) {
                return@withContext Result.failure(SecurityException("Unauthorized: only group admins can remove members"))
            }
        }

        val priorEpoch = protoMeta?.currentKeyEpoch ?: 0
        val priorKeyBase64 = com.peersplit.app.crypto.KeyRotationManager.getKeyForEpoch(database, groupId, priorEpoch)
            ?: groupEntity.groupKeyBase64

        val groupMutex = getGroupMutex(groupId)
        val (signedOp, outboxOp, keyOutboxList) = groupMutex.withLock {
            val latestKey = database.groupKeyDao().getLatestKey(groupId)
            val nextEpoch = (latestKey?.keyEpoch ?: priorEpoch) + 1
            val newKeyBytes = com.peersplit.app.crypto.EncryptionUtil.generateGroupKey()
            val newKeyBase64 = com.peersplit.app.crypto.EncryptionUtil.keyToBase64(newKeyBytes)

            val remainingMembers = database.memberDao().getMembersForGroupSync(groupId)
                .map { it.pubkey }
                .filter { !it.equals(memberPubkey, ignoreCase = true) }

            val rotatedKeyEntity = com.peersplit.app.data.local.entity.GroupKeyEntity(
                groupId = groupId,
                keyEpoch = nextEpoch,
                groupKeyBase64 = newKeyBase64,
                distributedToPubkeysJson = json.encodeToString(ListSerializer(String.serializer()), remainingMembers),
                createdAtMillis = System.currentTimeMillis()
            )

            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)

            val payload = OperationPayload.MemberRemoved(
                memberPublicKey = memberPubkey,
                reason = reason,
                newKeyEpoch = nextEpoch
            )

            // Encrypt MemberRemoved with prior epoch key so all existing peers can decrypt the removal transition
            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                keyEpoch = priorEpoch,
                payload = payload
            )

            val removalOutboxOp = syncManager.buildOutboxOperation(op, priorKeyBase64)

            // Distribute new epoch key confidentially to each remaining member
            val keyOutboxItems = mutableListOf<OutboxOperationEntity>()
            for (recipientPubkey in remainingMembers) {
                if (recipientPubkey.equals(myPubkey, ignoreCase = true)) continue
                try {
                    val encryptedKeyForPeer = com.peersplit.app.crypto.KeyExchangeUtil.encryptForPeer(
                        message = newKeyBase64,
                        myPrivHex = privKeyHex,
                        theirPubHex = recipientPubkey
                    )
                    val envelope = com.peersplit.app.crypto.KeyDistributionEnvelope.createAndSign(
                        groupId = groupId,
                        epoch = nextEpoch,
                        authorPublicKey = identity.publicKeyHex,
                        authorPrivateKeyHex = privKeyHex,
                        recipientPublicKey = recipientPubkey,
                        transitionOperationId = op.operationId,
                        keyCiphertext = encryptedKeyForPeer
                    )
                    val envelopeJson = json.encodeToString(com.peersplit.app.crypto.KeyDistributionEnvelope.serializer(), envelope)

                    val keyEventTags = listOf(
                        listOf("t", "peersplit_key"),
                        listOf("g", groupId),
                        listOf("p", recipientPubkey.lowercase()),
                        listOf("epoch", nextEpoch.toString()),
                        listOf("op", op.operationId),
                        listOf("author", identity.publicKeyHex.lowercase())
                    )
                    val keyEvent = NostrEvent.createAndSign(
                        pubkey = identity.publicKeyHex,
                        privateKeyHex = privKeyHex,
                        kind = NostrEvent.KIND_PEERSPLIT_BLOCK,
                        tags = keyEventTags,
                        content = envelopeJson
                    )
                    val eventJson = json.encodeToString(NostrEvent.serializer(), keyEvent)
                    keyOutboxItems.add(
                        OutboxOperationEntity(
                            operationId = "key:${groupId}:${nextEpoch}:${recipientPubkey.take(8)}",
                            groupId = groupId,
                            nostrEventId = keyEvent.id,
                            signedEventJson = eventJson,
                            keyEpoch = nextEpoch,
                            attemptCount = 0,
                            nextAttemptAtMillis = System.currentTimeMillis() + 5000L,
                            status = OutboxOperationEntity.STATUS_QUEUED
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not encrypt rotated key for member $recipientPubkey: ${e.message}")
                }
            }

            database.withTransaction {
                database.groupKeyDao().insertKey(rotatedKeyEntity)
                database.signedOperationDao().insertOperationIgnore(
                    OperationEntityMapper.toEntity(op, SignedOperationEntity.STATUS_ACCEPTED)
                )
                val deps = OperationEntityMapper.toDependencyEntities(op)
                if (deps.isNotEmpty()) {
                    database.signedOperationDao().insertDependenciesIgnore(deps)
                }
                database.outboxDao().insertOutboxOperation(removalOutboxOp)
                for (kOp in keyOutboxItems) {
                    database.outboxDao().insertOutboxOperation(kOp)
                }
                database.memberDao().deleteMember(groupId, memberPubkey)
            }

            Triple(op, removalOutboxOp, keyOutboxItems)
        }

        syncManager.transmitOutboxOperation(outboxOp)
        for (kOp in keyOutboxList) {
            syncManager.transmitOutboxOperation(kOp)
        }
        syncManager.reconcileV2Group(groupId)
        Result.success(Unit)
    }

    /**
     * Updates the monthly / group spending budget target.
     */
    suspend fun updateGroupBudget(groupId: String, budget: Double) {
        database.groupDao().updateGroupBudget(groupId, budget.coerceAtLeast(0.0))
    }

    /**
     * Archives or unarchives a group.
     */
    suspend fun archiveGroup(groupId: String, isArchived: Boolean = true) {
        database.groupDao().setGroupArchived(groupId, isArchived)
    }

    /**
     * Completely deletes a group, its members, and all its transaction blocks.
     */
    suspend fun deleteGroup(groupId: String) {
        groupDetailCache.remove(groupId)
        database.withTransaction {
            database.outboxDao().deleteRelayDeliveriesForGroup(groupId)
            database.outboxDao().deleteOutboxForGroup(groupId)
            database.groupKeyDao().deleteKeysForGroup(groupId)
            database.blockDao().deleteBlocksForGroup(groupId)
            database.memberDao().deleteMembersForGroup(groupId)
            database.signedOperationDao().deleteDependenciesForGroup(groupId)
            database.signedOperationDao().deleteOperationsForGroup(groupId)
            database.projectionDao().deleteExpenseProjectionsForGroup(groupId)
            database.projectionDao().deleteBalanceProjectionsForGroup(groupId)
            database.groupProtocolDao().deleteGroupProtocol(groupId)
            database.groupDao().deleteGroup(groupId)
        }
    }

    /**
     * Updates the local UPI ID for a member in a group.
     */
    suspend fun updateMemberUpiId(groupId: String, pubkey: String, upiId: String) {
        val cleanUpi = com.peersplit.app.util.UpiAddress.requireValidOrEmpty(upiId)
        database.memberDao().updateMemberUpiId(groupId, pubkey, cleanUpi)
    }

    /**
     * Updates both phone number and UPI ID for a member in a group.
     */
    suspend fun updateMemberContact(groupId: String, pubkey: String, phone: String?, upiId: String?) {
        val cleanPhone = KeyManager.normalizePhone(phone).takeIf { it.isNotBlank() }
        val cleanUpi = upiId?.let { com.peersplit.app.util.UpiAddress.requireValidOrEmpty(it) }?.takeIf { it.isNotBlank() }
        database.memberDao().updateMemberContact(groupId, pubkey, cleanPhone, cleanUpi)
    }

    /**
     * Adds an expense, signs the block, appends to local chain, and broadcasts.
     */
    suspend fun addExpense(
        groupId: String,
        description: String,
        amount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String = "General",
        splitType: String = "EQUAL",
        note: String = "",
        payers: List<SplitItem> = emptyList(),
        expenseDateMillis: Long? = null,
        editedFromExpenseId: String? = null
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: error("Group or ledger is no longer available")

        val (expenseBlock, expenseOutboxOp) = syncManager.withLedgerLock<Pair<Block, OutboxOperationEntity>?> {
            val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return@withLedgerLock null
            val prevBlock = latestBlockEntity.toDomainBlock()

            val block = BlockchainEngine.createExpenseBlock(
                groupId = groupId,
                prevBlock = prevBlock,
                description = description,
                totalAmount = amount,
                currency = currency,
                payerPubkey = payerPubkey,
                splits = splits,
                category = category,
                note = note,
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex,
                splitType = splitType,
                payers = payers,
                expenseDateMillis = expenseDateMillis,
                editedFromExpenseId = editedFromExpenseId
            )

            // V2 ExpenseCreated SignedOperation
            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)
            val totalAmountMinor = MoneyMath.toMinorUnits(amount)
            val rawSplitsMinor = splits.associate {
                it.userPubkey to MoneyMath.toMinorUnits(it.amount)
            }
            val splitsMinor = MoneyMath.normalizeSplitsToExactTotal(totalAmountMinor, rawSplitsMinor)
            val rawPayersMinor = if (payers.isNotEmpty()) {
                payers.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
            } else {
                mapOf(payerPubkey to totalAmountMinor)
            }
            val payersMinor = MoneyMath.normalizeSplitsToExactTotal(totalAmountMinor, rawPayersMinor)

            val expPayload = OperationPayload.ExpenseCreated(
                expenseId = block.blockHash,
                amountMinor = MoneyMath.toMinorUnits(amount),
                currency = currency,
                paidBy = payerPubkey,
                splits = splitsMinor,
                description = description,
                payers = payersMinor,
                category = category,
                note = note,
                splitType = splitType,
                expenseDateMillis = expenseDateMillis ?: System.currentTimeMillis()
            )
            val (currentEpoch, activeKey) = getActiveEpochAndKey(groupId, groupEntity.groupKeyBase64)
            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                keyEpoch = currentEpoch,
                payload = expPayload
            )
            val outboxOp = commitOperationDurableAtomic(op, activeKey) {
                database.blockDao().insertBlock(
                    BlockEntity.fromDomainBlock(block, isVerified = true, isSynced = false)
                )
            }

            Pair(block, outboxOp)
        } ?: error("Group or ledger is no longer available")

        syncManager.broadcastBlock(expenseBlock, groupEntity.groupKeyBase64)
        syncManager.transmitOutboxOperation(expenseOutboxOp)
        syncManager.reconcileV2Group(groupId)
        check(database.signedOperationDao().getOperationByIdSync(expenseOutboxOp.operationId)?.status == SignedOperationEntity.STATUS_ACCEPTED) {
            "The ledger did not accept this change. Review current group membership and expense revisions before retrying."
        }
    }

    /**
     * Edits an existing expense on the immutable ledger by atomically voiding the original expense
     * and appending the updated expense block in a single NonCancellable block, then broadcasting both to peers.
     */
    suspend fun editExpense(
        groupId: String,
        originalExpenseId: String,
        originalDescription: String,
        newDescription: String,
        newAmount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String = "General",
        splitType: String = "EQUAL",
        note: String = "",
        payers: List<SplitItem> = emptyList(),
        expenseDateMillis: Long? = null
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: error("Group or ledger is no longer available")

        val (voidBlock, newExpenseBlock, updateOutboxOp) = syncManager.withLedgerLock<Triple<Block, Block, OutboxOperationEntity>?> {
            val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return@withLedgerLock null
            val prevBlock = latestBlockEntity.toDomainBlock()

            // 1. Mint Void Block for original expense
            val vBlock = BlockchainEngine.createExpenseVoidBlock(
                groupId = groupId,
                prevBlock = prevBlock,
                targetExpenseId = originalExpenseId,
                targetDescription = originalDescription,
                reason = "Edited & replaced",
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex
            )

            // 2. Mint Replacement Expense Block chained directly on top of voidBlock
            val expBlock = BlockchainEngine.createExpenseBlock(
                groupId = groupId,
                prevBlock = vBlock,
                description = newDescription,
                totalAmount = newAmount,
                currency = currency,
                payerPubkey = payerPubkey,
                splits = splits,
                category = category,
                note = note,
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex,
                splitType = splitType,
                payers = payers,
                expenseDateMillis = expenseDateMillis,
                editedFromExpenseId = originalExpenseId
            )

            // V2 ExpenseUpdated SignedOperation
            val targetExpProj = database.projectionDao().getExpenseProjectionsForGroupSync(groupId)
                .firstOrNull { it.expenseId == originalExpenseId }
            val expectedRevId = targetExpProj?.currentRevisionOperationId ?: originalExpenseId

            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)
            val totalAmountMinor = MoneyMath.toMinorUnits(newAmount)
            val rawSplitsMinor = splits.associate {
                it.userPubkey to MoneyMath.toMinorUnits(it.amount)
            }
            val splitsMinor = MoneyMath.normalizeSplitsToExactTotal(totalAmountMinor, rawSplitsMinor)
            val rawPayersMinor = if (payers.isNotEmpty()) {
                payers.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
            } else {
                mapOf(payerPubkey to totalAmountMinor)
            }
            val payersMinor = MoneyMath.normalizeSplitsToExactTotal(totalAmountMinor, rawPayersMinor)

            val replacement = OperationPayload.ExpenseCreated(
                expenseId = originalExpenseId,
                amountMinor = MoneyMath.toMinorUnits(newAmount),
                currency = currency,
                paidBy = payerPubkey,
                splits = splitsMinor,
                description = newDescription,
                payers = payersMinor,
                category = category,
                note = note,
                splitType = splitType,
                expenseDateMillis = expenseDateMillis ?: System.currentTimeMillis()
            )
            val updatePayload = OperationPayload.ExpenseUpdated(
                expenseId = originalExpenseId,
                expectedRevisionId = expectedRevId,
                replacement = replacement
            )
            val (currentEpoch, activeKey) = getActiveEpochAndKey(groupId, groupEntity.groupKeyBase64)
            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                keyEpoch = currentEpoch,
                payload = updatePayload
            )
            val outboxOp = commitOperationDurableAtomic(op, activeKey) {
                // Insert both blocks atomically in a single batch so UI never sees an intermediate "cancelled" state
                database.blockDao().insertBlocks(
                    listOf(
                        BlockEntity.fromDomainBlock(vBlock, isVerified = true, isSynced = false),
                        BlockEntity.fromDomainBlock(expBlock, isVerified = true, isSynced = false)
                    )
                )
            }

            Triple(vBlock, expBlock, outboxOp)
        } ?: error("Group or ledger is no longer available")

        syncManager.broadcastBlock(voidBlock, groupEntity.groupKeyBase64)
        syncManager.broadcastBlock(newExpenseBlock, groupEntity.groupKeyBase64)
        syncManager.transmitOutboxOperation(updateOutboxOp)
        syncManager.reconcileV2Group(groupId)
        check(database.signedOperationDao().getOperationByIdSync(updateOutboxOp.operationId)?.status == SignedOperationEntity.STATUS_ACCEPTED) {
            "The ledger did not accept this change. Review current group membership and expense revisions before retrying."
        }
    }

    /**
     * Voids / reverses a previously recorded expense by appending a signed EXPENSE_VOID block.
     */
    suspend fun voidExpense(
        groupId: String,
        targetExpenseId: String,
        targetDescription: String,
        reason: String = "Voided by member"
    ) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: error("Group or ledger is no longer available")

        val (voidBlock, voidOutboxOp) = syncManager.withLedgerLock<Pair<Block, OutboxOperationEntity>?> {
            val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return@withLedgerLock null
            val prevBlock = latestBlockEntity.toDomainBlock()

            val block = BlockchainEngine.createExpenseVoidBlock(
                groupId = groupId,
                prevBlock = prevBlock,
                targetExpenseId = targetExpenseId,
                targetDescription = targetDescription,
                reason = reason,
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex
            )

            // V2 ExpenseVoided SignedOperation
            val targetExpProj = database.projectionDao().getExpenseProjectionsForGroupSync(groupId)
                .firstOrNull { it.expenseId == targetExpenseId }
            val expectedRevId = targetExpProj?.currentRevisionOperationId ?: targetExpenseId

            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)
            val (currentEpoch, activeKey) = getActiveEpochAndKey(groupId, groupEntity.groupKeyBase64)
            val voidPayload = OperationPayload.ExpenseVoided(
                expenseId = targetExpenseId,
                expectedRevisionId = expectedRevId,
                reason = reason
            )
            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                keyEpoch = currentEpoch,
                payload = voidPayload
            )
            val outboxOp = commitOperationDurableAtomic(op, activeKey) {
                database.blockDao().insertBlock(
                    BlockEntity.fromDomainBlock(block, isVerified = true, isSynced = false)
                )
            }

            Pair(block, outboxOp)
        } ?: error("Group or ledger is no longer available")

        syncManager.broadcastBlock(voidBlock, groupEntity.groupKeyBase64)
        syncManager.transmitOutboxOperation(voidOutboxOp)
        syncManager.reconcileV2Group(groupId)
        check(database.signedOperationDao().getOperationByIdSync(voidOutboxOp.operationId)?.status == SignedOperationEntity.STATUS_ACCEPTED) {
            "The ledger did not accept this change. Review current group membership and expense revisions before retrying."
        }
    }

    /**
     * Records a debt settlement between two users.
     */
    suspend fun recordSettlement(
        groupId: String,
        payerPubkey: String,
        payeePubkey: String,
        amount: Double,
        currency: String
    ) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: error("Group or ledger is no longer available")

        val (settlementBlock, settlementOutboxOp) = syncManager.withLedgerLock<Pair<Block, OutboxOperationEntity>?> {
            val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return@withLedgerLock null
            val prevBlock = latestBlockEntity.toDomainBlock()

            val block = BlockchainEngine.createSettlementBlock(
                groupId = groupId,
                prevBlock = prevBlock,
                payerPubkey = payerPubkey,
                payeePubkey = payeePubkey,
                amount = amount,
                currency = currency,
                note = "Direct Settlement",
                authorPubkey = identity.publicKeyHex,
                authorPrivateKeyHex = privKeyHex
            )

            // V2 SettlementRecorded SignedOperation
            val parents = getDagHeads(groupId)
            val nextClock = getNextClock(groupId, parents)
            val (currentEpoch, activeKey) = getActiveEpochAndKey(groupId, groupEntity.groupKeyBase64)
            val stlPayload = OperationPayload.SettlementRecorded(
                settlementId = block.blockHash,
                fromMember = payerPubkey,
                toMember = payeePubkey,
                amountMinor = MoneyMath.toMinorUnits(amount),
                currency = currency,
                note = "Direct Settlement"
            )

            val op = OperationSigner.createAndSign(
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                groupId = groupId,
                authorPublicKey = identity.publicKeyHex,
                privateKeyHex = privKeyHex,
                parents = parents,
                logicalClock = nextClock,
                keyEpoch = currentEpoch,
                payload = stlPayload
            )
            val outboxOp = commitOperationDurableAtomic(op, activeKey) {
                database.blockDao().insertBlock(
                    BlockEntity.fromDomainBlock(block, isVerified = true, isSynced = false)
                )
            }

            Pair(block, outboxOp)
        } ?: error("Group or ledger is no longer available")

        syncManager.broadcastBlock(settlementBlock, groupEntity.groupKeyBase64)
        syncManager.transmitOutboxOperation(settlementOutboxOp)
        syncManager.reconcileV2Group(groupId)
        check(database.signedOperationDao().getOperationByIdSync(settlementOutboxOp.operationId)?.status == SignedOperationEntity.STATUS_ACCEPTED) {
            "The ledger did not accept this change. Review current group membership and expense revisions before retrying."
        }
    }

    private fun encodeInviteUri(inviteData: GroupInviteData): String {
        val jsonBytes = json.encodeToString(inviteData).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(jsonBytes) }
        val compressed = bos.toByteArray()
        return "peersplit://" + Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decodeInviteData(inviteUri: String): GroupInviteData {
        val cleanUri = Regex("peersplit://\\S+").find(inviteUri)?.value ?: inviteUri.trim()
        val base64Payload = cleanUri.removePrefix("peersplit://").trim()
        require(base64Payload.isNotBlank()) { "Empty invite code" }
        require(base64Payload.length <= 512000) { "Invite is too large" }
        val rawBytes = Base64.decode(base64Payload, Base64.URL_SAFE or Base64.NO_WRAP)
        val jsonStr = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
            com.peersplit.app.util.BoundedInput(GZIPInputStream(ByteArrayInputStream(rawBytes)), 2L * 1024 * 1024).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(rawBytes, Charsets.UTF_8)
        }
        return json.decodeFromString(GroupInviteData.serializer(), jsonStr)
    }

    /**
     * Generates a shareable URL-safe invite string containing group credentials and current ledger blocks.
     * Uses GZIP compression and caps payload size so the invite always renders cleanly as a QR code.
     */
    suspend fun generateInviteCode(groupId: String): String {
        val group = database.groupDao().getGroupByIdSync(groupId) ?: return ""
        val blocks = database.blockDao().getBlocksForGroupSync(groupId).map { it.toDomainBlock() }.sortedBy { it.index }
        val genesisBlock = blocks.firstOrNull { it.index == 0L }
        val genesisJson = genesisBlock?.let { json.encodeToString(Block.serializer(), it) }
        val allBlocksJson = blocks.map { json.encodeToString(Block.serializer(), it) }
        val members = database.memberDao().getMembersForGroupSync(groupId)
        val creatorName = members.firstOrNull { it.pubkey == group.creatorPubkey }?.displayName ?: "Creator"

        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val grant = try {
            val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
            OperationSigner.createInvitationGrant(
                inviterPublicKey = identity.publicKeyHex,
                invitedPublicKey = "*",
                groupId = group.id,
                targetRole = ProtocolMemberInfo.ROLE_MEMBER,
                invitationNonce = nonce,
                expiresAtMillis = System.currentTimeMillis() + 14L * 24L * 60L * 60L * 1000L,
                inviterPrivateKeyHex = privKeyHex
            )
        } catch (_: Exception) { null }
        val grantJson = grant?.let { json.encodeToString(MembershipInvitationGrant.serializer(), it) }

        val acceptedOps = database.signedOperationDao().getAcceptedOperationsForGroupSync(groupId)
        val domainOps = acceptedOps.map { OperationEntityMapper.toDomain(it) }
        val domainOpsById = domainOps.associateBy { it.operationId }
        val allOpsJson = domainOps.map {
            json.encodeToString(SignedOperation.serializer(), it)
        }

        val fullInvite = GroupInviteData(
            groupId = group.id,
            groupName = group.name,
            currency = group.currency,
            groupKeyBase64 = group.groupKeyBase64,
            creatorPubkey = group.creatorPubkey,
            creatorDisplayName = creatorName,
            genesisBlockJson = if (allBlocksJson.isEmpty()) genesisJson else null,
            blocksJson = allBlocksJson,
            invitationGrantJson = grantJson,
            protocolVersion = ProtocolVersion.CURRENT,
            operationsJson = allOpsJson
        )
        val encodedFull = encodeInviteUri(fullInvite)
        if (encodedFull.length <= 2200) {
            return encodedFull
        }

        // If payload is large, trim non-essential V1 blocks first while keeping operations
        val essentialBlocks = blocks.filter {
            it.blockType == BlockType.GENESIS || it.blockType == BlockType.MEMBER_JOIN
        }
        val otherBlocks = blocks.filter {
            it.blockType != BlockType.GENESIS && it.blockType != BlockType.MEMBER_JOIN
        }
        val selectedBlocks = essentialBlocks.toMutableList()
        for (b in otherBlocks.asReversed()) {
            val candidate = (selectedBlocks + b).sortedBy { it.index }.map { json.encodeToString(Block.serializer(), it) }
            val candidateUri = encodeInviteUri(fullInvite.copy(blocksJson = candidate))
            if (candidateUri.length <= 2200) {
                selectedBlocks.add(b)
            } else {
                break
            }
        }
        val finalBlocksJson = selectedBlocks.sortedBy { it.index }.map { json.encodeToString(Block.serializer(), it) }
        val trimmedUri = encodeInviteUri(fullInvite.copy(blocksJson = finalBlocksJson))
        if (trimmedUri.length <= 2500) {
            return trimmedUri
        }

        // Helper to compute causal ancestor closure within accepted operations
        fun getCausalClosure(seedOpIds: Collection<String>): Set<String> {
            val closure = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            for (id in seedOpIds) {
                if (closure.add(id)) {
                    queue.add(id)
                }
            }
            while (queue.isNotEmpty()) {
                val currentId = queue.removeFirst()
                val op = domainOpsById[currentId] ?: continue
                for (parentId in op.parents) {
                    if (parentId in domainOpsById && closure.add(parentId)) {
                        queue.add(parentId)
                    }
                }
            }
            return closure
        }

        // Identify root operations and inviter admin-authorization chain
        val rootOps = domainOps.filter {
            it.parents.isEmpty() ||
            it.payload is OperationPayload.GenesisCreated ||
            it.payload is OperationPayload.LegacyV1Checkpoint
        }
        val inviterPubkey = identity.publicKeyHex
        val inviterAddOp = if (inviterPubkey != group.creatorPubkey) {
            domainOps.firstOrNull {
                it.payload is OperationPayload.MemberAdded && it.payload.memberPublicKey == inviterPubkey
            }
        } else null
        val inviterClosureIds = if (inviterAddOp != null) {
            getCausalClosure(listOf(inviterAddOp.operationId))
        } else {
            emptySet()
        }

        // All membership operations and their causal closures
        val membershipOps = domainOps.filter {
            it.payload is OperationPayload.MemberAdded || it.payload is OperationPayload.MemberRemoved
        }
        val membershipClosureIds = getCausalClosure(membershipOps.map { it.operationId })

        // Essential operations: Roots + Inviter chain + Membership operations with complete causal ancestors
        val essentialOpIds = LinkedHashSet<String>().apply {
            addAll(rootOps.map { it.operationId })
            addAll(inviterClosureIds)
            addAll(membershipClosureIds)
        }
        val essentialOps = domainOps.filter { it.operationId in essentialOpIds }.sortedBy { it.logicalClock }

        // Start with essential operations, then greedily append other accepted operations in causal/clock order
        val selectedOps = essentialOps.toMutableList()
        val selectedOpIds = selectedOps.map { it.operationId }.toMutableSet()
        val remainingOps = domainOps.filter { it.operationId !in selectedOpIds }.sortedBy { it.logicalClock }

        for (op in remainingOps) {
            if (op.parents.all { it in selectedOpIds }) {
                val candidateOps = (selectedOps + op).sortedBy { it.logicalClock }
                val candidateOpsJson = candidateOps.map {
                    json.encodeToString(SignedOperation.serializer(), it)
                }
                val candidateUri = encodeInviteUri(
                    fullInvite.copy(
                        genesisBlockJson = genesisJson,
                        blocksJson = emptyList(),
                        operationsJson = candidateOpsJson
                    )
                )
                if (candidateUri.length <= 2200) {
                    selectedOps.add(op)
                    selectedOpIds.add(op.operationId)
                } else {
                    break
                }
            }
        }

        val finalOpsJson = selectedOps.sortedBy { it.logicalClock }.map {
            json.encodeToString(SignedOperation.serializer(), it)
        }
        val trimmedOpsUri = encodeInviteUri(
            fullInvite.copy(
                genesisBlockJson = genesisJson,
                blocksJson = emptyList(),
                operationsJson = finalOpsJson
            )
        )
        if (trimmedOpsUri.length <= 2500) {
            return trimmedOpsUri
        }

        // Fallback: strictly root operations + inviter's causal authorization chain (guaranteed to include inviter as admin)
        val minimalInviterOpIds = LinkedHashSet<String>().apply {
            addAll(rootOps.map { it.operationId })
            addAll(inviterClosureIds)
        }
        val minimalOps = domainOps.filter { it.operationId in minimalInviterOpIds }.sortedBy { it.logicalClock }
        val minimalOpsJson = minimalOps.map {
            json.encodeToString(SignedOperation.serializer(), it)
        }
        return encodeInviteUri(
            fullInvite.copy(
                genesisBlockJson = genesisJson,
                blocksJson = emptyList(),
                operationsJson = minimalOpsJson
            )
        )
    }

    /**
     * Parses an invite URI to preview the group name, currency, and any unclaimed offline members
     * before joining.
     */
    fun parseInvitePreview(inviteUri: String): GroupInvitePreview? {
        return try {
            val inviteData = decodeInviteData(inviteUri)

            val blockStrings = if (inviteData.blocksJson.isNotEmpty()) {
                inviteData.blocksJson
            } else {
                listOfNotNull(inviteData.genesisBlockJson)
            }
            val blocks = blockStrings.mapNotNull {
                try { json.decodeFromString(Block.serializer(), it) } catch (_: Exception) { null }
            }.sortedBy { it.index }

            val claimedGhosts = mutableSetOf<String>()
            val ghostMap = linkedMapOf<String, MemberInfo>()
            var expenseCount = 0

            for (b in blocks) {
                when (b.blockType) {
                    BlockType.MEMBER_JOIN -> {
                        try {
                            val p = json.decodeFromString(MemberJoinedPayload.serializer(), b.payloadJson)
                            if (!p.claimedGhostPubkey.isNullOrBlank()) {
                                claimedGhosts.add(p.claimedGhostPubkey)
                                ghostMap.remove(p.claimedGhostPubkey)
                            }
                            if (isGhostPubkey(inviteData.groupId, p.pubkey, p.displayName) && p.pubkey !in claimedGhosts) {
                                ghostMap[p.pubkey] = MemberInfo(p.pubkey, p.displayName, p.upiId, p.phone)
                            }
                        } catch (_: Exception) {}
                    }
                    BlockType.EXPENSE -> expenseCount++
                    else -> {}
                }
            }

            GroupInvitePreview(
                groupId = inviteData.groupId,
                groupName = inviteData.groupName,
                currency = inviteData.currency,
                creatorDisplayName = inviteData.creatorDisplayName,
                unclaimedGhostMembers = ghostMap.values.toList(),
                expenseCount = expenseCount
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Joins a group via invite string / QR code, imports all embedded blocks,
     * claims matching/selected offline member, and broadcasts Member Join block.
     */
    suspend fun joinGroup(
        inviteUri: String,
        explicitClaimGhostPubkey: String? = null
    ): Result<String> {
        return try {
            val inviteData = decodeInviteData(inviteUri)
            require(inviteData.groupId.isNotBlank() && inviteData.groupKeyBase64.isNotBlank()) {
                "Invalid invite credentials"
            }

            // 1. Parse and validate all blocks included in the invite link
            val rawBlockJsonList = if (inviteData.blocksJson.isNotEmpty()) {
                inviteData.blocksJson
            } else {
                listOfNotNull(inviteData.genesisBlockJson)
            }

            val importedBlocks = rawBlockJsonList.mapNotNull {
                try { json.decodeFromString(Block.serializer(), it) } catch (_: Exception) { null }
            }.filter { block ->
                block.groupId == inviteData.groupId &&
                    block.verifyIntegrity() &&
                    BlockchainEngine.validateBlockPayload(block) &&
                    (block.index != 0L || (block.prevHash == Block.GENESIS_PREV_HASH && block.creatorPubkey == inviteData.creatorPubkey))
            }.sortedBy { it.index }

            val identity = KeyManager.getOrCreateIdentity(context)
            val privKeyHex = KeyManager.getPrivateKeyHex(context)

            val (mintedJoinBlock, joinV2Operation) = syncManager.withLedgerLock {
                val existing = database.groupDao().getGroupByIdSync(inviteData.groupId)
                if (existing == null) {
                    database.groupDao().insertGroup(
                        GroupEntity(
                            id = inviteData.groupId,
                            name = inviteData.groupName,
                            currency = inviteData.currency,
                            creatorPubkey = inviteData.creatorPubkey,
                            groupKeyBase64 = inviteData.groupKeyBase64
                        )
                    )
                }

                // Insert Creator Member first
                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = inviteData.groupId,
                        pubkey = inviteData.creatorPubkey,
                        displayName = inviteData.creatorDisplayName
                    )
                )

                // Import verified blocks
                for (block in importedBlocks) {
                    database.blockDao().insertBlock(
                        BlockEntity.fromDomainBlock(block, isVerified = true, isSynced = true)
                    )
                    syncManager.processBlockSideEffects(inviteData.groupId, block)
                }

                // Import V2 SignedOperations included in the invite
                if (inviteData.operationsJson.isNotEmpty()) {
                    for (opJson in inviteData.operationsJson) {
                        try {
                            val op = json.decodeFromString(SignedOperation.serializer(), opJson)
                            if (op.groupId == inviteData.groupId && OperationVerifier.verify(op)) {
                                database.signedOperationDao().insertOperationIgnore(
                                    OperationEntityMapper.toEntity(op, SignedOperationEntity.STATUS_ACCEPTED)
                                )
                                val deps = OperationEntityMapper.toDependencyEntities(op)
                                if (deps.isNotEmpty()) {
                                    database.signedOperationDao().insertDependenciesIgnore(deps)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ExpenseRepository", "Failed to ingest operation from invite: ${e.message}")
                        }
                    }
                }

                // If signed operations table is still empty but we have imported blocks, migrate to V2 so we have a root checkpoint
                val currentOps = database.signedOperationDao().getOperationsForGroupSync(inviteData.groupId)
                if (currentOps.isEmpty() && importedBlocks.isNotEmpty()) {
                    migrationManager.migrateGroupToV2(inviteData.groupId)
                }

                // Determine if this phone should claim/assume an offline member added by the creator
                val allCurrentBlocks = database.blockDao().getBlocksForGroupSync(inviteData.groupId).map { it.toDomainBlock() }
                val alreadyClaimedGhosts = allCurrentBlocks
                    .filter { it.blockType == BlockType.MEMBER_JOIN }
                    .mapNotNull {
                        try {
                            json.decodeFromString(MemberJoinedPayload.serializer(), it.payloadJson).claimedGhostPubkey
                        } catch (_: Exception) { null }
                    }
                    .toSet()

                val existingMembers = database.memberDao().getMembersForGroupSync(inviteData.groupId)
                val unclaimedGhosts = existingMembers.filter {
                    isGhostPubkey(inviteData.groupId, it.pubkey, it.displayName) && it.pubkey !in alreadyClaimedGhosts
                }

                val normMyPhone = KeyManager.normalizePhone(identity.phone)
                val ghostMatch: MemberEntity? = when {
                    explicitClaimGhostPubkey == "NONE" -> null
                    !explicitClaimGhostPubkey.isNullOrBlank() -> unclaimedGhosts.firstOrNull { it.pubkey == explicitClaimGhostPubkey }
                    else -> {
                        // 1. Match by complete normalized phone number first
                        val byPhone = if (com.peersplit.app.util.PhoneNumbers.canMatch(normMyPhone)) {
                            unclaimedGhosts.firstOrNull { KeyManager.normalizePhone(it.phone) == normMyPhone }
                        } else null
                        // 2. Match by displayName
                        byPhone ?: unclaimedGhosts.firstOrNull {
                            it.displayName.trim().equals(identity.displayName.trim(), ignoreCase = true)
                        } ?: unclaimedGhosts.firstOrNull()
                    }
                }

                val effectiveDisplayName = if (ghostMatch != null &&
                    (identity.displayName.isBlank() ||
                     identity.displayName == "My Phone" ||
                     identity.displayName.trim().equals(inviteData.creatorDisplayName.trim(), ignoreCase = true))
                ) {
                    KeyManager.updateDisplayName(context, ghostMatch.displayName)
                    ghostMatch.displayName
                } else {
                    identity.displayName
                }

                val effectivePhone = identity.phone?.takeIf { it.isNotBlank() } ?: ghostMatch?.phone?.also {
                    if (it.isNotBlank()) KeyManager.updatePhone(context, it)
                }
                val effectiveUpi = identity.upiId?.takeIf { it.isNotBlank() } ?: ghostMatch?.upiId

                if (ghostMatch != null) {
                    database.memberDao().deleteMember(inviteData.groupId, ghostMatch.pubkey)
                }

                database.memberDao().insertMember(
                    MemberEntity(
                        groupId = inviteData.groupId,
                        pubkey = identity.publicKeyHex,
                        displayName = effectiveDisplayName,
                        upiId = effectiveUpi,
                        phone = effectivePhone
                    )
                )

                val alreadyJoinedOnChain = allCurrentBlocks.any { block ->
                    block.blockType == BlockType.MEMBER_JOIN && try {
                        json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson).pubkey == identity.publicKeyHex
                    } catch (_: Exception) { false }
                }

                val currentTip = database.blockDao().getLatestBlockForGroup(inviteData.groupId)?.toDomainBlock()
                    ?: importedBlocks.lastOrNull()

                val joinBlock = if (currentTip != null && identity.publicKeyHex != inviteData.creatorPubkey && !alreadyJoinedOnChain) {
                    val jb = BlockchainEngine.createMemberJoinBlock(
                        groupId = inviteData.groupId,
                        prevBlock = currentTip,
                        newMemberPubkey = identity.publicKeyHex,
                        newMemberName = effectiveDisplayName,
                        authorPubkey = identity.publicKeyHex,
                        authorPrivateKeyHex = privKeyHex,
                        upiId = effectiveUpi,
                        claimedGhostPubkey = ghostMatch?.pubkey,
                        phone = effectivePhone
                    )
                    database.blockDao().insertBlock(
                        BlockEntity.fromDomainBlock(jb, isVerified = true, isSynced = false)
                    )
                    jb
                } else {
                    null
                }

                // V2 MemberAdded SignedOperation
                val joinV2Operation = if (identity.publicKeyHex != inviteData.creatorPubkey) {
                    val groupProtocol = database.groupProtocolDao().getGroupProtocolSync(inviteData.groupId)
                    val existingOps = database.signedOperationDao().getOperationsForGroupSync(inviteData.groupId)
                    val alreadyJoinedV2 = existingOps.any {
                        it.payloadType == "MEMBER_ADDED" && try {
                            val p = json.decodeFromString(OperationPayload.MemberAdded.serializer(), it.payloadJson)
                            p.memberPublicKey == identity.publicKeyHex
                        } catch (_: Exception) { false }
                    }
                    if (!alreadyJoinedV2) {
                        val grant = inviteData.invitationGrantJson?.let {
                            try { json.decodeFromString(MembershipInvitationGrant.serializer(), it) } catch (_: Exception) { null }
                        }
                        if (grant != null && grant.expiresAtMillis > 0L && System.currentTimeMillis() > grant.expiresAtMillis) {
                            throw SecurityException("Invitation grant expired at ${grant.expiresAtMillis} (current time ${System.currentTimeMillis()})")
                        }

                        // Reconcile imported history to ensure projection and DAG heads are populated
                        syncManager.reconcileV2Group(inviteData.groupId)

                        val parents = getDagHeads(inviteData.groupId)
                        val nextClock = getNextClock(inviteData.groupId, parents)
                        val memberPayload = OperationPayload.MemberAdded(
                            memberPublicKey = identity.publicKeyHex,
                            displayName = effectiveDisplayName,
                            role = ProtocolMemberInfo.ROLE_MEMBER,
                            upiId = effectiveUpi,
                            phone = effectivePhone,
                            claimedGhostPubkey = ghostMatch?.pubkey,
                            invitationGrant = grant
                        )
                        val (currentEpoch, activeKey) = getActiveEpochAndKey(inviteData.groupId, inviteData.groupKeyBase64)
                        val op = OperationSigner.createAndSign(
                            protocolVersion = ProtocolVersion.CURRENT,
                            groupId = inviteData.groupId,
                            authorPublicKey = identity.publicKeyHex,
                            privateKeyHex = privKeyHex,
                            parents = parents,
                            logicalClock = nextClock,
                            payload = memberPayload,
                            keyEpoch = currentEpoch
                        )
                        val outboxOp = commitOperationDurableAtomic(op, activeKey)
                        outboxOp
                    } else {
                        null
                    }
                } else {
                    null
                }

                Pair(joinBlock, joinV2Operation)
            }

            // Start monitoring Nostr relays for the group's ledger blocks
            syncManager.monitorGroup(inviteData.groupId)

            if (mintedJoinBlock != null) {
                syncManager.broadcastBlock(mintedJoinBlock, inviteData.groupKeyBase64)
            }

            if (joinV2Operation != null) {
                syncManager.transmitOutboxOperation(joinV2Operation)
                syncManager.reconcileV2Group(inviteData.groupId)
            }

            // Trigger full chain sync with peers
            syncManager.syncGroupNow(inviteData.groupId)

            Result.success(inviteData.groupId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractExpenses(blocks: List<Block>): List<ExpensePayload> {
        return blocks.filter { it.blockType == BlockType.EXPENSE }.mapNotNull { decodeExpense(it) }
    }

    private fun extractVoidedExpenseIds(blocks: List<Block>): Set<String> {
        return blocks.filter { it.blockType == BlockType.EXPENSE_VOID }.mapNotNull {
            decodeVoid(it)?.targetExpenseId
        }.toSet()
    }

    private fun extractSettlements(blocks: List<Block>): List<SettlementPayload> {
        return blocks.filter { it.blockType == BlockType.SETTLEMENT }.mapNotNull { decodeSettlement(it) }
    }
}
