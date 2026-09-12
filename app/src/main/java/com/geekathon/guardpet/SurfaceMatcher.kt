package com.geekathon.guardpet

data class SurfaceHit(
    val rule: SurfaceRule,
    val matchedText: String
)

object SurfaceMatcher {
    private val navNoise = setOf(
        "首页", "推荐", "热门", "动态", "我的", "搜索", "消息", "通讯录", "发现",
        "微信", "设置", "返回", "分享", "评论", "点赞", "收藏", "下载", "关注",
        "Home", "Following", "Search", "Me", "Back"
    )

    /**
     * KEYWORD 必须命中整段可见节点文案（equals）。
     * 微信「发现」列表会同时露出「朋友圈」「视频号」入口，不能当成已进入娱乐面。
     */
    fun match(
        texts: List<String>,
        surfaces: List<SurfaceRule>,
        packageName: String? = null
    ): SurfaceHit? {
        if (texts.isEmpty() || surfaces.isEmpty()) return null
        val nodes = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val titles = candidateTitles(nodes)

        if (isWeChatPackage(packageName) && isWeChatDiscoveryOrHome(nodes)) {
            // 主界面 / 发现页：不因列表入口字样拦截
            return null
        }
        if (isQqPackage(packageName) && isQqMainChrome(nodes)) {
            // QQ 主壳 SplashActivity：底栏消息/联系人/动态 仍在时，不因「看点/空间」入口字样拦截
            return null
        }

        val studyAllow = surfaces.filter {
            it.severity == SurfaceSeverity.WARN && it.match == SurfaceMatchMode.TITLE_HEURISTIC
        }
        val blockSurfaces = surfaces.filter { it.severity == SurfaceSeverity.BLOCK }

        for (surface in blockSurfaces) {
            when (surface.match) {
                SurfaceMatchMode.KEYWORD -> {
                    val hit = surface.patterns.firstOrNull { pattern ->
                        pattern.isNotBlank() && nodes.any { nodeEqualsPattern(it, pattern) }
                    }
                    if (hit != null) {
                        // 真正进入朋友圈时，顶栏标题常为「朋友圈」，且没有「通讯录+发现」底栏
                        if (isWeChatPackage(packageName) &&
                            !isLikelyWeChatImmersiveSurface(nodes, hit)
                        ) {
                            continue
                        }
                        return SurfaceHit(surface, hit)
                    }
                }
                SurfaceMatchMode.TITLE_HEURISTIC -> {
                    val entertainmentHit = titles.firstNotNullOfOrNull { title ->
                        val bad = surface.patterns.firstOrNull { p ->
                            p.isNotBlank() && title.contains(p, ignoreCase = true)
                        }
                        if (bad != null) title to bad else null
                    } ?: continue
                    val allowedByStudy = studyAllow.any { allow ->
                        allow.patterns.any { p ->
                            p.isNotBlank() && entertainmentHit.first.contains(p, ignoreCase = true)
                        }
                    }
                    if (!allowedByStudy) {
                        return SurfaceHit(surface, entertainmentHit.first)
                    }
                }
            }
        }
        return null
    }

    private fun isWeChatPackage(packageName: String?): Boolean =
        packageName == "com.tencent.mm" || packageName?.contains("tencent.mm") == true

    private fun isQqPackage(packageName: String?): Boolean =
        packageName == "com.tencent.mobileqq" || packageName?.startsWith("com.tencent.mobileqq") == true

    /** QQ 主界面：底栏常见「消息/联系人/动态」仍在。 */
    private fun isQqMainChrome(nodes: List<String>): Boolean {
        val set = nodes.toHashSet()
        val tabs = listOf("消息", "联系人", "动态").count { it in set }
        return tabs >= 2
    }

    /** 微信主界面或发现页：底栏通讯录+发现仍在，且入口列表可见。 */
    private fun isWeChatDiscoveryOrHome(nodes: List<String>): Boolean {
        val set = nodes.toHashSet()
        val hasTabs = "通讯录" in set && "发现" in set
        if (!hasTabs) return false
        val discoveryEntries = listOf("朋友圈", "视频号", "看一看", "搜一搜", "直播", "附近")
            .count { it in set }
        // 底栏在 + 多个发现入口 → 仍在壳层，未进入朋友圈时间线
        return discoveryEntries >= 1 && ("微信" in set || "WeChat" in set || discoveryEntries >= 2)
    }

    private fun isLikelyWeChatImmersiveSurface(nodes: List<String>, pattern: String): Boolean {
        val set = nodes.toHashSet()
        val hasMainTabs = "通讯录" in set && "发现" in set
        if (hasMainTabs) return false
        // 顶栏/前几个节点出现精确标题
        return nodes.take(12).any { nodeEqualsPattern(it, pattern) }
    }

    private fun nodeEqualsPattern(node: String, pattern: String): Boolean {
        if (node.equals(pattern, ignoreCase = true)) return true
        if (pattern.length <= 8 && node.length <= pattern.length + 4) {
            return node.startsWith(pattern, ignoreCase = true)
        }
        return false
    }

    fun candidateTitles(texts: List<String>): List<String> =
        texts.asSequence()
            .map { it.trim() }
            .filter { it.length in 6..80 }
            .filter { it !in navNoise }
            .filter { !it.matches(Regex("^\\d+([:：.]\\d+)*$")) }
            .distinct()
            .take(24)
            .toList()
}
