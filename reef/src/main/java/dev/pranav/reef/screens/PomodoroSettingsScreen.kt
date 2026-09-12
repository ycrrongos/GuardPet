package dev.pranav.reef.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.pranav.reef.R
import dev.pranav.reef.util.append
import dev.pranav.reef.util.prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PomodoroSettingsContent(
    onBackPressed: () -> Unit,
    onSoundPicker: () -> Unit
) {
    BackHandler { onBackPressed() }

    var focusMinutes by remember { mutableIntStateOf(prefs.getInt("pomodoro_focus_minutes", 25)) }
    var shortBreakMinutes by remember {
        mutableIntStateOf(
            prefs.getInt(
                "pomodoro_short_break_minutes",
                5
            )
        )
    }
    var longBreakMinutes by remember {
        mutableIntStateOf(
            prefs.getInt(
                "pomodoro_long_break_minutes",
                15
            )
        )
    }
    var cycles by remember { mutableIntStateOf(prefs.getInt("pomodoro_cycles", 4)) }
    var autoStartBreaks by remember { mutableStateOf(prefs.getBoolean("auto_start_breaks", false)) }
    var autoStartPomodoros by remember {
        mutableStateOf(
            prefs.getBoolean(
                "auto_start_pomodoro",
                true
            )
        )
    }
    var soundEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                "pomodoro_sound_enabled",
                true
            )
        )
    }
    var vibrationEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                "pomodoro_vibration_enabled",
                true
            )
        )
    }

    val numberSettings = listOf(
        NumberSetting(
            label = stringResource(R.string.focus_duration),
            value = focusMinutes,
            range = 1..120,
            suffix = stringResource(R.string.min_short_suffix),
            onValueChange = {
                focusMinutes = it
                prefs.edit { putInt("pomodoro_focus_minutes", it) }
            }
        ),
        NumberSetting(
            label = stringResource(R.string.short_break_duration),
            value = shortBreakMinutes,
            range = 1..30,
            suffix = stringResource(R.string.min_short_suffix),
            onValueChange = {
                shortBreakMinutes = it
                prefs.edit { putInt("pomodoro_short_break_minutes", it) }
            }
        ),
        NumberSetting(
            label = stringResource(R.string.long_break_duration),
            value = longBreakMinutes,
            range = 1..60,
            suffix = stringResource(R.string.min_short_suffix),
            onValueChange = {
                longBreakMinutes = it
                prefs.edit { putInt("pomodoro_long_break_minutes", it) }
            }
        ),
        NumberSetting(
            label = stringResource(R.string.cycles_before_long_break),
            value = cycles,
            range = 1..10,
            suffix = "",
            onValueChange = {
                cycles = it
                prefs.edit { putInt("pomodoro_cycles", it) }
            }
        )
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.pomodoro_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0.dp),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = innerPadding.append(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.durations_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            itemsIndexed(numberSettings) { index, setting ->
                NumberSettingRow(
                    setting = setting,
                    index = index,
                    listSize = numberSettings.size
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text(
                    text = stringResource(R.string.automation),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(index = 0, listSize = 2) {
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                autoStartBreaks = !autoStartBreaks
                                prefs.edit { putBoolean("auto_start_breaks", autoStartBreaks) }
                            }
                            .padding(4.dp),
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.auto_start_breaks),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.auto_start_breaks_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = autoStartBreaks,
                                onCheckedChange = {
                                    autoStartBreaks = it
                                    prefs.edit { putBoolean("auto_start_breaks", it) }
                                }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item {
                SettingsCard(index = 1, listSize = 2) {
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                autoStartPomodoros = !autoStartPomodoros
                                prefs.edit {
                                    putBoolean(
                                        "auto_start_pomodoro",
                                        autoStartPomodoros
                                    )
                                }
                            }
                            .padding(4.dp),
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.auto_start_focus),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.auto_start_focus_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = autoStartPomodoros,
                                onCheckedChange = {
                                    autoStartPomodoros = it
                                    prefs.edit { putBoolean("auto_start_pomodoro", it) }
                                }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text(
                    text = stringResource(R.string.sound),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(index = 0, listSize = 2) {
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                soundEnabled = !soundEnabled
                                prefs.edit { putBoolean("pomodoro_sound_enabled", soundEnabled) }
                            }
                            .padding(4.dp),
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.transition_sound),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.transition_sound_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = soundEnabled,
                                onCheckedChange = {
                                    soundEnabled = it
                                    prefs.edit { putBoolean("pomodoro_sound_enabled", it) }
                                }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item {
                SettingsCard(index = 1, listSize = 2) {
                    ListItem(
                        modifier = Modifier
                            .clickable(enabled = soundEnabled) { onSoundPicker() }
                            .padding(4.dp),
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.select_sound),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (soundEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.5f
                                )
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.select_sound_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (soundEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.5f
                                )
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = if (soundEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.5f
                                )
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Text(
                    text = stringResource(R.string.vibration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(index = 0, listSize = 1) {
                    ListItem(
                        modifier = Modifier
                            .clickable {
                                vibrationEnabled = !vibrationEnabled
                                prefs.edit {
                                    putBoolean(
                                        "pomodoro_vibration_enabled",
                                        vibrationEnabled
                                    )
                                }
                            }
                            .padding(4.dp),
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.transition_vibration),
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.transition_vibration_description),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = vibrationEnabled,
                                onCheckedChange = {
                                    vibrationEnabled = it
                                    prefs.edit { putBoolean("pomodoro_vibration_enabled", it) }
                                }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }
}
