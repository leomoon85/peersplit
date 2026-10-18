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

            val groupProtocol = database.groupProtocolDao().getGroupProtocolSync(groupId)
            val opEntities = database.signedOperationDao().getOperationsForGroupSync(groupId)

            if (groupProtocol != null || opEntities.isNotEmpty()) {
                val groupEntity = database.groupDao().getGroupByIdSync(groupId)
                val domainOps = opEntities.map { com.peersplit.app.protocol.OperationEntityMapper.toDomain(it) }
                val trustedGenesis = com.peersplit.app.reconciliation.TrustedGenesis(
                    groupId = groupId,
                    groupName = groupEntity?.name ?: "Group",
                    currency = groupEntity?.currency ?: "INR",
                    creatorPublicKey = groupEntity?.creatorPubkey ?: domainOps.firstOrNull { it.parents.isEmpty() }?.authorPublicKey ?: "",
                    genesisOperationId = groupProtocol?.genesisOperationId ?: domainOps.firstOrNull { it.parents.isEmpty() }?.operationId
                )
                val reconciliationResult = com.peersplit.app.reconciliation.DefaultLedgerReconciliationEngine.reconcile(
                    trustedGenesis = trustedGenesis,
                    operations = domainOps
                )
                val acceptedIds = reconciliationResult.acceptedOperations.map { it.operationId }.toSet()

                val auditItems = domainOps.sortedBy { it.logicalClock }.map { op ->
                    val hasValidHash = com.peersplit.app.protocol.OperationVerifier.hasValidOperationId(op)
                    val hasValidSig = com.peersplit.app.protocol.OperationVerifier.hasValidSignature(op)
                    val isLinked = op.parents.isEmpty() || op.parents.any { p -> domainOps.any { it.operationId == p } }

                    val blockType = when (op.payload) {
                        is com.peersplit.app.protocol.OperationPayload.ExpenseCreated -> com.peersplit.app.blockchain.BlockType.EXPENSE
                        is com.peersplit.app.protocol.OperationPayload.ExpenseUpdated -> com.peersplit.app.blockchain.BlockType.EXPENSE
                        is com.peersplit.app.protocol.OperationPayload.ExpenseVoided -> com.peersplit.app.blockchain.BlockType.EXPENSE
                        is com.peersplit.app.protocol.OperationPayload.SettlementRecorded -> com.peersplit.app.blockchain.BlockType.SETTLEMENT
                        is com.peersplit.app.protocol.OperationPayload.GenesisCreated -> com.peersplit.app.blockchain.BlockType.GENESIS
                        is com.peersplit.app.protocol.OperationPayload.MemberAdded -> com.peersplit.app.blockchain.BlockType.MEMBER_JOIN
                        is com.peersplit.app.protocol.OperationPayload.MemberRemoved -> com.peersplit.app.blockchain.BlockType.MEMBER_JOIN
                        is com.peersplit.app.protocol.OperationPayload.LegacyV1Checkpoint -> com.peersplit.app.blockchain.BlockType.GENESIS
                    }
                    val block = Block(
                        index = op.logicalClock,
                        groupId = op.groupId,
                        prevHash = op.parents.firstOrNull() ?: Block.GENESIS_PREV_HASH,
                        timestamp = op.createdAtMillis,
                        creatorPubkey = op.authorPublicKey,
                        blockType = blockType,
                        payloadJson = op.operationId,
                        signature = op.signature,
                        blockHash = op.operationId
                    )
                    BlockAuditItem(
                        block = block,
                        hasValidHash = hasValidHash,
                        hasValidSignature = hasValidSig,
                        isPrevHashLinked = isLinked
                    )
                }

                val allValid = auditItems.isNotEmpty() && auditItems.all { it.hasValidHash && it.hasValidSignature }
                val message = if (allValid) {
                    "V2 Authoritative Ledger verified. ${reconciliationResult.acceptedOperations.size} accepted operations, state digest: ${reconciliationResult.stateDigest.take(8)}."
                } else if (auditItems.isEmpty()) {
                    "V2 ledger initialized; awaiting initial operations."
                } else {
                    "Tamper Alert: One or more V2 operations failed cryptographic signature or hash verification."
                }

                _state.value = LedgerAuditState(
                    blocks = auditItems,
                    isChainValid = allValid || auditItems.isEmpty(),
                    validationMessage = message,
                    isLoading = false
                )
                return@launch
            }

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
