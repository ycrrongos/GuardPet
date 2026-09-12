package com.geekathon.guardpet

/**
 * 通讯 App 主壳白名单 / 娱乐面参考名单。
 *
 * 公开资料（非官方、随版本漂移，仅作种子）：
 * - QQ 主页：`com.tencent.mobileqq.activity.SplashActivity`
 *   https://zhaolong.me/2019/Android-2019-08-30-常用App软件的包和Activity名/
 * - QQ 精简脚本里禁用的看点/空间组件（娱乐面参考）：
 *   https://gist.github.com/uncia/b8a91b7239d865064084073eacdad3cb
 * - 微信朋友圈：`...plugin.sns.ui.SnsTimeLineUI`；主界面 `...ui.LauncherUI`
 *
 * 没有完整「官方 Active 对照表」；主壳绝不能按页面上的「空间/看点」入口字样整页拦截。
 */
object CommActiveLists {
    /** 主界面 / 登录壳：永远不 blockInWork */
    private val mainShellExact = setOf(
        "com.tencent.mobileqq.activity.SplashActivity",
        "com.tencent.mobileqq.activity.LoginActivity",
        "com.tencent.mobileqq.activity.LoginPublicFragmentActivity",
        "com.tencent.mobileqq.activity.RegisterActivity",
        "com.tencent.mm.ui.LauncherUI",
        "com.tencent.mm.plugin.account.ui.WelcomeActivity",
        "com.tencent.mm.ui.account.LoginUI"
    )

    private val mainShellShort = setOf(
        "SplashActivity", "LauncherUI", "LoginActivity", "LoginUI",
        "MainActivity", "HomeActivity", "Conversation"
    )

    /** QQ 主页底栏/顶栏常见字，不能当娱乐 KEYWORD */
    private val qqChromeKeywords = setOf(
        "消息", "联系人", "动态", "频道", "更多", "搜索", "QQ", "通话", "短视频"
    )

    /** 微信发现页/主页壳层字，不能单独当已进入娱乐面 */
    private val wechatChromeKeywords = setOf(
        "微信", "通讯录", "发现", "我", "搜索", "WeChat"
    )

    fun isMainShell(activity: String?): Boolean {
        if (activity.isNullOrBlank()) return false
        if (activity in mainShellExact) return true
        val short = activity.substringAfterLast('.')
        if (short in mainShellShort) return true
        // QQ 主进程里挂载会话的壳
        if (activity.contains(".activity.SplashActivity")) return true
        if (activity.endsWith(".ui.LauncherUI")) return true
        return false
    }

    fun isChromeKeyword(packageName: String, keyword: String): Boolean {
        val k = keyword.trim()
        return when {
            packageName == "com.tencent.mobileqq" || packageName.startsWith("com.tencent.mobileqq") ->
                k in qqChromeKeywords || k == "空间" // 单字「空间」会误伤动态页入口
            packageName == "com.tencent.mm" || packageName.contains("tencent.mm") ->
                k in wechatChromeKeywords
            else -> false
        }
    }

    /**
     * 社区脚本里常见的 QQ 娱乐相关组件前缀（看点/空间/游戏等）。
     * 只用于增强启发式，不用于主壳。
     */
    fun looksQqEntertainmentPath(activity: String): Boolean {
        val p = activity.lowercase()
        if (isMainShell(activity)) return false
        return listOf(
            "cooperation.qzone.",
            "com.qzone.",
            ".qzone.",
            "readinjoy",
            "qqhotspot",
            "kandian",
            "gamecenter",
            "minigame",
            "cooperation.ilive.",
            "cooperation.liveroom.",
            "com.tencent.gamecenter.",
            "qzonediscover",
            "qzonefriendfeed",
            "qzonepluginproxy"
        ).any { it in p }
    }
}
