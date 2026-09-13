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
import kotlin.math.min
import kotlin.random.Random

/**
 * Deterministic styled chibi renderer.
 * Each [ArtStyle] uses a clearly different drawing language so style choice is obvious.
 */
object StyledPetRenderer {
    private const val SIZE = 512

    enum class ArtStyle {
        ANIME_STICKER, WATERCOLOR, PIXEL, MINIMAL, CYBER, SEMI_REAL, CLAY, CUSTOM
    }

    data class Spec(
        val species: String,
        val style: ArtStyle,
        val primary: Int,
        val accent: Int,
        val belly: Int,
        val eyeColor: Int,
        val scarfColor: Int,
        val accessories: Set<String>,
        val seed: Int,
        /** Quality path: extra shading / labels / denser accessories. */
        val detailed: Boolean = false
    )

    fun render(spec: Spec): ByteArray {
        val hi = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(hi)
        canvas.drawColor(Color.TRANSPARENT)
        when (spec.style) {
            ArtStyle.PIXEL -> renderPixel(canvas, spec)
            ArtStyle.MINIMAL -> renderMinimal(canvas, spec)
            ArtStyle.WATERCOLOR -> renderWatercolor(canvas, spec)
            ArtStyle.CYBER -> renderCyber(canvas, spec)
            ArtStyle.SEMI_REAL -> renderSemiReal(canvas, spec)
            ArtStyle.CLAY -> renderClay(canvas, spec)
            ArtStyle.CUSTOM, ArtStyle.ANIME_STICKER -> renderAnimeSticker(canvas, spec)
        }
        return encode(hi).also { hi.recycle() }
    }

    fun parseStyle(raw: String): ArtStyle {
        val t = raw.lowercase()
        return when {
            listOf("像素", "pixel").any { it in t } -> ArtStyle.PIXEL
            listOf("水彩", "watercolor").any { it in t } -> ArtStyle.WATERCOLOR
            listOf("极简", "minimal", "clean").any { it in t } -> ArtStyle.MINIMAL
            listOf("赛博", "cyber").any { it in t } -> ArtStyle.CYBER
            listOf("写实", "realistic", "semi").any { it in t } -> ArtStyle.SEMI_REAL
            listOf("粘土", "clay", "手办", "figure").any { it in t } -> ArtStyle.CLAY
            listOf("贴纸", "二次元", "anime", "q版", "chibi").any { it in t } -> ArtStyle.ANIME_STICKER
            listOf("自定义", "custom").any { it in t } -> ArtStyle.CUSTOM
            else -> ArtStyle.ANIME_STICKER
        }
    }

    fun parseAccessories(text: String): Set<String> {
        val lower = text.lowercase()
        return buildSet {
            if (listOf("围巾", "scarf").any { it in lower }) add("scarf")
            if (listOf("帽", "hat", "beanie").any { it in lower }) add("hat")
            if (listOf("眼镜", "glasses").any { it in lower }) add("glasses")
            if (listOf("蝴蝶结", "bow").any { it in lower }) add("bow")
            if (listOf("皇冠", "crown").any { it in lower }) add("crown")
            if (listOf("翅膀", "wing").any { it in lower }) add("wings")
            if (listOf("耳机", "headset", "headphones").any { it in lower }) add("headphones")
            if (listOf("铃铛", "bell").any { it in lower }) add("bell")
        }
    }

    /** Prefer body/fur colors; ignore scarf-only red/blue so accessories don't steal body tint. */
    fun colorsFromText(text: String, seed: Int): Triple<Int, Int, Int> {
        val lower = text.lowercase()
        val hasScarf = listOf("围巾", "scarf").any { it in lower }
        val primary = when {
            listOf("薄荷绿", "mint").any { it in lower } -> Color.rgb(152, 220, 180)
            listOf("粉", "pink", "樱").any { it in lower } -> Color.rgb(244, 164, 188)
            listOf("天蓝", "sky").any { it in lower } -> Color.rgb(140, 200, 240)
            listOf("蓝", "blue", "青").any { it in lower } &&
                !(hasScarf && !listOf("蓝毛", "蓝色", "蓝猫", "蓝狗", "blue fur", "blue cat").any { it in lower }) ->
                Color.rgb(120, 176, 220)
            listOf("绿", "green", "翠").any { it in lower } -> Color.rgb(120, 186, 150)
            listOf("紫", "purple", "violet").any { it in lower } -> Color.rgb(168, 140, 214)
            listOf("橙", "orange", "橘").any { it in lower } -> Color.rgb(242, 168, 98)
            listOf("黄", "yellow").any { it in lower } -> Color.rgb(240, 206, 96)
            listOf("红毛", "红色", "红猫", "赤", "crimson").any { it in lower } ||
                (listOf("红", "red").any { it in lower } && !hasScarf) ->
                Color.rgb(224, 112, 112)
            listOf("黑", "black", "暗").any { it in lower } -> Color.rgb(72, 78, 86)
            listOf("白", "white", "米").any { it in lower } -> Color.rgb(236, 236, 230)
            listOf("棕", "brown", "咖").any { it in lower } -> Color.rgb(176, 132, 96)
            listOf("灰", "gray", "grey").any { it in lower } -> Color.rgb(150, 156, 164)
            else -> {
                val r = 110 + (seed ushr 1 and 0x5f)
                val g = 130 + (seed ushr 8 and 0x4f)
                val b = 150 + (seed ushr 16 and 0x3f)
                Color.rgb(r.coerceIn(80, 230), g.coerceIn(90, 230), b.coerceIn(100, 230))
            }
        }
        val accent = Color.rgb(
            min(255, Color.red(primary) + 35),
            min(255, Color.green(primary) + 25),
            min(255, Color.blue(primary) + 20)
        )
        val belly = Color.rgb(
            min(255, Color.red(primary) + 45),
            min(255, Color.green(primary) + 45),
            min(255, Color.blue(primary) + 40)
        )
        return Triple(primary, accent, belly)
    }

    fun colorFromEnglishName(name: String, fallback: Int): Int {
        val t = name.lowercase()
        return when {
            listOf("mint", "薄荷绿").any { it in t } -> Color.rgb(152, 220, 180)
            listOf("pink", "粉").any { it in t } -> Color.rgb(244, 164, 188)
            listOf("blue", "蓝").any { it in t } -> Color.rgb(120, 176, 220)
            listOf("green", "绿").any { it in t } -> Color.rgb(120, 186, 150)
            listOf("purple", "紫").any { it in t } -> Color.rgb(168, 140, 214)
            listOf("orange", "橙", "橘").any { it in t } -> Color.rgb(242, 168, 98)
            listOf("yellow", "黄", "gold").any { it in t } -> Color.rgb(240, 206, 96)
            listOf("red", "红", "crimson").any { it in t } -> Color.rgb(224, 112, 112)
            listOf("black", "黑").any { it in t } -> Color.rgb(72, 78, 86)
            listOf("white", "白", "cream").any { it in t } -> Color.rgb(236, 236, 230)
            listOf("brown", "棕").any { it in t } -> Color.rgb(176, 132, 96)
            listOf("gray", "grey", "灰").any { it in t } -> Color.rgb(150, 156, 164)
            else -> fallback
        }
    }

    fun eyeFromText(text: String): Int {
        val lower = text.lowercase()
        return when {
            listOf("金色", "金眼", "golden", "amber", "gold eye").any { it in lower } ->
                Color.rgb(218, 165, 32)
            listOf("蓝眼", "blue eye", "blue eyes").any { it in lower } -> Color.rgb(70, 130, 200)
            listOf("绿眼", "green eye", "green eyes").any { it in lower } -> Color.rgb(60, 150, 90)
            listOf("红眼", "red eye", "red eyes").any { it in lower } -> Color.rgb(200, 60, 60)
            listOf("紫眼", "purple eye").any { it in lower } -> Color.rgb(140, 90, 200)
            else -> Color.rgb(32, 40, 38)
        }
    }

    fun scarfColorFromText(text: String): Int {
        val lower = text.lowercase()
        return when {
            listOf("红围巾", "红色围巾", "red scarf").any { it in lower } -> Color.rgb(220, 64, 72)
            listOf("蓝围巾", "蓝色围巾", "blue scarf").any { it in lower } -> Color.rgb(70, 120, 210)
            listOf("粉围巾", "粉色围巾", "pink scarf").any { it in lower } -> Color.rgb(240, 140, 170)
            listOf("绿围巾", "green scarf").any { it in lower } -> Color.rgb(60, 170, 110)
            listOf("黄围巾", "yellow scarf").any { it in lower } -> Color.rgb(240, 200, 70)
            listOf("紫围巾", "purple scarf").any { it in lower } -> Color.rgb(150, 100, 200)
            listOf("围巾", "scarf").any { it in lower } && listOf("红", "red").any { it in lower } ->
                Color.rgb(220, 64, 72)
            listOf("围巾", "scarf").any { it in lower } && listOf("蓝", "blue").any { it in lower } ->
                Color.rgb(70, 120, 210)
            else -> Color.rgb(220, 64, 72)
        }
    }

    fun accessoriesFromEnglish(list: List<String>): Set<String> {
        val joined = list.joinToString(" ").lowercase()
        return parseAccessories(joined + " " + list.joinToString(" "))
    }

    // region Styles — intentionally different drawing languages

    private fun renderAnimeSticker(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 8f
        // White sticker plate
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawOval(
            RectF(cx - 160f, cy + 165f, cx + 160f, cy + 195f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }
        )
        canvas.drawRoundRect(RectF(cx - 190f, cy - 200f, cx + 190f, cy + 200f), 48f, 48f, plate)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            color = Color.rgb(28, 28, 32)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.primary }
        drawSpeciesSilhouette(canvas, spec, cx, cy, fill, ink, earBoost = 1.15f)
        // Huge anime eyes
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.eyeColor }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx - 42f, cy - 40f, 28f, white)
        canvas.drawCircle(cx + 42f, cy - 40f, 28f, white)
        canvas.drawCircle(cx - 42f, cy - 40f, 20f, eye)
        canvas.drawCircle(cx + 42f, cy - 40f, 20f, eye)
        canvas.drawCircle(cx - 34f, cy - 50f, 8f, white)
        canvas.drawCircle(cx + 50f, cy - 50f, 8f, white)
        canvas.drawCircle(cx - 48f, cy - 30f, 4f, white)
        // Blush + smile
        val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 120, 140) }
        canvas.drawOval(RectF(cx - 85f, cy - 10f, cx - 50f, cy + 12f), blush)
        canvas.drawOval(RectF(cx + 50f, cy - 10f, cx + 85f, cy + 12f), blush)
        drawSmile(canvas, cx, cy + 8f, 8f, Color.rgb(40, 40, 48))
        drawBelly(canvas, spec, cx, cy, flat = true)
        drawAccessories(canvas, spec, cx, cy, bold = true)
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    private fun renderClay(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 16f
        // Soft floor shadow
        canvas.drawOval(
            RectF(cx - 100f, cy + 155f, cx + 100f, cy + 185f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 40, 50, 60) }
        )
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - 35f, cy - 90f, 210f,
                intArrayOf(lighten(spec.primary, 50), spec.primary, darken(spec.primary, 35)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(40, 255, 255, 255)
        }
        drawSpeciesSilhouette(canvas, spec, cx, cy, body, soft, earBoost = 1f)
        // Specular blob
        canvas.drawCircle(
            cx - 55f, cy - 95f, 36f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx - 55f, cy - 95f, 36f,
                    intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
        )
        drawBelly(canvas, spec, cx, cy, flat = false)
        // Smaller soft eyes
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.eyeColor }
        canvas.drawCircle(cx - 36f, cy - 42f, 14f, eye)
        canvas.drawCircle(cx + 36f, cy - 42f, 14f, eye)
        canvas.drawCircle(cx - 31f, cy - 47f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawSmile(canvas, cx, cy + 2f, 5f, Color.argb(160, 60, 50, 50))
        drawAccessories(canvas, spec, cx, cy, bold = false)
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    private fun renderSemiReal(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 12f
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy - 40f, 200f,
                intArrayOf(lighten(spec.primary, 25), spec.primary, darken(spec.primary, 45)),
                floatArrayOf(0.15f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            color = Color.argb(90, 30, 35, 40)
        }
        drawSpeciesSilhouette(canvas, spec, cx, cy, body, outline, earBoost = 1.05f)
        // Fur strokes
        val fur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(50, 255, 255, 255)
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0..7) {
            val a = -40f + i * 12f
            canvas.drawArc(RectF(cx - 95f, cy - 100f, cx + 95f, cy + 30f), a, 18f, false, fur)
        }
        drawBelly(canvas, spec, cx, cy, flat = false)
        // Smaller almond eyes + nose
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.eyeColor }
        canvas.drawOval(RectF(cx - 48f, cy - 52f, cx - 24f, cy - 34f), eye)
        canvas.drawOval(RectF(cx + 24f, cy - 52f, cx + 48f, cy - 34f), eye)
        canvas.drawCircle(cx, cy - 18f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(spec.primary, 60) })
        drawSmile(canvas, cx, cy + 4f, 4f, Color.rgb(60, 50, 48))
        drawAccessories(canvas, spec, cx, cy, bold = false, fringe = true)
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    private fun renderWatercolor(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 10f
        val rnd = Random(spec.seed)
        // Paper washes
        repeat(6) {
            val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    28 + rnd.nextInt(25),
                    Color.red(spec.primary),
                    Color.green(spec.primary),
                    Color.blue(spec.primary)
                )
            }
            canvas.drawCircle(
                cx + rnd.nextInt(160) - 80f,
                cy + rnd.nextInt(160) - 80f,
                70f + rnd.nextInt(80),
                wash
            )
        }
        // Soft body without hard outline
        fun blob(x: Float, y: Float, r: Float, alpha: Int = 160) {
            canvas.drawCircle(
                x, y, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(
                        alpha,
                        Color.red(spec.primary),
                        Color.green(spec.primary),
                        Color.blue(spec.primary)
                    )
                }
            )
        }
        blob(cx, cy - 35f, 118f, 150)
        blob(cx, cy + 55f, 95f, 140)
        blob(cx - 70f, cy - 110f, 36f, 130)
        blob(cx + 70f, cy - 110f, 36f, 130)
        blob(cx - 55f, cy + 145f, 28f, 130)
        blob(cx + 55f, cy + 145f, 28f, 130)
        // Soft belly wash
        canvas.drawOval(
            RectF(cx - 50f, cy + 40f, cx + 50f, cy + 120f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, Color.red(spec.belly), Color.green(spec.belly), Color.blue(spec.belly))
            }
        )
        // Ink-ish eyes (dry brush feel via layered dots)
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, Color.red(spec.eyeColor), Color.green(spec.eyeColor), Color.blue(spec.eyeColor)) }
        canvas.drawCircle(cx - 36f, cy - 45f, 12f, eye)
        canvas.drawCircle(cx + 36f, cy - 45f, 12f, eye)
        drawAccessories(canvas, spec, cx, cy, bold = false, watercolor = true)
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    private fun renderCyber(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 8f
        // Dark HUD backdrop
        canvas.drawRoundRect(
            RectF(40f, 40f, SIZE - 40f, SIZE - 40f),
            24f, 24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 16, 28) }
        )
        val neon = Color.rgb(80, 255, 230)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 80, 255, 230)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 1..6) {
            val y = 60f + i * 60f
            canvas.drawLine(60f, y, SIZE - 60f, y, grid)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = neon
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(
                180,
                Color.red(spec.primary) / 2 + 20,
                Color.green(spec.primary) / 2 + 40,
                Color.blue(spec.primary) / 2 + 90
            )
        }
        drawSpeciesSilhouette(canvas, spec, cx, cy, fill, outline, earBoost = 1.1f)
        // Neon eyes
        val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = neon }
        canvas.drawCircle(cx - 38f, cy - 42f, 12f, eye)
        canvas.drawCircle(cx + 38f, cy - 42f, 12f, eye)
        canvas.drawCircle(cx - 38f, cy - 42f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        canvas.drawCircle(cx + 38f, cy - 42f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        // Corner brackets
        val br = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = neon
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(56f, 56f, 100f, 56f, br)
        canvas.drawLine(56f, 56f, 56f, 100f, br)
        canvas.drawLine(SIZE - 56f, 56f, SIZE - 100f, 56f, br)
        canvas.drawLine(SIZE - 56f, 56f, SIZE - 56f, 100f, br)
        drawAccessories(canvas, spec, cx, cy, bold = true, neon = true)
        if (spec.detailed) drawTraitChip(canvas, spec, neonChip = true)
    }

    private fun renderMinimal(canvas: Canvas, spec: Spec) {
        val cx = SIZE / 2f
        val cy = SIZE / 2f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.rgb(36, 40, 44)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val pale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, Color.red(spec.primary), Color.green(spec.primary), Color.blue(spec.primary))
        }
        canvas.drawCircle(cx, cy - 30f, 88f, pale)
        canvas.drawCircle(cx, cy - 30f, 88f, line)
        canvas.drawRoundRect(RectF(cx - 68f, cy + 30f, cx + 68f, cy + 145f), 36f, 36f, pale)
        canvas.drawRoundRect(RectF(cx - 68f, cy + 30f, cx + 68f, cy + 145f), 36f, 36f, line)
        // Minimal ears as strokes only
        val s = spec.species.lowercase()
        if (listOf("cat", "fox", "rabbit", "猫", "狐", "兔").any { it in s }) {
            canvas.drawLine(cx - 70f, cy - 90f, cx - 40f, cy - 150f, line)
            canvas.drawLine(cx - 40f, cy - 150f, cx - 10f, cy - 95f, line)
            canvas.drawLine(cx + 70f, cy - 90f, cx + 40f, cy - 150f, line)
            canvas.drawLine(cx + 40f, cy - 150f, cx + 10f, cy - 95f, line)
        }
        canvas.drawCircle(cx - 28f, cy - 40f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.eyeColor })
        canvas.drawCircle(cx + 28f, cy - 40f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.eyeColor })
        if ("scarf" in spec.accessories) {
            val scarf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 10f
                color = spec.scarfColor
            }
            canvas.drawArc(RectF(cx - 70f, cy + 10f, cx + 70f, cy + 70f), 200f, 140f, false, scarf)
            canvas.drawLine(cx + 40f, cy + 50f, cx + 55f, cy + 110f, scarf)
        }
        drawAccessories(canvas, spec, cx, cy + 12f, bold = false, minimal = true)
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    private fun renderPixel(canvas: Canvas, spec: Spec) {
        val small = 48
        val tiny = Bitmap.createBitmap(small, small, Bitmap.Config.ARGB_8888)
        val t = Canvas(tiny)
        t.drawColor(Color.TRANSPARENT)
        val cx = small / 2f
        val cy = small / 2f + 2f
        fun px(paint: Paint, x: Int, y: Int, w: Int = 1, h: Int = 1) {
            paint.isAntiAlias = false
            t.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        }
        val body = Paint().apply { color = spec.primary; isAntiAlias = false }
        val dark = Paint().apply { color = darken(spec.primary, 40); isAntiAlias = false }
        val eye = Paint().apply { color = spec.eyeColor; isAntiAlias = false }
        val belly = Paint().apply { color = spec.belly; isAntiAlias = false }
        // Blocky body
        px(body, 14, 10, 20, 16)
        px(body, 16, 26, 16, 14)
        px(dark, 14, 24, 20, 2)
        px(belly, 18, 28, 12, 8)
        // Ears
        val s = spec.species.lowercase()
        if (listOf("cat", "fox", "rabbit", "猫", "狐", "兔").any { it in s }) {
            px(body, 14, 4, 5, 7)
            px(body, 29, 4, 5, 7)
        } else if (listOf("dog", "bear", "狗", "熊", "熊猫").any { it in s }) {
            px(body, 12, 8, 5, 5)
            px(body, 31, 8, 5, 5)
        }
        px(eye, 18, 15, 3, 3)
        px(eye, 27, 15, 3, 3)
        if ("scarf" in spec.accessories) {
            val sc = Paint().apply { color = spec.scarfColor; isAntiAlias = false }
            px(sc, 15, 25, 18, 3)
            px(sc, 30, 27, 4, 8)
        }
        if ("hat" in spec.accessories) {
            val h = Paint().apply { color = spec.accent; isAntiAlias = false }
            px(h, 16, 5, 16, 4)
            px(h, 18, 2, 12, 4)
        }
        if ("crown" in spec.accessories) {
            val c = Paint().apply { color = Color.rgb(240, 200, 70); isAntiAlias = false }
            px(c, 18, 3, 12, 3)
            px(c, 20, 1, 2, 3)
            px(c, 24, 0, 2, 4)
            px(c, 28, 1, 2, 3)
        }
        // Checkerboard border to sell "pixel"
        val border = Paint().apply { color = Color.rgb(30, 30, 34); isAntiAlias = false }
        for (i in 0 until small) {
            px(border, i, 0)
            px(border, i, small - 1)
            px(border, 0, i)
            px(border, small - 1, i)
        }
        val scaled = Bitmap.createScaledBitmap(tiny, SIZE, SIZE, false)
        canvas.drawBitmap(scaled, 0f, 0f, Paint().apply { isFilterBitmap = false; isAntiAlias = false })
        tiny.recycle()
        if (scaled !== tiny) scaled.recycle()
        if (spec.detailed) drawTraitChip(canvas, spec)
    }

    // endregion

    private fun drawSpeciesSilhouette(
        canvas: Canvas,
        spec: Spec,
        cx: Float,
        cy: Float,
        body: Paint,
        stroke: Paint,
        earBoost: Float
    ) {
        val s = spec.species.lowercase()
        when {
            listOf("penguin", "企鹅").any { it in s } -> {
                canvas.drawOval(RectF(cx - 85f, cy - 120f, cx + 85f, cy + 150f), body)
                canvas.drawOval(RectF(cx - 85f, cy - 120f, cx + 85f, cy + 150f), stroke)
                canvas.drawOval(
                    RectF(cx - 45f, cy - 20f, cx + 45f, cy + 120f),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                )
                val beakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 170, 60) }
                val beakPath = Path()
                beakPath.moveTo(cx - 14f, cy - 50f)
                beakPath.lineTo(cx + 14f, cy - 50f)
                beakPath.lineTo(cx, cy - 20f)
                beakPath.close()
                canvas.drawPath(beakPath, beakPaint)
            }
            listOf("robot", "机器").any { it in s } -> {
                canvas.drawRoundRect(RectF(cx - 90f, cy - 110f, cx + 90f, cy + 40f), 18f, 18f, body)
                canvas.drawRoundRect(RectF(cx - 90f, cy - 110f, cx + 90f, cy + 40f), 18f, 18f, stroke)
                canvas.drawRoundRect(RectF(cx - 70f, cy + 50f, cx + 70f, cy + 150f), 12f, 12f, body)
                canvas.drawRoundRect(RectF(cx - 70f, cy + 50f, cx + 70f, cy + 150f), 12f, 12f, stroke)
                canvas.drawCircle(cx, cy - 130f, 10f, body)
                canvas.drawLine(cx, cy - 130f, cx, cy - 110f, stroke)
            }
            listOf("girl", "boy", "女孩", "男孩", "human").any { it in s } -> {
                canvas.drawCircle(cx, cy - 50f, 95f, body)
                canvas.drawCircle(cx, cy - 50f, 95f, stroke)
                canvas.drawRoundRect(RectF(cx - 80f, cy + 30f, cx + 80f, cy + 160f), 50f, 50f, body)
                canvas.drawRoundRect(RectF(cx - 80f, cy + 30f, cx + 80f, cy + 160f), 50f, 50f, stroke)
                // Hair bangs
                val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(spec.primary, 40) }
                canvas.drawArc(RectF(cx - 95f, cy - 140f, cx + 95f, cy - 10f), 200f, 140f, true, hair)
            }
            listOf("dumpling", "团子", "blob").any { it in s } -> {
                canvas.drawOval(RectF(cx - 130f, cy - 90f, cx + 130f, cy + 130f), body)
                canvas.drawOval(RectF(cx - 130f, cy - 90f, cx + 130f, cy + 130f), stroke)
            }
            listOf("dragon", "龙").any { it in s } -> {
                canvas.drawCircle(cx, cy - 40f, 110f, body)
                canvas.drawCircle(cx, cy - 40f, 110f, stroke)
                canvas.drawRoundRect(RectF(cx - 90f, cy + 20f, cx + 90f, cy + 150f), 60f, 60f, body)
                val horn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.accent }
                val h1 = Path()
                h1.moveTo(cx - 35f, cy - 120f); h1.lineTo(cx - 15f, cy - 185f); h1.lineTo(cx, cy - 120f); h1.close()
                canvas.drawPath(h1, horn)
                val h2 = Path()
                h2.moveTo(cx + 35f, cy - 120f); h2.lineTo(cx + 15f, cy - 185f); h2.lineTo(cx, cy - 120f); h2.close()
                canvas.drawPath(h2, horn)
            }
            listOf("panda", "熊猫").any { it in s } -> {
                canvas.drawCircle(cx, cy - 40f, 115f, body)
                canvas.drawCircle(cx, cy - 40f, 115f, stroke)
                canvas.drawRoundRect(RectF(cx - 95f, cy + 20f, cx + 95f, cy + 155f), 70f, 70f, body)
                val patch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 44) }
                canvas.drawOval(RectF(cx - 70f, cy - 70f, cx - 20f, cy - 20f), patch)
                canvas.drawOval(RectF(cx + 20f, cy - 70f, cx + 70f, cy - 20f), patch)
                canvas.drawCircle(cx - 95f, cy - 110f, 32f, patch)
                canvas.drawCircle(cx + 95f, cy - 110f, 32f, patch)
            }
            else -> {
                // Default round pet + ears for cat/dog/fox/rabbit/etc.
                if (listOf("cat", "fox", "rabbit", "猫", "狐", "兔").any { it in s }) {
                    val ear = Path()
                    ear.moveTo(cx - 88f * earBoost, cy - 70f)
                    ear.lineTo(cx - 48f, cy - 175f * earBoost)
                    ear.lineTo(cx - 10f, cy - 78f)
                    ear.close()
                    canvas.drawPath(ear, body)
                    canvas.drawPath(ear, stroke)
                    val ear2 = Path()
                    ear2.moveTo(cx + 88f * earBoost, cy - 70f)
                    ear2.lineTo(cx + 48f, cy - 175f * earBoost)
                    ear2.lineTo(cx + 10f, cy - 78f)
                    ear2.close()
                    canvas.drawPath(ear2, body)
                    canvas.drawPath(ear2, stroke)
                    if ("rabbit" in s || "兔" in s) {
                        canvas.drawOval(RectF(cx - 55f, cy - 220f, cx - 25f, cy - 90f), body)
                        canvas.drawOval(RectF(cx + 25f, cy - 220f, cx + 55f, cy - 90f), body)
                    }
                } else if (listOf("dog", "bear", "狗", "熊").any { it in s }) {
                    canvas.drawCircle(cx - 95f, cy - 95f, 36f, body)
                    canvas.drawCircle(cx + 95f, cy - 95f, 36f, body)
                    canvas.drawCircle(cx - 95f, cy - 95f, 36f, stroke)
                    canvas.drawCircle(cx + 95f, cy - 95f, 36f, stroke)
                }
                canvas.drawCircle(cx, cy - 40f, 118f, body)
                canvas.drawCircle(cx, cy - 40f, 118f, stroke)
                canvas.drawRoundRect(RectF(cx - 95f, cy + 20f, cx + 95f, cy + 155f), 70f, 70f, body)
                canvas.drawRoundRect(RectF(cx - 95f, cy + 20f, cx + 95f, cy + 155f), 70f, 70f, stroke)
                canvas.drawOval(RectF(cx - 78f, cy + 140f, cx - 28f, cy + 168f), body)
                canvas.drawOval(RectF(cx + 28f, cy + 140f, cx + 78f, cy + 168f), body)
            }
        }
    }

    private fun drawBelly(canvas: Canvas, spec: Spec, cx: Float, cy: Float, flat: Boolean) {
        val belly = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (flat) spec.belly else Color.argb(
                220,
                Color.red(spec.belly),
                Color.green(spec.belly),
                Color.blue(spec.belly)
            )
        }
        canvas.drawOval(RectF(cx - 58f, cy + 45f, cx + 58f, cy + 135f), belly)
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, width: Float, color: Int) {
        val smile = Path()
        smile.moveTo(cx - 22f, cy)
        smile.quadTo(cx, cy + width + 4f, cx + 22f, cy)
        canvas.drawPath(
            smile,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                this.color = color
            }
        )
    }

    private fun drawAccessories(
        canvas: Canvas,
        spec: Spec,
        cx: Float,
        cy: Float,
        bold: Boolean,
        fringe: Boolean = false,
        watercolor: Boolean = false,
        neon: Boolean = false,
        minimal: Boolean = false
    ) {
        val alphaMul = if (watercolor) 0.75f else 1f
        if ("scarf" in spec.accessories && !minimal) {
            val scarf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    (220 * alphaMul).toInt(),
                    Color.red(spec.scarfColor),
                    Color.green(spec.scarfColor),
                    Color.blue(spec.scarfColor)
                )
            }
            canvas.drawOval(RectF(cx - 92f, cy + 8f, cx + 92f, cy + 52f), scarf)
            canvas.drawRoundRect(RectF(cx + 40f, cy + 28f, cx + 78f, cy + 110f), 16f, 16f, scarf)
            canvas.drawRoundRect(RectF(cx + 55f, cy + 40f, cx + 88f, cy + 125f), 14f, 14f, scarf)
            canvas.drawCircle(cx + 52f, cy + 36f, 12f, scarf)
            if (fringe || bold) {
                val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darken(spec.scarfColor, 30)
                    strokeWidth = 4f
                    style = Paint.Style.STROKE
                }
                for (i in 0..5) {
                    val x = cx + 48f + i * 7f
                    canvas.drawLine(x, cy + 100f, x - 3f, cy + 130f, fringePaint)
                }
            }
        }
        if ("hat" in spec.accessories) {
            val hat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (neon) Color.rgb(80, 255, 230) else spec.accent }
            canvas.drawOval(RectF(cx - 75f, cy - 155f, cx + 75f, cy - 115f), hat)
            canvas.drawRoundRect(RectF(cx - 50f, cy - 205f, cx + 50f, cy - 125f), 18f, 18f, hat)
        }
        if ("glasses" in spec.accessories) {
            val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (bold) 6f else 4f
                color = if (neon) Color.rgb(80, 255, 230) else Color.rgb(40, 44, 50)
            }
            canvas.drawCircle(cx - 38f, cy - 48f, 20f, g)
            canvas.drawCircle(cx + 38f, cy - 48f, 20f, g)
            canvas.drawLine(cx - 18f, cy - 48f, cx + 18f, cy - 48f, g)
        }
        if ("bow" in spec.accessories) {
            val bow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 110, 150) }
            canvas.drawOval(RectF(cx + 55f, cy - 110f, cx + 95f, cy - 80f), bow)
            canvas.drawOval(RectF(cx + 85f, cy - 110f, cx + 125f, cy - 80f), bow)
            canvas.drawCircle(cx + 90f, cy - 95f, 8f, bow)
        }
        if ("crown" in spec.accessories) {
            val crown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 70) }
            val p = Path()
            p.moveTo(cx - 42f, cy - 145f)
            p.lineTo(cx - 26f, cy - 180f)
            p.lineTo(cx - 6f, cy - 145f)
            p.lineTo(cx + 14f, cy - 185f)
            p.lineTo(cx + 34f, cy - 145f)
            p.lineTo(cx + 42f, cy - 128f)
            p.lineTo(cx - 42f, cy - 128f)
            p.close()
            canvas.drawPath(p, crown)
        }
        if ("wings" in spec.accessories) {
            val wing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (neon) Color.argb(140, 80, 255, 230) else Color.argb(170, 220, 230, 255)
            }
            canvas.drawOval(RectF(cx - 175f, cy - 20f, cx - 75f, cy + 90f), wing)
            canvas.drawOval(RectF(cx + 75f, cy - 20f, cx + 175f, cy + 90f), wing)
        }
        if ("headphones" in spec.accessories) {
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (neon) Color.rgb(80, 255, 230) else Color.rgb(50, 54, 60)
            }
            canvas.drawCircle(cx - 108f, cy - 40f, 24f, hp)
            canvas.drawCircle(cx + 108f, cy - 40f, 24f, hp)
            canvas.drawArc(
                RectF(cx - 108f, cy - 125f, cx + 108f, cy - 15f),
                200f, 140f, false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 12f
                    color = hp.color
                }
            )
        }
        if ("bell" in spec.accessories) {
            val bell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 70) }
            canvas.drawCircle(cx, cy + 55f, 15f, bell)
            canvas.drawCircle(cx, cy + 63f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 70, 40) })
        }
    }

    private fun drawTraitChip(canvas: Canvas, spec: Spec, neonChip: Boolean = false) {
        val label = buildList {
            add(spec.style.name.lowercase().replace('_', ' '))
            if ("scarf" in spec.accessories) add("scarf")
            if ("hat" in spec.accessories) add("hat")
        }.take(3).joinToString(" · ")
        if (label.isBlank()) return
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (neonChip) Color.argb(180, 20, 30, 40) else Color.argb(170, 255, 255, 255)
        }
        canvas.drawRoundRect(RectF(24f, SIZE - 56f, SIZE - 24f, SIZE - 20f), 14f, 14f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (neonChip) Color.rgb(80, 255, 230) else Color.rgb(50, 56, 62)
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(label, SIZE / 2f, SIZE - 32f, text)
    }

    private fun lighten(c: Int, d: Int): Int = Color.rgb(
        min(255, Color.red(c) + d),
        min(255, Color.green(c) + d),
        min(255, Color.blue(c) + d)
    )

    private fun darken(c: Int, d: Int): Int = Color.rgb(
        (Color.red(c) - d).coerceAtLeast(0),
        (Color.green(c) - d).coerceAtLeast(0),
        (Color.blue(c) - d).coerceAtLeast(0)
    )

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
