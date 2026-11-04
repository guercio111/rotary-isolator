package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Low-cost, bass-controlled Schroeder reverb for the experimental parallel return.
 * The original Android playback remains untouched; this class creates a wet return only.
 */
internal class SchroederReverb(private val sampleRate: Int) {
    private class Comb(delay: Int) {
        private val data = FloatArray(delay.coerceAtLeast(1))
        private var index = 0
        private var dampingState = 0f
        fun tick(input: Float, feedback: Float, damping: Float): Float {
            val delayed = data[index]
            dampingState = delayed * (1f - damping) + dampingState * damping
            data[index] = input + dampingState * feedback
            index++
            if (index == data.size) index = 0
            return delayed
        }
        fun clear() {
            data.fill(0f)
            index = 0
            dampingState = 0f
        }
    }

    private class AllPass(delay: Int, private val gain: Float = 0.5f) {
        private val data = FloatArray(delay.coerceAtLeast(1))
        private var index = 0
        fun tick(input: Float): Float {
            val delayed = data[index]
            val output = delayed - gain * input
            data[index] = input + gain * output
            index++
            if (index == data.size) index = 0
            return output
        }
        fun clear() {
            data.fill(0f)
            index = 0
        }
    }

    private val combs = arrayOf(
        Comb((sampleRate * 0.0297f).toInt()),
        Comb((sampleRate * 0.0371f).toInt()),
        Comb((sampleRate * 0.0411f).toInt()),
        Comb((sampleRate * 0.0437f).toInt())
    )
    private val allPassA = AllPass((sampleRate * 0.0051f).toInt())
    private val allPassB = AllPass((sampleRate * 0.0017f).toInt())
    private var hpPreviousInput = 0f
    private var hpPreviousOutput = 0f
    private var limiterGain = 1f

    fun clear() {
        combs.forEach { it.clear() }
        allPassA.clear()
        allPassB.clear()
        hpPreviousInput = 0f
        hpPreviousOutput = 0f
        limiterGain = 1f
    }

    fun process(
        input: ShortArray, output: ShortArray, count: Int,
        mixPercent: Int, decayMs: Int, roomSize: Int, dampingPercent: Int,
        lowCutHz: Int
    ) {
        val mix = mixPercent.coerceIn(0, 100) / 100f
        val decay = decayMs.coerceIn(100, 8000) / 8000f
        val size = roomSize.coerceIn(0, 1000) / 1000f
        // Calibrate comb feedback against the requested RT60 instead of a linear knob curve.
        // This gives the 2.6 s V10-style preset a genuinely longer, smoother tail.
        val decaySeconds = decayMs.coerceIn(100, 8000) / 1000.0
        val feedback = (Math.pow(10.0, -3.0 * 0.0379 / decaySeconds).toFloat() + size * 0.01f).coerceIn(0.55f, 0.94f)
        val damping = (dampingPercent.coerceIn(100, 1000) / 1000f * 0.78f).coerceIn(0.08f, 0.78f)
        val cutoff = lowCutHz.coerceIn(60, 500).toFloat()
        val hpAlpha = exp((-2.0 * Math.PI * cutoff / sampleRate).toFloat())

        for (i in 0 until count) {
            val x = input[i].toFloat() / 32768f
            // Keep kick/sub energy out of the reverb send; it remains in the dry track.
            val highPassed = hpAlpha * (hpPreviousOutput + x - hpPreviousInput)
            hpPreviousInput = x
            hpPreviousOutput = highPassed

            val send = highPassed * 0.30f
            var wet = 0f
            for (comb in combs) wet += comb.tick(send, feedback, damping)
            wet *= 0.25f
            wet = allPassB.tick(allPassA.tick(wet))

            // Conservative parallel return level and smooth peak protection.
            val raw = wet * mix * 1.65f
            val magnitude = abs(raw)
            val targetGain = if (magnitude > 0.88f) 0.88f / magnitude else 1f
            if (targetGain < limiterGain) {
                limiterGain = targetGain
            } else {
                limiterGain += (1f - limiterGain) * 0.0015f
            }
            val limited = raw * limiterGain
            val soft = if (abs(limited) <= 0.78f) limited else {
                val excess = abs(limited) - 0.78f
                sign(limited) * (0.78f + 0.20f * tanh(excess / 0.20f))
            }
            output[i] = (soft * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
