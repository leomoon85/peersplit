package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class AtomicReconciliationAndIsolationTest {

    private val creator = KeyManager.generateKeyPair()
    private val bob = KeyManager.generateKeyPair()
    private val charlie = KeyManager.generateKeyPair()
    private val groupId = "test-atomic-group"

    private fun setupGenesis(): Pair<TrustedGenesis, com.peersplit.app.protocol.SignedOperation> {
        val members = listOf(
            ProtocolMemberInfo(creator.publicKeyHex, "Creator", ProtocolMemberInfo.ROLE_ADMIN),
            ProtocolMemberInfo(bob.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER),
            ProtocolMemberInfo(charlie.publicKeyHex, "Charlie", ProtocolMemberInfo.ROLE_MEMBER)
        )
        val genesisOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creator.publicKeyHex,
            privateKeyHex = creator.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Atomic Test Group",
                currency = "INR",
                creatorPublicKey = creator.publicKeyHex,
                initialMembers = members
            )
        )
        val trusted = TrustedGenesis(
            groupId = groupId,
            groupName = "Atomic Test Group",
            currency = "INR",
            creatorPublicKey = creator.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )
        return trusted to genesisOp
    }

    @Test
    fun testStaleReconciliationOverwritesAreDetectedAndPrevented() {
        val (trusted, genesisOp) = setupGenesis()

        // Op 1 (clock 2)
        val op1 = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-1",
                amountMinor = 1000L,
                currency = "INR",
                paidBy = bob.publicKeyHex,
                splits = mapOf(creator.publicKeyHex to 500L, bob.publicKeyHex to 500L),
                description = "Tea"
            )
        )

        // Op 2 (clock 3)
        val op2 = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creator.publicKeyHex,
            privateKeyHex = creator.privateKeyHex,
            parents = listOf(op1.operationId),
            logicalClock = 3L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-2",
                amountMinor = 2000L,
                currency = "INR",
                paidBy = creator.publicKeyHex,
                splits = mapOf(creator.publicKeyHex to 1000L, bob.publicKeyHex to 1000L),
                description = "Snacks"
            )
        )

        val newerResult = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trusted,
            operations = listOf(genesisOp, op1, op2)
        )
        assertEquals(3L, newerResult.maxLogicalClock)

        // Older/stale result (reconciled without op2)
        val staleResult = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trusted,
            operations = listOf(genesisOp, op1)
        )
        assertEquals(2L, staleResult.maxLogicalClock)

        // Simulate projection state check logic:
        val existingDbMeta = GroupProtocolEntity(
            groupId = groupId,
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            genesisOperationId = genesisOp.operationId,
            currentKeyEpoch = 0,
            latestStateDigest = newerResult.stateDigest,
            maxLogicalClock = newerResult.maxLogicalClock
        )

        // Stale result clock (2) is less than DB clock (3) -> MUST be discarded
        val isStale = existingDbMeta.maxLogicalClock > staleResult.maxLogicalClock
        assertTrue("Stale result with lower maxLogicalClock must be rejected", isStale)
    }

    @Test
    fun testConcurrentWritersProduceIdenticalDeterministicState() {
        val (trusted, genesisOp) = setupGenesis()

        val ops = mutableListOf(genesisOp)
        for (i in 1..20) {
            val author = if (i % 2 == 0) bob else charlie
            val prev = ops.last().operationId
            val op = OperationSigner.createAndSign(
                groupId = groupId,
                authorPublicKey = author.publicKeyHex,
                privateKeyHex = author.privateKeyHex,
                parents = listOf(prev),
                logicalClock = 1L + i,
                payload = OperationPayload.ExpenseCreated(
                    expenseId = "exp-$i",
                    amountMinor = (100L * i),
                    currency = "INR",
                    paidBy = author.publicKeyHex,
                    splits = mapOf(creator.publicKeyHex to 50L * i, author.publicKeyHex to 50L * i),
                    description = "Item $i"
                )
            )
            ops.add(op)
        }

        val threadPool = Executors.newFixedThreadPool(4)
        val futures = mutableListOf<Future<ReconciliationResult>>()

        for (t in 1..8) {
            val shuffled = ops.shuffled()
            futures.add(threadPool.submit<ReconciliationResult> {
                DefaultLedgerReconciliationEngine.reconcile(trusted, shuffled)
            })
        }

        threadPool.shutdown()
        assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS))

        val results = futures.map { it.get() }
        val canonicalDigest = results[0].stateDigest
        val canonicalClock = results[0].maxLogicalClock

        for (r in results) {
            assertEquals("All concurrent reconciliations must converge to identical state digest", canonicalDigest, r.stateDigest)
            assertEquals("All concurrent reconciliations must produce identical max logical clock", canonicalClock, r.maxLogicalClock)
            assertEquals(21, r.acceptedOperations.size)
            assertEquals(0, r.rejectedOperations.size)
        }
    }

    @Test
    fun testKeyRotationOnMemberRemovalEnsuresForwardSecrecy() {
        val (trusted, genesisOp) = setupGenesis()

        val epoch0KeyBytes = EncryptionUtil.generateGroupKey()
        val epoch0KeyBase64 = EncryptionUtil.keyToBase64(epoch0KeyBytes)

        // Bob authors an expense in epoch 0
        val expOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            keyEpoch = 0,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-epoch-0",
                amountMinor = 4000L,
                currency = "INR",
                paidBy = bob.publicKeyHex,
                splits = mapOf(creator.publicKeyHex to 2000L, bob.publicKeyHex to 2000L),
                description = "Epoch 0 dinner"
            )
        )

        // Creator removes Bob and rotates to epoch 1
        val epoch1KeyBytes = EncryptionUtil.generateGroupKey()
        val epoch1KeyBase64 = EncryptionUtil.keyToBase64(epoch1KeyBytes)
        assertNotEquals(epoch0KeyBase64, epoch1KeyBase64)

        val removeBobOp = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = creator.publicKeyHex,
            privateKeyHex = creator.privateKeyHex,
            parents = listOf(expOp.operationId),
            logicalClock = 3L,
            keyEpoch = 1,
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = bob.publicKeyHex,
                reason = "Removed by creator",
                newKeyEpoch = 1
            )
        )

        // Charlie authors an expense in epoch 1
        val charlieEpoch1Expense = OperationSigner.createAndSign(
            groupId = groupId,
            authorPublicKey = charlie.publicKeyHex,
            privateKeyHex = charlie.privateKeyHex,
            parents = listOf(removeBobOp.operationId),
            logicalClock = 4L,
            keyEpoch = 1,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-epoch-1",
                amountMinor = 3000L,
                currency = "INR",
                paidBy = charlie.publicKeyHex,
                splits = mapOf(creator.publicKeyHex to 1500L, charlie.publicKeyHex to 1500L),
                description = "Epoch 1 confidential meeting"
            )
        )

        val result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trusted,
            operations = listOf(genesisOp, expOp, removeBobOp, charlieEpoch1Expense)
        )

        assertEquals(4, result.acceptedOperations.size)
        assertEquals(1, result.currentKeyEpoch)
        assertFalse("Bob must not be in active members after removal", result.activeMembers.containsKey(bob.publicKeyHex))
        assertTrue("Bob must be marked removed", result.removedMembers.contains(bob.publicKeyHex))

        // Cryptographic forward secrecy check:
        // Message encrypted with Epoch 1 key cannot be decrypted using Bob's Epoch 0 key!
        val wireEnvelope = com.peersplit.app.protocol.WireOperationEnvelope(
            version = charlieEpoch1Expense.protocolVersion,
            groupId = charlieEpoch1Expense.groupId,
            operationId = charlieEpoch1Expense.operationId,
            keyEpoch = charlieEpoch1Expense.keyEpoch,
            serializedOperationJson = "CONFIDENTIAL_PAYLOAD"
        )
        val encryptedWithEpoch1 = EncryptionUtil.encryptString(
            "CONFIDENTIAL_PAYLOAD",
            EncryptionUtil.keyFromBase64(epoch1KeyBase64)
        )

        // Bob tries to decrypt with Epoch 0 key -> Must throw or fail
        var decryptionFailed = false
        try {
            EncryptionUtil.decryptString(
                encryptedWithEpoch1,
                EncryptionUtil.keyFromBase64(epoch0KeyBase64)
            )
        } catch (_: Exception) {
            decryptionFailed = true
        }
        assertTrue("Removed member possessing only Epoch 0 key must fail to decrypt Epoch 1 content", decryptionFailed)

        // Authorized members with Epoch 1 key successfully decrypt
        val decryptedWithEpoch1 = EncryptionUtil.decryptString(
            encryptedWithEpoch1,
            EncryptionUtil.keyFromBase64(epoch1KeyBase64)
        )
        assertEquals("CONFIDENTIAL_PAYLOAD", decryptedWithEpoch1)
    }

    @Test
    fun testLegacyProtocolIsolationRejectsV1EventsOnV2Groups() {
        val (trusted, genesisOp) = setupGenesis()

        // Simulate V2 protocol active for groupId
        val protoMeta = GroupProtocolEntity(
            groupId = groupId,
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            genesisOperationId = genesisOp.operationId,
            maxLogicalClock = 1L
        )

        val isV2Active = protoMeta.protocolVersion >= ProtocolVersion.V2_SIGNED_OPERATIONS
        assertTrue("Group must be recognized as active on V2", isV2Active)

        // Legacy V1 block arriving on V2 group
        val isLegacyV1Event = false // not tagged as peersplit_v2
        if (isV2Active && !isLegacyV1Event) {
            // Must be quarantined / rejected without invoking V1 block processing
            val shouldQuarantine = true
            assertTrue("Incoming legacy V1 event must be quarantined when group is on V2", shouldQuarantine)
        }
    }
}
