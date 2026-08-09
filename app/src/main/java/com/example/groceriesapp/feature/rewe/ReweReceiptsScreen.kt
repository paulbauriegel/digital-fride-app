package com.example.groceriesapp.feature.rewe

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotInterested
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.network.ReweEbonEntry
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.parsing.ReweReceiptParser
import com.example.groceriesapp.vision.parsing.ReweReceiptPdfExtractor
import com.example.groceriesapp.vision.parsing.categoryName
import com.example.groceriesapp.vision.parsing.foodConceptId
import com.example.groceriesapp.vision.parsing.normalizedUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReweReceiptsScreen(
    entries: List<ReweEbonEntry>,
    reweRepository: ReweRepository,
    groceryRepository: GroceriesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val extractor = remember(context) { ReweReceiptPdfExtractor(context) }
    val parser = remember { ReweReceiptParser() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import REWE Receipts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        Icon(
                            Icons.Default.Receipt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("No receipts found", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Pull receipts first from Settings, or your REWE account has none yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.small,
                        bottom = MaterialTheme.spacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    items(entries, key = ReweEbonEntry::receiptId) { entry ->
                        val alreadyImported = groceryRepository.hasReweReceipt(entry.receiptId)
                        ReceiptRow(
                            entry = entry,
                            selected = entry.receiptId in selected,
                            alreadyImported = alreadyImported,
                            onToggle = {
                                if (alreadyImported) return@ReceiptRow
                                selected = if (entry.receiptId in selected) {
                                    selected - entry.receiptId
                                } else {
                                    selected + entry.receiptId
                                }
                            },
                        )
                    }
                }
            }

            if (entries.isNotEmpty() || importResult != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    importResult?.let { result ->
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.contains("failed", ignoreCase = true)) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            if (selected.isEmpty()) return@Button
                            isImporting = true
                            importResult = null
                            scope.launch {
                                val chosen = entries.filter { it.receiptId in selected }
                                var imported = 0
                                var failed = 0
                                for (entry in chosen) {
                                    val success = importEntry(
                                        entry = entry,
                                        reweRepository = reweRepository,
                                        groceryRepository = groceryRepository,
                                        extractor = extractor,
                                        parser = parser,
                                    )
                                    if (success) imported++ else failed++
                                }
                                isImporting = false
                                selected = emptySet()
                                importResult = if (failed == 0) {
                                    "Imported $imported receipt(s)"
                                } else {
                                    "Imported $imported, failed $failed"
                                }
                            }
                        },
                        enabled = selected.isNotEmpty() && !isImporting,
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (selected.isEmpty()) "Select receipts" else "Import ${selected.size}")
                    }
                }
            }
        }
    }
}

private suspend fun importEntry(
    entry: ReweEbonEntry,
    reweRepository: ReweRepository,
    groceryRepository: GroceriesRepository,
    extractor: ReweReceiptPdfExtractor,
    parser: ReweReceiptParser,
): Boolean = withContext(Dispatchers.IO) {
    if (groceryRepository.hasReweReceipt(entry.receiptId)) return@withContext true
    val pdf = reweRepository.downloadReceiptPdf(entry).getOrNull() ?: return@withContext false
    val items = runCatching { parser.parse(extractor.extract(pdf)) }.getOrNull()
        ?: return@withContext false
    if (items.isEmpty()) return@withContext false

    groceryRepository.importReweReceipt(
        merchant = entry.market?.name ?: "REWE",
        dateIso = entry.receiptTimestamp,
        totalCents = entry.receiptTotalPriceCents,
        items = items.map { item ->
            ReceiptItem(
                name = item.name,
                quantity = item.quantity.coerceAtLeast(0.001),
                unit = item.normalizedUnit(),
                priceCents = item.finalPriceCents.toInt(),
                category = item.categoryName(),
                foodConceptId = item.foodConceptId(),
            )
        },
        reweReceiptId = entry.receiptId,
    )
    true
}

@Composable
private fun ReceiptRow(
    entry: ReweEbonEntry,
    selected: Boolean,
    alreadyImported: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        // toggleable rather than clickable: selection is otherwise carried only by a trailing icon,
        // so TalkBack had no way to announce whether a row was picked.
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = !alreadyImported,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                alreadyImported -> MaterialTheme.colorScheme.surfaceContainerLow
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Icon(
                Icons.Default.Receipt,
                contentDescription = null,
                tint = if (alreadyImported) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.market?.city?.let { "REWE $it" } ?: "REWE Markt",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${formatReceiptDate(entry.receiptTimestamp)}  •  " +
                        formatEuro(entry.receiptTotalPriceCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alreadyImported) {
                    Text(
                        "Already imported",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                alreadyImported -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Already imported",
                    tint = MaterialTheme.colorScheme.primary,
                )
                selected -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                else -> Icon(
                    Icons.Default.NotInterested,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val RECEIPT_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)

private fun formatReceiptDate(timestamp: String): String = runCatching {
    Instant.parse(timestamp).atZone(ZoneId.systemDefault()).format(RECEIPT_DATE_FORMAT)
}.getOrDefault(timestamp)

private fun formatEuro(cents: Int): String =
    String.format(Locale.GERMANY, "%.2f €", cents / 100.0)
