package com.geekathon.guardpet

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.geekathon.guardpet.ui.GuardBottomNavBar
import com.geekathon.guardpet.ui.GuardHomeScreen
import com.geekathon.guardpet.ui.GuardSettingsFooter
import com.geekathon.guardpet.ui.ScheduleTabScreen
import dev.pranav.reef.PermissionsCheckActivity
import dev.pranav.reef.getDailyUsageForLastWeek
import dev.pranav.reef.navigation.Screen
import dev.pranav.reef.screens.CreateRoutineScreen
import dev.pranav.reef.screens.DailyLimitScreen
import dev.pranav.reef.screens.FocusSessionDetailScreen
import dev.pranav.reef.screens.FocusStatsScreen
import dev.pranav.reef.screens.HomeContent
import dev.pranav.reef.screens.MindfulLaunchAppsScreen
import dev.pranav.reef.screens.MindfulLaunchScreen
import dev.pranav.reef.screens.RoutinesScreen
import dev.pranav.reef.screens.SettingsContent
import dev.pranav.reef.screens.UsageScreenWrapper
import dev.pranav.reef.screens.WebsiteBlocklistScreen
import dev.pranav.reef.screens.WhitelistScreenWrapper
import dev.pranav.reef.timer.OverlayFocusSession
import dev.pranav.reef.timer.TimerConfig
import dev.pranav.reef.timer.TimerContent
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.AppLimits
import dev.pranav.reef.util.MindfulLaunchManager
import dev.pranav.reef.util.ScreenUsageHelper
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.applyDefaults
import dev.pranav.reef.util.checkAndRequestMissingPermissions
import dev.pranav.reef.util.hasUsageStatsPermission
import dev.pranav.reef.util.isAccessibilityServiceEnabledForBlocker
import dev.pranav.reef.util.isBlockerServiceOperational
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 守伴主页：五栏 MD3（首页 / 日程 / 统计 / 专注 / 设置）。
 * 专注计时走 [OverlayFocusSession]，不启动 FocusModeService。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var assets: PetAssetRepository
    private lateinit var settings: PetSettings
    private val focusSession = OverlayFocusSession()

    private var hasPromptedPermissions = false
    private var skipPermissionPromptOnce = false
    private var pendingFocusModeStart = false
    private var shouldNavigateToTimer = false
    private var shouldNavigateToSettings = false

    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    android.net.Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            uri?.let { prefs.edit { putString("pomodoro_sound", it.toString()) } }
        }
    }

    private val usageStatsManager by lazy { getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager }
    private val launcherApps by lazy { getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as dev.pranav.reef.App).initializeAfterUnlock()
        enableEdgeToEdge()
        applyDefaults()
        addExceptions()

        assets = PetAssetRepository(this)
        settings = PetSettings(this)

        shouldNavigateToTimer = intent?.getBooleanExtra(EXTRA_NAVIGATE_TIMER, false) == true ||
            intent?.getBooleanExtra("navigate_to_timer", false) == true
        shouldNavigateToSettings = intent?.getBooleanExtra(EXTRA_NAVIGATE_SETTINGS, false) == true ||
            intent?.getBooleanExtra("navigate_to_settings", false) == true

        if (intent?.getBooleanExtra(EXTRA_AUTO_EXTRACT, false) == true) {
            scheduleAutoExtract()
        }
        intent?.getStringExtra(EXTRA_DEBUG_BIGBANG)?.takeIf { it.isNotBlank() }?.let { raw ->
            skipPermissionPromptOnce = true
            val text = raw.replace("\\n", "\n")
            TextCaptureHolder.tokens = TextTokenizer.splitAll(listOf(text))
            startActivity(
                Intent(this, BigBangActivity::class.java).apply {
                    if (intent.getBooleanExtra(EXTRA_DEBUG_AUTO_AI, false)) {
                        putExtra(BigBangActivity.EXTRA_AUTO_AI_NOTE, true)
                    }
                }
            )
        }

        focusSession.onFocusStarted = { HabitRewardTracker.onFocusSessionStarted() }
        focusSession.onFocusCompleted = {
            HabitRewardTracker.onFocusSessionCompleted(this)
            Toast.makeText(this, R.string.focus_complete, Toast.LENGTH_LONG).show()
        }

        setContent {
            val navController = rememberNavController()
            val overlayUi by focusSession.state.collectAsState()
            val timerState by TimerStateManager.state.collectAsState()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val showAccessibilityDialog = remember { mutableStateOf(false) }
            var isZenMode by rememberSaveable { mutableStateOf(false) }

            var petTick by remember { mutableIntStateOf(0) }
            var previewState by remember { mutableStateOf(PetState.HAPPY) }
            var petRunning by remember { mutableStateOf(PetService.isRunning) }

            val whitelistedCount =
                remember { Whitelist.getWhitelistedLaunchableCount(launcherApps) }
            val mindfulAppsCount = remember { MindfulLaunchManager.getMindfulApps().size }
            val isMindfulLaunchEnabled = remember { MindfulLaunchManager.isEnabled() }

            val selectedNavIndex = remember(currentDestination) {
                when {
                    currentDestination?.hasRoute<Screen.Home>() == true -> 0
                    currentDestination?.hasRoute<Screen.Schedule>() == true -> 1
                    currentDestination?.hasRoute<Screen.Usage>() == true -> 2
                    currentDestination?.hasRoute<Screen.DailyLimit>() == true -> 2
                    currentDestination?.hasRoute<Screen.FocusHub>() == true -> 3
                    currentDestination?.hasRoute<Screen.Timer>() == true -> 3
                    currentDestination?.hasRoute<Screen.FocusStats>() == true -> 3
                    currentDestination?.hasRoute<Screen.Routines>() == true -> 3
                    currentDestination?.hasRoute<Screen.Whitelist>() == true -> 3
                    currentDestination?.hasRoute<Screen.WebsiteBlocklist>() == true -> 3
                    currentDestination?.hasRoute<Screen.MindfulLaunch>() == true -> 3
                    currentDestination?.hasRoute<Screen.MindfulLaunchApps>() == true -> 3
                    currentDestination?.hasRoute<Screen.Settings>() == true -> 4
                    else -> -1
                }
            }

            val showBottomBar = remember(currentDestination, isZenMode) {
                !isZenMode && (
                    currentDestination?.hasRoute<Screen.Home>() == true ||
                        currentDestination?.hasRoute<Screen.Schedule>() == true ||
                        currentDestination?.hasRoute<Screen.Usage>() == true ||
                        currentDestination?.hasRoute<Screen.FocusHub>() == true ||
                        currentDestination?.hasRoute<Screen.Timer>() == true ||
                        currentDestination?.hasRoute<Screen.Settings>() == true ||
                        currentDestination?.hasRoute<Screen.Whitelist>() == true ||
                        currentDestination?.hasRoute<Screen.Routines>() == true
                    )
            }

            LaunchedEffect(timerState.isRunning, timerState.isPaused) {
                if (!timerState.isRunning && !timerState.isPaused) {
                    isZenMode = false
                }
            }

            LaunchedEffect(shouldNavigateToTimer, shouldNavigateToSettings) {
                if (shouldNavigateToTimer) {
                    navController.navigate(Screen.FocusHub) { launchSingleTop = true }
                    navController.navigate(Screen.Timer) { launchSingleTop = true }
                    shouldNavigateToTimer = false
                } else if (shouldNavigateToSettings) {
                    navController.navigate(Screen.Settings) { launchSingleTop = true }
                    shouldNavigateToSettings = false
                }
            }

            var dailyUsageText by remember { mutableStateOf("0m today") }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val todayUsage = ScreenUsageHelper.fetchAppUsageTodayTillNow(usageStatsManager)
                    val totalUsageMinutes = todayUsage.values.sum() / 60
                    val hours = totalUsageMinutes / 60
                    val minutes = totalUsageMinutes % 60
                    val usageText = when {
                        hours > 0 && minutes > 0 ->
                            getString(dev.pranav.reef.R.string.hour_min_short_suffix, hours, minutes) +
                                " " + getString(dev.pranav.reef.R.string.today)
                        hours > 0 ->
                            getString(dev.pranav.reef.R.string.hours_short_format, hours) +
                                " " + getString(dev.pranav.reef.R.string.today)
                        minutes > 0 ->
                            getString(dev.pranav.reef.R.string.minutes_short_format, minutes) +
                                " " + getString(dev.pranav.reef.R.string.today)
                        else -> getString(dev.pranav.reef.R.string.less_than_one_minute)
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        dailyUsageText = usageText
                    }
                }
            }

            val statusMessage = when {
                !Settings.canDrawOverlays(this@MainActivity) -> getString(R.string.status_permission)
                petRunning -> getString(R.string.status_running)
                else -> getString(R.string.status_stopped)
            }
            val badgeRes = if (petRunning) R.string.badge_running else R.string.badge_stopped
            val heroMessageRes = when (previewState) {
                PetState.FEED -> R.string.hero_message_feed
                PetState.TOUCH -> R.string.hero_message_pet
                PetState.SLEEP -> R.string.hero_message_sleep
                else -> if (petRunning) R.string.hero_message_running else R.string.hero_message_idle
            }

            ReefTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showBottomBar,
                            enter = fadeIn() + slideInVertically { it },
                            exit = fadeOut() + slideOutVertically { it }
                        ) {
                            GuardBottomNavBar(
                                selectedItem = selectedNavIndex,
                                onItemSelected = { index ->
                                    val options = androidx.navigation.navOptions {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    when (index) {
                                        0 -> navController.navigate(Screen.Home) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = false
                                        }
                                        1 -> navController.navigate(Screen.Schedule, options)
                                        2 -> navController.navigate(Screen.Usage, options)
                                        3 -> navController.navigate(Screen.FocusHub, options)
                                        4 -> navController.navigate(Screen.Settings, options)
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                PaddingValues(
                                    innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                    0.dp,
                                    innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                    innerPadding.calculateBottomPadding()
                                )
                            ),
                        enterTransition = {
                            fadeIn(animationSpec = tween(300)) +
                                slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                                )
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(300)) +
                                slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                                )
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(300)) +
                                slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                                )
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(300)) +
                                slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                                )
                        }
                    ) {
                        composable<Screen.Home> {
                            GuardHomeScreen(
                                assets = assets,
                                settings = settings,
                                petRunning = petRunning,
                                statusMessage = statusMessage,
                                badgeRes = badgeRes,
                                heroMessageRes = heroMessageRes,
                                previewState = previewState,
                                onStartCompanion = {
                                    requestPermissionsAndStart {
                                        petRunning = PetService.isRunning
                                        petTick++
                                    }
                                },
                                onStopCompanion = {
                                    stopPet()
                                    petRunning = false
                                    previewState = PetState.HAPPY
                                    petTick++
                                },
                                onFeed = {
                                    feedPet {
                                        previewState = PetState.FEED
                                        petTick++
                                    }
                                },
                                onPet = {
                                    changeMood(PetState.TOUCH, 5) {
                                        previewState = PetState.TOUCH
                                        petTick++
                                    }
                                },
                                onSleep = {
                                    sendState(PetState.SLEEP) {
                                        previewState = PetState.SLEEP
                                        petTick++
                                    }
                                },
                                onOpenTodos = {
                                    startActivity(
                                        Intent(this@MainActivity, PetPanelActivity::class.java)
                                    )
                                },
                                onOpenHabit = {
                                    startActivity(
                                        Intent(this@MainActivity, HabitGuardianActivity::class.java)
                                    )
                                },
                                onOpenFlashComposer = { openFlashNoteComposer() },
                                tick = petTick
                            )
                        }

                        composable<Screen.Schedule> {
                            ScheduleTabScreen()
                        }

                        composable<Screen.FocusHub> {
                            HomeContent(
                                onNavigateToTimer = { navController.navigate(Screen.Timer) },
                                onNavigateToUsage = { navController.navigate(Screen.Usage) },
                                onNavigateToRoutines = { navController.navigate(Screen.Routines) },
                                onNavigateToWhitelist = {
                                    if (prefs.getBoolean("focus_mode", false) &&
                                        TimerStateManager.state.value.isStrictMode
                                    ) {
                                        Toast.makeText(
                                            baseContext,
                                            "Wait for focus mode to end",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        navController.navigate(Screen.Whitelist)
                                    }
                                },
                                onNavigateToWebsiteBlocklist = {
                                    navController.navigate(Screen.WebsiteBlocklist)
                                },
                                onNavigateToMindfulLaunch = {
                                    navController.navigate(Screen.MindfulLaunch)
                                },
                                onNavigateToIntro = { },
                                onRequestAccessibility = {
                                    pendingFocusModeStart = true
                                    showAccessibilityDialog.value = true
                                },
                                currentTimeLeft = overlayUi.timeLeft,
                                currentTimerState = overlayUi.timerState,
                                whitelistedAppsCount = whitelistedCount,
                                mindfulAppsCount = mindfulAppsCount,
                                isMindfulLaunchEnabled = isMindfulLaunchEnabled,
                                dailyUsageText = dailyUsageText,
                                skipPromos = true,
                                title = getString(R.string.nav_focus)
                            )
                        }

                        composable<Screen.Timer> {
                            TimerContent(
                                navController = navController,
                                isTimerRunning = overlayUi.isRunning,
                                isPaused = overlayUi.isPaused,
                                currentTimeLeft = overlayUi.timeLeft,
                                currentTimerState = overlayUi.timerState,
                                isStrictMode = overlayUi.strictMode,
                                isZenMode = isZenMode,
                                onZenModeChange = {
                                    isZenMode = it
                                    focusSession.setZenMode(it)
                                },
                                onStartTimer = { config -> startOverlayFocus(config) },
                                onPauseTimer = { focusSession.pause() },
                                onResumeTimer = { focusSession.resume() },
                                onCancelTimer = { focusSession.cancel() },
                                onRestartTimer = { focusSession.restart() },
                                onTakeBreak = { focusSession.takeBreak() }
                            )
                        }

                        composable<Screen.Usage> {
                            UsageScreenWrapper(
                                context = this@MainActivity,
                                usageStatsManager = usageStatsManager,
                                launcherApps = launcherApps,
                                packageManager = packageManager,
                                currentPackageName = packageName,
                                onBackPressed = {
                                    if (!navController.popBackStack()) {
                                        navController.navigate(Screen.Home) { launchSingleTop = true }
                                    }
                                },
                                onAppClick = { appUsageStats ->
                                    navController.navigate(
                                        Screen.DailyLimit(appUsageStats.applicationInfo.packageName)
                                    )
                                }
                            )
                        }

                        composable<Screen.DailyLimit> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.DailyLimit>()
                            val pkgName = route.packageName
                            val application =
                                remember(pkgName) { packageManager.getApplicationInfo(pkgName, 0) }
                            val appIcon = remember(application) {
                                packageManager.getApplicationIcon(application)
                            }
                            val appName = remember(application) {
                                packageManager.getApplicationLabel(application).toString()
                            }
                            val existingLimitMinutes =
                                remember(pkgName) { (AppLimits.getLimit(pkgName) / 60000).toInt() }
                            var weekOffset by remember { mutableIntStateOf(0) }
                            val dailyData by remember(pkgName, weekOffset) {
                                derivedStateOf {
                                    getDailyUsageForLastWeek(pkgName, usageStatsManager, weekOffset)
                                }
                            }
                            DailyLimitScreen(
                                appName = appName,
                                appIcon = appIcon,
                                packageName = pkgName,
                                existingLimitMinutes = existingLimitMinutes,
                                dailyData = dailyData,
                                onSave = { minutes ->
                                    AppLimits.setLimit(pkgName, minutes)
                                    AppLimits.save()
                                    navController.popBackStack()
                                },
                                onRemove = {
                                    AppLimits.removeLimit(pkgName)
                                    AppLimits.save()
                                    navController.popBackStack()
                                },
                                onBackPressed = { navController.popBackStack() },
                                weekOffset = weekOffset,
                                onWeekChange = { newOffset -> weekOffset = newOffset },
                                canGoPrevious = weekOffset > -4
                            )
                        }

                        composable<Screen.Routines> {
                            RoutinesScreen(
                                onBackPress = { navController.popBackStack() },
                                onCreateRoutine = {
                                    navController.navigate(Screen.CreateRoutine(null))
                                },
                                onEditRoutine = { routine ->
                                    navController.navigate(Screen.CreateRoutine(routine.id))
                                }
                            )
                        }

                        composable<Screen.CreateRoutine> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.CreateRoutine>()
                            CreateRoutineScreen(
                                routineId = route.routineId,
                                onBackPressed = { navController.popBackStack() },
                                onSaveComplete = { navController.popBackStack() }
                            )
                        }

                        composable<Screen.Whitelist> {
                            WhitelistScreenWrapper(
                                navController = navController,
                                launcherApps = launcherApps,
                                packageManager = packageManager,
                                currentPackageName = packageName
                            )
                        }

                        composable<Screen.Settings> {
                            SettingsContent(
                                onSoundPicker = { launchSoundPicker() },
                                mainFooter = {
                                    GuardSettingsFooter(
                                        settings = settings,
                                        onRefreshSettings = {
                                            sendServiceAction(PetService.ACTION_REFRESH_SETTINGS)
                                            petTick++
                                        },
                                        tick = petTick
                                    )
                                }
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

                        composable<Screen.FocusSessionDetail> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.FocusSessionDetail>()
                            FocusSessionDetailScreen(
                                sessionId = route.sessionId,
                                onBackPressed = { navController.popBackStack() }
                            )
                        }

                        composable<Screen.WebsiteBlocklist> {
                            WebsiteBlocklistScreen(onBackPressed = { navController.popBackStack() })
                        }

                        composable<Screen.MindfulLaunch> {
                            MindfulLaunchScreen(
                                onBackPressed = { navController.popBackStack() },
                                onNavigateToApps = {
                                    navController.navigate(Screen.MindfulLaunchApps)
                                }
                            )
                        }

                        composable<Screen.MindfulLaunchApps> {
                            MindfulLaunchAppsScreen(
                                onBackPressed = { navController.popBackStack() }
                            )
                        }
                    }

                    if (showAccessibilityDialog.value) {
                        val accessibilityEnabled = isAccessibilityServiceEnabledForBlocker()
                        AlertDialog(
                            onDismissRequest = { showAccessibilityDialog.value = false },
                            title = {
                                Text(stringResource(dev.pranav.reef.R.string.accessibility_service))
                            },
                            text = {
                                Text(
                                    stringResource(
                                        if (accessibilityEnabled) {
                                            dev.pranav.reef.R.string.accessibility_service_not_running_description
                                        } else {
                                            dev.pranav.reef.R.string.accessibility_service_description
                                        }
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showAccessibilityDialog.value = false
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    shapes = ButtonDefaults.shapes()
                                ) {
                                    Text(stringResource(dev.pranav.reef.R.string.open_accessibility_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showAccessibilityDialog.value = false },
                                    shapes = ButtonDefaults.shapes()
                                ) {
                                    Text(stringResource(dev.pranav.reef.R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NAVIGATE_TIMER, false) ||
            intent.getBooleanExtra("navigate_to_timer", false)
        ) {
            shouldNavigateToTimer = true
        }
        if (intent.getBooleanExtra(EXTRA_NAVIGATE_SETTINGS, false) ||
            intent.getBooleanExtra("navigate_to_settings", false)
        ) {
            shouldNavigateToSettings = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasPromptedPermissions) {
            hasPromptedPermissions = true
            if (!skipPermissionPromptOnce) {
                lifecycleScope.launch {
                    delay(400)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        checkAndRequestMissingPermissions()
                    }
                }
            }
            skipPermissionPromptOnce = false
        }
        if (pendingFocusModeStart && isBlockerServiceOperational()) {
            pendingFocusModeStart = false
        }
    }

    override fun onDestroy() {
        focusSession.release()
        super.onDestroy()
    }

    private fun startOverlayFocus(config: TimerConfig) {
        if (!isPrefsInitialized) {
            Toast.makeText(this, R.string.focus_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (!focusSession.start(config)) {
            Toast.makeText(this, R.string.focus_not_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSoundPicker() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TITLE,
                getString(dev.pranav.reef.R.string.select_transition_sound)
            )
            val currentSound = prefs.getString("pomodoro_sound", null)
            if (!currentSound.isNullOrEmpty()) {
                putExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    currentSound.toUri()
                )
            }
        }
        soundPickerLauncher.launch(intent)
    }

    private fun openFlashNoteComposer() {
        if (Settings.canDrawOverlays(this)) {
            FlashNoteHud.startCapture(this)
        } else {
            startActivity(Intent(this, FlashNoteActivity::class.java))
        }
    }

    private fun requestPermissionsAndStart(onDone: () -> Unit) {
        if (!Settings.canDrawOverlays(this) ||
            !hasUsageStatsPermission() ||
            !isAccessibilityServiceEnabledForBlocker()
        ) {
            startActivity(Intent(this, PermissionsCheckActivity::class.java))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, PermissionsCheckActivity::class.java))
            return
        }
        startPetService(onDone)
    }

    private fun startPetService(onDone: () -> Unit = {}) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, PetService::class.java))
        }.onSuccess {
            window.decorView.postDelayed({
                if (!PetService.isRunning) showLastStartError()
                onDone()
            }, SERVICE_START_CHECK_DELAY_MS)
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.start_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLastStartError() {
        val message = getSharedPreferences("pet_runtime", MODE_PRIVATE)
            .getString("last_start_error", null) ?: return
        Toast.makeText(this, getString(R.string.start_failed, message), Toast.LENGTH_LONG).show()
    }

    private fun stopPet() {
        stopService(Intent(this, PetService::class.java))
    }

    private fun feedPet(onChanged: () -> Unit) {
        if (settings.foodCount <= 0) {
            Toast.makeText(this, R.string.no_food, Toast.LENGTH_SHORT).show()
            return
        }
        settings.foodCount -= 1
        settings.hunger += 20
        settings.mood += 3
        sendState(PetState.FEED, onChanged)
    }

    private fun changeMood(state: PetState, delta: Int, onChanged: () -> Unit) {
        settings.mood += delta
        sendState(state, onChanged)
    }

    private fun sendState(state: PetState, onChanged: () -> Unit = {}) {
        if (!PetService.isRunning) {
            Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, PetService::class.java)
                .setAction(PetService.ACTION_SET_STATE)
                .putExtra(PetService.EXTRA_STATE, state.key)
        )
        onChanged()
    }

    private fun sendServiceAction(action: String) {
        if (PetService.isRunning) {
            startService(Intent(this, PetService::class.java).setAction(action))
        }
    }

    private fun scheduleAutoExtract() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        val trigger = Runnable {
            if (PetService.isRunning) {
                sendServiceAction(PetService.ACTION_EXTRACT_TEXT)
            } else {
                Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            }
        }
        if (PetService.isRunning) {
            window.decorView.postDelayed(trigger, 600L)
        } else {
            startPetService()
            window.decorView.postDelayed(trigger, 1_800L)
        }
    }

    private fun addExceptions() {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        packageManager.queryIntentActivities(intent, 0).forEach {
            val packageName = it.activityInfo.packageName
            if (!Whitelist.isWhitelisted(packageName)) Whitelist.whitelist(packageName)
        }
    }

    companion object {
        const val EXTRA_AUTO_EXTRACT = "auto_extract"
        const val EXTRA_DEBUG_BIGBANG = "debug_bigbang_text"
        const val EXTRA_DEBUG_AUTO_AI = "debug_auto_ai"
        const val EXTRA_NAVIGATE_TIMER = "navigate_to_timer"
        const val EXTRA_NAVIGATE_SETTINGS = "navigate_to_settings"
        private const val SERVICE_START_CHECK_DELAY_MS = 1_200L
    }
}
