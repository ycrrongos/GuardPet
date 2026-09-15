package com.geekathon.guardpet

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 全屏透明宿主：只有点到子 View（左日程 / 右闪记）才消费触摸，空白处放行给下层 App。
 *
 * 重叠区：**优先上层**。仅当触点完全落在下层可见区域（上层 hitRect 不含该点）时才点下层并切层。
 * 禁止按几何中心选侧——否则点上层控件会误抬下层再弹回。
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
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (resolveDownTarget(ev) == null) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * @return 应接收本次 DOWN 的子面板；null 表示放行给下层 App。
     */
    private fun resolveDownTarget(ev: MotionEvent): View? {
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        val frontSide = OverlayLayerCoordinator.currentFront()
        var frontHit: View? = null
        var backHit: View? = null
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE || child.alpha < 0.02f) continue
            child.getHitRect(hit)
            if (!hit.contains(x, y)) continue
            val side = child.getTag(R.id.overlay_side_tag) as? OverlayLayerCoordinator.Side
            if (side == frontSide) {
                frontHit = child
            } else {
                backHit = child
            }
        }
        // 上层盖住的区域一律给上层；只有上层没盖到的点才给下层
        val target = frontHit ?: backHit ?: return null
        if (indexOfChild(target) != childCount - 1) {
            target.bringToFront()
        }
        // 仅点到下层露出区域时才请求切层；点上层不要触发收边动画
        val side = target.getTag(R.id.overlay_side_tag) as? OverlayLayerCoordinator.Side
        if (side != null && side != frontSide) {
            OverlayLayerCoordinator.noteUserOn(side)
        }
        return target
    }
}
