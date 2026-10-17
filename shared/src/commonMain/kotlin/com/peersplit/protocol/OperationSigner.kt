package com.peersplit.protocol

import com.peersplit.protocol.crypto.PlatformCrypto
import com.peersplit.protocol.crypto.PureSha256

/**
 * Cryptographic signer for PeerSplit V2 operations using Ed25519 and domain-separated digests.
 */
object OperationSigner {

    /**
     * Computes the 32-byte domain-separated signing digest:
     * SHA256("PeerSplit/V2/Signature" || 0x00 || operationIdBytes || canonicalUnsignedBytes)
     */
    fun computeSigningDigest(operationId: String, canonicalUnsignedBytes: ByteArray): ByteArray {
        val domainBytes = ProtocolVersion.DOMAIN_SIGNATURE.encodeToByteArray()
        val opIdBytes = PureSha256.fromHex(operationId)
        val combined = ByteArray(domainBytes.size + 1 + opIdBytes.size + canonicalUnsignedBytes.size)
        var offset = 0
        domainBytes.copyInto(combined, offset, 0, domainBytes.size)
        offset += domainBytes.size
        combined[offset++] = 0
        opIdBytes.copyInto(combined, offset, 0, opIdBytes.size)
        offset += opIdBytes.size
        canonicalUnsignedBytes.copyInto(combined, offset, 0, canonicalUnsignedBytes.size)
        return PureSha256.digest(combined)
    }

    /**
     * Canonically encodes, hashes, and signs a new [SignedOperation].
     */
    fun createAndSign(
        protocolVersion: Int = ProtocolVersion.V2_SIGNED_OPERATIONS,
        groupId: String,
        authorPublicKey: String,
        privateKeyHex: String,
        parents: List<String>,
        logicalClock: Long,
        payload: OperationPayload,
        keyEpoch: Int = 0,
        createdAtMillis: Long = 0L
    ): SignedOperation {
        val normalizedParents = parents.map { OperationCanonicalizer.normalizeString(it.trim().lowercase()) }.sorted()
        val normalizedGroupId = OperationCanonicalizer.normalizeString(groupId.trim())
        val normalizedAuthor = OperationCanonicalizer.normalizeString(authorPublicKey.trim().lowercase())

        val canonicalBytes = OperationCanonicalizer.canonicalizeUnsigned(
            protocolVersion = protocolVersion,
            groupId = normalizedGroupId,
            authorPublicKey = normalizedAuthor,
            parents = normalizedParents,
            logicalClock = logicalClock,
            keyEpoch = keyEpoch,
            payload = payload
        )

        val opId = OperationId.compute(canonicalBytes)
        val signingDigest = computeSigningDigest(opId, canonicalBytes)
        val signatureHex = PlatformCrypto.signEd25519(signingDigest, privateKeyHex)

        return SignedOperation(
            protocolVersion = protocolVersion,
            groupId = normalizedGroupId,
            authorPublicKey = normalizedAuthor,
            parents = normalizedParents,
            logicalClock = logicalClock,
            payload = payload,
            operationId = opId,
            signature = signatureHex,
            keyEpoch = keyEpoch,
            createdAtMillis = createdAtMillis
        )
    }

    /**
     * Signs a cryptographically authenticated invitation grant by an ADMIN/creator.
     */
    fun createInvitationGrant(
        inviterPublicKey: String,
        inviterPrivateKeyHex: String,
        invitedPublicKey: String = "*",
        groupId: String,
        targetRole: String = ProtocolMemberInfo.ROLE_MEMBER,
        invitationNonce: String,
        expiresAtMillis: Long = 0L
    ): MembershipInvitationGrant {
        val grantMsgBytes = OperationCanonicalizer.canonicalizeInvitationGrantMessage(
            inviterPublicKey = inviterPublicKey,
            invitedPublicKey = invitedPublicKey,
            groupId = groupId,
            targetRole = targetRole,
            invitationNonce = invitationNonce,
            expiresAtMillis = expiresAtMillis
        )
        val grantDigest = PureSha256.digest(grantMsgBytes)
        val sigHex = PlatformCrypto.signEd25519(grantDigest, inviterPrivateKeyHex)
        return MembershipInvitationGrant(
            inviterPublicKey = inviterPublicKey.trim().lowercase(),
            invitedPublicKey = invitedPublicKey.trim().lowercase(),
            groupId = groupId.trim(),
            targetRole = targetRole.trim().uppercase(),
            invitationNonce = invitationNonce.trim().lowercase(),
            expiresAtMillis = expiresAtMillis,
            adminSignature = sigHex
        )
    }
}
