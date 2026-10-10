import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Optional: bundle the model in the APK, so the app works with no import
 * step. `./gradlew assembleRelease -PembedModel` downloads Qwen 2.5 1.5B
 * (Apache-2.0, ~1.6 GB) once, at BUILD time, into build/qwenModel/, and adds
 * that folder to the APK's assets for this build only; the app itself still
 * never touches the network. The APK becomes ~1.6 GB.
 */
val embedModel = project.hasProperty("embedModel")
val qwenModelUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
    "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
val qwenAssetsDir = layout.buildDirectory.dir("qwenModel/assets")
val qwenAsset = qwenAssetsDir.map { it.file("Qwen2.5-1.5B-Instruct_q8_ekv1280.task") }

val downloadQwenModel by tasks.registering {
    description = "Downloads the Qwen 2.5 1.5B model into the app's assets (build machine only)."
    outputs.file(qwenAsset)
    doLast {
        val target = qwenAsset.get().asFile
        if (target.length() > 1_000_000_000L) return@doLast // already there
        target.parentFile.mkdirs()
        val partial = File(target.path + ".part")
        URI(qwenModelUrl).toURL().openStream().use { input ->
            partial.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
        }
        check(partial.renameTo(target)) { "Could not move the model into assets" }
    }
}

if (embedModel) {
    tasks.named("preBuild") { dependsOn(downloadQwenModel) }
}

android {
    namespace = "com.example.hinglishpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hinglishpdf"
        // Android 10+: MediaStore saves to Downloads without any storage
        // permission, and GPU inference needs a phone of that generation anyway.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    // One APK per ABI: arm64-v8a for real phones, x86_64 for the emulator.
    // With the model embedded only the phone APK is built (it is 1.6 GB).
    splits {
        abi {
            isEnable = true
            reset()
            if (embedModel) include("arm64-v8a") else include("arm64-v8a", "x86_64")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    // A bundled model must be stored uncompressed, otherwise it cannot be
    // streamed out of the APK and copying it costs twice the memory.
    androidResources {
        noCompress += listOf("task", "bin", "tflite")
    }

    if (embedModel) {
        sourceSets["main"].assets.srcDir(qwenAssetsDir)
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

    // --- On-device LLM inference: Qwen 2.5 1.5B on the GPU delegate ---
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    // Runs the Room DAOs against real SQLite on the JVM.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
