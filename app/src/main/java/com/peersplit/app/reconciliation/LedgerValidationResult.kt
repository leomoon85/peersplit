package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.SignedOperation

/**
 * Trusted anchor defining a group's immutable genesis identity and authorization policy.
 */
data class TrustedGenesis(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val creatorPublicKey: String,
    val genesisOperationId: String? = null
)

/**
 * Reasons an operation was rejected by the deterministic reconciliation engine.
 */
enum class RejectionReason {
    UNSUPPORTED_PROTOCOL_VERSION,
    GROUP_ID_MISMATCH,
    INVALID_ENVELOPE_OR_PAYLOAD,
    INVALID_OPERATION_ID,
    INVALID_SIGNATURE,
    CAUSAL_CYCLE_DETECTED,
    NON_MONOTONIC_LOGICAL_CLOCK,
    PARENT_REJECTED,
    MULTIPLE_GENESIS_OPERATIONS,
    UNAUTHORIZED_AUTHOR,
    UNAUTHORIZED_REMOVED_MEMBER,
    UNAUTHORIZED_ROLE_CHANGE,
    UNKNOWN_MEMBER_IN_FINANCIAL_OP,
    CURRENCY_MISMATCH,
    DUPLICATE_EXPENSE_ID,
    UNKNOWN_EXPENSE_ID,
    REVISION_MISMATCH_CONFLICT,
    EXPENSE_ALREADY_VOIDED,
    DUPLICATE_SETTLEMENT_ID,
    ARITHMETIC_OVERFLOW,
    INVALID_INVITATION,
    EXPIRED_INVITATION,
    REPLAYED_INVITATION
}

data class RejectedOperation(
    val operation: SignedOperation,
    val reason: RejectionReason,
    val detail: String
)

data class PendingOperation(
    val operation: SignedOperation,
    val missingParentIds: Set<String>
)

data class ConflictRecord(
    val targetEntityId: String,
    val winningOperationId: String,
    val losingOperationId: String,
    val description: String
)

/**
 * Materialized state of an expense after applying all accepted creations, updates, and voids.
 */
data class ReconciledExpense(
    val expenseId: String,
    val originOperationId: String,
    val currentRevisionOperationId: String,
    val authorPublicKey: String,
    val logicalClock: Long,
    val createdAtMillis: Long,
    val payload: OperationPayload.ExpenseCreated,
    val isVoided: Boolean = false,
    val voidedByOperationId: String? = null,
    val voidReason: String? = null,
    val revisionHistoryOperationIds: List<String> = listOf(originOperationId)
)

/**
 * Materialized state of an accepted settlement.
 */
data class ReconciledSettlement(
    val settlementId: String,
    val operationId: String,
    val authorPublicKey: String,
    val logicalClock: Long,
    val createdAtMillis: Long,
    val payload: OperationPayload.SettlementRecorded
)

/**
 * Proposed min-cash-flow transfer in exact integer minor units.
 */
data class MinorUnitSettlementSuggestion(
    val fromMember: String,
    val toMember: String,
    val amountMinor: Long,
    val currency: String
)

/**
 * Complete, deterministic output of reconciling a group's operation set.
 */
data class ReconciliationResult(
    val groupId: String,
    val currency: String,
    val acceptedOperations: List<SignedOperation>,
    val rejectedOperations: List<RejectedOperation>,
    val pendingOperations: List<PendingOperation>,
    val conflicts: List<ConflictRecord>,
    val effectiveExpenses: Map<String, ReconciledExpense>,
    val effectiveSettlements: Map<String, ReconciledSettlement>,
    val activeMembers: Map<String, ProtocolMemberInfo>,
    val removedMembers: Set<String>,
    val ghostAliases: Map<String, String>,
    val memberBalancesMinor: Map<String, Long>,
    val settlementSuggestions: List<MinorUnitSettlementSuggestion>,
    val currentKeyEpoch: Int,
    val maxLogicalClock: Long,
    val headOperationIds: Set<String>,
    val stateDigest: String
)
