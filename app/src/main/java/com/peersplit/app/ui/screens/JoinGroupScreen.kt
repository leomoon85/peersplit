package com.peersplit.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.peersplit.app.PeerSplitApp
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.crypto.QrCodeUtil
import com.peersplit.app.ui.navigation.PendingInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JoinGroupScreen(
    onBackClick: () -> Unit,
    onJoinGroup: (inviteUri: String, claimedGhostPubkey: String?, onFailed: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { PeerSplitApp.instance.repository }
    val currentIdentity = remember { KeyManager.getOrCreateIdentity(context) }

    var inviteCode by rememberSaveable { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }

    val preview = remember(inviteCode) {
        if (inviteCode.isNotBlank()) repository.parseInvitePreview(inviteCode) else null
    }

    // Default to matching ghost member by 10-digit phone number first, then by displayName, otherwise default to Join as New ("NONE")
    var selectedGhostPubkey by remember(preview) {
        val ghosts = preview?.unclaimedGhostMembers.orEmpty()
        val normMyPhone = KeyManager.normalizePhone(currentIdentity.phone)
        val byPhone = if (normMyPhone.length == 10) {
            ghosts.firstOrNull { KeyManager.normalizePhone(it.phone) == normMyPhone }
        } else null
        val matching = byPhone ?: ghosts.firstOrNull {
            it.displayName.trim().equals(currentIdentity.displayName.trim(), ignoreCase = true)
        }
        mutableStateOf<String?>(matching?.pubkey ?: "NONE")
    }

    // Prefill from a tapped peersplit:// link (also when this screen is already open)
    val pending by PendingInvite.code.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            inviteCode = it
            PendingInvite.code.value = null
            Toast.makeText(context, "Invite link received — tap Join to continue", Toast.LENGTH_SHORT).show()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    val decoded = bitmap?.let { QrCodeUtil.decodeQrBitmap(it) }
                    withContext(Dispatchers.Main) {
                        if (!decoded.isNullOrBlank()) {
                            inviteCode = decoded.trim()
                            Toast.makeText(context, "QR code recognized!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No QR code detected in selected image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join Group", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Enter Group Invite Link",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = { Text("Invite link or code") },
                placeholder = { Text("peersplit://...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(12.dp),
                maxLines = 5
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        if (!text.isNullOrBlank()) {
                            inviteCode = Regex("peersplit://\\S+").find(text)?.value ?: text
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste Link")
                }
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("QR from Gallery")
                }
            }

            // Live Group Preview & 1-Tap Member Claiming Card
            if (preview != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
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
                            text = "Group: ${preview.groupName} (${preview.currency})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Created by ${preview.creatorDisplayName}" +
                                if (preview.expenseCount > 0) " • ${preview.expenseCount} expense(s) ready to sync" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (preview.unclaimedGhostMembers.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                text = "Who are you in this group?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${preview.creatorDisplayName} already added member(s) to this group. Select your name so your expenses and balances link automatically:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                preview.unclaimedGhostMembers.forEach { ghost ->
                                    val isSelected = selectedGhostPubkey == ghost.pubkey
                                    val labelText = if (!ghost.phone.isNullOrBlank()) {
                                        "I am ${ghost.displayName} (${ghost.phone})"
                                    } else {
                                        "I am ${ghost.displayName}"
                                    }
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedGhostPubkey = ghost.pubkey },
                                        label = { Text(labelText) },
                                        leadingIcon = {
                                            Icon(
                                                if (isSelected) Icons.Default.Check else Icons.Default.Person,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                FilterChip(
                                    selected = selectedGhostPubkey == "NONE",
                                    onClick = { selectedGhostPubkey = "NONE" },
                                    label = { Text("Join as new (${currentIdentity.displayName})") },
                                    leadingIcon = {
                                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Ask a friend who is already in the group to share their invite link or QR code. Once joined, all existing members and expenses will appear immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (inviteCode.isNotBlank() && !isJoining) {
                        isJoining = true
                        onJoinGroup(inviteCode.trim(), selectedGhostPubkey) { isJoining = false }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = inviteCode.isNotBlank() && !isJoining,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isJoining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    val targetGhost = preview?.unclaimedGhostMembers?.firstOrNull { it.pubkey == selectedGhostPubkey }
                    Text(
                        if (targetGhost != null) "Join Group as ${targetGhost.displayName}"
                        else "Join Group"
                    )
                }
            }
        }
    }
}
