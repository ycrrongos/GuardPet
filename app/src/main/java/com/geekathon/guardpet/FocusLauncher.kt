package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.net.Uri

object FocusLauncher {
    const val SOURCE_URL = "https://github.com/aload0/Reef"

    fun openTimer(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NAVIGATE_TIMER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NAVIGATE_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun openAbout(context: Context) {
        context.startActivity(
            Intent(context, GuardAboutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openFriends(context: Context) {
        context.startActivity(
            Intent(context, FriendActivity::class.java)
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
