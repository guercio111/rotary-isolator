package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Dense, diffuse algorithmic reverb return.
 *
 * An eight-line feedback-delay network (orthogonal Hadamard feedback matrix)
 * replaces the audible parallel-comb character of the previous experiment.
 * Input and output all-pass diffusion soften the onset and merge the late tail.
 */
internal class SchroederReverb(private val sampleRate: Int) {
    private class AllPass(delay: Int, private val coefficient: Float) {
        private val data = FloatArray(delay.coerceAtLeast(1))
        private var index = 0

        fun tick(input: Float): Float {
            val delayed = data[index]
            val output = delayed - coefficient * input
            data[index] = input + coefficient * output
            index++
            if (index == data.size) index = 0
            return output
        }

        fun clear() {
            data.fill(0f)
            index = 0
        }
    }

    private class DelayLine(delay: Int) {
        private val data = FloatArray(delay.coerceAtLeast(1))
        private var index = 0
        var damped = 0f
            private set

        fun read(damping: Float): Float {
            val delayed = data[index]
            damped = delayed * (1f - damping) + damped * damping
            return damped
        }

        fun write(value: Float) {
            data[index] = value
            index++
            if (index == data.size) index = 0
        }

        fun clear() {
            data.fill(0f)
            index = 0
            damped = 0f
        }
    }

    // Incommensurate delay lengths reduce obvious periodic ringing.
    private val delayTimes = floatArrayOf(
        0.0293f, 0.0317f, 0.0341f, 0.0367f,
        0.0391f, 0.0419f, 0.0443f, 0.0473f
    )
    private val lines = delayTimes.map { DelayLine((sampleRate * it).toInt()) }.toTypedArray()
    private val inputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0031f).toInt(), 0.62f),
        AllPass((sampleRate * 0.0047f).toInt(), 0.62f),
        AllPass((sampleRate * 0.0073f).toInt(), 0.62f)
    )
    private val outputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0053f).toInt(), 0.68f),
        AllPass((sampleRate * 0.0079f).toInt(), 0.68f),
        AllPass((sampleRate * 0.0107f).toInt(), 0.68f),
        AllPass((sampleRate * 0.0131f).toInt(), 0.68f)
    )

    private val matrix = FloatArray(8)
    private var hpX = 0f
    private var hpY = 0f
    private var lpY = 0f
    private var limiter = 1f

    fun clear() {
        lines.forEach { it.clear() }
        inputDiffusers.forEach { it.clear() }
        outputDiffusers.forEach { it.clear() }
        matrix.fill(0f)
        hpX = 0f
        hpY = 0f
        lpY = 0f
        limiter = 1f
    }

    // Normalized 8-point Hadamard transform: an energy-preserving feedback matrix.
    private fun hadamard(v: FloatArray) {
        var step = 1
        while (step < 8) {
            var base = 0
            while (base < 8) {
                for (j in 0 until step) {
                    val a = v[base + j]
                    val b = v[base + j + step]
                    v[base + j] = a + b
                    v[base + j + step] = a - b
                }
                base += step * 2
            }
            step *= 2
        }
        val norm = 0.35355339f
        for (i in 0 until 8) v[i] *= norm
    }

    fun process(
        input: ShortArray,
        output: ShortArray,
        count: Int,
        mixPercent: Int,
        roomSize: Int,
        hpHz: Int,
        lpHz: Int,
        freeze: Boolean
    ) {
        val mix = mixPercent.coerceIn(0, 100) / 100f
        val size = roomSize.coerceIn(0, 1000) / 1000f
        val hp = hpHz.coerceIn(20, 1200).toFloat()
        val lp = lpHz.coerceIn(1000, 20000).toFloat()
        val hpA = exp((-2.0 * PI * hp / sampleRate).toFloat())
        val lpA = (1f - exp((-2.0 * PI * lp / sampleRate).toFloat())).coerceIn(0.001f, 1f)

        // A long but controlled room tail; freeze is bounded to avoid runaway.
        val feedback = if (freeze) 0.985f else (0.885f + size * 0.078f).coerceIn(0.885f, 0.963f)
        val damping = if (freeze) 0.025f else (0.08f + (1f - size) * 0.24f)

        for (i in 0 until count) {
            val x = input[i] / 32768f
            val hpOut = hpA * (hpY + x - hpX)
            hpX = x
            hpY = hpOut
            lpY += lpA * (hpOut - lpY)

            var excitation = if (freeze) 0f else lpY * 0.34f
            for (diffuser in inputDiffusers) excitation = diffuser.tick(excitation)

            var wet = 0f
            for (n in 0 until 8) {
                val delayed = lines[n].read(damping)
                matrix[n] = delayed
                wet += delayed
            }

            // Orthogonal feedback disperses energy across all delay paths instead
            // of returning eight individually recognizable echo trains.
            hadamard(matrix)
            for (n in 0 until 8) {
                val polarity = if ((n and 1) == 0) 1f else -1f
                lines[n].write(excitation * polarity + matrix[n] * feedback)
            }

            wet *= 0.35355339f
            for (diffuser in outputDiffusers) wet = diffuser.tick(wet)

            val raw = wet * mix * 0.92f
            val magnitude = abs(raw)
            val target = if (magnitude > 0.78f) 0.78f / magnitude else 1f
            limiter = if (target < limiter) target else limiter + (1f - limiter) * 0.002f
            val safe = (raw * limiter).coerceIn(-0.78f, 0.78f)
            output[i] = (safe * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
