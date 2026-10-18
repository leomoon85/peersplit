package com.peersplit.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.backup.BackupManager
import com.peersplit.app.network.nostr.RelayPool
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.ui.theme.ThemeManager
import com.peersplit.app.ui.theme.ThemeMode
import com.peersplit.app.util.NameUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.saveable.rememberSaveable
import com.peersplit.app.util.BoundedInput
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = PeerSplitApp.instance.database
    val identity = remember { KeyManager.getOrCreateIdentity(context) }

    var displayName by rememberSaveable { mutableStateOf(identity.displayName) }
    var phone by rememberSaveable { mutableStateOf(KeyManager.getPhone(context)) }
    var upiId by rememberSaveable { mutableStateOf(KeyManager.getUpiId(context)) }
    var showAdvancedInfo by remember { mutableStateOf(false) }
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }

    // Backup state
    var showExportPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var pendingBackupPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Restore state
    var showRestorePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var pendingRestorePath by rememberSaveable { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    // File saver launcher for .peersplit backup export
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val path = pendingBackupPath
        if (path != null) {
            coroutineScope.launch {
                try {
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(uri)
                                ?: error("Unable to open destination file")
                            output.use { out -> File(path).inputStream().use { it.copyTo(out) } }
                        }
                        Toast.makeText(context, "Encrypted backup saved", Toast.LENGTH_LONG).show()
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Toast.makeText(context, "Backup write failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { File(path).delete() }
                    pendingBackupPath = null
                }
            }
        } else if (uri != null) {
            Toast.makeText(context, "Backup preparation expired. Please export again.", Toast.LENGTH_LONG).show()
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) coroutineScope.launch {
            try {
                pendingRestorePath = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open backup")
                    val temporary = File.createTempFile("peersplit-restore-", ".encrypted", context.cacheDir)
                    try {
                        input.use { source -> temporary.outputStream().use { BoundedInput(source, 32L * 1024 * 1024).copyTo(it) } }
                        require(temporary.length() > 0) { "Selected file is empty" }
                        temporary.absolutePath
                    } catch (e: Exception) { temporary.delete(); throw e }
                }
                showRestorePasswordDialog = true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Could not read backup: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile & Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = NameUtil.getInitials(displayName),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "End-to-End Encrypted Account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Display Name
            item {
                Text("Display Name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            KeyManager.updateDisplayName(context, displayName)
                            Toast.makeText(context, "Display name saved!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }

            // Phone Number Setting
            item {
                Text("Phone Number (Optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Used to automatically link you to groups when friends add your number, and for GPay/PhonePe settlements.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        placeholder = { Text("e.g. 9876543210") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            KeyManager.updatePhone(context, phone)
                            phone = KeyManager.getPhone(context)
                            Toast.makeText(context, "Phone number saved!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }

            // Personal UPI ID Setting
            item {
                Text("Personal UPI ID (For Direct Settlements)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Friends in your groups can settle debts directly to this UPI address via GPay/PhonePe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = upiId,
                        onValueChange = { upiId = it },
                        placeholder = { Text("e.g. mobile@upi, yourname@okhdfcbank") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val clean = upiId.trim()
                            if (clean.isNotBlank() && !com.peersplit.app.util.UpiAddress.isValid(clean)) {
                                Toast.makeText(context, "Please enter a valid UPI format (e.g. name@bank)", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            KeyManager.updateUpiId(context, clean)
                            Toast.makeText(context, if (clean.isBlank()) "UPI ID cleared" else "UPI ID saved!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (upiId.isBlank()) "Clear" else "Save")
                    }
                }
                val normPhone = KeyManager.normalizePhone(phone)
                if (normPhone.length == 10) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Quick-fill UPI ID from your phone number:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("@ybl", "@okaxis", "@paytm", "@upi").forEach { suffix ->
                            SuggestionChip(
                                onClick = {
                                    upiId = "$normPhone$suffix"
                                    KeyManager.updateUpiId(context, upiId)
                                    Toast.makeText(context, "UPI ID set to $upiId", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text("$normPhone$suffix", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // App Theme (Light / Dark / System Default)
            item {
                Text("Appearance & Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Select Light, Dark, or follow your Android system preference.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                val currentTheme by ThemeManager.themeMode.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val isSelected = currentTheme == mode
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { ThemeManager.setThemeMode(context, mode) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(mode.iconEmoji, fontSize = 22.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Encrypted Backup & Restore Card
            item {
                Text("Backup & Restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Export a password-protected backup file to migrate to a new phone or keep offline backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = BlockchainAccent.copy(alpha = 0.12f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = BlockchainAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Encrypted Backup File", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = BlockchainAccent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Securely packages all your expense groups, members, payment history, and profile into an encrypted file protected by your password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showExportPasswordDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export Backup")
                            }

                            OutlinedButton(
                                onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore Backup")
                            }
                        }
                    }
                }
            }

            // Advanced & Connection Info (Collapsed by default for regular users)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedInfo = !showAdvancedInfo },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Advanced / Connection Info", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            }
                            Icon(
                                if (showAdvancedInfo) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showAdvancedInfo) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Device Identity Key", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                identity.publicKeyHex,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Public Key", identity.publicKeyHex)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Public key copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Key", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Network Relays (${RelayPool.DEFAULT_RELAYS.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            RelayPool.DEFAULT_RELAYS.forEach { relay ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(relay, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Configured", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = BlockchainAccent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Group records are stored on your phone and encrypted before relay sync. Members can retain shared records; relays see connection metadata. Receipt recognition uses ML Kit, which collects technical diagnostics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = { showPrivacyPolicy = true }) {
                    Text("Privacy policy")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showPrivacyPolicy) {
        val policy = remember(context) {
            context.assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showPrivacyPolicy = false },
            title = { Text("Privacy policy") },
            text = {
                SelectionContainer {
                    Text(
                        policy,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicy = false }) { Text("Close") }
            }
        )
    }

    // Export Password Dialog
    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportPasswordDialog = false
                exportPassword = ""
            },
            title = { Text("Encrypt Vault Backup") },
            text = {
                Column {
                    Text("Enter a password to encrypt your backup with AES-256-GCM. You will need this password to restore your data on another phone.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = exportPassword,
                        enabled = !isExporting,
                        onValueChange = { exportPassword = it },
                        label = { Text("Backup Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (exportPassword.length < 4) {
                            Toast.makeText(context, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
                        } else {
                            coroutineScope.launch {
                                isExporting = true
                                try {
                                    val password = exportPassword
                                    val backupBytes = BackupManager.createEncryptedBackup(context, database, password)
                                    require(backupBytes.size <= 32 * 1024 * 1024) { "Backup exceeds the supported 32 MB limit" }
                                    pendingBackupPath = withContext(Dispatchers.IO) {
                                        File.createTempFile("peersplit-export-", ".encrypted", context.cacheDir).apply { writeBytes(backupBytes) }.absolutePath
                                    }
                                    showExportPasswordDialog = false
                                    exportPassword = ""
                                    // Trigger document creation file picker
                                    createDocLauncher.launch("peersplit_backup_${System.currentTimeMillis()}.peersplit")
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isExporting = false
                                }
                            }
                        }
                    },
                    enabled = exportPassword.isNotBlank() && !isExporting
                ) {
                    Text(if (isExporting) "Encrypting..." else "Save Backup")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportPasswordDialog = false
                    exportPassword = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Restore Password Dialog
    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestorePasswordDialog = false
                restorePassword = ""
                pendingRestorePath?.let { oldPath -> coroutineScope.launch(Dispatchers.IO) { File(oldPath).delete() } }
                pendingRestorePath = null
            },
            title = { Text("Decrypt & Restore Vault") },
            text = {
                Column {
                    Text("Enter the password you used when creating this .peersplit backup file.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = restorePassword,
                        enabled = !isRestoring,
                        onValueChange = { restorePassword = it },
                        label = { Text("Vault Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = pendingRestorePath
                        if (path != null) {
                            coroutineScope.launch {
                                isRestoring = true
                                try {
                                    val success = withContext(Dispatchers.IO) {
                                        val bytes = File(path).inputStream().use { BoundedInput(it, 32L * 1024 * 1024).readBytes() }
                                        BackupManager.restoreFromBackup(context, database, bytes, restorePassword)
                                    }
                                    if (success) {
                                        showRestorePasswordDialog = false
                                        restorePassword = ""
                                        withContext(Dispatchers.IO) { File(path).delete() }
                                        pendingRestorePath = null
                                        Toast.makeText(context, "Vault restored successfully! All groups and keys recovered.", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isRestoring = false
                                }
                            }
                        }
                    },
                    enabled = restorePassword.isNotBlank() && !isRestoring
                ) {
                    Text(if (isRestoring) "Decrypting..." else "Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestorePasswordDialog = false
                    restorePassword = ""
                    pendingRestorePath?.let { oldPath -> coroutineScope.launch(Dispatchers.IO) { File(oldPath).delete() } }
                    pendingRestorePath = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
