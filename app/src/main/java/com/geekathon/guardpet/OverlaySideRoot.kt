package com.geekathon.guardpet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 左右悬浮窗根布局：任意触摸（含子 View 消费、无业务反馈）都算「点了这一侧」。
 * 回调 post 出去，避免在 dispatch 里同步改 WindowManager。
 */
class OverlaySideRoot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onAnyTouchDown: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val cb = onAnyTouchDown
            if (cb != null) {
                post { cb() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
