package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.data.repository.GroupDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository

    val groupDetail: StateFlow<GroupDetail?> = repository.getGroupDetail(groupId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    fun generateInvite() {
        viewModelScope.launch {
            val code = repository.generateInviteCode(groupId)
            _inviteCode.value = code
        }
    }

    fun dismissInviteDialog() {
        _inviteCode.value = null
    }

    fun addOfflineMember(displayName: String, upiId: String?) {
        viewModelScope.launch {
            repository.addOfflineMember(groupId, displayName, upiId)
        }
    }

    fun voidExpense(targetExpenseId: String, targetDescription: String, reason: String = "Voided by member") {
        viewModelScope.launch {
            repository.voidExpense(groupId, targetExpenseId, targetDescription, reason)
        }
    }

    fun updateBudget(budget: Double) {
        viewModelScope.launch {
            repository.updateGroupBudget(groupId, budget)
        }
    }

    fun updateMemberUpiId(pubkey: String, upiId: String) {
        viewModelScope.launch {
            repository.updateMemberUpiId(groupId, pubkey, upiId)
        }
    }
}

class AddExpenseViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository

    val groupDetail: StateFlow<GroupDetail?> = repository.getGroupDetail(groupId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submissionSuccess = MutableStateFlow(false)
    val submissionSuccess: StateFlow<Boolean> = _submissionSuccess.asStateFlow()

    fun submitExpense(
        description: String,
        amount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String = "General",
        splitType: String = "EQUAL",
        note: String = "",
        payers: List<SplitItem> = emptyList()
    ) {
        if (amount <= 0 || description.isBlank() || splits.isEmpty()) return

        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                repository.addExpense(
                    groupId = groupId,
                    description = description.trim(),
                    amount = amount,
                    currency = currency,
                    payerPubkey = payerPubkey,
                    splits = splits,
                    category = category,
                    splitType = splitType,
                    note = note,
                    payers = payers
                )
                _submissionSuccess.value = true
            } catch (e: Exception) {
                _isSubmitting.value = false
            }
        }
    }
}
