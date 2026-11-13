package com.editpro.studio

import com.editpro.studio.media.TimelineAudioEncoder
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.ClipVoice
import com.editpro.studio.model.PcmFeed
import com.editpro.studio.model.PcmWindow
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.VolumePoint
import com.editpro.studio.model.WindowedPcmFeed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The export's sound mix, checked without a codec: the conversions that turn decoded audio into
 * 16-bit stereo, the window that lets a file of any length be read in a small buffer, and the
 * placing, retiming and processing of a clip in the mixed stream.
 */
class AudioMixTest {

    // ------------------------------------------------------------ test feeds

    /** A whole stream held in arrays. */
    private class ArrayFeed(
        val leftData: ShortArray,
        val rightData: ShortArray,
        override val sampleRate: Int,
        override val startSec: Double = 0.0
    ) : PcmFeed {
        var released = -1L
        override fun ensure(frame: Long) = frame >= 0 && frame < leftData.size
        override fun left(frame: Long) = leftData[frame.toInt()]
        override fun right(frame: Long) = rightData[frame.toInt()]
        override fun release(frame: Long) {
            released = frame
        }
    }

    /** The same kind of stream handed over a chunk at a time, the way a decoder does. */
    private class ChunkedFeed(
        private val source: ShortArray,
        private val channels: Int,
        private val chunk: Int,
        override val sampleRate: Int,
        override val startSec: Double = 0.0
    ) : WindowedPcmFeed() {
        private var at = 0
        var mostHeld = 0
            private set

        override fun pump() {
            if (at >= source.size) {
                ended = true
                return
            }
            val count = min(chunk, source.size - at)
            window.append(source.copyOfRange(at, at + count), count, channels)
            at += count
            mostHeld = max(mostHeld, window.size)
        }
    }

    private fun stereoSine(frames: Int, rate: Int, leftHz: Double, rightHz: Double): ShortArray =
        ShortArray(frames * 2) { i ->
            val frame = i / 2
            val hz = if (i % 2 == 0) leftHz else rightHz
            (12000.0 * sin(2.0 * PI * hz * frame / rate)).toInt().toShort()
        }

    // ------------------------------------------------------ decoded layouts

    @Test
    fun decodedSoundOfAnyLayoutBecomes16Bit() {
        val out = ShortArray(64)

        val plain = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        shortArrayOf(1, -2, 32767, -32768).forEach { plain.putShort(it) }
        plain.flip()
        assertEquals(4, AudioDsp.pcmToShorts(plain, 8, AudioDsp.PCM_16BIT, out))
        assertArrayEquals(shortArrayOf(1, -2, 32767, -32768), out.copyOf(4))

        // An unknown layout is read as 16-bit, which is what a missing key means.
        plain.position(0)
        assertEquals(4, AudioDsp.pcmToShorts(plain, 8, 0, out))
        assertEquals(32767.toShort(), out[2])

        // Reading starts at the buffer's position, not at its beginning.
        plain.position(4)
        assertEquals(2, AudioDsp.pcmToShorts(plain, 4, AudioDsp.PCM_16BIT, out))
        assertArrayEquals(shortArrayOf(32767, -32768), out.copyOf(2))

        val bytes = ByteBuffer.wrap(byteArrayOf(128.toByte(), 255.toByte(), 0))
        assertEquals(3, AudioDsp.pcmToShorts(bytes, 3, AudioDsp.PCM_8BIT, out))
        assertArrayEquals(shortArrayOf(0, 32512, -32768), out.copyOf(3))

        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0.5f, -1f, 4f).forEach { floats.putFloat(it) }
        floats.flip()
        assertEquals(4, AudioDsp.pcmToShorts(floats, 16, AudioDsp.PCM_FLOAT, out))
        assertArrayEquals(shortArrayOf(0, 16383, -32767, 32767), out.copyOf(4))

        // 24-bit: 0x123456 and -2 (0xFFFFFE), least significant byte first.
        val packed = ByteBuffer.wrap(byteArrayOf(0x56, 0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, AudioDsp.pcmToShorts(packed, 6, AudioDsp.PCM_24BIT_PACKED, out))
        assertArrayEquals(shortArrayOf(0x1234, -1), out.copyOf(2))
        val packedBig = ByteBuffer.wrap(byteArrayOf(0x12, 0x34, 0x56)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, AudioDsp.pcmToShorts(packedBig, 3, AudioDsp.PCM_24BIT_PACKED, out))
        assertEquals(0x1234.toShort(), out[0])

        val wide = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        wide.putInt(0x12345678).putInt(-65536)
        wide.flip()
        assertEquals(2, AudioDsp.pcmToShorts(wide, 8, AudioDsp.PCM_32BIT, out))
        assertArrayEquals(shortArrayOf(0x1234, -1), out.copyOf(2))
    }

    @Test
    fun surroundFoldsDownWithTheDialogue() {
        val (monoL, monoR) = AudioDsp.downmixGains(1)
        assertEquals(1f, monoL[0], 0f)
        assertEquals(1f, monoR[0], 0f)
        val (stereoL, stereoR) = AudioDsp.downmixGains(2)
        assertArrayEquals(floatArrayOf(1f, 0f), stereoL, 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), stereoR, 0f)

        // 5.1: front left, front right, centre, LFE, back left, back right.
        val (left, right) = AudioDsp.downmixGains(6)
        assertEquals(0.5f, left[0], 1e-6f)
        assertEquals(0f, right[0], 0f)
        assertEquals(0f, left[1], 0f)
        assertEquals(0.5f, right[1], 1e-6f)
        // The centre, where dialogue lives, goes to both sides equally.
        assertEquals(left[2], right[2], 0f)
        assertTrue(left[2] > 0.3f)
        // The LFE channel is left out.
        assertEquals(0f, left[3], 0f)
        assertEquals(0f, right[3], 0f)
        // Each rear channel stays on its own side.
        assertTrue(left[4] > 0.3f && right[4] == 0f)
        assertTrue(right[5] > 0.3f && left[5] == 0f)

        // Every layout up to 7.1 keeps both sides and never sends a channel out louder than it came in.
        for (n in 1..8) {
            val (l, r) = AudioDsp.downmixGains(n)
            assertEquals(n, l.size)
            assertEquals(n, r.size)
            assertTrue(l.sum() > 0f && r.sum() > 0f)
            assertTrue(l.all { it in 0f..1f } && r.all { it in 0f..1f })
        }

        // Through the window: one 5.1 frame with sound only in the centre, one only in the LFE,
        // one only at the front left.
        val window = PcmWindow()
        val frames = shortArrayOf(
            0, 0, 10000, 0, 0, 0,
            0, 0, 0, 10000, 0, 0,
            10000, 0, 0, 0, 0, 0
        )
        window.append(frames, frames.size, 6)
        assertEquals(3, window.size)
        assertEquals(3535.0, window.left(0).toDouble(), 2.0)
        assertEquals(window.left(0), window.right(0))
        assertEquals(0.toShort(), window.left(1))
        assertEquals(0.toShort(), window.right(1))
        assertEquals(5000.toShort(), window.left(2))
        assertEquals(0.toShort(), window.right(2))

        // A fold-down that would overflow is held at full scale instead of wrapping round.
        val loud = PcmWindow()
        loud.append(shortArrayOf(32767, 0, 32767, 0, 32767, 0, 32767, 0), 8, 8)
        assertEquals(32767.toShort(), loud.left(0))
    }

    // ------------------------------------------------------------ the window

    @Test
    fun windowKeepsItsPlaceInTheStream() {
        val window = PcmWindow()
        assertEquals(0, window.size)
        assertFalse(window.has(0))

        // Mono goes to both sides.
        window.append(shortArrayOf(10, 20, 30), 3, 1)
        assertEquals(3, window.size)
        assertEquals(20.toShort(), window.left(1))
        assertEquals(20.toShort(), window.right(1))

        // Only the first `count` samples of the array belong to the chunk.
        window.append(shortArrayOf(40, 41, 50, 51, 99, 99), 4, 2)
        assertEquals(5, window.size)
        assertEquals(40.toShort(), window.left(3))
        assertEquals(51.toShort(), window.right(4))

        // Dropping the front does not renumber what is left.
        window.discardBefore(3)
        assertEquals(3L, window.first)
        assertEquals(2, window.size)
        assertEquals(5L, window.end)
        assertFalse(window.has(2))
        assertTrue(window.has(3))
        assertEquals(41.toShort(), window.right(3))
        assertEquals(50.toShort(), window.left(4))

        // Dropping something already gone changes nothing; dropping past the end empties it and
        // the stream carries on from where it was.
        window.discardBefore(1)
        assertEquals(2, window.size)
        window.discardBefore(100)
        assertEquals(0, window.size)
        assertEquals(5L, window.first)
        window.append(shortArrayOf(60, 61), 2, 2)
        assertTrue(window.has(5))
        assertEquals(60.toShort(), window.left(5))

        // A frame split across two chunks is put back together rather than shifting channels.
        val split = PcmWindow()
        split.append(shortArrayOf(1, 2, 3), 3, 2)
        assertEquals(1, split.size)
        split.append(shortArrayOf(4, 5, 6), 3, 2)
        assertEquals(3, split.size)
        split.append(shortArrayOf(7), 1, 2)
        assertEquals(3, split.size)
        split.append(shortArrayOf(8), 1, 2)
        assertEquals(4, split.size)
        for (frame in 0 until 4) {
            assertEquals((frame * 2 + 1).toShort(), split.left(frame.toLong()))
            assertEquals((frame * 2 + 2).toShort(), split.right(frame.toLong()))
        }

        // A long stream stays intact as the buffer grows and is trimmed.
        val long = PcmWindow()
        var next = 0
        val chunk = ShortArray(3000)
        for (round in 0 until 40) {
            for (i in chunk.indices) chunk[i] = (next++ % 30011).toShort()
            long.append(chunk, chunk.size, 2)
            if (round % 3 == 2) long.discardBefore(long.end - 500)
        }
        for (frame in long.first until long.end) {
            assertEquals(((frame * 2) % 30011).toShort(), long.left(frame))
            assertEquals(((frame * 2 + 1) % 30011).toShort(), long.right(frame))
        }
    }

    @Test
    fun aFarSeekDoesNotPileUpMemory() {
        // 200,000 mono frames, each holding its own position, handed over 1,000 at a time.
        val source = ShortArray(200_000) { (it % 29989).toShort() }
        val feed = ChunkedFeed(source, channels = 1, chunk = 1000, sampleRate = 48000)

        assertTrue(feed.ensure(150_000))
        assertEquals((150_000 % 29989).toShort(), feed.left(150_000))
        // The frame before the one asked for is kept for the mixer's interpolation.
        assertTrue(feed.ensure(149_999))
        assertEquals((149_999 % 29989).toShort(), feed.right(149_999))
        // Getting there never held more than a fraction of a second of sound.
        assertTrue("held ${feed.mostHeld} frames", feed.mostHeld < 20_000)

        // Walking on works frame by frame, and release lets the past go.
        for (frame in 150_000L until 150_500L) {
            assertTrue(feed.ensure(frame + 1))
            assertEquals((frame % 29989).toShort(), feed.left(frame))
        }
        feed.release(150_400)
        assertFalse(feed.ensure(150_399))
        assertTrue(feed.ensure(150_400))

        // Past the end of the stream there is nothing, and asking does not hang.
        assertFalse(feed.ensure(200_000))
        assertFalse(feed.ensure(5_000_000))
        assertTrue(feed.ensure(199_999))

        // The mixer reads a frame and then its neighbour. When the neighbour is the first frame
        // of the next chunk, fetching it must not let go of the frame before.
        val edge = ChunkedFeed(source, channels = 1, chunk = 20_000, sampleRate = 48000)
        assertTrue(edge.ensure(19_999))
        assertTrue(edge.ensure(20_000))
        assertTrue(edge.ensure(19_999))
        assertEquals((19_999 % 29989).toShort(), edge.left(19_999))
        assertEquals((20_000 % 29989).toShort(), edge.left(20_000))
    }

    // ------------------------------------------------------------- the clip

    private fun mixWhole(voice: ClipVoice, feed: PcmFeed, totalFrames: Int, blockFrames: Int): FloatArray {
        val out = FloatArray(totalFrames * 2)
        val block = FloatArray(blockFrames * 2)
        var position = 0
        while (position < totalFrames) {
            val frames = min(blockFrames, totalFrames - position)
            java.util.Arrays.fill(block, 0f)
            voice.mixInto(feed, block, position.toLong(), frames)
            System.arraycopy(block, 0, out, position * 2, frames * 2)
            position += frames
        }
        return out
    }

    @Test
    fun clipTimingFollowsTrimSpeedAndTheRenderRange() {
        // A source whose every frame holds its own position, so the output shows exactly which
        // part of the file was read: 48 kHz, and its first frame sits at 0.25 s of source time.
        val ramp = ShortArray(30_000) { it.toShort() }
        val feed = ArrayFeed(ramp, ramp, sampleRate = 48000, startSec = 0.25)

        // The clip starts at 1.0 s on the timeline and plays from 0.4 s of its source at one and
        // a half times speed for a quarter of a second. The render starts at 0.5 s.
        val clip = TimelineClip(track = TimelineTrack.AUDIO, trimIn = 0.4, speed = 1.5, volume = 50.0, audioPan = 50.0)
        val rate = 44100
        val total = rate
        val voice = ClipVoice(clip, clipStartSec = 1.0, clipDurationSec = 0.25, originSec = 0.5, totalFrames = total.toLong(), outputRate = rate)
        assertEquals(22050L, voice.firstFrame)
        assertEquals(33075L, voice.endFrame)
        assertEquals(0.4, voice.sourceStartSec, 1e-9)

        val out = FloatArray(total * 2)
        assertTrue(voice.mixInto(feed, out, 0, total))

        // Nothing before the clip or after it.
        for (n in 0 until 22050) assertEquals(0f, out[n * 2], 0f)
        for (n in 33075 until total) assertEquals(0f, out[n * 2 + 1], 0f)

        // Inside it, output frame n reads source time 0.4 + 1.5 * localT, which is frame
        // (that - 0.25) * 48000 of the feed. Volume 50% and a pan of +50 leave the right side
        // at 0.5 and the left at 0.25.
        for (n in listOf(22050, 22051, 23000, 26460, 30000, 33074)) {
            val localT = 0.5 + n.toDouble() / rate - 1.0
            val sourceFrame = (0.4 + 1.5 * localT - 0.25) * 48000
            assertEquals("frame $n", sourceFrame / 32768.0 * 0.5, out[n * 2 + 1].toDouble(), 1e-3)
            assertEquals("frame $n", sourceFrame / 32768.0 * 0.25, out[n * 2].toDouble(), 1e-3)
        }
        // The feed was told what it no longer needs: everything before the last frame read.
        assertTrue("released ${feed.released}", feed.released in 25_100L..25_200L)

        // A render that starts part-way through the clip begins decoding where the clip is by then.
        val late = ClipVoice(clip, clipStartSec = 1.0, clipDurationSec = 0.25, originSec = 1.0625, totalFrames = total.toLong(), outputRate = rate)
        assertEquals(0L, late.firstFrame)
        assertEquals(0.49375, late.sourceStartSec, 1e-9)
        val lateOut = FloatArray(total * 2)
        late.mixInto(feed, lateOut, 0, total)
        assertEquals((0.49375 - 0.25) * 48000 / 32768.0 * 0.5, lateOut[1].toDouble(), 1e-3)

        // A clip that lies outside the render adds nothing.
        val outside = ClipVoice(clip, clipStartSec = 5.0, clipDurationSec = 0.25, originSec = 0.5, totalFrames = total.toLong(), outputRate = rate)
        assertTrue(outside.endFrame <= outside.firstFrame)
        assertFalse(outside.mixInto(feed, FloatArray(total * 2), 0, total))

        // Where the file runs out before the clip does, the rest of the clip is silent.
        val short = ArrayFeed(ShortArray(100) { 1000 }, ShortArray(100) { 1000 }, sampleRate = 44100)
        val plain = TimelineClip(track = TimelineTrack.AUDIO)
        val overrun = ClipVoice(plain, 0.0, 1.0, 0.0, total.toLong(), rate)
        val overrunOut = FloatArray(total * 2)
        overrun.mixInto(short, overrunOut, 0, total)
        assertEquals(1000 / 32768f, overrunOut[50 * 2], 1e-6f)
        assertEquals(1000 / 32768f, overrunOut[98 * 2], 1e-6f)
        assertEquals(0f, overrunOut[102 * 2], 0f)
        assertEquals(0f, overrunOut[5000 * 2], 0f)
    }

    @Test
    fun fadesAndAutomationShapeTheClip() {
        val flat = ShortArray(60_000) { 16384 }
        val feed = ArrayFeed(flat, flat, sampleRate = 44100)
        val rate = 44100
        val clip = TimelineClip(
            track = TimelineTrack.AUDIO,
            fadeInAudio = 0.2,
            fadeOutAudio = 0.2,
            volumeKeyframes = listOf(VolumePoint(0.4, 100.0), VolumePoint(0.6, 0.0))
        )
        val voice = ClipVoice(clip, 0.0, 1.0, 0.0, rate.toLong(), rate)
        val out = FloatArray(rate * 2)
        voice.mixInto(feed, out, 0, rate)
        // Half-way up the fade-in, then full level, then the automation takes it to silence.
        assertEquals(0.25, out[4410 * 2].toDouble(), 0.01)
        assertEquals(0.5, out[13230 * 2].toDouble(), 0.01)
        assertEquals(0.25, out[22050 * 2].toDouble(), 0.01)
        assertEquals(0.0, out[30870 * 2].toDouble(), 0.01)

        // Automation points stored out of order mean the same as the same points in order.
        val shuffled = clip.copy(volumeKeyframes = clip.volumeKeyframes.reversed())
        for (t in listOf(0.0, 0.3, 0.4, 0.45, 0.5, 0.59, 0.6, 0.7)) {
            assertEquals(TimelineEngine.automationGain(clip, t), TimelineEngine.automationGain(shuffled, t), 0.0)
        }
        assertEquals(0.5, TimelineEngine.automationGain(shuffled, 0.5), 1e-9)
    }

    @Test
    fun aClipIsMixedTheSameHoweverTheStreamIsCut() {
        val rate = 44100
        val total = (rate * 1.5).toInt()
        // Two seconds of stereo at 48 kHz, a different tone on each side.
        val source = stereoSine(96_000, 48000, 440.0, 660.0)

        // Every stateful part is switched on: EQ, compressor, fades, automation, pan, a retime.
        val clip = TimelineClip(
            track = TimelineTrack.AUDIO,
            trimIn = 0.3,
            speed = 0.75,
            volume = 120.0,
            audioPan = -30.0,
            audioEq = listOf(3.0, 0.0, -6.0, 4.0, 0.0),
            audioComp = 60.0,
            fadeInAudio = 0.25,
            fadeOutAudio = 0.4,
            volumeKeyframes = listOf(VolumePoint(0.2, 100.0), VolumePoint(0.9, 40.0))
        )
        fun voice() = ClipVoice(clip, clipStartSec = 0.2, clipDurationSec = 1.1, originSec = 0.0, totalFrames = total.toLong(), outputRate = rate)

        // The reference: the whole stream in memory, mixed in one go.
        val left = ShortArray(96_000) { source[it * 2] }
        val right = ShortArray(96_000) { source[it * 2 + 1] }
        val reference = mixWhole(voice(), ArrayFeed(left, right, 48000), total, total)
        assertTrue(reference.any { abs(it) > 0.05f })
        assertEquals(0f, reference[0], 0f)

        // The same clip fed by a decoder-like stream in awkward chunk sizes, a tenth of a second
        // at a time and then in small odd blocks, must come out sample for sample the same.
        for ((chunk, blockFrames) in listOf(2048 to 4410, 4096 to 777, 999 to 1, 50_000 to 10_000)) {
            val feed = ChunkedFeed(source, channels = 2, chunk = chunk, sampleRate = 48000)
            val mixed = mixWhole(voice(), feed, total, blockFrames)
            assertArrayEquals("chunk $chunk, block $blockFrames", reference, mixed, 0f)
            // Only a short stretch of the two-second file was ever held: what one block reads,
            // one chunk, and the run-up to the clip's first frame.
            assertTrue("held ${feed.mostHeld} frames", feed.mostHeld < 17_000 + chunk + blockFrames)
        }
    }

    @Test
    fun slowedAndLowRateSourcesReuseFramesAcrossBlocks() {
        // Quarter speed from an 8 kHz mono file: every source frame is read many times over, so
        // a block often starts on the frame the last one ended on.
        val rate = 44100
        val total = rate / 2
        val mono = ShortArray(8000) { (8000.0 * sin(2.0 * PI * 200.0 * it / 8000)).toInt().toShort() }
        val clip = TimelineClip(track = TimelineTrack.AUDIO, speed = 0.25, trimIn = 0.1)
        fun voice() = ClipVoice(clip, 0.0, 0.5, 0.0, total.toLong(), rate)

        val reference = mixWhole(voice(), ArrayFeed(mono, mono, 8000), total, total)
        // 100 output frames are about four and a half source frames, so blocks start mid-frame.
        val feed = ChunkedFeed(mono, channels = 1, chunk = 256, sampleRate = 8000)
        val streamed = mixWhole(voice(), feed, total, 100)
        assertArrayEquals(reference, streamed, 0f)
        // Mono reaches both sides alike.
        for (n in 0 until total step 97) assertEquals(streamed[n * 2], streamed[n * 2 + 1], 0f)
        assertTrue(streamed.any { abs(it) > 0.1f })
    }

    // ------------------------------------------------------- what is reported

    @Test
    fun aRenderSaysWhenSoundIsMissing() {
        // Everything in place, or nothing to hear in the first place: nothing to say.
        assertNull(TimelineAudioEncoder.soundNote(hasAudio = true, unreadableClips = 0, failed = false, cutAtSec = null))
        assertNull(TimelineAudioEncoder.soundNote(hasAudio = false, unreadableClips = 0, failed = false, cutAtSec = null))

        val broken = TimelineAudioEncoder.soundNote(hasAudio = false, unreadableClips = 0, failed = true, cutAtSec = null)
        assertTrue(broken!!.startsWith("without sound"))

        val unread = TimelineAudioEncoder.soundNote(hasAudio = false, unreadableClips = 2, failed = false, cutAtSec = null)
        assertTrue(unread!!.startsWith("without sound"))
        assertTrue(unread.contains("2 clips"))

        val partly = TimelineAudioEncoder.soundNote(hasAudio = true, unreadableClips = 1, failed = false, cutAtSec = null)
        assertTrue(partly!!.contains("1 clip "))
        assertFalse(partly.contains("without sound"))

        val cut = TimelineAudioEncoder.soundNote(hasAudio = true, unreadableClips = 3, failed = false, cutAtSec = 3600.0)
        assertTrue(cut!!.contains("3 clips"))
        assertTrue(cut.contains("60 minutes"))

        val onlyCut = TimelineAudioEncoder.soundNote(hasAudio = true, unreadableClips = 0, failed = false, cutAtSec = 3600.0)
        assertTrue(onlyCut!!.startsWith("the sound stops after 60 minutes"))
    }
}
