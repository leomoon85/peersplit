package com.peersplit.app.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateGroup : Screen("create_group")
    object JoinGroup : Screen("join_group")
    object Profile : Screen("profile")

    object GroupDetail : Screen("group_detail/{groupId}") {
        fun createRoute(groupId: String) = "group_detail/$groupId"
    }

    object AddExpense : Screen("add_expense/{groupId}") {
        fun createRoute(groupId: String) = "add_expense/$groupId"
    }

    object SettleUp : Screen("settle_up/{groupId}") {
        fun createRoute(groupId: String) = "settle_up/$groupId"
    }

    object LedgerAudit : Screen("ledger_audit/{groupId}") {
        fun createRoute(groupId: String) = "ledger_audit/$groupId"
    }
}
