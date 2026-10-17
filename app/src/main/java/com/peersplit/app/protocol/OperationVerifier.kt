package com.peersplit.app.protocol

import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Independently verifies the content-derived `operationId` and Ed25519 `signature`
 * of a [SignedOperation].
 */
object OperationVerifier {

    private val verifiedCache = ConcurrentHashMap.newKeySet<String>()

    /**
     * Verifies that `operation.operationId` matches the SHA-256 of the canonical unsigned bytes.
     */
    fun hasValidOperationId(operation: SignedOperation): Boolean {
        return try {
            if (!OperationId.isValidFormat(operation.operationId)) return false
            val canonicalBytes = OperationCanonicalizer.canonicalizeUnsigned(operation)
            val expectedId = OperationId.compute(canonicalBytes)
            expectedId == operation.operationId
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifies the author's Ed25519 signature over the domain-separated signing digest.
     */
    fun hasValidSignature(operation: SignedOperation): Boolean {
        return try {
            if (!isValidHexKey(operation.authorPublicKey, 64)) return false
            if (!isValidHexKey(operation.signature, 128)) return false
            val canonicalBytes = OperationCanonicalizer.canonicalizeUnsigned(operation)
            val expectedId = OperationId.compute(canonicalBytes)
            if (expectedId != operation.operationId) return false

            val signingDigest = OperationSigner.computeSigningDigest(operation.operationId, canonicalBytes)
            KeyManager.verifyHex(signingDigest, operation.signature, operation.authorPublicKey)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Full cryptographic and canonical verification of an operation envelope.
     */
    fun verify(operation: SignedOperation): Boolean {
        val cacheKey = "${operation.operationId}:${operation.authorPublicKey}:${operation.signature}"
        if (verifiedCache.contains(cacheKey)) {
            // Still verify canonical bytes match operationId in case fields were mutated in-memory
            return hasValidOperationId(operation)
        }
        if (!hasValidOperationId(operation)) return false
        if (!hasValidSignature(operation)) return false
        if (verifiedCache.size > 10_000) verifiedCache.clear()
        verifiedCache.add(cacheKey)
        return true
    }

    private fun isValidHexKey(hex: String, expectedLength: Int): Boolean {
        if (hex.length != expectedLength) return false
        return try {
            HashUtil.fromHex(hex)
            true
        } catch (_: Exception) {
            false
        }
    }
}
