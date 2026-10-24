package com.peersplit.app.util

/** Formatting normalization only: never discard a country code or infer a country. */
object PhoneNumbers {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val number = raw.split(Regex("(?i)(?:extension|ext\\.?|[x#;])"), limit = 2).first()
        val digits = number.mapNotNull { it.digitToIntOrNull() }.joinToString("")
        return if (digits.startsWith("00")) digits.drop(2) else digits
    }

    fun canMatch(normalized: String): Boolean = normalized.length in 7..15

    /** Existing Indian local numbers and explicit +91 numbers may suggest a UPI address. */
    fun indianMobile(raw: String?): String? {
        val number = normalize(raw)
        val local = if (number.length == 12 && number.startsWith("91")) number.drop(2) else number
        return local.takeIf { it.length == 10 && it.first() in '6'..'9' }
    }

    /** A local number has no unambiguous WhatsApp country code: use the share chooser. */
    fun whatsappNumber(raw: String?): String? {
        val number = normalize(raw)
        return number.takeIf { it.length in 11..15 && !it.startsWith("0") }
    }
}
