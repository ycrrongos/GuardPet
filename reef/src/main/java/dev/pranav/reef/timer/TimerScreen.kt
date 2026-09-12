package dev.pranav.reef.timer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavController
import dev.pranav.reef.R
import dev.pranav.reef.navigation.Screen
import dev.pranav.reef.ui.Typography.DMSerif
import dev.pranav.reef.util.AndroidUtilities.formatTime
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface TimerConfig {
    data class Simple(val minutes: Int, val strictMode: Boolean) : TimerConfig
    data class Pomodoro(
        val focusMinutes: Int,
        val shortBreakMinutes: Int,
        val longBreakMinutes: Int,
        val cycles: Int,
        val strictMode: Boolean
    ) : TimerConfig

    data class CountUp(val ratio: Int, val strictMode: Boolean) : TimerConfig
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerContent(
    navController: NavController,
    isTimerRunning: Boolean,
    isPaused: Boolean,
    currentTimeLeft: String,
    currentTimerState: String,
    isStrictMode: Boolean,
    isZenMode: Boolean = false,
    onZenModeChange: (Boolean) -> Unit = {},
    onStartTimer: (TimerConfig) -> Unit,
    onPauseTimer: () -> Unit,
    onResumeTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onRestartTimer: () -> Unit,
    onTakeBreak: () -> Unit = {}
) {
    val showRunningView = isTimerRunning || isPaused
    var selectedMode by remember {
        mutableIntStateOf(
            if (prefs.getBoolean("timer_is_count_up", false)) 1 else 0
        )
    }

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    BackHandler(enabled = isZenMode) {
        onZenModeChange(false)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!(showRunningView && isZenMode)) {
                Column(modifier = Modifier.animateContentSize()) {
                    MediumTopAppBar(
                        title = {
                            Text(stringResource(R.string.focus_mode_title))
                        },
                        actions = {
                            if (!showRunningView) {
                                IconButton(onClick = { navController.navigate(Screen.FocusStats) }) {
                                    Icon(
                                        Icons.Outlined.BarChart,
                                        contentDescription = "Focus Stats"
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        scrollBehavior = scrollBehavior
                    )
                }
            }
        }) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            AnimatedContent(
                targetState = showRunningView,
                label = "timer_view_transition"
            ) { running ->
                if (running) {
                    RunningTimerView(
                        timeLeft = currentTimeLeft,
                        timerState = currentTimerState,
                        isPaused = isPaused,
                        isStrictMode = isStrictMode,
                        isZenMode = isZenMode,
                        onZenModeChange = onZenModeChange,
                        onPause = onPauseTimer,
                        onResume = onResumeTimer,
                        onCancel = onCancelTimer,
                        onRestart = onRestartTimer,
                        onTakeBreak = onTakeBreak,
                        onOpenStats = { navController.navigate(Screen.FocusStats) }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Spacer(Modifier.height(4.dp))

                        FocusModeGroup(
                            selectedMode = selectedMode,
                            onSelectionChange = {
                                selectedMode = it
                                if (it < 2) {
                                    prefs.edit {
                                        putBoolean("timer_is_count_up", it == 1)
                                    }
                                }
                            }
                        )

                        when (selectedMode) {
                            0 -> SimpleFocusSetup(
                                isCountUp = false,
                                onStart = onStartTimer
                            )

                            1 -> SimpleFocusSetup(
                                isCountUp = true,
                                onStart = onStartTimer
                            )

                            else -> PomodoroFocusSetup(
                                onStart = onStartTimer
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FocusModeGroup(
    selectedMode: Int, onSelectionChange: (Int) -> Unit
) {
    val modes = listOf(
        stringResource(R.string.timer_tab),
        stringResource(R.string.count_up_tab),
        stringResource(R.string.pomodoro_tab)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            ButtonGroupDefaults.ConnectedSpaceBetween
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        modes.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selectedMode,
                onCheckedChange = {
                    if (index != selectedMode) onSelectionChange(index)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton }
            ) {
                Text(label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SimpleFocusSetup(
    isCountUp: Boolean,
    onStart: (TimerConfig) -> Unit
) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }
    var ratio by remember { mutableIntStateOf(prefs.getInt("timer_count_up_ratio", 5)) }
    var isStrictMode by remember { mutableStateOf(false) }
    var previewTime by remember { mutableStateOf(LocalDateTime.now()) }

    val totalMinutes = hours * 60 + minutes
    val focusEndTime = remember(previewTime, totalMinutes) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault()).format(previewTime.plusMinutes(totalMinutes.toLong()))
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30.seconds)
            previewTime = LocalDateTime.now()
        }
    }

    LaunchedEffect(isCountUp) {
        if (isCountUp) isStrictMode = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (isCountUp) {
            CountUpRatioDisplay(ratio)
        } else {
            CountdownDurationDisplay(
                hours = hours,
                minutes = minutes,
                focusEndTime = focusEndTime
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (isCountUp) {
                CountUpRatioControls(
                    ratio = ratio,
                    onDecrease = {
                        if (ratio > 1) {
                            ratio--
                            prefs.edit { putInt("timer_count_up_ratio", ratio) }
                        }
                    },
                    onIncrease = {
                        if (ratio < 10) {
                            ratio++
                            prefs.edit { putInt("timer_count_up_ratio", ratio) }
                        }
                    }
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CountdownControls(
                            hours = hours,
                            minutes = minutes,
                            totalMinutes = totalMinutes,
                            onHoursIncrease = { if (hours < 12) hours++ },
                            onHoursDecrease = { if (hours > 0) hours-- },
                            onMinutesIncrease = {
                                if (minutes < 59) {
                                    minutes++
                                } else if (hours < 12) {
                                    hours++
                                    minutes = 0
                                }
                            },
                            onMinutesDecrease = {
                                if (minutes > 0) {
                                    minutes--
                                } else if (hours > 0) {
                                    hours--
                                    minutes = 59
                                }
                            },
                            onPresetSelected = { presetMinutes ->
                                hours = presetMinutes / 60
                                minutes = presetMinutes % 60
                            }
                        )
                    }
                }

                SessionBehaviorControl(
                    isStrictMode = isStrictMode,
                    onStrictModeChange = { isStrictMode = it }
                )
            }

            Button(
                onClick = {
                    if (isCountUp) {
                        onStart(TimerConfig.CountUp(ratio, false))
                    } else {
                        onStart(TimerConfig.Simple(totalMinutes, isStrictMode))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                enabled = isCountUp || totalMinutes > 0,
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCountUp) {
                        stringResource(R.string.start_count_up)
                    } else {
                        stringResource(R.string.start)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun CountdownDurationDisplay(
    hours: Int,
    minutes: Int,
    focusEndTime: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "${hours.toString().padStart(2, '0')}:${
                minutes.toString().padStart(2, '0')
            }",
            fontFamily = DMSerif,
            fontSize = 96.sp,
            lineHeight = 104.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.focus_ends_at, focusEndTime),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CountdownControls(
    hours: Int,
    minutes: Int,
    totalMinutes: Int,
    onHoursIncrease: () -> Unit,
    onHoursDecrease: () -> Unit,
    onMinutesIncrease: () -> Unit,
    onMinutesDecrease: () -> Unit,
    onPresetSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TimeUnitControls(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.hours),
                increaseDescription = stringResource(R.string.increase_hours),
                decreaseDescription = stringResource(R.string.decrease_hours),
                canIncrease = hours < 12,
                canDecrease = hours > 0,
                onIncrease = onHoursIncrease,
                onDecrease = onHoursDecrease
            )
            TimeUnitControls(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.minutes),
                increaseDescription = stringResource(R.string.increase_minutes),
                decreaseDescription = stringResource(R.string.decrease_minutes),
                canIncrease = hours < 12 || minutes < 59,
                canDecrease = totalMinutes > 0,
                onIncrease = onMinutesIncrease,
                onDecrease = onMinutesDecrease
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(5, 15, 30, 60, 120).forEach { preset ->
                AssistChip(
                    onClick = { onPresetSelected(preset) },
                    label = {
                        Text(
                            if (preset < 60) {
                                pluralStringResource(
                                    R.plurals.minutes_label,
                                    preset,
                                    preset
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.hours_label,
                                    preset / 60,
                                    preset / 60
                                )
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeUnitControls(
    modifier: Modifier,
    label: String,
    increaseDescription: String,
    decreaseDescription: String,
    canIncrease: Boolean,
    canDecrease: Boolean,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        RepeatableTimerButton(
            icon = Icons.Rounded.Remove,
            contentDescription = decreaseDescription,
            enabled = canDecrease,
            onClick = onDecrease
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RepeatableTimerButton(
            icon = Icons.Rounded.Add,
            contentDescription = increaseDescription,
            enabled = canIncrease,
            onClick = onIncrease
        )
    }
}

@Composable
private fun CountUpRatioDisplay(ratio: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.count_up_ratio_label),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.ratio_format, ratio),
            fontFamily = DMSerif,
            fontSize = 96.sp,
            lineHeight = 104.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.count_up_ratio_description, ratio),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CountUpRatioControls(
    ratio: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        FilledTonalIconButton(
            onClick = onDecrease,
            enabled = ratio > 1
        ) {
            Icon(Icons.Rounded.Remove, stringResource(R.string.decrease))
        }
        Spacer(Modifier.width(32.dp))
        FilledTonalIconButton(
            onClick = onIncrease,
            enabled = ratio < 10
        ) {
            Icon(Icons.Rounded.Add, stringResource(R.string.increase))
        }
    }
}

@Composable
private fun SessionBehaviorControl(
    isStrictMode: Boolean,
    onStrictModeChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isStrictMode) {
                        Icons.Rounded.Lock
                    } else {
                        Icons.Rounded.LockOpen
                    },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = if (isStrictMode) {
                            stringResource(R.string.strict_mode)
                        } else {
                            stringResource(R.string.flexible_mode)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isStrictMode) {
                            stringResource(R.string.no_pausing_allowed)
                        } else {
                            stringResource(R.string.pause_resume_anytime)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isStrictMode,
                onCheckedChange = onStrictModeChange
            )
        }
    }
}

@Composable
private fun RepeatableTimerButton(
    icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }

    Surface(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                if (enabled) {
                    onClick {
                        currentOnClick()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures

                        currentOnClick()
                        coroutineScope {
                            val repeatJob = launch {
                                delay(400.milliseconds)
                                while (true) {
                                    currentOnClick()
                                    delay(80.milliseconds)
                                }
                            }
                            tryAwaitRelease()
                            repeatJob.cancel()
                        }
                    })
            },
        shape = IconButtonDefaults.extraLargeSquareShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PomodoroFocusSetup(
    onStart: (TimerConfig) -> Unit
) {
    var focusMinutes by remember { mutableIntStateOf(prefs.getInt("pomodoro_focus_minutes", 25)) }
    var shortBreakMinutes by remember {
        mutableIntStateOf(
            prefs.getInt(
                "pomodoro_short_break_minutes", 5
            )
        )
    }
    var longBreakMinutes by remember {
        mutableIntStateOf(
            prefs.getInt(
                "pomodoro_long_break_minutes", 15
            )
        )
    }
    var cycles by remember { mutableIntStateOf(prefs.getInt("pomodoro_cycles", 4)) }
    var isStrictMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.pomodoro_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    PomodoroMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.focus_label),
                        value = focusMinutes,
                        suffix = stringResource(R.string.min_short_suffix)
                    )
                    PomodoroMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.short_break_label),
                        value = shortBreakMinutes,
                        suffix = stringResource(R.string.min_short_suffix)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    PomodoroMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.long_break_label),
                        value = longBreakMinutes,
                        suffix = stringResource(R.string.min_short_suffix)
                    )
                    PomodoroMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.cycles_label),
                        value = cycles,
                        suffix = ""
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PomodoroAdjustment(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.focus_label),
                            value = focusMinutes,
                            suffix = stringResource(R.string.min_short_suffix),
                            range = 1..120,
                            onValueChange = {
                                focusMinutes = it
                                prefs.edit { putInt("pomodoro_focus_minutes", it) }
                            }
                        )
                        PomodoroAdjustment(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.short_break_label),
                            value = shortBreakMinutes,
                            suffix = stringResource(R.string.min_short_suffix),
                            range = 1..30,
                            onValueChange = {
                                shortBreakMinutes = it
                                prefs.edit { putInt("pomodoro_short_break_minutes", it) }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PomodoroAdjustment(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.long_break_label),
                            value = longBreakMinutes,
                            suffix = stringResource(R.string.min_short_suffix),
                            range = 1..60,
                            onValueChange = {
                                longBreakMinutes = it
                                prefs.edit { putInt("pomodoro_long_break_minutes", it) }
                            }
                        )
                        PomodoroAdjustment(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.cycles_label),
                            value = cycles,
                            suffix = "",
                            range = 1..10,
                            onValueChange = {
                                cycles = it
                                prefs.edit { putInt("pomodoro_cycles", it) }
                            }
                        )
                    }
                }
            }

            SessionBehaviorControl(
                isStrictMode = isStrictMode,
                onStrictModeChange = { isStrictMode = it }
            )

            Button(
                onClick = {
                    onStart(
                        TimerConfig.Pomodoro(
                            focusMinutes,
                            shortBreakMinutes,
                            longBreakMinutes,
                            cycles,
                            isStrictMode
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(
                    imageVector = Icons.TwoTone.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.start_pomodoro),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun PomodoroMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: Int,
    suffix: String
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "$value$suffix",
            style = MaterialTheme.typography.displayMedium,
            fontFamily = DMSerif,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PomodoroAdjustment(
    modifier: Modifier = Modifier,
    label: String,
    value: Int,
    suffix: String,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "$label · $value$suffix",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                modifier = Modifier.size(40.dp),
                enabled = value > range.first
            ) {
                Icon(Icons.Rounded.Remove, stringResource(R.string.decrease))
            }
            FilledTonalIconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                modifier = Modifier.size(40.dp),
                enabled = value < range.last
            ) {
                Icon(Icons.Rounded.Add, stringResource(R.string.increase))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunningTimerView(
    timeLeft: String,
    timerState: String,
    isPaused: Boolean,
    isStrictMode: Boolean,
    isZenMode: Boolean = false,
    onZenModeChange: (Boolean) -> Unit = {},
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit = {},
    onTakeBreak: () -> Unit = {},
    onOpenStats: () -> Unit = {}
) {
    val state by TimerStateManager.state.collectAsState()
    val isCountUpMode = state.isCountUpMode
    val isBreak =
        state.pomodoroPhase == PomodoroPhase.SHORT_BREAK || state.pomodoroPhase == PomodoroPhase.LONG_BREAK || state.pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 180.dp)
        ) {
            if (state.isPomodoroMode) {
                AssistChip(onClick = {}, label = {
                    Text(
                        text = stringResource(
                            if (timerState == "FOCUS") R.string.cycle_count else R.string.pomodoro_tab,
                            state.currentCycle,
                            state.totalCycles
                        )
                    )
                })
            } else if (isCountUpMode) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.count_up_mode_label)) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.TrendingUp, null, Modifier.size(16.dp)
                        )
                    })
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (timerState) {
                    "SHORT_BREAK", "LONG_BREAK", "COUNT_UP_BREAK" -> stringResource(R.string.short_break_label)
                    else -> stringResource(R.string.focus_label)
                },
                style = MaterialTheme.typography.displaySmall,
                color = if (isBreak) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isCountUpMode && !isBreak) formatTime(state.focusTimeElapsed) else timeLeft,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontFamily = DMSerif,
                fontSize = 112.sp,
                lineHeight = 120.sp
            )

            if (isCountUpMode && !isBreak) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Coffee, null, Modifier.size(18.dp))
                        Text(
                            text = stringResource(
                                R.string.earned_break_budget, formatTime(state.breakBudget)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (isPaused) Text(
                text = stringResource(R.string.paused_status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .fillMaxWidth(0.95f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onOpenStats,
                    modifier = Modifier.size(52.dp),
                    shapes = IconButtonDefaults.shapes()
                ) {
                    Icon(Icons.Outlined.BarChart, contentDescription = "Focus Stats")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { onZenModeChange(!isZenMode) },
                    modifier = Modifier.size(52.dp),
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    Icon(
                        if (isZenMode) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(
                            if (isZenMode) R.string.exit_zen_mode else R.string.enter_zen_mode
                        )
                    )
                }
            }
            RunningTimerActions(
                modifier = Modifier.fillMaxWidth(),
                isPaused = isPaused,
                isStrictMode = isStrictMode,
                isCountUpMode = isCountUpMode,
                canRedeemBreak = isCountUpMode && !isBreak && state.breakBudget > 0,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onTakeBreak = onTakeBreak,
                onRestart = onRestart
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunningTimerActions(
    modifier: Modifier = Modifier,
    isPaused: Boolean,
    isStrictMode: Boolean,
    isCountUpMode: Boolean = false,
    canRedeemBreak: Boolean = false,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onTakeBreak: () -> Unit = {},
    onRestart: () -> Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconToggleButton(
            checked = isPaused,
            onCheckedChange = { if (isPaused) onResume() else onPause() },
            modifier = Modifier
                .height(64.dp)
                .aspectRatio(1f),
            shapes = IconButtonDefaults.toggleableShapes()
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
        if (canRedeemBreak) {
            FilledTonalButton(
                onClick = onTakeBreak,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(
                    text = stringResource(R.string.redeem_break),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        if (!isStrictMode) {
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(
                    text = stringResource(if (isCountUpMode) R.string.stop else R.string.cancel),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            IconButton(
                onClick = onRestart,
                modifier = Modifier.size(64.dp),
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.iconButtonColors()
            ) {
                Icon(Icons.Filled.Replay, null)
            }
        }
    }
}
