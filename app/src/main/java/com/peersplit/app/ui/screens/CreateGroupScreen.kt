package com.peersplit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.peersplit.app.ui.theme.BlockchainAccent
import com.peersplit.app.util.CurrencyUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBackClick: () -> Unit,
    onCreateGroup: (name: String, currency: String, isSecret: Boolean, secretPin: String?, isPersonalSolo: Boolean) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    val currencies = listOf("USD", "EUR", "GBP", "INR", "CAD", "AUD", "JPY")
    var selectedCurrency by remember { mutableStateOf("USD") }
    var isSecret by remember { mutableStateOf(false) }
    var secretPin by remember { mutableStateOf("") }
    var isPersonalSolo by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Group", fontWeight = FontWeight.Bold) },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group Name") },
                placeholder = { Text(if (isPersonalSolo) "e.g. My Personal Expenses" else "e.g. Summer Vacation, Flatmates") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Column {
                Text(
                    "Default Currency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
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
                        Text("Personal / Solo Budget Mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("Track your own expenses without split debt calculations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Secret / Hidden Group", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Hidden from the main dashboard. Unlocked with a secret PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isSecret, onCheckedChange = { isSecret = it })
                    }

                    if (isSecret) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = secretPin,
                            onValueChange = { if (it.length <= 6) secretPin = it },
                            label = { Text("4-6 Digit Unlock PIN") },
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

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (groupName.isNotBlank() && !isCreating) {
                        isCreating = true
                        onCreateGroup(
                            groupName.trim(),
                            selectedCurrency,
                            isSecret,
                            if (isSecret && secretPin.isNotBlank()) secretPin else null,
                            isPersonalSolo
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
