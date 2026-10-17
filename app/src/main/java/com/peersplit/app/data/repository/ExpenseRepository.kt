package com.peersplit.app.data.repository

import android.content.Context
import android.util.Base64
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.DebtSettlementEngine
import com.peersplit.app.blockchain.ExpensePayload
import com.peersplit.app.blockchain.ExpenseVoidPayload
import com.peersplit.app.blockchain.GroupGenesisPayload
import com.peersplit.app.blockchain.SettlementPayload
import com.peersplit.app.blockchain.SettlementSuggestion
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.blockchain.ValidationResult
import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.data.model.ExpenseItem
import com.peersplit.app.data.model.Group
import com.peersplit.app.data.model.Member
import com.peersplit.app.network.sync.P2PSyncManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class GroupDetail(
    val group: Group,
    val members: List<Member>,
    val expenses: List<ExpenseItem>,
    val settlements: List<SettlementSuggestion>,
    val blocks: List<Block>,
    val isLedgerIntact: Boolean
)

@Serializable
data class GroupInviteData(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val groupKeyBase64: String,
    val creatorPubkey: String,
    val creatorDisplayName: String = "Creator",
    val genesisBlockJson: String? = null
)

class ExpenseRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val syncManager: P2PSyncManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Observes all groups with dynamic user balance computation.
     */
    fun getGroups(): Flow<List<Group>> {
        val identity = KeyManager.getOrCreateIdentity(context)
        return combine(
            database.groupDao().getAllGroups(),
            database.memberDao().getAllMembersCountFlow(),
            database.blockDao().getAllBlocksCountFlow()
        ) { groupEntities, _, _ ->
            groupEntities.map { entity ->
                val blocks = database.blockDao().getBlocksForGroupSync(entity.id).map { it.toDomainBlock() }
                val members = database.memberDao().getMembersForGroupSync(entity.id)
                val expenses = extractExpenses(blocks)
                val settlements = extractSettlements(blocks)
                val voidedIds = extractVoidedExpenseIds(blocks)
                val netBalances = DebtSettlementEngine.calculateNetBalances(expenses, settlements, voidedIds)

                val userBalance = netBalances[identity.publicKeyHex] ?: 0.0
                val validation = BlockchainEngine.validateChain(blocks)

                Group(
                    id = entity.id,
                    name = entity.name,
                    currency = entity.currency,
                    creatorPubkey = entity.creatorPubkey,
                    groupKeyBase64 = entity.groupKeyBase64,
                    memberCount = members.size,
                    userNetBalance = userBalance,
                    blockCount = blocks.size.toLong(),
                    isLedgerValid = validation is ValidationResult.Valid,
                    isSecret = entity.isSecret,
                    secretPinHash = entity.secretPinHash,
                    isPersonalSolo = entity.isPersonalSolo,
                    monthlyBudget = entity.monthlyBudget,
                    isArchived = entity.isArchived,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    /**
     * Observes a specific group's detail view, member list, and settlements.
     */
    fun getGroupDetail(groupId: String): Flow<GroupDetail?> {
        val identity = KeyManager.getOrCreateIdentity(context)
        return combine(
            database.groupDao().getGroupById(groupId),
            database.memberDao().getMembersForGroup(groupId),
            database.blockDao().getBlocksForGroup(groupId)
        ) { groupEntity, memberEntities, blockEntities ->
            if (groupEntity == null) return@combine null

            val domainBlocks = blockEntities.map { it.toDomainBlock() }
            val expensesPayloads = extractExpenses(domainBlocks)
            val settlementPayloads = extractSettlements(domainBlocks)
            val voidedIds = extractVoidedExpenseIds(domainBlocks)
            val ghostPubkeyHash = HashUtil.sha256Hex("ghost:$groupId:${identity.displayName.trim().lowercase()}")
            val aliasMap = mutableMapOf<String, String>()
            for (m in memberEntities) {
                if (m.pubkey == ghostPubkeyHash || (m.displayName.equals(identity.displayName.trim(), ignoreCase = true) && m.pubkey != identity.publicKeyHex)) {
                    aliasMap[m.pubkey] = identity.publicKeyHex
                }
            }

            val netBalances = DebtSettlementEngine.calculateNetBalances(expensesPayloads, settlementPayloads, voidedIds, aliasMap)
            val suggestions = DebtSettlementEngine.simplifyDebts(netBalances)
            val validation = BlockchainEngine.validateChain(domainBlocks)

            val memberMap = memberEntities.associate { it.pubkey to it.displayName }
            val members = memberEntities.map { memberEntity ->
                val resolvedPubkey = aliasMap[memberEntity.pubkey] ?: memberEntity.pubkey
                Member(
                    pubkey = memberEntity.pubkey,
                    displayName = memberEntity.displayName,
                    isCurrentUser = resolvedPubkey == identity.publicKeyHex,
                    netBalance = netBalances[resolvedPubkey] ?: 0.0,
                    upiId = memberEntity.upiId
                )
            }

            val expenseItems = domainBlocks.filter { it.blockType == BlockType.EXPENSE || it.blockType == BlockType.SETTLEMENT }.mapNotNull { block ->
                try {
                    if (block.blockType == BlockType.EXPENSE) {
                        val exp = json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
                        val isVoided = exp.expenseId in voidedIds
                        val userSplit = exp.splits.find { (aliasMap[it.userPubkey] ?: it.userPubkey) == identity.publicKeyHex }?.amount ?: 0.0
                        val userPaid = if (exp.payers.size > 1) {
                            exp.payers.find { (aliasMap[it.userPubkey] ?: it.userPubkey) == identity.publicKeyHex }?.amount ?: 0.0
                        } else if ((aliasMap[exp.payerPubkey] ?: exp.payerPubkey) == identity.publicKeyHex) {
                            exp.totalAmount
                        } else {
                            0.0
                        }
                        val share = if (isVoided) 0.0 else (userPaid - userSplit)

                        val resolvedPayerName = if (exp.payers.size > 1) {
                            exp.payers.joinToString(" + ") { p -> memberMap[p.userPubkey] ?: "Member" }
                        } else {
                            memberMap[exp.payerPubkey] ?: "Member"
                        }

                        ExpenseItem(
                            id = exp.expenseId,
                            description = exp.description,
                            totalAmount = exp.totalAmount,
                            currency = exp.currency,
                            payerPubkey = exp.payerPubkey,
                            payerName = resolvedPayerName,
                            timestamp = block.timestamp,
                            userShare = share,
                            blockIndex = block.index,
                            category = exp.category,
                            note = exp.note,
                            splitType = exp.splitType,
                            splits = exp.splits,
                            payers = exp.payers,
                            blockHash = block.blockHash,
                            signature = block.signature,
                            isVoided = isVoided
                        )
                    } else { // BlockType.SETTLEMENT
                        val stl = json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
                        val payerPub = aliasMap[stl.payerPubkey] ?: stl.payerPubkey
                        val payeePub = aliasMap[stl.payeePubkey] ?: stl.payeePubkey
                        val payerName = memberMap[stl.payerPubkey] ?: "Member"
                        val payeeName = memberMap[stl.payeePubkey] ?: "Member"
                        val userShare = if (payerPub == identity.publicKeyHex) {
                            stl.amount // user paid
                        } else if (payeePub == identity.publicKeyHex) {
                            -stl.amount // user received
                        } else {
                            0.0
                        }

                        ExpenseItem(
                            id = stl.settlementId,
                            description = "$payerName paid $payeeName",
                            totalAmount = stl.amount,
                            currency = stl.currency,
                            payerPubkey = stl.payerPubkey,
                            payerName = payerName,
                            timestamp = block.timestamp,
                            userShare = userShare,
                            blockIndex = block.index,
                            category = "SETTLEMENT",
                            note = stl.note,
                            splitType = "SETTLEMENT",
                            splits = listOf(SplitItem(stl.payeePubkey, stl.amount)),
                            payers = listOf(SplitItem(stl.payerPubkey, stl.amount)),
                            blockHash = block.blockHash,
                            signature = block.signature,
                            isVoided = false
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }.reversed()

            val group = Group(
                id = groupEntity.id,
                name = groupEntity.name,
                currency = groupEntity.currency,
                creatorPubkey = groupEntity.creatorPubkey,
                groupKeyBase64 = groupEntity.groupKeyBase64,
                memberCount = members.size,
                userNetBalance = netBalances[identity.publicKeyHex] ?: 0.0,
                blockCount = domainBlocks.size.toLong(),
                isLedgerValid = validation is ValidationResult.Valid,
                isSecret = groupEntity.isSecret,
                secretPinHash = groupEntity.secretPinHash,
                isPersonalSolo = groupEntity.isPersonalSolo,
                monthlyBudget = groupEntity.monthlyBudget,
                isArchived = groupEntity.isArchived,
                createdAt = groupEntity.createdAt
            )

            GroupDetail(
                group = group,
                members = members,
                expenses = expenseItems,
                settlements = suggestions,
                blocks = domainBlocks,
                isLedgerIntact = validation is ValidationResult.Valid
            )
        }
    }

    /**
     * Creates a new Group with its Genesis block and broadcasts to Nostr.
     */
    suspend fun createGroup(
        name: String,
        currency: String = "USD",
        isSecret: Boolean = false,
        secretPin: String? = null,
        isPersonalSolo: Boolean = false,
        monthlyBudget: Double = 0.0
    ): String {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)

        // Generate 256-bit AES symmetric group key
        val groupKeyBytes = EncryptionUtil.generateGroupKey()
        val groupKeyBase64 = EncryptionUtil.keyToBase64(groupKeyBytes)

        val (groupId, genesisBlock) = BlockchainEngine.createGenesisBlock(
            groupName = name.trim(),
            currency = currency.trim(),
            creatorPubkey = identity.publicKeyHex,
            creatorDisplayName = identity.displayName,
            privateKeyHex = privKeyHex,
            creatorUpiId = identity.upiId
        )

        val pinHash = if (!secretPin.isNullOrBlank()) KeyManager.hashSecretPin(secretPin) else null

        // Store Group and Genesis Block locally
        database.groupDao().insertGroup(
            GroupEntity(
                id = groupId,
                name = name.trim(),
                currency = currency.trim(),
                creatorPubkey = identity.publicKeyHex,
                groupKeyBase64 = groupKeyBase64,
                isSecret = isSecret,
                secretPinHash = pinHash,
                isPersonalSolo = isPersonalSolo,
                monthlyBudget = monthlyBudget
            )
        )

        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(genesisBlock, isVerified = true))
        database.memberDao().insertMember(
            MemberEntity(
                groupId = groupId,
                pubkey = identity.publicKeyHex,
                displayName = identity.displayName,
                upiId = identity.upiId
            )
        )

        // Broadcast to P2P relay network
        syncManager.monitorGroup(groupId)
        syncManager.broadcastBlock(genesisBlock, groupKeyBase64)

        return groupId
    }

    /**
     * Adds an offline / non-app member ("Ghost Member") directly to the group's blockchain ledger.
     */
    suspend fun addOfflineMember(
        groupId: String,
        displayName: String,
        upiId: String? = null
    ) {
        if (displayName.isBlank()) return
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return
        val latestBlock = database.blockDao().getLatestBlockForGroup(groupId)?.toDomainBlock() ?: return

        val cleanName = displayName.trim()
        val cleanUpi = upiId?.trim()?.takeIf { it.isNotBlank() }
        // Generate deterministic 64-char hex pubkey identifier for offline member
        val ghostPubkey = HashUtil.sha256Hex("ghost:$groupId:${cleanName.lowercase()}")

        database.memberDao().insertMember(
            MemberEntity(
                groupId = groupId,
                pubkey = ghostPubkey,
                displayName = cleanName,
                upiId = cleanUpi
            )
        )

        val joinBlock = BlockchainEngine.createMemberJoinBlock(
            groupId = groupId,
            prevBlock = latestBlock,
            newMemberPubkey = ghostPubkey,
            newMemberName = cleanName,
            authorPubkey = identity.publicKeyHex,
            authorPrivateKeyHex = privKeyHex,
            upiId = cleanUpi
        )

        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(joinBlock, isVerified = true))
        syncManager.broadcastBlock(joinBlock, groupEntity.groupKeyBase64)
    }

    /**
     * Updates the monthly / group spending budget target.
     */
    suspend fun updateGroupBudget(groupId: String, budget: Double) {
        database.groupDao().updateGroupBudget(groupId, budget.coerceAtLeast(0.0))
    }

    /**
     * Archives or unarchives a group.
     */
    suspend fun archiveGroup(groupId: String, isArchived: Boolean = true) {
        database.groupDao().setGroupArchived(groupId, isArchived)
    }

    /**
     * Completely deletes a group, its members, and all its transaction blocks.
     */
    suspend fun deleteGroup(groupId: String) {
        database.blockDao().deleteBlocksForGroup(groupId)
        database.memberDao().deleteMembersForGroup(groupId)
        database.groupDao().deleteGroup(groupId)
    }

    /**
     * Updates the local UPI ID for a member in a group.
     */
    suspend fun updateMemberUpiId(groupId: String, pubkey: String, upiId: String) {
        val cleanUpi = upiId.trim()
        database.memberDao().updateMemberUpiId(groupId, pubkey, cleanUpi)
    }

    /**
     * Adds an expense, signs the block, appends to local chain, and broadcasts.
     */
    suspend fun addExpense(
        groupId: String,
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
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return

        val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return
        val prevBlock = latestBlockEntity.toDomainBlock()

        val expenseBlock = BlockchainEngine.createExpenseBlock(
            groupId = groupId,
            prevBlock = prevBlock,
            description = description,
            totalAmount = amount,
            currency = currency,
            payerPubkey = payerPubkey,
            splits = splits,
            category = category,
            note = note,
            authorPubkey = identity.publicKeyHex,
            authorPrivateKeyHex = privKeyHex,
            splitType = splitType,
            payers = payers
        )

        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(expenseBlock, isVerified = true))
        syncManager.broadcastBlock(expenseBlock, groupEntity.groupKeyBase64)
    }

    /**
     * Voids / reverses a previously recorded expense by appending a signed EXPENSE_VOID block.
     */
    suspend fun voidExpense(
        groupId: String,
        targetExpenseId: String,
        targetDescription: String,
        reason: String = "Voided by member"
    ) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return

        val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return
        val prevBlock = latestBlockEntity.toDomainBlock()

        val voidBlock = BlockchainEngine.createExpenseVoidBlock(
            groupId = groupId,
            prevBlock = prevBlock,
            targetExpenseId = targetExpenseId,
            targetDescription = targetDescription,
            reason = reason,
            authorPubkey = identity.publicKeyHex,
            authorPrivateKeyHex = privKeyHex
        )

        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(voidBlock, isVerified = true))
        syncManager.broadcastBlock(voidBlock, groupEntity.groupKeyBase64)
    }

    /**
     * Records a debt settlement between two users.
     */
    suspend fun recordSettlement(
        groupId: String,
        payerPubkey: String,
        payeePubkey: String,
        amount: Double,
        currency: String
    ) {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return

        val latestBlockEntity = database.blockDao().getLatestBlockForGroup(groupId) ?: return
        val prevBlock = latestBlockEntity.toDomainBlock()

        val settlementBlock = BlockchainEngine.createSettlementBlock(
            groupId = groupId,
            prevBlock = prevBlock,
            payerPubkey = payerPubkey,
            payeePubkey = payeePubkey,
            amount = amount,
            currency = currency,
            note = "Direct Settlement",
            authorPubkey = identity.publicKeyHex,
            authorPrivateKeyHex = privKeyHex
        )

        database.blockDao().insertBlock(BlockEntity.fromDomainBlock(settlementBlock, isVerified = true))
        syncManager.broadcastBlock(settlementBlock, groupEntity.groupKeyBase64)
    }

    /**
     * Generates a shareable URL-safe invite string containing group credentials.
     */
    suspend fun generateInviteCode(groupId: String): String {
        val group = database.groupDao().getGroupByIdSync(groupId) ?: return ""
        val blocks = database.blockDao().getBlocksForGroupSync(groupId)
        val genesisBlock = blocks.firstOrNull { it.index == 0L }?.toDomainBlock()
        val genesisJson = genesisBlock?.let { json.encodeToString(Block.serializer(), it) }
        val members = database.memberDao().getMembersForGroupSync(groupId)
        val creatorName = members.firstOrNull { it.pubkey == group.creatorPubkey }?.displayName ?: "Creator"

        val inviteData = GroupInviteData(
            groupId = group.id,
            groupName = group.name,
            currency = group.currency,
            groupKeyBase64 = group.groupKeyBase64,
            creatorPubkey = group.creatorPubkey,
            creatorDisplayName = creatorName,
            genesisBlockJson = genesisJson
        )
        val jsonStr = json.encodeToString(inviteData)
        return "peersplit://" + Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /**
     * Joins a group via invite string / QR code, registers member, and monitors sync.
     */
    suspend fun joinGroup(inviteUri: String): Result<String> {
        return try {
            val base64Payload = inviteUri.removePrefix("peersplit://").trim()
            val jsonBytes = Base64.decode(base64Payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val inviteData = json.decodeFromString(GroupInviteData.serializer(), String(jsonBytes, Charsets.UTF_8))

            val existing = database.groupDao().getGroupByIdSync(inviteData.groupId)
            if (existing == null) {
                database.groupDao().insertGroup(
                    GroupEntity(
                        id = inviteData.groupId,
                        name = inviteData.groupName,
                        currency = inviteData.currency,
                        creatorPubkey = inviteData.creatorPubkey,
                        groupKeyBase64 = inviteData.groupKeyBase64
                    )
                )
            }

            // 1. Insert Genesis Block if available
            var genesisBlock: Block? = null
            if (!inviteData.genesisBlockJson.isNullOrBlank()) {
                val block = json.decodeFromString(Block.serializer(), inviteData.genesisBlockJson)
                database.blockDao().insertBlock(BlockEntity.fromDomainBlock(block, isVerified = true))
                genesisBlock = block
            }

            // 2. Insert Creator Member
            database.memberDao().insertMember(
                MemberEntity(
                    groupId = inviteData.groupId,
                    pubkey = inviteData.creatorPubkey,
                    displayName = inviteData.creatorDisplayName
                )
            )

            // 3. Insert Current User Member & clean up matching ghost member
            val identity = KeyManager.getOrCreateIdentity(context)
            val privKeyHex = KeyManager.getPrivateKeyHex(context)

            val ghostPubkeyHash = HashUtil.sha256Hex("ghost:${inviteData.groupId}:${identity.displayName.trim().lowercase()}")
            val existingMembers = database.memberDao().getMembersForGroupSync(inviteData.groupId)
            val ghostMatch = existingMembers.firstOrNull {
                it.pubkey == ghostPubkeyHash || 
                ((it.pubkey.startsWith("ghost:") || it.displayName.equals(identity.displayName.trim(), ignoreCase = true)) && it.pubkey != identity.publicKeyHex)
            }
            if (ghostMatch != null) {
                database.memberDao().deleteMember(inviteData.groupId, ghostMatch.pubkey)
            }

            database.memberDao().insertMember(
                MemberEntity(
                    groupId = inviteData.groupId,
                    pubkey = identity.publicKeyHex,
                    displayName = identity.displayName,
                    upiId = identity.upiId
                )
            )

            // 4. Start monitoring Nostr relays for the group's ledger blocks
            syncManager.monitorGroup(inviteData.groupId)

            // 5. Broadcast Member Joined block if joining a group created by someone else
            val currentTip = database.blockDao().getLatestBlockForGroup(inviteData.groupId)?.toDomainBlock()
                ?: genesisBlock

            if (currentTip != null && identity.publicKeyHex != inviteData.creatorPubkey) {
                val joinBlock = BlockchainEngine.createMemberJoinBlock(
                    groupId = inviteData.groupId,
                    prevBlock = currentTip,
                    newMemberPubkey = identity.publicKeyHex,
                    newMemberName = identity.displayName,
                    authorPubkey = identity.publicKeyHex,
                    authorPrivateKeyHex = privKeyHex,
                    upiId = identity.upiId
                )
                database.blockDao().insertBlock(BlockEntity.fromDomainBlock(joinBlock, isVerified = true))
                syncManager.broadcastBlock(joinBlock, inviteData.groupKeyBase64)
            }

            Result.success(inviteData.groupId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractExpenses(blocks: List<Block>): List<ExpensePayload> {
        return blocks.filter { it.blockType == BlockType.EXPENSE }.mapNotNull { block ->
            try {
                json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun extractVoidedExpenseIds(blocks: List<Block>): Set<String> {
        return blocks.filter { it.blockType == BlockType.EXPENSE_VOID }.mapNotNull { block ->
            try {
                json.decodeFromString(ExpenseVoidPayload.serializer(), block.payloadJson).targetExpenseId
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }

    private fun extractSettlements(blocks: List<Block>): List<SettlementPayload> {
        return blocks.filter { it.blockType == BlockType.SETTLEMENT }.mapNotNull { block ->
            try {
                json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
            } catch (e: Exception) {
                null
            }
        }
    }
}
