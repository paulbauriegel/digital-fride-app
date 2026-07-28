package com.example.groceriesapp.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    receiptCount: Int,
    inventoryCount: Int,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("More") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item {
                LocalFirstCard(receiptCount, inventoryCount)
            }
            item {
                SectionHeader("Appearance")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        Icon(
                            if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dark theme", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Designed for tracked receipt panoramas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = darkTheme,
                            onCheckedChange = onDarkThemeChanged,
                        )
                    }
                }
            }
            item {
                SectionHeader("Capture tips")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        TipRow(Icons.Default.Straighten, "Keep the receipt flat")
                        TipRow(Icons.Default.LightMode, "Use even light without glare")
                        TipRow(Icons.AutoMirrored.Filled.ReceiptLong, "Move freely and let the live paper outline guide you")
                    }
                }
            }
            item {
                SectionHeader("On the roadmap")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(
                            "Shared fridges • barcode lookup • shopping suggestions • meal planning",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "These need separate product data and sync decisions. They are not silently enabled in this local scanner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalFirstCard(receiptCount: Int, inventoryCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Text("Private by default", style = MaterialTheme.typography.titleLarge)
            Text(
                "Receipt images, recognized text, and inventory remain in app-private storage. OCR runs with a bundled on-device model.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
                Text("$receiptCount receipts", style = MaterialTheme.typography.labelLarge)
                Text("$inventoryCount items", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.CloudOff, contentDescription = "No cloud sync")
            }
        }
    }
}

@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
