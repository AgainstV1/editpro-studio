package com.editpro.studio.agent

import com.editpro.studio.mcp.McpTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * The loop that turns a chat model into an editor: send the tools, let the model pick one, run it
 * locally, feed the result back, repeat until it answers in prose.
 *
 * The conversation buffer is kept across turns so the agent remembers what it already did. It is
 * raw OpenAI-shaped messages, not the display transcript, because tool calls and their results have
 * to be replayed exactly as the provider produced them.
 *
 * Guard rails, because a language model with edit access needs them:
 *  - at most [MAX_STEPS] model round-trips per message, so it cannot loop forever;
 *  - tool output is truncated before going back into context, so one big listing cannot blow the
 *    context window;
 *  - failures are returned to the model as text rather than thrown, so it can recover instead of
 *    the whole turn dying.
 */
class AgentEngine(private val tools: McpTools) {

    sealed interface Event {
        data class Thinking(val step: Int) : Event
        data class ToolStarted(val name: String) : Event
        data class ToolFinished(val name: String, val summary: String) : Event
        data class Assistant(val text: String) : Event
    }

    private val conversation = JSONArray()

    fun reset() {
        while (conversation.length() > 0) conversation.remove(0)
    }

    suspend fun send(
        client: OpenAiClient,
        userText: String,
        onEvent: (Event) -> Unit
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (i in 0 until conversation.length()) {
            conversation.optJSONObject(i)?.let { messages.put(it) }
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val toolDefinitions = tools.openAiTools()
        var step = 0

        while (step < MAX_STEPS) {
            step++
            onEvent(Event.Thinking(step))
            val reply = client.chat(messages, toolDefinitions)

            if (!reply.wantsTools) {
                val text = reply.content?.trim().orEmpty().ifEmpty { "Done." }
                messages.put(JSONObject().put("role", "assistant").put("content", text))
                commit(messages)
                onEvent(Event.Assistant(text))
                return text
            }

            // Record the assistant turn that asked for the tools.
            val toolCallsJson = JSONArray()
            reply.toolCalls.forEach { call ->
                toolCallsJson.put(
                    JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", call.name).put("arguments", call.arguments)
                        )
                )
            }
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", reply.content ?: JSONObject.NULL)
                    .put("tool_calls", toolCallsJson)
            )

            reply.toolCalls.forEach { call ->
                onEvent(Event.ToolStarted(call.name))
                val result = runCatching {
                    tools.execute(call.name, parseArguments(call.arguments))
                }
                val payload = result.getOrElse { error ->
                    JSONObject().put("error", error.message ?: error.javaClass.simpleName).toString()
                }
                onEvent(Event.ToolFinished(call.name, summarise(payload)))
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", payload.take(MAX_TOOL_CHARS))
                )
            }
        }

        val fallback = "I stopped after $MAX_STEPS steps to avoid looping. " +
            "Check the timeline and tell me what to do next."
        messages.put(JSONObject().put("role", "assistant").put("content", fallback))
        commit(messages)
        onEvent(Event.Assistant(fallback))
        return fallback
    }

    /** Keeps everything after the system message, so context carries into the next turn. */
    private fun commit(messages: JSONArray) {
        reset()
        for (i in 1 until messages.length()) {
            messages.optJSONObject(i)?.let { conversation.put(it) }
        }
    }

    private fun parseArguments(raw: String): JSONObject = runCatching {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }.getOrElse { JSONObject() }

    private fun summarise(payload: String): String {
        val oneLine = payload.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 90) oneLine else oneLine.take(90) + "..."
    }

    private companion object {
        const val MAX_STEPS = 8
        const val MAX_TOOL_CHARS = 4000

        val SYSTEM_PROMPT = """
            You are the editing assistant inside Edit Pro Studio, a multi-track video editor running
            on the user's Android tablet. You edit the project that is currently open by calling tools.

            How to work:
            - Start by calling get_summary so you know what is on the timeline, and list_media when
              you need asset ids.
            - Make small, deliberate changes. One tool call per intent, then check the result.
            - Times are absolute seconds on the timeline. Durations come from the media itself.
            - If the user asks for something you cannot do with the available tools, say so plainly
              instead of improvising. You cannot export, and you cannot clear the whole timeline.
            - You can fetch a media file from a URL with fetch_url, then place it with add_clip.

            Style:
            - After making changes, reply with one or two sentences describing what you did, not a
              list of every tool call.
            - Never claim to have done something you did not call a tool for.
            - If a tool returns an error, read it and either fix your call or explain the problem.
        """.trimIndent()
    }
}
