package app.muka.bonsai.api

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Minimal OpenAI-compatible HTTP server (`/v1/models`, `/v1/chat/completions`).
 *
 * Implemented with a raw [ServerSocket] to avoid extra dependencies. Each connection
 * is handled in its own coroutine; generation itself is serialized by the [Handler]
 * (the underlying engine only supports one inference at a time).
 */
class OpenAiServer(
    private val port: Int,
    private val handler: Handler,
) {

    interface Handler {
        /** IDs (file names) of models available on disk. */
        fun listModelIds(): List<String>

        /** ID (file name) of the currently loaded model, or null when none is loaded. */
        fun loadedModelId(): String?

        /**
         * Run a chat completion against the currently loaded model.
         *
         * @param model the client-requested model ID, or null when the client
         *              omitted it. The handler may load/switch to the matching
         *              local model before generating.
         * @param messages (role, content) pairs in conversation order
         * @param maxTokens maximum number of tokens to generate; negative means
         *                  the client did not specify one — fall back to the
         *                  app's configured generation length
         * @param temperature request-level override, null when not specified
         * @param topP request-level override, null when not specified
         * @param seed request-level override, null when not specified
         * @param onDelta invoked with every generated token (used for SSE streaming)
         * @throws ApiBusyException when another generation is already running
         * @throws NoModelLoadedException when no model is loaded
         * @throws ModelNotFoundException when [model] matches no local model
         */
        suspend fun chatCompletion(
            model: String?,
            messages: List<Pair<String, String>>,
            maxTokens: Int,
            temperature: Double?,
            topP: Double?,
            seed: Int?,
            onDelta: suspend (String) -> Unit,
        ): CompletionResult
    }

    data class CompletionResult(
        val text: String,
        val promptTokens: Int,
        val completionTokens: Int,
        /** OpenAI-style finish reason: "stop" (EOG) or "length" (token limit). */
        val finishReason: String,
    )

    class ApiBusyException : Exception("Another generation is in progress")
    class NoModelLoadedException : Exception("No model is loaded")
    class ModelNotFoundException(requestedId: String) : Exception("Model not found: $requestedId")

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(port)
        serverSocket = socket
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            Log.i(TAG, "OpenAI-compatible server listening on 0.0.0.0:$port")
            while (isActive) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (e: SocketException) {
                    // Socket closed by stop()
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Accept error", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        scope?.cancel()
        scope = null
        Log.i(TAG, "OpenAI-compatible server stopped")
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.use { sock ->
                sock.tcpNoDelay = true
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Headers are ASCII; read byte-per-char so the body can be re-decoded as UTF-8.
                val reader = input.bufferedReader(StandardCharsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeError(output, 400, "Bad request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }

                val bodyChars = CharArray(contentLength.coerceAtMost(MAX_BODY_BYTES))
                var read = 0
                while (read < bodyChars.size) {
                    val n = reader.read(bodyChars, read, bodyChars.size - read)
                    if (n < 0) break
                    read += n
                }
                val body = String(
                    String(bodyChars, 0, read).toByteArray(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8
                )

                route(method, path, body, output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Client handling failed: ${e.message}")
        }
    }

    private suspend fun route(method: String, path: String, body: String, output: OutputStream) {
        when {
            method == "OPTIONS" -> writeEmpty(output, 200)
            method == "GET" && (path == "/" || path == "/health" || path == "/v1") ->
                writeJson(output, 200, JSONObject().put("status", "ok"))
            method == "GET" && path == "/v1/models" -> handleListModels(output)
            method == "POST" && path == "/v1/chat/completions" -> handleChatCompletions(body, output)
            else -> writeError(output, 404, "Not found: $method $path")
        }
    }

    private fun handleListModels(output: OutputStream) {
        val data = JSONArray()
        handler.listModelIds().forEach { id ->
            data.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", 0)
                    .put("owned_by", "bonsai-local")
            )
        }
        writeJson(output, 200, JSONObject().put("object", "list").put("data", data))
    }

    private suspend fun handleChatCompletions(body: String, output: OutputStream) {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            writeError(output, 400, "Invalid JSON body")
            return
        }

        val messagesJson = json.optJSONArray("messages")
        if (messagesJson == null || messagesJson.length() == 0) {
            writeError(output, 400, "\"messages\" is required and must not be empty")
            return
        }
        val messages = (0 until messagesJson.length()).map { i ->
            val m = messagesJson.getJSONObject(i)
            m.optString("role", "user") to m.optString("content", "")
        }
        val stream = json.optBoolean("stream", false)
        // Negative when the client omitted max_tokens: the handler then falls
        // back to the app's configured generation length instead of imposing
        // a hidden cap of its own.
        val maxTokens = json.optInt("max_tokens", -1)
        // Optional OpenAI-compatible sampling overrides.
        val temperature = if (json.has("temperature")) json.optDouble("temperature") else null
        val topP = if (json.has("top_p")) json.optDouble("top_p") else null
        val seed = if (json.has("seed")) json.optInt("seed") else null
        // The requested model is passed to the handler (which may auto-switch to it);
        // the response echoes it back, falling back to the loaded model when omitted.
        val requestedModel = json.optString("model").takeIf { it.isNotBlank() }
        val model = requestedModel ?: handler.loadedModelId() ?: "bonsai"
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000

        if (!stream) {
            try {
                val result = handler.chatCompletion(requestedModel, messages, maxTokens, temperature, topP, seed) { }
                val resp = JSONObject()
                    .put("id", id)
                    .put("object", "chat.completion")
                    .put("created", created)
                    .put("model", model)
                    .put(
                        "choices", JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "message", JSONObject()
                                        .put("role", "assistant")
                                        .put("content", result.text)
                                )
                                .put("finish_reason", result.finishReason)
                        )
                    )
                    .put(
                        "usage", JSONObject()
                            .put("prompt_tokens", result.promptTokens)
                            .put("completion_tokens", result.completionTokens)
                            .put("total_tokens", result.promptTokens + result.completionTokens)
                    )
                writeJson(output, 200, resp)
            } catch (e: ApiBusyException) {
                writeError(output, 429, e.message)
            } catch (e: NoModelLoadedException) {
                writeError(output, 503, e.message)
            } catch (e: ModelNotFoundException) {
                writeError(output, 404, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeError(output, 500, e.message ?: "Generation failed")
            }
            return
        }

        // SSE streaming. Headers are sent lazily so early failures (busy / no model)
        // can still be reported as regular JSON error responses.
        var headersSent = false
        fun ensureSseHeaders() {
            if (!headersSent) {
                writeRaw(
                    output,
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                )
                headersSent = true
            }
        }

        fun sendChunk(delta: JSONObject?, finishReason: String?) {
            ensureSseHeaders()
            val choice = JSONObject().put("index", 0)
            if (delta != null) choice.put("delta", delta)
            if (finishReason != null) choice.put("finish_reason", finishReason)
            else choice.put("finish_reason", JSONObject.NULL)
            val chunk = JSONObject()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("created", created)
                .put("model", model)
                .put("choices", JSONArray().put(choice))
            writeRaw(output, "data: ${chunk}\n\n")
            output.flush()
        }

        try {
            var sentRole = false
            fun ensureRoleChunk() {
                if (!sentRole) {
                    sendChunk(JSONObject().put("role", "assistant"), null)
                    sentRole = true
                }
            }
            val result = handler.chatCompletion(requestedModel, messages, maxTokens, temperature, topP, seed) { token ->
                ensureRoleChunk()
                sendChunk(JSONObject().put("content", token), null)
            }
            ensureRoleChunk()
            sendChunk(JSONObject(), result.finishReason)
            ensureSseHeaders()
            writeRaw(output, "data: [DONE]\n\n")
            output.flush()
        } catch (e: ApiBusyException) {
            if (headersSent) sendErrorEvent(output, e.message) else writeError(output, 429, e.message)
        } catch (e: NoModelLoadedException) {
            if (headersSent) sendErrorEvent(output, e.message) else writeError(output, 503, e.message)
        } catch (e: ModelNotFoundException) {
            if (headersSent) sendErrorEvent(output, e.message) else writeError(output, 404, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (headersSent) sendErrorEvent(output, e.message) else writeError(output, 500, e.message)
        }
    }

    private fun sendErrorEvent(output: OutputStream, message: String?) {
        try {
            val err = JSONObject().put(
                "error", JSONObject()
                    .put("message", message ?: "Generation failed")
                    .put("type", "server_error")
            )
            writeRaw(output, "data: $err\n\ndata: [DONE]\n\n")
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun writeJson(output: OutputStream, status: Int, json: JSONObject) {
        val bodyBytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        writeRaw(
            output,
            "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        )
        output.write(bodyBytes)
        output.flush()
    }

    private fun writeEmpty(output: OutputStream, status: Int) {
        writeRaw(
            output,
            "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization\r\n\r\n"
        )
        output.flush()
    }

    private fun writeError(output: OutputStream, status: Int, message: String?) {
        writeJson(
            output, status,
            JSONObject().put(
                "error", JSONObject()
                    .put("message", message ?: "error")
                    .put("type", "server_error")
            )
        )
    }

    private fun writeRaw(output: OutputStream, text: String) {
        output.write(text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> ""
    }

    companion object {
        private const val TAG = "OpenAiServer"
        private const val MAX_BODY_BYTES = 1 * 1024 * 1024

        /** Non-loopback IPv4 addresses of this device, for displaying the API endpoint. */
        fun localIpAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
