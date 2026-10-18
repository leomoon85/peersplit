package com.peersplit.protocol

/**
 * Detailed validation result for structural, schema, and financial invariants of a [SignedOperation].
 */
sealed class OperationValidationOutcome {
    object Valid : OperationValidationOutcome()
    data class Invalid(val code: String, val message: String) : OperationValidationOutcome()
}

/**
 * Validates structural limits, causal reference formatting, and exact integer financial invariants
 * for PeerSplit V2 operations.
 */
object OperationValidator {

    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

    fun validate(operation: SignedOperation, expectedGroupCurrency: String? = null): OperationValidationOutcome {
        // 1. Protocol Version
        if (!ProtocolVersion.isSupported(operation.protocolVersion)) {
            return OperationValidationOutcome.Invalid(
                "UNSUPPORTED_PROTOCOL_VERSION",
                "Unsupported protocol version: ${operation.protocolVersion}"
            )
        }

        // 2. Envelope Metadata
        if (operation.groupId.isBlank() || operation.groupId.length > ProtocolVersion.MAX_ID_LENGTH) {
            return OperationValidationOutcome.Invalid("INVALID_GROUP_ID", "Invalid groupId")
        }
        if (!HEX_64_REGEX.matches(operation.authorPublicKey)) {
            return OperationValidationOutcome.Invalid("INVALID_AUTHOR_PUBKEY", "Author public key must be 64-char lowercase hex")
        }
        if (operation.logicalClock < 1L) {
            return OperationValidationOutcome.Invalid("INVALID_LOGICAL_CLOCK", "logicalClock must be >= 1")
        }
        if (operation.keyEpoch < 0) {
            return OperationValidationOutcome.Invalid("INVALID_KEY_EPOCH", "keyEpoch must be >= 0")
        }
        if (operation.protocolVersion >= ProtocolVersion.V3_SIGNED_OPERATIONS) {
            if (operation.createdAtMillis <= 0L) {
                return OperationValidationOutcome.Invalid("INVALID_TIMESTAMP", "createdAtMillis must be > 0")
            }
        }


        // 3. Parents Validation
        if (operation.parents.size > ProtocolVersion.MAX_PARENTS) {
            return OperationValidationOutcome.Invalid("TOO_MANY_PARENTS", "Exceeds max parents (${ProtocolVersion.MAX_PARENTS})")
        }
        if (operation.parents.size != operation.parents.toSet().size) {
            return OperationValidationOutcome.Invalid("DUPLICATE_PARENTS", "Duplicate parent IDs are not allowed")
        }
        for (parentId in operation.parents) {
            if (!OperationId.isValidFormat(parentId)) {
                return OperationValidationOutcome.Invalid("INVALID_PARENT_ID", "Malformed parent operationId: $parentId")
            }
            if (parentId == operation.operationId) {
                return OperationValidationOutcome.Invalid("SELF_PARENT_CYCLE", "Operation cannot reference itself as a parent")
            }
        }

        val isRootPayload = operation.payload is OperationPayload.GenesisCreated ||
            operation.payload is OperationPayload.LegacyV1Checkpoint
        if (isRootPayload && operation.parents.isNotEmpty()) {
            return OperationValidationOutcome.Invalid("ROOT_HAS_PARENTS", "Genesis/Checkpoint operation must have empty parents")
        }
        if (!isRootPayload && operation.parents.isEmpty()) {
            return OperationValidationOutcome.Invalid("MISSING_PARENTS", "Non-root operation must reference at least one causal parent")
        }

        // 4. Cryptographic Integrity (operationId + Ed25519 signature)
        if (!OperationVerifier.hasValidOperationId(operation)) {
            return OperationValidationOutcome.Invalid("INVALID_OPERATION_ID", "operationId does not match canonical payload hash")
        }
        if (!OperationVerifier.hasValidSignature(operation)) {
            return OperationValidationOutcome.Invalid("INVALID_SIGNATURE", "Ed25519 signature verification failed")
        }

        // 5. Payload-specific Domain & Financial Invariants
        return validatePayload(operation, expectedGroupCurrency)
    }

    private fun validatePayload(
        operation: SignedOperation,
        expectedGroupCurrency: String?
    ): OperationValidationOutcome {
        return when (val p = operation.payload) {
            is OperationPayload.GenesisCreated -> {
                if (p.groupId != operation.groupId) {
                    return OperationValidationOutcome.Invalid("GENESIS_GROUP_MISMATCH", "Genesis groupId mismatch")
                }
                if (p.creatorPublicKey != operation.authorPublicKey) {
                    return OperationValidationOutcome.Invalid("GENESIS_CREATOR_MISMATCH", "Genesis creator must match authorPublicKey")
                }
                if (p.groupName.isBlank() || p.groupName.length > ProtocolVersion.MAX_STRING_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_GROUP_NAME", "Invalid groupName")
                }
                if (!isValidCurrency(p.currency, expectedGroupCurrency)) {
                    return OperationValidationOutcome.Invalid("INVALID_CURRENCY", "Unsupported or mismatched currency: ${p.currency}")
                }
                if (p.initialMembers.isEmpty() || p.initialMembers.size > ProtocolVersion.MAX_MEMBERS_PER_OP) {
                    return OperationValidationOutcome.Invalid("INVALID_INITIAL_MEMBERS", "Initial members list is empty or too large")
                }
                if (p.initialMembers.none { it.publicKey == p.creatorPublicKey }) {
                    return OperationValidationOutcome.Invalid("CREATOR_NOT_IN_MEMBERS", "Creator must be included in initialMembers")
                }
                for (m in p.initialMembers) {
                    val mCheck = validateMemberInfo(m)
                    if (mCheck !is OperationValidationOutcome.Valid) return mCheck
                }
                OperationValidationOutcome.Valid
            }

            is OperationPayload.ExpenseCreated -> validateExpenseCreated(p, expectedGroupCurrency)

            is OperationPayload.ExpenseUpdated -> {
                if (p.expenseId.isBlank() || p.expenseId.length > ProtocolVersion.MAX_ID_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_EXPENSE_ID", "Invalid expenseId")
                }
                if (p.replacement.expenseId != p.expenseId) {
                    return OperationValidationOutcome.Invalid("EXPENSE_ID_MISMATCH", "Replacement expenseId must match target expenseId")
                }
                if (!OperationId.isValidFormat(p.expectedRevisionId)) {
                    return OperationValidationOutcome.Invalid("INVALID_REVISION_ID", "expectedRevisionId must be a valid 64-char hex operationId")
                }
                validateExpenseCreated(p.replacement, expectedGroupCurrency)
            }

            is OperationPayload.ExpenseVoided -> {
                if (p.expenseId.isBlank() || p.expenseId.length > ProtocolVersion.MAX_ID_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_EXPENSE_ID", "Invalid expenseId")
                }
                if (!OperationId.isValidFormat(p.expectedRevisionId)) {
                    return OperationValidationOutcome.Invalid("INVALID_REVISION_ID", "expectedRevisionId must be a valid 64-char hex operationId")
                }
                if (p.reason.isBlank() || p.reason.length > ProtocolVersion.MAX_STRING_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_VOID_REASON", "Invalid void reason")
                }
                OperationValidationOutcome.Valid
            }

            is OperationPayload.SettlementRecorded -> validateSettlement(p, expectedGroupCurrency)

            is OperationPayload.MemberAdded -> {
                if (!HEX_64_REGEX.matches(p.memberPublicKey)) {
                    return OperationValidationOutcome.Invalid("INVALID_MEMBER_PUBKEY", "memberPublicKey must be 64-char lowercase hex")
                }
                if (p.displayName.isBlank() || p.displayName.length > ProtocolVersion.MAX_STRING_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_DISPLAY_NAME", "Invalid displayName")
                }
                if (p.role !in setOf(ProtocolMemberInfo.ROLE_ADMIN, ProtocolMemberInfo.ROLE_MEMBER, ProtocolMemberInfo.ROLE_GHOST)) {
                    return OperationValidationOutcome.Invalid("INVALID_MEMBER_ROLE", "Unsupported member role: ${p.role}")
                }
                if (!p.claimedGhostPubkey.isNullOrBlank() && !HEX_64_REGEX.matches(p.claimedGhostPubkey)) {
                    return OperationValidationOutcome.Invalid("INVALID_GHOST_PUBKEY", "claimedGhostPubkey must be 64-char lowercase hex")
                }
                val grant = p.invitationGrant
                if (grant != null) {
                    if (!HEX_64_REGEX.matches(grant.inviterPublicKey)) {
                        return OperationValidationOutcome.Invalid("INVALID_INVITER_PUBKEY", "inviterPublicKey must be 64-char lowercase hex")
                    }
                    if (grant.groupId != operation.groupId) {
                        return OperationValidationOutcome.Invalid("GRANT_GROUP_MISMATCH", "invitationGrant groupId does not match operation")
                    }
                    if (grant.invitationNonce.isBlank() || grant.invitationNonce.length > ProtocolVersion.MAX_ID_LENGTH) {
                        return OperationValidationOutcome.Invalid("INVALID_INVITATION_NONCE", "invitationNonce is invalid")
                    }
                    if (!OperationVerifier.verifyInvitationGrant(grant)) {
                        return OperationValidationOutcome.Invalid("INVALID_INVITATION_SIGNATURE", "Invitation grant signature verification failed")
                    }
                }
                OperationValidationOutcome.Valid
            }

            is OperationPayload.MemberRemoved -> {
                if (!HEX_64_REGEX.matches(p.memberPublicKey)) {
                    return OperationValidationOutcome.Invalid("INVALID_MEMBER_PUBKEY", "memberPublicKey must be 64-char lowercase hex")
                }
                if (p.reason.isBlank() || p.reason.length > ProtocolVersion.MAX_STRING_LENGTH) {
                    return OperationValidationOutcome.Invalid("INVALID_REMOVE_REASON", "Invalid removal reason")
                }
                OperationValidationOutcome.Valid
            }

            is OperationPayload.LegacyV1Checkpoint -> {
                if (!HEX_64_REGEX.matches(p.v1TipBlockHash)) {
                    return OperationValidationOutcome.Invalid("INVALID_V1_TIP_HASH", "Invalid v1TipBlockHash")
                }
                if (p.v1BlockCount < 1L) {
                    return OperationValidationOutcome.Invalid("INVALID_V1_BLOCK_COUNT", "v1BlockCount must be >= 1")
                }
                if (!isValidCurrency(p.currency, expectedGroupCurrency)) {
                    return OperationValidationOutcome.Invalid("INVALID_CURRENCY", "Invalid checkpoint currency: ${p.currency}")
                }
                if (p.members.isEmpty()) {
                    return OperationValidationOutcome.Invalid("EMPTY_CHECKPOINT_MEMBERS", "Checkpoint must contain members")
                }
                for (m in p.members) {
                    val mCheck = validateMemberInfo(m)
                    if (mCheck !is OperationValidationOutcome.Valid) return mCheck
                }
                for (exp in p.activeExpenses) {
                    val expCheck = validateExpenseCreated(exp, p.currency)
                    if (expCheck !is OperationValidationOutcome.Valid) return expCheck
                }
                for (stl in p.settlements) {
                    val stlCheck = validateSettlement(stl, p.currency)
                    if (stlCheck !is OperationValidationOutcome.Valid) return stlCheck
                }
                OperationValidationOutcome.Valid
            }
        }
    }

    fun validateExpenseCreated(
        exp: OperationPayload.ExpenseCreated,
        expectedGroupCurrency: String? = null
    ): OperationValidationOutcome {
        if (exp.expenseId.isBlank() || exp.expenseId.length > ProtocolVersion.MAX_ID_LENGTH) {
            return OperationValidationOutcome.Invalid("INVALID_EXPENSE_ID", "Invalid expenseId")
        }
        if (exp.description.isBlank() || exp.description.length > ProtocolVersion.MAX_STRING_LENGTH) {
            return OperationValidationOutcome.Invalid("INVALID_DESCRIPTION", "Expense description must not be blank or exceed max length")
        }
        if (exp.note.length > ProtocolVersion.MAX_NOTE_LENGTH) {
            return OperationValidationOutcome.Invalid("NOTE_TOO_LONG", "Expense note exceeds max length")
        }
        if (!isValidCurrency(exp.currency, expectedGroupCurrency)) {
            return OperationValidationOutcome.Invalid("INVALID_CURRENCY", "Invalid or mismatched currency: ${exp.currency}")
        }
        if (exp.amountMinor <= 0L || exp.amountMinor > ProtocolVersion.MAX_AMOUNT_MINOR) {
            return OperationValidationOutcome.Invalid("INVALID_EXPENSE_AMOUNT", "Expense amountMinor (${exp.amountMinor}) must be in 1..${ProtocolVersion.MAX_AMOUNT_MINOR}")
        }
        if (!HEX_64_REGEX.matches(exp.paidBy)) {
            return OperationValidationOutcome.Invalid("INVALID_PAID_BY", "paidBy must be 64-char hex pubkey")
        }
        if (exp.splits.isEmpty() || exp.splits.size > ProtocolVersion.MAX_SPLITS_PER_EXPENSE) {
            return OperationValidationOutcome.Invalid("INVALID_SPLITS_COUNT", "Expense splits must not be empty or exceed max limit")
        }

        // Exact integer arithmetic validation for splits (reject negative splits and overflow)
        var splitsTotal = 0L
        for ((memberKey, shareMinor) in exp.splits) {
            if (!HEX_64_REGEX.matches(memberKey)) {
                return OperationValidationOutcome.Invalid("INVALID_SPLIT_MEMBER", "Split member key must be 64-char hex")
            }
            if (shareMinor < 0L || shareMinor > ProtocolVersion.MAX_AMOUNT_MINOR) {
                return OperationValidationOutcome.Invalid("INVALID_SPLIT_AMOUNT", "Split share cannot be negative or exceed max amount")
            }
            splitsTotal = try {
                MoneyMath.addExact(splitsTotal, shareMinor)
            } catch (_: ArithmeticException) {
                return OperationValidationOutcome.Invalid("ARITHMETIC_OVERFLOW", "Arithmetic overflow summing expense splits")
            }
        }
        if (splitsTotal != exp.amountMinor) {
            return OperationValidationOutcome.Invalid(
                "SPLITS_SUM_MISMATCH",
                "Sum of splits ($splitsTotal) does not equal expense amountMinor (${exp.amountMinor})"
            )
        }

        // Multi-payer exact sum check if payers map is populated
        if (exp.payers.isNotEmpty()) {
            if (exp.payers.size > ProtocolVersion.MAX_PAYERS_PER_EXPENSE) {
                return OperationValidationOutcome.Invalid("TOO_MANY_PAYERS", "Too many payers in expense")
            }
            var payersTotal = 0L
            for ((payerKey, paidMinor) in exp.payers) {
                if (!HEX_64_REGEX.matches(payerKey)) {
                    return OperationValidationOutcome.Invalid("INVALID_PAYER_MEMBER", "Payer member key must be 64-char hex")
                }
                if (paidMinor < 0L || paidMinor > ProtocolVersion.MAX_AMOUNT_MINOR) {
                    return OperationValidationOutcome.Invalid("INVALID_PAYER_AMOUNT", "Payer contribution cannot be negative")
                }
                payersTotal = try {
                    MoneyMath.addExact(payersTotal, paidMinor)
                } catch (_: ArithmeticException) {
                    return OperationValidationOutcome.Invalid("ARITHMETIC_OVERFLOW", "Arithmetic overflow summing expense payers")
                }
            }
            if (payersTotal != exp.amountMinor) {
                return OperationValidationOutcome.Invalid(
                    "PAYERS_SUM_MISMATCH",
                    "Sum of payers ($payersTotal) does not equal expense amountMinor (${exp.amountMinor})"
                )
            }
        }

        return OperationValidationOutcome.Valid
    }

    fun validateSettlement(
        stl: OperationPayload.SettlementRecorded,
        expectedGroupCurrency: String? = null
    ): OperationValidationOutcome {
        if (stl.settlementId.isBlank() || stl.settlementId.length > ProtocolVersion.MAX_ID_LENGTH) {
            return OperationValidationOutcome.Invalid("INVALID_SETTLEMENT_ID", "Invalid settlementId")
        }
        if (!HEX_64_REGEX.matches(stl.fromMember) || !HEX_64_REGEX.matches(stl.toMember)) {
            return OperationValidationOutcome.Invalid("INVALID_SETTLEMENT_MEMBERS", "Settlement members must be 64-char hex pubkeys")
        }
        if (stl.fromMember == stl.toMember) {
            return OperationValidationOutcome.Invalid("SELF_SETTLEMENT", "fromMember and toMember cannot be identical")
        }
        if (stl.amountMinor <= 0L || stl.amountMinor > ProtocolVersion.MAX_AMOUNT_MINOR) {
            return OperationValidationOutcome.Invalid("INVALID_SETTLEMENT_AMOUNT", "Settlement amountMinor (${stl.amountMinor}) must be > 0")
        }
        if (!isValidCurrency(stl.currency, expectedGroupCurrency)) {
            return OperationValidationOutcome.Invalid("INVALID_CURRENCY", "Invalid or mismatched settlement currency: ${stl.currency}")
        }
        if (stl.note.length > ProtocolVersion.MAX_NOTE_LENGTH) {
            return OperationValidationOutcome.Invalid("NOTE_TOO_LONG", "Settlement note exceeds max length")
        }
        return OperationValidationOutcome.Valid
    }

    private fun validateMemberInfo(m: ProtocolMemberInfo): OperationValidationOutcome {
        if (!HEX_64_REGEX.matches(m.publicKey)) {
            return OperationValidationOutcome.Invalid("INVALID_MEMBER_PUBKEY", "Member publicKey must be 64-char hex")
        }
        if (m.displayName.isBlank() || m.displayName.length > ProtocolVersion.MAX_STRING_LENGTH) {
            return OperationValidationOutcome.Invalid("INVALID_DISPLAY_NAME", "Member displayName invalid")
        }
        return OperationValidationOutcome.Valid
    }

    private fun isValidCurrency(currency: String, expectedGroupCurrency: String?): Boolean {
        val norm = currency.trim().uppercase()
        if (norm !in ProtocolVersion.SUPPORTED_CURRENCIES) return false
        if (expectedGroupCurrency != null && norm != expectedGroupCurrency.trim().uppercase()) return false
        return true
    }
}
