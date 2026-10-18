package com.peersplit.app.util

import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.protocol.ProtocolVersion
import kotlin.math.roundToLong

sealed class AmountValidationResult {
    data class Valid(val amountMinor: Long, val majorAmount: Double) : AmountValidationResult()
    object Incomplete : AmountValidationResult()
    data class Invalid(val error: String) : AmountValidationResult()

    val isValid: Boolean get() = this is Valid
}

/**
 * Validates user-entered monetary amounts before any financial math or allocation is attempted.
 * Prevents throwing operations and composition crashes on malformed inputs, scientific notation,
 * sub-cent precision, zero/negative amounts, and overflow values.
 */
object AmountInputValidator {

    private val validAmountPattern = Regex("""^\d+(\.\d{1,2})?$""")

    fun validate(rawText: String?): AmountValidationResult {
        if (rawText.isNullOrBlank()) {
            return AmountValidationResult.Incomplete
        }
        val trimmed = rawText.trim().replace(',', '.')
        if (trimmed.isEmpty()) {
            return AmountValidationResult.Incomplete
        }

        // Allow intermediate editing states like "12." or "."
        if (trimmed == "." || trimmed.endsWith(".")) {
            val prefix = trimmed.removeSuffix(".")
            if (prefix.isEmpty() || prefix.all { it.isDigit() }) {
                return AmountValidationResult.Incomplete
            }
            return AmountValidationResult.Invalid("Invalid amount format")
        }

        // Check for scientific notation, infinity, NaN, or signs
        if (trimmed.contains('e', ignoreCase = true) ||
            trimmed.contains("inf", ignoreCase = true) ||
            trimmed.contains("nan", ignoreCase = true) ||
            trimmed.startsWith("-") ||
            trimmed.startsWith("+")
        ) {
            return AmountValidationResult.Invalid("Invalid amount format")
        }

        // Sub-cent precision check (> 2 decimal places)
        val dotIndex = trimmed.indexOf('.')
        if (dotIndex != -1) {
            val decimals = trimmed.length - dotIndex - 1
            if (decimals > 2) {
                return AmountValidationResult.Invalid("Maximum 2 decimal places allowed")
            }
        }

        if (!validAmountPattern.matches(trimmed)) {
            return AmountValidationResult.Invalid("Invalid amount format")
        }

        val parsedDouble = trimmed.toDoubleOrNull()
        if (parsedDouble == null || !parsedDouble.isFinite() || parsedDouble.isNaN()) {
            return AmountValidationResult.Invalid("Invalid amount format")
        }

        val minor = try {
            val m = (parsedDouble * MoneyMath.MINOR_UNITS_PER_MAJOR).roundToLong()
            if (m <= 0L) {
                return AmountValidationResult.Invalid("Amount must be greater than zero")
            }
            if (m > ProtocolVersion.MAX_AMOUNT_MINOR) {
                return AmountValidationResult.Invalid("Amount exceeds maximum limit")
            }
            m
        } catch (_: Exception) {
            return AmountValidationResult.Invalid("Amount calculation overflow")
        }

        val major = MoneyMath.toMajorDouble(minor)
        return AmountValidationResult.Valid(amountMinor = minor, majorAmount = major)
    }
}
