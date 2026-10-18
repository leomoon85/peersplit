package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.MembershipInvitationGrant
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation

/**
 * Evaluates causal and concurrent membership authorization for PeerSplit V2 operations.
 *
 * Security & Concurrency Policy (Documented Removal-Precedence Model):
 * 1. Trusted Genesis (`GenesisCreated` or `LegacyV1Checkpoint`) must be signed by `trustedGenesis.creatorPublicKey`.
 * 2. A removed member (`MemberRemoved(M)`) is forbidden from authoring any operation `op` if:
 *    - `MemberRemoved(M)` is a causal ancestor of `op`, OR
 *    - `MemberRemoved(M)` is causally concurrent with `op` (neither is an ancestor of the other) AND `op` has not
 *      been causally endorsed (referenced as an ancestor) by an accepted operation from the group creator/admin
 *      prior to `MemberRemoved(M)`.
 *    This guarantees that a removed member cannot manipulate `logicalClock` or reference old pre-removal
 *    parent IDs to inject expenses or re-add members after removal.
 * 3. Only active members (`ADMIN` or `MEMBER`) may author financial operations (`ExpenseCreated`, `ExpenseUpdated`,
 *    `ExpenseVoided`, `SettlementRecorded`) or `MemberAdded`.
 * 4. Self-join via invite link (`MemberAdded` where `authorPublicKey == memberPublicKey`) is permitted if the author
 *    was not previously removed (`authorPublicKey !in removedMembers`) and references a valid group parent.
 * 5. Only `ADMIN` (or the group creator) may author `MemberRemoved` or grant `ROLE_ADMIN`.
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
        ancestorsOf: Map<String, Set<String>>,
        consumedInvitationNonces: Set<String> = emptySet()
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
            for (removalOp in removalsForAuthor) {
                val removalAncestors = ancestorsOf[removalOp.operationId].orEmpty()
                val isOpCausalBeforeRemoval = operation.operationId in removalAncestors
                if (!isOpCausalBeforeRemoval) {
                    // Either removal is an ancestor of op, or removal and op are concurrent: removal wins!
                    return AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                        detail = "Operation by $author is concurrent with or subsequent to removal ${removalOp.operationId}"
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
                if (author != trustedGenesis.creatorPublicKey && author !in activeMembers) {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "LegacyV1Checkpoint must be authored by creator or authorized member"
                    )
                } else {
                    AuthOutcome(true)
                }
            }

            is OperationPayload.MemberAdded -> {
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
                        consumedInvitationNonces = consumedInvitationNonces
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
                                consumedInvitationNonces = consumedInvitationNonces
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
                            consumedInvitationNonces = consumedInvitationNonces
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
        consumedInvitationNonces: Set<String>
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

        // 5. Expiry check: operation createdAtMillis must be valid (> 0) for V3+ and not past grant.expiresAtMillis
        if (operation.protocolVersion >= ProtocolVersion.V3_SIGNED_OPERATIONS && operation.createdAtMillis <= 0L) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Operation createdAtMillis is invalid (${operation.createdAtMillis})"
            )
        }
        if (grant.expiresAtMillis > 0L && operation.createdAtMillis > 0L && operation.createdAtMillis > grant.expiresAtMillis) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.EXPIRED_INVITATION,
                detail = "Invitation grant expired at ${grant.expiresAtMillis} (operation timestamp ${operation.createdAtMillis})"
            )
        }

        // 6. Cryptographic signature check
        if (!com.peersplit.app.protocol.OperationVerifier.verifyInvitationGrant(grant)) {
            return AuthOutcome(
                isAuthorized = false,
                rejectionReason = RejectionReason.INVALID_INVITATION,
                detail = "Invitation grant cryptographic signature is invalid"
            )
        }

        return AuthOutcome(true)
    }
}
