import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * The Gemini API key and model come from local.properties (git-ignored), or
 * from environment variables on a CI machine. They are compiled into
 * BuildConfig, so they never appear in the source code or in git:
 *
 *   GEMINI_API_KEY=your-key-from-aistudio.google.com
 *   GEMINI_MODEL=gemini-3.5-flash-lite      (optional)
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    (localProperties.getProperty(name) ?: System.getenv(name) ?: default).trim()

/** Escapes a value for a Java string literal in BuildConfig. */
fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.hinglishpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hinglishpdf"
        // Android 10+: MediaStore saves to Downloads without any storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "GEMINI_API_KEY", javaString(secret("GEMINI_API_KEY")))
        buildConfigField("String", "GEMINI_MODEL", javaString(secret("GEMINI_MODEL", "gemini-3.5-flash-lite")))
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

    // --- Translation: Google Gemini API (cloud) ---
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    // Runs the Room DAOs against real SQLite on the JVM.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
