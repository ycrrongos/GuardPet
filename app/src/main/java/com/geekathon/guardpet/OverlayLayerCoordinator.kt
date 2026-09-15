package com.geekathon.guardpet

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 左日程 / 右闪记 叠层与临时隐藏。
 *
 * 两侧同属 [DualOverlayShell] 一个 WM 窗口；切层只改 elevation / bringToFront，
 * **绝不**为 z-order 做 removeView+addView（会闪、丢 IME、点不动）。
 */
object OverlayLayerCoordinator {
    enum class Side { FLASH, SCHEDULE }

    private const val SWITCH_WATCHDOG_MS = 1_200L

    @Volatile
    private var front: Side = Side.FLASH

    @Volatile
    private var switching: Boolean = false

    @Volatile
    private var pending: Side? = null

    @Volatile
    private var switchStartedAt = 0L

    @Volatile
    var hiddenForTimePicker: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        if (!switching) return@Runnable
        // 收边/展开动画 onEnd 丢失时会死锁，强制收尾
        switching = false
        DualOverlayShell.bringSideToFront(front)
        drainPending()
    }

    fun currentFront(): Side = front

    fun noteUserOn(side: Side) {
        if (hiddenForTimePicker) return
        if (switching && SystemClock.elapsedRealtime() - switchStartedAt > SWITCH_WATCHDOG_MS) {
            mainHandler.removeCallbacks(watchdog)
            switching = false
        }
        if (!switching && front == side) return
        mainHandler.post { bringToFront(side, animated = true) }
    }

    fun bringToFront(side: Side, animated: Boolean = true) {
        if (hiddenForTimePicker) return
        if (switching) {
            if (SystemClock.elapsedRealtime() - switchStartedAt > SWITCH_WATCHDOG_MS) {
                mainHandler.removeCallbacks(watchdog)
                switching = false
            } else {
                pending = side
                return
            }
        }
        if (front == side) {
            DualOverlayShell.bringSideToFront(side)
            return
        }
        if (!animated) {
            front = side
            DualOverlayShell.bringSideToFront(side)
            return
        }
        switching = true
        switchStartedAt = SystemClock.elapsedRealtime()
        pending = null
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, SWITCH_WATCHDOG_MS)
        val demote = if (side == Side.FLASH) Side.SCHEDULE else Side.FLASH
        val finish = once {
            switching = false
            mainHandler.removeCallbacks(watchdog)
            DualOverlayShell.bringSideToFront(front)
            drainPending()
        }
        retract(demote) {
            front = side
            DualOverlayShell.bringSideToFront(side)
            expand(demote, finish)
        }
    }

    fun hideForTimePicker() {
        if (hiddenForTimePicker) return
        hiddenForTimePicker = true
        DualOverlayShell.setHostVisible(false)
    }

    fun restoreAfterTimePicker() {
        if (!hiddenForTimePicker) return
        hiddenForTimePicker = false
        DualOverlayShell.setHostVisible(true)
        DualOverlayShell.bringSideToFront(front)
    }

    private fun drainPending() {
        val next = pending ?: return
        pending = null
        if (next != front) {
            bringToFront(next, animated = true)
        }
    }

    private fun retract(side: Side, onEnd: () -> Unit) {
        when (side) {
            Side.FLASH -> FlashNoteHud.retractToEdge(onEnd)
            Side.SCHEDULE -> ScheduleHud.retractToEdge(onEnd)
        }
    }

    private fun expand(side: Side, onEnd: () -> Unit) {
        when (side) {
            Side.FLASH -> FlashNoteHud.expandFromEdge(onEnd)
            Side.SCHEDULE -> ScheduleHud.expandFromEdge(onEnd)
        }
    }

    private fun once(block: () -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        return {
            if (done.compareAndSet(false, true)) {
                mainHandler.post(block)
            }
        }
    }
}
