package com.geekathon.guardpet

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.Executors

/**
 * 日程编辑 / AI / 导入对话框（View 对话框，主列表已 MD3 Compose）。
 */
class ScheduleEditor(
    private val activity: AppCompatActivity,
    private val selectedDate: () -> LocalDate,
    private val visibleMonth: () -> YearMonth,
    private val onFocusDate: (LocalDate) -> Unit,
    private val onChanged: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var destroyed = false
    var pendingCalendarImport = false

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    fun onResumeImport() {
        if (destroyed) return
        if (pendingCalendarImport && CalendarScheduleImporter.hasPermission(activity)) {
            pendingCalendarImport = false
            importCalendarRange { }
        }
    }

    fun showEditDialog(existing: DaySchedule?) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_schedule_edit, null)
        val title = view.findViewById<EditText>(R.id.scheduleEditTitle)
        val dateButton = view.findViewById<AppCompatButton>(R.id.scheduleEditDate)
        val startButton = view.findViewById<AppCompatButton>(R.id.scheduleEditStart)
        val endButton = view.findViewById<AppCompatButton>(R.id.scheduleEditEnd)
        val allow = view.findViewById<EditText>(R.id.scheduleEditAllow)
        val block = view.findViewById<EditText>(R.id.scheduleEditBlock)
        val colorsRow = view.findViewById<LinearLayout>(R.id.scheduleEditColors)
        var date = existing?.let { parseDate(it.date) } ?: selectedDate()
        var start = existing?.startMinutes ?: defaultStartMinutes()
        var end = existing?.endMinutes ?: (start + 60).coerceAtMost(24 * 60 - 1)
        var colorArgb = existing?.colorArgb?.takeIf { it != 0 }
            ?: DayScheduleColor.randomPending(0.4f, (existing?.title ?: date.toString()).hashCode())
        title.setText(existing?.title.orEmpty())
        allow.setText(existing?.allowPackages?.joinToString(", ").orEmpty())
        block.setText(existing?.blockPackages?.joinToString(", ").orEmpty())

        fun bindColorDots() {
            colorsRow.removeAllViews()
            val density = activity.resources.displayMetrics.density
            val size = (28 * density).toInt()
            val margin = (6 * density).toInt()
            val selectedIndex = DayScheduleColor.brightPalette.indexOf(colorArgb).takeIf { it >= 0 }
                ?: DayScheduleColor.brightPalette.indices.minByOrNull {
                    colorDistance(DayScheduleColor.argb(it), colorArgb)
                } ?: 0
            DayScheduleColor.brightPalette.forEachIndexed { index, paletteColor ->
                val isSelected = index == selectedIndex
                val dot = View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(paletteColor)
                        if (isSelected) setStroke((2.5f * density).toInt(), Color.WHITE)
                    }
                    setOnClickListener {
                        colorArgb = paletteColor
                        bindColorDots()
                    }
                }
                colorsRow.addView(
                    dot,
                    LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                )
            }
        }
        bindColorDots()

        fun updateLabels() {
            dateButton.text = activity.getString(R.string.schedule_date_value, date.toString())
            startButton.text = activity.getString(
                R.string.schedule_start_value,
                DayScheduleStore.minutesToHm(start)
            )
            endButton.text = activity.getString(
                R.string.schedule_end_value,
                DayScheduleStore.minutesToHm(end)
            )
        }
        updateLabels()
        dateButton.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.schedule_date)
                .setSelection(
                    date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                date = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                updateLabels()
            }
            picker.show(activity.supportFragmentManager, "schedule_edit_date")
        }
        startButton.setOnClickListener {
            TimePickerDialog(activity, { _, hour, minute ->
                start = hour * 60 + minute
                if (end <= start) end = (start + 60).coerceAtMost(24 * 60 - 1)
                updateLabels()
            }, start / 60, start % 60, true).show()
        }
        endButton.setOnClickListener {
            TimePickerDialog(activity, { _, hour, minute ->
                end = hour * 60 + minute
                updateLabels()
            }, end / 60, end % 60, true).show()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.schedule_new_title else R.string.schedule_edit)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_save, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.schedule_delete, null)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveEdited(dialog, existing, title, date, start, end, allow, block, colorArgb)
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    confirmDelete(existing)
                }
            }
        }
        dialog.show()
    }

    fun showAiDialog() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.schedule_ai_input_hint)
            minLines = 4
            maxLines = 8
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.schedule_ai_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_ai_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val instruction = input.text?.toString()?.trim().orEmpty()
                if (instruction.isBlank()) {
                    input.error = activity.getString(R.string.schedule_title_required)
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text =
                    activity.getString(R.string.schedule_ai_running)
                val month = visibleMonth()
                val anchor = selectedDate()
                io.execute {
                    val result = ScheduleLlmClient.applyInstruction(
                        instruction = instruction,
                        anchorDate = anchor,
                        rangeStart = month.minusMonths(1).atDay(1),
                        rangeEnd = month.plusMonths(1).atEndOfMonth()
                    )
                    mainHandler.post {
                        if (destroyed) return@post
                        dialog.dismiss()
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                        if (result.changed) {
                            result.focusDate?.let(onFocusDate)
                            onChanged()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    fun importCalendarRange(onBusy: (Boolean) -> Unit) {
        Toast.makeText(activity, R.string.schedule_importing, Toast.LENGTH_SHORT).show()
        onBusy(true)
        io.execute {
            val result = CalendarScheduleImporter.importUpcoming(activity)
            mainHandler.post {
                if (destroyed) return@post
                onBusy(false)
                val message = result.third ?: activity.getString(
                    R.string.schedule_import_range_result,
                    result.first,
                    result.second
                )
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                onChanged()
            }
        }
    }

    fun requestImportOrPermission(onBusy: (Boolean) -> Unit = {}) {
        if (CalendarScheduleImporter.hasPermission(activity)) {
            importCalendarRange(onBusy)
        } else {
            pendingCalendarImport = true
            activity.startActivity(
                android.content.Intent(activity, CalendarPermissionActivity::class.java)
                    .putExtra(CalendarPermissionActivity.EXTRA_RETURN_TO_SCHEDULE, true)
            )
        }
    }

    private fun confirmDelete(schedule: DaySchedule) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.schedule_delete)
            .setMessage(activity.getString(R.string.schedule_delete_confirm, schedule.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_delete) { _, _ ->
                DayScheduleStore.delete(schedule.id)
                Toast.makeText(activity, R.string.schedule_deleted, Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .show()
    }

    private fun saveEdited(
        dialog: AlertDialog,
        existing: DaySchedule?,
        titleInput: EditText,
        date: LocalDate,
        start: Int,
        end: Int,
        allowInput: EditText,
        blockInput: EditText,
        colorArgb: Int
    ) {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            titleInput.error = activity.getString(R.string.schedule_title_required)
            return
        }
        if (end <= start) {
            Toast.makeText(activity, R.string.schedule_time_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val next = (existing ?: DaySchedule(
            title = title,
            date = date.toString(),
            startMinutes = start,
            endMinutes = end,
            colorArgb = colorArgb
        )).copy(
            title = title,
            date = date.toString(),
            startMinutes = start,
            endMinutes = end,
            allowPackages = splitPackages(allowInput.text?.toString()),
            blockPackages = splitPackages(blockInput.text?.toString()),
            colorArgb = colorArgb,
            source = existing?.source ?: DayScheduleSource.MANUAL
        )
        if (existing != null && existing.isPolicyLocked() &&
            (existing.date != next.date || existing.startMinutes != next.startMinutes ||
                existing.endMinutes != next.endMinutes || existing.allowPackages != next.allowPackages ||
                existing.blockPackages != next.blockPackages)
        ) {
            Toast.makeText(activity, R.string.schedule_policy_locked, Toast.LENGTH_LONG).show()
            return
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        io.execute {
            val kind = when {
                existing == null -> "create"
                existing.startMinutes != next.startMinutes || existing.endMinutes != next.endMinutes ||
                    existing.date != next.date -> "time"
                existing.allowPackages != next.allowPackages ||
                    existing.blockPackages != next.blockPackages -> "policy"
                else -> "title"
            }
            val review = ScheduleLlmClient.reviewChange(
                next,
                kind,
                DayScheduleStore.forDate(date)
            )
            mainHandler.post {
                if (destroyed) return@post
                if (!review.accept) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(activity, review.reason, Toast.LENGTH_LONG).show()
                    return@post
                }
                val ok = if (existing == null) {
                    DayScheduleStore.insert(next) > 0L
                } else {
                    DayScheduleStore.update(next)
                }
                if (!ok) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(activity, R.string.schedule_policy_locked, Toast.LENGTH_LONG).show()
                    return@post
                }
                dialog.dismiss()
                Toast.makeText(activity, R.string.schedule_saved, Toast.LENGTH_SHORT).show()
                onFocusDate(date)
                onChanged()
            }
        }
    }

    private fun splitPackages(raw: String?): List<String> =
        raw.orEmpty().split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun parseDate(raw: String): LocalDate =
        runCatching { LocalDate.parse(raw) }.getOrDefault(LocalDate.now())

    private fun defaultStartMinutes(): Int {
        val now = LocalTime.now()
        return ((now.hour * 60 + now.minute) / 30 * 30).coerceIn(0, 23 * 60)
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val ar = Color.red(a) - Color.red(b)
        val ag = Color.green(a) - Color.green(b)
        val ab = Color.blue(a) - Color.blue(b)
        return ar * ar + ag * ag + ab * ab
    }
}
