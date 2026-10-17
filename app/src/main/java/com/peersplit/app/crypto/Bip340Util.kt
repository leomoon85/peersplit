package com.peersplit.app.crypto

import org.bouncycastle.asn1.sec.SECNamedCurves
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Implements BIP-340 Schnorr signatures over the secp256k1 elliptic curve.
 * This is the exact signature scheme mandated by the Nostr NIP-01 protocol specification,
 * enabling PeerSplit blocks to be broadcast, stored, and replayed by public Nostr relays.
 */
object Bip340Util {

    private val ecParams = SECNamedCurves.getByName("secp256k1")
    private val curve = ecParams.curve
    private val g = ecParams.g
    private val n = ecParams.n
    private val p = curve.field.characteristic

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val tagHash = md.digest(tag.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(tagHash)
        md.update(tagHash)
        md.update(msg)
        return md.digest()
    }

    private fun bigIntTo32Bytes(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        val result = ByteArray(32)
        if (raw.size > 32) {
            // Trim leading zero byte if present from sign bit
            System.arraycopy(raw, raw.size - 32, result, 0, 32)
        } else {
            // Left pad with zeros
            System.arraycopy(raw, 0, result, 32 - raw.size, raw.size)
        }
        return result
    }

    /**
     * Derives a 32-byte BIP-340 x-only public key from a 32-byte private key.
     */
    fun getPublicKey(privateKey: ByteArray): ByteArray {
        val d0 = BigInteger(1, privateKey).mod(n)
        val d = if (d0 == BigInteger.ZERO) BigInteger.ONE else d0
        val pPoint = g.multiply(d).normalize()
        return bigIntTo32Bytes(pPoint.affineXCoord.toBigInteger())
    }

    fun getPublicKeyHex(privateKeyHex: String): String {
        val privBytes = HashUtil.fromHex(privateKeyHex)
        return HashUtil.toHex(getPublicKey(privBytes))
    }

    /**
     * Signs a 32-byte message hash using BIP-340 Schnorr signature.
     * Returns a 64-byte signature (R.x || s).
     */
    fun sign(msg: ByteArray, privateKey: ByteArray): ByteArray {
        require(msg.size == 32) { "BIP-340 message must be 32 bytes" }
        require(privateKey.size == 32) { "Private key must be 32 bytes" }

        val d0 = BigInteger(1, privateKey).mod(n)
        var d = if (d0 == BigInteger.ZERO) BigInteger.ONE else d0

        var pPoint = g.multiply(d).normalize()
        if (pPoint.affineYCoord.toBigInteger().testBit(0)) {
            d = n.subtract(d)
            pPoint = pPoint.negate().normalize()
        }

        val dBytes = bigIntTo32Bytes(d)
        val pXBytes = bigIntTo32Bytes(pPoint.affineXCoord.toBigInteger())

        val k0Hash = taggedHash("BIP0340/nonce", dBytes + msg)
        var k0 = BigInteger(1, k0Hash).mod(n)
        if (k0 == BigInteger.ZERO) {
            k0 = BigInteger.ONE
        }

        var rPoint = g.multiply(k0).normalize()
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) {
            n.subtract(k0)
        } else {
            k0
        }

        val rXBytes = bigIntTo32Bytes(rPoint.affineXCoord.toBigInteger())
        val eHash = taggedHash("BIP0340/challenge", rXBytes + pXBytes + msg)
        val e = BigInteger(1, eHash).mod(n)

        val s = k.add(e.multiply(d)).mod(n)
        val sBytes = bigIntTo32Bytes(s)

        return rXBytes + sBytes
    }

    fun signHex(msgHashHex: String, privateKeyHex: String): String {
        val msg = HashUtil.fromHex(msgHashHex)
        val priv = HashUtil.fromHex(privateKeyHex)
        return HashUtil.toHex(sign(msg, priv))
    }

    /**
     * Verifies a 64-byte BIP-340 Schnorr signature against a 32-byte x-only public key.
     */
    fun verify(msg: ByteArray, sig: ByteArray, pubKey: ByteArray): Boolean {
        if (msg.size != 32 || sig.size != 64 || pubKey.size != 32) return false

        try {
            val rX = BigInteger(1, sig.copyOfRange(0, 32))
            val s = BigInteger(1, sig.copyOfRange(32, 64))
            val pX = BigInteger(1, pubKey)

            if (rX >= p || s >= n || pX >= p) return false

            // Lift x to point P on curve: y^2 = x^3 + 7 mod p
            val ySquared = pX.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
            val exp = p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)) // (p+1)/4
            var y = ySquared.modPow(exp, p)

            // Verify that y is indeed square root of ySquared
            if (y.modPow(BigInteger.valueOf(2), p) != ySquared) return false

            // Point P must have an even y coordinate in BIP-340
            if (y.testBit(0)) {
                y = p.subtract(y)
            }

            val pPoint = curve.createPoint(pX, y)

            val rXBytes = sig.copyOfRange(0, 32)
            val eHash = taggedHash("BIP0340/challenge", rXBytes + pubKey + msg)
            val e = BigInteger(1, eHash).mod(n)

            // R = s*G - e*P
            val sG = g.multiply(s)
            val eP = pPoint.multiply(e)
            val rPoint = sG.subtract(eP).normalize()

            if (rPoint.isInfinity) return false
            if (rPoint.affineYCoord.toBigInteger().testBit(0)) return false // y must be even
            if (rPoint.affineXCoord.toBigInteger() != rX) return false

            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun verifyHex(msgHashHex: String, sigHex: String, pubKeyHex: String): Boolean {
        return try {
            val msg = HashUtil.fromHex(msgHashHex)
            val sig = HashUtil.fromHex(sigHex)
            val pub = HashUtil.fromHex(pubKeyHex)
            verify(msg, sig, pub)
        } catch (e: Exception) {
            false
        }
    }
}
