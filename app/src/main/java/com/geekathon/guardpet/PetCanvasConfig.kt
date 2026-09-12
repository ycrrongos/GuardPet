package com.geekathon.guardpet

import android.content.Context
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single source of truth for the overlay canvas size.
 * One default canvas occupies one cell of a 4 x 6 screen grid.
 */
object PetCanvasConfig {
    const val DEFAULT_GRID_COLUMNS = 4
    const val DEFAULT_GRID_ROWS = 6
    const val DEFAULT_CANVAS_SCALE = 2f / 3f

    data class Size(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Float,
        val heightDp: Float
    )

    fun resolve(
        context: Context,
        gridColumns: Int = DEFAULT_GRID_COLUMNS,
        gridRows: Int = DEFAULT_GRID_ROWS
    ): Size {
        require(gridColumns > 0) { "gridColumns must be greater than zero" }
        require(gridRows > 0) { "gridRows must be greater than zero" }

        val metrics = context.resources.displayMetrics
        val density = metrics.density.coerceAtLeast(0.1f)
        val screenWidth = max(1, metrics.widthPixels)
        val screenHeight = max(1, metrics.heightPixels)

        // Convert through dp so density is part of the sizing contract.
        val widthDp = screenWidth / gridColumns.toFloat() / density * DEFAULT_CANVAS_SCALE
        val heightDp = screenHeight / gridRows.toFloat() / density * DEFAULT_CANVAS_SCALE
        val widthPx = dpToPx(widthDp, density)
        val heightPx = dpToPx(heightDp, density)

        return Size(widthPx, heightPx, widthDp, heightDp)
    }

    private fun dpToPx(valueDp: Float, density: Float): Int =
        max(1, (valueDp * density).roundToInt())
}
