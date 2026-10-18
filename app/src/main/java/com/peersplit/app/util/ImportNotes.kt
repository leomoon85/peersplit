package com.peersplit.app.util

/** The receipt marker stays in signed history for safe retries, outside the visible note. */
object ImportNotes {
    private val receipt = Regex("\\[PeerSplit import:[a-f0-9]{64}(?::[0-9]+)?]")
    fun display(note: String) = receipt.replace(note, "").trim()
    fun retainReceipt(visibleNote: String, original: String): String =
        listOf(visibleNote.trim(), receipt.find(original)?.value.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
}
