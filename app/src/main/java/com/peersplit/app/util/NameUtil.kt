package com.peersplit.app.util

object NameUtil {

    /**
     * Extracts a clean 1-2 character monogram/initials from a display name.
     * Examples:
     * - "Rajesh Baakrishnan" -> "RB"
     * - "Rajesh Balakrishnan" -> "RB"
     * - "Alice" -> "A"
     * - "John Doe Jr." -> "JD"
     * - "" or blank -> "?"
     */
    fun getInitials(name: String?): String {
        if (name.isNullOrBlank()) return "?"

        val tokens = name.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            tokens.isEmpty() -> "?"
            tokens.size == 1 -> {
                val token = tokens[0]
                token.take(1).uppercase()
            }
            else -> {
                val first = tokens.first().take(1)
                val second = tokens.last().take(1)
                "${first}${second}".uppercase()
            }
        }
    }
}
