package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.SignedOperation
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class LedgerReconciliationEngineTest {

    private val engine: LedgerReconciliationEngine = DefaultLedgerReconciliationEngine

    @Test
    fun `three offline users independently add expenses and converge across all arrival permutations`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val charlie = KeyManager.generateKeyPair()
        val groupId = "grp_offline_3"

        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Manali Trek",
                currency = "INR",
                creatorPublicKey = alice.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(alice.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(bob.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER),
                    ProtocolMemberInfo(charlie.publicKeyHex, "Charlie", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )

        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Manali Trek",
            currency = "INR",
            creatorPublicKey = alice.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        val members = listOf(alice.publicKeyHex, bob.publicKeyHex, charlie.publicKeyHex)

        // All 3 devices are offline after receiving only genesisOp and each creates an expense referencing [genesisOp]
        val expAlice = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_alice_1",
                amountMinor = 90000L, // ₹900.00
                currency = "INR",
                paidBy = alice.publicKeyHex,
                splits = MoneyMath.allocateEqualSplits(90000L, members),
                description = "Hotel Booking"
            )
        )

        val expBob = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_bob_1",
                amountMinor = 60000L, // ₹600.00
                currency = "INR",
                paidBy = bob.publicKeyHex,
                splits = MoneyMath.allocateEqualSplits(60000L, members),
                description = "Cab Fare"
            )
        )

        val expCharlie = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = charlie.publicKeyHex,
            privateKeyHex = charlie.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_charlie_1",
                amountMinor = 30000L, // ₹300.00
                currency = "INR",
                paidBy = charlie.publicKeyHex,
                splits = MoneyMath.allocateEqualSplits(30000L, members),
                description = "Snacks"
            )
        )

        val allOps = listOf(genesisOp, expAlice, expBob, expCharlie)
        val rng = Random(42L)

        var referenceResult: ReconciliationResult? = null
        // Test 30 random arrival orders + duplicate deliveries
        repeat(30) {
            val shuffled = (allOps + allOps.shuffled(rng).take(2)).shuffled(rng)
            val result = engine.reconcile(trustedGenesis, shuffled)

            assertEquals(4, result.acceptedOperations.size)
            assertTrue(result.rejectedOperations.isEmpty())
            assertTrue(result.pendingOperations.isEmpty())

            // Verify original author signatures are 100% untouched
            val acceptedById = result.acceptedOperations.associateBy { it.operationId }
            assertEquals(alice.publicKeyHex, acceptedById[expAlice.operationId]?.authorPublicKey)
            assertEquals(expAlice.signature, acceptedById[expAlice.operationId]?.signature)
            assertEquals(bob.publicKeyHex, acceptedById[expBob.operationId]?.authorPublicKey)
            assertEquals(expBob.signature, acceptedById[expBob.operationId]?.signature)
            assertEquals(charlie.publicKeyHex, acceptedById[expCharlie.operationId]?.authorPublicKey)
            assertEquals(expCharlie.signature, acceptedById[expCharlie.operationId]?.signature)

            // Total spent = 180000 paise (60000 paise each)
            // Alice paid 90000 -> +30000
            // Bob paid 60000 -> 0
            // Charlie paid 30000 -> -30000
            assertEquals(30000L, result.memberBalancesMinor[alice.publicKeyHex])
            assertEquals(0L, result.memberBalancesMinor[bob.publicKeyHex])
            assertEquals(-30000L, result.memberBalancesMinor[charlie.publicKeyHex])

            if (referenceResult == null) {
                referenceResult = result
            } else {
                assertEquals(referenceResult!!.stateDigest, result.stateDigest)
                assertEquals(referenceResult!!.memberBalancesMinor, result.memberBalancesMinor)
            }
        }
    }

    @Test
    fun `concurrent edits and concurrent edit-vs-void are resolved deterministically`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val groupId = "grp_conflicts"

        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Conflicts Test",
                currency = "USD",
                creatorPublicKey = alice.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(alice.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(bob.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )
        val trustedGenesis = TrustedGenesis(groupId, "Conflicts Test", "USD", alice.publicKeyHex, genesisOp.operationId)

        val createExp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_shared",
                amountMinor = 4000L,
                currency = "USD",
                paidBy = alice.publicKeyHex,
                splits = mapOf(alice.publicKeyHex to 2000L, bob.publicKeyHex to 2000L),
                description = "Lunch"
            )
        )

        // Alice and Bob concurrently edit `exp_shared` referencing `createExp.operationId`
        val editByAlice = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(createExp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseUpdated(
                expenseId = "exp_shared",
                expectedRevisionId = createExp.operationId,
                replacement = OperationPayload.ExpenseCreated(
                    expenseId = "exp_shared",
                    amountMinor = 5000L,
                    currency = "USD",
                    paidBy = alice.publicKeyHex,
                    splits = mapOf(alice.publicKeyHex to 2500L, bob.publicKeyHex to 2500L),
                    description = "Lunch + Tip"
                )
            )
        )

        val editByBob = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(createExp.operationId),
            logicalClock = 4L,
            payload = OperationPayload.ExpenseUpdated(
                expenseId = "exp_shared",
                expectedRevisionId = createExp.operationId,
                replacement = OperationPayload.ExpenseCreated(
                    expenseId = "exp_shared",
                    amountMinor = 6000L,
                    currency = "USD",
                    paidBy = alice.publicKeyHex,
                    splits = mapOf(alice.publicKeyHex to 3000L, bob.publicKeyHex to 3000L),
                    description = "Lunch + Dessert"
                )
            )
        )

        val res1 = engine.reconcile(trustedGenesis, listOf(genesisOp, createExp, editByAlice, editByBob))
        val res2 = engine.reconcile(trustedGenesis, listOf(editByBob, editByAlice, createExp, genesisOp))

        assertEquals(res1.stateDigest, res2.stateDigest)
        assertEquals(1, res1.conflicts.size)
        assertEquals(editByAlice.operationId, res1.conflicts.first().winningOperationId)
        assertEquals(editByBob.operationId, res1.conflicts.first().losingOperationId)
        assertEquals(5000L, res1.effectiveExpenses["exp_shared"]?.payload?.amountMinor)

        // Now add a concurrent Void by Bob targeting `createExp.operationId` (clock = 3L)
        val voidByBob = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(createExp.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseVoided(
                expenseId = "exp_shared",
                expectedRevisionId = createExp.operationId,
                reason = "Duplicate bill"
            )
        )

        val resVoid1 = engine.reconcile(trustedGenesis, listOf(genesisOp, createExp, editByAlice, voidByBob))
        val resVoid2 = engine.reconcile(trustedGenesis, listOf(voidByBob, editByAlice, createExp, genesisOp))

        assertEquals(resVoid1.stateDigest, resVoid2.stateDigest)
        assertTrue("Concurrent void must win over concurrent edit on the same revision", resVoid1.effectiveExpenses["exp_shared"]!!.isVoided)
        assertEquals(0L, resVoid1.memberBalancesMinor[alice.publicKeyHex])
        assertEquals(0L, resVoid1.memberBalancesMinor[bob.publicKeyHex])
    }

    @Test
    fun `removed member cannot bypass authorization by manipulating logical clocks or referencing old parents`() {
        val alice = KeyManager.generateKeyPair()
        val mallory = KeyManager.generateKeyPair()
        val groupId = "grp_security"

        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Security Group",
                currency = "INR",
                creatorPublicKey = alice.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(alice.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(mallory.publicKeyHex, "Mallory", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )
        val trustedGenesis = TrustedGenesis(groupId, "Security Group", "INR", alice.publicKeyHex, genesisOp.operationId)

        // Alice removes Mallory at logicalClock = 5
        val removeMallory = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 5L,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = mallory.publicKeyHex,
                reason = "Left group",
                newKeyEpoch = 1
            )
        )

        // Mallory tries to backdate logicalClock = 2 referencing genesisOp to inject an expense concurrently with removal
        val maliciousExpense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = mallory.publicKeyHex,
            privateKeyHex = mallory.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_attack",
                amountMinor = 500000L,
                currency = "INR",
                paidBy = mallory.publicKeyHex,
                splits = mapOf(alice.publicKeyHex to 500000L),
                description = "Fake Expense"
            )
        )

        val result = engine.reconcile(trustedGenesis, listOf(genesisOp, maliciousExpense, removeMallory))
        assertTrue(result.effectiveExpenses.isEmpty())
        assertEquals(1, result.rejectedOperations.size)
        assertEquals(RejectionReason.UNAUTHORIZED_REMOVED_MEMBER, result.rejectedOperations.first().reason)
        assertEquals(0L, result.memberBalancesMinor[alice.publicKeyHex])
    }

    @Test
    fun `missing causal dependencies remain pending and never affect balances`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val groupId = "grp_pending"

        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Pending Test",
                currency = "INR",
                creatorPublicKey = alice.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(alice.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(bob.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )
        val trustedGenesis = TrustedGenesis(groupId, "Pending Test", "INR", alice.publicKeyHex, genesisOp.operationId)

        val missingParentId = "f".repeat(64)
        val orphanExpense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(missingParentId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_orphan",
                amountMinor = 10000L,
                currency = "INR",
                paidBy = bob.publicKeyHex,
                splits = mapOf(alice.publicKeyHex to 5000L, bob.publicKeyHex to 5000L),
                description = "Orphan"
            )
        )

        val result = engine.reconcile(trustedGenesis, listOf(genesisOp, orphanExpense))
        assertEquals(1, result.acceptedOperations.size)
        assertEquals(1, result.pendingOperations.size)
        assertTrue(missingParentId in result.pendingOperations.first().missingParentIds)
        assertEquals(0L, result.memberBalancesMinor[alice.publicKeyHex])
    }
}
