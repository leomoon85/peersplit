package com.peersplit.app.util

import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.protocol.MoneyMath
import org.junit.Assert.*
import org.junit.Test

class ExpenseAllocationTest {
    @Test fun originalFortySixtyAllocationSurvivesAndScalesWithoutLossyIntegerShares() {
        val original = listOf(SplitItem("alice", 40.0), SplitItem("bob", 60.0))
        assertEquals(mapOf("alice" to "2", "bob" to "3"), ExpenseAllocation.shareWeights(original))
        assertEquals(original, ExpenseAllocation.rescale(10000, original))
        assertEquals(listOf(SplitItem("alice", 80.0), SplitItem("bob", 120.0)), ExpenseAllocation.rescale(20000, original))
    }
    @Test fun roundingIsExactAndIndependentOfInputOrder() {
        val original = listOf(SplitItem("bob", 33.33), SplitItem("alice", 33.34), SplitItem("charlie", 33.33))
        val actual = ExpenseAllocation.rescale(10001, original)
        assertEquals(10001L, actual.sumOf { MoneyMath.toMinorUnits(it.amount) })
        assertEquals(actual.associate { it.userPubkey to it.amount }, ExpenseAllocation.rescale(10001, original.reversed()).associate { it.userPubkey to it.amount })
        assertEquals(original, ExpenseAllocation.rescale(10000, original))
    }
    @Test fun zeroSharesStayZeroAndMaximumAmountsDoNotOverflow() {
        val actual = ExpenseAllocation.rescale(100_000_000_000L, listOf(SplitItem("a", 999999999.99), SplitItem("b", 0.01), SplitItem("c", 0.0)))
        assertEquals(100_000_000_000L, actual.sumOf { MoneyMath.toMinorUnits(it.amount) })
        assertEquals(0.0, actual.last().amount, 0.0)
    }
}
