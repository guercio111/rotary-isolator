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
            putShort(0, -1000)
            putShort(1, -1500)
            putInt(2, decay.coerceIn(100, 8000))
            putShort(3, damping.coerceIn(100, 1000))
            putShort(4, -3000)
            putInt(5, 10)
            putShort(6, (-6000 + safeMix * 60))
            putInt(7, 20)
            putShort(8, size.coerceIn(0, 1000))
            putShort(9, size.coerceIn(0, 1000))

            stage = "attivazione Insert Reverb"
            created.enabled = true
            return "INSERT REVERB ATTIVO\nUID PROCESSO=${Process.myUid()} (2000=ADB/SHELL)\nPID=${Process.myPid()}\nID=${created.id}\nENABLED=${created.enabled}\nHAS CONTROL=${created.hasControl()}\nIMPLEMENTATION=$implementation\nSESSIONE=0 (OUTPUT MIX)\n\nRiproduci YouTube Morphe e verifica se senti il riverbero."
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