package com.geekathon.guardpet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 通义千问 / 万相 文生图与图生图（DashScope multimodal-generation）。
 * 文生图 fidelity=true 关闭随意扩写；图生图优先专用编辑模型并保留参考图身份。
 */
class QwenImageClient(private val apiKey: String) {

    fun textToImage(
        prompt: String,
        negativePrompt: String = DEFAULT_NEGATIVE,
        size: String = DEFAULT_SIZE,
        seed: Int? = null,
        promptExtend: Boolean = false,
        fidelity: Boolean = true
    ): ByteArray {
        require(apiKey.isNotBlank()) { "千问 API Key 未配置" }
        val cleaned = prompt.trim().take(2200)
        require(cleaned.isNotEmpty()) { "提示词为空" }
        val content = JSONArray().put(JSONObject().put("text", cleaned))
        return generateWithFallback(
            content = content,
            negativePrompt = negativePrompt,
            size = size,
            forEdit = false,
            seed = seed,
            promptExtend = promptExtend,
            fidelity = fidelity,
            preserveIdentity = false
        )
    }

    fun imageToImage(
        prompt: String,
        referenceJpegOrPng: ByteArray,
        mime: String = "image/jpeg",
        negativePrompt: String = DEFAULT_NEGATIVE_I2I,
        size: String? = null,
        seed: Int? = null,
        promptExtend: Boolean = false,
        fidelity: Boolean = true,
        preserveIdentity: Boolean = true
    ): ByteArray {
        require(apiKey.isNotBlank()) { "千问 API Key 未配置" }
        val cleaned = prompt.trim().take(800)
        require(cleaned.isNotEmpty()) { "提示词为空" }
        require(referenceJpegOrPng.isNotEmpty()) { "参考图为空" }
        val prepared = compressForApi(referenceJpegOrPng, highFidelity = preserveIdentity)
        // 编辑模型默认跟输入图比例；仅在调用方显式传入 size 时覆盖
        val resolvedSize = size
        val dataUrl = "data:${prepared.second};base64," +
            Base64.encodeToString(prepared.first, Base64.NO_WRAP)
        // 官方格式：先图后文；编辑模型以图为主，指令宜短
        val content = JSONArray()
            .put(JSONObject().put("image", dataUrl))
            .put(JSONObject().put("text", cleaned))
        Log.i(
            TAG,
            "I2I request: bytes=${prepared.first.size}, mime=${prepared.second}, " +
                "promptLen=${cleaned.length}, promptExtend=$promptExtend, preserve=$preserveIdentity"
        )
        return generateWithFallback(
            content = content,
            negativePrompt = negativePrompt,
            size = resolvedSize,
            forEdit = true,
            seed = seed,
            promptExtend = promptExtend,
            fidelity = fidelity,
            preserveIdentity = preserveIdentity
        )
    }

    private fun generateWithFallback(
        content: JSONArray,
        negativePrompt: String,
        size: String?,
        forEdit: Boolean,
        seed: Int?,
        promptExtend: Boolean,
        fidelity: Boolean,
        preserveIdentity: Boolean
    ): ByteArray {
        val models = if (forEdit) {
            // 只用不吃掉参考图的「真·图像编辑」模型。
            // qwen-image / qwen-image-plus 是纯文生图，放进回退链会成功出图但完全不看参考图。
            listOf(
                "qwen-image-edit-max",
                "qwen-image-edit-plus",
                "qwen-image-edit",
                "qwen-image-2.0-pro",
                "qwen-image-2.0",
                "qwen-image-3.0-pro",
                "qwen-image-3.0",
                "wan2.6-image"
            )
        } else {
            listOf("qwen-image-3.0", "qwen-image-plus", "qwen-image", "wan2.6-t2i", "wanx2.1-t2i-turbo")
        }
        var lastError: Exception? = null
        for (model in models) {
            try {
                val bytes = generateOnce(
                    model = model,
                    content = content,
                    negativePrompt = negativePrompt,
                    size = size,
                    seed = seed,
                    promptExtend = promptExtend,
                    fidelity = fidelity,
                    preserveIdentity = preserveIdentity,
                    forEdit = forEdit
                )
                Log.i(TAG, "image model succeeded: $model (forEdit=$forEdit)")
                return bytes
            } catch (e: Exception) {
                Log.w(TAG, "image model $model failed: ${e.message}")
                lastError = e
            }
        }
        throw friendlyError(lastError)
    }

    private fun generateOnce(
        model: String,
        content: JSONArray,
        negativePrompt: String,
        size: String?,
        seed: Int?,
        promptExtend: Boolean,
        fidelity: Boolean,
        preserveIdentity: Boolean,
        forEdit: Boolean
    ): ByteArray {
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content)
        )
        // 身份保留场景：关闭 prompt 扩写，避免扩写把「改绘」变成「按文字重画」
        val useExtend = when {
            forEdit && preserveIdentity -> false
            forEdit -> promptExtend
            fidelity -> false
            else -> promptExtend
        }
        val parameters = JSONObject()
            .put("n", 1)
            .put("prompt_extend", useExtend)
            .put("watermark", false)
            .put("negative_prompt", negativePrompt.take(500))
        // 多数编辑模型不传 size 时会贴近输入图比例，一致性更好
        if (!size.isNullOrBlank() && model != "qwen-image-edit") {
            parameters.put("size", size)
        }
        if (forEdit && useExtend && (model.contains("qwen-image-3.0") || model.contains("qwen-image-2.0"))) {
            parameters.put("prompt_extend_mode", "direct")
        }
        if (seed != null) {
            parameters.put("seed", seed.coerceIn(0, Int.MAX_VALUE))
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONObject().put("messages", messages))
            .put("parameters", parameters)
            .toString()

        val connection = (URL(GENERATION_ENDPOINT).openConnection() as HttpURLConnection).apply {
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
            if (code !in 200..299) {
                error("HTTP $code: ${extractError(text)}")
            }
            val imageUrl = extractImageUrl(text) ?: error("千问未返回图片 URL: ${text.take(200)}")
            return downloadImage(imageUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractImageUrl(raw: String): String? {
        val root = JSONObject(raw)
        if (root.has("code") && root.optString("code").isNotBlank() &&
            root.optString("code") != "Success"
        ) {
            error(root.optString("message").ifBlank { raw.take(200) })
        }
        root.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONArray("content")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val img = item.optString("image").ifBlank { item.optString("image_url") }
                    if (img.isNotBlank()) return img
                }
            }
        root.optJSONObject("output")?.optJSONArray("results")?.optJSONObject(0)?.let {
            val u = it.optString("url")
            if (u.isNotBlank()) return u
        }
        root.optJSONArray("data")?.optJSONObject(0)?.let {
            val u = it.optString("url")
            if (u.isNotBlank()) return u
        }
        return null
    }

    private fun downloadImage(urlSpec: String): ByteArray {
        val connection = (URL(urlSpec).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载图片 HTTP $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size > 2_000) { "图片过小" }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun compressForApi(
        bytes: ByteArray,
        highFidelity: Boolean = false
    ): Pair<ByteArray, String> {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes to "image/jpeg"
        val maxSide = if (highFidelity) 1536 else 1280
        val quality = if (highFidelity) 94 else 88
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == decoded.width && h == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (!decoded.isRecycled) decoded.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    /** 按参考图比例映射到 DashScope 常用合法尺寸，避免随意 w*h 被拒。 */
    private fun recommendSize(bytes: ByteArray): String {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return DEFAULT_SIZE
        val srcW = decoded.width.coerceAtLeast(1)
        val srcH = decoded.height.coerceAtLeast(1)
        if (!decoded.isRecycled) decoded.recycle()
        val aspect = srcW.toFloat() / srcH.toFloat()
        val candidates = listOf(
            "1024*1024" to 1f,
            "1024*768" to 1024f / 768f,
            "768*1024" to 768f / 1024f,
            "1280*720" to 1280f / 720f,
            "720*1280" to 720f / 1280f
        )
        return candidates.minByOrNull { kotlin.math.abs(it.second - aspect) }?.first ?: DEFAULT_SIZE
    }

    private fun extractError(raw: String): String = runCatching {
        val obj = JSONObject(raw)
        obj.optString("message")
            .ifBlank { obj.optJSONObject("error")?.optString("message").orEmpty() }
            .ifBlank { raw.take(240) }
    }.getOrDefault(raw.take(240))

    private fun friendlyError(cause: Exception?): IllegalStateException {
        val msg = cause?.message.orEmpty()
        return when {
            msg.contains("InvalidApiKey", true) || msg.contains("401") ->
                IllegalStateException("千问 API Key 无效，请在「陪伴偏好」中填写阿里云百炼 Key")
            msg.contains("Arrearage", true) || msg.contains("AccessDenied", true) ->
                IllegalStateException("千问账户余额不足或无模型权限，请到阿里云百炼开通 qwen-image-edit / qwen-image-2.0 等图像编辑模型")
            msg.contains("Unable to resolve host", true) || msg.contains("Failed to connect", true) ->
                IllegalStateException("无法连接千问服务，请检查网络后重试")
            else -> IllegalStateException(
                "千问参考图改绘失败：${msg.ifBlank { cause?.javaClass?.simpleName ?: "unknown" }}。" +
                    "请确认已开通 qwen-image-edit 或 qwen-image-2.0 图像编辑模型"
            )
        }
    }

    companion object {
        private const val TAG = "QwenImageClient"
        private const val GENERATION_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val DEFAULT_SIZE = "1024*1024"
        private const val DEFAULT_NEGATIVE =
            "低分辨率，模糊，畸形肢体，多余手指，文字水印，多只角色，真实照片背景杂乱，场景背景，不透明底板，渐变背景，血腥恐怖，换物种，错误颜色"
        private const val DEFAULT_NEGATIVE_I2I =
            "低分辨率，模糊，畸形肢体，多余手指，文字水印，logo，多只角色，真实杂乱背景，场景背景，" +
                "不透明底板，渐变背景，地面，房间，换脸，换物种，换发型，换主色，改变五官比例，变成另一个角色"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 180_000
    }
}
