package com.geekathon.guardpet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Analog 60-minute kitchen timer with a drop shadow and digital time on the face. */
class AnalogTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var minutes: Float = 25f
        set(value) {
            field = value.coerceIn(0f, 60f)
            invalidate()
        }
    var timeText: String = "25:00"
        set(value) {
            field = value
            invalidate()
        }
    var adjustable: Boolean = true
    var onMinutesChanged: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onLongPressClose: (() -> Unit)? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(22f), 0f, dp(10f), 0x3A000000)
    }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF7F7F7.toInt()
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8B8B8.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C2C2C.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() }
    private val hubRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE24B4B.toInt() }
    private val bounds = RectF()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { longPressFired = true; onLongPressClose?.invoke() }
    private var longPressFired = false
    private var draggingHand = false
    private var downX = 0f
    private var downY = 0f
    private var handAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMinutesAnimated(target: Float) {
        val next = target.coerceIn(0f, 60f)
        handAnimator?.cancel()
        if (kotlin.math.abs(next - minutes) < 0.08f) {
            minutes = next
            return
        }
        handAnimator = ValueAnimator.ofFloat(minutes, next).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { minutes = it.animatedValue as Float }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(268f).toInt()
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(18f)
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius, shadowPaint)
        canvas.drawCircle(cx, cy, radius, bezelPaint)
        canvas.drawCircle(cx, cy, radius - dp(10f), facePaint)

        val inner = radius - dp(18f)
        for (i in 0 until 60) {
            val angle = Math.toRadians(i * 6.0 - 90.0)
            val major = i % 5 == 0
            tickPaint.strokeWidth = if (major) dp(2.2f) else dp(1.2f)
            tickPaint.color = if (major) 0xFF8D8D8D.toInt() else 0xFFC9C9C9.toInt()
            val tickLen = if (major) dp(11f) else dp(6f)
            val x1 = cx + cos(angle).toFloat() * inner
            val y1 = cy + sin(angle).toFloat() * inner
            val x2 = cx + cos(angle).toFloat() * (inner - tickLen)
            val y2 = cy + sin(angle).toFloat() * (inner - tickLen)
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        labelPaint.textSize = dp(16f)
        canvas.drawText("60", cx, cy - inner + dp(28f), labelPaint)
        canvas.drawText("15", cx + inner - dp(22f), cy + dp(6f), labelPaint)

        timePaint.textSize = dp(22f)
        canvas.drawText(timeText, cx, cy + dp(42f), timePaint)

        val shown = if (minutes <= 0f) 0f else minutes
        val handAngle = Math.toRadians(shown * 6.0 - 90.0)
        handPaint.strokeWidth = dp(7f)
        val handLen = inner - dp(28f)
        canvas.drawLine(
            cx,
            cy,
            cx + cos(handAngle).toFloat() * handLen,
            cy + sin(handAngle).toFloat() * handLen,
            handPaint
        )
        canvas.drawCircle(cx, cy, dp(8.5f), hubPaint)
        canvas.drawCircle(cx, cy, dp(3.6f), hubRedPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                draggingHand = false
                longPressFired = false
                mainHandler.postDelayed(longPress, 650L)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > dp(8f) * dp(8f)) {
                    mainHandler.removeCallbacks(longPress)
                    if (adjustable) {
                        draggingHand = true
                        handAnimator?.cancel()
                        val next = minutesFromTouch(event.x, event.y).toFloat()
                        minutes = next
                        onMinutesChanged?.invoke(next)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPress)
                if (!longPressFired && !draggingHand && event.actionMasked == MotionEvent.ACTION_UP) {
                    onTap?.invoke()
                }
                draggingHand = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun minutesFromTouch(x: Float, y: Float): Int {
        val cx = width / 2f
        val cy = height / 2f
        var deg = Math.toDegrees(atan2((x - cx).toDouble(), -(y - cy).toDouble()))
        if (deg < 0) deg += 360.0
        var value = (deg / 6.0).roundToInt()
        if (value == 0) value = 60
        return value.coerceIn(1, 60)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
