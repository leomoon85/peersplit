package com.peersplit.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import com.peersplit.app.crypto.QrCodeUtil
import java.util.Locale

object UpiUtil {

    /**
     * Builds standard NPCI UPI payment URI.
     * Format: upi://pay?pa=address@upi&pn=PayeeName&am=100.00&cu=INR&tn=Note
     */
    fun buildUpiUri(
        upiId: String,
        payeeName: String,
        amount: Double,
        note: String = "PeerSplit Settlement"
    ): Uri {
        val formattedAmount = String.format(Locale.US, "%.2f", amount)
        val uriString = "upi://pay?pa=${Uri.encode(upiId.trim())}" +
                "&pn=${Uri.encode(payeeName.trim())}" +
                "&am=$formattedAmount" +
                "&cu=INR" +
                "&tn=${Uri.encode(note.trim())}"
        return Uri.parse(uriString)
    }

    /**
     * Launches payment via any installed UPI app (Google Pay, PhonePe, Paytm, BHIM, etc.)
     */
    fun launchUpiPayment(
        context: Context,
        upiId: String,
        payeeName: String,
        amount: Double,
        note: String = "PeerSplit Settlement"
    ): Boolean {
        if (upiId.isBlank()) {
            Toast.makeText(context, "No UPI ID provided for this member", Toast.LENGTH_SHORT).show()
            return false
        }
        val uri = buildUpiUri(upiId, payeeName, amount, note)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val chooser = Intent.createChooser(intent, "Pay ₹${String.format(Locale.US, "%.2f", amount)} via UPI")

        return try {
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "No UPI app found on device: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Generates a UPI Dynamic Payment QR code bitmap for scanning.
     */
    fun generateUpiQrBitmap(
        upiId: String,
        payeeName: String,
        amount: Double,
        note: String = "PeerSplit Settlement",
        size: Int = 360
    ): Bitmap? {
        if (upiId.isBlank()) return null
        val uri = buildUpiUri(upiId, payeeName, amount, note).toString()
        return try {
            QrCodeUtil.generateQrBitmap(uri, size, size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Shares a polite 1-tap payment reminder via WhatsApp / SMS / Share Sheet.
     */
    fun sharePaymentReminder(
        context: Context,
        debtorName: String,
        creditorName: String,
        groupName: String,
        amount: Double,
        currency: String,
        creditorUpiId: String? = null
    ) {
        val formattedAmt = CurrencyUtil.formatAmount(amount, currency)
        val sb = StringBuilder()
        sb.append("Hi $debtorName! 👋\n\n")
        sb.append("Friendly reminder for our *$groupName* expenses on PeerSplit:\n")
        sb.append("• Amount due to $creditorName: *$formattedAmt*\n")

        if (!creditorUpiId.isNullOrBlank()) {
            val upiUri = buildUpiUri(creditorUpiId, creditorName, amount, "PeerSplit: $groupName")
            sb.append("• UPI ID: `$creditorUpiId`\n")
            sb.append("• Quick UPI Pay Link: $upiUri\n")
        }

        sb.append("\n🔒 Verified on PeerSplit decentralized ledger.")

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            type = "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, "Send Payment Reminder to $debtorName")
        context.startActivity(chooser)
    }
}
