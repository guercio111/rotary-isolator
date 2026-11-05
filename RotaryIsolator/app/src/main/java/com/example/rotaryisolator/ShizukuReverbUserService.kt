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
        val report = StringBuilder()
        try {
            stage = "queryEffects nel processo Shizuku"
            val descriptors = AudioEffect.queryEffects()
            val envType = UUID.fromString("c2e5d5f0-94bd-4763-9cac-4e234d06839e")
            report.append("SHIZUKU AUDIO EFFECT PROBE\\n")
            report.append("UID=${Process.myUid()} | PID=${Process.myPid()}\\n")
            report.append("DESCRIPTORS=${descriptors?.size ?: 0}\\n")

            if (descriptors.isNullOrEmpty()) {
                return report.append("Android non restituisce descrittori AudioEffect nel processo Shizuku.\\n")
                    .append("Nessun tentativo di attivazione eseguito.").toString()
            }

            descriptors.forEach { d ->
                report.append("\\n• ${d.name} | ${d.implementor}")
                    .append("\\n  MODE=${d.connectMode} TYPE=${d.type} UUID=${d.uuid}\\n")
            }

            stage = "selezione implementazioni Environmental Reverb"
            val matching = descriptors.filter { it.type == envType }
            if (matching.isEmpty()) {
                return report.append("\\nNESSUN DESCRITTORE CON TYPE ENVIRONMENTAL REVERB.")
                    .append("\\nTYPE RICHIESTO=$envType").toString()
            }

            val insertFirst = matching.sortedBy { if (it.connectMode.equals("Insert", true)) 0 else 1 }
            val failures = StringBuilder()
            for (descriptor in insertFirst) {
                stage = "creazione AudioEffect con UUID ${descriptor.uuid} / mode ${descriptor.connectMode}"
                try {
                    val constructor = AudioEffect::class.java.getDeclaredConstructor(
                        UUID::class.java, UUID::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    val created = constructor.newInstance(descriptor.type, descriptor.uuid, 100, 0) as AudioEffect
                    effect = created

                    stage = "scrittura parametri reverb"
                    val setShort = AudioEffect::class.java.getDeclaredMethod(
                        "setParameter", Int::class.javaPrimitiveType, Short::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    val setInt = AudioEffect::class.java.getDeclaredMethod(
                        "setParameter", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }

                    fun putShort(id: Int, value: Int) {
                        val result = setShort.invoke(created, id, value.toShort()) as Int
                        if (result < 0) throw IllegalStateException("Parametro $id rifiutato: $result")
                    }
                    fun putInt(id: Int, value: Int) {
                        val result = setInt.invoke(created, id, value) as Int
                        if (result < 0) throw IllegalStateException("Parametro $id rifiutato: $result")
                    }

                    val safeMix = mix.coerceIn(0, 100)
                    val safeDecay = decay.coerceIn(300, 1800)
                    val safeDamping = damping.coerceIn(300, 650)
                    val wetLevel = (-6000 + safeMix * 35).coerceIn(-6000, -2500)
                    putShort(0, -1800)
                    putShort(1, -1900)
                    putInt(2, safeDecay)
                    putShort(3, safeDamping)
                    putShort(4, -5200)
                    putInt(5, 12)
                    putShort(6, wetLevel)
                    putInt(7, 22)
                    putShort(8, 850)
                    putShort(9, 850)

                    stage = "attivazione Insert Reverb"
                    created.enabled = safeMix > 0
                    return report.append("\\nCREAZIONE RIUSCITA")
                        .append("\\nIMPLEMENTAZIONE=${descriptor.uuid}")
                        .append("\\nMODE=${descriptor.connectMode}")
                        .append("\\nID=${created.id} ENABLED=${created.enabled} HAS CONTROL=${created.hasControl()}")
                        .append("\\nSESSIONE=0 (OUTPUT MIX)")
                        .append("\\nPARAMETRI MIX=$safeMix / DECAY=$safeDecay ms / DAMPING=$safeDamping / WET=$wetLevel dB")
                        .append("\\n\\nProva YouTube Morphe e verifica se il riverbero è udibile.").toString()
                } catch (t: Throwable) {
                    try { effect?.release() } catch (_: Throwable) {}
                    effect = null
                    failures.append("\\nFALLITO UUID=${descriptor.uuid} MODE=${descriptor.connectMode}")
                        .append("\\n${Log.getStackTraceString(t)}\\n")
                }
            }
            return report.append("\\nNESSUNA IMPLEMENTAZIONE AVVIABILE.")
                .append("\\nDETTAGLI TENTATIVI:").append(failures).toString()
        } catch (t: Throwable) {
            stopReverb()
            return "ERRORE SHIZUKU REVERB PROBE\\nFASE: $stage\\nUID PROCESSO=${Process.myUid()}\\nPID=${Process.myPid()}\\n\\n${Log.getStackTraceString(t)}"
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