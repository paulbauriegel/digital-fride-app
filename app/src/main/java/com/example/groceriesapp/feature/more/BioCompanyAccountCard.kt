package com.example.groceriesapp.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.network.BioCompanyAccount
import com.example.groceriesapp.data.network.BioCompanyPointsResponse
import com.example.groceriesapp.ui.theme.spacing

@Composable
fun BioCompanyAccountCard(
    account: BioCompanyAccount?,
    points: BioCompanyPointsResponse?,
    loginState: BioCompanyRepository.LoginState,
    syncState: BioCompanyRepository.SyncState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onFetchReceipts: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (account != null) {
        ConnectedCard(
            account = account,
            points = points,
            syncState = syncState,
            onLogout = onLogout,
            onFetchReceipts = onFetchReceipts,
            onOpenReceipts = onOpenReceipts,
            modifier = modifier,
        )
    } else {
        LoginCard(
            loginState = loginState,
            onLogin = onLogin,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectedCard(
    account: BioCompanyAccount,
    points: BioCompanyPointsResponse?,
    syncState: BioCompanyRepository.SyncState,
    onLogout: () -> Unit,
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
                    Text(
                        "BIO COMPANY Account",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${account.email} (${account.customerNumber})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            points?.let { p ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CardMembership,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "${p.points} loyalty points",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TextButton(
                    onClick = { onFetchReceipts() },
                    modifier = Modifier.weight(1f),
                ) {
                    val icon = when (syncState) {
                        is BioCompanyRepository.SyncState.FETCHING -> Icons.Default.Sync
                        is BioCompanyRepository.SyncState.SUCCESS -> Icons.Default.CloudDownload
                        else -> Icons.Default.CloudCircle
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text(
                        when (syncState) {
                            is BioCompanyRepository.SyncState.FETCHING -> "Fetching..."
                            is BioCompanyRepository.SyncState.SUCCESS -> "Pull Receipts"
                            is BioCompanyRepository.SyncState.ERROR -> "Retry"
                            else -> "Pull Receipts"
                        }
                    )
                }
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }

            when (syncState) {
                is BioCompanyRepository.SyncState.FETCHING -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
                is BioCompanyRepository.SyncState.SUCCESS -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${(syncState as BioCompanyRepository.SyncState.SUCCESS).count} receipts available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenReceipts) {
                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        Text("Review & Import")
                    }
                }
                is BioCompanyRepository.SyncState.ERROR -> {
                    val err = syncState as BioCompanyRepository.SyncState.ERROR
                    Text(
                        err.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                BioCompanyRepository.SyncState.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun LoginCard(
    loginState: BioCompanyRepository.LoginState,
    onLogin: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val loggingIn = loginState is BioCompanyRepository.LoginState.LOGGING_IN
    val loginError = (loginState as? BioCompanyRepository.LoginState.ERROR)?.message
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !loggingIn
    // Shared by the IME "Done" action and the Connect button so both honour the same guard.
    fun submit() {
        if (canSubmit) onLogin(email, password)
    }

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
                    Text(
                        "BIO COMPANY Account",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Sync your receipts from BIO COMPANY directly into your inventory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !loggingIn,
                isError = loginError != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    // Nothing to reveal on an empty field.
                    if (password.isNotEmpty()) {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !loggingIn,
                isError = loginError != null,
                // The failure belongs to the credential pair, so it is surfaced on the field the
                // user edits last rather than as loose text next to the button.
                supportingText = loginError?.let { message ->
                    { Text(message, color = MaterialTheme.colorScheme.error) }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.small))
                }
                androidx.compose.material3.Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                ) {
                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Connect")
                }
            }
        }
    }
}
