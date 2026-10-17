package com.peersplit.protocol.crypto

/**
 * Common cryptographic abstraction for digital signatures and hashing.
 */
interface CryptoProvider {
    fun sha256(data: ByteArray): ByteArray
    fun sha256Hex(data: ByteArray): String
    fun signEd25519(digest: ByteArray, privateKeyHex: String): String
    fun verifyEd25519(digest: ByteArray, signatureHex: String, publicKeyHex: String): Boolean
    fun derivePublicKey(privateKeyHex: String): String
    fun generateKeyPair(): Pair<String, String>
}

expect object PlatformCrypto : CryptoProvider
