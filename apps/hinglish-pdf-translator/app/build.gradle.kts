plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.hinglishpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hinglishpdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    // MediaPipe's LLM engine is a ~27 MB native library per ABI, so build one
    // APK per ABI: arm64-v8a for real phones, x86_64 for the emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // A bundled model must be stored uncompressed, otherwise it cannot be
    // streamed out of the APK and copying it costs twice the memory.
    androidResources {
        noCompress += listOf("task", "bin", "tflite")
    }

    packaging {
        // Compress the native libraries in the APK (roughly halves the download);
        // Android extracts them once at install time.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Post-quantum crypto tables pulled in via PDFBox's BouncyCastle
            // dependency; PDF decryption never uses them.
            excludes += "org/bouncycastle/pqc/**"
        }
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

    // --- Coroutines & Flow ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- PDF text extraction (Apache PDFBox port for Android) ---
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // --- On-device LLM inference (MediaPipe LLM Inference API) ---
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
}
