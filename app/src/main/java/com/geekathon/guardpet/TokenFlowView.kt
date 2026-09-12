package com.geekathon.guardpet

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Wrap of token chips with Smartisan/Nova-style drag-to-select; 强制换行 + 标点色区分。 */
class TokenFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val tokens = mutableListOf<String>()
    /** chip 下标 → tokens 下标（跳过 LINE_BREAK） */
    private val chipTokenIndex = mutableListOf<Int>()
    private val selected = sortedSetOf<Int>()
    private val gap = (6 * resources.displayMetrics.density).toInt()
    private val rowGap = (10 * resources.displayMetrics.density).toInt()
    private val chipPaddingH = (8 * resources.displayMetrics.density).toInt()
    private val chipPaddingV = (6 * resources.displayMetrics.density).toInt()
    private val punctPaddingH = (6 * resources.displayMetrics.density).toInt()
    private val slop = (8 * resources.displayMetrics.density).toInt()
    private var dragStartIndex = -1
    private var dragging = false
    private var dragDeselect = false
    private val dragSnapshot = sortedSetOf<Int>()
    private var downX = 0f
    private var downY = 0f
    var onSelectionChanged: (() -> Unit)? = null

    fun setTokens(values: List<String>, animateEntrance: Boolean = false) {
        tokens.clear()
        tokens += values
        selected.clear()
        chipTokenIndex.clear()
        removeAllViews()
        values.forEachIndexed { tokenIndex, token ->
            if (TextTokenizer.isLineBreak(token)) return@forEachIndexed
            chipTokenIndex.add(tokenIndex)
            addView(createChip(token))
        }
        requestLayout()
        onSelectionChanged?.invoke()
        if (animateEntrance) {
            post { playEntrance() }
        }
    }

    private fun playEntrance() {
        val density = resources.displayMetrics.density
        for (index in 0 until childCount) {
            val child = getChildAt(index) ?: continue
            child.alpha = 0f
            child.scaleX = 0.82f
            child.scaleY = 0.82f
            child.translationY = 10f * density
            child.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay((index * 18L).coerceAtMost(360L))
                .setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun createChip(text: String): TextView {
        val punct = TextTokenizer.isPunctuationToken(text)
        return TextView(context).apply {
            this.text = text
            textSize = if (punct) 14.5f else 15.5f
            val padH = if (punct) punctPaddingH else chipPaddingH
            setPadding(padH, chipPaddingV, padH, chipPaddingV)
            gravity = Gravity.CENTER
            tag = if (punct) TAG_PUNCT else TAG_WORD
            applyChipStyle(this, selected = false)
            isClickable = false
            isFocusable = false
            elevation = 1.2f * resources.displayMetrics.density
        }
    }

    private fun applyChipStyle(chip: TextView, selected: Boolean) {
        val punct = chip.tag == TAG_PUNCT
        when {
            selected && punct -> {
                chip.setBackgroundResource(R.drawable.token_bg_punct_selected)
                chip.setTextColor(ContextCompat.getColor(context, R.color.md3_on_primary))
            }
            selected -> {
                chip.setBackgroundResource(R.drawable.token_bg_selected)
                chip.setTextColor(ContextCompat.getColor(context, R.color.md3_on_primary))
            }
            punct -> {
                chip.setBackgroundResource(R.drawable.token_bg_punct)
                chip.setTextColor(ContextCompat.getColor(context, R.color.token_punct_text))
            }
            else -> {
                chip.setBackgroundResource(R.drawable.token_bg)
                chip.setTextColor(ContextCompat.getColor(context, R.color.md3_on_surface))
            }
        }
    }

    fun selectedText(): String = TextTokenizer.joinSelected(tokens, selected)

    fun hasSelection(): Boolean = selected.isNotEmpty()

    fun invertSelection() {
        val selectable = chipTokenIndex.toSet()
        val inverted = selectable - selected
        selected.clear()
        selected.addAll(inverted)
        refreshChips()
        onSelectionChanged?.invoke()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = paddingStart
        var y = paddingTop
        var lineHeight = 0
        val innerWidth = width - paddingStart - paddingEnd
        for (chipIndex in 0 until childCount) {
            val child = getChildAt(chipIndex)
            child.measure(childWidthSpec, childHeightSpec)
            val childW = child.measuredWidth
            val childH = child.measuredHeight
            val forceBreak = shouldForceBreak(chipIndex)
            if (forceBreak || (x > paddingStart && x + childW > paddingStart + innerWidth)) {
                x = paddingStart
                if (lineHeight > 0) y += lineHeight + rowGap
                lineHeight = 0
            }
            lineHeight = max(lineHeight, childH)
            x += childW + gap
        }
        val wantedHeight = y + lineHeight + paddingBottom
        val height = resolveSize(wantedHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val innerWidth = r - l - paddingStart - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var lineHeight = 0
        for (chipIndex in 0 until childCount) {
            val child = getChildAt(chipIndex)
            val childW = child.measuredWidth
            val childH = child.measuredHeight
            val forceBreak = shouldForceBreak(chipIndex)
            if (forceBreak || (x > paddingStart && x + childW > paddingStart + innerWidth)) {
                x = paddingStart
                if (lineHeight > 0) y += lineHeight + rowGap
                lineHeight = 0
            }
            child.layout(x, y, x + childW, y + childH)
            lineHeight = max(lineHeight, childH)
            x += childW + gap
        }
    }

    /** 上一枚芯片对应 token 之后、本枚之前是否夹着 LINE_BREAK。 */
    private fun shouldForceBreak(chipIndex: Int): Boolean {
        if (chipIndex <= 0 || chipIndex >= chipTokenIndex.size) return false
        val prevToken = chipTokenIndex[chipIndex - 1]
        val curToken = chipTokenIndex[chipIndex]
        return (prevToken + 1 until curToken).any { TextTokenizer.isLineBreak(tokens[it]) }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && hitChip(event.x, event.y) >= 0) {
            return true
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val chip = hitChip(event.x, event.y)
                dragStartIndex = chipTokenOf(chip)
                dragging = false
                dragDeselect = dragStartIndex in selected
                dragSnapshot.clear()
                dragSnapshot.addAll(selected)
                if (dragStartIndex >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragStartIndex < 0) return false
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > slop || dy > slop) {
                    dragging = true
                    val chip = hitChip(event.x, event.y).takeIf { it >= 0 }
                        ?: nearestChip(event.x, event.y)
                    val current = chipTokenOf(chip)
                    applyDragRange(dragStartIndex, current)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragStartIndex >= 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!dragging) {
                        toggle(dragStartIndex)
                    }
                }
                dragging = false
                dragStartIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toggle(tokenIndex: Int) {
        if (TextTokenizer.isLineBreak(tokens.getOrNull(tokenIndex).orEmpty())) return
        if (!selected.add(tokenIndex)) selected.remove(tokenIndex)
        refreshChips()
        onSelectionChanged?.invoke()
    }

    private fun applyDragRange(fromToken: Int, toToken: Int) {
        if (tokens.isEmpty() || fromToken < 0 || toToken < 0) return
        val start = min(fromToken, toToken).coerceIn(0, tokens.lastIndex)
        val end = max(fromToken, toToken).coerceIn(0, tokens.lastIndex)
        selected.clear()
        selected.addAll(dragSnapshot)
        for (index in start..end) {
            if (TextTokenizer.isLineBreak(tokens[index])) continue
            if (dragDeselect) selected.remove(index) else selected.add(index)
        }
        refreshChips()
        onSelectionChanged?.invoke()
    }

    private fun refreshChips() {
        for (chipIndex in 0 until childCount) {
            val chip = getChildAt(chipIndex) as? TextView ?: continue
            val tokenIndex = chipTokenIndex.getOrNull(chipIndex) ?: continue
            applyChipStyle(chip, selected = tokenIndex in selected)
        }
    }

    private fun chipTokenOf(chipIndex: Int): Int =
        chipTokenIndex.getOrNull(chipIndex) ?: -1

    private fun hitChip(x: Float, y: Float): Int {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return index
            }
        }
        return -1
    }

    private fun nearestChip(x: Float, y: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val cx = (child.left + child.right) / 2f
            val cy = (child.top + child.bottom) / 2f
            val dist = abs(x - cx) + abs(y - cy)
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

    companion object {
        private const val TAG_WORD = "word"
        private const val TAG_PUNCT = "punct"
    }
}
