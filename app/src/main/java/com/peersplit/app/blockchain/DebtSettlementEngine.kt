package com.peersplit.app.blockchain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Proposed settlement transaction to square up balances with minimum number of transfers.
 */
data class SettlementSuggestion(
    val fromPubkey: String,
    val toPubkey: String,
    val amount: Double
)

/**
 * High-performance, zero-server debt simplification engine.
 * Computes net balances across expense and settlement blocks and applies
 * greedy min-cash-flow graph reduction to minimize cross-payments.
 */
object DebtSettlementEngine {

    private const val CENTS_FACTOR = 100.0

    /**
     * Computes the net balance for each user from all valid expense and settlement records.
     * Positive balance = user is owed money (creditor).
     * Negative balance = user owes money (debtor).
     */
    fun calculateNetBalances(
        expenses: List<ExpensePayload>,
        settlements: List<SettlementPayload>,
        voidedExpenseIds: Set<String> = emptySet()
    ): Map<String, Double> {
        // Use integer cents to eliminate floating-point drift
        val balanceCents = mutableMapOf<String, Long>()

        // 1. Process Active (Non-Voided) Expenses
        for (exp in expenses) {
            if (exp.expenseId in voidedExpenseIds) continue

            if (exp.payers.size > 1) {
                for (payerItem in exp.payers) {
                    val paidCents = (payerItem.amount * CENTS_FACTOR).roundToLong()
                    balanceCents[payerItem.userPubkey] = (balanceCents[payerItem.userPubkey] ?: 0L) + paidCents
                }
            } else {
                val payer = exp.payerPubkey
                val totalCents = (exp.totalAmount * CENTS_FACTOR).roundToLong()
                balanceCents[payer] = (balanceCents[payer] ?: 0L) + totalCents
            }

            for (split in exp.splits) {
                val owedCents = (split.amount * CENTS_FACTOR).roundToLong()
                balanceCents[split.userPubkey] = (balanceCents[split.userPubkey] ?: 0L) - owedCents
            }
        }

        // 2. Process Settlements (Direct repayments)
        for (stl in settlements) {
            val stlCents = (stl.amount * CENTS_FACTOR).roundToLong()
            // Payer gave money, so their debt decreases (balance goes up)
            balanceCents[stl.payerPubkey] = (balanceCents[stl.payerPubkey] ?: 0L) + stlCents
            // Payee received money, so their credit decreases (balance goes down)
            balanceCents[stl.payeePubkey] = (balanceCents[stl.payeePubkey] ?: 0L) - stlCents
        }

        return balanceCents.mapValues { it.value / CENTS_FACTOR }
    }

    /**
     * Simplifies debts into minimum number of payments using the greedy Min-Cash-Flow algorithm.
     */
    fun simplifyDebts(netBalances: Map<String, Double>): List<SettlementSuggestion> {
        val suggestions = mutableListOf<SettlementSuggestion>()

        // Convert to mutable lists of debtors and creditors in cents
        data class BalanceEntry(val pubkey: String, var amountCents: Long)

        val debtors = mutableListOf<BalanceEntry>()
        val creditors = mutableListOf<BalanceEntry>()

        for ((pubkey, bal) in netBalances) {
            val cents = (bal * CENTS_FACTOR).roundToLong()
            if (cents < 0) {
                debtors.add(BalanceEntry(pubkey, -cents)) // store positive amount owed
            } else if (cents > 0) {
                creditors.add(BalanceEntry(pubkey, cents))
            }
        }

        // Sort both descending by amount for greedy matching
        debtors.sortByDescending { it.amountCents }
        creditors.sortByDescending { it.amountCents }

        var dIdx = 0
        var cIdx = 0

        while (dIdx < debtors.size && cIdx < creditors.size) {
            val debtor = debtors[dIdx]
            val creditor = creditors[cIdx]

            val settleCents = min(debtor.amountCents, creditor.amountCents)
            if (settleCents > 0) {
                suggestions.add(
                    SettlementSuggestion(
                        fromPubkey = debtor.pubkey,
                        toPubkey = creditor.pubkey,
                        amount = settleCents / CENTS_FACTOR
                    )
                )
            }

            debtor.amountCents -= settleCents
            creditor.amountCents -= settleCents

            if (debtor.amountCents == 0L) dIdx++
            if (creditor.amountCents == 0L) cIdx++
        }

        return suggestions
    }
}
