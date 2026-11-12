package com.editpro.studio.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.editpro.studio.model.AudioDsp
import com.editpro.studio.model.MediaAsset
import com.editpro.studio.model.ResolvedClip
import com.editpro.studio.model.TimelineEngine
import com.editpro.studio.model.TimelineTrack
import com.editpro.studio.model.TrackState
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Mixes the timeline's sound and encodes it to AAC for the exported MP4.
 *
 * Every audible clip that carries sound is mixed: clips on the audio tracks and the soundtrack of
 * video clips on the picture tracks. Each clip is decoded (only the part the export needs),
 * resampled onto the 44.1 kHz grid with linear interpolation, run through its 5-band EQ and
 * compressor ([AudioDsp.ClipChain]), then placed at its timeline position with volume, fades,
 * volume automation and stereo balance applied. The summed mix goes through a soft limiter so
 * several loud clips together bend rather than clip.
 *
 * Deliberate limits (kept simple and predictable):
 *  - audio is capped at [MAX_SECONDS] so the PCM buffer cannot exhaust memory on a tablet;
 *  - clip `speed` shifts the source read position (pitch follows, as there is no time-stretch);
 *  - de-essing and reverb are stored on the clip but not processed.
 *
 * Everything is wrapped by the caller: a failure here must never fail the video export.
 */
object TimelineAudioEncoder {

    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    private const val BIT_RATE = 128_000
    private const val MAX_SECONDS = 300.0

    data class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    data class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

    /** Decoded PCM for part of a file; [startSec] is the source time of the first sample. */
    private class Decoded(val pcm: ShortArray, val sampleRate: Int, val channels: Int, val startSec: Double)

    /** Returns null when the timeline has no audible audio, or when anything goes wrong. */
    fun encode(
        clips: List<ResolvedClip>,
        assets: Map<String, MediaAsset>,
        trackStates: Map<TimelineTrack, TrackState>,
        fromSec: Double,
        toSec: Double
    ): EncodedAudio? {
        val audibleTracks = TimelineEngine.audibleTracks(trackStates)
        val audible = clips.filter { rc ->
            rc.clip.track in audibleTracks &&
                TimelineEngine.carriesSound(rc.clip, rc.clip.assetId?.let { assets[it] }) &&
                (rc.clip.volume > 0.0 || rc.clip.volumeKeyframes.isNotEmpty()) &&
                rc.end > fromSec &&
                rc.start < toSec
        }
        if (audible.isEmpty()) return null

        val pcm = mix(audible, assets, fromSec, toSec) ?: return null
        return encodePcm(pcm)
    }

    // ---------------------------------------------------------------- mixing

    private fun mix(
        clips: List<ResolvedClip>,
        assets: Map<String, MediaAsset>,
        fromSec: Double,
        toSec: Double
    ): ShortArray? {
        val duration = (toSec - fromSec).coerceIn(0.0, MAX_SECONDS)
        if (duration <= 0.0) return null
        val totalFrames = (duration * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val accumulator = FloatArray(totalFrames * CHANNELS)

        var mixedAnything = false

        for (rc in clips) {
            val clip = rc.clip
            val asset = clip.assetId?.let { assets[it] } ?: continue
            val path = asset.localPath ?: continue
            if (!File(path).exists()) continue

            val startFrame = ((rc.start - fromSec) * SAMPLE_RATE).toInt().coerceAtLeast(0)
            val endFrame = ((rc.end - fromSec) * SAMPLE_RATE).toInt().coerceAtMost(totalFrames)
            if (endFrame <= startFrame) continue

            // Decode only the stretch of the source this clip actually plays inside the range.
            val speed = clip.speed.coerceIn(0.25, 4.0)
            val firstLocal = max(0.0, fromSec - rc.start)
            val lastLocal = min(rc.duration, fromSec + duration - rc.start)
            val decoded = decodePcm(path, clip.trimIn + firstLocal * speed, clip.trimIn + lastLocal * speed) ?: continue

            val baseGain = (clip.volume / 100.0).coerceIn(0.0, 2.0)
            val (panL, panR) = AudioDsp.panGains(clip.audioPan)
            val chain = AudioDsp.ClipChain(SAMPLE_RATE, clip.audioEq, clip.audioComp)
            val srcFrames = decoded.pcm.size / decoded.channels
            val rightCh = if (decoded.channels > 1) 1 else 0

            for (n in startFrame until endFrame) {
                val localT = fromSec + n.toDouble() / SAMPLE_RATE - rc.start
                if (localT < 0.0) continue

                val srcPos = (clip.trimIn + localT * speed - decoded.startSec) * decoded.sampleRate
                val i0 = floor(srcPos).toInt()
                if (i0 < 0 || i0 >= srcFrames) continue
                val i1 = min(i0 + 1, srcFrames - 1)
                val frac = (srcPos - i0).toFloat()
                val a = i0 * decoded.channels
                val b = i1 * decoded.channels
                val l0 = decoded.pcm[a] / 32768f
                val r0 = decoded.pcm[a + rightCh] / 32768f
                var left = l0 + (decoded.pcm[b] / 32768f - l0) * frac
                var right = r0 + (decoded.pcm[b + rightCh] / 32768f - r0) * frac

                if (!chain.isBypassed) {
                    chain.process(left, right)
                    left = chain.outL
                    right = chain.outR
                }

                val gain = (baseGain * TimelineEngine.audioEnvelope(clip, localT, rc.duration)).toFloat()
                if (gain == 0f) continue

                val outBase = n * CHANNELS
                accumulator[outBase] += left * gain * panL
                accumulator[outBase + 1] += right * gain * panR
                mixedAnything = true
            }
        }

        if (!mixedAnything) return null

        val out = ShortArray(accumulator.size)
        for (i in accumulator.indices) {
            out[i] = (AudioDsp.softLimit(accumulator[i]) * 32767f).toInt().toShort()
        }
        return out
    }

    // --------------------------------------------------------------- decoding

    /**
     * Decodes the audio track of [path] between two source times. Returns null when the file has
     * no audio track (a silent video) or cannot be decoded.
     */
    private fun decodePcm(path: String, fromSrcSec: Double, toSrcSec: Double): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
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
            if (trackIndex < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            extractor.selectTrack(trackIndex)
            val seekUs = (max(0.0, fromSrcSec - 0.1) * 1_000_000.0).toLong()
            if (seekUs > 0L) extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val stopUs = ((toSrcSec + 0.5) * 1_000_000.0).toLong()

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val chunks = ArrayList<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPtsUs = -1L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0 || extractor.sampleTime > stopUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                outBuf.asShortBuffer().get(shorts)
                                chunks.add(shorts)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (chunks.isEmpty()) return null
            val total = chunks.sumOf { it.size }
            val pcm = ShortArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(pcm, offset)
                offset += chunk.size
            }
            return Decoded(
                pcm = pcm,
                sampleRate = sampleRate.coerceAtLeast(8000),
                channels = channels.coerceIn(1, 2),
                startSec = max(0L, firstPtsUs) / 1_000_000.0
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    // --------------------------------------------------------------- encoding

    private fun encodePcm(pcm: ShortArray): EncodedAudio? {
        val totalFrames = pcm.size / CHANNELS
        if (totalFrames <= 0) return null

        var encoder: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var outputFormat: MediaFormat? = null
            val samples = ArrayList<Sample>()
            val info = MediaCodec.BufferInfo()

            var framePos = 0
            var inputDone = false
            var outputDone = false

            fun ptsUs(frame: Int): Long = (frame.toLong() * 1_000_000L) / SAMPLE_RATE

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buffer = encoder.getInputBuffer(inIdx)
                        if (buffer == null) {
                            inputDone = true
                        } else {
                            buffer.clear()
                            val remaining = totalFrames - framePos
                            if (remaining <= 0) {
                                encoder.queueInputBuffer(
                                    inIdx, 0, 0, ptsUs(framePos), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val capacityFrames = buffer.capacity() / (2 * CHANNELS)
                                val frames = min(min(1024, remaining), capacityFrames.coerceAtLeast(1))
                                val slice = ShortArray(frames * CHANNELS)
                                System.arraycopy(pcm, framePos * CHANNELS, slice, 0, frames * CHANNELS)
                                buffer.asShortBuffer().put(slice)
                                encoder.queueInputBuffer(
                                    inIdx, 0, frames * CHANNELS * 2, ptsUs(framePos), 0
                                )
                                framePos += frames
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = encoder.outputFormat
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                outBuf.get(bytes)
                                samples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            val fmt = outputFormat ?: return null
            if (samples.isEmpty()) return null
            return EncodedAudio(fmt, samples)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }
}
