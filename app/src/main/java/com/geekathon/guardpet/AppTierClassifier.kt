package com.geekathon.guardpet

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 本地启发式四类分类（商店/AI 都失败时的兜底）。
 * 正式分析：`AppStoreMetaFetcher`（Play/iTunes）→ AI 补全 → 本类。
 */
object AppTierClassifier {
    private val communicationPkgs = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.tencent.wework",
        "com.slack",
        "com.microsoft.teams",
        "com.whatsapp",
        "org.telegram.messenger"
    )

    private val videoPkgs = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.youku.phone",
        "com.qiyi.video",
        "com.tencent.qqlive",
        "com.netflix.mediaclient",
        "com.google.android.youtube"
    )

    private val entertainmentPkgs = setOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.xingin.xhs",
        "com.coolapk.market",
        "com.sina.weibo",
        "com.sankuai.meituan",
        "com.sankuai.meituan.takeoutnew",
        "me.ele",
        "com.taobao.taobao",
        "com.jingdong.app.mall",
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.pubgmhd"
    )

    private val workHints = listOf(
        "office", "docs", "mail", "email", "calendar", "notion", "feishu", "dingtalk",
        "wps", "excel", "word", "slides", "笔记", "文档", "邮箱", "会议", "打卡", "办公"
    )

    private val entertainmentHints = listOf(
        "game", "游戏", "娱乐", "漫画", "小说", "dating", "casino", "poker",
        "小红书", "xhs", "xingin", "酷安", "coolapk", "微博", "weibo",
        "社区", "种草", "刷帖", "美团", "meituan", "饿了么", "淘宝", "京东"
    )

    private val videoHints = listOf(
        "video", "movie", "tv", "bili", "youtube", "iqiyi", "youku", "抖音", "快手",
        "短视频", "直播", "影音", "影视"
    )

    private val communicationHints = listOf(
        "chat", "im", "messenger", "微信", "qq", "telegram", "whatsapp", "通讯", "社交聊天"
    )

    /**
     * 内置硬名单（不查商店）。抖音/快手等短视频归娱乐整禁，不归 VIDEO。
     */
    fun knownKind(packageName: String): AppGuardKind? {
        if (packageName in communicationPkgs) return AppGuardKind.COMMUNICATION
        if (packageName in setOf(
                "com.ss.android.ugc.aweme",
                "com.ss.android.ugc.aweme.lite",
                "com.smile.gifmaker",
                "com.kuaishou.nebula"
            )
        ) {
            return AppGuardKind.ENTERTAINMENT
        }
        if (packageName in videoPkgs) return AppGuardKind.VIDEO
        if (packageName in entertainmentPkgs) return AppGuardKind.ENTERTAINMENT
        return null
    }

    fun classify(
        context: Context,
        packageName: String,
        llmKind: AppGuardKind? = null
    ): AppGuardKind {
        if (llmKind != null) return llmKind
        knownKind(packageName)?.let { return it }

        val label = appLabel(context, packageName).lowercase()
        val blob = "$packageName $label"
        val category = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)

        when (category) {
            ApplicationInfo.CATEGORY_GAME -> return AppGuardKind.ENTERTAINMENT
            ApplicationInfo.CATEGORY_VIDEO -> return AppGuardKind.VIDEO
            ApplicationInfo.CATEGORY_SOCIAL -> {
                return if (communicationHints.any { it in blob }) {
                    AppGuardKind.COMMUNICATION
                } else {
                    AppGuardKind.ENTERTAINMENT
                }
            }
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppGuardKind.WORK
        }

        return when {
            videoHints.any { it in blob } -> AppGuardKind.VIDEO
            communicationHints.any { it in blob } -> AppGuardKind.COMMUNICATION
            entertainmentHints.any { it in blob } -> AppGuardKind.ENTERTAINMENT
            workHints.any { it in blob } -> AppGuardKind.WORK
            else -> AppGuardKind.WORK
        }
    }

    fun appLabel(context: Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
}
