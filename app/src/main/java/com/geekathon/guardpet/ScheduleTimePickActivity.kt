package com.geekathon.guardpet

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 从悬浮窗无法安全弹出 TimePickerDialog（无 Activity token 会崩）。
 * 用独立透明 Activity 串行选开始/结束时间；打开时隐藏左右悬浮窗以免挡住。
 */
class ScheduleTimePickActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayLayerCoordinator.hideForTimePicker()
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val startDefault = intent.getIntExtra(EXTRA_START, 9 * 60)
        if (index < 0) {
            finish()
            return
        }
        val startH = startDefault / 60
        val startM = startDefault % 60
        TimePickerDialog(
            this,
            { _, h, m ->
                val start = h * 60 + m
                val endDefault = (start + 60).coerceAtMost(24 * 60 - 1)
                TimePickerDialog(
                    this,
                    { _, eh, em ->
                        val end = (eh * 60 + em).coerceAtLeast(start + 15)
                        pendingCallback?.invoke(index, start, end)
                        pendingCallback = null
                        finish()
                    },
                    endDefault / 60,
                    endDefault % 60,
                    true
                ).apply {
                    setOnCancelListener { finish() }
                    show()
                }
            },
            startH,
            startM,
            true
        ).apply {
            setOnCancelListener { finish() }
            show()
        }
    }

    override fun onDestroy() {
        OverlayLayerCoordinator.restoreAfterTimePicker()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_START = "start_minutes"

        @Volatile
        private var pendingCallback: ((Int, Int, Int) -> Unit)? = null

        fun request(
            context: Context,
            index: Int,
            startMinutes: Int?,
            onPicked: (index: Int, start: Int, end: Int) -> Unit
        ) {
            pendingCallback = onPicked
            context.startActivity(
                Intent(context, ScheduleTimePickActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_START, startMinutes ?: (9 * 60))
            )
        }
    }
}
