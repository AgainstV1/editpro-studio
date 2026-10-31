package com.editpro.studio.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to any OpenAI-compatible chat-completions endpoint.
 *
 * One implementation deliberately covers a lot of ground, because almost every provider now speaks
 * this dialect: OpenAI, Google Gemini (`/v1beta/openai`), OpenRouter, Groq, DeepSeek, Mistral,
 * Together, and local servers such as Ollama, LM Studio and vLLM. The user supplies the base URL,
 * the model name and a key, so nothing here is tied to one vendor and the app never ships a key.
 *
 * Only the pieces an editing agent needs are implemented: messages, tools (function calling), and
 * a non-streaming reply. Streaming is a nice-to-have that would double the parsing surface for no
 * functional gain here.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {

    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class Reply(val content: String?, val toolCalls: List<ToolCall>) {
        val wantsTools: Boolean get() = toolCalls.isNotEmpty()
    }

    suspend fun chat(messages: JSONArray, tools: JSONArray): Reply = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
        if (tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            // OpenRouter asks for these; harmless everywhere else.
            setRequestProperty("HTTP-Referer", "https://github.com/AgainstV1/editpro-studio")
            setRequestProperty("X-Title", "Edit Pro Studio")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(humanError(status, text))
            }

            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalStateException("The model returned something that is not JSON.")
            }

            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException(
                    root.optJSONObject("error")?.optString("message")
                        ?: "The model returned no choices."
                )
            val message = choice.optJSONObject("message")
                ?: throw IllegalStateException("The model returned a choice with no message.")

            val content = if (message.isNull("content")) {
                null
            } else {
                message.optString("content", "").ifBlank { null }
            }

            val calls = mutableListOf<ToolCall>()
            message.optJSONArray("tool_calls")?.let { array ->
                for (i in 0 until array.length()) {
                    val call = array.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    calls.add(
                        ToolCall(
                            id = call.optString("id", "call_$i"),
                            name = function.optString("name", ""),
                            arguments = function.optString("arguments", "{}")
                        )
                    )
                }
            }

            Reply(content = content, toolCalls = calls)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Turns a provider error into something a person can act on. */
    private fun humanError(status: Int, body: String): String {
        val providerMessage = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?: root.optJSONArray("error")?.optJSONObject(0)?.optString("message")
        }.getOrNull()

        val hint = when (status) {
            401, 403 -> "The API key was rejected. Check the key and that it is valid for this provider."
            404 -> "Endpoint not found. Check the base URL ends with the right path, usually /v1."
            429 -> "Rate limited or out of quota. Try again, or use a different model."
            in 500..599 -> "The provider had a server error. Try again shortly."
            else -> null
        }
        return buildString {
            append("HTTP $status")
            if (!providerMessage.isNullOrBlank()) append(": $providerMessage")
            if (hint != null) append("\n").append(hint)
        }
    }
}
