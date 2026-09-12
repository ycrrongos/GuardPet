package dev.pranav.reef.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.pranav.reef.R
import dev.pranav.reef.data.Routine
import dev.pranav.reef.data.RoutineSchedule
import dev.pranav.reef.routine.Routines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreateRoutineScreen(
    routineId: String?,
    onBackPressed: () -> Unit,
    onSaveComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentRoutine by remember { mutableStateOf<Routine?>(null) }
    var routineName by remember { mutableStateOf("") }
    var scheduleType by remember { mutableStateOf(RoutineSchedule.ScheduleType.WEEKLY) }
    var selectedTime by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var selectedEndTime by remember { mutableStateOf(LocalTime.of(17, 0)) }
    var selectedDays by remember { mutableStateOf(setOf<DayOfWeek>()) }
    var appLimits by remember { mutableStateOf(listOf<Routine.AppLimit>()) }
    var appGroups by remember { mutableStateOf(listOf<Routine.AppGroup>()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showAppSelector by remember { mutableStateOf(false) }
    var showGroupCreator by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingLimit by remember { mutableStateOf<Routine.AppLimit?>(null) }
    var editingGroup by remember { mutableStateOf<Routine.AppGroup?>(null) }

    LaunchedEffect(routineId) {
        routineId?.let {
            currentRoutine = Routines.get(routineId)
            currentRoutine?.let { routine ->
                routineName = routine.name
                scheduleType = routine.schedule.type
                routine.schedule.time?.let { selectedTime = it }
                routine.schedule.endTime?.let { selectedEndTime = it }
                selectedDays = routine.schedule.daysOfWeek
                appLimits = routine.limits
                appGroups = routine.groups
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        if (currentRoutine != null) stringResource(R.string.edit_routine)
                        else stringResource(R.string.create_routine)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = routineName,
                onValueChange = { routineName = it },
                label = { Text(stringResource(R.string.routine_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Text(
                text = stringResource(R.string.schedule),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ToggleButton(
                    checked = scheduleType == RoutineSchedule.ScheduleType.MANUAL,
                    onCheckedChange = { scheduleType = RoutineSchedule.ScheduleType.MANUAL },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.manual))
                }

                ToggleButton(
                    checked = scheduleType == RoutineSchedule.ScheduleType.DAILY,
                    onCheckedChange = { scheduleType = RoutineSchedule.ScheduleType.DAILY },
                    shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.daily))
                }

                ToggleButton(
                    checked = scheduleType == RoutineSchedule.ScheduleType.WEEKLY,
                    onCheckedChange = { scheduleType = RoutineSchedule.ScheduleType.WEEKLY },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.weekly))
                }
            }

            AnimatedVisibility(visible = scheduleType != RoutineSchedule.ScheduleType.MANUAL) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.start_time),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            FilledTonalButton(
                                onClick = { showTimePicker = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccessTime,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(formatTime(selectedTime))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.end_time),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            FilledTonalButton(
                                onClick = { showEndTimePicker = true },
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccessTime,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(formatTime(selectedEndTime))
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = scheduleType == RoutineSchedule.ScheduleType.WEEKLY) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.select_days),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            DayOfWeek.MONDAY to R.string.monday,
                            DayOfWeek.TUESDAY to R.string.tuesday,
                            DayOfWeek.WEDNESDAY to R.string.wednesday,
                            DayOfWeek.THURSDAY to R.string.thursday,
                            DayOfWeek.FRIDAY to R.string.friday,
                            DayOfWeek.SATURDAY to R.string.saturday,
                            DayOfWeek.SUNDAY to R.string.sunday
                        ).forEach { (day, stringRes) ->
                            FilterChip(
                                selected = selectedDays.contains(day),
                                onClick = {
                                    selectedDays = if (selectedDays.contains(day))
                                        selectedDays - day
                                    else selectedDays + day
                                },
                                label = { Text(stringResource(stringRes)) },
                                leadingIcon = if (selectedDays.contains(day)) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_limits),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FilledTonalButton(
                    onClick = { showAppSelector = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_app))
                }
            }

            if (appLimits.isEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.no_app_limits_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    appLimits.forEach { limit ->
                        AppLimitItem(
                            appLimit = limit,
                            onRemove = {
                                appLimits = appLimits.filter { it.packageName != limit.packageName }
                            },
                            onEdit = { editingLimit = limit },
                            context = context
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_groups),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FilledTonalButton(
                    onClick = { showGroupCreator = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_group))
                }
            }

            if (appGroups.isEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.no_app_groups_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    appGroups.forEach { group ->
                        AppGroupItem(
                            group = group,
                            onEdit = {
                                editingGroup = group
                                showGroupCreator = true
                            },
                            onRemove = {
                                appGroups = appGroups.filter { it.id != group.id }
                            },
                            context = context
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val result = saveRoutine(
                        context = context,
                        currentRoutine = currentRoutine,
                        name = routineName,
                        scheduleType = scheduleType,
                        selectedTime = selectedTime,
                        selectedEndTime = selectedEndTime,
                        selectedDays = selectedDays,
                        appLimits = appLimits,
                        appGroups = appGroups,
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                    if (result) onSaveComplete()
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.save_routine),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (currentRoutine != null) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.delete_routine),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialTime = selectedTime,
            onTimeSelected = { selectedTime = it },
            onDismiss = { showTimePicker = false },
            title = stringResource(R.string.start_time)
        )
    }

    if (showEndTimePicker) {
        TimePickerDialog(
            initialTime = selectedEndTime,
            onTimeSelected = { selectedEndTime = it },
            onDismiss = { showEndTimePicker = false },
            title = stringResource(R.string.end_time)
        )
    }

    if (showAppSelector) {
        AppSelectorDialog(
            onAppSelected = { packageName, _, limitMinutes ->
                appLimits = appLimits.filter { it.packageName != packageName } +
                        Routine.AppLimit(packageName, limitMinutes)
                showAppSelector = false
            },
            onDismiss = { showAppSelector = false }
        )
    }

    editingLimit?.let { limit ->
        var editAppName by remember(limit.packageName) { mutableStateOf(limit.packageName) }
        LaunchedEffect(limit.packageName) {
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    editAppName =
                        pm.getApplicationLabel(pm.getApplicationInfo(limit.packageName, 0))
                            .toString()
                } catch (_: Exception) {
                }
            }
        }
        LimitPickerDialog(
            packageName = limit.packageName,
            appName = editAppName,
            initialMinutes = limit.limitMinutes,
            onConfirm = { newMinutes ->
                appLimits = appLimits.map {
                    if (it.packageName == limit.packageName) it.copy(limitMinutes = newMinutes) else it
                }
                editingLimit = null
            },
            onDismiss = { editingLimit = null }
        )
    }

    if (showGroupCreator) {
        CreateGroupDialog(
            onGroupSaved = { group ->
                appGroups = if (editingGroup != null) {
                    appGroups.map { if (it.id == group.id) group else it }
                } else {
                    appGroups + group
                }
                showGroupCreator = false
                editingGroup = null
            },
            onDismiss = {
                showGroupCreator = false
                editingGroup = null
            },
            initialGroup = editingGroup
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_routine)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_routine_confirmation,
                        currentRoutine?.name ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentRoutine?.let {
                            Routines.delete(it.id, context)
                        }
                        onSaveComplete()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AppLimitItem(
    appLimit: Routine.AppLimit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    context: android.content.Context
) {
    var appName by remember { mutableStateOf(appLimit.packageName) }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(appLimit.packageName) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(appLimit.packageName, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
                appIcon = pm.getApplicationIcon(appInfo)
            } catch (_: Exception) {
            }
        }
    }

    OutlinedCard(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            appIcon?.let { icon ->
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Image(
                        bitmap = icon.toBitmap().asImageBitmap(),
                        contentDescription = stringResource(R.string.app_icon)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (appLimit.limitMinutes == 0) stringResource(R.string.block_entirely)
                    else formatLimitTime(appLimit.limitMinutes, context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_app_limit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppGroupItem(
    group: Routine.AppGroup,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    context: android.content.Context
) {
    val typeLabel = stringResource(
        if (group.type == Routine.AppGroup.GroupType.SHARED) R.string.shared_type_label
        else R.string.individual_type_label
    )
    val appsCount =
        pluralStringResource(R.plurals.apps_count, group.packageNames.size, group.packageNames.size)
    val limitSuffix = if (group.type == Routine.AppGroup.GroupType.SHARED) {
        stringResource(
            R.string.shared_limit_suffix,
            formatLimitTime(group.sharedLimitMinutes, context)
        )
    } else {
        stringResource(R.string.individual_limits_suffix)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$typeLabel · $appsCount$limitSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_group),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_group),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CreateGroupDialog(
    onGroupSaved: (Routine.AppGroup) -> Unit,
    onDismiss: () -> Unit,
    initialGroup: Routine.AppGroup? = null
) {
    val context = LocalContext.current

    var step by remember { mutableIntStateOf(0) }
    var groupName by remember(initialGroup) { mutableStateOf(initialGroup?.name ?: "") }
    var groupType by remember(initialGroup) {
        mutableStateOf(
            initialGroup?.type ?: Routine.AppGroup.GroupType.SHARED
        )
    }
    var sharedLimitMinutes by remember(initialGroup) {
        mutableIntStateOf(
            initialGroup?.sharedLimitMinutes ?: 30
        )
    }
    var selectedPackages by remember(initialGroup) {
        mutableStateOf(
            initialGroup?.packageNames?.toSet() ?: emptySet()
        )
    }
    var individualLimits by remember(initialGroup) {
        mutableStateOf(
            initialGroup?.individualLimits ?: emptyMap()
        )
    }
    var limitDialogForPackage by remember { mutableStateOf<String?>(null) }

    var allApps by remember { mutableStateOf<List<AppMetadata>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var onlyLaunchable by remember { mutableStateOf(true) }

    val filteredApps = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allApps = loadAccessibleApps(context)
            isLoading = false
        }
    }

    val sheetState = rememberBottomSheetState(SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                0 -> GroupConfigStep(
                    groupName = groupName,
                    onGroupNameChange = { groupName = it },
                    groupType = groupType,
                    onGroupTypeChange = { groupType = it },
                    sharedLimitMinutes = sharedLimitMinutes,
                    onSharedLimitChange = { sharedLimitMinutes = it },
                    onNext = { step = 1 }
                )

                1 -> AppSelectStep(
                    isLoading = isLoading,
                    filteredApps = filteredApps,
                    selectedPackages = selectedPackages,
                    onTogglePackage = { pkg ->
                        selectedPackages = if (pkg in selectedPackages)
                            selectedPackages - pkg
                        else
                            selectedPackages + pkg
                    },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    showSystemApps = showSystemApps,
                    onToggleSystemApps = { showSystemApps = !showSystemApps },
                    onlyLaunchable = onlyLaunchable,
                    onToggleOnlyLaunchable = { onlyLaunchable = !onlyLaunchable },
                    groupType = groupType,
                    onBack = { step = 0 },
                    onNext = {
                        if (groupType == Routine.AppGroup.GroupType.SHARED) {
                            onGroupSaved(
                                Routine.AppGroup(
                                    id = initialGroup?.id ?: UUID.randomUUID().toString(),
                                    name = groupName,
                                    type = Routine.AppGroup.GroupType.SHARED,
                                    packageNames = selectedPackages.toList(),
                                    sharedLimitMinutes = sharedLimitMinutes
                                )
                            )
                        } else {
                            individualLimits =
                                individualLimits.filterKeys { it in selectedPackages } +
                                        selectedPackages.associateWith {
                                            individualLimits[it] ?: 30
                                        }
                            step = 2
                        }
                    }
                )

                2 -> IndividualLimitsStep(
                    selectedPackages = selectedPackages.toList(),
                    allApps = allApps,
                    individualLimits = individualLimits,
                    onLimitChange = { pkg, minutes ->
                        individualLimits = individualLimits + (pkg to minutes)
                    },
                    limitDialogForPackage = limitDialogForPackage,
                    onShowLimitDialog = { limitDialogForPackage = it },
                    onDismissLimitDialog = { limitDialogForPackage = null },
                    onBack = { step = 1 },
                    onCreateGroup = {
                        onGroupSaved(
                            Routine.AppGroup(
                                id = initialGroup?.id ?: UUID.randomUUID().toString(),
                                name = groupName,
                                type = Routine.AppGroup.GroupType.INDIVIDUAL,
                                packageNames = selectedPackages.toList(),
                                individualLimits = individualLimits
                            )
                        )
                    },
                    context = context
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GroupConfigStep(
    groupName: String,
    onGroupNameChange: (String) -> Unit,
    groupType: Routine.AppGroup.GroupType,
    onGroupTypeChange: (Routine.AppGroup.GroupType) -> Unit,
    sharedLimitMinutes: Int,
    onSharedLimitChange: (Int) -> Unit,
    onNext: () -> Unit
) {
    Text(
        text = stringResource(R.string.create_group),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )

    OutlinedTextField(
        value = groupName,
        onValueChange = onGroupNameChange,
        label = { Text(stringResource(R.string.group_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp)
    )

    Text(
        text = stringResource(R.string.group_type),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        ToggleButton(
            checked = groupType == Routine.AppGroup.GroupType.SHARED,
            onCheckedChange = { onGroupTypeChange(Routine.AppGroup.GroupType.SHARED) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.shared_limit))
        }
        ToggleButton(
            checked = groupType == Routine.AppGroup.GroupType.INDIVIDUAL,
            onCheckedChange = { onGroupTypeChange(Routine.AppGroup.GroupType.INDIVIDUAL) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.individual_limits))
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = stringResource(
                if (groupType == Routine.AppGroup.GroupType.SHARED) R.string.shared_limit_desc
                else R.string.individual_limit_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }

    AnimatedVisibility(visible = groupType == Routine.AppGroup.GroupType.SHARED) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.group_total_limit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = formatLimitTime(sharedLimitMinutes),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        if (sharedLimitMinutes >= 15) onSharedLimitChange(sharedLimitMinutes - 15) else if (sharedLimitMinutes > 0) onSharedLimitChange(
                            0
                        )
                    },
                    enabled = sharedLimitMinutes > 0,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("−15m") }

                FilledTonalButton(
                    onClick = {
                        if (sharedLimitMinutes >= 5) onSharedLimitChange(sharedLimitMinutes - 5) else if (sharedLimitMinutes > 0) onSharedLimitChange(
                            0
                        )
                    },
                    enabled = sharedLimitMinutes > 0,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("−5m") }

                FilledTonalButton(
                    onClick = { onSharedLimitChange(sharedLimitMinutes + 5) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+5m") }

                FilledTonalButton(
                    onClick = { onSharedLimitChange(sharedLimitMinutes + 15) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+15m") }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 5, 15, 30, 45, 60, 90).forEach { preset ->
                    FilterChip(
                        selected = sharedLimitMinutes == preset,
                        onClick = { onSharedLimitChange(preset) },
                        label = { Text(formatLimitTime(preset)) },
                        leadingIcon = if (sharedLimitMinutes == preset) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = groupName.isNotBlank(),
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        Text(stringResource(R.string.select_apps_label))
    }
}

private data class AppMetadata(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLaunchable: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppSelectStep(
    isLoading: Boolean,
    filteredApps: List<AppMetadata>,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showSystemApps: Boolean,
    onToggleSystemApps: () -> Unit,
    onlyLaunchable: Boolean,
    onToggleOnlyLaunchable: () -> Unit,
    groupType: Routine.AppGroup.GroupType,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var showFilterMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }
        Text(
            text = stringResource(R.string.select_apps_label),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { showFilterMenu = true }) {
                Icon(Icons.Rounded.FilterList, contentDescription = "Filters")
            }
            DropdownMenu(
                expanded = showFilterMenu,
                onDismissRequest = { showFilterMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Show System Apps") },
                    onClick = {
                        onToggleSystemApps()
                        showFilterMenu = false
                    },
                    leadingIcon = {
                        Checkbox(checked = showSystemApps, onCheckedChange = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Only Launchable Apps") },
                    onClick = {
                        onToggleOnlyLaunchable()
                        showFilterMenu = false
                    },
                    leadingIcon = {
                        Checkbox(checked = onlyLaunchable, onCheckedChange = null)
                    }
                )
            }
        }
    }

    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        label = { Text(stringResource(R.string.search_apps)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search
        )
    )

    val finalFilteredApps = remember(filteredApps, showSystemApps, onlyLaunchable) {
        filteredApps.filter { app ->
            (showSystemApps || !app.isSystem) && (!onlyLaunchable || app.isLaunchable)
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
        ) {
            items(finalFilteredApps) { app ->
                val appIcon = rememberAppIcon(app.packageName, context)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePackage(app.packageName) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        appIcon?.let { icon ->
                            Image(
                                bitmap = icon.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Checkbox(
                        checked = app.packageName in selectedPackages,
                        onCheckedChange = { onTogglePackage(app.packageName) }
                    )
                }
            }
        }
    }

    if (selectedPackages.isNotEmpty()) {
        Text(
            text = pluralStringResource(
                R.plurals.apps_selected_count,
                selectedPackages.size,
                selectedPackages.size
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = selectedPackages.isNotEmpty(),
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        Text(
            stringResource(
                if (groupType == Routine.AppGroup.GroupType.SHARED) R.string.create_group
                else R.string.set_limits_label
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IndividualLimitsStep(
    selectedPackages: List<String>,
    allApps: List<AppMetadata>,
    individualLimits: Map<String, Int>,
    onLimitChange: (String, Int) -> Unit,
    limitDialogForPackage: String?,
    onShowLimitDialog: (String) -> Unit,
    onDismissLimitDialog: () -> Unit,
    onBack: () -> Unit,
    onCreateGroup: () -> Unit,
    context: android.content.Context
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }
        Text(
            text = stringResource(R.string.set_limits_label),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(selectedPackages) { packageName ->
            val appName = allApps.find { it.packageName == packageName }?.label ?: packageName
            val currentLimit = individualLimits[packageName] ?: 30

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                FilledTonalButton(
                    onClick = { onShowLimitDialog(packageName) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(formatLimitTime(currentLimit, context))
                }
            }
        }
    }

    Button(
        onClick = onCreateGroup,
        modifier = Modifier.fillMaxWidth(),
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        Text(stringResource(R.string.create_group))
    }

    if (limitDialogForPackage != null) {
        val appName =
            allApps.find { it.packageName == limitDialogForPackage }?.label ?: limitDialogForPackage
        LimitPickerDialog(
            packageName = limitDialogForPackage,
            appName = appName,
            initialMinutes = individualLimits[limitDialogForPackage] ?: 30,
            onConfirm = { minutes ->
                onLimitChange(limitDialogForPackage, minutes)
                onDismissLimitDialog()
            },
            onDismiss = onDismissLimitDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialTime: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    title: String
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(title) },
        text = {
            TimePicker(state = timePickerState)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSelectorDialog(
    onAppSelected: (packageName: String, appName: String, limitMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppMetadata>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<AppMetadata?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var onlyLaunchable by remember { mutableStateOf(true) }
    var showFilterMenu by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(SheetValue.Hidden)

    val filteredApps = remember(apps, searchQuery, showSystemApps, onlyLaunchable) {
        apps.filter { app ->
            (searchQuery.isBlank() || app.label.contains(searchQuery, ignoreCase = true)) &&
                    (showSystemApps || !app.isSystem) &&
                    (!onlyLaunchable || app.isLaunchable)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            apps = loadAccessibleApps(context)
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_app),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )

                Box {
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "Filters")
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Show System Apps") },
                            onClick = {
                                showSystemApps = !showSystemApps
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                Checkbox(checked = showSystemApps, onCheckedChange = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Only Launchable Apps") },
                            onClick = {
                                onlyLaunchable = !onlyLaunchable
                                showFilterMenu = false
                            },
                            leadingIcon = {
                                Checkbox(checked = onlyLaunchable, onCheckedChange = null)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.search_apps)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                )
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Text(
                    text = if (searchQuery.isBlank()) stringResource(R.string.no_apps_found) else stringResource(
                        R.string.no_apps_match
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .height(400.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                ) {
                    items(filteredApps) { app ->
                        val appIcon = rememberAppIcon(app.packageName, context)
                        TextButton(
                            onClick = { selectedApp = app },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    appIcon?.let { icon ->
                                        Image(
                                            bitmap = icon.toBitmap().asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedApp != null) {
        val app = selectedApp!!
        LimitPickerDialog(
            appName = app.label,
            onConfirm = { minutes ->
                onAppSelected(app.packageName, app.label, minutes)
                selectedApp = null
            },
            packageName = app.packageName,
            onDismiss = { selectedApp = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LimitPickerDialog(
    packageName: String,
    appName: String,
    initialMinutes: Int = 15,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = rememberAppIcon(packageName, context)
    val presets = listOf(0, 5, 15, 30, 45, 60, 90)
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    val sheetState = rememberBottomSheetState(SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.set_limit_for, appName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilledTonalIconButton(
                        onClick = { if (minutes > 0) minutes -= 1 },
                        enabled = minutes > 0,
                        modifier = Modifier.size(48.dp),
                        shapes = IconButtonDefaults.shapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = stringResource(R.string.decrease_by_one_minute)
                        )
                    }

                    Spacer(modifier = Modifier.width(32.dp))

                    Text(
                        text = if (minutes == 0) stringResource(R.string.block_entirely)
                        else formatLimitTime(minutes),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        color = if (minutes == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.width(32.dp))

                    FilledTonalIconButton(
                        onClick = { minutes += 1 },
                        modifier = Modifier.size(48.dp),
                        shapes = IconButtonDefaults.shapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.increase_by_one_minute)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    -15 to stringResource(R.string.minus_15m),
                    -5 to stringResource(R.string.minus_5m),
                    5 to stringResource(R.string.plus_5m),
                    15 to stringResource(R.string.plus_15m)
                )
                    .forEach { (delta, label) ->
                        FilledTonalButton(
                            onClick = {
                                minutes = when {
                                    delta < 0 -> maxOf(0, minutes + delta)
                                    else -> minutes + delta
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(label)
                        }
                    }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = minutes == preset,
                        onClick = { minutes = preset },
                        label = {
                            Text(
                                text = formatLimitTime(preset)
                            )
                        }
                    )
                }
            }

            Button(
                onClick = { onConfirm(minutes) },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.save_routine))
            }
        }
    }
}

private fun loadAccessibleApps(context: Context): List<AppMetadata> {
    val pm = context.packageManager

    val launcherPackages = pm.queryIntentActivities(
        android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
        0
    ).map { it.activityInfo.packageName }.toSet()

    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != context.packageName }
        .sortedBy { it.loadLabel(pm).toString() }
        .map { appInfo ->
            AppMetadata(
                packageName = appInfo.packageName,
                label = appInfo.loadLabel(pm).toString(),
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isLaunchable = launcherPackages.contains(appInfo.packageName)
            )
        }
}

private fun formatTime(time: LocalTime): String {
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")
    return time.format(formatter)
}

private fun formatLimitTime(minutes: Int, context: android.content.Context): String {
    return when {
        minutes < 60 -> context.getString(R.string.minutes_short_format, minutes)
        minutes % 60 == 0 -> context.getString(R.string.hours_short_format, minutes / 60)
        else -> context.getString(R.string.hour_min_short_suffix, minutes / 60, minutes % 60)
    }
}

@Composable
private fun rememberAppIcon(
    packageName: String,
    context: android.content.Context
): Drawable? {
    var icon by remember(packageName) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                icon = context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
            }
        }
    }
    return icon
}

private fun formatLimitTime(minutes: Int): String {
    return when {
        minutes == 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

private fun saveRoutine(
    context: android.content.Context,
    currentRoutine: Routine?,
    name: String,
    scheduleType: RoutineSchedule.ScheduleType,
    selectedTime: LocalTime,
    selectedEndTime: LocalTime,
    selectedDays: Set<DayOfWeek>,
    appLimits: List<Routine.AppLimit>,
    appGroups: List<Routine.AppGroup>,
    onError: (String) -> Unit
): Boolean {
    if (name.trim().isEmpty()) {
        onError(context.getString(R.string.enter_routine_name_error))
        return false
    }

    val schedule = RoutineSchedule(
        type = scheduleType,
        timeHour = if (scheduleType != RoutineSchedule.ScheduleType.MANUAL) selectedTime.hour else null,
        timeMinute = if (scheduleType != RoutineSchedule.ScheduleType.MANUAL) selectedTime.minute else null,
        endTimeHour = if (scheduleType != RoutineSchedule.ScheduleType.MANUAL) selectedEndTime.hour else null,
        endTimeMinute = if (scheduleType != RoutineSchedule.ScheduleType.MANUAL) selectedEndTime.minute else null,
        daysOfWeek = if (scheduleType == RoutineSchedule.ScheduleType.WEEKLY) selectedDays else emptySet()
    )

    if (scheduleType == RoutineSchedule.ScheduleType.WEEKLY && schedule.daysOfWeek.isEmpty()) {
        onError("Please select at least one day")
        return false
    }

    val routine = Routine(
        id = currentRoutine?.id ?: UUID.randomUUID().toString(),
        name = name.trim(),
        isEnabled = currentRoutine?.isEnabled ?: true,
        schedule = schedule,
        limits = appLimits,
        groups = appGroups
    )

    Routines.save(routine, context)
    return true
}

