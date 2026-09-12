package com.geekathon.guardpet

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.view.isVisible

/** 桌宠旁短时语音气泡。 */
class PetSpeechBubbleOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private val root = LayoutInflater.from(
        ContextThemeWrapper(service, R.style.Theme_DesktopPet)
    ).inflate(R.layout.overlay_pet_speech_bubble, null)
    private val textView = root.findViewById<TextView>(R.id.petSpeechBubbleText)
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private val hideRunnable = Runnable { dismissAnimated() }

    fun show(message: String, anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int) {
        textView.text = message
        handler.removeCallbacks(hideRunnable)
        val metrics = service.resources.displayMetrics
        val density = metrics.density
        val maxBubbleW = (220 * density).toInt()
        if (!attached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            windowManager.addView(root, params)
            attached = true
        }
        root.isVisible = true
        root.alpha = 0f
        root.scaleX = 0.86f
        root.scaleY = 0.86f
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(maxBubbleW, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val bw = root.measuredWidth.coerceAtLeast((120 * density).toInt())
        val bh = root.measuredHeight.coerceAtLeast((48 * density).toInt())
        val params = root.layoutParams as WindowManager.LayoutParams
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        // 气泡在桌宠上方居中，略偏右一点更像在说话
        params.x = (anchorX + (anchorWidth - bw) / 2)
            .coerceIn(8, (metrics.widthPixels - bw - 8).coerceAtLeast(8))
        params.y = (anchorY - bh - (6 * density).toInt())
            .coerceIn(8, (metrics.heightPixels - bh - 8).coerceAtLeast(8))
        windowManager.updateViewLayout(root, params)

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(root, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(root, "scaleX", 0.86f, 1f),
                ObjectAnimator.ofFloat(root, "scaleY", 0.86f, 1f)
            )
            duration = 280
            interpolator = OvershootInterpolator(1.1f)
            start()
        }
        handler.postDelayed(hideRunnable, DISPLAY_MS)
    }

    private fun dismissAnimated() {
        if (!attached) return
        root.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(180)
            .withEndAction { close() }
            .start()
    }

    fun close() {
        handler.removeCallbacks(hideRunnable)
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    companion object {
        private const val DISPLAY_MS = 3_600L
    }
}
