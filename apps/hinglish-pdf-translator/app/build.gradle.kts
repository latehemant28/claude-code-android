import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * An optional Gemini API key is read from local.properties (git-ignored,
 * never committed), or else from the GEMINI_API_KEY environment variable,
 * and compiled into BuildConfig.GEMINI_API_KEY, so it never appears in the
 * source code (keys typed in the app are stored on the phone instead):
 *
 *   GEMINI_API_KEY=my_actual_key_here
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
// local.properties first, then the GEMINI_API_KEY environment variable (for CI and build scripts).
val geminiApiKey: String = (localProperties.getProperty("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY")).orEmpty().trim()

android {
    namespace = "com.example.hinglishpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hinglishpdf"
        // Android 10+: MediaStore saves to Downloads without any storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "4.4"

        // Quotes and backslashes escaped so any key is a valid Java string literal.
        val escapedKey = geminiApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$escapedKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Post-quantum crypto tables pulled in via PDFBox's BouncyCastle
            // dependency; PDF decryption never uses them.
            excludes += "org/bouncycastle/pqc/**"
        }
    }
}

// Room writes each database version's schema here, so migrations can be checked against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Jetpack Compose / Material 3 ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Downloadable Google Fonts (Devanagari reading fonts), fetched once via Play Services.
    implementation("androidx.compose.ui:ui-text-google-fonts")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // --- Room: page-by-page progress, survives crashes and reboots ---
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.google.code.gson:gson:2.11.0") // Room column converters

    // --- Coroutines & Flow ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // --- PDF text extraction (Apache PDFBox port for Android) ---
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // --- EPUB (XHTML) parsing and rewriting ---
    implementation("org.jsoup:jsoup:1.18.3")

    // --- Translation: Gemini, OpenAI, Anthropic and Groq over their HTTPS APIs ---
    // (HttpURLConnection + Gson, see data/ai; no provider SDK needed.)

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    // Runs the Room DAOs against real SQLite on the JVM.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Compose UI tests on the JVM (Robolectric): screens, buttons and messages.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The W3C EPUB validator: generated EPUBs must pass it.
    testImplementation("org.w3c:epubcheck:5.1.0")
}
