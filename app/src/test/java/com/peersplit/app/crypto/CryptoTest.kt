package com.peersplit.app.crypto

import org.junit.Assert.*
import org.junit.Test

class CryptoTest {

    @Test
    fun testSha256Hashing() {
        val input = "PeerSplit: Serverless P2P Expense Sharing"
        val hashHex = HashUtil.sha256Hex(input)
        assertNotNull(hashHex)
        assertEquals(64, hashHex.length) // 256 bits = 64 hex characters
    }

    @Test
    fun testHexConversion() {
        val originalBytes = byteArrayOf(0x00, 0x0F, 0x1A, 0xFF.toByte())
        val hex = HashUtil.toHex(originalBytes)
        assertEquals("000f1aff", hex)

        val restoredBytes = HashUtil.fromHex(hex)
        assertArrayEquals(originalBytes, restoredBytes)
    }

    @Test
    fun testEd25519KeyGenAndSigning() {
        val keyPair = KeyManager.generateKeyPair()
        assertNotNull(keyPair.privateKey)
        assertNotNull(keyPair.publicKey)
        assertEquals(32, keyPair.privateKey.size)
        assertEquals(32, keyPair.publicKey.size)

        val message = "Expense Block #12: Dinner $45.00".toByteArray(Charsets.UTF_8)
        val signature = KeyManager.sign(message, keyPair.privateKey)
        assertNotNull(signature)
        assertEquals(64, signature.size)

        // Signature should verify with public key
        val isValid = KeyManager.verify(message, signature, keyPair.publicKey)
        assertTrue("Signature must be valid", isValid)

        // Tampered message should fail verification
        val tamperedMessage = "Expense Block #12: Dinner $95.00".toByteArray(Charsets.UTF_8)
        val isTamperedValid = KeyManager.verify(tamperedMessage, signature, keyPair.publicKey)
        assertFalse("Tampered message signature must fail", isTamperedValid)
    }

    @Test
    fun testAesGcmKeyGeneration() {
        val key = EncryptionUtil.generateGroupKey()
        assertNotNull(key)
        assertEquals(32, key.size) // 256 bits
    }
}
