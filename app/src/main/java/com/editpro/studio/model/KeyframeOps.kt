package com.editpro.studio.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** A partial transform edit: only the non-null values are written. */
data class KeyframePatch(
    val scale: Double? = null,
    val opacity: Double? = null,
    val rotation: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
    val easing: String? = null
) {
    val isEmpty: Boolean
        get() = scale == null && opacity == null && rotation == null && x == null && y == null && easing == null
}

/**
 * Pure keyframe editing. Times are clip-local seconds (0 = the clip's first frame).
 *
 * Everything here returns a new clip and touches nothing else, so the view model, the assistant
 * tools and the unit tests all share one implementation.
 */
object KeyframeOps {

    /** Two keyframes closer than this are treated as the same keyframe. */
    const val MERGE_TOLERANCE = 0.05

    val EASINGS: List<Pair<String, String>> = listOf(
        "" to "Clip curve",
        "linear" to "Linear",
        "ease" to "Ease",
        "in" to "Ease in",
        "out" to "Ease out",
        "hold" to "Hold"
    )

    private fun roundTime(t: Double): Double = (t * 1000.0).roundToInt() / 1000.0

    private fun sorted(clip: TimelineClip): List<Keyframe> = clip.keyframes.sortedBy { it.time }

    /** Index (in time order) of the keyframe at [localT], or -1. */
    fun indexAt(clip: TimelineClip, localT: Double, tolerance: Double = MERGE_TOLERANCE): Int {
        val ks = sorted(clip)
        var best = -1
        var bestDist = Double.MAX_VALUE
        ks.forEachIndexed { i, k ->
            val d = abs(k.time - localT)
            if (d <= tolerance && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    /**
     * Writes [patch] into the keyframe at [localT]. When there is none, one is created from the
     * interpolated values at that time first, so adding a keyframe never makes the picture jump.
     */
    fun upsert(clip: TimelineClip, localT: Double, patch: KeyframePatch = KeyframePatch()): TimelineClip {
        val t = roundTime(localT.coerceAtLeast(0.0))
        val ks = sorted(clip)
        val idx = indexAt(clip, t)
        val base = if (idx >= 0) {
            ks[idx]
        } else {
            val a = TimelineEngine.animatedProps(clip, t)
            Keyframe(
                time = t,
                scale = a.scale,
                opacity = a.opacity,
                rotation = a.rotation,
                x = a.x,
                y = a.y,
                filters = a.filters,
                // A keyframe dropped into an eased segment keeps that segment's easing.
                easing = ks.lastOrNull { it.time <= t }?.easing ?: ""
            )
        }
        val patched = base.copy(
            scale = patch.scale ?: base.scale,
            opacity = patch.opacity ?: base.opacity,
            rotation = patch.rotation ?: base.rotation,
            x = patch.x ?: base.x,
            y = patch.y ?: base.y,
            easing = patch.easing ?: base.easing
        )
        val rest = if (idx >= 0) ks.filterIndexed { i, _ -> i != idx } else ks
        return clip.copy(keyframes = (rest + patched).sortedBy { it.time })
    }

    /** Removes the keyframe at [index] (time order). */
    fun remove(clip: TimelineClip, index: Int): TimelineClip {
        val ks = sorted(clip)
        if (index !in ks.indices) return clip
        return clip.copy(keyframes = ks.filterIndexed { i, _ -> i != index })
    }

    /**
     * Retimes one keyframe. The new time is clamped to the clip, and a keyframe it lands on is
     * replaced rather than left as a duplicate at the same instant.
     */
    fun move(clip: TimelineClip, index: Int, newTime: Double, clipDuration: Double): TimelineClip {
        val ks = sorted(clip)
        if (index !in ks.indices) return clip
        val t = roundTime(newTime.coerceIn(0.0, clipDuration.coerceAtLeast(0.0)))
        val moved = ks[index].copy(time = t)
        val others = ks.filterIndexed { i, k -> i != index && abs(k.time - t) > MERGE_TOLERANCE / 2.0 }
        return clip.copy(keyframes = (others + moved).sortedBy { it.time })
    }

    fun setEasing(clip: TimelineClip, index: Int, easing: String): TimelineClip {
        val ks = sorted(clip)
        if (index !in ks.indices) return clip
        return clip.copy(keyframes = ks.mapIndexed { i, k -> if (i == index) k.copy(easing = easing) else k })
    }

    /** Time of the nearest keyframe before [localT], or null. */
    fun prevTime(clip: TimelineClip, localT: Double): Double? =
        sorted(clip).lastOrNull { it.time < localT - 0.01 }?.time

    /** Time of the nearest keyframe after [localT], or null. */
    fun nextTime(clip: TimelineClip, localT: Double): Double? =
        sorted(clip).firstOrNull { it.time > localT + 0.01 }?.time

    /**
     * Keyframes for the part of the clip that starts [offset] seconds in: earlier keyframes are
     * dropped, later ones move left, and the interpolated value at the cut becomes the first
     * keyframe so the animation carries on exactly where it was.
     */
    fun tailFrom(clip: TimelineClip, offset: Double): List<Keyframe> {
        if (clip.keyframes.isEmpty()) return emptyList()
        val ks = sorted(clip)
        val boundary = boundaryAt(clip, offset).copy(time = 0.0)
        val later = ks.filter { it.time > offset + 0.001 }.map { it.copy(time = roundTime(it.time - offset)) }
        return listOf(boundary) + later
    }

    /** Keyframes for the part of the clip that ends [offset] seconds in (see [tailFrom]). */
    fun headUntil(clip: TimelineClip, offset: Double): List<Keyframe> {
        if (clip.keyframes.isEmpty()) return emptyList()
        val ks = sorted(clip)
        val earlier = ks.filter { it.time < offset - 0.001 }
        return earlier + boundaryAt(clip, offset)
    }

    private fun boundaryAt(clip: TimelineClip, offset: Double): Keyframe {
        val a = TimelineEngine.animatedProps(clip, offset)
        return Keyframe(
            time = roundTime(offset),
            scale = a.scale,
            opacity = a.opacity,
            rotation = a.rotation,
            x = a.x,
            y = a.y,
            filters = a.filters,
            easing = sorted(clip).lastOrNull { it.time <= offset }?.easing ?: ""
        )
    }

    /** Volume automation for the tail / head of a clip cut [offset] seconds in. */
    fun volumeTailFrom(clip: TimelineClip, offset: Double): List<VolumePoint> {
        if (clip.volumeKeyframes.isEmpty()) return emptyList()
        val boundary = VolumePoint(0.0, TimelineEngine.automationGain(clip, offset) * 100.0)
        val later = clip.volumeKeyframes.sortedBy { it.time }
            .filter { it.time > offset + 0.001 }
            .map { it.copy(time = roundTime(it.time - offset)) }
        return listOf(boundary) + later
    }

    fun volumeHeadUntil(clip: TimelineClip, offset: Double): List<VolumePoint> {
        if (clip.volumeKeyframes.isEmpty()) return emptyList()
        val earlier = clip.volumeKeyframes.sortedBy { it.time }.filter { it.time < offset - 0.001 }
        return earlier + VolumePoint(roundTime(offset), TimelineEngine.automationGain(clip, offset) * 100.0)
    }

    /**
     * Makes a plain property edit behave sensibly on an animated clip.
     *
     * Once a clip has keyframes the renderer reads the keyframes, not the clip's base values, so
     * moving a slider used to change nothing on screen. This compares the clip before and after
     * an edit and routes the difference into the animation:
     *  - a changed transform value is written to the keyframe at [localT] (auto-key);
     *  - a changed grade replaces the grade on every keyframe when the grade was static, or is
     *    keyed at [localT] when the grade itself was animated.
     *
     * Edits that replaced the keyframe list themselves (presets, clearing) pass through untouched.
     */
    fun reconcileEdit(old: TimelineClip, new: TimelineClip, localT: Double): TimelineClip {
        if (new.keyframes.isEmpty() || new.keyframes != old.keyframes) return new
        var out = new
        if (new.filters != old.filters) {
            val gradeWasStatic = old.keyframes.all { it.filters == old.filters }
            out = if (gradeWasStatic) {
                out.copy(keyframes = out.keyframes.map { it.copy(filters = new.filters) })
            } else {
                val keyed = upsert(out, localT)
                val t = roundTime(localT.coerceAtLeast(0.0))
                keyed.copy(
                    keyframes = keyed.keyframes.map {
                        if (abs(it.time - t) <= MERGE_TOLERANCE) it.copy(filters = new.filters) else it
                    }
                )
            }
        }
        val patch = KeyframePatch(
            scale = new.scale.takeIf { it != old.scale },
            opacity = new.opacity.takeIf { it != old.opacity },
            rotation = new.rotation.takeIf { it != old.rotation },
            x = new.x.takeIf { it != old.x },
            y = new.y.takeIf { it != old.y }
        )
        if (!patch.isEmpty) out = upsert(out, localT, patch)
        return out
    }
}
