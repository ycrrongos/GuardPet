package com.geekathon.guardpet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.geekathon.guardpet.FlashNote
import com.geekathon.guardpet.FlashNoteColor
import com.geekathon.guardpet.FlashNotePlayer
import com.geekathon.guardpet.FlashNoteStore
import com.geekathon.guardpet.FocusLauncher
import com.geekathon.guardpet.PetAction
import com.geekathon.guardpet.PetAssetRepository
import com.geekathon.guardpet.PetCanvas
import com.geekathon.guardpet.PetGesture
import com.geekathon.guardpet.PetSettings
import com.geekathon.guardpet.PetState
import com.geekathon.guardpet.R
import com.geekathon.guardpet.SenseVoiceModelStore
import dev.pranav.reef.screens.HomeNavigationRow
import dev.pranav.reef.util.hasUsageStatsPermission
import dev.pranav.reef.util.isAccessibilityServiceEnabledForBlocker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardHomeScreen(
    assets: PetAssetRepository,
    settings: PetSettings,
    petRunning: Boolean,
    statusMessage: String,
    badgeRes: Int,
    heroMessageRes: Int,
    previewState: PetState,
    onStartCompanion: () -> Unit,
    onStopCompanion: () -> Unit,
    onFeed: () -> Unit,
    onPet: () -> Unit,
    onSleep: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenHabit: () -> Unit,
    onOpenFlashComposer: () -> Unit,
    tick: Int
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            GuardCompanionHeader(
                assets = assets,
                settings = settings,
                petRunning = petRunning,
                statusMessage = statusMessage,
                badgeRes = badgeRes,
                heroMessageRes = heroMessageRes,
                previewState = previewState,
                onStartCompanion = onStartCompanion,
                onStopCompanion = onStopCompanion,
                onFeed = onFeed,
                onPet = onPet,
                onSleep = onSleep,
                onOpenTodos = onOpenTodos,
                tick = tick
            )
            GuardHomeToolRows(
                onOpenHabit = onOpenHabit,
                onOpenFlashComposer = onOpenFlashComposer,
                onOpenFriends = { FocusLauncher.openFriends(context) }
            )
            FlashNotesCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ColumnScope.GuardCompanionHeader(
    assets: PetAssetRepository,
    settings: PetSettings,
    petRunning: Boolean,
    statusMessage: String,
    badgeRes: Int,
    heroMessageRes: Int,
    previewState: PetState,
    onStartCompanion: () -> Unit,
    onStopCompanion: () -> Unit,
    onFeed: () -> Unit,
    onPet: () -> Unit,
    onSleep: () -> Unit,
    onOpenTodos: () -> Unit,
    tick: Int
) {
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(badgeRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hero_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.hero_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            AndroidView(
                factory = { ctx ->
                    PetCanvas(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (180 * resources.displayMetrics.density).toInt()
                        )
                        fitPreviewToCanvas()
                        show(assets.randomFileFor(PetState.HAPPY))
                    }
                },
                update = { canvas ->
                    canvas.show(assets.randomFileFor(previewState))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(heroMessageRes),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                mood = settings.mood,
                hunger = settings.hunger,
                food = settings.foodCount
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onFeed,
                    enabled = petRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.feed))
                }
                FilledTonalButton(
                    onClick = onPet,
                    enabled = petRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.pet))
                }
                FilledTonalButton(
                    onClick = onSleep,
                    enabled = petRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Nightlight, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sleep))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenTodos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.todo_short))
            }
            Spacer(Modifier.height(8.dp))
            if (petRunning) {
                Button(
                    onClick = onStopCompanion,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.pause_companion))
                }
            } else {
                Button(
                    onClick = onStartCompanion,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.start_companion))
                }
            }
        }
    }
}

@Composable
private fun StatRow(mood: Int, hunger: Int, food: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniStat(stringResource(R.string.mood_label), mood)
        MiniStat(stringResource(R.string.hunger_label), hunger)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.food_label), style = MaterialTheme.typography.labelMedium)
            Text(
                "$food " + stringResource(R.string.food_unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: Int) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("$value", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

/** 首页功能入口：贴合的 MD3 分组行（作息 + 闪记）。 */
@Composable
fun GuardHomeToolRows(
    onOpenHabit: () -> Unit,
    onOpenFlashComposer: () -> Unit,
    onOpenFriends: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HomeNavigationRow(
            title = stringResource(R.string.friend_entry),
            subtitle = stringResource(R.string.friend_entry_subtitle),
            icon = Icons.Rounded.Groups,
            index = 0,
            totalItems = 3,
            onClick = onOpenFriends
        )
        HomeNavigationRow(
            title = stringResource(R.string.habit_guard_entry),
            subtitle = stringResource(R.string.habit_guard_title),
            icon = Icons.Rounded.Nightlight,
            index = 1,
            totalItems = 3,
            onClick = onOpenHabit
        )
        HomeNavigationRow(
            title = stringResource(R.string.flash_note),
            subtitle = stringResource(R.string.flash_note_write_overlay),
            icon = Icons.AutoMirrored.Rounded.Notes,
            index = 2,
            totalItems = 3,
            onClick = onOpenFlashComposer
        )
    }
}

@Composable
fun GuardSettingsFooter(
    settings: PetSettings,
    onRefreshSettings: () -> Unit,
    tick: Int
) {
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp)
        )
        val context = LocalContext.current
        Button(
            onClick = { FocusLauncher.openFriends(context) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.friend_entry))
        }
        CompanionPrefsCard(settings = settings, onRefreshSettings = onRefreshSettings)
        GestureShortcutsCard(settings = settings)
        PermissionsCard()
        TextButton(onClick = { FocusLauncher.openAbout(context) }) {
            Text(
                text = stringResource(R.string.focus_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FlashNotesCard() {
    var notes by remember { mutableStateOf(FlashNoteStore.all()) }
    DisposableEffect(Unit) {
        val stop = FlashNoteStore.observe { notes = FlashNoteStore.all() }
        onDispose { stop() }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.flash_note_list),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            if (notes.isEmpty()) {
                Text(
                    stringResource(R.string.flash_note_empty_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                notes.forEach { note ->
                    FlashNoteRow(note = note, onDeleted = { notes = FlashNoteStore.all() })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FlashNoteRow(note: FlashNote, onDeleted: () -> Unit) {
    val category = stringResource(note.category.labelRes)
    val title = if (note.scheduleDate.isNullOrBlank()) category else "$category · ${note.scheduleDate}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(FlashNoteColor.argb(note.color, note.category)))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(
                note.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.flash_note_delete),
            modifier = Modifier
                .size(36.dp)
                .clickable {
                    if (FlashNotePlayer.playingId == note.id) FlashNotePlayer.stop()
                    FlashNoteStore.delete(note.id)
                    onDeleted()
                }
                .padding(6.dp),
            tint = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CompanionPrefsCard(settings: PetSettings, onRefreshSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var walk by remember { mutableStateOf(settings.edgeWalkEnabled) }
    var scale by remember { mutableFloatStateOf(settings.petScale) }
    var speed by remember { mutableIntStateOf(settings.walkSpeed) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.free_walk))
                            Text(
                                stringResource(R.string.free_walk_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = walk,
                            onCheckedChange = {
                                walk = it
                                settings.edgeWalkEnabled = it
                                onRefreshSettings()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pet_size))
                    Slider(
                        value = ((scale - 0.55f) / 0.9f).coerceIn(0f, 1f),
                        onValueChange = {
                            scale = 0.55f + it * 0.9f
                            settings.petScale = scale
                            onRefreshSettings()
                        }
                    )
                    Text(
                        stringResource(R.string.percent_value, (scale * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.walk_speed))
                    Slider(
                        value = ((speed - 10) / 490f).coerceIn(0f, 1f),
                        onValueChange = {
                            speed = (10 + it * 490).toInt()
                            settings.walkSpeed = speed
                            onRefreshSettings()
                        }
                    )
                    Text(
                        stringResource(R.string.percent_value, ((speed - 10) * 100 / 490).coerceIn(0, 100)),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureShortcutsCard(settings: PetSettings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.gesture_shortcuts), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gesture_shortcuts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PetGesture.shortcuts.forEach { gesture ->
                GestureDropdown(gesture = gesture, settings = settings)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureDropdown(gesture: PetGesture, settings: PetSettings) {
    val actions = PetAction.entries
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(settings.actionFor(gesture)) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        TextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(gesture.labelRes)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    onClick = {
                        selected = action
                        settings.setAction(gesture, action)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    fun mark(ok: Boolean) =
        context.getString(if (ok) R.string.permission_ok else R.string.permission_missing)
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val senseOk = SenseVoiceModelStore.isPackInstalled(context) ||
        SenseVoiceModelStore.hasLocalModel(context)
    val text = buildString {
        appendLine(context.getString(R.string.permission_overlay, mark(Settings.canDrawOverlays(context))))
        appendLine(context.getString(R.string.permission_usage, mark(context.hasUsageStatsPermission())))
        appendLine(context.getString(R.string.permission_a11y, mark(context.isAccessibilityServiceEnabledForBlocker())))
        appendLine(context.getString(R.string.permission_mic, mark(mic)))
        append(
            context.getString(
                R.string.permission_sensevoice,
                context.getString(
                    if (senseOk) R.string.permission_sensevoice_ok else R.string.permission_sensevoice_missing
                )
            )
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
