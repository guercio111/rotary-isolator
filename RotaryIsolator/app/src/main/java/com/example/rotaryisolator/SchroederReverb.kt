package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Dense, diffuse algorithmic reverb return.
 *
 * An eight-line feedback-delay network (orthogonal Hadamard feedback matrix)
 * replaces the audible parallel-comb character of the previous experiment.
 * Input and output all-pass diffusion soften the onset and merge the late tail.
 */
internal class SchroederReverb(private val sampleRate: Int) {
    // Fourth-order Linkwitz-Riley crossover sections for clean, phase-aligned
    // FX band isolation. Two cascaded Butterworth biquads create each LR4 slope.
    private class Biquad(sampleRate: Int, frequency: Float, highPass: Boolean) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var z1 = 0f
        private var z2 = 0f

        init {
            val omega = (2.0 * PI * frequency.coerceIn(20f, sampleRate * 0.45f) / sampleRate).toFloat()
            val c = kotlin.math.cos(omega.toDouble()).toFloat()
            val sn = kotlin.math.sin(omega.toDouble()).toFloat()
            val alpha = sn * 0.70710678f
            val a0 = 1f + alpha
            if (highPass) {
                b0 = ((1f + c) * 0.5f) / a0
                b1 = -(1f + c) / a0
                b2 = ((1f + c) * 0.5f) / a0
            } else {
                b0 = ((1f - c) * 0.5f) / a0
                b1 = (1f - c) / a0
                b2 = ((1f - c) * 0.5f) / a0
            }
            a1 = (-2f * c) / a0
            a2 = (1f - alpha) / a0
        }

        fun tick(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun clear() { z1 = 0f; z2 = 0f }
    }

    private val fxLow1 = Biquad(sampleRate, 250f, false)
    private val fxLow2 = Biquad(sampleRate, 250f, false)
    private val fxMidHighPass1 = Biquad(sampleRate, 250f, true)
    private val fxMidHighPass2 = Biquad(sampleRate, 250f, true)
    private val fxMidLowPass1 = Biquad(sampleRate, 2500f, false)
    private val fxMidLowPass2 = Biquad(sampleRate, 2500f, false)
    private val fxHigh1 = Biquad(sampleRate, 2500f, true)
    private val fxHigh2 = Biquad(sampleRate, 2500f, true)
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
    private val delayBuffer = FloatArray(sampleRate * 9)
    private var delayWriteIndex = 0
    private var smoothedDelayMix = 0f
    // Beat-division changes use a short dual-tap crossfade. This avoids
    // abruptly moving the read head through the circular buffer (zipper/click noise).
    private var delayTransitionFromSamples = sampleRate * 0.5f
    private var delayTransitionToSamples = sampleRate * 0.5f
    private var delayTransitionPosition = 0
    private var activeDelayDivision = -1
    private val delayTransitionLength = (sampleRate * 0.12f).toInt().coerceAtLeast(1)
    private var delayToneState = 0f

    private fun readDelayTap(delaySamples: Float): Float {
        // Use Double for circular-position arithmetic. At this buffer size,
        // Float can round a value just below size up to size itself, producing
        // the invalid index 432000 on a 48 kHz / 9 s buffer.
        val size = delayBuffer.size
        val writeIndex = Math.floorMod(delayWriteIndex, size)
        var position = (writeIndex.toDouble() - delaySamples.toDouble()) % size.toDouble()
        if (position < 0.0) position += size.toDouble()
        // Guard the upper boundary against any modulo/rounding edge case.
        if (position >= size.toDouble()) position = 0.0
        val indexA = position.toInt().coerceIn(0, size - 1)
        val fraction = (position - indexA.toDouble()).toFloat().coerceIn(0f, 1f)
        val indexB = if (indexA + 1 >= size) 0 else indexA + 1
        val a = delayBuffer[indexA]
        val b = delayBuffer[indexB]
        return a + (b - a) * fraction
    }

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
        delayTransitionFromSamples = sampleRate * 0.5f
        delayTransitionToSamples = sampleRate * 0.5f
        delayTransitionPosition = 0
        activeDelayDivision = -1
        delayToneState = 0f
        fxLow1.clear(); fxLow2.clear()
        fxMidHighPass1.clear(); fxMidHighPass2.clear()
        fxMidLowPass1.clear(); fxMidLowPass2.clear()
        fxHigh1.clear(); fxHigh2.clear()
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
        delayBpm: Float = 120f,
        delayEnabled: Boolean = false,
        delayFadeMs: Int = 1400,
        delayDivision: Int = 3,
        delayMode: Int = 2,
        delayTimePercent: Int = 100,
        delayFeedbackPercent: Int = 52,
        delayTonePercent: Int = 50,
        fxFrequency: Int = 1,
        reverbFeedbackPercent: Int = 50,
        reverbDecayPercent: Int = 50,
        reverbColorPercent: Int = 50,
        delaySyncOffsetMs: Float = 0f,
        delaySyncLocked: Boolean = false
    ) {
        val mixTarget = if (inputEnabled) (mixPercent.coerceIn(0, 100) / 100f * 1.42f) else 0f
        val delayTarget = if (delayEnabled) delayMixPercent.coerceIn(0, 100) / 100f * 1.28f else 0f
        val beatFraction = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 2f, 4f, 8f)[delayDivision.coerceIn(0, 6)]
        // V10-inspired SEND voicings. Pioneer does not publish the internal DSP
        // coefficients; these fixed voicings reproduce the documented behavior:
        // SHORT = compact, lightly repeating; LONG = longer, more open repeats;
        // DUB = progressively attenuated, darker echo repeats.
        val mode = delayMode.coerceIn(0, 2)
        val modeTime = 1f // The selected beat division is the exact musical delay interval.
        val modeFeedbackBase = when (mode) { 0 -> 0.20f; 1 -> 0.36f; else -> 0.58f }
        val modeFeedback = (modeFeedbackBase * (0.5f + delayFeedbackPercent.coerceIn(0,90) / 100f)).coerceIn(0.05f,0.82f)
        val toneControlHz = 700f + delayTonePercent.coerceIn(0,100) * 130f
        val delayToneHz = when (mode) { 0 -> toneControlHz.coerceAtLeast(5000f); 1 -> toneControlHz; else -> toneControlHz.coerceAtMost(9000f) }
        val delayToneAlpha = (1f - exp((-2.0 * PI * delayToneHz / sampleRate).toFloat())).coerceIn(0.001f, 1f)
        // When SYNC is locked, TIME remains freely adjustable but snaps to
        // sixteenth-beat increments, so every selected delay remains on the
        // musical grid instead of drifting between beats.
        val rawTimeBeats = beatFraction * (delayTimePercent.coerceIn(50,200) / 100f)
        val effectiveTimeBeats = if (delaySyncLocked)
            (rawTimeBeats * 16f).roundToInt().coerceAtLeast(1) / 16f
        else rawTimeBeats
        val targetDelaySamples = (sampleRate * (60f / delayBpm.coerceIn(60f, 180f)) * effectiveTimeBeats * modeTime + sampleRate * (delaySyncOffsetMs.coerceIn(0f,1000f) / 1000f)).coerceIn(1f, (delayBuffer.size - 2).toFloat())
        val requestedDivision = delayDivision.coerceIn(0, 6)
        if (requestedDivision != activeDelayDivision) {
            val progress = (delayTransitionPosition.toFloat() / delayTransitionLength).coerceIn(0f, 1f)
            val eased = progress * progress * (3f - 2f * progress)
            val currentTap = delayTransitionFromSamples +
                (delayTransitionToSamples - delayTransitionFromSamples) * eased
            delayTransitionFromSamples = currentTap
            delayTransitionToSamples = targetDelaySamples
            delayTransitionPosition = 0
            activeDelayDivision = requestedDivision
        } else if (delayTransitionPosition >= delayTransitionLength &&
            kotlin.math.abs(targetDelaySamples - delayTransitionToSamples) > 1f) {
            // BPM/TIME changes also move to the new tap without a hard discontinuity.
            delayTransitionFromSamples = delayTransitionToSamples
            delayTransitionToSamples = targetDelaySamples
            delayTransitionPosition = 0
        }
        val fadeSeconds = fadeMs.coerceIn(300, 6000) / 1000f
        val delayFadeSeconds = delayFadeMs.coerceIn(300, 6000) / 1000f
        val mixSmoothing = (1.0 - exp(-3.0 / (sampleRate * fadeSeconds))).toFloat().coerceIn(0.000001f, 1f)
        val delayMixSmoothing = (1.0 - exp(-3.0 / (sampleRate * delayFadeSeconds))).toFloat().coerceIn(0.000001f, 1f)
        val size = roomSize.coerceIn(0, 1000) / 1000f * 1.30f
        val hp = hpHz.coerceIn(20, 1200).toFloat()
        val color = reverbColorPercent.coerceIn(0,100) / 100f
        val lp = (lpHz.coerceIn(1000, 20000) * (0.55f + color * 0.90f)).coerceIn(1000f, 20000f)
        val hpA = exp((-2.0 * PI * hp / sampleRate).toFloat())
        val lpA = (1f - exp((-2.0 * PI * lp / sampleRate).toFloat())).coerceIn(0.001f, 1f)

        // A long but controlled room tail; freeze is bounded to avoid runaway.
        // Dedicated Reverb FEEDBACK control: 50% preserves approximately the
        // previous tail length; lower values shorten it, higher values extend it.
        val decay = reverbDecayPercent.coerceIn(0,100) / 100f
        val feedback = if (freeze) 0.965f else
            (0.78f + size * 0.10f + reverbFeedbackPercent.coerceIn(0,100) / 100f * 0.146f + (decay - 0.5f) * 0.09f).coerceIn(0.78f, 0.955f)
        // COLOR moves the late tail from dark/warm to open/bright.
        val damping = if (freeze) 0.035f else
            (0.09f + (1f - size.coerceIn(0f, 1f)) * 0.16f + (0.5f - color) * 0.12f).coerceIn(0.015f, 0.32f)

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
            var excitation = if (freeze || !inputEnabled) 0f else lpY * 0.45f
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

            val transitionProgress = (delayTransitionPosition.toFloat() / delayTransitionLength).coerceIn(0f, 1f)
            val transitionBlend = transitionProgress * transitionProgress * (3f - 2f * transitionProgress)
            val oldTap = readDelayTap(delayTransitionFromSamples)
            val newTap = readDelayTap(delayTransitionToSamples)
            val delayed = oldTap + (newTap - oldTap) * transitionBlend
            if (delayTransitionPosition < delayTransitionLength) {
                delayTransitionPosition++
            } else {
                delayTransitionFromSamples = delayTransitionToSamples
            }
            // Tone is fixed per voicing for now; the V10 TONE control is not
            // exposed in this simplified interface. Darker repeats in DUB mode
            // progressively lose top end, while SHORT stays crisp.
            delayToneState += delayToneAlpha * (delayed - delayToneState)
            // Keep the circular-buffer cursor in range even if a previous
            // interrupted processing cycle left it at an unexpected position.
            val safeWriteIndex = Math.floorMod(delayWriteIndex, delayBuffer.size)
            delayBuffer[safeWriteIndex] = (x * 0.68f + delayToneState * modeFeedback).coerceIn(-0.88f, 0.88f)
            delayWriteIndex = if (safeWriteIndex + 1 >= delayBuffer.size) 0 else safeWriteIndex + 1
            // TEST: OFF is a hard output gate. The fade controls continue to shape
            // the return while enabled, but a disabled switch must produce no wet
            // signal at all. This isolates lingering DSP tails from stale AudioTrack output.
            val reverbReturn = if (inputEnabled) wet * smoothedMix * 1.28f else 0f
            val delayReturn = if (delayEnabled) delayToneState * smoothedDelayMix * 1.28f else 0f
            val raw = reverbReturn + delayReturn
            // Split the FX return into three genuinely isolated bands:
            // LOW <250 Hz, MID 250-2500 Hz, HIGH >2500 Hz.
            // LR4 crossover pairs provide steep 24 dB/oct slopes without the
            // broad overlap and phasey subtraction of the previous one-pole filters.
            val lowBand = fxLow2.tick(fxLow1.tick(raw))
            val midHighPassed = fxMidHighPass2.tick(fxMidHighPass1.tick(raw))
            val midBand = fxMidLowPass2.tick(fxMidLowPass1.tick(midHighPassed))
            val highBand = fxHigh2.tick(fxHigh1.tick(raw))
            val bandLimited = when (fxFrequency.coerceIn(0,2)) {
                0 -> lowBand
                1 -> midBand
                else -> highBand
            }
            val magnitude = abs(bandLimited)
            val target = if (magnitude > 0.84f) 0.84f / magnitude else 1f
            limiter = if (target < limiter) target else limiter + (1f - limiter) * 0.002f
            val safe = (bandLimited * limiter).coerceIn(-0.84f, 0.84f)
            output[i] = (safe * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
