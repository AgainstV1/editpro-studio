package com.editpro.studio.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.ClipVoice
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import com.editpro.studio.model.WindowedPcmFeed
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mixes the timeline's sound and encodes it to AAC for the exported MP4.
 *
 * Every audible clip that carries sound is mixed: clips on the audio tracks and the soundtrack of
 * video clips on the picture tracks. Each clip is resampled onto the 44.1 kHz grid with linear
 * interpolation, run through its 5-band EQ and compressor, then placed at its timeline position
 * with volume, fades, volume automation and stereo balance applied ([ClipVoice]). The summed mix
 * goes through a soft limiter so several loud clips together bend rather than clip.
 *
 * The mix is streamed. The timeline is walked about a tenth of a second at a time; each clip that
 * sounds in that stretch is decoded only as far as it has been heard, and the result goes straight
 * into the AAC encoder. Memory therefore does not grow with the length of the render or of a
 * clip, and a long export keeps its sound to the end.
 *
 * Deliberate limits (kept simple and predictable):
 *  - one render carries at most [MAX_SECONDS] of sound, because the encoded track waits in memory
 *    until the picture is written;
 *  - clip `speed` shifts the source read position (pitch follows, as there is no time-stretch);
 *  - de-essing and reverb are stored on the clip but not processed.
 *
 * A clip that cannot be read is left out and counted, and the caller wraps the whole call: a
 * failure here must never fail the video export, but it must not pass unnoticed either.
 */
object TimelineAudioEncoder {

    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2

    /**
     * The longest sound track one render carries. The encoded track is held in memory until the
     * picture has been written, at about a megabyte a minute.
     */
    const val MAX_SECONDS = 3600.0

    private const val BIT_RATE = 128_000

    /** Frames in one AAC frame, which is also what one encoder input buffer is given. */
    private const val AAC_FRAME = 1024

    /**
     * The mix advances four AAC frames (about a tenth of a second) at a time, so the encoder is
     * always handed whole frames of its own size.
     */
    private const val BLOCK_FRAMES = 4 * AAC_FRAME

    /** Decoding starts this far ahead of the first sample needed, so the decoder has settled. */
    private const val PRE_ROLL_SEC = 0.1

    /** A codec that gives nothing back for this many polls (several seconds) has stopped. */
    private const val MAX_IDLE_POLLS = 400

    /** Input buffers handed to a decoder between two collections of its output. */
    private const val MAX_FEED_PER_PUMP = 4

    /** A sample rate below this is a damaged header rather than a real stream. */
    private const val MIN_SOURCE_RATE = 4000

    data class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    data class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

    /**
     * What a mix produced. [audio] is null both when the timeline has nothing to hear and when
     * the sound could not be produced; the other fields tell the two apart.
     */
    class Outcome(
        val audio: EncodedAudio?,
        /** Clips whose sound is missing because their file is gone or could not be decoded. */
        val unreadableClips: Int = 0,
        /** The mix itself broke, as opposed to there being nothing to hear. */
        val failed: Boolean = false,
        /** Set when the render is longer than [MAX_SECONDS] and its sound was cut there. */
        val cutAtSec: Double? = null
    ) {
        /** What the user should be told about the sound, or null when all of it is in the file. */
        fun note(): String? = soundNote(audio != null, unreadableClips, failed, cutAtSec)
    }

    /**
     * The sentence that goes with a finished render when some of its sound is missing, or null
     * when nothing is. Kept apart from [Outcome] so the wording can be checked without a codec.
     */
    fun soundNote(hasAudio: Boolean, unreadableClips: Int, failed: Boolean, cutAtSec: Double?): String? {
        val clips = if (unreadableClips == 1) "1 clip" else "$unreadableClips clips"
        if (!hasAudio) {
            return when {
                failed -> "without sound: the audio could not be mixed"
                unreadableClips > 0 -> "without sound: the audio of $clips could not be read"
                else -> null
            }
        }
        val parts = ArrayList<String>()
        if (unreadableClips > 0) parts.add("the sound of $clips could not be read and is missing")
        if (cutAtSec != null) {
            parts.add("the sound stops after ${(cutAtSec / 60.0).roundToInt()} minutes, the most one render carries")
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    /**
     * Mixes and encodes the sound between [fromSec] and [toSec] of the timeline.
     *
     * [onProgress] is called with 0..1 every couple of seconds of sound. It may throw to abandon
     * the mix (a cancelled export does); the codecs are released on the way out.
     */
    fun encode(
        clips: List<ResolvedClip>,
        assets: Map<String, MediaAsset>,
        trackStates: Map<TimelineTrack, TrackState>,
        fromSec: Double,
        toSec: Double,
        onProgress: (Float) -> Unit = {}
    ): Outcome {
        val audibleTracks = TimelineEngine.audibleTracks(trackStates)
        val audible = clips.filter { rc ->
            rc.clip.track in audibleTracks &&
                TimelineEngine.carriesSound(rc.clip, rc.clip.assetId?.let { assets[it] }) &&
                (rc.clip.volume > 0.0 || rc.clip.volumeKeyframes.isNotEmpty()) &&
                rc.end > fromSec &&
                rc.start < toSec
        }
        if (audible.isEmpty()) return Outcome(null)

        val wanted = toSec - fromSec
        val duration = wanted.coerceIn(0.0, MAX_SECONDS)
        if (duration <= 0.0) return Outcome(null)
        val totalFrames = (duration * SAMPLE_RATE).toLong().coerceAtLeast(1L)
        val cutAtSec = if (wanted > MAX_SECONDS && audible.any { it.end > fromSec + MAX_SECONDS }) MAX_SECONDS else null

        var missingFiles = 0
        val voices = ArrayList<Voice>()
        for (rc in audible) {
            if (rc.start >= fromSec + duration) continue
            val path = rc.clip.assetId?.let { assets[it] }?.localPath
            if (path == null || !File(path).exists()) {
                missingFiles++
                continue
            }
            val voice = Voice(rc, path, fromSec, totalFrames)
            if (voice.mixer.endFrame > voice.mixer.firstFrame) voices.add(voice)
        }
        if (voices.isEmpty()) return Outcome(null, unreadableClips = missingFiles)

        val writer = AacWriter()
        val block = FloatArray(BLOCK_FRAMES * CHANNELS)
        val pcm = ShortArray(BLOCK_FRAMES * CHANNELS)
        // Nothing is encoded until the first clip is actually heard, so a timeline whose clips
        // turn out to be silent videos gets no sound track rather than an empty one.
        var silentLead = 0L
        var position = 0L
        var nextReport = 0L
        try {
            while (position < totalFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalFrames - position).toInt()
                java.util.Arrays.fill(block, 0, frames * CHANNELS, 0f)

                var sounded = false
                for (voice in voices) {
                    if (voice.finished || voice.mixer.firstFrame >= position + frames) continue
                    if (voice.mixInto(block, position, frames)) sounded = true
                    if (voice.mixer.endFrame <= position + frames) voice.close()
                }

                if (sounded && !writer.started) {
                    writer.start()
                    writer.writeSilence(silentLead)
                }
                if (writer.started) {
                    for (i in 0 until frames * CHANNELS) {
                        pcm[i] = (AudioDsp.softLimit(block[i]) * 32767f).toInt().toShort()
                    }
                    writer.write(pcm, frames)
                } else {
                    silentLead += frames
                }

                position += frames
                if (position >= nextReport) {
                    onProgress(position.toFloat() / totalFrames)
                    nextReport = position + SAMPLE_RATE * 2L
                }
            }

            val unreadable = missingFiles + voices.count { it.failed }
            if (!writer.started) return Outcome(null, unreadableClips = unreadable)
            val audio = writer.finish()
            return Outcome(
                audio = audio,
                unreadableClips = unreadable,
                failed = audio == null,
                cutAtSec = if (audio != null) cutAtSec else null
            )
        } finally {
            voices.forEach { it.close() }
            writer.release()
        }
    }

    // ----------------------------------------------------------------- clips

    /** One clip in the mix: its place and processing ([mixer]) and the decoder feeding it. */
    private class Voice(rc: ResolvedClip, private val path: String, fromSec: Double, totalFrames: Long) {

        val mixer = ClipVoice(rc.clip, rc.start, rc.duration, fromSec, totalFrames, SAMPLE_RATE)
        private var reader: PcmReader? = null
        private var opened = false

        /** Nothing more comes from this clip: it has ended, it has no sound, or it broke. */
        var finished = false
            private set

        /** The file has sound that could not be decoded. */
        var failed = false
            private set

        fun mixInto(block: FloatArray, blockStart: Long, frames: Int): Boolean {
            if (finished) return false
            try {
                if (!opened) {
                    opened = true
                    val fresh = PcmReader(path)
                    reader = fresh
                    if (!fresh.open(mixer.sourceStartSec)) {
                        // No audio track: a silent video, which is not an error.
                        close()
                        return false
                    }
                }
                val source = reader ?: return false
                val added = mixer.mixInto(source, block, blockStart, frames)
                if (source.stalled) {
                    failed = true
                    close()
                }
                return added
            } catch (e: Exception) {
                failed = true
                close()
                return false
            } catch (e: OutOfMemoryError) {
                failed = true
                close()
                return false
            }
        }

        fun close() {
            finished = true
            reader?.close()
            reader = null
        }
    }

    // --------------------------------------------------------------- decoding

    /**
     * Runs one file's audio track through a decoder a chunk at a time. Whatever the file holds —
     * mono, stereo or surround, 16-bit or not — comes out as 16-bit stereo ([WindowedPcmFeed]).
     */
    private class PcmReader(private val path: String) : WindowedPcmFeed() {

        private val extractor = MediaExtractor()
        private var codec: MediaCodec? = null
        private val info = MediaCodec.BufferInfo()
        private var scratch = ShortArray(0)
        private var sourceChannels = 2
        private var encoding = AudioDsp.PCM_16BIT
        private var inputDone = false
        private var gotSound = false
        private var idlePolls = 0

        override var sampleRate = SAMPLE_RATE
            private set
        override var startSec = 0.0
            private set

        /** The decoder stopped answering part-way through the file. */
        var stalled = false
            private set

        /**
         * Opens the file a moment before [fromSrcSec]. Returns false when it has no audio track;
         * throws when the track cannot be decoded.
         */
        fun open(fromSrcSec: Double): Boolean {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false

            extractor.selectTrack(trackIndex)
            val seekUs = (max(0.0, fromSrcSec - PRE_ROLL_SEC) * 1_000_000.0).toLong()
            if (seekUs > 0L) extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            readFormat(format, decoded = false)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            // The first decoded chunk says where the stream really starts and what it holds.
            while (!gotSound && !ended) pump()
            return true
        }

        /**
         * Takes the shape of the stream from the file's own description ([decoded] false) or
         * from what the decoder says it is producing ([decoded] true), which is the one to
         * believe: a file can understate its rate and channels, and how the samples are laid
         * out is the decoder's to say.
         */
        private fun readFormat(format: MediaFormat, decoded: Boolean) {
            // A change part-way through would move every frame already counted, so the shape
            // of the stream is fixed once sound has arrived.
            if (gotSound) return
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(MIN_SOURCE_RATE)
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            }
            if (decoded) {
                // Decoded sound is 16-bit unless the decoder says otherwise.
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioDsp.PCM_16BIT
                }
            }
        }

        override fun pump() {
            val decoder = codec
            if (decoder == null) {
                ended = true
                return
            }
            var progressed = false

            // Hand the decoder whatever input it has room for, without waiting for room: when
            // it is full it is because decoded sound is waiting to be collected below.
            var fed = 0
            while (!inputDone && fed < MAX_FEED_PER_PUMP) {
                val inIdx = decoder.dequeueInputBuffer(0L)
                if (inIdx < 0) break
                val buffer = decoder.getInputBuffer(inIdx)
                val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                if (size < 0) {
                    decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                fed++
                progressed = true
            }

            // Then take the next decoded chunk, waiting a moment for it if need be.
            val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    readFormat(decoder.outputFormat, decoded = true)
                    progressed = true
                }
                outIdx >= 0 -> {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(outIdx)
                        if (buffer != null) {
                            if (!gotSound) {
                                // A stream may begin a few milliseconds before zero (encoder
                                // priming); anything further back is not a real timestamp.
                                val ptsUs = info.presentationTimeUs
                                startSec = if (ptsUs > -1_000_000L) ptsUs / 1_000_000.0 else 0.0
                                gotSound = true
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            if (scratch.size < info.size) scratch = ShortArray(info.size)
                            val count = AudioDsp.pcmToShorts(buffer, info.size, encoding, scratch)
                            window.append(scratch, count, sourceChannels)
                            progressed = true
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                }
            }

            if (progressed) {
                idlePolls = 0
            } else if (++idlePolls > MAX_IDLE_POLLS) {
                // Silence after the last input is just a decoder that never said it was done.
                // Silence in the middle of the file is a decoder that has stopped working.
                if (!inputDone) stalled = true
                ended = true
            }
        }

        fun close() {
            ended = true
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { extractor.release() }
        }
    }

    // --------------------------------------------------------------- encoding

    /** Takes the mix a block at a time and collects the AAC frames that come out. */
    private class AacWriter {

        private var encoder: MediaCodec? = null
        private val info = MediaCodec.BufferInfo()
        private val samples = ArrayList<Sample>()
        private var outputFormat: MediaFormat? = null
        private var framesIn = 0L
        private var outputEnded = false

        val started: Boolean get() = encoder != null

        fun start() {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = created
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
        }

        private fun ptsUs(frame: Long): Long = frame * 1_000_000L / SAMPLE_RATE

        /** Queues [frames] stereo frames from the start of [pcm]. */
        fun write(pcm: ShortArray, frames: Int) {
            val codec = encoder ?: return
            var done = 0
            var idle = 0
            while (done < frames) {
                var progressed = false
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buffer = codec.getInputBuffer(inIdx)
                        ?: throw IllegalStateException("The audio encoder gave no input buffer")
                    buffer.clear()
                    val capacityFrames = buffer.capacity() / (2 * CHANNELS)
                    val count = min(min(AAC_FRAME, frames - done), capacityFrames.coerceAtLeast(1))
                    buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, done * CHANNELS, count * CHANNELS)
                    codec.queueInputBuffer(inIdx, 0, count * CHANNELS * 2, ptsUs(framesIn), 0)
                    framesIn += count
                    done += count
                    progressed = true
                }
                if (drain(codec, 0L)) progressed = true
                if (progressed) {
                    idle = 0
                } else if (++idle > MAX_IDLE_POLLS) {
                    throw IllegalStateException("The audio encoder stopped responding")
                }
            }
        }

        fun writeSilence(frames: Long) {
            if (frames <= 0L) return
            val quiet = ShortArray(BLOCK_FRAMES * CHANNELS)
            var left = frames
            while (left > 0L) {
                val count = min(BLOCK_FRAMES.toLong(), left).toInt()
                write(quiet, count)
                left -= count
            }
        }

        /** Collects whatever the encoder has ready. True when anything came out. */
        private fun drain(codec: MediaCodec, timeoutUs: Long): Boolean {
            var any = false
            while (!outputEnded) {
                val outIdx = codec.dequeueOutputBuffer(info, if (any) 0L else timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return any
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        any = true
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                outBuf.get(bytes)
                                // The end-of-stream flag is left off: to the muxer it marks a
                                // sample that only closes the track, and this one has sound.
                                val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                                samples.add(Sample(bytes, info.presentationTimeUs, flags))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        any = true
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                    // Any other negative code is information only; ask again next time.
                    else -> return any
                }
            }
            return any
        }

        /** Closes the stream and returns the track, or null when the encoder produced nothing. */
        fun finish(): EncodedAudio? {
            val codec = encoder ?: return null
            var endQueued = false
            var idle = 0
            while (!outputEnded) {
                var progressed = false
                if (!endQueued) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs(framesIn), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        endQueued = true
                        progressed = true
                    }
                }
                if (drain(codec, 10_000L)) progressed = true
                if (progressed) {
                    idle = 0
                } else if (++idle > MAX_IDLE_POLLS) {
                    // The tail never arrived. What was encoded so far is still a valid track.
                    break
                }
            }
            val format = outputFormat ?: return null
            if (samples.isEmpty()) return null
            return EncodedAudio(format, samples)
        }

        fun release() {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
        }
    }
}
