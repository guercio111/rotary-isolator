package com.example.rotaryisolator

import android.media.audiofx.AudioEffect
import android.os.Process
import android.util.Log
import java.util.UUID

class ShizukuReverbUserService : IShizukuReverbService.Stub() {
    private var effect: AudioEffect? = null

    override fun startReverb(mix: Int, decay: Int, size: Int, damping: Int): String {
        stopReverb()
        var stage = "inizializzazione servizio"
        try {
            stage = "lettura UUID Insert Environmental Reverb"
            val type = UUID.fromString("c2e5d5f0-94bd-4763-9cac-4e234d06839e")
            val implementation = UUID.fromString("c7a511a0-a3bc-11df-860e-0002a5d5c51b")

            stage = "creazione AudioEffect nel processo Shizuku (UID ${Process.myUid()})"
            val constructor = AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java, UUID::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val created = constructor.newInstance(type, implementation, 100, 0) as AudioEffect
            effect = created

            stage = "accesso setParameter"
            val setShort = AudioEffect::class.java.getDeclaredMethod(
                "setParameter", Int::class.javaPrimitiveType, Short::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val setInt = AudioEffect::class.java.getDeclaredMethod(
                "setParameter", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            fun putShort(id: Int, value: Int) {
                stage = "scrittura parametro short $id"
                val result = setShort.invoke(created, id, value.toShort()) as Int
                if (result < 0) throw IllegalStateException("Parametro $id rifiutato dal driver: $result")
            }
            fun putInt(id: Int, value: Int) {
                stage = "scrittura parametro int $id"
                val result = setInt.invoke(created, id, value) as Int
                if (result < 0) throw IllegalStateException("Parametro $id rifiutato dal driver: $result")
            }

            val safeMix = mix.coerceIn(0, 100)
            // Softer room and early reflections reduce the metallic/ringing character.
            // Shorter decay with high diffusion/density gives a smoother, less tonal tail.
            val safeDecay = decay.coerceIn(300, 1800)
            val safeDamping = damping.coerceIn(300, 650)
            val wetLevel = (-6000 + safeMix * 35).coerceIn(-6000, -2500)

            putShort(0, -1800)                 // room level
            putShort(1, -1900)                 // room HF level
            putInt(2, safeDecay)               // decay time (ms)
            putShort(3, safeDamping)           // decay HF ratio
            putShort(4, -5200)                 // reflections level
            putInt(5, 12)                       // reflections delay (ms)
            putShort(6, wetLevel)              // reverb level
            putInt(7, 22)                      // reverb delay (ms)
            putShort(8, 850)                   // diffusion
            putShort(9, 850)                   // density

            stage = "attivazione Insert Reverb"
            created.enabled = safeMix > 0
            return "INSERT REVERB ATTIVO\nUID PROCESSO=${Process.myUid()} (2000=ADB/SHELL)\nPID=${Process.myPid()}\nID=${created.id}\nENABLED=${created.enabled}\nHAS CONTROL=${created.hasControl()}\nIMPLEMENTATION=$implementation\nSESSIONE=0 (OUTPUT MIX)\nPARAMETRI: MIX=$safeMix / DECAY=$safeDecay ms / DAMPING=$safeDamping / DIFFUSION=850 / DENSITY=850 / WET=$wetLevel dB\n\nProva YouTube Morphe. Se senti ancora una risonanza marcata, interrompi con STOP / DISATTIVA."
        } catch (t: Throwable) {
            stopReverb()
            return "ERRORE SHIZUKU INSERT REVERB\nFASE: $stage\nUID PROCESSO=${Process.myUid()}\nPID=${Process.myPid()}\n\n${Log.getStackTraceString(t)}"
        }
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