package com.editpro.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class EditProPalette(
    val isLight: Boolean,
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val cyan: Color,
    val orange: Color,
    val red: Color,
    val green: Color,
    val purple: Color,
    val controlBg: Color,
    val controlHover: Color,
    val clipVideo: Color,
    val clipAudio: Color,
    val clipImage: Color,
    val clipText: Color,
    val timelineBg: Color,
    val rulerBg: Color
)

val DarkEditProPalette = EditProPalette(
    isLight = false,
    bg = Color(0xFF161618),
    panel = Color(0xFF232326),
    panel2 = Color(0xFF2C2C31),
    line = Color(0x17FFFFFF),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFF9A9AA3),
    accent = Color(0xFF0A84FF),
    cyan = Color(0xFF64D2FF),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    purple = Color(0xFFBF5AF2),
    controlBg = Color(0x14FFFFFF),
    controlHover = Color(0x24FFFFFF),
    clipVideo = Color(0xFF35679F),
    clipAudio = Color(0xFF2D7A6B),
    clipImage = Color(0xFF7653A8),
    clipText = Color(0xFFA8741F),
    timelineBg = Color(0xFF1B1B1D),
    rulerBg = Color(0xFF1B1B1D)
)

val LightEditProPalette = EditProPalette(
    isLight = true,
    bg = Color(0xFFEEF0F4),
    panel = Color(0xFFF8F9FC),
    panel2 = Color(0xFFFFFFFF),
    line = Color(0xFFD6DAE3),
    text = Color(0xFF20232B),
    muted = Color(0xFF697181),
    accent = Color(0xFF1769D2),
    cyan = Color(0xFF138D82),
    orange = Color(0xFFB46B17),
    red = Color(0xFFD93850),
    green = Color(0xFF1F9D4C),
    purple = Color(0xFF8A3FFC),
    controlBg = Color(0xFFE9ECF2),
    controlHover = Color(0xFFDFE4EC),
    clipVideo = Color(0xFF35679F),
    clipAudio = Color(0xFF2D7A6B),
    clipImage = Color(0xFF7653A8),
    clipText = Color(0xFFA8741F),
    timelineBg = Color(0xFFF0F2F6),
    rulerBg = Color(0xFFE8EBF1)
)

val LocalEditProPalette = staticCompositionLocalOf { DarkEditProPalette }

@Composable
fun EditProStudioTheme(
    isLight: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (isLight) LightEditProPalette else DarkEditProPalette
    val colorScheme = if (isLight) {
        lightColorScheme(
            primary = palette.accent,
            background = palette.bg,
            surface = palette.panel,
            onPrimary = Color.White,
            onBackground = palette.text,
            onSurface = palette.text
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            background = palette.bg,
            surface = palette.panel,
            onPrimary = Color.White,
            onBackground = palette.text,
            onSurface = palette.text
        )
    }

    CompositionLocalProvider(LocalEditProPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

enum class SmileIconType {
    MODE_EDIT, MODE_COLOR, MODE_MOTION, MODE_MUSIC,
    NEW_PROJ, OPEN_PROJ, SAVE_PROJ, PRESETS, TOOLS, EXPORT_VID, IMPORT_MEDIA,
    ADD_TEXT, SPLIT, TRASH, UNDO, REDO, SHORTCUTS,
    ZOOM_OUT, ZOOM_IN, ZOOM_CLIP, FOLLOW, DEMO, SCOPES, SPLIT_WIPE, RESET
}

/**
 * Renders EditPro's signature minimalist vector icons with subtle curved smile arcs.
 */
@Composable
fun SmileVectorIcon(
    type: SmileIconType,
    tint: Color,
    modifier: Modifier = Modifier.size(15.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun DrawScope.drawSmileArc(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float) {
            val p = Path().apply {
                moveTo(w * x1, h * y1)
                quadraticBezierTo(w * cx, h * cy, w * x2, h * y2)
            }
            drawPath(p, color = tint, style = stroke)
        }

        when (type) {
            SmileIconType.MODE_EDIT -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.15f), Size(w * 0.76f, h * 0.7f), CornerRadius(w * 0.15f), stroke)
                drawLine(tint, Offset(w * 0.31f, h * 0.38f), Offset(w * 0.69f, h * 0.38f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.32f, 0.63f, 0.50f, 0.76f, 0.68f, 0.63f)
            }
            SmileIconType.MODE_COLOR -> {
                drawCircle(tint, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.37f, h * 0.39f))
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.63f, h * 0.39f))
                drawSmileArc(0.32f, 0.61f, 0.50f, 0.76f, 0.68f, 0.61f)
            }
            SmileIconType.MODE_MOTION -> {
                val diamond = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.85f, h * 0.48f)
                    lineTo(w * 0.5f, h * 0.84f)
                    lineTo(w * 0.15f, h * 0.48f)
                    close()
                }
                drawPath(diamond, tint, style = stroke)
                drawSmileArc(0.34f, 0.53f, 0.50f, 0.66f, 0.66f, 0.53f)
            }
            SmileIconType.MODE_MUSIC -> {
                drawLine(tint, Offset(w * 0.2f, h * 0.42f), Offset(w * 0.2f, h * 0.56f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.4f, h * 0.28f), Offset(w * 0.4f, h * 0.60f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.6f, h * 0.28f), Offset(w * 0.6f, h * 0.60f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.8f, h * 0.42f), Offset(w * 0.8f, h * 0.56f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.28f, 0.73f, 0.50f, 0.88f, 0.72f, 0.73f)
            }
            SmileIconType.NEW_PROJ, SmileIconType.IMPORT_MEDIA -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.3f, h * 0.42f), Offset(w * 0.7f, h * 0.42f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.26f, 0.72f, 0.50f, 0.86f, 0.74f, 0.72f)
            }
            SmileIconType.OPEN_PROJ -> {
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.28f), Size(w * 0.72f, h * 0.52f), CornerRadius(w * 0.12f), stroke)
                drawSmileArc(0.33f, 0.60f, 0.50f, 0.72f, 0.67f, 0.60f)
            }
            SmileIconType.SAVE_PROJ -> {
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.68f), CornerRadius(w * 0.12f), stroke)
                drawSmileArc(0.32f, 0.64f, 0.50f, 0.76f, 0.68f, 0.64f)
            }
            SmileIconType.PRESETS -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawSmileArc(0.34f, 0.54f, 0.50f, 0.68f, 0.66f, 0.54f)
            }
            SmileIconType.TOOLS -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.38f, h * 0.4f))
                drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.62f, h * 0.4f))
                drawSmileArc(0.33f, 0.61f, 0.50f, 0.74f, 0.67f, 0.61f)
            }
            SmileIconType.EXPORT_VID -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.58f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.34f), Offset(w * 0.5f, h * 0.16f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.68f, h * 0.34f), Offset(w * 0.5f, h * 0.16f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.20f, 0.64f, 0.50f, 0.86f, 0.80f, 0.64f)
            }
            SmileIconType.ADD_TEXT -> {
                drawLine(tint, Offset(w * 0.25f, h * 0.22f), Offset(w * 0.75f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.30f, 0.72f, 0.50f, 0.85f, 0.70f, 0.72f)
            }
            SmileIconType.SPLIT -> {
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.31f, h * 0.31f), style = stroke)
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.69f, h * 0.31f), style = stroke)
                drawLine(tint, Offset(w * 0.39f, h * 0.39f), Offset(w * 0.69f, h * 0.69f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.61f, h * 0.39f), Offset(w * 0.31f, h * 0.69f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.31f, 0.79f, 0.50f, 0.90f, 0.69f, 0.79f)
            }
            SmileIconType.TRASH -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.28f), Offset(w * 0.78f, h * 0.28f), stroke.width, StrokeCap.Round)
                drawRoundRect(tint, Offset(w * 0.30f, h * 0.28f), Size(w * 0.40f, h * 0.52f), CornerRadius(w * 0.08f), stroke)
                drawSmileArc(0.38f, 0.61f, 0.50f, 0.70f, 0.62f, 0.61f)
            }
            SmileIconType.UNDO -> {
                drawLine(tint, Offset(w * 0.2f, h * 0.44f), Offset(w * 0.4f, h * 0.25f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.2f, h * 0.44f), Offset(w * 0.4f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.44f), Offset(w * 0.72f, h * 0.44f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.35f, 0.68f, 0.55f, 0.80f, 0.75f, 0.68f)
            }
            SmileIconType.REDO -> {
                drawLine(tint, Offset(w * 0.8f, h * 0.44f), Offset(w * 0.6f, h * 0.25f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.8f, h * 0.44f), Offset(w * 0.6f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.44f), Offset(w * 0.78f, h * 0.44f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.25f, 0.68f, 0.45f, 0.80f, 0.65f, 0.68f)
            }
            SmileIconType.SHORTCUTS, SmileIconType.ZOOM_CLIP -> {
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.56f), CornerRadius(w * 0.12f), stroke)
                drawSmileArc(0.32f, 0.58f, 0.50f, 0.72f, 0.68f, 0.58f)
            }
            SmileIconType.ZOOM_OUT -> {
                drawCircle(tint, radius = w * 0.29f, center = Offset(w * 0.45f, h * 0.45f), style = stroke)
                drawLine(tint, Offset(w * 0.32f, h * 0.45f), Offset(w * 0.58f, h * 0.45f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.85f, h * 0.85f), stroke.width, StrokeCap.Round)
            }
            SmileIconType.ZOOM_IN -> {
                drawCircle(tint, radius = w * 0.29f, center = Offset(w * 0.45f, h * 0.45f), style = stroke)
                drawLine(tint, Offset(w * 0.32f, h * 0.45f), Offset(w * 0.58f, h * 0.45f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.45f, h * 0.32f), Offset(w * 0.45f, h * 0.58f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.85f, h * 0.85f), stroke.width, StrokeCap.Round)
            }
            SmileIconType.FOLLOW -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.30f, 0.66f, 0.50f, 0.80f, 0.70f, 0.66f)
            }
            SmileIconType.DEMO -> {
                drawCircle(tint, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                val tri = Path().apply {
                    moveTo(w * 0.42f, h * 0.34f)
                    lineTo(w * 0.66f, h * 0.48f)
                    lineTo(w * 0.42f, h * 0.62f)
                    close()
                }
                drawPath(tri, tint)
                drawSmileArc(0.32f, 0.72f, 0.50f, 0.82f, 0.68f, 0.72f)
            }
            SmileIconType.SCOPES -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.16f), Size(w * 0.76f, h * 0.68f), CornerRadius(w * 0.12f), stroke)
                val wave = Path().apply {
                    moveTo(w * 0.22f, h * 0.60f)
                    lineTo(w * 0.35f, h * 0.40f)
                    lineTo(w * 0.49f, h * 0.65f)
                    lineTo(w * 0.64f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.58f)
                }
                drawPath(wave, tint, style = stroke)
                drawSmileArc(0.32f, 0.73f, 0.50f, 0.82f, 0.68f, 0.73f)
            }
            SmileIconType.SPLIT_WIPE -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.16f), Size(w * 0.76f, h * 0.68f), CornerRadius(w * 0.12f), stroke)
                drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), stroke.width, StrokeCap.Round)
                drawSmileArc(0.30f, 0.62f, 0.50f, 0.76f, 0.70f, 0.62f)
            }
            SmileIconType.RESET -> {
                drawCircle(tint, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawSmileArc(0.35f, 0.58f, 0.50f, 0.70f, 0.65f, 0.58f)
            }
        }
    }
}

@Composable
fun SmileIconButton(
    icon: SmileIconType,
    accessibleLabel: String,
    shortcutBadge: String? = null,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current
    // A 30dp target is fine with a mouse and fiddly with a thumb, so phones get 44dp.
    val touchMin = if (rememberIsCompact()) 44.dp else 30.dp
    val bgBrush = when {
        isPrimary -> Brush.verticalGradient(listOf(Color(0xFF2997FF), Color(0xFF0071E3)))
        isActive -> Brush.verticalGradient(listOf(Color(0xFF194F78), Color(0xFF123A5C)))
        else -> Brush.verticalGradient(listOf(palette.controlBg, palette.controlBg))
    }
    val contentColor = when {
        !enabled -> palette.muted.copy(alpha = 0.4f)
        isPrimary -> Color.White
        isDanger -> palette.red
        isActive -> Color(0xFFD9F0FF)
        else -> palette.text
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = touchMin)
            .widthIn(min = if (rememberIsCompact()) 44.dp else 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgBrush)
            .border(
                width = 0.5.dp,
                color = if (isActive) palette.accent.copy(alpha = 0.7f) else palette.line,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics { contentDescription = accessibleLabel }
    ) {
        SmileVectorIcon(type = icon, tint = contentColor)
        if (!shortcutBadge.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = shortcutBadge,
                color = contentColor.copy(alpha = 0.65f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Plain app wordmark.
 *
 * This used to draw three macOS-style window dots. They read as buttons, have no meaning on
 * Android, and only added clutter, so they are gone; the actions they carried now live in the
 * Tools panel.
 */
@Composable
fun AppWordmark(title: String = "Edit Pro") {
    val palette = LocalEditProPalette.current
    Text(
        text = title,
        color = palette.text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}
