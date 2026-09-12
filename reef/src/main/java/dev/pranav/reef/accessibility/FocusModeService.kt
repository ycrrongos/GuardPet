package dev.pranav.reef.accessibility

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.pranav.reef.App
import dev.pranav.reef.MainActivity
import dev.pranav.reef.R
import dev.pranav.reef.data.PhaseType
import dev.pranav.reef.data.SessionType
import dev.pranav.reef.timer.PomodoroConfig
import dev.pranav.reef.timer.PomodoroPhase
import dev.pranav.reef.timer.TimerSessionState
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.util.AndroidUtilities
import dev.pranav.reef.util.AndroidUtilities.formatTime
import dev.pranav.reef.util.BLOCKER_CHANNEL_ID
import dev.pranav.reef.util.FOCUS_MODE_CHANNEL_ID
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class FocusModeService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val BREAK_ALERT_NOTIFICATION_ID = 2
        private const val COMPLETE_NOTIFICATION_ID = 3
        const val ACTION_TIMER_UPDATED = "dev.pranav.reef.TIMER_UPDATED"
        const val ACTION_START = "dev.pranav.reef.START_TIMER"
        const val ACTION_PAUSE = "dev.pranav.reef.PAUSE_TIMER"
        const val ACTION_RESUME = "dev.pranav.reef.RESUME_TIMER"
        const val ACTION_RESTART = "dev.pranav.reef.RESTART_TIMER"
        const val ACTION_TAKE_BREAK = "dev.pranav.reef.TAKE_BREAK"
        const val EXTRA_TIME_LEFT = "extra_time_left"
        const val EXTRA_TIMER_STATE = "extra_timer_state"
    }

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val systemNotificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var countDownTimer: CountDownTimer? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var previousInterruptionFilter: Int? = null
    private var initialDuration: Long = 0
    private var notificationStyle: NotificationCompat.ProgressStyle? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countUpJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        if (!isPrefsInitialized) {
            createDeviceProtectedStorageContext().also { safeContext ->
                prefs = safeContext.getSharedPreferences("prefs", MODE_PRIVATE)
            }
        }
    }

    private fun promoteToForeground() {
        val state = TimerStateManager.state.value
        val timeToDisplay = when {
            state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK ->
                state.focusTimeElapsed

            state.timeRemaining > 0 -> state.timeRemaining
            else -> prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))
        }

        val notification = createNotification(
            title = getNotificationTitle(),
            text = buildNotificationText(state, timeToDisplay),
            showPauseButton = !state.isStrictMode && state.isRunning,
            timeLeft = timeToDisplay
        )

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) {
            return START_NOT_STICKY
        }

        if (intent.action != ACTION_PAUSE) {
            if (intent.action != ACTION_START) {
                promoteToForeground()
            }
        }

        when (intent.action) {
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_RESTART -> restartCurrentPhase()
            ACTION_TAKE_BREAK -> startBreakPhase()
            ACTION_START -> startTimer()
        }

        return START_STICKY
    }

    private fun startTimer() {
        val focusTimeMillis = prefs.getLong("focus_time", TimeUnit.MINUTES.toMillis(10))
        val isStrictMode = prefs.getBoolean("strict_mode", false)
        val isPomodoroMode = prefs.getBoolean("pomodoro_mode", false)
        val isCountUpMode = prefs.getBoolean("count_up_mode", false)

        initialDuration = focusTimeMillis
        notificationStyle = null

        if (isCountUpMode) {
            val ratio = prefs.getFloat("count_up_ratio", 5f)
            TimerStateManager.updateState {
                copy(
                    isRunning = true,
                    isPaused = false,
                    isPomodoroMode = false,
                    isCountUpMode = true,
                    isStrictMode = isStrictMode,
                    countUpRatio = ratio,
                    focusTimeElapsed = 0,
                    breakBudget = 0,
                    pomodoroPhase = PomodoroPhase.FOCUS
                )
            }
            startCountUp()
        } else if (isPomodoroMode) {
            val config = PomodoroConfig(
                focusDuration = prefs.getLong("pomodoro_focus_duration", 25 * 60 * 1000L),
                shortBreakDuration = prefs.getLong("pomodoro_short_break_duration", 5 * 60 * 1000L),
                longBreakDuration = prefs.getLong("pomodoro_long_break_duration", 15 * 60 * 1000L),
                cyclesBeforeLongBreak = prefs.getInt("pomodoro_cycles_before_long_break", 4)
            )
            TimerStateManager.setPomodoroConfig(config)

            val currentCycle = prefs.getInt("pomodoro_current_cycle", 1)
            TimerStateManager.updateState {
                copy(
                    isRunning = true,
                    isPaused = false,
                    timeRemaining = focusTimeMillis,
                    pomodoroPhase = PomodoroPhase.FOCUS,
                    currentCycle = currentCycle,
                    totalCycles = config.cyclesBeforeLongBreak,
                    isPomodoroMode = true,
                    isStrictMode = isStrictMode
                )
            }
        } else {
            TimerStateManager.updateState {
                copy(
                    isRunning = true,
                    isPaused = false,
                    timeRemaining = focusTimeMillis,
                    isPomodoroMode = false,
                    isStrictMode = isStrictMode
                )
            }
        }

        FocusStats.startSession(if (isPomodoroMode) SessionType.POMODORO else SessionType.SIMPLE)
        FocusStats.startPhase(PhaseType.FOCUS, focusTimeMillis)

        prefs.edit { putBoolean("focus_mode", true) }

        enableDNDIfNeeded()

        promoteToForeground()

        updateNotification(
            title = getNotificationTitle(),
            text = buildNotificationText(TimerStateManager.state.value, focusTimeMillis),
            showPauseButton = !isStrictMode,
            timeLeft = focusTimeMillis
        )
        if (!isCountUpMode) {
            startCountdown(focusTimeMillis)
        }
    }

    private fun getNotificationTitle(): String {
        val state = TimerStateManager.state.value
        if (!state.isPomodoroMode) {
            return getString(R.string.focus_mode)
        }

        return when (state.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> getString(R.string.short_break_label)
            PomodoroPhase.LONG_BREAK -> getString(R.string.long_break_label)
            else -> getString(R.string.focus_mode)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun startCountUp() {
        countUpJob?.cancel()
        countUpJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val state = TimerStateManager.state.value
                if (!state.isPaused) {
                    val newElapsed = state.focusTimeElapsed + 1000
                    val newBreakBudget = if (state.pomodoroPhase == PomodoroPhase.FOCUS) {
                        (newElapsed / state.countUpRatio).toLong()
                    } else {
                        (state.breakBudget - 1000).coerceAtLeast(0)
                    }

                    if (state.pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK && newBreakBudget == 0L) {
                        handleTimerComplete()
                        break
                    }

                    TimerStateManager.updateState {
                        copy(
                            focusTimeElapsed = newElapsed,
                            breakBudget = newBreakBudget,
                            timeRemaining = if (pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK) newBreakBudget else 0
                        )
                    }

                    val displayTime = if (state.pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK) {
                        newBreakBudget
                    } else {
                        newElapsed
                    }
                    updateNotificationAndBroadcast(displayTime)
                }
            }
        }
    }

    private fun startBreakPhase() {
        countUpJob?.cancel()
        val state = TimerStateManager.state.value
        val breakMillis = state.breakBudget

        if (breakMillis <= 0) {
            resumeCountUpAfterBreak()
            return
        }

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = PomodoroPhase.COUNT_UP_BREAK,
                timeRemaining = breakMillis,
                breakBudget = 0,
                isRunning = true,
                isPaused = false
            )
        }

        FocusStats.endPhase(isCompleted = true)
        FocusStats.startPhase(PhaseType.SHORT_BREAK, breakMillis)

        restoreDND()
        prefs.edit { putBoolean("focus_mode", false) }

        initialDuration = breakMillis
        notificationStyle = null

        updateNotification(
            title = getString(R.string.short_break_label),
            text = getString(R.string.time_remaining, formatTime(breakMillis)),
            showPauseButton = !state.isStrictMode,
            timeLeft = breakMillis
        )
        broadcastTimerUpdate(formatTime(breakMillis))
        startCountdown(breakMillis)
    }

    private fun resumeCountUpAfterBreak() {
        countDownTimer?.cancel()

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = PomodoroPhase.FOCUS,
                focusTimeElapsed = 0,
                timeRemaining = 0,
                breakBudget = 0,
                isRunning = true,
                isPaused = false
            )
        }

        prefs.edit { putBoolean("focus_mode", true) }
        enableDNDIfNeeded()

        updateNotification(
            title = getNotificationTitle(),
            text = buildNotificationText(TimerStateManager.state.value, 0),
            showPauseButton = !TimerStateManager.state.value.isStrictMode,
            timeLeft = 0
        )
        broadcastTimerUpdate(formatTime(0))
        startCountUp()
    }

    private fun startCountdown(timeMillis: Long) {
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(timeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val state = TimerStateManager.state.value
                if (!state.isPaused) {
                    TimerStateManager.updateState { copy(timeRemaining = millisUntilFinished) }
                    updateNotificationAndBroadcast(millisUntilFinished)
                }
            }

            override fun onFinish() = handleTimerComplete()
        }.start()
    }

    private fun pauseTimer() {
        val state = TimerStateManager.state.value
        if (state.isStrictMode) return

        countDownTimer?.cancel()
        countUpJob?.cancel()

        TimerStateManager.updateState {
            copy(isRunning = false, isPaused = true)
        }

        prefs.edit { putBoolean("focus_mode", false) }

        restoreDND()

        val timeToDisplay =
            if (state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK) {
                state.focusTimeElapsed
            } else {
                state.timeRemaining
            }

        updateNotification(
            title = getNotificationTitle(),
            text = formatTime(timeToDisplay),
            showPauseButton = false,
            timeLeft = timeToDisplay
        )
        broadcastTimerUpdate(formatTime(timeToDisplay))
    }

    private fun resumeTimer() {
        val state = TimerStateManager.state.value

        TimerStateManager.updateState {
            copy(isRunning = true, isPaused = false)
        }

        val isFocusPhase = (state.isPomodoroMode && state.pomodoroPhase == PomodoroPhase.FOCUS) ||
                (state.isCountUpMode && state.pomodoroPhase == PomodoroPhase.FOCUS)
        prefs.edit {
            putBoolean(
                "focus_mode",
                isFocusPhase || (!state.isPomodoroMode && !state.isCountUpMode)
            )
        }

        if (isFocusPhase || (!state.isPomodoroMode && !state.isCountUpMode)) {
            enableDNDIfNeeded()
        }

        val timeToDisplay =
            if (state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK) {
                state.focusTimeElapsed
            } else {
                state.timeRemaining
            }

        updateNotification(
            title = getNotificationTitle(),
            text = if (state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK) formatTime(
                timeToDisplay
            ) else getString(R.string.time_remaining, formatTime(timeToDisplay)),
            showPauseButton = !state.isStrictMode,
            timeLeft = timeToDisplay
        )

        if (state.isCountUpMode) {
            startCountUp()
        } else {
            startCountdown(state.timeRemaining)
        }
    }

    private fun restartCurrentPhase() {
        countDownTimer?.cancel()
        countUpJob?.cancel()

        val state = TimerStateManager.state.value
        val currentPhaseType = when (state.pomodoroPhase) {
            PomodoroPhase.SHORT_BREAK -> PhaseType.SHORT_BREAK
            PomodoroPhase.LONG_BREAK -> PhaseType.LONG_BREAK
            else -> PhaseType.FOCUS
        }
        FocusStats.endPhase(isCompleted = false)
        FocusStats.startPhase(currentPhaseType, initialDuration)

        if (state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK) {
            TimerStateManager.updateState {
                copy(
                    focusTimeElapsed = 0,
                    breakBudget = 0,
                    timeRemaining = 0,
                    isPaused = false,
                    isRunning = true
                )
            }

            prefs.edit { putBoolean("focus_mode", true) }

            updateNotification(
                title = getNotificationTitle(),
                text = buildNotificationText(TimerStateManager.state.value, 0),
                showPauseButton = !TimerStateManager.state.value.isStrictMode,
                timeLeft = 0
            )
            broadcastTimerUpdate(formatTime(0))
            startCountUp()
        } else {
            TimerStateManager.updateState {
                copy(
                    timeRemaining = initialDuration,
                    isPaused = false,
                    isRunning = true
                )
            }

            prefs.edit { putBoolean("focus_mode", true) }

            updateNotification(
                title = getNotificationTitle(),
                text = buildNotificationText(TimerStateManager.state.value, initialDuration),
                showPauseButton = !TimerStateManager.state.value.isStrictMode,
                timeLeft = initialDuration
            )
            broadcastTimerUpdate(formatTime(initialDuration))
            startCountdown(initialDuration)
        }
    }

    private fun updateNotificationAndBroadcast(millisUntilFinished: Long) {
        val state = TimerStateManager.state.value
        val formattedTime = formatTime(millisUntilFinished)

        updateNotification(
            title = getNotificationTitle(),
            text = buildNotificationText(state, millisUntilFinished),
            showPauseButton = !state.isStrictMode && !state.isPaused,
            timeLeft = millisUntilFinished
        )
        broadcastTimerUpdate(formattedTime)
    }

    private fun handleTimerComplete() {
        val state = TimerStateManager.state.value

        if (state.isCountUpMode) {
            if (state.pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK) {
                resumeCountUpAfterBreak()
            } else {
                // For count up focus, user manually stops. 
                // But if they reached some internal limit if we add one later.
            }
        } else if (!state.isPomodoroMode) {
            endSession()
        } else {
            transitionPomodoroPhase()
        }
    }

    private fun endSession() {
        TimerStateManager.updateState {
            copy(isRunning = false, isPaused = false)
        }

        prefs.edit { putBoolean("focus_mode", false) }

        FocusStats.endSession(isCompleted = true)

        broadcastTimerUpdate("00:00")
        TimerStateManager.reset()
        restoreDND()
        showFocusCompleteNotification()
        stopSelf()
    }

    private fun transitionPomodoroPhase() {
        val state = TimerStateManager.state.value
        val config = TimerStateManager.getPomodoroConfig() ?: return endSession()

        val nextPhase = calculateNextPhase(state, config)

        FocusStats.endPhase(isCompleted = true)

        if (nextPhase.isComplete) {
            prefs.edit {
                putBoolean("pomodoro_mode", false)
                remove("pomodoro_current_cycle")
            }
            endSession()
            return
        }

        val nextPhaseType = when (nextPhase.phase) {
            PomodoroPhase.SHORT_BREAK -> PhaseType.SHORT_BREAK
            PomodoroPhase.LONG_BREAK -> PhaseType.LONG_BREAK
            else -> PhaseType.FOCUS
        }

        val shouldAutoStart = when (nextPhase.phase) {
            PomodoroPhase.FOCUS -> prefs.getBoolean("auto_start_pomodoro", true)
            PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK -> prefs.getBoolean(
                "auto_start_breaks",
                false
            )

            else -> false
        }

        TimerStateManager.updateState {
            copy(
                pomodoroPhase = nextPhase.phase,
                currentCycle = nextPhase.currentCycle,
                timeRemaining = nextPhase.duration,
                isRunning = shouldAutoStart,
                isPaused = !shouldAutoStart
            )
        }

        prefs.edit {
            putInt("pomodoro_current_cycle", nextPhase.currentCycle)
            putBoolean("focus_mode", shouldAutoStart && nextPhase.phase == PomodoroPhase.FOCUS)
        }

        initialDuration = nextPhase.duration
        notificationStyle = null
        FocusStats.startPhase(nextPhaseType, nextPhase.duration)

        if (nextPhase.phase == PomodoroPhase.FOCUS) {
            if (shouldAutoStart) {
                enableDNDIfNeeded()
            }
            if (prefs.getBoolean("break_alerts", true)) {
                showBreakEndedNotification()
            }
        } else {
            restoreDND()
        }

        if (prefs.getBoolean("pomodoro_sound_enabled", true)) {
            playTransitionSound()
        }

        if (prefs.getBoolean("pomodoro_vibration_enabled", true)) {
            AndroidUtilities.vibrate(this, 1000)
        }

        val notificationText = if (shouldAutoStart) {
            buildNotificationText(TimerStateManager.state.value, nextPhase.duration)
        } else {
            getString(R.string.tap_to_start_next_phase)
        }

        updateNotification(
            title = getNotificationTitle(),
            text = notificationText,
            showPauseButton = shouldAutoStart && !state.isStrictMode,
            timeLeft = nextPhase.duration
        )
        broadcastTimerUpdate(formatTime(nextPhase.duration))

        if (shouldAutoStart) {
            startCountdown(nextPhase.duration)
        }
    }

    private data class NextPhaseResult(
        val phase: PomodoroPhase,
        val duration: Long,
        val currentCycle: Int,
        val isComplete: Boolean
    )

    private fun calculateNextPhase(
        state: TimerSessionState,
        config: PomodoroConfig
    ): NextPhaseResult {
        return when (state.pomodoroPhase) {
            PomodoroPhase.FOCUS -> {
                if (state.currentCycle >= state.totalCycles) {
                    NextPhaseResult(
                        phase = PomodoroPhase.LONG_BREAK,
                        duration = config.longBreakDuration,
                        currentCycle = 0,
                        isComplete = false
                    )
                } else {
                    NextPhaseResult(
                        phase = PomodoroPhase.SHORT_BREAK,
                        duration = config.shortBreakDuration,
                        currentCycle = state.currentCycle + 1,
                        isComplete = false
                    )
                }
            }

            PomodoroPhase.LONG_BREAK -> {
                NextPhaseResult(
                    phase = PomodoroPhase.COMPLETE,
                    duration = 0,
                    currentCycle = 0,
                    isComplete = true
                )
            }

            else -> {
                NextPhaseResult(
                    phase = PomodoroPhase.FOCUS,
                    duration = config.focusDuration,
                    currentCycle = state.currentCycle,
                    isComplete = false
                )
            }
        }
    }

    private fun updateProgressSegments() {
        val state = TimerStateManager.state.value
        val config = TimerStateManager.getPomodoroConfig()

        notificationStyle = NotificationCompat.ProgressStyle().also { style ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && state.isPomodoroMode && config != null) {
                val primaryColor = runCatching { App.colorScheme.primary.toArgb() }.getOrDefault(0xFF6750A4.toInt())
                val tertiaryColor = runCatching { App.colorScheme.tertiaryContainer.toArgb() }.getOrDefault(0xFFE8DEF8.toInt())
                for (i in 0 until state.totalCycles * 2) {
                    when {
                        i % 2 == 0 -> style.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(config.focusDuration.toInt())
                                .setColor(primaryColor)
                        )

                        i != state.totalCycles * 2 - 1 -> style.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(config.shortBreakDuration.toInt())
                                .setColor(tertiaryColor)
                        )

                        else -> style.addProgressSegment(
                            NotificationCompat.ProgressStyle.Segment(config.longBreakDuration.toInt())
                                .setColor(tertiaryColor)
                        )
                    }
                }
            } else {
                style.addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(
                        initialDuration.toInt().coerceAtLeast(1)
                    )
                )
            }
        }
    }

    private fun calculateCumulativeProgress(timeLeft: Long): Int {
        val state = TimerStateManager.state.value
        val config = TimerStateManager.getPomodoroConfig()
        val elapsed = (initialDuration - timeLeft).coerceAtLeast(0L)

        if (!state.isPomodoroMode || config == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return elapsed.toInt()
        }

        val n = state.currentCycle
        val previousTime: Long = when (state.pomodoroPhase) {
            PomodoroPhase.FOCUS ->
                (n - 1).toLong() * (config.focusDuration + config.shortBreakDuration)

            PomodoroPhase.SHORT_BREAK ->
                (n - 1).toLong() * config.focusDuration + (n - 2).coerceAtLeast(0)
                    .toLong() * config.shortBreakDuration

            PomodoroPhase.LONG_BREAK ->
                state.totalCycles.toLong() * config.focusDuration + (state.totalCycles - 1).toLong() * config.shortBreakDuration

            else -> 0L
        }

        return (previousTime + elapsed).toInt()
    }

    private fun createNotification(
        title: String,
        text: String,
        showPauseButton: Boolean,
        timeLeft: Long = 0
    ): Notification {
        val isStrictMode = TimerStateManager.state.value.isStrictMode

        if (notificationBuilder == null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_TIME_LEFT, text)
                putExtra("navigate_to_timer", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            notificationBuilder = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.hourglass)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (notificationStyle == null) updateProgressSegments()

        val chipText = "${TimeUnit.MILLISECONDS.toMinutes(timeLeft)}m"

        return notificationBuilder!!.apply {
            setContentTitle(title)
            setContentText(text)
            setStyle(notificationStyle!!.setProgress(calculateCumulativeProgress(timeLeft)))
            setWhen(System.currentTimeMillis() + timeLeft)
            setShortCriticalText(chipText)
            setRequestPromotedOngoing(
                prefs.getBoolean("prominent_focus_notification", true)
            )

            clearActions()

            val state = TimerStateManager.state.value
            val isBreak =
                state.pomodoroPhase == PomodoroPhase.SHORT_BREAK || state.pomodoroPhase == PomodoroPhase.LONG_BREAK

            if (!isStrictMode || (!showPauseButton && isBreak && state.isPaused)) {
                val action = if (showPauseButton) {
                    ACTION_PAUSE to getString(R.string.notification_pause)
                } else {
                    ACTION_RESUME to getString(R.string.notification_resume)
                }

                val actionIntent =
                    Intent(this@FocusModeService, FocusModeService::class.java).apply {
                        this.action = action.first
                    }

                val actionPendingIntent = PendingIntent.getService(
                    this@FocusModeService,
                    if (showPauseButton) 1 else 2,
                    actionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                addAction(
                    NotificationCompat.Action.Builder(0, action.second, actionPendingIntent).build()
                )
            }
        }.build()
    }

    private fun buildNotificationText(state: TimerSessionState, timeLeft: Long): String {
        return if (state.isCountUpMode && state.pomodoroPhase != PomodoroPhase.COUNT_UP_BREAK) {
            formatTime(state.focusTimeElapsed.coerceAtLeast(0L))
        } else {
            getString(R.string.time_remaining, formatTime(timeLeft))
        }
    }

    private fun updateNotification(
        title: String,
        text: String,
        showPauseButton: Boolean,
        timeLeft: Long = 0
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(title, text, showPauseButton, timeLeft)
        )
    }

    private fun broadcastTimerUpdate(formattedTime: String) {
        val state = TimerStateManager.state.value
        val intent = Intent(ACTION_TIMER_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TIME_LEFT, formattedTime)
            putExtra(EXTRA_TIMER_STATE, state.pomodoroPhase.name)
            addFlags(FLAG_RECEIVER_FOREGROUND)
        }
        sendBroadcast(intent)
    }

    private fun playTransitionSound() {
        try {
            val soundUriString = prefs.getString("pomodoro_sound", null)
            val soundUri = if (soundUriString.isNullOrEmpty()) {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            } else {
                soundUriString.toUri()
            }

            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, soundUri)

            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enableDNDIfNeeded() {
        if (!prefs.getBoolean("enable_dnd", false)) return

        if (systemNotificationManager.isNotificationPolicyAccessGranted) {
            previousInterruptionFilter = systemNotificationManager.currentInterruptionFilter
            systemNotificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        }
    }

    private fun restoreDND() {
        if (previousInterruptionFilter != null) {
            if (systemNotificationManager.isNotificationPolicyAccessGranted) {
                systemNotificationManager.setInterruptionFilter(
                    previousInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                )
                previousInterruptionFilter = null
            }
        }
    }

    private fun showBreakEndedNotification() {
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.break_ended_title))
            .setContentText(getString(R.string.break_ended_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BREAK_ALERT_NOTIFICATION_ID, notification)
    }

    private fun showFocusCompleteNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val continueIntent = Intent(this, FocusModeService::class.java).apply {
            action = ACTION_START
        }
        val continuePendingIntent = PendingIntent.getService(
            this,
            4,
            continueIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = try {
            val soundUriString = prefs.getString("pomodoro_sound", null)
            if (soundUriString.isNullOrEmpty()) {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            } else {
                soundUriString.toUri()
            }
        } catch (_: Exception) {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        }

        val notification = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.focus_session_complete))
            .setContentText(getString(R.string.focus_session_complete_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    getString(R.string.notification_continue),
                    continuePendingIntent
                ).build()
            )
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        countUpJob?.cancel()
        serviceScope.cancel()
        notificationManager.cancel(NOTIFICATION_ID)
        restoreDND()

        if (FocusStats.activeSession != null) {
            FocusStats.endSession(isCompleted = false)
        }

        prefs.edit { putBoolean("focus_mode", false) }
        TimerStateManager.reset()
    }
}
