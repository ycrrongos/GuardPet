package com.geekathon.guardpet.ui

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geekathon.guardpet.DaySchedule
import com.geekathon.guardpet.DayScheduleColor
import com.geekathon.guardpet.DayScheduleStatus
import com.geekathon.guardpet.DayScheduleStore
import com.geekathon.guardpet.HabitGuardianActivity
import com.geekathon.guardpet.R
import com.geekathon.guardpet.ScheduleCompleteActivity
import com.geekathon.guardpet.ScheduleEditor
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * 底栏「日程」与独立 [com.geekathon.guardpet.ScheduleActivity] 共用的 MD3 页面。
 */
@Composable
fun ScheduleTabScreen(
    modifier: Modifier = Modifier,
    showCloseButton: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    ScheduleMd3Screen(
        modifier = modifier,
        showCloseButton = showCloseButton,
        onClose = onClose
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMd3Screen(
    modifier: Modifier = Modifier,
    showCloseButton: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val activity = LocalContext.current as AppCompatActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var visibleMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var importBusy by remember { mutableStateOf(false) }

    val editor = remember {
        ScheduleEditor(
            activity = activity,
            selectedDate = { selectedDate },
            visibleMonth = { visibleMonth },
            onFocusDate = { date ->
                selectedDate = date
                visibleMonth = YearMonth.from(date)
            },
            onChanged = { refreshTick++ }
        )
    }
    DisposableEffect(Unit) {
        onDispose { editor.destroy() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                editor.onResumeImport()
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val stop = DayScheduleStore.observe { refreshTick++ }
        onDispose { stop() }
    }

    val dayCounts = remember(visibleMonth, refreshTick) {
        val start = visibleMonth.minusMonths(1).atDay(1)
        val end = visibleMonth.plusMonths(1).atEndOfMonth()
        DayScheduleStore.countsBetween(start, end)
    }
    val schedules = remember(selectedDate, refreshTick) {
        DayScheduleStore.forDate(selectedDate)
    }
    val guardHint = remember(refreshTick) {
        val active = runCatching { DayScheduleStore.activeNow() }.getOrNull()
        if (active != null &&
            (active.allowPackages.isNotEmpty() || active.blockPackages.isNotEmpty())
        ) {
            activity.getString(R.string.schedule_active_guard, active.title)
        } else {
            activity.getString(R.string.schedule_ai_hint)
        }
    }

    val startMonth = remember { YearMonth.of(today.year - 3, 1) }
    val endMonth = remember { YearMonth.of(today.year + 3, 12) }
    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }
    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = visibleMonth,
        firstDayOfWeek = firstDayOfWeek
    )
    LaunchedEffect(visibleMonth) {
        if (calendarState.firstVisibleMonth.yearMonth != visibleMonth) {
            calendarState.animateScrollToMonth(visibleMonth)
        }
    }
    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.firstVisibleMonth.yearMonth }
            .distinctUntilChanged()
            .collect { visibleMonth = it }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val weekDays = remember { daysOfWeek() }
    val listScroll = rememberScrollState()
    var listDragging by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.schedule_page_title),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1).sp
                            )
                        )
                        Text(
                            stringResource(R.string.schedule_page_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (showCloseButton && onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.schedule_close))
                        }
                    }
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
                .verticalScroll(listScroll, enabled = !listDragging)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val next = visibleMonth.minusMonths(1)
                                if (next >= startMonth) visibleMonth = next
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.schedule_previous_month)
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.schedule_month_format,
                                visibleMonth.year,
                                visibleMonth.monthValue
                            ),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        IconButton(
                            onClick = {
                                val next = visibleMonth.plusMonths(1)
                                if (next <= endMonth) visibleMonth = next
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.schedule_next_month)
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        weekDays.forEach { day ->
                            Text(
                                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalCalendar(
                        state = calendarState,
                        dayContent = { day ->
                            ScheduleDayCell(
                                day = day,
                                selected = day.date == selectedDate,
                                today = day.date == today,
                                hasEvents = (dayCounts[day.date] ?: 0) > 0,
                                onClick = {
                                    if (day.position == DayPosition.MonthDate) {
                                        selectedDate = day.date
                                    }
                                }
                            )
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        AssistChip(
                            onClick = {
                                selectedDate = today
                                visibleMonth = YearMonth.from(today)
                                scope.launch { calendarState.animateScrollToMonth(visibleMonth) }
                            },
                            label = { Text(stringResource(R.string.schedule_today)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.schedule_selected_date_format,
                        selectedDate.monthValue,
                        selectedDate.dayOfMonth,
                        selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.schedule_count_format, schedules.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            if (schedules.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        text = stringResource(R.string.schedule_page_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.schedule_drag_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                ReorderableScheduleList(
                    schedules = schedules,
                    selectedDate = selectedDate,
                    onDraggingChange = { listDragging = it },
                    onColorCycle = { schedule ->
                        if (schedule.status == DayScheduleStatus.DONE) return@ReorderableScheduleList
                        val next = schedule.copy(
                            colorArgb = DayScheduleColor.nextColor(schedule.colorArgb)
                        )
                        if (DayScheduleStore.update(next)) refreshTick++
                    },
                    onDone = { schedule ->
                        if (!schedule.canCompleteNow()) {
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.schedule_complete_too_early,
                                    DayScheduleStore.minutesToHm(schedule.midpointMinutes())
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            return@ReorderableScheduleList
                        }
                        activity.startActivity(
                            Intent(activity, ScheduleCompleteActivity::class.java)
                                .putExtra(ScheduleCompleteActivity.EXTRA_SCHEDULE_ID, schedule.id)
                        )
                    },
                    onEdit = { editor.showEditDialog(it) },
                    onReordered = { ok, msg ->
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        if (ok) refreshTick++
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { editor.showEditDialog(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.schedule_add))
                }
                FilledTonalButton(
                    onClick = { editor.showAiDialog() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.schedule_ai_action))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (!importBusy) editor.requestImportOrPermission { importBusy = it }
                    },
                    enabled = !importBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.schedule_import_range))
                }
                OutlinedButton(
                    onClick = {
                        activity.startActivity(Intent(activity, HabitGuardianActivity::class.java))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.schedule_habit_settings))
                }
            }

            Text(
                text = guardHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ScheduleDayCell(
    day: CalendarDay,
    selected: Boolean,
    today: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit
) {
    val inMonth = day.position == DayPosition.MonthDate
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(
                when {
                    !inMonth -> Modifier
                    selected -> Modifier.background(primary)
                    today -> Modifier.background(primary.copy(alpha = 0.14f))
                    else -> Modifier
                }
            )
            .clickable(enabled = inMonth, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (inMonth) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    selected -> onPrimary
                    today -> primary
                    else -> onSurface
                }
            )
            if (hasEvents) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) onPrimary else primary)
                )
            }
        }
    }
}

@Composable
private fun ReorderableScheduleList(
    schedules: List<DaySchedule>,
    selectedDate: LocalDate,
    onDraggingChange: (Boolean) -> Unit,
    onColorCycle: (DaySchedule) -> Unit,
    onDone: (DaySchedule) -> Unit,
    onEdit: (DaySchedule) -> Unit,
    onReordered: (ok: Boolean, msg: String) -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val canReorder = schedules.size >= 2

    var items by remember { mutableStateOf(schedules) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    // 非拖动时才同步外部数据，避免拖到一半被 refresh 打断
    LaunchedEffect(schedules) {
        if (draggingId == null) items = schedules
    }

    val itemsLatest = rememberUpdatedState(items)
    val schedulesLatest = rememberUpdatedState(schedules)
    val onDraggingLatest = rememberUpdatedState(onDraggingChange)
    val onReorderedLatest = rememberUpdatedState(onReordered)
    val selectedDateLatest = rememberUpdatedState(selectedDate)
    val rowHeightLatest = rememberUpdatedState(rowHeightPx)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, schedule ->
            key(schedule.id) {
                val isDragging = draggingId == schedule.id
                ScheduleRowCard(
                    schedule = schedule,
                    index = index,
                    total = items.size,
                    draggable = canReorder,
                    isDragging = isDragging,
                    dragOffsetY = if (isDragging) dragOffsetY else 0f,
                    onHeightChanged = { h ->
                        if (h > 0f) rowHeightPx = h + with(density) { 2.dp.toPx() }
                    },
                    // 整卡可长按；pointerInput 只用 id，换序不重建手势
                    dragModifier = if (canReorder) {
                        Modifier.pointerInput(schedule.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = schedule.id
                                    dragOffsetY = 0f
                                    onDraggingLatest.value(true)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffsetY = 0f
                                    onDraggingLatest.value(false)
                                    items = schedulesLatest.value
                                },
                                onDragEnd = {
                                    val ordered = itemsLatest.value.map { it.id }
                                    val (ok, msg) = DayScheduleStore.reorderDay(
                                        selectedDateLatest.value,
                                        ordered
                                    )
                                    draggingId = null
                                    dragOffsetY = 0f
                                    onDraggingLatest.value(false)
                                    if (!ok) items = schedulesLatest.value
                                    onReorderedLatest.value(ok, msg)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val list = itemsLatest.value
                                    val from = list.indexOfFirst { it.id == draggingId }
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    val height = rowHeightLatest.value.takeIf { it > 0f }
                                        ?: with(density) { 72.dp.toPx() }
                                    val direction = when {
                                        dragOffsetY > height * 0.55f -> 1
                                        dragOffsetY < -height * 0.55f -> -1
                                        else -> 0
                                    }
                                    if (direction == 0) return@detectDragGesturesAfterLongPress
                                    val target = from + direction
                                    if (target !in list.indices) return@detectDragGesturesAfterLongPress
                                    // layout 跳一格后用反向 translation 留在手指下，避免命中丢失
                                    items = list.toMutableList().also { mutable ->
                                        val moving = mutable.removeAt(from)
                                        mutable.add(target, moving)
                                    }
                                    dragOffsetY -= direction * height
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            )
                        }
                    } else {
                        Modifier
                    },
                    onColorCycle = { onColorCycle(schedule) },
                    onDone = { onDone(schedule) },
                    onEdit = { onEdit(schedule) }
                )
            }
        }
    }
}

@Composable
private fun ScheduleRowCard(
    schedule: DaySchedule,
    index: Int,
    total: Int,
    draggable: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    dragModifier: Modifier,
    onHeightChanged: (Float) -> Unit,
    onColorCycle: () -> Unit,
    onDone: () -> Unit,
    onEdit: () -> Unit
) {
    val shape = when {
        total == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        index == total - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(4.dp)
    }
    val done = schedule.status == DayScheduleStatus.DONE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height.toFloat()) }
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                shadowElevation = if (isDragging) 12f else 0f
                scaleX = if (isDragging) 1.02f else 1f
                scaleY = if (isDragging) 1.02f else 1f
            }
            .then(dragModifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(schedule.displayColor()))
                    .clickable(enabled = !isDragging, onClick = onColorCycle)
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(56.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (draggable) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.schedule_drag_handle),
                    modifier = Modifier.size(32.dp),
                    tint = if (draggable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    }
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isDragging, onClick = onEdit)
            ) {
                Text(
                    text = stringResource(
                        R.string.schedule_time_format,
                        DayScheduleStore.minutesToHm(schedule.startMinutes),
                        DayScheduleStore.minutesToHm(schedule.endMinutes)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (done) {
                        val deg = schedule.completionDegree?.let { " · $it%" }.orEmpty()
                        "✓ ${schedule.title}$deg"
                    } else {
                        schedule.title
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (done) 0.62f else 1f)
                )
                val allow = schedule.allowPackages.size
                val block = schedule.blockPackages.size
                val lock = if (schedule.isPolicyLocked()) {
                    " · ${stringResource(R.string.schedule_locked_short)}"
                } else {
                    ""
                }
                Text(
                    text = stringResource(R.string.schedule_policy_counts, allow, block) + lock,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!done) {
                IconButton(onClick = onDone, enabled = !isDragging) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.schedule_mark_done),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onEdit, enabled = !isDragging) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.schedule_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
