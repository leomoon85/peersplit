package com.peersplit.app.crypto

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
}
