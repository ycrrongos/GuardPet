package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import dev.pranav.reef.accessibility.BlockerService

/** 遮挡提示层（原视频「仅搜索」页；内容判断暂停后仍可供其它限制提示复用）。 */
class HabitRestrictOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private var attached = false
    private var root = LayoutInflater.from(service).inflate(R.layout.overlay_habit_restrict, null)
    private val title = root.findViewById<TextView>(R.id.habitRestrictTitle)
    private val message = root.findViewById<TextView>(R.id.habitRestrictMessage)
    private val backBtn = root.findViewById<AppCompatButton>(R.id.habitRestrictBack)

    fun show(messageText: String) {
        if (!Settings.canDrawOverlays(service)) return
        title.text = service.getString(R.string.habit_restrict_title)
        message.text = messageText
        backBtn.setOnClickListener {
            BlockerService.performGlobalBack()
            close()
        }
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching {
            windowManager.addView(root, params)
            attached = true
        }
    }

    fun close() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    companion object {
        fun request(context: Context, message: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PetService::class.java)
                        .setAction(PetService.ACTION_HABIT_RESTRICT)
                        .putExtra(PetService.EXTRA_RESTRICT_MSG, message)
                )
            }
        }

        fun dismiss(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PetService::class.java)
                        .setAction(PetService.ACTION_HABIT_RESTRICT_HIDE)
                )
            }
        }
    }
}
