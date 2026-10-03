package com.example.groceriesapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.EdekaRepository
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.products.ProductLookupRepository
import com.example.groceriesapp.data.household.HouseholdRepository
import com.example.groceriesapp.data.recipes.RecipesRepository
import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.ui.GroceriesApp
import com.example.groceriesapp.ui.theme.GroceriesTheme
import com.example.groceriesapp.data.OpenAiKeyStore
import com.example.groceriesapp.vision.ocr.GlmOcrModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelManager
import com.example.groceriesapp.vision.ocr.OcrEngine
import com.example.groceriesapp.vision.parsing.GraniteModelManager
import com.example.groceriesapp.vision.parsing.ItemExtractionEngine
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incomingEdekaLink = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Constructing this runs the one-time storage migration, so it has to happen before any
        // GroceriesRepository is built against a household directory.
        val householdRepository = HouseholdRepository(applicationContext)
        // Parsed once and reused across household switches; re-reading the asset on every switch
        // would be the most expensive part of rebuilding the repository.
        val shelfLifeCatalog = ShelfLifeCatalog.fromAssets(applicationContext)
        val recipesRepository = RecipesRepository(applicationContext)
        val bioCompanyRepository = BioCompanyRepository(applicationContext)
        val reweRepository = ReweRepository(applicationContext)
        val edekaRepository = EdekaRepository(applicationContext)
        captureEdekaLink(intent)
        // Household-independent: a barcode means the same product whichever household is active.
        val productLookupRepository = ProductLookupRepository()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)

        setContent {
            var darkTheme by remember {
                mutableStateOf(preferences.getBoolean("dark_theme", true))
            }
            var userName by remember {
                mutableStateOf(preferences.getString("user_name", null)?.takeIf { it.isNotBlank() })
            }
            var ocrEngine by remember {
                val storedEngine = OcrEngine.fromPreference(
                    preferences.getString("ocr_engine", null),
                )
                mutableStateOf(
                    storedEngine.takeUnless { engine ->
                        when (engine) {
                            OcrEngine.GLM_OCR ->
                                !GlmOcrModelManager(applicationContext).state().ready
                            OcrEngine.GLM_OCR_LITERT ->
                                !GlmOcrLiteRtModelManager(applicationContext).state().ready
                            OcrEngine.ML_KIT -> false
                        }
                    } ?: OcrEngine.ML_KIT,
                )
            }
            var itemExtractionEngine by remember {
                val storedEngine = ItemExtractionEngine.fromPreference(
                    preferences.getString("item_extractor", null),
                )
                mutableStateOf(
                    storedEngine.takeUnless { engine ->
                        when (engine) {
                            ItemExtractionEngine.GRANITE ->
                                !GraniteModelManager(applicationContext).state().ready
                            ItemExtractionEngine.OPENAI ->
                                !OpenAiKeyStore(applicationContext).hasKey()
                            ItemExtractionEngine.BUILT_IN_RULES -> false
                        }
                    } ?: ItemExtractionEngine.BUILT_IN_RULES,
                )
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            val activeHouseholdId by householdRepository.activeHouseholdId
                .collectAsStateWithLifecycle()
            GroceriesTheme(darkTheme = darkTheme) {
                // key() rather than remember() alone: a fresh repository swaps the data, but every
                // rememberSaveable inside GroceriesApp — the open tab, the receipt under review,
                // per-tab scroll and filters, the active scan id — would otherwise carry the
                // previous household's state into the new one. Discarding the subtree resets all
                // of it in one move.
                key(activeHouseholdId) {
                    val repository = remember(activeHouseholdId) {
                        GroceriesRepository.forHousehold(
                            context = applicationContext,
                            householdId = activeHouseholdId,
                            shelfLifeCatalog = shelfLifeCatalog,
                        )
                    }
                    GroceriesApp(
                        repository = repository,
                        householdRepository = householdRepository,
                        recipesRepository = recipesRepository,
                        bioCompanyRepository = bioCompanyRepository,
                        reweRepository = reweRepository,
                        edekaRepository = edekaRepository,
                        incomingEdekaLink = incomingEdekaLink,
                        onEdekaLinkConsumed = { incomingEdekaLink.value = null },
                        productLookupRepository = productLookupRepository,
                        widthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
                        userName = userName,
                        onUserNameChanged = { name ->
                            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
                            userName = trimmed
                            preferences.edit {
                                if (trimmed == null) remove("user_name") else putString("user_name", trimmed)
                            }
                        },
                        darkTheme = darkTheme,
                        ocrEngine = ocrEngine,
                        onDarkThemeChanged = { enabled ->
                            darkTheme = enabled
                            preferences.edit { putBoolean("dark_theme", enabled) }
                        },
                        onOcrEngineChanged = { engine ->
                            ocrEngine = engine
                            preferences.edit { putString("ocr_engine", engine.preferenceValue) }
                        },
                        itemExtractionEngine = itemExtractionEngine,
                        onItemExtractionEngineChanged = { engine ->
                            itemExtractionEngine = engine
                            preferences.edit { putString("item_extractor", engine.preferenceValue) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureEdekaLink(intent)
    }

    private fun captureEdekaLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        if (url.startsWith("edeka://main/auth/redirect") ||
            url.startsWith("https://login.edeka/actions/start-login-edeka-app")
        ) {
            incomingEdekaLink.value = url
        }
    }
}
