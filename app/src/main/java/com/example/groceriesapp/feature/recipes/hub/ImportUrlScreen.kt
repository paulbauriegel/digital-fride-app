package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.RecipeSourceSite
import com.example.groceriesapp.data.recipes.RecipeSources
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.InitialsBadge
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing

/**
 * Paste-a-link import as a full screen. The field is a plain [OutlinedTextField] rather than
 * anything search-shaped: it is a form value the user must be able to read back and correct.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportUrlScreen(
    prefill: String?,
    siteHint: RecipeSourceSite?,
    aiEnabled: Boolean,
    busy: Boolean,
    error: String?,
    onImport: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable(prefill) { mutableStateOf(prefill.orEmpty()) }
    val canImport = url.isNotBlank() && !busy

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import from URL") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            EmptyStateCard(
                title = "Paste recipe link",
                body = if (siteHint != null) {
                    "Open the recipe on ${siteHint.name}, copy its link and paste it here. " +
                        "It is read directly from the page — no API key needed."
                } else if (aiEnabled) {
                    "Most cooking sites publish structured recipe data, which is read " +
                        "directly; anything else is handled by AI."
                } else {
                    "Most cooking sites publish structured recipe data, which is read " +
                        "directly — no API key needed. Pages without it need an OpenAI key in Settings."
                },
                icon = Icons.Default.Link,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Recipe link") },
                placeholder = { Text("https://example.com/recipe") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (canImport) onImport(url) }),
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { url = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear link")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        "Fetching the recipe…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SettingsSectionHeader(title = "Supported websites")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
                items(RecipeSources.curated, key = { it.id }) { site ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                        modifier = Modifier.width(72.dp),
                    ) {
                        InitialsBadge(label = site.name)
                        Text(
                            text = site.name,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = "and many more…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onImport(url) },
                enabled = canImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import recipe")
            }
        }
    }
}
