package com.example.groceriesapp.feature.biocompany

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Receipt
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.network.BioCompanyReceiptDetail
import com.example.groceriesapp.data.network.BioCompanyReceiptEntry
import androidx.compose.foundation.clickable
import com.example.groceriesapp.ui.theme.spacing
import kotlin.collections.mutableSetOf
import kotlin.collections.setOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BioCompanyReceiptsScreen(
    entries: List<BioCompanyReceiptEntry>,
    onBack: () -> Unit,
    groceryRepository: GroceriesRepository,
    fetchDetail: suspend (BioCompanyReceiptEntry) -> Result<BioCompanyReceiptDetail>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(setOf<BioCompanyReceiptEntry>()) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import BIO COMPANY Receipts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (entries.isEmpty()) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
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
                            "Pull receipts first from Settings, or your BIO COMPANY account has no receipts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.small,
                        bottom = MaterialTheme.spacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    items(entries, key = { it.receiptnumber * 1000000 + it.checkout }) { entry ->
                        val isSelected = entry in selected
                        val alreadyImported = groceryRepository.hasBioCompanyReceipt(entry.receiptnumber, entry.checkout)
                        ReceiptRow(
                            entry = entry,
                            selected = isSelected,
                            alreadyImported = alreadyImported,
                            onToggle = {
                                if (alreadyImported) return@ReceiptRow
                                if (isSelected) {
                                    selected = selected - entry
                                } else {
                                    selected = selected + entry
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
                        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (importResult != null) {
                        Text(
                            importResult ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (importResult!!.contains("Failed", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            if (selected.isNotEmpty()) {
                                isImporting = true
                                importResult = null
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val imported = kotlin.collections.mutableSetOf<Int>()
                                    val failed = kotlin.collections.mutableSetOf<Int>()
                                    for (entry in selected) {
                                        if (groceryRepository.hasBioCompanyReceipt(entry.receiptnumber, entry.checkout)) {
                                            imported.add(entry.receiptnumber)
                                            continue
                                        }
                                        val detail = fetchDetail(entry)
                                        if (detail.isSuccess) {
                                            val d = detail.getOrNull() ?: continue
                                            val items = d.items.map { item ->
                                                ReceiptItem(
                                                    name = item.name,
                                                    quantity = item.quantity.coerceAtLeast(0.1),
                                                    unit = item.unit,
                                                    priceCents = item.priceCents,
                                                )
                                            }
                                            val totalCents = (entry.amount * 100).toInt()
                                            groceryRepository.importBioCompanyReceipt(
                                                merchant = "BIO COMPANY",
                                                dateIso = entry.date,
                                                totalCents = totalCents,
                                                items = items,
                                                bioCompanyReceiptNumber = entry.receiptnumber,
                                                bioCompanyCheckout = entry.checkout,
                                            )
                                            imported.add(entry.receiptnumber)
                                        } else {
                                            failed.add(entry.receiptnumber)
                                        }
                                    }
                                    isImporting = false
                                    importResult = if (failed.isEmpty()) {
                                        "Imported ${imported.size} receipt(s)"
                                    } else {
                                        "Imported ${imported.size}, Failed ${failed.size}"
                                    }
                                    selected = emptySet()
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

@Composable
private fun ReceiptRow(
    entry: BioCompanyReceiptEntry,
    selected: Boolean,
    alreadyImported: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !alreadyImported, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else if (alreadyImported) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainer
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
                tint = if (alreadyImported) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Receipt #${entry.receiptnumber} (Checkout ${entry.checkout})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${entry.date}  •  ${String.format("%.2f€", entry.amount)}  •  ${entry.positions} items",
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
                alreadyImported -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                selected -> Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                else -> Icon(Icons.Default.NotInterested, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
        }
    }
}
