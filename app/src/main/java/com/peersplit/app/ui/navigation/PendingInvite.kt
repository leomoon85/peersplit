package com.peersplit.app.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds an invite link received via deep link (peersplit://...) until the UI consumes it.
 */
object PendingInvite {
    val code = MutableStateFlow<String?>(null)

    fun offer(raw: String?) {
        val trimmed = raw?.trim()
        if (!trimmed.isNullOrBlank() && trimmed.startsWith("peersplit://", ignoreCase = true)) {
            code.value = trimmed
        }
    }
}
