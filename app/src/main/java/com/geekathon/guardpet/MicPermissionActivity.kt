package com.geekathon.guardpet

import android.Manifest
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
            FlashNoteHud.onMicGranted()
        } else {
            Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            FlashNoteHud.onMicGranted()
            finish()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
