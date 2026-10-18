package com.peersplit.app.reconciliation

import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class OldParentInvitationProbeTest {

    private data class KeyPair(val privateKeyHex: String, val publicKeyHex: String)

    private fun generateKey(): KeyPair {
        val random = SecureRandom()
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        return KeyPair(priv.encoded.toHex(), pub.encoded.toHex())
    }

    private lateinit var creatorKey: KeyPair
    private lateinit var bobKey: KeyPair
    private val groupId = "test-r3-group"

    @Before
    fun setUp() {
        creatorKey = generateKey()
        bobKey = generateKey()
    }

    /**
     * R3 [P1] Inverted Safety Regression:
     * Joining via a stale parent (Genesis at T=500) when the group timeline has already
     * progressed past the invitation expiry (Expense at T=1500, Expiry at T=1000)
     * MUST be rejected with INVALID_INVITATION, even if Bob backdates his join op to T=800.
     */
    @Test
    fun staleParentInvitationBypassMustBeRejected() {
        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "R3 Safety Group",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(
                        publicKey = creatorKey.publicKeyHex,
                        displayName = "Creator",
                        role = ProtocolMemberInfo.ROLE_ADMIN
                    )
                )
            ),
            createdAtMillis = 500L
        )

        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "R3 Safety Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        // Creator issues an invitation grant for Bob expiring at T=1000L
        val grant = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-stale-parent-test",
            expiresAtMillis = 1000L
        )

        // Group progresses on creator's device: expense authored at T=1500L (past Bob's grant expiry)
        val expenseOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-001",
                amountMinor = 10000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 10000L),
                description = "Snacks",
                expenseDateMillis = 1500L
            ),
            createdAtMillis = 1500L
        )

        // Bob attempts to join referencing only Genesis (T=500L), setting createdAtMillis = 800L
        val staleJoinOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = grant
            ),
            createdAtMillis = 800L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, expenseOp, staleJoinOp)
        )

        // Bob's stale join operation MUST be rejected
        val rejectedIds = result.rejectedOperations.map { it.operation.operationId }
        assertTrue("Stale join op must be in rejected operations", staleJoinOp.operationId in rejectedIds)
        val rej = result.rejectedOperations.first { it.operation.operationId == staleJoinOp.operationId }
        assertEquals("Rejection reason must be INVALID_INVITATION", RejectionReason.INVALID_INVITATION, rej.reason)
        assertFalse("Bob must NOT be admitted to active members", result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    /**
     * R3 [P1] Real-time admission bound:
     * An unconfirmed leaf join with an expired grant evaluated against wall-clock time
     * MUST be rejected at admission time.
     */
    @Test
    fun unconfirmedJoinAfterWallClockExpiryMustBeRejected() {
        val now = System.currentTimeMillis()
        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "R3 Safety Group",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(
                        publicKey = creatorKey.publicKeyHex,
                        displayName = "Creator",
                        role = ProtocolMemberInfo.ROLE_ADMIN
                    )
                )
            ),
            createdAtMillis = now - 100_000L
        )

        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "R3 Safety Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        // Grant expired 5 seconds ago
        val expiredGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-expired-wallclock",
            expiresAtMillis = now - 5_000L
        )

        val joinOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
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
            createdAtMillis = now - 10_000L // claims to have been authored before expiry
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, joinOp)
        )

        assertTrue("Join with expired grant evaluated at admission time must be rejected",
            joinOp.operationId in result.rejectedOperations.map { it.operation.operationId })
        assertFalse("Bob must NOT be active", result.activeMembers.containsKey(bobKey.publicKeyHex))
    }
}
