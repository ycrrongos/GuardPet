package com.geekathon.guardpet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDate

enum class FlashNoteCategory(val key: String, val labelRes: Int) {
    SCHEDULE("schedule", R.string.category_schedule),
    IDEA("idea", R.string.category_idea),
    DIARY("diary", R.string.category_diary),
    TODO("todo", R.string.category_todo),
    OTHER("other", R.string.category_other);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

object FlashNoteColor {
    val palette = intArrayOf(
        0xFF6B8FDB.toInt(),
        0xFFE3926C.toInt(),
        0xFFC486C8.toInt(),
        0xFF5AA8B5.toInt(),
        0xFFE07A62.toInt(),
        0xFF8896D8.toInt()
    )

    fun argb(index: Int): Int {
        if (palette.isEmpty()) return 0xFF6B8FDB.toInt()
        val size = palette.size
        return palette[((index % size) + size) % size]
    }

    fun next(index: Int) = (index + 1) % palette.size
}

data class FlashNote(
    val id: Long = 0,
    val text: String,
    val category: FlashNoteCategory,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val scheduleDate: String? = null,
    val color: Int = 0,
    val audioPath: String? = null
) {
    val hasAudio: Boolean
        get() = !audioPath.isNullOrBlank() && File(audioPath).let { it.isFile && it.length() > 64L }
}

object FlashNoteStore {
    private lateinit var helper: Helper
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        helper = Helper(context.applicationContext)
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return {
            listeners.remove(listener)
        }
    }

    fun insert(note: FlashNote): Long {
        val id = helper.writableDatabase.insert("flash_notes", null, values(note, includeId = false))
        notifyChanged()
        return id
    }

    fun update(note: FlashNote) {
        helper.writableDatabase.update(
            "flash_notes",
            values(note, includeId = false),
            "id=?",
            arrayOf(note.id.toString())
        )
        notifyChanged()
    }

    fun delete(id: Long) {
        val audio = byId(id)?.audioPath
        helper.writableDatabase.delete("flash_notes", "id=?", arrayOf(id.toString()))
        audio?.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } }
        notifyChanged()
    }

    fun byId(id: Long): FlashNote? =
        query("id=?", arrayOf(id.toString())).firstOrNull()

    fun all(): List<FlashNote> = query(null, null)

    fun todaySchedules(today: LocalDate = LocalDate.now()): List<FlashNote> =
        query(
            "category=? AND schedule_date=?",
            arrayOf(FlashNoteCategory.SCHEDULE.key, today.toString())
        )

    private fun values(note: FlashNote, includeId: Boolean) = ContentValues().apply {
        if (includeId && note.id > 0) put("id", note.id)
        put("text", note.text)
        put("category", note.category.key)
        put("source", note.source)
        put("created_at", note.createdAt)
        put("schedule_date", note.scheduleDate)
        put("color", note.color)
        put("audio_path", note.audioPath)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun query(selection: String?, args: Array<String>?): List<FlashNote> {
        val cursor = helper.readableDatabase.query(
            "flash_notes",
            null,
            selection,
            args,
            null,
            null,
            "created_at DESC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        FlashNote(
                            id = it.getLong(it.getColumnIndexOrThrow("id")),
                            text = it.getString(it.getColumnIndexOrThrow("text")),
                            category = FlashNoteCategory.fromKey(
                                it.getString(it.getColumnIndexOrThrow("category"))
                            ),
                            source = it.getString(it.getColumnIndexOrThrow("source")),
                            createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                            scheduleDate = it.getString(it.getColumnIndexOrThrow("schedule_date")),
                            color = it.getInt(it.getColumnIndexOrThrow("color")),
                            audioPath = it.getString(it.getColumnIndexOrThrow("audio_path"))
                        )
                    )
                }
            }
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "flash_notes.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE flash_notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    text TEXT NOT NULL,
                    category TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    schedule_date TEXT,
                    color INTEGER NOT NULL DEFAULT 0,
                    audio_path TEXT
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE flash_notes ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flash_notes ADD COLUMN audio_path TEXT")
            }
        }
    }
}
