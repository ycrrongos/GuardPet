package com.geekathon.guardpet

import android.app.ActivityOptions
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pranav.reef.accessibility.BlockerService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class FlashNoteRecorder(private val context: android.content.Context) {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var pcmBytes = 0
    var outputPath: String? = null
        private set
    val isRecording: Boolean
        get() = recording.get()

    fun start(): Boolean {
        if (isRecording) stop()
        notifyPetMic(true)
        val dir = File(context.filesDir, AUDIO_DIR).apply { mkdirs() }
        val file = File(dir, "rec_${System.currentTimeMillis()}.wav")
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (minBuf <= 0) {
            notifyPetMic(false)
            return false
        }
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_IN)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            notifyPetMic(false)
            return false
        }
        outputPath = file.absolutePath
        pcmBytes = 0
        audioRecord = record
        recording.set(true)
        runCatching { record.startRecording() }.onFailure {
            recording.set(false)
            runCatching { record.release() }
            audioRecord = null
            outputPath = null
            notifyPetMic(false)
            return false
        }
        recordThread = Thread({
            FileOutputStream(file).use { out ->
                out.write(ByteArray(WAV_HEADER))
                val buf = ByteArray(minBuf)
                while (recording.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        pcmBytes += n
                    }
                }
                out.flush()
            }
            writeWavHeader(file, pcmBytes)
        }, "flash-note-record").also { it.start() }
        return true
    }

    fun stop(): String? {
        val path = outputPath
        recording.set(false)
        runCatching { audioRecord?.stop() }
        recordThread?.join(3_000)
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordThread = null
        notifyPetMic(false)
        val file = path?.let(::File)
        if (file == null || !file.isFile || pcmBytes < MIN_PCM_BYTES) {
            file?.delete()
            outputPath = null
            return null
        }
        return path
    }

    fun cancel() {
        val path = outputPath
        recording.set(false)
        runCatching { audioRecord?.stop() }
        recordThread?.join(3_000)
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordThread = null
        notifyPetMic(false)
        path?.let { runCatching { File(it).delete() } }
        outputPath = null
        pcmBytes = 0
    }

    private fun notifyPetMic(enable: Boolean) {
        PetService.instance?.setMicrophoneCapture(enable)
    }

    companion object {
        const val AUDIO_DIR = "flash_audio"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val WAV_HEADER = 44
        private const val MIN_PCM_BYTES = 1600

        fun writeWavHeader(file: File, dataBytes: Int) {
            RandomAccessFile(file, "rw").use { raf ->
                val total = 36 + dataBytes
                raf.seek(0)
                raf.writeBytes("RIFF")
                writeInt(raf, total)
                raf.writeBytes("WAVE")
                raf.writeBytes("fmt ")
                writeInt(raf, 16)
                writeShort(raf, 1)
                writeShort(raf, 1)
                writeInt(raf, SAMPLE_RATE)
                writeInt(raf, SAMPLE_RATE * 2)
                writeShort(raf, 2)
                writeShort(raf, 16)
                raf.writeBytes("data")
                writeInt(raf, dataBytes)
            }
        }

        private fun writeInt(raf: RandomAccessFile, value: Int) {
            raf.write(value and 0xff)
            raf.write(value shr 8 and 0xff)
            raf.write(value shr 16 and 0xff)
            raf.write(value shr 24 and 0xff)
        }

        private fun writeShort(raf: RandomAccessFile, value: Int) {
            raf.write(value and 0xff)
            raf.write(value shr 8 and 0xff)
        }
    }
}

object FlashNotePlayer {
    enum class State { IDLE, PLAYING, PAUSED }

    private const val TAG = "FlashNote"
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    var playingId: Long? = null
        private set
    var state: State = State.IDLE
        private set
    var onState: ((id: Long, state: State) -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var audioContext: android.content.Context? = null

    fun toggle(context: android.content.Context, id: Long, path: String, onFailed: ((Boolean) -> Unit)? = null) {
        when {
            playingId == id && state == State.PLAYING -> pause()
            playingId == id && state == State.PAUSED -> resume()
            else -> play(context, path, id, onFailed)
        }
    }

    fun play(context: android.content.Context, path: String, id: Long, onFailed: ((Boolean) -> Unit)? = null) {
        if (context is FlashNotePlayActivity) {
            startTrack(context, path, id, onFailed)
            return
        }
        val intent = FlashNotePlayActivity.intent(context, path, id)
        val launched = launchPlayActivity(context, intent)
        if (!launched) {
            android.util.Log.e(TAG, "start play activity failed")
            onFailed?.invoke(true)
        }
    }

    fun startTrack(
        context: android.content.Context,
        path: String,
        id: Long,
        onFailed: ((Boolean) -> Unit)? = null
    ) {
        releaseTrack()
        val file = File(path)
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || !isWav(bytes)) {
            android.util.Log.e(TAG, "play skip, not wav path=$path exists=${file.isFile} size=${file.length()}")
            onFailed?.invoke(true)
            return
        }
        val pcm = bytes.copyOfRange(44, bytes.size)
        val minBuf = AudioTrack.getMinBufferSize(
            FlashNoteRecorder.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            android.util.Log.e(TAG, "AudioTrack minBuf=$minBuf")
            onFailed?.invoke(true)
            return
        }
        audioContext = context.applicationContext
        PetService.instance?.setMediaPlayback(true)
        requestFocus(context)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(FlashNoteRecorder.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val next = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull()
        if (next == null || next.state != AudioTrack.STATE_INITIALIZED) {
            android.util.Log.e(TAG, "AudioTrack init failed state=${next?.state}")
            runCatching { next?.release() }
            abandonFocus()
            PetService.instance?.setMediaPlayback(false)
            onFailed?.invoke(true)
            return
        }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val speaker = audio?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker != null) next.setPreferredDevice(speaker)
        next.setVolume(1f)
        track = next
        playingId = id
        running.set(true)
        paused.set(false)
        state = State.PLAYING
        runCatching { next.play() }
        android.util.Log.i(TAG, "play start id=$id bytes=${pcm.size} playState=${next.playState}")
        onState?.invoke(id, State.PLAYING)
        playThread = Thread({
            var offset = 0
            while (running.get() && offset < pcm.size) {
                while (paused.get() && running.get()) {
                    Thread.sleep(40)
                }
                if (!running.get()) break
                val chunk = min(2048, pcm.size - offset)
                val written = next.write(pcm, offset, chunk, AudioTrack.WRITE_BLOCKING)
                when {
                    written > 0 -> offset += written
                    written == 0 -> Thread.sleep(8)
                    else -> {
                        android.util.Log.e(TAG, "AudioTrack write=$written offset=$offset")
                        break
                    }
                }
            }
            android.util.Log.i(TAG, "play thread done offset=$offset total=${pcm.size} running=${running.get()}")
            val finished = running.get() && offset >= pcm.size
            if (finished) main.post { stop() }
        }, "flash-note-play").also { it.start() }
        onFailed?.invoke(false)
    }

    fun pause() {
        val id = playingId ?: return
        if (state != State.PLAYING) return
        paused.set(true)
        runCatching { track?.pause() }
        state = State.PAUSED
        onState?.invoke(id, State.PAUSED)
    }

    fun resume() {
        val id = playingId ?: return
        if (state != State.PAUSED) return
        paused.set(false)
        runCatching { track?.play() }
        state = State.PLAYING
        onState?.invoke(id, State.PLAYING)
    }

    fun stop() {
        val id = playingId
        releaseTrack()
        FlashNotePlayActivity.finishIfShowing()
        if (id != null) onState?.invoke(id, State.IDLE)
    }

    private fun releaseTrack() {
        running.set(false)
        paused.set(false)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        playThread = null
        playingId = null
        state = State.IDLE
        abandonFocus()
        audioContext = null
        PetService.instance?.setMediaPlayback(false)
    }

    private fun launchPlayActivity(context: android.content.Context, intent: android.content.Intent): Boolean {
        if (BlockerService.tryStartActivity(intent)) {
            android.util.Log.i(TAG, "play activity via accessibility")
            return true
        }
        val pet = PetService.instance
        if (pet != null) {
            val started = runCatching {
                pet.startActivity(
                    intent,
                    ActivityOptions.makeCustomAnimation(pet, 0, 0).toBundle()
                )
            }.isSuccess
            if (started) {
                android.util.Log.i(TAG, "play activity via pet service")
                return true
            }
        }
        return runCatching { context.startActivity(intent) }
            .onSuccess { android.util.Log.i(TAG, "play activity via overlay context") }
            .isSuccess
    }

    private fun requestFocus(context: android.content.Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        val audio = trackContext()?.getSystemService(AudioManager::class.java)
            ?: PetService.instance?.getSystemService(AudioManager::class.java)
        val req = focusRequest
        focusRequest = null
        if (audio == null) return
        if (Build.VERSION.SDK_INT >= 26 && req != null) {
            audio.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }

    private fun trackContext(): android.content.Context? = audioContext

    private fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
}
