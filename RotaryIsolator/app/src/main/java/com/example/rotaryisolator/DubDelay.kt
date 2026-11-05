package com.example.rotaryisolator

import kotlin.math.PI
import kotlin.math.exp

/**
 * Parallel, tempo-divided dub delay return.
 *
 * The feedback path stays entirely inside this processor. Captured output is
 * never fed back into AudioRecord, so echoes cannot create an external loop.
 */
internal class DubDelay(private val sampleRate: Int) {
    private val divisions = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 2f, 4f, 8f)
    private val ring = FloatArray(sampleRate * 8 + 8)
    private var writeIndex = 0
    private var filteredFeedback = 0f
    private var inputEnvelope = 0f
    private var outputEnvelope = 0f

    fun process(
        input: ShortArray,
        output: ShortArray,
        count: Int,
        enabled: Boolean,
        divisionIndex: Int,
        bpm: Int,
        feedbackPercent: Int,
        filterPercent: Int,
        mixPercent: Int,
        fadeMs: Int
    ) {
        val division = divisions[divisionIndex.coerceIn(0, divisions.lastIndex)]
        val tempo = bpm.coerceIn(60, 180)
        val delaySamples = ((60.0 / tempo * division) * sampleRate)
            .toInt().coerceIn(1, ring.size - 2)
        val feedback = feedbackPercent.coerceIn(0, 90) / 100f
        val mix = mixPercent.coerceIn(0, 100) / 100f
        // Turning FILTER clockwise progressively darkens every repeat.
        val cutoff = 12000f - filterPercent.coerceIn(0, 100) * 105f
        val filterAlpha = (1.0 - exp(-2.0 * PI * cutoff / sampleRate)).toFloat()
            .coerceIn(0.001f, 1f)
        val fadeSeconds = fadeMs.coerceIn(300, 6000) / 1000f
        val smoothing = (1.0 - exp(-1.0 / (sampleRate * fadeSeconds))).toFloat()
            .coerceIn(0.000001f, 1f)
        val inputTarget = if (enabled) 1f else 0f
        val outputTarget = if (enabled) mix else 0f

        for (i in 0 until count) {
            inputEnvelope += (inputTarget - inputEnvelope) * smoothing
            outputEnvelope += (outputTarget - outputEnvelope) * smoothing

            var readIndex = writeIndex - delaySamples
            if (readIndex < 0) readIndex += ring.size
            val echo = ring[readIndex]

            // A gentle low-pass in the feedback loop makes each repeat warmer.
            filteredFeedback += filterAlpha * (echo - filteredFeedback)
            val dryInput = input[i] / 32768f
            val feedbackInput = filteredFeedback * feedback
            // Headroom is reserved before writing; feedback is always bounded.
            val writeValue = (dryInput * inputEnvelope * 0.72f + feedbackInput)
                .coerceIn(-0.92f, 0.92f)
            ring[writeIndex] = writeValue

            val wet = filteredFeedback * outputEnvelope * 0.82f
            output[i] = (wet.coerceIn(-0.78f, 0.78f) * 32767f)
                .toInt().coerceIn(-32768, 32767).toShort()

            writeIndex++
            if (writeIndex == ring.size) writeIndex = 0
        }
    }

    fun clear() {
        ring.fill(0f)
        writeIndex = 0
        filteredFeedback = 0f
        inputEnvelope = 0f
        outputEnvelope = 0f
    }
}
