package com.editpro.studio.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One file's decoded sound, read forwards as stereo frames.
 *
 * Frame 0 sits at source time [startSec]. The mixer asks for frames in rising order and says when
 * it has finished with the earlier ones, so an implementation only ever holds a short stretch of
 * the file however long the file is.
 */
interface PcmFeed {
    /** Frames per second of this stream. */
    val sampleRate: Int

    /** Source time, in seconds, of frame 0. */
    val startSec: Double

    /** Makes [frame] readable. False when the stream ended before it. */
    fun ensure(frame: Long): Boolean

    fun left(frame: Long): Short

    fun right(frame: Long): Short

    /** Nothing before [frame] will be asked for again. */
    fun release(frame: Long)
}

/**
 * The stretch of a decoded stream that is held in memory, always as interleaved stereo.
 *
 * Decoded chunks are appended at the end and frames that have been used are dropped from the
 * front. Frames keep their position in the stream, so `left(123456)` means the same frame
 * whatever has been dropped since.
 */
class PcmWindow {

    private var data = ShortArray(16384)

    /** Stream position of the first frame held. */
    var first = 0L
        private set

    /** Number of frames held. */
    var size = 0
        private set

    /** Stream position just past the last frame held. */
    val end: Long get() = first + size

    // A decoder is meant to hand over whole frames. If one ever splits a frame across two
    // chunks, the loose samples wait here for the rest instead of shifting every channel.
    private var carry = ShortArray(8)
    private var carried = 0

    private var gainsFor = 0
    private var leftGains = FloatArray(0)
    private var rightGains = FloatArray(0)

    fun has(frame: Long): Boolean = frame >= first && frame < first + size

    fun left(frame: Long): Short = data[((frame - first) * 2).toInt()]

    fun right(frame: Long): Short = data[((frame - first) * 2).toInt() + 1]

    /**
     * Appends [count] interleaved samples that have [channels] channels to a frame. Mono is
     * copied to both sides and anything wider than stereo is folded down
     * ([AudioDsp.downmixGains]).
     */
    fun append(samples: ShortArray, count: Int, channels: Int) {
        val ch = max(1, channels)
        var offset = 0
        var available = count
        if (carried > 0) {
            if (carry.size < ch) carry = carry.copyOf(ch)
            while (carried < ch && available > 0) {
                carry[carried++] = samples[offset++]
                available--
            }
            if (carried < ch) return
            appendFrames(carry, 0, 1, ch)
            carried = 0
        }
        val frames = available / ch
        appendFrames(samples, offset, frames, ch)
        val rest = available - frames * ch
        if (rest > 0) {
            if (carry.size < ch) carry = ShortArray(ch)
            System.arraycopy(samples, offset + frames * ch, carry, 0, rest)
            carried = rest
        }
    }

    private fun appendFrames(source: ShortArray, offset: Int, frames: Int, ch: Int) {
        if (frames <= 0) return
        val needed = (size + frames) * 2
        if (needed > data.size) data = data.copyOf(max(needed, data.size * 2))
        var out = size * 2
        var at = offset
        when (ch) {
            1 -> for (i in 0 until frames) {
                val s = source[at++]
                data[out++] = s
                data[out++] = s
            }
            2 -> System.arraycopy(source, offset, data, out, frames * 2)
            else -> {
                if (gainsFor != ch) {
                    val gains = AudioDsp.downmixGains(ch)
                    leftGains = gains.first
                    rightGains = gains.second
                    gainsFor = ch
                }
                for (i in 0 until frames) {
                    var l = 0f
                    var r = 0f
                    for (c in 0 until ch) {
                        val s = source[at + c]
                        l += s * leftGains[c]
                        r += s * rightGains[c]
                    }
                    at += ch
                    data[out++] = l.coerceIn(-32768f, 32767f).toInt().toShort()
                    data[out++] = r.coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }
        }
        size += frames
    }

    /** Drops every frame before [frame]. Dropping past the end leaves the window empty. */
    fun discardBefore(frame: Long) {
        val drop = min(max(0L, frame - first), size.toLong()).toInt()
        if (drop <= 0) return
        val keep = size - drop
        if (keep > 0) System.arraycopy(data, drop * 2, data, 0, keep * 2)
        first += drop
        size = keep
    }
}

/**
 * A [PcmFeed] over a decoder that is run a little at a time. A subclass supplies [pump], which
 * decodes the next piece into [window]; this class decides when more is needed and what can be
 * let go.
 */
abstract class WindowedPcmFeed : PcmFeed {

    protected val window = PcmWindow()

    /** Set once the source has nothing more to give. */
    protected var ended = false

    /** Decodes a little more into [window], or sets [ended] at the end of the stream. */
    protected abstract fun pump()

    final override fun ensure(frame: Long): Boolean {
        while (frame >= window.end && !ended) {
            // Reaching a point far ahead (a seek that landed early, or a file that cannot seek
            // at all) must not pile the stretch in between up in memory. The frame before the
            // one asked for is kept because the mixer blends each frame with its neighbour.
            if (window.size > SKIP_KEEP_FRAMES) window.discardBefore(min(frame - 1, window.end))
            pump()
        }
        return window.has(frame)
    }

    final override fun left(frame: Long): Short = window.left(frame)

    final override fun right(frame: Long): Short = window.right(frame)

    final override fun release(frame: Long) = window.discardBefore(frame)

    private companion object {
        const val SKIP_KEEP_FRAMES = 16384
    }
}

/**
 * One timeline clip's part in the mix: where it falls in the output, how its source is read
 * (trim, speed, resampling), and its EQ, compressor, volume, fades, automation and balance.
 *
 * The output is a stereo stream at [outputRate] whose frame 0 is timeline time [originSec] and
 * which is [totalFrames] long. It is filled a block at a time by [mixInto]; the result does not
 * depend on how the stream is cut into blocks.
 *
 * Clip `speed` moves the read position through the source, so pitch follows speed.
 */
class ClipVoice(
    private val clip: TimelineClip,
    private val clipStartSec: Double,
    private val clipDurationSec: Double,
    private val originSec: Double,
    totalFrames: Long,
    private val outputRate: Int
) {
    private val speed = clip.speed.coerceIn(0.25, 4.0)
    private val baseGain = (clip.volume / 100.0).coerceIn(0.0, 2.0)
    private val panLeft: Float
    private val panRight: Float
    private val chain = AudioDsp.ClipChain(outputRate, clip.audioEq, clip.audioComp)

    /** The first output frame the clip sounds on. */
    val firstFrame: Long = ((clipStartSec - originSec) * outputRate).toLong().coerceAtLeast(0L)

    /** The output frame just past the clip. */
    val endFrame: Long =
        ((clipStartSec + clipDurationSec - originSec) * outputRate).toLong().coerceAtMost(totalFrames)

    /** The source time the clip reads first, which is where decoding can begin. */
    val sourceStartSec: Double = clip.trimIn + max(0.0, originSec - clipStartSec) * speed

    init {
        val (l, r) = AudioDsp.panGains(clip.audioPan)
        panLeft = l
        panRight = r
    }

    /**
     * Adds this clip's sound to [block] — interleaved stereo holding [frames] output frames from
     * output frame [blockStart] on. Returns true when anything was added.
     */
    fun mixInto(feed: PcmFeed, block: FloatArray, blockStart: Long, frames: Int): Boolean {
        val from = max(firstFrame, blockStart)
        val until = min(endFrame, blockStart + frames)
        if (until <= from) return false

        val rate = feed.sampleRate
        val feedStart = feed.startSec
        var added = false
        var lastRead = -1L

        var n = from
        while (n < until) {
            val out = ((n - blockStart) * 2).toInt()
            val localT = originSec + n.toDouble() / outputRate - clipStartSec
            n++
            if (localT < 0.0) continue

            // Linear interpolation between the two source frames around the read position.
            val srcPos = (clip.trimIn + localT * speed - feedStart) * rate
            val i0 = floor(srcPos).toLong()
            if (i0 < 0L || !feed.ensure(i0)) continue
            val i1 = if (feed.ensure(i0 + 1)) i0 + 1 else i0
            lastRead = i0
            val frac = (srcPos - i0).toFloat()
            val l0 = feed.left(i0) / 32768f
            val r0 = feed.right(i0) / 32768f
            var left = l0 + (feed.left(i1) / 32768f - l0) * frac
            var right = r0 + (feed.right(i1) / 32768f - r0) * frac

            if (!chain.isBypassed) {
                chain.process(left, right)
                left = chain.outL
                right = chain.outR
            }

            val gain = (baseGain * TimelineEngine.audioEnvelope(clip, localT, clipDurationSec)).toFloat()
            if (gain == 0f) continue

            block[out] += left * gain * panLeft
            block[out + 1] += right * gain * panRight
            added = true
        }

        // The last frame read stays: the next block starts between it and its neighbour.
        if (lastRead >= 0L) feed.release(lastRead)
        return added
    }
}
