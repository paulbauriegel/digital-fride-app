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
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.EdekaRepository
import com.example.groceriesapp.data.network.EdekaAccount
import com.example.groceriesapp.ui.theme.spacing

@Composable
fun EdekaAccountCard(
    account: EdekaAccount?,
    loginState: EdekaRepository.LoginState,
    syncState: EdekaRepository.SyncState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onFetchReceipts: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (account == null) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
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
                    if (account == null) Icons.Default.CloudCircle else Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(Modifier.weight(1f)) {
                    Text("EDEKA Account", style = MaterialTheme.typography.titleMedium)
                    Text(
                        account?.email ?: "Sync your EDEKA receipts directly into your inventory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (account == null) {
                if (loginState is EdekaRepository.LoginState.RECONNECT_REQUIRED) {
                    Text(
                        "Your EDEKA session expired. Sign in again to keep syncing receipts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (loginState is EdekaRepository.LoginState.ERROR) {
                    Text(
                        loginState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onConnect,
                    enabled = loginState !is EdekaRepository.LoginState.LOGGING_IN,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Default.Login, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Sign in with EDEKA")
                }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TextButton(onClick = onFetchReceipts, modifier = Modifier.weight(1f)) {
                    Icon(
                        when (syncState) {
                            EdekaRepository.SyncState.FETCHING -> Icons.Default.Sync
                            is EdekaRepository.SyncState.SUCCESS -> Icons.Default.CloudDownload
                            else -> Icons.Default.CloudCircle
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text(if (syncState is EdekaRepository.SyncState.FETCHING) "Fetching..." else "Pull Receipts")
                }
                TextButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }

            when (syncState) {
                EdekaRepository.SyncState.FETCHING -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
                is EdekaRepository.SyncState.SUCCESS -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        "${syncState.count} receipts available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenReceipts) {
                        Icon(Icons.Default.Receipt, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        Text("Review & Import")
                    }
                }
                is EdekaRepository.SyncState.ERROR -> Text(
                    syncState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                EdekaRepository.SyncState.IDLE -> Unit
            }
        }
    }
}
