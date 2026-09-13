package com.geekathon.guardpet

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** DeepSeek Chat Completions client for prompt planning and vision QA. */
class DeepSeekClient(private val apiKey: String) {
    fun refineIdeaToImagePrompt(idea: String): String {
        val system = """
            You write English image-generation prompts for cute chibi desktop-pet mascots.
            Convert the user idea into ONE English prompt under 70 words.
            Put required species/colors/accessories FIRST and repeat them once.
            Style: full-body chibi mascot, centered, sticker style, soft shading,
            clean simple background, no text, no watermark, no extra people.
            Output ONLY the prompt text.
        """.trimIndent()
        return chatRaw(system, idea.trim(), temperature = 0.1)
    }

    fun describeImageAsPetPrompt(jpegOrPngBytes: ByteArray, mime: String, userHint: String?): String {
        val system = """
            Describe the subject as traits for a chibi desktop-pet mascot prompt.
            List species/character type, main colors, outfit, accessories, expression.
            Output a concise English trait summary under 70 words. No markdown.
        """.trimIndent()
        val hint = userHint?.trim().orEmpty().ifEmpty { "Extract desktop-pet traits from this image." }
        return chatVisionRaw(system, hint, jpegOrPngBytes, mime, temperature = 0.1)
    }

    fun chatRaw(system: String, user: String, temperature: Double = 0.3): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return complete(messages, preferVision = false, temperature = temperature)
    }

    fun chatVisionRaw(
        system: String,
        userText: String,
        imageBytes: ByteArray,
        mime: String,
        temperature: Double = 0.2
    ): String {
        val dataUrl = "data:$mime;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        // Vision model only accepts images in user messages.
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "$system\n\n$userText"))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", dataUrl)
                            .put("detail", "high")
                    )
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", content))
        return complete(messages, preferVision = true, temperature = temperature)
    }

    private fun complete(
        messages: JSONArray,
        preferVision: Boolean,
        temperature: Double
    ): String {
        val models = if (preferVision) {
            // Only this model accepts images; text models return 400 and used to silently skip QA.
            listOf("deepseek-v4-flash-vision-exp")
        } else {
            listOf("deepseek-v4-flash", "deepseek-chat")
        }
        var lastError: Exception? = null
        for (model in models) {
            try {
                return completeOnce(model, messages, temperature)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("DeepSeek request failed")
    }

    private fun completeOnce(model: String, messages: JSONArray, temperature: Double): String {
        require(apiKey.isNotBlank()) { "DeepSeek API Key 未配置" }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("stream", false)
            .toString()

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
            val text = stream?.use { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) error("DeepSeek HTTP $code: ${text.take(240)}")
            val root = JSONObject(text)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
            require(content.isNotEmpty()) { "DeepSeek 返回空内容" }
            return content
                .replace(Regex("^```(?:json|JSON)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
                .trim('"', '“', '”')
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
