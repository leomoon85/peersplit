package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.protocol.OperationCanonicalizer
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.OperationVerifier
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety regression test suite inverted from audit findings A01, A02, A03, A04, A16, A28.
 * Each test verifies that the vulnerability is remediated and rejects malicious or invalid behavior.
 */
class AuditRegressionProbeTest {

    private val creatorKey = KeyManager.generateKeyPair()
    private val adminKey = KeyManager.generateKeyPair()
    private val bobKey = KeyManager.generateKeyPair()
    private val charlieKey = KeyManager.generateKeyPair()
    private val attackerKey = KeyManager.generateKeyPair()

    private val groupId = "probe-audit-group"

    private fun setupGenesis(): Pair<TrustedGenesis, SignedOperation> {
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
                groupName = "Audit Probe Group",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = initialMembers
            )
        )
        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Audit Probe Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )
        return trustedGenesis to genesisOp
    }

    /**
     * A01 [P1]: Inverted probe for ghost claims.
     * Claiming the group creator (or any non-ghost member) as a ghost alias must be REJECTED,
     * and balances must NOT be reassigned.
     */
    @Test
    fun realCreatorCanBeClaimedAsGhostAndBalancesErased_InvertedSafetyRegression() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val now = System.currentTimeMillis()

        // 1. Legitimate expense: Creator paid 1000 INR, split with Admin (Admin owes Creator 500)
        val expenseOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-1",
                amountMinor = 100000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 50000L, adminKey.publicKeyHex to 50000L),
                description = "Dinner",
                payers = mapOf(creatorKey.publicKeyHex to 100000L)
            ),
            createdAtMillis = now
        )

        // 2. Attacker joins group legitimately
        val grantForAttacker = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = attackerKey.publicKeyHex,
            invitationNonce = "nonce-attacker-01",
            expiresAtMillis = now + 86400_000L
        )
        val attackerJoinOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(expenseOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = attackerKey.publicKeyHex,
                displayName = "Attacker",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = grantForAttacker
            ),
            createdAtMillis = now + 1000L
        )

        // 3. Attacker attempts to claim Creator as a ghost alias
        val maliciousClaimOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(attackerJoinOp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = attackerKey.publicKeyHex,
                displayName = "Attacker",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                claimedGhostPubkey = creatorKey.publicKeyHex
            ),
            createdAtMillis = now + 2000L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, expenseOp, attackerJoinOp, maliciousClaimOp)
        )

        // Malicious claim must be rejected
        val rejectedIds = result.rejectedOperations.map { it.operation.operationId }
        assertTrue("Malicious ghost claim of creator must be rejected", maliciousClaimOp.operationId in rejectedIds)
        assertFalse("Creator must not be in ghost aliases", result.ghostAliases.containsKey(creatorKey.publicKeyHex))
        assertTrue("Creator must remain active member", result.activeMembers.containsKey(creatorKey.publicKeyHex))

        // Creator's balance (+500 INR = +50000 minor) must be intact and not reassigned to attacker
        assertEquals(50000L, result.memberBalancesMinor[creatorKey.publicKeyHex])
        assertEquals(0L, result.memberBalancesMinor[attackerKey.publicKeyHex] ?: 0L)
    }

    /**
     * A02 [P1]: Inverted probe for rejected removal precedence leak.
     * A rejected admin grant or rejected removal operation must NOT suppress legitimate expenses.
     */
    @Test
    fun rejectedAdminGrantStillLetsRejectedRemovalSuppressExpense_InvertedSafetyRegression() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val now = System.currentTimeMillis()

        // 1. Add Bob as ordinary member
        val grantForBob = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-bob-01",
            expiresAtMillis = now + 86400_000L
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
                invitationGrant = grantForBob
            ),
            createdAtMillis = now
        )

        // 2. Attacker (unauthorized) tries to revoke Bob's membership
        val unauthorizedRemovalOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = attackerKey.publicKeyHex,
            privateKeyHex = attackerKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = bobKey.publicKeyHex,
                reason = "Attacker malicious kick"
            ),
            createdAtMillis = now + 1000L
        )

        // 3. Bob authors a legitimate expense
        val bobExpenseOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-bob-1",
                amountMinor = 60000L,
                currency = "INR",
                paidBy = bobKey.publicKeyHex,
                splits = mapOf(bobKey.publicKeyHex to 30000L, creatorKey.publicKeyHex to 30000L),
                description = "Bob Groceries",
                payers = mapOf(bobKey.publicKeyHex to 60000L)
            ),
            createdAtMillis = now + 2000L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp, unauthorizedRemovalOp, bobExpenseOp)
        )

        // Unauthorized removal is rejected
        val rejectedIds = result.rejectedOperations.map { it.operation.operationId }
        assertTrue("Unauthorized removal must be rejected", unauthorizedRemovalOp.operationId in rejectedIds)

        // Bob's expense MUST BE ACCEPTED (not suppressed by rejected removal)
        val acceptedIds = result.acceptedOperations.map { it.operationId }
        assertTrue("Bob's expense must be accepted despite rejected removal", bobExpenseOp.operationId in acceptedIds)
        assertTrue(result.effectiveExpenses.containsKey("exp-bob-1"))
    }

    /**
     * A03 [P1]: Inverted probe for valid removal lowering maximum accepted clock.
     * When a member is legitimately removed at clock 2, operations authored by that member at clock 100
     * are rejected. The maximum accepted logical clock legitimately drops, and reconciliation succeeds.
     */
    @Test
    fun validRemovalCanLowerMaximumAcceptedClock_InvertedSafetyRegression() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val now = System.currentTimeMillis()

        // 1. Bob joins
        val grantForBob = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-bob-clock",
            expiresAtMillis = now + 86400_000L
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
                invitationGrant = grantForBob
            ),
            createdAtMillis = now
        )

        // 2. Bob authors an expense at logicalClock = 100
        val bobHighClockExpense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 100L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-high-clock",
                amountMinor = 50000L,
                currency = "INR",
                paidBy = bobKey.publicKeyHex,
                splits = mapOf(bobKey.publicKeyHex to 50000L),
                description = "High clock expense"
            ),
            createdAtMillis = now + 1000L
        )

        // 3. Creator legitimately removes Bob at clock 3
        val creatorRemoveBobOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = bobKey.publicKeyHex,
                reason = "Admin removal"
            ),
            createdAtMillis = now + 2000L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp, creatorRemoveBobOp, bobHighClockExpense)
        )

        // Creator's removal is accepted, Bob's high-clock expense is rejected
        val acceptedIds = result.acceptedOperations.map { it.operationId }
        assertTrue("Creator removal must be accepted", creatorRemoveBobOp.operationId in acceptedIds)
        assertFalse("Bob's high clock expense must NOT be accepted", bobHighClockExpense.operationId in acceptedIds)

        // Max logical clock reflects the accepted ledger state
        assertEquals(3L, result.maxLogicalClock)
    }

    /**
     * A04 [P1]: Inverted probe for expired grant via V2 downgrade.
     * Expired grants cannot be accepted via V2 downgrade with createdAtMillis = 0.
     */
    @Test
    fun reviewProbeExpiredGrantAcceptedViaV2Downgrade_InvertedSafetyRegression() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val now = System.currentTimeMillis()

        // Admin creates an expired grant
        val expiredGrant = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-expired-probe",
            expiresAtMillis = now - 60_000L
        )

        // Bob attempts to join with createdAtMillis = 0L to bypass expiry
        val opV2 = OperationSigner.createAndSign(
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
            createdAtMillis = 0L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, opV2)
        )

        // Must be rejected
        assertEquals(1, result.acceptedOperations.size)
        assertEquals(1, result.rejectedOperations.size)
        val rej = result.rejectedOperations.first()
        assertTrue(
            "Expired grant via V2 must be rejected with invalid invitation or invalid envelope",
            rej.reason == RejectionReason.INVALID_INVITATION || rej.reason == RejectionReason.INVALID_ENVELOPE_OR_PAYLOAD
        )
        assertFalse(result.activeMembers.containsKey(bobKey.publicKeyHex))
    }

    /**
     * A16 [P2]: Inverted probe for admin readmission.
     * When an admin readmits a previously removed member, that member's subsequent operations
     * descended from the readmission MUST be accepted.
     */
    @Test
    fun adminReadmissionDoesNotRestoreAbilityToAuthor_InvertedSafetyRegression() {
        val (trustedGenesis, genesisOp) = setupGenesis()
        val now = System.currentTimeMillis()

        // 1. Bob joins
        val grantForBob = OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = bobKey.publicKeyHex,
            invitationNonce = "nonce-readmit-01",
            expiresAtMillis = now + 86400_000L
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
                invitationGrant = grantForBob
            ),
            createdAtMillis = now
        )

        // 2. Creator removes Bob
        val removeBobOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(bobJoinOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = bobKey.publicKeyHex,
                reason = "Temporary suspension"
            ),
            createdAtMillis = now + 1000L
        )

        // 3. Creator readmits Bob directly as Admin
        val readmitBobOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(removeBobOp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = bobKey.publicKeyHex,
                displayName = "Bob",
                role = ProtocolMemberInfo.ROLE_MEMBER
            ),
            createdAtMillis = now + 2000L
        )

        // 4. Bob authors a new expense descended from the readmission
        val bobPostReadmitExpense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bobKey.publicKeyHex,
            privateKeyHex = bobKey.privateKeyHex,
            parents = listOf(readmitBobOp.operationId),
            logicalClock = 5L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-readmitted-bob",
                amountMinor = 40000L,
                currency = "INR",
                paidBy = bobKey.publicKeyHex,
                splits = mapOf(bobKey.publicKeyHex to 20000L, creatorKey.publicKeyHex to 20000L),
                description = "Post Readmission Lunch"
            ),
            createdAtMillis = now + 3000L
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, bobJoinOp, removeBobOp, readmitBobOp, bobPostReadmitExpense)
        )

        val acceptedIds = result.acceptedOperations.map { it.operationId }
        assertTrue("Readmission op must be accepted", readmitBobOp.operationId in acceptedIds)
        assertTrue("Bob's post-readmission expense MUST be accepted", bobPostReadmitExpense.operationId in acceptedIds)
        assertTrue(result.activeMembers.containsKey(bobKey.publicKeyHex))
        assertTrue(result.effectiveExpenses.containsKey("exp-readmitted-bob"))
    }

    /**
     * A28 [P1]: Inverted probe for interim commit 79a036c timestamp-signed V2 records.
     * Records signed with protocolVersion = 2 and createdAtMillis > 0 must verify successfully.
     */
    @Test
    fun interimTimestampSignedV2RecordsAreRejectedAfterUpgrade_InvertedSafetyRegression() {
        val now = 1729200000000L

        // Construct an operation using interim commit 79a036c encoding:
        // protocolVersion = 2, createdAtMillis > 0, canonicalized with unsigned interim format
        val payload = OperationPayload.ExpenseCreated(
            expenseId = "exp-interim-v2",
            amountMinor = 150000L,
            currency = "INR",
            paidBy = creatorKey.publicKeyHex,
            splits = mapOf(creatorKey.publicKeyHex to 150000L),
            description = "Interim Commit V2 Record"
        )
        val parents = listOf("parent-op-id-1234567890abcdef")
        val interimOpTemplate = SignedOperation(
            operationId = "",
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            parents = parents,
            logicalClock = 2L,
            payload = payload,
            signature = "",
            createdAtMillis = now,
            keyEpoch = 0
        )
        val canonicalBytes = OperationCanonicalizer.canonicalizeUnsignedInterimV2(interimOpTemplate)
        val opId = com.peersplit.app.protocol.OperationId.compute(canonicalBytes)
        val signingDigest = OperationSigner.computeSigningDigest(opId, canonicalBytes)
        val sigHex = KeyManager.signHex(signingDigest, creatorKey.privateKeyHex)

        val interimOp = interimOpTemplate.copy(
            operationId = opId,
            signature = sigHex
        )

        // Verifier must accept this record!
        assertTrue("Interim V2 record must have valid operationId", OperationVerifier.hasValidOperationId(interimOp))
        assertTrue("Interim V2 record must have valid signature", OperationVerifier.hasValidSignature(interimOp))
        assertTrue("Interim V2 record must verify completely", OperationVerifier.verify(interimOp))
    }
}
