package com.example.rotaryisolator

import kotlin.math.max
import kotlin.math.min

/**
 * Low-cost Schroeder-style parallel reverb.
 * The original Android playback remains untouched; this class creates a wet return only.
 */
internal class SchroederReverb(sampleRate: Int) {
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
    }

    private val combs = arrayOf(
        Comb((sampleRate * 0.0297f).toInt()),
        Comb((sampleRate * 0.0371f).toInt()),
        Comb((sampleRate * 0.0411f).toInt()),
        Comb((sampleRate * 0.0437f).toInt())
    )
    private val allPassA = AllPass((sampleRate * 0.0051f).toInt())
    private val allPassB = AllPass((sampleRate * 0.0017f).toInt())

    fun process(
        input: ShortArray, output: ShortArray, count: Int,
        mixPercent: Int, decayMs: Int, roomSize: Int, dampingPercent: Int
    ) {
        val mix = (mixPercent.coerceIn(0, 100) / 100f)
        val decay = decayMs.coerceIn(100, 8000) / 8000f
        val size = roomSize.coerceIn(0, 1000) / 1000f
        val feedback = (0.48f + decay * 0.43f + size * 0.06f).coerceIn(0.48f, 0.94f)
        val damping = (dampingPercent.coerceIn(100, 1000) / 1000f * 0.82f).coerceIn(0.08f, 0.82f)
        for (i in 0 until count) {
            val x = input[i].toFloat() / 32768f
            val send = x * 0.55f
            var wet = 0f
            for (comb in combs) wet += comb.tick(send, feedback, damping)
            wet *= 0.25f
            wet = allPassB.tick(allPassA.tick(wet))
            // Raise the wet return so the parallel effect is clearly audible over the untouched dry track.
            val sample = (wet * mix * 1.55f * 32767f).toInt().coerceIn(-32768, 32767)
            output[i] = sample.toShort()
        }
    }
}
