package com.geekathon.guardpet

import android.app.TimePickerDialog
import android.app.usage.UsageStatsManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.geekathon.guardpet.databinding.ActivityHabitGuardianBinding
import com.geekathon.guardpet.databinding.ItemHabitRuleBinding
import dev.pranav.reef.accessibility.BlockerService
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class HabitGuardianActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHabitGuardianBinding
    private lateinit var petSettings: PetSettings
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHabitGuardianBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top + 8, bars.right, bars.bottom + 16)
            insets
        }
        petSettings = PetSettings(this)
        bindControls()
        refreshUi()
        if (intent?.getBooleanExtra(EXTRA_AUTO_ANALYZE, false) == true ||
            intent?.action == ACTION_HABIT_ANALYZE
        ) {
            binding.root.post { triggerAnalyze() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindControls() {
        binding.habitEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            HabitPolicyStore.agentEnabled = checked
        }
        binding.habitTestSwitch.setOnCheckedChangeListener { _, checked ->
            HabitPolicyStore.testMode = checked
        }
        binding.sleepStartButton.setOnClickListener {
            pickTime(HabitPolicyStore.sleepStartMinutes) { HabitPolicyStore.sleepStartMinutes = it }
        }
        binding.wakeButton.setOnClickListener {
            pickTime(HabitPolicyStore.wakeMinutes) { HabitPolicyStore.wakeMinutes = it }
        }
        binding.workStartButton.setOnClickListener {
            pickTime(HabitPolicyStore.workStartMinutes) { HabitPolicyStore.workStartMinutes = it }
        }
        binding.workEndButton.setOnClickListener {
            pickTime(HabitPolicyStore.workEndMinutes) { HabitPolicyStore.workEndMinutes = it }
        }
        binding.analyzeButton.setOnClickListener { triggerAnalyze() }
        binding.markEntertainmentButton.setOnClickListener {
            startDelayedMark(SurfaceMarkKind.ENTERTAINMENT)
        }
        binding.markWorkButton.setOnClickListener {
            startDelayedMark(SurfaceMarkKind.WORK)
        }
    }

    private fun triggerAnalyze() {
        persistLlmFields()
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            Toast.makeText(this, R.string.habit_analyze_no_key_hint, Toast.LENGTH_LONG).show()
        }
        binding.analyzeButton.isEnabled = false
        binding.analyzeButton.text = getString(R.string.habit_analyzing)
        AppActiveCatalog.analyzeAllByNamesAsync(this) { ok, message ->
            binding.analyzeButton.isEnabled = true
            binding.analyzeButton.text = getString(R.string.habit_reanalyze)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (ok) {
                HabitAgent.refreshAsync(this, reason = "after_active_names") { _, _ -> refreshUi() }
            }
            refreshUi()
        }
    }

    private fun persistLlmFields() {
        HabitPolicyStore.llmBaseUrl = binding.llmBaseUrl.text?.toString().orEmpty()
        HabitPolicyStore.llmApiKey = binding.llmApiKey.text?.toString().orEmpty()
        HabitPolicyStore.llmModel = binding.llmModel.text?.toString().orEmpty()
    }

    private fun startDelayedMark(kind: SurfaceMarkKind) {
        if (!BlockerService.isConnected) {
            Toast.makeText(this, R.string.habit_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.habit_mark_countdown, Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
        handler.postDelayed({
            val pkg = resolveForegroundPackage()
            val app = applicationContext
            if (pkg.isNullOrBlank() || pkg == packageName) {
                Toast.makeText(app, R.string.habit_mark_need_other_app, Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            val texts = BlockerService.captureVisibleText()
                .map { it.trim() }
                .filter { it.length in 2..40 }
                .distinct()
                .take(10)
            if (texts.isEmpty()) {
                Toast.makeText(app, R.string.habit_mark_no_text, Toast.LENGTH_LONG).show()
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
            HabitRewardTracker.onMarkAssist(app)
            Toast.makeText(app, R.string.habit_mark_saved, Toast.LENGTH_SHORT).show()
        }, 2_800L)
    }

    private fun refreshUi() {
        val policy = HabitPolicyStore.loadPolicy()
        binding.habitEnabledSwitch.isChecked = HabitPolicyStore.agentEnabled
        binding.habitTestSwitch.isChecked = HabitPolicyStore.testMode
        binding.habitSummary.text = policy.summary.ifBlank {
            getString(R.string.habit_summary_empty)
        }
        binding.habitMoodHint.text = getString(R.string.habit_mood_hint, petSettings.mood)
        binding.sleepStartButton.text = getString(
            R.string.habit_sleep_start_btn,
            formatMinutes(HabitPolicyStore.sleepStartMinutes)
        )
        binding.wakeButton.text = getString(
            R.string.habit_wake_btn,
            formatMinutes(HabitPolicyStore.wakeMinutes)
        )
        binding.workStartButton.text = getString(
            R.string.habit_work_start_btn,
            formatMinutes(HabitPolicyStore.workStartMinutes)
        )
        binding.workEndButton.text = getString(
            R.string.habit_work_end_btn,
            formatMinutes(HabitPolicyStore.workEndMinutes)
        )
        if (binding.llmBaseUrl.text.isNullOrBlank()) {
            binding.llmBaseUrl.setText(HabitPolicyStore.llmBaseUrl)
        }
        if (binding.llmApiKey.text.isNullOrBlank()) {
            binding.llmApiKey.setText(HabitPolicyStore.llmApiKey)
        }
        if (binding.llmModel.text.isNullOrBlank()) {
            binding.llmModel.setText(HabitPolicyStore.llmModel)
        }
        renderRules(policy)
        val fg = resolveForegroundPackage()
        val samples = BlockerService.captureVisibleText().take(8)
        binding.markPreview.text = buildString {
            append(getString(R.string.habit_mark_preview_pkg, fg ?: "—"))
            append('\n')
            if (samples.isEmpty()) {
                append(getString(R.string.habit_mark_preview_empty))
            } else {
                append(samples.joinToString(" · "))
            }
        }
    }

    private fun renderRules(policy: HabitPolicy) {
        binding.rulesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val usagePkgs = usagePackages()
        val shown = LinkedHashMap<String, PackageRule>()
        policy.packageRules.forEach { shown[it.packageName] = it }
        usagePkgs.forEach { pkg ->
            if (pkg !in shown) {
                shown[pkg] = PackageRule(pkg, PackageRuleMode.ALLOW, reason = "用量 Top")
            }
        }
        shown.values.take(24).forEach { rule ->
            val item = ItemHabitRuleBinding.inflate(inflater, binding.rulesContainer, false)
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(rule.packageName, 0))
                    .toString()
            }.getOrDefault(rule.packageName)
            item.ruleTitle.text = label
            item.ruleSubtitle.text = buildString {
                append(rule.packageName)
                if (rule.reason.isNotBlank()) append(" · ").append(rule.reason)
                if (rule.manualOverride) append(" · 手动")
                if (rule.surfaces.isNotEmpty()) {
                    append('\n')
                    append(rule.surfaces.joinToString(" / ") { it.label })
                }
            }
            item.ruleMode.text = when (rule.mode) {
                PackageRuleMode.ALLOW -> getString(R.string.habit_mode_allow)
                PackageRuleMode.BLOCK -> getString(R.string.habit_mode_block)
                PackageRuleMode.SURFACE_FILTER -> getString(R.string.habit_mode_surface)
                PackageRuleMode.SEARCH_ONLY -> getString(R.string.habit_mode_search_only)
            }
            item.ruleMode.setOnClickListener {
                val nextMode = when (rule.mode) {
                    PackageRuleMode.ALLOW -> PackageRuleMode.SURFACE_FILTER
                    PackageRuleMode.SURFACE_FILTER -> PackageRuleMode.SEARCH_ONLY
                    PackageRuleMode.SEARCH_ONLY -> PackageRuleMode.BLOCK
                    PackageRuleMode.BLOCK -> PackageRuleMode.ALLOW
                }
                HabitPolicyStore.upsertManualRule(
                    rule.copy(
                        mode = nextMode,
                        kind = AppGuardKind.fromMode(nextMode),
                        manualOverride = true
                    )
                )
                refreshUi()
            }
            val isRealRule = policy.packageRules.any { it.packageName == rule.packageName }
            item.ruleDelete.isEnabled = isRealRule
            item.ruleDelete.alpha = if (isRealRule) 1f else 0.35f
            item.ruleDelete.setOnClickListener {
                if (!isRealRule) {
                    Toast.makeText(this, R.string.habit_rule_delete_none, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppActiveCatalog.resetAndReanalyze(this, rule.packageName)
                Toast.makeText(this, R.string.habit_rule_deleted_reanalyze, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            binding.rulesContainer.addView(item.root)
        }
    }

    private fun pickTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                onPicked(hour * 60 + minute)
                refreshUi()
            },
            initialMinutes / 60,
            initialMinutes % 60,
            true
        ).show()
    }

    private fun resolveForegroundPackage(): String? {
        BlockerService.currentForegroundPackage()
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?.let { return it }
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val start = end - 120_000L
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?.asSequence()
            ?.filter { it.packageName != packageName }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    private fun usagePackages(): List<String> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val end = System.currentTimeMillis()
        val start = LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
                ?.filter { it.totalTimeInForeground >= 5 * 60_000L }
                ?.filter { it.packageName != packageName }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.map { it.packageName }
                ?.distinct()
                ?.take(16)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun formatMinutes(total: Int): String =
        "%02d:%02d".format(total / 60, total % 60)

    companion object {
        const val EXTRA_AUTO_ANALYZE = "auto_analyze"
        const val ACTION_HABIT_ANALYZE = "com.geekathon.guardpet.action.HABIT_ANALYZE"
    }
}
