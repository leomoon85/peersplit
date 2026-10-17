package com.peersplit.app.protocol

import com.peersplit.app.crypto.HashUtil
import java.io.ByteArrayOutputStream

/**
 * Utilities for computing and validating content-addressed V2 Operation IDs.
 * Format: lowercase 64-character hex string representing SHA-256("PeerSplit/V2/Operation" || canonicalUnsignedBytes).
 */
object OperationId {

    private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")

    fun isValidFormat(id: String): Boolean {
        return id.length == 64 && HEX_64_REGEX.matches(id)
    }

    /**
     * Computes the deterministic, domain-separated operation ID from the canonical unsigned operation bytes.
     */
    fun compute(canonicalUnsignedBytes: ByteArray): String {
        val domainPrefix = ProtocolVersion.DOMAIN_OPERATION_ID.toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(domainPrefix.size + 1 + canonicalUnsignedBytes.size)
        bos.write(domainPrefix)
        bos.write(0) // Null-byte separator between domain tag and payload
        bos.write(canonicalUnsignedBytes)
        return HashUtil.sha256Hex(bos.toByteArray())
    }
}
