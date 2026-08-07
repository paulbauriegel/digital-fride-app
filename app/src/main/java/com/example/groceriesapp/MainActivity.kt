package com.example.groceriesapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.ui.GroceriesApp
import com.example.groceriesapp.ui.theme.GroceriesTheme
import com.example.groceriesapp.vision.ocr.GlmOcrModelManager
import com.example.groceriesapp.vision.ocr.GlmOcrLiteRtModelManager
import com.example.groceriesapp.vision.ocr.OcrEngine

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = GroceriesRepository(applicationContext)
        val bioCompanyRepository = BioCompanyRepository(applicationContext)
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)

        setContent {
            var darkTheme by remember {
                mutableStateOf(preferences.getBoolean("dark_theme", true))
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
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            GroceriesTheme(darkTheme = darkTheme) {
                GroceriesApp(
                    repository = repository,
                    bioCompanyRepository = bioCompanyRepository,
                    widthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
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
                )
            }
        }
    }
}
