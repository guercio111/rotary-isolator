package com.example.rotaryisolator;

/**
 * Streaming tempo estimator for 48 kHz stereo PCM.
 * Uses spectral-flux onsets and multi-hypothesis tempo autocorrelation.
 * The estimator is deliberately independent from the master clock.
 */
public final class BeatEngine {
    public static final int SAMPLE_RATE = 48000;
    private static final int HOP = 256;
    private static final int FFT_SIZE = 1024;
    private static final int RING_SIZE = 2400;
    private static final float HOP_MS = 1000f * HOP / SAMPLE_RATE;

    private final float[] novelty = new float[RING_SIZE];
    private final float[] sampleWindow = new float[FFT_SIZE];
    private final float[] previousMagnitude = new float[FFT_SIZE / 2 + 1];
    private final float[] real = new float[FFT_SIZE];
    private final float[] imag = new float[FFT_SIZE];
    private int ringWrite, ringCount, sampleWrite, samplesFilled, hopCounter;
    private float detectedBpm, confidence;
    private long lastAnalysisMs, phaseMs;
    private double periodMs;
    private float manualOffset;
    private boolean locked;
    private float candidateBpm;
    private int candidateStreak;
    private float lockedBpm;
    private double lockedPeriodMs;

    public synchronized void reset() {
        ringWrite = ringCount = sampleWrite = samplesFilled = hopCounter = 0;
        detectedBpm = confidence = candidateBpm = 0f;
        candidateStreak = 0;
        lockedBpm = 0f;
        lockedPeriodMs = 0.0;
        lastAnalysisMs = phaseMs = 0L;
        periodMs = 0.0;
        locked = false;
        for (int i = 0; i < sampleWindow.length; i++) sampleWindow[i] = 0f;
        for (int i = 0; i < previousMagnitude.length; i++) previousMagnitude[i] = 0f;
    }

    public synchronized void setManualOffset(float bpm) {
        manualOffset = Math.max(-10f, Math.min(10f, bpm));
    }
    public synchronized float getManualOffset() { return manualOffset; }
    public synchronized float getDetectedBpm() { return detectedBpm; }
    public synchronized float getConfidence() { return confidence; }
    public synchronized float getClockBpm() {
        if (locked && lockedBpm > 0f) return lockedBpm;
        if (detectedBpm <= 0f) return 0f;
        return Math.max(50f, Math.min(250f, detectedBpm + manualOffset));
    }
    public synchronized boolean isLocked() { return locked; }
    public synchronized long getPhaseMs() { return phaseMs; }
    public synchronized double getPeriodMs() { return periodMs; }

    /** Re-align phase to the strongest recent detected attack without restarting capture. */
    public synchronized void sync(long nowMs) {
        if (periodMs <= 0.0 || ringCount == 0) return;
        int searchFrames = Math.max(1, Math.min(ringCount - 1,
                (int)Math.ceil((periodMs * 2.0) / HOP_MS)));
        int strongestAge = 0;
        float strongest = Float.NEGATIVE_INFINITY;
        for (int age = 0; age < searchFrames; age++) {
            float value = atFromNewest(age);
            if (value > strongest) {
                strongest = value;
                strongestAge = age;
            }
        }
        phaseMs = nowMs - Math.round(strongestAge * HOP_MS);
    }

    /** Freeze/unfreeze the tempo clock. Detection continues in the background. */
    public synchronized void setClockLocked(boolean value) {
        if (value && !locked && periodMs > 0.0) {
            lockedBpm = Math.max(50f, Math.min(250f, detectedBpm + manualOffset));
            lockedPeriodMs = 60000.0 / lockedBpm;
            periodMs = lockedPeriodMs;
        } else if (!value) {
            lockedBpm = 0f;
            lockedPeriodMs = 0.0;
        }
        locked = value;
    }

    /** Fine phase nudge; positive moves the clock later, negative earlier. */
    public synchronized void nudgePhase(float milliseconds) {
        if (phaseMs > 0L) phaseMs += Math.round(milliseconds);
    }

    public synchronized void resetPhase() {
        phaseMs = 0L;
        locked = false;
        lockedBpm = 0f;
        lockedPeriodMs = 0.0;
    }

    public synchronized void process(short[] pcm, int length, long nowMs) {
        if (pcm == null) return;
        int usable = Math.min(length, pcm.length);
        for (int i = 0; i + 1 < usable; i += 2) {
            float mono = ((pcm[i] + pcm[i + 1]) * 0.5f) / 32768f;
            sampleWindow[sampleWrite] = mono;
            sampleWrite = (sampleWrite + 1) % FFT_SIZE;
            if (samplesFilled < FFT_SIZE) samplesFilled++;
            hopCounter++;
            if (samplesFilled == FFT_SIZE && hopCounter >= HOP) {
                hopCounter = 0;
                addSpectralFlux();
            }
        }
        if (nowMs - lastAnalysisMs >= 250L && ringCount >= 900) {
            analyse(nowMs);
            lastAnalysisMs = nowMs;
        }
    }

    /** Hann-windowed spectral flux, balanced across bass, mid and high bands. */
    private void addSpectralFlux() {
        for (int i = 0; i < FFT_SIZE; i++) {
            int index = sampleWrite + i;
            if (index >= FFT_SIZE) index -= FFT_SIZE;
            float hann = 0.5f - 0.5f * (float)Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));
            real[i] = sampleWindow[index] * hann;
            imag[i] = 0f;
        }
        fft(real, imag);

        double lowFlux = 0, midFlux = 0, highFlux = 0;
        double lowEnergy = 0, midEnergy = 0, highEnergy = 0;
        for (int k = 1; k <= FFT_SIZE / 2; k++) {
            float mag = (float)Math.sqrt(real[k] * real[k] + imag[k] * imag[k]);
            float rise = Math.max(0f, mag - previousMagnitude[k]);
            previousMagnitude[k] = mag;
            if (k <= 8) { lowFlux += rise; lowEnergy += mag; }
            else if (k <= 64) { midFlux += rise; midEnergy += mag; }
            else if (k <= 220) { highFlux += rise; highEnergy += mag; }
        }
        // Relative flux prevents bass-heavy recordings from masking snare/hat attacks.
        double low = lowFlux / (0.015 + lowEnergy);
        double mid = midFlux / (0.015 + midEnergy);
        double high = highFlux / (0.015 + highEnergy);
        float onset = (float)(0.55 * low + 1.00 * mid + 0.75 * high);
        onset = (float)Math.log1p(Math.max(0.0, onset));
        novelty[ringWrite] = onset;
        ringWrite = (ringWrite + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;
    }

    /** In-place radix-2 Cooley-Tukey FFT. */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wLenR = (float)Math.cos(angle), wLenI = (float)Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    float vr = re[v] * wr - im[v] * wi;
                    float vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr; im[u] += vi;
                    float nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }

    private void analyse(long nowMs) {
        int minLag = (int)(60000.0 / 200.0 / HOP_MS);
        int maxLag = (int)(60000.0 / 60.0 / HOP_MS);
        int window = Math.min(ringCount - maxLag - 1, 1400);
        if (window < 700) return;

        double mean = 0.0;
        for (int i = 0; i < window; i++) mean += atFromNewest(i);
        mean /= window;
        double[] scores = new double[maxLag + 1];
        for (int lag = minLag / 2; lag <= maxLag; lag++) {
            double xy = 0.0, xx = 0.0, yy = 0.0;
            for (int i = 0; i < window; i++) {
                double x = atFromNewest(i) - mean;
                double y = atFromNewest(i + lag) - mean;
                xy += x * y; xx += x * x; yy += y * y;
            }
            scores[lag] = xy / Math.sqrt(Math.max(1e-12, xx * yy));
        }

        int chosenLag = 0;
        double bestScore = -1.0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double direct = Math.max(0.0, scores[lag]);
            if (direct < 0.06) continue;
            double score = direct;
            if (lag * 2 <= maxLag) score += 0.50 * Math.max(0.0, scores[lag * 2]);
            if (lag * 3 <= maxLag) score += 0.20 * Math.max(0.0, scores[lag * 3]);

            // If a slower candidate has clear periodic evidence at twice its
            // tempo, penalize the half-time interpretation.
            if (lag / 2 >= minLag) score -= 0.55 * Math.max(0.0, scores[lag / 2]);

            float bpm = 60000f / (lag * HOP_MS);
            // Very gentle DJ-tempo prior, never enough to override real evidence.
            score += 0.025 * Math.exp(-Math.abs(bpm - 120.0) / 45.0);
            if (score > bestScore) { bestScore = score; chosenLag = lag; }
        }

        if (chosenLag == 0) {
            confidence *= 0.92f;
            if (confidence < 0.12f) locked = false;
            candidateStreak = 0;
            return;
        }

        float rawBpm = 60000f / (chosenLag * HOP_MS);
        float quality = (float)Math.max(0.0, Math.min(1.0,
                (scores[chosenLag] - 0.05) / 0.42));

        if (candidateBpm == 0f || Math.abs(rawBpm - candidateBpm) > Math.max(1.5f, candidateBpm * 0.018f)) {
            candidateBpm = rawBpm;
            candidateStreak = 1;
        } else {
            candidateBpm += (rawBpm - candidateBpm) * 0.15f;
            candidateStreak = Math.min(24, candidateStreak + 1);
        }

        boolean octaveChange = detectedBpm > 0f &&
                (candidateBpm / detectedBpm > 1.80f || candidateBpm / detectedBpm < 0.56f);
        int confirmationsNeeded = octaveChange ? 16 : 4;
        if (candidateStreak < confirmationsNeeded) {
            confidence *= 0.985f;
            return;
        }

        if (detectedBpm == 0f) {
            detectedBpm = candidateBpm;
        } else if (octaveChange) {
            // Octave changes require sustained evidence; then switch cleanly.
            detectedBpm = candidateBpm;
        } else {
            float delta = candidateBpm - detectedBpm;
            detectedBpm += Math.max(-0.35f, Math.min(0.35f, delta)) * 0.18f;
        }

        float stability = Math.min(1f, candidateStreak / 8f);
        float measuredConfidence = quality * stability;
        confidence = confidence * 0.82f + measuredConfidence * 0.18f;

        double newPeriod = 60000.0 / getClockBpm();
        if (periodMs <= 0.0) {
            periodMs = newPeriod;
            phaseMs = nowMs;
            locked = confidence > 0.25f;
        } else {
            periodMs += (newPeriod - periodMs) * 0.02;
            if (!locked && confidence > 0.35f) {
                phaseMs = nowMs;
                locked = true;
            }
        }
    }

    private float atFromNewest(int age) {
        int index = ringWrite - 1 - age;
        while (index < 0) index += RING_SIZE;
        return novelty[index];
    }
}
