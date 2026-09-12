package com.geekathon.guardpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CalendarPermissionActivity : AppCompatActivity() {
    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ScheduleHud.onCalendarGranted()
        } else {
            Toast.makeText(this, R.string.schedule_calendar_permission, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ScheduleHud.onCalendarGranted()
            finish()
        } else {
            launcher.launch(Manifest.permission.READ_CALENDAR)
        }
    }
}
