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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.util.CurrencyUtil
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    groupDetail: GroupDetail?,
    isSubmitting: Boolean,
    onBackClick: () -> Unit,
    onSubmitExpense: (description: String, amount: Double, currency: String, payerPubkey: String, splits: List<String>) -> Unit
) {
    if (groupDetail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    val members = groupDetail.members

    // Default payer is the current user or first member
    var selectedPayerPubkey by remember {
        mutableStateOf(members.firstOrNull { it.isCurrentUser }?.pubkey ?: members.firstOrNull()?.pubkey ?: "")
    }

    // Default split among all members
    val selectedMembers = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(members) {
        members.forEach { selectedMembers[it.pubkey] = true }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val activeMemberCount = selectedMembers.values.count { it }
    val splitAmountPerPerson = if (activeMemberCount > 0 && amount > 0) amount / activeMemberCount else 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Expense", fontWeight = FontWeight.Bold) },
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
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Dinner, Groceries, Hotel") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (${groupDetail.group.currency})") },
                    prefix = { Text(CurrencyUtil.getSymbol(groupDetail.group.currency) + " ") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            item {
                Text(
                    "Paid By",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(members) { member ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPayerPubkey = member.pubkey },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPayerPubkey == member.pubkey) {
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
                            fontWeight = if (selectedPayerPubkey == member.pubkey) FontWeight.Bold else FontWeight.Normal
                        )
                        if (selectedPayerPubkey == member.pubkey) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Split Equally Between",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (splitAmountPerPerson > 0) {
                        Text(
                            "${CurrencyUtil.formatAmount(splitAmountPerPerson, groupDetail.group.currency)}/person",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            items(members) { member ->
                val isChecked = selectedMembers[member.pubkey] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMembers[member.pubkey] = !isChecked }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { selectedMembers[member.pubkey] = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val activePubkeys = selectedMembers.filter { it.value }.keys.toList()
                        onSubmitExpense(
                            description,
                            amount,
                            groupDetail.group.currency,
                            selectedPayerPubkey,
                            activePubkeys
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = description.isNotBlank() && amount > 0 && activeMemberCount > 0 && !isSubmitting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign & Mint Expense Block")
                    }
                }
            }
        }
    }
}
