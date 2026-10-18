package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.EncryptionUtil
import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyExchangeUtil
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.protocol.OperationEntityMapper
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import com.peersplit.app.protocol.SignedOperation
import com.peersplit.app.protocol.WireOperationEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBlockerHardeningTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val creatorKey = KeyManager.generateKeyPair()
    private val aliceKey = KeyManager.generateKeyPair() // To be removed
    private val bobKey = KeyManager.generateKeyPair()   // Remaining member
    private val groupId = "test-hardening-group"

    /**
     * R1 [P1]: Key Rotation Transition & Confidential Distribution:
     * - MemberRemoved wire envelope is encrypted with prior epoch key (Epoch 0) so all peers can decrypt the removal transition.
     * - New epoch key (Epoch 1) is distributed confidentially via X25519 ECDH to Bob (remaining).
     * - Alice (removed) cannot decrypt Epoch 1 key or any future Epoch 1 operations.
     */
    @Test
    fun testKeyRotationAndPostRemovalConfidentiality() {
        // 1. Initial shared epoch 0 key
        val epoch0KeyBytes = EncryptionUtil.generateGroupKey()
        val epoch0KeyBase64 = EncryptionUtil.keyToBase64(epoch0KeyBytes)

        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            keyEpoch = 0,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Epoch Rotation Group",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(creatorKey.publicKeyHex, "Creator", ProtocolMemberInfo.ROLE_ADMIN),
                    ProtocolMemberInfo(aliceKey.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_MEMBER),
                    ProtocolMemberInfo(bobKey.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER)
                )
            )
        )

        // 2. Creator removes Alice and rotates to Epoch 1
        val epoch1KeyBytes = EncryptionUtil.generateGroupKey()
        val epoch1KeyBase64 = EncryptionUtil.keyToBase64(epoch1KeyBytes)

        val removalOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            keyEpoch = 0, // Encrypted with epoch 0 key so existing peers (including Alice) receive the transition
            payload = OperationPayload.MemberRemoved(
                memberPublicKey = aliceKey.publicKeyHex,
                reason = "Removed by admin",
                newKeyEpoch = 1
            )
        )

        // Package removal wire envelope encrypted with Epoch 0 key
        val removalOpJson = OperationEntityMapper.json.encodeToString(SignedOperation.serializer(), removalOp)
        val removalWire = WireOperationEnvelope(
            version = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            operationId = removalOp.operationId,
            keyEpoch = 0,
            serializedOperationJson = removalOpJson
        )
        val removalEncryptedWithEpoch0 = EncryptionUtil.encryptString(
            json.encodeToString(WireOperationEnvelope.serializer(), removalWire),
            epoch0KeyBytes
        )

        // Both Alice and Bob can decrypt the removal transition using Epoch 0 key!
        val aliceDecryptedRemoval = EncryptionUtil.decryptString(removalEncryptedWithEpoch0, epoch0KeyBytes)
        val bobDecryptedRemoval = EncryptionUtil.decryptString(removalEncryptedWithEpoch0, epoch0KeyBytes)
        assertNotNull(aliceDecryptedRemoval)
        assertNotNull(bobDecryptedRemoval)

        // 3. Creator distributes Epoch 1 key confidentially to Bob ONLY
        val encryptedKeyForBob = KeyExchangeUtil.encryptForPeer(
            message = epoch1KeyBase64,
            myPrivHex = creatorKey.privateKeyHex,
            theirPubHex = bobKey.publicKeyHex
        )

        // Bob decrypts Epoch 1 key using his private key and creator's public key
        val bobReceivedKeyBase64 = KeyExchangeUtil.decryptFromPeer(
            ciphertext = encryptedKeyForBob,
            myPrivHex = bobKey.privateKeyHex,
            theirPubHex = creatorKey.publicKeyHex
        )
        assertEquals("Bob must recover the exact Epoch 1 key", epoch1KeyBase64, bobReceivedKeyBase64)

        // Alice cannot decrypt the key message intended for Bob
        var aliceDecryptionFailed = false
        try {
            KeyExchangeUtil.decryptFromPeer(
                ciphertext = encryptedKeyForBob,
                myPrivHex = aliceKey.privateKeyHex,
                theirPubHex = creatorKey.publicKeyHex
            )
        } catch (_: Exception) {
            aliceDecryptionFailed = true
        }
        assertTrue("Alice must NOT be able to decrypt the key sent to Bob", aliceDecryptionFailed)

        // 4. Creator writes an ordinary expense in Epoch 1 encrypted with Epoch 1 key
        val expenseOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(removalOp.operationId),
            logicalClock = 3L,
            keyEpoch = 1,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-post-removal",
                amountMinor = 50000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 25000L, bobKey.publicKeyHex to 25000L),
                description = "Post-Removal Secret Dinner"
            )
        )

        val expOpJson = OperationEntityMapper.json.encodeToString(SignedOperation.serializer(), expenseOp)
        val expWire = WireOperationEnvelope(
            version = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = groupId,
            operationId = expenseOp.operationId,
            keyEpoch = 1,
            serializedOperationJson = expOpJson
        )
        val expCiphertext = EncryptionUtil.encryptString(
            json.encodeToString(WireOperationEnvelope.serializer(), expWire),
            epoch1KeyBytes
        )

        // Bob can decrypt the Epoch 1 expense
        val bobKeyBytes = EncryptionUtil.keyFromBase64(bobReceivedKeyBase64)
        val bobDecryptedExp = EncryptionUtil.decryptString(expCiphertext, bobKeyBytes)
        assertNotNull(bobDecryptedExp)

        // Alice (having only Epoch 0 key) CANNOT decrypt the Epoch 1 expense
        var aliceExpDecryptionFailed = false
        try {
            EncryptionUtil.decryptString(expCiphertext, epoch0KeyBytes)
        } catch (_: Exception) {
            aliceExpDecryptionFailed = true
        }
        assertTrue("Removed Alice must NOT be able to decrypt future Epoch 1 ciphertext", aliceExpDecryptionFailed)

        // 5. Reconcile ledger: Alice is removed, Bob has balance -25000L, Creator has balance +25000L
        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Epoch Rotation Group",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )
        val recon = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, removalOp, expenseOp)
        )
        assertTrue("Genesis must be accepted", genesisOp.operationId in recon.acceptedOperations.map { it.operationId })
        assertTrue("Removal must be accepted", removalOp.operationId in recon.acceptedOperations.map { it.operationId })
        assertTrue("Expense must be accepted", expenseOp.operationId in recon.acceptedOperations.map { it.operationId })
        assertFalse("Alice must not be active", recon.activeMembers.containsKey(aliceKey.publicKeyHex))
        assertTrue("Bob must be active", recon.activeMembers.containsKey(bobKey.publicKeyHex))
        assertEquals(1, recon.currentKeyEpoch)
        assertEquals(25000L, recon.memberBalancesMinor[creatorKey.publicKeyHex])
        assertEquals(-25000L, recon.memberBalancesMinor[bobKey.publicKeyHex])
    }
}
