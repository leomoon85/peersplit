package com.peersplit.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.peersplit.app.util.ExternalPaymentApps
import com.peersplit.app.util.PaymentApp
import com.peersplit.app.util.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExternalPaymentCard(amount: Double, currency: String, canCopyAmount: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<PaymentApp>>(emptyList()) }
    var showMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var lookupFailed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Pay externally", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Use your preferred payment app", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Enter the recipient and amount in the app you choose. PeerSplit sends no payment details with this action.",
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Button(
                        enabled = !isLoading,
                        onClick = {
                            scope.launch {
                                isLoading = true
                                lookupFailed = false
                                try {
                                    apps = withContext(Dispatchers.IO) { ExternalPaymentApps.installedApps(context) }
                                    showMenu = true
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                                } catch (_: Exception) {
                                    apps = emptyList()
                                    lookupFailed = true
                                } finally { isLoading = false }
                            }
                        }
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Checking apps…" else "Select app")
                        if (!isLoading) Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false },
                        modifier = Modifier.heightIn(max = 320.dp)) {
                        if (apps.isEmpty()) {
                            DropdownMenuItem(text = { Text("No supported apps found") }, onClick = {}, enabled = false)
                        }
                        apps.forEach { app ->
                            DropdownMenuItem(
                                text = { Text(app.name) },
                                leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    if (!ExternalPaymentApps.open(context, app.packageName)) {
                                        Toast.makeText(context, "Could not open this app. Open it from your home screen.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }
                OutlinedButton(enabled = canCopyAmount, onClick = {
                    ShareUtil.copySensitive(context, "Settlement amount", String.format(Locale.US, "%.2f", amount))
                    Toast.makeText(context, "Amount copied ($currency). Paste it only where you intend to pay.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy amount")
                }
            }
            if (lookupFailed) Text("Could not check installed apps. Retry or open your preferred app manually.",
                color = MaterialTheme.colorScheme.error)
            Text("App not listed? Open it from your home screen. Available transfer features depend on the app and region.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("After paying, return and record the settlement. Opening an app does not mark this debt as paid.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
