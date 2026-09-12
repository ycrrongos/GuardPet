package com.geekathon.guardpet

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.JiebaSegmenter.SegMode
import java.util.concurrent.atomic.AtomicBoolean

object TextTokenizer {
    /** 词块流里的强制换行标记（不渲染成芯片，布局时折行）。 */
    const val LINE_BREAK = "\u2028"

    private val jiebaReady = AtomicBoolean(false)
    private val segmenter: JiebaSegmenter? by lazy {
        runCatching {
            JiebaSegmenter().also { jiebaReady.set(true) }
        }.getOrNull()
    }

    /**
     * [chunks] 一行一条（来自框选按 Y 聚类）。行间插入 [LINE_BREAK]；
     * 块内原有 `\n` 也会拆行。
     */
    fun splitAll(chunks: List<String>): List<String> {
        val out = mutableListOf<String>()
        var firstLine = true
        for (chunk in chunks) {
            val parts = chunk.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for (part in parts) {
                if (!firstLine) out.add(LINE_BREAK)
                firstLine = false
                out += split(part).filter { it.isNotBlank() && it != LINE_BREAK }
            }
        }
        return out
    }

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val fromJieba = segmenter?.let { engine ->
            runCatching {
                engine.process(text, SegMode.SEARCH)
                    .map { it.word.trim() }
                    .filter { it.isNotEmpty() }
                    .flatMap(::detachPunctuation)
            }.getOrNull()
        }
        if (!fromJieba.isNullOrEmpty()) return fromJieba
        return fallbackSplit(text)
    }

    fun joinSelected(tokens: List<String>, indices: Iterable<Int>): String {
        val sorted = indices.sorted().filter { tokens.getOrNull(it) != LINE_BREAK }
        if (sorted.isEmpty()) return ""
        val builder = StringBuilder()
        var previousIndex = -2
        for (index in sorted) {
            val word = tokens.getOrNull(index) ?: continue
            if (builder.isNotEmpty()) {
                val crossedBreak = (previousIndex + 1 until index).any {
                    tokens.getOrNull(it) == LINE_BREAK
                }
                if (crossedBreak) {
                    builder.append('\n')
                } else {
                    val previous = tokens.getOrNull(previousIndex).orEmpty()
                    if (needsSpace(previous, word)) builder.append(' ')
                }
            }
            builder.append(word)
            previousIndex = index
        }
        return builder.toString()
    }

    fun isLineBreak(token: String): Boolean = token == LINE_BREAK

    fun isPunctuationToken(token: String): Boolean {
        if (token.isEmpty() || token == LINE_BREAK) return false
        return token.all { isPunctuation(it) || it.isWhitespace() }
    }

    /** 把粘在词上的标点拆成独立 token（jieba 常把「勇气。」收成一块）。 */
    private fun detachPunctuation(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        if (word.all { isPunctuation(it) }) return listOf(word)
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(buf.toString())
                buf.clear()
            }
        }
        for (c in word) {
            if (isPunctuation(c)) {
                flush()
                out.add(c.toString())
            } else {
                buf.append(c)
            }
        }
        flush()
        return out.ifEmpty { listOf(word) }
    }

    private fun fallbackSplit(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flushBuffer() {
            if (buffer.isEmpty()) return
            tokens += segmentCjk(buffer.toString())
            buffer.clear()
        }
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens.add(latin.toString())
                latin.clear()
            }
        }
        text.forEach { char ->
            when {
                char == '\n' || char == '\r' -> {
                    flushLatin()
                    flushBuffer()
                    if (tokens.lastOrNull() != LINE_BREAK) tokens.add(LINE_BREAK)
                }
                char.isWhitespace() -> {
                    flushLatin()
                    flushBuffer()
                }
                isPunctuation(char) -> {
                    flushLatin()
                    flushBuffer()
                    tokens.add(char.toString())
                }
                isCjk(char) -> {
                    flushLatin()
                    buffer.append(char)
                }
                else -> {
                    flushBuffer()
                    latin.append(char)
                }
            }
        }
        flushLatin()
        flushBuffer()
        return tokens.filter { it.isNotBlank() }
    }

    private fun segmentCjk(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            var matched: String? = null
            val maxLen = minOf(4, text.length - index)
            for (len in maxLen downTo 2) {
                val piece = text.substring(index, index + len)
                if (piece in COMMON_WORDS) {
                    matched = piece
                    break
                }
            }
            if (matched != null) {
                tokens.add(matched)
                index += matched.length
            } else {
                tokens.add(text[index].toString())
                index += 1
            }
        }
        return tokens
    }

    private fun needsSpace(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        if (isPunctuationToken(left) || isPunctuationToken(right)) return false
        return !isCjk(left.last()) && !isCjk(right.first())
    }

    private fun isCjk(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    fun isPunctuation(char: Char): Boolean {
        val type = Character.getType(char)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            char == '|' || char == '｜' || char == '/' || char == '\\' ||
            char == '·' || char == '•' || char == '…' || char == '—' || char == '–'
    }

    fun prepare() {
        segmenter
        jiebaReady.get()
    }

    private val COMMON_WORDS = setOf(
        "我们", "你们", "他们", "自己", "什么", "怎么", "为什么", "因为", "所以", "但是",
        "然后", "如果", "虽然", "而且", "或者", "还是", "已经", "正在", "可以", "应该",
        "需要", "没有", "不是", "这个", "那个", "这些", "那些", "现在", "今天", "明天",
        "昨天", "时候", "时间", "分钟", "小时", "东西", "地方", "问题", "方法", "工作",
        "学习", "生活", "朋友", "家人", "孩子", "老师", "学生", "公司", "学校", "中国",
        "世界", "国家", "城市", "电话", "信息", "消息", "通知", "设置", "系统", "文件",
        "图片", "视频", "音乐", "搜索", "分享", "收藏", "关注", "评论", "点赞", "转发",
        "登录", "注册", "账号", "密码", "用户", "个人", "中心", "主页", "返回", "确定",
        "取消", "完成", "开始", "暂停", "继续", "关闭", "打开", "保存", "删除", "编辑",
        "复制", "粘贴", "发送", "接收", "下载", "上传", "更新", "安装", "应用", "软件",
        "网络", "无线", "蓝牙", "定位", "权限", "隐私", "安全", "帮助", "关于", "意见",
        "反馈", "服务", "条款", "协议", "内容", "详情", "更多", "全部", "其他", "默认",
        "选择", "添加", "创建", "管理", "记录", "历史", "日历", "提醒", "闹钟", "计时",
        "专注", "番茄", "守护", "陪伴", "夜间", "白天", "晚上", "早上", "中午", "下午",
        "习惯", "日程", "笔记", "闪记", "文字", "提取", "分词", "复制", "搜索", "页面",
        "屏幕", "悬浮", "窗口", "桌宠", "表情", "样式", "走动", "拖拽", "点击", "双击",
        "知道", "觉得", "希望", "喜欢", "看见", "听到", "说话", "起来", "出来", "过来",
        "过去", "回来", "一起", "一下", "一点", "一些", "一样", "一直", "一定", "一切",
        "不能", "不会", "不要", "不用", "不错", "不好意思", "对不起", "没关系", "谢谢",
        "请问", "怎么了", "干什么", "有没有", "是不是", "好不好", "对不对", "能不能",
        "手机", "电脑", "平板", "相机", "相册", "浏览器", "微信", "聊天", "群聊", "朋友圈",
        "新闻", "文章", "标题", "作者", "阅读", "播放", "暂停", "下一首", "上一首", "歌词",
        "订单", "支付", "价格", "优惠", "购物", "快递", "地址", "姓名", "号码", "验证码",
        "天气", "温度", "空气", "位置", "距离", "地图", "导航", "路线", "出发", "到达",
        "会议", "项目", "任务", "计划", "目标", "进度", "结果", "报告", "数据", "分析",
        "听见", "声音", "赛前", "调研", "伟大", "作品", "诞生", "敢于", "勇气", "筹备",
        "已久", "即将", "正式", "开启", "报名", "通道", "左划", "右划", "即可", "查看",
        "双指", "内容"
    )
}

object TextCaptureHolder {
    var tokens: List<String> = emptyList()
}
