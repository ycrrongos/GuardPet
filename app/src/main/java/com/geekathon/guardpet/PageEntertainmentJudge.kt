package com.geekathon.guardpet

/**
 * 按整页可见元素判断：是否「大体是娱乐页」。
 * 同时有娱乐入口 + 其它非娱乐主功能（消息/通讯录等）→ 算壳层，不算娱乐页。
 */
enum class PageVerdict {
    /** 沉浸娱乐页，应记入禁用名单 */
    ENTERTAINMENT_PAGE,
    /** 主壳/多功能页：仅有娱乐入口字样 */
    SHELL_MIXED,
    /** 偏工作/通讯 */
    WORK_PAGE,
    UNCLEAR
}

object PageEntertainmentJudge {
    private val entertainmentMarkers = listOf(
        "朋友圈", "视频号", "看一看", "看点", "小世界", "QQ空间", "直播中", "游戏中心",
        "短视频", "推荐关注", "弹幕", "投币", "追剧", "番剧", "礼物", "秒杀",
        "Moments", "Channels", "For You"
    )
    private val immersiveMarkers = listOf(
        "说点什么", "评论了", "赞了", "转发", "分享到朋友圈", "写说说", "发表情",
        "关注并", "进入直播间", "人气榜", "送礼", "连麦", "弹幕"
    )
    private val workChrome = listOf(
        "消息", "联系人", "通讯录", "微信", "会话", "聊天", "通话", "邮件",
        "日历", "文档", "会议", "待办", "发送", "输入消息", "语音通话", "视频通话"
    )
    private val shellTabs = listOf(
        "消息", "联系人", "动态", "发现", "我", "通讯录", "频道", "工作台", "首页"
    )

    fun judge(texts: List<String>, packageName: String? = null): PageVerdict {
        val nodes = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nodes.isEmpty()) return PageVerdict.UNCLEAR
        val set = nodes.toHashSet()
        val blob = nodes.joinToString("\n")

        val workHits = workChrome.count { it in set || blob.contains(it) }
        val tabHits = shellTabs.count { it in set }
        val entEntryHits = entertainmentMarkers.count { m ->
            nodes.any { it.equals(m, ignoreCase = true) } ||
                (m.length >= 3 && nodes.any { it.contains(m, ignoreCase = true) && it.length <= m.length + 6 })
        }
        val immersiveHits = immersiveMarkers.count { m ->
            nodes.any { it.contains(m, ignoreCase = true) }
        }

        // QQ / 微信主壳：底栏多 + 娱乐只是入口
        val looksShell = tabHits >= 2 || (tabHits >= 1 && workHits >= 1)
        if (looksShell && immersiveHits == 0) {
            return PageVerdict.SHELL_MIXED
        }
        if (looksShell && entEntryHits >= 1 && immersiveHits < 2) {
            return PageVerdict.SHELL_MIXED
        }
        // 微信发现页：通讯录+发现 + 朋友圈入口
        if (packageName == "com.tencent.mm" || packageName?.contains("tencent.mm") == true) {
            if ("通讯录" in set && "发现" in set) return PageVerdict.SHELL_MIXED
        }
        if (packageName == "com.tencent.mobileqq") {
            if (listOf("消息", "联系人", "动态").count { it in set } >= 2) {
                return PageVerdict.SHELL_MIXED
            }
        }

        if (immersiveHits >= 2 || (immersiveHits >= 1 && entEntryHits >= 1 && !looksShell)) {
            return PageVerdict.ENTERTAINMENT_PAGE
        }
        // 顶栏标题精确为娱乐名，且无明显工作底栏
        val titleHit = nodes.take(8).any { n ->
            entertainmentMarkers.any { it.equals(n, ignoreCase = true) }
        }
        if (titleHit && tabHits <= 1 && workHits == 0) {
            return PageVerdict.ENTERTAINMENT_PAGE
        }
        if (workHits >= 2 && entEntryHits == 0) return PageVerdict.WORK_PAGE
        if (workHits >= 1 && immersiveHits == 0 && entEntryHits == 0) return PageVerdict.WORK_PAGE
        return PageVerdict.UNCLEAR
    }

    fun llmSystemPrompt(): String = """
根据当前页面可见文字，判断整页性质。只输出 JSON：
{"verdict":"ENTERTAINMENT_PAGE|SHELL_MIXED|WORK_PAGE|UNCLEAR","label":"短名","patterns":["关键词"]}
定义：
- ENTERTAINMENT_PAGE：整页大体是娱乐（朋友圈时间线、视频号流、QQ空间动态、看点正文、直播间等）
- SHELL_MIXED：主界面/多功能页，同时有娱乐入口和其它非娱乐功能（消息/通讯录等）——不要判娱乐
- WORK_PAGE：聊天、邮件、办公等
- UNCLEAR：看不出
禁止把「仅有朋友圈/空间入口字样」的主壳判成 ENTERTAINMENT_PAGE。
    """.trimIndent()
}
