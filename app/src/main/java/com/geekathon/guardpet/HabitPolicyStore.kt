package com.geekathon.guardpet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

enum class PackageRuleMode {
    /** 工作应用：工作时不限制 */
    ALLOW,
    /** 明确娱乐应用：工作时段整包禁 */
    BLOCK,
    /** 通讯等：只禁娱乐面 */
    SURFACE_FILTER,
    /**
     * 视频应用：工作时段仅允许搜索（首页遮挡，播放页按内容判）。
     * 曾短暂一刀切整包禁，归档对照见 `GuardPet/archived/video-content-judge/`。
     */
    SEARCH_ONLY;

    companion object {
        fun fromKey(raw: String?): PackageRuleMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALLOW
    }
}

/** 应用层四类筛选（分析时先定类，再细筛）。 */
enum class AppGuardKind {
    WORK, ENTERTAINMENT, COMMUNICATION, VIDEO;

    companion object {
        fun fromKey(raw: String?): AppGuardKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WORK

        fun fromMode(mode: PackageRuleMode): AppGuardKind = when (mode) {
            PackageRuleMode.ALLOW -> WORK
            PackageRuleMode.BLOCK -> ENTERTAINMENT
            PackageRuleMode.SURFACE_FILTER -> COMMUNICATION
            PackageRuleMode.SEARCH_ONLY -> VIDEO
        }
    }

    fun toMode(): PackageRuleMode = when (this) {
        WORK -> PackageRuleMode.ALLOW
        ENTERTAINMENT -> PackageRuleMode.BLOCK
        COMMUNICATION -> PackageRuleMode.SURFACE_FILTER
        VIDEO -> PackageRuleMode.SEARCH_ONLY
    }
}

enum class SurfaceMatchMode {
    KEYWORD, TITLE_HEURISTIC;

    companion object {
        fun fromKey(raw: String?): SurfaceMatchMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: KEYWORD
    }
}

enum class SurfaceSeverity {
    BLOCK, WARN;

    companion object {
        fun fromKey(raw: String?): SurfaceSeverity =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BLOCK
    }
}

enum class SurfaceMarkKind {
    ENTERTAINMENT, WORK;

    companion object {
        fun fromKey(raw: String?): SurfaceMarkKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ENTERTAINMENT
    }
}

data class SurfaceRule(
    val id: String,
    val label: String,
    val match: SurfaceMatchMode,
    val patterns: List<String>,
    val severity: SurfaceSeverity = SurfaceSeverity.BLOCK
)

data class PackageRule(
    val packageName: String,
    val mode: PackageRuleMode,
    val surfaces: List<SurfaceRule> = emptyList(),
    val reason: String = "",
    val manualOverride: Boolean = false,
    val kind: AppGuardKind = AppGuardKind.fromMode(mode)
)

data class UserSurfaceMark(
    val id: String,
    val packageName: String,
    val kind: SurfaceMarkKind,
    val label: String,
    val sampleTexts: List<String>,
    val createdAt: Long
)

data class HabitPolicy(
    val summary: String = "",
    val generatedAt: Long = 0L,
    val packageRules: List<PackageRule> = emptyList()
)

object HabitPolicyStore {
    private const val PREFS = "habit_policy"
    private const val KEY_POLICY = "policy_json"
    private const val KEY_MARKS = "marks_json"
    private const val KEY_ENABLED = "agent_enabled"
    private const val KEY_BASE_URL = "llm_base_url"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_MODEL = "llm_model"
    private const val KEY_AUTO_REFRESH = "auto_refresh"
    private const val KEY_TEST_MODE = "test_mode"
    private const val KEY_SLEEP_START = "sleep_start_minutes"
    private const val KEY_WAKE = "wake_minutes"
    private const val KEY_WORK_START = "work_start_minutes"
    private const val KEY_WORK_END = "work_end_minutes"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (loadPolicy().packageRules.isEmpty()) {
            savePolicy(HabitPolicy(summary = "内置种子策略", generatedAt = 0L, packageRules = seedRules()))
        }
    }

    fun appContextOrNull(): Context? = appContext

    private fun prefs() =
        requireNotNull(appContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var agentEnabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, true)
        set(value) = prefs().edit().putBoolean(KEY_ENABLED, value).apply()

    var llmBaseUrl: String
        get() = prefs().getString(KEY_BASE_URL, "https://api.deepseek.com/v1")
            ?.trim()
            .orEmpty()
            .ifBlank { "https://api.deepseek.com/v1" }
        set(value) = prefs().edit().putString(KEY_BASE_URL, value.trim()).apply()

    var llmApiKey: String
        get() = prefs().getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs().edit().putString(KEY_API_KEY, value.trim()).apply()

    var llmModel: String
        get() = prefs().getString(KEY_MODEL, "deepseek-chat")
            ?.trim()
            .orEmpty()
            .ifBlank { "deepseek-chat" }
        set(value) = prefs().edit().putString(KEY_MODEL, value.trim()).apply()

    var autoRefresh: Boolean
        get() = prefs().getBoolean(KEY_AUTO_REFRESH, true)
        set(value) = prefs().edit().putBoolean(KEY_AUTO_REFRESH, value).apply()

    var testMode: Boolean
        get() = prefs().getBoolean(KEY_TEST_MODE, false)
        set(value) = prefs().edit().putBoolean(KEY_TEST_MODE, value).apply()

    var sleepStartMinutes: Int
        get() = prefs().getInt(KEY_SLEEP_START, 23 * 60)
        set(value) = prefs().edit().putInt(KEY_SLEEP_START, value.coerceIn(0, 24 * 60 - 1)).apply()

    var wakeMinutes: Int
        get() = prefs().getInt(KEY_WAKE, 7 * 60)
        set(value) = prefs().edit().putInt(KEY_WAKE, value.coerceIn(0, 24 * 60 - 1)).apply()

    /** 工作时段默认 09:00–18:00；跨午夜时 end < start 视为跨天。 */
    var workStartMinutes: Int
        get() = prefs().getInt(KEY_WORK_START, 9 * 60)
        set(value) = prefs().edit().putInt(KEY_WORK_START, value.coerceIn(0, 24 * 60 - 1)).apply()

    var workEndMinutes: Int
        get() = prefs().getInt(KEY_WORK_END, 18 * 60)
        set(value) = prefs().edit().putInt(KEY_WORK_END, value.coerceIn(0, 24 * 60 - 1)).apply()

    val sleepStart: LocalTime
        get() = LocalTime.of(sleepStartMinutes / 60, sleepStartMinutes % 60)

    val wakeTime: LocalTime
        get() = LocalTime.of(wakeMinutes / 60, wakeMinutes % 60)

    fun isWorkTime(now: LocalTime = LocalTime.now()): Boolean {
        val mins = now.hour * 60 + now.minute
        val start = workStartMinutes
        val end = workEndMinutes
        return if (start <= end) {
            mins in start until end
        } else {
            mins >= start || mins < end
        }
    }

    fun loadPolicy(): HabitPolicy {
        val raw = prefs().getString(KEY_POLICY, null) ?: return HabitPolicy()
        val parsed = runCatching { parsePolicy(JSONObject(raw)) }.getOrDefault(HabitPolicy())
        return sanitizePolicy(parsed)
    }

    fun savePolicy(policy: HabitPolicy) {
        prefs().edit().putString(KEY_POLICY, policyToJson(sanitizePolicy(policy)).toString()).apply()
    }

    /**
     * 读/写时清洗：去掉桌面误入规则，并把微信/B站等强制回正确 kind。
     * 曾被 iTunes 把 launcher3/微信标成 ENTERTAINMENT+BLOCK。
     */
    private fun sanitizePolicy(policy: HabitPolicy): HabitPolicy {
        val cleaned = policy.packageRules
            .filterNot { shouldNeverStoreRule(it.packageName) }
            .map { coerceKnownPackageRule(it) }
        if (cleaned.size == policy.packageRules.size &&
            cleaned.zip(policy.packageRules).all { (a, b) -> a == b }
        ) {
            return policy
        }
        val out = policy.copy(packageRules = cleaned)
        // 持久化纠正，避免下次仍读到脏 JSON（不递归：直接写 prefs）
        prefs().edit().putString(KEY_POLICY, policyToJson(out).toString()).apply()
        return out
    }

    private fun shouldNeverStoreRule(packageName: String): Boolean {
        val p = packageName.lowercase()
        val self = appContext?.packageName?.lowercase()
        return "launcher" in p ||
            (self != null && p == self) ||
            p.contains("sensevoice")
    }

    fun ruleFor(packageName: String): PackageRule? =
        loadPolicy().packageRules.firstOrNull { it.packageName == packageName }

    fun watchedPackages(): Set<String> =
        loadPolicy().packageRules
            .filter {
                it.mode == PackageRuleMode.SURFACE_FILTER ||
                    it.mode == PackageRuleMode.SEARCH_ONLY
            }
            .map { it.packageName }
            .toSet()

    fun removeRule(packageName: String) {
        val current = loadPolicy()
        val next = current.packageRules.filterNot { it.packageName == packageName }
        savePolicy(current.copy(packageRules = next))
    }

    fun upsertManualRule(rule: PackageRule) {
        upsertRule(rule.copy(manualOverride = true))
    }

    fun upsertRule(rule: PackageRule) {
        val current = loadPolicy()
        val next = current.packageRules
            .filterNot { it.packageName == rule.packageName }
            .toMutableList()
        next.add(rule)
        savePolicy(current.copy(packageRules = next))
    }

    /** 用分析结果写入包规则（不覆盖手动规则）。 */
    fun mergeAgentSurfaces(
        packageName: String,
        surfaces: List<SurfaceRule>,
        reason: String,
        kind: AppGuardKind = AppGuardKind.COMMUNICATION
    ) {
        val existing = ruleFor(packageName)
        if (existing?.manualOverride == true) return
        upsertRule(
            PackageRule(
                packageName = packageName,
                mode = kind.toMode(),
                surfaces = surfaces,
                reason = reason,
                manualOverride = false,
                kind = kind
            )
        )
    }

    fun upsertAgentKind(
        packageName: String,
        kind: AppGuardKind,
        reason: String,
        surfaces: List<SurfaceRule> = emptyList(),
        clearSurfaces: Boolean = false
    ) {
        val existing = ruleFor(packageName)
        if (existing?.manualOverride == true) return
        if (shouldNeverStoreRule(packageName) ||
            (AppStoreMetaFetcher.shouldSkipStoreLookup(packageName) &&
                packageName.startsWith("com.android."))
        ) {
            removeRule(packageName)
            return
        }
        val coerced = coerceKnownPackageRule(
            PackageRule(
                packageName = packageName,
                mode = kind.toMode(),
                surfaces = surfaces,
                reason = reason,
                manualOverride = false,
                kind = kind
            )
        )
        val nextSurfaces = when {
            clearSurfaces && coerced.kind != AppGuardKind.COMMUNICATION &&
                coerced.kind != AppGuardKind.VIDEO -> emptyList()
            coerced.surfaces.isNotEmpty() -> coerced.surfaces
            surfaces.isNotEmpty() -> surfaces
            coerced.kind == AppGuardKind.WORK || coerced.kind == AppGuardKind.ENTERTAINMENT -> emptyList()
            else -> existing?.surfaces.orEmpty()
        }
        upsertRule(
            coerced.copy(
                surfaces = nextSurfaces,
                mode = coerced.kind.toMode()
            )
        )
    }

    fun loadMarks(): List<UserSurfaceMark> {
        val raw = prefs().getString(KEY_MARKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(parseMark(arr.getJSONObject(i)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addMark(mark: UserSurfaceMark) {
        val marks = loadMarks().toMutableList()
        marks.add(0, mark)
        while (marks.size > 80) marks.removeAt(marks.lastIndex)
        val arr = JSONArray()
        marks.forEach { arr.put(markToJson(it)) }
        prefs().edit().putString(KEY_MARKS, arr.toString()).apply()
        applyMarkToRule(mark)
    }

    fun mergeAgentPolicy(agent: HabitPolicy): HabitPolicy {
        val existing = loadPolicy()
        val manuals = existing.packageRules.filter { it.manualOverride }
            .associateBy { it.packageName }
        val merged = agent.packageRules
            .filter { it.packageName !in manuals }
            .map { coerceKnownPackageRule(it) }
            .map { it.copy(manualOverride = false) }
            .toMutableList()
        merged.addAll(manuals.values)
        val out = HabitPolicy(
            summary = agent.summary.ifBlank { existing.summary },
            generatedAt = System.currentTimeMillis(),
            packageRules = merged.ifEmpty { seedRules() }
        )
        savePolicy(out)
        return out
    }

    /** 防止 LLM「重新分析」把微信/B站/美团等误判。 */
    private fun coerceKnownPackageRule(rule: PackageRule): PackageRule {
        AppTierClassifier.knownKind(rule.packageName)?.let { known ->
            return when (known) {
                AppGuardKind.COMMUNICATION -> rule.copy(
                    kind = AppGuardKind.COMMUNICATION,
                    mode = PackageRuleMode.SURFACE_FILTER,
                    surfaces = rule.surfaces.ifEmpty {
                        seedRules().firstOrNull { it.packageName == rule.packageName }?.surfaces.orEmpty()
                    }
                )
                AppGuardKind.VIDEO -> rule.copy(
                    kind = AppGuardKind.VIDEO,
                    mode = PackageRuleMode.SEARCH_ONLY,
                    surfaces = rule.surfaces.ifEmpty {
                        defaultVideoSurfaces(rule.packageName.substringAfterLast('.').take(12))
                    }
                )
                AppGuardKind.ENTERTAINMENT -> rule.copy(
                    kind = AppGuardKind.ENTERTAINMENT,
                    mode = PackageRuleMode.BLOCK,
                    surfaces = emptyList()
                )
                AppGuardKind.WORK -> rule.copy(
                    kind = AppGuardKind.WORK,
                    mode = PackageRuleMode.ALLOW,
                    surfaces = emptyList()
                )
            }
        }
        return when (rule.packageName) {
            "com.tencent.mm", "com.tencent.mobileqq" -> rule.copy(
                kind = AppGuardKind.COMMUNICATION,
                mode = PackageRuleMode.SURFACE_FILTER,
                surfaces = rule.surfaces.ifEmpty {
                    seedRules().firstOrNull { it.packageName == rule.packageName }?.surfaces.orEmpty()
                }
            )
            "tv.danmaku.bili", "com.bilibili.app.in" -> rule.copy(
                kind = AppGuardKind.VIDEO,
                mode = PackageRuleMode.SEARCH_ONLY,
                surfaces = rule.surfaces.ifEmpty { defaultVideoSurfaces("bili") }
            )
            else -> when (rule.kind) {
                AppGuardKind.VIDEO -> rule.copy(
                    mode = PackageRuleMode.SEARCH_ONLY,
                    surfaces = rule.surfaces.ifEmpty {
                        defaultVideoSurfaces(rule.packageName.substringAfterLast('.').take(12))
                    }
                )
                else -> rule
            }
        }
    }

    private fun applyMarkToRule(mark: UserSurfaceMark) {
        val patterns = mark.sampleTexts
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(8)
        if (patterns.isEmpty()) return
        val current = ruleFor(mark.packageName)
        val surface = SurfaceRule(
            id = "mark_${mark.id}",
            label = mark.label.ifBlank {
                if (mark.kind == SurfaceMarkKind.ENTERTAINMENT) "手动娱乐面" else "手动工作面"
            },
            match = SurfaceMatchMode.KEYWORD,
            patterns = patterns,
            severity = if (mark.kind == SurfaceMarkKind.ENTERTAINMENT) {
                SurfaceSeverity.BLOCK
            } else {
                SurfaceSeverity.WARN
            }
        )
        val surfaces = (current?.surfaces.orEmpty())
            .filterNot { it.id == surface.id }
            .toMutableList()
        if (mark.kind == SurfaceMarkKind.ENTERTAINMENT) {
            surfaces.add(surface)
        }
        val mode = when {
            mark.kind == SurfaceMarkKind.WORK && current?.mode == PackageRuleMode.BLOCK ->
                PackageRuleMode.SURFACE_FILTER
            current?.mode == PackageRuleMode.ALLOW && mark.kind == SurfaceMarkKind.ENTERTAINMENT ->
                PackageRuleMode.SURFACE_FILTER
            current != null -> current.mode
            mark.kind == SurfaceMarkKind.ENTERTAINMENT -> PackageRuleMode.SURFACE_FILTER
            else -> PackageRuleMode.ALLOW
        }
        upsertManualRule(
            PackageRule(
                packageName = mark.packageName,
                mode = mode,
                surfaces = surfaces,
                reason = "用户标记",
                manualOverride = true,
                kind = AppGuardKind.fromMode(mode)
            )
        )
    }

    fun seedRules(): List<PackageRule> = listOf(
        PackageRule(
            packageName = "com.tencent.mm",
            mode = PackageRuleMode.SURFACE_FILTER,
            kind = AppGuardKind.COMMUNICATION,
            reason = "通讯：工作可用，拦朋友圈/视频号",
            surfaces = listOf(
                SurfaceRule(
                    id = "wechat_moments",
                    label = "朋友圈",
                    match = SurfaceMatchMode.KEYWORD,
                    patterns = listOf("朋友圈", "Moments")
                ),
                SurfaceRule(
                    id = "wechat_channels",
                    label = "视频号",
                    match = SurfaceMatchMode.KEYWORD,
                    patterns = listOf("视频号", "Channels")
                )
            )
        ),
        PackageRule(
            packageName = "com.tencent.mobileqq",
            mode = PackageRuleMode.SURFACE_FILTER,
            kind = AppGuardKind.COMMUNICATION,
            reason = "通讯：拦看点/小世界等娱乐面",
            surfaces = listOf(
                SurfaceRule(
                    id = "qq_kan_dian",
                    label = "看点/小世界",
                    match = SurfaceMatchMode.KEYWORD,
                    patterns = listOf("看点", "小世界")
                )
            )
        ),
        PackageRule(
            packageName = "tv.danmaku.bili",
            mode = PackageRuleMode.SEARCH_ONLY,
            kind = AppGuardKind.VIDEO,
            reason = "视频：工作时段仅允许搜索，娱乐内容拦截",
            surfaces = defaultVideoSurfaces("bili")
        ),
        PackageRule(
            packageName = "com.bilibili.app.in",
            mode = PackageRuleMode.SEARCH_ONLY,
            kind = AppGuardKind.VIDEO,
            reason = "视频：工作时段仅允许搜索，娱乐内容拦截",
            surfaces = defaultVideoSurfaces("bili_in")
        )
    )

    fun defaultVideoSurfaces(prefix: String): List<SurfaceRule> = listOf(
        SurfaceRule(
            id = "${prefix}_entertainment",
            label = "娱乐向内容",
            match = SurfaceMatchMode.TITLE_HEURISTIC,
            patterns = listOf(
                "搞笑", "整活", "鬼畜", "娱乐", "八卦", "明星", "综艺",
                "游戏实况", "吃播", "颜值", "舞蹈", "番剧", "直播回放",
                "电影", "电视剧", "追剧", "爽剧", "漫画"
            )
        ),
        SurfaceRule(
            id = "${prefix}_study_allow",
            label = "学习向放行词",
            match = SurfaceMatchMode.TITLE_HEURISTIC,
            patterns = listOf(
                "教程", "课程", "学习", "考研", "高考", "纪录片", "公开课",
                "编程", "数学", "英语", "论文", "讲座", "新闻", "科普",
                "知识", "技能", "考试", "四六级", "面试", "职场",
                "剪辑", "攻略", "教学", "开源", "实用向"
            ),
            severity = SurfaceSeverity.WARN
        )
    )

    private fun policyToJson(policy: HabitPolicy): JSONObject = JSONObject()
        .put("summary", policy.summary)
        .put("generatedAt", policy.generatedAt)
        .put("packageRules", JSONArray().also { arr ->
            policy.packageRules.forEach { rule ->
                arr.put(
                    JSONObject()
                        .put("packageName", rule.packageName)
                        .put("mode", rule.mode.name)
                        .put("kind", rule.kind.name)
                        .put("reason", rule.reason)
                        .put("manualOverride", rule.manualOverride)
                        .put("surfaces", JSONArray().also { sArr ->
                            rule.surfaces.forEach { s ->
                                sArr.put(
                                    JSONObject()
                                        .put("id", s.id)
                                        .put("label", s.label)
                                        .put("match", s.match.name)
                                        .put("severity", s.severity.name)
                                        .put("patterns", JSONArray(s.patterns))
                                )
                            }
                        })
                )
            }
        })

    private fun parsePolicy(obj: JSONObject): HabitPolicy {
        val rulesArr = obj.optJSONArray("packageRules") ?: JSONArray()
        val rules = buildList {
            for (i in 0 until rulesArr.length()) {
                val r = rulesArr.getJSONObject(i)
                val surfacesArr = r.optJSONArray("surfaces") ?: JSONArray()
                val surfaces = buildList {
                    for (j in 0 until surfacesArr.length()) {
                        val s = surfacesArr.getJSONObject(j)
                        val patternsArr = s.optJSONArray("patterns") ?: JSONArray()
                        val patterns = buildList {
                            for (k in 0 until patternsArr.length()) {
                                add(patternsArr.getString(k))
                            }
                        }
                        add(
                            SurfaceRule(
                                id = s.optString("id"),
                                label = s.optString("label"),
                                match = SurfaceMatchMode.fromKey(s.optString("match")),
                                patterns = patterns,
                                severity = SurfaceSeverity.fromKey(s.optString("severity"))
                            )
                        )
                    }
                }
                val modeRaw = PackageRuleMode.fromKey(r.optString("mode"))
                val kind = if (r.has("kind")) {
                    AppGuardKind.fromKey(r.optString("kind"))
                } else {
                    AppGuardKind.fromMode(modeRaw)
                }
                val mode = if (kind == AppGuardKind.VIDEO) PackageRuleMode.SEARCH_ONLY else modeRaw
                val nextSurfaces = if (kind == AppGuardKind.VIDEO && surfaces.isEmpty()) {
                    defaultVideoSurfaces(r.optString("packageName").substringAfterLast('.').take(12))
                } else {
                    surfaces
                }
                add(
                    PackageRule(
                        packageName = r.optString("packageName"),
                        mode = mode,
                        surfaces = nextSurfaces,
                        reason = r.optString("reason"),
                        manualOverride = r.optBoolean("manualOverride", false),
                        kind = kind
                    )
                )
            }
        }
        return HabitPolicy(
            summary = obj.optString("summary"),
            generatedAt = obj.optLong("generatedAt"),
            packageRules = rules
        )
    }

    private fun markToJson(mark: UserSurfaceMark): JSONObject = JSONObject()
        .put("id", mark.id)
        .put("packageName", mark.packageName)
        .put("kind", mark.kind.name)
        .put("label", mark.label)
        .put("createdAt", mark.createdAt)
        .put("sampleTexts", JSONArray(mark.sampleTexts))

    private fun parseMark(obj: JSONObject): UserSurfaceMark {
        val samplesArr = obj.optJSONArray("sampleTexts") ?: JSONArray()
        val samples = buildList {
            for (i in 0 until samplesArr.length()) add(samplesArr.getString(i))
        }
        return UserSurfaceMark(
            id = obj.optString("id"),
            packageName = obj.optString("packageName"),
            kind = SurfaceMarkKind.fromKey(obj.optString("kind")),
            label = obj.optString("label"),
            sampleTexts = samples,
            createdAt = obj.optLong("createdAt")
        )
    }
}
