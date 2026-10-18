package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupEntryState(val busy: Boolean = false, val groupId: String? = null, val error: String? = null)

class GroupEntryViewModel : ViewModel() {
    private val repository = PeerSplitApp.instance.repository
    private val _state = MutableStateFlow(GroupEntryState())
    val state = _state.asStateFlow()
    fun consumeResult() { _state.value = GroupEntryState() }
    private fun submit(work: suspend () -> String) {
        if (_state.value.busy) return
        _state.value = GroupEntryState(busy = true)
        viewModelScope.launch {
            try { _state.value = GroupEntryState(groupId = withContext(Dispatchers.IO) { work() })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.value = GroupEntryState(error = e.message ?: "Please retry") }
        }
    }
    fun create(name: String, currency: String, secret: Boolean, pin: String?, solo: Boolean, members: List<String>) = submit {
        repository.createGroup(name, currency, secret, pin, solo, initialMemberNames = members)
    }
    fun join(invite: String, claimedGhost: String?) = submit { repository.joinGroup(invite, claimedGhost).getOrThrow() }
}
