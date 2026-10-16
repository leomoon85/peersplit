package com.peersplit.app.util

import android.content.Context
import android.content.Intent
import com.peersplit.app.data.repository.GroupDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportUtil {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun sanitizeCsvField(input: String): String {
        val escaped = input.replace("\"", "\"\"")
        return if (escaped.isNotEmpty() && escaped[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
            "'$escaped"
        } else {
            escaped
        }
    }

    /**
     * Converts a group's expense ledger into a clean CSV format.
     */
    fun generateCsv(groupDetail: GroupDetail): String {
        val sb = StringBuilder()
        sb.append("Block Index,Date,Category,Description,Payer,Total Amount,Currency,Your Share\n")

        for (exp in groupDetail.expenses) {
            val dateStr = dateFormat.format(Date(exp.timestamp))
            val safeCat = sanitizeCsvField(exp.category)
            val safeDesc = sanitizeCsvField(exp.description)
            val safePayer = sanitizeCsvField(exp.payerName)
            val totalStr = String.format(Locale.US, "%.2f", exp.totalAmount)
            val shareStr = String.format(Locale.US, "%.2f", exp.userShare)

            sb.append("${exp.blockIndex},\"$dateStr\",\"$safeCat\",\"$safeDesc\",\"$safePayer\",$totalStr,${exp.currency},$shareStr\n")
        }

        return sb.toString()
    }

    /**
     * Converts a group's settlement plan and expenses into a readable text summary.
     */
    fun generateTextStatement(groupDetail: GroupDetail): String {
        val group = groupDetail.group
        val sym = CurrencyUtil.getSymbol(group.currency)
        val sb = StringBuilder()

        sb.append("═══════════════════════════════════════\n")
        sb.append("📊 PEERSPLIT GROUP STATEMENT\n")
        sb.append("Group: ${group.name}\n")
        sb.append("Currency: ${group.currency} ($sym)\n")
        sb.append("Total Blocks in Chain: ${group.blockCount}\n")
        sb.append("Generated: ${dateFormat.format(Date())}\n")
        sb.append("═══════════════════════════════════════\n\n")

        sb.append("🤝 SIMPLIFIED SETTLEMENT PLAN:\n")
        if (groupDetail.settlements.isEmpty()) {
            sb.append("• All debts settled! Nobody owes anything.\n")
        } else {
            val memberMap = groupDetail.members.associate { it.pubkey to it.displayName }
            for (stl in groupDetail.settlements) {
                val debtor = memberMap[stl.fromPubkey] ?: "Member (${stl.fromPubkey.take(4)})"
                val creditor = memberMap[stl.toPubkey] ?: "Member (${stl.toPubkey.take(4)})"
                sb.append("• $debtor pays $creditor: ${CurrencyUtil.formatAmount(stl.amount, group.currency)}\n")
            }
        }

        sb.append("\n👥 MEMBER BALANCES:\n")
        for (m in groupDetail.members) {
            val bal = CurrencyUtil.formatBalance(m.netBalance, group.currency)
            sb.append("• ${m.displayName}: $bal\n")
        }

        sb.append("\n📜 ITEMIZED EXPENSE HISTORY (${groupDetail.expenses.size} items):\n")
        for (exp in groupDetail.expenses) {
            val date = dateFormat.format(Date(exp.timestamp))
            val amountFormatted = CurrencyUtil.formatAmount(exp.totalAmount, exp.currency)
            sb.append("[Block #${exp.blockIndex} • $date] [${exp.category}] ${exp.description}\n")
            sb.append("  Paid by: ${exp.payerName} • Total: $amountFormatted\n")
        }

        sb.append("\n🔒 Cryptographically verified on immutable micro-blockchain ledger.\n")
        return sb.toString()
    }

    /**
     * Triggers Android share sheet to export statement via WhatsApp, Email, Drive, etc.
     */
    fun shareStatement(context: Context, groupDetail: GroupDetail, asCsv: Boolean = false) {
        val textToSend = if (asCsv) generateCsv(groupDetail) else generateTextStatement(groupDetail)
        val title = if (asCsv) "${groupDetail.group.name}_ledger.csv" else "PeerSplit Statement - ${groupDetail.group.name}"

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToSend)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
            type = if (asCsv) "text/csv" else "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, "Share Ledger Statement via")
        context.startActivity(chooser)
    }
}
