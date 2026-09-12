package dev.pranav.reef.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import dev.pranav.reef.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    onSoundPicker: () -> Unit
) {
    var currentScreen by remember { mutableStateOf<SettingsScreenRoute>(SettingsScreenRoute.Main) }
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AnimatedVisibility(
                visible = currentScreen == SettingsScreenRoute.Main,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                MediumTopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { paddingValues ->
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != SettingsScreenRoute.Main) {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(100)
                    ) + fadeIn(animationSpec = tween(100)) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { -it / 3 },
                                animationSpec = tween(100)
                            ) + fadeOut(animationSpec = tween(100))
                } else {
                    slideInHorizontally(
                        initialOffsetX = { -it / 3 },
                        animationSpec = tween(100)
                    ) + fadeIn(animationSpec = tween(100)) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(100)
                            ) + fadeOut(animationSpec = tween(100))
                }
            },
            label = "settings_screen_transition"
        ) { screen ->
            when (screen) {
                SettingsScreenRoute.Main -> MainSettingsContent(
                    contentPadding = paddingValues,
                    onNavigate = { currentScreen = it }
                )

                SettingsScreenRoute.Pomodoro -> PomodoroSettingsContent(
                    onBackPressed = { currentScreen = SettingsScreenRoute.Main },
                    onSoundPicker = onSoundPicker
                )

                SettingsScreenRoute.Notifications -> NotificationSettingsContent(
                    onBackPressed = { currentScreen = SettingsScreenRoute.Main }
                )
            }
        }
    }
}

