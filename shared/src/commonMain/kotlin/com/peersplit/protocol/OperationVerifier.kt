package com.peersplit.protocol

import com.peersplit.protocol.crypto.PlatformCrypto
import com.peersplit.protocol.crypto.PureSha256

/**
 * Independently verifies the content-derived `operationId` and Ed25519 `signature`
 * of a [SignedOperation].
 */
object OperationVerifier {

    private val verifiedCache = HashSet<String>()

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
            PlatformCrypto.verifyEd25519(signingDigest, operation.signature, operation.authorPublicKey)
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
            return hasValidOperationId(operation)
        }
        if (!hasValidOperationId(operation)) return false
        if (!hasValidSignature(operation)) return false
        if (verifiedCache.size > 10_000) verifiedCache.clear()
        verifiedCache.add(cacheKey)
        return true
    }

    /**
     * Verifies an invitation grant signature created by an admin.
     */
    fun verifyInvitationGrant(grant: MembershipInvitationGrant): Boolean {
        return try {
            if (!isValidHexKey(grant.inviterPublicKey, 64)) return false
            if (!isValidHexKey(grant.adminSignature, 128)) return false
            val grantMsgBytes = OperationCanonicalizer.canonicalizeInvitationGrantMessage(
                inviterPublicKey = grant.inviterPublicKey,
                invitedPublicKey = grant.invitedPublicKey,
                groupId = grant.groupId,
                targetRole = grant.targetRole,
                invitationNonce = grant.invitationNonce,
                expiresAtMillis = grant.expiresAtMillis
            )
            val grantDigest = PureSha256.digest(grantMsgBytes)
            PlatformCrypto.verifyEd25519(grantDigest, grant.adminSignature, grant.inviterPublicKey)
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidHexKey(hex: String?, expectedLength: Int): Boolean {
        if (hex == null || hex.length != expectedLength) return false
        for (ch in hex) {
            val isHex = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            if (!isHex) return false
        }
        return true
    }
}
