package com.editpro.studio

import android.media.StandInCodecs
import com.editpro.studio.media.TimelineAudioEncoder
import com.editpro.studio.model.AssetKind
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.TimelineClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.VolumePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The export's sound path from file to encoder: [TimelineAudioEncoder] run against the stand-in
 * codecs in `android/media/StandInCodecs.kt`.
 *
 * Every made-up file holds a steady tone, so what the timeline should sound like at any instant
 * can be worked out and compared, sample for sample, with what reached the encoder. A chunk that
 * is lost, repeated or placed one frame out shows as a large difference. Each mix also has to
 * give back every buffer and release every codec, whatever went wrong on the way.
 *
 * This checks the encoder's bookkeeping against the codec protocol, not real codecs; see the
 * note at the top of the stand-ins.
 */
class AudioExportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var sourceCount = 0

    @Before
    fun freshCodecs() = StandInCodecs.reset()

    // ---------------------------------------------------------- made-up media

    /** A made-up media file and the sound it holds. */
    private class Source(
        val id: String,
        val path: String,
        val kind: AssetKind,
        val seconds: Double,
        private val rate: Int,
        private val channels: Int,
        private val tone: Double,
        private val amp: Double,
        private val startSec: Double,
        private val onlyChannel: Int,
        /** Source time after which nothing is heard although the file goes on (the decoder fails there). */
        private val heardUntil: Double,
        /** Nothing can be heard from it at all: it is missing, unreadable or has no audio track. */
        private val silent: Boolean
    ) {
        private val frames = ceil(seconds * rate)
        private val fold = AudioDsp.downmixGains(channels)

        fun asset() = MediaAsset(id = id, name = id, kind = kind, durationSec = seconds, localPath = path)

        /**
         * The sound on [side] (0 left, 1 right) at source time [t], on the 16-bit scale. NaN
         * within a couple of frames of where the sound starts or stops, where the exact value
         * depends on how the edge is interpolated and is not worth pinning down.
         */
        fun value(t: Double, side: Int): Double {
            if (silent) return 0.0
            val into = t - startSec
            val edge = 2.5 / rate
            val end = minOf(frames / rate, heardUntil - startSec)
            if (into < -edge || into > end + edge) return 0.0
            if (into < edge || into > end - edge) return Double.NaN
            var sum = 0.0
            for (channel in 0 until channels) {
                if (onlyChannel >= 0 && channel != onlyChannel) continue
                val hz = if (channels == 2 && channel == 1) tone * 1.5 else tone
                val share = when {
                    channels == 1 -> 1f
                    side == 0 -> fold.first[channel]
                    else -> fold.second[channel]
                }
                sum += share * amp * sin(2.0 * PI * hz * into)
            }
            return sum
        }
    }

    /** Writes a made-up file. [extra] takes the stand-in extractor's other keys as they are. */
    private fun source(
        rate: Int = 48000,
        channels: Int = 2,
        seconds: Double = 2.0,
        tone: Double = 440.0,
        amp: Double = 8000.0,
        encoding: Int = AudioDsp.PCM_16BIT,
        packet: Int = 960,
        startUs: Long = 0L,
        onlyChannel: Int = -1,
        kind: AssetKind = AssetKind.AUDIO,
        heardUntil: Double = Double.MAX_VALUE,
        exists: Boolean = true,
        extra: Map<String, Any> = emptyMap()
    ): Source {
        val id = "source${sourceCount++}"
        val file = File(folder.root, "$id.media")
        if (exists) {
            val lines = linkedMapOf<String, Any>(
                "rate" to rate, "channels" to channels, "seconds" to seconds, "tone" to tone, "amp" to amp,
                "encoding" to encoding, "packet" to packet, "startUs" to startUs, "onlyChannel" to onlyChannel
            )
            lines.putAll(extra)
            file.writeText(lines.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        val silent = !exists || extra["audio"] == false || extra["corrupt"] == true || extra["mime"] == "audio/unsupported"
        return Source(
            id, file.absolutePath, kind, seconds, rate, channels, tone, amp,
            startUs / 1_000_000.0, onlyChannel, heardUntil, silent
        )
    }

    private class Placed(val clip: TimelineClip, val source: Source)

    private fun place(
        source: Source,
        startAt: Double = 0.0,
        trimIn: Double = 0.0,
        trimOut: Double = source.seconds,
        track: TimelineTrack = TimelineTrack.AUDIO,
        edit: (TimelineClip) -> TimelineClip = { it }
    ) = Placed(
        edit(TimelineClip(assetId = source.id, track = track, startAt = startAt, trimIn = trimIn, trimOut = trimOut)),
        source
    )

    // ------------------------------------------------------------------ mixing

    private class Mix(
        private val result: TimelineAudioEncoder.Outcome?,
        /** What the encoder threw, if it threw. */
        val thrown: Throwable?,
        /** What reached the encoder, as interleaved stereo; empty when there is no sound track. */
        val pcm: ShortArray
    ) {
        /** What the encoder returned. Asking for it is itself a check that it did not throw. */
        val outcome: TimelineAudioEncoder.Outcome
            get() = result ?: throw AssertionError("the mix threw $thrown")
    }

    /**
     * Runs the encoder over [placed] and checks what must hold for every mix: nothing is left
     * open, and a sound track is exactly as long as the render with frames in order.
     */
    private fun mix(
        placed: List<Placed>,
        from: Double,
        to: Double,
        tracks: Map<TimelineTrack, TrackState> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Mix {
        val assets = placed.map { it.source }.distinctBy { it.id }.associate { it.id to it.asset() }
        val resolved = TimelineEngine.starts(placed.map { it.clip }, assets)
        var outcome: TimelineAudioEncoder.Outcome? = null
        var thrown: Throwable? = null
        try {
            outcome = TimelineAudioEncoder.encode(resolved, assets, tracks, from, to, onProgress)
        } catch (e: Throwable) {
            thrown = e
        }

        assertEquals("codecs left open", 0, StandInCodecs.openCodecs)
        assertEquals("extractors left open", 0, StandInCodecs.openExtractors)
        assertEquals("encoder input with the wrong timestamp", 0, StandInCodecs.encoderTimestampErrors)
        // Only the last buffer of a mix may hold less than a whole AAC frame.
        assertTrue("encoder was fed part frames", StandInCodecs.encoderPartFrames <= 1)

        val audio = outcome?.audio ?: return Mix(outcome, thrown, ShortArray(0))
        val frames = wantedFrames(from, to)
        assertEquals("frames given to the encoder", frames, StandInCodecs.framesEncoded)
        assertEquals("encoded frames", ceil(frames / 1024.0).toInt(), audio.samples.size)
        audio.samples.forEachIndexed { index, sample ->
            assertEquals("timestamp of encoded frame $index", index * 1024L * 1_000_000L / RATE, sample.ptsUs)
            assertEquals("end-of-stream or codec-config flag on encoded frame $index", 0, sample.flags and 6)
        }
        if (!StandInCodecs.encoderKeepsSound) return Mix(outcome, thrown, ShortArray(0))

        val bytes = ByteBuffer.allocate(audio.samples.sumOf { it.data.size })
        audio.samples.forEach { bytes.put(it.data) }
        bytes.flip()
        val pcm = ShortArray(bytes.remaining() / 2)
        bytes.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
        assertEquals("frames in the sound track", frames, pcm.size / 2L)
        return Mix(outcome, thrown, pcm)
    }

    private fun wantedFrames(from: Double, to: Double): Long =
        ((to - from).coerceIn(0.0, TimelineAudioEncoder.MAX_SECONDS) * RATE).toLong().coerceAtLeast(1L)

    /**
     * Compares the mix with what the timeline should sound like, frame by frame. [tolerance] is
     * in 16-bit steps and has to cover the linear resampling, which is a little off for a high
     * tone in a low-rate file; a chunk out of place is off by thousands.
     */
    private fun assertSounds(
        what: String,
        mix: Mix,
        placed: List<Placed>,
        from: Double,
        to: Double,
        tolerance: Double = 16.0,
        tracks: Map<TimelineTrack, TrackState> = emptyMap()
    ) {
        assertNotNull("$what: no sound track", mix.outcome.audio)
        val assets = placed.map { it.source }.distinctBy { it.id }.associate { it.id to it.asset() }
        val audible = TimelineEngine.audibleTracks(tracks)
        val resolved = TimelineEngine.starts(placed.map { it.clip }, assets).filter { it.clip.track in audible }
        val sourceOf = placed.associate { it.clip.id to it.source }
        val frames = wantedFrames(from, to)
        var worst = 0.0
        var worstAt = -1L
        var compared = 0L
        for (n in 0 until frames) {
            for (side in 0..1) {
                var sum = 0.0
                var nearAnEdge = false
                for (rc in resolved) {
                    val first = ((rc.start - from) * RATE).toLong().coerceAtLeast(0L)
                    val end = ((rc.start + rc.duration - from) * RATE).toLong().coerceAtMost(frames)
                    if (n < first || n >= end) continue
                    val local = from + n.toDouble() / RATE - rc.start
                    if (local < 0.0) continue
                    val clip = rc.clip
                    val value = sourceOf.getValue(clip.id).value(clip.trimIn + local * clip.speed.coerceIn(0.25, 4.0), side)
                    if (value.isNaN()) {
                        nearAnEdge = true
                        break
                    }
                    val gain = (clip.volume / 100.0).coerceIn(0.0, 2.0) * TimelineEngine.audioEnvelope(clip, local, rc.duration)
                    val pan = AudioDsp.panGains(clip.audioPan)
                    sum += value * gain * (if (side == 0) pan.first else pan.second)
                }
                if (nearAnEdge) continue
                val expected = AudioDsp.softLimit((sum / 32768.0).toFloat()) * 32767.0
                val off = abs(expected - mix.pcm[(n * 2 + side).toInt()])
                compared++
                if (off > worst) {
                    worst = off
                    worstAt = n
                }
            }
        }
        assertTrue("$what: nothing was compared", compared > frames)
        assertTrue(
            "$what: the mix is off by ${worst.toInt()} at frame $worstAt (${worstAt.toDouble() / RATE} s)",
            worst <= tolerance
        )
    }

    /** Mixes [placed] and expects all of it to be heard, with nothing to report. */
    private fun assertPlays(
        what: String,
        placed: List<Placed>,
        from: Double,
        to: Double,
        tolerance: Double = 16.0,
        tracks: Map<TimelineTrack, TrackState> = emptyMap()
    ): Mix {
        StandInCodecs.reset()
        val result = mix(placed, from, to, tracks)
        assertSounds(what, result, placed, from, to, tolerance, tracks)
        assertEquals("$what: unreadable clips", 0, result.outcome.unreadableClips)
        assertNull("$what: note", result.outcome.note())
        return result
    }

    /** Mixes [placed] and expects no sound track at all. */
    private fun assertNoSoundTrack(
        what: String,
        placed: List<Placed>,
        tracks: Map<TimelineTrack, TrackState> = emptyMap()
    ): Mix {
        StandInCodecs.reset()
        val result = mix(placed, 0.0, 2.0, tracks)
        assertNull("$what: got a sound track", result.outcome.audio)
        assertEquals("$what: an encoder was started", 0, StandInCodecs.encodersCreated)
        return result
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun aClipReachesTheEncoderSampleForSample() {
        assertPlays("whole clip", listOf(place(source())), 0.0, 2.0)
        assertEquals("no seek for a clip read from its start", 0, StandInCodecs.seeks)

        // Sound that starts late is preceded by exactly that much silence.
        assertPlays("clip three seconds in", listOf(place(source(), startAt = 3.0)), 0.0, 5.0)

        // A clip or a render that outlasts the sound goes quiet; it is not an error.
        assertPlays("clip longer than its file", listOf(place(source(seconds = 1.0), trimOut = 3.0)), 0.0, 3.0)
        assertPlays("render longer than the sound", listOf(place(source(seconds = 1.0))), 0.0, 4.0)
        assertPlays("a fiftieth of a second", listOf(place(source())), 0.5, 0.52)
    }

    @Test
    fun anyRateAndPacketSizeLandsOnTheOutputGrid() {
        fun clip(rate: Int, channels: Int = 2, packet: Int, tone: Double = 440.0, seconds: Double = 2.0) =
            listOf(place(source(rate = rate, channels = channels, packet = packet, tone = tone, seconds = seconds)))

        // The lower the rate, the less exact the linear resampling of the tone.
        assertPlays("22.05 kHz mono", clip(22050, channels = 1, packet = 441), 0.0, 2.0, tolerance = 24.0)
        assertPlays("8 kHz mono", clip(8000, channels = 1, packet = 160, tone = 200.0), 0.0, 2.0, tolerance = 40.0)
        assertPlays("11.025 kHz in tiny packets", clip(11025, packet = 37, tone = 150.0), 0.0, 2.0, tolerance = 30.0)
        assertPlays("96 kHz", clip(96000, packet = 1920), 0.0, 2.0)
        assertPlays("one-second packets", clip(48000, packet = 48000, seconds = 3.0), 0.0, 3.0)
        // Packets of 1024 frames have timestamps that are not whole microseconds, so the clip
        // sits a fraction of a frame out; hence the wider tolerance.
        assertPlays("44.1 kHz in 1024-frame packets", clip(44100, packet = 1024), 0.0, 2.0, tolerance = 40.0)
        assertPlays("48 kHz in 1024-frame packets", clip(48000, packet = 1024), 0.0, 2.0, tolerance = 40.0)
    }

    @Test
    fun whateverTheDecoderProducesBecomes16BitStereo() {
        fun plain(encoding: Int) = listOf(place(source(rate = 44100, packet = 441, encoding = encoding)))
        // Eight bits are coarse: a step of the source is 256 steps of the output.
        assertPlays("8-bit", plain(AudioDsp.PCM_8BIT), 0.0, 2.0, tolerance = 300.0)
        assertPlays("float", plain(AudioDsp.PCM_FLOAT), 0.0, 2.0)
        assertPlays("24-bit", plain(AudioDsp.PCM_24BIT_PACKED), 0.0, 2.0)
        assertPlays("32-bit", plain(AudioDsp.PCM_32BIT), 0.0, 2.0)

        assertPlays("5.1, centre", listOf(place(source(channels = 6, onlyChannel = 2))), 0.0, 2.0)
        assertPlays("5.1, back right", listOf(place(source(channels = 6, onlyChannel = 5))), 0.0, 2.0)
        assertPlays("7.1", listOf(place(source(channels = 8, amp = 3000.0))), 0.0, 2.0)
        assertPlays("5.1 in float", listOf(place(source(channels = 6, encoding = AudioDsp.PCM_FLOAT, amp = 4000.0))), 0.0, 2.0)

        // The file's header may understate the stream (HE-AAC does); the decoder's word counts.
        assertPlays(
            "header says 24 kHz mono, decoder gives 48 kHz stereo",
            listOf(place(source(extra = mapOf("containerRate" to 24000, "containerChannels" to 1)))), 0.0, 2.0
        )
    }

    @Test
    fun trimsAndRangesStartDecodingWhereTheyAreHeard() {
        assertPlays("trimmed in five seconds", listOf(place(source(seconds = 10.0), trimIn = 5.0, trimOut = 7.0)), 0.0, 2.0)
        assertEquals(1, StandInCodecs.seeks)
        // Two seconds of 20 ms packets plus the pre-roll, not the seven seconds before the out-point.
        assertTrue("decoded ${StandInCodecs.packetsDecoded} packets", StandInCodecs.packetsDecoded <= 115)

        assertPlays("range in the middle of a clip", listOf(place(source(seconds = 6.0))), 2.0, 4.0)
        assertTrue("decoded ${StandInCodecs.packetsDecoded} packets", StandInCodecs.packetsDecoded <= 115)

        assertPlays(
            "range, trim and double speed together",
            listOf(place(source(seconds = 20.0), startAt = 1.0, trimIn = 4.0, trimOut = 16.0) { it.copy(speed = 2.0) }), 3.0, 6.0
        )
        assertPlays("half speed", listOf(place(source()) { it.copy(speed = 0.5) }), 0.0, 4.0)
        assertPlays("four times speed", listOf(place(source(seconds = 8.0)) { it.copy(speed = 4.0) }), 0.0, 2.0)

        // A file that cannot seek is read from its start and the unwanted part thrown away.
        assertPlays(
            "file that cannot seek",
            listOf(place(source(seconds = 10.0, extra = mapOf("noSeek" to true)), trimIn = 5.0, trimOut = 7.0)), 0.0, 2.0
        )

        // A trim inside the pre-roll must not ask for a time before the file starts.
        assertPlays("trim of a twentieth of a second", listOf(place(source(seconds = 4.0), trimIn = 0.05, trimOut = 2.05)), 0.0, 2.0)
        assertEquals(0, StandInCodecs.seeks)
    }

    @Test
    fun aStreamThatDoesNotStartAtZeroKeepsItsPlace() {
        assertPlays("starts 23 ms late", listOf(place(source(startUs = 23_220L))), 0.0, 2.0)
        assertPlays("starts 20 ms early", listOf(place(source(startUs = -20_000L))), 0.0, 2.0)
        assertPlays(
            "starts half a second late and is trimmed in",
            listOf(place(source(seconds = 6.0, startUs = 500_000L), trimIn = 3.0, trimOut = 5.0)), 0.0, 2.0
        )
    }

    @Test
    fun clipsAreSummedEachWithItsOwnSettings() {
        val first = source(seconds = 4.0)
        val second = source(rate = 44100, packet = 441, seconds = 3.0, tone = 330.0, amp = 6000.0)
        assertPlays(
            "volume, pan, fades and automation",
            listOf(
                place(first) { it.copy(volume = 80.0, audioPan = -40.0, fadeInAudio = 0.5, fadeOutAudio = 1.0) },
                place(second, startAt = 1.5, track = TimelineTrack.AUDIO2) {
                    it.copy(
                        volume = 150.0,
                        audioPan = 100.0,
                        volumeKeyframes = listOf(VolumePoint(0.5, 100.0), VolumePoint(2.0, 20.0))
                    )
                }
            ),
            0.0, 5.0
        )

        // Two loud clips together bend into the limiter instead of wrapping round.
        assertPlays(
            "sum that reaches the limiter",
            listOf(place(source(amp = 20000.0)), place(source(amp = 20000.0), track = TimelineTrack.AUDIO2)),
            0.0, 2.0, tolerance = 40.0
        )
    }

    @Test
    fun aVideoClipIsHeardUnlessItsTrackIsSilenced() {
        val video = source(kind = AssetKind.VIDEO, tone = 500.0, seconds = 3.0)
        val music = source(tone = 250.0, seconds = 3.0)
        val both = listOf(place(video, track = TimelineTrack.VIDEO), place(music))

        assertPlays("video and music", both, 0.0, 3.0)
        assertEquals("two decoders and the encoder", 3, StandInCodecs.codecsCreated)

        val soloMusic = mapOf(TimelineTrack.AUDIO to TrackState(solo = true))
        assertPlays("music soloed", both, 0.0, 3.0, tracks = soloMusic)
        assertEquals("a silenced clip is not even opened", 2, StandInCodecs.codecsCreated)

        val pictureMuted = mapOf(TimelineTrack.VIDEO to TrackState(muted = true))
        assertPlays("picture track muted", both, 0.0, 3.0, tracks = pictureMuted)
        assertEquals(2, StandInCodecs.codecsCreated)

        // A video with no audio track is simply silent.
        val silentVideo = source(kind = AssetKind.VIDEO, extra = mapOf("audio" to false))
        assertPlays("silent video under music", listOf(place(silentVideo, track = TimelineTrack.VIDEO), place(music)), 0.0, 2.0)
    }

    @Test
    fun decodersAreOpenedLateAndClosedEarly() {
        // A long edit must never hold one decoder per clip: a device only has so many.
        val cuts = (0 until 40).map { i ->
            place(source(seconds = 1.0, tone = 300.0 + 10 * i), startAt = i * 0.5, trimIn = 0.2, trimOut = 0.7)
        }
        assertPlays("forty clips back to back", cuts, 0.0, 20.0, tolerance = 30.0)
        assertEquals(41, StandInCodecs.codecsCreated)
        assertEquals("one decoder and the encoder at a time", 2, StandInCodecs.mostOpenCodecs)

        val take = source(seconds = 30.0)
        val pieces = (0 until 30).map { i -> place(take, startAt = i * 0.7, trimIn = i * 1.0, trimOut = i * 1.0 + 0.7) }
        assertPlays("one file cut into thirty pieces", pieces, 0.0, 21.0)
        assertEquals(2, StandInCodecs.mostOpenCodecs)

        val stack = listOf(
            place(source(kind = AssetKind.VIDEO, amp = 3000.0, tone = 200.0), track = TimelineTrack.VIDEO),
            place(source(kind = AssetKind.VIDEO, amp = 3000.0, tone = 300.0), track = TimelineTrack.VIDEO2),
            place(source(kind = AssetKind.VIDEO, amp = 3000.0, tone = 400.0), track = TimelineTrack.OVERLAY),
            place(source(amp = 3000.0, tone = 500.0), track = TimelineTrack.AUDIO),
            place(source(amp = 3000.0, tone = 600.0), track = TimelineTrack.AUDIO2)
        )
        assertPlays("five tracks at once", stack, 0.0, 2.0)
        assertEquals(6, StandInCodecs.mostOpenCodecs)

        // A clip that lies wholly after the range is never opened.
        assertPlays("clip after the range", listOf(place(source()), place(source(), startAt = 10.0)), 0.0, 2.0)
        assertEquals(2, StandInCodecs.codecsCreated)
    }

    @Test
    fun aTimelineWithNothingToHearGetsNoSoundTrack() {
        val music = source()
        val video = source(kind = AssetKind.VIDEO)

        var result = assertNoSoundTrack(
            "everything silenced",
            listOf(place(video, track = TimelineTrack.VIDEO), place(music)),
            mapOf(TimelineTrack.VIDEO to TrackState(muted = true), TimelineTrack.AUDIO to TrackState(hidden = true))
        )
        assertEquals("nothing was opened", 0, StandInCodecs.codecsCreated)
        assertNull(result.outcome.note())

        assertNoSoundTrack("volume at zero", listOf(place(music) { it.copy(volume = 0.0) }))
        assertEquals(0, StandInCodecs.codecsCreated)

        // These open the file, find nothing to hear, and must not report a fault.
        val noAudioTrack = source(kind = AssetKind.VIDEO, extra = mapOf("audio" to false))
        val emptyTrack = source(seconds = 0.0)
        val emptyAndNeverEnds = source(seconds = 0.0, extra = mapOf("neverEnds" to true))
        val quiet = listOf(
            "video without an audio track" to place(noAudioTrack, track = TimelineTrack.VIDEO),
            "audio track with nothing in it" to place(emptyTrack, trimOut = 2.0),
            "empty track whose decoder never finishes" to place(emptyAndNeverEnds, trimOut = 2.0),
            "trim past the end of the file" to place(source(), trimIn = 5.0, trimOut = 7.0)
        )
        for ((what, placed) in quiet) {
            result = assertNoSoundTrack(what, listOf(placed))
            assertEquals(what, 0, result.outcome.unreadableClips)
            assertFalse(what, result.outcome.failed)
            assertNull(what, result.outcome.note())
        }
    }

    @Test
    fun anUnreadableClipIsCountedAndTheRestStillPlays() {
        val good = source(tone = 350.0)
        val broken = listOf(
            "file is gone" to source(exists = false),
            "not a media file" to source(extra = mapOf("corrupt" to true)),
            "no decoder for it" to source(extra = mapOf("mime" to "audio/unsupported"))
        )
        for ((what, bad) in broken) {
            // On its own: no sound track, and the render says why.
            val alone = assertNoSoundTrack(what, listOf(place(bad)))
            assertEquals(what, 1, alone.outcome.unreadableClips)
            assertEquals(what, "without sound: the audio of 1 clip could not be read", alone.outcome.note())

            // Beside a good clip: the good one is all there, and the render says one is missing.
            StandInCodecs.reset()
            val placed = listOf(place(bad, track = TimelineTrack.AUDIO2), place(good))
            val beside = mix(placed, 0.0, 2.0)
            assertSounds(what, beside, placed, 0.0, 2.0)
            assertEquals(what, 1, beside.outcome.unreadableClips)
            assertEquals(what, "the sound of 1 clip could not be read and is missing", beside.outcome.note())
        }

        val two = assertNoSoundTrack(
            "two unreadable files",
            listOf(place(broken[1].second), place(broken[2].second, track = TimelineTrack.AUDIO2))
        )
        assertEquals("without sound: the audio of 2 clips could not be read", two.outcome.note())
    }

    @Test
    fun aDecoderThatFailsPartWayKeepsWhatItGave() {
        val good = source(tone = 350.0, seconds = 3.0)
        val failing = listOf(
            "decoder dies a second in" to source(seconds = 3.0, heardUntil = 1.0, extra = mapOf("failAt" to 1.0)),
            "decoder stops answering a second in" to source(seconds = 3.0, heardUntil = 1.0, extra = mapOf("stallAt" to 1.0)),
            "decoder dies at once" to source(seconds = 3.0, heardUntil = 0.0, extra = mapOf("failAt" to 0.0))
        )
        for ((what, bad) in failing) {
            StandInCodecs.reset()
            val placed = listOf(place(bad, track = TimelineTrack.AUDIO2), place(good))
            val result = mix(placed, 0.0, 3.0)
            assertSounds(what, result, placed, 0.0, 3.0)
            assertEquals(what, 1, result.outcome.unreadableClips)
            assertEquals(what, "the sound of 1 clip could not be read and is missing", result.outcome.note())
        }

        // A decoder that gives all its sound and then never says so is slow, not broken.
        assertPlays(
            "decoder never signals the end",
            listOf(place(source(seconds = 1.0, extra = mapOf("neverEnds" to true)), trimOut = 2.0)), 0.0, 2.0
        )
    }

    @Test
    fun cancellingOrLosingTheEncoderReleasesEverything() {
        var calls = 0
        val cancelled = mix(listOf(place(source(seconds = 30.0))), 0.0, 30.0) {
            if (++calls == 3) throw CancellationException("cancelled")
        }
        // The caller's exception comes back out as it is, and `mix` has checked nothing is open.
        assertTrue("threw ${cancelled.thrown}", cancelled.thrown is CancellationException)
        assertEquals(3, calls)

        StandInCodecs.reset()
        StandInCodecs.encoderCannotBeCreated = true
        val noEncoder = mix(listOf(place(source())), 0.0, 2.0)
        assertNotNull("a mix that cannot be encoded throws for the exporter to report", noEncoder.thrown)
    }

    @Test
    fun theLastFrameSurvivesHoweverTheEncoderEnds() {
        val clip = listOf(place(source(seconds = 4.0)))
        val wholeFrames = 1024.0 * 100 / RATE

        for (to in listOf(2.0, wholeFrames)) {
            StandInCodecs.reset()
            StandInCodecs.encoderEndsOnItsLastFrame = true
            assertSounds("end flagged on the last frame", mix(clip, 0.0, to), clip, 0.0, to)

            StandInCodecs.reset()
            StandInCodecs.encoderNeverEnds = true
            assertSounds("end never signalled", mix(clip, 0.0, to), clip, 0.0, to)
        }
    }

    @Test
    fun progressRisesAndIsReportedEveryCoupleOfSeconds() {
        val seen = ArrayList<Float>()
        val result = mix(listOf(place(source(seconds = 12.0))), 0.0, 12.0) { seen.add(it) }
        assertNotNull(result.outcome.audio)
        assertTrue("reports: $seen", seen.size in 5..8)
        assertTrue("reports: $seen", seen.zipWithNext().all { (earlier, later) -> later > earlier })
        assertTrue("reports: $seen", seen.first() >= 0f && seen.last() <= 1f)
    }

    @Test
    fun soundPastAnHourIsCutAndTheRenderSaysSo() {
        // Far too long to hold: the stand-in encoder keeps only the head of each frame.
        val hour = TimelineAudioEncoder.MAX_SECONDS
        val opening = place(source(seconds = 1.0))
        val acrossTheHour = place(source(seconds = 4.0), startAt = hour - 2.0, track = TimelineTrack.AUDIO2)

        StandInCodecs.encoderKeepsSound = false
        val cut = mix(listOf(opening, acrossTheHour), 0.0, hour + 100.0)
        assertNotNull(cut.outcome.audio)
        assertEquals(hour, cut.outcome.cutAtSec!!, 0.0)
        assertEquals("the sound stops after 60 minutes, the most one render carries", cut.outcome.note())
        assertEquals((hour * RATE).toLong(), StandInCodecs.framesEncoded)

        // The same render with nothing to hear past the hour loses nothing, so says nothing.
        StandInCodecs.reset()
        StandInCodecs.encoderKeepsSound = false
        val whole = mix(listOf(opening), 0.0, hour + 100.0)
        assertNotNull(whole.outcome.audio)
        assertNull(whole.outcome.note())
    }

    private companion object {
        const val RATE = TimelineAudioEncoder.SAMPLE_RATE
    }
}
