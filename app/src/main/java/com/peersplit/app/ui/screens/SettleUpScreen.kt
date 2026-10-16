package com.peersplit.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.UpiUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleUpScreen(
    groupDetail: GroupDetail?,
    onBackClick: () -> Unit,
    onRecordSettlement: (payerPubkey: String, payeePubkey: String, amount: Double, currency: String) -> Unit
) {
    if (groupDetail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val members = groupDetail.members
    val currency = groupDetail.group.currency

    var selectedPayer by remember { mutableStateOf(members.firstOrNull { it.netBalance < 0 }?.pubkey ?: members.firstOrNull()?.pubkey ?: "") }
    var selectedPayee by remember { mutableStateOf(members.firstOrNull { it.netBalance > 0 }?.pubkey ?: members.getOrNull(1)?.pubkey ?: "") }
    var amountText by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    // Pre-populate recommended settlement if available
    LaunchedEffect(groupDetail.settlements) {
        if (groupDetail.settlements.isNotEmpty()) {
            val rec = groupDetail.settlements.first()
            selectedPayer = rec.fromPubkey
            selectedPayee = rec.toPubkey
            amountText = String.format(java.util.Locale.US, "%.2f", rec.amount)
        }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val payeeMember = members.find { it.pubkey == selectedPayee }
    var payeeUpiId by remember(selectedPayee) { mutableStateOf(payeeMember?.upiId ?: "") }
    var showQrDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Settlement", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Who is paying?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(members) { member ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPayer = member.pubkey },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPayer == member.pubkey) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selectedPayer == member.pubkey) FontWeight.Bold else FontWeight.Normal
                        )
                        if (selectedPayer == member.pubkey) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Who is receiving?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(members.filter { it.pubkey != selectedPayer }) { member ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPayee = member.pubkey },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPayee == member.pubkey) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selectedPayee == member.pubkey) FontWeight.Bold else FontWeight.Normal
                            )
                            if (!member.upiId.isNullOrBlank()) {
                                Text(
                                    "UPI: ${member.upiId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (selectedPayee == member.pubkey) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Settlement Amount ($currency)") },
                    prefix = { Text(CurrencyUtil.getSymbol(currency) + " ") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // UPI Deep Link & QR Payment Options (Enabled for INR or any payee with a UPI ID)
            if (currency.equals("INR", ignoreCase = true) || payeeUpiId.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Payment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Instant UPI Settlement", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = payeeUpiId,
                                onValueChange = { payeeUpiId = it },
                                label = { Text("Payee UPI ID") },
                                placeholder = { Text("e.g. mobile@upi, name@okhdfcbank") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (payeeUpiId.isBlank()) {
                                            Toast.makeText(context, "Please enter a valid Payee UPI ID", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val payeeName = payeeMember?.displayName ?: "PeerSplit Friend"
                                            UpiUtil.launchUpiPayment(context, payeeUpiId, payeeName, amount)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = amount > 0,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pay with UPI")
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (payeeUpiId.isBlank()) {
                                            Toast.makeText(context, "Please enter a Payee UPI ID first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showQrDialog = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = amount > 0,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("UPI QR Code")
                                }
                            }
                        }
                    }
                }
            }

            // Mint Settlement Block
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        isSubmitting = true
                        onRecordSettlement(selectedPayer, selectedPayee, amount, currency)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = amount > 0 && selectedPayer != selectedPayee && !isSubmitting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Handshake, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mint Settlement Block")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Dynamic UPI QR Code Dialog
    if (showQrDialog) {
        val payeeName = payeeMember?.displayName ?: "Friend"
        val qrBitmap: Bitmap? = remember(payeeUpiId, amount) {
            UpiUtil.generateUpiQrBitmap(payeeUpiId, payeeName, amount, "PeerSplit Settlement", 360)
        }

        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Scan to Pay $payeeName") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrBitmap != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "UPI Dynamic QR Code",
                                modifier = Modifier.size(200.dp).padding(10.dp)
                            )
                        }
                    }
                    Text(
                        "${CurrencyUtil.formatAmount(amount, currency)} to $payeeUpiId",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Scan with Google Pay, PhonePe, Paytm, or any BHIM UPI app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showQrDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}
