package com.peersplit.protocol

import com.peersplit.protocol.crypto.PureSha256
import com.peersplit.reconciliation.DefaultLedgerReconciliationEngine
import com.peersplit.reconciliation.LedgerProjection
import com.peersplit.reconciliation.TrustedGenesis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenVectorsTest {

    // Test Keypair Vectors (Deterministic Ed25519)
    // Alice (Admin/Creator)
    private val alicePriv = "4c974570f7204071ab4e4de4014500736f97d323fbad01372f4441caadeffe93"
    private val alicePub = com.peersplit.protocol.crypto.PlatformCrypto.derivePublicKey(alicePriv)

    // Bob (Member)
    private val bobPriv = "e5760fc8aa787262f6b3cf3a1e9c2c2f647970d24cc1c238e78553648eb113dc"
    private val bobPub = com.peersplit.protocol.crypto.PlatformCrypto.derivePublicKey(bobPriv)

    // Charlie (Member)
    private val charliePriv = "7dc09e530fe86a079948067f9fa68cf38c1052677119e7a8e7e1744cb89a3a93"
    private val charliePub = com.peersplit.protocol.crypto.PlatformCrypto.derivePublicKey(charliePriv)


    private val testGroupId = "group-golden-vector-test-001"

    @Test
    fun testPureSha256StandardVectors() {
        // Standard NIST FIPS 180-4 vectors
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PureSha256.digestHex(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PureSha256.digestHex("abc")
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            PureSha256.digestHex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
    }

    @Test
    fun testExactIntegerMinorUnitMoneyMath() {
        // Split ₹100.00 (10000 paise) among 3 members: Alice, Bob, Charlie
        val splits = MoneyMath.allocateEqualSplits(
            totalAmountMinor = 10000L,
            memberPublicKeys = listOf(alicePub, bobPub, charliePub)
        )

        // Exact integer distribution: 3334, 3333, 3333
        var total = 0L
        for ((_, share) in splits) {
            total = MoneyMath.addExact(total, share)
        }
        assertEquals(10000L, total)

        // Verify remainder (+1) allocated to lexicographically first member
        val sortedMembers = listOf(alicePub, bobPub, charliePub).sorted()
        assertEquals(3334L, splits[sortedMembers[0]])
        assertEquals(3333L, splits[sortedMembers[1]])
        assertEquals(3333L, splits[sortedMembers[2]])
    }

    @Test
    fun testCanonicalBinarySerializationDeterminism() {
        val genesis = OperationPayload.GenesisCreated(
            groupId = testGroupId,
            groupName = "Trip to Tokyo",
            currency = "JPY",
            creatorPublicKey = alicePub,
            initialMembers = listOf(
                ProtocolMemberInfo(alicePub, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                ProtocolMemberInfo(bobPub, "Bob", ProtocolMemberInfo.ROLE_MEMBER)
            ),
            initialKeyEpoch = 0
        )

        val bytes1 = OperationCanonicalizer.canonicalizeUnsigned(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = testGroupId,
            authorPublicKey = alicePub,
            parents = emptyList(),
            logicalClock = 1L,
            keyEpoch = 0,
            payload = genesis,
            createdAtMillis = 1700000000000L
        )

        val bytes2 = OperationCanonicalizer.canonicalizeUnsigned(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = " $testGroupId ",
            authorPublicKey = alicePub.uppercase(),
            parents = emptyList(),
            logicalClock = 1L,
            keyEpoch = 0,
            payload = genesis.copy(
                // Inverted order of initialMembers to verify canonical sorting
                initialMembers = listOf(
                    ProtocolMemberInfo(bobPub, "Bob", ProtocolMemberInfo.ROLE_MEMBER),
                    ProtocolMemberInfo(alicePub, "Alice", ProtocolMemberInfo.ROLE_ADMIN)
                )
            ),
            createdAtMillis = 1700000000000L
        )

        assertEquals(PureSha256.toHex(bytes1), PureSha256.toHex(bytes2))
        val opId = OperationId.compute(bytes1)
        assertTrue(OperationId.isValidFormat(opId))
    }

    @Test
    fun testDeterministicReconciliationPermutationInvariance() {
        val trustedGenesis = TrustedGenesis(
            groupId = testGroupId,
            groupName = "Tokyo Trip",
            currency = "INR",
            creatorPublicKey = alicePub
        )

        // 1. Genesis Operation
        val genesisOp = OperationSigner.createAndSign(
            groupId = testGroupId,
            authorPublicKey = alicePub,
            privateKeyHex = alicePriv,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = testGroupId,
                groupName = "Tokyo Trip",
                currency = "INR",
                creatorPublicKey = alicePub,
                initialMembers = listOf(
                    ProtocolMemberInfo(alicePub, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(bobPub, "Bob", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )

        // 2. Add Charlie via Admin signed invitation grant
        val grant = OperationSigner.createInvitationGrant(
            inviterPublicKey = alicePub,
            inviterPrivateKeyHex = alicePriv,
            invitedPublicKey = charliePub,
            groupId = testGroupId,
            targetRole = ProtocolMemberInfo.ROLE_MEMBER,
            invitationNonce = "nonce-golden-001",
            expiresAtMillis = 0L
        )

        val addCharlieOp = OperationSigner.createAndSign(
            groupId = testGroupId,
            authorPublicKey = charliePub,
            privateKeyHex = charliePriv,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = charliePub,
                displayName = "Charlie",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = grant
            )
        )

        // 3. Alice creates Hotel Expense (₹6,000.00 = 600,000 paise) split equally among Alice, Bob, Charlie
        val hotelSplits = MoneyMath.allocateEqualSplits(600_000L, listOf(alicePub, bobPub, charliePub))
        val expenseHotelOp = OperationSigner.createAndSign(
            groupId = testGroupId,
            authorPublicKey = alicePub,
            privateKeyHex = alicePriv,
            parents = listOf(addCharlieOp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-hotel-001",
                amountMinor = 600_000L,
                currency = "INR",
                paidBy = alicePub,
                splits = hotelSplits,
                description = "Shinjuku Hotel"
            )
        )

        // 4. Bob creates Dinner Expense (₹3,000.00 = 300,000 paise) split equally
        val dinnerSplits = MoneyMath.allocateEqualSplits(300_000L, listOf(alicePub, bobPub, charliePub))
        val expenseDinnerOp = OperationSigner.createAndSign(
            groupId = testGroupId,
            authorPublicKey = bobPub,
            privateKeyHex = bobPriv,
            parents = listOf(expenseHotelOp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-dinner-001",
                amountMinor = 300_000L,
                currency = "INR",
                paidBy = bobPub,
                splits = dinnerSplits,
                description = "Ramen Dinner"
            )
        )

        // 5. Charlie settles ₹1,000.00 (100,000 paise) directly to Alice
        val settlementOp = OperationSigner.createAndSign(
            groupId = testGroupId,
            authorPublicKey = charliePub,
            privateKeyHex = charliePriv,
            parents = listOf(expenseDinnerOp.operationId),
            logicalClock = 5L,
            payload = OperationPayload.SettlementRecorded(
                settlementId = "stl-001",
                fromMember = charliePub,
                toMember = alicePub,
                amountMinor = 100_000L,
                currency = "INR",
                note = "UPI settlement"
            )
        )

        val fullOpSet = listOf(genesisOp, addCharlieOp, expenseHotelOp, expenseDinnerOp, settlementOp)

        // Verify all signatures independently
        for ((idx, op) in fullOpSet.withIndex()) {
            val hasValidId = OperationVerifier.hasValidOperationId(op)
            val hasValidSig = OperationVerifier.hasValidSignature(op)
            assertTrue(hasValidId, "Op $idx (${op.payload::class.simpleName}) hasValidOperationId failed")
            assertTrue(hasValidSig, "Op $idx (${op.payload::class.simpleName}) hasValidSignature failed")
        }

        // Baseline reconciliation result
        val baseline = DefaultLedgerReconciliationEngine.reconcile(trustedGenesis, fullOpSet)
        assertEquals(5, baseline.acceptedOperations.size)
        assertEquals(0, baseline.rejectedOperations.size)
        assertEquals(0, baseline.pendingOperations.size)

        // Balances:
        // Alice: paid 600,000 hotel, consumed 200,000 hotel + 100,000 dinner = net +300,000; received 100,000 stl = +200,000
        // Bob: paid 300,000 dinner, consumed 200,000 hotel + 100,000 dinner = net 0
        // Charlie: paid 0, consumed 200,000 hotel + 100,000 dinner = -300,000; paid 100,000 stl = -200,000
        // Net sum: 200,000 + 0 + (-200,000) = 0
        assertEquals(200_000L, baseline.memberBalancesMinor[alicePub])
        assertEquals(0L, baseline.memberBalancesMinor[bobPub])
        assertEquals(-200_000L, baseline.memberBalancesMinor[charliePub])

        // Verify Order-Independent Convergence across 8 permutations
        val permutations = listOf(
            fullOpSet,
            fullOpSet.reversed(),
            listOf(settlementOp, expenseDinnerOp, expenseHotelOp, addCharlieOp, genesisOp),
            listOf(expenseHotelOp, genesisOp, settlementOp, addCharlieOp, expenseDinnerOp),
            listOf(addCharlieOp, settlementOp, genesisOp, expenseDinnerOp, expenseHotelOp),
            listOf(expenseDinnerOp, addCharlieOp, settlementOp, genesisOp, expenseHotelOp),
            listOf(settlementOp, genesisOp, addCharlieOp, expenseHotelOp, expenseDinnerOp),
            listOf(genesisOp, expenseDinnerOp, expenseHotelOp, settlementOp, addCharlieOp)
        )

        for ((index, perm) in permutations.withIndex()) {
            val result = DefaultLedgerReconciliationEngine.reconcile(trustedGenesis, perm)
            assertEquals(
                baseline.stateDigest,
                result.stateDigest,
                "Permutation $index must yield identical stateDigest"
            )
            assertEquals(
                baseline.memberBalancesMinor,
                result.memberBalancesMinor,
                "Permutation $index must yield identical memberBalancesMinor"
            )
            assertEquals(
                baseline.acceptedOperations.map { it.operationId },
                result.acceptedOperations.map { it.operationId },
                "Permutation $index must yield identical acceptedOperations"
            )
        }
    }
}
