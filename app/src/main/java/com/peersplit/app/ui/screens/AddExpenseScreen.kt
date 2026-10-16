package com.peersplit.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.data.model.ExpenseCategory
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BalanceNegative
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.ReceiptScannerUtil
import kotlinx.coroutines.launch
import java.util.Locale

enum class SplitMode(val label: String, val icon: String) {
    EQUAL("Equal", "÷"),
    EXACT("Exact", "="),
    PERCENTAGE("Percentage", "%"),
    SHARES("Shares", "⚖️")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    groupDetail: GroupDetail?,
    isSubmitting: Boolean,
    onBackClick: () -> Unit,
    onSubmitExpense: (
        description: String,
        amount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String,
        splitType: String,
        note: String
    ) -> Unit
) {
    if (groupDetail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val members = groupDetail.members
    val currency = groupDetail.group.currency
    val sym = CurrencyUtil.getSymbol(currency)

    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ExpenseCategory.FOOD) }
    var splitMode by remember { mutableStateOf(SplitMode.EQUAL) }
    var isOcrScanning by remember { mutableStateOf(false) }

    // Default payer is current user or first member
    var selectedPayerPubkey by remember {
        mutableStateOf(members.firstOrNull { it.isCurrentUser }?.pubkey ?: members.firstOrNull()?.pubkey ?: "")
    }

    // Split state holders
    val equalSelected = remember { mutableStateMapOf<String, Boolean>() }
    val exactAmounts = remember { mutableStateMapOf<String, String>() }
    val percentageInputs = remember { mutableStateMapOf<String, String>() }
    val sharesInputs = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(members) {
        members.forEach { m ->
            equalSelected[m.pubkey] = true
            exactAmounts[m.pubkey] = ""
            percentageInputs[m.pubkey] = ""
            sharesInputs[m.pubkey] = "1"
        }
    }

    // Receipt OCR Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isOcrScanning = true
                val parsed = ReceiptScannerUtil.scanReceipt(context, uri)
                isOcrScanning = false
                if (parsed != null) {
                    if (description.isBlank() && parsed.suggestedTitle.isNotBlank()) {
                        description = parsed.suggestedTitle
                    }
                    if (parsed.suggestedAmount != null && parsed.suggestedAmount > 0) {
                        amountText = String.format(Locale.US, "%.2f", parsed.suggestedAmount)
                    }
                    Toast.makeText(context, "Receipt scanned successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not detect receipt totals. Please type manually.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val totalAmount = amountText.toDoubleOrNull() ?: 0.0

    // Computed splits validation & generation
    val (computedSplits, isValidSplit, splitStatusText) = remember(
        totalAmount, splitMode, equalSelected.toMap(), exactAmounts.toMap(), percentageInputs.toMap(), sharesInputs.toMap()
    ) {
        if (totalAmount <= 0) return@remember Triple(emptyList<SplitItem>(), false, "Enter an amount")

        when (splitMode) {
            SplitMode.EQUAL -> {
                val active = equalSelected.filter { it.value }.keys.toList()
                if (active.isEmpty()) {
                    Triple(emptyList(), false, "Select at least 1 member")
                } else {
                    val perPerson = totalAmount / active.size
                    val list = active.map { SplitItem(userPubkey = it, amount = perPerson) }
                    Triple(list, true, "${CurrencyUtil.formatAmount(perPerson, currency)} / person")
                }
            }
            SplitMode.EXACT -> {
                var sum = 0.0
                val list = mutableListOf<SplitItem>()
                for (m in members) {
                    val a = exactAmounts[m.pubkey]?.toDoubleOrNull() ?: 0.0
                    sum += a
                    if (a > 0) list.add(SplitItem(userPubkey = m.pubkey, amount = a))
                }
                val diff = totalAmount - sum
                val valid = kotlin.math.abs(diff) < 0.05 && list.isNotEmpty()
                val status = if (valid) "Exact amounts match total ($sym${String.format(Locale.US, "%.2f", sum)})"
                else if (diff > 0) "Remaining to assign: $sym${String.format(Locale.US, "%.2f", diff)}"
                else "Exceeds total by: $sym${String.format(Locale.US, "%.2f", -diff)}"
                Triple(list, valid, status)
            }
            SplitMode.PERCENTAGE -> {
                var sumPct = 0.0
                val list = mutableListOf<SplitItem>()
                for (m in members) {
                    val p = percentageInputs[m.pubkey]?.toDoubleOrNull() ?: 0.0
                    sumPct += p
                    if (p > 0) {
                        val computed = (totalAmount * p) / 100.0
                        list.add(SplitItem(userPubkey = m.pubkey, amount = computed))
                    }
                }
                val diff = 100.0 - sumPct
                val valid = kotlin.math.abs(diff) < 0.5 && list.isNotEmpty()
                val status = if (valid) "100% assigned"
                else if (diff > 0) "Remaining: ${String.format(Locale.US, "%.1f", diff)}%"
                else "Exceeds by: ${String.format(Locale.US, "%.1f", -diff)}%"
                Triple(list, valid, status)
            }
            SplitMode.SHARES -> {
                var totalShares = 0.0
                for (m in members) {
                    val s = sharesInputs[m.pubkey]?.toDoubleOrNull() ?: 0.0
                    totalShares += s
                }
                if (totalShares <= 0) {
                    Triple(emptyList(), false, "At least 1 share required")
                } else {
                    val list = members.mapNotNull { m ->
                        val s = sharesInputs[m.pubkey]?.toDoubleOrNull() ?: 0.0
                        if (s > 0) {
                            val computed = (totalAmount * s) / totalShares
                            SplitItem(userPubkey = m.pubkey, amount = computed)
                        } else null
                    }
                    Triple(list, true, "${String.format(Locale.US, "%.0f", totalShares)} total shares")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Expense", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = "Scan Receipt", tint = MaterialTheme.colorScheme.primary)
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
            // Receipt OCR Quick Action Banner
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { photoPickerLauncher.launch("image/*") },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = BlockchainAccent.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = BlockchainAccent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scan Receipt / Bill (On-Device OCR)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = BlockchainAccent)
                            Text("Snap or pick a receipt to auto-fill title & amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isOcrScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BlockchainAccent)
                        }
                    }
                }
            }

            // Description & Amount
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Dinner, Fuel, Airbnb") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Total Amount ($currency)") },
                    prefix = { Text("$sym ") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // Category Picker
            item {
                Text("Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ExpenseCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.iconEmoji} ${cat.displayName}") }
                        )
                    }
                }
            }

            // Paid By Section
            item {
                Text("Paid By", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

            // Split Mode Selector
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Split Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = splitStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isValidSplit) BalancePositive else BalanceNegative,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SplitMode.entries.forEach { mode ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = splitMode == mode,
                            onClick = { splitMode = mode },
                            label = {
                                Text(
                                    "${mode.icon} ${mode.label}",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            // Split Details per mode
            when (splitMode) {
                SplitMode.EQUAL -> {
                    items(members) { member ->
                        val isChecked = equalSelected[member.pubkey] ?: false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { equalSelected[member.pubkey] = !isChecked }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { equalSelected[member.pubkey] = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (isChecked && totalAmount > 0) {
                                val activeCount = equalSelected.values.count { it }
                                if (activeCount > 0) {
                                    Text(
                                        CurrencyUtil.formatAmount(totalAmount / activeCount, currency),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
                SplitMode.EXACT -> {
                    items(members) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = exactAmounts[member.pubkey] ?: "",
                                onValueChange = { exactAmounts[member.pubkey] = it },
                                placeholder = { Text("0.00") },
                                prefix = { Text("$sym ") },
                                modifier = Modifier.width(130.dp),
                                shape = RoundedCornerShape(8.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
                SplitMode.PERCENTAGE -> {
                    items(members) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val p = percentageInputs[member.pubkey]?.toDoubleOrNull() ?: 0.0
                                if (p > 0 && totalAmount > 0) {
                                    Text(
                                        "= ${CurrencyUtil.formatAmount((totalAmount * p) / 100.0, currency)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = percentageInputs[member.pubkey] ?: "",
                                onValueChange = { percentageInputs[member.pubkey] = it },
                                placeholder = { Text("0") },
                                suffix = { Text("%") },
                                modifier = Modifier.width(110.dp),
                                shape = RoundedCornerShape(8.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
                SplitMode.SHARES -> {
                    items(members) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (member.isCurrentUser) "${member.displayName} (You)" else member.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val s = sharesInputs[member.pubkey]?.toDoubleOrNull() ?: 0.0
                                val totalShares = members.sumOf { sharesInputs[it.pubkey]?.toDoubleOrNull() ?: 0.0 }
                                if (s > 0 && totalShares > 0 && totalAmount > 0) {
                                    Text(
                                        "= ${CurrencyUtil.formatAmount((totalAmount * s) / totalShares, currency)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = sharesInputs[member.pubkey] ?: "",
                                onValueChange = { sharesInputs[member.pubkey] = it },
                                placeholder = { Text("1") },
                                suffix = { Text("share") },
                                modifier = Modifier.width(115.dp),
                                shape = RoundedCornerShape(8.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Note (Optional)
            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Optional)") },
                    placeholder = { Text("e.g. Paid cash, tip included") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Submit Button
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSubmitExpense(
                            description.trim(),
                            totalAmount,
                            currency,
                            selectedPayerPubkey,
                            computedSplits,
                            selectedCategory.name,
                            splitMode.name,
                            note.trim()
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = description.isNotBlank() && totalAmount > 0 && isValidSplit && !isSubmitting,
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
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
