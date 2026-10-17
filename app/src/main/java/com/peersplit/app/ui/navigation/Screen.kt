package com.peersplit.app.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateGroup : Screen("create_group")
    object JoinGroup : Screen("join_group")
    object Profile : Screen("profile")

    object GroupDetail : Screen("group_detail/{groupId}") {
        fun createRoute(groupId: String) = "group_detail/$groupId"
    }

    object AddExpense : Screen("add_expense/{groupId}?expenseId={expenseId}") {
        fun createRoute(groupId: String, expenseId: String? = null): String {
            return if (!expenseId.isNullOrBlank()) {
                "add_expense/$groupId?expenseId=$expenseId"
            } else {
                "add_expense/$groupId"
            }
        }
    }

    object SettleUp : Screen("settle_up/{groupId}?payer={payer}&payee={payee}&amount={amount}") {
        fun createRoute(groupId: String, payer: String? = null, payee: String? = null, amount: Double? = null): String {
            val p = payer ?: ""
            val pe = payee ?: ""
            val a = amount?.toString() ?: ""
            return "settle_up/$groupId?payer=$p&payee=$pe&amount=$a"
        }
    }

    object LedgerAudit : Screen("ledger_audit/{groupId}") {
        fun createRoute(groupId: String) = "ledger_audit/$groupId"
    }
}
