package com.geekathon.guardpet

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.datepicker.MaterialDatePicker
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.kizitonwose.calendar.view.CalendarView
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 多日日程工作台。
 *
 * 月历控件来自 MIT 项目 kizitonwose/Calendar；日程数据、AI 和守护规则仍由守伴自己管理。
 */
class ScheduleActivity : AppCompatActivity() {
    private lateinit var calendar: CalendarView
    private lateinit var monthTitle: TextView
    private lateinit var selectedDateLabel: TextView
    private lateinit var selectedCountLabel: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var aiButton: AppCompatButton
    private lateinit var guardStatus: TextView
    private lateinit var content: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var selectedDate = LocalDate.now()
    private var visibleMonth = YearMonth.now()
    private var pendingCalendarImport = false
    private var stopScheduleObserve: (() -> Unit)? = null

    private val dayCounts = linkedMapOf<LocalDate, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        content = findViewById(R.id.scheduleContent)
        applyInsets()
        bindViews()
        setupCalendar()
        bindActions()
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        stopScheduleObserve?.invoke()
        stopScheduleObserve = DayScheduleStore.observe {
            mainHandler.post { refreshAll() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingCalendarImport && CalendarScheduleImporter.hasPermission(this)) {
            pendingCalendarImport = false
            importCalendarRange()
        }
        if (::list.isInitialized) refreshAll()
    }

    override fun onStop() {
        stopScheduleObserve?.invoke()
        stopScheduleObserve = null
        super.onStop()
    }

    override fun onDestroy() {
        stopScheduleObserve?.invoke()
        stopScheduleObserve = null
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scheduleScroll)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.scheduleScroll))
    }

    private fun bindViews() {
        calendar = findViewById(R.id.scheduleCalendar)
        monthTitle = findViewById(R.id.scheduleMonthTitle)
        selectedDateLabel = findViewById(R.id.scheduleSelectedDate)
        selectedCountLabel = findViewById(R.id.scheduleSelectedCount)
        list = findViewById(R.id.scheduleList)
        empty = findViewById(R.id.scheduleEmpty)
        aiButton = findViewById(R.id.scheduleAiButton)
        guardStatus = findViewById(R.id.scheduleAiHint)

        val legend = findViewById<LinearLayout>(R.id.scheduleWeekLegend)
        val weekDays = daysOfWeek()
        for (index in 0 until legend.childCount) {
            (legend.getChildAt(index) as? TextView)?.text =
                weekDays[index].getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    private fun setupCalendar() {
        val today = LocalDate.now()
        val startMonth = YearMonth.of(today.year - 3, 1)
        val endMonth = YearMonth.of(today.year + 3, 12)
        calendar.dayBinder = object : MonthDayBinder<ScheduleDayContainer> {
            override fun create(view: View): ScheduleDayContainer = ScheduleDayContainer(view)

            override fun bind(container: ScheduleDayContainer, data: CalendarDay) {
                container.day = data
                val date = data.date
                val inMonth = data.position == DayPosition.MonthDate
                container.label.text = date.dayOfMonth.toString()
                container.label.visibility = if (inMonth) View.VISIBLE else View.INVISIBLE
                container.dot.visibility =
                    if (inMonth && (dayCounts[date] ?: 0) > 0) View.VISIBLE else View.GONE

                if (!inMonth) {
                    container.label.background = null
                    return
                }
                when {
                    date == selectedDate -> {
                        container.label.setTextColor(Color.WHITE)
                        container.label.background = rounded(brandColor(), 18f)
                    }
                    date == today -> {
                        container.label.setTextColor(brandColor())
                        container.label.background = rounded(0x1A417C69, 18f)
                    }
                    else -> {
                        container.label.setTextColor(textColor())
                        container.label.background = null
                    }
                }
                container.dot.background = rounded(
                    if (date == selectedDate) Color.WHITE else brandColor(),
                    8f
                )
            }
        }
        calendar.setup(startMonth, endMonth, firstDayOfWeekFromLocale())
        calendar.scrollToMonth(visibleMonth)
        calendar.monthScrollListener = { month ->
            visibleMonth = month.yearMonth
            updateMonthTitle()
        }
    }

    private fun bindActions() {
        findViewById<AppCompatImageButton>(R.id.scheduleCloseButton).setOnClickListener { finish() }
        findViewById<AppCompatImageButton>(R.id.schedulePreviousMonth).setOnClickListener {
            val next = visibleMonth.minusMonths(1)
            if (next >= YearMonth.of(LocalDate.now().year - 3, 1)) {
                visibleMonth = next
                calendar.smoothScrollToMonth(next)
                updateMonthTitle()
            }
        }
        findViewById<AppCompatImageButton>(R.id.scheduleNextMonth).setOnClickListener {
            val next = visibleMonth.plusMonths(1)
            if (next <= YearMonth.of(LocalDate.now().year + 3, 12)) {
                visibleMonth = next
                calendar.smoothScrollToMonth(next)
                updateMonthTitle()
            }
        }
        findViewById<AppCompatButton>(R.id.scheduleTodayButton).setOnClickListener {
            selectedDate = LocalDate.now()
            visibleMonth = YearMonth.now()
            calendar.smoothScrollToMonth(visibleMonth)
            calendar.notifyCalendarChanged()
            refreshAll()
        }
        findViewById<AppCompatButton>(R.id.scheduleAddButton).setOnClickListener {
            showEditDialog(null)
        }
        aiButton.setOnClickListener { showAiDialog() }
        findViewById<AppCompatButton>(R.id.scheduleImportButton).setOnClickListener {
            if (CalendarScheduleImporter.hasPermission(this)) {
                importCalendarRange()
            } else {
                pendingCalendarImport = true
                startActivity(
                    android.content.Intent(this, CalendarPermissionActivity::class.java)
                        .putExtra(CalendarPermissionActivity.EXTRA_RETURN_TO_SCHEDULE, true)
                )
            }
        }
        findViewById<AppCompatButton>(R.id.scheduleHabitButton).setOnClickListener {
            startActivity(android.content.Intent(this, HabitGuardianActivity::class.java))
        }
    }

    private fun refreshAll() {
        if (!::calendar.isInitialized) return
        val rangeStart = visibleMonth.minusMonths(1).atDay(1)
        val rangeEnd = visibleMonth.plusMonths(1).atEndOfMonth()
        dayCounts.clear()
        dayCounts.putAll(DayScheduleStore.countsBetween(rangeStart, rangeEnd))
        calendar.notifyCalendarChanged()
        updateMonthTitle()
        renderSelectedDate()
        renderGuardStatus()
    }

    private fun updateMonthTitle() {
        monthTitle.text = getString(
            R.string.schedule_month_format,
            visibleMonth.year,
            visibleMonth.monthValue
        )
    }

    private fun renderSelectedDate() {
        val schedules = DayScheduleStore.forDate(selectedDate)
        selectedDateLabel.text = getString(
            R.string.schedule_selected_date_format,
            selectedDate.monthValue,
            selectedDate.dayOfMonth,
            selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        )
        selectedCountLabel.text = getString(R.string.schedule_count_format, schedules.size)
        list.removeAllViews()
        empty.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        schedules.forEach { schedule ->
            val row = inflater.inflate(R.layout.item_schedule_row, list, false)
            bindScheduleRow(row, schedule)
            list.addView(row)
        }
    }

    private fun bindScheduleRow(row: View, schedule: DaySchedule) {
        val color = schedule.displayColor()
        val colorBar = row.findViewById<View>(R.id.scheduleRowColor)
        colorBar.background = rounded(color, 5f)
        colorBar.setOnClickListener {
            if (schedule.status == DayScheduleStatus.DONE) return@setOnClickListener
            val next = schedule.copy(colorArgb = DayScheduleColor.nextColor(schedule.colorArgb))
            if (DayScheduleStore.update(next)) refreshAll()
        }
        row.findViewById<TextView>(R.id.scheduleRowTime).text = getString(
            R.string.schedule_time_format,
            DayScheduleStore.minutesToHm(schedule.startMinutes),
            DayScheduleStore.minutesToHm(schedule.endMinutes)
        )
        val title = row.findViewById<TextView>(R.id.scheduleRowTitle)
        title.text = if (schedule.status == DayScheduleStatus.DONE) {
            "✓ ${schedule.title}"
        } else {
            schedule.title
        }
        title.alpha = if (schedule.status == DayScheduleStatus.DONE) 0.62f else 1f
        row.findViewById<TextView>(R.id.scheduleRowPolicy).text = buildPolicySummary(schedule)
        val done = row.findViewById<AppCompatImageButton>(R.id.scheduleRowDone)
        done.visibility = if (schedule.status == DayScheduleStatus.PENDING) View.VISIBLE else View.INVISIBLE
        done.setOnClickListener {
            val result = DayScheduleStore.markDone(schedule.id)
            Toast.makeText(this, result.second, Toast.LENGTH_SHORT).show()
            refreshAll()
        }
        row.findViewById<AppCompatImageButton>(R.id.scheduleRowEdit).setOnClickListener {
            showEditDialog(schedule)
        }
        row.setOnClickListener { showEditDialog(schedule) }
    }

    private fun buildPolicySummary(schedule: DaySchedule): String {
        val allow = schedule.allowPackages.size
        val block = schedule.blockPackages.size
        val lock = if (schedule.isPolicyLocked()) " · ${getString(R.string.schedule_locked_short)}" else ""
        return getString(R.string.schedule_policy_counts, allow, block) + lock
    }

    private fun renderGuardStatus() {
        val active = runCatching { DayScheduleStore.activeNow() }.getOrNull()
        guardStatus.text = if (active != null &&
            (active.allowPackages.isNotEmpty() || active.blockPackages.isNotEmpty())
        ) {
            getString(R.string.schedule_active_guard, active.title)
        } else {
            getString(R.string.schedule_ai_hint)
        }
    }

    private fun importCalendarRange() {
        Toast.makeText(this, R.string.schedule_importing, Toast.LENGTH_SHORT).show()
        findViewById<AppCompatButton>(R.id.scheduleImportButton).isEnabled = false
        io.execute {
            val result = CalendarScheduleImporter.importUpcoming(this@ScheduleActivity)
            mainHandler.post {
                findViewById<AppCompatButton>(R.id.scheduleImportButton).isEnabled = true
                val message = result.third ?: getString(
                    R.string.schedule_import_range_result,
                    result.first,
                    result.second
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshAll()
            }
        }
    }

    private fun showEditDialog(existing: DaySchedule?) {
        val view = layoutInflater.inflate(R.layout.dialog_schedule_edit, null)
        val title = view.findViewById<EditText>(R.id.scheduleEditTitle)
        val dateButton = view.findViewById<AppCompatButton>(R.id.scheduleEditDate)
        val startButton = view.findViewById<AppCompatButton>(R.id.scheduleEditStart)
        val endButton = view.findViewById<AppCompatButton>(R.id.scheduleEditEnd)
        val allow = view.findViewById<EditText>(R.id.scheduleEditAllow)
        val block = view.findViewById<EditText>(R.id.scheduleEditBlock)
        val colorsRow = view.findViewById<LinearLayout>(R.id.scheduleEditColors)
        var date = existing?.let { parseDate(it.date) } ?: selectedDate
        var start = existing?.startMinutes ?: defaultStartMinutes()
        var end = existing?.endMinutes ?: (start + 60).coerceAtMost(24 * 60 - 1)
        var colorArgb = existing?.colorArgb?.takeIf { it != 0 }
            ?: DayScheduleColor.randomPending(0.4f, (existing?.title ?: date.toString()).hashCode())
        title.setText(existing?.title.orEmpty())
        allow.setText(existing?.allowPackages?.joinToString(", ").orEmpty())
        block.setText(existing?.blockPackages?.joinToString(", ").orEmpty())

        fun bindColorDots() {
            colorsRow.removeAllViews()
            val density = resources.displayMetrics.density
            val size = (28 * density).toInt()
            val margin = (6 * density).toInt()
            val selectedIndex = DayScheduleColor.brightPalette.indexOf(colorArgb).takeIf { it >= 0 }
                ?: DayScheduleColor.brightPalette.indices.minByOrNull {
                    colorDistance(DayScheduleColor.argb(it), colorArgb)
                } ?: 0
            DayScheduleColor.brightPalette.forEachIndexed { index, paletteColor ->
                val isSelected = index == selectedIndex
                val dot = View(this).apply {
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
                val lp = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                colorsRow.addView(dot, lp)
            }
        }
        bindColorDots()

        fun updateLabels() {
            dateButton.text = getString(R.string.schedule_date_value, date.toString())
            startButton.text = getString(R.string.schedule_start_value, DayScheduleStore.minutesToHm(start))
            endButton.text = getString(R.string.schedule_end_value, DayScheduleStore.minutesToHm(end))
        }
        updateLabels()
        dateButton.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.schedule_date)
                .setSelection(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                date = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                updateLabels()
            }
            picker.show(supportFragmentManager, "schedule_edit_date")
        }
        startButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                start = hour * 60 + minute
                if (end <= start) end = (start + 60).coerceAtMost(24 * 60 - 1)
                updateLabels()
            }, start / 60, start % 60, true).show()
        }
        endButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                end = hour * 60 + minute
                updateLabels()
            }, end / 60, end % 60, true).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.schedule_new_title else R.string.schedule_edit)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_save, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.schedule_delete, null)
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveEditedSchedule(dialog, existing, title, date, start, end, allow, block, colorArgb)
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

    private fun colorDistance(a: Int, b: Int): Int {
        val ar = Color.red(a) - Color.red(b)
        val ag = Color.green(a) - Color.green(b)
        val ab = Color.blue(a) - Color.blue(b)
        return ar * ar + ag * ag + ab * ab
    }

    private fun saveEditedSchedule(
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
            titleInput.error = getString(R.string.schedule_title_required)
            return
        }
        if (end <= start) {
            Toast.makeText(this, R.string.schedule_time_invalid, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.schedule_policy_locked, Toast.LENGTH_LONG).show()
            return
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        io.execute {
            val kind = when {
                existing == null -> "create"
                existing.startMinutes != next.startMinutes || existing.endMinutes != next.endMinutes ||
                    existing.date != next.date -> "time"
                existing.allowPackages != next.allowPackages || existing.blockPackages != next.blockPackages -> "policy"
                else -> "title"
            }
            val review = ScheduleLlmClient.reviewChange(
                next,
                kind,
                DayScheduleStore.forDate(date)
            )
            mainHandler.post {
                if (!review.accept) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(this, review.reason, Toast.LENGTH_LONG).show()
                    return@post
                }
                val ok = if (existing == null) {
                    DayScheduleStore.insert(next) > 0L
                } else {
                    DayScheduleStore.update(next)
                }
                if (!ok) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(this, R.string.schedule_policy_locked, Toast.LENGTH_LONG).show()
                    return@post
                }
                dialog.dismiss()
                Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show()
                selectedDate = date
                visibleMonth = YearMonth.from(date)
                calendar.smoothScrollToMonth(visibleMonth)
                refreshAll()
            }
        }
    }

    private fun confirmDelete(schedule: DaySchedule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete)
            .setMessage(getString(R.string.schedule_delete_confirm, schedule.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_delete) { _, _ ->
                DayScheduleStore.delete(schedule.id)
                Toast.makeText(this, R.string.schedule_deleted, Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .show()
    }

    private fun showAiDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.schedule_ai_input_hint)
            minLines = 4
            maxLines = 8
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.schedule_ai_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_ai_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val instruction = input.text?.toString()?.trim().orEmpty()
                if (instruction.isBlank()) {
                    input.error = getString(R.string.schedule_title_required)
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = getString(R.string.schedule_ai_running)
                io.execute {
                    val result = ScheduleLlmClient.applyInstruction(
                        instruction = instruction,
                        anchorDate = selectedDate,
                        rangeStart = visibleMonth.minusMonths(1).atDay(1),
                        rangeEnd = visibleMonth.plusMonths(1).atEndOfMonth()
                    )
                    mainHandler.post {
                        dialog.dismiss()
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        if (result.changed) {
                            result.focusDate?.let {
                                selectedDate = it
                                visibleMonth = YearMonth.from(it)
                                calendar.smoothScrollToMonth(visibleMonth)
                            }
                            refreshAll()
                        }
                    }
                }
            }
        }
        dialog.show()
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

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun brandColor(): Int = ContextCompat.getColor(this, R.color.brand_primary)
    private fun textColor(): Int = ContextCompat.getColor(this, R.color.text_primary)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private inner class ScheduleDayContainer(view: View) : ViewContainer(view) {
        val label: TextView = view.findViewById(R.id.scheduleDayText)
        val dot: View = view.findViewById(R.id.scheduleDayDot)
        lateinit var day: CalendarDay

        init {
            view.setOnClickListener {
                if (::day.isInitialized && day.position == DayPosition.MonthDate) {
                    val previous = selectedDate
                    selectedDate = day.date
                    calendar.notifyDateChanged(previous)
                    calendar.notifyDateChanged(selectedDate)
                    renderSelectedDate()
                    renderGuardStatus()
                }
            }
        }
    }
}
