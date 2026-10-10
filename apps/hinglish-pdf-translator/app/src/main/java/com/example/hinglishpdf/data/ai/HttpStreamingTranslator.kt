package com.example.hinglishpdf.data.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Shared plumbing for the providers' streaming HTTP APIs: one POST with a
 * JSON body, answered with server-sent events ("data: {...}" lines). Each
 * subclass supplies its own endpoint, headers and JSON shapes, and sorts its
 * own error answers into [TranslatorException]s.
 *
 * Uses only HttpURLConnection and Gson, so it runs unchanged in JVM tests.
 */
abstract class HttpStreamingTranslator(
    protected val providerName: String,
    protected val model: String,
) : AITranslator {

    protected class Request(val url: String, val headers: Map<String, String>, val body: JsonObject)

    /** The request for one chunk. [temperature] is false once the model has said it doesn't take one. */
    protected abstract fun request(chunk: String, temperature: Boolean): Request

    /**
     * The new text in one streamed event (null or "" if it has none). Throws
     * [TranslatorException] for an error or a refusal reported mid-stream.
     */
    protected abstract fun textOf(event: JsonObject): String?

    /** Sorts an HTTP error answer ([status] is not 2xx) into what the app should do about it. */
    protected abstract fun classify(status: Int, body: String, retryAfterMillis: Long?): TranslatorException

    /**
     * Newer models of some providers accept only their default temperature.
     * Once one says so, requests to it are sent without one.
     */
    @Volatile
    private var temperatureRejected = false

    override fun translate(chunk: String): Flow<String> = flow {
        while (true) {
            val sent = stream(request(chunk, temperature = !temperatureRejected), this)
            if (sent) return@flow
            temperatureRejected = true // the model refused the temperature: same request without it
        }
    }.flowOn(Dispatchers.IO)

    /** One HTTP request. Returns false (nothing emitted) if it must be repeated without a temperature. */
    private suspend fun stream(request: Request, out: FlowCollector<String>): Boolean = coroutineScope {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        // A blocking socket read ignores coroutine cancellation (Pause): closing
        // the connection from here makes the read fail at once instead.
        val watchdog = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            request.headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(request.body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val body = (connection.errorStream ?: runCatching { connection.inputStream }.getOrNull())
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (status == 400 && !temperatureRejected && request.body.has("temperature") &&
                    TEMPERATURE.containsMatchIn(body)
                ) {
                    return@coroutineScope false
                }
                throw classify(status, body, retryAfterMillis(connection.getHeaderField("Retry-After")))
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val data = StringBuilder()
                suspend fun dispatch() {
                    if (data.isEmpty()) return
                    val payload = data.toString().trim()
                    data.clear()
                    if (payload.isEmpty() || payload == "[DONE]") return
                    val event = parse(payload) ?: return
                    textOf(event)?.takeIf { it.isNotEmpty() }?.let { out.emit(it) }
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> dispatch() // a blank line ends one event
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                        // "event:", "id:", ": keep-alive" lines carry nothing we need.
                    }
                }
                dispatch()
            }
            true
        } catch (e: TranslatorException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw TranslatorException.Transient("$providerName took too long to answer", null, e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // closed by the watchdog: just cancelled
            throw TranslatorException.Transient("No internet connection", null, e)
        } catch (e: JsonParseException) {
            throw TranslatorException.Transient("$providerName sent an answer this app couldn't read", null, e)
        } finally {
            watchdog.cancel()
            connection.disconnect()
        }
    }

    private fun parse(payload: String): JsonObject? =
        JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject

    protected companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private val TEMPERATURE = Regex("temperature", RegexOption.IGNORE_CASE)

        /** "Retry-After: 23" (seconds); HTTP dates are rare here and ignored. */
        fun retryAfterMillis(header: String?): Long? =
            header?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() + 500 }

        /** The JSON object in an error answer, or null if the body is not JSON. */
        fun errorObject(body: String): JsonObject? = runCatching {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                ?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString

        fun JsonObject.obj(name: String): JsonObject? =
            get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        fun JsonObject.firstOf(name: String): JsonObject? =
            get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject

        /** The provider's own words, shortened, for messages shown in the app. */
        fun detail(message: String?): String =
            message.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()

        fun jsonObject(build: JsonObject.() -> Unit) = JsonObject().apply(build)
    }
}
