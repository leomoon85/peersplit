package com.peersplit.app.util

import org.junit.Assert.*
import org.junit.Test

class PhoneNumbersTest {
    @Test fun preservesUaeCountryCodeFromContacts() {
        assertEquals("971501234567", PhoneNumbers.normalize("+971 50 123 4567"))
        assertEquals("971501234567", PhoneNumbers.normalize("00971-50-123-4567"))
    }
    @Test fun neverMatchesCountriesByTheirLastTenDigits() {
        assertNotEquals(PhoneNumbers.normalize("+44 7911 123456"), PhoneNumbers.normalize("+91 7911 123456"))
        assertEquals("9876543210", PhoneNumbers.normalize("98765 43210"))
        assertEquals("919876543210", PhoneNumbers.normalize("+91 98765 43210"))
    }
    @Test fun handlesEmptyUnicodeDigitsAndContactExtensions() {
        assertEquals("", PhoneNumbers.normalize(null))
        assertEquals("", PhoneNumbers.normalize(" "))
        assertEquals("971501234567", PhoneNumbers.normalize("+971 50 123 4567 ext. 42"))
        assertEquals("971501234567", PhoneNumbers.normalize("+971 50 123 4567;42"))
        assertEquals("971", PhoneNumbers.normalize("٩٧١"))
    }
    @Test fun upiSuggestionNeverUsesForeignCountryCodeOrTruncatedSuffix() {
        assertNull(PhoneNumbers.indianMobile("+971 50 123 4567"))
        assertNull(PhoneNumbers.indianMobile("+44 7911 123456"))
        assertEquals("9876543210", PhoneNumbers.indianMobile("+91 98765 43210"))
    }
    @Test fun whatsappUsesFullInternationalNumberWithoutAssumingIndia() {
        assertEquals("971501234567", PhoneNumbers.whatsappNumber("+971 50 123 4567"))
        assertEquals("919876543210", PhoneNumbers.whatsappNumber("+91 98765 43210"))
        assertNull(PhoneNumbers.whatsappNumber("9876543210"))
    }
    @Test fun matchingRejectsEmptyShortAndOverlongNumbers() {
        assertFalse(PhoneNumbers.canMatch(""))
        assertFalse(PhoneNumbers.canMatch("123"))
        assertFalse(PhoneNumbers.canMatch("1".repeat(16)))
        assertTrue(PhoneNumbers.canMatch("971501234567"))
    }
}
