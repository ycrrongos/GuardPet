package com.geekathon.guardpet

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate

/** 悬浮窗内选日期（避免 BadToken）；与 [ScheduleTimePickActivity] 同理。 */
class ScheduleDatePickActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayLayerCoordinator.hideForTimePicker()
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val default = intent.getStringExtra(EXTRA_DATE)?.let {
            ScheduleDateParse.parse(it) ?: LocalDate.now()
        } ?: LocalDate.now()
        if (index < 0) {
            finish()
            return
        }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                pendingCallback?.invoke(index, LocalDate.of(y, m + 1, d))
                pendingCallback = null
                finish()
            },
            default.year,
            default.monthValue - 1,
            default.dayOfMonth
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
        const val EXTRA_DATE = "date"

        @Volatile
        private var pendingCallback: ((Int, LocalDate) -> Unit)? = null

        fun request(
            context: Context,
            index: Int,
            date: LocalDate,
            onPicked: (index: Int, date: LocalDate) -> Unit
        ) {
            pendingCallback = onPicked
            context.startActivity(
                Intent(context, ScheduleDatePickActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_DATE, date.toString())
            )
        }
    }
}
