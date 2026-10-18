package com.peersplit.app.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.regex.Pattern
import kotlin.coroutines.resume

data class ParsedReceipt(
    val suggestedTitle: String,
    val suggestedAmount: Double?,
    val fullText: String
)

object ReceiptScannerUtil {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val amountRegex = Pattern.compile(
        """(?:total|amount|grand\s*total|net|balance|inr|rs\.?|\$|€|£)?\s*[:=-]?\s*([0-9]{1,6}(?:[.,][0-9]{2}))""",
        Pattern.CASE_INSENSITIVE
    )

    private val fallbackNumberRegex = Pattern.compile(
        """\b([0-9]{1,6}[.,][0-9]{2})\b"""
    )

    /**
     * Processes receipt image from Uri completely on-device without cloud connectivity.
     */
    suspend fun scanReceipt(context: Context, imageUri: Uri): ParsedReceipt? {
        val inputImage = try {
            InputImage.fromBitmap(ImageInput.decode(context, imageUri), 0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { return null }
        return processInputImage(inputImage)
    }

    /**
     * Processes receipt image from Bitmap on-device.
     */
    suspend fun scanReceipt(bitmap: Bitmap): ParsedReceipt? {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return processInputImage(inputImage)
    }

    private suspend fun processInputImage(image: InputImage): ParsedReceipt? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val lines = visionText.textBlocks.flatMap { it.lines.map { line -> line.text.trim() } }

                    var bestAmount: Double? = null
                    var highestScore = -1

                    // Scan lines for amounts, giving higher priority to lines mentioning "total" or "grand total"
                    for (line in lines) {
                        val lower = line.lowercase()
                        val matcher = amountRegex.matcher(lower)
                        if (matcher.find()) {
                            val numStr = matcher.group(1)?.replace(",", ".")
                            val parsed = numStr?.toDoubleOrNull()
                            if (parsed != null && parsed > 0) {
                                val score = when {
                                    "grand total" in lower -> 100
                                    "total" in lower -> 80
                                    "net" in lower || "amount" in lower -> 60
                                    "subtotal" in lower -> 40
                                    else -> 20
                                }
                                if (score > highestScore) {
                                    highestScore = score
                                    bestAmount = parsed
                                }
                            }
                        }
                    }

                    // Fallback: If no explicit keyword was found, find the largest decimal number in receipt
                    if (bestAmount == null) {
                        val allNumbers = mutableListOf<Double>()
                        for (line in lines) {
                            val matcher = fallbackNumberRegex.matcher(line)
                            while (matcher.find()) {
                                matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()?.let {
                                    if (it in 1.0..1000000.0) allNumbers.add(it)
                                }
                            }
                        }
                        bestAmount = allNumbers.maxOrNull()
                    }

                    // Candidate title: Pick the first sensible text line (vendor name usually appears at the top)
                    val candidateVendor = lines.firstOrNull { line ->
                        line.length in 3..40 &&
                                !line.contains(Regex("""\d{2}[/-]\d{2}[/-]\d{2,4}""")) && // not a date
                                !line.lowercase().contains("invoice") &&
                                !line.lowercase().contains("receipt") &&
                                !line.lowercase().contains("welcome") &&
                                !line.lowercase().contains("tax") &&
                                !line.lowercase().contains("cashier")
                    } ?: "Receipt Expense"

                    cont.resume(
                        ParsedReceipt(
                            suggestedTitle = candidateVendor,
                            suggestedAmount = bestAmount,
                            fullText = fullText
                        )
                    )
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
}
