package com.peersplit.app.protocol

import com.peersplit.app.crypto.HashUtil
import com.peersplit.app.crypto.KeyManager
import java.io.ByteArrayOutputStream

/**
 * Cryptographic signer for PeerSplit V2 operations using Ed25519 and domain-separated digests.
 */
object OperationSigner {

    /**
     * Computes the 32-byte domain-separated signing digest:
     * SHA256("PeerSplit/V2/Signature" || 0x00 || operationIdBytes || canonicalUnsignedBytes)
     */
    fun computeSigningDigest(operationId: String, canonicalUnsignedBytes: ByteArray): ByteArray {
        val domainBytes = ProtocolVersion.DOMAIN_SIGNATURE.toByteArray(Charsets.UTF_8)
        val opIdBytes = HashUtil.fromHex(operationId)
        val bos = ByteArrayOutputStream(domainBytes.size + 1 + opIdBytes.size + canonicalUnsignedBytes.size)
        bos.write(domainBytes)
        bos.write(0)
        bos.write(opIdBytes)
        bos.write(canonicalUnsignedBytes)
        return HashUtil.sha256(bos.toByteArray())
    }

    /**
     * Canonically encodes, hashes, and signs a new [SignedOperation].
     */
    fun createAndSign(
        protocolVersion: Int = ProtocolVersion.CURRENT,
        groupId: String,
        authorPublicKey: String,
        privateKeyHex: String,
        parents: List<String>,
        logicalClock: Long,
        payload: OperationPayload,
        keyEpoch: Int = 0,
        createdAtMillis: Long = System.currentTimeMillis()
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
            payload = payload,
            createdAtMillis = createdAtMillis
        )


        val opId = OperationId.compute(canonicalBytes)
        val signingDigest = computeSigningDigest(opId, canonicalBytes)
        val signatureHex = KeyManager.signHex(signingDigest, privateKeyHex)

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
     * Creates and cryptographically signs a [MembershipInvitationGrant] with an admin's Ed25519 private key.
     */
    fun createInvitationGrant(
        inviterPublicKey: String,
        inviterPrivateKeyHex: String,
        groupId: String,
        invitedPublicKey: String = "*",
        targetRole: String = ProtocolMemberInfo.ROLE_MEMBER,
        invitationNonce: String = java.util.UUID.randomUUID().toString(),
        expiresAtMillis: Long = 0L
    ): MembershipInvitationGrant {
        val message = OperationCanonicalizer.canonicalizeInvitationGrantMessage(
            inviterPublicKey = inviterPublicKey,
            invitedPublicKey = invitedPublicKey,
            groupId = groupId,
            targetRole = targetRole,
            invitationNonce = invitationNonce,
            expiresAtMillis = expiresAtMillis
        )
        val digest = HashUtil.sha256(message)
        val signature = KeyManager.signHex(digest, inviterPrivateKeyHex)
        return MembershipInvitationGrant(
            inviterPublicKey = OperationCanonicalizer.normalizeString(inviterPublicKey.trim().lowercase()),
            invitedPublicKey = OperationCanonicalizer.normalizeString(invitedPublicKey.trim().lowercase()),
            groupId = OperationCanonicalizer.normalizeString(groupId.trim()),
            targetRole = OperationCanonicalizer.normalizeString(targetRole.trim().uppercase()),
            invitationNonce = OperationCanonicalizer.normalizeString(invitationNonce.trim().lowercase()),
            expiresAtMillis = expiresAtMillis,
            adminSignature = signature
        )
    }
}
