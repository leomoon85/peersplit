package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.model.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val visibleGroups: List<Group> = emptyList(),
    val archivedGroups: List<Group> = emptyList(),
    val secretGroups: List<Group> = emptyList(),
    val isSecretVaultUnlocked: Boolean = false,
    val totalNetBalance: Double = 0.0,
    val totalOwedToUser: Double = 0.0,
    val totalUserOwes: Double = 0.0,
    val primaryCurrency: String = "INR",
    val otherCurrencyBalances: Map<String, Double> = emptyMap(),
    val isOnline: Boolean = true
)

class HomeViewModel : ViewModel() {

    private val repository = PeerSplitApp.instance.repository
    private val syncManager = PeerSplitApp.instance.syncManager
    private val unlockedPin = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.getGroups(),
        unlockedPin.asStateFlow(),
        syncManager.isOnline
    ) { allGroups, pin, isOnline ->
        val activeRegular = allGroups.filter { !it.isSecret && !it.isArchived }
        val archived = allGroups.filter { !it.isSecret && it.isArchived }
        val allSecret = allGroups.filter { it.isSecret }
        val unlocked = pin != null
        val unlockedSecrets = if (pin != null) {
            allSecret.filter { KeyManager.verifySecretPin(pin, it.secretPinHash) }
        } else {
            emptyList()
        }

        // Only calculate dashboard balance for active visible + unlocked secret groups
        val countedGroups = if (unlocked) (activeRegular + unlockedSecrets) else activeRegular

        // Determine dominant currency across counted groups so we don't sum incompatible currencies directly
        val primaryCurrency = countedGroups
            .groupingBy { it.currency }
            .eachCount()
            .maxByOrNull { it.value }?.key
            ?: allGroups.firstOrNull()?.currency
            ?: "INR"

        var totalBalance = 0.0
        var owedToUser = 0.0
        var userOwes = 0.0
        val otherBalances = mutableMapOf<String, Double>()

        for (g in countedGroups) {
            if (g.currency == primaryCurrency) {
                totalBalance += g.userNetBalance
                if (g.userNetBalance > 0) {
                    owedToUser += g.userNetBalance
                } else if (g.userNetBalance < 0) {
                    userOwes += -g.userNetBalance
                }
            } else {
                otherBalances[g.currency] = (otherBalances[g.currency] ?: 0.0) + g.userNetBalance
            }
        }

        HomeUiState(
            visibleGroups = activeRegular,
            archivedGroups = archived,
            secretGroups = if (unlocked) unlockedSecrets else allSecret,
            isSecretVaultUnlocked = unlocked,
            totalNetBalance = totalBalance,
            totalOwedToUser = owedToUser,
            totalUserOwes = userOwes,
            primaryCurrency = primaryCurrency,
            otherCurrencyBalances = otherBalances.filter { Math.abs(it.value) > 0.001 },
            isOnline = isOnline
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun archiveGroup(groupId: String, isArchived: Boolean = true) {
        viewModelScope.launch {
            repository.archiveGroup(groupId, isArchived)
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            repository.deleteGroup(groupId)
        }
    }

    suspend fun unlockVault(pin: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val allSecret = uiState.value.secretGroups
        val matches = allSecret.any { KeyManager.verifySecretPin(pin, it.secretPinHash) }
        if (matches || allSecret.isEmpty()) {
            unlockedPin.value = pin
            true
        } else {
            false
        }
    }

    fun lockVault() {
        unlockedPin.value = null
    }
}
