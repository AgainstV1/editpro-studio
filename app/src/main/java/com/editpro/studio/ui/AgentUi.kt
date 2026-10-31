package com.editpro.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.editpro.studio.viewmodel.AgentMessage
import com.editpro.studio.viewmodel.EditorUiState

/**
 * The built-in editing assistant: a chat panel that drives the same tools the MCP server exposes,
 * but through a model the user chose and pays for with their own key.
 *
 * Settings live inline rather than in a separate screen, because the first thing anyone needs is to
 * paste a key — making that a hidden sub-dialog would be needless friction.
 */
@Composable
fun AgentChatDialog(
    state: EditorUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var draft by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(!state.agentHasKey) }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.agentMessages.size, state.agentStatus) {
        if (scrollState.maxValue > 0) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Editing assistant",
                        color = palette.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.agentHasKey) "${state.agentModel}  ·  ${state.agentBaseUrl}"
                        else "Bring your own API key to start",
                        color = palette.muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                MiniTextButton(if (settingsOpen) "Chat" else "Settings") {
                    settingsOpen = !settingsOpen
                }
                Spacer(modifier = Modifier.width(6.dp))
                MiniTextButton("Close") { onDismiss() }
            }

            if (settingsOpen) {
                AgentSettings(
                    state = state,
                    onSetBaseUrl = onSetBaseUrl,
                    onSetModel = onSetModel,
                    onSaveKey = onSaveKey,
                    onClearKey = onClearKey,
                    onDone = { settingsOpen = false }
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .verticalScroll(scrollState)
                ) {
                    if (state.agentMessages.isEmpty()) {
                        Text(
                            "Ask for an edit. For example: \"put the first clip on the timeline and " +
                                "fade the audio out over the last second\".",
                            color = palette.muted,
                            fontSize = 11.5.sp
                        )
                    }
                    state.agentMessages.forEach { message -> AgentBubble(message) }
                    if (state.agentStatus.isNotEmpty()) {
                        Text(
                            state.agentStatus,
                            color = palette.accent,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.controlBg)
                            .border(0.5.dp, palette.line, RoundedCornerShape(9.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                "Describe the edit...",
                                color = palette.muted.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(color = palette.text, fontSize = 12.sp),
                            cursorBrush = SolidColor(palette.accent),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (state.agentRunning) {
                        AgentButton("Stop", destructive = true, onClick = onStop)
                    } else {
                        AgentButton("Send", primary = true, onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                draft = ""
                                onSend(text)
                            }
                        })
                    }
                }

                if (state.agentMessages.isNotEmpty()) {
                    MiniTextButton("Clear conversation") { onClear() }
                }
            }
        }
    }
}

@Composable
private fun AgentSettings(
    state: EditorUiState,
    onSetBaseUrl: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onDone: () -> Unit
) {
    val palette = LocalEditProPalette.current
    var baseUrl by remember { mutableStateOf(state.agentBaseUrl) }
    var model by remember { mutableStateOf(state.agentModel) }
    var key by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Any OpenAI-compatible endpoint works. You pay the provider directly; this app never " +
                "ships or proxies a key.",
            color = palette.muted,
            fontSize = 10.5.sp
        )

        Text("QUICK PRESETS", color = palette.muted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AgentPresets.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (preset == option.label) palette.controlBg else palette.panel2)
                        .clickable {
                            preset = option.label
                            baseUrl = option.baseUrl
                            model = option.model
                        }
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    Text(option.label, color = palette.text, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                    Text(option.note, color = palette.muted, fontSize = 9.5.sp)
                }
            }
        }

        AgentField("Base URL", baseUrl, "https://api.openai.com/v1") { baseUrl = it }
        AgentField("Model", model, "gpt-4o-mini") { model = it }
        AgentField(
            label = "API key" + if (state.agentHasKey) " (one is saved)" else "",
            value = key,
            placeholder = if (state.agentHasKey) "••••••••  saved" else "sk-...",
            secret = true
        ) { key = it }

        Text(
            "Stored encrypted with a key held in the Android Keystore. It is only ever sent to the " +
                "base URL above.",
            color = palette.muted,
            fontSize = 9.5.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AgentButton("Save", primary = true, onClick = {
                onSetBaseUrl(baseUrl)
                onSetModel(model)
                if (key.isNotBlank()) onSaveKey(key)
                key = ""
                onDone()
            })
            if (state.agentHasKey) {
                AgentButton("Remove key", destructive = true, onClick = onClearKey)
            }
        }
    }
}

@Composable
private fun AgentBubble(message: AgentMessage) {
    val palette = LocalEditProPalette.current
    when (message.role) {
        "user" -> Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                message.text,
                color = Color.White,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.accent)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        "assistant" -> Text(
            message.text,
            color = palette.text,
            fontSize = 11.5.sp,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.panel2)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )

        "tool" -> Text(
            "→ ${message.text}",
            color = palette.accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        "toolResult" -> Text(
            message.text,
            color = palette.muted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        else -> Text(
            message.text,
            color = palette.red,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AgentField(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit
) {
    val palette = LocalEditProPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = palette.muted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.controlBg)
                .border(0.5.dp, palette.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = palette.muted.copy(alpha = 0.6f), fontSize = 11.5.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AgentButton(
    label: String,
    primary: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    primary -> palette.accent
                    destructive -> palette.red.copy(alpha = 0.18f)
                    else -> palette.controlBg
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = when {
                primary -> Color.White
                destructive -> palette.red
                else -> palette.text
            },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MiniTextButton(label: String, onClick: () -> Unit) {
    val palette = LocalEditProPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, color = palette.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

private data class AgentPreset(val label: String, val note: String, val baseUrl: String, val model: String)

private val AgentPresets = listOf(
    AgentPreset("OpenAI", "api.openai.com", "https://api.openai.com/v1", "gpt-4o-mini"),
    AgentPreset("Google Gemini", "OpenAI-compatible", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
    AgentPreset("OpenRouter", "many free models", "https://openrouter.ai/api/v1", "google/gemini-2.0-flash-exp:free"),
    AgentPreset("Groq", "fast, free tier", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    AgentPreset("Local (Ollama)", "on this device", "http://127.0.0.1:11434/v1", "llama3.1")
)
