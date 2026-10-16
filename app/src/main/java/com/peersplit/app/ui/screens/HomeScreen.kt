package com.peersplit.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.model.Group
import com.peersplit.app.ui.theme.BalanceNegative
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.ui.viewmodel.HomeUiState
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.NameUtil
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onGroupClick: (String) -> Unit,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit,
    onProfileClick: () -> Unit,
    onArchiveGroup: (groupId: String, isArchived: Boolean) -> Unit = { _, _ -> },
    onDeleteGroup: (groupId: String) -> Unit = {},
    onUnlockVault: (String) -> Boolean = { true },
    onLockVault: () -> Unit = {}
) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var groupToDelete by remember { mutableStateOf<Group?>(null) }
    var showArchivedSection by remember { mutableStateOf(false) }

    // Start-up enter animation trigger
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (uiState.secretGroups.isNotEmpty()) {
                                    if (uiState.isSecretVaultUnlocked) {
                                        onLockVault()
                                        Toast.makeText(context, "Private Vault Locked", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showPinDialog = true
                                    }
                                } else {
                                    Toast.makeText(context, "No private groups created yet", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PeerSplit", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            if (uiState.isSecretVaultUnlocked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.LockOpen, contentDescription = "Vault Unlocked", tint = BlockchainAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(BalancePositive)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Private & Synced",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    val userInitials = remember {
                        NameUtil.getInitials(KeyManager.getOrCreateIdentity(context).displayName)
                    }
                    if (uiState.isSecretVaultUnlocked) {
                        IconButton(onClick = {
                            onLockVault()
                            Toast.makeText(context, "Private Vault Locked", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock Private Vault", tint = BlockchainAccent)
                        }
                    }
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userInitials,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = onJoinGroupClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Join via QR / Code")
                }
                Spacer(modifier = Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onCreateGroupClick,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Group") }
                )
            }
        }
    ) { padding ->
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(500)) + slideInVertically(
                initialOffsetY = { 60 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    TotalBalanceCard(uiState)
                }

                // Secret Vault Section (Visible only when unlocked)
                if (uiState.isSecretVaultUnlocked && uiState.secretGroups.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BlockchainAccent.copy(alpha = 0.12f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Visibility, contentDescription = null, tint = BlockchainAccent, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("PRIVATE VAULT (UNLOCKED)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = BlockchainAccent)
                                    }
                                    TextButton(onClick = onLockVault) {
                                        Text("Lock", color = BlockchainAccent)
                                    }
                                }
                            }
                        }
                    }

                    items(uiState.secretGroups, key = { "sec_${it.id}" }) { secretGroup ->
                        GroupCard(
                            group = secretGroup,
                            isSecretBadge = true,
                            onClick = { onGroupClick(secretGroup.id) },
                            onArchive = { onArchiveGroup(secretGroup.id, !secretGroup.isArchived) },
                            onDelete = { groupToDelete = secretGroup }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Your Groups",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${uiState.visibleGroups.size} Active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (uiState.visibleGroups.isEmpty()) {
                    item {
                        EmptyGroupsCard(onCreateGroupClick, onJoinGroupClick)
                    }
                } else {
                    items(uiState.visibleGroups, key = { it.id }) { group ->
                        GroupCard(
                            group = group,
                            isSecretBadge = false,
                            onClick = { onGroupClick(group.id) },
                            onArchive = { onArchiveGroup(group.id, true) },
                            onDelete = { groupToDelete = group }
                        )
                    }
                }

                // Archived Groups Section
                if (uiState.archivedGroups.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showArchivedSection = !showArchivedSection },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Archived Groups (${uiState.archivedGroups.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    if (showArchivedSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (showArchivedSection) {
                        items(uiState.archivedGroups, key = { "arch_${it.id}" }) { archivedGroup ->
                            GroupCard(
                                group = archivedGroup,
                                isSecretBadge = false,
                                isArchived = true,
                                onClick = { onGroupClick(archivedGroup.id) },
                                onArchive = { onArchiveGroup(archivedGroup.id, false) },
                                onDelete = { groupToDelete = archivedGroup }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // Discreet Unlock PIN Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                enteredPin = ""
                pinError = false
            },
            title = { Text("Unlock Private Vault") },
            text = {
                Column {
                    Text("Enter your 4-6 digit PIN to reveal hidden groups.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 6) {
                                enteredPin = it
                                pinError = false
                            }
                        },
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = pinError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text("Incorrect PIN. Please try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = onUnlockVault(enteredPin)
                        if (success) {
                            showPinDialog = false
                            enteredPin = ""
                            pinError = false
                            Toast.makeText(context, "Private Vault Unlocked!", Toast.LENGTH_SHORT).show()
                        } else {
                            pinError = true
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    enteredPin = ""
                    pinError = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (groupToDelete != null) {
        val g = groupToDelete!!
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("Delete '${g.name}'?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete this group? All expenses and balances for this group will be wiped from your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(g.id)
                        Toast.makeText(context, "'${g.name}' deleted", Toast.LENGTH_SHORT).show()
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TotalBalanceCard(uiState: HomeUiState) {
    val balance = uiState.totalNetBalance
    val balanceColor = when {
        balance > 0.01 -> BalancePositive
        balance < -0.01 -> BalanceNegative
        else -> MaterialTheme.colorScheme.onSurface
    }
    val balanceSign = if (balance > 0.01) "+" else if (balance < -0.01) "-" else ""
    val currSym = CurrencyUtil.getSymbol(uiState.primaryCurrency)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                )
            )
        )
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                "TOTAL BALANCE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "$balanceSign$currSym${String.format(Locale.US, "%.2f", if (balance < 0) -balance else balance)}",
                style = MaterialTheme.typography.headlineLarge,
                color = balanceColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "You are owed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "+${CurrencyUtil.formatAmount(uiState.totalOwedToUser, uiState.primaryCurrency)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = BalancePositive,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "You owe",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "-${CurrencyUtil.formatAmount(uiState.totalUserOwes, uiState.primaryCurrency)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = BalanceNegative,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun GroupCard(
    group: Group,
    isSecretBadge: Boolean = false,
    isArchived: Boolean = false,
    onClick: () -> Unit,
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    val bal = group.userNetBalance
    val balText = CurrencyUtil.formatBalance(bal, group.currency)
    val balColor = when {
        bal > 0.01 -> BalancePositive
        bal < -0.01 -> BalanceNegative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Subtle translucent background tinting
    val cardBg = if (isArchived) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (isArchived) 0.04f else 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (isSecretBadge) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Private", tint = BlockchainAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = balText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = balColor
                    )
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Group actions",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isArchived) "Unarchive Group" else "Archive Group") },
                                onClick = {
                                    showMenu = false
                                    onArchive()
                                },
                                leadingIcon = {
                                    Icon(if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Group", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (group.isPersonalSolo) "Personal Expenses • ${group.currency}" else "${group.memberCount} members • ${group.currency}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Clean status indicator (simplified, non-technical)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Synced",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isArchived) "Archived" else "Up to date",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyGroupsCard(onCreateGroup: () -> Unit, onJoinGroup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.GroupAdd,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "No Groups Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Create a group to easily split expenses with friends or roommates without ads or subscription fees.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateGroup) {
                    Text("Create Group")
                }
                OutlinedButton(onClick = onJoinGroup) {
                    Text("Join Group")
                }
            }
        }
    }
}
