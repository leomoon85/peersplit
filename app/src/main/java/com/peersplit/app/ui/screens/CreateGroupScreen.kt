package com.peersplit.app.ui.screens

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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil

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
    var groupName by remember { mutableStateOf("") }
    val currencies = listOf("INR", "USD", "EUR", "GBP", "CAD", "AUD", "JPY")
    var selectedCurrency by remember { mutableStateOf("INR") }
    var isSecret by remember { mutableStateOf(false) }
    var secretPin by remember { mutableStateOf("") }
    var isPersonalSolo by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    // Friends added right during group creation
    var friendInput by remember { mutableStateOf("") }
    val addedFriends = remember { mutableStateListOf<String>() }

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
        val clean = friendInput.trim()
        if (clean.isNotBlank() && addedFriends.none { it.equals(clean, ignoreCase = true) }) {
            addedFriends.add(clean)
            friendInput = ""
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
                        Text(
                            "Add names now so you can split bills right away (friends don't even need the app installed!).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = friendInput,
                                onValueChange = { friendInput = it },
                                placeholder = { Text("Friend's name (e.g. Rahul)") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
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
                                addedFriends.forEach { friend ->
                                    InputChip(
                                        selected = true,
                                        onClick = { addedFriends.remove(friend) },
                                        label = { Text(friend) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove $friend",
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
                        // Also include any pending name typed in friendInput
                        val finalFriends = addedFriends.toMutableList()
                        val pendingFriend = friendInput.trim()
                        if (pendingFriend.isNotBlank() && finalFriends.none { it.equals(pendingFriend, ignoreCase = true) }) {
                            finalFriends.add(pendingFriend)
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
