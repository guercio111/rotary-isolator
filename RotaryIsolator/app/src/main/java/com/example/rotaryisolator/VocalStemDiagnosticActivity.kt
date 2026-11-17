package com.example.rotaryisolator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

/**
 * Isolated diagnostic for the experimental VOCALS-only ONNX branch.
 * It does not request microphone/MediaProjection access and never touches
 * FREQUALIZER's capture, effects, BPM or playback paths.
 */
class VocalStemDiagnosticActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 19, 23)
        window.navigationBarColor = Color.rgb(17, 19, 23)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.rgb(17, 19, 23))
        }
        val title = TextView(this).apply {
            text = "VOCAL PROTECT — TEST"
            textSize = 24f
            setTextColor(Color.rgb(230, 190, 90))
            gravity = Gravity.CENTER
        }
        val note = TextView(this).apply {
            text = "Banco di prova isolato. Non modifica l'audio e non tocca il motore BPM. Il test usa un segnale sintetico per misurare caricamento e velocità del modello sul dispositivo."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 20)
        }
        runButton = Button(this).apply {
            text = "CARICA MODELLO E AVVIA TEST"
            setOnClickListener { runDiagnostic() }
        }
        status = TextView(this).apply {
            text = "Pronto. Premi il pulsante per iniziare."
            textSize = 15f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        }
        copyButton = Button(this).apply {
            text = "COPIA RISULTATO"
            isEnabled = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Vocal Protect test", status.text))
                Toast.makeText(this@VocalStemDiagnosticActivity, "Risultato copiato. Incollalo qui in chat.", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(title)
        root.addView(note)
        root.addView(runButton)
        root.addView(copyButton)
        root.addView(status)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun runDiagnostic() {
        runButton.isEnabled = false
        status.text = "Verifica modello e inizializzazione ONNX Runtime (NNAPI + CPU fallback)…"
        Thread {
            val report = try {
                performDiagnostic()
            } catch (t: Throwable) {
                "TEST NON RIUSCITO\n${t.javaClass.simpleName}: ${t.message ?: "errore senza dettagli"}\n\nNessun percorso audio è stato modificato."
            }
            runOnUiThread {
                status.text = report
                copyButton.isEnabled = true
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun performDiagnostic(): String {
        val model = File(filesDir, "vocals.onnx")
        if (!model.exists() || model.length() != MODEL_BYTES || sha256(model) != MODEL_SHA256) {
            assets.open("vocals.onnx").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(model.length() == MODEL_BYTES) { "Dimensione modello errata: ${model.length()} byte" }
        check(sha256(model) == MODEL_SHA256) { "SHA-256 del modello non corrispondente" }

        val loadStart = System.nanoTime()
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        options.setInterOpNumThreads(1)
        // Try Android NNAPI first; ONNX Runtime keeps its CPU provider as fallback.
        options.addNnapi()
        val session = try {
            env.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

        try {
            val expectedInputs = setOf(
                "audio_chunk",
                "vocals_audio_history",
                "vocals_local_hidden",
                "vocals_global_hidden",
                "vocals_spectral_numerator_tail"
            )
            val expectedOutputs = setOf(
                "separated_chunk",
                "next_vocals_audio_history",
                "next_vocals_local_hidden",
                "next_vocals_global_hidden",
                "next_vocals_spectral_numerator_tail"
            )
            check(session.inputNames == expectedInputs) { "Nomi input ONNX inattesi: ${session.inputNames}" }
            check(session.outputNames == expectedOutputs) { "Nomi output ONNX inattesi: ${session.outputNames}" }

            val audio = directFloats(256)
            val audioHistory = directFloats(1792)
            val localHidden = directFloats(3840)
            val globalHidden = directFloats(384)
            val spectralTail = directFloats(256)

            val tensors = linkedMapOf<String, OnnxTensor>()
            tensors["audio_chunk"] = OnnxTensor.createTensor(env, audio, longArrayOf(1, 2, 128))
            tensors["vocals_audio_history"] = OnnxTensor.createTensor(env, audioHistory, longArrayOf(1, 2, 896))
            tensors["vocals_local_hidden"] = OnnxTensor.createTensor(env, localHidden, longArrayOf(2, 20, 96))
            tensors["vocals_global_hidden"] = OnnxTensor.createTensor(env, globalHidden, longArrayOf(2, 1, 192))
            tensors["vocals_spectral_numerator_tail"] = OnnxTensor.createTensor(env, spectralTail, longArrayOf(1, 1, 2, 128))

            try {
                val inputMap = tensors.toMap()
                val warmupHops = 64
                val measuredHops = 690 // circa 2 secondi a 44.1 kHz
                val durations = LongArray(measuredHops)
                var finiteOutputSamples = 0
                var outputPeak = 0f

                fun infer(hop: Int): Long {
                    for (i in 0 until 128) {
                        val sample = (
                            0.12 * sin(2.0 * PI * 110.0 * (hop * 128 + i) / 44100.0) +
                            0.08 * sin(2.0 * PI * 440.0 * (hop * 128 + i) / 44100.0) +
                            0.04 * sin(2.0 * PI * 2200.0 * (hop * 128 + i) / 44100.0)
                        ).toFloat()
                        audio.put(i, sample)
                        audio.put(128 + i, sample)
                    }
                    val start = System.nanoTime()
                    val result = session.run(inputMap)
                    try {
                        val vocals = result.get("separated_chunk").get() as OnnxTensor
                        val separated = vocals.floatBuffer
                        for (i in 0 until separated.remaining()) {
                            val value = separated.get(i)
                            if (value.isFinite()) finiteOutputSamples++
                            val magnitude = kotlin.math.abs(value)
                            if (magnitude > outputPeak) outputPeak = magnitude
                        }
                        copyState(result, "next_vocals_audio_history", audioHistory)
                        copyState(result, "next_vocals_local_hidden", localHidden)
                        copyState(result, "next_vocals_global_hidden", globalHidden)
                        copyState(result, "next_vocals_spectral_numerator_tail", spectralTail)
                    } finally {
                        result.close()
                    }
                    return System.nanoTime() - start
                }

                repeat(warmupHops) { infer(it) }
                val totalStart = System.nanoTime()
                for (i in 0 until measuredHops) durations[i] = infer(warmupHops + i)
                val totalNs = System.nanoTime() - totalStart
                durations.sort()
                val meanMs = totalNs.toDouble() / measuredHops / 1_000_000.0
                val p95Ms = durations[((measuredHops - 1) * 0.95).toInt()] / 1_000_000.0
                val maxMs = durations.last() / 1_000_000.0
                val audioSeconds = measuredHops * 128.0 / 44100.0
                val elapsedSeconds = totalNs / 1_000_000_000.0
                val realtimeFactor = elapsedSeconds / audioSeconds
                val deadlineMs = 128.0 / 44100.0 * 1000.0
                val verdict = if (meanMs < deadlineMs && p95Ms < deadlineMs) {
                    "MEDIA E P95 SOTTO LA FINESTRA DI UN HOP"
                } else {
                    "NON ANCORA IN TEMPO REALE: SERVE OTTIMIZZAZIONE O UN'ALTRA STRATEGIA"
                }
                return """
                    MODELLO: OK
                    SHA-256: verificato
                    Input/output: nomi verificati
                    Inferenza: $measuredHops hop misurati dopo $warmupHops hop di riscaldamento\nProvider: NNAPI prioritario + CPU fallback\nConfigurazione CPU fallback: 4 thread intra-op, 1 thread inter-op
                    Caricamento sessione: ${"%.1f".format(loadMs)} ms
                    Tempo medio per hop: ${"%.3f".format(meanMs)} ms
                    P95 per hop: ${"%.3f".format(p95Ms)} ms
                    Picco per hop: ${"%.3f".format(maxMs)} ms
                    Budget di un hop a 44,1 kHz: ${"%.3f".format(deadlineMs)} ms
                    Fattore tempo reale: ${"%.2f".format(realtimeFactor)}x (1,00x = tempo reale)
                    Campioni uscita finiti: $finiteOutputSamples
                    Picco uscita separata: ${"%.5f".format(outputPeak)}
                    
                    ESITO: $verdict
                    
                    NOTA: test NNAPI con segnale sintetico. La registrazione NNAPI non garantisce che ogni operazione sia stata eseguita su GPU/NPU: i nodi non supportati possono ricadere su CPU. Non è una prova di qualità musicale né l'integrazione negli effetti. Cattura, BPM e DSP di FREQUALIZER non vengono usati o modificati.
                """.trimIndent()
            } finally {
                tensors.values.forEach { it.close() }
            }
        } finally {
            session.close()
        }
    }

    private fun copyState(result: OrtSession.Result, name: String, target: FloatBuffer) {
        val tensor = result.get(name).get() as OnnxTensor
        val source = tensor.floatBuffer
        require(source.remaining() == target.capacity()) { "Dimensione stato inattesa per $name" }
        for (i in 0 until source.remaining()) target.put(i, source.get(i))
    }

    private fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MODEL_BYTES = 11019332L
        private const val MODEL_SHA256 = "f29a3d8fa596ce4672dd4dc77ccf224f2538389940264aad46ab8168311c2148"
    }
}
