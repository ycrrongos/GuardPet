package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class ActiveKind {
    WORK, ENTERTAINMENT, NEUTRAL, UNCERTAIN;

    companion object {
        fun fromKey(raw: String?): ActiveKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNCERTAIN
    }
}

data class AppActiveInfo(
    val activity: String,
    val label: String,
    val kind: ActiveKind,
    val blockInWork: Boolean,
    val patterns: List<String> = emptyList(),
    val userConfirmed: Boolean = false
)

/** Per-package Activity / active 面目录：新应用时枚举并交给 DeepSeek 分类。 */
object AppActiveCatalog {
    private const val TAG = "AppActiveCatalog"
    private const val PREFS = "app_active_catalog"
    private const val KEY_PREFIX = "pkg_"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzing = ConcurrentHashMap.newKeySet<String>()
    private val pageResolving = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() =
        requireNotNull(appContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAnalyzing(packageName: String): Boolean = packageName in analyzing

    fun hasCatalog(packageName: String): Boolean {
        if (!prefs().contains(KEY_PREFIX + packageName)) return false
        val list = load(packageName)
        if (list.isNotEmpty()) return true
        // WORK/ENTERTAINMENT 故意存 []；COMMUNICATION/VIDEO 空目录必须重扫
        val kind = HabitPolicyStore.ruleFor(packageName)?.kind ?: return false
        return kind == AppGuardKind.WORK || kind == AppGuardKind.ENTERTAINMENT
    }

    fun load(packageName: String): List<AppActiveInfo> {
        val raw = prefs().getString(KEY_PREFIX + packageName, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val patternsArr = o.optJSONArray("patterns") ?: JSONArray()
                    val patterns = buildList {
                        for (j in 0 until patternsArr.length()) {
                            add(patternsArr.getString(j))
                        }
                    }
                    add(
                        AppActiveInfo(
                            activity = o.optString("activity"),
                            label = o.optString("label"),
                            kind = ActiveKind.fromKey(o.optString("kind")),
                            blockInWork = o.optBoolean("blockInWork", false),
                            patterns = patterns,
                            userConfirmed = o.optBoolean("userConfirmed", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(packageName: String, actives: List<AppActiveInfo>) {
        val arr = JSONArray()
        actives.forEach { a ->
            arr.put(
                JSONObject()
                    .put("activity", a.activity)
                    .put("label", a.label)
                    .put("kind", a.kind.name)
                    .put("blockInWork", a.blockInWork)
                    .put("patterns", JSONArray(a.patterns))
                    .put("userConfirmed", a.userConfirmed)
            )
        }
        prefs().edit().putString(KEY_PREFIX + packageName, arr.toString()).apply()
    }

    fun confirm(
        packageName: String,
        activityOrLabel: String,
        blockInWork: Boolean,
        kind: ActiveKind = if (blockInWork) ActiveKind.ENTERTAINMENT else ActiveKind.WORK
    ) {
        val list = load(packageName).toMutableList()
        val idx = list.indexOfFirst {
            it.activity == activityOrLabel || it.label == activityOrLabel
        }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                kind = kind,
                blockInWork = blockInWork,
                userConfirmed = true
            )
        } else {
            list.add(
                AppActiveInfo(
                    activity = activityOrLabel,
                    label = activityOrLabel,
                    kind = kind,
                    blockInWork = blockInWork,
                    patterns = listOf(activityOrLabel).filter { it.isNotBlank() },
                    userConfirmed = true
                )
            )
        }
        save(packageName, list)
        syncSurfacesToPolicy(packageName, list)
    }

    fun clearPackage(packageName: String) {
        prefs().edit().remove(KEY_PREFIX + packageName).apply()
        analyzing.remove(packageName)
        pageResolving.removeIf { it.startsWith("$packageName:") }
    }

    /** 删除规则后强制重新按 Active 名分析。 */
    fun resetAndReanalyze(context: Context, packageName: String) {
        HabitPolicyStore.removeRule(packageName)
        clearPackage(packageName)
        val app = context.applicationContext
        appContext = app
        if (!analyzing.add(packageName)) return
        executor.execute {
            try {
                analyzeByNameOnly(app, packageName)
            } finally {
                analyzing.remove(packageName)
            }
        }
    }

    /**
     * 点「重新分析」：拉取用量/已装应用的全部 Activity 名，交给 DeepSeek 按名字判断。
     * 明显娱乐 → 禁用；说不清 → UNCERTAIN，等进入该页再抓元素。
     */
    fun analyzeAllByNamesAsync(
        context: Context,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        val app = context.applicationContext
        appContext = app
        executor.execute {
            val result = runCatching { analyzeAllByNamesBlocking(app) }
            val (ok, msg) = result.getOrElse { false to (it.message ?: "分析失败") }
            mainHandler.post { onDone?.invoke(ok, msg) }
        }
    }

    fun ensureAnalyzed(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        if (hasCatalog(packageName)) return
        if (!analyzing.add(packageName)) return
        val app = context.applicationContext
        appContext = app
        executor.execute {
            try {
                analyzeByNameOnly(app, packageName)
            } finally {
                analyzing.remove(packageName)
            }
        }
    }

    /**
     * 名字不确定的 Active：用户已进入该页时，用页面元素辅助判定。
     * 主壳 / 混合入口页不算娱乐。
     */
    fun resolveUncertainOnPage(
        context: Context,
        packageName: String,
        activityClass: String?,
        visibleTexts: List<String>
    ): Boolean {
        if (CommActiveLists.isMainShell(activityClass)) return false
        val uncertain = matchUncertain(packageName, activityClass, visibleTexts) ?: return false
        val onTargetPage = !activityClass.isNullOrBlank() && (
            uncertain.activity == activityClass ||
                activityClass.endsWith(uncertain.activity) ||
                uncertain.activity.endsWith(activityClass.substringAfterLast('.'))
            )
        if (!onTargetPage) return false

        val verdict = PageEntertainmentJudge.judge(visibleTexts, packageName)
        when (verdict) {
            PageVerdict.SHELL_MIXED, PageVerdict.WORK_PAGE -> {
                confirm(packageName, uncertain.activity, blockInWork = false, kind = ActiveKind.WORK)
                return false
            }
            PageVerdict.ENTERTAINMENT_PAGE -> {
                confirm(packageName, uncertain.activity, blockInWork = true, kind = ActiveKind.ENTERTAINMENT)
                enrichPatterns(packageName, uncertain.activity, visibleTexts)
                return true
            }
            PageVerdict.UNCLEAR -> {
                val key = "$packageName:${uncertain.activity}"
                if (pageResolving.add(key)) {
                    val app = context.applicationContext
                    executor.execute {
                        try {
                            resolvePageWithLlm(app, packageName, uncertain.activity, visibleTexts)
                        } finally {
                            pageResolving.remove(key)
                        }
                    }
                }
                return false
            }
        }
    }

    private fun resolvePageWithLlm(
        context: Context,
        packageName: String,
        activity: String,
        visibleTexts: List<String>
    ) {
        if (HabitPolicyStore.llmApiKey.isBlank()) return
        val user = JSONObject()
            .put("packageName", packageName)
            .put("activity", activity)
            .put("pageTexts", JSONArray(visibleTexts.take(50)))
            .toString()
        val result = HabitLlmClient.chatJson(PageEntertainmentJudge.llmSystemPrompt(), user)
        val verdict = runCatching {
            PageVerdict.valueOf(result.json?.optString("verdict").orEmpty())
        }.getOrDefault(PageVerdict.UNCLEAR)
        when (verdict) {
            PageVerdict.ENTERTAINMENT_PAGE -> {
                confirm(packageName, activity, blockInWork = true, kind = ActiveKind.ENTERTAINMENT)
                enrichPatterns(packageName, activity, visibleTexts)
            }
            PageVerdict.SHELL_MIXED, PageVerdict.WORK_PAGE -> {
                confirm(packageName, activity, blockInWork = false, kind = ActiveKind.WORK)
            }
            PageVerdict.UNCLEAR -> Unit
        }
        Log.i(TAG, "page LLM $packageName/$activity -> $verdict")
    }

    private fun enrichPatterns(packageName: String, activity: String, texts: List<String>) {
        val list = load(packageName).toMutableList()
        val idx = list.indexOfFirst { it.activity == activity }
        if (idx < 0) return
        val extra = texts.map { it.trim() }.filter { it.length in 2..16 }.distinct().take(6)
        val old = list[idx]
        list[idx] = old.copy(patterns = (old.patterns + extra).distinct().take(12))
        save(packageName, list)
        syncSurfacesToPolicy(packageName, list)
    }

    private fun analyzeAllByNamesBlocking(context: Context): Pair<Boolean, String> {
        val packages = collectCandidatePackages(context)
        if (packages.isEmpty()) return false to "没有可分析的应用（需要用量权限或已安装应用）"

        // 已知包（微信/B站等）本地短路，不走商店；避免 Play 超时拖死整次分析
        val localKinds = linkedMapOf<String, PackageKindVerdict>()
        packages.forEach { pkg ->
            AppTierClassifier.knownKind(pkg)?.let { kind ->
                localKinds[pkg] = PackageKindVerdict(
                    kind = kind,
                    reason = "内置名单 → $kind",
                    source = "local"
                )
            }
        }
        val needStore = packages.filter { it !in localKinds }.map {
            it to AppTierClassifier.appLabel(context, it)
        }
        val storeMetas = AppStoreMetaFetcher.fetchMany(needStore)
        val storeKinds = linkedMapOf<String, PackageKindVerdict>()
        storeMetas.forEach { (pkg, meta) ->
            val kind = StoreCategoryMapper.map(meta.categories) ?: return@forEach
            storeKinds[pkg] = PackageKindVerdict(
                kind = kind,
                reason = "商店分类（${meta.summary}）→ $kind",
                source = "store"
            )
        }
        val needAi = packages.filter { it !in localKinds && it !in storeKinds }
        val aiKinds = classifyPackageKindsWithAi(context, needAi, storeMetas)

        var okCount = 0
        var failCount = 0
        var storeHit = 0
        var aiHit = 0
        var localHit = 0
        packages.forEach { pkg ->
            analyzing.add(pkg)
            try {
                val pre = localKinds[pkg] ?: storeKinds[pkg] ?: aiKinds[pkg]
                when (pre?.source) {
                    "local" -> localHit++
                    "store" -> storeHit++
                    "ai" -> aiHit++
                }
                analyzeByNameOnly(context, pkg, pre)
                okCount++
            } catch (t: Throwable) {
                Log.w(TAG, "analyze $pkg failed", t)
                failCount++
            } finally {
                analyzing.remove(pkg)
            }
        }
        val modeNote = buildString {
            append("本地 $localHit / 商店 $storeHit")
            if (HabitPolicyStore.llmApiKey.isBlank()) {
                append("；未填 API Key，其余启发式")
            } else {
                append("；AI 补全 $aiHit")
                append("；其余启发式")
            }
        }
        HabitPolicyStore.savePolicy(
            HabitPolicyStore.loadPolicy().copy(
                summary = "已分析 ${okCount} 个应用（$modeNote）。工作不限 / 娱乐整禁 / 视频仅搜索 / 通讯按 Active 筛",
                generatedAt = System.currentTimeMillis()
            )
        )
        return true to "完成（$modeNote）：成功 $okCount，失败 $failCount。"
    }

    private fun collectCandidatePackages(context: Context): List<String> {
        val priority = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "com.ss.android.ugc.aweme",
            "com.smile.gifmaker",
            "com.xingin.xhs",
            "com.coolapk.market",
            "com.sankuai.meituan",
            "com.taobao.taobao"
        )
        val pm = context.packageManager
        val fromUsage = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                ?: return@runCatching emptyList()
            val end = System.currentTimeMillis()
            val start = end - 7L * 24 * 60 * 60 * 1000
            usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end)
                ?.filter { it.totalTimeInForeground >= 60_000L }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.map { it.packageName }
                .orEmpty()
        }.getOrDefault(emptyList())
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fromLauncher = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val existing = HabitPolicyStore.loadPolicy().packageRules.map { it.packageName }
        val installedPriority = priority.filter {
            runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false)
        }
        return (installedPriority + fromUsage + fromLauncher + existing)
            .distinct()
            .filter { pkg ->
                pkg != context.packageName &&
                    !pkg.startsWith("com.android.") &&
                    !AppStoreMetaFetcher.shouldSkipStoreLookup(pkg) &&
                    "launcher" !in pkg.lowercase()
            }
            .take(40)
    }

    private data class PackageKindVerdict(
        val kind: AppGuardKind,
        val reason: String,
        /** store | ai | local */
        val source: String
    )

    private fun analyzeByNameOnly(
        context: Context,
        packageName: String,
        preclassified: PackageKindVerdict? = null
    ) {
        val appLabel = AppTierClassifier.appLabel(context, packageName)
        val verdict = preclassified ?: classifyPackageKind(context, packageName, appLabel)
        val reason = verdict.reason.ifBlank {
            when (verdict.kind) {
                AppGuardKind.WORK -> "工作应用：工作时不限制"
                AppGuardKind.ENTERTAINMENT -> "明确娱乐应用：工作时段整包禁用"
                AppGuardKind.COMMUNICATION -> "通讯应用：按 Active 筛娱乐面"
                AppGuardKind.VIDEO -> "视频应用：工作时段仅允许搜索"
            }
        }
        when (verdict.kind) {
            AppGuardKind.WORK -> {
                save(packageName, emptyList())
                HabitPolicyStore.upsertAgentKind(
                    packageName,
                    AppGuardKind.WORK,
                    reason = reason,
                    clearSurfaces = true
                )
                return
            }
            AppGuardKind.ENTERTAINMENT -> {
                save(packageName, emptyList())
                HabitPolicyStore.upsertAgentKind(
                    packageName,
                    AppGuardKind.ENTERTAINMENT,
                    reason = reason,
                    clearSurfaces = true
                )
                return
            }
            AppGuardKind.COMMUNICATION -> {
                val listed = listPackageActivities(context, packageName)
                if (listed.isEmpty()) {
                    Log.w(TAG, "no activities from PM for $packageName, using seed list")
                }
                // 始终合并种子（朋友圈/视频号），避免 PM 列表有主壳却缺插件 Active
                val actives = seedCommActivities(packageName, listed)
                // 微信 PM 可枚举两千+ Activity；只保留娱乐/主壳相关再分类，否则极慢且误标
                val trimmed = trimCommActivesForClassify(packageName, actives)
                val classified = forceSeedEntertainmentActives(
                    packageName,
                    classifyCommunicationActives(context, packageName, trimmed)
                )
                save(packageName, classified)
                syncSurfacesToPolicy(packageName, classified, AppGuardKind.COMMUNICATION)
                HabitPolicyStore.upsertAgentKind(
                    packageName,
                    AppGuardKind.COMMUNICATION,
                    reason = reason,
                    surfaces = HabitPolicyStore.ruleFor(packageName)?.surfaces.orEmpty(),
                    clearSurfaces = false
                )
                return
            }
            AppGuardKind.VIDEO -> {
                val actives = listPackageActivities(context, packageName)
                val classified = classifyVideoActives(context, packageName, actives)
                save(packageName, classified)
                HabitPolicyStore.upsertAgentKind(
                    packageName,
                    AppGuardKind.VIDEO,
                    reason = reason.ifBlank { "视频应用：工作时段仅允许搜索" },
                    surfaces = HabitPolicyStore.defaultVideoSurfaces(
                        packageName.substringAfterLast('.').take(12)
                    ),
                    clearSurfaces = false
                )
                return
            }
        }
    }

    /**
     * 对商店未覆盖的应用，带上商店线索批量问 LLM。
     */
    private fun classifyPackageKindsWithAi(
        context: Context,
        packages: List<String>,
        storeMetas: Map<String, AppStoreMetaFetcher.Meta>
    ): Map<String, PackageKindVerdict> {
        if (HabitPolicyStore.llmApiKey.isBlank() || packages.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, PackageKindVerdict>()
        packages.chunked(10).forEach { chunk ->
            val apps = JSONArray()
            chunk.forEach { pkg ->
                val label = AppTierClassifier.appLabel(context, pkg)
                val meta = storeMetas[pkg]
                apps.put(
                    JSONObject()
                        .put("packageName", pkg)
                        .put("label", label)
                        .put("storeCategories", JSONArray(meta?.categories.orEmpty()))
                        .put("storeSource", meta?.source ?: "none")
                )
            }
            val system = """
你是守伴的应用分类器。优先采信 storeCategories（来自 Google Play / iTunes 公开分类）。
只输出 JSON：
{"apps":[{"packageName":"包名","kind":"WORK|ENTERTAINMENT|COMMUNICATION|VIDEO","reason":"一句话"}]}
定义：
- WORK：办公/工具/系统/学习/金融工具/导航等生产力
- ENTERTAINMENT：游戏、短视频、刷帖社区、以及生活/购物/美食/旅游等消遣（如美团 LIFESTYLE、淘宝购物）——工作时段整包禁
- COMMUNICATION：以即时通讯为主（微信/QQ/飞书等）
- VIDEO：长视频站（B站、油管、爱奇艺等）
禁止把美团、饿了么、淘宝、京东、小红书、酷安判成 WORK。
            """.trimIndent()
            val user = JSONObject().put("apps", apps).toString()
            val result = HabitLlmClient.chatJson(system, user)
            if (!result.ok || result.json == null) {
                Log.w(TAG, "batch kind AI failed: ${result.error}")
                return@forEach
            }
            val arr = result.json.optJSONArray("apps") ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("packageName")
                val kind = parseGuardKind(o.optString("kind")) ?: continue
                if (pkg.isBlank()) continue
                out[pkg] = PackageKindVerdict(
                    kind = kind,
                    reason = o.optString("reason").ifBlank { "AI：$kind" },
                    source = "ai"
                )
            }
        }
        return out
    }

    private fun parseGuardKind(raw: String?): AppGuardKind? =
        AppGuardKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun classifyPackageKind(
        context: Context,
        packageName: String,
        appLabel: String
    ): PackageKindVerdict {
        AppTierClassifier.knownKind(packageName)?.let { kind ->
            return PackageKindVerdict(kind, "内置名单 → $kind", source = "local")
        }
        val meta = AppStoreMetaFetcher.fetchOne(packageName, appLabel)
        StoreCategoryMapper.map(meta.categories)?.let { kind ->
            return PackageKindVerdict(
                kind = kind,
                reason = "商店分类（${meta.summary}）→ $kind",
                source = "store"
            )
        }
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            val kind = AppTierClassifier.classify(context, packageName)
            return PackageKindVerdict(kind, "本地：$kind", source = "local")
        }
        val system = """
把 App 分成四类之一（只输出 JSON）：
{"kind":"WORK|ENTERTAINMENT|COMMUNICATION|VIDEO","reason":"一句话"}
商店线索：${meta.summary}
WORK=办公工具；ENTERTAINMENT=游戏/短视频/社区/生活购物美食旅游消遣（美团等）；COMMUNICATION=即时通讯；VIDEO=长视频站。
禁止把美团/淘宝/小红书/酷安判成 WORK。
        """.trimIndent()
        val user = JSONObject()
            .put("packageName", packageName)
            .put("label", appLabel)
            .put("storeCategories", JSONArray(meta.categories))
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        val llmKind = parseGuardKind(result.json?.optString("kind"))
        if (result.ok && llmKind != null) {
            return PackageKindVerdict(
                kind = llmKind,
                reason = result.json?.optString("reason").orEmpty().ifBlank { "AI：$llmKind" },
                source = "ai"
            )
        }
        Log.w(TAG, "single kind AI fallback $packageName: ${result.error}")
        val kind = AppTierClassifier.classify(context, packageName)
        return PackageKindVerdict(kind, "本地兜底：$kind", source = "local")
    }

    /**
     * 朋友圈/视频号等种子 Active 必须 blockInWork=true。
     * 全量启发式/AI 曾把种子冲掉，只剩 FinderChattingUI 等误标项。
     */
    private fun forceSeedEntertainmentActives(
        packageName: String,
        classified: List<AppActiveInfo>
    ): List<AppActiveInfo> {
        val seeds = seedCommActivities(packageName, emptyList())
        if (seeds.isEmpty()) return classified
        val appLabel = AppTierClassifier.appLabel(
            requireNotNull(appContext),
            packageName
        )
        val byName = classified.associateBy { it.activity }.toMutableMap()
        for ((name, label) in seeds) {
            byName[name] = AppActiveInfo(
                activity = name,
                label = label,
                kind = ActiveKind.ENTERTAINMENT,
                blockInWork = true,
                patterns = entertainmentPatternsFrom(
                    name, label, appLabel, packageName = packageName
                ),
                userConfirmed = true
            )
        }
        // 聊天壳挂了 finder/sns 字样的不要当「已进娱乐面」
        return byName.values.map { info ->
            val short = info.activity.substringAfterLast('.')
            if (info.blockInWork &&
                short.contains("Chatting", ignoreCase = true) &&
                seeds.none { it.first == info.activity }
            ) {
                info.copy(kind = ActiveKind.WORK, blockInWork = false, patterns = emptyList())
            } else {
                info
            }
        }
    }

    /** 通讯类只留种子 + 像娱乐/主壳的 Active，避免微信 2000+ 全量进启发式/AI。 */
    private fun trimCommActivesForClassify(
        packageName: String,
        actives: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        if (actives.size <= 80) return actives
        val seedNames = seedCommActivities(packageName, emptyList()).map { it.first }.toHashSet()
        val kept = actives.filter { (name, label) ->
            name in seedNames ||
                CommActiveLists.isMainShell(name) ||
                looksEntertainmentName(name, label) ||
                looksWorkName(name, label)
        }
        val out = if (kept.size >= 12) kept else actives.take(80)
        Log.i(TAG, "trimComm $packageName ${actives.size} → ${out.size}")
        return out.distinctBy { it.first }.take(120)
    }

    private fun classifyCommunicationActives(
        context: Context,
        packageName: String,
        actives: List<Pair<String, String>>
    ): List<AppActiveInfo> {
        if (actives.isEmpty()) return emptyList()
        val appLabel = AppTierClassifier.appLabel(context, packageName)
        val normalized = actives.map { (name, label) ->
            name to displayLabelForActive(name, label, appLabel)
        }
        val heuristic = normalized.map { (n, l) -> heuristicClassifyNameOnly(n, l, appLabel) }
        val candidates = normalized.filter { (name, label) ->
            looksEntertainmentName(name, label) || looksWorkName(name, label)
        }.ifEmpty { normalized.take(40) }

        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return sanitizeEntertainmentMarks(heuristic, appLabel)
        }
        val system = """
这是通讯类 App。根据 Activity「完整类名+短标签」标出娱乐面。
规则：
1. 娱乐面：朋友圈/Moment/Sns、视频号/Finder、看一看/TopStory、看点、小世界、游戏中心、QQ空间等 → kind=ENTERTAINMENT, blockInWork=true
2. 主界面 Splash/Launcher、聊天/会话/设置/登录/通讯录 → kind=WORK, blockInWork=false
3. 其它 → kind=UNCERTAIN
4. 标签等于应用名时不要当依据；patterns 填中文入口名，禁止填应用名与「消息/动态/更多」等主壳字
5. 合理数量约 15–60，不要把 SplashActivity 标成娱乐
只输出 JSON：
{"actives":[{"activity":"完整类名","label":"短名","kind":"WORK|ENTERTAINMENT|UNCERTAIN","blockInWork":true,"patterns":["朋友圈"]}]}
        """.trimIndent()
        val user = JSONObject()
            .put("packageName", packageName)
            .put("appLabel", appLabel)
            .put(
                "actives",
                JSONArray().also { arr ->
                    candidates.take(80).forEach { (name, label) ->
                        arr.put(
                            JSONObject()
                                .put("activity", name)
                                .put("label", label)
                                .put("shortName", name.substringAfterLast('.'))
                        )
                    }
                }
            )
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        val merged = if (result.ok && result.json != null) {
            mergeParsedActives(result.json, normalized, appLabel, heuristic)
        } else {
            heuristic
        }
        return sanitizeEntertainmentMarks(merged, appLabel)
    }

    private fun tightenNameOnly(info: AppActiveInfo, appLabel: String): AppActiveInfo {
        if (CommActiveLists.isMainShell(info.activity)) {
            return info.copy(
                kind = ActiveKind.WORK,
                blockInWork = false,
                patterns = emptyList()
            )
        }
        val cleaned = info.copy(
            patterns = entertainmentPatternsFrom(
                info.activity, info.label, appLabel, info.patterns, guessPkg(info.activity)
            )
        )
        return when (cleaned.kind) {
            ActiveKind.ENTERTAINMENT -> {
                if (!looksEntertainmentName(cleaned.activity, cleaned.label) &&
                    cleaned.patterns.isEmpty()
                ) {
                    cleaned.copy(blockInWork = false, kind = ActiveKind.UNCERTAIN)
                } else {
                    cleaned.copy(
                        blockInWork = true,
                        patterns = cleaned.patterns.ifEmpty {
                            entertainmentPatternsFrom(
                                cleaned.activity, cleaned.label, appLabel,
                                packageName = guessPkg(cleaned.activity)
                            )
                        }
                    )
                }
            }
            ActiveKind.UNCERTAIN -> cleaned.copy(blockInWork = false, kind = ActiveKind.UNCERTAIN)
            ActiveKind.WORK, ActiveKind.NEUTRAL -> cleaned.copy(blockInWork = false)
        }
    }

    private fun heuristicClassifyNameOnly(
        activity: String,
        label: String,
        appLabel: String
    ): AppActiveInfo {
        if (CommActiveLists.isMainShell(activity)) {
            return AppActiveInfo(activity, label, ActiveKind.WORK, blockInWork = false, patterns = emptyList())
        }
        return when {
            looksEntertainmentName(activity, label) -> AppActiveInfo(
                activity, label, ActiveKind.ENTERTAINMENT, blockInWork = true,
                patterns = entertainmentPatternsFrom(
                    activity, label, appLabel, packageName = guessPkg(activity)
                )
            )
            looksWorkName(activity, label) -> AppActiveInfo(
                activity, label, ActiveKind.WORK, blockInWork = false, patterns = emptyList()
            )
            else -> AppActiveInfo(
                activity, label, ActiveKind.UNCERTAIN, blockInWork = false, patterns = emptyList()
            )
        }
    }

    private fun displayLabelForActive(activity: String, label: String, appLabel: String): String {
        val short = activity.substringAfterLast('.')
        if (label.isBlank() || label.equals(appLabel, ignoreCase = true)) return short
        if (label.length <= 1) return short
        return label
    }

    private fun looksEntertainmentName(activity: String, label: String): Boolean {
        if (CommActiveLists.isMainShell(activity)) return false
        if (CommActiveLists.looksQqEntertainmentPath(activity)) return true
        val path = activity.lowercase()
        val short = activity.substringAfterLast('.').lowercase()
        val labelL = label.lowercase()
        val pathHits = listOf(
            ".plugin.sns.", ".plugin.finder.", ".plugin.topstory.",
            ".plugin.game.", ".plugin.gamecenter.", ".plugin.minigame.",
            ".plugin.shake.", ".plugin.nearby.",
            ".qzone.", ".qqzone.", ".readinjoy.", ".ilive.", ".qqlive.",
            ".minigame.", ".channels."
        )
        if (pathHits.any { it in path }) {
            // 会话壳（*ChattingUI）不算已进入朋友圈/视频号页
            if (short.contains("chatting")) return false
            if (looksWorkName(activity, label) &&
                listOf("sns", "finder", "qzone", "readinjoy").none { it in path }
            ) {
                return false
            }
            return true
        }
        val strong = listOf(
            "moment", "snstimeline", "snsui", "snsupload", "snsbrowse", "snscomment",
            "finderhome", "finderprofile", "finderfeed", "finderlive",
            "shortvideo", "gamecenter", "minigame", "lookaround", "topstory",
            "qzone", "readinjoy", "qqlive",
            "朋友圈", "视频号", "看一看", "看点", "小世界", "游戏中心", "附近的人", "摇一摇"
        )
        if (strong.any { it in short || it in labelL || it in path }) return true
        // 弱词：仅短类名；排除主壳与 Scheme / 埋点路由壳
        if (short.contains("schemerouter") || short.contains("splash") || "videoreport" in path) {
            return false
        }
        return short.contains("live") && "search" !in short && !looksWorkName(activity, label)
    }

    private fun entertainmentPriority(activity: String): Int {
        if (CommActiveLists.isMainShell(activity)) return -1
        val p = activity.lowercase()
        val short = activity.substringAfterLast('.').lowercase()
        return when {
            ".plugin.sns." in p || "snstimeline" in short -> 100
            ".plugin.finder." in p || short.startsWith("finder") -> 98
            CommActiveLists.looksQqEntertainmentPath(activity) -> 93
            "qzone" in p || "readinjoy" in p -> 92
            ".plugin.topstory." in p || "topstory" in p -> 88
            ".plugin.game." in p || "gamecenter" in p || "minigame" in p -> 75
            ".plugin.shake." in p || ".plugin.nearby." in p -> 45
            "live" in short -> 50
            else -> 40
        }
    }

    private fun looksWorkName(activity: String, label: String): Boolean {
        if (CommActiveLists.isMainShell(activity)) return true
        val short = activity.substringAfterLast('.').lowercase()
        val blob = "$short ${label.lowercase()}"
        return listOf(
            "chat", "chatting", "conversation", "launcher", "login", "setting", "splash",
            "contact", "mail", "meeting", "search",
            "聊天", "会话", "通讯录", "设置", "搜索"
        ).any { it in blob }
    }

    private fun entertainmentPatternsFrom(
        activity: String,
        label: String,
        appLabel: String,
        extra: List<String> = emptyList(),
        packageName: String = ""
    ): List<String> {
        if (CommActiveLists.isMainShell(activity)) return emptyList()
        val path = activity.lowercase()
        val short = activity.substringAfterLast('.')
        val banned = setOf(
            appLabel.trim().lowercase(),
            activity.lowercase(),
            short.lowercase()
        ).filter { it.isNotBlank() }
        val mapped = buildList {
            if (".plugin.sns." in path || "sns" in short.lowercase()) add("朋友圈")
            if (".plugin.finder." in path || short.lowercase().startsWith("finder")) add("视频号")
            if (".channels." in path) add("视频号")
            if (".plugin.topstory." in path || "topstory" in path) add("看一看")
            if (".plugin.game." in path || "gamecenter" in path || "minigame" in path) add("游戏中心")
            if (".plugin.shake." in path) add("摇一摇")
            if (".plugin.nearby." in path) add("附近的人")
            if ("qzone" in path) add("QQ空间")
            if ("readinjoy" in path || "kandian" in path || "qqhotspot" in path) add("看点")
            if ("qqlive" in path || "ilive" in path || "liveroom" in path) add("直播")
        }
        val fromName = listOf(
            "朋友圈", "视频号", "看一看", "看点", "小世界", "直播", "游戏中心",
            "QQ空间", "摇一摇", "附近的人", "Moments", "Channels", "Finder"
        ).filter { key ->
            key.lowercase() in path || key in label
        }
        val pkg = packageName.ifBlank {
            activity.substringBefore('.').let { "" } // filled by callers when known
        }
        return (extra + mapped + fromName)
            .map { it.trim() }
            .filter { it.length in 2..16 }
            .filter { it.lowercase() !in banned }
            .filter { !CommActiveLists.isChromeKeyword(pkg.ifBlank { guessPkg(activity) }, it) }
            .distinct()
            .take(8)
    }

    private fun guessPkg(activity: String): String = when {
        activity.startsWith("com.tencent.mobileqq") || activity.startsWith("cooperation.qzone") ||
            activity.startsWith("com.qzone") || activity.startsWith("cooperation.ilive") ->
            "com.tencent.mobileqq"
        activity.startsWith("com.tencent.mm") -> "com.tencent.mm"
        else -> ""
    }

    private fun sanitizeEntertainmentMarks(
        actives: List<AppActiveInfo>,
        appLabel: String,
        maxEntertainment: Int = 60
    ): List<AppActiveInfo> {
        val tightened = actives.map { tightenNameOnly(it, appLabel) }
        val entertainment = tightened.filter { it.kind == ActiveKind.ENTERTAINMENT && it.blockInWork }
        // 强信号（朋友圈/视频号/空间等）全保留；弱信号再限额
        val strong = entertainment.filter { entertainmentPriority(it.activity) >= 85 }
        val weak = entertainment.filter { entertainmentPriority(it.activity) < 85 }
            .sortedByDescending { entertainmentPriority(it.activity) }
        val weakBudget = (maxEntertainment - strong.size).coerceAtLeast(16)
        val keep = (strong + weak.take(weakBudget)).map { it.activity }.toSet()
        return tightened.map { info ->
            if (CommActiveLists.isMainShell(info.activity)) {
                info.copy(kind = ActiveKind.WORK, blockInWork = false, patterns = emptyList())
            } else if (info.blockInWork && info.activity !in keep) {
                info.copy(kind = ActiveKind.UNCERTAIN, blockInWork = false, patterns = emptyList())
            } else {
                info
            }
        }
    }

    private fun mergeParsedActives(
        json: JSONObject,
        all: List<Pair<String, String>>,
        appLabel: String,
        heuristic: List<AppActiveInfo>
    ): List<AppActiveInfo> {
        val heurBy = heuristic.associateBy { it.activity }
        val parsed = parseActives(json, all).associateBy { it.activity }
        return all.map { (activity, label) ->
            val shown = displayLabelForActive(activity, label, appLabel)
            val fromLlm = parsed[activity]
            val fromHeur = heurBy[activity]
            when {
                fromLlm != null -> {
                    val t = tightenNameOnly(fromLlm.copy(label = shown), appLabel)
                    if (t.kind != ActiveKind.ENTERTAINMENT &&
                        fromHeur?.kind == ActiveKind.ENTERTAINMENT
                    ) {
                        fromHeur
                    } else {
                        t
                    }
                }
                fromHeur != null -> fromHeur
                else -> heuristicClassifyNameOnly(activity, shown, appLabel)
            }
        }
    }

    fun matchBlockingActive(
        packageName: String,
        activityClass: String?,
        visibleTexts: List<String>
    ): AppActiveInfo? {
        val catalog = load(packageName)
        if (catalog.isEmpty()) return null
        val inWork = HabitPolicyStore.isWorkTime()
        if (!inWork) return null
        // QQ/微信主壳永不按 Active 名拦截
        if (CommActiveLists.isMainShell(activityClass)) return null

        if (!activityClass.isNullOrBlank()) {
            val byActivity = catalog.firstOrNull { info ->
                info.blockInWork &&
                    !CommActiveLists.isMainShell(info.activity) &&
                    (info.activity == activityClass ||
                        activityClass.endsWith(info.activity) ||
                        info.activity.endsWith(activityClass.substringAfterLast('.')))
            }
            if (byActivity != null) return byActivity
        }

        // 文本匹配：只用明确关键词 equals；过滤壳层入口字
        return catalog.firstOrNull { info ->
            if (!info.blockInWork || CommActiveLists.isMainShell(info.activity)) return@firstOrNull false
            val keys = info.patterns.filter {
                it.length in 2..16 && !CommActiveLists.isChromeKeyword(packageName, it)
            }
            keys.any { key -> visibleTexts.any { it.equals(key, ignoreCase = true) } }
        }
    }

    fun matchUncertain(
        packageName: String,
        activityClass: String?,
        visibleTexts: List<String>
    ): AppActiveInfo? {
        val catalog = load(packageName)
        if (catalog.isEmpty()) return null
        if (!HabitPolicyStore.isWorkTime()) return null
        if (!activityClass.isNullOrBlank()) {
            catalog.firstOrNull {
                it.kind == ActiveKind.UNCERTAIN &&
                    !it.userConfirmed &&
                    (it.activity == activityClass || activityClass.endsWith(it.activity))
            }?.let { return it }
        }
        return catalog.firstOrNull { info ->
            if (info.kind != ActiveKind.UNCERTAIN || info.userConfirmed) return@firstOrNull false
            val keys = (info.patterns + listOf(info.label)).filter { it.length >= 2 }
            keys.any { key -> visibleTexts.any { it.equals(key, ignoreCase = true) } }
        }
    }

    private fun listPackageActivities(
        context: Context,
        packageName: String
    ): List<Pair<String, String>> {
        val appLabel = AppTierClassifier.appLabel(context, packageName)
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                pm.getPackageInfo(packageName, flags)
            }
            info.activities.orEmpty().mapNotNull { ai ->
                val name = ai.name ?: return@mapNotNull null
                if (name.startsWith("androidx.") ||
                    name.startsWith("android.") ||
                    name.startsWith("com.google.android.") ||
                    name.startsWith("com.google.firebase.")
                ) {
                    return@mapNotNull null
                }
                val rawLabel = runCatching { ai.loadLabel(pm).toString() }.getOrDefault("")
                name to displayLabelForActive(name, rawLabel, appLabel)
            }.distinctBy { it.first }
        }.onFailure {
            Log.w(TAG, "listPackageActivities $packageName failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 仅当 PackageManager 枚举为空时使用（一刀切前微信曾靠完整 Active 列表）。 */
    private fun seedCommActivities(
        packageName: String,
        listed: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val seeds = when (packageName) {
            "com.tencent.mm" -> listOf(
                "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI" to "朋友圈",
                "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI" to "朋友圈",
                "com.tencent.mm.plugin.sns.ui.SnsUserUI" to "朋友圈",
                "com.tencent.mm.plugin.finder.ui.FinderHomeUI" to "视频号",
                "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI" to "视频号",
                "com.tencent.mm.plugin.finder.feed.ui.FinderProfileUI" to "视频号",
                "com.tencent.mm.plugin.finder.feed.ui.FinderFeedDetailUI" to "视频号",
                "com.tencent.mm.plugin.topstory.ui.home.TopStoryHomeUI" to "看一看"
            )
            "com.tencent.mobileqq" -> listOf(
                "com.tencent.mobileqq.activity.QZoneFriendFeedActivity" to "QQ空间",
                "cooperation.qzone.QzoneFeedsPluginProxyActivity" to "QQ空间",
                "com.tencent.mobileqq.kandian.biz.daily.ReadInJoyDailyActivity" to "看点"
            )
            else -> emptyList()
        }
        if (seeds.isEmpty()) return listed
        val names = listed.map { it.first }.toHashSet()
        return listed + seeds.filter { it.first !in names }
    }

    private fun classifyVideoActives(
        context: Context,
        packageName: String,
        actives: List<Pair<String, String>>
    ): List<AppActiveInfo> {
        val appLabel = AppTierClassifier.appLabel(context, packageName)
        return actives.map { (name, label) ->
            val shown = displayLabelForActive(name, label, appLabel)
            val short = name.substringAfterLast('.').lowercase()
            val path = name.lowercase()
            when {
                listOf("search", "sousuo").any { it in short } || shown.contains("搜索") ->
                    AppActiveInfo(name, shown, ActiveKind.WORK, blockInWork = false, patterns = emptyList())
                listOf("player", "videodetail", "ugcdetail", "bangumi", "playerview").any { it in short } ->
                    AppActiveInfo(
                        name, shown, ActiveKind.ENTERTAINMENT, blockInWork = true,
                        patterns = entertainmentPatternsFrom(name, shown, appLabel)
                    )
                (short.contains("live") && "search" !in short) || path.contains(".live.") ->
                    AppActiveInfo(
                        name, shown, ActiveKind.ENTERTAINMENT, blockInWork = true,
                        patterns = entertainmentPatternsFrom(name, shown, appLabel)
                    )
                (short.contains("video") && "search" !in short) ||
                    path.contains(".video.") || path.contains(".player.") ->
                    AppActiveInfo(
                        name, shown, ActiveKind.ENTERTAINMENT, blockInWork = true,
                        patterns = entertainmentPatternsFrom(name, shown, appLabel)
                    )
                else ->
                    AppActiveInfo(name, shown, ActiveKind.UNCERTAIN, blockInWork = false, patterns = emptyList())
            }
        }.let { sanitizeEntertainmentMarks(it, appLabel, maxEntertainment = 80) }
    }

    private fun parseActives(
        json: JSONObject,
        fallback: List<Pair<String, String>>
    ): List<AppActiveInfo> {
        val arr = json.optJSONArray("actives") ?: return emptyList()
        val byName = fallback.associateBy { it.first }
        val out = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val activity = o.optString("activity").trim()
                if (activity.isBlank()) continue
                val patternsArr = o.optJSONArray("patterns") ?: JSONArray()
                val patterns = buildList {
                    for (j in 0 until patternsArr.length()) {
                        val p = patternsArr.optString(j).trim()
                        if (p.isNotEmpty()) add(p)
                    }
                }
                val kind = ActiveKind.fromKey(o.optString("kind"))
                add(
                    AppActiveInfo(
                        activity = activity,
                        label = o.optString("label").ifBlank {
                            byName[activity]?.second ?: activity.substringAfterLast('.')
                        },
                        kind = kind,
                        blockInWork = o.optBoolean(
                            "blockInWork",
                            kind == ActiveKind.ENTERTAINMENT
                        ),
                        patterns = patterns
                    )
                )
            }
        }
        return out
    }

    private fun syncSurfacesToPolicy(
        packageName: String,
        actives: List<AppActiveInfo>,
        kind: AppGuardKind = AppGuardKind.COMMUNICATION
    ) {
        val appLabel = appContext?.let { AppTierClassifier.appLabel(it, packageName) }.orEmpty()
        val fromActives = actives
            .filter { it.blockInWork && it.kind == ActiveKind.ENTERTAINMENT }
            .mapNotNull { a ->
                val patterns = entertainmentPatternsFrom(
                    a.activity, a.label, appLabel, a.patterns, packageName
                )
                if (patterns.isEmpty()) return@mapNotNull null
                SurfaceRule(
                    id = "active_${a.activity.hashCode()}",
                    label = a.label.ifBlank { a.activity.substringAfterLast('.') },
                    match = SurfaceMatchMode.KEYWORD,
                    patterns = patterns,
                    severity = SurfaceSeverity.BLOCK
                )
            }
        // 按主关键词去重，并补上通讯 App  canonical 入口词
        val byKey = linkedMapOf<String, SurfaceRule>()
        fromActives.forEach { s ->
            val key = s.patterns.sorted().joinToString()
            if (key.isNotBlank()) byKey.putIfAbsent(key, s)
        }
        canonicalCommSurfaces(packageName).forEach { s ->
            val key = s.patterns.sorted().joinToString()
            byKey.putIfAbsent(key, s)
        }
        val blockSurfaces = byKey.values.take(24).toList()
        HabitPolicyStore.mergeAgentSurfaces(
            packageName,
            blockSurfaces,
            reason = if (blockSurfaces.isEmpty()) {
                "通讯应用：未见可安全匹配的娱乐面关键词（仍按 Activity 类名拦截）"
            } else {
                "通讯应用：禁用娱乐功能（${blockSurfaces.size} 个面）"
            },
            kind = kind
        )
    }

    private fun canonicalCommSurfaces(packageName: String): List<SurfaceRule> {
        return when (packageName) {
            "com.tencent.mm" -> listOf(
                SurfaceRule("canon_moments", "朋友圈", SurfaceMatchMode.KEYWORD, listOf("朋友圈", "Moments")),
                SurfaceRule("canon_channels", "视频号", SurfaceMatchMode.KEYWORD, listOf("视频号", "Channels")),
                SurfaceRule("canon_topstory", "看一看", SurfaceMatchMode.KEYWORD, listOf("看一看"))
            )
            "com.tencent.mobileqq" -> listOf(
                SurfaceRule("canon_qzone", "QQ空间", SurfaceMatchMode.KEYWORD, listOf("QQ空间")),
                SurfaceRule("canon_kandian", "看点", SurfaceMatchMode.KEYWORD, listOf("看点", "小世界"))
            )
            else -> emptyList()
        }
    }
}
