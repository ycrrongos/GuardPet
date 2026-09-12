package com.geekathon.guardpet

/**
 * 左日程 / 右闪记 叠层与临时隐藏。
 *
 * 两侧同属 [DualOverlayShell] 一个 WM 窗口；切层只改 elevation / bringToFront，
 * **绝不**为 z-order 做 removeView+addView（会闪、丢 IME、点不动）。
 */
object OverlayLayerCoordinator {
    enum class Side { FLASH, SCHEDULE }

    @Volatile
    private var front: Side = Side.FLASH

    @Volatile
    private var switching: Boolean = false

    @Volatile
    private var pending: Side? = null

    @Volatile
    var hiddenForTimePicker: Boolean = false
        private set

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun noteUserOn(side: Side) {
        if (hiddenForTimePicker) return
        if (!switching && front == side) return
        mainHandler.post { bringToFront(side, animated = true) }
    }

    fun bringToFront(side: Side, animated: Boolean = true) {
        if (hiddenForTimePicker) return
        if (switching) {
            pending = side
            return
        }
        if (front == side) return
        if (!animated) {
            front = side
            DualOverlayShell.bringSideToFront(side)
            return
        }
        switching = true
        pending = null
        val demote = if (side == Side.FLASH) Side.SCHEDULE else Side.FLASH
        retract(demote) {
            front = side
            DualOverlayShell.bringSideToFront(side)
            expand(demote) {
                switching = false
                drainPending()
            }
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
}
