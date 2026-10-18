package com.peersplit.reconciliation

import com.peersplit.protocol.MembershipInvitationGrant
import com.peersplit.protocol.OperationPayload
import com.peersplit.protocol.OperationVerifier
import com.peersplit.protocol.ProtocolMemberInfo
import com.peersplit.protocol.ProtocolVersion
import com.peersplit.protocol.SignedOperation

/**
 * Evaluates causal and concurrent membership authorization for PeerSplit V2 operations.
 */
object OperationAuthorization {

    data class AuthOutcome(
        val isAuthorized: Boolean,
        val rejectionReason: RejectionReason? = null,
        val detail: String = ""
    )

    fun evaluate(
        operation: SignedOperation,
        trustedGenesis: TrustedGenesis,
        activeMembers: Map<String, ProtocolMemberInfo>,
        removedMembers: Set<String>,
        acceptedRemovalsByTarget: Map<String, List<SignedOperation>>,
        acceptedReadmitsByTarget: Map<String, List<SignedOperation>> = emptyMap(),
        ancestorsOf: Map<String, Set<String>>,
        consumedInvitationNonces: Set<String> = emptySet(),
        ghostAliases: Map<String, String> = emptyMap(),
        parentTimestampProvider: ((String) -> Long?)? = null
    ): AuthOutcome {
        val author = operation.authorPublicKey

        // 1. Check if author was removed (either causally prior or concurrently)
        if (author in removedMembers) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                detail = "Author $author has been removed from the group"
            )
        }

        val removalsForAuthor = acceptedRemovalsByTarget[author].orEmpty()
        if (removalsForAuthor.isNotEmpty()) {
            val opAncestors = ancestorsOf[operation.operationId].orEmpty()
            val readmitsForAuthor = acceptedReadmitsByTarget[author].orEmpty()

            for (removalOp in removalsForAuthor) {
                val removalAncestors = ancestorsOf[removalOp.operationId].orEmpty()
                val isOpCausalBeforeRemoval = operation.operationId in removalAncestors
                if (isOpCausalBeforeRemoval) continue

                // Check if author was explicitly readmitted by an admin after this removal and this op is after that readmission
                val isOpAfterReadmission = readmitsForAuthor.any { readmitOp ->
                    val readmitAncestors = ancestorsOf[readmitOp.operationId].orEmpty()
                    val readmitIsAfterRemoval = removalOp.operationId in readmitAncestors
                    val opIsAfterReadmit = readmitOp.operationId in opAncestors || operation.operationId == readmitOp.operationId
                    readmitIsAfterRemoval && opIsAfterReadmit
                }

                if (!isOpAfterReadmission) {
                    return AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                        detail = "Operation by $author is concurrent with or subsequent to removal ${removalOp.operationId} without readmission"
                    )
                }
            }
        }

        // 2. Payload-specific authorization checks
        return when (val payload = operation.payload) {
            is OperationPayload.GenesisCreated -> {
                if (author != trustedGenesis.creatorPublicKey || payload.creatorPublicKey != trustedGenesis.creatorPublicKey) {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "GenesisCreated must be authored by group creator ${trustedGenesis.creatorPublicKey}"
                    )
                } else {
                    AuthOutcome(true)
                }
            }

            is OperationPayload.LegacyV1Checkpoint -> {
                val matchesPinnedGenesis = trustedGenesis.genesisOperationId != null &&
                    operation.operationId.trim().lowercase() == trustedGenesis.genesisOperationId.trim().lowercase()
                val isCreator = author.trim().lowercase() == trustedGenesis.creatorPublicKey.trim().lowercase()
                if (!matchesPinnedGenesis && !isCreator && author !in activeMembers) {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "LegacyV1Checkpoint must be authored by group creator ${trustedGenesis.creatorPublicKey} or match pinned genesis operation"
                    )
                } else {
                    AuthOutcome(true)
                }
            }

            is OperationPayload.MemberAdded -> {
                // A01: Validate ghost claim if present
                val ghostClaim = payload.claimedGhostPubkey?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                if (ghostClaim != null) {
                    val normTarget = ghostClaim.lowercase()
                    val normCreator = trustedGenesis.creatorPublicKey.trim().lowercase()
                    if (normTarget == normCreator) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                            detail = "Cannot claim the group creator as a ghost"
                        )
                    }
                    if (normTarget == payload.memberPublicKey.trim().lowercase()) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.INVALID_ENVELOPE_OR_PAYLOAD,
                            detail = "Cannot claim oneself as a ghost"
                        )
                    }
                    val targetMember = activeMembers[normTarget]
                    if (targetMember == null || targetMember.role != ProtocolMemberInfo.ROLE_GHOST) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                            detail = "Target $normTarget is not an active unclaimed ghost member"
                        )
                    }
                    if (normTarget in ghostAliases) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                            detail = "Ghost member $normTarget has already been claimed"
                        )
                    }
                    var curr: String? = normTarget
                    val visited = mutableSetOf<String>()
                    while (curr != null) {
                        if (!visited.add(curr) || curr == payload.memberPublicKey.trim().lowercase()) {
                            return AuthOutcome(
                                isAuthorized = false,
                                rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                                detail = "Ghost claim would create an alias cycle"
                            )
                        }
                        curr = ghostAliases[curr]
                    }
                }

                val authorMember = activeMembers[author]
                val isCreator = author == trustedGenesis.creatorPublicKey
                val isAdmin = authorMember?.role == ProtocolMemberInfo.ROLE_ADMIN || isCreator
                val isSelfJoin = author == payload.memberPublicKey

                if (isSelfJoin) {
                    // Removed members can never self-rejoin via invite token
                    if (author in removedMembers) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                            detail = "Removed member $author cannot self-rejoin; only an admin can re-add them"
                        )
                    }

                    // Self-join cannot grant ADMIN role
                    if (payload.role == ProtocolMemberInfo.ROLE_ADMIN) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_ROLE_CHANGE,
                            detail = "Self-joining member cannot claim ADMIN role"
                        )
                    }

                    // Self-join MUST provide an authenticated admin invitation grant
                    val grant = payload.invitationGrant
                    if (grant == null) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.INVALID_INVITATION,
                            detail = "Unrestricted self-join without valid admin invitation grant is forbidden"
                        )
                    }

                    return validateInvitationGrant(
                        grant = grant,
                        targetMemberPublicKey = payload.memberPublicKey,
                        operation = operation,
                        trustedGenesis = trustedGenesis,
                        activeMembers = activeMembers,
                        consumedInvitationNonces = consumedInvitationNonces,
                        parentTimestampProvider = parentTimestampProvider
                    )
                } else {
                    // Non-self addition (an existing user is adding someone else)
                    if (payload.role == ProtocolMemberInfo.ROLE_ADMIN && !isAdmin) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_ROLE_CHANGE,
                            detail = "Only an admin or creator can grant ADMIN role"
                        )
                    }

                    if (payload.memberPublicKey in removedMembers && !isAdmin) {
                        return AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                            detail = "A removed member can only be re-added by the group creator or an admin"
                        )
                    }

                    if (isAdmin) {
                        if (payload.invitationGrant != null) {
                            return validateInvitationGrant(
                                grant = payload.invitationGrant,
                                targetMemberPublicKey = payload.memberPublicKey,
                                operation = operation,
                                trustedGenesis = trustedGenesis,
                                activeMembers = activeMembers,
                                consumedInvitationNonces = consumedInvitationNonces,
                                parentTimestampProvider = parentTimestampProvider
                            )
                        } else {
                            AuthOutcome(true)
                        }
                    } else if (payload.invitationGrant != null) {
                        // Ordinary member presenting an admin-signed grant
                        return validateInvitationGrant(
                            grant = payload.invitationGrant,
                            targetMemberPublicKey = payload.memberPublicKey,
                            operation = operation,
                            trustedGenesis = trustedGenesis,
                            activeMembers = activeMembers,
                            consumedInvitationNonces = consumedInvitationNonces,
                            parentTimestampProvider = parentTimestampProvider
                        )
                    } else {
                        AuthOutcome(
                            isAuthorized = false,
                            rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                            detail = "Author $author is not authorized to add member ${payload.memberPublicKey} without admin privileges or grant"
                        )
                    }
                }
            }

            is OperationPayload.MemberRemoved -> {
                val authorMember = activeMembers[author]
                val isCreator = author == trustedGenesis.creatorPublicKey
                val isAdmin = authorMember?.role == ProtocolMemberInfo.ROLE_ADMIN

                if (payload.memberPublicKey == trustedGenesis.creatorPublicKey) {
                    return AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_ROLE_CHANGE,
                        detail = "The group creator cannot be removed"
                    )
                }

                if (isCreator || isAdmin) {
                    AuthOutcome(true)
                } else {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "Only group creator or admin can remove members"
                    )
                }
            }

            is OperationPayload.ExpenseCreated,
            is OperationPayload.ExpenseUpdated,
            is OperationPayload.ExpenseVoided,
            is OperationPayload.SettlementRecorded -> {
                val authorMember = activeMembers[author]
                val isCreator = author == trustedGenesis.creatorPublicKey
                val isActiveRealMember = authorMember != null && authorMember.role != ProtocolMemberInfo.ROLE_GHOST
                if (isCreator || isActiveRealMember) {
                    AuthOutcome(true)
                } else {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "Author $author is not an active member of group ${trustedGenesis.groupId}"
                    )
                }
            }
        }
    }

    private fun validateInvitationGrant(
        grant: MembershipInvitationGrant,
        targetMemberPublicKey: String,
        operation: SignedOperation,
        trustedGenesis: TrustedGenesis,
        activeMembers: Map<String, ProtocolMemberInfo>,
        consumedInvitationNonces: Set<String>,
        parentTimestampProvider: ((String) -> Long?)? = null
    ): AuthOutcome {
        // 1. Group ID match
        if (grant.groupId != operation.groupId) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grant groupId mismatch (${grant.groupId} != ${operation.groupId})"
            )
        }

        // 2. Inviter must be an active admin or creator
        val inviterMember = activeMembers[grant.inviterPublicKey]
        val isInviterAdmin = (grant.inviterPublicKey == trustedGenesis.creatorPublicKey) ||
            (inviterMember?.role == ProtocolMemberInfo.ROLE_ADMIN)
        if (!isInviterAdmin) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grant was not signed by an active group admin or creator (${grant.inviterPublicKey})"
            )
        }

        // 3. Recipient binding: if invitedPublicKey is specific (not wildcard "*"), target must match
        val normTarget = targetMemberPublicKey.trim().lowercase()
        val normInvited = grant.invitedPublicKey.trim().lowercase()
        if (normInvited.isNotBlank() && normInvited != "*" && normInvited != normTarget) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grant is bound to ${grant.invitedPublicKey}, but presented for $targetMemberPublicKey"
            )
        }

        // 4. Replay protection: nonce cannot be already consumed
        val nonce = grant.invitationNonce.trim().lowercase()
        if (nonce in consumedInvitationNonces) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.REPLAYED_INVITATION,
                detail = "Invitation nonce $nonce has already been consumed"
            )
        }

        // 5. Version & Expiry check:
        // Any operation presenting an invitation grant must be V3+ and have valid createdAtMillis
        if (operation.protocolVersion < ProtocolVersion.V3_SIGNED_OPERATIONS) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grants require protocol version ${ProtocolVersion.V3_SIGNED_OPERATIONS} or higher"
            )
        }
        if (operation.createdAtMillis <= 0L) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Operation createdAtMillis is invalid (${operation.createdAtMillis})"
            )
        }

        // Causal timestamp lower bound: join operation cannot be backdated before its causal parents
        val minCausalTimestamp = operation.parents.mapNotNull { parentTimestampProvider?.invoke(it) }.maxOrNull() ?: 0L
        if (minCausalTimestamp > 0L && operation.createdAtMillis < minCausalTimestamp) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.EXPIRED_INVITATION,
                detail = "Operation createdAtMillis (${operation.createdAtMillis}) is earlier than causal parent timestamp ($minCausalTimestamp)"
            )
        }

        if (grant.expiresAtMillis > 0L) {
            if (operation.createdAtMillis > grant.expiresAtMillis || minCausalTimestamp > grant.expiresAtMillis) {
                return AuthOutcome(
                    isAuthorized = false,
                    rejectionReason = RejectionReason.EXPIRED_INVITATION,
                    detail = "Invitation grant expired at ${grant.expiresAtMillis} (operation timestamp ${operation.createdAtMillis}, causal parent timestamp $minCausalTimestamp)"
                )
            }
        }

        // 6. Cryptographic signature check
        if (!OperationVerifier.verifyInvitationGrant(grant)) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grant cryptographic signature is invalid"
            )
        }

        return AuthOutcome(true)
    }
}
