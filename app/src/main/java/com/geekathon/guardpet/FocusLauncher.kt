package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.pranav.reef.AboutActivity
import dev.pranav.reef.MainActivity as FocusMainActivity

object FocusLauncher {
    const val SOURCE_URL = "https://github.com/aload0/Reef"

    fun openTimer(context: Context) {
        context.startActivity(
            Intent(context, FocusMainActivity::class.java)
                .putExtra("navigate_to_timer", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(context, FocusMainActivity::class.java)
                .putExtra("navigate_to_settings", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAbout(context: Context) {
        context.startActivity(
            Intent(context, AboutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSource(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
