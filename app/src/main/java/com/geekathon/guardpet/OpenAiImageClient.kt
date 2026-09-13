package com.geekathon.guardpet

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI Images API (ChatGPT / DALL·E).
 * Supports official api.openai.com and compatible proxy base URLs.
 */
class OpenAiImageClient(
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL
) {
    private val root = normalizeBaseUrl(baseUrl)

    fun generatePng(prompt: String): ByteArray {
        require(apiKey.isNotBlank()) { "OpenAI API Key 未配置" }
        val cleaned = prompt.trim().take(3800)
        require(cleaned.isNotEmpty()) { "empty image prompt" }

        var lastError: Exception? = null
        for (spec in MODEL_SPECS) {
            try {
                return generateOnce(cleaned, spec)
            } catch (e: Exception) {
                Log.e(TAG, "image model ${spec.model} failed: ${e.message}")
                lastError = e
            }
        }
        throw friendlyError(lastError)
    }

    private fun generateOnce(prompt: String, spec: ModelSpec): ByteArray {
        val endpoint = "$root/images/generations"
        val body = JSONObject()
            .put("model", spec.model)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", spec.size)
        if (spec.includeResponseFormat) body.put("response_format", "b64_json")
        if (spec.quality != null) body.put("quality", spec.quality)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
                it.write(body.toString())
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                val detail = extractApiError(text).ifBlank { text.take(220) }
                error("HTTP $code: $detail")
            }

            val rootJson = JSONObject(text)
            val data = rootJson.getJSONArray("data")
            require(data.length() > 0) { "OpenAI 未返回图片" }
            val item = data.getJSONObject(0)
            val b64 = item.optString("b64_json")
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                require(bytes.size > 2_000) { "image too small" }
                return bytes
            }
            val url = item.optString("url")
            require(url.isNotBlank()) { "OpenAI 未返回 b64 或 url" }
            return downloadBytes(url)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("download image http $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size > 2_000) { "image too small" }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private data class ModelSpec(
        val model: String,
        val size: String,
        val quality: String?,
        val includeResponseFormat: Boolean
    )

    companion object {
        private const val TAG = "ShoubanOpenAI"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val CONNECT_TIMEOUT_MS = 25_000
        private const val READ_TIMEOUT_MS = 180_000
        private val MODEL_SPECS = listOf(
            ModelSpec("dall-e-3", "1024x1024", "standard", true),
            ModelSpec("dall-e-3", "1024x1024", "hd", true),
            ModelSpec("gpt-image-1", "1024x1024", null, false)
        )

        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim().ifBlank { DEFAULT_BASE_URL }
            u = u.trimEnd('/')
            if (!u.endsWith("/v1")) {
                // allow https://xxx.com or https://xxx.com/v1
                if (u.endsWith("/v1/")) u = u.dropLast(1)
                else if (!u.contains("/v1")) u = "$u/v1"
            }
            return u
        }

        private fun extractApiError(text: String): String {
            return runCatching {
                val err = JSONObject(text).optJSONObject("error") ?: return@runCatching text.take(220)
                listOfNotNull(
                    err.optString("code").takeIf { it.isNotBlank() },
                    err.optString("type").takeIf { it.isNotBlank() },
                    err.optString("message").takeIf { it.isNotBlank() }
                ).joinToString(" · ")
            }.getOrDefault(text.take(220))
        }

        private fun friendlyError(cause: Exception?): IllegalStateException {
            val msg = cause?.message.orEmpty()
            val tip = when {
                msg.contains("UnknownHost", ignoreCase = true) ||
                    msg.contains("Unable to resolve", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("SSL", ignoreCase = true) ->
                    "无法连接 OpenAI（国内网络常被墙）。请在「陪伴偏好」填写可用的转发 Base URL，或开代理后再试。"
                msg.contains("401") || msg.contains("invalid_api_key", ignoreCase = true) ->
                    "OpenAI Key 无效，或与当前 Base URL 不匹配。"
                msg.contains("403") || msg.contains("billing", ignoreCase = true) ||
                    msg.contains("quota", ignoreCase = true) || msg.contains("insufficient", ignoreCase = true) ->
                    "OpenAI 账号欠费/无额度，或该 Key 无图片权限。"
                msg.contains("content_policy", ignoreCase = true) || msg.contains("safety", ignoreCase = true) ->
                    "提示词被内容安全策略拦截，请换更温和的描述。"
                msg.isBlank() -> "ChatGPT 图片生成失败"
                else -> msg.take(280)
            }
            return IllegalStateException(tip, cause)
        }
    }
}
