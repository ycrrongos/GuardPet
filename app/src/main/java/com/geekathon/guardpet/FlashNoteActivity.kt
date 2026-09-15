package com.geekathon.guardpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.geekathon.guardpet.databinding.ActivityFlashNoteBinding
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class FlashNoteActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFlashNoteBinding
    private var scheduleDate: LocalDate = LocalDate.now()
    private var recognizer: SpeechRecognizer? = null
    private var source: String = "typed"
    private val recorder by lazy { FlashNoteRecorder(this) }
    private var asrBusy = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoice() else {
            Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlashNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        source = intent.getStringExtra(EXTRA_SOURCE) ?: "typed"
        intent.getStringExtra(EXTRA_PREFILL)?.takeIf { it.isNotBlank() }?.let {
            binding.noteInput.setText(it)
        }
        updateScheduleDateLabel()
        binding.categoryGroup.setOnCheckedStateChangeListener { _, _ ->
            val schedule = binding.categorySchedule.isChecked
            binding.scheduleDateRow.visibility =
                if (schedule) android.view.View.VISIBLE else android.view.View.GONE
            binding.saveButton.setText(if (schedule) R.string.save_schedule else R.string.save_note)
        }
        binding.scheduleDateButton.setOnClickListener { pickDate() }
        binding.voiceButton.setOnClickListener { requestVoice() }
        binding.saveButton.setOnClickListener { save() }
        binding.closeButton.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun selectedCategory(): FlashNoteCategory? = when (binding.categoryGroup.checkedChipId) {
        R.id.categorySchedule -> FlashNoteCategory.SCHEDULE
        R.id.categoryIdea -> FlashNoteCategory.IDEA
        R.id.categoryDiary -> FlashNoteCategory.DIARY
        R.id.categoryTodo -> FlashNoteCategory.TODO
        R.id.categoryOther -> FlashNoteCategory.OTHER
        else -> null
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.schedule_date)
            .setSelection(
                scheduleDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            scheduleDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            updateScheduleDateLabel()
        }
        picker.show(supportFragmentManager, "schedule_date")
    }

    private fun updateScheduleDateLabel() {
        binding.scheduleDateButton.text = getString(R.string.schedule_date_value, scheduleDate.toString())
    }

    private fun requestVoice() {
        if (asrBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startVoice()
        }
    }

    private fun startVoice() {
        if (recorder.isRecording) {
            val path = recorder.stop()
            updateVoiceButton()
            if (!path.isNullOrBlank() && SenseVoiceAsr.hasModel(this)) {
                asrBusy = true
                updateVoiceButton()
                SenseVoiceAsr.transcribe(this, path) { text ->
                    asrBusy = false
                    updateVoiceButton()
                    if (!text.isNullOrBlank()) {
                        mergeText(text)
                        source = "voice"
                    } else {
                        Toast.makeText(this, R.string.voice_asr_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        if (SenseVoiceAsr.hasModel(this)) {
            recognizer?.destroy()
            recognizer = null
            if (recorder.start()) {
                source = "voice"
                updateVoiceButton()
            } else {
                Toast.makeText(this, R.string.flash_note_record_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        Toast.makeText(this, R.string.voice_model_missing, Toast.LENGTH_SHORT).show()
        SenseVoiceModelStore.openInstallHint(this)
        startSystemVoice()
    }

    private fun startSystemVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        source = "voice"
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateVoiceButton()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    updateVoiceButton()
                }
                override fun onError(error: Int) {
                    recognizer = null
                    updateVoiceButton()
                    Toast.makeText(this@FlashNoteActivity, R.string.voice_failed, Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) mergeText(text)
                    recognizer = null
                    updateVoiceButton()
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        }
        updateVoiceButton()
    }

    private fun mergeText(spoken: String) {
        val current = binding.noteInput.text?.toString().orEmpty()
        val merged = listOf(current, spoken).filter { it.isNotBlank() }.joinToString()
        binding.noteInput.setText(merged)
        binding.noteInput.setSelection(merged.length)
    }

    private fun updateVoiceButton() {
        binding.voiceButton.setText(
            when {
                asrBusy -> R.string.voice_transcribing
                recorder.isRecording -> R.string.flash_note_stop_record
                recognizer != null -> R.string.voice_listening
                else -> R.string.voice_input
            }
        )
    }

    private fun save() {
        if (recorder.isRecording) {
            recorder.stop()
            updateVoiceButton()
        }
        val text = binding.noteInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.flash_note_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val category = selectedCategory()
        if (category == null) {
            Toast.makeText(this, R.string.flash_note_pick_category, Toast.LENGTH_SHORT).show()
            return
        }
        // 日程：只进 DayScheduleStore，不进闪记列表
        if (category == FlashNoteCategory.SCHEDULE) {
            val noteText = text
            val date = scheduleDate
            Toast.makeText(this, R.string.schedule_parsing, Toast.LENGTH_SHORT).show()
            binding.saveButton.isEnabled = false
            Thread {
                val (drafts, warn) = ScheduleLlmClient.parseFromFlashNote(noteText, date)
                Handler(Looper.getMainLooper()).post {
                    binding.saveButton.isEnabled = true
                    if (warn != null) {
                        Toast.makeText(this, warn, Toast.LENGTH_SHORT).show()
                    }
                    if (drafts.isEmpty()) {
                        Toast.makeText(this, R.string.schedule_parse_empty, Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    if (drafts.any { it.needsTime || it.startMinutes == null }) {
                        ScheduleHud.showFillTimes(this, drafts, noteText, date)
                        finish()
                    } else {
                        Thread {
                            val (n, err) = ScheduleLlmClient.commitDrafts(drafts, date, noteText)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    this,
                                    err ?: getString(R.string.schedule_created_count, n),
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        }.start()
                    }
                }
            }.start()
            return
        }
        binding.saveButton.isEnabled = false
        Toast.makeText(this, R.string.flash_note_organizing, Toast.LENGTH_SHORT).show()
        val fallbackColor = if (category == FlashNoteCategory.TODO) 2 else 0
        Thread {
            val organized = FlashNoteLlmClient.organize(text, category, scheduleDate)
            Handler(Looper.getMainLooper()).post {
                binding.saveButton.isEnabled = true
                FlashNoteStore.insert(
                    FlashNote(
                        text = organized.text,
                        category = category,
                        source = source,
                        scheduleDate = null,
                        color = if (category == FlashNoteCategory.TODO) {
                            organized.color.coerceIn(0, 5).takeIf { organized.color in 0..5 }
                                ?: fallbackColor
                        } else {
                            0
                        }
                    )
                )
                Toast.makeText(
                    this,
                    organized.warning ?: getString(R.string.flash_note_organized_saved),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_PREFILL = "prefill"
        const val EXTRA_SOURCE = "source"
    }
}
