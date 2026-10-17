package com.peersplit.app.data.model

import androidx.compose.ui.graphics.Color

enum class ExpenseCategory(
    val displayName: String,
    val iconEmoji: String,
    val colorHex: Long
) {
    FOOD("Food & Dining", "🍔", 0xFFFF7043),
    TRANSPORT("Travel & Fuel", "🚗", 0xFF42A5F5),
    GROCERIES("Groceries", "🛒", 0xFF66BB6A),
    UTILITIES("Rent & Bills", "🏠", 0xFFFFA726),
    ENTERTAINMENT("Entertainment", "🎬", 0xFFAB47BC),
    SHOPPING("Shopping", "🛍️", 0xFFEC407A),
    HEALTH("Healthcare", "💊", 0xFF26A69A),
    SETTLEMENT("Payment", "💸", 0xFF10B981),
    GENERAL("General", "📦", 0xFF78909C);

    fun composeColor(): Color = Color(colorHex)

    companion object {
        fun fromName(name: String?): ExpenseCategory {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GENERAL
        }
    }
}
