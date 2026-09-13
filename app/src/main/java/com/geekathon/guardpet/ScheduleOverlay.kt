package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.geekathon.guardpet.databinding.OverlayDayScheduleCardBinding
import com.geekathon.guardpet.databinding.OverlayDaySchedulesBinding
import java.time.LocalDate
import java.util.concurrent.Executors

object ScheduleHud {
    @Volatile
    private var overlay: ScheduleOverlay? = null

    fun show(context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        val app = context.applicationContext
        val current = overlay ?: ScheduleOverlay(app).also { overlay = it }
        current.show()
    }

    fun close() {
        overlay?.close()
    }

    fun showFillTimes(context: Context, drafts: List<ScheduleDraft>, rawNote: String, date: LocalDate) {
        show(context)
        overlay?.beginFillTimes(drafts, rawNote, date)
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.SCHEDULE)
    }

    fun refresh() {
        overlay?.refreshList()
    }

    fun raiseWindow() {
        overlay?.raiseWindow()
    }

    fun retractToEdge(onEnd: () -> Unit) {
        overlay?.retractToEdge(onEnd) ?: onEnd()
    }

    fun expandFromEdge(onEnd: () -> Unit) {
        overlay?.expandFromEdge(onEnd) ?: onEnd()
    }

    fun setChromeVisible(visible: Boolean) {
        overlay?.setChromeVisible(visible)
    }

    fun noteInteraction() {
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.SCHEDULE)
    }

    internal fun closeIf(target: ScheduleOverlay) {
        if (overlay === target) overlay = null
    }

    fun onCalendarGranted() {
        overlay?.importCalendar()
    }

    /** MicPermissionActivity 日程请求授权成功后回调。 */
    internal fun onMicGranted() {
        // 下次按住语音按钮时再录音；此处不自动开录
    }
}

class ScheduleOverlay(private val app: Context) {
    private val themed = ContextThemeWrapper(app, R.style.Theme_DesktopPet)
    private val inflater = LayoutInflater.from(themed)
    private val binding = OverlayDaySchedulesBinding.inflate(inflater)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var attached = false
    private var expandedId: Long? = null
    private var stopObserve: (() -> Unit)? = null
    private var needsEntrance = false
    private var closing = false

    private var pendingDrafts: MutableList<ScheduleDraft> = mutableListOf()
    private var pendingRaw: String = ""
    private var pendingDate: LocalDate = LocalDate.now()
    private var fillVoiceRecorder: FlashNoteRecorder? = null
    private var policyVoiceForId: Long? = null

    fun show() {
        if (closing) return
        if (!attached) attach()
        val animate = needsEntrance
        needsEntrance = false
        refreshList(animateEntrance = animate)
        if (animate) {
            entranceChromeViews().forEach(::prepareEntrance)
            playEntranceAnimation()
        }
    }

    fun close() {
        if (!attached) {
            ScheduleHud.closeIf(this)
            return
        }
        if (closing) return
        closing = true
        stopObserve?.invoke()
        stopObserve = null
        playExitAnimation {
            dismissImmediate()
            ScheduleHud.closeIf(this)
        }
    }

    fun raiseWindow() {
        if (!attached || closing) return
        DualOverlayShell.bringSideToFront(OverlayLayerCoordinator.Side.SCHEDULE)
    }

    fun setChromeVisible(visible: Boolean) {
        if (!attached) return
        DualOverlayShell.setPanelVisible(OverlayLayerCoordinator.Side.SCHEDULE, visible)
    }

    fun retractToEdge(onEnd: () -> Unit) {
        if (!attached || closing) {
            onEnd()
            return
        }
        val items = allOverlayItemsTopToBottom()
        if (items.isEmpty()) {
            onEnd()
            return
        }
        val outX = -slideDistance()
        val lastIndex = items.lastIndex
        items.forEachIndexed { index, view ->
            val delay = (lastIndex - index) * ENTRANCE_STAGGER_MS
            view.animate().cancel()
            view.animate()
                .translationX(outX)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (index == 0) onEnd()
                }
                .start()
        }
    }

    fun expandFromEdge(onEnd: () -> Unit) {
        if (!attached || closing) {
            onEnd()
            return
        }
        val items = allOverlayItemsTopToBottom()
        if (items.isEmpty()) {
            onEnd()
            return
        }
        items.forEachIndexed { index, view ->
            view.animate().cancel()
            view.translationX = -slideDistance()
            view.alpha = 0f
            view.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(index * ENTRANCE_STAGGER_MS)
                .setDuration(ENTRANCE_DURATION_MS)
                .setInterpolator(OvershootInterpolator(1.15f))
                .withEndAction {
                    if (index == items.lastIndex) onEnd()
                }
                .start()
        }
    }

    fun refreshList(animateEntrance: Boolean = false) {
        if (!attached) return
        val list = DayScheduleStore.today()
        binding.scheduleCards.removeAllViews()
        binding.scheduleEmptyHint.visibility =
            if (list.isEmpty() && pendingDrafts.isEmpty()) View.VISIBLE else View.GONE
        val chromeCount = entranceChromeViews().size
        list.forEachIndexed { index, schedule ->
            bindCard(schedule, animateEntrance, chromeCount + index)
        }
        if (animateEntrance && binding.scheduleEmptyHint.visibility == View.VISIBLE) {
            prepareEntrance(binding.scheduleEmptyHint)
            slideInFromLeft(binding.scheduleEmptyHint, chromeCount)
        }
    }

    fun beginFillTimes(drafts: List<ScheduleDraft>, rawNote: String, date: LocalDate) {
        pendingDrafts = drafts.toMutableList()
        pendingRaw = rawNote
        pendingDate = date
        renderFillPanel()
    }

    fun importCalendar() {
        io.execute {
            val (added, skipped, err) = CalendarScheduleImporter.importToday(app)
            handler.post {
                when {
                    err != null -> Toast.makeText(app, err, Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(
                        app,
                        app.getString(R.string.schedule_import_result, added, skipped),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshList()
            }
        }
    }

    private fun dismissImmediate() {
        cancelAllOverlayAnimations(resetVisible = false)
        if (attached) {
            binding.root.alpha = 0f
            binding.root.visibility = View.GONE
            DualOverlayShell.detachSchedule(binding.root)
            attached = false
        }
        needsEntrance = false
        closing = false
    }

    private fun attach() {
        needsEntrance = true
        closing = false
        binding.root.alpha = 1f
        binding.root.visibility = View.VISIBLE
        DualOverlayShell.attachSchedule(app, binding.root)
        attached = true
        binding.closeScheduleOverlayButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            FlashNoteHud.close()
        }
        binding.importCalendarButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            requestImport()
        }
        binding.scheduleFillConfirmButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            confirmFillTimes()
        }
        binding.root.onAnyTouchDown = {
            ScheduleHud.noteInteraction()
        }
        setupFillVoiceHold()
        stopObserve = DayScheduleStore.observe { handler.post { refreshList() } }
    }

    private fun requestImport() {
        if (CalendarScheduleImporter.hasPermission(app)) {
            importCalendar()
        } else {
            app.startActivity(
                Intent(app, CalendarPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun renderFillPanel() {
        val panel = binding.scheduleFillPanel
        val rows = binding.scheduleFillRows
        if (pendingDrafts.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        rows.removeAllViews()
        pendingDrafts.forEachIndexed { index, draft ->
            val row = LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val title = TextView(themed).apply {
                text = draft.title
                setTextColor(app.getColor(R.color.text_primary))
                textSize = 13f
            }
            val itemDate = draft.date ?: pendingDate
            val dateBtn = TextView(themed).apply {
                text = app.getString(
                    R.string.schedule_draft_date,
                    ScheduleDateParse.label(itemDate, LocalDate.now())
                )
                setTextColor(app.getColor(R.color.text_primary))
                textSize = 12f
                setPadding(12, 10, 12, 10)
                background = app.getDrawable(R.drawable.flash_input_bg)
                setOnClickListener {
                    ScheduleHud.noteInteraction()
                    pickDateForDraft(index)
                }
            }
            val timeBtn = TextView(themed).apply {
                val start = draft.startMinutes
                val end = draft.endMinutes ?: start?.plus(60)
                text = if (start != null) {
                    "${DayScheduleStore.minutesToHm(start)} – ${DayScheduleStore.minutesToHm(end!!)}"
                } else {
                    app.getString(R.string.schedule_pick_time)
                }
                setTextColor(app.getColor(R.color.text_primary))
                textSize = 12f
                setPadding(12, 10, 12, 10)
                background = app.getDrawable(R.drawable.flash_input_bg)
                setOnClickListener {
                    ScheduleHud.noteInteraction()
                    pickTimesForDraft(index)
                }
            }
            val typical = DayScheduleStore.typicalTime(DaySchedule.normalizeTitleKey(draft.title))
            if (typical != null && draft.startMinutes == null) {
                val chip = TextView(themed).apply {
                    text = app.getString(
                        R.string.schedule_typical_chip,
                        DayScheduleStore.minutesToHm(typical.first)
                    )
                    setTextColor(app.getColor(R.color.text_secondary))
                    textSize = 11f
                    setOnClickListener {
                        ScheduleHud.noteInteraction()
                        pendingDrafts[index] = draft.copy(
                            startMinutes = typical.first,
                            endMinutes = typical.second,
                            needsTime = false
                        )
                        renderFillPanel()
                    }
                }
                row.addView(chip)
            }
            row.addView(title)
            row.addView(dateBtn)
            row.addView(timeBtn)
            rows.addView(row)
        }
        val allReady = pendingDrafts.none { it.needsTime || it.startMinutes == null }
        binding.scheduleFillConfirmButton.alpha = if (allReady) 1f else 0.45f
        binding.scheduleFillConfirmButton.isEnabled = allReady
    }

    private fun pickDateForDraft(index: Int) {
        val draft = pendingDrafts.getOrNull(index) ?: return
        val current = draft.date ?: pendingDate
        ScheduleDatePickActivity.request(app, index, current) { i, date ->
            handler.post {
                val cur = pendingDrafts.getOrNull(i) ?: return@post
                pendingDrafts[i] = cur.copy(date = date)
                renderFillPanel()
            }
        }
    }

    private fun pickTimesForDraft(index: Int) {
        val draft = pendingDrafts.getOrNull(index) ?: return
        ScheduleTimePickActivity.request(app, index, draft.startMinutes) { i, start, end ->
            handler.post {
                val cur = pendingDrafts.getOrNull(i) ?: return@post
                pendingDrafts[i] = cur.copy(
                    startMinutes = start,
                    endMinutes = end,
                    needsTime = false
                )
                renderFillPanel()
            }
        }
    }

    private fun confirmFillTimes() {
        if (pendingDrafts.any { it.needsTime || it.startMinutes == null }) {
            Toast.makeText(app, R.string.schedule_still_need_time, Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val (n, err) = ScheduleLlmClient.commitDrafts(
                pendingDrafts,
                pendingDate,
                pendingRaw
            )
            handler.post {
                if (err != null) {
                    Toast.makeText(app, err, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        app,
                        app.getString(R.string.schedule_created_count, n),
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingDrafts.clear()
                    binding.scheduleFillPanel.visibility = View.GONE
                    refreshList()
                }
            }
        }
    }

    private fun setupFillVoiceHold() {
        binding.scheduleFillVoiceButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ScheduleHud.noteInteraction()
                    val rec = FlashNoteRecorder(app)
                    fillVoiceRecorder = rec
                    if (rec.start()) {
                        binding.scheduleFillVoiceButton.text =
                            app.getString(R.string.schedule_listening)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val path = fillVoiceRecorder?.stop()
                    fillVoiceRecorder = null
                    binding.scheduleFillVoiceButton.text =
                        app.getString(R.string.schedule_hold_voice_time)
                    if (path != null) {
                        SenseVoiceAsr.transcribe(app, path) { spoken ->
                            handler.post {
                                if (spoken.isNullOrBlank()) {
                                    Toast.makeText(
                                        app,
                                        R.string.schedule_voice_empty,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@post
                                }
                                io.execute {
                                    val (aligned, err) = ScheduleLlmClient.alignTimesFromVoice(
                                        pendingDrafts,
                                        spoken,
                                        pendingDate
                                    )
                                    handler.post {
                                        pendingDrafts = aligned.map { d ->
                                            if (d.startMinutes != null) d.copy(needsTime = false) else d
                                        }.toMutableList()
                                        renderFillPanel()
                                        val msg = err ?: app.getString(R.string.schedule_voice_applied)
                                        Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
                                        if (pendingDrafts.none { it.needsTime || it.startMinutes == null }) {
                                            confirmFillTimes()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bindCard(schedule: DaySchedule, animateEntrance: Boolean, entranceIndex: Int) {
        val card = OverlayDayScheduleCardBinding.inflate(inflater, binding.scheduleCards, false)
        card.root.tag = schedule.id
        val bg = GradientDrawable().apply {
            cornerRadius = 18 * app.resources.displayMetrics.density
            setColor(schedule.displayColor())
        }
        card.root.background = bg
        val timeLabel =
            "${DayScheduleStore.minutesToHm(schedule.startMinutes)}–${DayScheduleStore.minutesToHm(schedule.endMinutes)}"
        card.scheduleCollapsedText.text = "$timeLabel  ${schedule.title}"
        card.scheduleExpandedTime.text = timeLabel
        card.scheduleExpandedTitle.text = schedule.title
        card.schedulePolicyHint.text = buildPolicyHint(schedule)
        val expanded = expandedId == schedule.id
        applyCardExpandedState(card, expanded)
        card.scheduleLockedHint.visibility =
            if (schedule.isPolicyLocked()) View.VISIBLE else View.GONE
        card.scheduleDoneButton.visibility =
            if (schedule.status == DayScheduleStatus.PENDING) View.VISIBLE else View.GONE
        card.scheduleEditPolicyButton.isEnabled = !schedule.isPolicyLocked()
        card.scheduleVoicePolicyButton.isEnabled = !schedule.isPolicyLocked()
        card.scheduleEditPolicyButton.alpha = if (schedule.isPolicyLocked()) 0.4f else 1f
        card.scheduleVoicePolicyButton.alpha = if (schedule.isPolicyLocked()) 0.4f else 1f

        card.scheduleCollapsedRow.setOnClickListener {
            ScheduleHud.noteInteraction()
            if (expandedId == schedule.id) return@setOnClickListener
            val previous = expandedId
            expandedId = schedule.id
            if (previous != null) {
                findCard(previous)?.let { animateCardCollapse(it) }
            }
            animateCardExpand(card)
        }
        card.scheduleCollapseButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            expandedId = null
            animateCardCollapse(card)
        }
        card.scheduleDoneButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            val (ok, msg) = DayScheduleStore.markDone(schedule.id)
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            if (ok) refreshList()
        }
        card.scheduleColorButton.visibility =
            if (schedule.status == DayScheduleStatus.PENDING) View.VISIBLE else View.GONE
        card.scheduleColorButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            val next = schedule.copy(colorArgb = DayScheduleColor.nextColor(schedule.colorArgb))
            if (DayScheduleStore.update(next)) refreshList()
        }
        card.scheduleDeleteButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            DayScheduleStore.delete(schedule.id)
            if (expandedId == schedule.id) expandedId = null
            refreshList()
        }
        card.scheduleEditPolicyButton.setOnClickListener {
            ScheduleHud.noteInteraction()
            if (schedule.isPolicyLocked()) {
                Toast.makeText(app, R.string.schedule_policy_locked, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePolicyPicker(card, schedule)
        }
        setupPolicyVoice(card, schedule)
        binding.scheduleCards.addView(card.root)
        if (animateEntrance) {
            prepareEntrance(card.root)
            slideInFromLeft(card.root, entranceIndex)
        }
    }

    private fun findCard(scheduleId: Long): OverlayDayScheduleCardBinding? {
        for (i in 0 until binding.scheduleCards.childCount) {
            val child = binding.scheduleCards.getChildAt(i)
            if (child.tag == scheduleId) return OverlayDayScheduleCardBinding.bind(child)
        }
        return null
    }

    private fun applyCardExpandedState(card: OverlayDayScheduleCardBinding, expanded: Boolean) {
        card.scheduleCollapsedRow.animate().cancel()
        card.scheduleExpandedBlock.animate().cancel()
        card.scheduleCollapsedRow.alpha = 1f
        card.scheduleExpandedBlock.alpha = 1f
        card.scheduleExpandedBlock.translationY = 0f
        card.scheduleExpandedBlock.scaleY = 1f
        card.scheduleCollapsedRow.visibility = if (expanded) View.GONE else View.VISIBLE
        card.scheduleExpandedBlock.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun animateCardExpand(card: OverlayDayScheduleCardBinding) {
        card.scheduleCollapsedRow.animate().cancel()
        card.scheduleExpandedBlock.animate().cancel()
        card.scheduleCollapsedRow.animate()
            .alpha(0f)
            .setDuration(PANEL_FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                card.scheduleCollapsedRow.visibility = View.GONE
                card.scheduleCollapsedRow.alpha = 1f
            }
            .start()
        card.scheduleExpandedBlock.visibility = View.VISIBLE
        card.scheduleExpandedBlock.alpha = 0f
        card.scheduleExpandedBlock.translationY = -dp(12f)
        card.scheduleExpandedBlock.scaleY = 0.92f
        card.scheduleExpandedBlock.pivotY = 0f
        card.scheduleExpandedBlock.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(PANEL_EXPAND_MS)
            .setInterpolator(OvershootInterpolator(1.08f))
            .start()
    }

    private fun animateCardCollapse(card: OverlayDayScheduleCardBinding) {
        card.scheduleCollapsedRow.animate().cancel()
        card.scheduleExpandedBlock.animate().cancel()
        card.scheduleExpandedBlock.pivotY = 0f
        card.scheduleExpandedBlock.animate()
            .alpha(0f)
            .translationY(-dp(10f))
            .scaleY(0.92f)
            .setDuration(PANEL_COLLAPSE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                card.scheduleExpandedBlock.visibility = View.GONE
                card.scheduleExpandedBlock.alpha = 1f
                card.scheduleExpandedBlock.translationY = 0f
                card.scheduleExpandedBlock.scaleY = 1f
                card.scheduleCollapsedRow.visibility = View.VISIBLE
                card.scheduleCollapsedRow.alpha = 0f
                card.scheduleCollapsedRow.animate()
                    .alpha(1f)
                    .setDuration(PANEL_FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun buildPolicyHint(s: DaySchedule): String {
        val allow = if (s.allowPackages.isEmpty()) "—" else s.allowPackages.joinToString("、") {
            labelOf(it)
        }
        val block = if (s.blockPackages.isEmpty()) "—" else s.blockPackages.joinToString("、") {
            labelOf(it)
        }
        return app.getString(R.string.schedule_policy_summary, allow, block)
    }

    private fun labelOf(pkg: String): String =
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

    private fun togglePolicyPicker(card: OverlayDayScheduleCardBinding, schedule: DaySchedule) {
        val picker = card.schedulePolicyPicker
        if (picker.visibility == View.VISIBLE) {
            picker.visibility = View.GONE
            return
        }
        picker.removeAllViews()
        picker.visibility = View.VISIBLE
        val candidates = candidatePackages()
        val allowSet = schedule.allowPackages.toMutableSet()
        val blockSet = schedule.blockPackages.toMutableSet()
        candidates.forEach { pkg ->
            val row = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(themed).apply {
                text = labelOf(pkg)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val allow = CheckBox(themed).apply {
                text = "允"
                setTextColor(0xFFFFFFFF.toInt())
                isChecked = pkg in allowSet
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        allowSet.add(pkg)
                        blockSet.remove(pkg)
                    } else allowSet.remove(pkg)
                }
            }
            val block = CheckBox(themed).apply {
                text = "禁"
                setTextColor(0xFFFFFFFF.toInt())
                isChecked = pkg in blockSet
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        blockSet.add(pkg)
                        allowSet.remove(pkg)
                    } else blockSet.remove(pkg)
                }
            }
            row.addView(name)
            row.addView(allow)
            row.addView(block)
            picker.addView(row)
        }
        val save = TextView(themed).apply {
            text = app.getString(R.string.schedule_save_policy)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 12, 0, 4)
            setOnClickListener {
                ScheduleHud.noteInteraction()
                val next = schedule.copy(
                    allowPackages = allowSet.toList(),
                    blockPackages = blockSet.toList()
                )
                io.execute {
                    val review = ScheduleLlmClient.reviewChange(next, "policy")
                    handler.post {
                        if (!review.accept) {
                            Toast.makeText(app, review.reason, Toast.LENGTH_LONG).show()
                            return@post
                        }
                        if (!DayScheduleStore.update(next)) {
                            Toast.makeText(app, R.string.schedule_policy_locked, Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            Toast.makeText(app, R.string.schedule_policy_saved, Toast.LENGTH_SHORT)
                                .show()
                            refreshList()
                        }
                    }
                }
            }
        }
        picker.addView(save)
    }

    private fun setupPolicyVoice(card: OverlayDayScheduleCardBinding, schedule: DaySchedule) {
        var recorder: FlashNoteRecorder? = null
        card.scheduleVoicePolicyButton.setOnTouchListener { _, event ->
            if (schedule.isPolicyLocked()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ScheduleHud.noteInteraction()
                    policyVoiceForId = schedule.id
                    recorder = FlashNoteRecorder(app).also { it.start() }
                    card.scheduleVoicePolicyButton.text = "…"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val path = recorder?.stop()
                    recorder = null
                    card.scheduleVoicePolicyButton.text =
                        app.getString(R.string.schedule_hold_voice_policy)
                    if (path != null && policyVoiceForId == schedule.id) {
                        SenseVoiceAsr.transcribe(app, path) { spoken ->
                            handler.post {
                                if (spoken.isNullOrBlank()) {
                                    Toast.makeText(
                                        app,
                                        R.string.schedule_voice_empty,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@post
                                }
                                val parsed = ScheduleLlmClient.parsePolicyFromVoice(schedule, spoken)
                                if (parsed == null) {
                                    Toast.makeText(
                                        app,
                                        R.string.schedule_voice_empty,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@post
                                }
                                val next = schedule.copy(
                                    allowPackages = parsed.first,
                                    blockPackages = parsed.second
                                )
                                io.execute {
                                    val review = ScheduleLlmClient.reviewChange(next, "policy")
                                    handler.post {
                                        if (!review.accept) {
                                            Toast.makeText(app, review.reason, Toast.LENGTH_LONG)
                                                .show()
                                        } else if (DayScheduleStore.update(next)) {
                                            Toast.makeText(
                                                app,
                                                R.string.schedule_policy_saved,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            refreshList()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun candidatePackages(): List<String> {
        val priority = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "tv.danmaku.bili",
            "com.xingin.xhs",
            "com.sankuai.meituan",
            "com.coolapk.market",
            "com.google.android.apps.docs",
            "com.android.chrome"
        )
        val pm = app.packageManager
        val installed = priority.filter {
            runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false)
        }.toMutableList()
        runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .take(30)
                .forEach { installed.add(it.packageName) }
        }
        return installed.distinct().take(24)
    }

    private fun entranceChromeViews(): List<View> = buildList {
        if (binding.closeScheduleOverlayButton.visibility == View.VISIBLE ||
            binding.importCalendarButton.visibility == View.VISIBLE
        ) {
            // 顶部一行：导入 + 关闭一起滑入
            add(binding.importCalendarButton.parent as View)
        }
        if (binding.scheduleFillPanel.visibility == View.VISIBLE) {
            add(binding.scheduleFillPanel)
        }
    }

    private fun allOverlayItemsTopToBottom(): List<View> = buildList {
        addAll(entranceChromeViews())
        if (binding.scheduleEmptyHint.visibility == View.VISIBLE) {
            add(binding.scheduleEmptyHint)
        }
        for (i in 0 until binding.scheduleCards.childCount) {
            add(binding.scheduleCards.getChildAt(i))
        }
    }

    private fun playEntranceAnimation() {
        entranceChromeViews().forEachIndexed { index, view ->
            slideInFromLeft(view, index)
        }
    }

    private fun playExitAnimation(onEnd: () -> Unit) {
        val items = allOverlayItemsTopToBottom()
        if (items.isEmpty()) {
            onEnd()
            return
        }
        val outX = -slideDistance()
        val lastIndex = items.lastIndex
        items.forEachIndexed { index, view ->
            val delay = (lastIndex - index) * ENTRANCE_STAGGER_MS
            view.animate().cancel()
            view.animate()
                .translationX(outX)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (index == 0) onEnd()
                }
                .start()
        }
    }

    private fun prepareEntrance(view: View) {
        view.animate().cancel()
        view.translationX = -slideDistance()
        view.alpha = 0f
    }

    private fun slideInFromLeft(view: View, index: Int) {
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setStartDelay(index * ENTRANCE_STAGGER_MS)
            .setDuration(ENTRANCE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
    }

    private fun cancelAllOverlayAnimations(resetVisible: Boolean = true) {
        allOverlayItemsTopToBottom().forEach { view ->
            view.animate().cancel()
            if (resetVisible) {
                view.translationX = 0f
                view.alpha = 1f
            }
        }
    }

    private fun slideDistance() = ENTRANCE_FROM_DP * app.resources.displayMetrics.density

    private fun dp(v: Float) = v * app.resources.displayMetrics.density

    companion object {
        private const val ENTRANCE_FROM_DP = 56f
        private const val ENTRANCE_STAGGER_MS = 48L
        private const val ENTRANCE_DURATION_MS = 360L
        private const val EXIT_DURATION_MS = 240L
        private const val PANEL_EXPAND_MS = 280L
        private const val PANEL_COLLAPSE_MS = 220L
        private const val PANEL_FADE_MS = 140L
    }
}
