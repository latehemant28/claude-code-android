package com.example.hinglishpdf.data.ai

import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Each provider's strategy against a local HTTP server: the request it
 * sends (endpoint, key header, system prompt, JSON shape), the streamed
 * answer it parses, and how it sorts the provider's error answers.
 */
class ProviderTranslatorsTest {

    private class Recorded(val path: String, val query: String?, val headers: Map<String, String>, val body: JsonObject)
    private class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val hangAfter: Boolean = false)

    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<Recorded>()
    private var replies: (Int) -> Reply = { Reply(200, "") }

    private val base get() = "http://127.0.0.1:${server.localPort}"

    /** A minimal HTTP/1.1 server: one request per connection, answered and closed. */
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input)
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { buf ->
            var read = 0
            while (read < buf.size) read += input.read(buf, read, buf.size - read).also { check(it >= 0) }
        }
        val uri = URI(requestLine.split(' ')[1])
        requests += Recorded(uri.path, uri.query, headers, JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)

        val reply = replies(requests.size)
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${reply.status} X\r\n")
        head.append("Content-Type: ${if (reply.status == 200) "text/event-stream" else "application/json"}\r\n")
        reply.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (!reply.hangAfter) head.append("Content-Length: ${bytes.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        val out = socket.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
        if (reply.hangAfter) Thread.sleep(60_000) // a stalled stream
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return line.toString(Charsets.US_ASCII.name())
    }

    @After
    fun stop() = server.close()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private fun answer(translator: AITranslator): String =
        runBlocking { translator.translate("Hello world.").toList().joinToString("") }

    private fun failure(translator: AITranslator): TranslatorException = try {
        answer(translator)
        fail("expected a TranslatorException")
        throw AssertionError()
    } catch (e: TranslatorException) {
        e
    }

    private fun error(status: Int, json: String, headers: Map<String, String> = emptyMap()): (Int) -> Reply =
        { Reply(status, json, headers) }

    // ------------------------------------------------------------- Gemini

    private fun gemini() = GeminiTranslator("gem-key", "gemini-x", baseUrl = "$base/v1beta")

    @Test
    fun `gemini request and streamed answer`() {
        replies = {
            Reply(
                200,
                sse(
                    """{"candidates":[{"content":{"parts":[{"text":"thinking…","thought":true},{"text":"नमस्ते "}]}}]}""",
                    """{"candidates":[{"content":{"parts":[{"thoughtSignature":"abc"}]}}]}""", // no text: skipped, not an error
                    """{"candidates":[{"content":{"parts":[{"text":"दुनिया।"}]},"finishReason":"STOP"}]}""",
                ),
            )
        }
        assertEquals("नमस्ते दुनिया।", answer(gemini()))

        val r = requests.single()
        assertEquals("/v1beta/models/gemini-x:streamGenerateContent", r.path)
        assertEquals("alt=sse", r.query)
        assertEquals("gem-key", r.headers["x-goog-api-key"])
        assertEquals(HinglishPrompt.SYSTEM_PROMPT, r.body["systemInstruction"].asJsonObject["parts"].asJsonArray[0].asJsonObject["text"].asString)
        val content = r.body["contents"].asJsonArray[0].asJsonObject
        assertEquals("user", content["role"].asString)
        assertEquals("Hello world.", content["parts"].asJsonArray[0].asJsonObject["text"].asString)
        assertEquals(0.2, r.body["generationConfig"].asJsonObject["temperature"].asDouble, 1e-9)
        assertTrue(r.body["safetySettings"].asJsonArray.all { it.asJsonObject["threshold"].asString == "BLOCK_NONE" })
    }

    @Test
    fun `gemini errors are sorted into what to do`() {
        replies = error(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertTrue(failure(gemini()) is TranslatorException.Fatal)

        replies = error(400, """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}""")
        assertTrue(failure(gemini()).message!!.contains("country"))

        replies = error(404, """{"error":{"code":404,"message":"models/gemini-x is not found for API version v1beta","status":"NOT_FOUND"}}""")
        assertTrue(failure(gemini()) is TranslatorException.ModelUnavailable)

        replies = error(429, """{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-x","status":"RESOURCE_EXHAUSTED"}}""")
        assertTrue(failure(gemini()) is TranslatorException.ModelUnavailable) // skip it, don't wait for it

        replies = error(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""")
        assertTrue(failure(gemini()) is TranslatorException.DailyQuota)

        replies = error(429, """{"error":{"code":429,"message":"Quota exceeded for metric ... Please retry in 23.4s.","status":"RESOURCE_EXHAUSTED","details":[{"retryDelay": "23s"}]}}""")
        val perMinute = failure(gemini()) as TranslatorException.Transient
        assertTrue(perMinute.rateLimited)
        assertEquals(24_400L, perMinute.retryAfterMillis)

        replies = error(503, """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""")
        val busy = failure(gemini()) as TranslatorException.Transient
        assertFalse(busy.rateLimited)
        assertNull(busy.retryAfterMillis)
    }

    @Test
    fun `gemini refusals keep the text in english`() {
        replies = { Reply(200, sse("""{"promptFeedback":{"blockReason":"OTHER"}}""")) }
        assertTrue(failure(gemini()) is TranslatorException.Blocked)
        replies = { Reply(200, sse("""{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"SAFETY"}]}""")) }
        assertTrue(failure(gemini()) is TranslatorException.Blocked)
    }

    @Test
    fun `an unreadable stream is retried, not a book stop`() {
        replies = { Reply(200, sse("""{"candidates":[{"content":""", "not json")) }
        val e = failure(gemini())
        assertTrue(e is TranslatorException.Transient && !e.rateLimited)
    }

    // ------------------------------------------------------------- OpenAI

    private fun openAi() = OpenAITranslator("sk-test", "gpt-test", endpoint = "$base/v1/chat/completions")

    @Test
    fun `openai request and streamed answer`() {
        replies = {
            Reply(
                200,
                sse(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
                    """{"choices":[{"index":0,"delta":{"content":"नमस्ते "},"finish_reason":null}]}""",
                    """{"choices":[{"index":0,"delta":{"content":"दुनिया।"},"finish_reason":null}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "[DONE]",
                ),
            )
        }
        assertEquals("नमस्ते दुनिया।", answer(openAi()))

        val r = requests.single()
        assertEquals("/v1/chat/completions", r.path)
        assertEquals("Bearer sk-test", r.headers["authorization"])
        assertEquals("gpt-test", r.body["model"].asString)
        assertTrue(r.body["stream"].asBoolean)
        val messages = r.body["messages"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"].asString })
        assertEquals(HinglishPrompt.SYSTEM_PROMPT, messages[0]["content"].asString)
        assertEquals("Hello world.", messages[1]["content"].asString)
    }

    @Test
    fun `a model that refuses a temperature is asked again without one`() {
        replies = { n ->
            if (n == 1) {
                Reply(400, """{"error":{"message":"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}""")
            } else {
                Reply(200, sse("""{"choices":[{"delta":{"content":"ठीक है"}}]}""", "[DONE]"))
            }
        }
        val translator = openAi()
        assertEquals("ठीक है", answer(translator))
        assertEquals(listOf(true, false), requests.map { it.body.has("temperature") })

        answer(translator) // remembered: no second failed request
        assertFalse(requests.last().body.has("temperature"))
        assertEquals(3, requests.size)
    }

    @Test
    fun `openai errors are sorted into what to do`() {
        replies = error(401, """{"error":{"message":"Incorrect API key provided: sk-test.","type":"invalid_request_error","code":"invalid_api_key"}}""")
        assertTrue(failure(openAi()) is TranslatorException.Fatal)

        replies = error(429, """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","code":"insufficient_quota"}}""")
        val noCredit = failure(openAi())
        assertTrue(noCredit is TranslatorException.Fatal && noCredit.message!!.contains("credit"))

        replies = error(429, """{"error":{"message":"Rate limit reached for gpt-test on requests per min (RPM)","code":"rate_limit_exceeded"}}""", mapOf("Retry-After" to "2"))
        val limited = failure(openAi()) as TranslatorException.Transient
        assertTrue(limited.rateLimited)
        assertEquals(2_500L, limited.retryAfterMillis)

        replies = error(429, """{"error":{"message":"Rate limit reached on tokens per min (TPM). Please try again in 1m30s.","code":"rate_limit_exceeded"}}""")
        assertEquals(90_500L, (failure(openAi()) as TranslatorException.Transient).retryAfterMillis)

        replies = error(404, """{"error":{"message":"The model `gpt-test` does not exist or you do not have access to it.","code":"model_not_found"}}""")
        assertTrue(failure(openAi()) is TranslatorException.ModelUnavailable)

        replies = error(400, """{"error":{"message":"This model's maximum context length is 128000 tokens.","code":"context_length_exceeded"}}""")
        assertTrue(failure(openAi()) is TranslatorException.Blocked) // the chunk is halved

        replies = { Reply(200, sse("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""")) }
        assertTrue(failure(openAi()) is TranslatorException.Blocked)

        replies = error(500, """{"error":{"message":"The server had an error"}}""")
        assertFalse((failure(openAi()) as TranslatorException.Transient).rateLimited)
    }

    // --------------------------------------------------------------- Groq

    private fun groq() = GroqTranslator("gsk-test", "llama-test", endpoint = "$base/openai/v1/chat/completions")

    @Test
    fun `groq uses the openai format with its own limits`() {
        replies = { Reply(200, sse("""{"choices":[{"delta":{"content":"हाँ"}}]}""", "[DONE]")) }
        assertEquals("हाँ", answer(groq()))
        assertEquals("/openai/v1/chat/completions", requests.single().path)
        assertEquals("Bearer gsk-test", requests.single().headers["authorization"])

        replies = error(413, """{"error":{"message":"Request too large for model `llama-test` on tokens per minute (TPM): Limit 6000, Requested 7400","type":"tokens","code":"rate_limit_exceeded"}}""")
        assertTrue(failure(groq()) is TranslatorException.Blocked)

        replies = error(429, """{"error":{"message":"Rate limit reached for model `llama-test` on tokens per day (TPD): Limit 100000, Used 99000. Please try again in 7m12.5s.","code":"rate_limit_exceeded"}}""")
        assertTrue(failure(groq()) is TranslatorException.DailyQuota)

        replies = error(400, """{"error":{"message":"The model `llama-test` has been decommissioned and is no longer supported.","code":"model_decommissioned"}}""")
        assertTrue(failure(groq()) is TranslatorException.ModelUnavailable)
    }

    // ---------------------------------------------------------- Anthropic

    private fun claude() = AnthropicTranslator("sk-ant-test", "claude-test", endpoint = "$base/v1/messages")

    @Test
    fun `anthropic request and streamed answer`() {
        replies = {
            Reply(
                200,
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: ping
                data: {"type":"ping"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"नमस्ते "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"दुनिया।"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
            )
        }
        assertEquals("नमस्ते दुनिया।", answer(claude()))

        val r = requests.single()
        assertEquals("/v1/messages", r.path)
        assertEquals("sk-ant-test", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertEquals("claude-test", r.body["model"].asString)
        assertEquals(HinglishPrompt.SYSTEM_PROMPT, r.body["system"].asString)
        assertTrue(r.body["max_tokens"].asInt >= 4_096)
        val message = r.body["messages"].asJsonArray.single().asJsonObject
        assertEquals("user", message["role"].asString)
        assertEquals("Hello world.", message["content"].asString)
    }

    @Test
    fun `anthropic errors are sorted into what to do`() {
        replies = error(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertTrue(failure(claude()) is TranslatorException.Fatal)

        replies = error(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-test"}}""")
        assertTrue(failure(claude()) is TranslatorException.ModelUnavailable)

        replies = error(429, """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}""", mapOf("retry-after" to "15"))
        val limited = failure(claude()) as TranslatorException.Transient
        assertTrue(limited.rateLimited)
        assertEquals(15_500L, limited.retryAfterMillis)

        replies = error(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertFalse((failure(claude()) as TranslatorException.Transient).rateLimited)

        replies = error(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""")
        assertTrue(failure(claude()) is TranslatorException.Fatal)

        replies = error(400, """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum"}}""")
        assertTrue(failure(claude()) is TranslatorException.Blocked)

        replies = { Reply(200, "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n") }
        assertTrue(failure(claude()) is TranslatorException.Transient)

        replies = { Reply(200, sse("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}""")) }
        assertTrue(failure(claude()) is TranslatorException.Blocked)
    }

    // ------------------------------------------------------------ network

    @Test
    fun `no connection is retried later`() {
        val port = server.localPort
        server.close()
        val e = failure(OpenAITranslator("k", "m", endpoint = "http://127.0.0.1:$port/v1/chat/completions"))
        assertTrue(e is TranslatorException.Transient && e.message == "No internet connection")
        start() // for @After
    }

    @Test
    fun `pausing stops a stalled stream at once`() {
        replies = { Reply(200, sse("""{"choices":[{"delta":{"content":"पहला"}}]}"""), hangAfter = true) }
        val started = System.nanoTime()
        val first = runBlocking { withTimeout(10_000) { openAi().translate("x").first() } }
        assertEquals("पहला", first)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }
}
