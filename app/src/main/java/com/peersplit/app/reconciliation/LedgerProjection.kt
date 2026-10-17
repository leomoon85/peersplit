package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.min

/**
 * Pure integer minor-unit balance computation, debt simplification, and canonical state digest generation.
 */
object LedgerProjection {

    /**
     * Computes exact net balances in integer minor units (`Long`) for all group members.
     * - Zero floating-point operations.
     * - Uses `Math.addExact` / `Math.subtractExact` to guarantee arithmetic overflow is caught.
     * - Resolves any claimed ghost member public keys via `ghostAliases`.
     * - Guarantees the double-entry accounting invariant: `sum(balances.values) == 0L`.
     */
    fun calculateBalancesMinor(
        effectiveExpenses: Map<String, ReconciledExpense>,
        effectiveSettlements: Map<String, ReconciledSettlement>,
        knownMembers: Set<String>,
        ghostAliases: Map<String, String>
    ): Map<String, Long> {
        val resolve = { pk: String ->
            var curr = pk.trim().lowercase()
            var hops = 0
            while (curr in ghostAliases && hops < 8) {
                val next = ghostAliases[curr]!!
                if (next == curr) break
                curr = next
                hops++
            }
            curr
        }

        val balances = LinkedHashMap<String, Long>()
        for (memberKey in knownMembers.map(resolve).sorted()) {
            balances[memberKey] = 0L
        }

        // 1. Apply non-voided expenses in deterministic sorted order of expenseId
        for (expId in effectiveExpenses.keys.sorted()) {
            val reconciled = effectiveExpenses[expId] ?: continue
            if (reconciled.isVoided) continue
            val exp = reconciled.payload

            if (exp.payers.isNotEmpty()) {
                for ((payerRaw, paidMinor) in exp.payers.entries.sortedBy { it.key }) {
                    val payer = resolve(payerRaw)
                    balances[payer] = Math.addExact(balances[payer] ?: 0L, paidMinor)
                }
            } else {
                val payer = resolve(exp.paidBy)
                balances[payer] = Math.addExact(balances[payer] ?: 0L, exp.amountMinor)
            }

            for ((splitMemberRaw, owedMinor) in exp.splits.entries.sortedBy { it.key }) {
                val member = resolve(splitMemberRaw)
                balances[member] = Math.subtractExact(balances[member] ?: 0L, owedMinor)
            }
        }

        // 2. Apply settlements in deterministic sorted order of settlementId
        for (stlId in effectiveSettlements.keys.sorted()) {
            val reconciled = effectiveSettlements[stlId] ?: continue
            val stl = reconciled.payload
            val from = resolve(stl.fromMember)
            val to = resolve(stl.toMember)

            // Payer (fromMember) transferred money -> their net balance increases
            balances[from] = Math.addExact(balances[from] ?: 0L, stl.amountMinor)
            // Payee (toMember) received money -> their net balance decreases
            balances[to] = Math.subtractExact(balances[to] ?: 0L, stl.amountMinor)
        }

        // Verify strict zero-sum accounting invariant
        var netSum = 0L
        for (v in balances.values) {
            netSum = Math.addExact(netSum, v)
        }
        check(netSum == 0L) { "Accounting invariant violation: net balances sum to $netSum instead of 0" }

        return balances.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    /**
     * Deterministic greedy min-cash-flow debt simplification in exact integer minor units.
     * Ties in balance amounts are broken deterministically by lexicographical member public key.
     */
    fun simplifyDebtsMinor(
        memberBalancesMinor: Map<String, Long>,
        currency: String
    ): List<MinorUnitSettlementSuggestion> {
        data class Entry(val pubkey: String, var amountMinor: Long)

        val debtors = mutableListOf<Entry>()
        val creditors = mutableListOf<Entry>()

        for ((pubkey, bal) in memberBalancesMinor.entries.sortedBy { it.key }) {
            if (bal < 0L) {
                debtors.add(Entry(pubkey, Math.negateExact(bal)))
            } else if (bal > 0L) {
                creditors.add(Entry(pubkey, bal))
            }
        }

        val entryComparator = Comparator<Entry> { a, b ->
            val amtCmp = b.amountMinor.compareTo(a.amountMinor) // descending by amountMinor
            if (amtCmp != 0) amtCmp else a.pubkey.compareTo(b.pubkey) // ascending by pubkey tie-breaker
        }

        val suggestions = mutableListOf<MinorUnitSettlementSuggestion>()

        while (true) {
            debtors.removeAll { it.amountMinor <= 0L }
            creditors.removeAll { it.amountMinor <= 0L }
            if (debtors.isEmpty() || creditors.isEmpty()) break

            debtors.sortWith(entryComparator)
            creditors.sortWith(entryComparator)

            val debtor = debtors.first()
            val creditor = creditors.first()
            val transferMinor = min(debtor.amountMinor, creditor.amountMinor)

            if (transferMinor > 0L) {
                suggestions.add(
                    MinorUnitSettlementSuggestion(
                        fromMember = debtor.pubkey,
                        toMember = creditor.pubkey,
                        amountMinor = transferMinor,
                        currency = currency
                    )
                )
            }

            debtor.amountMinor -= transferMinor
            creditor.amountMinor -= transferMinor
        }

        return suggestions
    }

    /**
     * Computes a deterministic, domain-separated SHA-256 digest of the entire reconciled ledger state.
     * Any two devices with the same accepted operation set, effective expenses, settlements, membership,
     * and balances are mathematically guaranteed to produce the exact same 64-character hex `stateDigest`.
     */
    fun computeStateDigest(
        groupId: String,
        currency: String,
        acceptedOperations: List<SignedOperation>,
        activeMembers: Map<String, ProtocolMemberInfo>,
        removedMembers: Set<String>,
        effectiveExpenses: Map<String, ReconciledExpense>,
        effectiveSettlements: Map<String, ReconciledSettlement>,
        memberBalancesMinor: Map<String, Long>
    ): String {
        val bos = ByteArrayOutputStream(1024)
        DataOutputStream(bos).use { out ->
            writeUtf8(out, ProtocolVersion.DOMAIN_STATE_DIGEST)
            writeUtf8(out, groupId.trim())
            writeUtf8(out, currency.trim().uppercase())

            // 1. Accepted Operation IDs (sorted lexicographically so digest depends on set + state)
            val sortedOpIds = acceptedOperations.map { it.operationId }.sorted()
            out.writeInt(sortedOpIds.size)
            for (opId in sortedOpIds) {
                writeUtf8(out, opId)
            }

            // 2. Active Members
            val sortedActiveMembers = activeMembers.keys.sorted()
            out.writeInt(sortedActiveMembers.size)
            for (pk in sortedActiveMembers) {
                val m = activeMembers[pk]!!
                writeUtf8(out, pk)
                writeUtf8(out, m.role)
            }

            // 3. Removed Members
            val sortedRemoved = removedMembers.sorted()
            out.writeInt(sortedRemoved.size)
            for (pk in sortedRemoved) {
                writeUtf8(out, pk)
            }

            // 4. Effective Expenses (expenseId, currentRevisionOperationId, isVoided, amountMinor)
            val sortedExpIds = effectiveExpenses.keys.sorted()
            out.writeInt(sortedExpIds.size)
            for (expId in sortedExpIds) {
                val exp = effectiveExpenses[expId]!!
                writeUtf8(out, exp.expenseId)
                writeUtf8(out, exp.currentRevisionOperationId)
                out.writeBoolean(exp.isVoided)
                out.writeLong(exp.payload.amountMinor)
            }

            // 5. Effective Settlements
            val sortedStlIds = effectiveSettlements.keys.sorted()
            out.writeInt(sortedStlIds.size)
            for (stlId in sortedStlIds) {
                val stl = effectiveSettlements[stlId]!!
                writeUtf8(out, stl.settlementId)
                writeUtf8(out, stl.operationId)
                out.writeLong(stl.payload.amountMinor)
            }

            // 6. Member Balances Minor
            val sortedBalanceEntries = memberBalancesMinor.entries.sortedBy { it.key }
            out.writeInt(sortedBalanceEntries.size)
            for ((pk, balMinor) in sortedBalanceEntries) {
                writeUtf8(out, pk)
                out.writeLong(balMinor)
            }
        }
        return HashUtil.sha256Hex(bos.toByteArray())
    }

    private fun writeUtf8(out: DataOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }
}
