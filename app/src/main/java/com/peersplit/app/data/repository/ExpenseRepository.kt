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
import com.peersplit.app.blockchain.MemberInfo
import com.peersplit.app.blockchain.MemberJoinedPayload
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    val genesisBlockJson: String? = null,
    val blocksJson: List<String> = emptyList()
)

data class GroupInvitePreview(
    val groupId: String,
    val groupName: String,
    val currency: String,
    val creatorDisplayName: String,
    val unclaimedGhostMembers: List<MemberInfo>,
    val expenseCount: Int
)

class ExpenseRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val syncManager: P2PSyncManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isGhostPubkey(groupId: String, pubkey: String, displayName: String): Boolean {
        val expectedHash = HashUtil.sha256Hex("ghost:$groupId:${displayName.trim().lowercase()}")
        return pubkey == expectedHash || pubkey.startsWith("ghost:")
    }

    /**
     * Builds a comprehensive map of [ghostPubkey -> realMemberPubkey] from:
     * 1. Explicit `claimedGhostPubkey` recorded in MEMBER_JOIN blocks on the chain.
     * 2. Matching normalized 10-digit phone number between an offline ghost member and a real member.
     * 3. Matching displayName between an offline ghost member and a real member.
     */
    private fun buildAliasMap(
        groupId: String,
        memberEntities: List<MemberEntity>,
        blocks: List<Block>,
        currentIdentityPubkey: String,
        currentIdentityName: String,
        currentIdentityPhone: String? = null
    ): Map<String, String> {
        val aliasMap = mutableMapOf<String, String>()

        // 1. Explicit claims recorded in MEMBER_JOIN blocks
        for (block in blocks) {
            if (block.blockType == BlockType.MEMBER_JOIN) {
                try {
                    val payload = json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson)
                    if (!payload.claimedGhostPubkey.isNullOrBlank() && payload.claimedGhostPubkey != payload.pubkey) {
                        aliasMap[payload.claimedGhostPubkey] = payload.pubkey
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 2. Real members claiming ghost members by 10-digit phone number FIRST, then by displayName
        val realMembers = memberEntities.filter { !isGhostPubkey(groupId, it.pubkey, it.displayName) }
        val ghostMembers = memberEntities.filter { isGhostPubkey(groupId, it.pubkey, it.displayName) }
        val normMyPhone = KeyManager.normalizePhone(currentIdentityPhone)

        for (ghost in ghostMembers) {
            if (ghost.pubkey in aliasMap) continue
            val normGhostPhone = KeyManager.normalizePhone(ghost.phone)
            if (normGhostPhone.length == 10) {
                val matchingByPhone = realMembers.firstOrNull {
                    KeyManager.normalizePhone(it.phone) == normGhostPhone
                }
                if (matchingByPhone != null) {
                    aliasMap[ghost.pubkey] = matchingByPhone.pubkey
                    continue
                } else if (normMyPhone == normGhostPhone) {
                    aliasMap[ghost.pubkey] = currentIdentityPubkey
                    continue
                }
            }

            val matchingReal = realMembers.firstOrNull {
                it.displayName.trim().equals(ghost.displayName.trim(), ignoreCase = true)
            }
            if (matchingReal != null) {
                aliasMap[ghost.pubkey] = matchingReal.pubkey
            } else if (ghost.displayName.trim().equals(currentIdentityName.trim(), ignoreCase = true)) {
                aliasMap[ghost.pubkey] = currentIdentityPubkey
            }
        }

        return aliasMap
    }

    /**
     * Observes all groups with dynamic user balance computation.
     */
    fun getGroups(): Flow<List<Group>> {
        return combine(
            database.groupDao().getAllGroups(),
            database.memberDao().getAllMembersCountFlow(),
            database.blockDao().getAllBlocksCountFlow()
        ) { groupEntities, _, _ ->
            val identity = KeyManager.getOrCreateIdentity(context)
            groupEntities.map { entity ->
                val blocks = database.blockDao().getBlocksForGroupSync(entity.id).map { it.toDomainBlock() }
                val rawMembers = database.memberDao().getMembersForGroupSync(entity.id)
                val aliasMap = buildAliasMap(entity.id, rawMembers, blocks, identity.publicKeyHex, identity.displayName, identity.phone)
                val deduplicatedMemberCount = rawMembers
                    .map { aliasMap[it.pubkey] ?: it.pubkey }
                    .distinct()
                    .size

                val expenses = extractExpenses(blocks)
                val settlements = extractSettlements(blocks)
                val voidedIds = extractVoidedExpenseIds(blocks)
                val netBalances = DebtSettlementEngine.calculateNetBalances(expenses, settlements, voidedIds, aliasMap)

                val userBalance = netBalances[identity.publicKeyHex] ?: 0.0
                val validation = BlockchainEngine.validateChain(blocks)

                Group(
                    id = entity.id,
                    name = entity.name,
                    currency = entity.currency,
                    creatorPubkey = entity.creatorPubkey,
                    groupKeyBase64 = entity.groupKeyBase64,
                    memberCount = deduplicatedMemberCount,
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
        return combine(
            database.groupDao().getGroupById(groupId),
            database.memberDao().getMembersForGroup(groupId),
            database.blockDao().getBlocksForGroup(groupId)
        ) { groupEntity, memberEntities, blockEntities ->
            if (groupEntity == null) return@combine null
            val identity = KeyManager.getOrCreateIdentity(context)

            val domainBlocks = blockEntities.map { it.toDomainBlock() }
            val expensesPayloads = extractExpenses(domainBlocks)
            val settlementPayloads = extractSettlements(domainBlocks)
            val voidedIds = extractVoidedExpenseIds(domainBlocks)

            val aliasMap = buildAliasMap(
                groupId = groupId,
                memberEntities = memberEntities,
                blocks = domainBlocks,
                currentIdentityPubkey = identity.publicKeyHex,
                currentIdentityName = identity.displayName,
                currentIdentityPhone = identity.phone
            )
            val resolvePubkey = { pk: String -> aliasMap[pk] ?: pk }

            val netBalances = DebtSettlementEngine.calculateNetBalances(
                expensesPayloads,
                settlementPayloads,
                voidedIds,
                aliasMap
            )
            val suggestions = DebtSettlementEngine.simplifyDebts(netBalances)
            val validation = BlockchainEngine.validateChain(domainBlocks)

            // Build deduplicated members list (merging any claimed ghost member into the real member)
            val membersByResolvedPubkey = linkedMapOf<String, MemberEntity>()
            for (m in memberEntities) {
                val resolved = resolvePubkey(m.pubkey)
                val existing = membersByResolvedPubkey[resolved]
                if (existing == null) {
                    membersByResolvedPubkey[resolved] = m.copy(pubkey = resolved)
                } else {
                    // Prefer real member row over ghost row, while preserving UPI ID and phone if set on ghost row
                    val isCurrentReal = m.pubkey == resolved
                    val chosenName = if (isCurrentReal) m.displayName else existing.displayName
                    val chosenUpi = m.upiId?.takeIf { it.isNotBlank() } ?: existing.upiId
                    val chosenPhone = m.phone?.takeIf { it.isNotBlank() } ?: existing.phone
                    membersByResolvedPubkey[resolved] = existing.copy(
                        pubkey = resolved,
                        displayName = chosenName,
                        upiId = chosenUpi,
                        phone = chosenPhone
                    )
                }
            }

            val memberMap = mutableMapOf<String, String>()
            for ((resolvedPk, mEntity) in membersByResolvedPubkey) {
                memberMap[resolvedPk] = mEntity.displayName
            }
            for ((ghostPk, realPk) in aliasMap) {
                memberMap[ghostPk] = memberMap[realPk] ?: "Member"
            }

            val members = membersByResolvedPubkey.values.map { memberEntity ->
                Member(
                    pubkey = memberEntity.pubkey,
                    displayName = memberEntity.displayName,
                    isCurrentUser = memberEntity.pubkey == identity.publicKeyHex,
                    netBalance = netBalances[memberEntity.pubkey] ?: 0.0,
                    upiId = memberEntity.upiId,
                    phone = memberEntity.phone
                )
            }

            val expenseItems = domainBlocks.filter { it.blockType == BlockType.EXPENSE || it.blockType == BlockType.SETTLEMENT }.mapNotNull { block ->
                try {
                    if (block.blockType == BlockType.EXPENSE) {
                        val exp = json.decodeFromString(ExpensePayload.serializer(), block.payloadJson)
                        val isVoided = exp.expenseId in voidedIds
                        val resolvedSplits = exp.splits.map { it.copy(userPubkey = resolvePubkey(it.userPubkey)) }
                        val resolvedPayers = exp.payers.map { it.copy(userPubkey = resolvePubkey(it.userPubkey)) }
                        val resolvedPrimaryPayer = resolvePubkey(exp.payerPubkey)

                        val userSplit = resolvedSplits.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                        val userPaid = if (resolvedPayers.size > 1) {
                            resolvedPayers.filter { it.userPubkey == identity.publicKeyHex }.sumOf { it.amount }
                        } else if (resolvedPrimaryPayer == identity.publicKeyHex) {
                            exp.totalAmount
                        } else {
                            0.0
                        }
                        val share = if (isVoided) 0.0 else (userPaid - userSplit)

                        val resolvedPayerName = if (resolvedPayers.size > 1) {
                            resolvedPayers.joinToString(" + ") { p -> memberMap[p.userPubkey] ?: "Member" }
                        } else {
                            memberMap[resolvedPrimaryPayer] ?: "Member"
                        }

                        ExpenseItem(
                            id = exp.expenseId,
                            description = exp.description,
                            totalAmount = exp.totalAmount,
                            currency = exp.currency,
                            payerPubkey = resolvedPrimaryPayer,
                            payerName = resolvedPayerName,
                            timestamp = block.timestamp,
                            userShare = share,
                            blockIndex = block.index,
                            category = exp.category,
                            note = exp.note,
                            splitType = exp.splitType,
                            splits = resolvedSplits,
                            payers = resolvedPayers,
                            blockHash = block.blockHash,
                            signature = block.signature,
                            isVoided = isVoided
                        )
                    } else { // BlockType.SETTLEMENT
                        val stl = json.decodeFromString(SettlementPayload.serializer(), block.payloadJson)
                        val payerPub = resolvePubkey(stl.payerPubkey)
                        val payeePub = resolvePubkey(stl.payeePubkey)
                        val payerName = memberMap[payerPub] ?: "Member"
                        val payeeName = memberMap[payeePub] ?: "Member"
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
                            payerPubkey = payerPub,
                            payerName = payerName,
                            timestamp = block.timestamp,
                            userShare = userShare,
                            blockIndex = block.index,
                            category = "SETTLEMENT",
                            note = stl.note,
                            splitType = "SETTLEMENT",
                            splits = listOf(SplitItem(payeePub, stl.amount)),
                            payers = listOf(SplitItem(payerPub, stl.amount)),
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
        currency: String = "INR",
        isSecret: Boolean = false,
        secretPin: String? = null,
        isPersonalSolo: Boolean = false,
        monthlyBudget: Double = 0.0,
        initialMemberNames: List<String> = emptyList()
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
            creatorUpiId = identity.upiId,
            creatorPhone = identity.phone
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
                upiId = identity.upiId,
                phone = identity.phone
            )
        )

        // Broadcast to P2P relay network
        syncManager.monitorGroup(groupId)
        syncManager.broadcastBlock(genesisBlock, groupKeyBase64)

        // Add any initial friends added during group creation (supports "Name" or "Name|Phone" or "Name|Phone|UpiId")
        if (!isPersonalSolo) {
            for (friendEntry in initialMemberNames) {
                val parts = friendEntry.split("|")
                val cleanName = parts.getOrNull(0)?.trim().orEmpty()
                val cleanPhone = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val cleanUpi = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                if (cleanName.isNotBlank()) {
                    addOfflineMember(groupId, cleanName, cleanUpi, cleanPhone)
                }
            }
        }

        return groupId
    }

    /**
     * Adds an offline / non-app member ("Ghost Member") directly to the group's blockchain ledger.
     */
    suspend fun addOfflineMember(
        groupId: String,
        displayName: String,
        upiId: String? = null,
        phone: String? = null
    ) {
        if (displayName.isBlank()) return
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)
        val groupEntity = database.groupDao().getGroupByIdSync(groupId) ?: return
        val latestBlock = database.blockDao().getLatestBlockForGroup(groupId)?.toDomainBlock() ?: return

        val cleanName = displayName.trim()
        val cleanPhone = KeyManager.normalizePhone(phone).takeIf { it.isNotBlank() }
        val cleanUpi = upiId?.trim()?.takeIf { it.isNotBlank() }
        // Generate deterministic 64-char hex pubkey identifier for offline member
        val ghostPubkey = HashUtil.sha256Hex("ghost:$groupId:${cleanName.lowercase()}")

        database.memberDao().insertMember(
            MemberEntity(
                groupId = groupId,
                pubkey = ghostPubkey,
                displayName = cleanName,
                upiId = cleanUpi,
                phone = cleanPhone
            )
        )

        val joinBlock = BlockchainEngine.createMemberJoinBlock(
            groupId = groupId,
            prevBlock = latestBlock,
            newMemberPubkey = ghostPubkey,
            newMemberName = cleanName,
            authorPubkey = identity.publicKeyHex,
            authorPrivateKeyHex = privKeyHex,
            upiId = cleanUpi,
            phone = cleanPhone
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
     * Updates both phone number and UPI ID for a member in a group.
     */
    suspend fun updateMemberContact(groupId: String, pubkey: String, phone: String?, upiId: String?) {
        val cleanPhone = KeyManager.normalizePhone(phone).takeIf { it.isNotBlank() }
        val cleanUpi = upiId?.trim()?.takeIf { it.isNotBlank() }
        database.memberDao().updateMemberContact(groupId, pubkey, cleanPhone, cleanUpi)
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

    private fun encodeInviteUri(inviteData: GroupInviteData): String {
        val jsonBytes = json.encodeToString(inviteData).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(jsonBytes) }
        val compressed = bos.toByteArray()
        return "peersplit://" + Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decodeInviteData(inviteUri: String): GroupInviteData {
        val cleanUri = Regex("peersplit://\\S+").find(inviteUri)?.value ?: inviteUri.trim()
        val base64Payload = cleanUri.removePrefix("peersplit://").trim()
        require(base64Payload.isNotBlank()) { "Empty invite code" }
        val rawBytes = Base64.decode(base64Payload, Base64.URL_SAFE or Base64.NO_WRAP)
        val jsonStr = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(rawBytes, Charsets.UTF_8)
        }
        return json.decodeFromString(GroupInviteData.serializer(), jsonStr)
    }

    /**
     * Generates a shareable URL-safe invite string containing group credentials and current ledger blocks.
     * Uses GZIP compression and caps payload size so the invite always renders cleanly as a QR code.
     */
    suspend fun generateInviteCode(groupId: String): String {
        val group = database.groupDao().getGroupByIdSync(groupId) ?: return ""
        val blocks = database.blockDao().getBlocksForGroupSync(groupId).map { it.toDomainBlock() }.sortedBy { it.index }
        val genesisBlock = blocks.firstOrNull { it.index == 0L }
        val genesisJson = genesisBlock?.let { json.encodeToString(Block.serializer(), it) }
        val allBlocksJson = blocks.map { json.encodeToString(Block.serializer(), it) }
        val members = database.memberDao().getMembersForGroupSync(groupId)
        val creatorName = members.firstOrNull { it.pubkey == group.creatorPubkey }?.displayName ?: "Creator"

        val fullInvite = GroupInviteData(
            groupId = group.id,
            groupName = group.name,
            currency = group.currency,
            groupKeyBase64 = group.groupKeyBase64,
            creatorPubkey = group.creatorPubkey,
            creatorDisplayName = creatorName,
            genesisBlockJson = if (allBlocksJson.isEmpty()) genesisJson else null,
            blocksJson = allBlocksJson
        )
        val encodedFull = encodeInviteUri(fullInvite)
        if (encodedFull.length <= 2200) {
            return encodedFull
        }

        // If group has a large history, prioritize GENESIS + MEMBER_JOIN blocks + latest expenses that fit in a QR code
        val essentialBlocks = blocks.filter {
            it.blockType == BlockType.GENESIS || it.blockType == BlockType.MEMBER_JOIN
        }
        val otherBlocks = blocks.filter {
            it.blockType != BlockType.GENESIS && it.blockType != BlockType.MEMBER_JOIN
        }
        val selectedBlocks = essentialBlocks.toMutableList()
        for (b in otherBlocks.asReversed()) {
            val candidate = (selectedBlocks + b).sortedBy { it.index }.map { json.encodeToString(Block.serializer(), it) }
            val candidateUri = encodeInviteUri(fullInvite.copy(blocksJson = candidate))
            if (candidateUri.length <= 2200) {
                selectedBlocks.add(b)
            } else {
                break
            }
        }
        val finalBlocksJson = selectedBlocks.sortedBy { it.index }.map { json.encodeToString(Block.serializer(), it) }
        val trimmedUri = encodeInviteUri(fullInvite.copy(blocksJson = finalBlocksJson))
        if (trimmedUri.length <= 2500) {
            return trimmedUri
        }

        // Ultimate fallback: Genesis block only so QR code never fails
        return encodeInviteUri(
            fullInvite.copy(
                genesisBlockJson = genesisJson,
                blocksJson = emptyList()
            )
        )
    }

    /**
     * Parses an invite URI to preview the group name, currency, and any unclaimed offline members
     * before joining.
     */
    fun parseInvitePreview(inviteUri: String): GroupInvitePreview? {
        return try {
            val inviteData = decodeInviteData(inviteUri)

            val blockStrings = if (inviteData.blocksJson.isNotEmpty()) {
                inviteData.blocksJson
            } else {
                listOfNotNull(inviteData.genesisBlockJson)
            }
            val blocks = blockStrings.mapNotNull {
                try { json.decodeFromString(Block.serializer(), it) } catch (_: Exception) { null }
            }.sortedBy { it.index }

            val claimedGhosts = mutableSetOf<String>()
            val ghostMap = linkedMapOf<String, MemberInfo>()
            var expenseCount = 0

            for (b in blocks) {
                when (b.blockType) {
                    BlockType.MEMBER_JOIN -> {
                        try {
                            val p = json.decodeFromString(MemberJoinedPayload.serializer(), b.payloadJson)
                            if (!p.claimedGhostPubkey.isNullOrBlank()) {
                                claimedGhosts.add(p.claimedGhostPubkey)
                                ghostMap.remove(p.claimedGhostPubkey)
                            }
                            if (isGhostPubkey(inviteData.groupId, p.pubkey, p.displayName) && p.pubkey !in claimedGhosts) {
                                ghostMap[p.pubkey] = MemberInfo(p.pubkey, p.displayName, p.upiId, p.phone)
                            }
                        } catch (_: Exception) {}
                    }
                    BlockType.EXPENSE -> expenseCount++
                    else -> {}
                }
            }

            GroupInvitePreview(
                groupId = inviteData.groupId,
                groupName = inviteData.groupName,
                currency = inviteData.currency,
                creatorDisplayName = inviteData.creatorDisplayName,
                unclaimedGhostMembers = ghostMap.values.toList(),
                expenseCount = expenseCount
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Joins a group via invite string / QR code, imports all embedded blocks,
     * claims matching/selected offline member, and broadcasts Member Join block.
     */
    suspend fun joinGroup(
        inviteUri: String,
        explicitClaimGhostPubkey: String? = null
    ): Result<String> {
        return try {
            val inviteData = decodeInviteData(inviteUri)

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

            // 1. Insert Creator Member first
            database.memberDao().insertMember(
                MemberEntity(
                    groupId = inviteData.groupId,
                    pubkey = inviteData.creatorPubkey,
                    displayName = inviteData.creatorDisplayName
                )
            )

            // 2. Import all blocks included in the invite link (Genesis, Member Joins, Expenses, Settlements)
            val rawBlockJsonList = if (inviteData.blocksJson.isNotEmpty()) {
                inviteData.blocksJson
            } else {
                listOfNotNull(inviteData.genesisBlockJson)
            }

            val importedBlocks = rawBlockJsonList.mapNotNull {
                try { json.decodeFromString(Block.serializer(), it) } catch (_: Exception) { null }
            }.sortedBy { it.index }

            for (block in importedBlocks) {
                if (block.verifyIntegrity()) {
                    database.blockDao().insertBlock(BlockEntity.fromDomainBlock(block, isVerified = true))
                    syncManager.processBlockSideEffects(inviteData.groupId, block)
                }
            }

            // 3. Determine if this phone should claim/assume an offline member added by the creator
            val identity = KeyManager.getOrCreateIdentity(context)
            val privKeyHex = KeyManager.getPrivateKeyHex(context)

            val allCurrentBlocks = database.blockDao().getBlocksForGroupSync(inviteData.groupId).map { it.toDomainBlock() }
            val alreadyClaimedGhosts = allCurrentBlocks
                .filter { it.blockType == BlockType.MEMBER_JOIN }
                .mapNotNull {
                    try {
                        json.decodeFromString(MemberJoinedPayload.serializer(), it.payloadJson).claimedGhostPubkey
                    } catch (_: Exception) { null }
                }
                .toSet()

            val existingMembers = database.memberDao().getMembersForGroupSync(inviteData.groupId)
            val unclaimedGhosts = existingMembers.filter {
                isGhostPubkey(inviteData.groupId, it.pubkey, it.displayName) && it.pubkey !in alreadyClaimedGhosts
            }

            val normMyPhone = KeyManager.normalizePhone(identity.phone)
            val ghostMatch: MemberEntity? = when {
                explicitClaimGhostPubkey == "NONE" -> null
                !explicitClaimGhostPubkey.isNullOrBlank() -> unclaimedGhosts.firstOrNull { it.pubkey == explicitClaimGhostPubkey }
                else -> {
                    // 1. Match by normalized 10-digit phone number first
                    val byPhone = if (normMyPhone.length == 10) {
                        unclaimedGhosts.firstOrNull { KeyManager.normalizePhone(it.phone) == normMyPhone }
                    } else null
                    // 2. Match by displayName
                    byPhone ?: unclaimedGhosts.firstOrNull {
                        it.displayName.trim().equals(identity.displayName.trim(), ignoreCase = true)
                    } ?: unclaimedGhosts.firstOrNull()
                }
            }

            val effectiveDisplayName = if (ghostMatch != null &&
                (identity.displayName.isBlank() ||
                 identity.displayName == "My Phone" ||
                 identity.displayName.trim().equals(inviteData.creatorDisplayName.trim(), ignoreCase = true))
            ) {
                KeyManager.updateDisplayName(context, ghostMatch.displayName)
                ghostMatch.displayName
            } else {
                identity.displayName
            }

            val effectivePhone = identity.phone?.takeIf { it.isNotBlank() } ?: ghostMatch?.phone?.also {
                if (it.isNotBlank()) KeyManager.updatePhone(context, it)
            }
            val effectiveUpi = identity.upiId?.takeIf { it.isNotBlank() } ?: ghostMatch?.upiId

            if (ghostMatch != null) {
                database.memberDao().deleteMember(inviteData.groupId, ghostMatch.pubkey)
            }

            database.memberDao().insertMember(
                MemberEntity(
                    groupId = inviteData.groupId,
                    pubkey = identity.publicKeyHex,
                    displayName = effectiveDisplayName,
                    upiId = effectiveUpi,
                    phone = effectivePhone
                )
            )

            // 4. Start monitoring Nostr relays for the group's ledger blocks
            syncManager.monitorGroup(inviteData.groupId)

            // 5. Broadcast Member Joined block on top of the latest block tip (if not already joined)
            val alreadyJoinedOnChain = allCurrentBlocks.any { block ->
                block.blockType == BlockType.MEMBER_JOIN && try {
                    json.decodeFromString(MemberJoinedPayload.serializer(), block.payloadJson).pubkey == identity.publicKeyHex
                } catch (_: Exception) { false }
            }

            val currentTip = database.blockDao().getLatestBlockForGroup(inviteData.groupId)?.toDomainBlock()
                ?: importedBlocks.lastOrNull()

            if (currentTip != null && identity.publicKeyHex != inviteData.creatorPubkey && !alreadyJoinedOnChain) {
                val joinBlock = BlockchainEngine.createMemberJoinBlock(
                    groupId = inviteData.groupId,
                    prevBlock = currentTip,
                    newMemberPubkey = identity.publicKeyHex,
                    newMemberName = effectiveDisplayName,
                    authorPubkey = identity.publicKeyHex,
                    authorPrivateKeyHex = privKeyHex,
                    upiId = effectiveUpi,
                    claimedGhostPubkey = ghostMatch?.pubkey,
                    phone = effectivePhone
                )
                database.blockDao().insertBlock(BlockEntity.fromDomainBlock(joinBlock, isVerified = true))
                syncManager.broadcastBlock(joinBlock, inviteData.groupKeyBase64)
            }

            // Trigger full chain sync with peers
            syncManager.syncGroupNow(inviteData.groupId)

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
