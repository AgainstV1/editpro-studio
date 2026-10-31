package com.editpro.studio.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal, dependency-free MCP server.
 *
 * Speaks JSON-RPC 2.0 over HTTP POST at `/mcp`. MCP's "Streamable HTTP" transport lets a server
 * answer a POST with a plain `application/json` body instead of an SSE stream, which is all a
 * tool-only server needs and avoids dragging in a whole HTTP stack.
 *
 * Bound to 127.0.0.1 on purpose: the intended client is an agent app on the same tablet. Every
 * request except `OPTIONS` must carry the pairing token, as `Authorization: Bearer <token>` or
 * `?token=<token>`.
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val tools: McpTools,
    private val onLog: (String) -> Unit
) {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            running = true
            acceptThread = Thread {
                while (running) {
                    try {
                        val client = socket.accept()
                        pool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) onLog("accept failed: ${e.message}")
                    }
                }
            }.also {
                it.isDaemon = true
                it.name = "mcp-accept"
                it.start()
            }
            onLog("listening on http://127.0.0.1:$port/mcp")
            true
        } catch (e: Exception) {
            onLog("could not start: ${e.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        onLog("stopped")
    }

    // ------------------------------------------------------------------ HTTP

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeJson(output, 400, errorBody(null, -32700, "Malformed request line"))
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) {
                    headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
            }

            if (method == "OPTIONS") {
                writeEmpty(output, 204)
                return
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            if (!isAuthorised(headers, query)) {
                val origin = headers["x-forwarded-for"]?.substringBefore(',')?.trim()
                    ?: client.inetAddress.hostAddress
                onLog("rejected unauthorised request from $origin")
                writeJson(output, 401, errorBody(null, -32001, "Unauthorised: missing or invalid pairing token"))
                return
            }

            when {
                path == "/health" && method == "GET" -> writeJson(
                    output, 200,
                    JSONObject()
                        .put("status", "ok")
                        .put("server", "editpro-studio")
                        .put("tools", tools.count())
                )

                path == "/mcp" && method == "POST" -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length <= 0 || length > 4 * 1024 * 1024) {
                        writeJson(output, 413, errorBody(null, -32600, "Missing or oversized body"))
                        return
                    }
                    val body = readBody(input, length)
                    val response = dispatch(body)
                    // MCP Streamable HTTP clients often advertise text/event-stream; answer in SSE
                    // framing when they do, otherwise plain JSON. Same payload either way.
                    val wantsSse = headers["accept"].orEmpty().contains("text/event-stream")
                    when {
                        response == null -> writeEmpty(output, 202)
                        wantsSse -> writeSse(output, response)
                        else -> writeJson(output, 200, response)
                    }
                }

                path == "/mcp" && method == "GET" -> writeJson(
                    output, 405,
                    errorBody(null, -32000, "This endpoint answers POST only; SSE streaming is not supported.")
                )

                else -> writeJson(output, 404, errorBody(null, -32601, "Not found"))
            }
        } catch (e: Exception) {
            onLog("request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    @Volatile
    private var lockedUntilMs = 0L
    private val recentFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Token check with a brute-force brake. Once the endpoint is reachable through a tunnel the
     * token is the only thing guarding the project, so repeated wrong guesses earn a short lockout
     * and every comparison is constant-time.
     */
    private fun isAuthorised(headers: Map<String, String>, query: String): Boolean {
        if (token.isEmpty()) return true
        val now = System.currentTimeMillis()
        if (now < lockedUntilMs) return false

        val bearer = headers["authorization"]
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
            ?.trim()
        val fromQuery = query.split("&")
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")

        val ok = (bearer != null && constantTimeEquals(bearer, token)) ||
            (fromQuery != null && constantTimeEquals(fromQuery, token))

        if (ok) {
            recentFailures.set(0)
            return true
        }

        if (recentFailures.incrementAndGet() >= 5) {
            recentFailures.set(0)
            lockedUntilMs = now + 30_000L
            onLog("5 bad tokens - pausing authentication for 30s")
        }
        // Slow the caller down a little without blocking the pool.
        runCatching { Thread.sleep(150) }
        return false
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // -------------------------------------------------------------- JSON-RPC

    private fun dispatch(body: String): Any? {
        val parsed = try {
            JSONTokener(body).nextValue()
        } catch (e: Exception) {
            return errorBody(null, -32700, "Parse error")
        }
        return when (parsed) {
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until parsed.length()) {
                    parsed.optJSONObject(i)?.let { request ->
                        handleOne(request)?.let { out.put(it) }
                    }
                }
                if (out.length() == 0) null else out
            }
            is JSONObject -> handleOne(parsed)
            else -> errorBody(null, -32700, "Parse error")
        }
    }

    private fun handleOne(request: JSONObject): JSONObject? {
        val id = request.opt("id")
        val isNotification = !request.has("id")
        val method = request.optString("method", "")
        val params = request.optJSONObject("params") ?: JSONObject()

        return try {
            when (method) {
                "initialize" -> ok(id, tools.initializeResult(params))
                "notifications/initialized", "notifications/cancelled" -> null
                "ping" -> ok(id, JSONObject())
                "tools/list" -> ok(id, JSONObject().put("tools", tools.definitions()))
                "tools/call" -> {
                    val name = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    ok(id, tools.call(name, args))
                }
                "resources/list" -> ok(id, JSONObject().put("resources", JSONArray()))
                "prompts/list" -> ok(id, JSONObject().put("prompts", JSONArray()))
                else -> if (isNotification) null else errorBody(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            errorBody(id, -32603, e.message ?: "Internal error")
        }
    }

    private fun ok(id: Any?, result: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun errorBody(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject().put("code", code).put("message", message)
        )

    // ------------------------------------------------------------- plumbing

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(b.toChar())
            if (buffer.length > 8192) return null
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun writeJson(output: OutputStream, status: Int, body: Any) {
        val text = body.toString()
        write(output, status, "application/json", text)
    }

    private fun writeSse(output: OutputStream, body: Any) {
        val payload = body.toString().replace("\n", "\ndata: ")
        val text = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id\r\n")
            append("Connection: close\r\n\r\n")
            append("event: message\r\n")
            append("data: $payload\r\n\r\n")
        }
        runCatching {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun writeEmpty(output: OutputStream, status: Int) {
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun write(output: OutputStream, status: Int, contentType: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "OK"
    }
}
