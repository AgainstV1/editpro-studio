package com.editpro.studio.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A motion-graphics template: an animated title, counter or shape that is drawn procedurally.
 *
 * A template is not a bundle of clips. It becomes one ordinary [TimelineClip] whose `graphic`
 * field names the template, so it trims, splits, moves, keyframes and saves like any other clip.
 * Its content comes from the clip's own fields — `text` (one line per element), `textColor`,
 * `accentColor`, `fontSize`, the position settings and `boxW` / `boxH` — and its intro and outro
 * are computed from the clip's local time, so they follow the clip when it is retimed.
 */
data class MgTemplate(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val defaultText: String = "",
    /** What each text line means, shown under the text box in the inspector. */
    val textHint: String = "",
    val defaultDuration: Double = 4.0,
    val track: TimelineTrack = TimelineTrack.GRAPHICS,
    val fontSize: Int = 42,
    val verticalPosition: String = "center",
    val textAlign: String = "center",
    val textColor: String = "#ffffff",
    val accentColor: String = "#0a84ff",
    val boxW: Double = 40.0,
    val boxH: Double = 40.0,
    /** The W / H sliders mean something for this template. */
    val usesBox: Boolean = false,
    /** Inserted across the whole edit rather than at the playhead (bars, progress, timer). */
    val fullLength: Boolean = false
)

object MotionGraphics {

    val templates: List<MgTemplate> = listOf(
        MgTemplate(
            id = "lower_third", title = "Lower third", category = "Titles",
            description = "Name and role on an accent bar that wipes in from the left.",
            defaultText = "Your Name\nTitle or role", textHint = "Line 1: name · Line 2: role",
            defaultDuration = 5.0, fontSize = 40, verticalPosition = "bottom", textAlign = "left"
        ),
        MgTemplate(
            id = "title_card", title = "Title card", category = "Titles",
            description = "Large title with an underline that draws on, then a subtitle.",
            defaultText = "MAIN TITLE\nA short subtitle", textHint = "Line 1: title · Line 2: subtitle",
            defaultDuration = 4.0, fontSize = 72
        ),
        MgTemplate(
            id = "typewriter", title = "Typewriter", category = "Titles",
            description = "Text typed out one character at a time with a blinking cursor.",
            defaultText = "Type your message here…", defaultDuration = 4.0, fontSize = 44
        ),
        MgTemplate(
            id = "quote", title = "Quote", category = "Titles",
            description = "A quotation with oversized marks and an attribution line.",
            defaultText = "Simplicity is the ultimate sophistication.\n— Leonardo da Vinci",
            textHint = "Last line: who said it", defaultDuration = 5.0, fontSize = 40
        ),
        MgTemplate(
            id = "chapter_tag", title = "Chapter tag", category = "Titles",
            description = "Small corner tag for chapters, locations or dates.",
            defaultText = "CHAPTER 01\nGetting started", textHint = "Line 1: tag · Line 2: caption",
            defaultDuration = 4.0, fontSize = 30, verticalPosition = "top", textAlign = "left"
        ),
        MgTemplate(
            id = "credits_roll", title = "Credits roll", category = "Titles",
            description = "Lines scroll from the bottom of the frame to the top over the clip.",
            defaultText = "DIRECTED BY\nYour Name\n\nEDITED BY\nYour Name\n\nMUSIC\nArtist Name",
            textHint = "One credit per line · blank lines add space", defaultDuration = 10.0,
            track = TimelineTrack.TEXT, fontSize = 34
        ),
        MgTemplate(
            id = "callout", title = "Callout", category = "Labels",
            description = "A label with a pointer line. Move it with Position X / Y.",
            defaultText = "Look here", defaultDuration = 4.0, fontSize = 32
        ),
        MgTemplate(
            id = "subscribe", title = "Subscribe button", category = "Labels",
            description = "Pill button that pops in and pulses as if clicked.",
            defaultText = "SUBSCRIBE", defaultDuration = 4.0, fontSize = 36,
            verticalPosition = "bottom", accentColor = "#ff3b30"
        ),
        MgTemplate(
            id = "countdown", title = "Countdown", category = "Counters",
            description = "Counts down one number per second inside a sweeping ring.",
            defaultText = "", textHint = "Optional word shown under the number",
            defaultDuration = 3.0, fontSize = 150
        ),
        MgTemplate(
            id = "counter", title = "Number counter", category = "Counters",
            description = "Counts up from zero to your number.",
            defaultText = "1000\nFollowers", textHint = "Line 1: target number · Line 2: label",
            defaultDuration = 3.0, fontSize = 96
        ),
        MgTemplate(
            id = "timer", title = "Running timer", category = "Counters",
            description = "Elapsed time since the clip started, burned into a corner.",
            defaultText = "", defaultDuration = 10.0, fontSize = 34,
            verticalPosition = "top", textAlign = "right", fullLength = true
        ),
        MgTemplate(
            id = "progress_bar", title = "Progress bar", category = "Counters",
            description = "A thin bar that fills across the frame as the clip plays.",
            defaultDuration = 10.0, verticalPosition = "bottom", boxH = 1.2, usesBox = true,
            fullLength = true
        ),
        MgTemplate(
            id = "letterbox", title = "Cinematic bars", category = "Shapes & frames",
            description = "Black bars slide in from the top and bottom for a widescreen look.",
            defaultDuration = 10.0, accentColor = "#000000", boxH = 12.0, usesBox = true,
            fullLength = true
        ),
        MgTemplate(
            id = "shape_rect", title = "Rectangle", category = "Shapes & frames",
            description = "A solid block; round its corners with Corner Radius.",
            defaultDuration = 4.0, boxW = 40.0, boxH = 24.0, usesBox = true
        ),
        MgTemplate(
            id = "shape_ellipse", title = "Ellipse", category = "Shapes & frames",
            description = "A solid circle or oval.",
            defaultDuration = 4.0, boxW = 22.0, boxH = 39.0, usesBox = true
        ),
        MgTemplate(
            id = "shape_line", title = "Divider line", category = "Shapes & frames",
            description = "A line that draws on from its centre.",
            defaultDuration = 4.0, boxW = 50.0, boxH = 0.8, usesBox = true
        ),
        MgTemplate(
            id = "shape_arrow", title = "Arrow", category = "Shapes & frames",
            description = "An arrow that grows from tail to head. Rotate it to aim.",
            defaultDuration = 4.0, boxW = 28.0, boxH = 10.0, usesBox = true
        )
    )

    val categories: List<String> get() = templates.map { it.category }.distinct()

    fun find(id: String): MgTemplate? = templates.find { it.id == id }

    /**
     * Builds the clip for a template. Templates marked [MgTemplate.fullLength] cover the whole
     * edit from zero; the rest start at [atSec].
     */
    fun createClip(template: MgTemplate, atSec: Double, timelineDurationSec: Double): TimelineClip {
        val full = template.fullLength && timelineDurationSec > 0.5
        return TimelineClip(
            id = generateUid(),
            assetId = null,
            track = template.track,
            startAt = if (full) 0.0 else max(0.0, atSec),
            trimIn = 0.0,
            trimOut = if (full) timelineDurationSec else template.defaultDuration,
            text = template.defaultText,
            fontSize = template.fontSize,
            fontWeight = 800,
            textColor = template.textColor,
            textAlign = template.textAlign,
            verticalPosition = template.verticalPosition,
            transitionDuration = 0.5,
            label = template.title,
            graphic = template.id,
            accentColor = template.accentColor,
            boxW = template.boxW,
            boxH = template.boxH
        )
    }
}

/**
 * Timing maths for motion graphics, kept apart from the drawing code so it can be unit-tested.
 * All progress values are 0..1.
 */
object MgMath {

    fun clamp01(t: Double): Double = t.coerceIn(0.0, 1.0)

    fun easeOutCubic(t: Double): Double = 1.0 - (1.0 - clamp01(t)).pow(3)

    fun easeInOutCubic(t: Double): Double {
        val u = clamp01(t)
        return if (u < 0.5) 4.0 * u * u * u else 1.0 - (-2.0 * u + 2.0).pow(3) / 2.0
    }

    /** Overshoots slightly past 1 before settling — the "pop" of buttons and badges. */
    fun easeOutBack(t: Double): Double {
        val u = clamp01(t)
        val c1 = 1.70158
        val c3 = c1 + 1.0
        return 1.0 + c3 * (u - 1.0).pow(3) + c1 * (u - 1.0).pow(2)
    }

    /**
     * Intro and outro never overlap: on a clip shorter than both together, each gets its share of
     * the clip, so the graphic still fully appears before it leaves.
     */
    fun fitSpans(duration: Double, introSec: Double, outroSec: Double): Pair<Double, Double> {
        val d = max(0.0, duration)
        val i = max(0.0, introSec)
        val o = max(0.0, outroSec)
        if (i + o <= d || i + o == 0.0) return i to o
        val k = d / (i + o)
        return i * k to o * k
    }

    /** Linear 0→1 across the intro. */
    fun introProgress(localT: Double, duration: Double, introSec: Double, outroSec: Double = introSec): Double {
        val (i, _) = fitSpans(duration, introSec, outroSec)
        return if (i <= 0.0) 1.0 else clamp01(localT / i)
    }

    /** Linear 0→1 across the outro (0 until the outro begins). */
    fun outroProgress(localT: Double, duration: Double, introSec: Double, outroSec: Double = introSec): Double {
        val (_, o) = fitSpans(duration, introSec, outroSec)
        return if (o <= 0.0) 0.0 else clamp01((localT - (duration - o)) / o)
    }

    /** How "present" the graphic is: rises through the intro, holds at 1, falls through the outro. */
    fun presence(localT: Double, duration: Double, introSec: Double, outroSec: Double = introSec): Double =
        min(
            introProgress(localT, duration, introSec, outroSec),
            1.0 - outroProgress(localT, duration, introSec, outroSec)
        )

    /**
     * Progress of element [index] of [count] when elements start one after another: each begins
     * [overlap] of an element's length before the previous one ends.
     */
    fun stagger(progress: Double, index: Int, count: Int, overlap: Double = 0.5): Double {
        if (count <= 1) return clamp01(progress)
        val step = 1.0 - overlap.coerceIn(0.0, 0.95)
        val total = 1.0 + step * (count - 1)
        return clamp01(progress * total - step * index)
    }

    /**
     * Characters visible in a typewriter line. Typing uses the first [typingShare] of the clip so
     * the finished line can be read before the clip ends.
     */
    fun typewriterCount(length: Int, localT: Double, duration: Double, typingShare: Double = 0.7): Int {
        if (length <= 0 || duration <= 0.0) return 0
        val typing = max(0.05, duration * typingShare.coerceIn(0.1, 1.0))
        return floor(clamp01(localT / typing) * length + 1e-9).toInt().coerceIn(0, length)
    }

    /** The cursor blinks twice a second. */
    fun cursorVisible(localT: Double): Boolean = (floor(localT * 4.0).toInt() % 2) == 0

    /** The number shown by a countdown: a 3 s clip shows 3, 2, 1. Never below 1. */
    fun countdownValue(localT: Double, duration: Double): Int =
        max(1, ceil(duration - localT.coerceIn(0.0, max(0.0, duration)) - 1e-9).toInt())

    /** How far through the current second a countdown is — drives the ring sweep. */
    fun countdownSweep(localT: Double): Double {
        val t = max(0.0, localT)
        return t - floor(t)
    }

    fun progressFraction(localT: Double, duration: Double): Double =
        if (duration <= 0.0) 1.0 else clamp01(localT / duration)

    /** Value shown by a counter heading for [target]; eases out so it lands softly. */
    fun counterValue(target: Double, localT: Double, duration: Double, countShare: Double = 0.75): Double {
        val span = max(0.05, duration * countShare.coerceIn(0.1, 1.0))
        return target * easeOutCubic(localT / span)
    }

    /** Leading number of a line, tolerant of thousands separators ("1,250 km" → 1250). */
    fun parseTarget(line: String): Double? {
        val match = Regex("-?\\d[\\d,]*(\\.\\d+)?").find(line) ?: return null
        return match.value.replace(",", "").toDoubleOrNull()
    }

    /** Formats a counter value like its target: no decimals unless the target has them. */
    fun formatCounter(value: Double, targetLine: String): String {
        val match = Regex("-?\\d[\\d,]*(\\.\\d+)?").find(targetLine) ?: return targetLine
        val decimals = match.groupValues.getOrNull(1)?.let { max(0, it.length - 1) } ?: 0
        val grouped = match.value.contains(',')
        val number = if (decimals > 0) {
            "%.${decimals}f".format(java.util.Locale.US, value)
        } else {
            val whole = value.roundToInt().toLong()
            if (grouped) "%,d".format(java.util.Locale.US, whole) else whole.toString()
        }
        return targetLine.replaceRange(match.range, number)
    }

    /** "m:ss.t" elapsed time for the running timer. */
    fun timerText(localT: Double): String {
        val t = max(0.0, localT)
        val minutes = floor(t / 60.0).toInt()
        val seconds = floor(t % 60.0).toInt()
        val tenths = floor((t % 1.0) * 10.0).toInt()
        return "%d:%02d.%d".format(java.util.Locale.US, minutes, seconds, tenths)
    }

    /**
     * Vertical offset of a credits block: it starts just below the frame and ends just above it,
     * moving at a constant speed for the whole clip.
     */
    fun creditsOffset(localT: Double, duration: Double, contentHeight: Double, frameHeight: Double): Double {
        val p = progressFraction(localT, duration)
        return frameHeight - p * (frameHeight + contentHeight)
    }
}
