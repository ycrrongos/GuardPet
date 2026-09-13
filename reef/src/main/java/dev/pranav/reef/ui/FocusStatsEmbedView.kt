package dev.pranav.reef.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pranav.reef.navigation.Screen
import dev.pranav.reef.screens.FocusSessionDetailScreen
import dev.pranav.reef.screens.FocusStatsScreen

/**
 * 守伴主页嵌入用的专注统计大卡片（Reef [FocusStatsScreen]）。
 * 对外普通 [FrameLayout]，避免 :app 编译 Compose。
 */
class FocusStatsEmbedView(context: Context) : FrameLayout(context) {
    private val composeView = ComposeView(context)
    private var bound = false

    init {
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun bindToHost(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        viewModelStoreOwner: androidx.lifecycle.ViewModelStoreOwner,
        savedStateRegistryOwner: androidx.savedstate.SavedStateRegistryOwner,
        onBackPressedDispatcherOwner: androidx.activity.OnBackPressedDispatcherOwner
    ) {
        if (bound) return
        bound = true
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        setViewTreeOnBackPressedDispatcherOwner(onBackPressedDispatcherOwner)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        composeView.setViewTreeOnBackPressedDispatcherOwner(onBackPressedDispatcherOwner)
        composeView.setContent { EmbedContent() }
    }

    fun release() {
        if (!bound) return
        bound = false
        composeView.disposeComposition()
    }

    /** 让卡片内 LazyColumn 优先滚动，不被主页 NestedScrollView 抢走。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(ev)
    }

    @Composable
    private fun EmbedContent() {
        ReefTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 1.dp
            ) {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = Screen.FocusStats,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 4.dp)
                ) {
                    composable<Screen.FocusStats> {
                        FocusStatsScreen(
                            onBackPressed = { },
                            onSessionClick = { id ->
                                navController.navigate(Screen.FocusSessionDetail(id))
                            },
                            embedded = true
                        )
                    }
                    composable<Screen.FocusSessionDetail> { entry ->
                        val route = entry.toRoute<Screen.FocusSessionDetail>()
                        FocusSessionDetailScreen(
                            sessionId = route.sessionId,
                            onBackPressed = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
