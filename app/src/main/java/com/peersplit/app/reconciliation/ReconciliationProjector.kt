package com.peersplit.app.reconciliation

import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.data.local.entity.ExpenseProjectionEntity
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import com.peersplit.app.protocol.OperationEntityMapper
import com.peersplit.app.protocol.ProtocolVersion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

import android.util.Log
import androidx.room.withTransaction

/**
 * Persists the output of [ReconciliationResult] into Room database tables
 * (`expense_projections`, `balance_projections`, `group_protocol_metadata`, `signed_operations`).
 * All updates are executed within an atomic Room transaction with stale-state overwrite prevention.
 */
object ReconciliationProjector {

    private const val TAG = "ReconciliationProjector"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stringLongMapSerializer = MapSerializer(String.serializer(), Long.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    suspend fun persistReconciliationResult(
        database: AppDatabase,
        result: ReconciliationResult,
        forceRebuild: Boolean = false
    ): Boolean {
        val groupId = result.groupId

        return database.withTransaction {
            // 1. Update status on signed_operations table unconditionally (A27)
            for (op in result.acceptedOperations) {
                database.signedOperationDao().updateOperationStatus(
                    operationId = op.operationId,
                    status = SignedOperationEntity.STATUS_ACCEPTED,
                    rejectionReason = null
                )
            }
            for (rej in result.rejectedOperations) {
                database.signedOperationDao().updateOperationStatus(
                    operationId = rej.operation.operationId,
                    status = SignedOperationEntity.STATUS_REJECTED,
                    rejectionReason = "${rej.reason}: ${rej.detail}"
                )
            }
            for (pend in result.pendingOperations) {
                database.signedOperationDao().updateOperationStatus(
                    operationId = pend.operation.operationId,
                    status = SignedOperationEntity.STATUS_PENDING,
                    rejectionReason = "Waiting for parents: ${pend.missingParentIds.joinToString(",")}"
                )
            }

            // 1b. Materialize Member Projections (A11)
            val existingMembers = database.memberDao().getMembersForGroupSync(groupId).associateBy { it.pubkey.lowercase() }
            val membersToInsert = mutableListOf<com.peersplit.app.data.local.entity.MemberEntity>()
            for ((pubkey, info) in result.activeMembers) {
                val existing = existingMembers[pubkey.lowercase()]
                val displayName = when {
                    info.displayName.isNotBlank() -> info.displayName
                    existing != null && existing.displayName.isNotBlank() -> existing.displayName
                    else -> "Member ${pubkey.take(8)}"
                }
                membersToInsert.add(
                    com.peersplit.app.data.local.entity.MemberEntity(
                        id = existing?.id ?: 0,
                        groupId = groupId,
                        pubkey = pubkey,
                        displayName = displayName,
                        upiId = existing?.upiId,
                        phone = existing?.phone
                    )
                )
            }
            if (membersToInsert.isNotEmpty()) {
                database.memberDao().insertMembers(membersToInsert)
            }
            for (removedPubkey in result.removedMembers) {
                database.memberDao().deleteMember(groupId, removedPubkey)
            }
            for (ghostPubkey in result.ghostAliases.keys) {
                database.memberDao().deleteMember(groupId, ghostPubkey)
            }

            val existingMeta = database.groupProtocolDao().getGroupProtocolSync(groupId)
            if (!forceRebuild && existingMeta != null && existingMeta.latestStateDigest == result.stateDigest && existingMeta.maxLogicalClock == result.maxLogicalClock) {
                val existingExpensesCount = database.projectionDao().getExpenseProjectionsForGroupSync(groupId).size
                val existingBalancesCount = database.projectionDao().getBalanceProjectionsForGroupSync(groupId).size
                val projectionsComplete = existingExpensesCount >= result.effectiveExpenses.size &&
                    existingBalancesCount >= result.memberBalancesMinor.size
                if (projectionsComplete) {
                    return@withTransaction true
                }
            }

            // 2. Materialize Expense Projections
            val expenseEntities = result.effectiveExpenses.values.map { exp ->
                val payload = exp.payload
                val payersJson = json.encodeToString(stringLongMapSerializer, payload.payers)
                val splitsJson = json.encodeToString(stringLongMapSerializer, payload.splits)
                ExpenseProjectionEntity(
                    groupId = groupId,
                    expenseId = exp.expenseId,
                    originOperationId = exp.originOperationId,
                    currentRevisionOperationId = exp.currentRevisionOperationId,
                    authorPublicKey = exp.authorPublicKey,
                    logicalClock = exp.logicalClock,
                    amountMinor = payload.amountMinor,
                    currency = payload.currency,
                    paidBy = payload.paidBy,
                    payersJson = payersJson,
                    splitsJson = splitsJson,
                    description = payload.description,
                    category = payload.category,
                    note = payload.note,
                    splitType = payload.splitType,
                    expenseDateMillis = if (payload.expenseDateMillis > 0L) payload.expenseDateMillis else exp.createdAtMillis,
                    isVoided = exp.isVoided,
                    voidedByOperationId = exp.voidedByOperationId,
                    voidReason = exp.voidReason
                )
            }
            database.projectionDao().deleteExpenseProjectionsForGroup(groupId)
            if (expenseEntities.isNotEmpty()) {
                database.projectionDao().insertExpenseProjections(expenseEntities)
            }

            // 3. Materialize Balance Projections
            val balanceEntities = result.memberBalancesMinor.map { (pubkey, balMinor) ->
                BalanceProjectionEntity(
                    groupId = groupId,
                    memberPublicKey = pubkey,
                    balanceMinor = balMinor,
                    currency = result.currency,
                    stateDigest = result.stateDigest,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }
            database.projectionDao().deleteBalanceProjectionsForGroup(groupId)
            if (balanceEntities.isNotEmpty()) {
                database.projectionDao().insertBalanceProjections(balanceEntities)
            }

            // 4. Update Group Protocol Metadata
            val headOpsJson = json.encodeToString(stringListSerializer, result.headOperationIds.toList())
            val updatedMeta = GroupProtocolEntity(
                groupId = groupId,
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                genesisOperationId = existingMeta?.genesisOperationId ?: result.acceptedOperations.firstOrNull { it.parents.isEmpty() }?.operationId,
                currentKeyEpoch = result.currentKeyEpoch,
                latestStateDigest = result.stateDigest,
                maxLogicalClock = result.maxLogicalClock,
                headOperationIdsJson = headOpsJson,
                v1MigrationStatus = existingMeta?.v1MigrationStatus ?: GroupProtocolEntity.MIGRATION_NONE,
                v1MigratedTipHash = existingMeta?.v1MigratedTipHash,
                updatedAtMillis = System.currentTimeMillis()
            )
            database.groupProtocolDao().upsertGroupProtocol(updatedMeta)
            true
        }
    }
}
