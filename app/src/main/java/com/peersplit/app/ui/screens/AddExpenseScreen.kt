package com.peersplit.app.ui.screens

import android.app.DatePickerDialog
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.data.model.ExpenseCategory
import com.peersplit.app.data.model.ExpenseItem
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BalanceNegative
import com.peersplit.app.ui.theme.BalancePositive
import com.peersplit.app.ui.theme.BlockchainAccent
import androidx.compose.runtime.saveable.rememberSaveable
import com.peersplit.app.util.AmountInputValidator
import com.peersplit.app.util.AmountValidationResult
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.ReceiptScannerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SplitMode(val label: String, val icon: String) {
    EQUAL("Equal", "÷"),
    EXACT("Exact", "="),
    PERCENTAGE("Percentage", "%"),
    SHARES("Shares", "⚖️")
}

data class QuickExpensePreset(
    val title: String,
    val emoji: String,
    val category: ExpenseCategory
)

private val defaultRecurringPresets = listOf(
    QuickExpensePreset("Dinner / Food", "🍕", ExpenseCategory.FOOD),
    QuickExpensePreset("Chai & Snacks", "☕", ExpenseCategory.FOOD),
    QuickExpensePreset("Cab / Auto", "🚕", ExpenseCategory.TRANSPORT),
    QuickExpensePreset("Groceries", "🛒", ExpenseCategory.GROCERIES),
    QuickExpensePreset("Fuel / Petrol", "⛽", ExpenseCategory.TRANSPORT),
    QuickExpensePreset("Movie / Outing", "🎬", ExpenseCategory.ENTERTAINMENT),
    QuickExpensePreset("Monthly Rent", "🏠", ExpenseCategory.UTILITIES),
    QuickExpensePreset("Wi-Fi / Electricity", "⚡", ExpenseCategory.UTILITIES)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    groupDetail: GroupDetail?,
    isSubmitting: Boolean,
    editingExpenseId: String? = null,
    onBackClick: () -> Unit,
    onSubmitExpense: (
        description: String,
        amount: Double,
        currency: String,
        payerPubkey: String,
        splits: List<SplitItem>,
        category: String,
        splitType: String,
        note: String,
        payers: List<SplitItem>,
        expenseDateMillis: Long,
        editingExpenseId: String?,
        originalDescription: String?
    ) -> Unit
) {
    if (groupDetail == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (editingExpenseId != null) "Edit Expense" else "Add Expense") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val members = groupDetail.members
    val currency = groupDetail.group.currency
    val sym = CurrencyUtil.getSymbol(currency)

    val editingExpense: ExpenseItem? = remember(groupDetail.expenses, editingExpenseId) {
        if (editingExpenseId.isNullOrBlank()) null
        else groupDetail.expenses.firstOrNull { it.id == editingExpenseId }
    }
    val isEditMode = editingExpense != null

    var description by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(ExpenseCategory.FOOD) }
    var splitMode by rememberSaveable { mutableStateOf(SplitMode.EQUAL) }
    var isOcrScanning by remember { mutableStateOf(false) }
    var hasUserModifiedSplits by rememberSaveable { mutableStateOf(false) }

    // Expense Date (Today, Yesterday, or any past date via Calendar picker)
    var expenseDateMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    val dateFormatter = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }

    val isTodaySelected = remember(expenseDateMillis) {
        val cal1 = Calendar.getInstance().apply { timeInMillis = expenseDateMillis }
        val calNow = Calendar.getInstance()
        cal1.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)
    }
    val isYesterdaySelected = remember(expenseDateMillis) {
        val cal1 = Calendar.getInstance().apply { timeInMillis = expenseDateMillis }
        val calYest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        cal1.get(Calendar.YEAR) == calYest.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == calYest.get(Calendar.DAY_OF_YEAR)
    }

    fun openCalendarPicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = expenseDateMillis }
        val dialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    timeInMillis = expenseDateMillis
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                expenseDateMillis = picked.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    // Single vs Multiple Payers
    var isMultiPayer by rememberSaveable { mutableStateOf(false) }
    var selectedPayerPubkey by rememberSaveable {
        mutableStateOf(members.firstOrNull { it.isCurrentUser }?.pubkey ?: members.firstOrNull()?.pubkey ?: "")
    }
    val multiPayerAmounts = remember { mutableStateMapOf<String, String>() }

    // Split state holders
    val equalSelected = remember { mutableStateMapOf<String, Boolean>() }
    val exactAmounts = remember { mutableStateMapOf<String, String>() }
    val percentageInputs = remember { mutableStateMapOf<String, String>() }
    val sharesInputs = remember { mutableStateMapOf<String, String>() }
    var hasInitializedFields by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(members, editingExpense) {
        if (!hasInitializedFields && members.isNotEmpty()) {
            members.forEach { m ->
                equalSelected[m.pubkey] = true
                exactAmounts[m.pubkey] = ""
                percentageInputs[m.pubkey] = ""
                sharesInputs[m.pubkey] = "1"
                multiPayerAmounts[m.pubkey] = ""
            }

            if (editingExpense != null) {
                description = editingExpense.description
                amountText = String.format(Locale.US, "%.2f", editingExpense.totalAmount)
                note = editingExpense.note
                selectedCategory = ExpenseCategory.fromName(editingExpense.category)
                expenseDateMillis = editingExpense.timestamp

                if (editingExpense.payers.size > 1) {
                    isMultiPayer = true
                    editingExpense.payers.forEach { p ->
                        multiPayerAmounts[p.userPubkey] = String.format(Locale.US, "%.2f", p.amount)
                    }
                } else {
                    isMultiPayer = false
                    selectedPayerPubkey = editingExpense.payerPubkey
                }

                val parsedMode = try {
                    SplitMode.valueOf(editingExpense.splitType)
                } catch (_: Exception) {
                    SplitMode.EQUAL
                }
                splitMode = parsedMode

                val splitMap = editingExpense.splits.associate { it.userPubkey to it.amount }
                when (parsedMode) {
                    SplitMode.EQUAL -> {
                        members.forEach { m ->
                            equalSelected[m.pubkey] = (splitMap[m.pubkey] ?: 0.0) > 0.0
                        }
                    }
                    SplitMode.EXACT -> {
                        members.forEach { m ->
                            val amt = splitMap[m.pubkey] ?: 0.0
                            exactAmounts[m.pubkey] = if (amt > 0) String.format(Locale.US, "%.2f", amt) else ""
                        }
                    }
                    SplitMode.PERCENTAGE -> {
                        val total = editingExpense.totalAmount.coerceAtLeast(0.01)
                        members.forEach { m ->
                            val amt = splitMap[m.pubkey] ?: 0.0
                            val pct = (amt / total) * 100.0
                            percentageInputs[m.pubkey] = if (pct > 0) String.format(Locale.US, "%.1f", pct) else ""
                        }
                    }
                    SplitMode.SHARES -> {
                        val minNonZero = splitMap.values.filter { it > 0.0 }.minOrNull() ?: 1.0
                        members.forEach { m ->
                            val amt = splitMap[m.pubkey] ?: 0.0
                            val sh = if (amt > 0) kotlin.math.round(amt / minNonZero).toInt().coerceAtLeast(1) else 0
                            sharesInputs[m.pubkey] = sh.toString()
                        }
                    }
                }
            }
            hasInitializedFields = true
        }
    }

    // Recent recurring expenses from this group
    val recentCustomTemplates = remember(groupDetail.expenses) {
        groupDetail.expenses
            .filter { !it.isVoided && it.splitType != "SETTLEMENT" }
            .distinctBy { it.description.lowercase() }
            .take(4)
    }

    // Receipt OCR Photo Picker (Executed off Main thread on Dispatchers.IO)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                isOcrScanning = true
                val parsed = ReceiptScannerUtil.scanReceipt(context, uri)
                withContext(Dispatchers.Main) {
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
    }

    val amountValidation = remember(amountText) {
        AmountInputValidator.validate(amountText)
    }
    val totalAmount = (amountValidation as? AmountValidationResult.Valid)?.majorAmount ?: 0.0
    val isAmountValid = amountValidation.isValid
    val amountFieldError = (amountValidation as? AmountValidationResult.Invalid)?.error

    // Multiple Payers validation
    val (computedPayers, isValidPayer, payerStatusText) = remember(
        totalAmount, isAmountValid, isMultiPayer, selectedPayerPubkey, multiPayerAmounts.toMap()
    ) {
        if (!isAmountValid) {
            Triple(emptyList<SplitItem>(), false, amountFieldError ?: "Enter total bill amount first")
        } else if (!isMultiPayer) {
            Triple(emptyList<SplitItem>(), selectedPayerPubkey.isNotBlank(), "Single payer")
        } else {
            try {
                var sumPaid = 0.0
                val list = mutableListOf<SplitItem>()
                for (m in members) {
                    val paid = multiPayerAmounts[m.pubkey]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                    sumPaid += paid
                    if (paid > 0) {
                        list.add(SplitItem(userPubkey = m.pubkey, amount = paid))
                    }
                }
                val diff = totalAmount - sumPaid
                val valid = totalAmount > 0 && kotlin.math.abs(diff) < 0.05 && list.isNotEmpty()
                val finalList = if (valid) {
                    val totalMinor = MoneyMath.toMinorUnits(totalAmount)
                    val rawMinor = list.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
                    val normalized = MoneyMath.normalizeSplitsToExactTotal(totalMinor, rawMinor)
                    list.map { SplitItem(userPubkey = it.userPubkey, amount = MoneyMath.toMajorDouble(normalized[it.userPubkey] ?: 0L)) }
                } else {
                    list
                }
                val status = if (valid) "Paid total matches ($sym${String.format(Locale.US, "%.2f", sumPaid)})"
                else if (diff > 0) "Remaining to pay: $sym${String.format(Locale.US, "%.2f", diff)}"
                else "Exceeds bill by: $sym${String.format(Locale.US, "%.2f", -diff)}"
                Triple(finalList, valid, status)
            } catch (_: Exception) {
                Triple(emptyList(), false, "Invalid payer amounts")
            }
        }
    }

    // Computed splits validation & generation
    val (computedSplits, isValidSplit, splitStatusText) = remember(
        totalAmount, isAmountValid, splitMode, equalSelected.toMap(), exactAmounts.toMap(), percentageInputs.toMap(), sharesInputs.toMap()
    ) {
        if (!isAmountValid) {
            return@remember Triple(emptyList<SplitItem>(), false, amountFieldError ?: "Enter an amount")
        }

        try {
            when (splitMode) {
                SplitMode.EQUAL -> {
                    val active = equalSelected.filter { it.value }.keys.toList()
                    if (active.isEmpty()) {
                        Triple(emptyList(), false, "Select at least 1 member")
                    } else {
                        val totalMinor = MoneyMath.toMinorUnits(totalAmount)
                        val eqSplits = MoneyMath.allocateEqualSplits(totalMinor, active)
                        val list = active.map { pubkey ->
                            SplitItem(userPubkey = pubkey, amount = MoneyMath.toMajorDouble(eqSplits[pubkey] ?: 0L))
                        }
                        val perPersonAvg = totalAmount / active.size
                        Triple(list, true, "${CurrencyUtil.formatAmount(perPersonAvg, currency)} / person")
                    }
                }
                SplitMode.EXACT -> {
                    var sum = 0.0
                    val list = mutableListOf<SplitItem>()
                    for (m in members) {
                        val a = exactAmounts[m.pubkey]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                        sum += a
                        if (a > 0) list.add(SplitItem(userPubkey = m.pubkey, amount = a))
                    }
                    val diff = totalAmount - sum
                    val valid = kotlin.math.abs(diff) < 0.05 && list.isNotEmpty()
                    val finalList = if (valid) {
                        val totalMinor = MoneyMath.toMinorUnits(totalAmount)
                        val rawMinor = list.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
                        val normalized = MoneyMath.normalizeSplitsToExactTotal(totalMinor, rawMinor)
                        list.map { SplitItem(userPubkey = it.userPubkey, amount = MoneyMath.toMajorDouble(normalized[it.userPubkey] ?: 0L)) }
                    } else {
                        list
                    }
                    val status = if (valid) "Exact amounts match total ($sym${String.format(Locale.US, "%.2f", sum)})"
                    else if (diff > 0) "Remaining to assign: $sym${String.format(Locale.US, "%.2f", diff)}"
                    else "Exceeds total by: $sym${String.format(Locale.US, "%.2f", -diff)}"
                    Triple(finalList, valid, status)
                }
                SplitMode.PERCENTAGE -> {
                    var sumPct = 0.0
                    val list = mutableListOf<SplitItem>()
                    for (m in members) {
                        val p = percentageInputs[m.pubkey]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                        sumPct += p
                        if (p > 0) {
                            val computed = (totalAmount * p) / 100.0
                            list.add(SplitItem(userPubkey = m.pubkey, amount = computed))
                        }
                    }
                    val diff = 100.0 - sumPct
                    val valid = kotlin.math.abs(diff) < 0.5 && list.isNotEmpty()
                    val finalList = if (valid) {
                        val totalMinor = MoneyMath.toMinorUnits(totalAmount)
                        val rawMinor = list.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
                        val normalized = MoneyMath.normalizeSplitsToExactTotal(totalMinor, rawMinor)
                        list.map { SplitItem(userPubkey = it.userPubkey, amount = MoneyMath.toMajorDouble(normalized[it.userPubkey] ?: 0L)) }
                    } else {
                        list
                    }
                    val status = if (valid) "100% assigned"
                    else if (diff > 0) "Remaining: ${String.format(Locale.US, "%.1f", diff)}%"
                    else "Exceeds by: ${String.format(Locale.US, "%.1f", -diff)}%"
                    Triple(finalList, valid, status)
                }
                SplitMode.SHARES -> {
                    var totalShares = 0.0
                    for (m in members) {
                        val s = sharesInputs[m.pubkey]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                        totalShares += s
                    }
                    if (totalShares <= 0) {
                        Triple(emptyList(), false, "At least 1 share required")
                    } else {
                        val list = members.mapNotNull { m ->
                            val s = sharesInputs[m.pubkey]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                            if (s > 0) {
                                val computed = (totalAmount * s) / totalShares
                                SplitItem(userPubkey = m.pubkey, amount = computed)
                            } else null
                        }
                        val totalMinor = MoneyMath.toMinorUnits(totalAmount)
                        val rawMinor = list.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) }
                        val normalized = MoneyMath.normalizeSplitsToExactTotal(totalMinor, rawMinor)
                        val finalList = list.map { SplitItem(userPubkey = it.userPubkey, amount = MoneyMath.toMajorDouble(normalized[it.userPubkey] ?: 0L)) }
                        Triple(finalList, true, "${String.format(Locale.US, "%.0f", totalShares)} total shares")
                    }
                }
            }
        } catch (_: Exception) {
            Triple(emptyList(), false, "Invalid split configuration")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Expense" else "Add Expense", fontWeight = FontWeight.Bold) },
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
            if (!isEditMode) {
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
                                Text("Auto-Scan Bill / Receipt Photo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = BlockchainAccent)
                                Text("Pick a receipt photo to auto-fill title & amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isOcrScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BlockchainAccent)
                            }
                        }
                    }
                }

                // Quick Recurring / Preset Templates Bar
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick Recurring & Templates", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Show group's recent expenses as 1-tap repeat chips (fills title, amount, category)
                        items(recentCustomTemplates) { prev ->
                            AssistChip(
                                onClick = {
                                    description = prev.description
                                    amountText = String.format(Locale.US, "%.2f", prev.totalAmount)
                                    selectedCategory = ExpenseCategory.fromName(prev.category)
                                },
                                label = {
                                    Text("↻ ${prev.description} (${CurrencyUtil.formatAmount(prev.totalAmount, currency)})")
                                }
                            )
                        }
                        // Standard presets
                        items(defaultRecurringPresets) { preset ->
                            AssistChip(
                                onClick = {
                                    description = preset.title
                                    selectedCategory = preset.category
                                },
                                label = { Text("${preset.emoji} ${preset.title}") }
                            )
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
                    onValueChange = {
                        amountText = it
                        hasUserModifiedSplits = true
                    },
                    label = { Text("Total Amount ($currency)") },
                    prefix = { Text("$sym ") },
                    placeholder = { Text("0.00") },
                    isError = amountFieldError != null,
                    supportingText = amountFieldError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // Expense Date Selector (Today, Yesterday, or Pick any Past Date)
            item {
                Text("Expense Date", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = isTodaySelected,
                        onClick = { expenseDateMillis = System.currentTimeMillis() },
                        label = { Text("Today") }
                    )
                    FilterChip(
                        selected = isYesterdaySelected,
                        onClick = {
                            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                            expenseDateMillis = cal.timeInMillis
                        },
                        label = { Text("Yesterday") }
                    )
                    FilterChip(
                        selected = !isTodaySelected && !isYesterdaySelected,
                        onClick = { openCalendarPicker() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = "Pick Past Date",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                if (!isTodaySelected && !isYesterdaySelected) {
                                    dateFormatter.format(Date(expenseDateMillis))
                                } else {
                                    "Pick Past Date"
                                }
                            )
                        }
                    )
                }
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

            // Paid By Section (Single or Multiple Payers)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Paid By", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (members.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Multiple Payers", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = isMultiPayer,
                                onCheckedChange = {
                                    isMultiPayer = it
                                    hasUserModifiedSplits = true
                                }
                            )
                        }
                    }
                }
                if (isMultiPayer) {
                    Text(
                        text = payerStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isValidPayer) BalancePositive else BalanceNegative,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!isMultiPayer) {
                items(members) { member ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPayerPubkey = member.pubkey
                                hasUserModifiedSplits = true
                            },
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
            } else {
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
                            value = multiPayerAmounts[member.pubkey] ?: "",
                            onValueChange = {
                                multiPayerAmounts[member.pubkey] = it
                                hasUserModifiedSplits = true
                            },
                            placeholder = { Text("0.00") },
                            prefix = { Text("$sym ") },
                            modifier = Modifier.width(135.dp),
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
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
                            onClick = {
                                splitMode = mode
                                hasUserModifiedSplits = true
                            },
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
                                .clickable {
                                    equalSelected[member.pubkey] = !isChecked
                                    hasUserModifiedSplits = true
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    equalSelected[member.pubkey] = it
                                    hasUserModifiedSplits = true
                                }
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
                                onValueChange = {
                                    exactAmounts[member.pubkey] = it
                                    hasUserModifiedSplits = true
                                },
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
                                onValueChange = {
                                    percentageInputs[member.pubkey] = it
                                    hasUserModifiedSplits = true
                                },
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
                                onValueChange = {
                                    sharesInputs[member.pubkey] = it
                                    hasUserModifiedSplits = true
                                },
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
                val primaryPayer = if (isMultiPayer) {
                    computedPayers.maxByOrNull { it.amount }?.userPubkey ?: selectedPayerPubkey
                } else {
                    selectedPayerPubkey
                }
                val finalSplits = if (isEditMode && editingExpense != null && !hasUserModifiedSplits &&
                    totalAmount == editingExpense.totalAmount && splitMode.name == editingExpense.splitType
                ) {
                    editingExpense.splits
                } else {
                    computedSplits
                }
                val finalPayers = if (isEditMode && editingExpense != null && !hasUserModifiedSplits &&
                    totalAmount == editingExpense.totalAmount && isMultiPayer == (editingExpense.payers.size > 1)
                ) {
                    editingExpense.payers
                } else {
                    computedPayers
                }
                Button(
                    onClick = {
                        onSubmitExpense(
                            description.trim(),
                            totalAmount,
                            currency,
                            primaryPayer,
                            finalSplits,
                            selectedCategory.name,
                            splitMode.name,
                            note.trim(),
                            finalPayers,
                            expenseDateMillis,
                            editingExpense?.id,
                            editingExpense?.description
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = description.isNotBlank() && isAmountValid && isValidPayer && isValidSplit && !isSubmitting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            if (isEditMode) Icons.Default.Edit else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isEditMode) "Update Expense" else "Save Expense")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
