package com.peersplit.app.protocol

/**
 * Protocol versioning, cryptographic domain-separation constants, and strict validation limits
 * for the PeerSplit V2 Immutable Signed-Operation Protocol.
 */
object ProtocolVersion {
    const val V1_LEGACY_BLOCKCHAIN = 1
    const val V2_SIGNED_OPERATIONS = 2
    const val CURRENT = V2_SIGNED_OPERATIONS

    val SUPPORTED_VERSIONS = setOf(V2_SIGNED_OPERATIONS)

    // Cryptographic Domain Separation Tags
    const val DOMAIN_OPERATION_ID = "PeerSplit/V2/Operation"
    const val DOMAIN_SIGNATURE = "PeerSplit/V2/Signature"
    const val DOMAIN_STATE_DIGEST = "PeerSplit/V2/StateDigest"
    const val DOMAIN_KEY_EPOCH = "PeerSplit/V2/KeyEpoch"

    // Structural & Security Limits
    const val MAX_PARENTS = 32
    const val MAX_MEMBERS_PER_OP = 128
    const val MAX_SPLITS_PER_EXPENSE = 128
    const val MAX_PAYERS_PER_EXPENSE = 64
    const val MAX_STRING_LENGTH = 512
    const val MAX_NOTE_LENGTH = 2048
    const val MAX_ID_LENGTH = 128
    const val MAX_CURRENCY_LENGTH = 8
    const val MAX_WIRE_MESSAGE_BYTES = 262_144 // 256 KB max encrypted operation payload

    // Financial Limits (in integer minor units, e.g. paise/cents)
    // Max single transaction: 100 billion minor units (e.g., ₹1,000,000,000.00) to prevent overflow on aggregation
    const val MAX_AMOUNT_MINOR = 100_000_000_000L

    val SUPPORTED_CURRENCIES = setOf(
        "INR", "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CNY", "CHF", "SGD", "AED", "BRL", "KRW", "RUB"
    )

    fun isSupported(version: Int): Boolean = version in SUPPORTED_VERSIONS
}
