package com.example.hinglishpdf.data.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Finds an on-device model (`.litertlm` for LiteRT-LM, `.task`/`.bin` for
 * MediaPipe) and makes sure it is available as a real file, because both
 * runtimes load models from a file path.
 *
 * Lookup order:
 *  1. Internal storage: `filesDir/models/` (where imports and asset copies land).
 *  2. App-specific external storage: `Android/data/<package>/files/models/`,
 *     the easiest `adb push` target; no storage permission required.
 *  3. A model bundled in `src/main/assets/`, copied once into internal storage.
 *
 * Users can also import a model they downloaded with [importModel].
 */
class ModelFileManager(private val context: Context) {

    private val internalDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val externalDir: File?
        get() = context.getExternalFilesDir("models")

    /** Returns a ready-to-load model file, or null if the device has none yet. */
    suspend fun findModel(onCopyProgress: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            listOfNotNull(internalDir, externalDir)
                .flatMap { dir -> dir.listFiles().orEmpty().filter(::isModelFile) }
                .maxByOrNull { it.lastModified() }
                ?: copyBundledModel(onCopyProgress)
        }

    /**
     * Copies a user-picked model (SAF [uri]) into internal storage, replacing
     * any previously imported model to free space. Returns the new file.
     */
    suspend fun importModel(uri: Uri, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val (name, size) = resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val n = if (nameIdx >= 0) c.getString(nameIdx) else null
                val s = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                (n ?: "model.task") to s
            } ?: ("model.task" to -1L)

            if (!isModelName(name)) {
                throw IOException("\"$name\" is not a supported model. Pick a .litertlm, .task or .bin file.")
            }

            val input = resolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected model file.")
            val target = File(internalDir, File(name).name)
            input.use { copyAtomically(it, target, size, onProgress) }

            internalDir.listFiles().orEmpty()
                .filter { isModelFile(it) && it != target }
                .forEach { it.delete() }
            target
        }

    private suspend fun copyBundledModel(onProgress: (Float) -> Unit): File? {
        val assetName = context.assets.list("").orEmpty().firstOrNull(::isModelName)
            ?: return null
        val target = File(internalDir, assetName)
        // Assets are stored uncompressed (see noCompress in build.gradle.kts),
        // so openFd() works and gives us the length for progress reporting.
        val length = runCatching { context.assets.openFd(assetName).use { it.length } }
            .getOrDefault(-1L)
        context.assets.open(assetName).use { copyAtomically(it, target, length, onProgress) }
        return target
    }

    /** Copies to a temp file first so a half-written model is never picked up. */
    private suspend fun copyAtomically(
        input: InputStream,
        target: File,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var lastReported = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (totalBytes > 0) {
                        val percent = (copied * 100 / totalBytes).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Could not save the model file.")
        } finally {
            tmp.delete()
        }
    }

    private fun isModelFile(file: File) = file.isFile && file.length() > 0 && isModelName(file.name)

    private fun isModelName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".task") || lower.endsWith(".bin")
    }
}
