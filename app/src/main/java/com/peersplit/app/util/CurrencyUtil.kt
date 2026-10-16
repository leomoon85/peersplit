package com.peersplit.app.util

import java.util.Currency
import java.util.Locale

object CurrencyUtil {

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
                // If locale-dependent symbol returns code itself or $ incorrectly, fallback to code
                val sym = cur.getSymbol(Locale.getDefault())
                if (sym.length > 4) code else sym
            } catch (e: Exception) {
                code
            }
        }
    }

    /**
     * Formats an amount with the currency symbol, e.g. "₹500.00" or "$25.50"
     */
    fun formatAmount(amount: Double, currencyCode: String?): String {
        val sym = getSymbol(currencyCode)
        return "$sym${String.format(Locale.US, "%.2f", amount)}"
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
            balance > 0.01 -> "+$sym${String.format(Locale.US, "%.2f", balance)}"
            balance < -0.01 -> "-$sym${String.format(Locale.US, "%.2f", -balance)}"
            else -> "settled"
        }
    }

    /**
     * Formats signed user share, e.g. "+₹120.00", "-₹60.00", or "not involved"
     */
    fun formatShare(share: Double, currencyCode: String?): String {
        val sym = getSymbol(currencyCode)
        return when {
            share > 0.01 -> "+$sym${String.format(Locale.US, "%.2f", share)}"
            share < -0.01 -> "-$sym${String.format(Locale.US, "%.2f", -share)}"
            else -> "not involved"
        }
    }
}
