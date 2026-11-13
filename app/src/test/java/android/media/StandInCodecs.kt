/*
 * Stand-ins for the android.media classes that TimelineAudioEncoder drives, so that its
 * decode → mix → encode loop can run in a plain JVM unit test (AudioExportTest).
 *
 * Unit tests run against an android.jar whose methods do nothing. Gradle puts that jar last on
 * the test classpath precisely so that classes like these can take its place; nothing here is
 * part of the app. Only the methods the encoder calls exist.
 *
 * What is imitated is the buffer protocol, not the codecs:
 *  - a "media file" is a few key=value lines describing a steady tone (see MediaExtractor);
 *  - the "decoder" hands the tone back as PCM, one packet late, announces its format before the
 *    first chunk, refuses input while output is waiting, and gives read-only buffers that do not
 *    start at offset zero;
 *  - the "encoder" emits one frame per 1024 frames of input, after a codec-config buffer, and
 *    each frame carries the PCM it was given so a test can read the mix back.
 *
 * So a test built on these shows that the encoder's bookkeeping is right — every chunk in its
 * place, every buffer returned, every codec released, each failure reported. It says nothing
 * about how a real codec sounds or how long it takes; that is what the device checklist in
 * docs/MAINTENANCE.md is for.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package android.media

import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Switches and counters for the stand-ins. A test calls [reset] before it starts. */
object StandInCodecs {

    // ------------------------------------------------ what a test can make go wrong

    /** `MediaCodec.createEncoderByType` throws. */
    var encoderCannotBeCreated = false

    /** The encoder flags the end of the stream on its last frame instead of on an empty buffer. */
    var encoderEndsOnItsLastFrame = false

    /** The encoder never signals the end of the stream. */
    var encoderNeverEnds = false

    /** Encoded frames carry all of their PCM. Turned off for mixes too long to hold in memory. */
    var encoderKeepsSound = true

    // -------------------------------------------------- what a test can check after

    var openCodecs = 0
    var mostOpenCodecs = 0
    var codecsCreated = 0
    var encodersCreated = 0
    var openExtractors = 0
    var seeks = 0
    var packetsDecoded = 0L
    var framesEncoded = 0L

    /** Encoder input buffers whose timestamp did not match the frames queued before them. */
    var encoderTimestampErrors = 0

    /** Encoder input buffers that did not hold exactly one AAC frame. */
    var encoderPartFrames = 0

    fun reset() {
        encoderCannotBeCreated = false
        encoderEndsOnItsLastFrame = false
        encoderNeverEnds = false
        encoderKeepsSound = true
        openCodecs = 0
        mostOpenCodecs = 0
        codecsCreated = 0
        encodersCreated = 0
        openExtractors = 0
        seeks = 0
        packetsDecoded = 0L
        framesEncoded = 0L
        encoderTimestampErrors = 0
        encoderPartFrames = 0
    }
}

class MediaFormat {
    private val values = HashMap<String, Any>()

    fun getString(name: String): String? = values[name] as? String
    fun getInteger(name: String): Int = values[name] as? Int ?: throw NullPointerException("no $name in the format")
    fun containsKey(name: String): Boolean = values.containsKey(name)
    fun setInteger(name: String, value: Int) {
        values[name] = value
    }

    fun setString(name: String, value: String?) {
        if (value != null) values[name] = value
    }

    companion object {
        @JvmStatic
        fun createAudioFormat(mime: String, sampleRate: Int, channelCount: Int): MediaFormat = MediaFormat().apply {
            setString("mime", mime)
            setInteger("sample-rate", sampleRate)
            setInteger("channel-count", channelCount)
        }
    }
}

/**
 * Reads a made-up media file: a text file of `key=value` lines.
 *
 *  - `rate`, `channels`, `seconds`: the shape and length of the audio stream;
 *  - `tone`, `amp`: the sine it holds (the right channel of a stereo file is a fifth higher, so
 *    swapped channels show); `onlyChannel` puts the tone on one channel of a surround stream;
 *  - `encoding`: the sample layout, as an `AudioFormat.ENCODING_PCM_*` number;
 *  - `packet`: frames per packet; `startUs`: the timestamp of the first frame;
 *  - `containerRate`, `containerChannels`: what the file claims, when that differs from what
 *    the decoder produces (as HE-AAC files do);
 *  - `mime`: `audio/unsupported` has no decoder;
 *  - `audio=false`: no audio track; `corrupt=true`: cannot be opened; `noSeek=true`: every
 *    seek lands at the start;
 *  - `failAt`, `stallAt`: the source time at which the decoder throws or stops answering;
 *    `neverEnds=true`: the decoder never signals the end of the stream.
 */
class MediaExtractor {
    private val props = HashMap<String, String>()
    private var hasAudio = true
    private var rate = 48000
    private var channels = 2
    private var frames = 0L
    private var tone = 440.0
    private var amp = 8000.0
    private var encoding = 2
    private var packetFrames = 1024
    private var startUs = 0L
    private var noSeek = false
    private var onlyChannel = -1
    private var packet = 0L
    private var packets = 0L
    private var released = false

    init {
        StandInCodecs.openExtractors++
    }

    fun setDataSource(path: String) {
        File(path).readLines().forEach { line ->
            val at = line.indexOf('=')
            if (at > 0) props[line.substring(0, at).trim()] = line.substring(at + 1).trim()
        }
        if (props["corrupt"] == "true") throw IOException("not a media file")
        hasAudio = props["audio"] != "false"
        rate = props["rate"]?.toInt() ?: rate
        channels = props["channels"]?.toInt() ?: channels
        tone = props["tone"]?.toDouble() ?: tone
        amp = props["amp"]?.toDouble() ?: amp
        encoding = props["encoding"]?.toInt() ?: encoding
        packetFrames = props["packet"]?.toInt() ?: packetFrames
        startUs = props["startUs"]?.toLong() ?: startUs
        noSeek = props["noSeek"] == "true"
        onlyChannel = props["onlyChannel"]?.toInt() ?: onlyChannel
        frames = ceil((props["seconds"]?.toDouble() ?: 1.0) * rate).toLong()
        packets = (frames + packetFrames - 1) / packetFrames
    }

    val trackCount: Int get() = if (hasAudio) 2 else 1

    /** Track 0 is the picture; track 1, when there is one, is the sound. */
    fun getTrackFormat(index: Int): MediaFormat {
        val format = MediaFormat()
        if (index == 0) {
            format.setString("mime", "video/avc")
            return format
        }
        format.setString("mime", props["mime"] ?: "audio/mp4a-latm")
        format.setInteger("sample-rate", props["containerRate"]?.toInt() ?: rate)
        format.setInteger("channel-count", props["containerChannels"]?.toInt() ?: channels)
        // Not real format keys: how the stand-in decoder learns what it is to produce.
        format.setInteger("standin-rate", rate)
        format.setInteger("standin-channels", channels)
        format.setInteger("standin-encoding", encoding)
        format.setString("standin-fail-at", props["failAt"])
        format.setString("standin-stall-at", props["stallAt"])
        format.setString("standin-never-ends", props["neverEnds"])
        return format
    }

    fun selectTrack(index: Int) {
        check(index == 1) { "selected a track that is not audio" }
    }

    fun seekTo(timeUs: Long, mode: Int) {
        StandInCodecs.seeks++
        // The packet that holds the time asked for, as SEEK_TO_PREVIOUS_SYNC gives.
        packet = if (noSeek) 0L else Math.floorDiv((timeUs - startUs) * rate, 1_000_000L * packetFrames).coerceIn(0L, packets)
    }

    val sampleTime: Long get() = if (packet >= packets) -1L else startUs + packet * packetFrames * 1_000_000L / rate

    fun advance(): Boolean {
        packet++
        return packet < packets
    }

    private fun sample(frame: Long, channel: Int): Double {
        if (onlyChannel >= 0 && channel != onlyChannel) return 0.0
        val hz = if (channels == 2 && channel == 1) tone * 1.5 else tone
        return amp * sin(2.0 * PI * hz * frame / rate)
    }

    fun readSampleData(byteBuf: ByteBuffer, offset: Int): Int {
        check(!released) { "read after release" }
        if (packet >= packets) return -1
        val first = packet * packetFrames
        val last = minOf(first + packetFrames, frames)
        byteBuf.clear()
        byteBuf.position(offset)
        val out = byteBuf.order(ByteOrder.nativeOrder())
        for (frame in first until last) {
            for (channel in 0 until channels) {
                val v = sample(frame, channel).toInt()
                when (encoding) {
                    ENCODING_8BIT -> out.put(((v shr 8) + 128).toByte())
                    ENCODING_FLOAT -> out.putFloat(v / 32768f)
                    ENCODING_24BIT_PACKED -> {
                        val wide = v shl 8
                        val bytes = byteArrayOf(wide.toByte(), (wide shr 8).toByte(), (wide shr 16).toByte())
                        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) bytes.reverse()
                        out.put(bytes)
                    }
                    ENCODING_32BIT -> out.putInt(v shl 16)
                    else -> out.putShort(v.toShort())
                }
            }
        }
        return out.position() - offset
    }

    fun release() {
        if (!released) {
            released = true
            StandInCodecs.openExtractors--
        }
    }

    private companion object {
        const val ENCODING_8BIT = 3
        const val ENCODING_FLOAT = 4
        const val ENCODING_24BIT_PACKED = 21
        const val ENCODING_32BIT = 22
    }
}

class MediaCodec private constructor(private val work: Work) {

    class BufferInfo {
        @JvmField var offset = 0
        @JvmField var size = 0
        @JvmField var presentationTimeUs = 0L
        @JvmField var flags = 0

        fun set(newOffset: Int, newSize: Int, newTimeUs: Long, newFlags: Int) {
            offset = newOffset
            size = newSize
            presentationTimeUs = newTimeUs
            flags = newFlags
        }
    }

    /** What differs between the stand-in decoder and the stand-in encoder. */
    internal interface Work {
        fun configure(format: MediaFormat)
        fun dequeueInput(): Int
        fun inputBuffer(index: Int): ByteBuffer
        fun queueInput(index: Int, offset: Int, size: Int, timeUs: Long, flags: Int)
        fun dequeueOutput(info: BufferInfo): Int
        fun outputBuffer(index: Int): ByteBuffer
        fun outputFormat(): MediaFormat
        fun releaseOutput(index: Int)
    }

    private var started = false
    private var released = false

    fun configure(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        work.configure(format ?: throw IllegalArgumentException("no format"))
    }

    fun start() {
        started = true
    }

    fun stop() {
        check(!released) { "stopped after release" }
        started = false
    }

    fun release() {
        if (!released) {
            released = true
            StandInCodecs.openCodecs--
        }
    }

    private fun running() = check(started && !released) { "codec used while it was not running" }

    fun dequeueInputBuffer(timeoutUs: Long): Int {
        running()
        return work.dequeueInput()
    }

    fun getInputBuffer(index: Int): ByteBuffer? {
        running()
        return work.inputBuffer(index)
    }

    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        running()
        work.queueInput(index, offset, size, presentationTimeUs, flags)
    }

    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int {
        running()
        return work.dequeueOutput(info)
    }

    fun getOutputBuffer(index: Int): ByteBuffer? {
        running()
        return work.outputBuffer(index)
    }

    val outputFormat: MediaFormat get() = work.outputFormat()

    fun releaseOutputBuffer(index: Int, render: Boolean) {
        running()
        work.releaseOutput(index)
    }

    companion object {
        @JvmStatic
        fun createDecoderByType(type: String): MediaCodec {
            if (type == "audio/unsupported") throw IllegalArgumentException("no decoder for $type")
            return opened(MediaCodec(StandInDecoder()))
        }

        @JvmStatic
        fun createEncoderByType(type: String): MediaCodec {
            if (StandInCodecs.encoderCannotBeCreated) throw IOException("no encoder for $type")
            StandInCodecs.encodersCreated++
            return opened(MediaCodec(StandInEncoder()))
        }

        private fun opened(codec: MediaCodec): MediaCodec {
            StandInCodecs.codecsCreated++
            StandInCodecs.openCodecs++
            if (StandInCodecs.openCodecs > StandInCodecs.mostOpenCodecs) StandInCodecs.mostOpenCodecs = StandInCodecs.openCodecs
            return codec
        }
    }
}

// The values of MediaCodec's constants that the stand-ins hand out.
private const val TRY_AGAIN_LATER = -1
private const val OUTPUT_FORMAT_CHANGED = -2
private const val FLAG_KEY_FRAME = 1
private const val FLAG_CODEC_CONFIG = 2
private const val FLAG_END_OF_STREAM = 4

private class Chunk(val data: ByteArray, val timeUs: Long, val flags: Int)

/** The input side both stand-ins share: a few buffers that are lent out and handed back. */
private class InputBuffers(count: Int, size: Int) {
    private val buffers = Array(count) { ByteBuffer.allocateDirect(size) }
    private val lent = BooleanArray(count)

    fun lend(): Int {
        val index = lent.indexOfFirst { !it }
        if (index >= 0) lent[index] = true
        return index
    }

    fun buffer(index: Int): ByteBuffer {
        check(lent[index]) { "asked for an input buffer that was not dequeued" }
        return buffers[index].also { it.clear() }
    }

    fun takeBack(index: Int, offset: Int, size: Int): ByteArray {
        check(lent[index]) { "queued an input buffer that was not dequeued" }
        lent[index] = false
        val bytes = ByteArray(size)
        val from = buffers[index].duplicate()
        from.position(offset)
        from.limit(offset + size)
        from.get(bytes)
        return bytes
    }
}

/** The output side both stand-ins share: chunks that are lent out until they are released. */
private class OutputBuffers(private val offset: Int) {
    private val lent = HashMap<Int, Chunk>()
    private var next = 0

    /** Lends [chunk] and fills [info] the way a codec does. */
    fun lend(chunk: Chunk, info: MediaCodec.BufferInfo): Int {
        val index = next++
        lent[index] = chunk
        info.set(if (chunk.data.isEmpty()) 0 else offset, chunk.data.size, chunk.timeUs, chunk.flags)
        return index
    }

    /** Read-only and in the JVM's default byte order, so the caller has to set both itself. */
    fun buffer(index: Int): ByteBuffer {
        val chunk = lent[index] ?: throw IllegalStateException("asked for an output buffer that was not dequeued")
        val buffer = ByteBuffer.allocateDirect(chunk.data.size + offset + 16)
        buffer.position(offset)
        buffer.put(chunk.data)
        buffer.position(0)
        return buffer.asReadOnlyBuffer()
    }

    fun release(index: Int) {
        lent.remove(index) ?: throw IllegalStateException("released an output buffer that was not dequeued")
    }
}

/** Passes PCM packets through one packet late, holding at most four that are not yet collected. */
private class StandInDecoder : MediaCodec.Work {
    private val inputs = InputBuffers(4, 1 shl 20)
    private val outputs = OutputBuffers(offset = 16)
    private val waiting = ArrayDeque<Chunk>()
    private var formatAnnounced = false
    private var inputEnded = false
    private var endSent = false
    private var stalled = false
    private var rate = 48000
    private var channels = 2
    private var encoding = 2
    private var failAtUs = Long.MAX_VALUE
    private var stallAtUs = Long.MAX_VALUE
    private var neverEnds = false

    override fun configure(format: MediaFormat) {
        rate = format.getInteger("standin-rate")
        channels = format.getInteger("standin-channels")
        encoding = format.getInteger("standin-encoding")
        format.getString("standin-fail-at")?.let { failAtUs = (it.toDouble() * 1_000_000.0).toLong() }
        format.getString("standin-stall-at")?.let { stallAtUs = (it.toDouble() * 1_000_000.0).toLong() }
        neverEnds = format.getString("standin-never-ends") == "true"
    }

    // No room for input while decoded sound is waiting to be collected.
    override fun dequeueInput(): Int = if (stalled || inputEnded || waiting.size >= 4) TRY_AGAIN_LATER else inputs.lend()

    override fun inputBuffer(index: Int): ByteBuffer = inputs.buffer(index)

    override fun queueInput(index: Int, offset: Int, size: Int, timeUs: Long, flags: Int) {
        val bytes = inputs.takeBack(index, offset, size)
        if (flags and FLAG_END_OF_STREAM != 0) {
            inputEnded = true
            return
        }
        waiting.addLast(Chunk(bytes, timeUs, 0))
        StandInCodecs.packetsDecoded++
    }

    override fun dequeueOutput(info: MediaCodec.BufferInfo): Int {
        if (stalled) return TRY_AGAIN_LATER
        // One packet of latency: nothing comes out until a second packet has gone in.
        val ready = waiting.size >= 2 || (inputEnded && waiting.isNotEmpty())
        if (!ready) {
            if (inputEnded && !endSent && !neverEnds) {
                endSent = true
                return outputs.lend(Chunk(ByteArray(0), 0L, FLAG_END_OF_STREAM), info)
            }
            return TRY_AGAIN_LATER
        }
        if (!formatAnnounced) {
            formatAnnounced = true
            return OUTPUT_FORMAT_CHANGED
        }
        val chunk = waiting.first()
        if (chunk.timeUs >= failAtUs) throw IllegalStateException("the decoder died")
        if (chunk.timeUs >= stallAtUs) {
            stalled = true
            return TRY_AGAIN_LATER
        }
        waiting.removeFirst()
        return outputs.lend(chunk, info)
    }

    override fun outputBuffer(index: Int): ByteBuffer = outputs.buffer(index)

    override fun outputFormat(): MediaFormat = MediaFormat().apply {
        setInteger("sample-rate", rate)
        setInteger("channel-count", channels)
        // As on a device, 16-bit output goes without saying.
        if (encoding != 2) setInteger("pcm-encoding", encoding)
    }

    override fun releaseOutput(index: Int) = outputs.release(index)
}

/**
 * Stands in for the AAC encoder: one output frame for every 1024 frames of input, carrying that
 * input, after a codec-config buffer that is not sound.
 */
private class StandInEncoder : MediaCodec.Work {
    private val inputs = InputBuffers(4, 16384)
    private val outputs = OutputBuffers(offset = 8)
    private val waiting = ArrayDeque<Chunk>()
    private var formatAnnounced = false
    private var configSent = false
    private var inputEnded = false
    private var endSent = false
    private var framesIn = 0L
    private var framesOut = 0L
    private var pending = ByteArray(0)

    override fun configure(format: MediaFormat) {
        check(format.getInteger("sample-rate") == RATE && format.getInteger("channel-count") == 2) { "not 44.1 kHz stereo" }
        check(format.getInteger("bitrate") > 0) { "no bit rate" }
    }

    override fun dequeueInput(): Int = if (inputEnded || waiting.size >= 8) TRY_AGAIN_LATER else inputs.lend()

    override fun inputBuffer(index: Int): ByteBuffer = inputs.buffer(index)

    private fun timeUs(frame: Long) = frame * 1_000_000L / RATE

    private fun emit(bytes: ByteArray, flags: Int) {
        val kept = if (StandInCodecs.encoderKeepsSound) bytes else bytes.copyOf(minOf(bytes.size, 8))
        waiting.addLast(Chunk(kept, timeUs(framesOut), flags))
        framesOut += FRAME
    }

    override fun queueInput(index: Int, offset: Int, size: Int, timeUs: Long, flags: Int) {
        val bytes = inputs.takeBack(index, offset, size)
        if (flags and FLAG_END_OF_STREAM != 0) {
            check(size == 0) { "the end-of-stream buffer carried sound" }
            inputEnded = true
            val endsHere = if (StandInCodecs.encoderEndsOnItsLastFrame) FLAG_END_OF_STREAM else 0
            if (pending.isNotEmpty()) {
                emit(pending, FLAG_KEY_FRAME or endsHere)
                pending = ByteArray(0)
            } else if (endsHere != 0) {
                waiting.addLast(Chunk(ByteArray(0), 0L, FLAG_END_OF_STREAM))
            }
            return
        }
        check(size > 0 && size % BYTES_PER_FRAME == 0) { "queued $size bytes, which is not whole stereo frames" }
        if (size != FRAME * BYTES_PER_FRAME) StandInCodecs.encoderPartFrames++
        if (timeUs != timeUs(framesIn)) StandInCodecs.encoderTimestampErrors++
        framesIn += size / BYTES_PER_FRAME
        StandInCodecs.framesEncoded += size / BYTES_PER_FRAME
        pending += bytes
        var at = 0
        while (pending.size - at >= FRAME * BYTES_PER_FRAME) {
            emit(pending.copyOfRange(at, at + FRAME * BYTES_PER_FRAME), FLAG_KEY_FRAME)
            at += FRAME * BYTES_PER_FRAME
        }
        if (at > 0) pending = pending.copyOfRange(at, pending.size)
    }

    override fun dequeueOutput(info: MediaCodec.BufferInfo): Int {
        if (waiting.isEmpty()) {
            val endsApart = !StandInCodecs.encoderEndsOnItsLastFrame && !StandInCodecs.encoderNeverEnds
            if (inputEnded && !endSent && endsApart) {
                endSent = true
                return outputs.lend(Chunk(ByteArray(0), 0L, FLAG_END_OF_STREAM), info)
            }
            return TRY_AGAIN_LATER
        }
        if (!formatAnnounced) {
            formatAnnounced = true
            return OUTPUT_FORMAT_CHANGED
        }
        if (!configSent) {
            // A real encoder hands its codec-specific data over as a buffer of its own.
            configSent = true
            return outputs.lend(Chunk(byteArrayOf(0x12, 0x10), 0L, FLAG_CODEC_CONFIG), info)
        }
        return outputs.lend(waiting.removeFirst(), info)
    }

    override fun outputBuffer(index: Int): ByteBuffer = outputs.buffer(index)

    override fun outputFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2)

    override fun releaseOutput(index: Int) = outputs.release(index)

    private companion object {
        const val RATE = 44100
        const val FRAME = 1024
        const val BYTES_PER_FRAME = 4
    }
}
