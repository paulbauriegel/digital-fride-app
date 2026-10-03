import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Values that must not be in the public source: local.properties (gitignored) first, then a Gradle
// property, then an environment variable (GitHub secrets in CI). Missing values become "".
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun privateValue(localKey: String, envKey: String): String =
    localProperties.getProperty(localKey)
        ?: providers.gradleProperty(localKey).orNull
        ?: providers.environmentVariable(envKey).orNull
        ?: ""

android {
    namespace = "com.example.groceriesapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.groceriesapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Bio Company app cipher (hex). Without it the card-points call reports that it is unavailable.
        buildConfigField("String", "BIOCOMPANY_AES_KEY", "\"${privateValue("biocompany.aesKey", "BIOCOMPANY_AES_KEY")}\"")
        buildConfigField("String", "BIOCOMPANY_AES_IV", "\"${privateValue("biocompany.aesIv", "BIOCOMPANY_AES_IV")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // Lets android.util.Log no-op in unit tests (FallbackReceiptItemExtractor logs fallbacks).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.ar:core:1.45.0")
    implementation("org.opencv:opencv:4.13.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    // 1.4.x deliberately: the barcode scanner binds through ProcessCameraProvider.getInstance's
    // ListenableFuture, and the suspending awaitInstance replacement only exists from 1.5.
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Bundled model rather than play-services-mlkit-*: no on-demand download, so the first scan
    // works offline and on devices without Play Services.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.ai.edge.litert:litert:2.1.6")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Model downloads keep running (foreground, resumable) when the user leaves the app.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws in unit tests; Android supplies the real one at runtime.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
