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
    // Longer incommensurate delays disperse the tail and reduce the
    // beat-like, metallic comb pattern.
    private val delayTimes = floatArrayOf(
        0.0531f, 0.0617f, 0.0709f, 0.0823f,
        0.0957f, 0.1091f, 0.1237f, 0.1391f
    )
    private val lines = delayTimes.map { DelayLine((sampleRate * it).toInt()) }.toTypedArray()
    private val inputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0037f).toInt(), 0.62f),
        AllPass((sampleRate * 0.0053f).toInt(), 0.62f),
        AllPass((sampleRate * 0.0079f).toInt(), 0.62f),
        AllPass((sampleRate * 0.0113f).toInt(), 0.62f)
    )
    private val outputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0041f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0067f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0097f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0131f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0173f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0217f).toInt(), 0.66f),
        AllPass((sampleRate * 0.0263f).toInt(), 0.66f)
    )

    // Use different, non-eigenvector injection and pickup patterns.
    // The previous identical alternating pattern was a Hadamard eigenvector:
    // it excited and listened to essentially one feedback mode, creating a
    // beat-synchronous metallic pulse. These two independent patterns engage
    // the full network and average its modes into a smoother continuous tail.
    private val inputPattern = intArrayOf(1, 1, -1, 1, -1, -1, 1, -1)
    private val outputPattern = intArrayOf(1, -1, -1, 1, 1, -1, 1, -1)
    private val matrix = FloatArray(8)
    private var hpX = 0f
    private var hpY = 0f
    private var hp2X = 0f
    private var hp2Y = 0f
    private var lpY = 0f
    private var limiter = 1f
    private var smoothedMix = 0f
    // A separate, low-feedback quarter-note echo return. The reverb network
    // above is left unchanged so a zero delay setting is bit-for-bit equivalent.
    private val delayBuffer = FloatArray(sampleRate * 2)
    private var delayWriteIndex = 0
    private var smoothedDelayMix = 0f
    private var smoothedDelaySamples = sampleRate * 0.5f

    fun clear() {
        lines.forEach { it.clear() }
        inputDiffusers.forEach { it.clear() }
        outputDiffusers.forEach { it.clear() }
        matrix.fill(0f)
        hpX = 0f
        hpY = 0f
        hp2X = 0f
        hp2Y = 0f
        lpY = 0f
        limiter = 1f
        smoothedMix = 0f
        delayBuffer.fill(0f)
        delayWriteIndex = 0
        smoothedDelayMix = 0f
        smoothedDelaySamples = sampleRate * 0.5f
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
        freeze: Boolean,
        inputEnabled: Boolean,
        fadeMs: Int,
        delayMixPercent: Int = 0,
        delayBpm: Int = 120,
        delayEnabled: Boolean = false,
        delayFadeMs: Int = 1400,
        delayDivision: Int = 3,
        delayMode: Int = 2
    ) {
        val mixTarget = if (inputEnabled) (mixPercent.coerceIn(0, 100) / 100f * 1.30f) else 0f
        val delayTarget = if (delayEnabled) delayMixPercent.coerceIn(0, 100) / 100f else 0f
        val beatFraction = when (delayDivision.coerceIn(0, 3)) { 0 -> 0.25f; 1 -> 0.5f; 2 -> 0.75f; else -> 1f }
        // V10-style SEND voicings: short, long and dub echo use progressively longer time and feedback.
        val modeTime = when (delayMode.coerceIn(0, 2)) { 0 -> 0.5f; 1 -> 1.5f; else -> 1f }
        val modeFeedback = when (delayMode.coerceIn(0, 2)) { 0 -> 0.22f; 1 -> 0.32f; else -> 0.46f }
        val targetDelaySamples = (sampleRate * (60f / delayBpm.coerceIn(60, 180)) * beatFraction * modeTime).coerceIn(1f, (delayBuffer.size - 2).toFloat())
        val fadeSeconds = fadeMs.coerceIn(300, 6000) / 1000f
        val delayFadeSeconds = delayFadeMs.coerceIn(300, 6000) / 1000f
        val mixSmoothing = (1.0 - exp(-3.0 / (sampleRate * fadeSeconds))).toFloat().coerceIn(0.000001f, 1f)
        val delayMixSmoothing = (1.0 - exp(-3.0 / (sampleRate * delayFadeSeconds))).toFloat().coerceIn(0.000001f, 1f)
        val size = roomSize.coerceIn(0, 1000) / 1000f * 1.30f
        val hp = hpHz.coerceIn(20, 1200).toFloat()
        val lp = lpHz.coerceIn(1000, 20000).toFloat()
        val hpA = exp((-2.0 * PI * hp / sampleRate).toFloat())
        val lpA = (1f - exp((-2.0 * PI * lp / sampleRate).toFloat())).coerceIn(0.001f, 1f)

        // A long but controlled room tail; freeze is bounded to avoid runaway.
        val feedback = if (freeze) 0.965f else (0.80f + size * 0.14f).coerceIn(0.80f, 0.955f)
        val damping = if (freeze) 0.035f else (0.09f + (1f - size.coerceIn(0f, 1f)) * 0.16f)

        for (i in 0 until count) {
            val x = input[i] / 32768f
            // Two gentle high-pass stages keep kick/sub energy out of the
            // reverb excitation while retaining a natural mid/high body.
            val hpOut1 = hpA * (hpY + x - hpX)
            hpX = x
            hpY = hpOut1
            val hpOut2 = hpA * (hp2Y + hpOut1 - hp2X)
            hp2X = hpOut1
            hp2Y = hpOut2
            lpY += lpA * (hpOut2 - lpY)

            smoothedMix += (mixTarget - smoothedMix) * mixSmoothing
            smoothedDelayMix += (delayTarget - smoothedDelayMix) * delayMixSmoothing
            smoothedDelaySamples += (targetDelaySamples - smoothedDelaySamples) * 0.00015f
            var excitation = if (freeze || !inputEnabled) 0f else lpY * 0.32f
            for (diffuser in inputDiffusers) excitation = diffuser.tick(excitation)

            var wet = 0f
            for (n in 0 until 8) {
                val delayed = lines[n].read(damping)
                matrix[n] = delayed
                wet += delayed * outputPattern[n]
            }

            // Orthogonal feedback disperses energy across all delay paths instead
            // of returning eight individually recognizable echo trains.
            hadamard(matrix)
            for (n in 0 until 8) {
                lines[n].write(excitation * inputPattern[n] + matrix[n] * feedback)
            }

            wet *= 0.35355339f
            for (diffuser in outputDiffusers) wet = diffuser.tick(wet)

            val readIndex = (delayWriteIndex - smoothedDelaySamples.toInt() + delayBuffer.size) % delayBuffer.size
            val delayed = delayBuffer[readIndex]
            // Controlled dub feedback: audible repeats without runaway buildup.
            delayBuffer[delayWriteIndex] = (x * 0.48f + delayed * modeFeedback).coerceIn(-0.82f, 0.82f)
            delayWriteIndex++
            if (delayWriteIndex == delayBuffer.size) delayWriteIndex = 0
            val reverbReturn = wet * smoothedMix * 0.92f
            val delayReturn = delayed * smoothedDelayMix * 0.78f
            val raw = reverbReturn + delayReturn
            val magnitude = abs(raw)
            val target = if (magnitude > 0.78f) 0.78f / magnitude else 1f
            limiter = if (target < limiter) target else limiter + (1f - limiter) * 0.002f
            val safe = (raw * limiter).coerceIn(-0.78f, 0.78f)
            output[i] = (safe * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
