package com.peersplit.reconciliation

import com.peersplit.protocol.OperationPayload
import com.peersplit.protocol.SignedOperation

/**
 * Deterministic conflict resolution rules for PeerSplit V2 financial operations.
 */
object OperationConflictResolver {

    sealed class ApplicationOutcome {
        object Applied : ApplicationOutcome()
        data class Rejected(
            val reason: RejectionReason,
            val detail: String,
            val conflict: ConflictRecord? = null
        ) : ApplicationOutcome()
    }

    fun applyExpenseCreated(
        op: SignedOperation,
        payload: OperationPayload.ExpenseCreated,
        groupCurrency: String,
        knownMemberKeys: Set<String>,
        expenses: MutableMap<String, ReconciledExpense>
    ): ApplicationOutcome {
        if (payload.currency.trim().uppercase() != groupCurrency.trim().uppercase()) {
            return ApplicationOutcome.Rejected(
                RejectionReason.CURRENCY_MISMATCH,
                "Expense currency ${payload.currency} does not match group currency $groupCurrency"
            )
        }
        val memberCheck = verifyExpenseMembersKnown(payload, knownMemberKeys)
        if (memberCheck != null) return memberCheck

        val existing = expenses[payload.expenseId]
        if (existing != null) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.DUPLICATE_EXPENSE_ID,
                detail = "Expense ID ${payload.expenseId} already created by operation ${existing.originOperationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = existing.originOperationId,
                    losingOperationId = op.operationId,
                    description = "Duplicate ExpenseCreated for expenseId=${payload.expenseId}"
                )
            )
        }

        expenses[payload.expenseId] = ReconciledExpense(
            expenseId = payload.expenseId,
            originOperationId = op.operationId,
            currentRevisionOperationId = op.operationId,
            authorPublicKey = op.authorPublicKey,
            logicalClock = op.logicalClock,
            createdAtMillis = op.createdAtMillis,
            payload = payload,
            isVoided = false,
            revisionHistoryOperationIds = listOf(op.operationId)
        )
        return ApplicationOutcome.Applied
    }

    fun applyExpenseUpdated(
        op: SignedOperation,
        payload: OperationPayload.ExpenseUpdated,
        groupCurrency: String,
        knownMemberKeys: Set<String>,
        expenses: MutableMap<String, ReconciledExpense>,
        concurrentVoidWinnerOpId: String? = null
    ): ApplicationOutcome {
        if (payload.replacement.currency.trim().uppercase() != groupCurrency.trim().uppercase()) {
            return ApplicationOutcome.Rejected(
                RejectionReason.CURRENCY_MISMATCH,
                "Replacement currency ${payload.replacement.currency} does not match group currency $groupCurrency"
            )
        }
        val memberCheck = verifyExpenseMembersKnown(payload.replacement, knownMemberKeys)
        if (memberCheck != null) return memberCheck

        val existing = expenses[payload.expenseId]
            ?: return ApplicationOutcome.Rejected(
                RejectionReason.UNKNOWN_EXPENSE_ID,
                "Cannot update unknown expenseId=${payload.expenseId}"
            )

        if (existing.isVoided) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.EXPENSE_ALREADY_VOIDED,
                detail = "Cannot update expense ${payload.expenseId} because it was voided by ${existing.voidedByOperationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = existing.voidedByOperationId ?: existing.currentRevisionOperationId,
                    losingOperationId = op.operationId,
                    description = "Attempted to update already-voided expense ${payload.expenseId}"
                )
            )
        }

        // If a concurrent ExpenseVoided targets the exact same expectedRevisionId, Void Precedence Policy rejects the edit
        if (concurrentVoidWinnerOpId != null) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.EXPENSE_ALREADY_VOIDED,
                detail = "Concurrent ExpenseVoided ($concurrentVoidWinnerOpId) takes precedence over ExpenseUpdated for revision ${payload.expectedRevisionId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = concurrentVoidWinnerOpId,
                    losingOperationId = op.operationId,
                    description = "Concurrent void precedence over edit on revision ${payload.expectedRevisionId}"
                )
            )
        }

        if (existing.currentRevisionOperationId != payload.expectedRevisionId) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.REVISION_MISMATCH_CONFLICT,
                detail = "CAS revision mismatch for ${payload.expenseId}: expected ${payload.expectedRevisionId}, current is ${existing.currentRevisionOperationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = existing.currentRevisionOperationId,
                    losingOperationId = op.operationId,
                    description = "Concurrent edit conflict on expense ${payload.expenseId} (expected revision ${payload.expectedRevisionId})"
                )
            )
        }

        expenses[payload.expenseId] = existing.copy(
            currentRevisionOperationId = op.operationId,
            logicalClock = op.logicalClock,
            payload = payload.replacement,
            revisionHistoryOperationIds = existing.revisionHistoryOperationIds + op.operationId
        )
        return ApplicationOutcome.Applied
    }

    fun applyExpenseVoided(
        op: SignedOperation,
        payload: OperationPayload.ExpenseVoided,
        expenses: MutableMap<String, ReconciledExpense>
    ): ApplicationOutcome {
        val existing = expenses[payload.expenseId]
            ?: return ApplicationOutcome.Rejected(
                RejectionReason.UNKNOWN_EXPENSE_ID,
                "Cannot void unknown expenseId=${payload.expenseId}"
            )

        if (existing.isVoided) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.EXPENSE_ALREADY_VOIDED,
                detail = "Expense ${payload.expenseId} is already voided by ${existing.voidedByOperationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = existing.voidedByOperationId ?: existing.currentRevisionOperationId,
                    losingOperationId = op.operationId,
                    description = "Duplicate void on expense ${payload.expenseId}"
                )
            )
        }

        if (existing.currentRevisionOperationId != payload.expectedRevisionId) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.REVISION_MISMATCH_CONFLICT,
                detail = "CAS revision mismatch for voiding ${payload.expenseId}: expected ${payload.expectedRevisionId}, current is ${existing.currentRevisionOperationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.expenseId,
                    winningOperationId = existing.currentRevisionOperationId,
                    losingOperationId = op.operationId,
                    description = "Void expected revision ${payload.expectedRevisionId} but current is ${existing.currentRevisionOperationId}"
                )
            )
        }

        expenses[payload.expenseId] = existing.copy(
            currentRevisionOperationId = op.operationId,
            logicalClock = op.logicalClock,
            isVoided = true,
            voidedByOperationId = op.operationId,
            voidReason = payload.reason,
            revisionHistoryOperationIds = existing.revisionHistoryOperationIds + op.operationId
        )
        return ApplicationOutcome.Applied
    }

    fun applySettlementRecorded(
        op: SignedOperation,
        payload: OperationPayload.SettlementRecorded,
        groupCurrency: String,
        knownMemberKeys: Set<String>,
        settlements: MutableMap<String, ReconciledSettlement>
    ): ApplicationOutcome {
        if (payload.currency.trim().uppercase() != groupCurrency.trim().uppercase()) {
            return ApplicationOutcome.Rejected(
                RejectionReason.CURRENCY_MISMATCH,
                "Settlement currency ${payload.currency} does not match group currency $groupCurrency"
            )
        }
        if (payload.fromMember !in knownMemberKeys || payload.toMember !in knownMemberKeys) {
            return ApplicationOutcome.Rejected(
                RejectionReason.UNKNOWN_MEMBER_IN_FINANCIAL_OP,
                "Settlement involves unknown member (${payload.fromMember} -> ${payload.toMember})"
            )
        }

        val existing = settlements[payload.settlementId]
        if (existing != null) {
            return ApplicationOutcome.Rejected(
                reason = RejectionReason.DUPLICATE_SETTLEMENT_ID,
                detail = "Settlement ID ${payload.settlementId} already recorded by ${existing.operationId}",
                conflict = ConflictRecord(
                    targetEntityId = payload.settlementId,
                    winningOperationId = existing.operationId,
                    losingOperationId = op.operationId,
                    description = "Duplicate settlementId=${payload.settlementId}"
                )
            )
        }

        settlements[payload.settlementId] = ReconciledSettlement(
            settlementId = payload.settlementId,
            operationId = op.operationId,
            authorPublicKey = op.authorPublicKey,
            logicalClock = op.logicalClock,
            createdAtMillis = op.createdAtMillis,
            payload = payload
        )
        return ApplicationOutcome.Applied
    }

    private fun verifyExpenseMembersKnown(
        exp: OperationPayload.ExpenseCreated,
        knownMemberKeys: Set<String>
    ): ApplicationOutcome.Rejected? {
        if (exp.paidBy !in knownMemberKeys) {
            return ApplicationOutcome.Rejected(
                RejectionReason.UNKNOWN_MEMBER_IN_FINANCIAL_OP,
                "Expense paidBy ${exp.paidBy} is not a known member of the group"
            )
        }
        for (payerKey in exp.payers.keys) {
            if (payerKey !in knownMemberKeys) {
                return ApplicationOutcome.Rejected(
                    RejectionReason.UNKNOWN_MEMBER_IN_FINANCIAL_OP,
                    "Expense payer $payerKey is not a known member of the group"
                )
            }
        }
        for (splitKey in exp.splits.keys) {
            if (splitKey !in knownMemberKeys) {
                return ApplicationOutcome.Rejected(
                    RejectionReason.UNKNOWN_MEMBER_IN_FINANCIAL_OP,
                    "Expense split participant $splitKey is not a known member of the group"
                )
            }
        }
        return null
    }
}
