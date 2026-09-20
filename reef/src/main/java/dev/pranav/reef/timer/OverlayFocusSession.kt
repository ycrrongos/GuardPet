package dev.pranav.reef.timer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import dev.pranav.reef.data.SessionType
import dev.pranav.reef.util.AndroidUtilities.formatTime
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌宠悬浮窗内的专注会话：写 prefs `focus_mode`，不启动 FocusModeService
 *（避免与 PetService 抢第二条 specialUse FGS）。
 */
class OverlayFocusSession {
    data class UiState(
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val timeLeft: String = "25:00",
        val timerState: String = "FOCUS",
        val strictMode: Boolean = false,
        val zenMode: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var kind: Kind = Kind.Idle
    private var remainingMs = 0L
    private var elapsedMs = 0L
    private var anchorElapsed = 0L
    private var paused = false
    private var strictMode = false
    private var zenMode = false
    private var phaseLabel = "FOCUS"

    private var pomodoroFocusMs = 25 * 60_000L
    private var pomodoroShortMs = 5 * 60_000L
    private var pomodoroLongMs = 15 * 60_000L
    private var pomodoroCycles = 4
    private var pomodoroCycle = 1
    private var countUpRatio = 5
    private var simpleInitialMs = 25 * 60_000L

    private val focusStarted = linkedSetOf<() -> Unit>()
    private val focusCompleted = linkedSetOf<() -> Unit>()

    fun addOnFocusStarted(listener: () -> Unit) {
        focusStarted += listener
    }

    fun removeOnFocusStarted(listener: () -> Unit) {
        focusStarted -= listener
    }

    fun addOnFocusCompleted(listener: () -> Unit) {
        focusCompleted += listener
    }

    fun removeOnFocusCompleted(listener: () -> Unit) {
        focusCompleted -= listener
    }

    private fun notifyFocusStarted() {
        focusStarted.toList().forEach { runCatching { it() } }
    }

    private fun notifyFocusCompleted() {
        focusCompleted.toList().forEach { runCatching { it() } }
    }

    private enum class Kind {
        Idle, Simple, PomodoroFocus, PomodoroShort, PomodoroLong, CountUp, CountUpBreak
    }

    private val tick = object : Runnable {
        override fun run() {
            if (kind == Kind.Idle || paused) return
            when (kind) {
                Kind.CountUp -> {
                    elapsedMs = SystemClock.elapsedRealtime() - anchorElapsed
                    publish()
                    handler.postDelayed(this, 250L)
                }
                else -> {
                    remainingMs = (anchorElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    publish()
                    if (remainingMs <= 0L) {
                        onPhaseComplete()
                    } else {
                        handler.postDelayed(this, 250L)
                    }
                }
            }
        }
    }

    fun setZenMode(enabled: Boolean) {
        zenMode = enabled
        publish()
    }

    fun start(config: TimerConfig): Boolean {
        if (!isPrefsInitialized) return false
        handler.removeCallbacks(tick)
        paused = false
        when (config) {
            is TimerConfig.Simple -> {
                kind = Kind.Simple
                phaseLabel = "FOCUS"
                strictMode = config.strictMode
                simpleInitialMs = config.minutes.coerceAtLeast(1) * 60_000L
                remainingMs = simpleInitialMs
                applySimplePrefs(config)
                FocusStats.startSession(SessionType.SIMPLE)
            }
            is TimerConfig.Pomodoro -> {
                kind = Kind.PomodoroFocus
                phaseLabel = "FOCUS"
                strictMode = config.strictMode
                pomodoroFocusMs = config.focusMinutes.coerceAtLeast(1) * 60_000L
                pomodoroShortMs = config.shortBreakMinutes.coerceAtLeast(1) * 60_000L
                pomodoroLongMs = config.longBreakMinutes.coerceAtLeast(1) * 60_000L
                pomodoroCycles = config.cycles.coerceAtLeast(1)
                pomodoroCycle = 1
                remainingMs = pomodoroFocusMs
                applyPomodoroPrefs(config)
                FocusStats.startSession(SessionType.POMODORO)
            }
            is TimerConfig.CountUp -> {
                kind = Kind.CountUp
                phaseLabel = "FOCUS"
                strictMode = false
                countUpRatio = config.ratio.coerceIn(1, 60)
                elapsedMs = 0L
                applyCountUpPrefs(config)
                FocusStats.startSession(SessionType.SIMPLE)
            }
        }
        if (kind == Kind.CountUp) {
            anchorElapsed = SystemClock.elapsedRealtime()
        } else {
            anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
        }
        setBlocking(isFocusPhase())
        notifyFocusStarted()
        publish()
        handler.post(tick)
        return true
    }

    fun pause() {
        if (kind == Kind.Idle || paused || strictMode) return
        paused = true
        handler.removeCallbacks(tick)
        if (kind == Kind.CountUp) {
            elapsedMs = SystemClock.elapsedRealtime() - anchorElapsed
        } else {
            remainingMs = (anchorElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
        setBlocking(false)
        publish()
    }

    fun resume() {
        if (kind == Kind.Idle || !paused) return
        paused = false
        if (kind == Kind.CountUp) {
            anchorElapsed = SystemClock.elapsedRealtime() - elapsedMs
        } else {
            anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
        }
        setBlocking(isFocusPhase())
        publish()
        handler.post(tick)
    }

    fun restart() {
        if (kind == Kind.Idle) return
        handler.removeCallbacks(tick)
        paused = false
        when (kind) {
            Kind.CountUp -> {
                elapsedMs = 0L
                anchorElapsed = SystemClock.elapsedRealtime()
            }
            Kind.PomodoroFocus -> remainingMs = pomodoroFocusMs
            Kind.PomodoroShort -> remainingMs = pomodoroShortMs
            Kind.PomodoroLong -> remainingMs = pomodoroLongMs
            Kind.CountUpBreak -> remainingMs = remainingMs.coerceAtLeast(60_000L)
            Kind.Simple -> remainingMs = simpleInitialMs
            Kind.Idle -> return
        }
        if (kind != Kind.CountUp) {
            anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
        }
        setBlocking(isFocusPhase())
        publish()
        handler.post(tick)
    }

    fun takeBreak() {
        if (kind != Kind.CountUp || paused) return
        elapsedMs = SystemClock.elapsedRealtime() - anchorElapsed
        val budget = (elapsedMs / countUpRatio.coerceAtLeast(1)).coerceAtLeast(60_000L)
        kind = Kind.CountUpBreak
        phaseLabel = "BREAK"
        remainingMs = budget
        anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
        setBlocking(false)
        publish()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        if (kind != Kind.Idle) {
            runCatching { FocusStats.endSession(isCompleted = false) }
        }
        resetIdle()
        clearSessionPrefs()
        publish()
    }

    /** 不再取消会话。计时挂在进程里，关页面不能清掉另一边正在走的番茄钟。 */
    fun release() = Unit

    private fun onPhaseComplete() {
        handler.removeCallbacks(tick)
        when (kind) {
            Kind.Simple -> finishCompleted()
            Kind.PomodoroFocus -> {
                val longBreak = pomodoroCycle >= pomodoroCycles
                if (longBreak) {
                    kind = Kind.PomodoroLong
                    phaseLabel = "LONG_BREAK"
                    remainingMs = pomodoroLongMs
                } else {
                    kind = Kind.PomodoroShort
                    phaseLabel = "SHORT_BREAK"
                    remainingMs = pomodoroShortMs
                }
                anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
                setBlocking(false)
                publish()
                handler.post(tick)
            }
            Kind.PomodoroShort -> {
                pomodoroCycle += 1
                kind = Kind.PomodoroFocus
                phaseLabel = "FOCUS"
                remainingMs = pomodoroFocusMs
                anchorElapsed = SystemClock.elapsedRealtime() + remainingMs
                setBlocking(true)
                publish()
                handler.post(tick)
            }
            Kind.PomodoroLong -> finishCompleted()
            Kind.CountUpBreak -> {
                kind = Kind.CountUp
                phaseLabel = "FOCUS"
                elapsedMs = 0L
                anchorElapsed = SystemClock.elapsedRealtime()
                setBlocking(true)
                publish()
                handler.post(tick)
            }
            Kind.CountUp, Kind.Idle -> Unit
        }
    }

    private fun finishCompleted() {
        resetIdle()
        clearSessionPrefs()
        runCatching { FocusStats.endSession(isCompleted = true) }
        notifyFocusCompleted()
        publish()
    }

    private fun resetIdle() {
        kind = Kind.Idle
        paused = false
        remainingMs = 0L
        elapsedMs = 0L
        phaseLabel = "FOCUS"
        setBlocking(false)
    }

    private fun isFocusPhase(): Boolean =
        when (kind) {
            Kind.Simple, Kind.PomodoroFocus, Kind.CountUp -> true
            else -> false
        }

    private fun setBlocking(enabled: Boolean) {
        if (!isPrefsInitialized) return
        runCatching {
            prefs.edit { putBoolean("focus_mode", enabled) }
        }
    }

    private fun applySimplePrefs(config: TimerConfig.Simple) {
        prefs.edit {
            putBoolean("focus_mode", true)
            putBoolean("pomodoro_mode", false)
            remove("count_up_mode")
            putLong("focus_time", config.minutes * 60_000L)
            putBoolean("strict_mode", config.strictMode)
        }
    }

    private fun applyPomodoroPrefs(config: TimerConfig.Pomodoro) {
        prefs.edit {
            putBoolean("focus_mode", true)
            putBoolean("pomodoro_mode", true)
            putLong("focus_time", config.focusMinutes * 60_000L)
            putLong("pomodoro_focus_duration", config.focusMinutes * 60_000L)
            putLong("pomodoro_short_break_duration", config.shortBreakMinutes * 60_000L)
            putLong("pomodoro_long_break_duration", config.longBreakMinutes * 60_000L)
            putInt("pomodoro_cycles_before_long_break", config.cycles)
            putInt("pomodoro_current_cycle", 1)
            putString("pomodoro_state", "FOCUS")
            remove("count_up_mode")
            putBoolean("strict_mode", config.strictMode)
        }
    }

    private fun applyCountUpPrefs(config: TimerConfig.CountUp) {
        prefs.edit {
            putBoolean("focus_mode", true)
            putBoolean("pomodoro_mode", false)
            putBoolean("count_up_mode", true)
            putFloat("count_up_ratio", config.ratio.toFloat())
            putBoolean("strict_mode", false)
        }
    }

    private fun clearSessionPrefs() {
        if (!isPrefsInitialized) return
        runCatching {
            prefs.edit {
                putBoolean("focus_mode", false)
                remove("count_up_mode")
                putBoolean("pomodoro_mode", false)
            }
        }
    }

    private fun publish() {
        val displayMs = when (kind) {
            Kind.Idle -> 0L
            Kind.CountUp -> if (paused) elapsedMs else (SystemClock.elapsedRealtime() - anchorElapsed).coerceAtLeast(0L)
            else -> if (paused) remainingMs else (anchorElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
        val active = kind != Kind.Idle
        _state.value = UiState(
            isRunning = active && !paused,
            isPaused = active && paused,
            timeLeft = formatTime(displayMs),
            timerState = phaseLabel,
            strictMode = strictMode,
            zenMode = zenMode
        )
        // 与主页 / TimerContent 共用的状态，避免再走 FocusModeService
        if (!active) {
            TimerStateManager.reset()
            return
        }
        val phase = when (phaseLabel) {
            "SHORT_BREAK" -> PomodoroPhase.SHORT_BREAK
            "LONG_BREAK" -> PomodoroPhase.LONG_BREAK
            "BREAK" -> PomodoroPhase.COUNT_UP_BREAK
            else -> PomodoroPhase.FOCUS
        }
        TimerStateManager.updateState {
            copy(
                isRunning = active && !paused,
                isPaused = active && paused,
                timeRemaining = displayMs,
                focusTimeElapsed = if (kind == Kind.CountUp) displayMs else 0L,
                breakBudget = if (kind == Kind.CountUpBreak) remainingMs else 0L,
                pomodoroPhase = phase,
                currentCycle = pomodoroCycle,
                totalCycles = pomodoroCycles,
                isPomodoroMode = kind == Kind.PomodoroFocus ||
                    kind == Kind.PomodoroShort ||
                    kind == Kind.PomodoroLong,
                isCountUpMode = kind == Kind.CountUp || kind == Kind.CountUpBreak,
                isStrictMode = strictMode,
                countUpRatio = countUpRatio.toFloat()
            )
        }
    }

    companion object {
        /** 应用内番茄钟和桌宠番茄钟共用这一份。 */
        val shared = OverlayFocusSession()
    }
}
