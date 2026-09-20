package com.geekathon.guardpet

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.pranav.reef.accessibility.BlockerService
import dev.pranav.reef.util.HabitEval
import dev.pranav.reef.util.ScreenUsageHelper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class AppCategory {
    VIDEO, GAME, SOCIAL, BROWSER, TOOL, SYSTEM, OTHER
}

data class HabitContext(
    val now: LocalTime,
    val isNightWindow: Boolean,
    val isPastBedtime: Boolean,
    val foregroundPackage: String,
    val category: AppCategory,
    val todaySchedules: List<FlashNote>,
    val habitualPackages: Set<String>
)

enum class HabitDecision {
    NONE, BAN_CURRENT, SLEEP_LOCK, SCHEDULE_WHITELIST
}

fun interface HabitJudge {
    fun decide(context: HabitContext): HabitDecision
}

class RuleHabitJudge : HabitJudge {
    override fun decide(context: HabitContext): HabitDecision {
        if (!context.isNightWindow) return HabitDecision.NONE
        val hasSchedule = context.todaySchedules.isNotEmpty()
        val entertainment = context.category == AppCategory.VIDEO ||
            context.category == AppCategory.GAME ||
            context.category == AppCategory.SOCIAL
        return if (hasSchedule) {
            if (entertainment && context.foregroundPackage !in context.habitualPackages) {
                HabitDecision.BAN_CURRENT
            } else {
                HabitDecision.SCHEDULE_WHITELIST
            }
        } else if (context.isPastBedtime && entertainment) {
            HabitDecision.SLEEP_LOCK
        } else if (entertainment) {
            HabitDecision.BAN_CURRENT
        } else {
            HabitDecision.NONE
        }
    }
}

object HabitGuardian {
    private val judge: HabitJudge = RuleHabitJudge()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var sleepLockActive: Boolean = false
        private set

    private var lastResetDay: LocalDate? = null
    private var lastSleepHonorCheckDay: LocalDate? = null
    private var entertainmentBlockedInSleepWindow = false

    fun evaluate(context: Context, packageName: String, activityClass: String? = null): HabitEval {
        resetIfNewDay()
        HabitRewardTracker.resetDayIfNeeded()
        if (!HabitPolicyStore.agentEnabled) return HabitEval.NONE
        if (isAlwaysAllowed(context, packageName)) {
            maybeRewardCompliance(context, packageName)
            return HabitEval.NONE
        }

        // 新应用：枚举 Active 并异步交给 DeepSeek / 启发式分类
        AppActiveCatalog.ensureAnalyzed(context, packageName)

        val now = LocalTime.now()
        val inSleepWindow = TimeBehaviorConfig.isSleepTime(now)
        if (!inSleepWindow && sleepLockActive) {
            sleepLockActive = false
        }

        // 今日进行中的富日程：优先 allow/block
        val daySchedEval = evaluateActiveDaySchedule(context, packageName)
        if (daySchedEval != null) return daySchedEval

        val category = categorize(context, packageName)

        if (sleepLockActive && inSleepWindow) {
            return HabitEval.sleepLock("催睡锁机中")
        }

        val texts = BlockerService.captureVisibleText()
        val resolvedClass = activityClass ?: BlockerService.currentForegroundClassName()

        if (HabitPolicyStore.isWorkTime()) {
            val workEval = evaluateWorkTime(context, packageName, resolvedClass, texts)
            if (workEval != null) return workEval
        } else {
            HabitRestrictOverlay.dismiss(context)
        }

        val policyRule = HabitPolicyStore.ruleFor(packageName)
        when (policyRule?.mode) {
            PackageRuleMode.ALLOW -> {
                maybeRewardCompliance(context, packageName)
                return HabitEval.NONE
            }
            PackageRuleMode.BLOCK -> {
                // 整包禁仅对明确娱乐 / 手动覆盖；VIDEO/SEARCH_ONLY 只在工作时段走 evaluateVideo
                if (policyRule.kind == AppGuardKind.ENTERTAINMENT || policyRule.manualOverride) {
                    return finishBlock(
                        context,
                        packageName,
                        key = "$packageName:pkg",
                        reason = policyRule.reason.ifBlank { "习惯守护：已限制此应用" },
                        sleepLock = false,
                        pressBack = false
                    )
                }
            }
            PackageRuleMode.SEARCH_ONLY, PackageRuleMode.SURFACE_FILTER, null -> Unit
        }

        if (!inSleepWindow) {
            maybeRewardCompliance(context, packageName)
            return HabitEval.NONE
        }

        val schedules = runCatching { FlashNoteStore.todaySchedules() }.getOrDefault(emptyList())
        val habitContext = HabitContext(
            now = now,
            isNightWindow = true,
            isPastBedtime = TimeBehaviorConfig.isPastBedtime(now),
            foregroundPackage = packageName,
            category = category,
            todaySchedules = schedules,
            habitualPackages = habitualPackages(context) + packagesForSchedule(context, schedules)
        )
        return when (judge.decide(habitContext)) {
            HabitDecision.NONE -> {
                maybeRewardCompliance(context, packageName)
                HabitEval.NONE
            }
            HabitDecision.BAN_CURRENT -> finishBlock(
                context,
                packageName,
                key = "$packageName:night",
                reason = "习惯守护：夜间限制娱乐应用",
                sleepLock = false,
                pressBack = false
            )
            HabitDecision.SLEEP_LOCK -> {
                sleepLockActive = true
                entertainmentBlockedInSleepWindow = true
                showSleepLock(context)
                finishBlock(
                    context,
                    packageName,
                    key = "$packageName:sleep",
                    reason = "习惯守护：该睡觉了",
                    sleepLock = true,
                    pressBack = false
                )
            }
            HabitDecision.SCHEDULE_WHITELIST -> {
                if (isEntertainmentCategory(category) && packageName !in habitContext.habitualPackages) {
                    finishBlock(
                        context,
                        packageName,
                        key = "$packageName:schedule",
                        reason = "习惯守护：今夜日程期间限制娱乐应用",
                        sleepLock = false,
                        pressBack = false
                    )
                } else {
                    maybeRewardCompliance(context, packageName)
                    HabitEval.NONE
                }
            }
        }
    }

    /**
     * 工作时段四类筛选：
     * WORK 不限；ENTERTAINMENT 整包禁；VIDEO 仅搜索 + 内容判断；COMMUNICATION 禁娱乐面。
     */
    private fun evaluateWorkTime(
        context: Context,
        packageName: String,
        resolvedClass: String?,
        texts: List<String>
    ): HabitEval? {
        val rule = HabitPolicyStore.ruleFor(packageName)
        val kind = rule?.kind
            ?: AppTierClassifier.classify(context, packageName)

        when (kind) {
            AppGuardKind.WORK -> {
                HabitRestrictOverlay.dismiss(context)
                maybeRewardCompliance(context, packageName)
                return HabitEval.NONE
            }
            AppGuardKind.ENTERTAINMENT -> {
                HabitRestrictOverlay.dismiss(context)
                return finishBlock(
                    context,
                    packageName,
                    key = "$packageName:ent",
                    reason = rule?.reason?.ifBlank { null }
                        ?: "习惯守护：工作时段禁用娱乐应用",
                    sleepLock = false,
                    pressBack = false
                )
            }
            AppGuardKind.VIDEO -> {
                return evaluateVideo(context, packageName, resolvedClass, texts, rule)
            }
            AppGuardKind.COMMUNICATION -> {
                HabitRestrictOverlay.dismiss(context)
                val eval = evaluateCommunication(context, packageName, resolvedClass, texts, rule)
                android.util.Log.i(
                    "HabitGuardian",
                    "comm $packageName cls=$resolvedClass block=${eval.shouldBlock} reason=${eval.blockReason}"
                )
                return eval
            }
        }
    }

    private fun evaluateVideo(
        context: Context,
        packageName: String,
        resolvedClass: String?,
        texts: List<String>,
        rule: PackageRule?
    ): HabitEval {
        val surfaces = rule?.surfaces?.ifEmpty { null }
            ?: HabitPolicyStore.defaultVideoSurfaces("video")

        if (VideoGuard.isSearchSurface(resolvedClass, texts)) {
            HabitRestrictOverlay.dismiss(context)
            maybeRewardCompliance(context, packageName)
            return HabitEval.watch()
        }

        if (VideoGuard.isPlayerOrDetail(resolvedClass, texts)) {
            HabitRestrictOverlay.dismiss(context)
            val (verdict, hint) = VideoGuard.judgePlayerContent(
                packageName, resolvedClass, texts, surfaces
            )
            return when (verdict) {
                VideoContentVerdict.PENDING_EXPAND -> HabitEval.watch()
                VideoContentVerdict.STUDY -> {
                    maybeRewardCompliance(context, packageName)
                    HabitEval.watch()
                }
                VideoContentVerdict.ENTERTAINMENT -> finishBlock(
                    context,
                    packageName,
                    key = "$packageName:video_meta:$hint",
                    reason = "习惯守护：娱乐向视频「${hint.take(20)}」",
                    sleepLock = false,
                    watched = true,
                    pressBack = true
                )
                VideoContentVerdict.UNKNOWN -> {
                    val hit = VideoGuard.entertainmentHit(texts, surfaces, packageName)
                    if (hit != null) {
                        finishBlock(
                            context,
                            packageName,
                            key = "$packageName:${hit.rule.id}",
                            reason = "习惯守护：娱乐内容「${hit.matchedText.take(24)}」",
                            sleepLock = false,
                            watched = true,
                            pressBack = true
                        )
                    } else {
                        HabitEval.watch()
                    }
                }
            }
        }

        if (VideoGuard.preferCover(texts)) {
            HabitRestrictOverlay.request(
                context,
                context.getString(R.string.habit_restrict_video_msg)
            )
            return HabitEval.watch()
        }

        HabitRestrictOverlay.dismiss(context)
        return finishBlock(
            context,
            packageName,
            key = "$packageName:video_nonsearch",
            reason = "习惯守护：视频应用仅允许搜索",
            sleepLock = false,
            watched = true,
            pressBack = true
        )
    }

    private fun evaluateCommunication(
        context: Context,
        packageName: String,
        resolvedClass: String?,
        texts: List<String>,
        rule: PackageRule?
    ): HabitEval {
        HabitRestrictOverlay.dismiss(context)
        // QQ Splash / 微信 Launcher 等主壳永不拦
        if (CommActiveLists.isMainShell(resolvedClass)) {
            maybeRewardCompliance(context, packageName)
            return HabitEval.watch()
        }

        // Active 类名命中优先于「壳层混合」文本判断：朋友圈页无障碍树里常仍有「发现/微信」底栏字
        val activeHit = AppActiveCatalog.matchBlockingActive(packageName, resolvedClass, texts)
        if (activeHit != null) {
            return finishBlock(
                context,
                packageName,
                key = "$packageName:active:${activeHit.activity}",
                reason = "习惯守护：禁用「${activeHit.label}」",
                sleepLock = false,
                watched = true,
                pressBack = true
            )
        }

        // 主页底栏还在时，不因入口字样拦（与 SurfaceMatcher 一致）
        if (PageEntertainmentJudge.judge(texts, packageName) == PageVerdict.SHELL_MIXED) {
            maybeRewardCompliance(context, packageName)
            return HabitEval.watch()
        }

        val uncertain = AppActiveCatalog.matchUncertain(packageName, resolvedClass, texts)
        if (uncertain != null) {
            if (AppActiveCatalog.resolveUncertainOnPage(context, packageName, resolvedClass, texts)) {
                return finishBlock(
                    context,
                    packageName,
                    key = "$packageName:page:${uncertain.activity}",
                    reason = "习惯守护：禁用「${uncertain.label}」",
                    sleepLock = false,
                    watched = true,
                    pressBack = true
                )
            }
            if (AppActiveCatalog.matchUncertain(packageName, resolvedClass, texts) != null) {
                return HabitEval.watch()
            }
        }
        val surfaces = rule?.surfaces.orEmpty()
        if (surfaces.isNotEmpty()) {
            val hit = SurfaceMatcher.match(texts, surfaces, packageName)
            if (hit != null && hit.rule.severity == SurfaceSeverity.BLOCK) {
                return finishBlock(
                    context,
                    packageName,
                    key = "$packageName:${hit.rule.id}",
                    reason = "习惯守护：${hit.rule.label}",
                    sleepLock = false,
                    watched = true,
                    pressBack = true
                )
            }
        }
        maybeRewardCompliance(context, packageName)
        return HabitEval.watch()
    }

    fun isWatched(packageName: String): Boolean {
        if (!HabitPolicyStore.agentEnabled) return false
        val active = runCatching { DayScheduleStore.activeNow() }.getOrNull()
        if (active != null &&
            (active.allowPackages.isNotEmpty() || active.blockPackages.isNotEmpty())
        ) {
            return true
        }
        return packageName in HabitPolicyStore.watchedPackages() ||
            AppActiveCatalog.hasCatalog(packageName) ||
            AppActiveCatalog.isAnalyzing(packageName)
    }

    fun clearSleepLock() {
        sleepLockActive = false
    }

    fun onSleepWindowTick(context: Context) {
        resetIfNewDay()
        val now = LocalTime.now()
        if (!TimeBehaviorConfig.isSleepTime(now)) {
            if (lastSleepHonorCheckDay != LocalDate.now() &&
                !entertainmentBlockedInSleepWindow &&
                LocalTime.now().isAfter(TimeBehaviorConfig.wakeTime)
            ) {
                // handled at wake edge below
            }
            return
        }
        // Entering sleep window without entertainment blocks for 30+ minutes → bedtime honor
        if (!entertainmentBlockedInSleepWindow &&
            lastSleepHonorCheckDay != LocalDate.now() &&
            TimeBehaviorConfig.minutesIntoSleepWindow(now) >= 30
        ) {
            lastSleepHonorCheckDay = LocalDate.now()
            HabitRewardTracker.onBedtimeHonored(context)
        }
    }

    private fun finishBlock(
        context: Context,
        packageName: String,
        key: String,
        reason: String,
        sleepLock: Boolean,
        watched: Boolean = false,
        pressBack: Boolean = false
    ): HabitEval {
        entertainmentBlockedInSleepWindow = true
        HabitRewardTracker.noteComplianceOpportunity(packageName, key.substringAfter(':', ""))
        HabitRewardTracker.onHabitBlock(
            context,
            key = key,
            sleepLock = sleepLock,
            testMode = HabitPolicyStore.testMode
        )
        if (HabitPolicyStore.testMode) {
            mainHandler.post {
                Toast.makeText(context.applicationContext, "本会拦截：$reason", Toast.LENGTH_SHORT)
                    .show()
            }
            return HabitEval.test(reason, watched = watched || isWatched(packageName))
        }
        return if (sleepLock) {
            HabitEval.sleepLock(reason).copy(watchedForSurfaces = watched)
        } else {
            HabitEval.block(reason, pressBack = pressBack).copy(watchedForSurfaces = watched)
        }
    }

    private fun maybeRewardCompliance(context: Context, packageName: String) {
        HabitRewardTracker.onAllowedForeground(context, packageName)
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now()
        if (lastResetDay == today) return
        lastResetDay = today
        sleepLockActive = false
        entertainmentBlockedInSleepWindow = false
    }

    private fun showSleepLock(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PetService::class.java).setAction(PetService.ACTION_SLEEP_LOCK)
            )
        }
    }

    /**
     * 进行中日程：有 allow 列表则只放行列表+紧急；有 block 则拦禁止项。
     * allow 与 block 同时存在时：先看 block，再看 allow。
     */
    private fun evaluateActiveDaySchedule(context: Context, packageName: String): HabitEval? {
        val active = runCatching { DayScheduleStore.activeNow() }.getOrNull() ?: return null
        if (packageName in active.blockPackages) {
            return finishBlock(
                context,
                packageName,
                key = "daysched:${active.id}:block:$packageName",
                reason = "日程「${active.title}」禁止此应用",
                sleepLock = false,
                pressBack = false
            )
        }
        if (active.allowPackages.isNotEmpty() && packageName !in active.allowPackages) {
            return finishBlock(
                context,
                packageName,
                key = "daysched:${active.id}:allow:$packageName",
                reason = "日程「${active.title}」仅允许指定应用",
                sleepLock = false,
                pressBack = false
            )
        }
        maybeRewardCompliance(context, packageName)
        return HabitEval.watch()
    }

    private fun isAlwaysAllowed(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName in emergencyPackages(context)) return true
        if (isHomeLauncher(context, packageName)) return true
        return false
    }

    /** 桌面 / 默认 Home：永不拦截（曾被商店误标娱乐整包禁）。 */
    private fun isHomeLauncher(context: Context, packageName: String): Boolean {
        val p = packageName.lowercase()
        if ("launcher" in p) return true
        return runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(home, 0).any { it.activityInfo?.packageName == packageName }
        }.getOrDefault(false)
    }

    private fun emergencyPackages(context: Context): Set<String> {
        val packages = mutableSetOf(
            context.packageName,
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher3",
            "com.smartisanos.launcher"
        )
        runCatching {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage?.let { packages.add(it) }
        }
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { packages.add(it.packageName) }
        }
        return packages
    }

    private fun packagesForSchedule(context: Context, schedules: List<FlashNote>): Set<String> {
        val text = schedules.joinToString(" ") { it.text }.lowercase()
        if (text.isBlank()) return emptySet()
        val matched = mutableSetOf<String>()
        if ("微信" in text || "wechat" in text) matched.add("com.tencent.mm")
        if ("钉钉" in text) matched.add("com.alibaba.android.rimet")
        if ("文档" in text || "docs" in text) matched.add("com.google.android.apps.docs")
        if ("邮件" in text || "gmail" in text) matched.add("com.google.android.gm")
        if ("会议" in text || "meet" in text) matched.add("com.google.android.apps.meet")
        if ("b站" in text || "哔哩" in text || "bilibili" in text) {
            matched.add("tv.danmaku.bili")
            matched.add("com.bilibili.app.in")
        }
        runCatching {
            val pm = context.packageManager
            pm.getInstalledApplications(0).forEach { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.length >= 2 && text.contains(label)) {
                    matched.add(app.packageName)
                }
            }
        }
        return matched
    }

    fun categorize(context: Context, packageName: String): AppCategory {
        val lowered = packageName.lowercase()
        if (VIDEO_HINTS.any { it in lowered }) return AppCategory.VIDEO
        if (GAME_HINTS.any { it in lowered }) return AppCategory.GAME
        if (SOCIAL_HINTS.any { it in lowered }) return AppCategory.SOCIAL
        if (BROWSER_HINTS.any { it in lowered }) return AppCategory.BROWSER
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            when {
                info.category == ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
                info.category == ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
                info.category == ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 -> AppCategory.SYSTEM
                info.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.TOOL
                else -> AppCategory.OTHER
            }
        } catch (_: Exception) {
            AppCategory.OTHER
        }
    }

    private fun habitualPackages(context: Context): Set<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptySet()
        val zone = ZoneId.systemDefault()
        val end = System.currentTimeMillis()
        val start = LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val usage = runCatching { ScreenUsageHelper.fetchUsageInMs(usm, start, end) }
            .getOrDefault(emptyMap())
        return usage.entries
            .asSequence()
            .filter { it.value >= 10 * 60_000L }
            .filter { categorize(context, it.key) == AppCategory.TOOL || !isEntertainment(context, it.key) }
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key }
            .toSet()
    }

    private fun isEntertainment(context: Context, packageName: String): Boolean =
        isEntertainmentCategory(categorize(context, packageName))

    private fun isEntertainmentCategory(category: AppCategory): Boolean =
        category == AppCategory.VIDEO ||
            category == AppCategory.GAME ||
            category == AppCategory.SOCIAL

    private val VIDEO_HINTS = listOf(
        "tiktok", "douyin", "bilibili", "youtube", "iqiyi", "youku",
        "tencent.qqlive", "kuaishou", "triller", "netflix", "video"
    )
    private val GAME_HINTS = listOf("game", "unity", "mihoyo", "hoyoverse", "pubg", "mlbb")
    private val SOCIAL_HINTS = listOf("weibo", "instagram", "facebook", "twitter", "reddit")
    private val BROWSER_HINTS = listOf("chrome", "browser", "firefox", "edge", "opera", "brave")
}
