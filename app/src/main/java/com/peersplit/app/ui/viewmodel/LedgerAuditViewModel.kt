package com.peersplit.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.ValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BlockAuditItem(
    val block: Block,
    val hasValidHash: Boolean,
    val hasValidSignature: Boolean,
    val isPrevHashLinked: Boolean
)

data class LedgerAuditState(
    val blocks: List<BlockAuditItem> = emptyList(),
    val isChainValid: Boolean = true,
    val validationMessage: String = "All cryptographic blocks are verified and intact.",
    val isLoading: Boolean = true
)

class LedgerAuditViewModel(private val groupId: String) : ViewModel() {

    private val repository = PeerSplitApp.instance.repository
    private val database = PeerSplitApp.instance.database

    private val _state = MutableStateFlow(LedgerAuditState())
    val state: StateFlow<LedgerAuditState> = _state.asStateFlow()

    init {
        loadAndAuditLedger()
    }

    fun loadAndAuditLedger() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val blockEntities = database.blockDao().getBlocksForGroupSync(groupId)
            val domainBlocks = blockEntities.map { it.toDomainBlock() }

            val validation = BlockchainEngine.validateChain(domainBlocks)

            val auditItems = domainBlocks.mapIndexed { index, block ->
                val hasValidHash = block.hasValidHash()
                val hasValidSignature = block.hasValidSignature()
                val isPrevHashLinked = if (index == 0) {
                    block.prevHash == Block.GENESIS_PREV_HASH
                } else {
                    block.prevHash == domainBlocks[index - 1].blockHash
                }

                BlockAuditItem(
                    block = block,
                    hasValidHash = hasValidHash,
                    hasValidSignature = hasValidSignature,
                    isPrevHashLinked = isPrevHashLinked
                )
            }

            val isValid = validation is ValidationResult.Valid
            val message = when (validation) {
                is ValidationResult.Valid -> "Cryptographic proof passed. All ${domainBlocks.size} blocks are immutable and tamper-free."
                is ValidationResult.Invalid -> "Tamper Alert: Block #${validation.blockIndex} failed validation: ${validation.reason}"
            }

            _state.value = LedgerAuditState(
                blocks = auditItems,
                isChainValid = isValid,
                validationMessage = message,
                isLoading = false
            )
        }
    }
}
