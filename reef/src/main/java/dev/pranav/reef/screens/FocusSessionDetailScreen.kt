package dev.pranav.reef.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.pranav.reef.data.*
import dev.pranav.reef.util.FocusStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun fmt(millis: Long, pattern: String = "HH:mm"): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        .format(DateTimeFormatter.ofPattern(pattern))

private fun fmtDuration(millis: Long): String {
    val h = millis / 3_600_000
    val m = (millis % 3_600_000) / 60_000
    val s = (millis % 60_000) / 1_000
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        s > 0 -> "${s}s"
        else -> "< 1s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusSessionDetailScreen(sessionId: String, onBackPressed: () -> Unit) {
    val session = remember(sessionId) { FocusStats.sessionById(sessionId) }

    if (session == null) {
        LaunchedEffect(Unit) { onBackPressed() }
        return
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        "Session Detail",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Medium)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SessionOverviewCard(session) }
            itemsIndexed(session.phases, key = { i, _ -> i }) { _, phase -> PhaseCard(phase) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SessionOverviewCard(session: FocusSession) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val typeColor = MaterialTheme.colorScheme.primaryContainer
                    val typeOnColor = MaterialTheme.colorScheme.onPrimaryContainer
                    Surface(shape = RoundedCornerShape(8.dp), color = typeColor) {
                        Text(
                            if (session.sessionType == SessionType.POMODORO) "Pomodoro" else "Simple",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = typeOnColor
                        )
                    }
                    val (statusColor, statusOnColor, statusIcon, statusLabel) = if (session.isCompleted)
                        Quadruple(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            Icons.Rounded.CheckCircle,
                            "Completed"
                        )
                    else
                        Quadruple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            Icons.Rounded.RadioButtonUnchecked,
                            "Incomplete"
                        )
                    Surface(shape = RoundedCornerShape(8.dp), color = statusColor) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                statusIcon,
                                null,
                                modifier = Modifier.size(12.dp),
                                tint = statusOnColor
                            )
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusOnColor
                            )
                        }
                    }
                }
                Text(
                    fmt(session.startTimestamp, "MMM d, yyyy"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCell("Started", fmt(session.startTimestamp))
                    StatCell("Total Focus", formatFocusDuration(session.totalFocusTime))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (session.endTimestamp > 0L) StatCell("Ended", fmt(session.endTimestamp))
                    if (session.sessionType == SessionType.POMODORO)
                        StatCell("Cycles", "${session.completedCycles}")
                    if (session.totalBlockEvents > 0)
                        StatCell("Blocked", "${session.totalBlockEvents} times")
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhaseCard(phase: PhaseEntry) {
    val (accentColor, accentOnColor, phaseLabel) = when (phase.type) {
        PhaseType.FOCUS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Focus"
        )

        PhaseType.SHORT_BREAK -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Short Break"
        )

        PhaseType.LONG_BREAK -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Long Break"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Phase header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = accentColor) {
                        Text(
                            phaseLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentOnColor
                        )
                    }
                    if (!phase.isCompleted) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                "Incomplete",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Text(
                    fmtDuration(phase.actualDuration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Metrics row
            Row(modifier = Modifier.fillMaxWidth()) {
                PhaseStatCell("Planned", fmtDuration(phase.plannedDuration), Modifier.weight(1f))
                PhaseStatCell("Actual", fmtDuration(phase.actualDuration), Modifier.weight(1f))
                PhaseStatCell("Start", fmt(phase.startTimestamp), Modifier.weight(1f))
                if (phase.endTimestamp > 0L) PhaseStatCell(
                    "End",
                    fmt(phase.endTimestamp),
                    Modifier.weight(1f)
                )
            }

            // Blocked apps
            if (phase.blockEvents.isNotEmpty()) {
                HorizontalDivider()

                val grouped = remember(phase.blockEvents) {
                    phase.blockEvents.groupBy { it.packageName }
                        .map { (pkg, events) -> pkg to events.sortedBy { it.timestamp } }
                        .sortedByDescending { it.second.size }
                }

                Text(
                    "${grouped.size} app${if (grouped.size > 1) "s" else ""} opened  ·  ${phase.blockEvents.size} total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {

                    grouped.forEachIndexed { index, (pkg, events) ->
                        val itemShape = when {
                            grouped.size == 1 -> RoundedCornerShape(12.dp)
                            index == 0 -> RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 4.dp
                            )

                            index == grouped.lastIndex -> RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 4.dp,
                                bottomStart = 12.dp,
                                bottomEnd = 12.dp
                            )

                            else -> RoundedCornerShape(4.dp)
                        }
                        BlockedAppRow(pkg, events, itemShape)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BlockedAppRow(
    packageName: String,
    events: List<BlockEvent>,
    shape: RoundedCornerShape
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron"
    )
    val pm = LocalContext.current.packageManager

    val appName = remember(packageName) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }
    val icon = remember(packageName) {
        runCatching { pm.getApplicationIcon(packageName).toBitmap().asImageBitmap() }.getOrNull()
    }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            ListItem(
                modifier = Modifier.clickable { expanded = !expanded },
                headlineContent = {
                    Text(appName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                },
                supportingContent = {
                    Text(
                        if (expanded) "Tap to collapse" else "${events.size} attempt${if (events.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    if (icon != null) {
                        Image(
                            icon, appName, modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                "${events.size}×",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight, null,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(chevronAngle),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    events.forEachIndexed { i, event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            )
                            Text(
                                "Attempt ${i + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                fmt(event.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
