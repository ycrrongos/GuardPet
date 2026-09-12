package com.geekathon.guardpet

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 全屏透明宿主：只有点到子 View（左日程 / 右闪记）才消费触摸，空白处放行给下层 App。
 */
class PassthroughFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val hit = Rect()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!touchHitsVisibleChild(ev)) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun touchHitsVisibleChild(ev: MotionEvent): Boolean {
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE || child.alpha < 0.02f) continue
            child.getHitRect(hit)
            if (hit.contains(x, y)) return true
        }
        return false
    }
}
