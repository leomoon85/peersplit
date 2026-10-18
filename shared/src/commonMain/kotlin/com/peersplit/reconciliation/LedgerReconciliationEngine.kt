package com.peersplit.reconciliation

import com.peersplit.protocol.OperationPayload
import com.peersplit.protocol.OperationValidationOutcome
import com.peersplit.protocol.OperationValidator
import com.peersplit.protocol.ProtocolMemberInfo
import com.peersplit.protocol.SignedOperation

/**
 * Pure Kotlin interface for PeerSplit V2 deterministic DAG reconciliation.
 */
interface LedgerReconciliationEngine {
    fun reconcile(
        trustedGenesis: TrustedGenesis,
        operations: Collection<SignedOperation>
    ): ReconciliationResult
}

/**
 * Production implementation of [LedgerReconciliationEngine].
 *
 * Satisfies all PeerSplit V2 invariants:
 * - Zero Android, Room, WebSocket, or UI dependencies.
 * - Order-independent convergence: any permutation of the same valid operation set produces the
 *   exact same accepted operations, member balances, and 64-character SHA-256 `stateDigest`.
 * - Never mutates or re-signs any [SignedOperation].
 * - Enforces causal dependency resolution, topological sorting, removal-precedence membership
 *   authorization, Compare-And-Swap (`expectedRevisionId`) expense revision control, and
 *   exact 64-bit integer minor-unit accounting.
 */
object DefaultLedgerReconciliationEngine : LedgerReconciliationEngine {

    override fun reconcile(
        trustedGenesis: TrustedGenesis,
        operations: Collection<SignedOperation>
    ): ReconciliationResult {
        val normGroupId = trustedGenesis.groupId.trim()
        val normCurrency = trustedGenesis.currency.trim().uppercase()
        val normCreator = trustedGenesis.creatorPublicKey.trim().lowercase()
        val normalizedGenesis = trustedGenesis.copy(
            groupId = normGroupId,
            currency = normCurrency,
            creatorPublicKey = normCreator
        )

        val rejected = mutableListOf<RejectedOperation>()
        val rejectedIds = mutableSetOf<String>()
        val validEnvelopeOps = LinkedHashMap<String, SignedOperation>()

        // Step 1: Deduplicate by operationId (preserving first verified instance; if another instance has
        // the same operationId but corrupted signature/fields, verify each before accepting into deduplicated map)
        val sortedInput = operations.sortedWith(
            compareBy<SignedOperation> { it.operationId }.thenBy { it.signature }
        )

        for (op in sortedInput) {
            if (op.operationId in validEnvelopeOps) {
                // Already verified an authentic operation with this exact content-derived operationId
                continue
            }

            // Step 2 & 3: Verify groupId, protocolVersion, canonical encoding, operationId, signature, and payload schema
            if (op.groupId != normGroupId) {
                rejected.add(
                    RejectedOperation(op, RejectionReason.GROUP_ID_MISMATCH, "Expected groupId $normGroupId, found ${op.groupId}")
                )
                rejectedIds.add(op.operationId)
                continue
            }

            val validation = OperationValidator.validate(op, normCurrency)
            if (validation is OperationValidationOutcome.Invalid) {
                val reason = when (validation.code) {
                    "UNSUPPORTED_PROTOCOL_VERSION" -> RejectionReason.UNSUPPORTED_PROTOCOL_VERSION
                    "INVALID_OPERATION_ID" -> RejectionReason.INVALID_OPERATION_ID
                    "INVALID_SIGNATURE" -> RejectionReason.INVALID_SIGNATURE
                    "INVALID_CURRENCY" -> RejectionReason.CURRENCY_MISMATCH
                    "ARITHMETIC_OVERFLOW" -> RejectionReason.ARITHMETIC_OVERFLOW
                    else -> RejectionReason.INVALID_ENVELOPE_OR_PAYLOAD
                }
                rejected.add(RejectedOperation(op, reason, "${validation.code}: ${validation.message}"))
                if (validation.code != "INVALID_OPERATION_ID" && validation.code != "INVALID_SIGNATURE") {
                    rejectedIds.add(op.operationId)
                }
                continue
            }

            validEnvelopeOps[op.operationId] = op
        }

        // Step 4 & 5: Resolve causal dependencies, detect cycles, and enforce parent clock monotonicity
        val causalResult = CausalDependencyResolver.resolve(validEnvelopeOps, rejectedIds)
        rejected.addAll(causalResult.rejected)
        val pending = causalResult.pending.toMutableList()

        // Step 6: Deterministic topological ordering over causally ready operations
        val topologicallyOrdered = OperationOrdering.sortTopologically(causalResult.causallyReady)
        val maxLedgerTimestamp = topologicallyOrdered.map { it.createdAtMillis }.maxOrNull() ?: 0L

        // Pre-pass for Removal-Precedence Policy:
        val activeMembers = LinkedHashMap<String, ProtocolMemberInfo>()
        val allEverKnownMembers = LinkedHashSet<String>()
        val removedMembers = LinkedHashSet<String>()
        val ghostAliases = LinkedHashMap<String, String>()

        // Seed creator from trustedGenesis as baseline admin
        activeMembers[normCreator] = ProtocolMemberInfo(
            publicKey = normCreator,
            displayName = "Creator",
            role = ProtocolMemberInfo.ROLE_ADMIN
        )
        allEverKnownMembers.add(normCreator)

        val opsById = operations.associateBy { it.operationId }

        // Pre-pass for Concurrent Void Precedence over Edit on the same (expenseId, expectedRevisionId)
        val voidOperationsByTargetRevision = mutableMapOf<Pair<String, String>, SignedOperation>()
        for (op in topologicallyOrdered) {
            val p = op.payload
            if (p is OperationPayload.ExpenseVoided) {
                val key = p.expenseId to p.expectedRevisionId
                if (key !in voidOperationsByTargetRevision) {
                    voidOperationsByTargetRevision[key] = op
                }
            }
        }

        // Stage 1: Authenticated pre-pass for membership operations
        // Only operations that pass envelope, root, parent acceptance, authorization,
        // and conflict resolution are recognized as valid ancestors and authorized membership events.
        val preAuthorizedRemovalsByTarget = mutableMapOf<String, MutableList<SignedOperation>>()
        val preAuthorizedReadmitsByTarget = mutableMapOf<String, MutableList<SignedOperation>>()
        run {
            val pass1AcceptedOpIds = mutableSetOf<String>()
            val pass1ActiveMembers = LinkedHashMap(activeMembers)
            val pass1RemovedMembers = LinkedHashSet<String>()
            val pass1ConsumedNonces = LinkedHashSet<String>()
            val pass1GhostAliases = LinkedHashMap<String, String>()
            val pass1AllKnownMembers = LinkedHashSet<String>(allEverKnownMembers)
            val pass1Expenses = LinkedHashMap<String, ReconciledExpense>()
            val pass1Settlements = LinkedHashMap<String, ReconciledSettlement>()
            var pass1RootOpId: String? = null

            for (op in topologicallyOrdered) {
                // Check parent acceptance
                val parentNotAccepted = op.parents.any { it !in pass1AcceptedOpIds }
                if (op.parents.isNotEmpty() && parentNotAccepted) {
                    continue
                }

                when (val p = op.payload) {
                    is OperationPayload.GenesisCreated -> {
                        if (pass1RootOpId != null && pass1RootOpId != op.operationId) continue
                        if (normalizedGenesis.genesisOperationId != null && op.operationId != normalizedGenesis.genesisOperationId) continue
                        if (op.authorPublicKey != normCreator) continue
                        pass1RootOpId = op.operationId
                        pass1AcceptedOpIds.add(op.operationId)
                        for (m in p.initialMembers) {
                            val role = if (m.publicKey == normCreator) ProtocolMemberInfo.ROLE_ADMIN else m.role
                            val normPk = m.publicKey.trim().lowercase()
                            pass1ActiveMembers[normPk] = m.copy(publicKey = normPk, role = role)
                            pass1AllKnownMembers.add(normPk)
                        }
                    }
                    is OperationPayload.LegacyV1Checkpoint -> {
                        if (pass1RootOpId != null && pass1RootOpId != op.operationId) continue
                        if (normalizedGenesis.genesisOperationId != null && op.operationId != normalizedGenesis.genesisOperationId) continue
                        val matchesPinned = normalizedGenesis.genesisOperationId != null && op.operationId == normalizedGenesis.genesisOperationId
                        val isCreator = op.authorPublicKey == normCreator
                        if (!isCreator && !matchesPinned && op.authorPublicKey !in pass1ActiveMembers) continue
                        pass1RootOpId = op.operationId
                        pass1AcceptedOpIds.add(op.operationId)
                        for (m in p.members) {
                            val role = if (m.publicKey == normCreator) ProtocolMemberInfo.ROLE_ADMIN else m.role
                            val normPk = m.publicKey.trim().lowercase()
                            pass1ActiveMembers[normPk] = m.copy(publicKey = normPk, role = role)
                            pass1AllKnownMembers.add(normPk)
                        }
                        for ((ghostPk, realPk) in p.ghostAliases) {
                            val normGhost = ghostPk.trim().lowercase()
                            val normReal = realPk.trim().lowercase()
                            pass1GhostAliases[normGhost] = normReal
                            pass1AllKnownMembers.add(normGhost)
                            pass1AllKnownMembers.add(normReal)
                        }
                        val voidedSet = p.voidedExpenseIds.toSet()
                        for (exp in p.activeExpenses) {
                            pass1AllKnownMembers.add(exp.paidBy.trim().lowercase())
                            pass1AllKnownMembers.addAll(exp.payers.keys.map { it.trim().lowercase() })
                            pass1AllKnownMembers.addAll(exp.splits.keys.map { it.trim().lowercase() })
                            pass1Expenses[exp.expenseId] = ReconciledExpense(
                                expenseId = exp.expenseId,
                                originOperationId = op.operationId,
                                currentRevisionOperationId = op.operationId,
                                authorPublicKey = op.authorPublicKey,
                                logicalClock = op.logicalClock,
                                createdAtMillis = op.createdAtMillis,
                                payload = exp,
                                isVoided = exp.expenseId in voidedSet,
                                voidedByOperationId = if (exp.expenseId in voidedSet) op.operationId else null,
                                voidReason = if (exp.expenseId in voidedSet) "Legacy V1 Void" else null
                            )
                        }
                        for (stl in p.settlements) {
                            pass1AllKnownMembers.add(stl.fromMember.trim().lowercase())
                            pass1AllKnownMembers.add(stl.toMember.trim().lowercase())
                            pass1Settlements[stl.settlementId] = ReconciledSettlement(
                                settlementId = stl.settlementId,
                                operationId = op.operationId,
                                authorPublicKey = op.authorPublicKey,
                                logicalClock = op.logicalClock,
                                createdAtMillis = op.createdAtMillis,
                                payload = stl
                            )
                        }
                    }
                    is OperationPayload.MemberAdded -> {
                        val normTarget = p.memberPublicKey.trim().lowercase()
                        val hasTrustedAdmission = if (p.invitationGrant != null) {
                            topologicallyOrdered.any { cand ->
                                val candAuthor = cand.authorPublicKey.trim().lowercase()
                                candAuthor != normTarget &&
                                    (candAuthor == normCreator || pass1ActiveMembers[candAuthor]?.role == ProtocolMemberInfo.ROLE_ADMIN) &&
                                    op.operationId in (causalResult.ancestorsOf[cand.operationId] ?: emptySet())
                            }
                        } else false
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis },
                            hasTrustedAdmission = hasTrustedAdmission,
                            maxLedgerTimestamp = maxLedgerTimestamp
                        )
                        if (authOutcome.isAuthorized) {
                            pass1AcceptedOpIds.add(op.operationId)
                            val normPk = p.memberPublicKey.trim().lowercase()
                            val wasRemoved = normPk in pass1RemovedMembers
                            pass1RemovedMembers.remove(normPk)
                            val authorInfo = pass1ActiveMembers[op.authorPublicKey]
                            val isCreator = op.authorPublicKey == normCreator
                            val role = if (isCreator || authorInfo?.role == ProtocolMemberInfo.ROLE_ADMIN) p.role else ProtocolMemberInfo.ROLE_MEMBER
                            pass1ActiveMembers[normPk] = ProtocolMemberInfo(
                                publicKey = normPk,
                                displayName = p.displayName.trim(),
                                role = role
                            )
                            pass1AllKnownMembers.add(normPk)
                            p.invitationGrant?.let { pass1ConsumedNonces.add(it.invitationNonce.trim().lowercase()) }
                            val claimedGhost = p.claimedGhostPubkey?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                            if (claimedGhost != null && claimedGhost != normPk) {
                                pass1GhostAliases[claimedGhost] = normPk
                                pass1ActiveMembers.remove(claimedGhost)
                                pass1AllKnownMembers.add(claimedGhost)
                            }
                            if (wasRemoved || !isCreator) {
                                preAuthorizedReadmitsByTarget.getOrPut(normPk) { mutableListOf() }.add(op)
                            }
                        }
                    }
                    is OperationPayload.MemberRemoved -> {
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis }
                        )
                        if (authOutcome.isAuthorized) {
                            pass1AcceptedOpIds.add(op.operationId)
                            val targetPk = p.memberPublicKey.trim().lowercase()
                            pass1ActiveMembers.remove(targetPk)
                            pass1RemovedMembers.add(targetPk)
                            preAuthorizedRemovalsByTarget.getOrPut(targetPk) { mutableListOf() }.add(op)
                        }
                    }
                    is OperationPayload.ExpenseCreated -> {
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis }
                        )
                        if (authOutcome.isAuthorized) {
                            val outcome = OperationConflictResolver.applyExpenseCreated(
                                op = op,
                                payload = p,
                                groupCurrency = normCurrency,
                                knownMemberKeys = pass1AllKnownMembers,
                                expenses = pass1Expenses
                            )
                            if (outcome is OperationConflictResolver.ApplicationOutcome.Applied) {
                                pass1AcceptedOpIds.add(op.operationId)
                            }
                        }
                    }
                    is OperationPayload.ExpenseUpdated -> {
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis }
                        )
                        if (authOutcome.isAuthorized) {
                            val concurrentVoid = voidOperationsByTargetRevision[p.expenseId to p.expectedRevisionId]
                            val outcome = OperationConflictResolver.applyExpenseUpdated(
                                op = op,
                                payload = p,
                                groupCurrency = normCurrency,
                                knownMemberKeys = pass1AllKnownMembers,
                                expenses = pass1Expenses,
                                concurrentVoidWinnerOpId = concurrentVoid?.operationId
                            )
                            if (outcome is OperationConflictResolver.ApplicationOutcome.Applied) {
                                pass1AcceptedOpIds.add(op.operationId)
                            }
                        }
                    }
                    is OperationPayload.ExpenseVoided -> {
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis }
                        )
                        if (authOutcome.isAuthorized) {
                            val outcome = OperationConflictResolver.applyExpenseVoided(
                                op = op,
                                payload = p,
                                expenses = pass1Expenses
                            )
                            if (outcome is OperationConflictResolver.ApplicationOutcome.Applied) {
                                pass1AcceptedOpIds.add(op.operationId)
                            }
                        }
                    }
                    is OperationPayload.SettlementRecorded -> {
                        val authOutcome = OperationAuthorization.evaluate(
                            operation = op,
                            trustedGenesis = normalizedGenesis,
                            activeMembers = pass1ActiveMembers,
                            removedMembers = pass1RemovedMembers,
                            acceptedRemovalsByTarget = emptyMap(),
                            acceptedReadmitsByTarget = emptyMap(),
                            ancestorsOf = causalResult.ancestorsOf,
                            consumedInvitationNonces = pass1ConsumedNonces,
                            ghostAliases = pass1GhostAliases,
                            parentTimestampProvider = { opsById[it]?.createdAtMillis }
                        )
                        if (authOutcome.isAuthorized) {
                            val outcome = OperationConflictResolver.applySettlementRecorded(
                                op = op,
                                payload = p,
                                groupCurrency = normCurrency,
                                knownMemberKeys = pass1AllKnownMembers,
                                settlements = pass1Settlements
                            )
                            if (outcome is OperationConflictResolver.ApplicationOutcome.Applied) {
                                pass1AcceptedOpIds.add(op.operationId)
                            }
                        }
                    }
                }
            }
        }

        // Step 7 & 8: Sequentially evaluate authorization, causal parent acceptance, and conflict rules
        val acceptedOperations = mutableListOf<SignedOperation>()
        val acceptedOpIds = mutableSetOf<String>()
        val dynamicRejectedIds = rejectedIds.toMutableSet()
        val conflicts = mutableListOf<ConflictRecord>()
        val effectiveExpenses = LinkedHashMap<String, ReconciledExpense>()
        val effectiveSettlements = LinkedHashMap<String, ReconciledSettlement>()
        val consumedInvitationNonces = LinkedHashSet<String>()
        var rootOperationId: String? = null
        var currentKeyEpoch = 0

        for (op in topologicallyOrdered) {
            // 7a. If any parent was rejected during earlier steps of this loop, reject this child too
            val rejectedParent = op.parents.firstOrNull { it in dynamicRejectedIds || it !in acceptedOpIds }
            if (op.parents.isNotEmpty() && rejectedParent != null) {
                rejected.add(
                    RejectedOperation(
                        operation = op,
                        reason = RejectionReason.PARENT_REJECTED,
                        detail = "Parent operation $rejectedParent was not accepted"
                    )
                )
                dynamicRejectedIds.add(op.operationId)
                if (op.payload is OperationPayload.MemberRemoved) {
                    val targetPk = op.payload.memberPublicKey.trim().lowercase()
                    preAuthorizedRemovalsByTarget[targetPk]?.removeAll { it.operationId == op.operationId }
                }
                continue
            }

            // 7b. Enforce single Genesis / Root checkpoint rule
            val isRoot = op.payload is OperationPayload.GenesisCreated || op.payload is OperationPayload.LegacyV1Checkpoint
            if (isRoot) {
                if (normalizedGenesis.genesisOperationId != null && op.operationId != normalizedGenesis.genesisOperationId) {
                    rejected.add(
                        RejectedOperation(
                            operation = op,
                            reason = RejectionReason.MULTIPLE_GENESIS_OPERATIONS,
                            detail = "Operation ${op.operationId} does not match trusted genesisOperationId ${normalizedGenesis.genesisOperationId}"
                        )
                    )
                    dynamicRejectedIds.add(op.operationId)
                    if (op.payload is OperationPayload.MemberRemoved) {
                        val targetPk = op.payload.memberPublicKey.trim().lowercase()
                        preAuthorizedRemovalsByTarget[targetPk]?.removeAll { it.operationId == op.operationId }
                    }
                    continue
                }
                if (rootOperationId != null && rootOperationId != op.operationId) {
                    rejected.add(
                        RejectedOperation(
                            operation = op,
                            reason = RejectionReason.MULTIPLE_GENESIS_OPERATIONS,
                            detail = "Group already has an accepted root operation $rootOperationId"
                        )
                    )
                    dynamicRejectedIds.add(op.operationId)
                    if (op.payload is OperationPayload.MemberRemoved) {
                        val targetPk = op.payload.memberPublicKey.trim().lowercase()
                        preAuthorizedRemovalsByTarget[targetPk]?.removeAll { it.operationId == op.operationId }
                    }
                    continue
                }
            }

            // 7c. Evaluate membership authorization (including concurrent removal precedence)
            val normTarget = (op.payload as? OperationPayload.MemberAdded)?.memberPublicKey?.trim()?.lowercase()
            val hasTrustedAdmission = if (normTarget != null && (op.payload as OperationPayload.MemberAdded).invitationGrant != null) {
                topologicallyOrdered.any { cand ->
                    val candAuthor = cand.authorPublicKey.trim().lowercase()
                    candAuthor != normTarget &&
                        (candAuthor == normCreator || activeMembers[candAuthor]?.role == ProtocolMemberInfo.ROLE_ADMIN) &&
                        op.operationId in (causalResult.ancestorsOf[cand.operationId] ?: emptySet())
                }
            } else false
            val authOutcome = OperationAuthorization.evaluate(
                operation = op,
                trustedGenesis = normalizedGenesis,
                activeMembers = activeMembers,
                removedMembers = removedMembers,
                acceptedRemovalsByTarget = preAuthorizedRemovalsByTarget,
                acceptedReadmitsByTarget = preAuthorizedReadmitsByTarget,
                ancestorsOf = causalResult.ancestorsOf,
                consumedInvitationNonces = consumedInvitationNonces,
                ghostAliases = ghostAliases,
                parentTimestampProvider = { opsById[it]?.createdAtMillis },
                hasTrustedAdmission = hasTrustedAdmission,
                maxLedgerTimestamp = maxLedgerTimestamp
            )
            if (!authOutcome.isAuthorized) {
                rejected.add(
                    RejectedOperation(
                        operation = op,
                        reason = authOutcome.rejectionReason ?: RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = authOutcome.detail
                    )
                )
                dynamicRejectedIds.add(op.operationId)
                if (op.payload is OperationPayload.MemberRemoved) {
                    val targetPk = op.payload.memberPublicKey.trim().lowercase()
                    preAuthorizedRemovalsByTarget[targetPk]?.removeAll { it.operationId == op.operationId }
                }
                continue
            }

            // 7d. Apply payload state transition & conflict resolution
            var accepted = false
            when (val payload = op.payload) {
                is OperationPayload.GenesisCreated -> {
                    rootOperationId = op.operationId
                    currentKeyEpoch = maxOf(currentKeyEpoch, payload.initialKeyEpoch)
                    for (m in payload.initialMembers) {
                        val role = if (m.publicKey == normCreator) ProtocolMemberInfo.ROLE_ADMIN else m.role
                        val normalizedMember = m.copy(publicKey = m.publicKey.trim().lowercase(), role = role)
                        activeMembers[normalizedMember.publicKey] = normalizedMember
                        allEverKnownMembers.add(normalizedMember.publicKey)
                        normalizedMember.claimedGhostPubkey?.takeIf { it.isNotBlank() }?.let { ghostPk ->
                            ghostAliases[ghostPk] = normalizedMember.publicKey
                            allEverKnownMembers.add(ghostPk)
                        }
                    }
                    accepted = true
                }

                is OperationPayload.LegacyV1Checkpoint -> {
                    rootOperationId = op.operationId
                    currentKeyEpoch = maxOf(currentKeyEpoch, op.keyEpoch)
                    for (m in payload.members) {
                        val role = if (m.publicKey == normCreator) ProtocolMemberInfo.ROLE_ADMIN else m.role
                        val normPk = m.publicKey.trim().lowercase()
                        activeMembers[normPk] = m.copy(publicKey = normPk, role = role)
                        allEverKnownMembers.add(normPk)
                    }
                    for ((ghostPk, realPk) in payload.ghostAliases) {
                        val normGhost = ghostPk.trim().lowercase()
                        val normReal = realPk.trim().lowercase()
                        ghostAliases[normGhost] = normReal
                        allEverKnownMembers.add(normGhost)
                        allEverKnownMembers.add(normReal)
                    }
                    val voidedSet = payload.voidedExpenseIds.toSet()
                    for (exp in payload.activeExpenses) {
                        allEverKnownMembers.add(exp.paidBy.trim().lowercase())
                        allEverKnownMembers.addAll(exp.payers.keys.map { it.trim().lowercase() })
                        allEverKnownMembers.addAll(exp.splits.keys.map { it.trim().lowercase() })
                        effectiveExpenses[exp.expenseId] = ReconciledExpense(
                            expenseId = exp.expenseId,
                            originOperationId = op.operationId,
                            currentRevisionOperationId = op.operationId,
                            authorPublicKey = op.authorPublicKey,
                            logicalClock = op.logicalClock,
                            createdAtMillis = op.createdAtMillis,
                            payload = exp,
                            isVoided = exp.expenseId in voidedSet,
                            voidedByOperationId = if (exp.expenseId in voidedSet) op.operationId else null,
                            voidReason = if (exp.expenseId in voidedSet) "Legacy V1 Void" else null
                        )
                    }
                    for (stl in payload.settlements) {
                        allEverKnownMembers.add(stl.fromMember.trim().lowercase())
                        allEverKnownMembers.add(stl.toMember.trim().lowercase())
                        effectiveSettlements[stl.settlementId] = ReconciledSettlement(
                            settlementId = stl.settlementId,
                            operationId = op.operationId,
                            authorPublicKey = op.authorPublicKey,
                            logicalClock = op.logicalClock,
                            createdAtMillis = op.createdAtMillis,
                            payload = stl
                        )
                    }
                    accepted = true
                }

                is OperationPayload.MemberAdded -> {
                    val normPk = payload.memberPublicKey.trim().lowercase()
                    removedMembers.remove(normPk)
                    val claimedGhost = payload.claimedGhostPubkey?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    if (claimedGhost != null && claimedGhost != normPk) {
                        ghostAliases[claimedGhost] = normPk
                        activeMembers.remove(claimedGhost)
                        allEverKnownMembers.add(claimedGhost)
                    }
                    activeMembers[normPk] = ProtocolMemberInfo(
                        publicKey = normPk,
                        displayName = payload.displayName.trim(),
                        role = payload.role,
                        upiId = payload.upiId,
                        phone = payload.phone,
                        claimedGhostPubkey = claimedGhost
                    )
                    allEverKnownMembers.add(normPk)
                    payload.invitationGrant?.let { consumedInvitationNonces.add(it.invitationNonce.trim().lowercase()) }
                    currentKeyEpoch = maxOf(currentKeyEpoch, payload.newKeyEpoch, op.keyEpoch)
                    accepted = true
                }

                is OperationPayload.MemberRemoved -> {
                    val normPk = payload.memberPublicKey.trim().lowercase()
                    activeMembers.remove(normPk)
                    removedMembers.add(normPk)
                    currentKeyEpoch = maxOf(currentKeyEpoch, payload.newKeyEpoch, op.keyEpoch)
                    accepted = true
                }

                is OperationPayload.ExpenseCreated -> {
                    val outcome = OperationConflictResolver.applyExpenseCreated(
                        op = op,
                        payload = payload,
                        groupCurrency = normCurrency,
                        knownMemberKeys = allEverKnownMembers,
                        expenses = effectiveExpenses
                    )
                    when (outcome) {
                        is OperationConflictResolver.ApplicationOutcome.Applied -> accepted = true
                        is OperationConflictResolver.ApplicationOutcome.Rejected -> {
                            rejected.add(RejectedOperation(op, outcome.reason, outcome.detail))
                            outcome.conflict?.let { conflicts.add(it) }
                        }
                    }
                }

                is OperationPayload.ExpenseUpdated -> {
                    // Check if a valid authorized concurrent ExpenseVoided targets the same (expenseId, expectedRevisionId)
                    val concurrentVoid = voidOperationsByTargetRevision[payload.expenseId to payload.expectedRevisionId]
                        ?.takeIf { voidOp ->
                            voidOp.operationId != op.operationId &&
                                voidOp.parents.all { it in acceptedOpIds } &&
                                OperationAuthorization.evaluate(
                                    operation = voidOp,
                                    trustedGenesis = normalizedGenesis,
                                    activeMembers = activeMembers,
                                    removedMembers = removedMembers,
                                    acceptedRemovalsByTarget = preAuthorizedRemovalsByTarget,
                                    ancestorsOf = causalResult.ancestorsOf,
                                    parentTimestampProvider = { opsById[it]?.createdAtMillis }
                                ).isAuthorized
                        }

                    val outcome = OperationConflictResolver.applyExpenseUpdated(
                        op = op,
                        payload = payload,
                        groupCurrency = normCurrency,
                        knownMemberKeys = allEverKnownMembers,
                        expenses = effectiveExpenses,
                        concurrentVoidWinnerOpId = concurrentVoid?.operationId
                    )
                    when (outcome) {
                        is OperationConflictResolver.ApplicationOutcome.Applied -> accepted = true
                        is OperationConflictResolver.ApplicationOutcome.Rejected -> {
                            rejected.add(RejectedOperation(op, outcome.reason, outcome.detail))
                            outcome.conflict?.let { conflicts.add(it) }
                        }
                    }
                }

                is OperationPayload.ExpenseVoided -> {
                    val outcome = OperationConflictResolver.applyExpenseVoided(
                        op = op,
                        payload = payload,
                        expenses = effectiveExpenses
                    )
                    when (outcome) {
                        is OperationConflictResolver.ApplicationOutcome.Applied -> accepted = true
                        is OperationConflictResolver.ApplicationOutcome.Rejected -> {
                            rejected.add(RejectedOperation(op, outcome.reason, outcome.detail))
                            outcome.conflict?.let { conflicts.add(it) }
                        }
                    }
                }

                is OperationPayload.SettlementRecorded -> {
                    val outcome = OperationConflictResolver.applySettlementRecorded(
                        op = op,
                        payload = payload,
                        groupCurrency = normCurrency,
                        knownMemberKeys = allEverKnownMembers,
                        settlements = effectiveSettlements
                    )
                    when (outcome) {
                        is OperationConflictResolver.ApplicationOutcome.Applied -> accepted = true
                        is OperationConflictResolver.ApplicationOutcome.Rejected -> {
                            rejected.add(RejectedOperation(op, outcome.reason, outcome.detail))
                            outcome.conflict?.let { conflicts.add(it) }
                        }
                    }
                }
            }

            if (accepted) {
                acceptedOperations.add(op)
                acceptedOpIds.add(op.operationId)
                currentKeyEpoch = maxOf(currentKeyEpoch, op.keyEpoch)
            } else {
                dynamicRejectedIds.add(op.operationId)
                if (op.payload is OperationPayload.MemberRemoved) {
                    val targetPk = op.payload.memberPublicKey.trim().lowercase()
                    preAuthorizedRemovalsByTarget[targetPk]?.removeAll { it.operationId == op.operationId }
                }
            }
        }

        // Step 9: Calculate exact integer minor-unit balances and min-cash-flow settlement suggestions
        val memberBalancesMinor = LedgerProjection.calculateBalancesMinor(
            effectiveExpenses = effectiveExpenses,
            effectiveSettlements = effectiveSettlements,
            knownMembers = allEverKnownMembers,
            ghostAliases = ghostAliases
        )

        val settlementSuggestions = LedgerProjection.simplifyDebtsMinor(
            memberBalancesMinor = memberBalancesMinor,
            currency = normCurrency
        )

        // Compute DAG frontier (head operation IDs that have no accepted children)
        val referencedAsParent = mutableSetOf<String>()
        for (op in acceptedOperations) {
            referencedAsParent.addAll(op.parents)
        }
        val headOperationIds = acceptedOperations
            .map { it.operationId }
            .filter { it !in referencedAsParent }
            .toSortedSet()

        val maxLogicalClock = acceptedOperations.maxOfOrNull { it.logicalClock } ?: 0L

        // Step 10: Compute canonical state digest
        val stateDigest = LedgerProjection.computeStateDigest(
            groupId = normGroupId,
            currency = normCurrency,
            acceptedOperations = acceptedOperations,
            activeMembers = activeMembers,
            removedMembers = removedMembers,
            effectiveExpenses = effectiveExpenses,
            effectiveSettlements = effectiveSettlements,
            memberBalancesMinor = memberBalancesMinor
        )

        return ReconciliationResult(
            groupId = normGroupId,
            currency = normCurrency,
            acceptedOperations = acceptedOperations,
            rejectedOperations = rejected.sortedBy { it.operation.operationId },
            pendingOperations = pending.sortedBy { it.operation.operationId },
            conflicts = conflicts,
            effectiveExpenses = effectiveExpenses,
            effectiveSettlements = effectiveSettlements,
            activeMembers = activeMembers,
            removedMembers = removedMembers,
            ghostAliases = ghostAliases,
            memberBalancesMinor = memberBalancesMinor,
            settlementSuggestions = settlementSuggestions,
            currentKeyEpoch = currentKeyEpoch,
            maxLogicalClock = maxLogicalClock,
            headOperationIds = headOperationIds,
            stateDigest = stateDigest
        )
    }
}
