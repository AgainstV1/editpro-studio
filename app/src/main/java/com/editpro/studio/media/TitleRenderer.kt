package com.editpro.studio.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.editpro.studio.model.AnimatedProperties
import com.editpro.studio.model.MgMath
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws text clips and motion graphics onto an Android [Canvas].
 *
 * The exporter and the preview both call this, so a title looks the same on the stage as it does
 * in the exported file. All sizes are relative to a 1280×720 reference frame and scale with the
 * canvas, which is how the preview (a few hundred pixels wide) matches a 1080p export.
 *
 * Drawing only: every timing decision comes from [MgMath] and [TimelineEngine], which are covered
 * by JVM unit tests.
 */
object TitleRenderer {

    private val TITLE_TRACKS = TimelineTrack.entries.filter { it.isTitle }

    /** Draws every title-track clip under the playhead: GRAPHICS first, then TEXT on top. */
    fun drawTitles(
        canvas: Canvas,
        width: Int,
        height: Int,
        playheadSec: Double,
        allResolved: List<ResolvedClip>,
        trackStates: Map<TimelineTrack, TrackState>
    ) {
        if (width <= 0 || height <= 0) return
        val projectEnd = allResolved.maxOfOrNull { it.end } ?: 0.0
        for (track in TITLE_TRACKS) {
            if (trackStates[track]?.hidden == true) continue
            for (rc in allResolved) {
                if (rc.clip.track != track) continue
                if (!TimelineEngine.isActiveAt(rc, playheadSec, projectEnd)) continue
                drawClip(canvas, width, height, rc.clip, playheadSec - rc.start, rc.duration)
            }
        }
    }

    /** Draws one title or motion-graphics clip at clip-local time [localT]. */
    fun drawClip(canvas: Canvas, width: Int, height: Int, clip: TimelineClip, localT: Double, duration: Double) {
        if (width <= 0 || height <= 0) return
        val t = localT.coerceIn(0.0, max(0.0, duration))
        if (clip.isGraphic) {
            Frame(canvas, width, height, clip, t, duration).drawGraphic()
        } else {
            drawPlainText(canvas, width, height, clip, t, duration)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun parseColor(hex: String, fallback: Int): Int =
        try { Color.parseColor(hex) } catch (_: Exception) { fallback }

    private fun fade(color: Int, alpha: Double): Int = Color.argb(
        (Color.alpha(color) * alpha.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun typefaceOf(clip: TimelineClip, forceBold: Boolean? = null): Typeface = Typeface.create(
        when (clip.fontFamily) {
            "monospace" -> Typeface.MONOSPACE
            "serif", "Georgia" -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        },
        if (forceBold ?: (clip.fontWeight >= 700)) Typeface.BOLD else Typeface.NORMAL
    )

    /** Opacity from a clip's fade-in / fade-out transitions; 1 when it has none. */
    private fun transitionAlpha(c: TimelineClip, localT: Double, duration: Double): Double {
        val span = max(0.05, c.transitionDuration)
        var a = 1.0
        if (c.transition == "fade" || c.transition == "dissolve") a *= (localT / span).coerceIn(0.0, 1.0)
        if (c.transitionOut != "none") a *= ((duration - localT) / span).coerceIn(0.0, 1.0)
        if (c.fadeOut > 0.0) a *= ((duration - localT) / c.fadeOut).coerceIn(0.0, 1.0)
        return a
    }

    // --------------------------------------------------------------- plain text

    private fun drawPlainText(canvas: Canvas, width: Int, height: Int, c: TimelineClip, localT: Double, duration: Double) {
        val anim = TimelineEngine.animatedProps(c, localT)
        val alpha = (anim.opacity / 100.0) * transitionAlpha(c, localT, duration)
        if (alpha <= 0.003) return
        val alphaInt = (alpha * 255.0).roundToInt().coerceIn(0, 255)
        val scaleFactor = min(width / 1280f, height / 720f)
        val pxSize = max(10f * scaleFactor, c.fontSize * (anim.scale.toFloat() / 100f) * scaleFactor)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(c.textColor, Color.WHITE)
            this.alpha = alphaInt
            textSize = pxSize
            typeface = typefaceOf(c)
            textAlign = when (c.textAlign) {
                "left" -> Paint.Align.LEFT
                "right" -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            if (c.shadow) {
                setShadowLayer(pxSize * 0.18f, 0f, pxSize * 0.05f, Color.argb((200 * alpha).roundToInt(), 0, 0, 0))
            }
        }

        val lines = c.text.ifEmpty { "Text" }.split("\n")
        val lineH = pxSize * 1.18f
        val baseX = when (c.textAlign) {
            "left" -> width * 0.08f
            "right" -> width * 0.92f
            else -> width / 2f
        } + ((anim.x / 100.0) * width).toFloat()
        val centerY = when (c.verticalPosition) {
            "top" -> height * 0.18f
            "bottom" -> height * 0.82f
            else -> height / 2f
        } + ((anim.y / 100.0) * height).toFloat()
        var baseY = centerY - (lines.size - 1) * lineH / 2f

        val rotated = anim.rotation != 0.0
        if (rotated) {
            canvas.save()
            canvas.rotate(anim.rotation.toFloat(), baseX, centerY)
        }
        val strokePaint = if (c.strokeWidth > 0f) {
            Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = c.strokeWidth * scaleFactor * 2f
                strokeJoin = Paint.Join.ROUND
                color = parseColor(c.strokeColor, Color.BLACK)
                this.alpha = alphaInt
                clearShadowLayer()
            }
        } else null
        for (line in lines) {
            if (strokePaint != null) canvas.drawText(line, baseX, baseY, strokePaint)
            canvas.drawText(line, baseX, baseY, textPaint)
            baseY += lineH
        }
        if (rotated) canvas.restore()
    }

    // ----------------------------------------------------------- motion graphics

    /** Everything one graphic needs for one frame, resolved once. */
    private class Frame(
        val canvas: Canvas,
        val width: Int,
        val height: Int,
        val c: TimelineClip,
        val localT: Double,
        val duration: Double
    ) {
        val anim: AnimatedProperties = TimelineEngine.animatedProps(c, localT)
        val s: Float = min(width / 1280f, height / 720f)
        // Graphics animate themselves in and out, so of the clip-level fades only Fade Out applies.
        val opacity: Double = (anim.opacity / 100.0).coerceIn(0.0, 1.0) *
            (if (c.fadeOut > 0.0) ((duration - localT) / c.fadeOut).coerceIn(0.0, 1.0) else 1.0)
        val userScale: Float = (anim.scale / 100.0).toFloat().coerceAtLeast(0.01f)
        val introSec: Double = c.transitionDuration.coerceIn(0.0, 5.0)
        val pIn: Double = MgMath.introProgress(localT, duration, introSec)
        val pOut: Double = MgMath.outroProgress(localT, duration, introSec)
        val presence: Double = MgMath.presence(localT, duration, introSec)
        val textColor: Int = parseColor(c.textColor, Color.WHITE)
        val accent: Int = parseColor(c.accentColor, Color.parseColor("#0a84ff"))
        val lines: List<String> = c.text.split("\n")
        val px: Float = max(8f, c.fontSize * s)

        /** Anchor from the alignment settings plus the X / Y offsets. */
        val ax: Float = when (c.textAlign) {
            "left" -> width * 0.08f
            "right" -> width * 0.92f
            else -> width / 2f
        } + (anim.x / 100.0 * width).toFloat()
        val ay: Float = when (c.verticalPosition) {
            "top" -> height * 0.16f
            "bottom" -> height * 0.84f
            else -> height / 2f
        } + (anim.y / 100.0 * height).toFloat()

        fun line(i: Int): String = lines.getOrNull(i)?.trim().orEmpty()

        fun textPaint(size: Float, color: Int, alpha: Double, bold: Boolean? = null, align: Paint.Align = Paint.Align.LEFT, shadow: Boolean = false): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = fade(color, alpha * opacity)
                textSize = size
                typeface = typefaceOf(c, bold)
                textAlign = align
                if (shadow && c.shadow) {
                    setShadowLayer(size * 0.16f, 0f, size * 0.05f, Color.argb((190 * alpha * opacity).roundToInt().coerceIn(0, 255), 0, 0, 0))
                }
            }

        fun fillPaint(color: Int, alpha: Double = 1.0): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = fade(color, alpha * opacity)
            style = Paint.Style.FILL
        }

        fun strokePaint(color: Int, widthPx: Float, alpha: Double = 1.0): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = fade(color, alpha * opacity)
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        /** Left edge of a block of [blockW] placed against the anchor according to the alignment. */
        fun alignedLeft(blockW: Float): Float = when (c.textAlign) {
            "left" -> 0f
            "right" -> -blockW
            else -> -blockW / 2f
        }

        fun paintAlign(): Paint.Align = when (c.textAlign) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }

        /** Runs [block] with the origin at the anchor, rotated and scaled by the clip transform. */
        inline fun atAnchor(block: () -> Unit) {
            canvas.save()
            canvas.translate(ax, ay)
            if (anim.rotation != 0.0) canvas.rotate(anim.rotation.toFloat())
            if (userScale != 1f) canvas.scale(userScale, userScale)
            block()
            canvas.restore()
        }

        fun drawGraphic() {
            if (opacity <= 0.003) return
            when (c.graphic) {
                "lower_third" -> lowerThird()
                "title_card" -> titleCard()
                "typewriter" -> typewriter()
                "quote" -> quote()
                "chapter_tag" -> chapterTag()
                "credits_roll" -> creditsRoll()
                "callout" -> callout()
                "subscribe" -> subscribe()
                "countdown" -> countdown()
                "counter" -> counter()
                "timer" -> timer()
                "progress_bar" -> progressBar()
                "letterbox" -> letterbox()
                "shape_rect" -> shapeBox(oval = false)
                "shape_ellipse" -> shapeBox(oval = true)
                "shape_line" -> shapeLine()
                "shape_arrow" -> shapeArrow()
                // A template this build does not know (a project from a newer version) still
                // shows its text rather than vanishing.
                else -> drawPlainText(canvas, width, height, c, localT, duration)
            }
        }

        // -- Titles

        private fun lowerThird() = atAnchor {
            val name = line(0).ifEmpty { "Your Name" }
            val role = line(1)
            val namePaint = textPaint(px, textColor, 1.0, bold = true)
            val rolePx = px * 0.58f
            val rolePaint = textPaint(rolePx, textColor, 0.82, bold = false)
            val contentW = max(namePaint.measureText(name), if (role.isEmpty()) 0f else rolePaint.measureText(role))
            val padX = px * 0.5f
            val padY = px * 0.34f
            val barW = max(3f, px * 0.14f)
            val gap = px * 0.22f
            val blockW = barW + padX + contentW + padX
            val blockH = padY + px + (if (role.isEmpty()) 0f else gap + rolePx) + padY
            val left = alignedLeft(blockW)
            val top = -blockH / 2f

            // The plate wipes open from the bar; the text follows it in.
            val reveal = MgMath.easeOutCubic(presence).toFloat()
            canvas.save()
            canvas.clipRect(left, top, left + barW + (blockW - barW) * reveal, top + blockH)
            val radius = max(0f, c.cornerRadius * s)
            canvas.drawRoundRect(RectF(left, top, left + blockW, top + blockH), radius, radius, fillPaint(Color.BLACK, 0.58))
            val textLeft = left + barW + padX
            val nameIn = MgMath.easeOutCubic(MgMath.stagger(presence, 0, 2)).toFloat()
            namePaint.alpha = (namePaint.alpha * nameIn).roundToInt()
            canvas.drawText(name, textLeft - (1f - nameIn) * padX * 1.5f, top + padY + px * 0.86f, namePaint)
            if (role.isNotEmpty()) {
                val roleIn = MgMath.easeOutCubic(MgMath.stagger(presence, 1, 2)).toFloat()
                rolePaint.alpha = (rolePaint.alpha * roleIn).roundToInt()
                canvas.drawText(role, textLeft - (1f - roleIn) * padX * 1.5f, top + padY + px + gap + rolePx * 0.86f, rolePaint)
            }
            canvas.restore()
            val barGrow = MgMath.easeOutCubic(MgMath.clamp01(presence * 2.0)).toFloat()
            canvas.drawRect(left, top + blockH * (1f - barGrow) / 2f, left + barW, top + blockH * (1f + barGrow) / 2f, fillPaint(accent))
        }

        private fun titleCard() = atAnchor {
            val title = line(0).ifEmpty { "TITLE" }
            val subtitle = line(1)
            val out = 1.0 - pOut
            val titleIn = MgMath.easeOutCubic(MgMath.stagger(pIn, 0, 3))
            val ruleIn = MgMath.easeInOutCubic(MgMath.stagger(pIn, 1, 3))
            val subIn = MgMath.easeOutCubic(MgMath.stagger(pIn, 2, 3))
            val align = paintAlign()
            val titlePaint = textPaint(px, textColor, titleIn * out, bold = true, align = align, shadow = true)
            val titleW = titlePaint.measureText(title)
            val subPx = px * 0.42f
            val ruleH = max(2f, px * 0.06f)
            val gap = px * 0.26f
            val blockH = px + gap + ruleH + (if (subtitle.isEmpty()) 0f else gap + subPx)
            val top = -blockH / 2f

            canvas.save()
            val grow = (0.92 + 0.08 * titleIn).toFloat()
            canvas.scale(grow, grow)
            canvas.drawText(title, 0f, top + px * 0.86f, titlePaint)
            canvas.restore()

            val ruleW = titleW * 0.62f * ruleIn.toFloat()
            val ruleCenter = when (c.textAlign) {
                "left" -> titleW * 0.31f
                "right" -> -titleW * 0.31f
                else -> 0f
            }
            val ruleTop = top + px + gap
            if (ruleW > 0.5f) {
                canvas.drawRoundRect(
                    RectF(ruleCenter - ruleW / 2f, ruleTop, ruleCenter + ruleW / 2f, ruleTop + ruleH),
                    ruleH / 2f, ruleH / 2f, fillPaint(accent, out)
                )
            }
            if (subtitle.isNotEmpty()) {
                val subPaint = textPaint(subPx, textColor, 0.88 * subIn * out, bold = false, align = align, shadow = true)
                canvas.drawText(subtitle, 0f, ruleTop + ruleH + gap + subPx * 0.86f + (1f - subIn.toFloat()) * subPx * 0.5f, subPaint)
            }
        }

        private fun typewriter() = atAnchor {
            val shown = lines.ifEmpty { listOf("") }
            val total = shown.sumOf { it.length }
            var remaining = MgMath.typewriterCount(total, localT, duration)
            val paint = textPaint(px, textColor, 1.0 - pOut, align = Paint.Align.LEFT, shadow = true)
            val lineH = px * 1.22f
            var y = -(shown.size - 1) * lineH / 2f + px * 0.34f
            var cursorX = 0f
            var cursorY = y
            for (full in shown) {
                // Each line is laid out at its final position, so text does not slide while typing.
                val x0 = alignedLeft(paint.measureText(full))
                val visible = full.take(remaining.coerceAtLeast(0))
                if (visible.isNotEmpty()) canvas.drawText(visible, x0, y, paint)
                if (remaining >= 0) {
                    cursorX = x0 + paint.measureText(visible)
                    cursorY = y
                }
                remaining -= full.length
                if (remaining < 0) break
                y += lineH
            }
            if (MgMath.cursorVisible(localT) && pOut <= 0.0) {
                val cw = max(2f, px * 0.08f)
                canvas.drawRect(cursorX + cw, cursorY - px * 0.82f, cursorX + cw * 2f, cursorY + px * 0.12f, fillPaint(accent))
            }
        }

        private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            if (text.isEmpty() || paint.measureText(text) <= maxWidth) return listOf(text)
            val out = ArrayList<String>()
            var current = StringBuilder()
            for (word in text.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    out.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
            return out
        }

        private fun quote() = atAnchor {
            val filled = lines.map { it.trim() }.filter { it.isNotEmpty() }
            val author = if (filled.size > 1) filled.last() else ""
            val bodySource = if (filled.size > 1) filled.dropLast(1) else filled.ifEmpty { listOf("Quote") }
            val out = 1.0 - pOut
            val bodyPaint = textPaint(px, textColor, 1.0, bold = c.fontWeight >= 700, align = Paint.Align.CENTER, shadow = true)
            val body = bodySource.flatMap { wrap(it, bodyPaint, width * 0.72f / userScale) }
            val lineH = px * 1.24f
            val authorPx = px * 0.56f
            val markPx = px * 2.4f
            val blockH = body.size * lineH + (if (author.isEmpty()) 0f else authorPx * 1.8f)
            val top = -blockH / 2f
            val count = body.size + 2

            val markIn = MgMath.easeOutBack(MgMath.stagger(pIn, 0, count)).toFloat().coerceAtLeast(0f)
            val markPaint = textPaint(markPx, accent, MgMath.stagger(pIn, 0, count) * out, bold = true, align = Paint.Align.CENTER)
            canvas.save()
            canvas.translate(0f, top - markPx * 0.08f)
            canvas.scale(markIn, markIn)
            canvas.drawText("\u201C", 0f, 0f, markPaint)
            canvas.restore()

            body.forEachIndexed { i, text ->
                val p = MgMath.easeOutCubic(MgMath.stagger(pIn, i + 1, count))
                bodyPaint.color = fade(textColor, p * out * opacity)
                canvas.drawText(text, 0f, top + i * lineH + px * 0.9f + (1f - p.toFloat()) * px * 0.4f, bodyPaint)
            }
            if (author.isNotEmpty()) {
                val p = MgMath.easeOutCubic(MgMath.stagger(pIn, count - 1, count))
                val authorPaint = textPaint(authorPx, accent, p * out, bold = true, align = Paint.Align.CENTER)
                canvas.drawText(author, 0f, top + body.size * lineH + authorPx * 1.5f, authorPaint)
            }
        }

        private fun chapterTag() = atAnchor {
            val tag = line(0).ifEmpty { "CHAPTER" }
            val caption = line(1)
            val tagPx = px * 0.74f
            val tagPaint = textPaint(tagPx, textColor, 1.0, bold = true).apply { letterSpacing = 0.08f }
            val tagW = tagPaint.measureText(tag)
            val padX = tagPx * 0.6f
            val padY = tagPx * 0.34f
            val pillW = tagW + padX * 2f
            val pillH = tagPx + padY * 2f
            val captionPaint = textPaint(px, textColor, 1.0, bold = false, shadow = true)
            val captionW = if (caption.isEmpty()) 0f else captionPaint.measureText(caption)
            val blockW = max(pillW, captionW)
            val left = alignedLeft(blockW)
            val pillLeft = when (c.textAlign) {
                "right" -> left + blockW - pillW
                "center" -> left + (blockW - pillW) / 2f
                else -> left
            }
            val reveal = MgMath.easeOutCubic(MgMath.stagger(presence, 0, 2)).toFloat()
            canvas.save()
            canvas.clipRect(pillLeft, 0f, pillLeft + pillW * reveal, pillH)
            val radius = if (c.cornerRadius > 0) c.cornerRadius * s else pillH * 0.22f
            canvas.drawRoundRect(RectF(pillLeft, 0f, pillLeft + pillW, pillH), radius, radius, fillPaint(accent))
            canvas.drawText(tag, pillLeft + padX, padY + tagPx * 0.86f, tagPaint)
            canvas.restore()
            if (caption.isNotEmpty()) {
                val p = MgMath.easeOutCubic(MgMath.stagger(presence, 1, 2))
                captionPaint.color = fade(textColor, p * opacity)
                val captionLeft = when (c.textAlign) {
                    "right" -> left + blockW - captionW
                    "center" -> left + (blockW - captionW) / 2f
                    else -> left
                }
                canvas.drawText(caption, captionLeft - (1f - p.toFloat()) * px * 0.4f, pillH + px * 1.12f, captionPaint)
            }
        }

        private fun creditsRoll() {
            val lineH = px * 1.36f
            val contentH = lines.size * lineH
            val top = MgMath.creditsOffset(localT, duration, contentH.toDouble() * userScale, height.toDouble()).toFloat()
            val headPaint = textPaint(px * 0.6f, accent, 1.0, bold = true, align = paintAlign()).apply { letterSpacing = 0.12f }
            val namePaint = textPaint(px, textColor, 1.0, bold = false, align = paintAlign(), shadow = true)
            canvas.save()
            canvas.translate(ax, top + (anim.y / 100.0 * height).toFloat())
            if (userScale != 1f) canvas.scale(userScale, userScale)
            lines.forEachIndexed { i, raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@forEachIndexed
                val y = i * lineH + px
                // Skip lines outside the frame: a long roll has most of its lines off screen.
                val screenY = top + y * userScale
                if (screenY < -lineH * userScale || screenY > height + lineH * userScale) return@forEachIndexed
                // A line in capitals is a heading ("DIRECTED BY"); anything else is a name.
                val heading = text.any { it.isLetter() } && text == text.uppercase()
                canvas.drawText(text, 0f, y, if (heading) headPaint else namePaint)
            }
            canvas.restore()
        }

        // -- Labels

        private fun callout() = atAnchor {
            val label = line(0).ifEmpty { "Label" }
            val dir = if (c.textAlign == "right") -1f else 1f
            val dotIn = MgMath.easeOutBack(MgMath.stagger(presence, 0, 3)).toFloat().coerceAtLeast(0f)
            val lineIn = MgMath.easeInOutCubic(MgMath.stagger(presence, 1, 3)).toFloat()
            val labelIn = MgMath.easeOutCubic(MgMath.stagger(presence, 2, 3))
            val dotR = px * 0.2f
            val elbowX = px * 1.5f * dir
            val elbowY = -px * 1.3f
            val runX = elbowX + px * 0.9f * dir

            canvas.drawCircle(0f, 0f, dotR * dotIn, fillPaint(accent))
            canvas.drawCircle(0f, 0f, dotR * 1.9f * dotIn, strokePaint(accent, max(1.5f, px * 0.05f), 0.55))

            if (lineIn > 0f) {
                // The pointer is two segments; draw as much of their combined length as has grown.
                val path = Path().apply { moveTo(0f, 0f) }
                val firstShare = 0.65f
                if (lineIn <= firstShare) {
                    val k = lineIn / firstShare
                    path.lineTo(elbowX * k, elbowY * k)
                } else {
                    val k = (lineIn - firstShare) / (1f - firstShare)
                    path.lineTo(elbowX, elbowY)
                    path.lineTo(elbowX + (runX - elbowX) * k, elbowY)
                }
                canvas.drawPath(path, strokePaint(accent, max(1.5f, px * 0.07f)))
            }

            if (labelIn > 0.0) {
                val paint = textPaint(px, textColor, labelIn, bold = true)
                val textW = paint.measureText(label)
                val padX = px * 0.42f
                val padY = px * 0.26f
                val boxW = textW + padX * 2f
                val boxH = px + padY * 2f
                val boxLeft = if (dir > 0f) runX else runX - boxW
                val boxTop = elbowY - boxH / 2f
                val radius = if (c.cornerRadius > 0) c.cornerRadius * s else boxH * 0.2f
                val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
                canvas.drawRoundRect(rect, radius, radius, fillPaint(Color.BLACK, 0.66 * labelIn))
                canvas.drawRoundRect(rect, radius, radius, strokePaint(accent, max(1.5f, px * 0.05f), labelIn))
                canvas.drawText(label, boxLeft + padX, boxTop + padY + px * 0.86f, paint)
            }
        }

        private fun subscribe() = atAnchor {
            val label = line(0).ifEmpty { "SUBSCRIBE" }
            val paint = textPaint(px, textColor, 1.0, bold = true, align = Paint.Align.CENTER)
            val textW = paint.measureText(label)
            val padX = px * 0.85f
            val padY = px * 0.42f
            val w = textW + padX * 2f
            val h = px + padY * 2f
            // Pop in, then dip once around the middle of the clip as if it were pressed.
            val pop = MgMath.easeOutBack(pIn).toFloat().coerceAtLeast(0f) * (1.0 - MgMath.easeInOutCubic(pOut)).toFloat()
            val pressAt = duration * 0.5
            val press = (1.0 - (kotlin.math.abs(localT - pressAt) / 0.18).coerceIn(0.0, 1.0))
            val scale = pop * (1f - 0.08f * MgMath.easeInOutCubic(press).toFloat())
            if (scale <= 0.01f) return@atAnchor
            canvas.save()
            canvas.scale(scale, scale)
            val rect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
            val radius = if (c.cornerRadius > 0) c.cornerRadius * s else h / 2f
            canvas.drawRoundRect(rect, radius, radius, fillPaint(accent))
            if (press > 0.0) {
                canvas.drawRoundRect(rect, radius, radius, fillPaint(Color.WHITE, 0.22 * press))
            }
            canvas.drawText(label, 0f, px * 0.36f, paint)
            canvas.restore()
        }

        // -- Counters

        private fun countdown() = atAnchor {
            val number = MgMath.countdownValue(localT, duration)
            val sweep = MgMath.countdownSweep(localT)
            val out = 1.0 - pOut
            val radius = px * 0.78f
            val ringW = max(3f, px * 0.06f)
            val oval = RectF(-radius, -radius, radius, radius)
            canvas.drawCircle(0f, 0f, radius, strokePaint(textColor, ringW, 0.22 * out))
            canvas.drawArc(oval, -90f, (360.0 * sweep).toFloat(), false, strokePaint(accent, ringW, out))

            // Each new number lands slightly large and settles.
            val beat = (1.0 + 0.22 * (1.0 - MgMath.easeOutCubic(sweep * 3.0))).toFloat()
            val paint = textPaint(px, textColor, out, bold = true, align = Paint.Align.CENTER, shadow = true)
            canvas.save()
            canvas.scale(beat, beat)
            canvas.drawText(number.toString(), 0f, px * 0.35f, paint)
            canvas.restore()

            val word = line(0)
            if (word.isNotEmpty()) {
                val wordPaint = textPaint(px * 0.2f, textColor, 0.85 * out, bold = true, align = Paint.Align.CENTER, shadow = true)
                wordPaint.letterSpacing = 0.1f
                canvas.drawText(word, 0f, radius + px * 0.36f, wordPaint)
            }
        }

        private fun counter() = atAnchor {
            val targetLine = line(0).ifEmpty { "100" }
            val label = line(1)
            val target = MgMath.parseTarget(targetLine)
            val shown = if (target == null) targetLine
            else MgMath.formatCounter(MgMath.counterValue(target, localT, duration), targetLine)
            val alpha = MgMath.easeOutCubic(pIn) * (1.0 - pOut)
            val align = paintAlign()
            val paint = textPaint(px, textColor, alpha, bold = true, align = align, shadow = true)
            // Digits of equal width stop the number from shivering as it counts.
            paint.fontFeatureSettings = "tnum"
            canvas.drawText(shown, 0f, px * 0.2f, paint)
            if (label.isNotEmpty()) {
                val labelPx = px * 0.3f
                val labelPaint = textPaint(labelPx, accent, alpha, bold = true, align = align, shadow = true)
                labelPaint.letterSpacing = 0.08f
                canvas.drawText(label, 0f, px * 0.2f + labelPx * 1.7f, labelPaint)
            }
        }

        private fun timer() = atAnchor {
            val prefix = line(0)
            val time = MgMath.timerText(localT)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fade(textColor, opacity)
                textSize = px
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val labelPaint = textPaint(px * 0.62f, textColor, 0.8, bold = true)
            val dotR = px * 0.2f
            val padX = px * 0.44f
            val padY = px * 0.26f
            val gap = px * 0.3f
            val labelW = if (prefix.isEmpty()) 0f else labelPaint.measureText(prefix) + gap
            // Sized for the widest reading so the plate does not change width as digits change.
            val timeW = paint.measureText("0".repeat(time.length))
            val blockW = padX + dotR * 2f + gap + labelW + timeW + padX
            val blockH = px + padY * 2f
            val left = alignedLeft(blockW)
            val top = -blockH / 2f
            val radius = if (c.cornerRadius > 0) c.cornerRadius * s else blockH * 0.22f
            canvas.drawRoundRect(RectF(left, top, left + blockW, top + blockH), radius, radius, fillPaint(Color.BLACK, 0.6))
            canvas.drawCircle(left + padX + dotR, 0f, dotR, fillPaint(accent, if (MgMath.cursorVisible(localT / 2.0)) 1.0 else 0.35))
            var x = left + padX + dotR * 2f + gap
            if (prefix.isNotEmpty()) {
                canvas.drawText(prefix, x, px * 0.24f, labelPaint)
                x += labelW
            }
            canvas.drawText(time, x, px * 0.35f, paint)
        }

        private fun progressBar() {
            val thickness = max(1f, (c.boxH / 100.0 * height).toFloat() * userScale)
            val top = when (c.verticalPosition) {
                "top" -> 0f
                "center" -> (height - thickness) / 2f
                else -> height - thickness
            } + (anim.y / 100.0 * height).toFloat()
            val fraction = MgMath.progressFraction(localT, duration).toFloat()
            canvas.drawRect(0f, top, width.toFloat(), top + thickness, fillPaint(textColor, 0.2))
            canvas.drawRect(0f, top, width * fraction, top + thickness, fillPaint(accent))
        }

        // -- Shapes & frames

        private fun letterbox() {
            val bar = (c.boxH.coerceIn(0.0, 50.0) / 100.0 * height).toFloat() * MgMath.easeOutCubic(presence).toFloat()
            if (bar <= 0.2f) return
            val paint = fillPaint(accent)
            canvas.drawRect(0f, 0f, width.toFloat(), bar, paint)
            canvas.drawRect(0f, height - bar, width.toFloat(), height.toFloat(), paint)
        }

        private fun shapeBox(oval: Boolean) = atAnchor {
            val w = (c.boxW / 100.0 * width).toFloat()
            val h = (c.boxH / 100.0 * height).toFloat()
            val grow = (0.86 + 0.14 * MgMath.easeOutCubic(presence)).toFloat()
            val rect = RectF(-w / 2f * grow, -h / 2f * grow, w / 2f * grow, h / 2f * grow)
            val fill = fillPaint(accent, presence)
            val outline = if (c.strokeWidth > 0f) {
                strokePaint(parseColor(c.strokeColor, Color.BLACK), c.strokeWidth * s * 2f, presence)
            } else null
            if (oval) {
                canvas.drawOval(rect, fill)
                outline?.let { canvas.drawOval(rect, it) }
            } else {
                val radius = max(0f, c.cornerRadius * s)
                canvas.drawRoundRect(rect, radius, radius, fill)
                outline?.let { canvas.drawRoundRect(rect, radius, radius, it) }
            }
        }

        private fun shapeLine() = atAnchor {
            val length = (c.boxW / 100.0 * width).toFloat() * MgMath.easeInOutCubic(presence).toFloat()
            val thickness = max(1f, (c.boxH / 100.0 * height).toFloat())
            if (length <= 0.5f) return@atAnchor
            canvas.drawRoundRect(
                RectF(-length / 2f, -thickness / 2f, length / 2f, thickness / 2f),
                thickness / 2f, thickness / 2f, fillPaint(accent)
            )
        }

        private fun shapeArrow() = atAnchor {
            val total = (c.boxW / 100.0 * width).toFloat()
            val headH = max(4f, (c.boxH / 100.0 * height).toFloat())
            val headL = min(total * 0.45f, headH * 0.9f)
            val shaftH = headH * 0.36f
            val grow = MgMath.easeOutCubic(presence).toFloat()
            if (grow <= 0.01f) return@atAnchor
            val tail = -total / 2f
            val tip = tail + max(headL, total * grow)
            val neck = tip - headL
            val path = Path().apply {
                moveTo(tail, -shaftH / 2f)
                lineTo(neck, -shaftH / 2f)
                lineTo(neck, -headH / 2f)
                lineTo(tip, 0f)
                lineTo(neck, headH / 2f)
                lineTo(neck, shaftH / 2f)
                lineTo(tail, shaftH / 2f)
                close()
            }
            canvas.drawPath(path, fillPaint(accent, MgMath.clamp01(presence * 3.0)))
        }
    }
}
