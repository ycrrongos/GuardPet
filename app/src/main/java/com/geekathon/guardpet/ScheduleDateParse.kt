package com.geekathon.guardpet

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 把「明天 / 下周一 / 9月15日 / 2026-09-20」等解析成 [LocalDate]。
 * [anchor] 一般为「今天」或闪记/日程页选中的锚点日。
 */
object ScheduleDateParse {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(raw: String?, anchor: LocalDate = LocalDate.now()): LocalDate? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        runCatching { LocalDate.parse(t, iso) }.getOrNull()?.let { return it }

        when {
            t == "今天" || t == "今日" || t.equals("today", true) -> return anchor
            t == "明天" || t == "明日" || t.equals("tomorrow", true) -> return anchor.plusDays(1)
            t == "后天" -> return anchor.plusDays(2)
            t == "大后天" -> return anchor.plusDays(3)
            t == "昨天" || t == "昨日" -> return anchor.minusDays(1)
        }

        Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$""").matchEntire(t)?.let { m ->
            return runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        Regex("""^(\d{1,2})[-/.月](\d{1,2})日?$""").matchEntire(t)?.let { m ->
            return runCatching {
                val candidate = LocalDate.of(
                    anchor.year,
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt()
                )
                if (candidate.isBefore(anchor.minusMonths(1))) candidate.plusYears(1) else candidate
            }.getOrNull()
        }

        weekDayOffset(t, anchor)?.let { return it }
        return null
    }

    /**
     * 从整段文案里抽出日期；返回 (日期, 去掉日期短语后的标题残段)。
     * 找不到则 date=null、title=原文。
     */
    fun extractFromText(text: String, anchor: LocalDate = LocalDate.now()): Pair<LocalDate?, String> {
        val patterns = listOf(
            Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?"""),
            Regex("""(\d{1,2})月(\d{1,2})日?"""),
            Regex("""(大后天|后天|明天|明日|今天|今日|昨天|昨日)"""),
            Regex("""((?:下*下?|本|这)?周[一二三四五六日天]|星期[一二三四五六日天]|周[一二三四五六日天])""")
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val date = parse(m.value, anchor) ?: continue
            val cleaned = text.replaceFirst(m.value, " ").replace(Regex("""\s{2,}"""), " ").trim()
            return date to cleaned.ifBlank { text.trim() }
        }
        return null to text.trim()
    }

    private fun weekDayOffset(raw: String, anchor: LocalDate): LocalDate? {
        val t = raw.trim()
        val dow = when {
            t.contains("一") -> DayOfWeek.MONDAY
            t.contains("二") -> DayOfWeek.TUESDAY
            t.contains("三") -> DayOfWeek.WEDNESDAY
            t.contains("四") -> DayOfWeek.THURSDAY
            t.contains("五") -> DayOfWeek.FRIDAY
            t.contains("六") -> DayOfWeek.SATURDAY
            t.contains("日") || t.contains("天") -> DayOfWeek.SUNDAY
            else -> return null
        }
        if (!t.contains("周") && !t.contains("星期")) return null
        val nextCount = Regex("下").findAll(t).count()
        return when {
            t.startsWith("本") || t.startsWith("这") ->
                anchor.with(TemporalAdjusters.nextOrSame(dow))
            nextCount >= 1 -> {
                var d = anchor.with(TemporalAdjusters.next(dow))
                repeat(nextCount - 1) {
                    d = d.plusWeeks(1)
                }
                d
            }
            else -> {
                // 「周一」：若今天已过该星期几则下周，否则本周
                val thisOrNext = anchor.with(TemporalAdjusters.nextOrSame(dow))
                if (thisOrNext == anchor || !thisOrNext.isBefore(anchor)) thisOrNext
                else anchor.with(TemporalAdjusters.next(dow))
            }
        }
    }

    fun label(date: LocalDate, anchor: LocalDate = LocalDate.now()): String = when (date) {
        anchor -> "今天"
        anchor.plusDays(1) -> "明天"
        anchor.plusDays(2) -> "后天"
        else -> date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
    }
}
