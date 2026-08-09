package com.example.groceriesapp.feature.more

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.OpenAiKeyStore
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.household.HouseholdEntryCounts
import com.example.groceriesapp.data.household.HouseholdRepository
import com.example.groceriesapp.data.household.householdNameError
import com.example.groceriesapp.ui.components.InitialsBadge
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.vision.ocr.GlmOcrModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrModelState
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelState
import com.example.groceriesapp.vision.ocr.OcrEngine
import com.example.groceriesapp.vision.parsing.GraniteModelManager
import com.example.groceriesapp.vision.parsing.GraniteModelState
import com.example.groceriesapp.vision.parsing.ItemExtractionEngine
import java.util.Locale
import kotlinx.coroutines.launch

/** Sub-screens reachable from the settings list. */
private enum class SettingsDetail(val title: String) {
    HOUSEHOLDS("Households"),
    BIO_COMPANY("BIO COMPANY"),
    REWE("REWE"),
    OPENAI("OpenAI"),
    TEXT_RECOGNITION("Text recognition"),
    ITEM_EXTRACTION("Item extraction"),
    ABOUT("About"),
}

@Composable
fun SettingsScreen(
    householdRepo: HouseholdRepository,
    userName: String?,
    onUserNameChanged: (String?) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    ocrEngine: OcrEngine,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    itemExtractionEngine: ItemExtractionEngine,
    onItemExtractionEngineChanged: (ItemExtractionEngine) -> Unit,
    bioCompanyRepo: com.example.groceriesapp.data.BioCompanyRepository,
    onOpenBioCompanyReceipts: () -> Unit,
    reweRepo: com.example.groceriesapp.data.ReweRepository,
    onConnectRewe: () -> Unit,
    onOpenReweReceipts: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when Settings renders as a full-screen sub-destination rather than a tab. */
    onBack: (() -> Unit)? = null,
    /**
     * Fired after a household is deleted, so the caller can re-read anything the deletion rewrote
     * behind its back — the shared receipt index, whose import records the deletion releases.
     */
    onHouseholdDeleted: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val modelManager = remember(context) { GlmOcrModelManager(context) }
    val liteRtModelManager = remember(context) { GlmOcrLiteRtModelManager(context) }
    val modelState = remember { modelManager.state() }
    val liteRtModelState = remember { liteRtModelManager.state() }
    val graniteModelManager = remember(context) { GraniteModelManager(context) }
    val graniteModelState = remember { graniteModelManager.state() }
    val openAiKeyStore = remember(context) { OpenAiKeyStore(context) }
    var openAiKeyPresent by remember(openAiKeyStore) { mutableStateOf(openAiKeyStore.hasKey()) }
    val coroutineScope = rememberCoroutineScope()
    val account by bioCompanyRepo.account.collectAsStateWithLifecycle()
    val loginState by bioCompanyRepo.loginState.collectAsStateWithLifecycle()
    val syncState by bioCompanyRepo.syncState.collectAsStateWithLifecycle()
    val points by bioCompanyRepo.points.collectAsStateWithLifecycle()
    val reweAccount by reweRepo.account.collectAsStateWithLifecycle()
    val reweCredentials by reweRepo.credentials.collectAsStateWithLifecycle()
    val reweLoginState by reweRepo.loginState.collectAsStateWithLifecycle()
    val reweSyncState by reweRepo.syncState.collectAsStateWithLifecycle()
    var reweCredentialError by remember { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }
    val households by householdRepo.households.collectAsStateWithLifecycle()
    val activeHouseholdId by householdRepo.activeHouseholdId.collectAsStateWithLifecycle()
    val importRewePem: (Uri, (String) -> Result<Unit>) -> Unit = { uri, importer ->
        reweCredentialError = null
        coroutineScope.launch {
            val pem = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (pem == null) {
                reweCredentialError = "Could not read the selected file."
            } else {
                importer(pem).onFailure { error ->
                    reweCredentialError = error.message ?: "Could not import the file."
                }
            }
        }
    }
    val reweCertificatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importRewePem(uri, reweRepo::importCertificate)
    }
    val rewePrivateKeyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importRewePem(uri, reweRepo::importPrivateKey)
    }

    val openDetail = detail
    if (openDetail != null) {
        BackHandler { detail = null }
        SettingsDetailScaffold(
            title = openDetail.title,
            onBack = { detail = null },
            modifier = modifier,
        ) {
            when (openDetail) {
                SettingsDetail.HOUSEHOLDS -> HouseholdsDetail(
                    households = households,
                    activeHouseholdId = activeHouseholdId,
                    onSelect = householdRepo::setActiveHousehold,
                    onCreate = { name -> householdRepo.createHousehold(name) },
                    onRename = householdRepo::renameHousehold,
                    onDelete = { id ->
                        householdRepo.deleteHousehold(id)
                        onHouseholdDeleted?.invoke(id)
                    },
                    entryCounts = householdRepo::storedEntryCounts,
                )
                SettingsDetail.BIO_COMPANY -> BioCompanyAccountCard(
                    account = account,
                    points = points,
                    loginState = loginState,
                    syncState = syncState,
                    onLogin = { email, password ->
                        coroutineScope.launch {
                            bioCompanyRepo.login(email, password)
                        }
                    },
                    onLogout = {
                        coroutineScope.launch {
                            bioCompanyRepo.logout()
                        }
                    },
                    onFetchReceipts = {
                        coroutineScope.launch {
                            bioCompanyRepo.fetchReceipts()
                            bioCompanyRepo.fetchPoints()
                        }
                    },
                    onOpenReceipts = onOpenBioCompanyReceipts,
                )
                SettingsDetail.REWE -> ReweAccountCard(
                    account = reweAccount,
                    credentials = reweCredentials,
                    loginState = reweLoginState,
                    syncState = reweSyncState,
                    credentialError = reweCredentialError,
                    onImportCertificate = {
                        reweCertificatePicker.launch(arrayOf("*/*"))
                    },
                    onImportPrivateKey = {
                        rewePrivateKeyPicker.launch(arrayOf("*/*"))
                    },
                    onConnect = onConnectRewe,
                    onDisconnect = {
                        coroutineScope.launch { reweRepo.logout() }
                    },
                    onFetchReceipts = {
                        coroutineScope.launch { reweRepo.fetchReceipts() }
                    },
                    onOpenReceipts = onOpenReweReceipts,
                )
                SettingsDetail.OPENAI -> OpenAiDetail(
                    keyStore = openAiKeyStore,
                    keyPresent = openAiKeyPresent,
                    onKeyPresenceChanged = { present ->
                        openAiKeyPresent = present
                        // Extraction can't run without a key, so don't leave it selected.
                        if (!present && itemExtractionEngine == ItemExtractionEngine.OPENAI) {
                            onItemExtractionEngineChanged(ItemExtractionEngine.BUILT_IN_RULES)
                        }
                    },
                )
                SettingsDetail.TEXT_RECOGNITION -> TextRecognitionDetail(
                    ocrEngine = ocrEngine,
                    onOcrEngineChanged = onOcrEngineChanged,
                    modelState = modelState,
                    liteRtModelState = liteRtModelState,
                )
                SettingsDetail.ITEM_EXTRACTION -> ItemExtractionDetail(
                    engine = itemExtractionEngine,
                    onEngineChanged = onItemExtractionEngineChanged,
                    graniteState = graniteModelState,
                    openAiKeyPresent = openAiKeyPresent,
                    openAiTextModel = openAiKeyStore.textModel(),
                    onOpenOpenAiSettings = { detail = SettingsDetail.OPENAI },
                )
                SettingsDetail.ABOUT -> AttributionCard()
            }
        }
        return
    }

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }
    SettingsHome(
        userName = userName,
        onUserNameChanged = onUserNameChanged,
        activeHouseholdName = households.firstOrNull { it.id == activeHouseholdId }?.name,
        householdCount = households.size,
        darkTheme = darkTheme,
        onDarkThemeChanged = onDarkThemeChanged,
        ocrEngine = ocrEngine,
        itemExtractionEngine = itemExtractionEngine,
        bioCompanyConnected = account != null,
        reweConnected = reweAccount != null,
        openAiConnected = openAiKeyPresent,
        onOpenDetail = { detail = it },
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    userName: String?,
    onUserNameChanged: (String?) -> Unit,
    activeHouseholdName: String?,
    householdCount: Int,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    ocrEngine: OcrEngine,
    itemExtractionEngine: ItemExtractionEngine,
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    openAiConnected: Boolean,
    onOpenDetail: (SettingsDetail) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var nameDialogOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Profile",
                        supportingText = "Shown in the Home greeting. Stays on this device.",
                    )
                    SettingsGroup {
                        SettingsRow(
                            headline = userName ?: "Set your name",
                            supportingText = if (userName == null) {
                                "Add a name for a personal greeting"
                            } else {
                                "Tap to change"
                            },
                            leadingContent = { InitialsBadge(userName, size = 40.dp) },
                            trailingContent = { SettingsChevron() },
                            onClick = { nameDialogOpen = true },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Household",
                        supportingText = "Each household keeps its own inventory and consumption " +
                            "history. Receipts are shared, so you can add a shop's items to " +
                            "whichever household they belong to.",
                    )
                    SettingsGroup {
                        SettingsRow(
                            headline = activeHouseholdName ?: "Households",
                            supportingText = if (householdCount > 1) {
                                "$householdCount households"
                            } else {
                                "Only household"
                            },
                            leadingContent = {
                                InitialsBadge(activeHouseholdName, size = 40.dp)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.HOUSEHOLDS) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader(
                        title = "Accounts & Integrations",
                        supportingText = "Connect your grocery accounts to import receipts, and " +
                            "an OpenAI key for the app's AI features.",
                    )
                    SettingsGroup {
                        AccountRow(
                            merchant = "BIO COMPANY",
                            connected = bioCompanyConnected,
                            onClick = { onOpenDetail(SettingsDetail.BIO_COMPANY) },
                        )
                        SettingsRowDivider(inset = 72.dp)
                        AccountRow(
                            merchant = "REWE",
                            connected = reweConnected,
                            onClick = { onOpenDetail(SettingsDetail.REWE) },
                        )
                        SettingsRowDivider(inset = 72.dp)
                        SettingsRow(
                            headline = "OpenAI",
                            supportingText = if (openAiConnected) {
                                "API key saved"
                            } else {
                                "No API key"
                            },
                            supportingTextColor = if (openAiConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            // "Open AI" so the badge reads OA rather than OP.
                            leadingContent = { InitialsBadge("Open AI", size = 40.dp) },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.OPENAI) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("Appearance")
                    SettingsGroup {
                        SettingsRow(
                            headline = "Dark theme",
                            supportingText = "Designed for tracked receipt panoramas",
                            leadingContent = {
                                Icon(
                                    if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = darkTheme,
                                    onCheckedChange = onDarkThemeChanged,
                                )
                            },
                            onClick = { onDarkThemeChanged(!darkTheme) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("Receipt processing")
                    SettingsGroup {
                        SettingsRow(
                            headline = "Text recognition",
                            supportingText = ocrEngine.displayName,
                            leadingContent = {
                                Icon(Icons.Default.Memory, contentDescription = null)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.TEXT_RECOGNITION) },
                        )
                        SettingsRowDivider(inset = 56.dp)
                        SettingsRow(
                            headline = "Item extraction",
                            supportingText = itemExtractionEngine.displayName,
                            leadingContent = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.ITEM_EXTRACTION) },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionHeader("App")
                    SettingsGroup {
                        SettingsRow(
                            headline = "About",
                            supportingText = "Icon and shelf-life data attributions",
                            leadingContent = {
                                Icon(Icons.Default.Info, contentDescription = null)
                            },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenDetail(SettingsDetail.ABOUT) },
                        )
                    }
                }
            }
        }
    }

    if (nameDialogOpen) {
        UserNameDialog(
            initialName = userName.orEmpty(),
            onConfirm = { name ->
                onUserNameChanged(name)
                nameDialogOpen = false
            },
            onDismiss = { nameDialogOpen = false },
        )
    }
}

@Composable
private fun UserNameDialog(
    initialName: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, contentDescription = null) },
        title = { Text("Your name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                // Leaving the field empty is a valid way to clear the name again.
                supportingText = { Text("Leave empty for a greeting without a name.") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().takeIf(String::isNotEmpty)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HouseholdsDetail(
    households: List<Household>,
    activeHouseholdId: String,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    entryCounts: (String) -> HouseholdEntryCounts,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    // Ids rather than Household objects, so the dialogs survive rotation without a custom Saver.
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        SettingsGroup {
            households.forEachIndexed { index, household ->
                if (index > 0) SettingsRowDivider(inset = 72.dp)
                HouseholdRow(
                    household = household,
                    active = household.id == activeHouseholdId,
                    // Deleting the only household would leave the app with nowhere to store data.
                    deletable = households.size > 1,
                    onSelect = { onSelect(household.id) },
                    onRename = { renameTargetId = household.id },
                    onDelete = { deleteTargetId = household.id },
                )
            }
            SettingsRowDivider(inset = 72.dp)
            SettingsRow(
                headline = "Add household",
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { showCreateDialog = true },
            )
        }
        Text(
            text = "Switching households reloads the app with that household's inventory and " +
                "returns you to the home screen. Receipts stay the same in every household — " +
                "adding a receipt's items puts them in whichever household is active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
        )
    }

    if (showCreateDialog) {
        HouseholdNameDialog(
            title = "New household",
            confirmLabel = "Create",
            initialName = "",
            existing = households,
            excludingId = null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreate(name)
            },
        )
    }

    val renameTarget = households.firstOrNull { it.id == renameTargetId }
    if (renameTarget != null) {
        HouseholdNameDialog(
            title = "Rename household",
            confirmLabel = "Save",
            initialName = renameTarget.name,
            existing = households,
            excludingId = renameTarget.id,
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                renameTargetId = null
                onRename(renameTarget.id, name)
            },
        )
    }

    val deleteTarget = households.firstOrNull { it.id == deleteTargetId }
    if (deleteTarget != null) {
        DeleteHouseholdDialog(
            household = deleteTarget,
            counts = remember(deleteTarget.id) { entryCounts(deleteTarget.id) },
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                deleteTargetId = null
                onDelete(deleteTarget.id)
            },
        )
    }
}

@Composable
private fun HouseholdRow(
    household: Household,
    active: Boolean,
    deletable: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SettingsRow(
        headline = household.name,
        supportingText = if (active) "Active" else null,
        supportingTextColor = MaterialTheme.colorScheme.primary,
        leadingContent = { InitialsBadge(household.name, size = 40.dp) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options for ${household.name}",
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    if (deletable) {
                        DropdownMenuItem(
                            text = {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
        onClick = onSelect,
    )
}

/** Shared by create and rename: the only difference is the labels and which name is excluded. */
@Composable
private fun HouseholdNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    existing: List<Household>,
    excludingId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    // Suppress the "enter a name" message on an untouched field — it reads as an error the user
    // caused, when they simply have not typed yet.
    var edited by rememberSaveable { mutableStateOf(false) }
    val error = householdNameError(name, existing, excludingId)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Home, contentDescription = null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    edited = true
                },
                label = { Text("Name") },
                singleLine = true,
                isError = edited && error != null,
                // The slot is always filled so the dialog keeps its height when the error appears.
                supportingText = {
                    if (edited && error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Shown wherever you switch households.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = error == null, onClick = { onConfirm(name) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteHouseholdDialog(
    household: Household,
    counts: HouseholdEntryCounts,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
        title = { Text("Delete \"${household.name}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "The inventory and consumption history in this household will be permanently " +
                        "removed. Your receipts are shared and are not affected, nor are recipes " +
                        "or connected accounts.",
                )
                // There is no export and no undo, so spell out the size of what is being lost.
                val summary = counts.summary()
                if (summary != null) {
                    Text(summary, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Null when neither count could be read — better to say nothing than to imply a confident zero. */
private fun HouseholdEntryCounts.summary(): String? {
    val parts = buildList {
        inventoryItems?.let { add(if (it == 1) "1 inventory item" else "$it inventory items") }
        consumptionEvents?.let {
            add(if (it == 1) "1 consumption entry" else "$it consumption entries")
        }
    }
    if (parts.isEmpty()) return null
    return "This deletes " + parts.joinToString(" and ") + "."
}

@Composable
private fun AccountRow(
    merchant: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        headline = merchant,
        supportingText = if (connected) "Connected" else "Not connected",
        supportingTextColor = if (connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        leadingContent = { MerchantBadge(merchant, size = 40.dp) },
        trailingContent = { SettingsChevron() },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item { content() }
        }
    }
}

@Composable
private fun TextRecognitionDetail(
    ocrEngine: OcrEngine,
    onOcrEngineChanged: (OcrEngine) -> Unit,
    modelState: GlmOcrModelState,
    liteRtModelState: GlmOcrLiteRtModelState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        OcrEngineCard(
            engine = OcrEngine.ML_KIT,
            selected = ocrEngine == OcrEngine.ML_KIT,
            enabled = true,
            description = "Fast bundled Latin text recognition with line positions.",
            onSelect = { onOcrEngineChanged(OcrEngine.ML_KIT) },
        )
        OcrEngineCard(
            engine = OcrEngine.GLM_OCR,
            selected = ocrEngine == OcrEngine.GLM_OCR,
            enabled = modelState.ready,
            description = if (modelState.ready) {
                "Higher-quality generative OCR using the bundled GGUF models."
            } else {
                "Not installed on this device yet."
            },
            onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR) },
        )
        if (ocrEngine == OcrEngine.GLM_OCR) {
            GlmOcrModelCard(state = modelState)
        }
        OcrEngineCard(
            engine = OcrEngine.GLM_OCR_LITERT,
            selected = ocrEngine == OcrEngine.GLM_OCR_LITERT,
            enabled = liteRtModelState.ready,
            description = if (liteRtModelState.ready) {
                "Experimental GLM-OCR using the bundled LiteRT vision and decoder models."
            } else {
                "Not installed on this device yet."
            },
            onSelect = { onOcrEngineChanged(OcrEngine.GLM_OCR_LITERT) },
        )
        if (ocrEngine == OcrEngine.GLM_OCR_LITERT) {
            GlmOcrLiteRtModelCard(state = liteRtModelState)
        }
    }
}

@Composable
private fun AttributionCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("Grocery icons", style = MaterialTheme.typography.titleSmall)
            Text(
                "Icons by OpenMoji (openmoji.org), licensed under CC BY-SA 4.0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Shelf-life estimates", style = MaterialTheme.typography.titleSmall)
            Text(
                "Storage times derived from the USDA FSIS FoodKeeper dataset (public domain), " +
                    "with stricter values for a few items where German practice differs. " +
                    "Estimates only — the date printed on the packaging always takes precedence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OcrEngineCard(
    engine: OcrEngine,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onSelect: () -> Unit,
) {
    EngineRadioCard(
        title = engine.displayName,
        description = description,
        icon = if (engine == OcrEngine.ML_KIT) Icons.Default.Memory else Icons.Default.AutoAwesome,
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
    )
}

@Composable
private fun EngineRadioCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ItemExtractionDetail(
    engine: ItemExtractionEngine,
    onEngineChanged: (ItemExtractionEngine) -> Unit,
    graniteState: GraniteModelState,
    openAiKeyPresent: Boolean,
    openAiTextModel: String,
    onOpenOpenAiSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        EngineRadioCard(
            title = ItemExtractionEngine.BUILT_IN_RULES.displayName,
            description = "Fast offline parsing with hand-tuned rules for German receipts.",
            icon = Icons.Default.Memory,
            selected = engine == ItemExtractionEngine.BUILT_IN_RULES,
            enabled = true,
            onSelect = { onEngineChanged(ItemExtractionEngine.BUILT_IN_RULES) },
        )
        EngineRadioCard(
            title = ItemExtractionEngine.GRANITE.displayName,
            description = if (graniteState.ready) {
                "On-device Granite 4.0 350M language model via LiteRT-LM."
            } else {
                "Not installed on this device yet."
            },
            icon = Icons.Default.AutoAwesome,
            selected = engine == ItemExtractionEngine.GRANITE,
            enabled = graniteState.ready,
            onSelect = { onEngineChanged(ItemExtractionEngine.GRANITE) },
        )
        if (engine == ItemExtractionEngine.GRANITE) {
            GraniteModelCard(state = graniteState)
        }
        EngineRadioCard(
            title = ItemExtractionEngine.OPENAI.displayName,
            description = if (openAiKeyPresent) {
                "Sends receipt text to OpenAI ($openAiTextModel)."
            } else {
                "Needs an API key — add one under Settings › OpenAI."
            },
            icon = Icons.Default.AutoAwesome,
            selected = engine == ItemExtractionEngine.OPENAI,
            enabled = openAiKeyPresent,
            onSelect = { onEngineChanged(ItemExtractionEngine.OPENAI) },
        )
        if (!openAiKeyPresent) {
            SettingsGroup {
                SettingsRow(
                    headline = "OpenAI",
                    supportingText = "No API key",
                    leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                    trailingContent = { SettingsChevron() },
                    onClick = onOpenOpenAiSettings,
                )
            }
        }
        Text(
            "Extraction falls back to the built-in rules if the selected engine fails.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GraniteModelCard(state: GraniteModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("Granite model file", style = MaterialTheme.typography.titleMedium)
            Text(
                "The Granite 4.0 350M .litertlm file is provisioned with the app and can't be " +
                    "changed on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Model",
                name = state.modelName,
                sizeBytes = state.modelSizeBytes,
            )
        }
    }
}

/**
 * One key for every OpenAI-backed feature: receipt item extraction, recipe suggestions and
 * recipe images all read it from the same store, so it lives in the main settings rather than
 * inside any single feature's screen.
 */
private enum class OpenAiRegionOption { EU, US, CUSTOM }

@Composable
private fun OpenAiDetail(
    keyStore: OpenAiKeyStore,
    keyPresent: Boolean,
    onKeyPresenceChanged: (Boolean) -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    val storedBase = remember { keyStore.apiBase() }
    var regionOption by remember {
        mutableStateOf(
            when (storedBase) {
                OpenAiKeyStore.EU_API_BASE -> OpenAiRegionOption.EU
                OpenAiKeyStore.US_API_BASE -> OpenAiRegionOption.US
                else -> OpenAiRegionOption.CUSTOM
            },
        )
    }
    var customBaseInput by remember {
        mutableStateOf(if (regionOption == OpenAiRegionOption.CUSTOM) storedBase else "")
    }
    var textModelInput by remember { mutableStateOf(keyStore.textModel()) }
    var imageModelInput by remember { mutableStateOf(keyStore.imageModel()) }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text("OpenAI API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (keyPresent) {
                        "An API key is stored on this device, encrypted."
                    } else {
                        "Paste an OpenAI API key to turn on the app's AI features."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (keyPresent) "Replace API key" else "API key") },
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        // Nothing to reveal on an empty field, so the toggle only appears once
                        // there is a key in the box.
                        if (keyInput.isNotEmpty()) {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (keyVisible) {
                                        "Hide API key"
                                    } else {
                                        "Show API key"
                                    },
                                )
                            }
                        }
                    },
                    // Autocorrect off keeps the secret out of the keyboard's learned-word store.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        MaterialTheme.spacing.small,
                        Alignment.End,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (keyPresent) {
                        TextButton(
                            onClick = {
                                keyStore.setApiKey(null)
                                onKeyPresenceChanged(false)
                            },
                        ) {
                            Text("Remove key", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Button(
                        onClick = {
                            keyStore.setApiKey(keyInput)
                            keyInput = ""
                            // Otherwise the next key typed into the box starts out in plaintext.
                            keyVisible = false
                            onKeyPresenceChanged(true)
                        },
                        enabled = keyInput.isNotBlank(),
                    ) {
                        Text("Save key")
                    }
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text("API region", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Which OpenAI endpoint receipt and recipe requests go to. EU keeps data " +
                        "in-region.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EngineRadioCard(
                    title = "European Union",
                    description = OpenAiKeyStore.EU_API_BASE,
                    icon = Icons.Default.Public,
                    selected = regionOption == OpenAiRegionOption.EU,
                    enabled = true,
                    onSelect = {
                        regionOption = OpenAiRegionOption.EU
                        keyStore.setApiBase(OpenAiKeyStore.EU_API_BASE)
                    },
                )
                EngineRadioCard(
                    title = "United States",
                    description = OpenAiKeyStore.US_API_BASE,
                    icon = Icons.Default.Public,
                    selected = regionOption == OpenAiRegionOption.US,
                    enabled = true,
                    onSelect = {
                        regionOption = OpenAiRegionOption.US
                        keyStore.setApiBase(OpenAiKeyStore.US_API_BASE)
                    },
                )
                EngineRadioCard(
                    title = "Custom",
                    description = "A self-hosted or proxy base URL.",
                    icon = Icons.Default.Public,
                    selected = regionOption == OpenAiRegionOption.CUSTOM,
                    enabled = true,
                    onSelect = {
                        regionOption = OpenAiRegionOption.CUSTOM
                        if (customBaseInput.isNotBlank()) {
                            keyStore.setApiBase(customBaseInput)
                        }
                    },
                )
                if (regionOption == OpenAiRegionOption.CUSTOM) {
                    OutlinedTextField(
                        value = customBaseInput,
                        onValueChange = {
                            customBaseInput = it
                            keyStore.setApiBase(it)
                        },
                        label = { Text("Base URL") },
                        placeholder = { Text(OpenAiKeyStore.EU_API_BASE) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Falls back to the EU default until you enter one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Which OpenAI model each feature calls. Must be a model your key can access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = textModelInput,
                    onValueChange = {
                        textModelInput = it
                        keyStore.setTextModel(it)
                    },
                    label = { Text("Text model") },
                    placeholder = { Text(OpenAiKeyStore.DEFAULT_TEXT_MODEL) },
                    supportingText = { Text("Receipt item extraction and recipe suggestions.") },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imageModelInput,
                    onValueChange = {
                        imageModelInput = it
                        keyStore.setImageModel(it)
                    },
                    label = { Text("Image model") },
                    placeholder = { Text(OpenAiKeyStore.DEFAULT_IMAGE_MODEL) },
                    supportingText = { Text("Recipe photo generation.") },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text("What the key is used for", style = MaterialTheme.typography.titleMedium)
                OpenAiUsageRow(
                    title = "Item extraction",
                    description = "Sends receipt text to $textModelInput when that engine is " +
                        "selected. Falls back to the built-in rules on any error.",
                )
                OpenAiUsageRow(
                    title = "Recipe ideas",
                    description = "Suggests recipes from what's in your inventory.",
                )
                OpenAiUsageRow(
                    title = "Recipe images",
                    description = "Generates a picture for a recipe that has none.",
                )
                Text(
                    "Nothing is sent to OpenAI until you use one of these features. Without a key " +
                        "the app stays fully offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OpenAiUsageRow(title: String, description: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GlmOcrModelCard(state: GlmOcrModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR model files", style = MaterialTheme.typography.titleMedium)
            Text(
                "The GGUF decoder and mmproj pair are provisioned with the app and can't be " +
                    "changed on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Decoder",
                name = state.decoderName,
                sizeBytes = state.decoderSizeBytes,
            )
            ModelFileRow(
                label = "Vision projector",
                name = state.projectorName,
                sizeBytes = state.projectorSizeBytes,
            )
            if (!state.runtimeAvailable) {
                Text(
                    "The bundled GLM-OCR runtime requires an arm64 Android device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "GLM-OCR returns text without ML Kit line boxes, so receipt highlighting is " +
                    "unavailable for GLM-OCR results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlmOcrLiteRtModelCard(state: GlmOcrLiteRtModelState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text("GLM-OCR LiteRT files", style = MaterialTheme.typography.titleMedium)
            Text(
                "The fixed 644 × 952 vision encoder and cached decoder are provisioned with the " +
                    "app and can't be changed on-device. First use can take several minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelFileRow(
                label = "Vision encoder",
                name = state.visionSizeBytes?.let { GlmOcrLiteRtModelManager.VISION_FILE },
                sizeBytes = state.visionSizeBytes,
            )
            ModelFileRow(
                label = "Cached decoder",
                name = state.decoderSizeBytes?.let { GlmOcrLiteRtModelManager.DECODER_FILE },
                sizeBytes = state.decoderSizeBytes,
            )
            Text(
                "LiteRT output has no line boxes, so receipt highlighting is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelFileRow(
    label: String,
    name: String?,
    sizeBytes: Long?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                name ?: "Not installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        sizeBytes?.let {
            Text(
                formatFileSize(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)
    }
