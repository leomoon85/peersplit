package com.peersplit.app.crypto

import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.DebtSettlementEngine
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.blockchain.ValidationResult
import com.peersplit.app.network.nostr.NostrEvent
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class Bip340UtilTest {

    @Test
    fun testBip340SignAndVerify() {
        val random = SecureRandom()
        val privKey = ByteArray(32)
        random.nextBytes(privKey)

        val pubKey = Bip340Util.getPublicKey(privKey)
        assertNotNull(pubKey)
        assertEquals(32, pubKey.size)

        val messageHash = HashUtil.sha256("Nostr Event ID Test 1234")
        val sig = Bip340Util.sign(messageHash, privKey)
        assertNotNull(sig)
        assertEquals(64, sig.size)

        // Must verify
        val isValid = Bip340Util.verify(messageHash, sig, pubKey)
        assertTrue("BIP-340 Schnorr signature must verify", isValid)

        // Tampered message must fail
        val tamperedMsg = HashUtil.sha256("Tampered Message")
        val isTamperedValid = Bip340Util.verify(tamperedMsg, sig, pubKey)
        assertFalse("Tampered message signature must fail", isTamperedValid)
    }

    @Test
    fun testBip340HexMethods() {
        val privHex = "0000000000000000000000000000000000000000000000000000000000000003"
        val pubHex = Bip340Util.getPublicKeyHex(privHex)
        assertEquals(64, pubHex.length)

        val msgHex = HashUtil.sha256Hex("PeerSplit Ledger Block #0")
        val sigHex = Bip340Util.signHex(msgHex, privHex)
        assertEquals(128, sigHex.length) // 64 bytes = 128 hex chars

        val isValid = Bip340Util.verifyHex(msgHex, sigHex, pubHex)
        assertTrue("Hex verification must succeed", isValid)
    }

    @Test
    fun testTwoPhoneGhostClaimAndBidirectionalExpenseChain() {
        // Phone 1 identity
        val phone1Keys = KeyManager.generateKeyPair()
        // Phone 2 identity
        val phone2Keys = KeyManager.generateKeyPair()

        // 1. Phone 1 creates group (#0)
        val (groupId, genesis) = BlockchainEngine.createGenesisBlock(
            groupName = "Goa Trip",
            currency = "INR",
            creatorPubkey = phone1Keys.publicKeyHex,
            creatorDisplayName = "Phone 1",
            privateKeyHex = phone1Keys.privateKeyHex
        )

        // 2. Phone 1 adds 2nd member ("Friend") while creating group (#1)
        val ghostPubkey = HashUtil.sha256Hex("ghost:$groupId:friend")
        val ghostJoin = BlockchainEngine.createMemberJoinBlock(
            groupId = groupId,
            prevBlock = genesis,
            newMemberPubkey = ghostPubkey,
            newMemberName = "Friend",
            authorPubkey = phone1Keys.publicKeyHex,
            authorPrivateKeyHex = phone1Keys.privateKeyHex
        )

        // 3. Phone 1 adds ₹500 expense split equally with Friend (#2)
        val exp1 = BlockchainEngine.createExpenseBlock(
            groupId = groupId,
            prevBlock = ghostJoin,
            description = "Dinner",
            totalAmount = 500.0,
            currency = "INR",
            payerPubkey = phone1Keys.publicKeyHex,
            splits = listOf(
                SplitItem(phone1Keys.publicKeyHex, 250.0),
                SplitItem(ghostPubkey, 250.0)
            ),
            category = "FOOD",
            note = "",
            authorPubkey = phone1Keys.publicKeyHex,
            authorPrivateKeyHex = phone1Keys.privateKeyHex
        )

        // 4. Phone 2 joins and claims ghostPubkey (#3)
        val phone2Join = BlockchainEngine.createMemberJoinBlock(
            groupId = groupId,
            prevBlock = exp1,
            newMemberPubkey = phone2Keys.publicKeyHex,
            newMemberName = "Friend",
            authorPubkey = phone2Keys.publicKeyHex,
            authorPrivateKeyHex = phone2Keys.privateKeyHex,
            claimedGhostPubkey = ghostPubkey
        )

        // 5. Phone 2 adds ₹300 expense paid by Phone 2 (#4)
        val exp2 = BlockchainEngine.createExpenseBlock(
            groupId = groupId,
            prevBlock = phone2Join,
            description = "Cab",
            totalAmount = 300.0,
            currency = "INR",
            payerPubkey = phone2Keys.publicKeyHex,
            splits = listOf(
                SplitItem(phone1Keys.publicKeyHex, 150.0),
                SplitItem(phone2Keys.publicKeyHex, 150.0)
            ),
            category = "TRANSPORT",
            note = "",
            authorPubkey = phone2Keys.publicKeyHex,
            authorPrivateKeyHex = phone2Keys.privateKeyHex
        )

        val chain: List<Block> = listOf(genesis, ghostJoin, exp1, phone2Join, exp2)
        val validation = BlockchainEngine.validateChain(chain)
        assertTrue("5-block 2-phone chain must be 100% valid", validation is ValidationResult.Valid)

        // Verify aliasMap maps ghostPubkey -> phone2Keys.publicKeyHex so net balances merge
        val aliasMap = mapOf(ghostPubkey to phone2Keys.publicKeyHex)
        val exp1Payload = BlockchainEngine.json.decodeFromString(com.peersplit.app.blockchain.ExpensePayload.serializer(), exp1.payloadJson)
        val exp2Payload = BlockchainEngine.json.decodeFromString(com.peersplit.app.blockchain.ExpensePayload.serializer(), exp2.payloadJson)
        val balances = DebtSettlementEngine.calculateNetBalances(
            expenses = listOf(exp1Payload, exp2Payload),
            settlements = emptyList(),
            voidedExpenseIds = emptySet(),
            pubkeyAliases = aliasMap
        )

        // Phone 1 paid 500 (lent 250) and borrowed 150 -> net +100
        // Phone 2 borrowed 250 and paid 300 (lent 150) -> net -100
        assertEquals(100.0, balances[phone1Keys.publicKeyHex] ?: 0.0, 0.01)
        assertEquals(-100.0, balances[phone2Keys.publicKeyHex] ?: 0.0, 0.01)
    }

    @Test
    fun testNostrEventSelfVerification() {
        val privHex = "0000000000000000000000000000000000000000000000000000000000000007"
        val pubHex = Bip340Util.getPublicKeyHex(privHex)
        val groupId = "grp_test_123"
        val tags = listOf(
            listOf("t", "peersplit_$groupId"),
            listOf("g", groupId),
            listOf("idx", "0")
        )
        val event = NostrEvent.createAndSign(
            pubkey = pubHex,
            privateKeyHex = privHex,
            tags = tags,
            content = "encrypted_test_payload"
        )
        assertTrue("NostrEvent must self-verify", event.verify())
    }
}
