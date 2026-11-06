package com.example.rotaryisolator

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Tempo-divided dub delay with lightweight kick/transient tempo estimation. */
internal class DubDelay(private val sampleRate: Int) {
    private val divisions = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 2f, 4f, 8f)
    private val ring = FloatArray(sampleRate * 8 + 8)
    private var writeIndex = 0
    private var filteredFeedback = 0f
    private var inputEnvelope = 0f
    private var outputEnvelope = 0f

    // Low-frequency energy in 10 ms windows, adaptive threshold and beat interval median.
    private val analysisWindow = max(1, sampleRate / 100)
    private var windowSamples = 0
    private var windowEnergy = 0.0
    private var lowFrequencyState = 0f
    private var energyFloor = 0.00002
    private var sampleClock = 0L
    private var lastOnsetSample = -1L
    private var wasAboveThreshold = false
    private val beatIntervals = ArrayDeque<Long>()
    private var smoothedBpm = 120f
    private var beatPhase = 0.0
    private var activeDivisionIndex = 3
    private var pendingDivisionIndex = -1
    private var hasDetectedTempo = false
    private var tempoWasReported = false
    @Volatile var tempoLocked: Boolean = false
        private set
    @Volatile var detectedBpm: Float = 120f
        private set
    var onTempoChanged: ((Float) -> Unit)? = null

    private fun analyzeForTempo(sample: Float) {
        lowFrequencyState += 0.12f * (sample - lowFrequencyState)
        windowEnergy += lowFrequencyState.toDouble() * lowFrequencyState.toDouble()
        windowSamples++
        sampleClock++
        if (windowSamples < analysisWindow) return

        val energy = windowEnergy / windowSamples
        val threshold = max(0.000004, energyFloor * 1.55)
        val refractory = sampleRate / 4L
        val aboveThreshold = energy > threshold
        if (aboveThreshold && !wasAboveThreshold && energy > 0.000025 &&
            (lastOnsetSample < 0L || sampleClock - lastOnsetSample > refractory)) {
            if (lastOnsetSample >= 0L) {
                val interval = sampleClock - lastOnsetSample
                val seconds = interval.toDouble() / sampleRate
                if (seconds in 0.30..1.05) {
                    var candidate = (60.0 / seconds).toFloat()
                    while (candidate < 60f) candidate *= 2f
                    while (candidate > 180f) candidate *= 0.5f
                    beatIntervals.addLast((60.0 * sampleRate / candidate).toLong())
                    while (beatIntervals.size > 7) beatIntervals.removeFirst()
                    val sorted = beatIntervals.toList().sorted()
                    val medianInterval = sorted[sorted.size / 2].coerceAtLeast(1L)
                    val medianBpm = (60.0 * sampleRate / medianInterval).toFloat().coerceIn(60f, 180f)
                    smoothedBpm = if (hasDetectedTempo) smoothedBpm * 0.72f + medianBpm * 0.28f else medianBpm
                    hasDetectedTempo = true
                    tempoLocked = true
                    val precise = smoothedBpm.coerceIn(60f, 180f)
                    if (kotlin.math.abs(precise - detectedBpm) >= 0.05f || !tempoWasReported) {
                        detectedBpm = precise
                        tempoWasReported = true
                        onTempoChanged?.invoke(precise)
                    }
                }
            }
            lastOnsetSample = sampleClock
        }
        wasAboveThreshold = aboveThreshold
        energyFloor = energyFloor * 0.97 + energy * 0.03
        windowSamples = 0
        windowEnergy = 0.0
    }

    fun process(
        input: ShortArray,
        output: ShortArray,
        count: Int,
        enabled: Boolean,
        divisionIndex: Int,
        bpm: Float,
        autoSync: Boolean,
        feedbackPercent: Int,
        filterPercent: Int,
        mixPercent: Int,
        fadeMs: Int,
        tempoTrim: Float,
        beatLock: Boolean,
        phaseNudgeMs: Float
    ) {
        val requestedDivision = divisionIndex.coerceIn(0, divisions.lastIndex)
        if (!beatLock) {
            activeDivisionIndex = requestedDivision
            pendingDivisionIndex = -1
        } else if (requestedDivision != activeDivisionIndex) {
            pendingDivisionIndex = requestedDivision
        }
        val baseTempo = if (autoSync && hasDetectedTempo) detectedBpm else bpm
        val tempo = (baseTempo + tempoTrim.coerceIn(-6f, 6f)).coerceIn(60f, 180f)
        val division = divisions[activeDivisionIndex.coerceIn(0, divisions.lastIndex)]
        val delaySamples = ((60.0 / tempo.toDouble() * division) * sampleRate).toInt().coerceIn(1, ring.size - 2)
        val phaseNudgeSamples = (phaseNudgeMs.coerceIn(-100f, 100f) * sampleRate / 1000f).roundToInt()
        val feedback = feedbackPercent.coerceIn(0, 90) / 100f
        val mix = mixPercent.coerceIn(0, 100) / 100f
        val cutoff = 12000f - filterPercent.coerceIn(0, 100) * 105f
        val filterAlpha = (1.0 - exp(-2.0 * PI * cutoff / sampleRate)).toFloat().coerceIn(0.001f, 1f)
        val fadeSeconds = fadeMs.coerceIn(300, 6000) / 1000f
        val smoothing = (1.0 - exp(-1.0 / (sampleRate * fadeSeconds))).toFloat().coerceIn(0.000001f, 1f)
        val inputTarget = if (enabled) 1f else 0f
        val outputTarget = if (enabled) mix else 0f

        for (i in 0 until count) {
            val dryInput = input[i] / 32768f
            analyzeForTempo(dryInput)
            inputEnvelope += (inputTarget - inputEnvelope) * smoothing
            outputEnvelope += (outputTarget - outputEnvelope) * smoothing

            beatPhase += tempo.toDouble() / (60.0 * sampleRate)
            if (beatPhase >= 1.0) {
                beatPhase -= 1.0
                if (beatLock && pendingDivisionIndex >= 0) {
                    activeDivisionIndex = pendingDivisionIndex
                    pendingDivisionIndex = -1
                }
            }
            var readIndex = writeIndex - delaySamples + phaseNudgeSamples
            while (readIndex < 0) readIndex += ring.size
            while (readIndex >= ring.size) readIndex -= ring.size
            val echo = ring[readIndex]
            filteredFeedback += filterAlpha * (echo - filteredFeedback)
            val feedbackInput = filteredFeedback * feedback

            // TEST254: stronger input send (+32%) while retaining internal headroom.
            ring[writeIndex] = (dryInput * inputEnvelope * 0.95f + feedbackInput).coerceIn(-0.92f, 0.92f)

            // Stronger delay return; the main output mix remains hard-limited downstream.
            val wet = filteredFeedback * outputEnvelope * 1.00f
            output[i] = (wet.coerceIn(-0.90f, 0.90f) * 32767f).toInt().coerceIn(-32768, 32767).toShort()

            writeIndex++
            if (writeIndex == ring.size) writeIndex = 0
        }
    }

    fun clear() {
        ring.fill(0f)
        writeIndex = 0
        activeDivisionIndex = 3
        pendingDivisionIndex = -1
        beatPhase = 0.0
        filteredFeedback = 0f
        inputEnvelope = 0f
        outputEnvelope = 0f
        lowFrequencyState = 0f
        windowSamples = 0
        windowEnergy = 0.0
        energyFloor = 0.00002
        sampleClock = 0L
        lastOnsetSample = -1L
        wasAboveThreshold = false
        beatIntervals.clear()
        smoothedBpm = 120f
        hasDetectedTempo = false
        tempoWasReported = false
        tempoLocked = false
        detectedBpm = 120f
    }
}
