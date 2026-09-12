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

/** 工作时段遇到不确定 Active 时，悬浮询问用户是否禁用。 */
class HabitConfirmOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private var attached = false
    private var root = LayoutInflater.from(service).inflate(R.layout.overlay_habit_confirm, null)
    private val title = root.findViewById<TextView>(R.id.habitConfirmTitle)
    private val message = root.findViewById<TextView>(R.id.habitConfirmMessage)
    private val blockBtn = root.findViewById<AppCompatButton>(R.id.habitConfirmBlock)
    private val allowBtn = root.findViewById<AppCompatButton>(R.id.habitConfirmAllow)

    private var packageName: String = ""
    private var activeKey: String = ""
    private var label: String = ""

    fun show(packageName: String, activeKey: String, label: String) {
        if (!Settings.canDrawOverlays(service)) return
        this.packageName = packageName
        this.activeKey = activeKey
        this.label = label
        title.text = service.getString(R.string.habit_confirm_title)
        message.text = service.getString(R.string.habit_confirm_message, label.ifBlank { activeKey })
        blockBtn.setOnClickListener {
            AppActiveCatalog.confirm(packageName, activeKey, blockInWork = true)
            HabitRewardTracker.onMarkAssist(service)
            close()
        }
        allowBtn.setOnClickListener {
            AppActiveCatalog.confirm(packageName, activeKey, blockInWork = false, kind = ActiveKind.WORK)
            close()
        }
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (56 * service.resources.displayMetrics.density).toInt()
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
        fun request(context: Context, packageName: String, activeKey: String, label: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PetService::class.java)
                        .setAction(PetService.ACTION_HABIT_CONFIRM)
                        .putExtra(PetService.EXTRA_CONFIRM_PKG, packageName)
                        .putExtra(PetService.EXTRA_CONFIRM_ACTIVE, activeKey)
                        .putExtra(PetService.EXTRA_CONFIRM_LABEL, label)
                )
            }
        }
    }
}
