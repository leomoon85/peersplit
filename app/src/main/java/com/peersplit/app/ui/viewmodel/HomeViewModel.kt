package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.data.model.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val visibleGroups: List<Group> = emptyList(),
    val secretGroups: List<Group> = emptyList(),
    val isSecretVaultUnlocked: Boolean = false,
    val totalNetBalance: Double = 0.0,
    val totalOwedToUser: Double = 0.0,
    val totalUserOwes: Double = 0.0,
    val connectedRelaysCount: Int = 4
)

class HomeViewModel : ViewModel() {

    private val repository = PeerSplitApp.instance.repository
    private val isSecretVaultUnlocked = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.getGroups(),
        isSecretVaultUnlocked.asStateFlow()
    ) { allGroups, unlocked ->
        val regular = allGroups.filter { !it.isSecret }
        val secret = allGroups.filter { it.isSecret }

        // Only calculate dashboard balance for regular groups (or unlocked) so secrets remain hidden
        val countedGroups = if (unlocked) allGroups else regular

        var totalBalance = 0.0
        var owedToUser = 0.0
        var userOwes = 0.0

        for (g in countedGroups) {
            totalBalance += g.userNetBalance
            if (g.userNetBalance > 0) {
                owedToUser += g.userNetBalance
            } else if (g.userNetBalance < 0) {
                userOwes += -g.userNetBalance
            }
        }

        HomeUiState(
            visibleGroups = regular,
            secretGroups = secret,
            isSecretVaultUnlocked = unlocked,
            totalNetBalance = totalBalance,
            totalOwedToUser = owedToUser,
            totalUserOwes = userOwes,
            connectedRelaysCount = 4
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun unlockVault(pin: String): Boolean {
        val pinHash = HashUtil.sha256Hex(pin)
        val matches = uiState.value.secretGroups.any { it.secretPinHash == null || it.secretPinHash == pinHash }
        if (matches || uiState.value.secretGroups.isEmpty()) {
            isSecretVaultUnlocked.value = true
            return true
        }
        return false
    }

    fun lockVault() {
        isSecretVaultUnlocked.value = false
    }
}
