package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.ProtocolMemberInfo
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
        ancestorsOf: Map<String, Set<String>>
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
                val isActiveMember = authorMember != null && authorMember.role != ProtocolMemberInfo.ROLE_GHOST
                val isSelfJoinViaInvite = author == payload.memberPublicKey && payload.role == ProtocolMemberInfo.ROLE_MEMBER

                if (payload.role == ProtocolMemberInfo.ROLE_ADMIN && !isCreator && authorMember?.role != ProtocolMemberInfo.ROLE_ADMIN) {
                    return AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_ROLE_CHANGE,
                        detail = "Only an admin or creator can grant ADMIN role"
                    )
                }

                if (payload.memberPublicKey in removedMembers && !isCreator && authorMember?.role != ProtocolMemberInfo.ROLE_ADMIN) {
                    return AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_REMOVED_MEMBER,
                        detail = "A removed member can only be re-added by the group creator or an admin"
                    )
                }

                if (isCreator || isActiveMember || isSelfJoinViaInvite) {
                    AuthOutcome(true)
                } else {
                    AuthOutcome(
                        isAuthorized = false,
                        rejectionReason = RejectionReason.UNAUTHORIZED_AUTHOR,
                        detail = "Author $author is not authorized to add member ${payload.memberPublicKey}"
                    )
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
}
