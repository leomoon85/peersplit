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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onUnlockVault: suspend (String) -> Boolean = { true },
    onLockVault: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentIdentity = remember { KeyManager.getOrCreateIdentity(context) }
    val introPreferences = remember(context) {
        context.getSharedPreferences("peersplit_onboarding", android.content.Context.MODE_PRIVATE)
    }
    var showWelcomeDialog by rememberSaveable {
        mutableStateOf(!introPreferences.getBoolean("intro_overlay_seen_v2", false))
    }
    var welcomeNameInput by rememberSaveable {
        mutableStateOf(if (currentIdentity.displayName == "My Phone") "" else currentIdentity.displayName)
    }
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }

    fun finishIntro(saveName: Boolean) {
        if (saveName && welcomeNameInput.isNotBlank()) {
            KeyManager.updateDisplayName(context, welcomeNameInput.trim())
        }
        introPreferences.edit().putBoolean("intro_overlay_seen_v2", true).apply()
        showWelcomeDialog = false
    }

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var groupToDelete by remember { mutableStateOf<Group?>(null) }
    var showArchivedSection by remember { mutableStateOf(false) }
    var showRewardsSheet by remember { mutableStateOf(false) }
    // theme menu moved to Profile
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

    val totalPendingSync = remember(uiState.visibleGroups, uiState.archivedGroups, uiState.secretGroups) {
        (uiState.visibleGroups + uiState.archivedGroups + uiState.secretGroups).sumOf { it.pendingSyncCount }
    }
    val totalFailedSync = remember(uiState.visibleGroups, uiState.archivedGroups, uiState.secretGroups) {
        (uiState.visibleGroups + uiState.archivedGroups + uiState.secretGroups).sumOf { it.failedSyncCount }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
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
                            Text(
                                text = "PeerSplit",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
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
                                    .background(if (uiState.isOnline) BalancePositive else MaterialTheme.colorScheme.outline)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isOnline) "Online" else "Offline · changes saved on this phone",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    val identityVersion by KeyManager.identityVersion.collectAsState()
                    val userInitials = remember(identityVersion) {
                        NameUtil.getInitials(KeyManager.getOrCreateIdentity(context).displayName)
                    }

                    // 1-Tap Sync Now button with live pending animation & badge
                    val infiniteTransition = rememberInfiniteTransition(label = "syncPendingInfinite")
                    val pendingPulseRotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
                        label = "pendingRotation"
                    )
                    val manualSyncRotation by animateFloatAsState(
                        targetValue = if (isManualSyncing) 360f else 0f,
                        animationSpec = if (isManualSyncing) infiniteRepeatable(tween(750, easing = LinearEasing)) else tween(300),
                        label = "manualSyncRotation"
                    )
                    val activeRotation = if (isManualSyncing) manualSyncRotation else if (totalPendingSync > 0) pendingPulseRotation else 0f

                    BadgedBox(
                        badge = {
                            if (totalFailedSync > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("!", fontWeight = FontWeight.Bold)
                                }
                            } else if (totalPendingSync > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("$totalPendingSync")
                                }
                            }
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (!isManualSyncing) {
                                    isManualSyncing = true
                                    coroutineScope.launch {
                                        try {
                                            com.peersplit.app.PeerSplitApp.instance.syncManager.syncAllGroupsNow(broadcastChains = true)
                                            Toast.makeText(context, "Sync requested. Updates will be delivered when connected.", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Could not request sync. Try again.", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isManualSyncing = false
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = if (totalPendingSync > 0) "Sync – $totalPendingSync pending" else "Sync All Groups",
                                modifier = Modifier.graphicsLayer { rotationZ = activeRotation },
                                tint = if (isManualSyncing || totalPendingSync > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .semantics { contentDescription = "Profile: ${currentIdentity.displayName.ifBlank { "User" }}" }
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
                    TextButton(onClick = { showHowItWorks = true }) {
                        Text("🔒 100% Local on Your Phone • Tap for Visual Guide ↗")
                    }
                    TotalBalanceCard(uiState)
                }

                item {
                    TextButton(onClick = { showRewardsSheet = true }) {
                        Text("SplitPoints and activity")
                    }
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
                                    try {
                                        com.peersplit.app.PeerSplitApp.instance.syncManager.syncAllGroupsNow()
                                        Toast.makeText(context, "Sync requested. Updates will be delivered when connected.", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not request sync. Try again.", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isManualSyncing = false
                                    }
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
                        coroutineScope.launch {
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

    if (showWelcomeDialog) {
        PeerSplitGuideDialog(
            showNameField = true,
            displayName = welcomeNameInput,
            onNameChange = { welcomeNameInput = it },
            onDone = { finishIntro(saveName = true) },
            onSkip = { finishIntro(saveName = false) }
        )
    }
    if (showHowItWorks) {
        PeerSplitGuideDialog(onDone = { showHowItWorks = false })
    }

    if (showRewardsSheet) {
        RewardsBottomSheet(
            rewardProfile = rewardProfile,
            onDismiss = { showRewardsSheet = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

            if (uiState.otherCurrencyBalances.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "OTHER CURRENCIES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.otherCurrencyBalances.forEach { (curr, amt) ->
                        val sign = if (amt > 0.01) "+" else if (amt < -0.01) "-" else ""
                        val color = if (amt > 0.01) BalancePositive else if (amt < -0.01) BalanceNegative else MaterialTheme.colorScheme.onSurface
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "$curr: $sign${CurrencyUtil.formatAmount(if (amt < 0) -amt else amt, curr)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
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
                val pending = group.pendingSyncCount
                val failed = group.failedSyncCount
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = when {
                                isArchived -> MaterialTheme.colorScheme.surfaceVariant
                                failed > 0 -> MaterialTheme.colorScheme.errorContainer
                                pending > 0 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        when {
                            isArchived -> Icons.Default.Archive
                            failed > 0 -> Icons.Default.Warning
                            pending > 0 -> Icons.Default.Sync
                            else -> Icons.Default.CheckCircle
                        },
                        contentDescription = if (failed > 0) "Upload failed" else if (pending > 0) "Pending sync" else "No outgoing updates queued",
                        tint = when {
                            isArchived -> MaterialTheme.colorScheme.onSurfaceVariant
                            failed > 0 -> MaterialTheme.colorScheme.error
                            pending > 0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            isArchived -> "Archived"
                            failed > 0 -> "$failed failed uploads"
                            pending > 0 -> "$pending pending"
                            else -> "No pending uploads"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isArchived -> MaterialTheme.colorScheme.onSurfaceVariant
                            failed > 0 -> MaterialTheme.colorScheme.error
                            pending > 0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
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


