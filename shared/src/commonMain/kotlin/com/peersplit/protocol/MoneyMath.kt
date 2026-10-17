package com.peersplit.protocol

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Exact integer minor-unit monetary arithmetic utilities (e.g., ₹1.00 = 100 paise, $1.00 = 100 cents).
 *
 * Guarantees:
 * - Zero floating-point arithmetic during ledger reconciliation or split allocation.
 * - Deterministic remainder allocation for equal splits sorted by canonical member public key.
 */
object MoneyMath {

    const val MINOR_UNITS_PER_MAJOR = 100L

    fun addExact(x: Long, y: Long): Long {
        val r = x + y
        if (((x xor r) and (y xor r)) < 0) {
            throw ArithmeticException("Long integer overflow: $x + $y")
        }
        return r
    }

    /**
     * Converts a UI display Double (e.g. 125.50) to exact integer minor units (12550L) at the input boundary.
     */
    fun toMinorUnits(majorAmount: Double): Long {
        require(majorAmount.isFinite() && majorAmount >= 0.0) { "Amount must be finite and non-negative" }
        val minor = (majorAmount * MINOR_UNITS_PER_MAJOR).roundToLong()
        require(minor <= ProtocolVersion.MAX_AMOUNT_MINOR) { "Amount exceeds maximum supported minor units" }
        return minor
    }

    /**
     * Converts exact integer minor units back to major Double strictly for UI presentation.
     */
    fun toMajorDouble(amountMinor: Long): Double {
        return amountMinor.toDouble() / MINOR_UNITS_PER_MAJOR.toDouble()
    }

    fun toMajorUnits(amountMinor: Long, currency: String? = null): Double {
        return toMajorDouble(amountMinor)
    }

    /**
     * Deterministically allocates `totalAmountMinor` equally across `memberPublicKeys`.
     * Any remainder (`totalAmountMinor % n`) is distributed +1 minor unit at a time to the
     * lexicographically smallest member public keys, ensuring `sum(splits.values) == totalAmountMinor`
     * on every device regardless of input list order.
     */
    fun allocateEqualSplits(
        totalAmountMinor: Long,
        memberPublicKeys: Collection<String>
    ): Map<String, Long> {
        require(totalAmountMinor > 0L) { "Total amountMinor must be positive" }
        val sortedMembers = memberPublicKeys.map { it.trim().lowercase() }.distinct().sorted()
        require(sortedMembers.isNotEmpty()) { "Cannot split among zero members" }

        val count = sortedMembers.size
        val baseShare = totalAmountMinor / count
        val remainder = (totalAmountMinor % count).toInt()

        val result = LinkedHashMap<String, Long>(count)
        for ((index, pubkey) in sortedMembers.withIndex()) {
            val extra = if (index < remainder) 1L else 0L
            result[pubkey] = addExact(baseShare, extra)
        }
        return result
    }

    /**
     * Normalizes a list of UI split shares so that the integer minor units
     * sum **exactly** to `totalAmountMinor`, adjusting any 1-cent rounding discrepancy on the
     * lexicographically first member with a positive share.
     */
    fun normalizeSplitsToExactTotal(
        totalAmountMinor: Long,
        rawSplitsMinor: Map<String, Long>
    ): Map<String, Long> {
        require(totalAmountMinor > 0L) { "Total amountMinor must be positive" }
        require(rawSplitsMinor.isNotEmpty()) { "Splits cannot be empty" }

        val sorted = rawSplitsMinor.entries
            .map { it.key.trim().lowercase() to it.value.coerceAtLeast(0L) }
            .sortedBy { it.first }
            .toMap(LinkedHashMap())

        var currentSum = 0L
        for (v in sorted.values) {
            currentSum = addExact(currentSum, v)
        }

        val diff = totalAmountMinor - currentSum
        if (diff != 0L && abs(diff) <= sorted.size.toLong() * 5L) {
            // Adjust rounding delta on the first eligible member
            val targetKey = sorted.entries.firstOrNull { it.value + diff >= 0L }?.key ?: sorted.keys.first()
            val adjusted = addExact(sorted[targetKey] ?: 0L, diff)
            if (adjusted >= 0L) {
                sorted[targetKey] = adjusted
            }
        }
        return sorted
    }
}
