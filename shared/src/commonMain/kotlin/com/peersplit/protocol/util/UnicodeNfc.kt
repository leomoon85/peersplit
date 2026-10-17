package com.peersplit.protocol.util

/**
 * Normalizes a string to Unicode NFC (Normalization Form Canonical Composition).
 */
expect fun normalizeNfc(input: String): String
