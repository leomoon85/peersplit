package com.peersplit.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = PeerSplitApp.instance.database
    val identity = remember { KeyManager.getOrCreateIdentity(context) }

    var displayName by remember { mutableStateOf(identity.displayName) }
    var upiId by remember { mutableStateOf(KeyManager.getUpiId(context)) }

    // Backup state
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var pendingBackupBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Restore state
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var pendingRestoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    // File saver launcher for .peersplit backup export
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null && pendingBackupBytes != null) {
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output: OutputStream ->
                        output.write(pendingBackupBytes!!)
                        output.flush()
                    }
                    Toast.makeText(context, "Encrypted backup saved successfully!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to write file: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    pendingBackupBytes = null
                }
            }
        }
    }

    // File picker launcher for .peersplit restore
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                        input.readBytes()
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        pendingRestoreBytes = bytes
                        showRestorePasswordDialog = true
                    } else {
                        Toast.makeText(context, "Selected file is empty", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to read backup: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity & Security", fontWeight = FontWeight.Bold) },
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
                            "Hardware-Bound Ed25519 Identity",
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
                            KeyManager.updateUpiId(context, upiId)
                            Toast.makeText(context, "UPI ID saved!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
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

            // Device Public Key
            item {
                Text("Cryptographic Public Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            identity.publicKeyHex,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Public Key", identity.publicKeyHex)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Public key copied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy PubKey")
                        }
                    }
                }
            }

            // Encrypted Vault Backup & Restore Card
            item {
                Text("Vault Backup & Migration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Export an AES-256 encrypted .peersplit vault file to migrate to a new phone or keep offline backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = BlockchainAccent.copy(alpha = 0.12f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = BlockchainAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Encrypted One-File Vault", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = BlockchainAccent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Includes your Ed25519 identity, private keys, secret groups, and full tamper-proof blockchain ledgers. Protected by your master password.",
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
                                Text("Restore Vault")
                            }
                        }
                    }
                }
            }

            // Decentralized Nostr Relays
            item {
                Text("Connected Decentralized Relays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            items(RelayPool.DEFAULT_RELAYS) { relay ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            relay,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(BalancePositive)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Connected",
                                style = MaterialTheme.typography.labelSmall,
                                color = BalancePositive
                            )
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
                            "PeerSplit is completely serverless. Your private key, financial ledgers, and secret vaults never leave your phone unencrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
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
                                    val backupBytes = BackupManager.createEncryptedBackup(context, database, exportPassword)
                                    pendingBackupBytes = backupBytes
                                    showExportPasswordDialog = false
                                    exportPassword = ""
                                    // Trigger document creation file picker
                                    createDocLauncher.launch("peersplit_backup_${System.currentTimeMillis()}.peersplit")
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
                pendingRestoreBytes = null
            },
            title = { Text("Decrypt & Restore Vault") },
            text = {
                Column {
                    Text("Enter the password you used when creating this .peersplit backup file.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = restorePassword,
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
                        val bytes = pendingRestoreBytes
                        if (bytes != null) {
                            coroutineScope.launch {
                                isRestoring = true
                                try {
                                    val success = BackupManager.restoreFromBackup(context, database, bytes, restorePassword)
                                    if (success) {
                                        showRestorePasswordDialog = false
                                        restorePassword = ""
                                        pendingRestoreBytes = null
                                        Toast.makeText(context, "Vault restored successfully! All groups and keys recovered.", Toast.LENGTH_LONG).show()
                                    }
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
                    pendingRestoreBytes = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
