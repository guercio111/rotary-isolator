package com.example.rotaryisolator

import android.media.audiofx.AudioEffect
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.PresetReverb
import android.os.Process
import android.util.Log

class ShizukuReverbUserService : IShizukuReverbService.Stub() {
    private var effect: AudioEffect? = null

    override fun startReverb(mix: Int, decay: Int, size: Int, damping: Int): String {
        stopReverb()
        val report = StringBuilder()
        report.append("SHIZUKU OFFICIAL REVERB CONSTRUCTOR TEST\n")
        report.append("UID=${Process.myUid()} | PID=${Process.myPid()}\n")
        report.append("SESSIONE=0 (OUTPUT MIX)\n")

        var environmental: EnvironmentalReverb? = null
        report.append("\n--- TEST 1: ENVIRONMENTAL REVERB UFFICIALE ---\n")
        try {
            environmental = EnvironmentalReverb(0)
            report.append("EnvironmentalReverb(0): OK | ID=${environmental.id} | HAS CONTROL=${environmental.hasControl()}\n")
        } catch (t: Throwable) {
            report.append("EnvironmentalReverb(0): FALLITO\n${Log.getStackTraceString(t)}\n")
        } finally {
            try { environmental?.enabled = false } catch (_: Throwable) {}
            try { environmental?.release() } catch (_: Throwable) {}
        }

        report.append("\n--- TEST 2: PRESET REVERB UFFICIALE ---\n")
        var preset: PresetReverb? = null
        try {
            preset = PresetReverb(0)
            report.append("PresetReverb(0): OK | ID=${preset.id} | HAS CONTROL=${preset.hasControl()}\n")
            preset.preset = PresetReverb.PRESET_LARGEHALL
            preset.enabled = true
            effect = preset
            report.append("PRESET=LARGEHALL | ATTIVATO=SI\n")
        } catch (t: Throwable) {
            try { preset?.enabled = false } catch (_: Throwable) {}
            try { preset?.release() } catch (_: Throwable) {}
            report.append("PresetReverb(0): FALLITO\n${Log.getStackTraceString(t)}\n")
        }
        report.append("\nSe Preset risulta attivo, prova YouTube Morphe. Per interrompere premi STOP REVERB.")
        return report.toString()
    }

    override fun stopReverb() {
        try { effect?.enabled = false } catch (_: Throwable) {}
        try { effect?.release() } catch (_: Throwable) {}
        effect = null
    }

    override fun destroy() {
        stopReverb()
        System.exit(0)
    }
}