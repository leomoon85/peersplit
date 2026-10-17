package com.peersplit.protocol

import com.peersplit.protocol.crypto.PureSha256

/**
 * Deterministic calculation and validation of content-addressed operation IDs.
 *
 * Operation ID:
 * `SHA-256("PeerSplit/V2/Operation" || 0x00 || canonicalUnsignedBytes)`
 */
object OperationId {

    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * Computes the 64-character lowercase hexadecimal SHA-256 operation ID from canonical unsigned bytes.
     */
    fun compute(canonicalUnsignedBytes: ByteArray): String {
        val domainBytes = ProtocolVersion.DOMAIN_OPERATION_ID.encodeToByteArray()
        val combined = ByteArray(domainBytes.size + 1 + canonicalUnsignedBytes.size)
        domainBytes.copyInto(combined, 0, 0, domainBytes.size)
        combined[domainBytes.size] = 0
        canonicalUnsignedBytes.copyInto(combined, domainBytes.size + 1, 0, canonicalUnsignedBytes.size)
        return PureSha256.digestHex(combined)
    }

    /**
     * Validates whether a string is a 64-character lowercase hex digest.
     */
    fun isValidFormat(operationId: String?): Boolean {
        if (operationId == null) return false
        return HEX_64_REGEX.matches(operationId.trim().lowercase())
    }
}
