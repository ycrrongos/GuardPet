package com.geekathon.guardpet

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import dev.pranav.reef.util.AndroidUtilities.formatTime
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlin.math.roundToInt

class FocusTimerOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = AnalogTimerView(service)
    private val pullTab = PullTabView(service)
    private val hint = TextView(service).apply {
        textSize = 13f
        setTextColor(0xCC202124.toInt())
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }
    private val clockColumn = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Color.TRANSPARENT.toDrawable()
        addView(clock)
        addView(hint)
    }
    private var attached = false
    private var selectedMinutes = 25f
    private var running = false
    private var paused = false
    private var endAtElapsed = 0L
    private var remainingMs = 25 * 60_000L
    private lateinit var tabParams: WindowManager.LayoutParams
    private val tick = object : Runnable {
        override fun run() {
            if (!running || paused) return
            remainingMs = (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            render(animateHand = false)
            if (remainingMs <= 0L) {
                completeTimer()
                return
            }
            handler.postDelayed(this, 80L)
        }
    }

    init {
        clock.minutes = selectedMinutes
        pullTab.minutes = selectedMinutes
        clock.onMinutesChanged = { setMinutes(it, fromClock = true) }
        pullTab.onMinutesChanged = { setMinutes(it, fromClock = false) }
        pullTab.onVisualChanged = { syncTabWindow() }
        clock.onTap = { onStartPause() }
        pullTab.onStartToggle = { onStartPause() }
        clock.onLongPressClose = { cancelAndClose() }
        pullTab.onClose = { cancelAndClose() }
        render(animateHand = false)
    }

    fun show() {
        if (attached) return
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        tabParams = WindowManager.LayoutParams(
            pullTab.preferredWidth(),
            pullTab.preferredHeight(),
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val clockParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(pullTab, tabParams)
        runCatching { windowManager.addView(clockColumn, clockParams) }.onFailure {
            runCatching { windowManager.removeView(pullTab) }
            throw it
        }
        attached = true
        render(animateHand = false)
    }

    fun close() {
        if (!attached) return
        handler.removeCallbacks(tick)
        if (running) setFocusBlocking(false)
        running = false
        paused = false
        clock.adjustable = true
        pullTab.adjustable = true
        runCatching { windowManager.removeView(pullTab) }
        runCatching { windowManager.removeView(clockColumn) }
        attached = false
    }

    private fun setMinutes(minutes: Float, fromClock: Boolean) {
        if (running) return
        selectedMinutes = minutes.coerceIn(1f, 60f)
        remainingMs = selectedMinutes.roundToInt() * 60_000L
        if (fromClock) {
            pullTab.minutes = selectedMinutes
        } else {
            clock.minutes = selectedMinutes
        }
        render(animateHand = fromClock)
    }

    private fun onStartPause() {
        when {
            running && !paused -> {
                paused = true
                handler.removeCallbacks(tick)
                remainingMs = (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                setFocusBlocking(false)
                render(animateHand = false)
            }
            running && paused -> {
                paused = false
                endAtElapsed = SystemClock.elapsedRealtime() + remainingMs
                setFocusBlocking(true)
                handler.post(tick)
                render(animateHand = false)
            }
            else -> startTimer()
        }
    }

    private fun startTimer() {
        if (!isPrefsInitialized) {
            Toast.makeText(service, R.string.focus_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        remainingMs = selectedMinutes.roundToInt() * 60_000L
        endAtElapsed = SystemClock.elapsedRealtime() + remainingMs
        running = true
        paused = false
        clock.adjustable = false
        pullTab.adjustable = false
        setFocusBlocking(true)
        HabitRewardTracker.onFocusSessionStarted()
        handler.removeCallbacks(tick)
        handler.post(tick)
        render(animateHand = false)
    }

    private fun cancelAndClose() {
        handler.removeCallbacks(tick)
        running = false
        paused = false
        setFocusBlocking(false)
        close()
    }

    private fun completeTimer() {
        handler.removeCallbacks(tick)
        running = false
        paused = false
        clock.adjustable = true
        pullTab.adjustable = true
        remainingMs = selectedMinutes.roundToInt() * 60_000L
        clock.setMinutesAnimated(selectedMinutes)
        pullTab.minutes = selectedMinutes
        setFocusBlocking(false)
        HabitRewardTracker.onFocusSessionCompleted(service)
        Toast.makeText(service, R.string.focus_complete, Toast.LENGTH_LONG).show()
        render(animateHand = false)
    }

    private fun setFocusBlocking(enabled: Boolean) {
        if (!isPrefsInitialized) return
        runCatching {
            prefs.edit {
                putBoolean("focus_mode", enabled)
                putBoolean("pomodoro_mode", false)
                putBoolean("strict_mode", false)
                if (enabled) {
                    putLong("focus_time", remainingMs)
                }
            }
        }
    }

    private fun render(animateHand: Boolean) {
        val shownMinutes = if (running) remainingMs / 60_000f else selectedMinutes
        if (running) {
            clock.minutes = shownMinutes
            pullTab.minutes = shownMinutes.coerceAtLeast(1f)
        } else if (animateHand) {
            clock.setMinutesAnimated(selectedMinutes)
        }
        clock.timeText = if (running) formatTime(remainingMs) else formatClock(selectedMinutes)
        hint.text = when {
            running && paused -> service.getString(R.string.focus_paused_hint)
            running -> service.getString(R.string.focus_running_hint)
            else -> service.getString(R.string.focus_setup_hint)
        }
        clock.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        clock.contentDescription = clock.timeText
        syncTabWindow()
    }

    private fun syncTabWindow() {
        if (!attached || !::tabParams.isInitialized) return
        tabParams.width = pullTab.preferredWidth()
        tabParams.height = pullTab.preferredHeight()
        tabParams.x = 0
        tabParams.y = 0
        runCatching { windowManager.updateViewLayout(pullTab, tabParams) }
    }

    private fun formatClock(minutes: Float): String {
        val total = (minutes * 60f).roundToInt().coerceIn(60, 3600)
        val mm = total / 60
        val ss = total % 60
        return "%02d:%02d".format(mm, ss)
    }
}
