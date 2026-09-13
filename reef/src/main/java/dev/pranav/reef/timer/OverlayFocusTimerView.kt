package dev.pranav.reef.timer

import android.content.Context
import android.widget.FrameLayout
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import dev.pranav.reef.ui.ReefTheme

/**
 * 可嵌进桌宠悬浮窗的 Reef 专注 UI（[TimerContent]）。
 * 对外是普通 [FrameLayout]，避免 :app 编译期依赖 Compose。
 */
class OverlayFocusTimerView(context: Context) : FrameLayout(context) {
    private val session = OverlayFocusSession()
    private val composeView = ComposeView(context)

    var onRequestClose: (() -> Unit)? = null
    var onFocusStarted: (() -> Unit)? = null
        set(value) {
            field = value
            session.onFocusStarted = value
        }
    var onFocusCompleted: (() -> Unit)? = null
        set(value) {
            field = value
            session.onFocusCompleted = value
        }
    var onStartFailed: (() -> Unit)? = null

    init {
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /** 由宿主在挂到 WindowManager 后设置 ViewTree owners，再启动 Composition。 */
    fun bindTreeOwners(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        viewModelStoreOwner: androidx.lifecycle.ViewModelStoreOwner,
        savedStateRegistryOwner: androidx.savedstate.SavedStateRegistryOwner,
        onBackPressedDispatcherOwner: androidx.activity.OnBackPressedDispatcherOwner? = null
    ) {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        if (onBackPressedDispatcherOwner != null) {
            setViewTreeOnBackPressedDispatcherOwner(onBackPressedDispatcherOwner)
            composeView.setViewTreeOnBackPressedDispatcherOwner(onBackPressedDispatcherOwner)
        }
        composeView.setContent { FocusNav() }
    }

    fun cancelSession() {
        session.cancel()
    }

    fun release() {
        session.release()
        onRequestClose = null
        onFocusStarted = null
        onFocusCompleted = null
        onStartFailed = null
        composeView.disposeComposition()
    }

    @Composable
    private fun FocusNav() {
        ReefTheme {
            val navController = rememberNavController()
            val ui by session.state.collectAsState()
            NavHost(
                navController = navController,
                startDestination = Screen.Timer,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<Screen.Timer> {
                    TimerContent(
                        navController = navController,
                        isTimerRunning = ui.isRunning,
                        isPaused = ui.isPaused,
                        currentTimeLeft = ui.timeLeft,
                        currentTimerState = ui.timerState,
                        isStrictMode = ui.strictMode,
                        isZenMode = ui.zenMode,
                        onZenModeChange = { session.setZenMode(it) },
                        onStartTimer = { config ->
                            if (!session.start(config)) {
                                onStartFailed?.invoke()
                            }
                        },
                        onPauseTimer = { session.pause() },
                        onResumeTimer = { session.resume() },
                        onCancelTimer = { session.cancel() },
                        onRestartTimer = { session.restart() },
                        onTakeBreak = { session.takeBreak() }
                    )
                }
                composable<Screen.FocusStats> {
                    FocusStatsScreen(
                        onBackPressed = { navController.popBackStack() },
                        onSessionClick = { id ->
                            navController.navigate(Screen.FocusSessionDetail(id))
                        }
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
