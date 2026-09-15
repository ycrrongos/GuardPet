package com.geekathon.guardpet

import org.json.JSONObject
import java.time.LocalDate

data class FlashOrganizeResult(
    val text: String,
    val color: Int = 0,
    val warning: String? = null
)

/**
 * 闪记非「日程」分类的 AI 整理：按灵感/日记/待办/其他润色与结构化。
 * HTTP 复用 [HabitLlmClient]。
 */
object FlashNoteLlmClient {

    fun organize(
        text: String,
        category: FlashNoteCategory,
        today: LocalDate = LocalDate.now()
    ): FlashOrganizeResult {
        val raw = text.trim()
        if (raw.isEmpty()) return FlashOrganizeResult("", warning = "内容为空")
        if (category == FlashNoteCategory.SCHEDULE) {
            return FlashOrganizeResult(raw, warning = "日程请走日程管线")
        }
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return FlashOrganizeResult(localOrganize(raw, category))
        }
        val system = systemPrompt(category)
        val user = JSONObject()
            .put("today", today.toString())
            .put("category", category.key)
            .put("text", raw)
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        if (!result.ok || result.json == null) {
            return FlashOrganizeResult(
                localOrganize(raw, category),
                warning = result.error ?: "AI 整理失败，已用本地规则"
            )
        }
        val organized = result.json.optString("text").trim().ifBlank {
            result.json.optJSONArray("items")?.let { arr ->
                buildString {
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i).trim()
                        if (line.isNotEmpty()) {
                            if (isNotEmpty()) append('\n')
                            append("· ").append(line)
                        }
                    }
                }
            }.orEmpty()
        }
        val urgency = result.json.optInt("urgency", -1)
        val color = when {
            category != FlashNoteCategory.TODO -> 0
            urgency in 0..5 -> urgency
            else -> 2
        }
        return FlashOrganizeResult(
            text = organized.ifBlank { localOrganize(raw, category) },
            color = color
        )
    }

    private fun systemPrompt(category: FlashNoteCategory): String {
        val role = when (category) {
            FlashNoteCategory.IDEA ->
                "把用户口述整理成「灵感」笔记：保留创意要点，可分条，语气轻快，不要编造用户没说的内容。"
            FlashNoteCategory.DIARY ->
                "把用户口述整理成「日记」：第一人称、通顺、保留情绪与事实，可分段，不要说教。"
            FlashNoteCategory.TODO ->
                "把用户口述整理成「待办」清单：每条一行、以 · 开头、可执行、短句；urgency 为 0..5（5 最紧急）。"
            FlashNoteCategory.OTHER ->
                "整理用户口述为清晰短笔记：去口头禅、保留信息，可分条。"
            FlashNoteCategory.SCHEDULE ->
                "原样返回。"
        }
        return """
你是守伴闪记助手。$role
输出 JSON（不要 markdown）：
{"text":"整理后的全文","urgency":0}
urgency 仅待办需要；其它分类 urgency 填 0。
不要添加用户未提及的事项。
        """.trimIndent()
    }

    private fun localOrganize(text: String, category: FlashNoteCategory): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when (category) {
            FlashNoteCategory.TODO -> {
                if (lines.size <= 1 && !text.contains('\n')) {
                    text.split(Regex("[；;。！？!?,，]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n") { "· $it" }
                        .ifBlank { "· $text" }
                } else {
                    lines.joinToString("\n") { line ->
                        if (line.startsWith("·") || line.startsWith("-") || line.startsWith("*")) line
                        else "· $line"
                    }
                }
            }
            FlashNoteCategory.IDEA, FlashNoteCategory.DIARY, FlashNoteCategory.OTHER ->
                lines.joinToString("\n").ifBlank { text }
            FlashNoteCategory.SCHEDULE -> text
        }
    }
}
