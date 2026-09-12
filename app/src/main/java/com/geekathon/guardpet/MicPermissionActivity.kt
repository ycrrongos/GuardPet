package com.geekathon.guardpet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MicPermissionActivity : AppCompatActivity() {
    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            notifyGranted()
        } else {
            Toast.makeText(this, deniedMessage(), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyGranted()
            finish()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun notifyGranted() {
        when (intent.getStringExtra(EXTRA_REQUESTER)) {
            REQUESTER_SCHEDULE -> ScheduleHud.onMicGranted()
            else -> FlashNoteHud.onMicGranted()
        }
    }

    private fun deniedMessage(): Int = when (intent.getStringExtra(EXTRA_REQUESTER)) {
        REQUESTER_SCHEDULE -> R.string.schedule_mic_permission_required
        else -> R.string.mic_permission_required
    }

    companion object {
        private const val EXTRA_REQUESTER = "mic_requester"
        private const val REQUESTER_SCHEDULE = "schedule"

        fun scheduleIntent(context: Context): Intent =
            Intent(context, MicPermissionActivity::class.java)
                .putExtra(EXTRA_REQUESTER, REQUESTER_SCHEDULE)
    }
}
