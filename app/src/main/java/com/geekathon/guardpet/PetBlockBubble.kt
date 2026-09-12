package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * 拦截时用桌宠气泡代替系统通知；文案偏软萌。
 */
object PetBlockBubble {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null
    private var lastAtMs = 0L

    fun handle(
        context: Context,
        kind: String,
        packageName: String,
        reason: String?
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val app = context.applicationContext
        val appLabel = appLabel(app, packageName)
        val message = cuteMessage(app, kind, appLabel, reason)
        val debounceKey = "$kind:$packageName:${message.take(12)}"
        val now = SystemClock.elapsedRealtime()
        if (debounceKey == lastKey && now - lastAtMs < 1_800L) return true
        lastKey = debounceKey
        lastAtMs = now
        mainHandler.post {
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, PetService::class.java)
                        .setAction(PetService.ACTION_SPEECH_BUBBLE)
                        .putExtra(PetService.EXTRA_BUBBLE_TEXT, message)
                )
            }
        }
        return true
    }

    private fun appLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.').ifBlank { "这个应用" }
        }
    }

    private fun cuteMessage(
        context: Context,
        kind: String,
        appLabel: String,
        reason: String?
    ): String {
        val shortName = appLabel.take(10)
        val r = reason.orEmpty()
        val arrayId = when {
            kind == "focus" -> R.array.pet_bubble_focus
            r.contains("夜间") || r.contains("睡觉") || r.contains("催睡") ||
                r.contains("sleep", ignoreCase = true) -> R.array.pet_bubble_sleep
            r.contains("视频") -> R.array.pet_bubble_video
            r.contains("娱乐") -> R.array.pet_bubble_entertainment
            "「" in r && "」" in r -> R.array.pet_bubble_surface
            else -> R.array.pet_bubble_generic
        }
        val options = context.resources.getStringArray(arrayId)
        val template = options[Random.nextInt(options.size)]
        val surface = Regex("「([^」]{1,12})」").find(r)?.groupValues?.getOrNull(1)
        return template
            .replace("%app%", shortName)
            .replace("%surface%", surface ?: "那边")
    }
}
