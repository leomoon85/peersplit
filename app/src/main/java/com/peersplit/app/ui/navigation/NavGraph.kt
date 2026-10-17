package com.peersplit.app.ui.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

    // Deep link: peersplit://... opens Join Group with the code prefilled
    val pendingInvite by PendingInvite.code.collectAsState()
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            navController.navigate(Screen.JoinGroup.route) { launchSingleTop = true }
        }
    }

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
                },
                onArchiveGroup = { targetGroupId, isArchived ->
                    homeViewModel.archiveGroup(targetGroupId, isArchived)
                },
                onDeleteGroup = { targetGroupId ->
                    homeViewModel.deleteGroup(targetGroupId)
                },
                onUnlockVault = { pin ->
                    homeViewModel.unlockVault(pin)
                },
                onLockVault = {
                    homeViewModel.lockVault()
                }
            )
        }

        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(
                onBackClick = { navController.popBackStack() },
                onCreateGroup = { name, currency, isSecret, secretPin, isPersonalSolo, initialMembers ->
                    scope.launch {
                        val groupId = repository.createGroup(
                            name = name,
                            currency = currency,
                            isSecret = isSecret,
                            secretPin = secretPin,
                            isPersonalSolo = isPersonalSolo,
                            initialMemberNames = initialMembers
                        )
                        navController.popBackStack()
                        navController.navigate(Screen.GroupDetail.createRoute(groupId))
                    }
                }
            )
        }

        composable(Screen.JoinGroup.route) {
            val context = LocalContext.current
            JoinGroupScreen(
                onBackClick = { navController.popBackStack() },
                onJoinGroup = { inviteUri, claimedGhostPubkey, onFailed ->
                    scope.launch {
                        val result = repository.joinGroup(inviteUri, claimedGhostPubkey)
                        result.onSuccess { groupId ->
                            navController.popBackStack()
                            navController.navigate(Screen.GroupDetail.createRoute(groupId))
                        }.onFailure {
                            Toast.makeText(context, "Invalid or corrupted invite link", Toast.LENGTH_LONG).show()
                            onFailed()
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
                onSettleUpClick = { payer, payee, amount ->
                    navController.navigate(Screen.SettleUp.createRoute(groupId, payer, payee, amount))
                },
                onAuditLedgerClick = {
                    navController.navigate(Screen.LedgerAudit.createRoute(groupId))
                },
                onGenerateInvite = {
                    groupViewModel.generateInvite()
                },
                onDismissInvite = {
                    groupViewModel.dismissInviteDialog()
                },
                onAddOfflineMember = { displayName, upiId, phone ->
                    groupViewModel.addOfflineMember(displayName, upiId, phone)
                },
                onVoidExpense = { expenseId, description ->
                    groupViewModel.voidExpense(expenseId, description)
                },
                onUpdateBudget = { budget ->
                    groupViewModel.updateBudget(budget)
                },
                onArchiveGroup = { isArchived ->
                    groupViewModel.archiveGroup(isArchived) {
                        navController.popBackStack()
                    }
                },
                onDeleteGroup = {
                    groupViewModel.deleteGroup {
                        navController.popBackStack()
                    }
                },
                onUpdateMemberContact = { pubkey, phone, upiId ->
                    groupViewModel.updateMemberContact(pubkey, phone, upiId)
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
                onSubmitExpense = { desc, amount, currency, payer, splits, category, splitType, note, payers ->
                    addExpenseViewModel.submitExpense(desc, amount, currency, payer, splits, category, splitType, note, payers)
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.SettleUp.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("payer") { type = NavType.StringType; defaultValue = ""; nullable = true },
                navArgument("payee") { type = NavType.StringType; defaultValue = ""; nullable = true },
                navArgument("amount") { type = NavType.StringType; defaultValue = ""; nullable = true }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val initialPayer = backStackEntry.arguments?.getString("payer")?.takeIf { it.isNotBlank() }
            val initialPayee = backStackEntry.arguments?.getString("payee")?.takeIf { it.isNotBlank() }
            val initialAmount = backStackEntry.arguments?.getString("amount")?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            val groupViewModel = viewModel { GroupViewModel(groupId) }
            val groupDetail by groupViewModel.groupDetail.collectAsState()

            SettleUpScreen(
                groupDetail = groupDetail,
                initialPayer = initialPayer,
                initialPayee = initialPayee,
                initialAmount = initialAmount,
                onBackClick = { navController.popBackStack() },
                onUpdatePayeeUpi = { payeePubkey, upiId ->
                    groupViewModel.updateMemberUpiId(payeePubkey, upiId)
                },
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
