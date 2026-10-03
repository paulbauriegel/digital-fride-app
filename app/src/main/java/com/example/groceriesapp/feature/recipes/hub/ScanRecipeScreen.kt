package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.theme.spacing

/**
 * Entry to the photo scan: take or pick a photo, then wait while OCR and extraction run. The
 * photo itself comes from the system camera / picker, launched by the shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanRecipeScreen(
    busy: Boolean,
    error: String?,
    aiEnabled: Boolean,
    slowOcrEngine: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Scan Recipe") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyStateCard(
                title = "Photograph a recipe",
                body = "Lay the page flat in good light and fit the whole recipe in the frame. " +
                    "Title, ingredients and steps are read from the photo; you can fix anything " +
                    "before saving.",
                icon = Icons.Default.PhotoCamera,
            )
            if (busy) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Column {
                            Text("Reading recipe text…", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = when {
                                    slowOcrEngine -> "On-device GLM-OCR is thorough but slow — this can take a few minutes."
                                    aiEnabled -> "Recognising the page, then structuring it with AI."
                                    else -> "Recognising the page, then structuring it on-device."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onTakePhoto,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("Take photo", modifier = Modifier.padding(start = MaterialTheme.spacing.small))
            }
            OutlinedButton(
                onClick = onPickPhoto,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Text("Choose from gallery", modifier = Modifier.padding(start = MaterialTheme.spacing.small))
            }
            if (!aiEnabled) {
                Text(
                    text = "Without an OpenAI key the page is structured by built-in rules; " +
                        "expect to tidy up the result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
