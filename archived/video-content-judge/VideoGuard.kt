package com.geekathon.guardpet

import android.os.SystemClock
import dev.pranav.reef.accessibility.A11yNodeSnap
import dev.pranav.reef.accessibility.BlockerService

enum class VideoContentVerdict {
    /** 学习/工作向，放行 */
    STUDY,
    /** 娱乐向，应返回 */
    ENTERTAINMENT,
    /** 已点展开，等下一帧再判 */
    PENDING_EXPAND,
    /** 信息不足 */
    UNKNOWN
}

data class VideoPageMeta(
    val title: String,
    val description: String,
    val tags: List<String>,
    val blob: String
)

/**
 * 视频类应用：工作时段仅允许搜索页；其它页返回或遮挡；
 * 播放页按 resource-id / contentDescription 展开简介，再用标题+简介+标签判断。
 *
 * B 站真机结构（UnitedBizDetailsActivity）：
 * - 展开：可点父节点 contentDesc 以「，展开」结尾；子节点 id/title + id/arrow
 * - 简介：展开后 id/desc（BV + 正文 + 末尾空格分隔标签）
 * - 勿把 id/time（日期时间）、id/duration、相关推荐 title 当简介
 */
object VideoGuard {
    private val searchActivityHints = listOf(
        "search", "sousuo", "find", "query"
    )
    private val searchTextExact = setOf("搜索", "Search", "搜一搜", "取消")
    private val searchHintFragments = listOf(
        "搜索", "Search", "搜你想看", "输入关键词", "热搜", "猜你想搜"
    )
    private val homeTabHints = listOf("首页", "推荐", "动态", "我的", "频道", "直播", "精选")
    private val playerHints = listOf(
        "player", "video", "detail", "bangumi", "ugc", "playback", "watch", "theseus"
    )
    private val navNoise = setOf(
        "首页", "推荐", "热门", "动态", "我的", "搜索", "消息", "弹幕", "投币", "三连",
        "分享", "收藏", "点赞", "充电", "粉丝", "关注", "直播", "影视", "漫画",
        "简介", "评论", "视频", "展开", "收起", "更多", "相关推荐", "选集", "不喜欢",
        "点我发弹幕"
    )
    private val progressOrStatNoise = Regex(
        pattern = buildString {
            append("^(")
            append("\\d{1,2}:\\d{2}(:\\d{2})?") // 进度/时长 3:11
            append("|共\\d+集\\s*\\d{1,2}:\\d{2}")
            append("|\\d+(\\.\\d+)?[万亿]?播放")
            append("|\\d+(\\.\\d+)?万")
            append("|\\d+条弹幕")
            append("|\\d+人正在看")
            append("|\\d{4}年\\d{1,2}月\\d{1,2}日(\\s+\\d{1,2}:\\d{2})?")
            append("|·\\s*\\d{4}年.*")
            append("|BV[0-9A-Za-z]+")
            append(")$")
        }
    )

    private var lastPageKey: String? = null
    private var lastExpandAtMs = 0L
    private var expandAttemptedKey: String? = null
    private var expandSettledKey: String? = null

    fun isSearchSurface(activityClass: String?, texts: List<String>): Boolean {
        val act = activityClass.orEmpty().lowercase()
        if (searchActivityHints.any { it in act } &&
            !playerHints.any { it in act }
        ) {
            return true
        }
        val nodes = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val set = nodes.toHashSet()
        if (searchTextExact.any { it in set } &&
            nodes.any { n -> searchHintFragments.any { h -> n.contains(h) } }
        ) {
            return true
        }
        val top = nodes.take(10)
        val hasSearchChrome = top.any { it == "搜索" || it.equals("Search", true) || it == "取消" }
        val looksLikePlayer = isPlayerOrDetail(activityClass, texts)
        return hasSearchChrome && !looksLikePlayer
    }

    fun isPlayerOrDetail(activityClass: String?, texts: List<String>): Boolean {
        val act = activityClass.orEmpty().lowercase()
        if (playerHints.any { it in act }) return true
        val set = texts.map { it.trim() }.toHashSet()
        return listOf("弹幕", "投币", "三连", "选集", "简介", "相关推荐").count { it in set } >= 2
    }

    fun preferCover(texts: List<String>): Boolean {
        val set = texts.map { it.trim() }.toHashSet()
        return homeTabHints.count { it in set } >= 2
    }

    fun entertainmentHit(
        texts: List<String>,
        surfaces: List<SurfaceRule>,
        packageName: String
    ): SurfaceHit? = SurfaceMatcher.match(texts, surfaces, packageName)

    /**
     * 播放页：按 B 站真实控件展开简介，再用标题/简介/标签判定。
     */
    fun judgePlayerContent(
        packageName: String,
        activityClass: String?,
        texts: List<String>,
        surfaces: List<SurfaceRule>
    ): Pair<VideoContentVerdict, String> {
        val pageKey = "$packageName:${activityClass.orEmpty()}"
        if (pageKey != lastPageKey) {
            lastPageKey = pageKey
            expandAttemptedKey = null
            expandSettledKey = null
        }

        val snaps = BlockerService.captureVisibleNodes().ifEmpty {
            textsToPseudoSnaps(texts)
        }
        val now = SystemClock.elapsedRealtime()
        val introReady = looksIntroExpanded(snaps)
        if (!introReady && expandSettledKey != pageKey) {
            if (expandAttemptedKey != pageKey || now - lastExpandAtMs > 3_500L) {
                tryExpandIntro()
                expandAttemptedKey = pageKey
                lastExpandAtMs = now
                return VideoContentVerdict.PENDING_EXPAND to "展开简介中"
            }
            if (now - lastExpandAtMs < 1_200L) {
                return VideoContentVerdict.PENDING_EXPAND to "等待简介"
            }
            expandSettledKey = pageKey
        } else {
            expandSettledKey = pageKey
        }

        val meta = collectMeta(snaps)
        val verdict = classifyMeta(meta, surfaces)
        val hint = buildString {
            if (meta.title.isNotBlank()) append(meta.title.take(20))
            else append("视频")
        }
        return verdict to hint
    }

    private fun tryExpandIntro() {
        // B 站：标题行 contentDesc =「互动视频，{标题}，展开」；点父节点或 id/arrow
        val byDesc = BlockerService.clickByContentDescSuffix("，展开") ||
            BlockerService.clickByContentDescSuffix(",展开")
        if (byDesc) return
        if (BlockerService.clickByViewIdSuffix("arrow")) return
        // 其它 App 文案兜底（精确匹配，避免点到进度条）
        BlockerService.clickVisibleText("简介")
        BlockerService.clickVisibleText(
            "展开完整简介",
            "展开简介",
            "展开更多",
            "查看全部"
        )
    }

    fun looksIntroExpanded(snaps: List<A11yNodeSnap>): Boolean {
        if (snaps.any { it.contentDescription.endsWith("，收起") || it.contentDescription.endsWith(",收起") }) {
            return true
        }
        val desc = snaps.firstOrNull { it.idSuffix == "desc" && it.text.length >= 8 }?.text
        return !desc.isNullOrBlank()
    }

    fun collectMeta(snaps: List<A11yNodeSnap>): VideoPageMeta {
        val title = resolveTitle(snaps)
        val rawDesc = snaps.firstOrNull { it.idSuffix == "desc" && it.text.isNotBlank() }?.text
            .orEmpty()
        val (description, tagsFromDesc) = splitDescAndTags(rawDesc)
        val chipTags = snaps.asSequence()
            .filter { it.idSuffix == "tag_text" || it.idSuffix == "tag" }
            .map { it.text.trim().removePrefix("#") }
            .filter { it.length in 2..16 }
            .filter { it !in navNoise }
            .filter { !isProgressOrStat(it) }
            .toList()
        val tags = (tagsFromDesc + chipTags).distinct().take(16)

        val blob = listOf(title, description).filter { it.isNotBlank() }
            .plus(tags)
            .joinToString("\n")
        return VideoPageMeta(title, description, tags, blob)
    }
    private fun resolveTitle(snaps: List<A11yNodeSnap>): String {
        // 展开行 contentDesc：「互动视频，标题，展开/收起」
        val fromExpand = snaps.asSequence()
            .map { it.contentDescription }
            .firstOrNull { it.endsWith("，展开") || it.endsWith("，收起") || it.endsWith(",展开") || it.endsWith(",收起") }
            ?.let { parseTitleFromExpandDesc(it) }
            .orEmpty()
        if (fromExpand.isNotBlank()) return fromExpand

        // 简介区第一个 id/title：出现在 id/desc 或 arrow 之前的 title
        val descIdx = snaps.indexOfFirst { it.idSuffix == "desc" }
        val arrowIdx = snaps.indexOfFirst { it.idSuffix == "arrow" }
        val cutoff = listOf(descIdx, arrowIdx).filter { it >= 0 }.minOrNull() ?: snaps.size
        val introTitle = snaps.take(cutoff.coerceAtLeast(0) + 1)
            .asSequence()
            .filter { it.idSuffix == "title" }
            .map { it.text.trim() }
            .firstOrNull { it.length in 4..120 && !isProgressOrStat(it) && it !in navNoise }
            .orEmpty()
        if (introTitle.isNotBlank()) return introTitle

        // 弱兜底：过滤进度/统计后的第一条中等长度文本
        return snaps.asSequence()
            .map { it.text.trim() }
            .filter { it.length in 8..80 }
            .filter { it !in navNoise }
            .filter { !isProgressOrStat(it) }
            .filter { !it.contains("万播放") && !it.contains("弹幕") }
            .firstOrNull()
            .orEmpty()
    }

    private fun parseTitleFromExpandDesc(desc: String): String {
        // 「互动视频，标题，展开」或「标题，展开」
        val body = desc
            .removeSuffix("，展开").removeSuffix("，收起")
            .removeSuffix(",展开").removeSuffix(",收起")
        val parts = body.split('，', ',').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 && parts.first() in setOf("互动视频", "视频", "直播") ->
                parts.drop(1).joinToString("，")
            parts.isNotEmpty() -> parts.last()
            else -> body.trim()
        }
    }

    /**
     * B 站 id/desc：前几行正文，末几行多为空格分隔标签。
     */
    private fun splitDescAndTags(raw: String): Pair<String, List<String>> {
        if (raw.isBlank()) return "" to emptyList()
        val lines = raw.replace("\r", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "" to emptyList()

        val tagLines = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()
        for (line in lines.asReversed()) {
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val looksLikeTags = tokens.size >= 2 &&
                tokens.all { it.length in 2..12 && !it.startsWith("http") && !it.startsWith("BV") } &&
                tokens.none { it.contains("授权") || it.contains("禁止") }
            if (looksLikeTags && bodyLines.isEmpty()) {
                tagLines.add(0, line)
            } else {
                bodyLines.add(0, line)
            }
        }
        val tags = tagLines
            .flatMap { it.split(Regex("\\s+")) }
            .map { it.removePrefix("#").trim() }
            .filter { it.length in 2..12 }
            .filter { it !in navNoise }
            .filter { !isProgressOrStat(it) }
            .distinct()
        val description = bodyLines
            .filterNot { it.matches(Regex("^BV[0-9A-Za-z]+.*")) }
            .filterNot { it.contains("未经作者授权") }
            .joinToString("\n")
            .trim()
        return description to tags
    }

    private fun isProgressOrStat(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (progressOrStatNoise.matches(t)) return true
        // 纯数字 / 播放量样式
        if (t.matches(Regex("^\\d+(\\.\\d+)?[万亿]?$"))) return true
        if (t.matches(Regex("^\\d{1,2}:\\d{2}(/\\d{1,2}:\\d{2})?$"))) return true
        return false
    }

    private fun textsToPseudoSnaps(texts: List<String>): List<A11yNodeSnap> =
        texts.map {
            A11yNodeSnap(viewId = "", text = it.trim(), contentDescription = "", clickable = false)
        }

    private fun classifyMeta(meta: VideoPageMeta, surfaces: List<SurfaceRule>): VideoContentVerdict {
        if (meta.blob.isBlank()) return VideoContentVerdict.UNKNOWN
        val study = surfaces.filter {
            it.severity == SurfaceSeverity.WARN && it.match == SurfaceMatchMode.TITLE_HEURISTIC
        }
        val entertainment = surfaces.filter {
            it.severity == SurfaceSeverity.BLOCK && it.match == SurfaceMatchMode.TITLE_HEURISTIC
        }
        val studyHit = study.any { rule ->
            rule.patterns.any { p -> p.isNotBlank() && meta.blob.contains(p, ignoreCase = true) }
        }
        val entHit = entertainment.any { rule ->
            rule.patterns.any { p -> p.isNotBlank() && meta.blob.contains(p, ignoreCase = true) }
        }
        val tagStudy = study.any { rule ->
            rule.patterns.any { p -> meta.tags.any { t -> t.contains(p, ignoreCase = true) } }
        }
        val tagEnt = entertainment.any { rule ->
            rule.patterns.any { p -> meta.tags.any { t -> t.contains(p, ignoreCase = true) } }
        }

        return when {
            studyHit || tagStudy -> VideoContentVerdict.STUDY
            entHit || tagEnt -> VideoContentVerdict.ENTERTAINMENT
            meta.title.isNotBlank() || meta.description.isNotBlank() ->
                VideoContentVerdict.ENTERTAINMENT
            else -> VideoContentVerdict.UNKNOWN
        }
    }
}
