package com.peersplit.app.util

import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.protocol.MoneyMath
import java.math.BigInteger

/** Preserve exact stored proportions, assigning rounding remainders deterministically. */
object ExpenseAllocation {
    fun shareWeights(original: List<SplitItem>): Map<String, String> {
        val amounts = original.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
        fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val divisor = amounts.values.fold(0L) { acc, amount -> gcd(acc, amount) }.coerceAtLeast(1L)
        return amounts.mapValues { (_, amount) -> (amount / divisor).toString() }
    }

    fun rescale(totalMinor: Long, original: List<SplitItem>): List<SplitItem> {
        require(totalMinor in 1..com.peersplit.app.protocol.ProtocolVersion.MAX_AMOUNT_MINOR && original.isNotEmpty())
        val weights = original.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
        require(weights.size == original.size)
        val sum = weights.values.fold(BigInteger.ZERO) { acc, value -> acc + value.toBigInteger() }
        require(sum.signum() > 0)
        val divided = weights.mapValues { (_, weight) ->
            (totalMinor.toBigInteger() * weight.toBigInteger()).divideAndRemainder(sum)
        }
        // Each quotient is nonnegative and <= totalMinor, already bounded to the protocol limit.
        val allocated = divided.mapValues { it.value[0].toLong() }.toMutableMap()
        var remainder = totalMinor - allocated.values.sum()
        divided.entries.sortedWith(
            compareByDescending<Map.Entry<String, Array<BigInteger>>> { it.value[1] }.thenBy { it.key }
        ).forEach { (key, _) ->
            if (remainder > 0) {
                allocated[key] = allocated.getValue(key) + 1
                remainder--
            }
        }
        return original.map { SplitItem(it.userPubkey, MoneyMath.toMajorDouble(allocated.getValue(it.userPubkey))) }
    }
}
