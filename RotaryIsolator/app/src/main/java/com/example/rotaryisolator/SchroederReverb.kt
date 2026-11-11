package com.example.rotaryisolator

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

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

    /**
     * Fourth-order Linkwitz-Riley crossover (two Butterworth sections per
     * branch). LOW and HIGH are phase-aligned at the crossover and have no
     * resonant-Q boost. The two branches sum to a flat, unity-gain response.
     */
    private class Biquad(sampleRate: Int, cutoffHz: Float, highPass: Boolean) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var z1 = 0f
        private var z2 = 0f

        init {
            val w0 = (2.0 * PI * cutoffHz / sampleRate).coerceIn(0.0001, PI - 0.0001)
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * 0.7071067811865476)
            val a0 = 1.0 + alpha
            val bb0 = if (highPass) (1.0 + c) * 0.5 else (1.0 - c) * 0.5
            val bb1 = if (highPass) -(1.0 + c) else 1.0 - c
            val bb2 = bb0
            b0 = (bb0 / a0).toFloat()
            b1 = (bb1 / a0).toFloat()
            b2 = (bb2 / a0).toFloat()
            a1 = (-2.0 * c / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun clear() { z1 = 0f; z2 = 0f }
    }

    private class LinkwitzRileySplit(sampleRate: Int, cutoffHz: Float) {
        private val low1 = Biquad(sampleRate, cutoffHz, false)
        private val low2 = Biquad(sampleRate, cutoffHz, false)
        private val high1 = Biquad(sampleRate, cutoffHz, true)
        private val high2 = Biquad(sampleRate, cutoffHz, true)

        fun processLow(input: Float): Float = low2.process(low1.process(input))
        fun processHigh(input: Float): Float = high2.process(high1.process(input))
        fun clear() {
            low1.clear(); low2.clear(); high1.clear(); high2.clear()
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
    private val delayBuffer = FloatArray(sampleRate * 34)
    private var delayWriteIndex = 0
    // FX phase trim is applied to the incoming send before it enters the
    // feedback loop. The feedback tap itself keeps its exact beat period.
    private val phaseDelayBuffer = FloatArray(sampleRate * 18)
    private var phaseDelayWriteIndex = 0
    @Volatile private var smoothedFxPhaseDelaySamples = 0f
    private var smoothedDelayMix = 0f

    /** Actual smoothed FX send delay, in milliseconds, as applied by the audio thread. */
    fun getFxPhaseDelayMs(): Float = smoothedFxPhaseDelaySamples * 1000f / sampleRate
    // Beat-division changes use a short dual-tap crossfade. This avoids
    // abruptly moving the read head through the circular buffer (zipper/click noise).
    private var delayTransitionFromSamples = sampleRate * 0.5f
    private var delayTransitionToSamples = sampleRate * 0.5f
    private var delayTransitionPosition = 0
    private var activeDelayDivision = -1
    private val delayTransitionLength = (sampleRate * 0.12f).toInt().coerceAtLeast(1)
    // Division changes use a zero-crossfade handoff: fade the old tap down,
    // switch read heads at silence, then fade the new tap up. This avoids the
    // metallic/comb sweep caused by mixing two unrelated beat taps together.
    private var delayDivisionSwitch = false
    private var delayToneState = 0f

    private fun readDelayTap(delaySamples: Float): Float {
        var position = delayWriteIndex.toFloat() - delaySamples
        position %= delayBuffer.size.toFloat()
        if (position < 0f) position += delayBuffer.size.toFloat()
        val indexA = position.toInt()
        val fraction = position - indexA
        val indexB = if (indexA + 1 == delayBuffer.size) 0 else indexA + 1
        val a = delayBuffer[indexA]
        val b = delayBuffer[indexB]
        return a + (b - a) * fraction
    }

    private fun readPhaseDelayTap(delaySamples: Float): Float {
        var position = phaseDelayWriteIndex.toFloat() - delaySamples
        position %= phaseDelayBuffer.size.toFloat()
        if (position < 0f) position += phaseDelayBuffer.size.toFloat()
        val indexA = position.toInt()
        val fraction = position - indexA
        val indexB = if (indexA + 1 == phaseDelayBuffer.size) 0 else indexA + 1
        val a = phaseDelayBuffer[indexA]
        val b = phaseDelayBuffer[indexB]
        return a + (b - a) * fraction
    }
    // Single retained FX HIGH selector. LOW/MID crossovers are removed entirely.
    private val fxHighSplit2500 = LinkwitzRileySplit(sampleRate, 2500f)
    private val fxHighSmoothing = (1.0 - exp(-1.0 / (sampleRate * 0.010))).toFloat()
    private var smoothedFxHigh = 1f

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
        phaseDelayBuffer.fill(0f)
        phaseDelayWriteIndex = 0
        smoothedFxPhaseDelaySamples = 0f
        smoothedDelayMix = 0f
        delayTransitionFromSamples = sampleRate * 0.5f
        delayTransitionToSamples = sampleRate * 0.5f
        delayTransitionPosition = 0
        activeDelayDivision = -1
        delayToneState = 0f
        fxHighSplit2500.clear()
        smoothedFxHigh = 1f
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
        delayDivision: Int = 2,
        delayMode: Int = 2,
        delayFeedbackPercent: Int = 52,
        delayTonePercent: Int = 50,
        fxHighEnabled: Boolean = true,
        reverbFeedbackPercent: Int = 50,
        reverbDecayPercent: Int = 50,
        reverbColorPercent: Int = 50,
        delaySyncOffsetMs: Float = 0f,
        fxPhaseOffsetMs: Float = 0f
    ) {
        val mixTarget = if (inputEnabled) (mixPercent.coerceIn(0, 100) / 100f * 1.42f) else 0f
        val delayTarget = if (delayEnabled) delayMixPercent.coerceIn(0, 100) / 100f * 1.28f else 0f
        val beatFraction = floatArrayOf(1f / 16f, 1f / 8f, 1f / 4f, 1f / 2f, 3f / 4f, 1f, 2f, 4f, 8f, 16f)[delayDivision.coerceIn(0, 9)]
        // V10-inspired SEND voicings. Pioneer does not publish the internal DSP
        // coefficients; these fixed voicings reproduce the documented behavior:
        // SHORT = compact, lightly repeating; LONG = longer, more open repeats;
        // DUB = progressively attenuated, darker echo repeats.
        val mode = delayMode.coerceIn(0, 2)
        // Three distinct send voicings:
        // SHORT = one clean repeat; LONG = restrained open repeats;
        // DUB = stronger, progressively darker feedback repeats.
        val feedbackControl = delayFeedbackPercent.coerceIn(0, 90) / 90f
        val modeFeedback = when (mode) {
            0 -> 0f
            1 -> (0.08f + feedbackControl * 0.40f).coerceIn(0.08f, 0.48f)
            else -> (0.28f + feedbackControl * 0.48f).coerceIn(0.28f, 0.76f)
        }
        val tone = delayTonePercent.coerceIn(0, 100) / 100f
        val delayToneHz = when (mode) {
            0 -> 8000f + tone * 8000f
            1 -> 2500f + tone * 9500f
            else -> 700f + tone * 6000f
        }
        val delayToneAlpha = (1f - exp((-2.0 * PI * delayToneHz / sampleRate).toFloat())).coerceIn(0.001f, 1f)
        // Phase trim from the clock arrows is applied to the audible delay
        // read head as well as the beat lamps; the transition below crossfades it.
        val beatPeriodMs = 60000f / delayBpm.coerceIn(60f, 180f)
        val targetDelaySamples = (sampleRate *
            (beatPeriodMs / 1000f * beatFraction + delaySyncOffsetMs.coerceIn(-250f,250f) / 1000f)
        ).coerceIn(1f, (delayBuffer.size - 2).toFloat())
        // Convert the signed phase trim to its equivalent non-negative offset
        // within one beat. It shifts the first echo's phase, while the feedback
        // loop continues to repeat at the unchanged selected beat division.
        val fxPhasePeriodMs = beatPeriodMs * beatFraction
        var normalizedFxPhaseMs = fxPhaseOffsetMs % fxPhasePeriodMs
        if (normalizedFxPhaseMs < 0f) normalizedFxPhaseMs += fxPhasePeriodMs
        val targetFxPhaseDelaySamples = (normalizedFxPhaseMs * sampleRate / 1000f)
            .coerceIn(0f, (phaseDelayBuffer.size - 2).toFloat())
        val fxPhaseDelaySmoothing = (1.0 - exp(-1.0 / (sampleRate * 0.025))).toFloat()
        val requestedDivision = delayDivision.coerceIn(0, 9)
        if (requestedDivision != activeDelayDivision) {
            val progress = (delayTransitionPosition.toFloat() / delayTransitionLength).coerceIn(0f, 1f)
            val currentTap = if (delayDivisionSwitch) {
                if (progress < 0.5f) delayTransitionFromSamples else delayTransitionToSamples
            } else {
                val eased = progress * progress * (3f - 2f * progress)
                delayTransitionFromSamples +
                    (delayTransitionToSamples - delayTransitionFromSamples) * eased
            }
            delayTransitionFromSamples = currentTap
            delayTransitionToSamples = targetDelaySamples
            delayTransitionPosition = 0
            delayDivisionSwitch = true
            activeDelayDivision = requestedDivision
        } else if (!delayDivisionSwitch && delayTransitionPosition >= delayTransitionLength &&
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
            val source = input[i] / 32768f

            // Only FX HIGH remains. When OFF, the effect receives the untouched
            // full-band source: no LOW/MID splitters or hidden crossover states.
            // When ON, only the high-frequency send enters the Beat FX.
            val highBand = if (fxHighEnabled) fxHighSplit2500.processHigh(source) else source
            val highTarget = if (fxHighEnabled) 1f else 0f
            smoothedFxHigh += (highTarget - smoothedFxHigh) * fxHighSmoothing
            val x = if (fxHighEnabled) highBand * smoothedFxHigh else source

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
            val delayed = if (delayDivisionSwitch) {
                val halfLength = (delayTransitionLength / 2).coerceAtLeast(1)
                if (delayTransitionPosition < halfLength) {
                    val fade = (delayTransitionPosition.toFloat() / halfLength).coerceIn(0f, 1f)
                    val smoothFade = fade * fade * (3f - 2f * fade)
                    oldTap * (1f - smoothFade)
                } else {
                    val fade = ((delayTransitionPosition - halfLength).toFloat() /
                        (delayTransitionLength - halfLength).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val smoothFade = fade * fade * (3f - 2f * fade)
                    newTap * smoothFade
                }
            } else {
                oldTap + (newTap - oldTap) * transitionBlend
            }
            if (delayTransitionPosition < delayTransitionLength) {
                delayTransitionPosition++
            } else {
                delayTransitionFromSamples = delayTransitionToSamples
                delayDivisionSwitch = false
            }
            // Tone is fixed per voicing for now; the V10 TONE control is not
            // exposed in this simplified interface. Darker repeats in DUB mode
            // progressively lose top end, while SHORT stays crisp.
            delayToneState += delayToneAlpha * (delayed - delayToneState)
            // Keep the circular-buffer cursor in range even if a previous
            // interrupted processing cycle left it at an unexpected position.
            // Phase-shift only the incoming send. The delay feedback loop keeps
            // reading and writing at the exact selected beat period, so the jog
            // cannot retune BPM or make the repeats progressively drift.
            smoothedFxPhaseDelaySamples +=
                (targetFxPhaseDelaySamples - smoothedFxPhaseDelaySamples) * fxPhaseDelaySmoothing
            val safePhaseWriteIndex = Math.floorMod(phaseDelayWriteIndex, phaseDelayBuffer.size)
            phaseDelayBuffer[safePhaseWriteIndex] = x
            val phaseShiftedInput = readPhaseDelayTap(smoothedFxPhaseDelaySamples)
            phaseDelayWriteIndex = if (safePhaseWriteIndex + 1 >= phaseDelayBuffer.size) 0 else safePhaseWriteIndex + 1

            val safeWriteIndex = Math.floorMod(delayWriteIndex, delayBuffer.size)
            delayBuffer[safeWriteIndex] = (phaseShiftedInput * 0.68f + delayToneState * modeFeedback).coerceIn(-0.88f, 0.88f)
            delayWriteIndex = if (safeWriteIndex + 1 >= delayBuffer.size) 0 else safeWriteIndex + 1
            // TEST: OFF is a hard output gate. The fade controls continue to shape
            // the return while enabled, but a disabled switch must produce no wet
            // signal at all. This isolates lingering DSP tails from stale AudioTrack output.
            val reverbReturn = if (inputEnabled) wet * smoothedMix * 1.28f else 0f
            val delayReturn = if (delayEnabled) delayToneState * smoothedDelayMix * 1.28f else 0f
            // Frequency selection is upstream on the FX send. The wet return
            // remains untouched so reverb tails and delay feedback are natural.
            val raw = reverbReturn + delayReturn
            val magnitude = abs(raw)
            val target = if (magnitude > 0.84f) 0.84f / magnitude else 1f
            limiter = if (target < limiter) target else limiter + (1f - limiter) * 0.002f
            val safe = (raw * limiter).coerceIn(-0.84f, 0.84f)
            output[i] = (safe * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
