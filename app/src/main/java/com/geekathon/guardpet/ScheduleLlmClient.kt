package com.geekathon.guardpet

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class ScheduleDraft(
    val title: String,
    val startMinutes: Int?,
    val endMinutes: Int?,
    val needsTime: Boolean,
    /** 条目所属日；空则入库时用锚点日（闪记选中日 / 日程页选中日）。 */
    val date: LocalDate? = null,
    val suggestedAllow: List<String> = emptyList(),
    val suggestedBlock: List<String> = emptyList(),
    val confidence: Float = 0.5f
)

data class ScheduleReviewResult(
    val accept: Boolean,
    val reason: String
)

data class ScheduleAiResult(
    val changed: Boolean,
    val message: String,
    val focusDate: LocalDate? = null
)

/**
 * 日程 DeepSeek 管线：规范化、补时间对齐、App 策略解析、合理性审查、多日自然语言指令。
 * HTTP 复用 [HabitLlmClient]。
 */
object ScheduleLlmClient {

    fun parseFromFlashNote(
        text: String,
        date: LocalDate = LocalDate.now()
    ): Pair<List<ScheduleDraft>, String?> {
        if (text.isBlank()) return emptyList<ScheduleDraft>() to "内容为空"
        val local = localHeuristicDrafts(text, date)
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return local to null
        }
        val system = """
你是守伴多日日程助手。把用户输入拆成日程 JSON（不要 markdown）。
输出：
{"items":[{"title":"短标题","date":"YYYY-MM-DD","start":"HH:mm或空","end":"HH:mm或空","needs_time":true/false,"suggested_allow":["包名"],"suggested_block":["包名"],"confidence":0.0}]}
规则：
- 每条必须有 date。用户说「明天/后天/下周一/9月20日」等要换成具体 YYYY-MM-DD；未提日期则用锚点日（today 字段）。
- 一条输入可含多天多条（例如「明天开会，后天交作业」→ 两条不同 date）。
- 缺具体钟点则 needs_time=true、start/end 可空；有「下午三点」等换算成 24h。
- suggested_* 可空；不要编造未出现的包名。
        """.trimIndent()
        val user = JSONObject()
            .put("today", date.toString())
            .put("weekday", date.dayOfWeek.name)
            .put("text", text)
            .put("history", DayScheduleStore.summaryForLlm())
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        if (!result.ok || result.json == null) {
            return local to (result.error ?: "AI 解析失败，已用本地规则")
        }
        val items = parseItems(result.json, date)
        return (items.ifEmpty { local }) to null
    }

    fun alignTimesFromVoice(
        drafts: List<ScheduleDraft>,
        voiceText: String,
        date: LocalDate = LocalDate.now()
    ): Pair<List<ScheduleDraft>, String?> {
        if (voiceText.isBlank()) return drafts to "没听清时间"
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return applyLocalTimeHints(drafts, voiceText) to null
        }
        val system = """
用户用语音补充各任务时间（可含「明天改到三点」等日期）。把时间对齐到已有任务列表，输出 JSON：
{"items":[{"title":"与输入尽量一致的标题","date":"YYYY-MM-DD或空","start":"HH:mm","end":"HH:mm","needs_time":false}]}
只输出能确定时间的项；不确定的保持 needs_time=true。date 空则保持原日期。
        """.trimIndent()
        val user = JSONObject()
            .put("today", date.toString())
            .put("voice", voiceText)
            .put(
                "tasks",
                JSONArray().also { arr ->
                    drafts.forEach { d ->
                        arr.put(
                            JSONObject()
                                .put("title", d.title)
                                .put("date", (d.date ?: date).toString())
                                .put("start", d.startMinutes?.let { DayScheduleStore.minutesToHm(it) })
                                .put("end", d.endMinutes?.let { DayScheduleStore.minutesToHm(it) })
                        )
                    }
                }
            )
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        if (!result.ok || result.json == null) {
            return applyLocalTimeHints(drafts, voiceText) to (result.error ?: "对齐失败")
        }
        val mapped = parseItems(result.json, date).associateBy {
            DaySchedule.normalizeTitleKey(it.title)
        }
        val merged = drafts.map { d ->
            val hit = mapped[DaySchedule.normalizeTitleKey(d.title)]
                ?: mapped.entries.firstOrNull { (k, _) ->
                    k.contains(DaySchedule.normalizeTitleKey(d.title)) ||
                        DaySchedule.normalizeTitleKey(d.title).contains(k)
                }?.value
            if (hit != null && hit.startMinutes != null) {
                d.copy(
                    date = hit.date ?: d.date,
                    startMinutes = hit.startMinutes,
                    endMinutes = hit.endMinutes ?: (hit.startMinutes + 60),
                    needsTime = false
                )
            } else d
        }
        return merged to null
    }

    fun parsePolicyFromVoice(
        schedule: DaySchedule,
        voiceText: String
    ): Pair<List<String>, List<String>>? {
        if (voiceText.isBlank()) return null
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return localPolicyFromVoice(voiceText)
        }
        val system = """
根据语音，为日程填写允许/禁止应用包名。输出 JSON：
{"allow":["com.xxx"],"block":["com.yyy"]}
常见：微信 com.tencent.mm，QQ com.tencent.mobileqq，飞书 com.ss.android.lark，钉钉 com.alibaba.android.rimet，B站 tv.danmaku.bili，小红书 com.xingin.xhs，美团 com.sankuai.meituan。
只输出 JSON。
        """.trimIndent()
        val user = JSONObject()
            .put("title", schedule.title)
            .put("voice", voiceText)
            .put("history", DayScheduleStore.summaryForLlm(schedule.titleKey))
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        val json = result.json ?: return localPolicyFromVoice(voiceText)
        val allow = jsonArrayStrings(json.optJSONArray("allow"))
        val block = jsonArrayStrings(json.optJSONArray("block"))
        return allow to block
    }

    fun reviewChange(
        draft: DaySchedule,
        changeKind: String,
        todayOthers: List<DaySchedule> = DayScheduleStore.today()
    ): ScheduleReviewResult {
        // 本地硬规则
        if (draft.title.isBlank()) {
            return ScheduleReviewResult(false, "标题不能为空")
        }
        if (draft.endMinutes <= draft.startMinutes) {
            return ScheduleReviewResult(false, "结束时间必须晚于开始时间")
        }
        if (draft.id > 0 &&
            draft.isPolicyLocked() &&
            changeKind in setOf("time", "policy", "both")
        ) {
            return ScheduleReviewResult(false, "开场前一小时内不可再改时间或 App 策略")
        }
        // 新建入库不做锁定拦截（changeKind=create）
        if (changeKind == "create") {
            // fall through to overlap / AI checks only
        }
        val overlap = todayOthers.count { other ->
            other.id != draft.id &&
                other.date == draft.date &&
                other.status == DayScheduleStatus.PENDING &&
                rangesOverlap(
                    draft.startMinutes,
                    draft.endMinutes,
                    other.startMinutes,
                    other.endMinutes
                )
        }
        if (overlap >= 3) {
            return ScheduleReviewResult(false, "今日重叠日程过多，疑似刷分，请调整时间")
        }
        // 空壳全放行 + 无禁止：可疑
        if (changeKind in setOf("policy", "both") &&
            draft.allowPackages.isEmpty() &&
            draft.blockPackages.isEmpty() &&
            draft.titleKey.length >= 2
        ) {
            val hist = DayScheduleStore.historyForTitleKey(draft.titleKey, 8)
            if (hist.any { it.blockPackages.isNotEmpty() || it.allowPackages.isNotEmpty() }) {
                return ScheduleReviewResult(
                    false,
                    "历史上这类日程有 App 限制，清空策略不合理（防刷分）"
                )
            }
        }
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return ScheduleReviewResult(true, "本地规则通过")
        }
        val system = """
审查日程修改是否合理（防刷食物分、胡填时间/策略）。输出 JSON：
{"accept":true/false,"reason":"一句话中文"}
拒绝：娱乐 App 放进深度工作允许列表、空策略骗完成奖励、时间胡填/严重重叠刷完成、与历史同类日程明显矛盾。
        """.trimIndent()
        val user = JSONObject()
            .put("changeKind", changeKind)
            .put(
                "draft",
                JSONObject()
                    .put("title", draft.title)
                    .put("start", DayScheduleStore.minutesToHm(draft.startMinutes))
                    .put("end", DayScheduleStore.minutesToHm(draft.endMinutes))
                    .put("allow", JSONArray(draft.allowPackages))
                    .put("block", JSONArray(draft.blockPackages))
            )
            .put("history", DayScheduleStore.summaryForLlm(draft.titleKey))
            .put(
                "today",
                JSONArray().also { arr ->
                    todayOthers.take(15).forEach { s ->
                        arr.put(
                            JSONObject()
                                .put("title", s.title)
                                .put("start", DayScheduleStore.minutesToHm(s.startMinutes))
                                .put("end", DayScheduleStore.minutesToHm(s.endMinutes))
                        )
                    }
                }
            )
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        val json = result.json
        if (!result.ok || json == null) {
            return ScheduleReviewResult(true, "审查服务暂不可用，已按本地规则放行")
        }
        val accept = json.optBoolean("accept", true)
        val reason = json.optString("reason").ifBlank {
            if (accept) "通过" else "不合理"
        }
        return ScheduleReviewResult(accept, reason)
    }

    fun commitDrafts(
        drafts: List<ScheduleDraft>,
        date: LocalDate,
        rawNote: String,
        source: DayScheduleSource = DayScheduleSource.FLASH_AI
    ): Pair<Int, String?> {
        var n = 0
        for (d in drafts) {
            if (d.needsTime || d.startMinutes == null) {
                return n to "还有任务缺时间，请用选择器或语音补齐"
            }
            val start = d.startMinutes
            val end = (d.endMinutes ?: (start + 60)).coerceAtLeast(start + 15)
            val itemDate = d.date ?: date
            val key = DaySchedule.normalizeTitleKey(d.title)
            val (histAllow, histBlock) = DayScheduleStore.typicalPackages(key)
            val allow = d.suggestedAllow.ifEmpty { histAllow }
            val block = d.suggestedBlock.ifEmpty { histBlock }
            val draft = DaySchedule(
                title = d.title,
                date = itemDate.toString(),
                startMinutes = start,
                endMinutes = end,
                allowPackages = allow,
                blockPackages = block,
                source = source,
                rawNote = rawNote,
                titleKey = key,
                difficulty = DayScheduleStore.difficultyFor(key),
                colorArgb = DayScheduleColor.randomPending(
                    DayScheduleStore.difficultyFor(key),
                    d.title.hashCode()
                )
            )
            val review = reviewChange(draft, "create", DayScheduleStore.forDate(itemDate))
            if (!review.accept) {
                return n to "「${d.title}」未通过审查：${review.reason}"
            }
            DayScheduleStore.insert(draft)
            n++
        }
        return n to null
    }

    /**
     * 多日日程页自然语言指令：创建 / 改时间策略 / 完成 / 删除。
     * 无 API Key 时用本地启发式在 [anchorDate] 创建日程。
     */
    fun applyInstruction(
        instruction: String,
        anchorDate: LocalDate,
        rangeStart: LocalDate,
        rangeEnd: LocalDate
    ): ScheduleAiResult {
        val text = instruction.trim()
        if (text.isBlank()) {
            return ScheduleAiResult(false, "请先输入要做的事")
        }
        val existing = DayScheduleStore.between(rangeStart, rangeEnd)
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            return applyInstructionLocal(text, anchorDate, existing)
        }
        val system = """
你是守伴多日日程助手。根据用户指令改日程，输出 JSON（不要 markdown）：
{"ops":[{"op":"create|update|complete|delete","id":0,"title":"","date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","allow":["包名"],"block":["包名"]}],"focus_date":"YYYY-MM-DD或空","summary":"一句话中文"}
规则：
- create 可不写 id；update/complete/delete 必须带已有 id
- 缺时间则按常见时长补 60 分钟；未提日期时 date 用锚点日，提到「明天/下周一/某月某日」必须写成对应 YYYY-MM-DD
- 可一次操作多天（创建多条不同 date、改非今天的条目）
- allow/block 可空；不要编造未提及的包名
- 不要在开场前 1 小时内改锁定中的条目的时间或策略（可 complete）
        """.trimIndent()
        val user = JSONObject()
            .put("anchorDate", anchorDate.toString())
            .put("rangeStart", rangeStart.toString())
            .put("rangeEnd", rangeEnd.toString())
            .put("instruction", text)
            .put(
                "existing",
                JSONArray().also { arr ->
                    existing.take(40).forEach { s ->
                        arr.put(
                            JSONObject()
                                .put("id", s.id)
                                .put("title", s.title)
                                .put("date", s.date)
                                .put("start", DayScheduleStore.minutesToHm(s.startMinutes))
                                .put("end", DayScheduleStore.minutesToHm(s.endMinutes))
                                .put("status", s.status.key)
                                .put("locked", s.isPolicyLocked())
                                .put("allow", JSONArray(s.allowPackages))
                                .put("block", JSONArray(s.blockPackages))
                        )
                    }
                }
            )
            .toString()
        val result = HabitLlmClient.chatJson(system, user)
        if (!result.ok || result.json == null) {
            val fallback = applyInstructionLocal(text, anchorDate, existing)
            return fallback.copy(
                message = (result.error ?: "AI 暂不可用") + "；" + fallback.message
            )
        }
        return applyOpsFromJson(result.json, existing, anchorDate)
    }

    private fun applyInstructionLocal(
        text: String,
        anchorDate: LocalDate,
        existing: List<DaySchedule>
    ): ScheduleAiResult {
        // 完成：完成「标题」/ 勾掉 xxx
        Regex("""(?:完成|勾掉|打勾)\s*[「"']?(.+?)[」"']?\s*$""").find(text)?.groupValues?.get(1)?.let { title ->
            val hit = existing.firstOrNull {
                it.status == DayScheduleStatus.PENDING &&
                    (it.title.contains(title.trim()) || title.trim().contains(it.title))
            }
            if (hit != null) {
                val (ok, msg) = DayScheduleStore.markDone(hit.id)
                return ScheduleAiResult(ok, msg, runCatching { LocalDate.parse(hit.date) }.getOrNull())
            }
        }
        // 删除
        Regex("""(?:删除|去掉|取消)\s*[「"']?(.+?)[」"']?\s*$""").find(text)?.groupValues?.get(1)?.let { title ->
            val hit = existing.firstOrNull {
                it.title.contains(title.trim()) || title.trim().contains(it.title)
            }
            if (hit != null) {
                DayScheduleStore.delete(hit.id)
                return ScheduleAiResult(
                    true,
                    "已删除「${hit.title}」",
                    runCatching { LocalDate.parse(hit.date) }.getOrNull()
                )
            }
        }
        val (drafts, warn) = parseFromFlashNote(text, anchorDate)
        if (drafts.isEmpty()) {
            return ScheduleAiResult(false, warn ?: "没理解指令，可写：明天 14:00 开会")
        }
        val ready = drafts.map { d ->
            val start = d.startMinutes ?: (9 * 60)
            d.copy(
                startMinutes = start,
                endMinutes = d.endMinutes ?: (start + 60),
                needsTime = false
            )
        }
        val (n, err) = commitDrafts(ready, anchorDate, text, DayScheduleSource.FLASH_AI)
        val focus = ready.mapNotNull { it.date }.firstOrNull() ?: anchorDate
        return when {
            n > 0 && err == null -> ScheduleAiResult(true, "已创建 $n 条日程", focus)
            n > 0 -> ScheduleAiResult(true, "已创建 $n 条；$err", focus)
            else -> ScheduleAiResult(false, err ?: warn ?: "创建失败")
        }
    }

    private fun applyOpsFromJson(
        json: JSONObject,
        existing: List<DaySchedule>,
        anchorDate: LocalDate
    ): ScheduleAiResult {
        val ops = json.optJSONArray("ops") ?: JSONArray()
        var changed = 0
        var focus: LocalDate? = runCatching {
            json.optString("focus_date").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        }.getOrNull()
        val notes = mutableListOf<String>()
        val byId = existing.associateBy { it.id }
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            when (o.optString("op").lowercase()) {
                "create" -> {
                    val title = o.optString("title").trim()
                    if (title.isBlank()) continue
                    val date = parseOpDate(o.optString("date"), anchorDate)
                    val start = DayScheduleStore.parseHm(o.optString("start")) ?: (9 * 60)
                    val end = (DayScheduleStore.parseHm(o.optString("end")) ?: (start + 60))
                        .coerceAtLeast(start + 15)
                    val draft = DaySchedule(
                        title = title,
                        date = date.toString(),
                        startMinutes = start,
                        endMinutes = end,
                        colorArgb = 0,
                        allowPackages = jsonArrayStrings(o.optJSONArray("allow")),
                        blockPackages = jsonArrayStrings(o.optJSONArray("block")),
                        source = DayScheduleSource.FLASH_AI,
                        rawNote = o.optString("title")
                    )
                    val review = reviewChange(draft, "create", DayScheduleStore.forDate(date))
                    if (!review.accept) {
                        notes += "创建「$title」拒绝：${review.reason}"
                        continue
                    }
                    DayScheduleStore.insert(draft)
                    changed++
                    focus = date
                }
                "update" -> {
                    val id = o.optLong("id", -1L)
                    val cur = byId[id] ?: continue
                    val date = parseOpDate(o.optString("date"), runCatching {
                        LocalDate.parse(cur.date)
                    }.getOrDefault(anchorDate))
                    val start = DayScheduleStore.parseHm(o.optString("start")) ?: cur.startMinutes
                    val end = (DayScheduleStore.parseHm(o.optString("end")) ?: cur.endMinutes)
                        .coerceAtLeast(start + 15)
                    val next = cur.copy(
                        title = o.optString("title").trim().ifBlank { cur.title },
                        date = date.toString(),
                        startMinutes = start,
                        endMinutes = end,
                        allowPackages = if (o.has("allow")) {
                            jsonArrayStrings(o.optJSONArray("allow"))
                        } else cur.allowPackages,
                        blockPackages = if (o.has("block")) {
                            jsonArrayStrings(o.optJSONArray("block"))
                        } else cur.blockPackages
                    )
                    val kind = when {
                        cur.date != next.date || cur.startMinutes != next.startMinutes ||
                            cur.endMinutes != next.endMinutes -> "time"
                        cur.allowPackages != next.allowPackages ||
                            cur.blockPackages != next.blockPackages -> "policy"
                        else -> "title"
                    }
                    val review = reviewChange(next, kind, DayScheduleStore.forDate(date))
                    if (!review.accept) {
                        notes += "更新「${cur.title}」拒绝：${review.reason}"
                        continue
                    }
                    if (!DayScheduleStore.update(next)) {
                        notes += "更新「${cur.title}」失败（可能已锁定）"
                        continue
                    }
                    changed++
                    focus = date
                }
                "complete" -> {
                    val id = o.optLong("id", -1L)
                    val (ok, msg) = DayScheduleStore.markDone(id)
                    if (ok) {
                        changed++
                        byId[id]?.date?.let {
                            focus = runCatching { LocalDate.parse(it) }.getOrNull() ?: focus
                        }
                    } else {
                        notes += msg
                    }
                }
                "delete" -> {
                    val id = o.optLong("id", -1L)
                    if (byId.containsKey(id)) {
                        DayScheduleStore.delete(id)
                        changed++
                    }
                }
            }
        }
        val summary = json.optString("summary").ifBlank {
            if (changed > 0) "已应用 $changed 项修改" else "没有可执行的修改"
        }
        val message = if (notes.isEmpty()) summary else "$summary（${notes.take(2).joinToString("；")}）"
        return ScheduleAiResult(changed > 0, message, focus)
    }

    private fun parseOpDate(raw: String?, fallback: LocalDate): LocalDate =
        ScheduleDateParse.parse(raw, fallback) ?: fallback

    private fun rangesOverlap(a0: Int, a1: Int, b0: Int, b1: Int): Boolean =
        a0 < b1 && b0 < a1

    private fun parseItems(json: JSONObject, anchor: LocalDate): List<ScheduleDraft> {
        val arr = json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isBlank()) continue
                val start = DayScheduleStore.parseHm(o.optString("start").takeIf { it.isNotBlank() })
                val end = DayScheduleStore.parseHm(o.optString("end").takeIf { it.isNotBlank() })
                val needs = o.optBoolean("needs_time", start == null)
                val itemDate = ScheduleDateParse.parse(
                    o.optString("date").takeIf { it.isNotBlank() },
                    anchor
                ) ?: anchor
                add(
                    ScheduleDraft(
                        title = title,
                        date = itemDate,
                        startMinutes = start,
                        endMinutes = end,
                        needsTime = needs || start == null,
                        suggestedAllow = jsonArrayStrings(o.optJSONArray("suggested_allow")),
                        suggestedBlock = jsonArrayStrings(o.optJSONArray("suggested_block")),
                        confidence = o.optDouble("confidence", 0.5).toFloat()
                    )
                )
            }
        }
    }

    private fun jsonArrayStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun localHeuristicDrafts(text: String, date: LocalDate): List<ScheduleDraft> {
        // 先按「明天…；后天…」等切段，再按换行/分号
        val rough = text.split(Regex("""(?=明天|明日|后天|大后天|今天|今日|下周|本周|这周|\d{1,2}月\d{1,2}日|\d{4}[-/.年])"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        val lines = if (rough.size > 1) {
            rough
        } else {
            text.split(Regex("[\\n；;]+")).map { it.trim() }.filter { it.length >= 2 }
        }
        val chunks = lines.ifEmpty { listOf(text.trim()) }
        return chunks.map { chunk ->
            val (itemDate, cleaned) = ScheduleDateParse.extractFromText(chunk, date)
            val start = DayScheduleStore.parseHm(cleaned)
                ?: Regex("""(\d{1,2})\s*点""").find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 0..23 }?.let { it * 60 }
            val title = cleaned.take(40).ifBlank { chunk.take(40) }
            val key = DaySchedule.normalizeTitleKey(title)
            val typical = DayScheduleStore.typicalTime(key)
            val (allow, block) = DayScheduleStore.typicalPackages(key)
            ScheduleDraft(
                title = title,
                date = itemDate ?: date,
                startMinutes = start ?: typical?.first,
                endMinutes = if (start != null) start + 60 else typical?.second,
                needsTime = start == null && typical == null,
                suggestedAllow = allow,
                suggestedBlock = block,
                confidence = if (start != null || typical != null) 0.6f else 0.3f
            )
        }
    }

    private fun applyLocalTimeHints(
        drafts: List<ScheduleDraft>,
        voice: String
    ): List<ScheduleDraft> {
        val times = Regex("""(\d{1,2})[:：点](\d{0,2})""")
            .findAll(voice)
            .mapNotNull {
                val h = it.groupValues[1].toInt()
                val m = it.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && m in 0..59) h * 60 + m else null
            }
            .toList()
        if (times.isEmpty()) return drafts
        return drafts.mapIndexed { i, d ->
            val t = times.getOrNull(i) ?: return@mapIndexed d
            d.copy(startMinutes = t, endMinutes = t + 60, needsTime = false)
        }
    }

    private fun localPolicyFromVoice(voice: String): Pair<List<String>, List<String>> {
        val allow = mutableListOf<String>()
        val block = mutableListOf<String>()
        val map = listOf(
            listOf("微信", "wechat") to "com.tencent.mm",
            listOf("qq") to "com.tencent.mobileqq",
            listOf("飞书", "lark") to "com.ss.android.lark",
            listOf("钉钉") to "com.alibaba.android.rimet",
            listOf("b站", "哔哩", "bilibili") to "tv.danmaku.bili",
            listOf("小红书") to "com.xingin.xhs",
            listOf("美团") to "com.sankuai.meituan"
        )
        val v = voice.lowercase()
        val forbid = v.contains("禁") || v.contains("不许") || v.contains("不要")
        val permit = v.contains("只许") || v.contains("允许") || v.contains("可以用")
        map.forEach { (keys, pkg) ->
            if (keys.any { it in v }) {
                when {
                    forbid && !permit -> block.add(pkg)
                    permit -> allow.add(pkg)
                    forbid -> block.add(pkg)
                    else -> allow.add(pkg)
                }
            }
        }
        return allow.distinct() to block.distinct()
    }
}
