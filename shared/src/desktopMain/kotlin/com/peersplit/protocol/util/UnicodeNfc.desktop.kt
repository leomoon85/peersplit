package com.peersplit.protocol.util

import java.text.Normalizer

actual fun normalizeNfc(input: String): String {
    return Normalizer.normalize(input, Normalizer.Form.NFC)
}
