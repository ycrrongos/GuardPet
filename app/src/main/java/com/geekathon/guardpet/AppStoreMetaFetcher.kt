package com.geekathon.guardpet

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 联网拉取应用商店公开分类（分析时用一次）。
 * 国内环境 Google Play 几乎总是超时，默认跳过 Play，只查 iTunes Search；再失败走本地/AI。
 * （与大爆炸文字提取 / HabitLlmClient 无关。）
 */
object AppStoreMetaFetcher {
    private const val TAG = "AppStoreMeta"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Meta>()
    /** Play 在国内常 8s×N 拖死「重新分析」；默认关闭。 */
    private const val TRY_GOOGLE_PLAY = false
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 3_000

    data class Meta(
        val packageName: String,
        val label: String,
        /** 如 LIFESTYLE、美食佳饮、生活 */
        val categories: List<String>,
        val source: String
    ) {
        val summary: String
            get() = if (categories.isEmpty()) "无商店分类"
            else "${source}: ${categories.joinToString("/")}"
    }

    fun fetchMany(apps: List<Pair<String, String>>): Map<String, Meta> {
        if (apps.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(6.coerceAtMost(apps.size))
        return try {
            val futures = apps.map { (pkg, label) ->
                pool.submit(Callable {
                    pkg to (cache[pkg] ?: fetchOne(pkg, label).also { cache[pkg] = it })
                })
            }
            futures.mapNotNull { f ->
                runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull()
            }.toMap()
        } finally {
            pool.shutdownNow()
        }
    }

    fun fetchOne(packageName: String, label: String): Meta {
        if (shouldSkipStoreLookup(packageName)) {
            return Meta(packageName, label, emptyList(), "skip")
        }
        if (TRY_GOOGLE_PLAY) {
            fetchPlay(packageName, label)?.let { return it }
        }
        fetchItunes(packageName, label)?.let { return it }
        return Meta(packageName, label, emptyList(), "none")
    }

    /** 桌面/系统组件不要去 iTunes 按名字瞎匹配（曾把 launcher3 配成游戏）。 */
    fun shouldSkipStoreLookup(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p.startsWith("com.android.") ||
            p.startsWith("com.google.android.") ||
            p.startsWith("org.lineageos.") ||
            p.startsWith("com.qualcomm.") ||
            "launcher" in p ||
            p in setOf(
                "com.smartisanos.launcher",
                "com.miui.home",
                "com.huawei.android.launcher",
                "com.oppo.launcher",
                "com.bbk.launcher2"
            )
    }

    private fun fetchPlay(packageName: String, label: String): Meta? {
        return runCatching {
            val url =
                "https://play.google.com/store/apps/details?id=${URLEncoder.encode(packageName, "UTF-8")}&hl=zh-CN"
            val html = httpGet(url) ?: return@runCatching null
            val cats = LinkedHashSet<String>()
            Regex("\"applicationCategory\":\"([^\"]+)\"").findAll(html).forEach {
                cats.add(it.groupValues[1])
            }
            Regex("itemprop=\"genre\"[^>]*content=\"([^\"]+)\"").findAll(html).forEach {
                cats.add(it.groupValues[1])
            }
            Regex("itemprop=\"genre\">([^<]+)<").findAll(html).forEach {
                cats.add(it.groupValues[1].trim())
            }
            if (cats.isEmpty()) null
            else Meta(packageName, label, cats.toList(), "play")
        }.onFailure { Log.w(TAG, "play $packageName: ${it.message}") }.getOrNull()
    }

    private fun fetchItunes(packageName: String, label: String): Meta? {
        if (label.isBlank()) return null
        return runCatching {
            val q = URLEncoder.encode(label.take(24), "UTF-8")
            val url = "https://itunes.apple.com/search?term=$q&country=cn&entity=software&limit=5"
            val text = httpGet(url) ?: return@runCatching null
            val results = JSONObject(text).optJSONArray("results") ?: return@runCatching null
            if (results.length() == 0) return@runCatching null
            // 优先名字最接近的一条
            var bestIdx = 0
            var bestScore = -1
            for (i in 0 until results.length()) {
                val name = results.getJSONObject(i).optString("trackName")
                val score = nameSimilarity(label, name)
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            if (bestScore < 4) return@runCatching null
            val o = results.getJSONObject(bestIdx)
            val cats = linkedSetOf<String>()
            o.optString("primaryGenreName").takeIf { it.isNotBlank() }?.let { cats.add(it) }
            val genres = o.optJSONArray("genres")
            if (genres != null) {
                for (i in 0 until genres.length()) {
                    genres.optString(i).takeIf { it.isNotBlank() }?.let { cats.add(it) }
                }
            }
            if (cats.isEmpty()) null
            else Meta(packageName, label, cats.toList(), "itunes")
        }.onFailure { Log.w(TAG, "itunes $label: ${it.message}") }.getOrNull()
    }

    private fun nameSimilarity(a: String, b: String): Int {
        val x = a.lowercase().filter { !it.isWhitespace() }
        val y = b.lowercase().filter { !it.isWhitespace() }
        if (x.isEmpty() || y.isEmpty()) return 0
        if (x == y || y.startsWith(x) || x.startsWith(y.take(x.length))) return 10
        if (y.contains(x.take(2.coerceAtMost(x.length)))) return 4
        return 1
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else return null
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 把商店分类映射到守伴四类。
 * 生活/购物/美食等「消遣」→ ENTERTAINMENT（美团 LIFESTYLE 即此）；办公工具 → WORK。
 */
object StoreCategoryMapper {
    fun map(categories: List<String>): AppGuardKind? {
        if (categories.isEmpty()) return null
        val blob = categories.joinToString(" ").lowercase()

        if (listOf("game", "游戏", "arcade", "action", "casino", "racing").any { it in blob }) {
            return AppGuardKind.ENTERTAINMENT
        }
        if (listOf(
                "video_players", "video players", "影视", "电影与电视", "流媒体"
            ).any { it in blob }
        ) {
            return AppGuardKind.VIDEO
        }
        // 即时通讯 / 社交网络（微信 iTunes: Social Networking）→ 通讯，不要当娱乐整包禁
        if (listOf(
                "social networking", "communication", "通讯", "即时通讯", "messaging", "社交网络"
            ).any { it in blob }
        ) {
            return AppGuardKind.COMMUNICATION
        }
        // 生活/购物/美食/约会等：工作时属消遣（如美团 LIFESTYLE）
        // 注意：不要用裸 "social"，会误伤 Social Networking
        if (listOf(
                "lifestyle", "shopping", "food", "drink", "travel", "dating",
                "comics", "sports", "music", "news", "magazine", "photography", "entertainment",
                "生活", "购物", "美食", "旅游", "约会", "体育", "音乐", "新闻", "漫画", "娱乐"
            ).any { it in blob }
        ) {
            return AppGuardKind.ENTERTAINMENT
        }
        if (listOf(
                "productivity", "business", "tools", "education", "finance", "medical",
                "maps", "navigation", "weather", "utilities", "reference",
                "效率", "商务", "工具", "教育", "财务", "医疗", "导航", "天气", "图书"
            ).any { it in blob }
        ) {
            return AppGuardKind.WORK
        }
        return null
    }
}
