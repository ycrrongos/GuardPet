package com.geekathon.guardpet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.util.concurrent.Executors

/** Offline SenseVoice ASR via sherpa-onnx (CPU). Decode never on main thread. */
object SenseVoiceAsr {
    private const val TAG = "SenseVoiceAsr"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sensevoice-asr").apply { isDaemon = true }
    }

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private val tagRegex = Regex("<\\|[^|]*\\|>")

    fun hasModel(context: Context): Boolean =
        SenseVoiceModelStore.hasLocalModel(context) ||
            SenseVoiceModelStore.isPackInstalled(context)

    fun warmUp(context: Context) {
        executor.execute { ensureRecognizer(context.applicationContext) }
    }

    fun transcribe(context: Context, wavPath: String, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val text = runCatching { decode(app, wavPath) }
                .onFailure { Log.e(TAG, "transcribe failed: $wavPath", it) }
                .getOrNull()
            mainHandler.post { onResult(text) }
        }
    }

    private fun decode(context: Context, wavPath: String): String? {
        val rec = ensureRecognizer(context) ?: return null
        val wave = WaveReader.readWave(wavPath)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            rec.decode(stream)
            val raw = rec.getResult(stream).text.trim()
            val cleaned = stripTags(raw).trim()
            return cleaned.ifBlank { null }
        } finally {
            stream.release()
        }
    }

    private fun ensureRecognizer(context: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val dir = SenseVoiceModelStore.ensureLocalModel(context) ?: return null
            return runCatching {
                val model = File(dir, "model.int8.onnx").absolutePath
                val tokens = File(dir, "tokens.txt").absolutePath
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        provider = "cpu",
                    )
                )
                // null AssetManager → load absolute file paths
                OfflineRecognizer(null, config).also {
                    recognizer = it
                    Log.i(TAG, "OfflineRecognizer ready at ${dir.absolutePath}")
                }
            }.onFailure {
                Log.e(TAG, "Failed to init OfflineRecognizer", it)
            }.getOrNull()
        }
    }

    fun stripTags(text: String): String = tagRegex.replace(text, "").replace(Regex("\\s+"), " ")
}
