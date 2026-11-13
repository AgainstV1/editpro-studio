package com.editpro.studio.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The audio processing applied to each clip when a timeline is mixed for export: the 5-band EQ,
 * the bus compressor and the stereo balance, plus the conversions that turn whatever a decoder
 * hands back into 16-bit stereo. Pure arithmetic on samples, no Android classes, so all of it is
 * checked by unit tests instead of by ear.
 */
object AudioDsp {

    /** Centre frequencies of the inspector's five EQ sliders. */
    val EQ_FREQUENCIES = doubleArrayOf(60.0, 250.0, 1000.0, 4000.0, 12000.0)

    /**
     * Stereo balance for a pan of -100 (left) to +100 (right). Centre leaves both channels at
     * unity; moving off centre only ever attenuates the far side, so panning cannot clip.
     */
    fun panGains(pan: Double): Pair<Float, Float> {
        val p = pan.coerceIn(-100.0, 100.0) / 100.0
        val left = if (p <= 0.0) 1.0 else 1.0 - p
        val right = if (p >= 0.0) 1.0 else 1.0 + p
        return left.toFloat() to right.toFloat()
    }

    /**
     * Keeps a summed mix below full scale by bending the last 10% instead of hard-clipping it.
     * The ceiling is 0.999, so the result can never reach full scale.
     */
    fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val shaped = 0.9f + 0.099f * tanh(((a - 0.9f) / 0.1f).toDouble()).toFloat()
        return if (x < 0f) -shaped else shaped
    }

    // The sample layouts a decoder can hand back. The numbers are android.media.AudioFormat's
    // ENCODING_PCM_* values, repeated here so this file stays free of Android classes.
    const val PCM_16BIT = 2
    const val PCM_8BIT = 3
    const val PCM_FLOAT = 4
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22

    /**
     * Reads [bytes] bytes of decoded sound, starting at the buffer's position and in the buffer's
     * byte order, into [out] as 16-bit samples, and returns how many samples that made. [out]
     * needs room for [bytes] samples.
     *
     * Decoders nearly always produce 16-bit sound, but a 24-bit or floating-point file can come
     * back in its own layout, and reading that as 16-bit turns it into noise.
     */
    fun pcmToShorts(buffer: ByteBuffer, bytes: Int, encoding: Int, out: ShortArray): Int {
        val start = buffer.position()
        when (encoding) {
            PCM_8BIT -> {
                // Unsigned, with silence at 128.
                for (i in 0 until bytes) {
                    out[i] = (((buffer.get(start + i).toInt() and 0xFF) - 128) shl 8).toShort()
                }
                return bytes
            }
            PCM_FLOAT -> {
                val count = bytes / 4
                for (i in 0 until count) {
                    val v = buffer.getFloat(start + i * 4)
                    out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                return count
            }
            PCM_24BIT_PACKED -> {
                // Three bytes a sample; the top two are the 16 bits kept.
                val count = bytes / 3
                val little = buffer.order() == ByteOrder.LITTLE_ENDIAN
                for (i in 0 until count) {
                    val at = start + i * 3
                    val high = buffer.get(if (little) at + 2 else at).toInt()
                    val mid = buffer.get(at + 1).toInt() and 0xFF
                    out[i] = ((high shl 8) or mid).toShort()
                }
                return count
            }
            PCM_32BIT -> {
                val count = bytes / 4
                for (i in 0 until count) out[i] = (buffer.getInt(start + i * 4) shr 16).toShort()
                return count
            }
            else -> {
                val count = bytes / 2
                buffer.asShortBuffer().get(out, 0, count)
                return count
            }
        }
    }

    /**
     * How much of each channel of a stream goes to the left and to the right of a stereo mix.
     *
     * Mono goes to both sides and stereo is left alone. Surround is folded down the standard way:
     * the centre (where dialogue lives) and the rear channels join their side 3 dB down and the
     * LFE channel is left out, then everything is halved so a busy mix has room to fold without
     * clipping. Channels are in Android's order: front left, front right, centre, LFE, back left,
     * back right, then back centre (6.1) or side left and side right (7.1).
     */
    fun downmixGains(channels: Int): Pair<FloatArray, FloatArray> {
        val n = max(1, channels)
        val left = FloatArray(n)
        val right = FloatArray(n)
        if (n == 1) {
            left[0] = 1f
            right[0] = 1f
            return left to right
        }
        left[0] = 1f
        right[1] = 1f
        if (n == 2) return left to right

        val minus3dB = 0.7071f
        when (n) {
            3 -> {
                left[2] = minus3dB
                right[2] = minus3dB
            }
            4 -> {
                // Four channels are either quad or front + centre + rear centre, and nothing
                // says which, so the extra pair is shared between the sides.
                for (c in 2..3) {
                    left[c] = 0.5f
                    right[c] = 0.5f
                }
            }
            5 -> {
                left[2] = minus3dB
                right[2] = minus3dB
                left[3] = minus3dB
                right[4] = minus3dB
            }
            else -> {
                left[2] = minus3dB
                right[2] = minus3dB
                // Channel 3 is the LFE.
                left[4] = minus3dB
                right[5] = minus3dB
                if (n == 7) {
                    left[6] = 0.5f
                    right[6] = 0.5f
                } else if (n >= 8) {
                    left[6] = minus3dB
                    right[7] = minus3dB
                }
            }
        }
        for (c in 0 until n) {
            left[c] *= 0.5f
            right[c] *= 0.5f
        }
        return left to right
    }

    /**
     * Coefficients of a peaking EQ band (RBJ audio-EQ cookbook), normalised by a0 and returned as
     * b0, b1, b2, a1, a2.
     */
    fun peakingCoefficients(sampleRate: Int, frequency: Double, gainDb: Double, q: Double = 1.0): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequency / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha * a) / a0,
            (-2.0 * cosW0) / a0,
            (1.0 - alpha / a) / a0
        )
    }

    /**
     * One clip's processing chain: EQ bands in series, then a stereo-linked compressor.
     *
     * Stateful — feed it the clip's samples in order through [process] and read [outL] / [outR].
     * [isBypassed] is true when every control is at rest, so untouched clips cost nothing.
     *
     * @param eqDb gains for [EQ_FREQUENCIES] in dB (-12..+12)
     * @param compAmount the "Bus Compressor / Glue" slider, 0..100
     */
    class ClipChain(sampleRate: Int, eqDb: List<Double>, compAmount: Double) {

        private val coefficients: List<DoubleArray>
        private val state: List<DoubleArray>

        private val compOn = compAmount > 0.5
        private val thresholdDb: Double
        private val slope: Double
        private val makeup: Double
        private val attack: Double
        private val release: Double
        private var envelope = 0.0

        var outL = 0f
            private set
        var outR = 0f
            private set

        val isBypassed: Boolean

        init {
            val bands = ArrayList<DoubleArray>()
            EQ_FREQUENCIES.forEachIndexed { i, freq ->
                val gain = (eqDb.getOrNull(i) ?: 0.0).coerceIn(-18.0, 18.0)
                // A band at or above Nyquist cannot be represented at this sample rate.
                if (abs(gain) >= 0.05 && freq < sampleRate * 0.45) {
                    bands.add(peakingCoefficients(sampleRate, freq, gain))
                }
            }
            coefficients = bands
            state = bands.map { DoubleArray(4) }

            val amount = (compAmount / 100.0).coerceIn(0.0, 1.0)
            thresholdDb = -6.0 - 18.0 * amount
            val ratio = 1.0 + 5.0 * amount
            slope = 1.0 - 1.0 / ratio
            // Give back half of what the compressor takes at the threshold, so "glue" is not
            // simply "quieter".
            makeup = 10.0.pow((-thresholdDb * slope * 0.5) / 20.0)
            attack = exp(-1.0 / (0.005 * sampleRate))
            release = exp(-1.0 / (0.120 * sampleRate))
            isBypassed = bands.isEmpty() && !compOn
        }

        fun process(inL: Float, inR: Float) {
            var l = inL.toDouble()
            var r = inR.toDouble()
            for (i in coefficients.indices) {
                val c = coefficients[i]
                val z = state[i]
                // Transposed direct form II, one state pair per channel.
                val yl = c[0] * l + z[0]
                z[0] = c[1] * l - c[3] * yl + z[1]
                z[1] = c[2] * l - c[4] * yl
                l = yl
                val yr = c[0] * r + z[2]
                z[2] = c[1] * r - c[3] * yr + z[3]
                z[3] = c[2] * r - c[4] * yr
                r = yr
            }
            if (compOn) {
                val level = max(abs(l), abs(r))
                val coef = if (level > envelope) attack else release
                envelope = coef * envelope + (1.0 - coef) * level
                val levelDb = 20.0 * log10(max(envelope, 1e-6))
                val reductionDb = if (levelDb > thresholdDb) (levelDb - thresholdDb) * slope else 0.0
                val gain = 10.0.pow(-reductionDb / 20.0) * makeup
                l *= gain
                r *= gain
            }
            outL = l.toFloat()
            outR = r.toFloat()
        }
    }
}
