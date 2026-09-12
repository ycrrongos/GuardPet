package com.geekathon.guardpet

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.pranav.reef.util.ScreenUsageHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object HabitAgent {
    private const val TAG = "HabitAgent"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun scheduleInitial(context: Context) {
        appContext = context.applicationContext
        if (!HabitPolicyStore.autoRefresh) return
        mainHandler.postDelayed({ refreshAsync(context.applicationContext, reason = "boot") }, 8_000L)
    }

    fun refreshAsync(
        context: Context,
        reason: String,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        val app = context.applicationContext
        appContext = app
        if (!running.compareAndSet(false, true)) {
            onDone?.invoke(false, "分析进行中")
            return
        }
        executor.execute {
            val result = runCatching { refreshBlocking(app, reason) }
            running.set(false)
            val (ok, message) = result.getOrElse { false to (it.message ?: "失败") }
            mainHandler.post { onDone?.invoke(ok, message) }
        }
    }

    private fun refreshBlocking(context: Context, reason: String): Pair<Boolean, String> {
        if (!HabitPolicyStore.agentEnabled) return false to "作息守护已关闭"
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            ensureSeedPolicy()
            return false to "未配置 API Key，已使用本地种子策略"
        }
        val usage = usageTop(context)
        val schedules = runCatching { FlashNoteStore.todaySchedules() }.getOrDefault(emptyList())
        val marks = HabitPolicyStore.loadMarks().take(20)
        val current = HabitPolicyStore.loadPolicy()
        val system = """
你是守伴的手机使用习惯顾问。根据用量与日程，输出健康用机策略 JSON（不要 markdown）。
先把应用分成四类再写规则：
- WORK → mode=ALLOW（工作时不限制）
- ENTERTAINMENT → mode=BLOCK（游戏/短视频/小红书·酷安·微博等刷帖社区）
- COMMUNICATION → mode=SURFACE_FILTER（微信/QQ 等，只禁娱乐面）
- VIDEO → mode=SEARCH_ONLY（B 站等：工作时段仅允许搜索；首页遮挡；播放页按简介判娱乐/学习）
只输出 JSON：
{
  "summary": "一句话中文摘要",
  "packageRules": [
    {
      "packageName": "包名",
      "kind": "WORK|ENTERTAINMENT|COMMUNICATION|VIDEO",
      "mode": "ALLOW|BLOCK|SURFACE_FILTER|SEARCH_ONLY",
      "reason": "原因",
      "surfaces": [
        {
          "id": "id",
          "label": "中文名",
          "match": "KEYWORD|TITLE_HEURISTIC",
          "severity": "BLOCK|WARN",
          "patterns": ["关键词"]
        }
      ]
    }
  ]
}
VIDEO 规则请带娱乐向 TITLE_HEURISTIC 与学习向 WARN 词表；不要对 VIDEO 使用 BLOCK。
WARN + TITLE_HEURISTIC 的 surfaces 可表示学习向放行词。
不要编造不存在的包名；优先使用输入里的包名。用户手动标记优先，你会在合并时被覆盖，无需处理 manual。
        """.trimIndent()
        val user = JSONObject()
            .put("reason", reason)
            .put("sleepStart", HabitPolicyStore.sleepStart.toString())
            .put("wakeTime", HabitPolicyStore.wakeTime.toString())
            .put("todaySchedules", JSONArray().also { arr ->
                schedules.forEach { arr.put(it.text) }
            })
            .put("usageTop", JSONArray().also { arr ->
                usage.forEach { (pkg, ms) ->
                    arr.put(
                        JSONObject()
                            .put("packageName", pkg)
                            .put("minutes", ms / 60_000L)
                            .put("category", HabitGuardian.categorize(context, pkg).name)
                    )
                }
            })
            .put("userMarks", JSONArray().also { arr ->
                marks.forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("packageName", m.packageName)
                            .put("kind", m.kind.name)
                            .put("label", m.label)
                            .put("samples", JSONArray(m.sampleTexts.take(5)))
                    )
                }
            })
            .put("currentSummary", current.summary)
            .toString()

        val llm = HabitLlmClient.chatPolicy(system, user)
        if (!llm.ok || llm.policy == null) {
            Log.w(TAG, "LLM failed: ${llm.error} raw=${llm.raw}")
            ensureSeedPolicy()
            return false to (llm.error ?: "分析失败")
        }
        val merged = HabitPolicyStore.mergeAgentPolicy(llm.policy)
        return true to (merged.summary.ifBlank { "策略已更新" })
    }

    private fun ensureSeedPolicy() {
        val current = HabitPolicyStore.loadPolicy()
        if (current.packageRules.isEmpty()) {
            HabitPolicyStore.savePolicy(
                HabitPolicy(
                    summary = "本地种子策略（微信面过滤 + B站标题）",
                    generatedAt = 0L,
                    packageRules = HabitPolicyStore.seedRules()
                )
            )
        }
    }

    private fun usageTop(context: Context): List<Pair<String, Long>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val end = System.currentTimeMillis()
        val start = LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val usage = runCatching { ScreenUsageHelper.fetchUsageInMs(usm, start, end) }
            .getOrDefault(emptyMap())
        return usage.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { it.key to it.value }
    }
}
