package com.geekathon.guardpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.ui.ReefTheme
import java.util.concurrent.Executors

/**
 * 完成日程：填写完成程度 + 经验；可按住语音由 AI 填写。
 */
class ScheduleCompleteActivity : AppCompatActivity() {
    private var recorder: FlashNoteRecorder? = null
    private val io = Executors.newSingleThreadExecutor()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        val schedule = DayScheduleStore.byId(id)
        if (schedule == null) {
            Toast.makeText(this, R.string.schedule_complete_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (schedule.status == DayScheduleStatus.DONE) {
            Toast.makeText(this, R.string.schedule_complete_already, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!schedule.canCompleteNow()) {
            Toast.makeText(
                this,
                getString(
                    R.string.schedule_complete_too_early,
                    DayScheduleStore.minutesToHm(schedule.midpointMinutes())
                ),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // 清除对应结束通知
        runCatching {
            NotificationManagerCompat.from(this)
                .cancel((71000 + 300000 + (id % 100000)).toInt())
        }

        setContent {
            ReefTheme {
                var degree by remember {
                    mutableFloatStateOf((schedule.completionDegree ?: 80).toFloat())
                }
                var experience by remember {
                    mutableStateOf(schedule.experienceNote.orEmpty())
                }
                var recording by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        LargeTopAppBar(
                            title = {
                                Column {
                                    Text(
                                        stringResource(R.string.schedule_complete_title),
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-1).sp
                                        )
                                    )
                                    Text(
                                        schedule.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Rounded.Close, contentDescription = null)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.schedule_time_format,
                                DayScheduleStore.minutesToHm(schedule.startMinutes),
                                DayScheduleStore.minutesToHm(schedule.endMinutes)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.schedule_complete_degree_label, degree.toInt()),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = degree,
                            onValueChange = { degree = it },
                            valueRange = 0f..100f,
                            steps = 19
                        )
                        OutlinedTextField(
                            value = experience,
                            onValueChange = { experience = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            label = { Text(stringResource(R.string.schedule_complete_experience)) },
                            placeholder = {
                                Text(stringResource(R.string.schedule_complete_experience_hint))
                            }
                        )
                        FilledTonalButton(
                            onClick = {
                                if (busy) return@FilledTonalButton
                                if (!recording) {
                                    if (ContextCompat.checkSelfPermission(
                                            this@ScheduleCompleteActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        startActivity(
                                            android.content.Intent(
                                                this@ScheduleCompleteActivity,
                                                MicPermissionActivity::class.java
                                            )
                                        )
                                        return@FilledTonalButton
                                    }
                                    if (!SenseVoiceAsr.hasModel(this@ScheduleCompleteActivity)) {
                                        SenseVoiceModelStore.openInstallHint(this@ScheduleCompleteActivity)
                                        return@FilledTonalButton
                                    }
                                    val rec = FlashNoteRecorder(this@ScheduleCompleteActivity)
                                    if (!rec.start()) {
                                        Toast.makeText(
                                            this@ScheduleCompleteActivity,
                                            R.string.flash_note_record_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@FilledTonalButton
                                    }
                                    recorder = rec
                                    recording = true
                                } else {
                                    recording = false
                                    busy = true
                                    val path = recorder?.stop()
                                    recorder = null
                                    if (path.isNullOrBlank()) {
                                        busy = false
                                        Toast.makeText(
                                            this@ScheduleCompleteActivity,
                                            R.string.schedule_voice_empty,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@FilledTonalButton
                                    }
                                    SenseVoiceAsr.transcribe(
                                        this@ScheduleCompleteActivity,
                                        path
                                    ) { spoken ->
                                        io.execute {
                                            val draft = ScheduleLlmClient.parseCompletionFromVoice(
                                                spoken.orEmpty(),
                                                schedule.title
                                            )
                                            runOnUiThread {
                                                busy = false
                                                if (spoken.isNullOrBlank()) {
                                                    Toast.makeText(
                                                        this@ScheduleCompleteActivity,
                                                        R.string.schedule_voice_empty,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    return@runOnUiThread
                                                }
                                                degree = draft.degree.toFloat()
                                                experience = draft.experience.ifBlank { spoken }
                                                Toast.makeText(
                                                    this@ScheduleCompleteActivity,
                                                    R.string.schedule_complete_voice_applied,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Mic, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (recording) {
                                        stringResource(R.string.schedule_complete_voice_stop)
                                    } else {
                                        stringResource(R.string.schedule_complete_voice_start)
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val (ok, msg) = DayScheduleStore.complete(
                                    id,
                                    degree.toInt(),
                                    experience
                                )
                                Toast.makeText(this@ScheduleCompleteActivity, msg, Toast.LENGTH_SHORT)
                                    .show()
                                if (ok) finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !recording
                        ) {
                            Text(stringResource(R.string.schedule_complete_submit))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
