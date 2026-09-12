package dev.pranav.reef.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import dev.pranav.reef.data.FocusSession
import dev.pranav.reef.data.SessionType
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.TimeLineChart
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FocusRange { DAILY, WEEKLY, MONTHLY }

internal fun formatFocusDuration(millis: Long): String {
    val h = millis / 3_600_000
    val m = (millis % 3_600_000) / 60_000
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        else -> "0m"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FocusStatsScreen(
    onBackPressed: () -> Unit,
    onSessionClick: (String) -> Unit
) {
    var range by remember { mutableStateOf(FocusRange.WEEKLY) }
    var offset by remember { mutableIntStateOf(0) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val today = remember { LocalDate.now() }

    val allSessions by FocusStats.sessions.collectAsState()

    val modelProducer = remember { CartesianChartModelProducer() }

    val chartData = remember(range, offset, allSessions) {
        when (range) {
            FocusRange.WEEKLY -> FocusStats.weeklyChartData(offset)
            FocusRange.MONTHLY -> FocusStats.monthlyChartData(offset)
            FocusRange.DAILY -> emptyList()
        }
    }

    LaunchedEffect(chartData, range) {
        if (chartData.any { it.second > 0f }) {
            modelProducer.runTransaction {
                lineSeries { series(chartData.map { it.second }) }
            }
        }
    }

    val displaySessions = remember(range, offset, selectedIndex, allSessions, today) {
        when {
            range == FocusRange.DAILY -> FocusStats.sessionsForDay(offset)
            selectedIndex == null && range == FocusRange.WEEKLY -> FocusStats.sessionsForWeek(offset)
            selectedIndex == null -> FocusStats.sessionsForMonth(offset)
            range == FocusRange.WEEKLY -> {
                val day = today.plusWeeks(offset.toLong())
                    .with(DayOfWeek.MONDAY).plusDays(selectedIndex!!.toLong())
                val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = day.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
                    .toEpochMilli()
                allSessions.filter { it.startTimestamp in start..end }
            }

            else -> {
                val month = YearMonth.from(today).plusMonths(offset.toLong())
                val weekStart = month.atDay(1).plusDays((selectedIndex!! * 7).toLong())
                val weekEnd = minOf(weekStart.plusDays(6), month.atEndOfMonth())
                val start =
                    weekStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = weekEnd.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
                    .toEpochMilli()
                allSessions.filter { it.startTimestamp in start..end }
            }
        }
    }

    val totalFocusTime = remember(displaySessions) { displaySessions.sumOf { it.totalFocusTime } }
    val totalBlocks = remember(displaySessions) { displaySessions.sumOf { it.totalBlockEvents } }

    val periodLabel = when {
        range == FocusRange.DAILY -> when (offset) {
            0 -> "Today"
            -1 -> "Yesterday"
            else -> LocalDate.now().plusDays(offset.toLong())
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        }

        range == FocusRange.MONTHLY -> YearMonth.now().plusMonths(offset.toLong())
            .format(DateTimeFormatter.ofPattern("MMMM yyyy"))

        selectedIndex != null && selectedIndex!! in chartData.indices -> chartData[selectedIndex!!].first
        offset == 0 -> "This Week"
        offset == -1 -> "Last Week"
        else -> "Week of ${
            LocalDate.now().plusWeeks(offset.toLong()).with(DayOfWeek.MONDAY)
                .format(DateTimeFormatter.ofPattern("MMM d"))
        }"
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        "Focus Stats",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                FocusRangeButtonGroup(range) { newRange ->
                    range = newRange
                    offset = 0
                    selectedIndex = null
                }
            }

            item {
                Column(
                    Modifier.animateContentSize(
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                ) {
                    Text(
                        periodLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))

                    AnimatedContent(
                        targetState = totalFocusTime,
                        transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
                        label = "totalFocusTime"
                    ) { time ->
                        Text(
                            formatFocusDuration(time),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${displaySessions.size} sessions") })
                        if (totalBlocks > 0) {
                            SuggestionChip(
                                onClick = {},
                                icon = {
                                    Icon(
                                        Icons.Rounded.Block,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("$totalBlocks blocked") }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (range != FocusRange.DAILY) {
                        if (chartData.any { it.second > 0f }) {
                            //TimeColumnChart(
                            //    modelProducer = modelProducer,
                            //    modifier = Modifier.padding(horizontal = 16.dp),
                            //    yValueFormatter = CartesianValueFormatter { _, value, _ ->
                            //        formatFocusDuration(value.toLong() * 60_000)
                            //    },
                            //    xValueFormatter = CartesianValueFormatter { _, value, _ ->
                            //        chartData.getOrNull(value.toInt())?.first?.take(6) ?: " "
                            //    },
                            //    onColumnClick = { index ->
                            //        selectedIndex = if (selectedIndex == index) null else index
                            //    },
                            //    dataValues = chartData.map { it.second },
                            //    selectedColumnIndex = selectedIndex
                            //)

                            TimeLineChart(
                                modelProducer = modelProducer,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                yValueFormatter = { _, value, _ ->
                                    formatFocusDuration(value.toLong() * 60_000)
                                },
                                xValueFormatter = { _, value, _ ->
                                    val label = chartData.getOrNull(value.toInt())?.first?.take(6)
                                    if (label.isNullOrBlank()) {
                                        "index_${value.toInt()}"
                                    } else label
                                },
                                dataValues = chartData.map { it.second }
                            )
                        } else {
                            ElevatedCard(
                                Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "No sessions ${if (range == FocusRange.MONTHLY) "this month" else "this week"}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Prev/next for all ranges — Daily can't go forward past today (offset >= 0)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { offset--; selectedIndex = null }) {
                            Icon(
                                Icons.Filled.ChevronLeft,
                                "Previous",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { offset++; selectedIndex = null },
                            enabled = offset < 0
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight, "Next",
                                tint = if (offset < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            if (displaySessions.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SelfImprovement, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No focus sessions yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Start a timer to begin tracking",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                itemsIndexed(displaySessions, key = { _, s -> s.id }) { index, session ->
                    FocusSessionItem(
                        session,
                        onClick = { onSessionClick(session.id) },
                        index,
                        displaySessions.size
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FocusRangeButtonGroup(range: FocusRange, onRangeChange: (FocusRange) -> Unit) {
    val options = listOf("Daily", "Weekly", "Monthly")
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, label ->
            ToggleButton(
                checked = range.ordinal == index,
                onCheckedChange = { if (range.ordinal != index) onRangeChange(FocusRange.entries[index]) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton }
            ) { Text(label) }
        }
    }
}

@Composable
private fun FocusSessionItem(
    session: FocusSession,
    onClick: () -> Unit,
    index: Int,
    listSize: Int
) {
    val shape = when {
        listSize == 1 -> RoundedCornerShape(20.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 6.dp,
            bottomEnd = 6.dp
        )

        index == listSize - 1 -> RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 6.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        )

        else -> RoundedCornerShape(6.dp)
    }

    val startLabel = remember(session.startTimestamp) {
        Instant.ofEpochMilli(session.startTimestamp).atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("HH:mm · MMM d"))
    }

    val completionRatio = remember(session) {
        val planned = session.phases.sumOf { it.plannedDuration }.takeIf { it > 0 } ?: 1L
        (session.totalFocusTime.toFloat() / planned).coerceIn(0f, 1f)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(
            initialScale = 0.95f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = shape
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        startLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                supportingContent = {
                    Text(
                        buildString {
                            append(formatFocusDuration(session.totalFocusTime) + " focused")
                            if (session.sessionType == SessionType.POMODORO) append(" · ${session.completedCycles} cycles")
                            if (session.totalBlockEvents > 0) append(" · ${session.totalBlockEvents}× blocked")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = { SessionRing(completionRatio, session.isCompleted) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}

@Composable
private fun SessionRing(completionRatio: Float, isCompleted: Boolean) {
    val animated by animateFloatAsState(
        targetValue = completionRatio.coerceIn(0f, 1f),
        label = "ring"
    )
    val ringColor = when {
        isCompleted -> MaterialTheme.colorScheme.primary
        animated > 0.5f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(54.dp),
            color = ringColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp
        )
        Icon(Icons.Rounded.SelfImprovement, null, modifier = Modifier.size(28.dp), tint = ringColor)
    }
}
