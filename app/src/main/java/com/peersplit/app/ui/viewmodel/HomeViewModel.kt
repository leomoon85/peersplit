package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.data.model.Group
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val groups: List<Group> = emptyList(),
    val totalNetBalance: Double = 0.0,
    val totalOwedToUser: Double = 0.0,
    val totalUserOwes: Double = 0.0,
    val connectedRelaysCount: Int = 4
)

class HomeViewModel : ViewModel() {

    private val repository = PeerSplitApp.instance.repository

    val uiState: StateFlow<HomeUiState> = repository.getGroups().map { groups ->
        var totalBalance = 0.0
        var owedToUser = 0.0
        var userOwes = 0.0

        for (g in groups) {
            totalBalance += g.userNetBalance
            if (g.userNetBalance > 0) {
                owedToUser += g.userNetBalance
            } else if (g.userNetBalance < 0) {
                userOwes += -g.userNetBalance
            }
        }

        HomeUiState(
            groups = groups,
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
}
