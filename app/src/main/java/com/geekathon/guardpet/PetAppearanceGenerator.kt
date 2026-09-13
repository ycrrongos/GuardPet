package com.geekathon.guardpet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min
import kotlin.random.Random

/**
 * Turns guided prompt fields / reference photos into a desktop-pet PNG.
 * DeepSeek plans + vision-QA; ChatGPT (OpenAI Images) draws pixels.
 */
object PetAppearanceGenerator {
    private const val SIZE = 512
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 90_000

    data class Result(
        val pngBytes: ByteArray,
        val source: Source,
        val prompt: String,
        val refinedPrompt: String = prompt,
        val score: Int = 0,
        val attempts: Int = 1
    )

    enum class Source { QWEN, CHATGPT, ONLINE, LOCAL, PLANNED, UPLOAD }

    enum class GenerationMode {
        /** Local keyword draw: instant, stable trait match. */
        FAST,
        /** Qwen plans + Qwen image (T2I / I2I); falls back to planned local. */
        QUALITY
    }

    fun generateFromIdea(
        guide: PetAppearanceAgent.PromptGuide,
        credentials: PetAppearanceAgent.Credentials?,
        mode: GenerationMode = GenerationMode.FAST,
        onProgress: ((String) -> Unit)? = null
    ): Result {
        val cleaned = runCatching { guide.composeIdea() }.getOrElse { throw it }
        if (mode == GenerationMode.FAST) {
            return generateLocalStyledResult(guide, cleaned, onProgress, detailed = false)
        }
        return generateQualityResult(guide, cleaned, credentials, onProgress)
    }

    private fun generateLocalStyledResult(
        guide: PetAppearanceAgent.PromptGuide,
        cleaned: String,
        onProgress: ((String) -> Unit)?,
        detailed: Boolean,
        source: Source = Source.LOCAL,
        blueprint: PetAppearanceAgent.Blueprint? = null,
        score: Int = 8
    ): Result {
        onProgress?.invoke(if (detailed) "planned_local" else "understanding")
        val styled = if (blueprint != null) {
            generateStyledFromBlueprint(blueprint, guide, detailed)
        } else {
            generateStyledFromGuide(guide, cleaned, detailed)
        }
        val traits = blueprint?.mustKeep?.take(6)?.joinToString(" · ")
            ?: summarizeTraits(guide, cleaned)
        onProgress?.invoke("traits:$traits")
        onProgress?.invoke("local")
        onProgress?.invoke("done")
        return Result(
            pngBytes = prepareGeneratedBytes(styled),
            source = source,
            prompt = cleaned,
            refinedPrompt = traits.ifBlank { cleaned },
            score = score,
            attempts = 1
        )
    }

    private fun generateQualityResult(
        guide: PetAppearanceAgent.PromptGuide,
        cleaned: String,
        credentials: PetAppearanceAgent.Credentials?,
        onProgress: ((String) -> Unit)?
    ): Result {
        onProgress?.invoke("understanding")
        val blueprint = PetAppearanceAgent.planAppearance(guide, credentials, onProgress)
        onProgress?.invoke("traits:${blueprint.mustKeep.take(6).joinToString(" · ")}")

        if (credentials != null) {
            val online = PetAppearanceAgent.tryOnlinePng(
                blueprint, credentials, guide, onProgress
            )
            if (online != null) {
                onProgress?.invoke("done")
                val usedPrompt = PetAppearanceAgent.buildFaithfulChinesePrompt(guide, blueprint)
                return Result(
                    pngBytes = prepareGeneratedBytes(online.bytes),
                    source = when (online.source) {
                        "qwen" -> Source.QWEN
                        "openai" -> Source.CHATGPT
                        else -> Source.ONLINE
                    },
                    prompt = cleaned,
                    refinedPrompt = blueprint.mustKeep.joinToString(" · ")
                        .ifBlank { usedPrompt.take(120) },
                    score = 9,
                    attempts = 1
                )
            }
        }

        onProgress?.invoke("planned_local")
        return generateLocalStyledResult(
            guide = guide,
            cleaned = cleaned,
            onProgress = onProgress,
            detailed = true,
            source = Source.PLANNED,
            blueprint = blueprint,
            score = 8
        )
    }

    fun generateFromUpload(
        imageBytes: ByteArray,
        mime: String,
        guide: PetAppearanceAgent.PromptGuide,
        credentials: PetAppearanceAgent.Credentials?,
        mode: GenerationMode = GenerationMode.FAST,
        onProgress: ((String) -> Unit)? = null
    ): Result {
        require(imageBytes.isNotEmpty()) { "empty image" }
        val label = guide.composeIdeaOrEmpty().ifBlank { "参考图改绘桌宠" }
        if (mode == GenerationMode.FAST) {
            // 快速模式：直接用上传图做贴纸，不再忽略参考图像素
            val direct = applyUploadDirect(imageBytes)
            return direct.copy(prompt = label, refinedPrompt = label)
        }
        require(credentials != null && credentials.hasQwen()) {
            "按图生成需要千问 API Key。请长按「陪伴偏好」填写阿里云百炼 Key。"
        }
        onProgress?.invoke("looking")
        val i2i = runCatching {
            PetAppearanceAgent.tryReferenceImagePng(
                imageBytes, mime, guide, credentials, onProgress
            )
        }.getOrElse { cause ->
            throw IllegalStateException("参考图生成失败，请检查网络、API Key 或模型配置：${cause.message ?: "未知错误"}", cause)
        }
        if (i2i != null) {
            onProgress?.invoke("done")
            return Result(
                pngBytes = prepareGeneratedBytes(i2i.first),
                source = Source.QWEN,
                prompt = label,
                refinedPrompt = i2i.second.take(120),
                score = 9,
                attempts = 1
            )
        }
        throw IllegalStateException("参考图生成服务未返回图片")
    }

    fun generateStyledFromGuide(
        guide: PetAppearanceAgent.PromptGuide,
        cleanedIdea: String,
        detailed: Boolean = false
    ): ByteArray {
        val text = listOf(
            cleanedIdea,
            guide.subjectType,
            guide.artStyle,
            guide.coreFeatures,
            guide.freeIdea
        ).joinToString(" ")
        val seed = text.hashCode()
        val colors = StyledPetRenderer.colorsFromText(text, seed)
        val styleRaw = guide.artStyle.ifBlank { cleanedIdea }
        val accessories = StyledPetRenderer.parseAccessories(text)
        val species = guide.subjectType.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        } ?: cleanedIdea
        val spec = StyledPetRenderer.Spec(
            species = species,
            style = StyledPetRenderer.parseStyle(styleRaw + " " + guide.freeIdea),
            primary = colors.first,
            accent = colors.second,
            belly = colors.third,
            eyeColor = StyledPetRenderer.eyeFromText(text),
            scarfColor = StyledPetRenderer.scarfColorFromText(text),
            accessories = accessories,
            seed = seed,
            detailed = detailed
        )
        return StyledPetRenderer.render(spec)
    }

    fun generateStyledFromBlueprint(
        blueprint: PetAppearanceAgent.Blueprint,
        guide: PetAppearanceAgent.PromptGuide,
        detailed: Boolean
    ): ByteArray {
        val joined = listOf(
            blueprint.species,
            blueprint.colors.joinToString(" "),
            blueprint.accessories.joinToString(" "),
            blueprint.style,
            blueprint.mustKeep.joinToString(" "),
            guide.coreFeatures,
            guide.freeIdea,
            guide.subjectType,
            guide.artStyle
        ).joinToString(" ")
        val seed = joined.hashCode()
        val fallbackColors = StyledPetRenderer.colorsFromText(joined, seed)
        val primary = blueprint.colors.firstOrNull()
            ?.let { StyledPetRenderer.colorFromEnglishName(it, fallbackColors.first) }
            ?: fallbackColors.first
        val accent = blueprint.colors.getOrNull(1)
            ?.let { StyledPetRenderer.colorFromEnglishName(it, fallbackColors.second) }
            ?: fallbackColors.second
        val accessories = (
            StyledPetRenderer.parseAccessories(joined) +
                StyledPetRenderer.accessoriesFromEnglish(blueprint.accessories)
            ).toSet()
        val style = StyledPetRenderer.parseStyle(
            guide.artStyle + " " + blueprint.style + " " + guide.freeIdea
        )
        val species = guide.subjectType.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        } ?: blueprint.species
        val scarfFromBlueprint = blueprint.accessories
            .firstOrNull { it.contains("scarf", true) || it.contains("围巾") }
        val scarfColor = when {
            scarfFromBlueprint != null -> StyledPetRenderer.colorFromEnglishName(
                scarfFromBlueprint,
                StyledPetRenderer.scarfColorFromText(joined)
            )
            else -> StyledPetRenderer.scarfColorFromText(joined)
        }
        val spec = StyledPetRenderer.Spec(
            species = species,
            style = style,
            primary = primary,
            accent = accent,
            belly = fallbackColors.third,
            eyeColor = StyledPetRenderer.eyeFromText(joined),
            scarfColor = scarfColor,
            accessories = accessories,
            seed = seed,
            detailed = detailed
        )
        return StyledPetRenderer.render(spec)
    }

    private fun summarizeTraits(
        guide: PetAppearanceAgent.PromptGuide,
        cleaned: String
    ): String {
        val text = listOf(guide.subjectType, guide.artStyle, guide.coreFeatures, guide.freeIdea, cleaned)
            .joinToString(" ")
        val style = StyledPetRenderer.parseStyle(guide.artStyle + " " + guide.freeIdea)
        val accessories = StyledPetRenderer.parseAccessories(text)
        val species = guide.subjectType.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        } ?: "宠物"
        return buildList {
            add(species)
            add(style.name)
            addAll(accessories)
        }.joinToString(" · ")
    }

    private fun prepareGeneratedBytes(raw: ByteArray): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return raw
        val prepared = prepareForOverlay(bitmap)
        if (bitmap !== prepared && !bitmap.isRecycled) bitmap.recycle()
        val out = encodePng(prepared)
        if (!prepared.isRecycled) prepared.recycle()
        return out
    }
    fun applyUploadDirect(imageBytes: ByteArray): Result {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("无法解析上传图片")
        val prepared = prepareForOverlay(bitmap)
        if (bitmap !== prepared && !bitmap.isRecycled) bitmap.recycle()
        return try {
            Result(
                pngBytes = encodePng(prepared),
                source = Source.UPLOAD,
                prompt = "上传图片",
                refinedPrompt = "上传图片"
            )
        } finally {
            if (!prepared.isRecycled) prepared.recycle()
        }
    }

    @Deprecated("Use generateFromIdea with PromptGuide")
    fun generate(idea: String): Result =
        generateFromIdea(PetAppearanceAgent.PromptGuide(freeIdea = idea), null)

    /** Offline / China-network fallback: draw a chibi from the idea text. */
    fun generateLocalAppearance(idea: String): ByteArray {
        val raw = generateLocal(idea)
        return prepareGeneratedBytes(raw)
    }

    private fun generateOnline(promptBody: String, seedKey: String): ByteArray {
        val prompt = buildString {
            append("cute chibi desktop pet mascot, ")
            append(promptBody)
            append(", full body, centered, soft shading, sticker style, ")
            append("simple background, no text, no watermark, high quality")
        }
        val encoded = URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val seed = seedKey.hashCode().toLong().and(0x7fff_ffffL)
        val url = URL(
            "https://image.pollinations.ai/prompt/$encoded" +
                "?width=$SIZE&height=$SIZE&nologo=true&seed=$seed&model=flux"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "GuardPet/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("http $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size > 2_000) { "image too small" }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("decode failed")
            val prepared = prepareForOverlay(bitmap)
            return encodePng(prepared)
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareForOverlay(source: Bitmap): Bitmap {
        val scaled = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val scale = min(SIZE.toFloat() / source.width, SIZE.toFloat() / source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = (SIZE - width) / 2f
            val top = (SIZE - height) / 2f
            drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        forceTransparentBackground(scaled)
        return scaled
    }

    /**
     * 强制透明背景：从四边洪水填充抠除与边框相近的底色。
     * 模型通常吐出不透明图，必须靠后处理保证桌宠悬浮可用。
     */
    private fun forceTransparentBackground(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun dist(c1: Int, c2: Int): Int {
            return kotlin.math.abs(Color.red(c1) - Color.red(c2)) +
                kotlin.math.abs(Color.green(c1) - Color.green(c2)) +
                kotlin.math.abs(Color.blue(c1) - Color.blue(c2))
        }

        val corners = intArrayOf(
            pixels[1 * w + 1],
            pixels[1 * w + (w - 2)],
            pixels[(h - 2) * w + 1],
            pixels[(h - 2) * w + (w - 2)]
        )
        val avgR = corners.map { Color.red(it) }.average()
        val avgG = corners.map { Color.green(it) }.average()
        val avgB = corners.map { Color.blue(it) }.average()
        val bg = Color.rgb(avgR.toInt(), avgG.toInt(), avgB.toInt())
        val cornerSpread = corners.maxOf { dist(it, bg) }
        // 四角差太大说明不是干净底，仍尽量抠近白色/近纯色边
        val threshold = if (cornerSpread <= 50) 72 else 48

        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        fun canKey(c: Int): Boolean {
            if (Color.alpha(c) == 0) return true
            val d = dist(c, bg)
            if (d <= threshold) return true
            // 常见纯白/浅灰底板
            val bright = Color.red(c) + Color.green(c) + Color.blue(c)
            return bright >= 720 && dist(c, Color.WHITE) <= 55
        }

        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (visited[i]) return
            if (!canKey(pixels[i])) return
            visited[i] = true
            queue.addLast(i)
        }

        for (x in 0 until w) {
            seed(x, 0)
            seed(x, h - 1)
        }
        for (y in 0 until h) {
            seed(0, y)
            seed(w - 1, y)
        }

        val dirs = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            pixels[i] = Color.TRANSPARENT
            val x = i % w
            val y = i / w
            for (d in 0 until 4) {
                val nx = x + dirs[d * 2]
                val ny = y + dirs[d * 2 + 1]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val ni = ny * w + nx
                if (visited[ni]) continue
                if (!canKey(pixels[ni])) continue
                visited[ni] = true
                queue.addLast(ni)
            }
        }

        // 边缘半透明羽化，减少锯齿
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (Color.alpha(pixels[i]) == 0) continue
                var transparentNeighbors = 0
                if (Color.alpha(pixels[i - 1]) == 0) transparentNeighbors++
                if (Color.alpha(pixels[i + 1]) == 0) transparentNeighbors++
                if (Color.alpha(pixels[i - w]) == 0) transparentNeighbors++
                if (Color.alpha(pixels[i + w]) == 0) transparentNeighbors++
                if (transparentNeighbors >= 2 && canKey(pixels[i])) {
                    val c = pixels[i]
                    pixels[i] = Color.argb(90, Color.red(c), Color.green(c), Color.blue(c))
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun generateLocal(idea: String): ByteArray {
        val profile = IdeaProfile.parse(idea)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val cx = SIZE / 2f
        val cy = SIZE / 2f + 12f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.primary
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.accent
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(40, 35, 49, 45)
        }
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.eyeColor
        }
        val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(90, 255, 140, 150)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.argb(55, 30, 40, 36)
        }

        // Soft floor shadow
        canvas.drawOval(RectF(cx - 110f, cy + 150f, cx + 110f, cy + 178f), shadowPaint)

        // Ears / accessories by species
        when (profile.species) {
            Species.CAT, Species.FOX, Species.RABBIT -> {
                val earPath = Path()
                earPath.moveTo(cx - 88f, cy - 70f)
                earPath.lineTo(cx - 48f, cy - 170f)
                earPath.lineTo(cx - 10f, cy - 78f)
                earPath.close()
                canvas.drawPath(earPath, bodyPaint)
                canvas.drawPath(earPath, strokePaint)
                val earPath2 = Path()
                earPath2.moveTo(cx + 88f, cy - 70f)
                earPath2.lineTo(cx + 48f, cy - 170f)
                earPath2.lineTo(cx + 10f, cy - 78f)
                earPath2.close()
                canvas.drawPath(earPath2, bodyPaint)
                canvas.drawPath(earPath2, strokePaint)
                if (profile.species == Species.RABBIT) {
                    canvas.drawOval(RectF(cx - 58f, cy - 210f, cx - 28f, cy - 90f), bodyPaint)
                    canvas.drawOval(RectF(cx + 28f, cy - 210f, cx + 58f, cy - 90f), bodyPaint)
                }
                if (profile.species == Species.FOX) {
                    accentPaint.color = Color.rgb(255, 236, 220)
                    canvas.drawOval(RectF(cx - 72f, cy - 120f, cx - 42f, cy - 78f), accentPaint)
                    canvas.drawOval(RectF(cx + 42f, cy - 120f, cx + 72f, cy - 78f), accentPaint)
                }
            }
            Species.DOG, Species.BEAR -> {
                canvas.drawCircle(cx - 95f, cy - 95f, 38f, bodyPaint)
                canvas.drawCircle(cx + 95f, cy - 95f, 38f, bodyPaint)
                if (profile.species == Species.DOG) {
                    canvas.drawOval(RectF(cx - 120f, cy - 90f, cx - 70f, cy - 30f), bodyPaint)
                    canvas.drawOval(RectF(cx + 70f, cy - 90f, cx + 120f, cy - 30f), bodyPaint)
                }
            }
            Species.DRAGON -> {
                val horn = Path()
                horn.moveTo(cx - 40f, cy - 110f)
                horn.lineTo(cx - 20f, cy - 180f)
                horn.lineTo(cx, cy - 110f)
                horn.close()
                canvas.drawPath(horn, accentPaint)
                val horn2 = Path()
                horn2.moveTo(cx + 40f, cy - 110f)
                horn2.lineTo(cx + 20f, cy - 180f)
                horn2.lineTo(cx, cy - 110f)
                horn2.close()
                canvas.drawPath(horn2, accentPaint)
            }
            Species.BIRD, Species.PENGUIN -> {
                // Head crest / beak handled below
            }
            Species.ROBOT -> {
                canvas.drawRoundRect(RectF(cx - 18f, cy - 175f, cx + 18f, cy - 130f), 8f, 8f, accentPaint)
                canvas.drawCircle(cx, cy - 185f, 10f, accentPaint)
            }
            Species.FROG -> Unit
            Species.BLOB -> Unit
        }

        // Body glow
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy - 20f, 170f,
                intArrayOf(Color.argb(50, Color.red(profile.primary), Color.green(profile.primary), Color.blue(profile.primary)), Color.TRANSPARENT),
                floatArrayOf(0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy - 10f, 170f, glow)

        // Head + body
        canvas.drawCircle(cx, cy - 40f, 118f, bodyPaint)
        canvas.drawCircle(cx, cy - 40f, 118f, strokePaint)
        canvas.drawRoundRect(RectF(cx - 95f, cy + 20f, cx + 95f, cy + 155f), 70f, 70f, bodyPaint)
        canvas.drawRoundRect(RectF(cx - 95f, cy + 20f, cx + 95f, cy + 155f), 70f, 70f, strokePaint)

        // Belly
        val belly = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.belly
        }
        canvas.drawOval(RectF(cx - 58f, cy + 45f, cx + 58f, cy + 135f), belly)

        if (profile.hasScarf) {
            val scarf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = profile.scarfColor
            }
            canvas.drawOval(RectF(cx - 92f, cy + 8f, cx + 92f, cy + 52f), scarf)
            canvas.drawRoundRect(RectF(cx + 40f, cy + 28f, cx + 78f, cy + 110f), 16f, 16f, scarf)
            canvas.drawRoundRect(RectF(cx + 55f, cy + 40f, cx + 88f, cy + 125f), 14f, 14f, scarf)
        }

        // Face
        canvas.drawCircle(cx - 38f, cy - 48f, 14f, eyePaint)
        canvas.drawCircle(cx + 38f, cy - 48f, 14f, eyePaint)
        canvas.drawCircle(cx - 33f, cy - 53f, 5f, shinePaint)
        canvas.drawCircle(cx + 43f, cy - 53f, 5f, shinePaint)
        canvas.drawOval(RectF(cx - 70f, cy - 28f, cx - 48f, cy - 10f), blushPaint)
        canvas.drawOval(RectF(cx + 48f, cy - 28f, cx + 70f, cy - 10f), blushPaint)

        val smile = Path()
        smile.moveTo(cx - 22f, cy - 8f)
        smile.quadTo(cx, cy + 10f, cx + 22f, cy - 8f)
        val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(50, 58, 54)
        }
        canvas.drawPath(smile, smilePaint)

        when (profile.species) {
            Species.BIRD, Species.PENGUIN -> {
                val beak = Path()
                beak.moveTo(cx - 12f, cy - 18f)
                beak.lineTo(cx + 12f, cy - 18f)
                beak.lineTo(cx, cy + 8f)
                beak.close()
                canvas.drawPath(beak, accentPaint)
            }
            Species.CAT -> {
                // Whiskers
                val wPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.argb(120, 40, 50, 45)
                }
                canvas.drawLine(cx - 95f, cy - 12f, cx - 55f, cy - 5f, wPaint)
                canvas.drawLine(cx - 95f, cy + 4f, cx - 55f, cy + 2f, wPaint)
                canvas.drawLine(cx + 55f, cy - 5f, cx + 95f, cy - 12f, wPaint)
                canvas.drawLine(cx + 55f, cy + 2f, cx + 95f, cy + 4f, wPaint)
            }
            else -> Unit
        }

        // Feet
        canvas.drawOval(RectF(cx - 78f, cy + 140f, cx - 28f, cy + 168f), bodyPaint)
        canvas.drawOval(RectF(cx + 28f, cy + 140f, cx + 78f, cy + 168f), bodyPaint)

        // Accessory badge (non-scarf)
        if (profile.accessory && !profile.hasScarf) {
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    cx + 70f, cy + 30f, cx + 120f, cy + 80f,
                    profile.accent, Color.WHITE, Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx + 88f, cy + 48f, 22f, badge)
        }

        // Tiny label initial
        if (profile.accessory && !profile.hasScarf) {
            val initial = profile.label.take(1).ifEmpty { "P" }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255)
                textSize = 28f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawText(initial, cx + 88f, cy + 58f, textPaint)
        }

        return encodePng(bitmap).also { bitmap.recycle() }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private enum class Species {
        CAT, DOG, RABBIT, BEAR, FOX, DRAGON, BIRD, PENGUIN, FROG, ROBOT, BLOB
    }

    private data class IdeaProfile(
        val primary: Int,
        val accent: Int,
        val belly: Int,
        val species: Species,
        val accessory: Boolean,
        val hasScarf: Boolean,
        val scarfColor: Int,
        val eyeColor: Int,
        val label: String
    ) {
        companion object {
            fun parse(idea: String): IdeaProfile {
                val lower = idea.lowercase()
                val species = when {
                    listOf("猫", "喵", "cat", "kitten", "neko").any { it in lower } -> Species.CAT
                    listOf("狗", "汪", "dog", "puppy", "shiba").any { it in lower } -> Species.DOG
                    listOf("兔", "bunny", "rabbit").any { it in lower } -> Species.RABBIT
                    listOf("熊", "bear", "panda", "熊猫").any { it in lower } -> Species.BEAR
                    listOf("狐", "fox").any { it in lower } -> Species.FOX
                    listOf("龙", "dragon").any { it in lower } -> Species.DRAGON
                    listOf("鸟", "bird", "小鸡", "鸭").any { it in lower } -> Species.BIRD
                    listOf("企鹅", "penguin").any { it in lower } -> Species.PENGUIN
                    listOf("蛙", "frog", "青蛙").any { it in lower } -> Species.FROG
                    listOf("机器", "robot", "机甲").any { it in lower } -> Species.ROBOT
                    else -> Species.BLOB
                }

                val primary = when {
                    listOf("薄荷绿", "mint").any { it in lower } -> Color.rgb(152, 220, 180)
                    listOf("粉", "pink", "樱").any { it in lower } -> Color.rgb(244, 164, 188)
                    listOf("蓝", "blue", "青").any { it in lower } -> Color.rgb(120, 176, 220)
                    listOf("绿", "green", "翠").any { it in lower } -> Color.rgb(120, 186, 150)
                    listOf("紫", "purple", "violet").any { it in lower } -> Color.rgb(168, 140, 214)
                    listOf("橙", "orange", "橘").any { it in lower } -> Color.rgb(242, 168, 98)
                    listOf("黄", "yellow").any { it in lower } -> Color.rgb(240, 206, 96)
                    listOf("红", "red", "赤").any { it in lower } -> Color.rgb(224, 112, 112)
                    listOf("黑", "black", "暗").any { it in lower } -> Color.rgb(72, 78, 86)
                    listOf("白", "white", "米").any { it in lower } -> Color.rgb(236, 236, 230)
                    listOf("棕", "brown", "咖").any { it in lower } -> Color.rgb(176, 132, 96)
                    else -> paletteFromHash(idea.hashCode())
                }

                val accent = shiftColor(primary, 40)
                val belly = Color.rgb(
                    min(255, Color.red(primary) + 40),
                    min(255, Color.green(primary) + 40),
                    min(255, Color.blue(primary) + 36)
                )
                val hasScarf = listOf("围巾", "scarf").any { it in lower }
                val scarfColor = when {
                    listOf("红围巾", "红色围巾", "red scarf").any { it in lower } -> Color.rgb(220, 64, 72)
                    listOf("红", "red").any { it in lower } && hasScarf -> Color.rgb(220, 64, 72)
                    hasScarf -> Color.rgb(220, 64, 72)
                    else -> accent
                }
                val eyeColor = when {
                    listOf("金色", "金眼", "golden", "amber").any { it in lower } -> Color.rgb(218, 165, 32)
                    listOf("蓝眼", "blue eye").any { it in lower } -> Color.rgb(70, 130, 200)
                    listOf("绿眼", "green eye").any { it in lower } -> Color.rgb(60, 150, 90)
                    else -> Color.rgb(32, 40, 38)
                }
                val accessory = hasScarf || listOf("帽", "眼镜", "翅膀", "皇冠", "hat", "wing", "crown", "glasses")
                    .any { it in lower }
                val label = idea.trim().firstOrNull { !it.isWhitespace() }?.toString() ?: "P"
                return IdeaProfile(
                    primary, accent, belly, species, accessory, hasScarf, scarfColor, eyeColor, label
                )
            }

            private fun paletteFromHash(hash: Int): Int {
                val hues = intArrayOf(
                    Color.rgb(120, 186, 150),
                    Color.rgb(120, 176, 220),
                    Color.rgb(244, 164, 188),
                    Color.rgb(168, 140, 214),
                    Color.rgb(242, 168, 98),
                    Color.rgb(240, 206, 96)
                )
                return hues[kotlin.math.abs(hash) % hues.size]
            }

            private fun shiftColor(color: Int, delta: Int): Int = Color.rgb(
                (Color.red(color) + delta).coerceIn(0, 255),
                (Color.green(color) - delta / 2).coerceIn(0, 255),
                (Color.blue(color) + delta / 3).coerceIn(0, 255)
            )
        }
    }
}
