package com.peersplit.app.reconciliation

import android.content.Context
import android.util.Log
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.ExpensePayload
import com.peersplit.app.blockchain.ExpenseVoidPayload
import com.peersplit.app.blockchain.MemberJoinedPayload
import com.peersplit.app.blockchain.SettlementPayload
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import com.peersplit.app.network.sync.P2PSyncManager
import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.protocol.OperationEntityMapper
import com.peersplit.app.protocol.OperationId
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation
import kotlinx.serialization.json.Json

/**
 * Migration manager that safely upgrades legacy V1 linear blockchain histories into the V2
 * immutable signed-operation DAG via an explicit [OperationPayload.LegacyV1Checkpoint].
 *
 * Invariants:
 * 1. Zero destructive data loss: Legacy V1 BlockEntity rows are never deleted or modified.
 * 2. Cryptographic signature integrity: Historical authors' signatures are never forged or re-signed.
 * 3. The checkpoint operation is authored and cryptographically signed by the migrating peer,
 *    attesting to the verified V1 tip block hash and deterministic balance state.
 */
class V1ToV2MigrationManager(
    private val context: Context,
    private val database: AppDatabase,
    private val syncManager: P2PSyncManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "V1ToV2Migration"
    }

    data class MigrationResult(
        val groupId: String,
        val isMigrated: Boolean,
        val checkpointOperationId: String?,
        val migratedExpenseCount: Int,
        val migratedSettlementCount: Int,
        val legacyChainVerified: Boolean,
        val message: String
    )

    suspend fun isGroupMigrated(groupId: String): Boolean {
        val meta = database.groupProtocolDao().getGroupProtocolSync(groupId)
        if (meta != null && meta.protocolVersion >= ProtocolVersion.V2_SIGNED_OPERATIONS) {
            return true
        }
        val ops = database.signedOperationDao().getOperationsForGroupSync(groupId)
        return ops.isNotEmpty()
    }

    /**
     * Inspects legacy V1 chain for [groupId], verifies integrity, and generates a signed V2
     * [LegacyV1Checkpoint] operation if the group has not already migrated.
     */
    suspend fun migrateGroupToV2(groupId: String): MigrationResult {
        val groupEntity = database.groupDao().getGroupByIdSync(groupId)
            ?: return MigrationResult(groupId, false, null, 0, 0, false, "Group not found")

        val existingOps = database.signedOperationDao().getOperationsForGroupSync(groupId)
        if (existingOps.isNotEmpty()) {
            val rootOp = existingOps.firstOrNull {
                it.payloadType == "GENESIS_CREATED" || it.payloadType == "LEGACY_V1_CHECKPOINT"
            }
            return MigrationResult(
                groupId = groupId,
                isMigrated = true,
                checkpointOperationId = rootOp?.operationId,
                migratedExpenseCount = 0,
                migratedSettlementCount = 0,
                legacyChainVerified = true,
                message = "Group already migrated to V2"
            )
        }

        val blockEntities = database.blockDao().getBlocksForGroupSync(groupId)
        if (blockEntities.isEmpty()) {
            return MigrationResult(groupId, false, null, 0, 0, false, "No V1 blocks found to migrate")
        }

        val domainBlocks = blockEntities.map { it.toDomainBlock() }
        val isChainVerified = BlockchainEngine.verifyStructuralChain(domainBlocks)

        val memberEntities = database.memberDao().getMembersForGroupSync(groupId)
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)

        // Parse V1 blocks
        val expensePayloads = mutableListOf<ExpensePayload>()
        val settlementPayloads = mutableListOf<SettlementPayload>()
        val voidedExpenseIds = mutableSetOf<String>()
        val ghostAliases = mutableMapOf<String, String>()

        for (block in domainBlocks) {
            try {
                when (block.blockType) {
                    BlockType.EXPENSE -> {
                        val exp = json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
                        expensePayloads.add(exp)
                    }
                    BlockType.SETTLEMENT -> {
                        val stl = json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
                        settlementPayloads.add(stl)
                    }
                    BlockType.EXPENSE_VOID -> {
                        val voidP = json.decodeFromString(ExpenseVoidPayload.serializer(), block.payloadJson)
                        voidedExpenseIds.add(voidP.targetExpenseId)
                    }
                    BlockType.MEMBER_JOIN -> {
                        val joinP = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                        if (!joinP.claimedGhostPubkey.isNullOrBlank() && joinP.claimedGhostPubkey != joinP.pubkey) {
                            ghostAliases[joinP.claimedGhostPubkey] = joinP.pubkey
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error decoding block payload during migration: ${e.message}")
            }
        }

        // Convert protocol members
        val protocolMembers = memberEntities.map { m ->
            ProtocolMemberInfo(
                publicKey = m.pubkey,
                displayName = m.displayName,
                role = if (m.pubkey == groupEntity.creatorPubkey) ProtocolMemberInfo.ROLE_ADMIN else ProtocolMemberInfo.ROLE_MEMBER,
                upiId = m.upiId,
                phone = m.phone
            )
        }

        // Convert active expenses to V2 integer minor units
        val replacedExpenseIds = expensePayloads.mapNotNull { it.editedFromExpenseId }.toSet()
        val currency = groupEntity.currency

        val v2Expenses = mutableListOf<OperationPayload.ExpenseCreated>()
        for (exp in expensePayloads) {
            // Keep all active non-replaced expenses
            if (exp.expenseId in replacedExpenseIds) continue

            val amountMinor = MoneyMath.toMinorUnits(exp.totalAmount)
            val splitsMinor = exp.splits.associate {
                it.userPubkey to MoneyMath.toMinorUnits(it.amount)
            }
            val payersMinor = if (exp.payers.isNotEmpty()) {
                exp.payers.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
            } else {
                mapOf(exp.payerPubkey to amountMinor)
            }

            v2Expenses.add(
                OperationPayload.ExpenseCreated(
                    expenseId = exp.expenseId,
                    amountMinor = amountMinor,
                    currency = currency,
                    paidBy = exp.payerPubkey,
                    splits = splitsMinor,
                    description = exp.description,
                    payers = payersMinor,
                    category = exp.category,
                    note = exp.note,
                    splitType = exp.splitType,
                    expenseDateMillis = exp.expenseDateMillis ?: 0L
                )
            )
        }

        // Convert settlements to V2 integer minor units
        val v2Settlements = settlementPayloads.map { stl ->
            OperationPayload.SettlementRecorded(
                settlementId = stl.settlementId,
                fromMember = stl.payerPubkey,
                toMember = stl.payeePubkey,
                amountMinor = MoneyMath.toMinorUnits(stl.amount),
                currency = currency,
                note = stl.note
            )
        }

        val tipBlock = domainBlocks.last()
        val checkpointPayload = OperationPayload.LegacyV1Checkpoint(
            v1TipBlockHash = tipBlock.blockHash,
            v1BlockCount = domainBlocks.size.toLong(),
            isLegacyChainFullyVerified = isChainVerified,
            currency = currency,
            members = protocolMembers,
            activeExpenses = v2Expenses,
            voidedExpenseIds = voidedExpenseIds.toList(),
            settlements = v2Settlements,
            ghostAliases = ghostAliases
        )

        val signedOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = identity.publicKeyHex,
            privateKeyHex = privKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = checkpointPayload
        )

        // Store checkpoint operation locally
        val entity = OperationEntityMapper.toEntity(signedOp, status = SignedOperationEntity.STATUS_ACCEPTED)
        database.signedOperationDao().insertOperationIgnore(entity)

        database.groupProtocolDao().upsertGroupProtocol(
            GroupProtocolEntity(
                groupId = groupId,
                protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
                genesisOperationId = signedOp.operationId,
                latestStateDigest = "",
                updatedAtMillis = System.currentTimeMillis()
            )
        )

        // Reconcile into projections and verify acceptance
        val reconciled = syncManager.reconcileV2Group(groupId)
        val checkpointOp = database.signedOperationDao().getOperationByIdSync(signedOp.operationId)
        val isAccepted = checkpointOp?.status == com.peersplit.app.data.local.entity.SignedOperationEntity.STATUS_ACCEPTED
        if (!reconciled || !isAccepted) {
            database.signedOperationDao().deleteOperationsForGroup(groupId)
            database.groupProtocolDao().deleteGroupProtocol(groupId)
            Log.e(TAG, "Migration checkpoint failed reconciliation for group $groupId (reconciled=$reconciled, accepted=$isAccepted)")
            return MigrationResult(
                groupId = groupId,
                isMigrated = false,
                checkpointOperationId = null,
                migratedExpenseCount = 0,
                migratedSettlementCount = 0,
                legacyChainVerified = isChainVerified,
                message = "Migration checkpoint failed reconciliation validation"
            )
        }

        val outboxOp = syncManager.buildOutboxOperation(signedOp, groupEntity.groupKeyBase64)
        database.outboxDao().insertOutboxOperation(outboxOp)

        // Broadcast to peers
        syncManager.broadcastOperation(signedOp, groupEntity.groupKeyBase64)

        Log.i(TAG, "Successfully migrated group $groupId to V2. Checkpoint op: ${signedOp.operationId.take(8)}")
        return MigrationResult(
            groupId = groupId,
            isMigrated = true,
            checkpointOperationId = signedOp.operationId,
            migratedExpenseCount = v2Expenses.size,
            migratedSettlementCount = v2Settlements.size,
            legacyChainVerified = isChainVerified,
            message = "Migration successful"
        )
    }
}
