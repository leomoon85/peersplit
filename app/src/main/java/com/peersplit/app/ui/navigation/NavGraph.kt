package com.peersplit.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.ui.screens.*
import com.peersplit.app.ui.viewmodel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    val repository = PeerSplitApp.instance.repository
    val scope = CoroutineScope(Dispatchers.Main)

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel()
            val uiState by homeViewModel.uiState.collectAsState()

            HomeScreen(
                uiState = uiState,
                onGroupClick = { groupId ->
                    navController.navigate(Screen.GroupDetail.createRoute(groupId))
                },
                onCreateGroupClick = {
                    navController.navigate(Screen.CreateGroup.route)
                },
                onJoinGroupClick = {
                    navController.navigate(Screen.JoinGroup.route)
                },
                onProfileClick = {
                    navController.navigate(Screen.Profile.route)
                }
            )
        }

        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(
                onBackClick = { navController.popBackStack() },
                onCreateGroup = { name, currency ->
                    scope.launch {
                        val groupId = repository.createGroup(name, currency)
                        navController.popBackStack()
                        navController.navigate(Screen.GroupDetail.createRoute(groupId))
                    }
                }
            )
        }

        composable(Screen.JoinGroup.route) {
            JoinGroupScreen(
                onBackClick = { navController.popBackStack() },
                onJoinGroup = { inviteUri ->
                    scope.launch {
                        val result = repository.joinGroup(inviteUri)
                        result.onSuccess { groupId ->
                            navController.popBackStack()
                            navController.navigate(Screen.GroupDetail.createRoute(groupId))
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.GroupDetail.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val groupViewModel = viewModel { GroupViewModel(groupId) }
            val groupDetail by groupViewModel.groupDetail.collectAsState()
            val inviteCode by groupViewModel.inviteCode.collectAsState()

            GroupDetailScreen(
                groupDetail = groupDetail,
                inviteCode = inviteCode,
                onBackClick = { navController.popBackStack() },
                onAddExpenseClick = {
                    navController.navigate(Screen.AddExpense.createRoute(groupId))
                },
                onSettleUpClick = {
                    navController.navigate(Screen.SettleUp.createRoute(groupId))
                },
                onAuditLedgerClick = {
                    navController.navigate(Screen.LedgerAudit.createRoute(groupId))
                },
                onGenerateInvite = {
                    groupViewModel.generateInvite()
                },
                onDismissInvite = {
                    groupViewModel.dismissInviteDialog()
                }
            )
        }

        composable(
            route = Screen.AddExpense.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val addExpenseViewModel = viewModel { AddExpenseViewModel(groupId) }
            val groupDetail by addExpenseViewModel.groupDetail.collectAsState()
            val isSubmitting by addExpenseViewModel.isSubmitting.collectAsState()

            AddExpenseScreen(
                groupDetail = groupDetail,
                isSubmitting = isSubmitting,
                onBackClick = { navController.popBackStack() },
                onSubmitExpense = { desc, amount, currency, payer, splits ->
                    addExpenseViewModel.submitExpense(desc, amount, currency, payer, splits)
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.SettleUp.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val groupViewModel = viewModel { GroupViewModel(groupId) }
            val groupDetail by groupViewModel.groupDetail.collectAsState()

            SettleUpScreen(
                groupDetail = groupDetail,
                onBackClick = { navController.popBackStack() },
                onRecordSettlement = { payer, payee, amount, currency ->
                    scope.launch {
                        repository.recordSettlement(groupId, payer, payee, amount, currency)
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = Screen.LedgerAudit.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val auditViewModel = viewModel { LedgerAuditViewModel(groupId) }
            val state by auditViewModel.state.collectAsState()

            LedgerAuditScreen(
                state = state,
                onBackClick = { navController.popBackStack() },
                onReaudit = { auditViewModel.loadAndAuditLedger() }
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
