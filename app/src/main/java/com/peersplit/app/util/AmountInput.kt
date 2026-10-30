package com.peersplit.app.util

object AmountInput {

    private val AMOUNT_REGEX = Regex("""^\d{0,9}([.,]\d{0,2})?$""")
    private val RATIO_REGEX = Regex("""^\d{0,6}([.,]\d{0,2})?$""")

    /**
     * Filters live amount typing to prevent invalid inputs (e.g., negative numbers, multiple dots/commas,
     * or more than 2 decimal places). Automatically allows typing smoothly and falls back to old value if invalid.
     */
    fun filterAmountInput(newText: String, oldText: String): String {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return ""
        return if (AMOUNT_REGEX.matches(trimmed)) trimmed else oldText
    }

    /**
     * Filters ratio / share / percentage input.
     */
    fun filterRatioInput(newText: String, oldText: String): String {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return ""
        return if (RATIO_REGEX.matches(trimmed)) trimmed else oldText
    }

    /**
     * Parses the string to a Double, normalizing commas to dots.
     */
    fun parseAmount(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val normalized = text.trim().replace(',', '.')
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
}
