package com.peersplit.app.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.text.Normalizer

/**
 * Deterministic binary canonical serializer for PeerSplit V2 operations.
 *
 * Guarantees identical byte output across all devices, OS versions, and locales by:
 * 1. Normalizing all strings to Unicode NFC and encoding as length-prefixed UTF-8.
 * 2. Sorting all `parents` lists and `Map` entries (`splits`, `payers`, `ghostAliases`) lexicographically by UTF-8 key.
 * 3. Rejecting duplicate `parents` IDs.
 * 4. Writing all integers (`Int`, `Long`) in explicit big-endian two's complement representation.
 * 5. Never relying on JSON field ordering or floating-point numbers.
 */
object OperationCanonicalizer {

    /**
     * Normalizes a string to Unicode NFC and validates that it contains no illegal control characters
     * (such as NUL `\u0000`).
     */
    fun normalizeString(input: String, allowNewlines: Boolean = false): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC)
        for (ch in normalized) {
            if (ch == '\u0000') {
                throw IllegalArgumentException("String contains forbidden NUL character")
            }
            if (ch.isISOControl() && ch != '\t' && !(allowNewlines && (ch == '\n' || ch == '\r'))) {
                throw IllegalArgumentException("String contains forbidden control character U+${ch.code.toString(16)}")
            }
        }
        return normalized
    }

    /**
     * Produces the canonical unsigned byte representation of an operation's fields.
     */
    fun canonicalizeUnsigned(
        protocolVersion: Int,
        groupId: String,
        authorPublicKey: String,
        parents: List<String>,
        logicalClock: Long,
        keyEpoch: Int,
        payload: OperationPayload,
        createdAtMillis: Long = 0L
    ): ByteArray {
        if (!ProtocolVersion.isSupported(protocolVersion)) {
            throw IllegalArgumentException("Unsupported protocol version: $protocolVersion")
        }
        val uniqueParents = parents.map { normalizeString(it.trim().lowercase()) }
        if (uniqueParents.size != uniqueParents.toSet().size) {
            throw IllegalArgumentException("Duplicate parent operation IDs are forbidden")
        }
        val sortedParents = uniqueParents.sorted()

        val bos = ByteArrayOutputStream(512)
        DataOutputStream(bos).use { out ->
            out.writeInt(protocolVersion)
            writeString(out, normalizeString(groupId.trim()))
            writeString(out, normalizeString(authorPublicKey.trim().lowercase()))
            out.writeInt(sortedParents.size)
            for (parentId in sortedParents) {
                writeString(out, parentId)
            }
            out.writeLong(logicalClock)
            out.writeInt(keyEpoch)
            out.writeLong(createdAtMillis)
            writePayload(out, payload)
        }
        return bos.toByteArray()
    }

    /**
     * Produces the canonical unsigned byte representation of an existing [SignedOperation].
     */
    fun canonicalizeUnsigned(operation: SignedOperation): ByteArray {
        return canonicalizeUnsigned(
            protocolVersion = operation.protocolVersion,
            groupId = operation.groupId,
            authorPublicKey = operation.authorPublicKey,
            parents = operation.parents,
            logicalClock = operation.logicalClock,
            keyEpoch = operation.keyEpoch,
            payload = operation.payload,
            createdAtMillis = operation.createdAtMillis
        )
    }


    private fun writePayload(out: DataOutputStream, payload: OperationPayload) {
        when (payload) {
            is OperationPayload.GenesisCreated -> {
                writeString(out, "GENESIS_CREATED")
                writeString(out, normalizeString(payload.groupId.trim()))
                writeString(out, normalizeString(payload.groupName.trim()))
                writeString(out, normalizeString(payload.currency.trim().uppercase()))
                writeString(out, normalizeString(payload.creatorPublicKey.trim().lowercase()))
                out.writeInt(payload.initialKeyEpoch)
                val sortedMembers = payload.initialMembers.sortedBy { it.publicKey.trim().lowercase() }
                out.writeInt(sortedMembers.size)
                for (member in sortedMembers) {
                    writeMemberInfo(out, member)
                }
            }

            is OperationPayload.ExpenseCreated -> {
                writeString(out, "EXPENSE_CREATED")
                writeExpenseCreatedBody(out, payload)
            }

            is OperationPayload.ExpenseUpdated -> {
                writeString(out, "EXPENSE_UPDATED")
                writeString(out, normalizeString(payload.expenseId.trim()))
                writeString(out, normalizeString(payload.expectedRevisionId.trim().lowercase()))
                writeExpenseCreatedBody(out, payload.replacement)
            }

            is OperationPayload.ExpenseVoided -> {
                writeString(out, "EXPENSE_VOIDED")
                writeString(out, normalizeString(payload.expenseId.trim()))
                writeString(out, normalizeString(payload.expectedRevisionId.trim().lowercase()))
                writeString(out, normalizeString(payload.reason.trim(), allowNewlines = true))
            }

            is OperationPayload.SettlementRecorded -> {
                writeString(out, "SETTLEMENT_RECORDED")
                writeSettlementBody(out, payload)
            }

            is OperationPayload.MemberAdded -> {
                writeString(out, "MEMBER_ADDED")
                writeString(out, normalizeString(payload.memberPublicKey.trim().lowercase()))
                writeString(out, normalizeString(payload.displayName.trim()))
                writeString(out, normalizeString(payload.role.trim().uppercase()))
                writeNullableString(out, payload.upiId?.trim())
                writeNullableString(out, payload.phone?.trim())
                writeNullableString(out, payload.claimedGhostPubkey?.trim()?.lowercase())
                out.writeInt(payload.newKeyEpoch)
                writeNullableInvitationGrant(out, payload.invitationGrant)
            }

            is OperationPayload.MemberRemoved -> {
                writeString(out, "MEMBER_REMOVED")
                writeString(out, normalizeString(payload.memberPublicKey.trim().lowercase()))
                writeString(out, normalizeString(payload.reason.trim(), allowNewlines = true))
                out.writeInt(payload.newKeyEpoch)
            }

            is OperationPayload.LegacyV1Checkpoint -> {
                writeString(out, "LEGACY_V1_CHECKPOINT")
                writeString(out, normalizeString(payload.v1TipBlockHash.trim().lowercase()))
                out.writeLong(payload.v1BlockCount)
                out.writeBoolean(payload.isLegacyChainFullyVerified)
                writeString(out, normalizeString(payload.currency.trim().uppercase()))

                val sortedMembers = payload.members.sortedBy { it.publicKey.trim().lowercase() }
                out.writeInt(sortedMembers.size)
                for (m in sortedMembers) {
                    writeMemberInfo(out, m)
                }

                val sortedExpenses = payload.activeExpenses.sortedBy { it.expenseId.trim() }
                out.writeInt(sortedExpenses.size)
                for (exp in sortedExpenses) {
                    writeExpenseCreatedBody(out, exp)
                }

                val sortedVoided = payload.voidedExpenseIds.map { normalizeString(it.trim()) }.sorted()
                out.writeInt(sortedVoided.size)
                for (vId in sortedVoided) {
                    writeString(out, vId)
                }

                val sortedSettlements = payload.settlements.sortedBy { it.settlementId.trim() }
                out.writeInt(sortedSettlements.size)
                for (stl in sortedSettlements) {
                    writeSettlementBody(out, stl)
                }

                writeStringMap(out, payload.ghostAliases)
            }
        }
    }

    private fun writeExpenseCreatedBody(out: DataOutputStream, exp: OperationPayload.ExpenseCreated) {
        writeString(out, normalizeString(exp.expenseId.trim()))
        out.writeLong(exp.amountMinor)
        writeString(out, normalizeString(exp.currency.trim().uppercase()))
        writeString(out, normalizeString(exp.paidBy.trim().lowercase()))
        writeLongMap(out, exp.splits)
        writeString(out, normalizeString(exp.description.trim()))
        writeLongMap(out, exp.payers)
        writeString(out, normalizeString(exp.category.trim()))
        writeString(out, normalizeString(exp.note.trim(), allowNewlines = true))
        writeString(out, normalizeString(exp.splitType.trim().uppercase()))
        out.writeLong(exp.expenseDateMillis)
    }

    private fun writeSettlementBody(out: DataOutputStream, stl: OperationPayload.SettlementRecorded) {
        writeString(out, normalizeString(stl.settlementId.trim()))
        writeString(out, normalizeString(stl.fromMember.trim().lowercase()))
        writeString(out, normalizeString(stl.toMember.trim().lowercase()))
        out.writeLong(stl.amountMinor)
        writeString(out, normalizeString(stl.currency.trim().uppercase()))
        writeString(out, normalizeString(stl.note.trim(), allowNewlines = true))
    }

    private fun writeMemberInfo(out: DataOutputStream, member: ProtocolMemberInfo) {
        writeString(out, normalizeString(member.publicKey.trim().lowercase()))
        writeString(out, normalizeString(member.displayName.trim()))
        writeString(out, normalizeString(member.role.trim().uppercase()))
        writeNullableString(out, member.upiId?.trim())
        writeNullableString(out, member.phone?.trim())
        writeNullableString(out, member.claimedGhostPubkey?.trim()?.lowercase())
    }

    private fun writeLongMap(out: DataOutputStream, map: Map<String, Long>) {
        val sortedEntries = map.entries
            .map { normalizeString(it.key.trim().lowercase()) to it.value }
            .sortedBy { it.first }
        out.writeInt(sortedEntries.size)
        for ((k, v) in sortedEntries) {
            writeString(out, k)
            out.writeLong(v)
        }
    }

    private fun writeStringMap(out: DataOutputStream, map: Map<String, String>) {
        val sortedEntries = map.entries
            .map { normalizeString(it.key.trim().lowercase()) to normalizeString(it.value.trim().lowercase()) }
            .sortedBy { it.first }
        out.writeInt(sortedEntries.size)
        for ((k, v) in sortedEntries) {
            writeString(out, k)
            writeString(out, v)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        if (value.isNullOrBlank()) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            writeString(out, normalizeString(value))
        }
    }

    private fun writeNullableInvitationGrant(out: DataOutputStream, grant: MembershipInvitationGrant?) {
        if (grant == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            writeString(out, normalizeString(grant.inviterPublicKey.trim().lowercase()))
            writeString(out, normalizeString(grant.invitedPublicKey.trim().lowercase()))
            writeString(out, normalizeString(grant.groupId.trim()))
            writeString(out, normalizeString(grant.targetRole.trim().uppercase()))
            writeString(out, normalizeString(grant.invitationNonce.trim().lowercase()))
            out.writeLong(grant.expiresAtMillis)
            writeString(out, normalizeString(grant.adminSignature.trim().lowercase()))
        }
    }

    /**
     * Produces the canonical domain-separated digest message signed by an admin for a membership grant.
     */
    fun canonicalizeInvitationGrantMessage(
        inviterPublicKey: String,
        invitedPublicKey: String,
        groupId: String,
        targetRole: String,
        invitationNonce: String,
        expiresAtMillis: Long
    ): ByteArray {
        val bos = ByteArrayOutputStream(256)
        DataOutputStream(bos).use { out ->
            writeString(out, "PeerSplit/V2/MembershipInvite")
            writeString(out, normalizeString(inviterPublicKey.trim().lowercase()))
            writeString(out, normalizeString(invitedPublicKey.trim().lowercase()))
            writeString(out, normalizeString(groupId.trim()))
            writeString(out, normalizeString(targetRole.trim().uppercase()))
            writeString(out, normalizeString(invitationNonce.trim().lowercase()))
            out.writeLong(expiresAtMillis)
        }
        return bos.toByteArray()
    }
}
