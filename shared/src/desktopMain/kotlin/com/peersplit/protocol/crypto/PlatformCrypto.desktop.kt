package com.peersplit.protocol.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

actual object PlatformCrypto : CryptoProvider {

    private val secureRandom = SecureRandom()

    override fun sha256(data: ByteArray): ByteArray = PureSha256.digest(data)

    override fun sha256Hex(data: ByteArray): String = PureSha256.digestHex(data)

    override fun signEd25519(digest: ByteArray, privateKeyHex: String): String {
        val privBytes = PureSha256.fromHex(privateKeyHex)
        val signer = Ed25519Signer()
        val privParams = Ed25519PrivateKeyParameters(privBytes, 0)
        signer.init(true, privParams)
        signer.update(digest, 0, digest.size)
        val sigBytes = signer.generateSignature()
        return PureSha256.toHex(sigBytes)
    }

    override fun verifyEd25519(digest: ByteArray, signatureHex: String, publicKeyHex: String): Boolean {
        return try {
            val sigBytes = PureSha256.fromHex(signatureHex)
            val pubBytes = PureSha256.fromHex(publicKeyHex)
            val verifier = Ed25519Signer()
            val pubParams = Ed25519PublicKeyParameters(pubBytes, 0)
            verifier.init(false, pubParams)
            verifier.update(digest, 0, digest.size)
            verifier.verifySignature(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    override fun derivePublicKey(privateKeyHex: String): String {
        val privBytes = PureSha256.fromHex(privateKeyHex)
        val privParams = Ed25519PrivateKeyParameters(privBytes, 0)
        val pubParams = privParams.generatePublicKey()
        return PureSha256.toHex(pubParams.encoded)
    }

    override fun generateKeyPair(): Pair<String, String> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        return PureSha256.toHex(priv) to PureSha256.toHex(pub)
    }
}
