package com.editpro.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.editpro.studio.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Which modifier keys were held at the most recent pointer event. A click handler has no access
 * to the keyboard, so the workspace records this once at its root and clip / library clicks read
 * it to tell a plain click from Ctrl-click (add to selection) and Shift-click (select a range).
 */
class PointerModifierState {
    var ctrl: Boolean = false
        private set
    var shift: Boolean = false
        private set
    var alt: Boolean = false
        private set

    internal fun update(ctrl: Boolean, shift: Boolean, alt: Boolean) {
        this.ctrl = ctrl
        this.shift = shift
        this.alt = alt
    }
}

/** Records the modifier keys of every pointer event that passes through this element. */
fun Modifier.trackPointerModifiers(target: PointerModifierState): Modifier = pointerInput(target) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val keys = event.keyboardModifiers
            target.update(
                ctrl = keys.isCtrlPressed || keys.isMetaPressed,
                shift = keys.isShiftPressed,
                alt = keys.isAltPressed
            )
        }
    }
}

/**
 * A context menu opened at [position] (in the coordinates of the enclosing `Box`). Null keeps it
 * closed. Styled like the clip menu: compact rows with the shortcut on the right.
 */
@Composable
fun EditorContextMenu(
    position: Offset?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (position == null) return
    val palette = LocalEditProPalette.current
    Box(
        modifier = Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .size(1.dp)
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = 224.dp)
                .background(palette.panel2)
                .border(0.5.dp, palette.line, RoundedCornerShape(10.dp))
        ) {
            content()
        }
    }
}

/** One row of a context menu: label on the left, shortcut hint on the right. */
@Composable
fun EditorMenuItem(
    label: String,
    shortcut: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val palette = LocalEditProPalette.current
    val color = when {
        !enabled -> palette.muted.copy(alpha = 0.5f)
        destructive -> palette.red
        else -> palette.text
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!shortcut.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(20.dp))
            Text(
                text = shortcut,
                color = palette.muted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun EditorMenuDivider() {
    val palette = LocalEditProPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(palette.line)
    )
}

/** Small square chevron used to collapse or expand a panel. */
@Composable
fun PanelToggleButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalEditProPalette.current
    val edge = if (rememberIsCompact()) 40.dp else 26.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(edge)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.controlBg)
            .border(0.5.dp, palette.line, RoundedCornerShape(7.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Text(glyph, color = palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * What a collapsed side panel leaves behind: a narrow rail with a button that brings it back and
 * the panel's name written down it, so it is obvious what is hidden there.
 */
@Composable
fun CollapsedPanelRail(title: String, expandGlyph: String, onExpand: () -> Unit) {
    val palette = LocalEditProPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(34.dp)
            .fillMaxHeight()
            .background(palette.panel)
            .border(0.5.dp, palette.line)
            .clickable(role = Role.Button, onClick = onExpand)
            .padding(vertical = 8.dp)
    ) {
        PanelToggleButton(glyph = expandGlyph, description = "Show $title", onClick = onExpand)
        Text(
            text = title.toCharArray().joinToString("\n"),
            color = palette.muted,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** A one-field dialog for naming things: projects, media, markers. */
@Composable
fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String = ""
) {
    val palette = LocalEditProPalette.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        val clean = value.text.trim()
        if (clean.isNotEmpty()) onConfirm(clean)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.panel)
                .border(0.5.dp, palette.line, RoundedCornerShape(14.dp))
                .padding(18.dp)
        ) {
            Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.controlBg)
                            .border(0.5.dp, palette.line, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp)
                    ) {
                        if (value.text.isEmpty() && hint.isNotEmpty()) {
                            Text(hint, color = palette.muted, fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                InspectorActionButton("Cancel", modifier = Modifier.width(96.dp), onClick = onDismiss)
                Spacer(modifier = Modifier.width(8.dp))
                InspectorActionButton(confirmLabel, isPrimary = true, modifier = Modifier.width(140.dp), onClick = submit)
            }
        }
    }
}

/** Parses "#rrggbb" for display, falling back when the stored value is not a colour. */
fun parseHexColor(hex: String?, fallback: Color = Color.White): Color = try {
    Color(android.graphics.Color.parseColor(hex ?: ""))
} catch (_: Exception) {
    fallback
}

/**
 * A small thumbnail for a library tile or a project card, decoded off the main thread so a grid
 * full of them never stalls scrolling. Null until it is ready, or when there is nothing to show.
 */
@Composable
fun rememberThumbnail(path: String?, maxEdgePx: Int = 360): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, path, maxEdgePx) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { MediaEngine.decodeStill(path, maxEdgePx, maxEdgePx) }?.asImageBitmap()
        }
    }
    return state.value
}

/**
 * Calls [onClick] with the pointer position when the secondary mouse button (or a stylus button)
 * is pressed. The press is consumed before any tap handler sees it, so a right-click never also
 * counts as a click. Touch has no secondary button; it reaches the same menus by long-press.
 */
fun Modifier.onSecondaryClick(key: Any?, onClick: (Offset) -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onClick)
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                    event.changes.forEach { it.consume() }
                    current(position)
                }
            }
        }
    }
}
