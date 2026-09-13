package com.geekathon.guardpet

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.geekathon.guardpet.databinding.OverlayFlashNoteCardBinding
import com.geekathon.guardpet.databinding.OverlayFlashNotesBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FlashNoteHud {
    @Volatile
    private var overlay: FlashNoteOverlay? = null

    fun showList(context: Context) {
        withOverlay(context) { it.show(openComposer = false) }
        ScheduleHud.show(context)
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.FLASH)
    }

    fun startCapture(
        context: Context,
        prefill: String? = null,
        source: String = "typed"
    ) {
        withOverlay(context) { it.show(openComposer = true, prefill = prefill, source = source) }
        ScheduleHud.show(context)
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.FLASH)
    }

    /** Volume chord long-press: open composer and start recording. */
    fun beginVolumeHoldRecord(context: Context) {
        withOverlay(context) { it.beginVolumeHoldRecord() }
        ScheduleHud.show(context)
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.FLASH)
    }

    /** Volume chord release after long-press: stop recording (+ ASR). */
    fun endVolumeHoldRecord(context: Context) {
        overlay?.endVolumeHoldRecord()
    }

    fun close() {
        overlay?.close()
        ScheduleHud.close()
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
        OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.FLASH)
    }

    internal fun closeIf(target: FlashNoteOverlay) {
        if (overlay === target) overlay = null
    }

    internal fun onMicGranted() {
        overlay?.onMicGranted()
    }

    private fun withOverlay(context: Context, block: (FlashNoteOverlay) -> Unit) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        val app = context.applicationContext
        val current = overlay ?: FlashNoteOverlay(app).also { overlay = it }
        block(current)
    }
}

class FlashNoteOverlay(private val app: Context) {
    private val themed = ContextThemeWrapper(app, R.style.Theme_DesktopPet)
    private val inflater = LayoutInflater.from(themed)
    private val binding = OverlayFlashNotesBinding.inflate(inflater)
    private val recorder = FlashNoteRecorder(app)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
    private var attached = false
    private var expandedId: Long? = null
    private var composerColor = 0
    private var composerCategory = FlashNoteCategory.OTHER
    private var scheduleDate: LocalDate = LocalDate.now()
    private var composerSource = "typed"
    private var recordedPath: String? = null
    private var pendingMic: PendingMic? = null
    private var recognizer: SpeechRecognizer? = null
    private var stopObserve: (() -> Unit)? = null
    private var needsEntrance = false
    private var closing = false
    private var composerAnimating = false
    private var dictatingWithRecorder = false
    private var asrBusy = false
    private var volumeHoldRecording = false

    private enum class PendingMic { RECORD, DICTATE, VOLUME_HOLD }

    fun show(openComposer: Boolean, prefill: String? = null, source: String = "typed") {
        if (closing) return
        if (!attached) attach()
        composerSource = source
        if (!prefill.isNullOrBlank()) {
            binding.composerInput.setText(prefill)
            binding.composerInput.setSelection(binding.composerInput.text?.length ?: 0)
        }
        val animate = needsEntrance
        needsEntrance = false
        if (animate) {
            setComposerVisible(openComposer, animated = false)
            entranceChromeViews().forEach(::prepareEntrance)
            bindNotes(animateEntrance = true)
            playEntranceAnimation()
        } else {
            bindNotes(animateEntrance = false)
            when {
                openComposer && binding.composerCard.visibility != View.VISIBLE ->
                    setComposerVisible(true, animated = true)
                !openComposer && binding.composerCard.visibility == View.VISIBLE ->
                    setComposerVisible(false, animated = true)
                else -> setComposerVisible(openComposer, animated = false)
            }
        }
    }

    fun onMicGranted() {
        when (pendingMic) {
            PendingMic.RECORD -> startRecording()
            PendingMic.DICTATE -> startDictation()
            PendingMic.VOLUME_HOLD -> startVolumeHoldRecording()
            null -> Unit
        }
        pendingMic = null
    }

    fun beginVolumeHoldRecord() {
        if (closing) return
        show(openComposer = true, source = "volume_hold")
        if (!VolumeChordFlashNote.isChordHolding()) return
        if (recorder.isRecording && volumeHoldRecording) return
        if (recorder.isRecording) {
            // Another record session already running — leave it
            return
        }
        requestMic(PendingMic.VOLUME_HOLD)
    }

    fun endVolumeHoldRecord() {
        if (!volumeHoldRecording) return
        volumeHoldRecording = false
        if (!recorder.isRecording) {
            updateRecordButton()
            return
        }
        val path = recorder.stop()
        recordedPath = path ?: recordedPath
        composerSource = "voice"
        dictatingWithRecorder = false
        updateRecordButton()
        maybeTranscribeAfterRecord(path)
    }

    fun close() {
        if (!attached) {
            FlashNoteHud.closeIf(this)
            return
        }
        if (closing) return
        closing = true
        hideKeyboard()
        if (recorder.isRecording) recorder.cancel()
        volumeHoldRecording = false
        stopDictation()
        FlashNotePlayer.onState = null
        FlashNotePlayer.stop()
        // 左右同时收起，避免闪记退出完才关日程
        ScheduleHud.close()
        playExitAnimation {
            dismissImmediate()
            FlashNoteHud.closeIf(this)
        }
    }

    fun dismiss() {
        closing = true
        dismissImmediate()
        FlashNoteHud.closeIf(this)
        ScheduleHud.close()
    }

    fun raiseWindow() {
        if (!attached || closing) return
        DualOverlayShell.bringSideToFront(OverlayLayerCoordinator.Side.FLASH)
    }

    fun setChromeVisible(visible: Boolean) {
        if (!attached) return
        DualOverlayShell.setPanelVisible(OverlayLayerCoordinator.Side.FLASH, visible)
    }

    /** 切下层：收到屏幕右缘；结束后仍在屏上但偏出。 */
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
        val outX = slideDistance()
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

    /** 从右缘再展开到当前位置（通常已在下层）。 */
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
            view.translationX = slideDistance()
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

    private fun dismissImmediate() {
        if (recorder.isRecording) recorder.cancel()
        volumeHoldRecording = false
        stopDictation()
        FlashNotePlayer.onState = null
        FlashNotePlayer.stop()
        hideKeyboard()
        stopObserve?.invoke()
        stopObserve = null
        // Keep exit fade/slide; resetting alpha=1 here flashes the full panel for one frame.
        cancelAllOverlayAnimations(resetVisible = false)
        if (attached) {
            binding.root.alpha = 0f
            binding.root.visibility = View.GONE
            DualOverlayShell.detachFlash(binding.root)
            attached = false
        }
        needsEntrance = false
        closing = false
        composerAnimating = false
    }

    private fun attach() {
        val metrics = app.resources.displayMetrics
        binding.noteScroll.maxHeightPx = (metrics.heightPixels * 0.62f).toInt()
        needsEntrance = true
        closing = false
        binding.root.alpha = 1f
        binding.root.visibility = View.VISIBLE
        binding.closeOverlayButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            close()
        }
        binding.closeComposerButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            closeComposer()
        }
        binding.writePill.setOnClickListener {
            FlashNoteHud.noteInteraction()
            setComposerVisible(true, animated = true)
        }
        binding.composerRecordButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            toggleRecording()
        }
        binding.composerDictateButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            requestMic(PendingMic.DICTATE)
        }
        binding.composerSaveButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            saveComposer()
        }
        binding.composerDateButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            pickDate()
        }
        binding.root.onAnyTouchDown = {
            FlashNoteHud.noteInteraction()
        }
        bindComposerColors()
        bindComposerCategories()
        updateDateLabel()
        DualOverlayShell.attachFlash(app, binding.root)
        attached = true
        stopObserve = FlashNoteStore.observe {
            handler.post { if (attached && !closing) bindNotes() }
        }
        FlashNotePlayer.onState = { _, _ ->
            handler.post { if (attached && !closing) bindNotes() }
        }
    }

    private fun setComposerVisible(visible: Boolean, animated: Boolean) {
        if (animated && attached && !closing) {
            if (visible) openComposerAnimated() else hideComposerAnimated()
            return
        }
        applyComposerVisibility(visible)
        if (visible) {
            binding.composerInput.requestFocus()
            binding.composerInput.post {
                app.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(binding.composerInput, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            hideKeyboard()
            if (recorder.isRecording) {
                val path = recorder.stop()
                recordedPath = path ?: recordedPath
                val wasDictate = dictatingWithRecorder
                dictatingWithRecorder = false
                updateRecordButton()
                if (wasDictate || SenseVoiceAsr.hasModel(app)) {
                    maybeTranscribeAfterRecord(path)
                }
            }
            stopDictation()
        }
    }

    private fun applyComposerVisibility(visible: Boolean) {
        binding.composerCard.animate().cancel()
        binding.writePill.animate().cancel()
        binding.closeOverlayButton.animate().cancel()
        binding.composerCard.translationX = 0f
        binding.composerCard.alpha = 1f
        binding.writePill.translationX = 0f
        binding.writePill.alpha = 1f
        binding.closeOverlayButton.translationX = 0f
        binding.closeOverlayButton.alpha = 1f
        binding.composerCard.visibility = if (visible) View.VISIBLE else View.GONE
        binding.writePill.visibility = if (visible) View.GONE else View.VISIBLE
        binding.closeOverlayButton.visibility = if (visible) View.GONE else View.VISIBLE
        applyFocus(visible)
    }

    private fun openComposerAnimated() {
        if (composerAnimating || binding.composerCard.visibility == View.VISIBLE) return
        composerAnimating = true
        val outX = slideDistance()
        val chrome = listOf(binding.writePill, binding.closeOverlayButton).filter {
            it.visibility == View.VISIBLE
        }
        var pending = chrome.size.coerceAtLeast(1)
        fun revealComposer() {
            binding.composerCard.visibility = View.VISIBLE
            prepareEntrance(binding.composerCard)
            applyFocus(true)
            binding.composerCard.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(ENTRANCE_DURATION_MS)
                .setInterpolator(OvershootInterpolator(1.15f))
                .withEndAction {
                    composerAnimating = false
                    binding.composerInput.requestFocus()
                    binding.composerInput.post {
                        app.getSystemService(InputMethodManager::class.java)
                            .showSoftInput(binding.composerInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                .start()
        }
        if (chrome.isEmpty()) {
            revealComposer()
            return
        }
        chrome.forEachIndexed { index, view ->
            view.animate()
                .translationX(outX)
                .alpha(0f)
                .setStartDelay(index * ENTRANCE_STAGGER_MS)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.alpha = 1f
                    pending--
                    if (pending == 0) revealComposer()
                }
                .start()
        }
    }

    private fun hideComposerAnimated() {
        if (composerAnimating) return
        if (binding.composerCard.visibility != View.VISIBLE) {
            applyComposerVisibility(false)
            return
        }
        composerAnimating = true
        hideKeyboard()
        if (recorder.isRecording) {
            recordedPath = recorder.stop() ?: recordedPath
        }
        stopDictation()
        val outX = slideDistance()
        binding.composerCard.animate()
            .translationX(outX)
            .alpha(0f)
            .setStartDelay(0)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                binding.composerCard.visibility = View.GONE
                binding.composerCard.translationX = 0f
                binding.composerCard.alpha = 1f
                binding.writePill.visibility = View.VISIBLE
                binding.closeOverlayButton.visibility = View.VISIBLE
                prepareEntrance(binding.closeOverlayButton)
                prepareEntrance(binding.writePill)
                applyFocus(false)
                slideInFromRight(binding.closeOverlayButton, 0)
                binding.writePill.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setStartDelay(ENTRANCE_STAGGER_MS)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .withEndAction { composerAnimating = false }
                    .start()
            }
            .start()
    }

    private fun closeComposer() {
        stopDictation()
        if (recorder.isRecording) recorder.cancel()
        recordedPath?.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).delete() } }
        recordedPath = null
        binding.composerInput.setText("")
        composerSource = "typed"
        updateRecordButton()
        setComposerVisible(false, animated = true)
    }

    private fun applyFocus(focusable: Boolean) {
        if (!attached) return
        DualOverlayShell.applyFocus(focusable)
    }

    private fun bindNotes(animateEntrance: Boolean = false) {
        val notes = runCatching { FlashNoteStore.all() }.getOrDefault(emptyList())
        binding.noteContainer.removeAllViews()
        val chromeCount = entranceChromeViews().size
        notes.forEachIndexed { index, note ->
            val card = inflateCard(note)
            binding.noteContainer.addView(card)
            if (animateEntrance) {
                prepareEntrance(card)
                slideInFromRight(card, chromeCount + index)
            }
        }
    }

    private fun entranceChromeViews(): List<View> = buildList {
        if (binding.closeOverlayButton.visibility == View.VISIBLE) add(binding.closeOverlayButton)
        if (binding.composerCard.visibility == View.VISIBLE) add(binding.composerCard)
        if (binding.writePill.visibility == View.VISIBLE) add(binding.writePill)
    }

    private fun allOverlayItemsTopToBottom(): List<View> = buildList {
        addAll(entranceChromeViews())
        for (i in 0 until binding.noteContainer.childCount) {
            add(binding.noteContainer.getChildAt(i))
        }
    }

    private fun playEntranceAnimation() {
        entranceChromeViews().forEachIndexed { index, view ->
            slideInFromRight(view, index)
        }
    }

    private fun playExitAnimation(onEnd: () -> Unit) {
        val items = allOverlayItemsTopToBottom()
        if (items.isEmpty()) {
            onEnd()
            return
        }
        val outX = slideDistance()
        val lastIndex = items.lastIndex
        items.forEachIndexed { index, view ->
            // Reverse of open: bottom leaves first, top last.
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
        view.translationX = slideDistance()
        view.alpha = 0f
    }

    private fun slideInFromRight(view: View, index: Int) {
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setStartDelay(index * ENTRANCE_STAGGER_MS)
            .setDuration(ENTRANCE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
    }

    private fun slideDistance() = ENTRANCE_FROM_DP * app.resources.displayMetrics.density

    private fun cancelAllOverlayAnimations(resetVisible: Boolean = true) {
        entranceChromeViews().forEach { view ->
            view.animate().cancel()
            if (resetVisible) {
                view.translationX = 0f
                view.alpha = 1f
            }
        }
        for (i in 0 until binding.noteContainer.childCount) {
            val child = binding.noteContainer.getChildAt(i)
            child.animate().cancel()
            if (resetVisible) {
                child.translationX = 0f
                child.alpha = 1f
            }
            runCatching {
                val card = OverlayFlashNoteCardBinding.bind(child)
                card.collapsedRow.animate().cancel()
                card.expandedBlock.animate().cancel()
            }
        }
    }

    private fun inflateCard(note: FlashNote): View {
        val card = OverlayFlashNoteCardBinding.inflate(inflater, binding.noteContainer, false)
        card.root.tag = note.id
        FlashNoteColor.applyCardBackground(card.root, note, dp(22f))
        val expanded = expandedId == note.id
        applyCardExpandedState(card, expanded, animate = false)
        val preview = note.text.ifBlank { app.getString(R.string.flash_note_voice_placeholder) }
        card.collapsedText.text = preview
        card.collapsedText.maxWidth = (app.resources.displayMetrics.widthPixels * 0.58f).toInt()
        bindPlayback(note, card)
        card.expandedTime.text = timeFormat.format(
            Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault())
        )
        card.expandedText.text = preview
        if (note.category == FlashNoteCategory.TODO) {
            card.colorMark.visibility = View.VISIBLE
            card.colorMark.setOnClickListener {
                FlashNoteHud.noteInteraction()
                FlashNoteStore.update(
                    note.copy(color = FlashNoteColor.nextTodoUrgency(note.color))
                )
            }
            bindExpandedColors(card.expandedColors, note)
        } else {
            card.colorMark.visibility = View.GONE
            card.expandedColors.removeAllViews()
            card.expandedColors.visibility = View.GONE
        }
        card.collapseButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            if (closing) return@setOnClickListener
            expandedId = null
            animateCardCollapse(card)
        }
        card.deleteButton.setOnClickListener {
            FlashNoteHud.noteInteraction()
            if (FlashNotePlayer.playingId == note.id) FlashNotePlayer.stop()
            FlashNoteStore.delete(note.id)
            if (expandedId == note.id) expandedId = null
        }
        card.collapsedRow.setOnClickListener {
            FlashNoteHud.noteInteraction()
            if (closing) return@setOnClickListener
            if (expandedId == note.id) return@setOnClickListener
            val previous = expandedId
            expandedId = note.id
            if (previous != null) {
                findCard(previous)?.let { animateCardCollapse(it) }
            }
            animateCardExpand(card)
        }
        return card.root
    }

    private fun findCard(noteId: Long): OverlayFlashNoteCardBinding? {
        for (i in 0 until binding.noteContainer.childCount) {
            val child = binding.noteContainer.getChildAt(i)
            if (child.tag == noteId) return OverlayFlashNoteCardBinding.bind(child)
        }
        return null
    }

    private fun applyCardExpandedState(
        card: OverlayFlashNoteCardBinding,
        expanded: Boolean,
        animate: Boolean
    ) {
        card.collapsedRow.animate().cancel()
        card.expandedBlock.animate().cancel()
        card.collapsedRow.alpha = 1f
        card.expandedBlock.alpha = 1f
        card.expandedBlock.translationY = 0f
        card.expandedBlock.scaleY = 1f
        if (animate) {
            if (expanded) animateCardExpand(card) else animateCardCollapse(card)
        } else {
            card.collapsedRow.visibility = if (expanded) View.GONE else View.VISIBLE
            card.expandedBlock.visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    private fun animateCardExpand(card: OverlayFlashNoteCardBinding) {
        card.collapsedRow.animate().cancel()
        card.expandedBlock.animate().cancel()
        card.collapsedRow.animate()
            .alpha(0f)
            .setDuration(PANEL_FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                card.collapsedRow.visibility = View.GONE
                card.collapsedRow.alpha = 1f
            }
            .start()
        card.expandedBlock.visibility = View.VISIBLE
        card.expandedBlock.alpha = 0f
        card.expandedBlock.translationY = -dp(12f)
        card.expandedBlock.scaleY = 0.92f
        card.expandedBlock.pivotY = 0f
        card.expandedBlock.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(PANEL_EXPAND_MS)
            .setInterpolator(OvershootInterpolator(1.08f))
            .start()
    }

    private fun animateCardCollapse(card: OverlayFlashNoteCardBinding) {
        card.collapsedRow.animate().cancel()
        card.expandedBlock.animate().cancel()
        card.expandedBlock.pivotY = 0f
        card.expandedBlock.animate()
            .alpha(0f)
            .translationY(-dp(10f))
            .scaleY(0.92f)
            .setDuration(PANEL_COLLAPSE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                card.expandedBlock.visibility = View.GONE
                card.expandedBlock.alpha = 1f
                card.expandedBlock.translationY = 0f
                card.expandedBlock.scaleY = 1f
                card.collapsedRow.visibility = View.VISIBLE
                card.collapsedRow.alpha = 0f
                card.collapsedRow.animate()
                    .alpha(1f)
                    .setDuration(PANEL_FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun bindPlayback(note: FlashNote, card: OverlayFlashNoteCardBinding) {
        val hasAudio = note.hasAudio
        card.collapsedPlay.visibility = if (hasAudio) View.VISIBLE else View.GONE
        card.playbackRow.visibility = if (hasAudio) View.VISIBLE else View.GONE
        if (!hasAudio) return
        val state = if (FlashNotePlayer.playingId == note.id) {
            FlashNotePlayer.state
        } else {
            FlashNotePlayer.State.IDLE
        }
        val label = when (state) {
            FlashNotePlayer.State.PLAYING -> app.getString(R.string.flash_note_pause_audio)
            FlashNotePlayer.State.PAUSED -> app.getString(R.string.flash_note_resume_audio)
            FlashNotePlayer.State.IDLE -> app.getString(R.string.flash_note_replay)
        }
        val icon = if (state == FlashNotePlayer.State.PLAYING) "⏸" else "▶"
        card.collapsedPlay.text = icon
        card.playbackIcon.text = icon
        card.playbackLabel.text = label
        val toggle = View.OnClickListener { view ->
            FlashNoteHud.noteInteraction()
            view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            val path = note.audioPath
            if (path.isNullOrBlank()) {
                Toast.makeText(app, R.string.flash_note_play_failed, Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            FlashNotePlayer.toggle(app, note.id, path) { failed ->
                if (failed) {
                    Toast.makeText(app, R.string.flash_note_play_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.collapsedPlay.setOnClickListener(toggle)
        card.playbackRow.setOnClickListener(toggle)
    }

    private fun bindExpandedColors(row: LinearLayout, note: FlashNote) {
        row.visibility = View.VISIBLE
        row.removeAllViews()
        FlashNoteColor.TODO_URGENCY.forEachIndexed { index, color ->
            row.addView(colorDot(row.context, color, index == note.color, lightStroke = false) {
                FlashNoteStore.update(note.copy(color = index))
            })
        }
    }

    private fun bindComposerColors() {
        val row = binding.composerColors
        row.removeAllViews()
        if (composerCategory != FlashNoteCategory.TODO) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        FlashNoteColor.TODO_URGENCY.forEachIndexed { index, color ->
            row.addView(colorDot(row.context, color, index == composerColor, lightStroke = true) {
                composerColor = index
                bindComposerColors()
                bindComposerCategories()
            })
        }
    }

    private fun bindComposerCategories() {
        val row = binding.composerCategories
        row.removeAllViews()
        FlashNoteCategory.entries.forEach { category ->
            val selected = category == composerCategory
            val chipColor = when (category) {
                FlashNoteCategory.IDEA -> FlashNoteColor.IDEA_YELLOW
                FlashNoteCategory.DIARY -> FlashNoteColor.DIARY_SUN
                FlashNoteCategory.TODO -> FlashNoteColor.argb(composerColor, FlashNoteCategory.TODO)
                FlashNoteCategory.SCHEDULE -> 0xFF417C69.toInt()
                FlashNoteCategory.OTHER -> FlashNoteColor.OTHER_SILVER
            }
            val chip = TextView(themed).apply {
                text = app.getString(category.labelRes)
                setTextColor(if (selected) Color.WHITE else 0xFF5F6368.toInt())
                textSize = 12f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f)
                    setColor(if (selected) chipColor else 0x14202124)
                }
                setOnClickListener {
                    composerCategory = category
                    if (category != FlashNoteCategory.TODO) composerColor = 2
                    binding.composerDateButton.visibility =
                        if (category == FlashNoteCategory.SCHEDULE) View.VISIBLE else View.GONE
                    updateSaveButtonLabel()
                    bindComposerColors()
                    bindComposerCategories()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            row.addView(chip, lp)
        }
        binding.composerDateButton.visibility =
            if (composerCategory == FlashNoteCategory.SCHEDULE) View.VISIBLE else View.GONE
        updateSaveButtonLabel()
        bindComposerColors()
    }

    private fun updateSaveButtonLabel() {
        binding.composerSaveButton.setText(
            if (composerCategory == FlashNoteCategory.SCHEDULE) {
                R.string.save_schedule
            } else {
                R.string.save_note
            }
        )
    }

    private fun colorDot(
        context: Context,
        color: Int,
        selected: Boolean,
        lightStroke: Boolean,
        onClick: () -> Unit
    ): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    dp(2),
                    when {
                        selected && lightStroke -> 0xFF202124.toInt()
                        selected -> Color.WHITE
                        else -> 0x33FFFFFF
                    }
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            volumeHoldRecording = false
            val path = recorder.stop()
            recordedPath = path ?: recordedPath
            composerSource = "voice"
            dictatingWithRecorder = false
            updateRecordButton()
            maybeTranscribeAfterRecord(path)
        } else {
            requestMic(PendingMic.RECORD)
        }
    }

    private fun startRecording() {
        stopDictation()
        FlashNotePlayer.stop()
        dictatingWithRecorder = false
        volumeHoldRecording = false
        if (recorder.start()) {
            recordedPath = null
            composerSource = "voice"
        } else {
            Toast.makeText(app, R.string.flash_note_record_failed, Toast.LENGTH_SHORT).show()
        }
        updateRecordButton()
    }

    private fun startVolumeHoldRecording() {
        if (!VolumeChordFlashNote.isChordHolding()) return
        stopDictation()
        FlashNotePlayer.stop()
        dictatingWithRecorder = false
        if (recorder.isRecording) {
            volumeHoldRecording = true
            updateRecordButton()
            return
        }
        if (recorder.start()) {
            recordedPath = null
            composerSource = "voice"
            volumeHoldRecording = true
        } else {
            volumeHoldRecording = false
            Toast.makeText(app, R.string.flash_note_record_failed, Toast.LENGTH_SHORT).show()
        }
        updateRecordButton()
    }

    private fun updateRecordButton() {
        binding.composerRecordButton.setText(
            if (recorder.isRecording && !dictatingWithRecorder) {
                R.string.flash_note_stop_record
            } else {
                R.string.flash_note_record
            }
        )
        binding.composerRecordButton.setTextColor(
            if (recorder.isRecording && !dictatingWithRecorder) 0xFFE53935.toInt() else 0xFF202124.toInt()
        )
        updateDictateButton()
    }

    private fun updateDictateButton() {
        binding.composerDictateButton.setText(
            when {
                asrBusy -> R.string.voice_transcribing
                recorder.isRecording && dictatingWithRecorder -> R.string.flash_note_stop_record
                recognizer != null -> R.string.voice_listening
                else -> R.string.voice_input
            }
        )
    }

    private fun requestMic(reason: PendingMic) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            when (reason) {
                PendingMic.RECORD -> startRecording()
                PendingMic.DICTATE -> startDictation()
                PendingMic.VOLUME_HOLD -> startVolumeHoldRecording()
            }
            return
        }
        pendingMic = reason
        app.startActivity(
            Intent(app, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        )
    }

    private fun startDictation() {
        if (asrBusy) return
        if (recorder.isRecording) {
            val path = recorder.stop()
            recordedPath = path ?: recordedPath
            val wasDictate = dictatingWithRecorder
            dictatingWithRecorder = false
            updateRecordButton()
            if (wasDictate || SenseVoiceAsr.hasModel(app)) {
                maybeTranscribeAfterRecord(path)
            }
            return
        }
        if (SenseVoiceAsr.hasModel(app)) {
            stopDictation()
            FlashNotePlayer.stop()
            if (recorder.start()) {
                recordedPath = null
                composerSource = "voice"
                dictatingWithRecorder = true
                updateRecordButton()
            } else {
                Toast.makeText(app, R.string.flash_note_record_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        Toast.makeText(app, R.string.voice_model_missing, Toast.LENGTH_SHORT).show()
        SenseVoiceModelStore.openInstallHint(app)
        startSystemDictation()
    }

    private fun maybeTranscribeAfterRecord(path: String?) {
        if (path.isNullOrBlank()) return
        if (!SenseVoiceAsr.hasModel(app)) {
            Toast.makeText(app, R.string.voice_model_missing, Toast.LENGTH_SHORT).show()
            SenseVoiceModelStore.openInstallHint(app)
            return
        }
        asrBusy = true
        updateDictateButton()
        SenseVoiceAsr.transcribe(app, path) { text ->
            asrBusy = false
            updateDictateButton()
            if (!text.isNullOrBlank()) {
                mergeComposerText(text)
            } else {
                Toast.makeText(app, R.string.voice_asr_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mergeComposerText(spoken: String) {
        val current = binding.composerInput.text?.toString().orEmpty()
        val merged = listOf(current, spoken).filter { it.isNotBlank() }.joinToString()
        binding.composerInput.setText(merged)
        binding.composerInput.setSelection(merged.length)
    }

    private fun startSystemDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            Toast.makeText(app, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        stopDictation()
        composerSource = "voice"
        recognizer = SpeechRecognizer.createSpeechRecognizer(app).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    updateDictateButton()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    updateDictateButton()
                }
                override fun onError(error: Int) {
                    recognizer = null
                    updateDictateButton()
                    Toast.makeText(app, R.string.voice_failed, Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: android.os.Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!spoken.isNullOrBlank()) mergeComposerText(spoken)
                    recognizer = null
                    updateDictateButton()
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                }
            )
        }
        updateDictateButton()
    }

    private fun stopDictation() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        updateDictateButton()
    }

    private fun saveComposer() {
        if (recorder.isRecording) {
            recordedPath = recorder.stop() ?: recordedPath
            updateRecordButton()
        }
        stopDictation()
        val typed = binding.composerInput.text?.toString()?.trim().orEmpty()
        val text = typed.ifBlank {
            if (!recordedPath.isNullOrBlank()) app.getString(R.string.flash_note_voice_placeholder) else ""
        }
        if (text.isEmpty()) {
            Toast.makeText(app, R.string.flash_note_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // 日程分类：只进日程，不进闪记列表
        if (composerCategory == FlashNoteCategory.SCHEDULE) {
            val noteText = text
            val date = scheduleDate
            Toast.makeText(app, R.string.schedule_parsing, Toast.LENGTH_SHORT).show()
            Thread {
                val (drafts, warn) = ScheduleLlmClient.parseFromFlashNote(noteText, date)
                Handler(Looper.getMainLooper()).post {
                    if (warn != null) {
                        Toast.makeText(app, warn, Toast.LENGTH_SHORT).show()
                    }
                    if (drafts.isEmpty()) {
                        Toast.makeText(app, R.string.schedule_parse_empty, Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    binding.composerInput.setText("")
                    recordedPath = null
                    composerSource = "typed"
                    setComposerVisible(false, animated = true)
                    if (drafts.any { it.needsTime || it.startMinutes == null }) {
                        ScheduleHud.showFillTimes(app, drafts, noteText, date)
                    } else {
                        Thread {
                            val (n, err) = ScheduleLlmClient.commitDrafts(
                                drafts, date, noteText
                            )
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    app,
                                    err ?: app.getString(R.string.schedule_created_count, n),
                                    Toast.LENGTH_LONG
                                ).show()
                                ScheduleHud.show(app)
                                ScheduleHud.refresh()
                                OverlayLayerCoordinator.noteUserOn(OverlayLayerCoordinator.Side.SCHEDULE)
                            }
                        }.start()
                    }
                }
            }.start()
            return
        }
        val colorIndex = if (composerCategory == FlashNoteCategory.TODO) composerColor else 0
        FlashNoteStore.insert(
            FlashNote(
                text = text,
                category = composerCategory,
                source = composerSource,
                scheduleDate = null,
                color = colorIndex,
                audioPath = recordedPath
            )
        )
        Toast.makeText(app, R.string.flash_note_saved, Toast.LENGTH_SHORT).show()
        binding.composerInput.setText("")
        recordedPath = null
        composerSource = "typed"
        setComposerVisible(false, animated = true)
        bindNotes()
    }

    private fun pickDate() {
        val current = scheduleDate
        runCatching {
            DatePickerDialog(
                themed,
                { _, year, month, day ->
                    scheduleDate = LocalDate.of(year, month + 1, day)
                    updateDateLabel()
                },
                current.year,
                current.monthValue - 1,
                current.dayOfMonth
            ).show()
        }
    }

    private fun updateDateLabel() {
        binding.composerDateButton.text = app.getString(R.string.schedule_date_value, scheduleDate.toString())
    }

    private fun hideKeyboard() {
        app.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.composerInput.windowToken, 0)
    }

    private fun dp(value: Int) = (value * app.resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * app.resources.displayMetrics.density

    companion object {
        private const val ENTRANCE_FROM_DP = 56f
        private const val ENTRANCE_STAGGER_MS = 48L
        private const val ENTRANCE_DURATION_MS = 360L
        private const val EXIT_DURATION_MS = 240L
        private const val PANEL_EXPAND_MS = 280L
        private const val PANEL_COLLAPSE_MS = 200L
        private const val PANEL_FADE_MS = 140L
    }
}
