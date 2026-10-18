package com.peersplit.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.crypto.QrCodeUtil
import java.util.Locale

data class PickedContact(
    val name: String,
    val phone: String
)

object UpiUtil {

    val COMMON_UPI_HANDLES = listOf("@ybl", "@okaxis", "@okicici", "@oksbi", "@okhdfcbank", "@paytm", "@upi")

    /**
     * Builds a permission-free system Contact Phone Picker intent.
     */
    fun createPickContactPhoneIntent(): Intent {
        return Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
    }

    /**
     * Reads (displayName, normalizedPhone) from the URI returned by [createPickContactPhoneIntent].
     * Requires zero runtime permissions because the system picker grants temporary URI read access.
     */
    fun readContactFromPickerUri(context: Context, uri: Uri?): PickedContact? {
        if (uri == null) return null
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNumber = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val cleanNumber = KeyManager.normalizePhone(rawNumber)
                    if (name.isNotBlank() || cleanNumber.isNotBlank()) {
                        PickedContact(name, cleanNumber)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

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
        require(UpiAddress.isValid(upiId)) { "Enter a valid UPI ID" }
        require(amount.isFinite() && amount > 0) { "Enter a valid amount" }
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
        currency: String = "INR",
        note: String = "PeerSplit Settlement"
    ): Boolean {
        if (!currency.equals("INR", ignoreCase = true)) {
            Toast.makeText(context, "UPI payments only support INR currency", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!UpiAddress.isValid(upiId) || !amount.isFinite() || amount <= 0) {
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
        if (!UpiAddress.isValid(upiId) || !amount.isFinite() || amount <= 0) return null
        val uri = buildUpiUri(upiId, payeeName, amount, note).toString()
        return try {
            QrCodeUtil.generateQrBitmap(uri, size, size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Shares a polite 1-tap payment reminder via WhatsApp / SMS / Share Sheet.
     * If debtorPhone is known and no QR image needs to be attached, opens WhatsApp chat directly
     * to that number (falling back to the share sheet if WhatsApp is not installed).
     */
    fun sharePaymentReminder(
        context: Context,
        debtorName: String,
        creditorName: String,
        groupName: String,
        amount: Double,
        currency: String,
        creditorUpiId: String? = null,
        creditorPhone: String? = null,
        debtorPhone: String? = null
    ) {
        val formattedAmt = CurrencyUtil.formatAmount(amount, currency)
        val isUpiEligible = currency.equals("INR", ignoreCase = true) && !creditorUpiId.isNullOrBlank()
        val cleanCreditorPhone = KeyManager.normalizePhone(creditorPhone)
        val cleanDebtorPhone = KeyManager.normalizePhone(debtorPhone)

        val sb = StringBuilder()
        sb.append("Hi $debtorName! 👋\n\n")
        sb.append("Friendly reminder for our *$groupName* expenses on PeerSplit:\n")
        sb.append("• Amount due to $creditorName: *$formattedAmt*\n")

        var qrBitmap: Bitmap? = null
        if (isUpiEligible) {
            sb.append("• Pay via UPI ID: ${creditorUpiId!!.trim()}\n")
            qrBitmap = generateUpiQrBitmap(creditorUpiId, creditorName, amount, "PeerSplit: $groupName", 600)
            if (qrBitmap != null) {
                sb.append("• Or scan the attached QR code with any UPI app (GPay, PhonePe, Paytm)\n")
            }
        }
        if (cleanCreditorPhone.length == 10) {
            sb.append("• GPay / PhonePe Number: $cleanCreditorPhone\n")
        }

        sb.append("\nThanks! 🙏")
        val messageText = sb.toString()

        // If we have a 10-digit debtor phone number and no QR image attachment is required,
        // try jumping straight into their WhatsApp chat!
        if (cleanDebtorPhone.length == 10 && qrBitmap == null) {
            try {
                val waUri = Uri.parse("https://wa.me/91$cleanDebtorPhone?text=${Uri.encode(messageText)}")
                val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(waIntent)
                return
            } catch (_: Exception) {
                // Fall back to ShareUtil below
            }
        }

        ShareUtil.shareTextWithImage(
            context = context,
            text = messageText,
            image = qrBitmap,
            imageName = "upi_payment_qr",
            chooserTitle = "Send Payment Reminder to $debtorName"
        )
    }
}
