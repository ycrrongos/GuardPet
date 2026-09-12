package dev.pranav.reef.util

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat


fun Activity.setDarkStatusBar() {
    val windowInsetsController =
        WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.isAppearanceLightStatusBars = false
}

fun ComponentActivity.applyDefaults() {
    setDarkStatusBar()
    enableEdgeToEdge()
}

fun PaddingValues.append(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    start: Dp = 0.dp,
    end: Dp = 0.dp
): PaddingValues {
    return PaddingValues(
        top = this.calculateTopPadding() + top,
        bottom = this.calculateBottomPadding() + bottom,
        start = this.calculateStartPadding(LayoutDirection.Ltr) + start,
        end = this.calculateEndPadding(LayoutDirection.Ltr) + end
    )
}

fun PaddingValues.append(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues {
    return append(
        top = vertical,
        bottom = vertical,
        start = horizontal,
        end = horizontal
    )
}

fun PaddingValues.append(all: Dp): PaddingValues {
    return append(
        top = all,
        bottom = all,
        start = all,
        end = all
    )
}
