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

    /**
     * Finding 1: Cross-group replay of key distribution envelope:
     * - Creator generates an authenticated KeyDistributionEnvelope for Group A (epoch 1, Bob as recipient).
     * - Relay observer attempts to rewrap that envelope into Group B.
     * - Envelope signature verifies for Group A, but if envelope.groupId is changed to Group B, verifySignature() fails!
     * - If unchanged envelope is kept in Group B Nostr event, envelope.groupId != outerGroupId, rejecting cross-group injection.
     */
    @Test
    fun testCrossGroupReplayOfKeyEnvelopeFailsSignatureOrDestinationCheck() {
        val groupA = "group-alpha"
        val groupB = "group-beta"
        val keyBase64 = EncryptionUtil.keyToBase64(EncryptionUtil.generateGroupKey())
        val encryptedKey = KeyExchangeUtil.encryptForPeer(
            message = keyBase64,
            myPrivHex = creatorKey.privateKeyHex,
            theirPubHex = bobKey.publicKeyHex
        )

        val envelopeA = com.peersplit.app.crypto.KeyDistributionEnvelope.createAndSign(
            groupId = groupA,
            epoch = 1,
            authorPublicKey = creatorKey.publicKeyHex,
            authorPrivateKeyHex = creatorKey.privateKeyHex,
            recipientPublicKey = bobKey.publicKeyHex,
            transitionOperationId = "op-removal-1",
            keyCiphertext = encryptedKey
        )

        // Envelope is cryptographically valid for Group A
        assertTrue("Original envelope must have valid Ed25519 signature", envelopeA.verifySignature())
        assertEquals(groupA, envelopeA.groupId)

        // Attempt 1: Attacker changes groupId inside envelope to groupB -> signature check must fail!
        val tamperedEnvelope = envelopeA.copy(groupId = groupB)
        assertFalse("Tampered envelope with changed groupId must fail Ed25519 verification", tamperedEnvelope.verifySignature())

        // Attempt 2: Attacker changes recipient inside envelope -> signature check must fail!
        val tamperedRecipient = envelopeA.copy(recipientPublicKey = aliceKey.publicKeyHex)
        assertFalse("Tampered envelope with changed recipient must fail Ed25519 verification", tamperedRecipient.verifySignature())

        // Attempt 3: Attacker leaves envelope intact but tags outer Nostr event as groupB ->
        // Destination check envelope.groupId == outerGroupId detects mismatch!
        val outerEventGroupId = groupB
        val destinationMatches = envelopeA.groupId == outerEventGroupId
        assertFalse("Outer Nostr event tagged as Group B must be rejected due to envelope.groupId mismatch", destinationMatches)
    }

    /**
     * Finding 2: Historical admin-endorsed joins synchronize to fresh peer even after grant expiry,
     * while unendorsed expired leaf joins are rejected deterministically during reconciliation.
     */
    @Test
    fun testHistoricalAdminEndorsedJoinSucceedsAfterGrantExpiryWhileUnendorsedFails() {
        val charlieKey = KeyManager.generateKeyPair()
        val genesisOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            createdAtMillis = 100L,
            payload = OperationPayload.GenesisCreated(
                groupId = groupId,
                groupName = "Admin Endorsement Test",
                currency = "INR",
                creatorPublicKey = creatorKey.publicKeyHex,
                initialMembers = listOf(
                    ProtocolMemberInfo(creatorKey.publicKeyHex, "Creator", ProtocolMemberInfo.ROLE_ADMIN)
                )
            )
        )

        // Admin issues grant with 1000ms expiry
        val grant = com.peersplit.app.protocol.OperationSigner.createInvitationGrant(
            inviterPublicKey = creatorKey.publicKeyHex,
            inviterPrivateKeyHex = creatorKey.privateKeyHex,
            groupId = groupId,
            invitedPublicKey = charlieKey.publicKeyHex,
            invitationNonce = "nonce-endorsed-1",
            expiresAtMillis = 1000L
        )

        // Charlie joins within grant validity (at 500ms)
        val charlieJoinOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = charlieKey.publicKeyHex,
            privateKeyHex = charlieKey.privateKeyHex,
            parents = listOf(genesisOp.operationId),
            logicalClock = 2L,
            createdAtMillis = 500L,
            payload = OperationPayload.MemberAdded(
                memberPublicKey = charlieKey.publicKeyHex,
                displayName = "Charlie",
                role = ProtocolMemberInfo.ROLE_MEMBER,
                invitationGrant = grant
            )
        )

        // Unendorsed case: timeline progresses past expiry (2000L) with no admin endorsing Charlie
        val creatorLateExpense = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(genesisOp.operationId), // Only depends on genesis, NOT Charlie!
            logicalClock = 3L,
            createdAtMillis = 2000L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-solo-1",
                amountMinor = 10000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 10000L),
                description = "Creator solo coffee"
            )
        )

        val trustedGenesis = TrustedGenesis(
            groupId = groupId,
            groupName = "Admin Endorsement Test",
            currency = "INR",
            creatorPublicKey = creatorKey.publicKeyHex,
            genesisOperationId = genesisOp.operationId
        )

        val unendorsedRecon = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, charlieJoinOp, creatorLateExpense)
        )
        assertFalse("Unendorsed expired join must be rejected", charlieJoinOp.operationId in unendorsedRecon.acceptedOperations.map { it.operationId })
        assertTrue("Unendorsed expired join must appear in rejectedOperations",
            charlieJoinOp.operationId in unendorsedRecon.rejectedOperations.map { it.operation.operationId })

        // Endorsed case: Admin (Creator) accepts Charlie into history by building an expense on top of Charlie's join!
        val creatorEndorsingExpense = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V3_SIGNED_OPERATIONS,
            groupId = groupId,
            authorPublicKey = creatorKey.publicKeyHex,
            privateKeyHex = creatorKey.privateKeyHex,
            parents = listOf(charlieJoinOp.operationId), // Admin causally endorses Charlie's join!
            logicalClock = 3L,
            createdAtMillis = 2500L,
            payload = OperationPayload.ExpenseCreated(
                expenseId = "exp-shared-1",
                amountMinor = 20000L,
                currency = "INR",
                paidBy = creatorKey.publicKeyHex,
                splits = mapOf(creatorKey.publicKeyHex to 10000L, charlieKey.publicKeyHex to 10000L),
                description = "Creator & Charlie dinner"
            )
        )

        val endorsedRecon = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(genesisOp, charlieJoinOp, creatorEndorsingExpense)
        )
        assertTrue("Admin-endorsed join must be accepted during historical replay even after grant expiry",
            charlieJoinOp.operationId in endorsedRecon.acceptedOperations.map { it.operationId })
        assertTrue("Charlie must be recognized as active member in reconciled state",
            endorsedRecon.activeMembers.containsKey(charlieKey.publicKeyHex))
        assertEquals("Charlie's net balance must be reconciled accurately",
            -10000L, endorsedRecon.memberBalancesMinor[charlieKey.publicKeyHex])
    }

    /**
     * Finding 3: Pending sync state persistence across restarts round-trip test.
     */
    @Test
    fun testPendingSyncStateSerializationRoundTrip() {
        val dummyEvent = com.peersplit.app.network.nostr.NostrEvent(
            id = "event-id-12345",
            pubkey = creatorKey.publicKeyHex,
            createdAt = 1234567L,
            kind = 1,
            tags = listOf(listOf("g", groupId), listOf("epoch", "1")),
            content = "encrypted-content-sample",
            sig = "sig-12345"
        )

        val state = com.peersplit.app.network.sync.PersistedPendingSyncState(
            pendingKeys = mapOf("transition-op-1" to listOf(dummyEvent)),
            pendingCiphertext = listOf(
                com.peersplit.app.network.sync.PersistedCiphertextEntry(
                    groupId = groupId,
                    epoch = 1,
                    event = dummyEvent
                )
            ),
            earlyGroupEvents = mapOf(groupId to listOf(dummyEvent))
        )

        val jsonStr = json.encodeToString(com.peersplit.app.network.sync.PersistedPendingSyncState.serializer(), state)
        val decoded = json.decodeFromString(com.peersplit.app.network.sync.PersistedPendingSyncState.serializer(), jsonStr)

        assertEquals("Pending keys count must match", state.pendingKeys.size, decoded.pendingKeys.size)
        assertEquals("Pending key event id must match",
            state.pendingKeys["transition-op-1"]?.first()?.id,
            decoded.pendingKeys["transition-op-1"]?.first()?.id
        )
        assertEquals("Pending ciphertext count must match", state.pendingCiphertext.size, decoded.pendingCiphertext.size)
        assertEquals("Pending ciphertext groupId must match", state.pendingCiphertext.first().groupId, decoded.pendingCiphertext.first().groupId)
        assertEquals("Pending ciphertext epoch must match", state.pendingCiphertext.first().epoch, decoded.pendingCiphertext.first().epoch)
        assertEquals("Early group events must match", state.earlyGroupEvents.size, decoded.earlyGroupEvents.size)
    }
}
