package com.example.groceriesapp.feature.more

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.models.DownloadableModel
import com.example.groceriesapp.vision.models.ModelCatalogStore
import com.example.groceriesapp.vision.models.ModelDownloadStatus
import com.example.groceriesapp.vision.models.ModelDownloads
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Download, progress, cancel and delete for one model, shown inside its model card. [ready] comes
 * from the model's manager; [onModelsChanged] lets the caller re-read the managers after a download
 * finished or the files were deleted.
 */
@Composable
internal fun ModelDownloadSection(
    model: DownloadableModel,
    ready: Boolean,
    onModelsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val spec = remember(model) { runCatching { ModelCatalogStore(context).current().spec(model) }.getOrNull() }
    val status by remember(model) { ModelDownloads.status(context, model) }
        .collectAsStateWithLifecycle(initialValue = ModelDownloadStatus.Idle)
    var wifiOnly by remember { mutableStateOf(ModelDownloads.wifiOnly(context)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The progress notification needs this on Android 13+; the download runs either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ModelDownloads.start(context, model)
    }
    val startDownload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ModelDownloads.start(context, model)
        }
    }

    LaunchedEffect(status) {
        if (status == ModelDownloadStatus.Finished) onModelsChanged()
    }

    if (spec == null) {
        Text(
            "This model is not available for download in this version of the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        when (val s = status) {
            is ModelDownloadStatus.Running -> {
                LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatBytes(s.bytesDone)} of ${formatBytes(s.bytesTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ModelDownloads.cancel(context, model) }) { Text("Cancel") }
                }
            }
            ModelDownloadStatus.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for a network connection…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ModelDownloads.cancel(context, model) }) { Text("Cancel") }
            }
            else -> {
                if (s is ModelDownloadStatus.Failed) {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (ready) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete model files") }
                } else {
                    Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s is ModelDownloadStatus.Failed) "Retry download" else "Download (${formatBytes(spec.totalBytes)})")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Download over Wi-Fi only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = wifiOnly, onCheckedChange = {
                            wifiOnly = it
                            ModelDownloads.setWifiOnly(context, it)
                        })
                    }
                }
            }
        }
        Text(
            "${spec.name} · ${spec.license} · from ${spec.source.removePrefix("https://")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${spec.name}?") },
            text = { Text("Frees ${formatBytes(spec.totalBytes)}. You can download it again at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        ModelDownloads.delete(context, spec)
                        onModelsChanged()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
    else String.format(Locale.ROOT, "%.0f MB", bytes / 1e6)
