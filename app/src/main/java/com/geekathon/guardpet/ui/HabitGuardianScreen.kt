package com.geekathon.guardpet.ui

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geekathon.guardpet.AppActiveCatalog
import com.geekathon.guardpet.AppGuardKind
import com.geekathon.guardpet.HabitAgent
import com.geekathon.guardpet.HabitGuardianActivity
import com.geekathon.guardpet.HabitPolicyStore
import com.geekathon.guardpet.HabitRewardTracker
import com.geekathon.guardpet.PackageRule
import com.geekathon.guardpet.PackageRuleMode
import com.geekathon.guardpet.PetSettings
import com.geekathon.guardpet.R
import com.geekathon.guardpet.SurfaceMarkKind
import com.geekathon.guardpet.UserSurfaceMark
import dev.pranav.reef.accessibility.BlockerService
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitGuardianScreen(activity: HabitGuardianActivity) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val petSettings = remember { PetSettings(context) }
    var enabled by remember { mutableStateOf(HabitPolicyStore.agentEnabled) }
    var testMode by remember { mutableStateOf(HabitPolicyStore.testMode) }
    var analyzing by remember { mutableStateOf(false) }
    val policy = remember(tick) { HabitPolicyStore.loadPolicy() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    DisposableEffect(Unit) {
        activity.onRefreshUi = { tick++ }
        onDispose { activity.onRefreshUi = null }
    }

    fun runAnalyze() {
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            Toast.makeText(context, R.string.habit_analyze_no_key_hint, Toast.LENGTH_LONG).show()
        }
        analyzing = true
        AppActiveCatalog.analyzeAllByNamesAsync(context) { ok, message ->
            analyzing = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (ok) {
                HabitAgent.refreshAsync(context, reason = "after_active_names") { _, _ -> tick++ }
            }
            tick++
        }
    }

    LaunchedEffect(Unit) {
        val auto = activity.intent?.getBooleanExtra(HabitGuardianActivity.EXTRA_AUTO_ANALYZE, false) == true ||
            activity.intent?.action == HabitGuardianActivity.ACTION_HABIT_ANALYZE
        if (auto) runAnalyze()
    }

    fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            activity,
            { _, hour, minute ->
                onPicked(hour * 60 + minute)
                tick++
            },
            initial / 60,
            initial % 60,
            true
        ).show()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.habit_guard_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scroll
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                policy.summary.ifBlank { stringResource(R.string.habit_summary_empty) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.habit_mood_hint, petSettings.mood),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(stringResource(R.string.habit_guard_enabled), enabled) {
                        enabled = it
                        HabitPolicyStore.agentEnabled = it
                    }
                    SwitchRow(stringResource(R.string.habit_test_mode), testMode) {
                        testMode = it
                        HabitPolicyStore.testMode = it
                    }
                    Text(stringResource(R.string.habit_sleep_window), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                pickTime(HabitPolicyStore.sleepStartMinutes) {
                                    HabitPolicyStore.sleepStartMinutes = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.habit_sleep_start_btn, formatMinutes(HabitPolicyStore.sleepStartMinutes)))
                        }
                        FilledTonalButton(
                            onClick = {
                                pickTime(HabitPolicyStore.wakeMinutes) {
                                    HabitPolicyStore.wakeMinutes = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.habit_wake_btn, formatMinutes(HabitPolicyStore.wakeMinutes)))
                        }
                    }
                    Text(stringResource(R.string.habit_work_window), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                pickTime(HabitPolicyStore.workStartMinutes) {
                                    HabitPolicyStore.workStartMinutes = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.habit_work_start_btn, formatMinutes(HabitPolicyStore.workStartMinutes)))
                        }
                        FilledTonalButton(
                            onClick = {
                                pickTime(HabitPolicyStore.workEndMinutes) {
                                    HabitPolicyStore.workEndMinutes = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.habit_work_end_btn, formatMinutes(HabitPolicyStore.workEndMinutes)))
                        }
                    }
                }
            }
            Button(
                onClick = { runAnalyze() },
                enabled = !analyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (analyzing) R.string.habit_analyzing else R.string.habit_reanalyze))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { activity.startDelayedMark(SurfaceMarkKind.ENTERTAINMENT) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.habit_mark_entertainment)) }
                OutlinedButton(
                    onClick = { activity.startDelayedMark(SurfaceMarkKind.WORK) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.habit_mark_work)) }
            }
            Text(
                markPreview(activity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.habit_rules_section), style = MaterialTheme.typography.titleMedium)
            habitRules(activity, policy, tick).forEach { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val next = when (row.rule.mode) {
                                    PackageRuleMode.ALLOW -> PackageRuleMode.SURFACE_FILTER
                                    PackageRuleMode.SURFACE_FILTER -> PackageRuleMode.SEARCH_ONLY
                                    PackageRuleMode.SEARCH_ONLY -> PackageRuleMode.BLOCK
                                    PackageRuleMode.BLOCK -> PackageRuleMode.ALLOW
                                }
                                HabitPolicyStore.upsertManualRule(
                                    row.rule.copy(
                                        mode = next,
                                        kind = AppGuardKind.fromMode(next),
                                        manualOverride = true
                                    )
                                )
                                tick++
                            }) {
                                Text(row.modeLabel)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    if (!row.deletable) {
                                        Toast.makeText(context, R.string.habit_rule_delete_none, Toast.LENGTH_SHORT).show()
                                        return@TextButton
                                    }
                                    AppActiveCatalog.resetAndReanalyze(context, row.rule.packageName)
                                    Toast.makeText(context, R.string.habit_rule_deleted_reanalyze, Toast.LENGTH_SHORT).show()
                                    tick++
                                },
                                enabled = row.deletable
                            ) { Text(stringResource(R.string.habit_rule_delete)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private data class RuleRow(
    val rule: PackageRule,
    val label: String,
    val subtitle: String,
    val modeLabel: String,
    val deletable: Boolean
)

@Composable
private fun habitRules(activity: HabitGuardianActivity, policy: com.geekathon.guardpet.HabitPolicy, tick: Int): List<RuleRow> {
    @Suppress("UNUSED_VARIABLE")
    val refresh = tick
    val context = LocalContext.current
    val shown = LinkedHashMap<String, PackageRule>()
    policy.packageRules.forEach { shown[it.packageName] = it }
    activity.usagePackages().forEach { pkg ->
        if (pkg !in shown) shown[pkg] = PackageRule(pkg, PackageRuleMode.ALLOW, reason = "用量 Top")
    }
    return shown.values.take(24).map { rule ->
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(rule.packageName, 0)
            ).toString()
        }.getOrDefault(rule.packageName)
        val subtitle = buildString {
            append(rule.packageName)
            if (rule.reason.isNotBlank()) append(" · ").append(rule.reason)
            if (rule.manualOverride) append(" · 手动")
            if (rule.surfaces.isNotEmpty()) {
                append('\n')
                append(rule.surfaces.joinToString(" / ") { it.label })
            }
        }
        val mode = when (rule.mode) {
            PackageRuleMode.ALLOW -> stringResource(R.string.habit_mode_allow)
            PackageRuleMode.BLOCK -> stringResource(R.string.habit_mode_block)
            PackageRuleMode.SURFACE_FILTER -> stringResource(R.string.habit_mode_surface)
            PackageRuleMode.SEARCH_ONLY -> stringResource(R.string.habit_mode_search_only)
        }
        RuleRow(rule, label, subtitle, mode, policy.packageRules.any { it.packageName == rule.packageName })
    }
}

@Composable
private fun markPreview(activity: HabitGuardianActivity): String {
    val fg = activity.resolveForegroundPackage()
    val samples = BlockerService.captureVisibleText().take(8)
    val pkgLine = stringResource(R.string.habit_mark_preview_pkg, fg ?: "—")
    val body = if (samples.isEmpty()) {
        stringResource(R.string.habit_mark_preview_empty)
    } else {
        samples.joinToString(" · ")
    }
    return "$pkgLine\n$body"
}

internal fun formatMinutes(total: Int): String = "%02d:%02d".format(total / 60, total % 60)

internal fun HabitGuardianActivity.startDelayedMark(kind: SurfaceMarkKind) {
    if (!BlockerService.isConnected) {
        Toast.makeText(this, R.string.habit_need_accessibility, Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(this, R.string.habit_mark_countdown, Toast.LENGTH_LONG).show()
    moveTaskToBack(true)
    window.decorView.postDelayed({
        val pkg = resolveForegroundPackage()
        if (pkg.isNullOrBlank() || pkg == packageName) {
            Toast.makeText(applicationContext, R.string.habit_mark_need_other_app, Toast.LENGTH_LONG).show()
            return@postDelayed
        }
        val texts = BlockerService.captureVisibleText()
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(10)
        if (texts.isEmpty()) {
            Toast.makeText(applicationContext, R.string.habit_mark_no_text, Toast.LENGTH_LONG).show()
            return@postDelayed
        }
        HabitPolicyStore.addMark(
            UserSurfaceMark(
                id = UUID.randomUUID().toString().take(8),
                packageName = pkg,
                kind = kind,
                label = if (kind == SurfaceMarkKind.ENTERTAINMENT) "手动娱乐面" else "手动工作面",
                sampleTexts = texts,
                createdAt = System.currentTimeMillis()
            )
        )
        HabitRewardTracker.onMarkAssist(applicationContext)
        Toast.makeText(applicationContext, R.string.habit_mark_saved, Toast.LENGTH_SHORT).show()
    }, 2_800L)
}
