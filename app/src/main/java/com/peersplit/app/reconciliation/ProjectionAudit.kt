package com.peersplit.app.reconciliation

import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.data.local.entity.ExpenseProjectionEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object ProjectionAudit {
    fun matches(result: ReconciliationResult, expenses: List<ExpenseProjectionEntity>, balances: List<BalanceProjectionEntity>): Boolean {
        if (expenses.size != result.effectiveExpenses.size || balances.size != result.memberBalancesMinor.size) return false
        if (balances.any { it.groupId != result.groupId || it.currency != result.currency ||
                it.stateDigest != result.stateDigest || result.memberBalancesMinor[it.memberPublicKey] != it.balanceMinor }) return false
        val serializer = MapSerializer(String.serializer(), Long.serializer())
        return expenses.all { row ->
            val expense = result.effectiveExpenses[row.expenseId] ?: return@all false
            val payload = expense.payload
            try {
                row.groupId == result.groupId && row.originOperationId == expense.originOperationId &&
                    row.currentRevisionOperationId == expense.currentRevisionOperationId && row.authorPublicKey == expense.authorPublicKey &&
                    row.logicalClock == expense.logicalClock && row.amountMinor == payload.amountMinor && row.currency == payload.currency &&
                    row.paidBy == payload.paidBy && Json.decodeFromString(serializer, row.payersJson) == payload.payers &&
                    Json.decodeFromString(serializer, row.splitsJson) == payload.splits && row.description == payload.description &&
                    row.category == payload.category && row.note == payload.note && row.splitType == payload.splitType &&
                    row.expenseDateMillis == payload.expenseDateMillis.takeIf { it > 0L }.let { it ?: expense.createdAtMillis } &&
                    row.isVoided == expense.isVoided && row.voidedByOperationId == expense.voidedByOperationId && row.voidReason == expense.voidReason
            } catch (_: Exception) { false }
        }
    }
}
