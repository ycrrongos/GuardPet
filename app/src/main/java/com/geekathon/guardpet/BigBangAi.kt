package com.geekathon.guardpet

enum class BigBangAiAction(val key: String, val labelRes: Int) {
    NOTE("note", R.string.bigbang_ai_note),
    CLEAN("clean", R.string.bigbang_ai_clean),
    TRANSLATE("translate", R.string.bigbang_ai_translate),
    CUSTOM("custom", R.string.bigbang_ai_custom);

    companion object {
        fun fromKey(raw: String?): BigBangAiAction =
            entries.firstOrNull { it.key == raw } ?: NOTE
    }
}

object BigBangAi {
    const val NOTE_PROMPT =
        "你是笔记助手。把用户给出的零散识别文字整理成简洁中文笔记：保留要点、补标点、分段。只输出整理后的正文，不要解释。"
    const val CLEAN_PROMPT =
        "清理 OCR/无障碍抓取的杂乱文本：去掉重复词、无意义符号、乱码；保留原意。只输出清理后的正文。"
    const val TRANSLATE_PROMPT =
        "把以下文本翻译成流畅中文（若已是中文则润色为更通顺）。只输出译文。"

    fun systemPrompt(action: BigBangAiAction, customHint: String = ""): String = when (action) {
        BigBangAiAction.NOTE -> NOTE_PROMPT
        BigBangAiAction.CLEAN -> CLEAN_PROMPT
        BigBangAiAction.TRANSLATE -> TRANSLATE_PROMPT
        BigBangAiAction.CUSTOM ->
            "按用户指令处理文本。指令：${customHint.ifBlank { "整理得更清晰" }}。只输出处理后的正文，不要解释。"
    }

    fun run(action: BigBangAiAction, source: String, customHint: String = ""): HabitLlmResult =
        runPrompt(systemPrompt(action, customHint), source)

    fun runPrompt(prompt: String, source: String): HabitLlmResult {
        if (source.isBlank()) {
            return HabitLlmResult(ok = false, error = "没有可整理的文字")
        }
        val trimmed = prompt.trim().ifBlank { "整理得更清晰" }
        val system = if (trimmed.contains("只输出")) trimmed
        else "$trimmed\n只输出处理后的正文，不要解释。"
        return HabitLlmClient.chatText(system, source)
    }
}
