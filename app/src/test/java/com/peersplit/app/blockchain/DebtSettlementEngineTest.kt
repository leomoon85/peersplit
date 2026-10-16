package com.peersplit.app.blockchain

import org.junit.Assert.*
import org.junit.Test

class DebtSettlementEngineTest {

    @Test
    fun testNetBalanceCalculation() {
        val alice = "alice_pubkey"
        val bob = "bob_pubkey"
        val charlie = "charlie_pubkey"

        // Expense 1: Alice paid $90 for Alice, Bob, Charlie ($30 each)
        val exp1 = ExpensePayload(
            expenseId = "exp_1",
            description = "Dinner",
            totalAmount = 90.0,
            currency = "USD",
            payerPubkey = alice,
            splits = listOf(
                SplitItem(alice, 30.0),
                SplitItem(bob, 30.0),
                SplitItem(charlie, 30.0)
            )
        )

        val net = DebtSettlementEngine.calculateNetBalances(listOf(exp1), emptyList())

        // Alice paid 90, owes 30 => net +60
        // Bob paid 0, owes 30 => net -30
        // Charlie paid 0, owes 30 => net -30
        assertEquals(60.0, net[alice] ?: 0.0, 0.001)
        assertEquals(-30.0, net[bob] ?: 0.0, 0.001)
        assertEquals(-30.0, net[charlie] ?: 0.0, 0.001)
    }

    @Test
    fun testMinCashFlowSimplification() {
        val alice = "alice"
        val bob = "bob"
        val charlie = "charlie"

        // Alice is owed 60, Bob owes 30, Charlie owes 30
        val netBalances = mapOf(
            alice to 60.0,
            bob to -30.0,
            charlie to -30.0
        )

        val suggestions = DebtSettlementEngine.simplifyDebts(netBalances)

        assertEquals(2, suggestions.size)
        // Either Bob pays Alice 30 and Charlie pays Alice 30
        for (s in suggestions) {
            assertEquals(alice, s.toPubkey)
            assertEquals(30.0, s.amount, 0.001)
            assertTrue(s.fromPubkey == bob || s.fromPubkey == charlie)
        }
    }

    @Test
    fun testSettlementSquareUp() {
        val alice = "alice"
        val bob = "bob"

        // Alice paid $40 for Bob ($20 each)
        val exp = ExpensePayload(
            expenseId = "exp_1",
            description = "Lunch",
            totalAmount = 40.0,
            currency = "USD",
            payerPubkey = alice,
            splits = listOf(
                SplitItem(alice, 20.0),
                SplitItem(bob, 20.0)
            )
        )

        // Bob settles $20 to Alice
        val stl = SettlementPayload(
            settlementId = "stl_1",
            payerPubkey = bob,
            payeePubkey = alice,
            amount = 20.0,
            currency = "USD"
        )

        val net = DebtSettlementEngine.calculateNetBalances(listOf(exp), listOf(stl))

        // Both balances should be 0.0
        assertEquals(0.0, net[alice] ?: 0.0, 0.001)
        assertEquals(0.0, net[bob] ?: 0.0, 0.001)

        val simplified = DebtSettlementEngine.simplifyDebts(net)
        assertTrue("No pending debts after settlement", simplified.isEmpty())
    }

    @Test
    fun testMultiPayerAndVoidedExpenses() {
        val alice = "alice"
        val bob = "bob"
        val charlie = "charlie"

        // Alice paid 120, Bob paid 60 (total 180), split equally 60 each
        val multiPayerExp = ExpensePayload(
            expenseId = "exp_multi",
            description = "Resort Booking",
            totalAmount = 180.0,
            currency = "USD",
            payerPubkey = alice,
            payers = listOf(
                SplitItem(alice, 120.0),
                SplitItem(bob, 60.0)
            ),
            splits = listOf(
                SplitItem(alice, 60.0),
                SplitItem(bob, 60.0),
                SplitItem(charlie, 60.0)
            )
        )

        // Mistake expense that was voided
        val voidedExp = ExpensePayload(
            expenseId = "exp_mistake",
            description = "Typo 5000",
            totalAmount = 5000.0,
            currency = "USD",
            payerPubkey = bob,
            splits = listOf(
                SplitItem(alice, 2500.0),
                SplitItem(bob, 2500.0)
            )
        )

        val net = DebtSettlementEngine.calculateNetBalances(
            expenses = listOf(multiPayerExp, voidedExp),
            settlements = emptyList(),
            voidedExpenseIds = setOf("exp_mistake")
        )

        // Alice paid 120, owes 60 => +60
        // Bob paid 60, owes 60 => 0
        // Charlie paid 0, owes 60 => -60
        assertEquals(60.0, net[alice] ?: 0.0, 0.001)
        assertEquals(0.0, net[bob] ?: 0.0, 0.001)
        assertEquals(-60.0, net[charlie] ?: 0.0, 0.001)
    }
}
