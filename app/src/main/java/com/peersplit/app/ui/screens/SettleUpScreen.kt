package com.peersplit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.util.CurrencyUtil

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

    val members = groupDetail.members
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Settlement", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                        Text(
                            text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selectedPayee == member.pubkey) FontWeight.Bold else FontWeight.Normal
                        )
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
                    label = { Text("Settlement Amount (${groupDetail.group.currency})") },
                    prefix = { Text(CurrencyUtil.getSymbol(groupDetail.group.currency) + " ") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isSubmitting = true
                        onRecordSettlement(selectedPayer, selectedPayee, amount, groupDetail.group.currency)
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
            }
        }
    }
}
