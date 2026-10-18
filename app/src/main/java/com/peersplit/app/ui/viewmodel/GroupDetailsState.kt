package com.peersplit.app.ui.viewmodel

import com.peersplit.app.data.repository.ExpenseRepository
import com.peersplit.app.data.repository.GroupDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class GroupDetailsState(
    val detail: GroupDetail? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeGroupDetails(
    repository: ExpenseRepository,
    groupId: String,
    scope: CoroutineScope,
    refresh: StateFlow<Int>
): StateFlow<GroupDetailsState> = refresh.flatMapLatest {
    repository.getGroupDetail(groupId)
        .map { GroupDetailsState(detail = it, isLoading = false) }
        .onStart { emit(GroupDetailsState()) }
        .catch { emit(GroupDetailsState(isLoading = false, error = it.message ?: "Unable to load group")) }
}.stateIn(scope, SharingStarted.WhileSubscribed(5000),
    repository.getCachedGroupDetail(groupId)?.let { GroupDetailsState(it, false) } ?: GroupDetailsState())
