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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class GroupViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository
    private val syncManager = PeerSplitApp.instance.syncManager

    private val refresh = MutableStateFlow(0)
    val detailState = observeGroupDetails(repository, groupId, viewModelScope, refresh)
    fun reload() { refresh.value++ }

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        viewModelScope.launch {
            syncManager.syncGroupNow(groupId, broadcastChain = false)
        }
    }

    fun manualSync(onDone: () -> Unit = {}) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                syncManager.syncGroupNow(groupId, broadcastChain = true)
                onDone() // Scheduling only; the UI labels this as a request.
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _actionError.value = e.message ?: "Could not request synchronization" }
            finally { _isSyncing.value = false }
        }
    }

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()
    private var retryAction: (() -> Unit)? = null
    fun retryLastAction() { retryAction?.invoke() }
    fun clearActionMessage() { _actionMessage.value = null }
    private fun action(message: String? = null, onSuccess: () -> Unit = {}, work: suspend () -> Unit) {
        _actionError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                _actionMessage.value = message
                onSuccess()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                retryAction = { action(message, onSuccess, work) }
                _actionError.value = e.message ?: "Action failed. Please retry."
            }
        }
    }
    fun generateInvite() = action { _inviteCode.value = repository.generateInviteCode(groupId) }
    fun dismissInviteDialog() { _inviteCode.value = null }
    fun addOfflineMember(displayName: String, upiId: String?, phone: String? = null) = action("Member saved locally") {
        repository.addOfflineMember(groupId, displayName, upiId, phone)
    }
    fun voidExpense(targetExpenseId: String, targetDescription: String, reason: String = "Voided by member") = action("Expense removed locally") {
        repository.voidExpense(groupId, targetExpenseId, targetDescription, reason)
    }
    fun updateBudget(budget: Double) = action("Budget saved") { repository.updateGroupBudget(groupId, budget) }
    suspend fun saveMemberUpi(pubkey: String, upiId: String) = withContext(Dispatchers.IO) {
        repository.updateMemberUpiId(groupId, pubkey, upiId)
    }
    fun updateMemberContact(pubkey: String, phone: String?, upiId: String?) = action("Contact saved") {
        repository.updateMemberContact(groupId, pubkey, phone, upiId)
    }
    fun archiveGroup(isArchived: Boolean = true, onDone: () -> Unit = {}) {
        action(onSuccess = onDone) { repository.archiveGroup(groupId, isArchived) }
    }
    fun deleteGroup(onDone: () -> Unit = {}) { action(onSuccess = onDone) { repository.deleteGroup(groupId) } }

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()
    private val _settlementSaved = MutableStateFlow(false)
    val settlementSaved = _settlementSaved.asStateFlow()
    fun consumeSettlementSaved() { _settlementSaved.value = false }
    fun recordSettlement(payer: String, payee: String, amount: Double, currency: String) {
        if (_isRecording.value) return
        _isRecording.value = true
        _actionError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.recordSettlement(groupId, payer, payee, amount, currency) }
                _settlementSaved.value = true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _actionError.value = e.message ?: "Could not record payment" }
            finally { _isRecording.value = false }
        }
    }

    private val _importProgress = MutableStateFlow(com.peersplit.app.util.ImportProgress())
    val importProgress = _importProgress.asStateFlow()
    fun dismissImportResult() { if (!_importProgress.value.running) _importProgress.value = com.peersplit.app.util.ImportProgress() }
    fun retryFailedImports() { _importProgress.value.outcome?.failures?.map { it.expense }?.let { importExpenses(it) } }
    fun importExpenses(expenses: List<com.peersplit.app.util.ParsedImportExpense>) {
        if (_importProgress.value.running) return
        _importProgress.value = com.peersplit.app.util.ImportProgress(running = true, total = expenses.size)
        viewModelScope.launch {
            var imported = 0
            var skipped = 0
            val failed = mutableListOf<com.peersplit.app.util.ImportFailure>()
            try {
                withContext(Dispatchers.IO) {
                    for ((index, expense) in expenses.withIndex()) {
                        try {
                            val detail = repository.getGroupDetail(groupId).first() ?: error("Group not found")
                            val key = expense.importKey.ifBlank {
                                com.peersplit.app.crypto.HashUtil.sha256Hex("${expense.description}|${expense.amount}|${expense.payerName}|${expense.dateString}")
                            }
                            val marker = "[PeerSplit import:$key]"
                            if (detail.expenses.any { it.note.contains(marker) }) {
                                skipped++
                                continue
                            }
                            val clean = expense.payerName.trim()
                            val matches = detail.members.filter { it.pubkey == clean || it.displayName.equals(clean, true) }
                            require(matches.size <= 1) { "More than one member is named $clean. Rename members before importing." }
                            val payer = matches.singleOrNull()?.pubkey ?: run {
                                repository.addOfflineMember(groupId, clean)
                                repository.getGroupDetail(groupId).first()?.members?.singleOrNull { it.displayName.equals(clean, true) }?.pubkey
                                    ?: error("Could not create payer $clean")
                            }
                            val members = repository.getGroupDetail(groupId).first()?.members ?: error("Group not found")
                            val allocated = com.peersplit.app.protocol.MoneyMath.allocateEqualSplits(
                                com.peersplit.app.protocol.MoneyMath.toMinorUnits(expense.amount), members.map { it.pubkey })
                            repository.addExpense(groupId, expense.description, expense.amount, detail.group.currency, payer,
                                allocated.map { SplitItem(it.key, com.peersplit.app.protocol.MoneyMath.toMajorDouble(it.value)) },
                                category = expense.category, splitType = "EQUAL",
                                note = "Imported (${expense.dateString}) $marker", expenseDateMillis = expense.timestamp)
                            imported++
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            failed += com.peersplit.app.util.ImportFailure(expense, e.message ?: "Import failed")
                        } finally {
                            _importProgress.value = _importProgress.value.copy(done = index + 1)
                        }
                    }
                }
            } finally {
                _importProgress.value = _importProgress.value.copy(running = false,
                    outcome = com.peersplit.app.util.ImportOutcome(imported, skipped, failed.toList()))
            }
        }
    }

}

class AddExpenseViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository

    private val refresh = MutableStateFlow(0)
    val detailState = observeGroupDetails(repository, groupId, viewModelScope, refresh)
    fun reload() { refresh.value++ }

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _savedReceipt = MutableStateFlow<ExpenseSaveReceipt?>(null)
    val savedReceipt = _savedReceipt.asStateFlow()
    fun consumeSavedReceipt() { _savedReceipt.value = null }
    private val _submissionSuccess = MutableStateFlow(false)
    val submissionSuccess: StateFlow<Boolean> = _submissionSuccess.asStateFlow()

    private val _submissionError = MutableStateFlow<String?>(null)
    val submissionError: StateFlow<String?> = _submissionError.asStateFlow()

    fun submitExpense(
        description: String,
        amount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String = "General",
        splitType: String = "EQUAL",
        note: String = "",
        payers: List<SplitItem> = emptyList(),
        expenseDateMillis: Long? = null,
        editingExpenseId: String? = null,
        originalDescription: String? = null,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        if (!amount.isFinite() || amount <= 0 || description.isBlank() || splits.isEmpty()) {
            _submissionError.value = "Enter a valid amount, description and split"
            return
        }
        _isSubmitting.value = true
        viewModelScope.launch {
            _isSubmitting.value = true
            _submissionError.value = null
            try {
                withContext(Dispatchers.IO) {
                if (!editingExpenseId.isNullOrBlank()) {
                    repository.editExpense(
                        groupId = groupId,
                        originalExpenseId = editingExpenseId,
                        originalDescription = originalDescription ?: description.trim(),
                        newDescription = description.trim(),
                        newAmount = amount,
                        currency = currency,
                        payerPubkey = payerPubkey,
                        splits = splits,
                        category = category,
                        splitType = splitType,
                        note = note,
                        payers = payers,
                        expenseDateMillis = expenseDateMillis
                    )
                } else {
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
                        payers = payers,
                        expenseDateMillis = expenseDateMillis
                    )
                }
                }
                val myKey = com.peersplit.app.crypto.KeyManager.getOrCreateIdentity(PeerSplitApp.instance).publicKeyHex
                _savedReceipt.value = ExpenseSaveReceipt(!editingExpenseId.isNullOrBlank(),
                    payerPubkey == myKey || payers.any { it.userPubkey == myKey && it.amount > 0 })
                _submissionSuccess.value = true
                onComplete()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to save expense"
                _submissionError.value = errorMsg
                onError(errorMsg)
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}

data class ExpenseSaveReceipt(val edited: Boolean, val userPaid: Boolean)
