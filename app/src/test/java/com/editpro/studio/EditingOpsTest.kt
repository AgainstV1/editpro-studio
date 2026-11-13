package com.editpro.studio

import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.ClipFilters
import com.editpro.studio.model.Keyframe
import com.editpro.studio.model.KeyframeOps
import com.editpro.studio.model.KeyframePatch
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineMarker
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.VolumePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop-style editing rules: cutting, ripple edits, clipboard, group moves and keyframes. */
class EditingOpsTest {

    private val video = MediaAsset(id = "v", name = "shot.mp4", kind = AssetKind.VIDEO, durationSec = 60.0)
    private val music = MediaAsset(id = "m", name = "song.mp3", kind = AssetKind.AUDIO, durationSec = 60.0)
    private val still = MediaAsset(id = "i", name = "still.png", kind = AssetKind.IMAGE, durationSec = 5.0)
    private val assets = mapOf("v" to video, "m" to music, "i" to still)

    private fun clip(
        id: String,
        start: Double,
        length: Double,
        track: TimelineTrack = TimelineTrack.VIDEO,
        assetId: String? = "v"
    ) = TimelineClip(id = id, assetId = assetId, track = track, startAt = start, trimIn = 0.0, trimOut = length)

    private fun starts(clips: List<TimelineClip>) =
        TimelineEngine.starts(clips, assets).associate { it.clip.id to it.start }

    // ---------------------------------------------------------------- cutting

    @Test
    fun splitIsSeamlessAndSpeedAware() {
        val source = clip("a", 2.0, 8.0).copy(
            speed = 2.0,
            fadeInAudio = 0.5,
            fadeOutAudio = 0.7,
            fadeOut = 0.3,
            transition = "fade",
            transitionOut = "fade"
        )
        // 8 s of source at 2x occupies 4 s of timeline: 2.0 .. 6.0.
        assertEquals(4.0, TimelineEngine.clipDuration(source, assets), 1e-9)
        val (left, right) = TimelineOps.splitClip(source, 2.0, 4.0, 3.0, rightId = "b")!!

        // One timeline second in at 2x is two source seconds.
        assertEquals(2.0, left.trimOut, 1e-9)
        assertEquals(2.0, right.trimIn, 1e-9)
        assertEquals(8.0, right.trimOut, 1e-9)
        assertEquals(3.0, right.startAt, 1e-9)
        assertEquals("b", right.id)

        // The head keeps the fade-in, the tail keeps the fade-out, and the cut itself has neither.
        assertEquals(0.5, left.fadeInAudio, 1e-9)
        assertEquals(0.0, left.fadeOutAudio, 1e-9)
        assertEquals(0.0, left.fadeOut, 1e-9)
        assertEquals("none", left.transitionOut)
        assertEquals(0.0, right.fadeInAudio, 1e-9)
        assertEquals(0.7, right.fadeOutAudio, 1e-9)
        assertEquals("none", right.transition)
        assertEquals("fade", right.transitionOut)
    }

    @Test
    fun splitRefusesSliversAndOutsideCuts() {
        val c = clip("a", 0.0, 4.0)
        assertNull(TimelineOps.splitClip(c, 0.0, 4.0, 0.01))
        assertNull(TimelineOps.splitClip(c, 0.0, 4.0, 3.99))
        assertNull(TimelineOps.splitClip(c, 0.0, 4.0, 9.0))
    }

    @Test
    fun splitDividesKeyframesWithoutChangingTheMotion() {
        val c = clip("a", 0.0, 4.0).copy(
            ease = "linear",
            keyframes = listOf(
                Keyframe(time = 0.0, scale = 100.0, x = 0.0),
                Keyframe(time = 4.0, scale = 200.0, x = 40.0)
            ),
            volumeKeyframes = listOf(VolumePoint(0.0, 100.0), VolumePoint(4.0, 0.0))
        )
        val (left, right) = TimelineOps.splitClip(c, 0.0, 4.0, 1.0)!!

        // Both pieces meet at the interpolated value, and the right piece's clock restarts at 0.
        assertEquals(125.0, left.keyframes.last().scale, 1e-6)
        assertEquals(1.0, left.keyframes.last().time, 1e-6)
        assertEquals(0.0, right.keyframes.first().time, 1e-6)
        assertEquals(125.0, right.keyframes.first().scale, 1e-6)
        assertEquals(3.0, right.keyframes.last().time, 1e-6)

        // Sampling the pieces gives what the uncut clip gave at the same timeline instant.
        assertEquals(TimelineEngine.animatedProps(c, 0.5).x, TimelineEngine.animatedProps(left, 0.5).x, 1e-6)
        assertEquals(TimelineEngine.animatedProps(c, 2.5).x, TimelineEngine.animatedProps(right, 1.5).x, 1e-6)
        assertEquals(TimelineEngine.automationGain(c, 2.5), TimelineEngine.automationGain(right, 1.5), 1e-6)
        assertEquals(TimelineEngine.automationGain(c, 0.5), TimelineEngine.automationGain(left, 0.5), 1e-6)
    }

    @Test
    fun splitAllTracksSkipsLockedTracksAndClipsNotUnderThePlayhead() {
        val clips = listOf(
            clip("v1", 0.0, 6.0),
            clip("a1", 0.0, 6.0, TimelineTrack.AUDIO, "m"),
            clip("locked", 0.0, 6.0, TimelineTrack.OVERLAY, "i"),
            clip("later", 8.0, 2.0)
        )
        val edit = TimelineOps.splitAt(clips, assets, 3.0, ids = null, lockedTracks = setOf(TimelineTrack.OVERLAY))!!
        assertEquals(6, edit.clips.size)
        assertEquals(2, edit.affectedIds.size)
        assertTrue(edit.clips.none { it.id == "locked" && it.trimOut != 6.0 })

        // Restricting to ids cuts only those clips.
        val one = TimelineOps.splitAt(clips, assets, 3.0, ids = setOf("a1"))!!
        assertEquals(5, one.clips.size)
        assertNull(TimelineOps.splitAt(clips, assets, 7.0, ids = null))
    }

    // ------------------------------------------------------------ ripple edits

    @Test
    fun rippleDeleteClosesOnlyTheAffectedTracks() {
        val clips = listOf(
            clip("a", 0.0, 2.0), clip("b", 2.0, 3.0), clip("c", 5.0, 1.0), clip("d", 8.0, 1.0),
            clip("music", 0.0, 9.0, TimelineTrack.AUDIO, "m")
        )
        val out = TimelineOps.rippleDelete(clips, assets, setOf("b"))
        val s = starts(out)
        assertFalse(s.containsKey("b"))
        assertEquals(0.0, s.getValue("a"), 1e-9)
        assertEquals(2.0, s.getValue("c"), 1e-9)
        // The existing gap before "d" is preserved, just moved.
        assertEquals(5.0, s.getValue("d"), 1e-9)
        assertEquals(0.0, s.getValue("music"), 1e-9)

        val multi = starts(TimelineOps.rippleDelete(clips, assets, setOf("a", "c")))
        assertEquals(0.0, multi.getValue("b"), 1e-9)
        assertEquals(5.0, multi.getValue("d"), 1e-9)
    }

    @Test
    fun closeGapAndCloseAllGaps() {
        val clips = listOf(clip("a", 0.0, 2.0), clip("b", 4.0, 1.0), clip("c", 7.0, 1.0))
        val closed = starts(TimelineOps.closeGapAt(clips, assets, TimelineTrack.VIDEO, 3.0)!!)
        assertEquals(2.0, closed.getValue("b"), 1e-9)
        assertEquals(5.0, closed.getValue("c"), 1e-9)

        // Inside a clip, or with nothing after the point, there is no gap to close.
        assertNull(TimelineOps.closeGapAt(clips, assets, TimelineTrack.VIDEO, 1.0))
        assertNull(TimelineOps.closeGapAt(clips, assets, TimelineTrack.VIDEO, 9.0))
        assertNull(TimelineOps.closeGapAt(clips, assets, TimelineTrack.AUDIO, 3.0))

        val packed = starts(TimelineOps.closeAllGaps(clips, assets, setOf(TimelineTrack.VIDEO)))
        assertEquals(0.0, packed.getValue("a"), 1e-9)
        assertEquals(2.0, packed.getValue("b"), 1e-9)
        assertEquals(3.0, packed.getValue("c"), 1e-9)
    }

    @Test
    fun rippleTrimToPlayhead() {
        val clips = listOf(clip("a", 0.0, 4.0).copy(fadeInAudio = 0.4, fadeOutAudio = 0.6), clip("b", 4.0, 2.0))

        // Q: drop the first 1.5 s; the clip stays put and everything after closes up.
        val q = TimelineOps.trimToTime(clips, assets, "a", 1.5, keepTail = true, ripple = true)!!
        val qa = q.first { it.id == "a" }
        assertEquals(1.5, qa.trimIn, 1e-9)
        assertEquals(0.0, qa.startAt, 1e-9)
        assertEquals(0.4, qa.fadeInAudio, 1e-9)
        assertEquals(0.6, qa.fadeOutAudio, 1e-9)
        assertEquals(2.5, starts(q).getValue("b"), 1e-9)

        // W: drop everything after 1.5 s.
        val w = TimelineOps.trimToTime(clips, assets, "a", 1.5, keepTail = false, ripple = true)!!
        assertEquals(1.5, w.first { it.id == "a" }.trimOut, 1e-9)
        assertEquals(0.6, w.first { it.id == "a" }.fadeOutAudio, 1e-9)
        assertEquals(1.5, starts(w).getValue("b"), 1e-9)

        // Without ripple the neighbours do not move and the kept tail starts at the playhead.
        val plain = TimelineOps.trimToTime(clips, assets, "a", 1.5, keepTail = true, ripple = false)!!
        assertEquals(1.5, plain.first { it.id == "a" }.startAt, 1e-9)
        assertEquals(4.0, starts(plain).getValue("b"), 1e-9)

        assertNull(TimelineOps.trimToTime(clips, assets, "a", 9.0, keepTail = true, ripple = true))
    }

    @Test
    fun insertSpaceCutsTheClipUnderThePointAndPushesTheRest() {
        val clips = listOf(clip("a", 0.0, 4.0), clip("b", 4.0, 2.0), clip("music", 0.0, 6.0, TimelineTrack.AUDIO, "m"))
        val out = TimelineOps.insertSpace(clips, assets, TimelineTrack.VIDEO, 1.0, 3.0)
        assertEquals(4, out.size)
        val resolved = TimelineEngine.starts(out, assets).filter { it.clip.track == TimelineTrack.VIDEO }
        assertEquals(listOf(0.0, 4.0, 7.0), resolved.map { it.start })
        assertEquals(0.0, starts(out).getValue("music"), 1e-9)
    }

    // --------------------------------------------------------------- clipboard

    @Test
    fun pasteKeepsSpacingAndNeverOverlaps() {
        val clips = listOf(clip("a", 0.0, 2.0), clip("b", 10.0, 2.0))
        val clipboard = listOf(clip("x", 3.0, 1.0), clip("y", 5.0, 1.0, TimelineTrack.AUDIO, "m"))
        val edit = TimelineOps.paste(clips, assets, clipboard, 20.0)
        assertEquals(2, edit.affectedIds.size)
        val pasted = TimelineEngine.starts(edit.clips, assets).filter { it.clip.id in edit.affectedIds }
        assertEquals(listOf(20.0, 22.0), pasted.map { it.start })
        assertTrue(pasted.none { it.clip.id == "x" || it.clip.id == "y" })

        // Pasting onto an occupied spot slides right to the first free position.
        val blocked = TimelineOps.paste(clips, assets, listOf(clip("x", 0.0, 1.0)), 10.5)
        val landed = TimelineEngine.starts(blocked.clips, assets).first { it.clip.id in blocked.affectedIds }
        assertEquals(12.0, landed.start, 1e-9)
    }

    // -------------------------------------------------------------- group moves

    @Test
    fun groupMoveStopsAtObstaclesAndAtZero() {
        val clips = listOf(
            clip("a", 2.0, 2.0), clip("b", 4.0, 2.0), clip("wall", 9.0, 2.0),
            clip("m1", 3.0, 2.0, TimelineTrack.AUDIO, "m")
        )
        val resolved = TimelineEngine.starts(clips, assets)
        val group = setOf("a", "b", "m1")
        // "b" ends at 6 and "wall" starts at 9: three seconds of room to the right.
        assertEquals(3.0, TimelineOps.clampGroupDelta(resolved, group, 10.0), 1e-9)
        // "a" starts at 2: two seconds of room to the left.
        assertEquals(-2.0, TimelineOps.clampGroupDelta(resolved, group, -10.0), 1e-9)
        assertEquals(1.0, TimelineOps.clampGroupDelta(resolved, group, 1.0), 1e-9)

        val moved = starts(TimelineOps.moveGroup(clips, assets, group, 10.0))
        assertEquals(5.0, moved.getValue("a"), 1e-9)
        assertEquals(7.0, moved.getValue("b"), 1e-9)
        assertEquals(6.0, moved.getValue("m1"), 1e-9)
        assertEquals(9.0, moved.getValue("wall"), 1e-9)
    }

    // --------------------------------------------------------------- selection

    @Test
    fun marqueeRangeAndForwardSelection() {
        val clips = listOf(
            clip("a", 0.0, 2.0), clip("b", 3.0, 2.0), clip("c", 6.0, 2.0),
            clip("t", 1.0, 2.0, TimelineTrack.TEXT, null),
            clip("m", 0.0, 8.0, TimelineTrack.AUDIO, "m")
        )
        val resolved = TimelineEngine.starts(clips, assets)
        assertEquals(setOf("b"), TimelineOps.clipsInRange(resolved, setOf(TimelineTrack.VIDEO), 2.5, 5.5))
        // A marquee dragged right-to-left selects the same clips.
        assertEquals(setOf("a", "b"), TimelineOps.clipsInRange(resolved, setOf(TimelineTrack.VIDEO), 4.0, 1.0))
        assertEquals(
            setOf("a", "b", "t"),
            TimelineOps.clipsInRange(resolved, setOf(TimelineTrack.VIDEO, TimelineTrack.TEXT), 0.5, 3.5)
        )
        // Shift-click from "a" to "c" takes everything between them on that track only.
        assertEquals(setOf("a", "b", "c"), TimelineOps.rangeBetween(resolved, "a", "c"))
        assertEquals(setOf("c"), TimelineOps.rangeBetween(resolved, null, "c"))
        assertEquals(setOf("b", "c"), TimelineOps.clipsFrom(resolved, 3.0))
    }

    @Test
    fun editPointsAndSnapTargets() {
        val clips = listOf(clip("a", 0.0, 2.0), clip("b", 3.0, 2.0))
        val resolved = TimelineEngine.starts(clips, assets)
        assertEquals(listOf(0.0, 2.0, 3.0, 5.0), TimelineOps.editPoints(resolved))
        assertEquals(2.0, TimelineOps.prevEditPoint(resolved, 2.6)!!, 1e-9)
        assertEquals(3.0, TimelineOps.nextEditPoint(resolved, 2.6)!!, 1e-9)
        assertNull(TimelineOps.nextEditPoint(resolved, 5.0))
        assertNull(TimelineOps.prevEditPoint(resolved, 0.0))

        val snaps = TimelineOps.snapTimes(resolved, setOf("b"), listOf(TimelineMarker(7.5, "Beat")))
        assertEquals(setOf(0.0, 2.0, 7.5), snaps.toSet())

        // A dragged clip snaps to a marker with either of its edges.
        val snapped = TimelineEngine.resolveTimelineStart(
            desired = 7.45, duration = 1.0, track = TimelineTrack.OVERLAY, clipId = "z",
            scalePxPerSec = 75.0, direction = 1.0, allClips = clips, assetsById = assets,
            playhead = 0.0, magneticSnap = true, extraSnapTimes = snaps
        )
        assertEquals(7.5, snapped, 1e-9)
        val endSnapped = TimelineEngine.resolveTimelineStart(
            desired = 6.55, duration = 1.0, track = TimelineTrack.OVERLAY, clipId = "z",
            scalePxPerSec = 75.0, direction = 1.0, allClips = clips, assetsById = assets,
            playhead = 0.0, magneticSnap = true, extraSnapTimes = snaps
        )
        assertEquals(6.5, endSnapped, 1e-9)
    }

    @Test
    fun trackPlacementRules() {
        val title = TimelineClip(id = "t", assetId = null, track = TimelineTrack.TEXT)
        assertTrue(TimelineOps.canPlace(title, null, TimelineTrack.GRAPHICS))
        assertFalse(TimelineOps.canPlace(title, null, TimelineTrack.VIDEO))
        assertTrue(TimelineOps.canPlace(clip("a", 0.0, 1.0), video, TimelineTrack.OVERLAY))
        // A video may sit on an audio track, where only its sound is used.
        assertTrue(TimelineOps.canPlace(clip("a", 0.0, 1.0), video, TimelineTrack.AUDIO2))
        assertFalse(TimelineOps.canPlace(clip("a", 0.0, 1.0, assetId = "m"), music, TimelineTrack.VIDEO))
        assertFalse(TimelineOps.canPlace(clip("a", 0.0, 1.0, assetId = "i"), still, TimelineTrack.AUDIO))
        assertEquals(TimelineTrack.AUDIO, TimelineOps.dropTrack(music, TimelineTrack.OVERLAY))
        assertEquals(TimelineTrack.OVERLAY, TimelineOps.dropTrack(still, TimelineTrack.OVERLAY))
        assertEquals(TimelineTrack.VIDEO, TimelineOps.dropTrack(still, TimelineTrack.TEXT))
    }

    @Test
    fun replaceMediaKeepsTheLookAndFitsTheTrim() {
        val graded = clip("a", 2.0, 30.0).copy(trimIn = 20.0, filters = ClipFilters(contrast = 130.0), scale = 140.0)
        val shorter = MediaAsset(id = "s", name = "short.mp4", kind = AssetKind.VIDEO, durationSec = 8.0)
        val out = TimelineOps.replaceMedia(graded, shorter)
        assertEquals("s", out.assetId)
        assertEquals(130.0, out.filters.contrast, 1e-9)
        assertEquals(140.0, out.scale, 1e-9)
        assertTrue(out.trimIn < out.trimOut)
        assertTrue(out.trimOut <= 8.0)
    }

    // ---------------------------------------------------------------- keyframes

    @Test
    fun keyframeUpsertMoveAndNavigation() {
        var c = clip("a", 0.0, 4.0).copy(scale = 100.0, x = 0.0)
        // The first keyframe captures the clip's current values.
        c = KeyframeOps.upsert(c, 0.0)
        c = KeyframeOps.upsert(c, 2.0, KeyframePatch(scale = 200.0, x = 20.0))
        assertEquals(2, c.keyframes.size)
        assertEquals(100.0, c.keyframes[0].scale, 1e-9)
        assertEquals(200.0, c.keyframes[1].scale, 1e-9)

        // Within the merge tolerance the existing keyframe is edited, not duplicated.
        c = KeyframeOps.upsert(c, 2.03, KeyframePatch(rotation = 45.0))
        assertEquals(2, c.keyframes.size)
        assertEquals(45.0, c.keyframes[1].rotation, 1e-9)
        assertEquals(200.0, c.keyframes[1].scale, 1e-9)

        // A keyframe added between two others starts from the interpolated value: no jump.
        val mid = KeyframeOps.upsert(c.copy(ease = "linear"), 1.0)
        assertEquals(3, mid.keyframes.size)
        assertEquals(150.0, mid.keyframes[1].scale, 1e-6)

        assertEquals(1, KeyframeOps.indexAt(c, 2.0))
        assertEquals(-1, KeyframeOps.indexAt(c, 1.0))
        assertEquals(0.0, KeyframeOps.prevTime(c, 1.0)!!, 1e-9)
        assertEquals(2.0, KeyframeOps.nextTime(c, 1.0)!!, 1e-9)
        assertNull(KeyframeOps.nextTime(c, 2.0))

        // Retiming clamps to the clip and replaces a keyframe it lands on.
        val moved = KeyframeOps.move(c, 1, 9.0, clipDuration = 4.0)
        assertEquals(4.0, moved.keyframes.last().time, 1e-9)
        val collapsed = KeyframeOps.move(c, 1, 0.0, clipDuration = 4.0)
        assertEquals(1, collapsed.keyframes.size)
        assertEquals(200.0, collapsed.keyframes[0].scale, 1e-9)

        assertEquals(1, KeyframeOps.remove(c, 0).keyframes.size)
        assertEquals(c, KeyframeOps.remove(c, 7))
    }

    @Test
    fun perKeyframeEasingOverridesTheClipCurve() {
        val base = clip("a", 0.0, 2.0).copy(
            ease = "linear",
            keyframes = listOf(Keyframe(time = 0.0, x = 0.0), Keyframe(time = 2.0, x = 100.0))
        )
        assertEquals(50.0, TimelineEngine.animatedProps(base, 1.0).x, 1e-6)
        assertEquals(25.0, TimelineEngine.animatedProps(base, 0.5).x, 1e-6)

        val hold = KeyframeOps.setEasing(base, 0, "hold")
        assertEquals(0.0, TimelineEngine.animatedProps(hold, 1.9).x, 1e-6)
        assertEquals(100.0, TimelineEngine.animatedProps(hold, 2.0).x, 1e-6)

        val easeIn = KeyframeOps.setEasing(base, 0, "in")
        assertEquals(6.25, TimelineEngine.animatedProps(easeIn, 0.5).x, 1e-6)
        val easeOut = KeyframeOps.setEasing(base, 0, "out")
        assertEquals(43.75, TimelineEngine.animatedProps(easeOut, 0.5).x, 1e-6)

        // An empty easing still means "use the clip's bezier curve", as before.
        val curved = base.copy(ease = "ease", bezier = listOf(0.0, 0.0, 1.0, 1.0))
        assertEquals(50.0, TimelineEngine.animatedProps(curved, 1.0).x, 1e-2)
    }

    @Test
    fun editingAnAnimatedClipReachesTheKeyframes() {
        val warm = ClipFilters(temperature = 30.0)
        val animated = clip("a", 0.0, 4.0).copy(
            ease = "linear",
            filters = ClipFilters(),
            keyframes = listOf(Keyframe(time = 0.0, x = 0.0), Keyframe(time = 4.0, x = 40.0))
        )

        // A slider change on an animated clip is written at the playhead instead of being ignored.
        val nudged = KeyframeOps.reconcileEdit(animated, animated.copy(x = 25.0), 2.0)
        assertEquals(3, nudged.keyframes.size)
        assertEquals(25.0, TimelineEngine.animatedProps(nudged, 2.0).x, 1e-6)
        assertEquals(0.0, TimelineEngine.animatedProps(nudged, 0.0).x, 1e-6)

        // A new look on a clip whose grade is not animated applies to the whole clip.
        val graded = KeyframeOps.reconcileEdit(animated, animated.copy(filters = warm), 2.0)
        assertEquals(2, graded.keyframes.size)
        assertEquals(30.0, TimelineEngine.animatedProps(graded, 0.0).filters.temperature, 1e-6)
        assertEquals(30.0, TimelineEngine.animatedProps(graded, 3.0).filters.temperature, 1e-6)

        // When the grade itself is animated, the change is keyed at the playhead only.
        val gradeAnimated = animated.copy(
            keyframes = listOf(
                Keyframe(time = 0.0, filters = ClipFilters(brightness = 80.0)),
                Keyframe(time = 4.0, filters = ClipFilters(brightness = 120.0))
            )
        )
        val keyed = KeyframeOps.reconcileEdit(gradeAnimated, gradeAnimated.copy(filters = warm), 2.0)
        assertEquals(3, keyed.keyframes.size)
        assertEquals(30.0, TimelineEngine.animatedProps(keyed, 2.0).filters.temperature, 1e-6)
        assertEquals(80.0, TimelineEngine.animatedProps(keyed, 0.0).filters.brightness, 1e-6)

        // Clips without keyframes, and edits that replace the keyframes, are left alone.
        val plain = clip("p", 0.0, 4.0)
        assertEquals(plain.copy(x = 5.0), KeyframeOps.reconcileEdit(plain, plain.copy(x = 5.0), 1.0))
        val cleared = animated.copy(keyframes = emptyList(), x = 9.0)
        assertEquals(cleared, KeyframeOps.reconcileEdit(animated, cleared, 1.0))
        assertNotNull(nudged)
    }
}
