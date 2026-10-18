package com.peersplit.app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Provides X25519 Diffie-Hellman key agreement derived from standard Ed25519 identity keypairs.
 * Enables confidential peer-to-peer encryption for group key rotation and epoch distributions.
 */
object KeyExchangeUtil {

    // Prime for Curve25519 / Ed25519: 2^255 - 19
    private val P = BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")

    /**
     * Converts a 32-byte Ed25519 private key seed into a 32-byte X25519 private key scalar
     * according to RFC 8032 / RFC 7748.
     */
    fun ed25519PrivToX25519Priv(ed25519PrivBytes: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        val h = md.digest(ed25519PrivBytes.copyOfRange(0, 32))
        val k = h.copyOfRange(0, 32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    /**
     * Converts a 32-byte Ed25519 compressed public key point into a 32-byte X25519 Montgomery u-coordinate
     * according to RFC 7748 Section 4.1: u = (1 + y) / (1 - y) mod (2^255 - 19).
     */
    fun ed25519PubToX25519Pub(ed25519PubBytes: ByteArray): ByteArray {
        val clean = ed25519PubBytes.copyOf(32)
        // Clear sign bit of x (highest bit of byte 31 in little-endian compressed Ed25519 point)
        clean[31] = (clean[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, clean.reversedArray())
        val num = BigInteger.ONE.add(y).mod(P)
        val den = BigInteger.ONE.subtract(y).mod(P)
        val u = num.multiply(den.modInverse(P)).mod(P)
        val uBytes = u.toByteArray()
        val result = ByteArray(32)
        if (uBytes.size > 32) {
            System.arraycopy(uBytes, uBytes.size - 32, result, 0, 32)
        } else {
            System.arraycopy(uBytes, 0, result, 32 - uBytes.size, uBytes.size)
        }
        return result.reversedArray() // Little-endian for Curve25519/X25519
    }

    /**
     * Calculates a 32-byte symmetric AES key using X25519 ECDH between my private key and peer's public key.
     */
    fun calculateSharedSecret(myPrivHex: String, theirPubHex: String): ByteArray {
        val myPriv = HashUtil.fromHex(myPrivHex)
        val theirPub = HashUtil.fromHex(theirPubHex)
        val xPriv = ed25519PrivToX25519Priv(myPriv)
        val xPub = ed25519PubToX25519Pub(theirPub)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(xPriv, 0))
        val shared = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(xPub, 0), shared, 0)
        return HashUtil.sha256(shared)
    }

    /**
     * Confidentially encrypts a plaintext string for a specific recipient public key.
     */
    fun encryptForPeer(message: String, myPrivHex: String, theirPubHex: String): String {
        val sharedKey = calculateSharedSecret(myPrivHex, theirPubHex)
        return EncryptionUtil.encryptString(message, sharedKey)
    }

    /**
     * Decrypts ciphertext received from a specific sender public key.
     */
    fun decryptFromPeer(ciphertext: String, myPrivHex: String, theirPubHex: String): String {
        val sharedKey = calculateSharedSecret(myPrivHex, theirPubHex)
        return EncryptionUtil.decryptString(ciphertext, sharedKey)
    }
}
