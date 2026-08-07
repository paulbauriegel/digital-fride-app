package com.example.groceriesapp.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.network.ReweAccount
import com.example.groceriesapp.ui.theme.spacing

@Composable
fun ReweAccountCard(
    account: ReweAccount?,
    credentials: ReweRepository.MtlsCredentialState,
    loginState: ReweRepository.LoginState,
    syncState: ReweRepository.SyncState,
    credentialError: String?,
    onImportCertificate: () -> Unit,
    onImportPrivateKey: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onFetchReceipts: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (account != null && loginState !is ReweRepository.LoginState.RECONNECT_REQUIRED) {
        ConnectedCard(
            account = account,
            syncState = syncState,
            onDisconnect = onDisconnect,
            onFetchReceipts = onFetchReceipts,
            onOpenReceipts = onOpenReceipts,
            modifier = modifier,
        )
    } else {
        SetupCard(
            credentials = credentials,
            loginState = loginState,
            credentialError = credentialError,
            onImportCertificate = onImportCertificate,
            onImportPrivateKey = onImportPrivateKey,
            onConnect = onConnect,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectedCard(
    account: ReweAccount,
    syncState: ReweRepository.SyncState,
    onDisconnect: () -> Unit,
    onFetchReceipts: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("REWE Account", style = MaterialTheme.typography.titleMedium)
                    Text(
                        account.name ?: account.email ?: "Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TextButton(onClick = onFetchReceipts, modifier = Modifier.weight(1f)) {
                    val icon = when (syncState) {
                        is ReweRepository.SyncState.FETCHING -> Icons.Default.Sync
                        is ReweRepository.SyncState.SUCCESS -> Icons.Default.CloudDownload
                        else -> Icons.Default.CloudCircle
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (syncState) {
                            is ReweRepository.SyncState.FETCHING -> "Fetching..."
                            is ReweRepository.SyncState.ERROR -> "Retry"
                            else -> "Pull Receipts"
                        },
                    )
                }
                TextButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }

            when (syncState) {
                is ReweRepository.SyncState.FETCHING -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
                is ReweRepository.SyncState.SUCCESS -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${syncState.count} receipts available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenReceipts) {
                        Icon(
                            Icons.Default.Receipt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Review & Import")
                    }
                }
                is ReweRepository.SyncState.ERROR -> Text(
                    syncState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                ReweRepository.SyncState.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun SetupCard(
    credentials: ReweRepository.MtlsCredentialState,
    loginState: ReweRepository.LoginState,
    credentialError: String?,
    onImportCertificate: () -> Unit,
    onImportPrivateKey: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Icon(
                    Icons.Default.CloudCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("REWE Account", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sync your REWE eBons directly into your inventory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "REWE's mobile API also requires the app's client certificate. Import both PEM " +
                    "files once — they are stored encrypted on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CredentialRow(
                label = "Client certificate",
                imported = credentials.hasCertificate,
                onImport = onImportCertificate,
            )
            CredentialRow(
                label = "Private key",
                imported = credentials.hasPrivateKey,
                onImport = onImportPrivateKey,
            )

            credentialError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (loginState is ReweRepository.LoginState.RECONNECT_REQUIRED) {
                Text(
                    "Your REWE session expired. Sign in again to keep syncing receipts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (loginState) {
                    is ReweRepository.LoginState.LOGGING_IN -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    is ReweRepository.LoginState.ERROR -> {
                        Text(
                            loginState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    else -> Unit
                }
                Button(
                    onClick = onConnect,
                    enabled = credentials.isComplete &&
                        loginState !is ReweRepository.LoginState.LOGGING_IN,
                ) {
                    Icon(
                        Icons.Default.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Sign in with REWE")
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    imported: Boolean,
    onImport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Icon(
            if (imported) Icons.Default.CheckCircle else Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (imported) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onImport) {
            Text(if (imported) "Replace" else "Import")
        }
    }
}
