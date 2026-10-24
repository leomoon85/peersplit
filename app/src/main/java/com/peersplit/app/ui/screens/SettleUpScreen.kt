package com.peersplit.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.peersplit.app.util.UpiAddress
import com.peersplit.app.util.AmountInputValidator
import com.peersplit.app.util.AmountValidationResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.repository.GroupDetail
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.ShareUtil
import com.peersplit.app.util.UpiUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleUpScreen(
    groupDetail: GroupDetail?,
    isLoading: Boolean = true,
    loadError: String? = null,
    onRetryLoad: () -> Unit = {},
    initialPayer: String? = null,
    initialPayee: String? = null,
    initialAmount: Double? = null,
    onBackClick: () -> Unit,
    isSubmitting: Boolean = false,
    submissionError: String? = null,
    onUpdatePayeeUpi: suspend (payeePubkey: String, upiId: String) -> Unit = { _, _ -> },
    onRecordSettlement: (payerPubkey: String, payeePubkey: String, amount: Double, currency: String) -> Unit
) {
    if (groupDetail == null) {
        GroupUnavailableScreen("Record Settlement", isLoading, loadError, onBackClick, onRetryLoad)
        return
    }

    val context = LocalContext.current
    val members = groupDetail.members
    val currency = groupDetail.group.currency

    var selectedPayer by rememberSaveable {
        mutableStateOf(
            initialPayer?.takeIf { p -> members.any { it.pubkey == p } }
                ?: members.firstOrNull { it.netBalance < 0 }?.pubkey
                ?: members.firstOrNull()?.pubkey
                ?: ""
        )
    }
    var selectedPayee by rememberSaveable {
        mutableStateOf(
            initialPayee?.takeIf { p -> members.any { it.pubkey == p } }
                ?: members.firstOrNull { it.netBalance > 0 }?.pubkey
                ?: members.getOrNull(1)?.pubkey
                ?: ""
        )
    }
    var amountText by rememberSaveable {
        mutableStateOf(
            initialAmount?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
        )
    }
    val scope = rememberCoroutineScope()
    var userEdited by rememberSaveable { mutableStateOf(false) }
    var hasInitializedRecommendation by rememberSaveable { mutableStateOf(false) }

    // Pre-populate recommended settlement once on load if not passed via navigation
    LaunchedEffect(groupDetail.settlements) {
        if (!userEdited && !hasInitializedRecommendation && initialPayer == null && initialPayee == null && groupDetail.settlements.isNotEmpty()) {
            val rec = groupDetail.settlements.first()
            selectedPayer = rec.fromPubkey
            selectedPayee = rec.toPubkey
            amountText = String.format(java.util.Locale.US, "%.2f", rec.amount)
            hasInitializedRecommendation = true
        }
    }

    val validation = AmountInputValidator.validate(amountText)
    val amount = (validation as? AmountValidationResult.Valid)?.majorAmount ?: 0.0
    val payeeMember = members.find { it.pubkey == selectedPayee }

    // Resolve payee UPI ID with fallbacks (member upiId or local profile upiId if payee is current user)
    var payeeUpiId by rememberSaveable(selectedPayee) {
        val resolvedUpi = payeeMember?.upiId
            ?: if (payeeMember?.isCurrentUser == true) KeyManager.getUpiId(context) else ""
        mutableStateOf(resolvedUpi ?: "")
    }

    var showQrDialog by remember { mutableStateOf(false) }
    var showEnterUpiDialog by remember { mutableStateOf(false) }
    var pendingActionAfterUpi by remember { mutableStateOf<String?>(null) } // "QR" or "PAY"
    var dialogUpiInput by rememberSaveable { mutableStateOf("") }
    var savedUpi by rememberSaveable(selectedPayee) { mutableStateOf(payeeUpiId) }
    var isSavingUpi by remember { mutableStateOf(false) }
    var upiError by remember { mutableStateOf<String?>(null) }
    val saveUpi: (String, String?) -> Unit = { value, nextAction ->
        if (!isSavingUpi) scope.launch {
            isSavingUpi = true
            upiError = null
            try {
                val clean = UpiAddress.requireValidOrEmpty(value)
                require(nextAction == null || clean.isNotBlank()) { "Enter a UPI ID before paying" }
                onUpdatePayeeUpi(selectedPayee, clean)
                savedUpi = clean
                payeeUpiId = clean
                showEnterUpiDialog = false
                if (nextAction == "QR") showQrDialog = true
                if (nextAction == "PAY") UpiUtil.launchUpiPayment(context, clean, payeeMember?.displayName ?: "Payee", amount)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { upiError = e.message ?: "Could not save UPI ID. Retry." }
            finally { isSavingUpi = false }
        }
    }

    val commonHandles = listOf("@okhdfcbank", "@okaxis", "@okicici", "@oksbi", "@paytm", "@ybl", "@upi")

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
                .padding(padding).imePadding()
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
                        .selectable(selected = selectedPayer == member.pubkey, role = Role.RadioButton, enabled = !isSubmitting && !isSavingUpi, onClick = { selectedPayer = member.pubkey; userEdited = true }),
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
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Who is getting paid?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(members) { member ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selectedPayee == member.pubkey, role = Role.RadioButton, enabled = !isSubmitting && !isSavingUpi, onClick = { selectedPayee = member.pubkey; userEdited = true }),
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
                            val hasMemberPhone = !member.phone.isNullOrBlank()
                            val hasMemberUpi = !member.upiId.isNullOrBlank()
                            if (hasMemberPhone || hasMemberUpi) {
                                val sub = buildList {
                                    if (hasMemberPhone) add("📱 ${member.phone}")
                                    if (hasMemberUpi) add("UPI: ${member.upiId}")
                                }.joinToString(" • ")
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (selectedPayee == member.pubkey) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            // Amount Input Field
            item {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; userEdited = true },
                    enabled = !isSubmitting && !isSavingUpi,
                    isError = validation is AmountValidationResult.Invalid,
                    supportingText = { (validation as? AmountValidationResult.Invalid)?.let { Text(it.error) } },
                    label = { Text("Settlement Amount ($currency)") },
                    prefix = { Text(CurrencyUtil.getSymbol(currency) + " ") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            item {
                ExternalPaymentCard(amount, currency, canCopyAmount = validation.isValid)
            }

            // UPI Deep Link & QR Payment Options (Enabled only for INR currency when payee has a UPI ID or Phone)
            if (currency.equals("INR", ignoreCase = true)) {
                val payeeName = payeeMember?.displayName ?: "Payee"
                val payeePhone = payeeMember?.phone?.takeIf { it.isNotBlank() }
                val payeeIndianMobile = com.peersplit.app.util.PhoneNumbers.indianMobile(payeePhone)

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Payment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("UPI payment options", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                }
                                if (payeeUpiId.isNotBlank()) {
                                    AssistChip(
                                        onClick = {
                                            dialogUpiInput = payeeUpiId
                                            pendingActionAfterUpi = null
                                            showEnterUpiDialog = true
                                        },
                                        label = { Text("Edit UPI", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                    )
                                }
                            }

                            if (payeePhone != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Phone number: $payeePhone",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    AssistChip(
                                        onClick = {
                                            ShareUtil.copySensitive(context, "Payee Phone", payeePhone)
                                            Toast.makeText(context, "Phone number $payeePhone copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        label = { Text("Copy Number", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = payeeUpiId,
                                enabled = !isSavingUpi,
                                onValueChange = {
                                    payeeUpiId = it
                                    upiError = null
                                },
                                label = { Text("Payee UPI ID ($payeeName)") },
                                placeholder = { Text("e.g. mobile@upi, name@okhdfcbank") },
                                supportingText = {
                                    if (payeeUpiId.isBlank()) {
                                        Text(
                                            if (payeeIndianMobile != null) "Tap a handle below to suggest ${payeeIndianMobile}@...; verify the UPI ID before paying."
                                            else "Enter and verify $payeeName's UPI ID to enable QR & UPI payment.",
                                            color = if (payeeIndianMobile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            fontSize = 12.sp
                                        )
                                    } else {
                                        Text(if (payeeUpiId.trim() == savedUpi) "Saved for $payeeName" else "Unsaved changes", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { saveUpi(payeeUpiId, null) }, enabled = !isSavingUpi && payeeUpiId.trim() != savedUpi) {
                                    Text(if (isSavingUpi) "Saving…" else "Save UPI ID")
                                }
                                TextButton(onClick = { saveUpi("", null) }, enabled = !isSavingUpi && savedUpi.isNotBlank()) { Text("Clear") }
                            }
                            if (upiError != null) Text(upiError!!, color = MaterialTheme.colorScheme.error)

                            // Quick Handle Suffix Chips if user is entering a new UPI ID
                            if (payeeUpiId.isBlank() || !payeeUpiId.contains("@")) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Common UPI handles:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(commonHandles) { handle ->
                                        val base = payeeUpiId.trim().substringBefore("@").ifBlank { payeeIndianMobile.orEmpty() }
                                        val chipLabel = if (base.isNotBlank()) "$base$handle" else handle
                                        SuggestionChip(
                                            onClick = {
                                                val full = if (base.isNotBlank()) "$base$handle" else handle
                                                payeeUpiId = full

                                            },
                                            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (payeeUpiId.isBlank()) {
                                            dialogUpiInput = payeeIndianMobile ?: ""
                                            pendingActionAfterUpi = "PAY"
                                            showEnterUpiDialog = true
                                        } else {
                                            saveUpi(payeeUpiId, "PAY")
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = validation.isValid && !isSavingUpi && !isSubmitting,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pay with UPI")
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (payeeUpiId.isBlank()) {
                                            dialogUpiInput = payeeIndianMobile ?: ""
                                            pendingActionAfterUpi = "QR"
                                            showEnterUpiDialog = true
                                        } else {
                                            saveUpi(payeeUpiId, "QR")
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = validation.isValid && !isSavingUpi && !isSubmitting,
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

            // 1-Tap Payment Reminder Option (WhatsApp / SMS / Share)
            item {
                val payerMember = members.find { it.pubkey == selectedPayer }
                OutlinedButton(
                    onClick = {
                        val debtorName = payerMember?.displayName ?: "Friend"
                        val creditorName = payeeMember?.displayName ?: "Me"
                        UpiUtil.sharePaymentReminder(
                            context = context,
                            debtorName = debtorName,
                            creditorName = creditorName,
                            groupName = groupDetail.group.name,
                            amount = amount,
                            currency = currency,
                            creditorUpiId = payeeUpiId.takeIf { it.isNotBlank() } ?: payeeMember?.upiId,
                            creditorPhone = payeeMember?.phone,
                            debtorPhone = payerMember?.phone
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = validation.isValid && members.any { it.pubkey == selectedPayer } && members.any { it.pubkey == selectedPayee } && selectedPayer != selectedPayee,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📲 Send Payment Reminder (WhatsApp / SMS)")
                }
            }

            if (submissionError != null) item { Text(submissionError, color = MaterialTheme.colorScheme.error) }
            // Mint Settlement Block
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        onRecordSettlement(selectedPayer, selectedPayee, amount, currency)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    enabled = validation.isValid && members.any { it.pubkey == selectedPayer } && members.any { it.pubkey == selectedPayee } && selectedPayer != selectedPayee && !isSubmitting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Handshake, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Record Payment")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Modal to Enter / Save Payee UPI ID
    if (showEnterUpiDialog) {
        val payeeName = payeeMember?.displayName ?: "Payee"

        AlertDialog(
            onDismissRequest = { if (!isSavingUpi) showEnterUpiDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enter UPI ID for $payeeName")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (upiError != null) Text(upiError!!, color = MaterialTheme.colorScheme.error)
                    Text(
                        "$payeeName needs a UPI ID (Virtual Payment Address) to generate a QR code or pay via Google Pay, PhonePe, or Paytm:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = dialogUpiInput,
                        enabled = !isSavingUpi,
                        onValueChange = { dialogUpiInput = it },
                        label = { Text("Payee UPI ID") },
                        placeholder = { Text("e.g. 9876543210@upi or name@okhdfcbank") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Text("Quick handles:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(commonHandles) { handle ->
                            SuggestionChip(
                                onClick = {
                                    val current = dialogUpiInput.trim()
                                    dialogUpiInput = if (current.contains("@")) {
                                        current.substringBefore("@") + handle
                                    } else {
                                        if (current.isNotBlank()) "$current$handle" else handle
                                    }
                                },
                                label = { Text(handle, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveUpi(dialogUpiInput, pendingActionAfterUpi)
                    },
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isSavingUpi
                ) {
                    Text(when (pendingActionAfterUpi) { "PAY" -> "Save & Pay"; "QR" -> "Save & Show QR"; else -> "Save UPI ID" })
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        saveUpi("", null)
                    }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(enabled = !isSavingUpi, onClick = { showEnterUpiDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // Dynamic UPI QR Code Dialog
    if (showQrDialog) {
        val payeeName = payeeMember?.displayName ?: "Friend"
        val qrState = remember(payeeUpiId, amount) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(payeeUpiId, amount) {
            qrState.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                UpiUtil.generateUpiQrBitmap(payeeUpiId, payeeMember?.displayName ?: "Payee", amount)
            }
        }
        val qrBitmap = qrState.value

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
                                modifier = Modifier.size(220.dp).padding(10.dp)
                            )
                        }
                    }
                    Text(
                        "${CurrencyUtil.formatAmount(amount, currency)} to $payeeUpiId",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Scan with Google Pay, PhonePe, Paytm, BHIM, or any UPI app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                ShareUtil.copySensitive(context, payeeUpiId, "UPI ID copied!")
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy UPI", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                val payeeNameSafe = payeeMember?.displayName ?: "PeerSplit Friend"
                                UpiUtil.launchUpiPayment(context, payeeUpiId, payeeNameSafe, amount)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open UPI App", fontSize = 12.sp)
                        }
                    }
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
