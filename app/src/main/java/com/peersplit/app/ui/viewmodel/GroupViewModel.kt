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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository
    private val syncManager = PeerSplitApp.instance.syncManager

    val groupDetail: StateFlow<GroupDetail?> = repository.getGroupDetail(groupId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                syncManager.syncGroupNow(groupId)
                kotlinx.coroutines.delay(6000)
            }
        }
    }

    fun manualSync(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _isSyncing.value = true
            syncManager.syncGroupNow(groupId)
            kotlinx.coroutines.delay(700)
            _isSyncing.value = false
            onDone()
        }
    }

    fun generateInvite() {
        viewModelScope.launch {
            val code = repository.generateInviteCode(groupId)
            _inviteCode.value = code
        }
    }

    fun dismissInviteDialog() {
        _inviteCode.value = null
    }

    fun addOfflineMember(displayName: String, upiId: String?, phone: String? = null) {
        viewModelScope.launch {
            repository.addOfflineMember(groupId, displayName, upiId, phone)
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

    fun updateMemberContact(pubkey: String, phone: String?, upiId: String?) {
        viewModelScope.launch {
            repository.updateMemberContact(groupId, pubkey, phone, upiId)
        }
    }

    fun archiveGroup(isArchived: Boolean = true, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.archiveGroup(groupId, isArchived)
            onDone()
        }
    }

    fun deleteGroup(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteGroup(groupId)
            onDone()
        }
    }

    fun importExpenses(
        expenses: List<com.peersplit.app.util.ParsedImportExpense>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onComplete: (Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val detail = repository.getGroupDetail(groupId).first() ?: return@launch
            val members = detail.members.toMutableList()
            var importedCount = 0

            // Helper to get or create member pubkey
            suspend fun getOrCreateMemberPubkey(payerName: String): String {
                val clean = payerName.trim()
                val existing = members.firstOrNull { it.displayName.equals(clean, ignoreCase = true) }
                if (existing != null) return existing.pubkey

                // Auto-create offline member
                repository.addOfflineMember(groupId, clean)
                val updatedDetail = repository.getGroupDetail(groupId).first()
                if (updatedDetail != null) {
                    members.clear()
                    members.addAll(updatedDetail.members)
                    val created = members.firstOrNull { it.displayName.equals(clean, ignoreCase = true) }
                    if (created != null) return created.pubkey
                }
                return detail.group.creatorPubkey
            }

            for ((index, exp) in expenses.withIndex()) {
                val payerPubkey = getOrCreateMemberPubkey(exp.payerName)
                val splitShare = exp.amount / members.size.coerceAtLeast(1)
                val splits = members.map { SplitItem(it.pubkey, splitShare) }

                repository.addExpense(
                    groupId = groupId,
                    description = exp.description,
                    amount = exp.amount,
                    currency = detail.group.currency,
                    payerPubkey = payerPubkey,
                    splits = splits,
                    category = exp.category,
                    splitType = "EQUAL",
                    note = if (exp.dateString.isNotBlank()) "Imported (${exp.dateString})" else "Imported"
                )
                importedCount++
                onProgress(index + 1, expenses.size)
            }
            onComplete(importedCount)
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
