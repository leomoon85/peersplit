package com.peersplit.app.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil
import com.peersplit.app.util.UpiUtil

private data class GroupTemplate(
    val emoji: String,
    val label: String,
    val defaultName: String,
    val isSolo: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateGroupScreen(
    onBackClick: () -> Unit,
    onCreateGroup: (
        name: String,
        currency: String,
        isSecret: Boolean,
        secretPin: String?,
        isPersonalSolo: Boolean,
        initialMembers: List<String>
    ) -> Unit
) {
    val context = LocalContext.current
    var groupName by remember { mutableStateOf("") }
    val currencies = listOf("INR", "USD", "EUR", "GBP", "CAD", "AUD", "JPY")
    var selectedCurrency by remember { mutableStateOf("INR") }
    var isSecret by remember { mutableStateOf(false) }
    var secretPin by remember { mutableStateOf("") }
    var isPersonalSolo by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    // Friends added right during group creation (formatted as "Name" or "Name|Phone")
    var friendInput by remember { mutableStateOf("") }
    var friendPhoneInput by remember { mutableStateOf("") }
    val addedFriends = remember { mutableStateListOf<String>() }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            val picked = UpiUtil.readContactFromPickerUri(context, uri)
            if (picked != null && picked.name.isNotBlank()) {
                val entry = if (picked.phone.isNotBlank()) "${picked.name}|${picked.phone}" else picked.name
                val alreadyExists = addedFriends.any {
                    val existingName = it.substringBefore("|")
                    val existingPhone = it.substringAfter("|", "")
                    (picked.phone.isNotBlank() && existingPhone == picked.phone) ||
                        existingName.equals(picked.name, ignoreCase = true)
                }
                if (!alreadyExists) {
                    addedFriends.add(entry)
                } else {
                    Toast.makeText(context, "${picked.name} is already added", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val templates = remember {
        listOf(
            GroupTemplate("🏝️", "Trip", "Trip Expenses"),
            GroupTemplate("🏠", "Flatmates", "Flat & Rent"),
            GroupTemplate("🍕", "Food & Outing", "Food & Outing"),
            GroupTemplate("💑", "Couple", "Us Two"),
            GroupTemplate("🎉", "Party", "Weekend Party"),
            GroupTemplate("💼", "Office", "Office Lunch"),
            GroupTemplate("👤", "Personal", "My Expenses", isSolo = true)
        )
    }

    fun addCurrentFriend() {
        val cleanName = friendInput.trim().replace("|", " ")
        val cleanPhone = KeyManager.normalizePhone(friendPhoneInput)
        if (cleanName.isNotBlank()) {
            val entry = if (cleanPhone.isNotBlank()) "$cleanName|$cleanPhone" else cleanName
            val alreadyExists = addedFriends.any {
                val existingName = it.substringBefore("|")
                val existingPhone = it.substringAfter("|", "")
                (cleanPhone.isNotBlank() && existingPhone == cleanPhone) ||
                    existingName.equals(cleanName, ignoreCase = true)
            }
            if (!alreadyExists) {
                addedFriends.add(entry)
                friendInput = ""
                friendPhoneInput = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Group", fontWeight = FontWeight.Bold) },
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
            // 1-Tap Quick Templates
            Column {
                Text(
                    "Quick Start (Tap to autofill)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(templates) { tpl ->
                        val isSelected = groupName == tpl.defaultName && isPersonalSolo == tpl.isSolo
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                groupName = tpl.defaultName
                                isPersonalSolo = tpl.isSolo
                            },
                            label = { Text("${tpl.emoji} ${tpl.label}") }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group Name") },
                placeholder = { Text(if (isPersonalSolo) "e.g. My Monthly Expenses" else "e.g. Goa Trip, Flat 302") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                singleLine = true
            )

            // Instant Add Friends section (shown whenever it's a shared group)
            if (!isPersonalSolo) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Add Friends to Split With",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            AssistChip(
                                onClick = {
                                    try {
                                        contactPickerLauncher.launch(UpiUtil.createPickContactPhoneIntent())
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Contacts app unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                label = { Text("Contacts", style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(Icons.Default.ContactPhone, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            )
                        }
                        Text(
                            "Pick from Contacts or type name & optional phone number so friends automatically link when they join.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = friendInput,
                            onValueChange = { friendInput = it },
                            placeholder = { Text("Friend's name (e.g. Rahul)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = friendPhoneInput,
                                onValueChange = { friendPhoneInput = it },
                                placeholder = { Text("Phone (Optional, e.g. 9876543210)") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { addCurrentFriend() }
                                )
                            )
                            FilledTonalButton(
                                onClick = { addCurrentFriend() },
                                enabled = friendInput.trim().isNotBlank(),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Friend", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        if (addedFriends.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Show "You" chip first so user sees the full group
                                AssistChip(
                                    onClick = {},
                                    label = { Text("You (Included)") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                                addedFriends.forEach { friendEntry ->
                                    val fName = friendEntry.substringBefore("|")
                                    val fPhone = friendEntry.substringAfter("|", "")
                                    val chipLabel = if (fPhone.isNotBlank()) "$fName ($fPhone)" else fName
                                    InputChip(
                                        selected = true,
                                        onClick = { addedFriends.remove(friendEntry) },
                                        label = { Text(chipLabel) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove $fName",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column {
                Text(
                    "Currency",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(currencies) { curr ->
                        FilterChip(
                            selected = selectedCurrency == curr,
                            onClick = { selectedCurrency = curr },
                            label = { Text("$curr (${CurrencyUtil.getSymbol(curr)})") }
                        )
                    }
                }
            }

            // Mode Selector: Group vs Solo/Personal
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Personal / Solo Expense Tracker", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("Track only your own spending without splitting with others", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isPersonalSolo, onCheckedChange = { isPersonalSolo = it })
                }
            }

            // Secret Group Mode
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = BlockchainAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Private PIN-Locked Group", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Hidden from the main list. Tap the lock icon on the home screen to open with PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isSecret, onCheckedChange = { isSecret = it })
                    }

                    if (isSecret) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = secretPin,
                            onValueChange = { if (it.length <= 6) secretPin = it },
                            label = { Text("Set 4-6 Digit PIN") },
                            placeholder = { Text("1234") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (groupName.isNotBlank() && !isCreating) {
                        // Also include any pending name/phone typed in friendInput
                        val finalFriends = addedFriends.toMutableList()
                        val pendingFriend = friendInput.trim().replace("|", " ")
                        val pendingPhone = KeyManager.normalizePhone(friendPhoneInput)
                        if (pendingFriend.isNotBlank()) {
                            val entry = if (pendingPhone.isNotBlank()) "$pendingFriend|$pendingPhone" else pendingFriend
                            if (finalFriends.none { it.substringBefore("|").equals(pendingFriend, ignoreCase = true) }) {
                                finalFriends.add(entry)
                            }
                        }
                        isCreating = true
                        onCreateGroup(
                            groupName.trim(),
                            selectedCurrency,
                            isSecret,
                            if (isSecret && secretPin.isNotBlank()) secretPin else null,
                            isPersonalSolo,
                            finalFriends
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = groupName.isNotBlank() && (!isSecret || secretPin.length >= 4) && !isCreating,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create Group")
                }
            }
        }
    }
}
