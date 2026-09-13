package com.geekathon.guardpet

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.random.Random

enum class FlashNoteCategory(val key: String, val labelRes: Int) {
    SCHEDULE("schedule", R.string.category_schedule),
    IDEA("idea", R.string.category_idea),
    DIARY("diary", R.string.category_diary),
    TODO("todo", R.string.category_todo),
    OTHER("other", R.string.category_other);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * 闪记固定配色（用户不可自选，除待办紧急度）：
 * - 灵感：Telegram 黄→绿对角渐变
 * - 日记：太阳黄
 * - 待办：黄→红 6 档紧急度（[FlashNote.color] 存 0..5）
 * - 其他：银灰
 */
object FlashNoteColor {
    /** Telegram 聊天背景参考：浅柠檬黄 → 薄荷绿 */
    val IDEA_YELLOW = 0xFFE8EA8A.toInt()
    val IDEA_GREEN = 0xFF82BC87.toInt()

    /** 太阳黄 */
    val DIARY_SUN = 0xFFFFC107.toInt()

    /** 银灰 */
    val OTHER_SILVER = 0xFF90A4AE.toInt()

    /** 待办紧急度 0(低) → 5(高)：黄到红 */
    val TODO_URGENCY = intArrayOf(
        0xFFFFE566.toInt(),
        0xFFFFD54F.toInt(),
        0xFFFFB74D.toInt(),
        0xFFFF8A65.toInt(),
        0xFFFF7043.toInt(),
        0xFFE53935.toInt()
    )

    /** @deprecated 仅兼容旧调用；请用 [argb] with category 或 [TODO_URGENCY]。 */
    @Deprecated("Use category-aware colors")
    val palette: IntArray
        get() = TODO_URGENCY

    fun argb(index: Int, category: FlashNoteCategory = FlashNoteCategory.OTHER): Int =
        when (category) {
            FlashNoteCategory.IDEA -> IDEA_YELLOW
            FlashNoteCategory.DIARY -> DIARY_SUN
            FlashNoteCategory.TODO -> {
                val size = TODO_URGENCY.size
                TODO_URGENCY[((index % size) + size) % size]
            }
            FlashNoteCategory.OTHER, FlashNoteCategory.SCHEDULE -> OTHER_SILVER
        }

    fun nextTodoUrgency(index: Int) = (index + 1) % TODO_URGENCY.size

    @Deprecated("Use nextTodoUrgency")
    fun next(index: Int) = nextTodoUrgency(index)

    fun applyCardBackground(view: View, note: FlashNote, cornerPx: Float) {
        view.background = cardBackground(note, cornerPx)
    }

    fun cardBackground(note: FlashNote, cornerPx: Float): GradientDrawable =
        when (note.category) {
            FlashNoteCategory.IDEA -> GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(IDEA_YELLOW, IDEA_GREEN)
            ).apply { cornerRadius = cornerPx }
            FlashNoteCategory.DIARY -> solid(DIARY_SUN, cornerPx)
            FlashNoteCategory.TODO -> solid(argb(note.color, FlashNoteCategory.TODO), cornerPx)
            FlashNoteCategory.OTHER, FlashNoteCategory.SCHEDULE -> solid(OTHER_SILVER, cornerPx)
        }

    private fun solid(color: Int, cornerPx: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = cornerPx
    }
}

/**
 * 日程亮色表（参考 Google Calendar 事件色 + 常见亮色日历色板）。
 * 新建随机取色；用户可切换。
 */
object DayScheduleColor {
    const val DONE_GREEN = 0xFF2E7D32.toInt()

    /** 亮色列表，适合白字卡片。 */
    val brightPalette = intArrayOf(
        0xFF039BE5.toInt(), // Peacock
        0xFF4285F4.toInt(), // Cobalt
        0xFF3F51B5.toInt(), // Blueberry
        0xFF7986CB.toInt(), // Lavender
        0xFF8E24AA.toInt(), // Grape
        0xFF9E69AF.toInt(), // Amethyst
        0xFFD81B60.toInt(), // Cherry Blossom
        0xFFAD1457.toInt(), // Radicchio
        0xFFE67C73.toInt(), // Flamingo
        0xFFF4511E.toInt(), // Tangerine
        0xFFEF6C00.toInt(), // Pumpkin
        0xFFF09300.toInt(), // Orange
        0xFFF6BF26.toInt(), // Banana
        0xFF33B679.toInt(), // Sage
        0xFF0B8043.toInt(), // Basil
        0xFF009688.toInt(), // Teal
        0xFF26A69A.toInt(),
        0xFF5C6BC0.toInt(),
        0xFFEC407A.toInt(),
        0xFFAB47BC.toInt()
    )

    fun argb(index: Int): Int {
        val size = brightPalette.size
        return brightPalette[((index % size) + size) % size]
    }

    fun nearestIndex(colorArgb: Int): Int {
        val exact = brightPalette.indexOf(colorArgb)
        if (exact >= 0) return exact
        var best = 0
        var bestDist = Int.MAX_VALUE
        brightPalette.forEachIndexed { index, c ->
            val dr = Color.red(c) - Color.red(colorArgb)
            val dg = Color.green(c) - Color.green(colorArgb)
            val db = Color.blue(c) - Color.blue(colorArgb)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                best = index
            }
        }
        return best
    }

    fun nextIndex(colorArgb: Int): Int =
        (nearestIndex(colorArgb) + 1) % brightPalette.size

    fun nextColor(colorArgb: Int): Int = argb(nextIndex(colorArgb))

    /** 随机主色（亮色表）；difficulty 略调明暗。 */
    fun randomPending(difficulty: Float, seed: Int = Random.nextInt()): Int {
        val rnd = Random(seed)
        val base = brightPalette[rnd.nextInt(brightPalette.size)]
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        // 难 → 略深；易 → 略亮
        hsv[2] = (hsv[2] - difficulty.coerceIn(0f, 1f) * 0.12f).coerceIn(0.55f, 0.95f)
        return Color.HSVToColor(hsv)
    }
}
