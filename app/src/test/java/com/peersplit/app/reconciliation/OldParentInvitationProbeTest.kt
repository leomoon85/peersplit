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
     * MUST be rejected at live admission time, and if ledger timeline has progressed past
     * expiry, rejected deterministically during reconciliation.
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

        // Live admission check at the network gateway MUST reject the expired grant
        val liveAuth = OperationAuthorization.isLiveAdmissionValid(joinOp, now)
        assertFalse("Live admission check must reject expired grant", liveAuth.isAuthorized)
        assertEquals(RejectionReason.EXPIRED_INVITATION, liveAuth.rejectionReason)

        // Group ledger progressed past expiry (T = now - 2_000L > expiry at now - 5_000L)
        val expenseOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-timeline-progression",
                amountMinor = 5000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 5000L),
                description = "Progression"
            ),
            createdAtMillis = now - 2_000L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, expenseOp, joinOp)
        )

        assertTrue("Join with expired grant in progressed timeline must be rejected",
            joinOp.operationId in result.rejectedOperations.map { it.operation.operationId })
        assertFalse("Bob must NOT be active", result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    /**
     * Probe 2: attackerAuthoredChildBypassesExpiredJoinAdmission
     * The joiner cannot bypass expiry admission by signing a child operation (e.g. an ExpenseCreated)
     * pointing to their own expired join. Descendants authored by the applicant cannot establish
     * trusted admission.
     */
    @Test
    fun attackerAuthoredChildBypassesExpiredJoinAdmission() {
        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Probe 2 Group",
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
            groupName = "Probe 2 Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        // Invitation expired at T=1000L
        val grant = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-attacker-child-test",
            expiresAtMillis = 1000L
        )

        // Creator ledger progress past expiry (T=1500L > 1000L)
        val creatorExpenseOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-creator-001",
                amountMinor = 5000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 5000L),
                description = "Creator Coffee"
            ),
            createdAtMillis = 1500L
        )

        // Bob authors a backdated join operation pointing to Genesis (T=800L < 1000L)
        val bobJoinOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
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

        // Bob ALSO authors an expense child referencing his own join as parent
        val bobChildExpenseOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-bob-child",
                amountMinor = 2500L,
                currency = "INR",
                paidBy = bobKey.publicKeyHex,
                splits = mapOf(bobKey.publicKeyHex to 2500L),
                description = "Bob Unauthorized Child"
            ),
            createdAtMillis = 850L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, creatorExpenseOp, bobJoinOp, bobChildExpenseOp)
        )

        // Bob's expired join MUST be rejected (applicant's own child cannot establish admission)
        val rejectedIds = result.rejectedOperations.map { it.operation.operationId }
        assertTrue("Bob's expired join must be rejected", bobJoinOp.operationId in rejectedIds)
        val rejJoin = result.rejectedOperations.first { it.operation.operationId == bobJoinOp.operationId }
        assertEquals(RejectionReason.EXPIRED_INVITATION, rejJoin.reason)

        // Bob's child expense MUST also be rejected (parent rejected)
        assertTrue("Bob's child expense must be rejected", bobChildExpenseOp.operationId in rejectedIds)
        val rejExp = result.rejectedOperations.first { it.operation.operationId == bobChildExpenseOp.operationId }
        assertEquals(RejectionReason.PARENT_REJECTED, rejExp.reason)

        assertFalse("Bob must NOT be active member", result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    /**
     * Probe 3: sameSignedLeafAdmissionChangesValidityWithEvaluationClock
     * Replay validity must follow immutable signed ledger evidence rather than the evaluation wall-clock.
     * Evaluating the exact same signed history at expiry-1 and expiry+1 must yield identical outcomes.
     */
    @Test
    fun sameSignedLeafAdmissionChangesValidityWithEvaluationClock() {
        val expiry = 50_000L
        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Deterministic Replay Group",
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
            createdAtMillis = 10_000L
        )

        val grant = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-replay-determinism",
            expiresAtMillis = expiry
        )

        val joinOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
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
            createdAtMillis = 20_000L
        )

        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Deterministic Replay Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        val activeMembers = mapOf(
            creatorKey.publicKeyHex to ProtocolMemberInfo(
                publicKey = creatorKey.publicKeyHex,
                displayName = "Creator",
                role = ProtocolMemberInfo.ROLE_ADMIN
            )
        )

        // Evaluate at expiry - 1
        val outcomeBeforeExpiry = OperationAuthorization.evaluate(
            operation = joinOp,
            trustedGenesis = trustedGenesis,
            activeMembers = activeMembers,
            removedMembers = emptySet(),
            acceptedRemovalsByTarget = emptyMap(),
            ancestorsOf = mapOf(joinOp.operationId to setOf(genesisOp.operationId)),
            consumedInvitationNonces = emptySet(),
            currentTimeMillis = expiry - 1L,
            hasTrustedAdmission = false,
            maxLedgerTimestamp = 20_000L
        )

        // Evaluate at expiry + 1
        val outcomeAfterExpiry = OperationAuthorization.evaluate(
            operation = joinOp,
            trustedGenesis = trustedGenesis,
            activeMembers = activeMembers,
            removedMembers = emptySet(),
            acceptedRemovalsByTarget = emptyMap(),
            ancestorsOf = mapOf(joinOp.operationId to setOf(genesisOp.operationId)),
            consumedInvitationNonces = emptySet(),
            currentTimeMillis = expiry + 1L,
            hasTrustedAdmission = false,
            maxLedgerTimestamp = 20_000L
        )

        // Replay evaluation MUST remain identical regardless of evaluation clock
        assertEquals("Evaluation at expiry-1 and expiry+1 must be identical",
            outcomeBeforeExpiry.isAuthorized, outcomeAfterExpiry.isAuthorized)
        assertTrue("Committed historical join within maxLedgerTimestamp must remain valid",
            outcomeBeforeExpiry.isAuthorized)
    }

    /**
     * Probe 1: actualNostrSenderKeyCannotDecryptEd25519DerivedKeyEnvelope
     * Demonstrates that BIP-340 Nostr public key differs from the sender's Ed25519 public key,
     * so passing event.pubkey to decryptFromPeer fails, while using KeyDistributionEnvelope
     * carrying the authenticated Ed25519 author key succeeds.
     */
    @Test
    fun actualNostrSenderKeyCannotDecryptEd25519DerivedKeyEnvelope() {
        val secretMessage = "new-aes-epoch-key-base64"

        // Sender encrypts for recipient using sender's Ed25519 private key seed and recipient's Ed25519 public key
        val ciphertext = com.peersplit.app.crypto.KeyExchangeUtil.encryptForPeer(
            message = secretMessage,
            myPrivHex = creatorKey.privateKeyHex,
            theirPubHex = bobKey.publicKeyHex
        )

        // Create Nostr event using NostrEvent.createAndSign
        val nostrEvent = com.peersplit.app.network.nostr.NostrEvent.createAndSign(
            pubkey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            kind = com.peersplit.app.network.nostr.NostrEvent.KIND_PEERSPLIT_BLOCK,
            tags = listOf(listOf("t", "peersplit_key")),
            content = ciphertext
        )

        // Nostr derives a BIP-340 / secp256k1 public key which is NOT the Ed25519 key
        assertFalse("Nostr event.pubkey must differ from sender's Ed25519 public key",
            nostrEvent.pubkey.equals(creatorKey.publicKeyHex, ignoreCase = true))

        // Decryption using nostrEvent.pubkey (the old defective path) fails
        var oldPathFailed = false
        try {
            com.peersplit.app.crypto.KeyExchangeUtil.decryptFromPeer(
                ciphertext = ciphertext,
                myPrivHex = bobKey.privateKeyHex,
                theirPubHex = nostrEvent.pubkey
            )
        } catch (_: Exception) {
            oldPathFailed = true
        }
        assertTrue("Raw Nostr event.pubkey path cannot decrypt Ed25519-derived envelope", oldPathFailed)

        // Using KeyDistributionEnvelope carrying the authenticated Ed25519 author key succeeds
        val envelope = com.peersplit.app.crypto.KeyDistributionEnvelope.createAndSign(
            groupId = groupId,
            epoch = 2,
            authorPublicKey = creatorKey.publicKeyHex,
            authorPrivateKeyHex = creatorKey.privateKeyHex,
            recipientPublicKey = bobKey.publicKeyHex,
            transitionOperationId = "transition-op-123",
            keyCiphertext = ciphertext
        )

        assertTrue("Envelope signature must be valid", envelope.verifySignature())

        val decrypted = com.peersplit.app.crypto.KeyExchangeUtil.decryptFromPeer(
            ciphertext = envelope.keyCiphertext,
            myPrivHex = bobKey.privateKeyHex,
            theirPubHex = envelope.authorPublicKey
        )
        assertEquals("Decrypted key must match original message", secretMessage, decrypted)
    }
}
