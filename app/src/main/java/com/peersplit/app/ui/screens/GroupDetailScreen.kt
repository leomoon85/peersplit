package com.peersplit.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.peersplit.app.crypto.QrCodeUtil
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.blockchain.SettlementSuggestion
import com.peersplit.app.data.model.ExpenseItem
import com.peersplit.app.data.model.Member
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BalanceNegative
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil
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
    onSettleUpClick: () -> Unit,
    onAuditLedgerClick: () -> Unit,
    onGenerateInvite: () -> Unit,
    onDismissInvite: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    if (groupDetail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(groupDetail.group.name, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (groupDetail.isLedgerIntact) BalancePositive else BalanceNegative,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (groupDetail.isLedgerIntact) "Ledger Validated" else "Tamper Warning",
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
                    IconButton(onClick = onAuditLedgerClick) {
                        Icon(Icons.Default.Analytics, contentDescription = "Audit Blockchain Ledger")
                    }
                    IconButton(onClick = onGenerateInvite) {
                        Icon(Icons.Default.Share, contentDescription = "Invite Members")
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
                    text = { Text("Debts & Balances") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Expense Ledger (${groupDetail.expenses.size})") }
                )
            }

            when (selectedTab) {
                0 -> BalancesAndDebtsTab(
                    groupDetail = groupDetail,
                    onSettleUpClick = onSettleUpClick
                )
                1 -> ExpensesTab(groupDetail = groupDetail)
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
                        "Share this link with friends to let them join this serverless group. It contains the peer group ID and the AES-256 group encryption key.",
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
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("PeerSplit Invite", inviteCode)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Invite link copied to clipboard!", Toast.LENGTH_SHORT).show()
                        onDismissInvite()
                    }
                ) {
                    Text("Copy Link")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissInvite) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun BalancesAndDebtsTab(
    groupDetail: GroupDetail,
    onSettleUpClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Simplified Settlements Card
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
                                "Min-Cash-Flow Plan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = onSettleUpClick,
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
                        val memberMap = groupDetail.members.associate { it.pubkey to it.displayName }
                        groupDetail.settlements.forEach { stl ->
                            val debtor = memberMap[stl.fromPubkey] ?: "Member (${stl.fromPubkey.take(4)})"
                            val creditor = memberMap[stl.toPubkey] ?: "Member (${stl.toPubkey.take(4)})"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "$debtor pays $creditor",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    CurrencyUtil.formatAmount(stl.amount, groupDetail.group.currency),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Member Balances Header
        item {
            Text(
                "Group Members (${groupDetail.members.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(groupDetail.members) { member ->
            MemberBalanceRow(member, groupDetail.group.currency)
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun MemberBalanceRow(member: Member, currency: String = "USD") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        member.displayName.take(1).uppercase(),
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
                    Text(
                        "${member.pubkey.take(6)}...${member.pubkey.takeLast(4)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
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
fun ExpensesTab(groupDetail: GroupDetail) {
    if (groupDetail.expenses.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No expenses recorded yet. Tap + to add the first expense to this micro-blockchain ledger.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(groupDetail.expenses) { expense ->
                ExpenseItemRow(expense, groupDetail.group.currency)
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun ExpenseItemRow(expense: ExpenseItem, currency: String) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${expense.payerName} paid ${CurrencyUtil.formatAmount(expense.totalAmount, currency)} • ${dateFormat.format(Date(expense.timestamp))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "⛓️ Block #${expense.blockIndex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BlockchainAccent,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(horizontalAlignment = Alignment.End) {
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
                    text = if (share > 0.01) "you lent" else if (share < -0.01) "you borrowed" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
