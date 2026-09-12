package dev.pranav.reef.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import dev.pranav.reef.R
import dev.pranav.reef.util.TimeColumnChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLimitScreen(
    appName: String,
    appIcon: Drawable,
    packageName: String,
    existingLimitMinutes: Int,
    dailyData: List<HourlyUsageData>,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit,
    onBackPressed: () -> Unit,
    onWeekChange: (Int) -> Unit = {},
    weekOffset: Int = 0,
    canGoPrevious: Boolean = true
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.daily_usage_limit)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        DailyLimitContent(
            appName = appName,
            appIcon = appIcon,
            packageName = packageName,
            existingLimitMinutes = existingLimitMinutes,
            dailyData = dailyData,
            onSave = onSave,
            onRemove = onRemove,
            weekOffset = weekOffset,
            onWeekChange = onWeekChange,
            canGoPrevious = canGoPrevious,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        )
    }
}

data class HourlyUsageData(
    val day: String,
    val usageMinutes: Double,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DailyLimitContent(
    appName: String,
    appIcon: Drawable,
    packageName: String,
    existingLimitMinutes: Int,
    dailyData: List<HourlyUsageData>,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit,
    weekOffset: Int,
    onWeekChange: (Int) -> Unit,
    canGoPrevious: Boolean,
    modifier: Modifier = Modifier
) {
    var hours by remember { mutableIntStateOf(existingLimitMinutes / 60) }
    var minutes by remember { mutableIntStateOf(existingLimitMinutes % 60) }

    val totalMinutes = hours * 60 + minutes
    val averageUsage = dailyData.map { it.usageMinutes }.average().takeIf { !it.isNaN() } ?: 0.0

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(dailyData) {
        if (dailyData.any { it.usageMinutes > 0 }) {
            modelProducer.runTransaction {
                columnModel { series(dailyData.map { it.usageMinutes.toLong() }) }
            }
        }
    }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("d/M", Locale.getDefault()) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Image(
            appIcon.toBitmap().asImageBitmap(),
            contentDescription = appName,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = appName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.min_day_average, averageUsage.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (weekOffset == 0) stringResource(R.string.last_7_days) else stringResource(
                            R.string.usage_history
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row {
                        IconButton(
                            onClick = { onWeekChange(weekOffset - 1) },
                            enabled = canGoPrevious,
                            modifier = Modifier.size(32.dp),
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            Icon(
                                Icons.Filled.ChevronLeft,
                                contentDescription = stringResource(R.string.previous_week),
                                tint = if (canGoPrevious) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onWeekChange(weekOffset + 1) },
                            enabled = weekOffset < 0,
                            modifier = Modifier.size(32.dp),
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = stringResource(R.string.next_week),
                                tint = if (weekOffset < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val hasUsageData = dailyData.any { it.usageMinutes > 0 }
                if (hasUsageData) {
                    TimeColumnChart(
                        modelProducer = modelProducer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        dataValues = dailyData.map { it.usageMinutes.toFloat() },
                        xValueFormatter = CartesianValueFormatter { _, value, _ ->
                            val index = value.toInt()
                            if (index in dailyData.indices) {
                                if (weekOffset == 0) {
                                    dailyData[index].day.take(3)
                                } else {
                                    val instant = Instant.ofEpochMilli(dailyData[index].timestamp)
                                    instant.atZone(ZoneId.systemDefault()).format(dateFormatter)
                                }
                            } else ""
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_usage_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.set_daily_usage_limit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(
                            onClick = { if (hours < 12) hours++ },
                            modifier = Modifier.size(44.dp),
                            shapes = IconButtonDefaults.shapes(
                                shape = IconButtonDefaults.extraLargeSquareShape,
                                pressedShape = IconButtonDefaults.largePressedShape
                            )
                        ) {
                            Icon(Icons.Rounded.Add, null)
                        }

                        Text(
                            text = hours.toString().padStart(2, '0'),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )

                        FilledTonalIconButton(
                            onClick = { if (hours > 0) hours-- },
                            modifier = Modifier.size(44.dp),
                            shapes = IconButtonDefaults.shapes(
                                shape = IconButtonDefaults.extraLargeSquareShape,
                                pressedShape = IconButtonDefaults.largePressedShape
                            )
                        ) {
                            Icon(Icons.Rounded.Remove, null)
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.hours),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = ":",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(
                            onClick = {
                                if (minutes < 59) minutes++ else if (hours < 12) {
                                    hours++; minutes = 0
                                }
                            },
                            modifier = Modifier.size(44.dp),
                            shapes = IconButtonDefaults.shapes(
                                shape = IconButtonDefaults.extraLargeSquareShape,
                                pressedShape = IconButtonDefaults.largePressedShape
                            )
                        ) {
                            Icon(Icons.Rounded.Add, null)
                        }

                        Text(
                            text = minutes.toString().padStart(2, '0'),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )

                        FilledTonalIconButton(
                            onClick = {
                                if (minutes > 0) minutes-- else if (hours > 0) {
                                    hours--; minutes = 59
                                }
                            },
                            modifier = Modifier.size(44.dp),
                            shapes = IconButtonDefaults.shapes(
                                shape = IconButtonDefaults.extraLargeSquareShape,
                                pressedShape = IconButtonDefaults.largePressedShape
                            )
                        ) {
                            Icon(Icons.Rounded.Remove, null)
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.minutes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    SuggestionChip(
                        onClick = { hours = 0; minutes = 30 },
                        label = { Text("30m") }
                    )
                    SuggestionChip(
                        onClick = { hours = 1; minutes = 0 },
                        label = { Text("1h") }
                    )
                    SuggestionChip(
                        onClick = { hours = 2; minutes = 0 },
                        label = { Text("2h") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSave(totalMinutes) },
            modifier = Modifier.fillMaxWidth(),
            enabled = totalMinutes > 0,
            shapes = ButtonDefaults.shapes()
        ) {
            Text(stringResource(R.string.save_routine))
        }

        if (existingLimitMinutes > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.remove_limit))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
