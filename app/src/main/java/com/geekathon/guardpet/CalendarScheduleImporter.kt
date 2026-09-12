package com.geekathon.guardpet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object CalendarScheduleImporter {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 导入今日系统日历事件。返回 (新增条数, 跳过已有, 错误信息)。
     */
    fun importToday(context: Context, today: LocalDate = LocalDate.now()): Triple<Int, Int, String?> {
        if (!hasPermission(context)) {
            return Triple(0, 0, "需要日历权限")
        }
        val zone = ZoneId.systemDefault()
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        var added = 0
        var skipped = 0
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(startMs.toString())
                    .appendPath(endMs.toString())
                    .build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val idxTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val idxBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val idxEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
                val idxAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(idxId)
                    if (DayScheduleStore.findByCalendarEvent(eventId, today.toString()) != null) {
                        skipped++
                        continue
                    }
                    val title = cursor.getString(idxTitle)?.trim().orEmpty().ifBlank { "日历事项" }
                    val begin = cursor.getLong(idxBegin)
                    val end = cursor.getLong(idxEnd)
                    val allDay = cursor.getInt(idxAllDay) == 1
                    val startMinutes: Int
                    val endMinutes: Int
                    if (allDay) {
                        startMinutes = 9 * 60
                        endMinutes = 10 * 60
                    } else {
                        val b = Instant.ofEpochMilli(begin).atZone(zone)
                        val e = Instant.ofEpochMilli(end).atZone(zone)
                        startMinutes = b.hour * 60 + b.minute
                        endMinutes = (e.hour * 60 + e.minute).coerceAtLeast(startMinutes + 30)
                    }
                    val key = DaySchedule.normalizeTitleKey(title)
                    val diff = DayScheduleStore.difficultyFor(key)
                    val (allow, block) = DayScheduleStore.typicalPackages(key)
                    DayScheduleStore.insert(
                        DaySchedule(
                            title = title,
                            date = today.toString(),
                            startMinutes = startMinutes,
                            endMinutes = endMinutes,
                            colorArgb = DayScheduleColor.randomPending(diff, title.hashCode()),
                            difficulty = diff,
                            allowPackages = allow,
                            blockPackages = block,
                            source = DayScheduleSource.CALENDAR,
                            calendarEventId = eventId,
                            titleKey = key
                        )
                    )
                    added++
                }
            }
            Triple(added, skipped, null)
        }.getOrElse { Triple(0, 0, it.message ?: "导入失败") }
    }
}
