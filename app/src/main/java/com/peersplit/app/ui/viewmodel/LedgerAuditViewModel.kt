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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction

data class BlockAuditItem(
    val block: Block,
    val hasValidHash: Boolean,
    val hasValidSignature: Boolean,
    val isPrevHashLinked: Boolean
)

data class LedgerAuditState(
    val blocks: List<BlockAuditItem> = emptyList(),
    val isChainValid: Boolean = false,
    val validationMessage: String = "All cryptographic blocks are verified and intact.",
    val isPending: Boolean = false,
    val statusTitle: String = "Awaiting verification",
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
        viewModelScope.launch(Dispatchers.Default) {
            try {
            _state.value = _state.value.copy(isLoading = true)

            val inputs = database.withTransaction {
                AuditInputs(
                    database.groupProtocolDao().getGroupProtocolSync(groupId),
                    database.groupDao().getGroupByIdSync(groupId),
                    database.signedOperationDao().getOperationsForGroupSync(groupId),
                    database.projectionDao().getExpenseProjectionsForGroupSync(groupId),
                    database.projectionDao().getBalanceProjectionsForGroupSync(groupId),
                    database.blockDao().getBlocksForGroupSync(groupId)
                )
            }
            val groupProtocol = inputs.protocol
            val opEntities = inputs.operations

            if (groupProtocol != null || opEntities.isNotEmpty()) {
                val groupEntity = inputs.group
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
                    val isLinked = op.parents.isEmpty() || op.parents.all { p -> domainOps.any { it.operationId == p } }

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

                val allAccepted = domainOps.isNotEmpty() && domainOps.all { it.operationId in acceptedIds } && reconciliationResult.rejectedOperations.isEmpty()
                val allIntegrityValid = auditItems.isNotEmpty() && auditItems.all { it.hasValidHash && it.hasValidSignature && it.isPrevHashLinked }
                val projectionMatches = com.peersplit.app.reconciliation.ProjectionAudit.matches(reconciliationResult, inputs.expenses, inputs.balances) &&
                    groupProtocol?.latestStateDigest == reconciliationResult.stateDigest
                val allValid = allIntegrityValid && allAccepted && projectionMatches
                val pending = reconciliationResult.pendingOperations.isNotEmpty()

                val message = when {
                    auditItems.isEmpty() -> "Ledger empty; awaiting initial operations."
                    allValid -> "V2 Authoritative Ledger verified. ${reconciliationResult.acceptedOperations.size} accepted operations, state digest: ${reconciliationResult.stateDigest.take(8)}."
                    pending -> "Awaiting ${reconciliationResult.pendingOperations.size} operation dependencies/admission proofs. Retry after synchronization."
                    !projectionMatches -> "Stored balances or expenses differ from replayed history. A projection rebuild is required."
                    reconciliationResult.rejectedOperations.isNotEmpty() -> "Tamper/Conflict Alert: ${reconciliationResult.rejectedOperations.size} operation(s) rejected during authoritative reconciliation."
                    else -> "Tamper Alert: One or more V2 operations failed cryptographic signature, hash, or parent link verification."
                }

                _state.value = LedgerAuditState(
                    blocks = auditItems,
                    isChainValid = allValid,
                    isPending = pending,
                    statusTitle = when { auditItems.isEmpty() -> "Awaiting history"; allValid -> "Local ledger verified"; pending -> "Awaiting dependencies"; else -> "Verification needs attention" },
                    validationMessage = message,
                    isLoading = false
                )
                return@launch
            }

            val blockEntities = inputs.blocks
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

            val isValid = domainBlocks.isNotEmpty() && validation is ValidationResult.Valid
            val message = when {
                domainBlocks.isEmpty() -> "Ledger empty; awaiting initial operations."
                validation is ValidationResult.Valid -> "Cryptographic proof passed. All ${domainBlocks.size} blocks are immutable and tamper-free."
                validation is ValidationResult.Invalid -> "Tamper Alert: Block #${validation.blockIndex} failed validation: ${validation.reason}"
                else -> "Ledger validation failed."
            }

            _state.value = LedgerAuditState(
                blocks = auditItems,
                isChainValid = isValid,
                statusTitle = if (domainBlocks.isEmpty()) "Awaiting history" else if (isValid) "Local ledger verified" else "Verification needs attention",
                validationMessage = message,
                isLoading = false
            )
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.value = LedgerAuditState(isLoading = false, isChainValid = false,
                    statusTitle = "Audit could not complete", validationMessage = e.message ?: "Retry audit")
            }
        }
    }
}

private data class AuditInputs(
    val protocol: com.peersplit.app.data.local.entity.GroupProtocolEntity?,
    val group: com.peersplit.app.data.local.entity.GroupEntity?,
    val operations: List<com.peersplit.app.data.local.entity.SignedOperationEntity>,
    val expenses: List<com.peersplit.app.data.local.entity.ExpenseProjectionEntity>,
    val balances: List<com.peersplit.app.data.local.entity.BalanceProjectionEntity>,
    val blocks: List<com.peersplit.app.data.local.entity.BlockEntity>
)
