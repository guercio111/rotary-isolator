package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.PI

/**
 * Dense, smooth algorithmic room reverb.
 *
 * Uses input diffusion, eight parallel decay lines and a four-stage
 * output diffuser. The denser network is intended to remove the audible
 * flutter/individual repeats of the earlier sparse Schroeder version.
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
            index = (index + 1) % data.size
            return delayed
        }

        fun clear() {
            data.fill(0f)
            index = 0
            dampingState = 0f
        }
    }

    private class AllPass(delay: Int, private val coefficient: Float = 0.55f) {
        private val data = FloatArray(delay.coerceAtLeast(1))
        private var index = 0

        fun tick(input: Float): Float {
            val delayed = data[index]
            val output = delayed - coefficient * input
            data[index] = input + coefficient * output
            index = (index + 1) % data.size
            return output
        }

        fun clear() {
            data.fill(0f)
            index = 0
        }
    }

    // Slightly different, mutually-prime-ish delay lengths make the tail denser.
    private val combs = floatArrayOf(
        0.0297f, 0.0311f, 0.0337f, 0.0353f,
        0.0371f, 0.0389f, 0.0411f, 0.0437f
    ).map { Comb((sampleRate * it).toInt()) }.toTypedArray()

    // Gentle input diffusion softens the initial reflections before the decay network.
    private val inputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0017f).toInt(), 0.50f),
        AllPass((sampleRate * 0.0023f).toInt(), 0.50f)
    )

    // Four serial diffusion stages turn the parallel decay lines into one smooth tail.
    private val outputDiffusers = arrayOf(
        AllPass((sampleRate * 0.0051f).toInt(), 0.55f),
        AllPass((sampleRate * 0.0037f).toInt(), 0.55f),
        AllPass((sampleRate * 0.0023f).toInt(), 0.55f),
        AllPass((sampleRate * 0.0011f).toInt(), 0.55f)
    )

    private var hpX = 0f
    private var hpY = 0f
    private var lpY = 0f
    private var limiter = 1f

    fun clear() {
        combs.forEach { it.clear() }
        inputDiffusers.forEach { it.clear() }
        outputDiffusers.forEach { it.clear() }
        hpX = 0f
        hpY = 0f
        lpY = 0f
        limiter = 1f
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

        // Preserve the current long tail while keeping feedback safely bounded.
        val feedback = if (freeze) 0.985f
            else (0.62f + size.pow(2.0f) * 0.35f).coerceIn(0.62f, 0.97f)
        val damping = if (freeze) 0.08f
            else (0.04f + (1f - size).pow(1.35f) * 0.16f)

        for (i in 0 until count) {
            val x = input[i] / 32768f
            val hpOut = hpA * (hpY + x - hpX)
            hpX = x
            hpY = hpOut
            lpY += lpA * (hpOut - lpY)

            var diffused = if (freeze) 0f else lpY * 0.55f
            for (diffuser in inputDiffusers) {
                diffused = diffuser.tick(diffused)
            }

            var wet = 0f
            for (comb in combs) {
                wet += comb.tick(diffused, feedback, damping)
            }

            // Normalize for the larger comb bank, then diffuse the reflections together.
            wet *= 0.32f
            for (diffuser in outputDiffusers) {
                wet = diffuser.tick(wet)
            }

            val raw = wet * mix * 1.25f
            val magnitude = abs(raw)
            val target = if (magnitude > 0.72f) 0.72f / magnitude else 1f
            limiter = if (target < limiter) target else limiter + (1f - limiter) * 0.002f
            val safe = (raw * limiter).coerceIn(-0.72f, 0.72f)
            output[i] = (safe * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
