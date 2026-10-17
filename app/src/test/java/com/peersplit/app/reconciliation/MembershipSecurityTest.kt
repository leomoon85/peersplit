package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipSecurityTest {

    private val creatorKey = KeyManager.generateKeyPair()
    private val adminKey = KeyManager.generateKeyPair()
    private val attackerKey = KeyManager.generateKeyPair()
    private val bobKey = KeyManager.generateKeyPair()
    private val charlieKey = KeyManager.generateKeyPair()

    private val groupId = "security-test-group"

    private fun setupGenesis(): Pair<TrustedGenesis, com.peersplit.app.protocol.SignedOperation> {
        val initialMembers = listOf(
            ProtocolMemberInfo(
                publicKey = creatorKey.publicKeyHex,
                displayName = "Creator",
                role = ProtocolMemberInfo.ROLE_ADMIN
            ),
            ProtocolMemberInfo(
                publicKey = adminKey.publicKeyHex,
                displayName = "Admin",
                role = ProtocolMemberInfo.ROLE_ADMIN
            )
        )
        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Security Group",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = initialMembers
            )
        )
        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Security Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )
        return trustedGenesis to genesisOp
    }

    @Test
    fun testSelfJoinWithoutInvitationGrantIsRejected() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        // Attacker attempts uninvited self-join without an invitation grant
        val uninvitedSelfJoin = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = attackerKey.publicKeyHex,
                displayName = "Attacker",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = null
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, uninvitedSelfJoin)
        )

        assertEquals(1, result.acceptedOperations.size)
        assertEquals(genesisOp.operationId, result.acceptedOperations[0].operationId)
        assertEquals(1, result.rejectedOperations.size)
        assertEquals(uninvitedSelfJoin.operationId, result.rejectedOperations[0].operation.operationId)
        assertEquals(RejectionReason.INVALID_INVITATION, result.rejectedOperations[0].reason)
        assertFalse(result.activeMembers.containsKey(attackerKey.publicKeyHex))
    }

    @Test
    fun testSelfJoinWithNonAdminSignatureIsRejected() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val rogueMember = KeyManager.generateKeyPair()

        // Creator legitimately adds rogueMember as an ordinary MEMBER (not admin)
        val addRogueOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = rogueMember.publicKeyHex,
                displayName = "Ordinary Member",
                role = ProtocolMemberInfo.ROLE_MEMBER
            )
        )

        // Rogue member attempts to sign an invitation grant for attacker
        val unauthorizedGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = rogueMember.publicKeyHex,
            inviterPrivateKeyHex = rogueMember.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = attackerKey.publicKeyHex
        )

        val attackerJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(addRogueOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = attackerKey.publicKeyHex,
                displayName = "Attacker",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = unauthorizedGrant
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, addRogueOp, attackerJoinOp)
        )

        assertEquals(2, result.acceptedOperations.size)
        val attackerRejection = result.rejectedOperations.find { it.operation.operationId == attackerJoinOp.operationId }
        assertNotNull(attackerRejection)
        assertEquals(RejectionReason.INVALID_INVITATION, attackerRejection?.reason)
        assertFalse(result.activeMembers.containsKey(attackerKey.publicKeyHex))
    }

    @Test
    fun testSelfJoinWithForgedSignatureIsRejected() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        // Attacker creates a grant claiming it was signed by admin, but signs with attacker's key
        val forgedGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = attackerKey.privateKeyHex, // Wrong key!
            groupId = groupId,
            invitedPublicKey = attackerKey.publicKeyHex
        )

        val forgedJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = attackerKey.publicKeyHex,
                displayName = "Attacker",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = forgedGrant
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, forgedJoinOp)
        )

        assertEquals(1, result.acceptedOperations.size)
        val rej = result.rejectedOperations.find { it.operation.operationId == forgedJoinOp.operationId }
        assertNotNull(rej)
        assertTrue(
            rej?.reason == RejectionReason.INVALID_ENVELOPE_OR_PAYLOAD ||
                rej?.reason == RejectionReason.INVALID_INVITATION
        )
    }

    @Test
    fun testExpiredInvitationIsRejected() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        val expiryTime = 1_000_000L
        val expiredGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = adminKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            expiresAtMillis = expiryTime
        )

        // Bob signs join operation after expiry timestamp
        val expiredJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = expiredGrant
            ),
            createdAtMillis = expiryTime + 5000L // Created after expiry!
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, expiredJoinOp)
        )

        assertEquals(1, result.acceptedOperations.size)
        val rej = result.rejectedOperations.find { it.operation.operationId == expiredJoinOp.operationId }
        assertNotNull(rej)
        assertEquals(RejectionReason.EXPIRED_INVITATION, rej?.reason)
        assertFalse(result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    @Test
    fun testReplayedInvitationNonceIsRejected() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        val fixedNonce = "shared-nonce-12345"
        val singleUseGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = adminKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = "*",
            invitationNonce = fixedNonce
        )

        // Bob joins using the grant
        val bobJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = singleUseGrant
            )
        )

        // Charlie tries to replay the exact same invitation nonce
        val charlieJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = charlieKey.publicKeyHex,
            privateKeyHex = charlieKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = charlieKey.publicKeyHex,
                displayName = "Charlie",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = singleUseGrant
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp, charlieJoinOp)
        )

        // Bob is accepted; Charlie's replayed nonce is rejected
        assertTrue(result.acceptedOperations.any { it.operationId == bobJoinOp.operationId })
        assertTrue(result.activeMembers.containsKey(bobKey.publicKeyHex))

        val charlieRej = result.rejectedOperations.find { it.operation.operationId == charlieJoinOp.operationId }
        assertNotNull(charlieRej)
        assertEquals(RejectionReason.REPLAYED_INVITATION, charlieRej?.reason)
        assertFalse(result.activeMembers.containsKey(charlieKey.publicKeyHex))
    }

    @Test
    fun testRevokedMemberCannotSelfRejoinOrAuthorOperations() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        // Legitimate addition of Bob
        val bobGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = adminKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "bob-initial-nonce"
        )
        val bobJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = bobGrant
            )
        )

        // Admin removes Bob
        val removeBobOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = adminKey.publicKeyHex,
            privateKeyHex = adminKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = bobKey.publicKeyHex,
                reason = "Violated group rules"
            )
        )

        // 1. Bob attempts to author an expense after being removed
        val bobPostRemovalExpense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(removeBobOp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-post-removal",
                amountMinor = 5000L,
                currency = "INR",
                paidBy = bobKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 2500L, bobKey.publicKeyHex to 2500L),
                description = "Post removal dinner"
            )
        )

        // 2. Bob attempts to self-rejoin using a new admin invite link
        val freshGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = adminKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = "*",
            invitationNonce = "fresh-open-nonce"
        )
        val bobSelfRejoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(removeBobOp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = freshGrant
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp, removeBobOp, bobPostRemovalExpense, bobSelfRejoinOp)
        )

        // Verify Bob's expense and self-rejoin were rejected
        val expRej = result.rejectedOperations.find { it.operation.operationId == bobPostRemovalExpense.operationId }
        assertNotNull(expRej)
        assertEquals(RejectionReason.UNAUTHORIZED_REMOVED_MEMBER, expRej?.reason)

        val rejoinRej = result.rejectedOperations.find { it.operation.operationId == bobSelfRejoinOp.operationId }
        assertNotNull(rejoinRej)
        assertEquals(RejectionReason.UNAUTHORIZED_REMOVED_MEMBER, rejoinRej?.reason)
        assertFalse(result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    @Test
    fun testValidInvitationGrantWithAdminSignatureIsAccepted() {
        val (trustedGenesis, genesisOp) = setupGenesis()

        val validGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = adminKey.publicKeyHex,
            inviterPrivateKeyHex = adminKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "valid-nonce-999"
        )

        val bobJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = validGrant
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp)
        )

        assertEquals(2, result.acceptedOperations.size)
        assertEquals(0, result.rejectedOperations.size)
        assertTrue(result.activeMembers.containsKey(bobKey.publicKeyHex))
        assertEquals("Bob", result.activeMembers[bobKey.publicKeyHex]?.displayName)
    }
}
