package com.peersplit.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyUtilTest {

    @Test
    fun testIndianNumberFormatting() {
        assertEquals("₹1,50,000.00", CurrencyUtil.formatAmount(150000.0, "INR"))
        assertEquals("₹10,00,000.00", CurrencyUtil.formatAmount(1000000.0, "INR"))
        assertEquals("+₹1,50,000.00", CurrencyUtil.formatBalance(150000.0, "INR"))
        assertEquals("-₹500.50", CurrencyUtil.formatBalance(-500.5, "INR"))
    }

    @Test
    fun testStandardCurrencyFormatting() {
        assertEquals("$150,000.00", CurrencyUtil.formatAmount(150000.0, "USD"))
        assertEquals("€1,234.56", CurrencyUtil.formatAmount(1234.56, "EUR"))
        assertEquals("AED500.00", CurrencyUtil.formatAmount(500.0, "AED"))
    }
}
