package com.editpro.studio.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure multi-clip timeline edits: cutting, ripple edits, gaps, clipboard, group moves and
 * selection geometry.
 *
 * Nothing here knows about Android or the view model — every function takes the clip list and
 * returns a new one — so the desktop-style editing rules are unit-tested on the JVM and shared by
 * the UI, the keyboard shortcuts and the assistant tools.
 */
object TimelineOps {

    /** A cut closer than this to a clip edge is ignored: it would leave an unusable sliver. */
    const val MIN_PIECE = 0.05
    private const val EPS = 0.001

    /** Result of an edit that creates or targets specific clips. */
    data class Edit(val clips: List<TimelineClip>, val affectedIds: Set<String> = emptySet())

    private fun resolve(clips: List<TimelineClip>, assets: Map<String, MediaAsset>): List<ResolvedClip> =
        TimelineEngine.starts(clips, assets)

    // ------------------------------------------------------------- placement

    /** Whether [clip] may live on [track]. Titles stay on title tracks, sound on audio tracks. */
    fun canPlace(clip: TimelineClip, asset: MediaAsset?, track: TimelineTrack): Boolean = when {
        clip.assetId == null -> track.isTitle
        asset == null -> track == clip.track
        asset.kind == AssetKind.AUDIO -> track.isAudio
        // A video on an audio track contributes only its sound (this is what "detach audio" makes).
        asset.kind == AssetKind.VIDEO -> track.isVisual || track.isAudio
        else -> track.isVisual
    }

    fun naturalTrack(asset: MediaAsset): TimelineTrack =
        if (asset.kind == AssetKind.AUDIO) TimelineTrack.AUDIO else TimelineTrack.VIDEO

    /** The track an asset dropped on [wanted] should really go to. */
    fun dropTrack(asset: MediaAsset, wanted: TimelineTrack?): TimelineTrack {
        val natural = naturalTrack(asset)
        if (wanted == null) return natural
        val probe = TimelineClip(assetId = asset.id)
        return if (canPlace(probe, asset, wanted)) wanted else natural
    }

    // ----------------------------------------------------------------- cutting

    /**
     * Cuts one clip at an absolute timeline time.
     *
     * The cut is seamless: the left piece keeps the clip's head (fade-in, entrance transition), the
     * right piece keeps its tail (fade-out, exit transition), and keyframes and volume automation
     * are divided so both pieces animate exactly as the uncut clip did. Returns null when [atSec]
     * is not comfortably inside the clip.
     */
    fun splitClip(
        clip: TimelineClip,
        start: Double,
        duration: Double,
        atSec: Double,
        rightId: String = generateUid()
    ): Pair<TimelineClip, TimelineClip>? {
        val offset = atSec - start
        if (offset < MIN_PIECE || offset > duration - MIN_PIECE) return null
        val speed = max(0.25, min(4.0, clip.speed))
        val cutSource = clip.trimIn + offset * speed
        val left = clip.copy(
            trimOut = cutSource,
            keyframes = KeyframeOps.headUntil(clip, offset),
            volumeKeyframes = KeyframeOps.volumeHeadUntil(clip, offset),
            fadeOut = 0.0,
            fadeOutAudio = 0.0,
            transitionOut = "none"
        )
        val right = clip.copy(
            id = rightId,
            startAt = atSec,
            trimIn = cutSource,
            keyframes = KeyframeOps.tailFrom(clip, offset),
            volumeKeyframes = KeyframeOps.volumeTailFrom(clip, offset),
            fadeInAudio = 0.0,
            transition = "none",
            label = if (clip.label.endsWith(" (B)")) clip.label else "${clip.label} (B)"
        )
        return left to right
    }

    /**
     * Cuts every clip in [ids] that lies under [atSec]; with `ids == null`, every clip under it on
     * a track that is not locked ("split all tracks"). Returns null when nothing was cut.
     * [Edit.affectedIds] holds the ids of the new right-hand pieces.
     */
    fun splitAt(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        atSec: Double,
        ids: Set<String>? = null,
        lockedTracks: Set<TimelineTrack> = emptySet()
    ): Edit? {
        val resolved = resolve(clips, assets).associateBy { it.clip.id }
        val created = LinkedHashSet<String>()
        val out = ArrayList<TimelineClip>(clips.size + 4)
        for (c in clips) {
            val rc = resolved[c.id]
            val wanted = if (ids != null) c.id in ids else c.track !in lockedTracks
            val parts = if (rc != null && wanted) splitClip(c, rc.start, rc.duration, atSec) else null
            if (parts == null) {
                out.add(c)
            } else {
                out.add(parts.first)
                out.add(parts.second)
                created.add(parts.second.id)
            }
        }
        return if (created.isEmpty()) null else Edit(out, created)
    }

    // ------------------------------------------------------------ ripple edits

    /** Removes [ids] and slides the later clips on each affected track left to close the holes. */
    fun rippleDelete(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        ids: Set<String>
    ): List<TimelineClip> {
        if (ids.isEmpty()) return clips
        val resolved = resolve(clips, assets)
        val removed = resolved.filter { it.clip.id in ids }
        val starts = resolved.associate { it.clip.id to it.start }
        return clips.filter { it.id !in ids }.map { c ->
            val st = starts[c.id] ?: c.startAt
            val shift = removed.filter { it.clip.track == c.track && it.start < st - EPS }.sumOf { it.duration }
            if (shift > 0.0) c.copy(startAt = max(0.0, st - shift)) else c
        }
    }

    /**
     * Closes the empty space around [atSec] on [track] by sliding everything after it left.
     * Returns null when [atSec] is inside a clip or nothing follows the gap.
     */
    fun closeGapAt(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        track: TimelineTrack,
        atSec: Double
    ): List<TimelineClip>? {
        val onTrack = resolve(clips, assets).filter { it.clip.track == track }
        if (onTrack.any { atSec > it.start + EPS && atSec < it.end - EPS }) return null
        val next = onTrack.filter { it.start >= atSec - EPS }.minByOrNull { it.start } ?: return null
        val prevEnd = onTrack.filter { it.end <= atSec + EPS }.maxOfOrNull { it.end } ?: 0.0
        val gap = next.start - prevEnd
        if (gap <= EPS) return null
        val moving = onTrack.filter { it.start >= next.start - EPS }.associate { it.clip.id to it.start }
        return clips.map { c ->
            val st = moving[c.id]
            if (st != null) c.copy(startAt = max(0.0, st - gap)) else c
        }
    }

    /** Packs the clips on each of [tracks] end to end, keeping each track's first clip in place. */
    fun closeAllGaps(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        tracks: Set<TimelineTrack>
    ): List<TimelineClip> {
        val resolved = resolve(clips, assets)
        val newStart = HashMap<String, Double>()
        for (track in tracks) {
            var cursor: Double? = null
            for (rc in resolved.filter { it.clip.track == track }.sortedBy { it.start }) {
                val st = cursor ?: rc.start
                newStart[rc.clip.id] = st
                cursor = st + rc.duration
            }
        }
        return clips.map { c -> newStart[c.id]?.let { c.copy(startAt = it) } ?: c }
    }

    /**
     * Trims a clip to the playhead in one step — the "Q / W" edit of desktop editors.
     *
     * [keepTail] true removes everything before [atSec]; false removes everything after it. With
     * [ripple] the track closes up so no hole is left behind. Returns null when [atSec] is not
     * inside the clip.
     */
    fun trimToTime(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        clipId: String,
        atSec: Double,
        keepTail: Boolean,
        ripple: Boolean
    ): List<TimelineClip>? {
        val resolved = resolve(clips, assets)
        val rc = resolved.find { it.clip.id == clipId } ?: return null
        val (head, tail) = splitClip(rc.clip, rc.start, rc.duration, atSec) ?: return null
        val original = rc.clip
        val removed = if (keepTail) atSec - rc.start else rc.end - atSec
        val kept = if (keepTail) {
            // The surviving tail is the same clip, so it keeps its identity and its head styling.
            tail.copy(
                id = original.id,
                label = original.label,
                startAt = if (ripple) rc.start else atSec,
                fadeInAudio = original.fadeInAudio,
                transition = original.transition
            )
        } else {
            head.copy(
                fadeOut = original.fadeOut,
                fadeOutAudio = original.fadeOutAudio,
                transitionOut = original.transitionOut
            )
        }
        val starts = resolved.associate { it.clip.id to it.start }
        return clips.map { c ->
            val st = starts[c.id] ?: c.startAt
            when {
                c.id == clipId -> kept
                ripple && c.track == original.track && st >= rc.end - EPS -> c.copy(startAt = max(0.0, st - removed))
                else -> c
            }
        }
    }

    /**
     * Opens [length] seconds of room on [track] at [atSec] for an insert edit: a clip lying across
     * that point is cut there, then everything from that point on moves right.
     */
    fun insertSpace(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        track: TimelineTrack,
        atSec: Double,
        length: Double
    ): List<TimelineClip> {
        if (length <= 0.0) return clips
        val spanning = resolve(clips, assets).find {
            it.clip.track == track && atSec > it.start + MIN_PIECE && atSec < it.end - MIN_PIECE
        }
        val work = if (spanning == null) clips else {
            val parts = splitClip(spanning.clip, spanning.start, spanning.duration, atSec)
            if (parts == null) clips else clips.flatMap { c ->
                if (c.id == spanning.clip.id) listOf(parts.first, parts.second) else listOf(c)
            }
        }
        val starts = resolve(work, assets).associate { it.clip.id to it.start }
        return work.map { c ->
            val st = starts[c.id] ?: c.startAt
            if (c.track == track && st >= atSec - EPS) c.copy(startAt = st + length) else c
        }
    }

    // --------------------------------------------------------------- clipboard

    /**
     * Pastes [clipboard] so its earliest clip starts at [atSec], keeping the clips' spacing and
     * tracks. A pasted clip that would land on an existing one slides right to the first free spot.
     * The clipboard clips must carry their resolved start in `startAt`.
     */
    fun paste(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        clipboard: List<TimelineClip>,
        atSec: Double
    ): Edit {
        if (clipboard.isEmpty()) return Edit(clips)
        val origin = clipboard.minOf { it.startAt }
        var work = clips
        val created = LinkedHashSet<String>()
        for (src in clipboard.sortedBy { it.startAt }) {
            val id = generateUid()
            val desired = max(0.0, atSec + (src.startAt - origin))
            val start = TimelineEngine.resolveTimelineStart(
                desired = desired,
                duration = TimelineEngine.clipDuration(src, assets),
                track = src.track,
                clipId = id,
                scalePxPerSec = 0.0,
                direction = 1.0,
                allClips = work,
                assetsById = assets,
                playhead = atSec,
                magneticSnap = false
            )
            work = work + src.copy(id = id, startAt = start)
            created.add(id)
        }
        return Edit(work, created)
    }

    // -------------------------------------------------------------- group moves

    /**
     * Limits a group move so no selected clip runs into an unselected clip on its own track or
     * past the start of the timeline. The group stops at the first obstacle instead of overlapping.
     */
    fun clampGroupDelta(resolved: List<ResolvedClip>, ids: Set<String>, delta: Double): Double {
        val moving = resolved.filter { it.clip.id in ids }
        if (moving.isEmpty() || delta == 0.0) return 0.0
        val fixed = resolved.filter { it.clip.id !in ids }
        var limit = Double.MAX_VALUE
        for (m in moving) {
            val room = if (delta > 0.0) {
                val next = fixed.filter { it.clip.track == m.clip.track && it.start >= m.end - EPS }
                    .minOfOrNull { it.start }
                if (next == null) Double.MAX_VALUE else next - m.end
            } else {
                val prevEnd = fixed.filter { it.clip.track == m.clip.track && it.end <= m.start + EPS }
                    .maxOfOrNull { it.end } ?: 0.0
                m.start - prevEnd
            }
            limit = min(limit, max(0.0, room))
        }
        return if (delta > 0.0) min(delta, limit) else max(delta, -limit)
    }

    /** Moves every clip in [ids] by the same amount, clamped by [clampGroupDelta]. */
    fun moveGroup(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        ids: Set<String>,
        delta: Double
    ): List<TimelineClip> {
        val resolved = resolve(clips, assets)
        val d = clampGroupDelta(resolved, ids, delta)
        if (d == 0.0) return clips
        val starts = resolved.associate { it.clip.id to it.start }
        return clips.map { c ->
            if (c.id in ids) c.copy(startAt = max(0.0, (starts[c.id] ?: c.startAt) + d)) else c
        }
    }

    // --------------------------------------------------------------- selection

    /** Clips on [tracks] that overlap the time range — what a marquee drag selects. */
    fun clipsInRange(
        resolved: List<ResolvedClip>,
        tracks: Set<TimelineTrack>,
        fromSec: Double,
        toSec: Double
    ): Set<String> {
        val lo = min(fromSec, toSec)
        val hi = max(fromSec, toSec)
        return resolved
            .filter { it.clip.track in tracks && it.end > lo + EPS && it.start < hi - EPS }
            .map { it.clip.id }
            .toSet()
    }

    /** Shift-click range: every clip between the anchor and the target, across the tracks between them. */
    fun rangeBetween(resolved: List<ResolvedClip>, anchorId: String?, targetId: String): Set<String> {
        val a = resolved.find { it.clip.id == anchorId }
        val b = resolved.find { it.clip.id == targetId } ?: return emptySet()
        if (a == null) return setOf(targetId)
        val tracks = TimelineTrack.entries
            .filter { it.ordinal in min(a.clip.track.ordinal, b.clip.track.ordinal)..max(a.clip.track.ordinal, b.clip.track.ordinal) }
            .toSet()
        return clipsInRange(resolved, tracks, min(a.start, b.start), max(a.end, b.end)) + targetId
    }

    /** Ids of every clip that starts at or after [fromSec] ("select everything forward"). */
    fun clipsFrom(resolved: List<ResolvedClip>, fromSec: Double): Set<String> =
        resolved.filter { it.start >= fromSec - EPS }.map { it.clip.id }.toSet()

    // --------------------------------------------------------------- navigation

    private fun ms(t: Double): Double = (t * 1000.0).roundToInt() / 1000.0

    /** Every cut on the timeline: the start and end of each clip, plus zero. */
    fun editPoints(resolved: List<ResolvedClip>): List<Double> =
        (listOf(0.0) + resolved.flatMap { listOf(ms(it.start), ms(it.end)) }).distinct().sorted()

    fun prevEditPoint(resolved: List<ResolvedClip>, t: Double): Double? =
        editPoints(resolved).lastOrNull { it < t - 0.01 }

    fun nextEditPoint(resolved: List<ResolvedClip>, t: Double): Double? =
        editPoints(resolved).firstOrNull { it > t + 0.01 }

    /** Snap targets for a drag: markers and the edges of every clip that is not being moved. */
    fun snapTimes(
        resolved: List<ResolvedClip>,
        excludeIds: Set<String>,
        markers: List<TimelineMarker>
    ): List<Double> =
        (resolved.filter { it.clip.id !in excludeIds }.flatMap { listOf(it.start, it.end) } +
            markers.map { it.time }).distinct()

    // ---------------------------------------------------------------- trimming

    /**
     * Drags one edge of a clip.
     *
     * [origin] and [originStart] are the clip and its position when the drag began, and
     * [deltaSec] is how far the edge has been dragged since then, so every step of a gesture is
     * computed from the same starting point and dragging back restores exactly what was there.
     *
     * The edge stops at the neighbouring clip on the same track, at the start of the timeline and
     * at the ends of the source media, and it snaps to [snapTimes] within [snapWindowSec].
     * Trimming the head keeps the animation attached to the picture: keyframes and volume
     * automation move with the media instead of sliding against it.
     */
    fun trimEdge(
        clips: List<TimelineClip>,
        assets: Map<String, MediaAsset>,
        origin: TimelineClip,
        originStart: Double,
        head: Boolean,
        deltaSec: Double,
        snapTimes: List<Double> = emptyList(),
        snapWindowSec: Double = 0.0
    ): TimelineClip {
        val speed = max(0.25, min(4.0, origin.speed))
        val asset = origin.assetId?.let { assets[it] }
        val timed = asset != null && (asset.kind == AssetKind.VIDEO || asset.kind == AssetKind.AUDIO)
        val outPoint = if (origin.trimOut > 0.0) origin.trimOut else (asset?.durationSec ?: 5.0)
        val originDur = TimelineEngine.clipDuration(origin, assets)
        val originEnd = originStart + originDur
        val minLen = 0.1
        val others = resolve(clips, assets).filter { it.clip.id != origin.id && it.clip.track == origin.track }

        fun snapped(t: Double): Double {
            if (snapWindowSec <= 0.0) return t
            val best = snapTimes.minByOrNull { kotlin.math.abs(it - t) } ?: return t
            return if (kotlin.math.abs(best - t) <= snapWindowSec) best else t
        }

        if (head) {
            val prevEnd = others.filter { it.end <= originStart + EPS }.maxOfOrNull { it.end } ?: 0.0
            // Timed media cannot be pulled back past its first frame; stills and titles can grow freely.
            val mediaLimit = if (timed) originStart - origin.trimIn / speed else 0.0
            val lo = max(max(0.0, prevEnd), mediaLimit)
            val hi = originEnd - minLen
            val newStart = snapped(originStart + deltaSec).coerceIn(min(lo, hi), hi)
            val shift = newStart - originStart
            if (kotlin.math.abs(shift) < 1e-9) return origin.copy(startAt = originStart)
            val keyframes = when {
                origin.keyframes.isEmpty() -> origin.keyframes
                shift > 0.0 -> KeyframeOps.tailFrom(origin, shift)
                else -> origin.keyframes.map { it.copy(time = roundSec(it.time - shift)) }
            }
            val volume = when {
                origin.volumeKeyframes.isEmpty() -> origin.volumeKeyframes
                shift > 0.0 -> KeyframeOps.volumeTailFrom(origin, shift)
                else -> origin.volumeKeyframes.map { it.copy(time = roundSec(it.time - shift)) }
            }
            return if (timed) {
                origin.copy(
                    startAt = newStart,
                    trimIn = max(0.0, origin.trimIn + shift * speed),
                    trimOut = outPoint,
                    keyframes = keyframes,
                    volumeKeyframes = volume
                )
            } else {
                origin.copy(
                    startAt = newStart,
                    trimOut = max(origin.trimIn + minLen * speed, outPoint - shift * speed),
                    keyframes = keyframes,
                    volumeKeyframes = volume
                )
            }
        }

        val nextStart = others.filter { it.start >= originEnd - EPS }.minOfOrNull { it.start } ?: Double.MAX_VALUE
        val mediaLimit = if (timed && asset != null && !asset.durationGuess) {
            originStart + (asset.durationSec - origin.trimIn) / speed
        } else Double.MAX_VALUE
        val lo = originStart + minLen
        val hi = max(lo, min(nextStart, mediaLimit))
        val newEnd = snapped(originEnd + deltaSec).coerceIn(lo, hi)
        return origin.copy(startAt = originStart, trimOut = origin.trimIn + (newEnd - originStart) * speed)
    }

    private fun roundSec(t: Double): Double = (t * 1000.0).roundToInt() / 1000.0

    // ------------------------------------------------------------------- media

    /**
     * Swaps the media behind a clip while keeping its grade, motion and position. The trim is
     * pulled back inside the new source when that source is shorter.
     */
    fun replaceMedia(clip: TimelineClip, newAsset: MediaAsset): TimelineClip {
        val timed = newAsset.kind == AssetKind.VIDEO || newAsset.kind == AssetKind.AUDIO
        if (!timed || newAsset.durationGuess) return clip.copy(assetId = newAsset.id, label = newAsset.name)
        val span = max(0.1, clip.trimOut - clip.trimIn)
        val trimIn = clip.trimIn.coerceIn(0.0, max(0.0, newAsset.durationSec - 0.1))
        val trimOut = min(newAsset.durationSec, trimIn + span).coerceAtLeast(trimIn + 0.1)
        return clip.copy(assetId = newAsset.id, label = newAsset.name, trimIn = trimIn, trimOut = trimOut)
    }
}
