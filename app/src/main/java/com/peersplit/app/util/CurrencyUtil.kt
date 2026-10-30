package com.peersplit.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

object CurrencyUtil {

    private val stdSymbols = DecimalFormatSymbols(Locale.US)
    private val stdFormat = DecimalFormat("#,##0.00", stdSymbols)

    /**
     * Formats numerical value with Indian lakh/crore grouping for INR (e.g. 1,50,000.00, 10,00,000.00)
     * and standard grouping for other currencies (e.g. 150,000.00).
     */
    fun formatNumeric(amount: Double, currencyCode: String?): String {
        val code = currencyCode?.uppercase()?.trim() ?: "USD"
        if (code != "INR") {
            return synchronized(stdFormat) {
                stdFormat.format(amount)
            }
        }

        // Custom, robust Indian number grouping (last 3 digits, then groups of 2)
        val formattedStandard = String.format(Locale.US, "%.2f", amount)
        val parts = formattedStandard.split('.')
        val integerPart = parts[0]
        val decimalPart = if (parts.size > 1) parts[1] else "00"

        if (integerPart.length <= 3) {
            return "$integerPart.$decimalPart"
        }

        val lastThree = integerPart.takeLast(3)
        val remaining = integerPart.dropLast(3)

        val sb = StringBuilder()
        var count = 0
        for (i in remaining.length - 1 downTo 0) {
            sb.append(remaining[i])
            count++
            if (count % 2 == 0 && i > 0) {
                sb.append(',')
            }
        }
        val groupedRemaining = sb.reverse().toString()
        return "$groupedRemaining,$lastThree.$decimalPart"
    }

    /**
     * Resolves the canonical display symbol for a 3-letter ISO currency code.
     * Guaranteed proper symbol mapping (e.g. INR -> ₹, EUR -> €, GBP -> £).
     */
    fun getSymbol(currencyCode: String?): String {
        val code = currencyCode?.uppercase()?.trim() ?: "USD"
        return when (code) {
            "INR" -> "₹"
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            "CAD" -> "CA$"
            "AUD" -> "A$"
            "CNY" -> "¥"
            "CHF" -> "CHF"
            "SGD" -> "S$"
            "AED" -> "AED"
            "BRL" -> "R$"
            "KRW" -> "₩"
            "RUB" -> "₽"
            else -> try {
                val cur = Currency.getInstance(code)
                val sym = cur.getSymbol(Locale.getDefault())
                if (sym.length > 4) code else sym
            } catch (e: Exception) {
                code
            }
        }
    }

    /**
     * Formats an amount with the currency symbol and grouping, e.g. "₹1,50,000.00" or "$25.50"
     */
    fun formatAmount(amount: Double, currencyCode: String?): String {
        val sym = getSymbol(currencyCode)
        return "$sym${formatNumeric(amount, currencyCode)}"
    }

    /**
     * Formats a balance with + or - and the currency symbol:
     * > 0.01: "+₹250.00"
     * < -0.01: "-₹150.00"
     * settled: "settled"
     */
    fun formatBalance(balance: Double, currencyCode: String?): String {
        val sym = getSymbol(currencyCode)
        return when {
            balance > 0.01 -> "+$sym${formatNumeric(balance, currencyCode)}"
            balance < -0.01 -> "-$sym${formatNumeric(-balance, currencyCode)}"
            else -> "settled"
        }
    }

    /**
     * Formats signed user share, e.g. "+₹120.00", "-₹60.00", or "not involved"
     */
    fun formatShare(share: Double, currencyCode: String?): String {
        val sym = getSymbol(currencyCode)
        return when {
            share > 0.01 -> "+$sym${formatNumeric(share, currencyCode)}"
            share < -0.01 -> "-$sym${formatNumeric(-share, currencyCode)}"
            else -> "not involved"
        }
    }
}
