package com.geekathon.guardpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CalendarPermissionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_RETURN_TO_SCHEDULE = "return_to_schedule"
    }

    private var returnToSchedule = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!returnToSchedule) {
                ScheduleHud.onCalendarGranted()
            }
        } else {
            Toast.makeText(this, R.string.schedule_calendar_permission, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnToSchedule = intent.getBooleanExtra(EXTRA_RETURN_TO_SCHEDULE, false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!returnToSchedule) {
                ScheduleHud.onCalendarGranted()
            }
            finish()
        } else {
            launcher.launch(Manifest.permission.READ_CALENDAR)
        }
    }
}
