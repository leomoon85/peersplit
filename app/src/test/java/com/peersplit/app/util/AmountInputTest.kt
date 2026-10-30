package com.peersplit.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {

    @Test
    fun testFilterAmountInput() {
        assertEquals("12", AmountInput.filterAmountInput("12", ""))
        assertEquals("12.5", AmountInput.filterAmountInput("12.5", "12"))
        assertEquals("12.50", AmountInput.filterAmountInput("12.50", "12.5"))
        // Disallow third decimal
        assertEquals("12.50", AmountInput.filterAmountInput("12.505", "12.50"))
        // Allow comma as decimal separator
        assertEquals("12,5", AmountInput.filterAmountInput("12,5", "12"))
        // Disallow multiple decimals
        assertEquals("12.5", AmountInput.filterAmountInput("12.5.5", "12.5"))
        // Disallow negative sign
        assertEquals("12", AmountInput.filterAmountInput("-12", "12"))
        // Allow empty
        assertEquals("", AmountInput.filterAmountInput("", "12"))
    }

    @Test
    fun testParseAmount() {
        assertEquals(12.5, AmountInput.parseAmount("12.5")!!, 0.001)
        assertEquals(12.5, AmountInput.parseAmount("12,5")!!, 0.001)
        assertEquals(150000.0, AmountInput.parseAmount("150000")!!, 0.001)
        assertNull(AmountInput.parseAmount(""))
        assertNull(AmountInput.parseAmount("abc"))
        assertNull(AmountInput.parseAmount("-10"))
    }
}
