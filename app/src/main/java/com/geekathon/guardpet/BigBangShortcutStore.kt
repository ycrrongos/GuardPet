package com.geekathon.guardpet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BigBangShortcut(
    val id: String,
    val title: String,
    val prompt: String
)

/** 提取文字词块页上的 AI 快捷整理。未保存过时用内置三条。 */
object BigBangShortcutStore {
    private const val PREFS = "bigbang_shortcuts"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<BigBangShortcut> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return defaults(context)
        return decode(raw) ?: defaults(context)
    }

    fun save(context: Context, items: List<BigBangShortcut>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, encode(items))
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    fun defaults(context: Context): List<BigBangShortcut> = listOf(
        BigBangShortcut("note", context.getString(R.string.bigbang_ai_note), BigBangAi.NOTE_PROMPT),
        BigBangShortcut("clean", context.getString(R.string.bigbang_ai_clean), BigBangAi.CLEAN_PROMPT),
        BigBangShortcut("translate", context.getString(R.string.bigbang_ai_translate), BigBangAi.TRANSLATE_PROMPT)
    )

    fun newItem(context: Context): BigBangShortcut = BigBangShortcut(
        id = UUID.randomUUID().toString().take(8),
        title = context.getString(R.string.bigbang_shortcut_new_title),
        prompt = context.getString(R.string.bigbang_ai_custom_default)
    )

    private fun encode(items: List<BigBangShortcut>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("prompt", item.prompt)
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<BigBangShortcut>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString().take(8) }
                add(
                    BigBangShortcut(
                        id = id,
                        title = obj.optString("title"),
                        prompt = obj.optString("prompt")
                    )
                )
            }
        }
    }.getOrNull()
}
