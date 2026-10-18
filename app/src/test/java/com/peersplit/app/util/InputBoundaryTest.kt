package com.peersplit.app.util

import org.junit.Assert.*
import org.junit.Test

class InputBoundaryTest {
    @Test fun importRetryReceiptStaysInHistoryWhenVisibleNoteIsEdited() {
        val marker = "[PeerSplit import:${"a".repeat(64)}:0]"
        assertEquals("Imported dinner", ImportNotes.display("Imported dinner $marker"))
        assertEquals("Corrected dinner $marker", ImportNotes.retainReceipt("Corrected dinner", "Imported dinner $marker"))
        assertEquals("Ordinary note", ImportNotes.retainReceipt("Ordinary note", ""))
    }
    @Test fun readsAndSkipsCannotBypassInputLimits() {
        val input = BoundedInput(ByteArray(101).inputStream(), 100)
        assertEquals(100L, input.skip(100))
        assertThrows(IllegalArgumentException::class.java) { input.read() }
        assertThrows(IllegalArgumentException::class.java) { BoundedInput(ByteArray(101).inputStream(), 100).readBytes() }
        assertEquals(100, BoundedInput(ByteArray(100).inputStream(), 100).readBytes().size)
    }
    @Test fun paymentAddressesMustBeCompleteAndClearingIsExplicit() {
        assertTrue(UpiAddress.isValid("person.name@okaxis"))
        assertEquals("", UpiAddress.requireValidOrEmpty("  "))
        listOf("person", "@bank", "person@", "person@@bank", "person @bank", "upi://pay").forEach {
            assertFalse(it, UpiAddress.isValid(it))
            assertThrows(IllegalArgumentException::class.java) { UpiAddress.requireValidOrEmpty(it) }
        }
    }
}
