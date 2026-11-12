package com.editpro.studio.model

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
 * the bus compressor and the stereo balance. Pure arithmetic on samples, no Android classes, so
 * the filters are checked by unit tests instead of by ear.
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
