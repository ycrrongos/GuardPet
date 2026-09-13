package com.geekathon.guardpet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

enum class DayScheduleStatus(val key: String) {
    PENDING("pending"),
    DONE("done"),
    SKIPPED("skipped");

    companion object {
        fun fromKey(raw: String?) =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

enum class DayScheduleSource(val key: String) {
    CALENDAR("calendar"),
    FLASH_AI("flash_ai"),
    MANUAL("manual");

    companion object {
        fun fromKey(raw: String?) =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: MANUAL
    }
}

data class DaySchedule(
    val id: Long = 0,
    val title: String,
    val date: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val status: DayScheduleStatus = DayScheduleStatus.PENDING,
    val colorArgb: Int,
    val difficulty: Float = 0.4f,
    val allowPackages: List<String> = emptyList(),
    val blockPackages: List<String> = emptyList(),
    val source: DayScheduleSource = DayScheduleSource.MANUAL,
    val calendarEventId: Long? = null,
    val rawNote: String? = null,
    val titleKey: String = normalizeTitleKey(title),
    val createdAt: Long = System.currentTimeMillis(),
    val foodAwarded: Boolean = false,
    /** 完成程度 0–100；未完成时为 null */
    val completionDegree: Int? = null,
    /** 完成经验 / 反思 */
    val experienceNote: String? = null
) {
    fun displayColor(): Int =
        if (status == DayScheduleStatus.DONE) DayScheduleColor.DONE_GREEN else colorArgb

    fun durationMinutes(): Int = (endMinutes - startMinutes).coerceAtLeast(1)

    fun midpointMinutes(): Int = startMinutes + durationMinutes() / 2

    fun isPolicyLocked(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (date != today.toString()) return date < today.toString()
        val nowMins = now.hour * 60 + now.minute
        return nowMins >= (startMinutes - 60)
    }

    fun isActiveNow(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (status != DayScheduleStatus.PENDING) return false
        if (date != today.toString()) return false
        val nowMins = now.hour * 60 + now.minute
        return nowMins in startMinutes until endMinutes.coerceAtLeast(startMinutes + 1)
    }

    /** 已过半程（含结束后）才可打开完成页。 */
    fun canCompleteNow(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (status != DayScheduleStatus.PENDING) return false
        if (date > today.toString()) return false
        if (date < today.toString()) return true
        val nowMins = now.hour * 60 + now.minute
        return nowMins >= midpointMinutes()
    }

    fun hasEnded(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Boolean {
        if (date < today.toString()) return true
        if (date > today.toString()) return false
        val nowMins = now.hour * 60 + now.minute
        return nowMins >= endMinutes
    }

    companion object {
        fun normalizeTitleKey(title: String): String =
            title.lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), "")
                .replace(Regex("[的了吗呢啊～~！!？?。．.]"), "")
                .take(32)
                .ifBlank { "untitled" }
    }
}

object DayScheduleStore {
    private lateinit var helper: Helper
    private var appContext: Context? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
        helper = Helper(context.applicationContext)
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun today(today: LocalDate = LocalDate.now()): List<DaySchedule> =
        query("date=?", arrayOf(today.toString()), "start_minutes ASC, id ASC")

    fun forDate(date: LocalDate): List<DaySchedule> = today(date)

    /** [start, end] 闭区间，按日统计条数（月历圆点）。 */
    fun countsBetween(start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
        if (end.isBefore(start)) return emptyMap()
        val rows = query(
            "date>=? AND date<=?",
            arrayOf(start.toString(), end.toString()),
            "date ASC, start_minutes ASC"
        )
        val map = linkedMapOf<LocalDate, Int>()
        rows.forEach { s ->
            val d = runCatching { LocalDate.parse(s.date) }.getOrNull() ?: return@forEach
            map[d] = (map[d] ?: 0) + 1
        }
        return map
    }

    fun between(start: LocalDate, end: LocalDate): List<DaySchedule> {
        if (end.isBefore(start)) return emptyList()
        return query(
            "date>=? AND date<=?",
            arrayOf(start.toString(), end.toString()),
            "date ASC, start_minutes ASC, id ASC"
        )
    }

    fun byId(id: Long): DaySchedule? =
        query("id=?", arrayOf(id.toString())).firstOrNull()

    fun activeNow(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): DaySchedule? =
        today(today).firstOrNull { it.isActiveNow(now, today) }

    fun historyForTitleKey(titleKey: String, limit: Int = 20): List<DaySchedule> =
        query(
            "title_key=?",
            arrayOf(titleKey),
            "created_at DESC"
        ).take(limit)

    fun completionRate(titleKey: String): Float {
        val hist = historyForTitleKey(titleKey, 40)
        if (hist.size < 2) return 0.55f
        val done = hist.count { it.status == DayScheduleStatus.DONE }
        return done.toFloat() / hist.size
    }

    fun difficultyFor(titleKey: String): Float =
        (1f - completionRate(titleKey)).coerceIn(0.15f, 0.95f)

    fun typicalTime(titleKey: String): Pair<Int, Int>? {
        val hist = historyForTitleKey(titleKey, 15)
            .filter { it.startMinutes in 0 until 24 * 60 && it.endMinutes > it.startMinutes }
        if (hist.size < 3) return null
        val start = hist.map { it.startMinutes }.sorted()[hist.size / 2]
        val end = hist.map { it.endMinutes }.sorted()[hist.size / 2]
        if (end <= start) return null
        return start to end
    }

    fun typicalPackages(titleKey: String): Pair<List<String>, List<String>> {
        val hist = historyForTitleKey(titleKey, 15)
        if (hist.isEmpty()) return emptyList<String>() to emptyList()
        fun top(picker: (DaySchedule) -> List<String>): List<String> {
            val counts = linkedMapOf<String, Int>()
            hist.forEach { s ->
                picker(s).forEach { pkg -> counts[pkg] = (counts[pkg] ?: 0) + 1 }
            }
            return counts.entries.sortedByDescending { it.value }.take(8).map { it.key }
        }
        return top { it.allowPackages } to top { it.blockPackages }
    }

    fun insert(schedule: DaySchedule): Long {
        val withMeta = schedule.copy(
            titleKey = DaySchedule.normalizeTitleKey(schedule.title),
            difficulty = if (schedule.difficulty > 0f) schedule.difficulty
            else difficultyFor(DaySchedule.normalizeTitleKey(schedule.title)),
            colorArgb = if (schedule.colorArgb != 0) schedule.colorArgb
            else DayScheduleColor.randomPending(
                difficultyFor(DaySchedule.normalizeTitleKey(schedule.title)),
                schedule.title.hashCode()
            )
        )
        val id = helper.writableDatabase.insert(
            "day_schedules",
            null,
            values(withMeta, includeId = false)
        )
        notifyChanged()
        return id
    }

    fun update(schedule: DaySchedule): Boolean {
        val existing = byId(schedule.id) ?: return false
        if (existing.isPolicyLocked() &&
            (existing.allowPackages != schedule.allowPackages ||
                existing.blockPackages != schedule.blockPackages ||
                existing.startMinutes != schedule.startMinutes ||
                existing.endMinutes != schedule.endMinutes ||
                existing.date != schedule.date)
        ) {
            return false
        }
        helper.writableDatabase.update(
            "day_schedules",
            values(
                schedule.copy(titleKey = DaySchedule.normalizeTitleKey(schedule.title)),
                includeId = false
            ),
            "id=?",
            arrayOf(schedule.id.toString())
        )
        notifyChanged()
        return true
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("day_schedules", "id=?", arrayOf(id.toString()))
        notifyChanged()
    }

    /**
     * 按给定顺序重排当日全部日程（含已完成）：时长不变，从最早开始时刻起紧挨重排。
     * [orderedIds] 必须是当日全部 id 的全排列。
     */
    fun reorderDay(date: LocalDate, orderedIds: List<Long>): Pair<Boolean, String> {
        val all = forDate(date)
        if (all.size < 2) return false to "至少两条日程才能调整顺序"
        val expected = all.map { it.id }.toSet()
        if (orderedIds.size != expected.size || orderedIds.toSet() != expected) {
            return false to "顺序无效"
        }
        val byId = all.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val anchor = all.minOf { it.startMinutes }
        var cursor = anchor
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ordered.forEach { s ->
                val dur = s.durationMinutes()
                val start = cursor
                val end = (start + dur).coerceAtMost(24 * 60 - 1)
                db.update(
                    "day_schedules",
                    ContentValues().apply {
                        put("start_minutes", start)
                        put("end_minutes", end.coerceAtLeast(start + 1))
                    },
                    "id=?",
                    arrayOf(s.id.toString())
                )
                cursor = end
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        return true to "已调整顺序与时间"
    }

    /**
     * 同日未完成日程上移/下移（兼容旧入口）。已开始也可调；已完成请用 [reorderDay]。
     */
    fun movePending(date: LocalDate, id: Long, direction: Int): Pair<Boolean, String> {
        if (direction != -1 && direction != 1) return false to "无效方向"
        val all = forDate(date)
        if (all.size < 2) return false to "至少两条日程才能调整顺序"
        val idx = all.indexOfFirst { it.id == id }
        val swapWith = idx + direction
        if (idx < 0) return false to "日程不存在"
        if (swapWith !in all.indices) {
            return false to if (direction < 0) "已经在最上面" else "已经在最下面"
        }
        val order = all.map { it.id }.toMutableList()
        val a = order[idx]
        order[idx] = order[swapWith]
        order[swapWith] = a
        return reorderDay(date, order)
    }

    /** @deprecated 用 [reorderDay]；保留给旧调用。 */
    fun reorderPending(date: LocalDate, orderedMovableIds: List<Long>): Pair<Boolean, String> =
        reorderDay(date, orderedMovableIds)

    fun findByCalendarEvent(eventId: Long, date: String): DaySchedule? =
        query(
            "calendar_event_id=? AND date=?",
            arrayOf(eventId.toString(), date)
        ).firstOrNull()

    /** 快捷完成（AI/旧入口）：需已过半程，默认完成度 80。 */
    fun markDone(id: Long): Pair<Boolean, String> {
        val s = byId(id) ?: return false to "日程不存在"
        if (!s.canCompleteNow()) {
            return false to "进行到一半后才能完成（${minutesToHm(s.midpointMinutes())} 起）"
        }
        return complete(id, degree = 80, experience = "（快捷完成）")
    }

    /**
     * 完成日程：需已过半程；写入完成程度与经验，食物 +1（仅一次）。
     */
    fun complete(id: Long, degree: Int, experience: String): Pair<Boolean, String> {
        val s = byId(id) ?: return false to "日程不存在"
        if (s.status == DayScheduleStatus.DONE) return false to "已经完成过了"
        if (!s.canCompleteNow()) {
            return false to "进行到一半后才能完成（${minutesToHm(s.midpointMinutes())} 起）"
        }
        val ctx = appContext ?: return false to "未初始化"
        val deg = degree.coerceIn(0, 100)
        val note = experience.trim()
        if (note.isBlank()) return false to "请填写完成经验"
        val awarded = !s.foodAwarded
        helper.writableDatabase.update(
            "day_schedules",
            ContentValues().apply {
                put("status", DayScheduleStatus.DONE.key)
                put("food_awarded", 1)
                put("completion_degree", deg)
                put("experience_note", note)
            },
            "id=?",
            arrayOf(id.toString())
        )
        if (awarded) {
            val settings = PetSettings(ctx)
            settings.foodCount = settings.foodCount + 1
            settings.mood = (settings.mood + 4).coerceAtMost(100)
        }
        ScheduleEndScheduler.clearNotified(ctx, id)
        notifyChanged()
        return true to if (awarded) "完成！食物 +1" else "已标记完成"
    }

    fun summaryForLlm(titleKey: String? = null): JSONArray {
        val arr = JSONArray()
        val list = if (titleKey.isNullOrBlank()) {
            today() + query(null, null, "created_at DESC").take(30)
        } else {
            historyForTitleKey(titleKey, 12)
        }
        list.distinctBy { it.id }.take(40).forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("title", s.title)
                    .put("titleKey", s.titleKey)
                    .put("date", s.date)
                    .put("start", minutesToHm(s.startMinutes))
                    .put("end", minutesToHm(s.endMinutes))
                    .put("status", s.status.key)
                    .put("allow", JSONArray(s.allowPackages))
                    .put("block", JSONArray(s.blockPackages))
            )
        }
        return arr
    }

    fun minutesToHm(mins: Int): String {
        val m = mins.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun parseHm(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        Regex("""(\d{1,2})[:：](\d{2})""").find(t)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return h * 60 + m
        }
        Regex("""(\d{1,2})\s*点\s*(\d{0,2})""").find(t)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && m in 0..59) return h * 60 + m
        }
        return null
    }

    private fun values(s: DaySchedule, includeId: Boolean) = ContentValues().apply {
        if (includeId && s.id > 0) put("id", s.id)
        put("title", s.title)
        put("date", s.date)
        put("start_minutes", s.startMinutes)
        put("end_minutes", s.endMinutes)
        put("status", s.status.key)
        put("color_argb", s.colorArgb)
        put("difficulty", s.difficulty)
        put("allow_packages", encodePkgs(s.allowPackages))
        put("block_packages", encodePkgs(s.blockPackages))
        put("source", s.source.key)
        if (s.calendarEventId != null) put("calendar_event_id", s.calendarEventId)
        else putNull("calendar_event_id")
        put("raw_note", s.rawNote)
        put("title_key", s.titleKey)
        put("created_at", s.createdAt)
        put("food_awarded", if (s.foodAwarded) 1 else 0)
        if (s.completionDegree != null) put("completion_degree", s.completionDegree)
        else putNull("completion_degree")
        put("experience_note", s.experienceNote)
    }

    private fun encodePkgs(list: List<String>): String =
        JSONArray(list).toString()

    private fun decodePkgs(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
        appContext?.let { ScheduleEndScheduler.rescheduleAll(it) }
    }

    private fun query(
        selection: String?,
        args: Array<String>?,
        order: String = "created_at DESC"
    ): List<DaySchedule> {
        val cursor = helper.readableDatabase.query(
            "day_schedules",
            null,
            selection,
            args,
            null,
            null,
            order
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val degIdx = it.getColumnIndex("completion_degree")
                    val expIdx = it.getColumnIndex("experience_note")
                    add(
                        DaySchedule(
                            id = it.getLong(it.getColumnIndexOrThrow("id")),
                            title = it.getString(it.getColumnIndexOrThrow("title")),
                            date = it.getString(it.getColumnIndexOrThrow("date")),
                            startMinutes = it.getInt(it.getColumnIndexOrThrow("start_minutes")),
                            endMinutes = it.getInt(it.getColumnIndexOrThrow("end_minutes")),
                            status = DayScheduleStatus.fromKey(
                                it.getString(it.getColumnIndexOrThrow("status"))
                            ),
                            colorArgb = it.getInt(it.getColumnIndexOrThrow("color_argb")),
                            difficulty = it.getFloat(it.getColumnIndexOrThrow("difficulty")),
                            allowPackages = decodePkgs(
                                it.getString(it.getColumnIndexOrThrow("allow_packages"))
                            ),
                            blockPackages = decodePkgs(
                                it.getString(it.getColumnIndexOrThrow("block_packages"))
                            ),
                            source = DayScheduleSource.fromKey(
                                it.getString(it.getColumnIndexOrThrow("source"))
                            ),
                            calendarEventId = if (it.isNull(it.getColumnIndexOrThrow("calendar_event_id"))) {
                                null
                            } else {
                                it.getLong(it.getColumnIndexOrThrow("calendar_event_id"))
                            },
                            rawNote = it.getString(it.getColumnIndexOrThrow("raw_note")),
                            titleKey = it.getString(it.getColumnIndexOrThrow("title_key")),
                            createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                            foodAwarded = it.getInt(it.getColumnIndexOrThrow("food_awarded")) == 1,
                            completionDegree = if (degIdx >= 0 && !it.isNull(degIdx)) {
                                it.getInt(degIdx)
                            } else null,
                            experienceNote = if (expIdx >= 0) it.getString(expIdx) else null
                        )
                    )
                }
            }
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "day_schedules.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE day_schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    date TEXT NOT NULL,
                    start_minutes INTEGER NOT NULL,
                    end_minutes INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    color_argb INTEGER NOT NULL,
                    difficulty REAL NOT NULL,
                    allow_packages TEXT NOT NULL,
                    block_packages TEXT NOT NULL,
                    source TEXT NOT NULL,
                    calendar_event_id INTEGER,
                    raw_note TEXT,
                    title_key TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    food_awarded INTEGER NOT NULL DEFAULT 0,
                    completion_degree INTEGER,
                    experience_note TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_day_sched_date ON day_schedules(date)")
            db.execSQL("CREATE INDEX idx_day_sched_title_key ON day_schedules(title_key)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE day_schedules ADD COLUMN completion_degree INTEGER")
                db.execSQL("ALTER TABLE day_schedules ADD COLUMN experience_note TEXT")
            }
        }
    }
}
