package com.example.groceriesapp.feature.edeka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.groceriesapp.data.EdekaRepository
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.network.EdekaReceiptEntry
import com.example.groceriesapp.data.toRawText
import com.example.groceriesapp.data.toReceiptItems
import com.example.groceriesapp.ui.theme.spacing
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdekaReceiptsScreen(
    entries: List<EdekaReceiptEntry>,
    edekaRepository: EdekaRepository,
    groceryRepository: GroceriesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var importing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    val syncState by edekaRepository.syncState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (edekaRepository.syncState.value !is EdekaRepository.SyncState.FETCHING) {
            edekaRepository.fetchReceipts()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import EDEKA Receipts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (entries.isEmpty()) {
                EmptyReceipts(syncState, { scope.launch { edekaRepository.fetchReceipts() } })
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(MaterialTheme.spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    items(entries, key = EdekaReceiptEntry::id) { entry ->
                        val imported = groceryRepository.hasEdekaReceipt(entry.id)
                        EdekaReceiptRow(
                            entry = entry,
                            selected = entry.id in selected,
                            imported = imported,
                            onToggle = {
                                if (!imported) selected = if (entry.id in selected) {
                                    selected - entry.id
                                } else {
                                    selected + entry.id
                                }
                            },
                        )
                    }
                }
            }

            if (entries.isNotEmpty() || resultMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    resultMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.contains("failed", true)) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        enabled = selected.isNotEmpty() && !importing,
                        onClick = {
                            importing = true
                            resultMessage = null
                            val chosen = entries.filter { it.id in selected }
                            scope.launch {
                                val (imported, failed) = withContext(Dispatchers.IO) {
                                    var importedCount = 0
                                    var failedCount = 0
                                    chosen.forEach { entry ->
                                        if (groceryRepository.hasEdekaReceipt(entry.id)) {
                                            importedCount++
                                            return@forEach
                                        }
                                        edekaRepository.fetchReceiptDetail(entry)
                                            .onSuccess { detail ->
                                                if (detail.canceled) {
                                                    failedCount++
                                                } else {
                                                    groceryRepository.importEdekaReceipt(
                                                        merchant = detail.shopName
                                                            ?: detail.retailerName
                                                            ?: entry.merchantName,
                                                        dateIso = entry.dateAndTime,
                                                        totalCents = detail.totalCents
                                                            ?: entry.totalCents,
                                                        currency = detail.currency
                                                            .takeIf(String::isNotBlank)
                                                            ?: entry.currency,
                                                        rawText = detail.toRawText(),
                                                        items = detail.toReceiptItems(),
                                                        edekaReceiptId = entry.id,
                                                    )
                                                    importedCount++
                                                }
                                            }
                                            .onFailure { failedCount++ }
                                    }
                                    importedCount to failedCount
                                }
                                importing = false
                                selected = emptySet()
                                resultMessage = if (failed == 0) {
                                    "Imported $imported receipt(s)"
                                } else {
                                    "Imported $imported, failed $failed"
                                }
                            }
                        },
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        }
                        Text(if (selected.isEmpty()) "Select receipts" else "Import ${selected.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyReceipts(
    syncState: EdekaRepository.SyncState,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            if (syncState is EdekaRepository.SyncState.FETCHING) {
                CircularProgressIndicator()
                Text("Loading receipts…")
            } else {
                Icon(
                    Icons.Default.Receipt,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (syncState is EdekaRepository.SyncState.ERROR) {
                        "Couldn't load receipts"
                    } else {
                        "No receipts found"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    (syncState as? EdekaRepository.SyncState.ERROR)?.message
                        ?: "Your EDEKA account has no digital receipts yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (syncState is EdekaRepository.SyncState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
                if (syncState is EdekaRepository.SyncState.ERROR) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun EdekaReceiptRow(
    entry: EdekaReceiptEntry,
    selected: Boolean,
    imported: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = selected,
            enabled = !imported,
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                imported -> MaterialTheme.colorScheme.surfaceContainerLow
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(entry.merchantName, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        formatDate(entry.dateAndTime),
                        entry.totalCents?.let { String.format("%.2f %s", it / 100.0, entry.currency) },
                    ).joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (imported) Text("Already imported", style = MaterialTheme.typography.labelSmall)
            }
            if (imported) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Already imported")
            } else if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected")
            }
        }
    }
}

private fun formatDate(value: String): String = runCatching {
    DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.parse(value).atZone(java.time.ZoneId.systemDefault()))
}.getOrDefault(value.take(10))
