package com.peersplit.app.ui.navigation

import android.widget.Toast
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    val repository = PeerSplitApp.instance.repository
    val scope = rememberCoroutineScope()

    // Deep link: peersplit://... opens Join Group with the code prefilled
    val pendingInvite by PendingInvite.code.collectAsState()
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            navController.navigate(Screen.JoinGroup.route) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                initialOffset = { it / 5 }
            ) + fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                targetOffset = { it / 7 }
            ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                initialOffset = { it / 7 }
            ) + fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                targetOffset = { it / 5 }
            ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
        }
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
            val context = LocalContext.current
            CreateGroupScreen(
                onBackClick = { navController.popBackStack() },
                onCreateGroup = { name, currency, isSecret, secretPin, isPersonalSolo, initialMembers, onError ->
                    scope.launch {
                        try {
                            val groupId = repository.createGroup(
                                name = name,
                                currency = currency,
                                isSecret = isSecret,
                                secretPin = secretPin,
                                isPersonalSolo = isPersonalSolo,
                                initialMemberNames = initialMembers
                            )
                            com.peersplit.app.util.RewardsEngine.recordAction(context, xpEarned = 50)
                            Toast.makeText(context, "🎉 Group created! +50 SplitPoints (XP)", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                            navController.navigate(Screen.GroupDetail.createRoute(groupId))
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Failed to create group", Toast.LENGTH_LONG).show()
                            onError()
                        }
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
                            com.peersplit.app.util.RewardsEngine.recordAction(context, xpEarned = 50)
                            Toast.makeText(context, "🚀 Joined group! +50 SplitPoints (XP)", Toast.LENGTH_SHORT).show()
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
                onEditExpenseClick = { expenseId ->
                    navController.navigate(Screen.AddExpense.createRoute(groupId, expenseId))
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
                },
                isSyncing = groupViewModel.isSyncing.collectAsState().value,
                onManualSync = { onDone ->
                    groupViewModel.manualSync(onDone)
                },
                onImportExpenses = { expenses, onProgress, onComplete ->
                    groupViewModel.importExpenses(expenses, onProgress, onComplete)
                }
            )
        }

        composable(
            route = Screen.AddExpense.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("expenseId") { type = NavType.StringType; defaultValue = ""; nullable = true }
            )
        ) { backStackEntry ->
            val context = LocalContext.current
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val editingExpenseId = backStackEntry.arguments?.getString("expenseId")?.takeIf { it.isNotBlank() }
            val addExpenseViewModel = viewModel { AddExpenseViewModel(groupId) }
            val groupDetail by addExpenseViewModel.groupDetail.collectAsState()
            val isSubmitting by addExpenseViewModel.isSubmitting.collectAsState()

            AddExpenseScreen(
                groupDetail = groupDetail,
                isSubmitting = isSubmitting,
                editingExpenseId = editingExpenseId,
                onBackClick = { navController.popBackStack() },
                onSubmitExpense = { desc, amount, currency, payer, splits, category, splitType, note, payers, expenseDateMillis, editId, origDesc ->
                    addExpenseViewModel.submitExpense(
                        description = desc,
                        amount = amount,
                        currency = currency,
                        payerPubkey = payer,
                        splits = splits,
                        category = category,
                        splitType = splitType,
                        note = note,
                        payers = payers,
                        expenseDateMillis = expenseDateMillis,
                        editingExpenseId = editId,
                        originalDescription = origDesc,
                        onComplete = {
                            val myPubkey = com.peersplit.app.crypto.KeyManager.getOrCreateIdentity(context).publicKeyHex
                            val userPaid = payer == myPubkey || payers.any { it.userPubkey == myPubkey && it.amount > 0 }
                            if (editId.isNullOrBlank()) {
                                val xp = if (userPaid) 45 else 25
                                com.peersplit.app.util.RewardsEngine.recordAction(
                                    context = context,
                                    xpEarned = xp,
                                    isExpense = true,
                                    userPaidBill = userPaid
                                )
                                Toast.makeText(context, "⚡ Expense logged! +$xp SplitPoints (XP)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "✏️ Expense updated & synced!", Toast.LENGTH_SHORT).show()
                            }
                            navController.popBackStack()
                        },
                        onError = { error ->
                            Toast.makeText(context, "Error saving expense: $error", Toast.LENGTH_LONG).show()
                        }
                    )
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
            val context = LocalContext.current
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
                        com.peersplit.app.util.RewardsEngine.recordAction(
                            context = context,
                            xpEarned = 100,
                            isSettlement = true
                        )
                        Toast.makeText(context, "🏆 Debt settled! +100 SplitPoints (XP)", Toast.LENGTH_SHORT).show()
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
