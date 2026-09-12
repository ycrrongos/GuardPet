package com.geekathon.guardpet.sensevoice

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Tiny launcher so the model pack is visible after sideload. */
class InstalledActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.installed_message)
                textSize = 18f
                setPadding(48, 96, 48, 48)
            }
        )
    }
}
