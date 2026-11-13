package com.editpro.studio.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class AssetKind(val id: String, val label: String) {
    VIDEO("video", "▶ VIDEO"),
    AUDIO("audio", "♫ AUDIO"),
    IMAGE("image", "IMAGE"),
    COLOR("color", "SOLID");

    companion object {
        fun fromId(id: String): AssetKind = entries.find { it.id.equals(id, ignoreCase = true) } ?: VIDEO
    }
}

enum class TimelineTrack(
    val key: String,
    val label: String,
    val isVisual: Boolean,
    val isAudio: Boolean,
    /** Title-like tracks hold text and motion-graphics clips (no media asset behind them). */
    val isTitle: Boolean = false
) {
    VIDEO("video", "VIDEO 1", isVisual = true, isAudio = false),
    VIDEO2("video2", "VIDEO 2", isVisual = true, isAudio = false),
    OVERLAY("overlay", "OVERLAY", isVisual = true, isAudio = false),
    // Drawn above every visual track and below TEXT, so a lower third or a letterbox can run
    // underneath subtitles for the whole edit.
    GRAPHICS("graphics", "GRAPHICS", isVisual = false, isAudio = false, isTitle = true),
    TEXT("text", "TEXT & TITLES", isVisual = false, isAudio = false, isTitle = true),
    AUDIO("audio", "AUDIO 1", isVisual = false, isAudio = true),
    AUDIO2("audio2", "AUDIO 2", isVisual = false, isAudio = true);

    companion object {
        fun fromKey(key: String): TimelineTrack =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: VIDEO
    }
}

enum class WorkspaceMode(val id: String, val label: String, val shortcutHint: String, val description: String) {
    EDIT("edit", "Normal", "Alt+1", "Normal Editing: Cut, Trim, Assemble & Text"),
    COLOR("color", "Colour", "Alt+2", "Colour Grading: 3-Way Wheels, LUTs & Live RGB Scopes"),
    MOTION("motion", "Motion", "Alt+3", "Extra Motion: Bezier Curves, 3D Rigs & Camera Optics"),
    MUSIC("music", "Music", "Alt+4", "Music Editing: 5-Band EQ, Mastering, Ducking & Beat Grid");

    fun defaultInspectorTab(): InspectorTab = when (this) {
        EDIT -> InspectorTab.CLIP
        COLOR -> InspectorTab.FX
        MOTION -> InspectorTab.MOTION
        MUSIC -> InspectorTab.AUDIO
    }
}

enum class InspectorTab(val id: String, val label: String) {
    CLIP("clip", "Clip"),
    LAYOUT("layout", "Layout"),
    MOTION("motion", "Motion"),
    FX("fx", "Effects"),
    AUDIO("audio", "Audio")
}

enum class ScopesMode(val id: String, val label: String) {
    PARADE("parade", "Parade"),
    WAVEFORM("waveform", "Luma"),
    HISTOGRAM("hist", "Hist")
}

data class TrackState(
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Collapsed tracks shrink to a thin strip in the timeline; they still play and export. */
    val collapsed: Boolean = false
)

data class MediaAsset(
    val id: String = generateUid(),
    val name: String,
    val mimeType: String = "",
    val kind: AssetKind,
    val sizeBytes: Long = 0L,
    val durationSec: Double = 5.0,
    val durationGuess: Boolean = false,
    val uriString: String? = null,
    val localPath: String? = null,
    val colorHex: String? = null,
    val bin: String = if (kind == AssetKind.AUDIO) "Music" else "Imported",
    val favorite: Boolean = false,
    val waveformPeaks: List<Float> = emptyList(),
    val filmstripPaths: List<String> = emptyList()
)

data class ClipFilters(
    val brightness: Double = 100.0,
    val contrast: Double = 100.0,
    val saturation: Double = 100.0,
    val blur: Double = 0.0,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val sharpen: Double = 0.0,
    val vignette: Double = 0.0,
    val hue: Double = 0.0,
    val sepia: Double = 0.0,
    val invert: Double = 0.0,
    val grain: Double = 0.0,
    // 3-Way Color Wheels: Lift (Shadows), Gamma (Midtones), Gain (Highlights)
    val liftR: Double = 0.0,
    val liftG: Double = 0.0,
    val liftB: Double = 0.0,
    val liftY: Double = 0.0,
    val gammaR: Double = 0.0,
    val gammaG: Double = 0.0,
    val gammaB: Double = 0.0,
    val gammaY: Double = 0.0,
    val gainR: Double = 0.0,
    val gainG: Double = 0.0,
    val gainB: Double = 0.0,
    val gainY: Double = 0.0
)

data class CameraRig(
    val tiltX: Double = 0.0,
    val swivelY: Double = 0.0,
    val shutter: Double = 180.0,
    val aberration: Double = 0.0,
    val anamorphic: Double = 0.0
)

data class Keyframe(
    val time: Double,
    val scale: Double = 100.0,
    val opacity: Double = 100.0,
    val rotation: Double = 0.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val filters: ClipFilters = ClipFilters(),
    /**
     * Easing of the segment that starts at this keyframe: "linear", "ease", "in", "out" or
     * "hold". Empty means "use the clip's bezier velocity curve", which is what every keyframe
     * did before per-keyframe easing existed.
     */
    val easing: String = ""
)

data class VolumePoint(
    val time: Double,
    val value: Double = 100.0
)

data class TimelineClip(
    val id: String = generateUid(),
    val assetId: String? = null,
    val track: TimelineTrack = TimelineTrack.VIDEO,
    val startAt: Double = 0.0,
    val trimIn: Double = 0.0,
    val trimOut: Double = 5.0,
    val filters: ClipFilters = ClipFilters(),
    val rig: CameraRig = CameraRig(),
    val volume: Double = 100.0,
    val text: String = "",
    val fontSize: Int = 42,
    val fontFamily: String = "system-ui",
    val fontWeight: Int = 800,
    val textColor: String = "#ffffff",
    val textAlign: String = "center",
    val verticalPosition: String = "center",
    val strokeColor: String = "#000000",
    val strokeWidth: Float = 0f,
    val shadow: Boolean = true,
    val opacity: Double = 100.0,
    val scale: Double = 100.0,
    val rotation: Double = 0.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val fadeOut: Double = 0.0,
    val fadeInAudio: Double = 0.0,
    val fadeOutAudio: Double = 0.0,
    val transition: String = "none",
    val transitionOut: String = "none",
    val transitionDuration: Double = 0.45,
    val speed: Double = 1.0,
    val ease: String = "ease",
    val bezier: List<Double> = listOf(0.42, 0.0, 0.58, 1.0),
    val curveName: String = "Cubic Smooth",
    val equipmentName: String = "",
    val audioPresetName: String = "",
    val blendMode: String = "normal",
    val cornerRadius: Int = 0,
    val audioEq: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0), // 60Hz, 250Hz, 1kHz, 4kHz, 12kHz
    val audioPan: Double = 0.0,
    val audioComp: Double = 0.0,
    val audioDeess: Double = 0.0,
    val audioReverb: Double = 0.0,
    val keyframes: List<Keyframe> = emptyList(),
    val volumeKeyframes: List<VolumePoint> = emptyList(),
    val label: String = "Clip",
    /** Motion-graphics template id (see [MotionGraphics]); empty for ordinary clips. */
    val graphic: String = "",
    /** Secondary colour used by motion graphics for plates, bars and accents. */
    val accentColor: String = "#0a84ff",
    /** Width / height of a shape graphic, in percent of the frame. */
    val boxW: Double = 40.0,
    val boxH: Double = 40.0
) {
    /** True for a motion-graphics clip, which is drawn procedurally instead of from text or media. */
    val isGraphic: Boolean get() = graphic.isNotEmpty()
}

data class ResolvedClip(
    val clip: TimelineClip,
    val start: Double,
    val duration: Double
) {
    val end: Double get() = start + duration
}

data class AnimatedProperties(
    val scale: Double,
    val opacity: Double,
    val rotation: Double,
    val x: Double,
    val y: Double,
    val filters: ClipFilters
)

data class TimelineMarker(
    val time: Double,
    val label: String
)

data class ProjectSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30
)

data class ExportSettings(
    val engine: String = "turbo", // "turbo" (Native MediaCodec Hardware) or "software"
    val aspect: String = "landscape", // landscape (16:9), portrait (9:16), square (1:1), classic (4:3)
    val resolution: String = "1280x720", // 1280x720, 1920x1080, 854x480
    val fps: Int = 30,
    val crf: Int = 23,
    val audioDucking: Boolean = true,
    val androidLowRamGuard: Boolean = true,
    val rangeInSec: Double? = null,
    val rangeOutSec: Double? = null
) {
    fun resolvedDimensions(deviceRamGb: Int = 8): Pair<Int, Int> {
        val parts = resolution.split("x")
        var w = parts.getOrNull(0)?.toIntOrNull() ?: 1280
        var h = parts.getOrNull(1)?.toIntOrNull() ?: 720
        when (aspect) {
            "portrait" -> if (w > h) { val tmp = w; w = h; h = tmp }
            "square" -> { val side = min(w, h); w = side; h = side }
            "classic" -> { h = (w * 3.0 / 4.0).roundToInt() }
        }
        if (androidLowRamGuard && deviceRamGb <= 4 && w > 1280) {
            val ratio = 1280.0 / w
            w = 1280
            h = ((h * ratio / 2.0).roundToInt() * 2)
        }
        // Ensure even dimensions for H.264 YUV420P hardware encoder compatibility
        w = max(16, (w / 2) * 2)
        h = max(16, (h / 2) * 2)
        return w to h
    }
}

data class RenderJob(
    val id: String = generateUid(),
    val name: String,
    val clips: List<TimelineClip>,
    val markers: List<TimelineMarker>,
    val settings: ExportSettings,
    val range: Pair<Double, Double>? = null,
    val queueIndex: Int? = null
)

data class EditorSnapshot(
    val clips: List<TimelineClip>,
    val markers: List<TimelineMarker>,
    val playhead: Double,
    val selectedClipId: String?,
    val projectName: String,
    val duration: Double,
    val settings: ProjectSettings,
    val assets: List<MediaAsset> = emptyList(),
    val selectedClipIds: Set<String> = emptySet()
)

fun generateUid(): String = UUID.randomUUID().toString().replace("-", "").take(16)

fun formatTimecode(seconds: Double): String {
    val t = max(0.0, if (seconds.isFinite()) seconds else 0.0)
    val m = floor(t / 60.0).toInt()
    val s = floor(t % 60.0).toInt()
    val cs = floor((t % 1.0) * 100.0).toInt()
    return "%02d:%02d.%02d".format(m, s, cs)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    return if (bytes < 1048576L) {
        "${(bytes / 1024.0).roundToInt()} KB"
    } else {
        "%.1f MB".format(bytes / 1048576.0)
    }
}

object TimelineEngine {

    fun clipDuration(clip: TimelineClip, assetsById: Map<String, MediaAsset>): Double {
        val assetDur = clip.assetId?.let { assetsById[it]?.durationSec } ?: 5.0
        val outPoint = if (clip.trimOut > 0.0) clip.trimOut else assetDur
        val raw = outPoint - clip.trimIn
        return max(0.1, raw / max(0.25, min(4.0, clip.speed)))
    }

    fun sourceSpanDuration(clip: TimelineClip, assetsById: Map<String, MediaAsset>): Double {
        val assetDur = clip.assetId?.let { assetsById[it]?.durationSec } ?: 5.0
        val outPoint = if (clip.trimOut > 0.0) clip.trimOut else assetDur
        return max(0.1, outPoint - clip.trimIn)
    }

    fun starts(clips: List<TimelineClip>, assetsById: Map<String, MediaAsset>): List<ResolvedClip> {
        var cursor = 0.0
        return clips.map { c ->
            // Speed-aware: a 2x clip occupies half its source span. The exporter and the audio
            // mixer already read the source at `speed`, so using the raw span here made a
            // retimed clip run past its out-point.
            val dur = clipDuration(c, assetsById)
            val st = if (c.startAt.isFinite()) max(0.0, c.startAt) else cursor
            cursor = max(cursor, st + dur)
            ResolvedClip(clip = c, start = st, duration = dur)
        }.sortedBy { it.start }
    }

    fun calcProjectDuration(clips: List<TimelineClip>, assetsById: Map<String, MediaAsset>): Double {
        return starts(clips, assetsById).maxOfOrNull { it.end } ?: 0.0
    }

    /**
     * Whether a clip is on screen at [timeSec]. A clip owns its first frame but not its last
     * instant, so two clips that meet at a cut are never both drawn; the one exception is the
     * very end of the project, where the final clip stays visible instead of going black.
     */
    fun isActiveAt(rc: ResolvedClip, timeSec: Double, projectEnd: Double): Boolean {
        val eps = 1e-6
        if (timeSec < rc.start - eps) return false
        if (timeSec < rc.end - eps) return true
        return timeSec <= rc.end + eps && rc.end >= projectEnd - eps
    }

    /**
     * Tracks whose sound should be heard. Mute and hide silence a track; when any track is
     * soloed, only soloed tracks play. Picture tracks count too, because a video clip carries
     * its own sound.
     */
    fun audibleTracks(trackStates: Map<TimelineTrack, TrackState>): Set<TimelineTrack> {
        val candidates = TimelineTrack.entries.filter { it.isAudio || it.isVisual }
        val anySolo = candidates.any { trackStates[it]?.solo == true }
        return candidates.filter { track ->
            val ts = trackStates[track] ?: TrackState()
            !ts.muted && !ts.hidden && (!anySolo || ts.solo)
        }.toSet()
    }

    /** True when the clip can produce sound: an audio file, or a video (which may have a soundtrack). */
    fun carriesSound(clip: TimelineClip, asset: MediaAsset?): Boolean =
        asset != null && (clip.track.isAudio || clip.track.isVisual) &&
            (asset.kind == AssetKind.AUDIO || asset.kind == AssetKind.VIDEO)

    /**
     * Exact native port of EditPro's `resolveTimelineStart`:
     * 1. Magnetic snapping to neighboring clip edges on the same track and playhead within 11px threshold.
     * 2. Multi-pass non-overlapping collision resolution in the direction of travel.
     */
    fun resolveTimelineStart(
        desired: Double,
        duration: Double,
        track: TimelineTrack,
        clipId: String,
        scalePxPerSec: Double,
        direction: Double,
        allClips: List<TimelineClip>,
        assetsById: Map<String, MediaAsset>,
        playhead: Double,
        magneticSnap: Boolean,
        /** Further snap targets, e.g. markers and clip edges on other tracks. */
        extraSnapTimes: List<Double> = emptyList()
    ): Double {
        var s = max(0.0, desired)
        val others = starts(allClips, assetsById)
            .filter { it.clip.id != clipId && it.clip.track == track }
            .sortedBy { it.start }

        if (magneticSnap && scalePxPerSec > 0.0) {
            data class SnapCandidate(val start: Double, val distPx: Double)
            val candidates = mutableListOf<SnapCandidate>()
            for (q in others) {
                candidates.add(SnapCandidate(q.start - duration, abs((s + duration - q.start) * scalePxPerSec)))
                candidates.add(SnapCandidate(q.end, abs((s - q.end) * scalePxPerSec)))
            }
            candidates.add(SnapCandidate(playhead, abs((s - playhead) * scalePxPerSec)))
            // Either edge of the moving clip may land on an extra target.
            for (t in extraSnapTimes) {
                candidates.add(SnapCandidate(t, abs((s - t) * scalePxPerSec)))
                candidates.add(SnapCandidate(t - duration, abs((s + duration - t) * scalePxPerSec)))
            }
            val best = candidates.filter { it.distPx <= 11.0 }.minByOrNull { it.distPx }
            if (best != null) {
                s = max(0.0, best.start)
            }
        }

        // Clips on the same track may touch, but must never overlap.
        for (pass in 0 until (others.size + 2)) {
            val hit = others.find { q ->
                s < q.end - 0.001 && (s + duration) > q.start + 0.001
            } ?: break
            s = when {
                direction > 0 -> hit.end
                direction < 0 -> max(0.0, hit.start - duration)
                else -> {
                    val leftCandidate = max(0.0, hit.start - duration)
                    val rightCandidate = hit.end
                    if (abs(s - leftCandidate) <= abs(s - rightCandidate)) leftCandidate else rightCandidate
                }
            }
        }
        return max(0.0, s)
    }

    /**
     * Exact Newton-Raphson solver for cubic-bezier(x1, y1, x2, y2) velocity curves.
     */
    fun evalCubicBezier(t: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        var s = u
        for (i in 0 until 5) {
            val inv = 1.0 - s
            val x = 3.0 * inv * inv * s * x1 + 3.0 * inv * s * s * x2 + s * s * s - u
            val dx = 3.0 * inv * inv * x1 + 6.0 * inv * s * (x2 - x1) + 3.0 * s * s * (1.0 - x2)
            if (abs(dx) < 1e-4) break
            s = (s - x / dx).coerceIn(0.0, 1.0)
        }
        val inv = 1.0 - s
        return 3.0 * inv * inv * s * y1 + 3.0 * inv * s * s * y2 + s * s * s
    }

    /** Named easing curves for a normalised progress value; unknown names are linear. */
    fun applyEasing(name: String, u: Double): Double = when (name) {
        "ease" -> u * u * (3.0 - 2.0 * u)
        "in" -> u * u
        "out" -> 1.0 - (1.0 - u) * (1.0 - u)
        "hold" -> 0.0
        else -> u
    }

    /**
     * Evaluates keyframe-interpolated transform and filter properties at clip-local time `localTimeSec`.
     */
    fun animatedProps(clip: TimelineClip, localTimeSec: Double): AnimatedProperties {
        val base = AnimatedProperties(
            scale = clip.scale,
            opacity = clip.opacity,
            rotation = clip.rotation,
            x = clip.x,
            y = clip.y,
            filters = clip.filters
        )
        val ks = clip.keyframes.sortedBy { it.time }
        if (ks.isEmpty()) return base

        fun toProps(k: Keyframe) = AnimatedProperties(
            scale = k.scale,
            opacity = k.opacity,
            rotation = k.rotation,
            x = k.x,
            y = k.y,
            filters = k.filters
        )

        var left: Keyframe? = null
        var right: Keyframe? = null
        for (k in ks) {
            if (k.time <= localTimeSec) {
                left = k
            } else {
                right = k
                break
            }
        }
        if (left == null) return toProps(right!!)
        if (right == null) return toProps(left)

        var u = ((localTimeSec - left.time) / max(0.001, right.time - left.time)).coerceIn(0.0, 1.0)
        // The keyframe that starts the segment decides how it eases; without its own easing it
        // falls back to the clip-wide curve.
        val segmentEase = left.easing.ifEmpty { clip.ease }
        u = if (left.easing.isEmpty() && clip.bezier.size == 4 && clip.ease == "ease") {
            evalCubicBezier(u, clip.bezier[0], clip.bezier[1], clip.bezier[2], clip.bezier[3])
        } else {
            applyEasing(segmentEase, u)
        }

        fun lerp(a: Double, b: Double): Double = a + (b - a) * u
        val lf = left.filters
        val rf = right.filters

        return AnimatedProperties(
            scale = lerp(left.scale, right.scale),
            opacity = lerp(left.opacity, right.opacity),
            rotation = lerp(left.rotation, right.rotation),
            x = lerp(left.x, right.x),
            y = lerp(left.y, right.y),
            filters = ClipFilters(
                brightness = lerp(lf.brightness, rf.brightness),
                contrast = lerp(lf.contrast, rf.contrast),
                saturation = lerp(lf.saturation, rf.saturation),
                blur = lerp(lf.blur, rf.blur),
                temperature = lerp(lf.temperature, rf.temperature),
                tint = lerp(lf.tint, rf.tint),
                sharpen = lerp(lf.sharpen, rf.sharpen),
                vignette = lerp(lf.vignette, rf.vignette),
                hue = lerp(lf.hue, rf.hue),
                sepia = lerp(lf.sepia, rf.sepia),
                invert = lerp(lf.invert, rf.invert),
                grain = lerp(lf.grain, rf.grain),
                liftR = lerp(lf.liftR, rf.liftR),
                liftG = lerp(lf.liftG, rf.liftG),
                liftB = lerp(lf.liftB, rf.liftB),
                liftY = lerp(lf.liftY, rf.liftY),
                gammaR = lerp(lf.gammaR, rf.gammaR),
                gammaG = lerp(lf.gammaG, rf.gammaG),
                gammaB = lerp(lf.gammaB, rf.gammaB),
                gammaY = lerp(lf.gammaY, rf.gammaY),
                gainR = lerp(lf.gainR, rf.gainR),
                gainG = lerp(lf.gainG, rf.gainG),
                gainB = lerp(lf.gainB, rf.gainB),
                gainY = lerp(lf.gainY, rf.gainY)
            )
        )
    }

    /**
     * Computes piecewise-linear volume automation gain [0.0 .. 2.0] at `localTimeSec`.
     */
    fun automationGain(clip: TimelineClip, localTimeSec: Double): Double {
        val stored = clip.volumeKeyframes
        if (stored.isEmpty()) return 1.0
        // The export asks for this once per audio sample, so the points are only sorted (which
        // copies the list) when they are not already in order.
        var inOrder = true
        for (i in 1 until stored.size) {
            if (stored[i].time < stored[i - 1].time) {
                inOrder = false
                break
            }
        }
        val pts = if (inOrder) stored else stored.sortedBy { it.time }
        val t = max(0.0, localTimeSec)
        if (t <= pts.first().time) return (pts.first().value / 100.0).coerceIn(0.0, 2.0)
        if (t >= pts.last().time) return (pts.last().value / 100.0).coerceIn(0.0, 2.0)
        for (i in 1 until pts.size) {
            if (t <= pts[i].time) {
                val a = pts[i - 1]
                val b = pts[i]
                val p = (t - a.time) / max(0.001, b.time - a.time)
                val v = a.value + (b.value - a.value) * p
                return (v / 100.0).coerceIn(0.0, 2.0)
            }
        }
        return 1.0
    }

    /**
     * Computes combined audio fade-in, fade-out, and automation envelope multiplier.
     */
    fun audioEnvelope(clip: TimelineClip, localTimeSec: Double, clipDurationSec: Double): Double {
        val d = max(0.1, clipDurationSec)
        val fi = max(0.0, clip.fadeInAudio)
        val fo = max(0.0, clip.fadeOutAudio)
        var g = 1.0
        if (fi > 0.0) g *= (localTimeSec / fi).coerceIn(0.0, 1.0)
        if (fo > 0.0) g *= ((d - localTimeSec) / fo).coerceIn(0.0, 1.0)
        return g * automationGain(clip, localTimeSec)
    }

    /**
     * Parses SubRip (.srt) subtitle content into TimelineClip items on the TEXT track.
     */
    fun parseSrtToClips(srtContent: String): List<TimelineClip> {
        val blocks = srtContent.replace("\r", "").trim().split(Regex("\\n\\s*\\n"))
        val result = mutableListOf<TimelineClip>()
        val timeRegex = Regex("(?:(\\d+):)?(\\d{2}):(\\d{2})[,.](\\d{3})")

        fun parseTime(raw: String): Double {
            val match = timeRegex.find(raw) ?: return 0.0
            val hours = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val mins = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val secs = match.groupValues[3].toDoubleOrNull() ?: 0.0
            val millis = match.groupValues[4].toDoubleOrNull() ?: 0.0
            return hours * 3600.0 + mins * 60.0 + secs + millis / 1000.0
        }

        var count = 0
        for (block in blocks) {
            val lines = block.split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val pair = lines[ti].split("-->").map { it.trim() }
            if (pair.size < 2) continue
            val start = parseTime(pair[0])
            val end = parseTime(pair[1])
            val text = lines.drop(ti + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (text.isEmpty() || end <= start) continue
            count++
            result.add(
                TimelineClip(
                    id = generateUid(),
                    assetId = null,
                    track = TimelineTrack.TEXT,
                    startAt = start,
                    trimIn = 0.0,
                    trimOut = end - start,
                    text = text,
                    fontSize = 30,
                    fontFamily = "system-ui",
                    fontWeight = 700,
                    textColor = "#ffffff",
                    textAlign = "center",
                    verticalPosition = "bottom",
                    strokeColor = "#000000",
                    strokeWidth = 1f,
                    shadow = true,
                    label = "Subtitle $count"
                )
            )
        }
        return result
    }
}
