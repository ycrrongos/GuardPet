package com.geekathon.guardpet

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 通义千问（DashScope OpenAI 兼容）文本 / 视觉，用于锁定桌宠特征。
 */
class QwenClient(private val apiKey: String) {
    fun chatRaw(system: String, user: String, temperature: Double = 0.2): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return complete(messages, vision = false, temperature = temperature)
    }

    fun chatVisionRaw(
        system: String,
        userText: String,
        imageBytes: ByteArray,
        mime: String,
        temperature: Double = 0.1
    ): String {
        val dataUrl = "data:$mime;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "$system\n\n$userText"))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl))
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", content))
        return complete(messages, vision = true, temperature = temperature)
    }

    fun describeImageAsPetPrompt(jpegOrPngBytes: ByteArray, mime: String, userHint: String?): String {
        val system = """
            你是桌宠形象分析助手。根据参考图提取可见特征，用于后续改绘。
            输出简洁中文：主体类型、主色、配饰、发型/五官、风格倾向。不超过80字。不要 markdown。
        """.trimIndent()
        val hint = userHint?.trim().orEmpty().ifEmpty { "请提取可改绘为桌宠贴纸的关键特征。" }
        return chatVisionRaw(system, hint, jpegOrPngBytes, mime, temperature = 0.1)
    }

    /**
     * 提取「身份锁定」描述：后续 I2I 必须保留的外形特征，避免生成成另一个角色。
     */
    fun describeImageIdentityLock(jpegOrPngBytes: ByteArray, mime: String, userHint: String?): String {
        val system = """
            你是角色身份锁定助手。只描述参考图里「换了就不能认出原角色」的特征。
            按固定顺序输出一行中文，用顿号分隔，不超过100字，不要 markdown：
            物种/人形、脸型五官、发型或耳角、主色与配色、标志性配饰、独特标记。
            不要写构图、背景、光影、情绪，不要发明图中没有的元素。
        """.trimIndent()
        val hint = buildString {
            append("请锁定这张图的可识别身份特征。")
            val extra = userHint?.trim().orEmpty()
            if (extra.isNotEmpty()) append("用户后续可能改动：").append(extra).append("（身份特征仍以图为准）。")
        }
        return chatVisionRaw(system, hint, jpegOrPngBytes, mime, temperature = 0.0)
    }

    private fun complete(messages: JSONArray, vision: Boolean, temperature: Double): String {
        require(apiKey.isNotBlank()) { "千问 API Key 未配置" }
        val models = if (vision) {
            listOf("qwen-vl-plus", "qwen3-vl-plus", "qwen-vl-max")
        } else {
            listOf("qwen-plus", "qwen-turbo", "qwen-max")
        }
        var lastError: Exception? = null
        for (model in models) {
            try {
                return completeOnce(model, messages, temperature)
            } catch (e: Exception) {
                Log.w(TAG, "model $model failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("千问请求失败")
    }

    private fun completeOnce(model: String, messages: JSONArray, temperature: Double): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("stream", false)
            .toString()
        val connection = (URL(CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) error("千问 HTTP $code: ${extractError(text)}")
            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
            require(content.isNotEmpty()) { "千问返回空内容" }
            return content
                .replace(Regex("^```(?:json|JSON)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
                .trim('"', '“', '”')
        } finally {
            connection.disconnect()
        }
    }

    private fun extractError(raw: String): String {
        return runCatching {
            val obj = JSONObject(raw)
            obj.optString("message")
                .ifBlank { obj.optJSONObject("error")?.optString("message").orEmpty() }
                .ifBlank { raw.take(220) }
        }.getOrDefault(raw.take(220))
    }

    companion object {
        private const val TAG = "QwenClient"
        private const val CHAT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
