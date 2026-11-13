package com.editpro.studio

import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.ClipMenuAction
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.Keyframe
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.ProjectSettings
import com.editpro.studio.model.ProjectStore
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineOps
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.VolumePoint
import com.editpro.studio.model.formatDurationShort
import com.editpro.studio.model.formatRelativeTime
import com.editpro.studio.ui.chordKeyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Edge trimming, what is on screen and audible at a given moment, media ownership inside a
 * project folder, and the small pieces the keyboard and the project list rely on.
 */
class TimelineEditingTest {

    private val video = MediaAsset(id = "v", name = "shot.mp4", kind = AssetKind.VIDEO, durationSec = 20.0)
    private val still = MediaAsset(id = "i", name = "photo.jpg", kind = AssetKind.IMAGE, durationSec = 5.0)
    private val music = MediaAsset(id = "a", name = "song.mp3", kind = AssetKind.AUDIO, durationSec = 60.0)
    private val assets = listOf(video, still, music).associateBy { it.id }

    private fun clip(
        id: String,
        start: Double,
        trimIn: Double = 0.0,
        trimOut: Double = 5.0,
        assetId: String? = "v",
        track: TimelineTrack = TimelineTrack.VIDEO,
        speed: Double = 1.0
    ) = TimelineClip(id = id, assetId = assetId, track = track, startAt = start, trimIn = trimIn, trimOut = trimOut, speed = speed)

    // -------------------------------------------------------------- edge trims

    @Test
    fun trimmingTheHeadMovesTheStartAndTheSourceInPointTogether() {
        val origin = clip("a", start = 10.0, trimIn = 2.0, trimOut = 8.0)
        val out = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = 1.5)
        assertEquals(11.5, out.startAt, 1e-9)
        assertEquals(3.5, out.trimIn, 1e-9)
        assertEquals(8.0, out.trimOut, 1e-9)
        // The end of the clip has not moved.
        assertEquals(16.0, out.startAt + TimelineEngine.clipDuration(out, assets), 1e-9)
    }

    @Test
    fun aHeadTrimCannotRevealMediaBeforeTheFirstFrame() {
        val origin = clip("a", start = 10.0, trimIn = 2.0, trimOut = 8.0)
        val out = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = -9.0)
        assertEquals(8.0, out.startAt, 1e-9)
        assertEquals(0.0, out.trimIn, 1e-9)
    }

    @Test
    fun aHeadTrimAccountsForClipSpeed() {
        // At 2x, one second on the timeline is two seconds of source.
        val origin = clip("a", start = 10.0, trimIn = 4.0, trimOut = 12.0, speed = 2.0)
        val out = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = 1.0)
        assertEquals(11.0, out.startAt, 1e-9)
        assertEquals(6.0, out.trimIn, 1e-9)
        assertEquals(14.0, out.startAt + TimelineEngine.clipDuration(out, assets), 1e-9)
    }

    @Test
    fun anEdgeStopsAtTheNeighbouringClip() {
        val before = clip("before", start = 0.0, trimOut = 4.0)
        val origin = clip("a", start = 5.0, trimIn = 3.0, trimOut = 8.0)
        val after = clip("after", start = 11.0, trimOut = 4.0)
        val all = listOf(before, origin, after)

        val head = TimelineOps.trimEdge(all, assets, origin, 5.0, head = true, deltaSec = -3.0)
        assertEquals("the head stops where the previous clip ends", 4.0, head.startAt, 1e-9)

        val tail = TimelineOps.trimEdge(all, assets, origin, 5.0, head = false, deltaSec = 6.0)
        val end = tail.startAt + TimelineEngine.clipDuration(tail, assets)
        assertEquals("the tail stops where the next clip begins", 11.0, end, 1e-9)
    }

    @Test
    fun aTailTrimCannotRunPastTheEndOfTheMedia() {
        val origin = clip("a", start = 0.0, trimIn = 15.0, trimOut = 18.0)
        val out = TimelineOps.trimEdge(listOf(origin), assets, origin, 0.0, head = false, deltaSec = 30.0)
        assertEquals(20.0, out.trimOut, 1e-9)
    }

    @Test
    fun aStillCanBeStretchedFromEitherEdge() {
        val origin = clip("p", start = 10.0, trimOut = 5.0, assetId = "i")
        val earlier = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = -4.0)
        assertEquals(6.0, earlier.startAt, 1e-9)
        assertEquals(9.0, TimelineEngine.clipDuration(earlier, assets), 1e-9)

        val later = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = false, deltaSec = 25.0)
        assertEquals(30.0, TimelineEngine.clipDuration(later, assets), 1e-9)
    }

    @Test
    fun anEdgeSnapsToANearbyCutOnAnotherTrack() {
        val origin = clip("a", start = 0.0, trimOut = 10.0)
        val snapped = TimelineOps.trimEdge(
            listOf(origin), assets, origin, 0.0, head = false, deltaSec = -2.92,
            snapTimes = listOf(7.0, 30.0), snapWindowSec = 0.15
        )
        assertEquals(7.0, TimelineEngine.clipDuration(snapped, assets), 1e-9)

        val free = TimelineOps.trimEdge(
            listOf(origin), assets, origin, 0.0, head = false, deltaSec = -2.5,
            snapTimes = listOf(7.0), snapWindowSec = 0.15
        )
        assertEquals(7.5, TimelineEngine.clipDuration(free, assets), 1e-9)
    }

    @Test
    fun draggingAnEdgeBackRestoresTheClipExactly() {
        val origin = clip("a", start = 10.0, trimIn = 2.0, trimOut = 8.0).copy(
            keyframes = listOf(Keyframe(time = 0.0, scale = 100.0), Keyframe(time = 4.0, scale = 200.0)),
            volumeKeyframes = listOf(VolumePoint(1.0, 50.0))
        )
        // Every step of a drag is computed from the clip as it was when the drag began.
        val dragged = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = 2.0)
        assertEquals(12.0, dragged.startAt, 1e-9)
        val back = TimelineOps.trimEdge(listOf(dragged), assets, origin, 10.0, head = true, deltaSec = 0.0)
        assertEquals(origin, back)
    }

    @Test
    fun trimmingTheHeadKeepsTheAnimationOnThePicture() {
        val origin = clip("a", start = 10.0, trimIn = 0.0, trimOut = 8.0).copy(
            keyframes = listOf(Keyframe(time = 0.0, scale = 100.0), Keyframe(time = 4.0, scale = 200.0))
        )
        val out = TimelineOps.trimEdge(listOf(origin), assets, origin, 10.0, head = true, deltaSec = 2.0)
        // Two seconds in, the original clip was half way through its zoom; the trimmed clip starts there.
        assertEquals(150.0, TimelineEngine.animatedProps(out, 0.0).scale, 0.5)
        assertEquals(200.0, TimelineEngine.animatedProps(out, 2.0).scale, 0.5)

        // Pulling the head back out moves the keyframes later by the same amount.
        val revealed = clip("b", start = 10.0, trimIn = 5.0, trimOut = 9.0).copy(
            keyframes = listOf(Keyframe(time = 1.0, scale = 120.0))
        )
        val wider = TimelineOps.trimEdge(listOf(revealed), assets, revealed, 10.0, head = true, deltaSec = -3.0)
        assertEquals(4.0, wider.keyframes.single().time, 1e-6)
    }

    // ------------------------------------------------- what plays at a moment

    @Test
    fun aClipOwnsItsFirstFrameButNotItsLastInstant() {
        val first = ResolvedClip(clip("a", start = 0.0), start = 0.0, duration = 5.0)
        val second = ResolvedClip(clip("b", start = 5.0), start = 5.0, duration = 5.0)
        val projectEnd = 10.0
        assertTrue(TimelineEngine.isActiveAt(first, 0.0, projectEnd))
        assertTrue(TimelineEngine.isActiveAt(first, 4.999, projectEnd))
        // At a cut only the incoming clip is shown.
        assertFalse(TimelineEngine.isActiveAt(first, 5.0, projectEnd))
        assertTrue(TimelineEngine.isActiveAt(second, 5.0, projectEnd))
        // The very end of the edit still shows the last clip instead of going black.
        assertTrue(TimelineEngine.isActiveAt(second, 10.0, projectEnd))
        assertFalse(TimelineEngine.isActiveAt(second, 10.5, projectEnd))
    }

    @Test
    fun soloAndMuteDecideWhichTracksAreHeard() {
        val all = TimelineTrack.entries.associateWith { TrackState() }
        val heard = TimelineEngine.audibleTracks(all)
        assertTrue(TimelineTrack.VIDEO in heard && TimelineTrack.AUDIO in heard)
        assertFalse("title tracks make no sound", TimelineTrack.TEXT in heard || TimelineTrack.GRAPHICS in heard)

        val muted = all + (TimelineTrack.AUDIO to TrackState(muted = true))
        assertFalse(TimelineTrack.AUDIO in TimelineEngine.audibleTracks(muted))

        // Soloing a picture track silences the music as well: a video clip carries its own sound.
        val solo = all + (TimelineTrack.VIDEO to TrackState(solo = true))
        assertEquals(setOf(TimelineTrack.VIDEO), TimelineEngine.audibleTracks(solo))

        val soloButMuted = all + (TimelineTrack.VIDEO to TrackState(solo = true, muted = true))
        assertTrue(TimelineEngine.audibleTracks(soloButMuted).isEmpty())
    }

    @Test
    fun videoAndAudioClipsCarrySoundAndStillsDoNot() {
        assertTrue(TimelineEngine.carriesSound(clip("v", 0.0), video))
        assertTrue(TimelineEngine.carriesSound(clip("m", 0.0, assetId = "a", track = TimelineTrack.AUDIO), music))
        // A video's sound detached onto an audio track is still sound.
        assertTrue(TimelineEngine.carriesSound(clip("d", 0.0, track = TimelineTrack.AUDIO), video))
        assertFalse(TimelineEngine.carriesSound(clip("p", 0.0, assetId = "i"), still))
        assertFalse(TimelineEngine.carriesSound(clip("t", 0.0, assetId = null, track = TimelineTrack.TEXT), null))
    }

    // ------------------------------------------------------- project ownership

    private fun tempDir(): File = Files.createTempDirectory("editpro-test").toFile()

    @Test
    fun importedMediaMovesIntoTheProjectFolder() {
        val root = tempDir()
        val outside = tempDir()
        try {
            val store = ProjectStore(root)
            val project = store.create("Holiday", ProjectSettings())
            val file = File(outside, "clip.mp4").apply { writeText("video") }
            val thumb = File(outside, "frame_0.jpg").apply { writeText("thumb") }
            val asset = video.copy(localPath = file.absolutePath, filmstripPaths = listOf(thumb.absolutePath))

            val owned = store.adoptAsset(project.id, asset)
            assertTrue(store.owns(project.id, owned.localPath))
            assertTrue(store.owns(project.id, owned.filmstripPaths.single()))
            assertTrue(File(owned.localPath!!).isFile)
            assertFalse("the file was moved, not copied", file.exists())
            // Adopting again changes nothing.
            assertEquals(owned, store.adoptAsset(project.id, owned))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun adoptingTwoFilesWithTheSameNameKeepsBoth() {
        val root = tempDir()
        val first = tempDir()
        val second = tempDir()
        try {
            val store = ProjectStore(root)
            val project = store.create("Clash", ProjectSettings())
            val a = File(first, "take.mp4").apply { writeText("one") }
            val b = File(second, "take.mp4").apply { writeText("two") }
            val pathA = store.adopt(project.id, a.absolutePath)!!
            val pathB = store.adopt(project.id, b.absolutePath)!!
            assertTrue(pathA != pathB)
            assertEquals("one", File(pathA).readText())
            assertEquals("two", File(pathB).readText())
        } finally {
            root.deleteRecursively()
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun pruningDeletesOnlyUnreferencedFilesInsideTheProject() {
        val root = tempDir()
        val outside = tempDir()
        try {
            val store = ProjectStore(root)
            val project = store.create("Tidy", ProjectSettings())
            val kept = store.adopt(project.id, File(outside, "kept.mp4").apply { writeText("k") }.absolutePath)!!
            val dropped = store.adopt(project.id, File(outside, "dropped.mp4").apply { writeText("d") }.absolutePath)!!
            val elsewhere = File(outside, "elsewhere.mp4").apply { writeText("e") }

            val removed = store.pruneMedia(project.id, setOf(kept))
            assertEquals(1, removed)
            assertTrue(File(kept).exists())
            assertFalse(File(dropped).exists())
            assertTrue("files outside the project are never touched", elsewhere.exists())
            assertTrue("the project file itself survives", store.exists(project.id))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun aProjectFileImportsAsANewProjectWithItsOwnCopyOfTheMedia() {
        val root = tempDir()
        val outside = tempDir()
        try {
            val store = ProjectStore(root)
            val source = File(outside, "shot.mp4").apply { writeText("video") }
            val asset = video.copy(localPath = source.absolutePath)
            val json = ProjectSerializer.serializeProject(
                name = "Shared edit",
                settings = ProjectSettings(1080, 1920, 30),
                clips = listOf(clip("c1", 0.0)),
                markers = emptyList(),
                assets = listOf(asset)
            )

            val imported = store.importDocument(json)
            assertNotNull(imported)
            assertEquals("Shared edit", imported!!.name)
            assertEquals(1, imported.clipCount)
            assertEquals(1080, imported.width)

            val doc = ProjectSerializer.deserializeProject(store.load(imported.id)!!)
            val path = doc.assets.single().localPath
            assertTrue(store.owns(imported.id, path))
            assertTrue("the original file is left where it was", source.exists())

            // Importing the same file again makes a second, separately named project.
            val again = store.importDocument(json)
            assertEquals("Shared edit 2", again!!.name)
            assertNull(store.importDocument("this is not a project"))
            assertEquals(2, store.list().size)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ small pieces

    @Test
    fun everyDefaultShortcutCanBeTypedOnAKeyboard() {
        val typeable = (0..320).mapNotNull { chordKeyName(it) }.toSet()
        for (action in EditorShortcut.entries) {
            for (chord in action.defaults) {
                val key = chord.substringAfterLast('+')
                assertTrue("${action.id}: no key produces \"$key\" (chord $chord)", key in typeable)
            }
        }
        // Modifier keys alone never count as a shortcut.
        assertNull(chordKeyName(android.view.KeyEvent.KEYCODE_CTRL_LEFT))
        assertNull(chordKeyName(android.view.KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals("Backspace", chordKeyName(android.view.KeyEvent.KEYCODE_DEL))
        assertEquals("Delete", chordKeyName(android.view.KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test
    fun aSavedClipMenuGainsTheNewEditingActionsOnce() {
        val saved = listOf("split", "duplicate", "delete", "no_longer_exists")
        val upgraded = ClipMenuAction.upgradeSavedOrder(saved)
        assertTrue(upgraded.containsAll(ClipMenuAction.addedInV3))
        assertEquals(listOf("split", "duplicate", "delete"), upgraded.takeLast(3))
        // Already-present entries are not duplicated.
        assertEquals(upgraded, ClipMenuAction.upgradeSavedOrder(upgraded))
        // Every default entry is a real action.
        assertTrue(ClipMenuAction.defaultOrder.all { ClipMenuAction.fromId(it) != null })
    }

    @Test
    fun projectCardTimesReadNaturally() {
        assertEquals("0:42", formatDurationShort(42.9))
        assertEquals("12:05", formatDurationShort(725.0))
        assertEquals("1:02:30", formatDurationShort(3750.0))
        assertEquals("0:00", formatDurationShort(Double.NaN))

        val now = 1_000_000_000_000L
        val minute = 60_000L
        assertEquals("just now", formatRelativeTime(now, now - 20_000L))
        assertEquals("5 min ago", formatRelativeTime(now, now - 5 * minute))
        assertEquals("3 h ago", formatRelativeTime(now, now - 3 * 60 * minute))
        assertEquals("yesterday", formatRelativeTime(now, now - 30 * 60 * minute))
        assertEquals("4 days ago", formatRelativeTime(now, now - 4 * 24 * 60 * minute))
        assertEquals("12 Mar 2026", formatRelativeTime(now, now - 30L * 24 * 60 * minute) { "12 Mar 2026" })
        // A clock that went backwards never produces a negative age.
        assertEquals("just now", formatRelativeTime(now, now + 5 * minute))
    }
}
