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
     * UPI details (ID + scannable QR image) are only included for INR groups,
     * since UPI payments are rupee-only. upi:// links are not clickable in most
     * chat apps, so we attach the QR image instead of relying on the link.
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
        val isUpiEligible = currency.equals("INR", ignoreCase = true) && !creditorUpiId.isNullOrBlank()

        val sb = StringBuilder()
        sb.append("Hi $debtorName! 👋\n\n")
        sb.append("Friendly reminder for our *$groupName* expenses on PeerSplit:\n")
        sb.append("• Amount due to $creditorName: *$formattedAmt*\n")

        var qrBitmap: Bitmap? = null
        if (isUpiEligible) {
            sb.append("• Pay via UPI to: ${creditorUpiId!!.trim()}\n")
            qrBitmap = generateUpiQrBitmap(creditorUpiId, creditorName, amount, "PeerSplit: $groupName", 600)
            if (qrBitmap != null) {
                sb.append("• Or scan the attached QR code with any UPI app (GPay, PhonePe, Paytm)\n")
            }
        }

        sb.append("\nThanks! 🙏")

        ShareUtil.shareTextWithImage(
            context = context,
            text = sb.toString(),
            image = qrBitmap,
            imageName = "upi_payment_qr",
            chooserTitle = "Send Payment Reminder to $debtorName"
        )
    }
}
