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

        composable(Screen.CreateGroup.route) { entry ->
            val context = LocalContext.current
            val entryViewModel: GroupEntryViewModel = viewModel()
            val entryState by entryViewModel.state.collectAsState()
            LaunchedEffect(entryState.groupId) {
                entryState.groupId?.let { groupId ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        entryViewModel.consumeResult()
                        com.peersplit.app.util.RewardsEngine.recordAction(context, xpEarned = 50)
                        Toast.makeText(context, "Group created locally", Toast.LENGTH_LONG).show()
                        navController.popBackStack()
                        navController.navigate(Screen.GroupDetail.createRoute(groupId))
                    }
                }
            }
            CreateGroupScreen(
                isCreating = entryState.busy, submissionError = entryState.error,
                onBackClick = { navController.popBackStack() },
                onCreateGroup = { name, currency, secret, pin, solo, members ->
                    entryViewModel.create(name, currency, secret, pin, solo, members)
                }
            )
        }

        composable(Screen.JoinGroup.route) { entry ->
            val context = LocalContext.current
            val entryViewModel: GroupEntryViewModel = viewModel()
            val entryState by entryViewModel.state.collectAsState()
            LaunchedEffect(entryState.groupId) {
                entryState.groupId?.let { groupId ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        entryViewModel.consumeResult()
                        Toast.makeText(context, "Invite saved. Group admission and synchronization may still be pending.", Toast.LENGTH_LONG).show()
                        navController.popBackStack()
                        navController.navigate(Screen.GroupDetail.createRoute(groupId))
                    }
                }
            }
            JoinGroupScreen(
                isJoining = entryState.busy, submissionError = entryState.error,
                onBackClick = { navController.popBackStack() },
                onJoinGroup = { invite, claim -> entryViewModel.join(invite, claim) }
            )
        }

        composable(
            route = Screen.GroupDetail.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val groupViewModel = viewModel { GroupViewModel(groupId) }
            val detailState by groupViewModel.detailState.collectAsState()
            val groupDetail = detailState.detail
            val inviteCode by groupViewModel.inviteCode.collectAsState()

            val actionMessage by groupViewModel.actionMessage.collectAsState()
            LaunchedEffect(actionMessage) {
                actionMessage?.let {
                    Toast.makeText(navController.context, it, Toast.LENGTH_LONG).show()
                    groupViewModel.clearActionMessage()
                }
            }
            GroupDetailScreen(
                groupDetail = groupDetail,
                isLoading = detailState.isLoading,
                loadError = detailState.error,
                onRetryLoad = { groupViewModel.reload() },
                inviteCode = inviteCode,
                actionError = groupViewModel.actionError.collectAsState().value,
                onRetryAction = { groupViewModel.retryLastAction() },
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
                importProgress = groupViewModel.importProgress.collectAsState().value,
                onDismissImportResult = { groupViewModel.dismissImportResult() },
                onRetryImport = { groupViewModel.retryFailedImports() },
                onImportExpenses = { expenses -> groupViewModel.importExpenses(expenses) }
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
            val detailState by addExpenseViewModel.detailState.collectAsState()
            val groupDetail = detailState.detail
            val isSubmitting by addExpenseViewModel.isSubmitting.collectAsState()

            val savedReceipt by addExpenseViewModel.savedReceipt.collectAsState()
            LaunchedEffect(savedReceipt) {
                savedReceipt?.let { receipt ->
                    if (navController.currentBackStackEntry?.id == backStackEntry.id) {
                        addExpenseViewModel.consumeSavedReceipt()
                        if (!receipt.edited) com.peersplit.app.util.RewardsEngine.recordAction(
                            context, xpEarned = if (receipt.userPaid) 45 else 25, isExpense = true, userPaidBill = receipt.userPaid)
                        Toast.makeText(context, "Expense saved locally; sync queued", Toast.LENGTH_LONG).show()
                        navController.popBackStack()
                    }
                }
            }
            AddExpenseScreen(
                groupDetail = groupDetail,
                isLoading = detailState.isLoading,
                loadError = detailState.error,
                onRetryLoad = { addExpenseViewModel.reload() },
                isSubmitting = isSubmitting,
                submissionError = addExpenseViewModel.submissionError.collectAsState().value,
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
                        onError = { /* Persistent error is displayed by the form. */ }
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
            val detailState by groupViewModel.detailState.collectAsState()
            val groupDetail = detailState.detail

            val settlementSaved by groupViewModel.settlementSaved.collectAsState()
            LaunchedEffect(settlementSaved) {
                if (settlementSaved) {
                    groupViewModel.consumeSettlementSaved()
                    com.peersplit.app.util.RewardsEngine.recordAction(context, xpEarned = 100, isSettlement = true)
                    Toast.makeText(context, "Payment recorded locally; sync queued", Toast.LENGTH_LONG).show()
                    navController.popBackStack()
                }
            }
            SettleUpScreen(
                groupDetail = groupDetail,
                isLoading = detailState.isLoading,
                loadError = detailState.error,
                onRetryLoad = { groupViewModel.reload() },
                initialPayer = initialPayer,
                initialPayee = initialPayee,
                initialAmount = initialAmount,
                onBackClick = { navController.popBackStack() },
                isSubmitting = groupViewModel.isRecording.collectAsState().value,
                submissionError = groupViewModel.actionError.collectAsState().value,
                onUpdatePayeeUpi = { payeePubkey, upiId -> groupViewModel.saveMemberUpi(payeePubkey, upiId) },
                onRecordSettlement = { payer, payee, amount, currency ->
                    groupViewModel.recordSettlement(payer, payee, amount, currency)
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
