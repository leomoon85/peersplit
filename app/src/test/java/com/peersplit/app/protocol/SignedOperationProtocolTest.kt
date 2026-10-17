package com.peersplit.app.protocol

import com.peersplit.app.crypto.KeyManager
import org.junit.Assert.*
import org.junit.Test

class SignedOperationProtocolTest {

    @Test
    fun `canonical serialization produces identical bytes across map and parent permutations`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val charlie = KeyManager.generateKeyPair()

        val parent1 = "a".repeat(64)
        val parent2 = "b".repeat(64)

        val splitsOrder1 = linkedMapOf(
            alice.publicKeyHex to 3334L,
            bob.publicKeyHex to 3333L,
            charlie.publicKeyHex to 3333L
        )
        val splitsOrder2 = linkedMapOf(
            charlie.publicKeyHex to 3333L,
            alice.publicKeyHex to 3334L,
            bob.publicKeyHex to 3333L
        )

        val payload1 = OperationPayload.ExpenseCreated(
            expenseId = "exp_001",
            amountMinor = 10000L,
            currency = "INR",
            paidBy = alice.publicKeyHex,
            splits = splitsOrder1,
            description = "Café Dinner"
        )
        // Use decomposed Unicode 'e' + combining acute accent for "Cafe\u0301 Dinner"
        val payload2 = OperationPayload.ExpenseCreated(
            expenseId = "exp_001",
            amountMinor = 10000L,
            currency = "INR",
            paidBy = alice.publicKeyHex,
            splits = splitsOrder2,
            description = "Cafe\u0301 Dinner"
        )

        val testTimestamp = 1700000000000L
        val op1 = OperationSigner.createAndSign(
            groupId = "grp_test_1",
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(parent2, parent1),
            logicalClock = 5L,
            payload = payload1,
            createdAtMillis = testTimestamp
        )

        val op2 = OperationSigner.createAndSign(
            groupId = "grp_test_1",
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(parent1, parent2),
            logicalClock = 5L,
            payload = payload2,
            createdAtMillis = testTimestamp
        )

        assertEquals("Operation IDs must be identical across key/parent order and NFC normalization", op1.operationId, op2.operationId)
        assertEquals("Ed25519 signatures must be deterministic and identical", op1.signature, op2.signature)
        assertTrue(OperationVerifier.verify(op1))
        assertTrue(OperationVerifier.verify(op2))
    }

    @Test
    fun `altering any signed field invalidates operationId and signature`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val parent = "1".repeat(64)

        val op = OperationSigner.createAndSign(
            groupId = "grp_test_2",
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(parent),
            logicalClock = 2L,
            payload = OperationPayload.SettlementRecorded(
                settlementId = "stl_100",
                fromMember = bob.publicKeyHex,
                toMember = alice.publicKeyHex,
                amountMinor = 50000L,
                currency = "INR"
            )
        )

        assertTrue(OperationValidator.validate(op, "INR") is OperationValidationOutcome.Valid)

        // Tamper with amountMinor while keeping original operationId and signature
        val tamperedAmount = op.copy(
            payload = (op.payload as OperationPayload.SettlementRecorded).copy(amountMinor = 100L)
        )
        assertFalse(OperationVerifier.hasValidOperationId(tamperedAmount))
        assertFalse(OperationVerifier.verify(tamperedAmount))
        assertTrue(OperationValidator.validate(tamperedAmount, "INR") is OperationValidationOutcome.Invalid)

        // Tamper with logicalClock and recompute operationId without Alice's private key
        val tamperedClockBytes = OperationCanonicalizer.canonicalizeUnsigned(
            op.copy(logicalClock = 99L)
        )
        val forgedOpId = OperationId.compute(tamperedClockBytes)
        val forgedOp = op.copy(logicalClock = 99L, operationId = forgedOpId)
        assertTrue(OperationVerifier.hasValidOperationId(forgedOp))
        assertFalse("Original signature must fail when operationId/clock is altered", OperationVerifier.hasValidSignature(forgedOp))
    }

    @Test
    fun `validator rejects unsupported protocol versions, duplicate parents, and split mismatches`() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val parent = "a".repeat(64)

        // 1. Split sum mismatch
        val badSplitOp = OperationSigner.createAndSign(
            groupId = "grp_1",
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = listOf(parent),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp_bad",
                amountMinor = 1000L,
                currency = "INR",
                paidBy = alice.publicKeyHex,
                splits = mapOf(alice.publicKeyHex to 400L, bob.publicKeyHex to 400L), // sums to 800 != 1000
                description = "Bad Split"
            )
        )
        val outcome1 = OperationValidator.validate(badSplitOp, "INR")
        assertTrue(outcome1 is OperationValidationOutcome.Invalid)
        assertEquals("SPLITS_SUM_MISMATCH", (outcome1 as OperationValidationOutcome.Invalid).code)

        // 2. Unsupported protocol version
        val badVersionOp = badSplitOp.copy(protocolVersion = 99)
        val outcome2 = OperationValidator.validate(badVersionOp, "INR")
        assertTrue(outcome2 is OperationValidationOutcome.Invalid)
        assertEquals("UNSUPPORTED_PROTOCOL_VERSION", (outcome2 as OperationValidationOutcome.Invalid).code)

        // 3. Duplicate parent IDs in envelope
        val dupParentOp = badSplitOp.copy(parents = listOf(parent, parent))
        val outcome3 = OperationValidator.validate(dupParentOp, "INR")
        assertTrue(outcome3 is OperationValidationOutcome.Invalid)
        assertEquals("DUPLICATE_PARENTS", (outcome3 as OperationValidationOutcome.Invalid).code)
    }

    @Test
    fun `MoneyMath allocates equal splits deterministically without losing remainder paise`() {
        val m1 = "11".repeat(32)
        val m2 = "22".repeat(32)
        val m3 = "33".repeat(32)

        val splitsA = MoneyMath.allocateEqualSplits(10000L, listOf(m3, m1, m2))
        val splitsB = MoneyMath.allocateEqualSplits(10000L, listOf(m1, m2, m3))

        assertEquals(splitsA, splitsB)
        assertEquals(10000L, splitsA.values.sum())
        assertEquals(3334L, splitsA[m1])
        assertEquals(3333L, splitsA[m2])
        assertEquals(3333L, splitsA[m3])
    }
}
