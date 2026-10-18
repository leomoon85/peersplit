package com.peersplit.app.util

import com.peersplit.app.protocol.ProtocolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountInputValidatorTest {

    @Test
    fun testValidAmounts() {
        val r1 = AmountInputValidator.validate("100")
        assertTrue(r1 is AmountValidationResult.Valid)
        assertEquals(10000L, (r1 as AmountValidationResult.Valid).amountMinor)
        assertEquals(100.0, r1.majorAmount, 0.0001)

        val r2 = AmountInputValidator.validate("12.50")
        assertTrue(r2 is AmountValidationResult.Valid)
        assertEquals(1250L, (r2 as AmountValidationResult.Valid).amountMinor)

        val r3 = AmountInputValidator.validate("0.01")
        assertTrue(r3 is AmountValidationResult.Valid)
        assertEquals(1L, (r3 as AmountValidationResult.Valid).amountMinor)

        val r4 = AmountInputValidator.validate("12,50")
        assertTrue(r4 is AmountValidationResult.Valid)
        assertEquals(1250L, (r4 as AmountValidationResult.Valid).amountMinor)
    }

    @Test
    fun testIncompleteInputs() {
        assertTrue(AmountInputValidator.validate("") is AmountValidationResult.Incomplete)
        assertTrue(AmountInputValidator.validate("   ") is AmountValidationResult.Incomplete)
        assertTrue(AmountInputValidator.validate(null) is AmountValidationResult.Incomplete)
        assertTrue(AmountInputValidator.validate(".") is AmountValidationResult.Incomplete)
        assertTrue(AmountInputValidator.validate("12.") is AmountValidationResult.Incomplete)
    }

    @Test
    fun testSubCentPrecisionRejected() {
        val r1 = AmountInputValidator.validate("0.001")
        assertTrue(r1 is AmountValidationResult.Invalid)
        assertEquals("Maximum 2 decimal places allowed", (r1 as AmountValidationResult.Invalid).error)

        val r2 = AmountInputValidator.validate("12.345")
        assertTrue(r2 is AmountValidationResult.Invalid)
        assertEquals("Maximum 2 decimal places allowed", (r2 as AmountValidationResult.Invalid).error)
    }

    @Test
    fun testZeroAndNegativeAmountsRejected() {
        val r1 = AmountInputValidator.validate("0")
        assertTrue(r1 is AmountValidationResult.Invalid)

        val r2 = AmountInputValidator.validate("0.00")
        assertTrue(r2 is AmountValidationResult.Invalid)

        val r3 = AmountInputValidator.validate("-5.00")
        assertTrue(r3 is AmountValidationResult.Invalid)
    }

    @Test
    fun testScientificNotationAndOverflowRejected() {
        val r1 = AmountInputValidator.validate("Infinity")
        assertTrue(r1 is AmountValidationResult.Invalid)

        val r2 = AmountInputValidator.validate("1e309")
        assertTrue(r2 is AmountValidationResult.Invalid)

        val r3 = AmountInputValidator.validate("NaN")
        assertTrue(r3 is AmountValidationResult.Invalid)

        val r4 = AmountInputValidator.validate("1000000000.01") // MAX_AMOUNT_MINOR is 100,000,000,000 minor = 1,000,000,000.00
        assertTrue(r4 is AmountValidationResult.Invalid)
        assertEquals("Amount exceeds maximum limit", (r4 as AmountValidationResult.Invalid).error)

        // Exact maximum is valid
        val r5 = AmountInputValidator.validate("1000000000.00")
        assertTrue(r5 is AmountValidationResult.Valid)
        assertEquals(ProtocolVersion.MAX_AMOUNT_MINOR, (r5 as AmountValidationResult.Valid).amountMinor)
    }
}
