package dev.pranav.reef.screens

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.pranav.reef.R
import dev.pranav.reef.accessibility.BlockerService
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.ui.Typography
import dev.pranav.reef.util.isAccessibilityServiceEnabledForBlocker
import dev.pranav.reef.util.isBlockerServiceOperational
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    onNavigateToTimer: () -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToWebsiteBlocklist: () -> Unit,
    onNavigateToMindfulLaunch: () -> Unit,
    onNavigateToIntro: () -> Unit,
    onRequestAccessibility: () -> Unit,
    @Suppress("UNUSED_PARAMETER") slideProgress: Float = 0f,
    onSlideProgressChange: (Float) -> Unit = {},
    currentTimeLeft: String = "00:00",
    currentTimerState: String = "FOCUS",
    whitelistedAppsCount: Int = 0,
    mindfulAppsCount: Int = 0,
    isMindfulLaunchEnabled: Boolean = false,
    dailyUsageText: String = "0m today"
) {
    val context = LocalContext.current
    val timerState by TimerStateManager.state.collectAsState()
    val blockerConnected by BlockerService.connectionState.collectAsState()
    var showDiscordDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var showServiceHealthWarning by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    LaunchedEffect(Unit) {
        if (prefs.getBoolean("first_run", true)) {
            onNavigateToIntro()
        } else {
            delay(500.milliseconds)
            if (!prefs.getBoolean("discord_shown", false)) {
                showDiscordDialog = true
            } else if (prefs.getBoolean("show_dialog", false)) {
                showDonateDialog = true
            }
        }
    }

    LaunchedEffect(blockerConnected) {
        if (blockerConnected) {
            showServiceHealthWarning = false
        } else {
            delay(1_500)
            showServiceHealthWarning =
                context.isAccessibilityServiceEnabledForBlocker() &&
                        !BlockerService.isConnected
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (showServiceHealthWarning) {
                Card(
                    onClick = onRequestAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.accessibility_service_not_running),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(
                                    R.string.accessibility_service_not_running_description
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            FocusHeader(
                onSlideProgress = onSlideProgressChange,
                onClick = {
                    if (context.isBlockerServiceOperational()) {
                        onNavigateToTimer()
                    } else {
                        onRequestAccessibility()
                    }
                }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToUsage() }
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.app_usage),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dailyUsageText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 16.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToWhitelist() }
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.whitelist_apps),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = LocalResources.current.getQuantityString(
                                    R.plurals.whitelisted_apps,
                                    whitelistedAppsCount,
                                    whitelistedAppsCount
                                ),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Text(
                text = "Configure limits",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )

            val totalRows = 4
            val isTimerActive = timerState.isRunning || timerState.isPaused
            val stateText = when (currentTimerState) {
                "FOCUS" -> stringResource(R.string.focus_label)
                "SHORT_BREAK" -> stringResource(R.string.short_break_label)
                "LONG_BREAK" -> stringResource(R.string.long_break_label)
                else -> stringResource(R.string.focus_label)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeNavigationRow(
                    title = stringResource(R.string.routines),
                    subtitle = stringResource(R.string.sample_routine),
                    icon = Icons.AutoMirrored.Rounded.EventNote,
                    index = 0,
                    totalItems = totalRows,
                    onClick = onNavigateToRoutines
                )

                HomeNavigationRow(
                    title = stringResource(R.string.website_blocklist),
                    subtitle = stringResource(R.string.website_blocklist_desc),
                    icon = Icons.Rounded.Public,
                    index = 1,
                    totalItems = totalRows,
                    onClick = onNavigateToWebsiteBlocklist
                )

                HomeNavigationRow(
                    title = stringResource(R.string.mindful_launch),
                    subtitle = if (!isMindfulLaunchEnabled) {
                        stringResource(R.string.disabled)
                    } else {
                        LocalResources.current.getQuantityString(
                            R.plurals.mindful_apps_count,
                            mindfulAppsCount,
                            mindfulAppsCount
                        )
                    },
                    icon = Icons.Rounded.HourglassEmpty,
                    index = 2,
                    totalItems = totalRows,
                    iconTint = if (isMindfulLaunchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.5f
                    ),
                    onClick = onNavigateToMindfulLaunch
                )

                HomeNavigationRow(
                    title = if (isTimerActive) stateText else stringResource(R.string.pomodoro),
                    subtitle = if (isTimerActive) {
                        if (timerState.isPaused) stringResource(
                            R.string.paused_focus_session,
                            currentTimeLeft
                        )
                        else stringResource(R.string.in_progress_focus_session, currentTimeLeft)
                    } else {
                        stringResource(R.string.start_focus_session)
                    },
                    icon = if (timerState.isPaused) Icons.Rounded.Pause else if (timerState.isRunning) Icons.Rounded.PlayArrow else Icons.Rounded.Timer,
                    index = 3,
                    totalItems = totalRows,
                    titleColor = if (isTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    iconTint = if (isTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    trailingTint = if (isTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onNavigateToTimer
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        if (showDiscordDialog) {
            CommunityDialog(
                onDismiss = {
                    prefs.edit { putBoolean("discord_shown", true) }
                    showDiscordDialog = false
                },
                onJoin = {
                    prefs.edit { putBoolean("discord_shown", true) }
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://discord.gg/46wCMRVAre".toUri()
                        )
                    )
                    showDiscordDialog = false
                }
            )
        }

        if (showDonateDialog) {
            DonateDialog(
                onDismiss = {
                    prefs.edit { putBoolean("show_dialog", false) }
                    showDonateDialog = false
                },
                onSupport = {
                    prefs.edit { putBoolean("show_dialog", false) }
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://PranavPurwar.github.io/donate.html".toUri()
                        )
                    )
                    showDonateDialog = false
                }
            )
        }
    }
}

@Composable
fun FocusHeader(
    onSlideProgress: (Float) -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HoldToFocusAnchor(
            onProgressChanged = onSlideProgress,
            onTrigger = onClick
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.focus_mode),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = Typography.DMSerif
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Hold down the ring to begin your focus session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HoldToFocusAnchor(
    onProgressChanged: (Float) -> Unit,
    onTrigger: () -> Unit
) {
    var isPressing by remember { mutableStateOf(false) }
    val progressAnim = remember { Animatable(0f) }

    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val innerCoreColor by animateColorAsState(
        targetValue = if (isPressing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(durationMillis = 100),
        label = "core_color"
    )

    LaunchedEffect(progressAnim.value) {
        onProgressChanged(progressAnim.value)
        if (progressAnim.value >= 1f) {
            isPressing = false
            onTrigger()
            progressAnim.snapTo(0f)
        }
    }

    LaunchedEffect(isPressing) {
        if (isPressing) {
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1000,
                    easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f) // Snappy acceleration curve
                )
            )
        } else {
            if (progressAnim.value < 1f) {
                progressAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessHigh
                    )
                )
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        try {
                            isPressing = true
                            awaitRelease()
                        } finally {
                            isPressing = false
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringStrokeWidth = 4.dp.toPx()

            drawCircle(
                color = trackColor,
                style = Stroke(width = ringStrokeWidth)
            )

            drawArc(
                color = activeColor,
                startAngle = -90f,
                sweepAngle = progressAnim.value * 360f,
                useCenter = false,
                style = Stroke(width = ringStrokeWidth, cap = StrokeCap.Round)
            )
        }

        Surface(
            modifier = Modifier.size(148.dp),
            shape = CircleShape,
            color = innerCoreColor,
            tonalElevation = 1.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.Waves,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = if (isPressing) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isPressing) "HOLDING..." else "DIVE IN",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    ),
                    color = if (isPressing) activeColor else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HomeNavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    index: Int,
    totalItems: Int,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailingTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    val containerShape = when {
        totalItems == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        index == totalItems - 1 -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 24.dp,
            bottomEnd = 24.dp
        )

        else -> RoundedCornerShape(4.dp)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = containerShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(4.dp),
            headlineContent = {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            },
            supportingContent = {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = iconTint
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = trailingTint
                )
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
private fun CommunityDialog(
    onDismiss: () -> Unit,
    onJoin: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.Groups,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.join_community),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = stringResource(R.string.join_community_desc),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onJoin, shapes = ButtonDefaults.shapes()) {
                Icon(
                    Icons.Rounded.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.join_discord))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.maybe_later))
            }
        }
    )
}

@Composable
private fun DonateDialog(
    onDismiss: () -> Unit,
    onSupport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.support_development),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.support_development_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.any_amount_helps),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            Button(onClick = onSupport, shapes = ButtonDefaults.shapes()) {
                Icon(
                    Icons.Rounded.VolunteerActivism,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.support_development))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.maybe_later))
            }
        }
    )
}
