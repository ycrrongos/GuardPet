package com.geekathon.guardpet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 桌宠旁 16:9 初始框选窗；长边=屏幕短边。可自由拖角/边改尺寸（不锁比例）。
 * 注意：不要用裸 Service 上下文建 Material/AppCompat 控件（会 Inflate/主题崩溃）。
 */
class TextCropOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private val themed = ContextThemeWrapper(service, R.style.Theme_DesktopPet)
    private var root: FrameLayout? = null
    private var attached = false

    fun show(anchorX: Int, anchorY: Int, anchorW: Int, anchorH: Int) {
        if (attached) close()
        val metrics = service.resources.displayMetrics
        val density = metrics.density
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val longSide = shortSide
        val shortFrame = (longSide * 9f / 16f).toInt()
            .coerceAtLeast((120 * density).toInt())
        val frameW = longSide.coerceAtMost(metrics.widthPixels)
        val frameH = shortFrame.coerceAtMost(metrics.heightPixels)
        val petCx = anchorX + anchorW / 2
        val petCy = anchorY + anchorH / 2
        val left = (petCx - frameW / 2).coerceIn(0, (metrics.widthPixels - frameW).coerceAtLeast(0))
        val top = (petCy - frameH / 2).coerceIn(0, (metrics.heightPixels - frameH).coerceAtLeast(0))

        val container = FrameLayout(themed)
        val crop = CropFrameView(themed).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setFrame(left, top, left + frameW, top + frameH)
        }
        container.addView(
            crop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val cancel = makeBarButton(
            label = service.getString(R.string.bigbang_crop_cancel),
            filled = false
        ) {
            close()
            service.onTextCropCancelled()
        }
        val confirm = makeBarButton(
            label = service.getString(R.string.bigbang_crop_confirm),
            filled = true
        ) {
            val r = crop.frameRect()
            close()
            service.onTextCropConfirmed(r)
        }
        container.addView(
            cancel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins((16 * density).toInt(), 0, 0, (28 * density).toInt())
            }
        )
        container.addView(
            confirm,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, (16 * density).toInt(), (28 * density).toInt())
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(container, params)
        root = container
        attached = true
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(220).start()
    }

    fun close() {
        if (!attached) return
        val view = root
        attached = false
        root = null
        if (view != null) {
            runCatching { windowManager.removeView(view) }
        }
    }

    fun isShowing(): Boolean = attached

    private fun makeBarButton(label: String, filled: Boolean, onClick: () -> Unit): Button {
        val density = themed.resources.displayMetrics.density
        val primary = ContextCompat.getColor(themed, R.color.brand_primary)
        val bg = GradientDrawable().apply {
            cornerRadius = 22f * density
            if (filled) {
                setColor(primary)
            } else {
                setColor(Color.WHITE)
                setStroke((1.5f * density).toInt(), primary)
            }
        }
        return Button(themed).apply {
            text = label
            setTextColor(if (filled) Color.WHITE else primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = bg
            isAllCaps = false
            minHeight = (44 * density).toInt()
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
            setOnClickListener { onClick() }
        }
    }
}

private class CropFrameView(context: android.content.Context) : View(context) {
    private val frame = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val handleRadius = 10f * resources.displayMetrics.density
    private val minSize = 80f * resources.displayMetrics.density
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    fun setFrame(l: Int, t: Int, r: Int, b: Int) {
        frame.set(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        invalidate()
    }

    fun frameRect(): android.graphics.Rect =
        android.graphics.Rect(
            frame.left.toInt(),
            frame.top.toInt(),
            frame.right.toInt(),
            frame.bottom.toInt()
        )

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(frame, 12f, 12f, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawRoundRect(frame, 12f, 12f, borderPaint)
        listOf(
            frame.left to frame.top,
            frame.right to frame.top,
            frame.left to frame.bottom,
            frame.right to frame.bottom
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                mode = hitMode(event.x, event.y)
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                when (mode) {
                    Mode.MOVE -> {
                        frame.offset(dx, dy)
                        clampFrame()
                    }
                    Mode.RESIZE_TL -> {
                        frame.left = min(frame.right - minSize, frame.left + dx)
                        frame.top = min(frame.bottom - minSize, frame.top + dy)
                        clampFrame()
                    }
                    Mode.RESIZE_TR -> {
                        frame.right = max(frame.left + minSize, frame.right + dx)
                        frame.top = min(frame.bottom - minSize, frame.top + dy)
                        clampFrame()
                    }
                    Mode.RESIZE_BL -> {
                        frame.left = min(frame.right - minSize, frame.left + dx)
                        frame.bottom = max(frame.top + minSize, frame.bottom + dy)
                        clampFrame()
                    }
                    Mode.RESIZE_BR -> {
                        frame.right = max(frame.left + minSize, frame.right + dx)
                        frame.bottom = max(frame.top + minSize, frame.bottom + dy)
                        clampFrame()
                    }
                    Mode.NONE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitMode(x: Float, y: Float): Mode {
        val slop = handleRadius * 2.2f
        fun near(px: Float, py: Float) = abs(x - px) <= slop && abs(y - py) <= slop
        return when {
            near(frame.left, frame.top) -> Mode.RESIZE_TL
            near(frame.right, frame.top) -> Mode.RESIZE_TR
            near(frame.left, frame.bottom) -> Mode.RESIZE_BL
            near(frame.right, frame.bottom) -> Mode.RESIZE_BR
            frame.contains(x, y) -> Mode.MOVE
            else -> Mode.NONE
        }
    }

    private fun clampFrame() {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (frame.width() < minSize) frame.right = frame.left + minSize
        if (frame.height() < minSize) frame.bottom = frame.top + minSize
        if (frame.left < 0) frame.offset(-frame.left, 0f)
        if (frame.top < 0) frame.offset(0f, -frame.top)
        if (frame.right > w) frame.offset(w - frame.right, 0f)
        if (frame.bottom > h) frame.offset(0f, h - frame.bottom)
    }
}
