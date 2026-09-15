package com.geekathon.guardpet

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 左右悬浮窗共用**一个** TYPE_APPLICATION_OVERLAY。
 * 切层只用 elevation / bringToFront，永不为 z-order 做 removeView+addView。
 */
object DualOverlayShell {
    private const val ELEVATION_FRONT = 24f
    private const val ELEVATION_BACK = 4f

    @Volatile
    private var app: Context? = null

    private var host: PassthroughFrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var flashPanel: View? = null
    private var schedulePanel: View? = null

    @Volatile
    private var front: OverlayLayerCoordinator.Side = OverlayLayerCoordinator.Side.FLASH

    private val wm: WindowManager?
        get() = app?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    fun attachFlash(context: Context, panel: View) {
        ensureHost(context)
        val h = host ?: return
        if (flashPanel === panel && panel.parent === h) {
            panel.visibility = View.VISIBLE
            panel.alpha = 1f
            applyFrontElevation()
            return
        }
        detachFlashViewOnly()
        flashPanel = panel
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = topMarginPx(context)
        }
        if (panel.parent != null) {
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        }
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        panel.setTag(R.id.overlay_side_tag, OverlayLayerCoordinator.Side.FLASH)
        h.addView(panel, lp)
        applyFrontElevation()
    }

    fun attachSchedule(context: Context, panel: View) {
        ensureHost(context)
        val h = host ?: return
        if (schedulePanel === panel && panel.parent === h) {
            panel.visibility = View.VISIBLE
            panel.alpha = 1f
            applyFrontElevation()
            return
        }
        detachScheduleViewOnly()
        schedulePanel = panel
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = topMarginPx(context)
        }
        if (panel.parent != null) {
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        }
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        panel.setTag(R.id.overlay_side_tag, OverlayLayerCoordinator.Side.SCHEDULE)
        h.addView(panel, lp)
        applyFrontElevation()
    }

    fun detachFlash(panel: View) {
        if (flashPanel !== panel) return
        detachFlashViewOnly()
        maybeDestroyHost()
    }

    fun detachSchedule(panel: View) {
        if (schedulePanel !== panel) return
        detachScheduleViewOnly()
        maybeDestroyHost()
    }

    /** 切层：只改子 View 叠放，不动 WindowManager。 */
    fun bringSideToFront(side: OverlayLayerCoordinator.Side) {
        front = side
        applyFrontElevation()
    }

    fun applyFocus(focusable: Boolean) {
        val params = windowParams ?: return
        val h = host ?: return
        val w = wm ?: return
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.flags = flags
        runCatching { w.updateViewLayout(h, params) }
    }

    fun setHostVisible(visible: Boolean) {
        val h = host ?: return
        h.visibility = if (visible) View.VISIBLE else View.GONE
        h.alpha = if (visible) 1f else 0f
    }

    fun setPanelVisible(side: OverlayLayerCoordinator.Side, visible: Boolean) {
        val panel = when (side) {
            OverlayLayerCoordinator.Side.FLASH -> flashPanel
            OverlayLayerCoordinator.Side.SCHEDULE -> schedulePanel
        } ?: return
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        panel.alpha = if (visible) 1f else 0f
    }

    private fun applyFrontElevation() {
        val flash = flashPanel
        val schedule = schedulePanel
        when (front) {
            OverlayLayerCoordinator.Side.FLASH -> {
                schedule?.elevation = ELEVATION_BACK
                schedule?.translationZ = ELEVATION_BACK
                flash?.elevation = ELEVATION_FRONT
                flash?.translationZ = ELEVATION_FRONT
                flash?.bringToFront()
            }
            OverlayLayerCoordinator.Side.SCHEDULE -> {
                flash?.elevation = ELEVATION_BACK
                flash?.translationZ = ELEVATION_BACK
                schedule?.elevation = ELEVATION_FRONT
                schedule?.translationZ = ELEVATION_FRONT
                schedule?.bringToFront()
            }
        }
        host?.invalidate()
    }

    private fun ensureHost(context: Context) {
        val application = context.applicationContext
        app = application
        if (host != null) return
        val w = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = PassthroughFrameLayout(application)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        runCatching { w.addView(root, params) }
            .onFailure {
                host = null
                windowParams = null
                return
            }
        host = root
        windowParams = params
    }

    private fun detachFlashViewOnly() {
        val panel = flashPanel ?: return
        val h = host
        if (h != null && panel.parent === h) {
            runCatching { h.removeView(panel) }
        }
        flashPanel = null
    }

    private fun detachScheduleViewOnly() {
        val panel = schedulePanel ?: return
        val h = host
        if (h != null && panel.parent === h) {
            runCatching { h.removeView(panel) }
        }
        schedulePanel = null
    }

    private fun maybeDestroyHost() {
        if (flashPanel != null || schedulePanel != null) return
        val h = host ?: return
        val w = wm
        runCatching { w?.removeView(h) }
        host = null
        windowParams = null
        front = OverlayLayerCoordinator.Side.FLASH
    }

    private fun topMarginPx(context: Context): Int =
        (48 * context.resources.displayMetrics.density).toInt()
}
