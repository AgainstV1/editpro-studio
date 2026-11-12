package com.editpro.studio

import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.EditorShortcut
import com.editpro.studio.model.Keyframe
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.MgMath
import com.editpro.studio.model.MotionGraphics
import com.editpro.studio.model.ProjectSerializer
import com.editpro.studio.model.ProjectSettings
import com.editpro.studio.model.ProjectStore
import com.editpro.studio.model.ShortcutBindings
import com.editpro.studio.model.ShortcutChords
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Shortcuts, project storage, motion-graphics timing, audio processing and the file format. */
class StudioFeaturesTest {

    // ---------------------------------------------------------------- shortcuts

    @Test
    fun defaultShortcutsNeverCollide() {
        val seen = HashMap<String, EditorShortcut>()
        for (action in EditorShortcut.entries) {
            assertTrue("${action.id} has no default chord", action.defaults.isNotEmpty())
            for (chord in action.defaults) {
                val clash = seen.put(chord, action)
                assertNull("$chord is bound to both ${clash?.id} and ${action.id}", clash)
            }
        }
        assertEquals(EditorShortcut.entries.size, EditorShortcut.entries.map { it.id }.distinct().size)
    }

    @Test
    fun chordsAreNormalised() {
        assertEquals("Ctrl+Shift+Z", ShortcutChords.normalize("shift+ctrl+z"))
        assertEquals("Ctrl+S", ShortcutChords.normalize("Cmd+s"))
        assertEquals("Shift+Delete", ShortcutChords.normalize("Shift+Del"))
        assertEquals("Space", ShortcutChords.normalize("Space"))
        assertEquals("Alt+Left", ShortcutChords.normalize("alt+ArrowLeft"))
        assertEquals("Ctrl+Slash", ShortcutChords.normalize("ctrl+/"))
        assertEquals("F11", ShortcutChords.normalize("f11"))
        assertEquals("", ShortcutChords.normalize(""))
        assertEquals("Ctrl+Alt+Shift+K", ShortcutChords.of("k", ctrl = true, alt = true, shift = true))
        assertEquals("Shift+,", ShortcutChords.pretty("Shift+Comma"))
        assertEquals("Ctrl+←", ShortcutChords.pretty("Ctrl+Left"))
    }

    @Test
    fun bindingsResolveAndRebind() {
        val defaults = ShortcutBindings()
        assertEquals(EditorShortcut.SPLIT, defaults.resolve("S"))
        assertEquals(EditorShortcut.SPLIT, defaults.resolve("ctrl+b"))
        assertEquals(EditorShortcut.SPLIT_ALL, defaults.resolve("Shift+S"))
        assertEquals(EditorShortcut.RIPPLE_TRIM_START, defaults.resolve("Q"))
        assertEquals(EditorShortcut.REDO, defaults.resolve("Ctrl+Y"))
        assertNull(defaults.resolve("Ctrl+Alt+Shift+F9"))
        assertEquals("S / Ctrl+B", defaults.display(EditorShortcut.SPLIT))

        // Giving Split the key that Stop used: Stop loses it, and with no other chord is unbound.
        val rebound = defaults.rebind(EditorShortcut.SPLIT, "K")
        assertEquals(EditorShortcut.SPLIT, rebound.resolve("K"))
        assertNull(rebound.resolve("S"))
        assertNull(rebound.resolve("Ctrl+B"))
        assertTrue(rebound.chords(EditorShortcut.SHUTTLE_STOP).isEmpty())
        assertEquals("—", rebound.display(EditorShortcut.SHUTTLE_STOP))

        // An action with a second chord keeps that one when its first is taken.
        val taken = defaults.rebind(EditorShortcut.ADD_TEXT, "Delete")
        assertEquals(EditorShortcut.ADD_TEXT, taken.resolve("Delete"))
        assertEquals(listOf("Backspace"), taken.chords(EditorShortcut.DELETE))
        assertEquals(EditorShortcut.DELETE, taken.resolve("Backspace"))

        // Resetting restores the defaults, and overrides survive a save / load of the plain map.
        assertEquals(EditorShortcut.SPLIT, rebound.reset(EditorShortcut.SPLIT).resolve("S"))
        val reloaded = ShortcutBindings(rebound.overrides)
        assertEquals(EditorShortcut.SPLIT, reloaded.resolve("K"))
        assertTrue(reloaded.isCustom(EditorShortcut.SPLIT))
        assertFalse(reloaded.isCustom(EditorShortcut.UNDO))

        // Unbinding leaves the action with no key at all.
        val unbound = defaults.rebind(EditorShortcut.GRADE_SPLIT, "")
        assertNull(unbound.resolve("Backslash"))
        // Junk in stored overrides is ignored rather than breaking the map.
        assertEquals(EditorShortcut.UNDO, ShortcutBindings(mapOf("no_such_action" to "Ctrl+Z")).resolve("Ctrl+Z"))
    }

    // ------------------------------------------------------------ project store

    private fun tempDir(): File = Files.createTempDirectory("editpro-store").toFile()

    @Test
    fun projectsLiveInTheirOwnFolders() {
        val root = tempDir()
        try {
            val store = ProjectStore(root)
            assertTrue(store.list().isEmpty())

            val a = store.create("Holiday", ProjectSettings(1080, 1920, 30), nowMs = 1_000L)
            val b = store.create("Holiday", nowMs = 2_000L)
            assertEquals("Holiday", a.name)
            assertEquals("Holiday 2", b.name)
            assertNotEquals(a.id, b.id)
            assertEquals(listOf(b.id, a.id), store.list().map { it.id })
            assertEquals(1080, store.summary(a.id)!!.width)

            // Media imported into a project is stored inside that project's folder.
            val media = File(store.mediaDir(a.id), "clip.mp4").apply { writeBytes(ByteArray(4096)) }
            assertTrue(store.owns(a.id, media.absolutePath))
            assertFalse(store.owns(b.id, media.absolutePath))
            assertFalse(store.owns(a.id, null))

            val asset = MediaAsset(
                id = "v", name = "clip.mp4", kind = AssetKind.VIDEO, durationSec = 3.0,
                localPath = media.absolutePath, waveformPeaks = listOf(0.25f, 0.5f),
                filmstripPaths = listOf(File(store.cacheDir(a.id), "t0.jpg").absolutePath)
            )
            val clip = TimelineClip(id = "c", assetId = "v", trimOut = 3.0)
            val json = ProjectSerializer.serializeProject("Holiday", ProjectSettings(1080, 1920, 30), listOf(clip), emptyList(), listOf(asset))
            store.save(a.id, json, "Holiday", 1, 1, 3.0, ProjectSettings(1080, 1920, 30), media.absolutePath, nowMs = 5_000L)

            val saved = store.summary(a.id)!!
            assertEquals(1, saved.clipCount)
            assertEquals(3.0, saved.durationSec, 1e-9)
            assertEquals(1_000L, saved.createdAtMs)
            assertEquals(5_000L, saved.modifiedAtMs)
            assertTrue(saved.sizeBytes >= 4096L)
            assertEquals(media.absolutePath, saved.thumbnailPath)
            assertEquals(a.id, store.list().first().id)
            assertFalse(File(store.projectDir(a.id), "project.filmcraft.tmp").exists())

            // A duplicate gets its own copy of the media and points at it.
            val copy = store.duplicate(a.id, nowMs = 6_000L)!!
            assertEquals("Holiday copy", copy.name)
            val copyDoc = ProjectSerializer.deserializeProject(store.load(copy.id)!!)
            assertEquals("Holiday copy", copyDoc.name)
            val copiedPath = copyDoc.assets.single().localPath!!
            assertTrue(store.owns(copy.id, copiedPath))
            assertTrue(File(copiedPath).isFile)
            assertTrue(store.owns(copy.id, copyDoc.assets.single().filmstripPaths.single()))
            assertTrue(store.owns(copy.id, copy.thumbnailPath))

            assertTrue(store.rename(copy.id, "Road trip"))
            assertEquals("Road trip", store.summary(copy.id)!!.name)
            assertEquals("Road trip", ProjectSerializer.deserializeProject(store.load(copy.id)!!).name)
            assertFalse(store.rename(copy.id, "   "))

            // Deleting a project removes its media and leaves the others intact.
            assertTrue(store.delete(a.id))
            assertFalse(media.exists())
            assertNull(store.load(a.id))
            assertTrue(File(copiedPath).isFile)
            assertEquals(2, store.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectIdsCannotEscapeTheStore() {
        val root = tempDir()
        try {
            val store = ProjectStore(root)
            val outside = File(root.parentFile, "editpro-outside-${System.nanoTime()}").apply { mkdirs() }
            try {
                assertFalse(ProjectStore.isValidId("../${outside.name}"))
                assertFalse(ProjectStore.isValidId(""))
                assertFalse(ProjectStore.isValidId("a/b"))
                assertTrue(ProjectStore.isValidId("abc_DEF-123"))
                assertFalse(store.delete("../${outside.name}"))
                assertTrue(outside.exists())
                assertNull(store.load("../${outside.name}"))
                assertFalse(store.exists(".."))
            } finally {
                outside.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- file format

    @Test
    fun projectFileKeepsTheNewFields() {
        val clip = TimelineClip(
            id = "g", assetId = null, track = TimelineTrack.GRAPHICS, trimOut = 5.0,
            text = "Your Name\nRole", graphic = "lower_third", accentColor = "#ff3b30", boxW = 55.0, boxH = 9.5,
            keyframes = listOf(Keyframe(time = 0.0, easing = "hold"), Keyframe(time = 1.0))
        )
        val asset = MediaAsset(
            id = "m", name = "song.mp3", kind = AssetKind.AUDIO, durationSec = 9.0, durationGuess = true,
            waveformPeaks = listOf(0.1f, 0.75f, 1.0f), filmstripPaths = listOf("/x/a.jpg", "/x/b.jpg")
        )
        val states = mapOf(
            TimelineTrack.GRAPHICS to TrackState(locked = true, collapsed = true),
            TimelineTrack.AUDIO to TrackState(muted = true, solo = true)
        )
        val doc = ProjectSerializer.deserializeProject(
            ProjectSerializer.serializeProject("P", ProjectSettings(), listOf(clip), emptyList(), listOf(asset), states)
        )
        val restored = doc.clips.single()
        assertEquals(TimelineTrack.GRAPHICS, restored.track)
        assertEquals("lower_third", restored.graphic)
        assertTrue(restored.isGraphic)
        assertEquals("#ff3b30", restored.accentColor)
        assertEquals(55.0, restored.boxW, 1e-9)
        assertEquals(9.5, restored.boxH, 1e-9)
        assertEquals("hold", restored.keyframes[0].easing)
        assertEquals("", restored.keyframes[1].easing)

        val restoredAsset = doc.assets.single()
        assertTrue(restoredAsset.durationGuess)
        assertEquals(3, restoredAsset.waveformPeaks.size)
        assertEquals(0.75f, restoredAsset.waveformPeaks[1], 1e-3f)
        assertEquals(listOf("/x/a.jpg", "/x/b.jpg"), restoredAsset.filmstripPaths)

        assertEquals(TrackState(locked = true, collapsed = true), doc.trackStates[TimelineTrack.GRAPHICS])
        assertEquals(TrackState(muted = true, solo = true), doc.trackStates[TimelineTrack.AUDIO])

        // A version 2 file has none of these keys and must still open with sensible defaults.
        val legacy = """{"format":"filmcraft-project","version":2,"name":"Old","clips":[{"id":"c","track":"text",
            "assetId":null,"trimOut":4,"keyframes":[{"time":0,"scale":120}]}],"assets":[{"id":"a","name":"x.mp4","kind":"video"}]}"""
        val old = ProjectSerializer.deserializeProject(legacy)
        assertEquals("", old.clips.single().graphic)
        assertEquals("", old.clips.single().keyframes.single().easing)
        assertTrue(old.trackStates.isEmpty())
        assertTrue(old.assets.single().waveformPeaks.isEmpty())
    }

    @Test
    fun retimedClipsOccupyTheirPlaybackLength() {
        val asset = MediaAsset(id = "v", name = "v.mp4", kind = AssetKind.VIDEO, durationSec = 20.0)
        val fast = TimelineClip(id = "f", assetId = "v", startAt = 0.0, trimIn = 2.0, trimOut = 10.0, speed = 2.0)
        val slow = fast.copy(id = "s", startAt = 10.0, speed = 0.5)
        val resolved = TimelineEngine.starts(listOf(fast, slow), mapOf("v" to asset))
        assertEquals(4.0, resolved[0].duration, 1e-9)
        assertEquals(16.0, resolved[1].duration, 1e-9)
        assertEquals(26.0, TimelineEngine.calcProjectDuration(listOf(fast, slow), mapOf("v" to asset)), 1e-9)
    }

    // ----------------------------------------------------------- motion graphics

    @Test
    fun motionGraphicsCatalogIsConsistent() {
        assertTrue(MotionGraphics.templates.size >= 15)
        assertEquals(MotionGraphics.templates.size, MotionGraphics.templates.map { it.id }.distinct().size)
        for (t in MotionGraphics.templates) {
            assertTrue("${t.id} must sit on a title track", t.track.isTitle)
            assertTrue(t.defaultDuration > 0.0)
            assertNotNull(MotionGraphics.find(t.id))
        }
        assertNull(MotionGraphics.find("nope"))

        val lowerThird = MotionGraphics.createClip(MotionGraphics.find("lower_third")!!, 12.0, 60.0)
        assertEquals(12.0, lowerThird.startAt, 1e-9)
        assertEquals(5.0, lowerThird.trimOut, 1e-9)
        assertNull(lowerThird.assetId)
        assertTrue(lowerThird.isGraphic)

        // Bars and progress run under the whole edit, wherever the playhead is.
        val bars = MotionGraphics.createClip(MotionGraphics.find("letterbox")!!, 12.0, 60.0)
        assertEquals(0.0, bars.startAt, 1e-9)
        assertEquals(60.0, bars.trimOut, 1e-9)
        val emptyTimeline = MotionGraphics.createClip(MotionGraphics.find("letterbox")!!, 3.0, 0.0)
        assertEquals(3.0, emptyTimeline.startAt, 1e-9)
        assertEquals(10.0, emptyTimeline.trimOut, 1e-9)
    }

    @Test
    fun motionGraphicsTiming() {
        // Presence rises through the intro, holds, and falls through the outro.
        assertEquals(0.0, MgMath.presence(0.0, 4.0, 0.5), 1e-9)
        assertEquals(0.5, MgMath.presence(0.25, 4.0, 0.5), 1e-9)
        assertEquals(1.0, MgMath.presence(2.0, 4.0, 0.5), 1e-9)
        assertEquals(0.5, MgMath.presence(3.75, 4.0, 0.5), 1e-9)
        assertEquals(0.0, MgMath.presence(4.0, 4.0, 0.5), 1e-9)
        // On a clip too short for both, the two share it and still reach full presence.
        assertEquals(1.0, MgMath.presence(0.2, 0.4, 0.5), 1e-9)
        assertEquals(0.2 to 0.2, MgMath.fitSpans(0.4, 0.5, 0.5))

        assertEquals(0.0, MgMath.easeOutCubic(0.0), 1e-9)
        assertEquals(1.0, MgMath.easeOutCubic(1.0), 1e-9)
        assertEquals(0.5, MgMath.easeInOutCubic(0.5), 1e-9)
        assertEquals(1.0, MgMath.easeOutBack(1.0), 1e-9)
        assertTrue(MgMath.easeOutBack(0.7) > 1.0)

        assertEquals(1.0, MgMath.stagger(0.5, 0, 3), 1e-9)
        assertEquals(0.5, MgMath.stagger(0.5, 1, 3), 1e-9)
        assertEquals(0.0, MgMath.stagger(0.5, 2, 3), 1e-9)
        assertEquals(1.0, MgMath.stagger(1.0, 2, 3), 1e-9)

        // Typing finishes at 70% of the clip so the line can be read.
        assertEquals(0, MgMath.typewriterCount(10, 0.0, 10.0))
        assertEquals(5, MgMath.typewriterCount(10, 3.5, 10.0))
        assertEquals(10, MgMath.typewriterCount(10, 7.0, 10.0))
        assertEquals(10, MgMath.typewriterCount(10, 9.9, 10.0))
        assertEquals(0, MgMath.typewriterCount(0, 1.0, 10.0))

        assertEquals(3, MgMath.countdownValue(0.0, 3.0))
        assertEquals(3, MgMath.countdownValue(0.99, 3.0))
        assertEquals(2, MgMath.countdownValue(1.0, 3.0))
        assertEquals(1, MgMath.countdownValue(2.5, 3.0))
        assertEquals(1, MgMath.countdownValue(3.0, 3.0))
        assertEquals(0.25, MgMath.countdownSweep(1.25), 1e-9)

        assertEquals(0.0, MgMath.counterValue(1000.0, 0.0, 4.0), 1e-9)
        assertEquals(1000.0, MgMath.counterValue(1000.0, 3.0, 4.0), 1e-9)
        assertEquals(1250.0, MgMath.parseTarget("1,250 km")!!, 1e-9)
        assertEquals(4.5, MgMath.parseTarget("Rated 4.5 stars")!!, 1e-9)
        assertNull(MgMath.parseTarget("Followers"))
        assertEquals("625 km", MgMath.formatCounter(625.0, "1250 km"))
        assertEquals("1,250 km", MgMath.formatCounter(1250.0, "1,250 km"))
        assertEquals("Rated 2.0 stars", MgMath.formatCounter(2.0, "Rated 4.5 stars"))

        assertEquals("0:00.0", MgMath.timerText(0.0))
        assertEquals("1:05.5", MgMath.timerText(65.5))

        // Credits start just below the frame and end just above it.
        assertEquals(720.0, MgMath.creditsOffset(0.0, 10.0, 400.0, 720.0), 1e-9)
        assertEquals(-400.0, MgMath.creditsOffset(10.0, 10.0, 400.0, 720.0), 1e-9)
    }

    // -------------------------------------------------------------------- audio

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)

    private fun sine(freq: Double, amplitude: Float = 0.25f, sampleRate: Int = 44100, seconds: Double = 0.5): FloatArray =
        FloatArray((sampleRate * seconds).toInt()) { i -> (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat() }

    private fun run(chain: AudioDsp.ClipChain, input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            chain.process(input[i], input[i])
            out[i] = chain.outL
        }
        // Skip the first 50 ms so filter and compressor settling does not skew the measurement.
        return out.copyOfRange(2205, out.size)
    }

    @Test
    fun equaliserBoostsOnlyItsBand() {
        val flat = AudioDsp.ClipChain(44100, listOf(0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
        assertTrue(flat.isBypassed)

        val boost = AudioDsp.ClipChain(44100, listOf(0.0, 0.0, 12.0, 0.0, 0.0), 0.0)
        assertFalse(boost.isBypassed)
        val tone = sine(1000.0)
        val gain = rms(run(boost, tone)) / rms(tone.copyOfRange(2205, tone.size))
        // +12 dB is a factor of 3.98.
        assertEquals(3.98, gain, 0.15)

        // A tone four octaves below the boosted band passes almost untouched.
        val low = sine(60.0)
        val lowGain = rms(run(AudioDsp.ClipChain(44100, listOf(0.0, 0.0, 12.0, 0.0, 0.0), 0.0), low)) /
            rms(low.copyOfRange(2205, low.size))
        assertEquals(1.0, lowGain, 0.08)

        val cut = AudioDsp.ClipChain(44100, listOf(0.0, 0.0, -12.0, 0.0, 0.0), 0.0)
        assertEquals(0.25, rms(run(cut, tone)) / rms(tone.copyOfRange(2205, tone.size)), 0.03)
    }

    @Test
    fun compressorNarrowsTheDynamicRange() {
        val loud = sine(440.0, amplitude = 0.9f)
        val quiet = sine(440.0, amplitude = 0.02f)
        val before = rms(loud) / rms(quiet)
        val after = rms(run(AudioDsp.ClipChain(44100, List(5) { 0.0 }, 80.0), loud)) /
            rms(run(AudioDsp.ClipChain(44100, List(5) { 0.0 }, 80.0), quiet))
        assertTrue("compression should reduce the loud/quiet ratio ($before -> $after)", after < before * 0.6)
        // Quiet material below the threshold is not squashed, only lifted by the make-up gain.
        val quietOut = rms(run(AudioDsp.ClipChain(44100, List(5) { 0.0 }, 80.0), quiet))
        assertTrue(quietOut >= rms(quiet.copyOfRange(2205, quiet.size)))
    }

    @Test
    fun panAndLimiter() {
        assertEquals(1f to 1f, AudioDsp.panGains(0.0))
        assertEquals(1f to 0f, AudioDsp.panGains(-100.0))
        assertEquals(0f to 1f, AudioDsp.panGains(100.0))
        assertEquals(0.5f to 1f, AudioDsp.panGains(50.0))
        assertEquals(1f to 0.75f, AudioDsp.panGains(-25.0))
        assertEquals(0f to 1f, AudioDsp.panGains(400.0))

        assertEquals(0.5f, AudioDsp.softLimit(0.5f), 1e-6f)
        assertEquals(-0.9f, AudioDsp.softLimit(-0.9f), 1e-6f)
        assertTrue(AudioDsp.softLimit(3f) < 1f)
        assertTrue(AudioDsp.softLimit(-3f) > -1f)
        assertTrue(abs(AudioDsp.softLimit(1.2f)) > 0.9f)
        assertTrue(AudioDsp.softLimit(1.5f) > AudioDsp.softLimit(1.1f))
    }
}
