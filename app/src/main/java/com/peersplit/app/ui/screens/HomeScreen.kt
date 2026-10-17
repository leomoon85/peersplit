package com.peersplit.app.ui.screens

import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.peersplit.app.ui.theme.ThemeManager
import com.peersplit.app.ui.theme.ThemeMode
import com.peersplit.app.ui.viewmodel.HomeUiState
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.NameUtil
import com.peersplit.app.util.RewardProfile
import com.peersplit.app.util.RewardsEngine
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
    val currentIdentity = remember { KeyManager.getOrCreateIdentity(context) }
    var showWelcomeDialog by remember {
        mutableStateOf(currentIdentity.displayName.isBlank() || currentIdentity.displayName == "My Phone")
    }
    var welcomeNameInput by remember {
        mutableStateOf(if (currentIdentity.displayName == "My Phone") "" else currentIdentity.displayName)
    }
    var welcomePhoneInput by remember { mutableStateOf(currentIdentity.phone ?: "") }
    var welcomeUpiInput by remember { mutableStateOf(currentIdentity.upiId ?: "") }

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var groupToDelete by remember { mutableStateOf<Group?>(null) }
    var showArchivedSection by remember { mutableStateOf(false) }
    var showRewardsSheet by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var isManualSyncing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val rewardsVersion by RewardsEngine.version.collectAsState()
    val currentThemeMode by ThemeManager.themeMode.collectAsState()

    val allGroupsCount = uiState.visibleGroups.size + uiState.archivedGroups.size + uiState.secretGroups.size
    val totalBlocksCount = remember(uiState.visibleGroups, uiState.archivedGroups, uiState.secretGroups) {
        (uiState.visibleGroups + uiState.archivedGroups + uiState.secretGroups).sumOf { it.blockCount.toInt() }
    }
    val allSquaredUp = remember(uiState.visibleGroups) {
        uiState.visibleGroups.isNotEmpty() && uiState.visibleGroups.all { kotlin.math.abs(it.userNetBalance) < 0.01 }
    }
    val rewardProfile = remember(rewardsVersion, allGroupsCount, totalBlocksCount, allSquaredUp) {
        RewardsEngine.getRewardProfile(
            context = context,
            groupsCount = allGroupsCount,
            totalBlocksCount = totalBlocksCount,
            allBalancesSquaredUp = allSquaredUp
        )
    }

    // Start-up enter animation trigger & daily streak touch
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        RewardsEngine.touchDailyVisit(context)
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
                    val identityVersion by KeyManager.identityVersion.collectAsState()
                    val userInitials = remember(identityVersion) {
                        NameUtil.getInitials(KeyManager.getOrCreateIdentity(context).displayName)
                    }

                    // Gamified XP & Streak Pill in TopBar
                    Surface(
                        onClick = { showRewardsSheet = true },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔥${rewardProfile.streakDays}d • ⚡${rewardProfile.totalXp} XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // 1-Tap Sync Now button
                    val syncRotation by animateFloatAsState(
                        targetValue = if (isManualSyncing) 360f else 0f,
                        animationSpec = if (isManualSyncing) infiniteRepeatable(tween(750, easing = LinearEasing)) else tween(300),
                        label = "syncRotation"
                    )
                    IconButton(
                        onClick = {
                            if (!isManualSyncing) {
                                isManualSyncing = true
                                coroutineScope.launch {
                                    com.peersplit.app.PeerSplitApp.instance.syncManager.syncAllGroupsNow(broadcastChains = true)
                                    kotlinx.coroutines.delay(800)
                                    isManualSyncing = false
                                    Toast.makeText(context, "All groups synced with peers ✓", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Sync All Groups",
                            modifier = Modifier.graphicsLayer { rotationZ = syncRotation },
                            tint = if (isManualSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 1-Tap Theme Mode Selector (Default: System Theme, switchable to Light or Dark)
                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
                            Text(currentThemeMode.iconEmoji, fontSize = 16.sp)
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${mode.iconEmoji}  ${mode.title}",
                                            fontWeight = if (currentThemeMode == mode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        ThemeManager.setThemeMode(context, mode)
                                        showThemeMenu = false
                                        Toast.makeText(context, "Theme set to ${mode.title}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.secretGroups.isNotEmpty() || uiState.isSecretVaultUnlocked) {
                        IconButton(onClick = {
                            if (uiState.isSecretVaultUnlocked) {
                                onLockVault()
                                Toast.makeText(context, "Private Vault Locked", Toast.LENGTH_SHORT).show()
                            } else {
                                showPinDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = if (uiState.isSecretVaultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = if (uiState.isSecretVaultUnlocked) "Lock Private Vault" else "Unlock Private Vault",
                                tint = if (uiState.isSecretVaultUnlocked) BlockchainAccent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

                item {
                    SplitPointsProgressCard(
                        rewardProfile = rewardProfile,
                        onClick = { showRewardsSheet = true }
                    )
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
                    HomeBottomEngagementSection(
                        rewardProfile = rewardProfile,
                        totalGroupsCount = allGroupsCount,
                        totalBlocksCount = totalBlocksCount,
                        onCreateGroupClick = onCreateGroupClick,
                        onJoinGroupClick = onJoinGroupClick,
                        onProfileClick = onProfileClick,
                        onOpenRewardsClick = { showRewardsSheet = true },
                        onSyncNowClick = {
                            if (!isManualSyncing) {
                                isManualSyncing = true
                                coroutineScope.launch {
                                    com.peersplit.app.PeerSplitApp.instance.syncManager.syncAllGroupsNow()
                                    delay(800)
                                    isManualSyncing = false
                                    Toast.makeText(context, "All groups synced with peers ✓", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
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

    // First Launch Welcome Onboarding Dialog
    if (showWelcomeDialog) {
        AlertDialog(
            onDismissRequest = { /* force setup or require skip */ },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Welcome to PeerSplit")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Set your display name so friends can recognize who you are in shared expense groups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = welcomeNameInput,
                        onValueChange = { welcomeNameInput = it },
                        label = { Text("Your Name") },
                        placeholder = { Text("e.g. Rajesh Balakrishnan") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = welcomePhoneInput,
                        onValueChange = { welcomePhoneInput = it },
                        label = { Text("Phone Number (Optional)") },
                        placeholder = { Text("10-digit mobile for member matching & UPI") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = welcomeUpiInput,
                        onValueChange = { welcomeUpiInput = it },
                        label = { Text("UPI ID (Optional for Direct Settlements)") },
                        placeholder = { Text("e.g. yourname@okhdfcbank") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    val normWelcomePhone = KeyManager.normalizePhone(welcomePhoneInput)
                    if (normWelcomePhone.length == 10) {
                        Text(
                            "Quick-fill UPI ID from phone:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("@ybl", "@okaxis", "@paytm", "@upi").forEach { suffix ->
                                SuggestionChip(
                                    onClick = { welcomeUpiInput = "$normWelcomePhone$suffix" },
                                    label = { Text(suffix, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = if (welcomeNameInput.isNotBlank()) welcomeNameInput.trim() else "My Phone"
                        KeyManager.updateDisplayName(context, finalName)
                        if (welcomePhoneInput.isNotBlank()) {
                            KeyManager.updatePhone(context, welcomePhoneInput.trim())
                        }
                        if (welcomeUpiInput.isNotBlank()) {
                            KeyManager.updateUpiId(context, welcomeUpiInput.trim())
                        }
                        showWelcomeDialog = false
                        Toast.makeText(context, "Profile saved!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Get Started")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWelcomeDialog = false
                    }
                ) {
                    Text("Later")
                }
            }
        )
    }

    if (showRewardsSheet) {
        RewardsBottomSheet(
            rewardProfile = rewardProfile,
            onDismiss = { showRewardsSheet = false }
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

@Composable
fun SplitPointsProgressCard(
    rewardProfile: RewardProfile,
    onClick: () -> Unit
) {
    val unlockedCount = rewardProfile.unlockedBadgesCount
    val totalBadges = rewardProfile.badges.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    BlockchainAccent.copy(alpha = 0.35f)
                )
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(rewardProfile.levelEmoji, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Lv.${rewardProfile.level} • ${rewardProfile.levelTitle}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "🔥 ${rewardProfile.streakDays}d",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "$unlockedCount/$totalBadges Badges Unlocked • Tap for rewards",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "⚡ ${rewardProfile.totalXp} XP",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Next: ${rewardProfile.nextLevelXp} XP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { rewardProfile.levelProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsBottomSheet(
    rewardProfile: RewardProfile,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rewardProfile.levelEmoji, fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "Level ${rewardProfile.level}: ${rewardProfile.levelTitle}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "⚡ ${rewardProfile.totalXp} SplitPoints XP  •  🔥 ${rewardProfile.streakDays} Day Streak",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { rewardProfile.levelProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${(rewardProfile.nextLevelXp - rewardProfile.totalXp).coerceAtLeast(0)} XP to next level • Earn +25 XP per split, +45 XP for paying the bill, +100 XP for settling debts!",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "ACHIEVEMENT BADGES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rewardProfile.badges) { badge ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (badge.isUnlocked) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (badge.isUnlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (badge.isUnlocked) badge.emoji else "🔒",
                                    fontSize = 22.sp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        badge.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (badge.isUnlocked) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${badge.description} (${badge.progressText})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = if (badge.isUnlocked) "Unlocked ✓" else "+${badge.xpReward} XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (badge.isUnlocked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeBottomEngagementSection(
    rewardProfile: RewardProfile,
    totalGroupsCount: Int,
    totalBlocksCount: Int,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit,
    onProfileClick: () -> Unit,
    onOpenRewardsClick: () -> Unit,
    onSyncNowClick: () -> Unit
) {
    val nextBadge = rewardProfile.badges.firstOrNull { !it.isUnlocked }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(2.dp))

        // 1. Quick Actions Grid (Fills lower half with instant 1-tap actions)
        Text(
            "Quick Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomeQuickActionTile(
                emoji = "➕",
                title = "New Group",
                subtitle = "Trips, Rent & Friends",
                modifier = Modifier.weight(1f),
                onClick = onCreateGroupClick
            )
            HomeQuickActionTile(
                emoji = "📷",
                title = "Scan / Join",
                subtitle = "QR Code or Link",
                modifier = Modifier.weight(1f),
                onClick = onJoinGroupClick
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomeQuickActionTile(
                emoji = "🔄",
                title = "Sync Now",
                subtitle = "Refresh All Phones",
                modifier = Modifier.weight(1f),
                onClick = onSyncNowClick
            )
            HomeQuickActionTile(
                emoji = "💳",
                title = "UPI & Profile",
                subtitle = "Phone & Backup",
                modifier = Modifier.weight(1f),
                onClick = onProfileClick
            )
        }

        // 2. Next Achievement Goal & Badge Strip Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRewardsClick),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "YOUR ACHIEVEMENTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "View All →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rewardProfile.badges, key = { it.id }) { badge ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (badge.isUnlocked) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (badge.isUnlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (badge.isUnlocked) badge.emoji else "🔒",
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = badge.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (badge.isUnlocked) FontWeight.Bold else FontWeight.Normal,
                                    color = if (badge.isUnlocked) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (nextBadge != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(nextBadge.emoji, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Next Goal: ${nextBadge.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${nextBadge.description} (${nextBadge.progressText})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "+${nextBadge.xpReward} XP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 3. P2P Security & Network Status Pill Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "🔒 End-to-End Encrypted P2P Ledger",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "$totalGroupsCount Groups • $totalBlocksCount Verified Blocks • Zero Cloud Tracking",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "100% Free",
                    style = MaterialTheme.typography.labelSmall,
                    color = BalancePositive,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun HomeQuickActionTile(
    emoji: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


