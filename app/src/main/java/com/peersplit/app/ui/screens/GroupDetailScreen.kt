package com.peersplit.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.crypto.QrCodeUtil
import com.peersplit.app.data.model.ExpenseCategory
import com.peersplit.app.data.model.ExpenseItem
import com.peersplit.app.data.model.Member
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BalanceNegative
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.ExportUtil
import com.peersplit.app.util.NameUtil
import com.peersplit.app.util.ShareUtil
import com.peersplit.app.util.UpiUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupDetail: GroupDetail?,
    inviteCode: String?,
    onBackClick: () -> Unit,
    onAddExpenseClick: () -> Unit,
    onSettleUpClick: (payer: String?, payee: String?, amount: Double?) -> Unit = { _, _, _ -> },
    onAuditLedgerClick: () -> Unit,
    onGenerateInvite: () -> Unit,
    onDismissInvite: () -> Unit,
    onAddOfflineMember: (displayName: String, upiId: String?) -> Unit = { _, _ -> },
    onVoidExpense: (expenseId: String, description: String) -> Unit = { _, _ -> },
    onUpdateBudget: (budget: Double) -> Unit = {},
    onArchiveGroup: (isArchived: Boolean) -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onUpdateMemberUpi: (pubkey: String, upiId: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var showExportMenu by remember { mutableStateOf(false) }

    // Add Offline Member Dialog state
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var newMemberName by remember { mutableStateOf("") }
    var newMemberUpi by remember { mutableStateOf("") }

    // Edit Member UPI Dialog state
    var memberForUpiEdit by remember { mutableStateOf<Member?>(null) }
    var editUpiInput by remember { mutableStateOf("") }

    // Budget Target Dialog state
    var showBudgetDialog by remember { mutableStateOf(false) }
    var budgetInputText by remember { mutableStateOf("") }

    // Expense Detail Bottom Sheet state
    var selectedExpenseForDetail by remember { mutableStateOf<ExpenseItem?>(null) }
    var showVoidConfirmDialog by remember { mutableStateOf<ExpenseItem?>(null) }

    // Delete Group Confirmation Dialog state
    var showDeleteGroupDialog by remember { mutableStateOf(false) }

    if (groupDetail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val activeExpensesCount = groupDetail.expenses.count { !it.isVoided }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(groupDetail.group.name, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (groupDetail.isLedgerIntact) BalancePositive else BalanceNegative,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (groupDetail.isLedgerIntact) "Synced & Secured" else "Sync Conflict",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (groupDetail.isLedgerIntact) BalancePositive else BalanceNegative
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMemberDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Member")
                    }
                    IconButton(onClick = onGenerateInvite) {
                        Icon(Icons.Default.Share, contentDescription = "Invite Members")
                    }
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add Member") },
                                onClick = {
                                    showExportMenu = false
                                    showAddMemberDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Set Group / Monthly Budget") },
                                onClick = {
                                    showExportMenu = false
                                    budgetInputText = if (groupDetail.group.monthlyBudget > 0) {
                                        String.format(Locale.US, "%.0f", groupDetail.group.monthlyBudget)
                                    } else ""
                                    showBudgetDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Share Formatted Statement") },
                                onClick = {
                                    showExportMenu = false
                                    ExportUtil.shareStatement(context, groupDetail, asCsv = false)
                                },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Export CSV Spreadsheet") },
                                onClick = {
                                    showExportMenu = false
                                    ExportUtil.shareStatement(context, groupDetail, asCsv = true)
                                },
                                leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Verify Security & History") },
                                onClick = {
                                    showExportMenu = false
                                    onAuditLedgerClick()
                                },
                                leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (groupDetail.group.isArchived) "Unarchive Group" else "Archive Group") },
                                onClick = {
                                    showExportMenu = false
                                    onArchiveGroup(!groupDetail.group.isArchived)
                                },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Group", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showExportMenu = false
                                    showDeleteGroupDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddExpenseClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Expense") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(if (groupDetail.group.isPersonalSolo) "Overview" else "Debts") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Expenses ($activeExpensesCount)") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Analytics 📊") }
                )
            }

            when (selectedTab) {
                0 -> BalancesAndDebtsTab(
                    groupDetail = groupDetail,
                    onSettleUpClick = onSettleUpClick,
                    onAddMemberClick = { showAddMemberDialog = true },
                    onShareInviteClick = onGenerateInvite,
                    onEditMemberUpi = { member ->
                        editUpiInput = member.upiId ?: ""
                        memberForUpiEdit = member
                    },
                    onSetBudgetClick = {
                        budgetInputText = if (groupDetail.group.monthlyBudget > 0) {
                            String.format(Locale.US, "%.0f", groupDetail.group.monthlyBudget)
                        } else ""
                        showBudgetDialog = true
                    }
                )
                1 -> ExpensesTab(
                    groupDetail = groupDetail,
                    onExpenseClick = { selectedExpenseForDetail = it }
                )
                2 -> AnalyticsTab(
                    groupDetail = groupDetail,
                    onSetBudgetClick = {
                        budgetInputText = if (groupDetail.group.monthlyBudget > 0) {
                            String.format(Locale.US, "%.0f", groupDetail.group.monthlyBudget)
                        } else ""
                        showBudgetDialog = true
                    }
                )
            }
        }
    }

    // Edit Member UPI Dialog
    if (memberForUpiEdit != null) {
        val m = memberForUpiEdit!!
        AlertDialog(
            onDismissRequest = { memberForUpiEdit = null },
            title = { Text("UPI ID for ${m.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter ${if (m.isCurrentUser) "your" else "${m.displayName}'s"} UPI ID so group members can pay directly via GPay, PhonePe, or Paytm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editUpiInput,
                        onValueChange = { editUpiInput = it },
                        label = { Text("UPI ID") },
                        placeholder = { Text("e.g. name@okhdfcbank, 9876543210@ybl") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = editUpiInput.trim()
                        onUpdateMemberUpi(m.pubkey, clean)
                        if (m.isCurrentUser) {
                            com.peersplit.app.crypto.KeyManager.updateUpiId(context, clean)
                        }
                        Toast.makeText(context, "UPI ID updated for ${m.displayName}!", Toast.LENGTH_SHORT).show()
                        memberForUpiEdit = null
                    }
                ) {
                    Text("Save UPI ID")
                }
            },
            dismissButton = {
                TextButton(onClick = { memberForUpiEdit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Offline / Ghost Member Dialog
    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = { Text("Add Group Member") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Add a friend by name so you can split expenses with them immediately—even before they install PeerSplit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newMemberName,
                        onValueChange = { newMemberName = it },
                        label = { Text("Member Name") },
                        placeholder = { Text("e.g. Rahul, Priya") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newMemberUpi,
                        onValueChange = { newMemberUpi = it },
                        label = { Text("UPI ID (Optional)") },
                        placeholder = { Text("e.g. rahul@upi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newMemberName.isNotBlank(),
                    onClick = {
                        onAddOfflineMember(newMemberName.trim(), newMemberUpi.trim().takeIf { it.isNotBlank() })
                        Toast.makeText(context, "${newMemberName.trim()} added to group!", Toast.LENGTH_SHORT).show()
                        newMemberName = ""
                        newMemberUpi = ""
                        showAddMemberDialog = false
                    }
                ) {
                    Text("Add Member")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Set Budget Target Dialog
    if (showBudgetDialog) {
        val sym = CurrencyUtil.getSymbol(groupDetail.group.currency)
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("Set Spending Budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Set a target budget for ${groupDetail.group.name} to track spending progress (enter 0 to remove budget).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = budgetInputText,
                        onValueChange = { budgetInputText = it },
                        label = { Text("Budget Limit (${groupDetail.group.currency})") },
                        prefix = { Text("$sym ") },
                        placeholder = { Text("e.g. 25000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val b = budgetInputText.toDoubleOrNull() ?: 0.0
                        onUpdateBudget(b)
                        Toast.makeText(
                            context,
                            if (b > 0) "Budget updated to ${CurrencyUtil.formatAmount(b, groupDetail.group.currency)}" else "Budget cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                        showBudgetDialog = false
                    }
                ) {
                    Text("Save Budget")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Expense Detail & Void Bottom Sheet / Dialog
    if (selectedExpenseForDetail != null) {
        val exp = selectedExpenseForDetail!!
        val memberMap = groupDetail.members.associate { it.pubkey to it.displayName }
        val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }
        val category = ExpenseCategory.fromName(exp.category)

        ModalBottomSheet(
            onDismissRequest = { selectedExpenseForDetail = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(category.composeColor().copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(category.iconEmoji, fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = exp.description,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textDecoration = if (exp.isVoided) TextDecoration.LineThrough else TextDecoration.None
                            )
                            Text(
                                text = "${category.displayName} • ${exp.splitType} Split",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = CurrencyUtil.formatAmount(exp.totalAmount, exp.currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (exp.isVoided) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                if (exp.isVoided) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "⚠️ This expense was cancelled and is excluded from balances.",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (exp.note.isNotBlank()) {
                    Text(
                        "Note: “${exp.note}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                // Paid By Breakdown
                Text("PAID BY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (exp.payers.size > 1) {
                    exp.payers.forEach { p ->
                        val pName = memberMap[p.userPubkey] ?: "Member (${p.userPubkey.take(6)})"
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pName, style = MaterialTheme.typography.bodyMedium)
                            Text(CurrencyUtil.formatAmount(p.amount, exp.currency), fontWeight = FontWeight.SemiBold, color = BalancePositive)
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(exp.payerName, style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyUtil.formatAmount(exp.totalAmount, exp.currency), fontWeight = FontWeight.SemiBold, color = BalancePositive)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Split Breakdown
                Text("ITEMIZED SPLIT BREAKDOWN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                exp.splits.forEach { split ->
                    val sName = memberMap[split.userPubkey] ?: "Member (${split.userPubkey.take(6)})"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(sName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            CurrencyUtil.formatAmount(split.amount, exp.currency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                HorizontalDivider()

                // Audit verification badge
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BalancePositive, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verified • ${dateFormat.format(Date(exp.timestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Void / Delete Expense Action
                if (!exp.isVoided) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            showVoidConfirmDialog = exp
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Remove / Void Expense")
                    }
                }
            }
        }
    }

    // Invite Code Dialog
    if (inviteCode != null) {
        AlertDialog(
            onDismissRequest = onDismissInvite,
            title = { Text("Group Invite Link") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val qrBitmap = remember(inviteCode) {
                        try {
                            QrCodeUtil.generateQrBitmap(inviteCode, 400, 400)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Scan to Join Group",
                                modifier = Modifier
                                    .size(180.dp)
                                    .padding(8.dp)
                            )
                        }
                        Text(
                            "Scan QR code or copy invite link",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(
                        "Share this link or QR code with friends so they can join this group securely.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = inviteCode,
                            modifier = Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 3
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            ShareUtil.copySensitive(context, "PeerSplit Invite", inviteCode)
                            Toast.makeText(context, "Invite link copied to clipboard!", Toast.LENGTH_SHORT).show()
                            onDismissInvite()
                        }
                    ) {
                        Text("Copy Link")
                    }
                    Button(
                        onClick = {
                            val qrBitmap = try {
                                QrCodeUtil.generateQrBitmap(inviteCode, 600, 600)
                            } catch (e: Exception) {
                                null
                            }
                            val shareText = "Join my expense sharing group *${groupDetail.group.name}* on PeerSplit!\n\n" +
                                    "Tap this link on your phone: $inviteCode\n\n" +
                                    "Or open PeerSplit and scan/pick the attached QR code."
                            ShareUtil.shareTextWithImage(
                                context = context,
                                text = shareText,
                                image = qrBitmap,
                                imageName = "peersplit_invite_qr",
                                chooserTitle = "Share Group Invite via",
                                subject = "PeerSplit Invite: ${groupDetail.group.name}"
                            )
                            onDismissInvite()
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissInvite) {
                    Text("Close")
                }
            }
        )
    }

    // Delete Group Confirmation Dialog
    if (showDeleteGroupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            title = { Text("Delete '${groupDetail.group.name}'?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete this group? All expenses and balances for this group will be wiped from your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteGroupDialog = false
                        onDeleteGroup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Void Expense Confirmation Dialog
    if (showVoidConfirmDialog != null) {
        val targetExp = showVoidConfirmDialog!!
        AlertDialog(
            onDismissRequest = { showVoidConfirmDialog = null },
            title = { Text("Remove '${targetExp.description}'?") },
            text = {
                Text(
                    "This expense will be reversed and excluded from all member balances.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onVoidExpense(targetExp.id, targetExp.description)
                        Toast.makeText(context, "Expense removed!", Toast.LENGTH_SHORT).show()
                        showVoidConfirmDialog = null
                        selectedExpenseForDetail = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoidConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BudgetProgressCard(
    totalSpent: Double,
    budgetLimit: Double,
    currency: String,
    onEditBudgetClick: () -> Unit
) {
    val ratio = if (budgetLimit > 0) (totalSpent / budgetLimit).toFloat() else 0f
    val progressColor = when {
        ratio >= 1.0f -> BalanceNegative
        ratio >= 0.8f -> Color(0xFFF59E0B) // Amber warning
        else -> BalancePositive
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEditBudgetClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = progressColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("BUDGET TRACKER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (budgetLimit > 0) "${String.format(Locale.US, "%.0f", ratio * 100)}% used" else "Tap to set budget",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (budgetLimit > 0) progressColor else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (budgetLimit > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        "${CurrencyUtil.formatAmount(totalSpent, currency)} spent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "of ${CurrencyUtil.formatAmount(budgetLimit, currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = progressColor,
                )
            } else {
                Text(
                    "Total Spent: ${CurrencyUtil.formatAmount(totalSpent, currency)} • Tap to set a monthly or trip budget target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BalancesAndDebtsTab(
    groupDetail: GroupDetail,
    onSettleUpClick: (payer: String?, payee: String?, amount: Double?) -> Unit = { _, _, _ -> },
    onAddMemberClick: () -> Unit = {},
    onShareInviteClick: () -> Unit = {},
    onEditMemberUpi: (Member) -> Unit = {},
    onSetBudgetClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val totalSpent = groupDetail.expenses.filter { !it.isVoided && it.splitType != "SETTLEMENT" }.sumOf { it.totalAmount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Quick-Start Card when a group only has 1 member
        if (!groupDetail.group.isPersonalSolo && groupDetail.members.size <= 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "👋 Add Friends to Start Splitting!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "You can add friends right now by name (even if they don't have the app yet) or share a group invite link.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onAddMemberClick,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add by Name", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onShareInviteClick,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Invite Link / QR", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Budget Progress Bar Card (Always shown in Solo mode or when budget > 0)
        if (groupDetail.group.isPersonalSolo || groupDetail.group.monthlyBudget > 0) {
            item {
                BudgetProgressCard(
                    totalSpent = totalSpent,
                    budgetLimit = groupDetail.group.monthlyBudget,
                    currency = groupDetail.group.currency,
                    onEditBudgetClick = onSetBudgetClick
                )
            }
        }

        // Simplified Settlements Card
        if (!groupDetail.group.isPersonalSolo) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "SIMPLIFIED SETTLEMENTS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Who Owes Whom",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = { onSettleUpClick(null, null, null) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Settle Up")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (groupDetail.settlements.isEmpty()) {
                            Text(
                                "All balances are squared up! No debts pending.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BalancePositive
                            )
                        } else {
                            val memberMap = groupDetail.members.associateBy { it.pubkey }
                            groupDetail.settlements.forEach { stl ->
                                val debtorMember = memberMap[stl.fromPubkey]
                                val creditorMember = memberMap[stl.toPubkey]
                                val debtor = debtorMember?.displayName ?: "Member (${stl.fromPubkey.take(4)})"
                                val creditor = creditorMember?.displayName ?: "Member (${stl.toPubkey.take(4)})"
                                val isOwedToUser = creditorMember?.isCurrentUser == true
                                val userIsDebtor = debtorMember?.isCurrentUser == true

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                if (userIsDebtor) "You pay $creditor" else if (isOwedToUser) "$debtor pays you" else "$debtor pays $creditor",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                CurrencyUtil.formatAmount(stl.amount, groupDetail.group.currency),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!userIsDebtor) {
                                            OutlinedButton(
                                                onClick = {
                                                    UpiUtil.sharePaymentReminder(
                                                        context = context,
                                                        debtorName = debtor,
                                                        creditorName = creditor,
                                                        groupName = groupDetail.group.name,
                                                        amount = stl.amount,
                                                        currency = groupDetail.group.currency,
                                                        creditorUpiId = creditorMember?.upiId
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.NotificationsActive,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Remind", fontSize = 11.sp)
                                            }
                                        }
                                        Button(
                                            onClick = { onSettleUpClick(stl.fromPubkey, stl.toPubkey, stl.amount) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(if (userIsDebtor) "Pay" else "Settle", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Member Balances Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Group Members (${groupDetail.members.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onAddMemberClick) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Member")
                }
            }
        }

        items(groupDetail.members) { member ->
            MemberBalanceRow(
                member = member,
                currency = groupDetail.group.currency,
                onClick = { onEditMemberUpi(member) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun MemberBalanceRow(
    member: Member,
    currency: String = "INR",
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        NameUtil.getInitials(member.displayName),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!member.upiId.isNullOrBlank()) {
                        Text(
                            "UPI: ${member.upiId} ✎",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            "+ Tap to add UPI ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val bal = member.netBalance
            val balText = CurrencyUtil.formatBalance(bal, currency)
            val balColor = when {
                bal > 0.01 -> BalancePositive
                bal < -0.01 -> BalanceNegative
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Text(
                text = balText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = balColor
            )
        }
    }
}

@Composable
fun ExpensesTab(
    groupDetail: GroupDetail,
    onExpenseClick: (ExpenseItem) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf<ExpenseCategory?>(null) }

    val filteredExpenses = remember(groupDetail.expenses, searchQuery, selectedCategoryFilter) {
        groupDetail.expenses.filter { exp ->
            val matchesQuery = searchQuery.isBlank() ||
                    exp.description.contains(searchQuery, ignoreCase = true) ||
                    exp.note.contains(searchQuery, ignoreCase = true) ||
                    exp.payerName.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategoryFilter == null ||
                    ExpenseCategory.fromName(exp.category) == selectedCategoryFilter
            matchesQuery && matchesCategory
        }
    }

    if (groupDetail.expenses.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No expenses yet! Tap + below to add your first shared expense.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search & Category Filter Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search expenses, notes, or payer...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedCategoryFilter == null,
                            onClick = { selectedCategoryFilter = null },
                            label = { Text("All") }
                        )
                    }
                    items(ExpenseCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategoryFilter == cat,
                            onClick = {
                                selectedCategoryFilter = if (selectedCategoryFilter == cat) null else cat
                            },
                            label = { Text("${cat.iconEmoji} ${cat.displayName}") }
                        )
                    }
                }
            }

            if (filteredExpenses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No expenses match your filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredExpenses) { expense ->
                        ExpenseItemRow(
                            expense = expense,
                            currency = groupDetail.group.currency,
                            onClick = { onExpenseClick(expense) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ExpenseItemRow(
    expense: ExpenseItem,
    currency: String,
    onClick: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val category = ExpenseCategory.fromName(expense.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expense.isVoided) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(category.composeColor().copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(category.iconEmoji, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = expense.description,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (expense.isVoided) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (expense.note.isNotBlank()) {
                        Text(
                            text = "“${expense.note}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${expense.payerName} paid ${CurrencyUtil.formatAmount(expense.totalAmount, currency)} • ${dateFormat.format(Date(expense.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (expense.isVoided) "Cancelled" else "Tap for details",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (expense.isVoided) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (expense.isVoided) {
                    Text(
                        text = "CANCELLED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    val share = expense.userShare
                    val shareText = CurrencyUtil.formatShare(share, currency)
                    val shareColor = when {
                        share > 0.01 -> BalancePositive
                        share < -0.01 -> BalanceNegative
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Text(
                        text = shareText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = shareColor
                    )
                    Text(
                        text = if (expense.splitType == "SETTLEMENT") "settled" else if (share > 0.01) "you lent" else if (share < -0.01) "you borrowed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AnalyticsTab(
    groupDetail: GroupDetail,
    onSetBudgetClick: () -> Unit = {}
) {
    val currency = groupDetail.group.currency
    val expenses = groupDetail.expenses.filter { !it.isVoided && it.splitType != "SETTLEMENT" }
    val totalSpend = expenses.sumOf { it.totalAmount }

    if (expenses.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No spending data available yet.\nAdd expenses to see category breakdown and charts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    val categoryBreakdown = remember(expenses) {
        expenses.groupBy { ExpenseCategory.fromName(it.category) }
            .mapValues { entry -> entry.value.sumOf { it.totalAmount } }
            .toList()
            .sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Budget Progress Bar Card
        item {
            BudgetProgressCard(
                totalSpent = totalSpend,
                budgetLimit = groupDetail.group.monthlyBudget,
                currency = currency,
                onEditBudgetClick = onSetBudgetClick
            )
        }

        // Visual Donut Chart Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "SPENDING BY CATEGORY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            var startAngle = -90f
                            categoryBreakdown.forEach { (cat, catAmount) ->
                                val sweep = if (totalSpend > 0) ((catAmount / totalSpend) * 360f).toFloat() else 0f
                                drawArc(
                                    color = cat.composeColor(),
                                    startAngle = startAngle,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    style = Stroke(width = 28.dp.toPx(), cap = StrokeCap.Butt)
                                )
                                startAngle += sweep
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Total Spent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                CurrencyUtil.formatAmount(totalSpend, currency),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Category Breakdown List
        item {
            Text(
                "Category Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(categoryBreakdown) { (cat, catAmount) ->
            val percentage = if (totalSpend > 0) (catAmount / totalSpend) * 100 else 0.0
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(cat.composeColor().copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(cat.iconEmoji, fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(cat.displayName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${String.format(Locale.US, "%.1f", percentage)}% of total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        CurrencyUtil.formatAmount(catAmount, currency),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
