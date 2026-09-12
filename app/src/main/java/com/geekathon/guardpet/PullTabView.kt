package com.geekathon.guardpet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Top-left hanging pull-tab. Dragging stretches the strap and sets 1–60 minutes. */
class PullTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var minutes: Float = 25f
        set(value) {
            val next = value.coerceIn(1f, 60f)
            if (field == next) return
            field = next
            requestLayout()
            invalidate()
            onVisualChanged?.invoke()
        }
    var adjustable: Boolean = true
    var onMinutesChanged: ((Float) -> Unit)? = null
    var onStartToggle: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onVisualChanged: (() -> Unit)? = null

    private val strapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA3C0A0.toInt() }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = dp(1.2f)
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(9f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(8f), 0f, dp(2f), 0x28000000)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D0D0.toInt() }
    private val triangle = Path()
    private val strap = RectF()
    private val grip = RectF()
    private var downY = 0f
    private var downMinutes = 25f
    private var dragged = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { onClose?.invoke() }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun preferredWidth(): Int = dp(56f).toInt()

    fun preferredHeight(): Int {
        val minH = dp(112f)
        val extra = dp(208f) * ((minutes - 1f) / 59f)
        return (minH + extra).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(preferredWidth(), preferredHeight())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val strapW = dp(28f)
        val strapLeft = (w - strapW) / 2f
        val ringCy = height - dp(34f)
        val gripTop = ringCy - dp(38f)
        strap.set(strapLeft, -dp(12f), strapLeft + strapW, gripTop + dp(18f))
        canvas.drawRoundRect(strap, dp(8f), dp(8f), strapPaint)
        val tickCount = (8 + minutes / 4f).toInt().coerceIn(8, 22)
        for (i in 1..tickCount) {
            val y = strap.top + dp(10f) + i * (strap.height() - dp(28f)) / tickCount
            val len = if (i % 3 == 0) dp(10f) else dp(6f)
            canvas.drawLine(strap.right - dp(3f) - len, y, strap.right - dp(3f), y, tickPaint)
        }
        grip.set(dp(6f), gripTop, w - dp(6f), gripTop + dp(28f))
        canvas.drawRoundRect(grip, dp(14f), dp(14f), shadowPaint)
        canvas.drawRoundRect(grip, dp(14f), dp(14f), whitePaint)
        triangle.reset()
        val tx = w / 2f
        val ty = grip.centerY() + dp(2f)
        triangle.moveTo(tx, ty + dp(5f))
        triangle.lineTo(tx - dp(6f), ty - dp(4f))
        triangle.lineTo(tx + dp(6f), ty - dp(4f))
        triangle.close()
        canvas.drawPath(triangle, arrowPaint)
        canvas.drawCircle(w / 2f, ringCy, dp(16f), shadowPaint)
        canvas.drawCircle(w / 2f, ringCy, dp(16f), ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pxPerMinute = dp(208f) / 59f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                downMinutes = minutes
                dragged = false
                mainHandler.postDelayed(longPress, 650L)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downY
                if (kotlin.math.abs(dy) > dp(4f)) {
                    mainHandler.removeCallbacks(longPress)
                    dragged = true
                    if (adjustable) {
                        val next = (downMinutes + dy / pxPerMinute).coerceIn(1f, 60f)
                        if (next != minutes) {
                            minutes = next
                            onMinutesChanged?.invoke(next)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPress)
                if (!dragged && event.actionMasked == MotionEvent.ACTION_UP) {
                    onStartToggle?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
