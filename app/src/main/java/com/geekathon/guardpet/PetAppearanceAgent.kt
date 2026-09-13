package com.geekathon.guardpet

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Appearance agent:
 * DeepSeek analyzes guided fields into a detailed English prompt;
 * ChatGPT (OpenAI Images) draws; DeepSeek vision QA may revise.
 */
object PetAppearanceAgent {
    private const val MAX_ATTEMPTS = 4
    private const val PASS_SCORE = 8
    private const val MAX_PROMPT_CHARS = 2800
    /** Pollinations puts the prompt in the URL — keep it short and trait-locked. */
    private const val MAX_FLUX_PROMPT_CHARS = 320
    private const val FLUX_CANDIDATES = 2

    data class Credentials(
        val dashScopeApiKey: String = "",
        val deepSeekApiKey: String = "",
        val openAiApiKey: String = "",
        val openAiBaseUrl: String = OpenAiImageClient.DEFAULT_BASE_URL
    ) {
        fun hasQwen(): Boolean = dashScopeApiKey.isNotBlank()
        /** Prefer Qwen; fall back to DeepSeek only if still configured. */
        fun plannerKey(): String = dashScopeApiKey.ifBlank { deepSeekApiKey }
    }

    data class PromptGuide(
        val subjectType: String = "",
        val artStyle: String = "",
        val coreFeatures: String = "",
        val freeIdea: String = ""
    ) {
        fun composeIdea(): String {
            val subject = subjectType.trim().takeIf { it.isNotEmpty() && it != "自定义 / 其他" && it != "请选择主体类型" }
            val style = artStyle.trim().takeIf { it.isNotEmpty() && it != "自定义 / 其他" && it != "请选择画面风格" }
            val features = coreFeatures.trim().takeIf { it.isNotEmpty() }
            val free = freeIdea.trim().takeIf { it.isNotEmpty() }
            require(subject != null || features != null || free != null) { "请至少填写主体类型、核心特征或补充描述" }
            return buildString {
                if (subject != null) append("主体类型：").append(subject).append("。")
                if (style != null) append("画面风格：").append(style).append("。")
                if (features != null) append("核心特征：").append(features).append("。")
                if (free != null) append("补充描述：").append(free).append("。")
            }.trim()
        }

        fun composeIdeaOrEmpty(): String = runCatching { composeIdea() }.getOrDefault("")
    }

    data class Blueprint(
        val originalIdea: String,
        val species: String,
        val colors: List<String>,
        val accessories: List<String>,
        val style: String,
        val mood: String,
        val imagePrompt: String,
        val shortPrompt: String,
        val mustKeep: List<String>,
        val avoid: List<String> = emptyList()
    )

    data class Critique(
        val score: Int,
        val matched: Boolean,
        val missing: List<String>,
        val wrong: List<String>,
        val fixPrompt: String
    )

    data class AgentResult(
        val pngBytes: ByteArray,
        val originalIdea: String,
        val finalPrompt: String,
        val blueprint: Blueprint,
        val attempts: Int,
        val score: Int
    )

    fun generateFaithfulAppearance(
        guide: PromptGuide,
        credentials: Credentials,
        onProgress: ((String) -> Unit)? = null
    ): AgentResult {
        val cleaned = guide.composeIdea().take(400)
        require(credentials.deepSeekApiKey.isNotBlank()) { "DeepSeek API Key 未配置" }
        val deepSeek = DeepSeekClient(credentials.deepSeekApiKey)
        val openAi = credentials.openAiApiKey.takeIf { it.isNotBlank() }?.let {
            OpenAiImageClient(it, credentials.openAiBaseUrl)
        }

        onProgress?.invoke("understanding")
        var blueprint = plan(cleaned, deepSeek, guide)
        var prompt = compactPrompt(blueprint.imagePrompt)
        var shortPrompt = compactFluxPrompt(blueprint.shortPrompt.ifBlank { buildFluxPrompt(blueprint, blueprint.mustKeep) })
        var bestBytes: ByteArray? = null
        var bestScore = -1
        var bestPrompt = prompt
        var lastCritique: Critique? = null
        var openAiBlocked = false

        repeat(MAX_ATTEMPTS) { index ->
            val attempt = index + 1
            onProgress?.invoke("drawing:$attempt")
            val png = drawBestImage(
                longPrompt = prompt,
                shortPrompt = shortPrompt,
                attempt = attempt,
                openAi = openAi,
                openAiBlocked = openAiBlocked,
                deepSeek = deepSeek,
                idea = cleaned,
                blueprint = blueprint,
                onOpenAiBlocked = { blocked ->
                    openAiBlocked = blocked
                    if (blocked) onProgress?.invoke("fallback")
                },
                onProgress = onProgress
            )
            onProgress?.invoke("checking:$attempt")
            val critique = runCatching {
                critiqueWithVision(deepSeek, cleaned, blueprint, png)
            }.getOrElse {
                Critique(
                    score = 2,
                    matched = false,
                    missing = blueprint.mustKeep,
                    wrong = emptyList(),
                    fixPrompt = buildFluxPrompt(blueprint, blueprint.mustKeep)
                )
            }
            lastCritique = critique
            if (critique.score >= bestScore) {
                bestScore = critique.score
                bestBytes = png
                bestPrompt = if (openAiBlocked || openAi == null) shortPrompt else prompt
            }
            if (critique.matched || critique.score >= PASS_SCORE) {
                onProgress?.invoke("done")
                return AgentResult(png, cleaned, bestPrompt, blueprint, attempt, critique.score)
            }
            if (attempt < MAX_ATTEMPTS) {
                onProgress?.invoke("revising:$attempt")
                val revised = revisePrompts(deepSeek, cleaned, blueprint, prompt, shortPrompt, critique)
                prompt = compactPrompt(revised.first)
                shortPrompt = compactFluxPrompt(revised.second)
                blueprint = blueprint.copy(
                    imagePrompt = prompt,
                    shortPrompt = shortPrompt,
                    mustKeep = (blueprint.mustKeep + critique.missing).distinct().take(10)
                )
            }
        }

        onProgress?.invoke("done")
        return AgentResult(
            pngBytes = bestBytes ?: error("生成失败"),
            originalIdea = cleaned,
            finalPrompt = bestPrompt,
            blueprint = blueprint.copy(imagePrompt = bestPrompt, shortPrompt = shortPrompt),
            attempts = MAX_ATTEMPTS,
            score = bestScore.coerceAtLeast(lastCritique?.score ?: 0)
        )
    }

    fun generateFromUploadFaithful(
        imageBytes: ByteArray,
        guide: PromptGuide,
        credentials: Credentials,
        onProgress: ((String) -> Unit)? = null
    ): AgentResult {
        require(credentials.deepSeekApiKey.isNotBlank()) { "DeepSeek API Key 未配置" }
        val deepSeek = DeepSeekClient(credentials.deepSeekApiKey)
        onProgress?.invoke("looking")
        val compressed = compressForVision(imageBytes)
        val described = deepSeek.describeImageAsPetPrompt(
            compressed.first,
            compressed.second,
            guide.composeIdeaOrEmpty()
        )
        val merged = guide.copy(
            freeIdea = buildString {
                append(guide.freeIdea.trim())
                if (isNotEmpty()) append("。")
                append("参考图特征：").append(described)
            }
        )
        return generateFaithfulAppearance(merged, credentials, onProgress)
    }

    /**
     * Plan locked traits (Qwen when possible, else DeepSeek / local keyword lock).
     */
    fun planAppearance(
        guide: PromptGuide,
        credentials: Credentials?,
        onProgress: ((String) -> Unit)? = null
    ): Blueprint {
        val cleaned = guide.composeIdea().take(400)
        val local = planLocal(cleaned, guide)
        val key = credentials?.plannerKey()?.takeIf { it.isNotBlank() } ?: return local
        onProgress?.invoke("understanding")
        return when {
            credentials?.hasQwen() == true -> {
                runCatching {
                    planWithQwen(QwenClient(key), cleaned, local, guide)
                }.getOrDefault(local)
            }
            else -> {
                runCatching {
                    planWithDeepSeek(DeepSeekClient(key), cleaned, local, guide)
                }.getOrDefault(local)
            }
        }
    }

    /** Try Qwen text-to-image with trait-locked Chinese prompt; optional OpenAI proxy last. */
    data class OnlineDraw(val bytes: ByteArray, val source: String)

    fun tryOnlinePng(
        blueprint: Blueprint,
        credentials: Credentials,
        guide: PromptGuide = PromptGuide(),
        onProgress: ((String) -> Unit)? = null
    ): OnlineDraw? {
        val zhPrompt = buildFaithfulChinesePrompt(guide, blueprint)
        if (credentials.hasQwen()) {
            onProgress?.invoke("drawing:1")
            onProgress?.invoke("traits:${blueprint.mustKeep.take(6).joinToString(" · ")}")
            runCatching {
                val bytes = QwenImageClient(credentials.dashScopeApiKey).textToImage(
                    prompt = zhPrompt,
                    negativePrompt = buildNegativePrompt(blueprint),
                    seed = (zhPrompt.hashCode().toLong() and 0x7fff_ffffL).toInt(),
                    promptExtend = false,
                    fidelity = true
                )
                return OnlineDraw(bytes, "qwen")
            }.onFailure {
                android.util.Log.w("PetAppearance", "Qwen T2I failed: ${it.message}")
                onProgress?.invoke("fallback")
            }
        }
        val openAiKey = credentials.openAiApiKey.takeIf { it.isNotBlank() } ?: return null
        val base = credentials.openAiBaseUrl
        if (base.contains("api.openai.com", ignoreCase = true)) return null
        onProgress?.invoke("drawing:1")
        return runCatching {
            OnlineDraw(
                OpenAiImageClient(openAiKey, base).generatePng(
                    compactPrompt(blueprint.imagePrompt.ifBlank { blueprint.shortPrompt })
                ),
                "openai"
            )
        }.getOrNull()
    }

    /**
     * 文生图提示：用户原文特征优先并复述，禁止模型擅自换物种/配色/配饰。
     */
    fun buildFaithfulChinesePrompt(guide: PromptGuide, blueprint: Blueprint): String {
        val subject = guide.subjectType.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        } ?: blueprint.species
        val style = guide.artStyle.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        } ?: blueprint.style.ifBlank { "二次元Q版贴纸" }
        val features = guide.coreFeatures.trim()
        val free = guide.freeIdea.trim()
        val must = blueprint.mustKeep.filter { it.isNotBlank() }.distinct().take(8)
        val colors = blueprint.colors.filter { it.isNotBlank() }.distinct().take(4)
        val accessories = blueprint.accessories.filter { it.isNotBlank() }.distinct().take(5)
        val lockLine = buildList {
            add(subject)
            addAll(colors)
            addAll(accessories)
            if (features.isNotBlank()) add(features)
        }.joinToString("，")

        return buildString {
            // 首句锁定，并立即复述一遍（对文生图模型很有效）
            append("严格按用户描述生成一只桌宠贴纸，不得改换主体或遗漏配饰。")
            append("锁定形象：").append(lockLine).append("。")
            append("再次强调锁定形象：").append(lockLine).append("。")
            append("主体类型必须是：").append(subject).append("。")
            append("画面风格必须是：").append(style).append("。")
            if (features.isNotBlank()) {
                append("核心特征必须全部清晰可见：").append(features).append("。")
            }
            if (free.isNotBlank()) {
                append("补充要求：").append(free).append("。")
            }
            if (colors.isNotEmpty()) {
                append("主色固定为：").append(colors.joinToString("、")).append("，不要换成其他颜色。")
            }
            if (accessories.isNotEmpty()) {
                append("配饰必须画上：").append(accessories.joinToString("、")).append("。")
            }
            if (must.isNotEmpty()) {
                append("验收清单（每项都要能看见）：").append(must.joinToString("、")).append("。")
            }
            if (blueprint.avoid.isNotEmpty()) {
                append("禁止出现：").append(blueprint.avoid.take(5).joinToString("、")).append("。")
            }
            append("构图：全身、正面略侧、角色居中、Q版桌宠比例。")
            append("背景必须完全透明（alpha透明），不要任何底色、地面、阴影底板、场景或渐变。")
            append("禁止：不透明背景、文字、水印、logo、多只角色、真实杂乱背景、血腥恐怖。")
        }.take(2000)
    }

    fun buildChineseImagePrompt(blueprint: Blueprint): String =
        buildFaithfulChinesePrompt(PromptGuide(), blueprint)

    fun buildNegativePrompt(blueprint: Blueprint): String {
        val ban = blueprint.avoid.take(6).joinToString("，")
        return buildString {
            append("低分辨率，模糊，畸形肢体，多余手指，文字，水印，logo，多只角色，")
            append("真实照片杂乱背景，场景背景，房间，风景，地面，渐变背景，不透明底板，")
            append("白色方块背景，灰色底板，血腥恐怖，换物种，错误颜色，缺少必选配饰")
            if (ban.isNotBlank()) append("，").append(ban)
        }
    }

    /** Reference image + user guide → Qwen I2I，优先保留参考图像素身份。 */
    fun tryReferenceImagePng(
        imageBytes: ByteArray,
        mime: String,
        guide: PromptGuide,
        credentials: Credentials,
        onProgress: ((String) -> Unit)? = null
    ): Pair<ByteArray, String>? {
        if (!credentials.hasQwen()) return null
        onProgress?.invoke("looking")
        // 直接把原图交给编辑模型；只做轻度压缩，避免再经 VL「转述」后按文字重画
        val compressed = compressForVision(imageBytes, maxSide = 1536, quality = 94)
        val local = planLocal(guide.composeIdeaOrEmpty().ifBlank { "参考图改绘" }, guide)
        val prompt = buildReferenceEditPrompt(guide, local, identityDescription = "")
        onProgress?.invoke("drawing:1")
        val png = QwenImageClient(credentials.dashScopeApiKey).imageToImage(
            prompt = prompt,
            referenceJpegOrPng = compressed.first,
            mime = compressed.second,
            negativePrompt = buildNegativePromptI2I(local),
            size = null,
            seed = null,
            promptExtend = false,
            fidelity = true,
            preserveIdentity = true
        )
        return png to prompt
    }

    /**
     * 参考图改绘提示：短指令、以图为准。
     * 长文+特征复述会把编辑模型推向「文生图」，参考图几乎失效。
     */
    fun buildReferenceEditPrompt(
        guide: PromptGuide,
        blueprint: Blueprint,
        identityDescription: String
    ): String {
        val style = guide.artStyle.trim().takeIf {
            it.isNotEmpty() && !it.startsWith("请选择") && it != "自定义 / 其他"
        }
        val features = guide.coreFeatures.trim()
        val free = guide.freeIdea.trim()
        val deltas = buildList {
            if (features.isNotBlank()) add(features)
            if (free.isNotBlank()) add(free)
        }.joinToString("；")

        return buildString {
            // 官方示例风格：先强调「完美保留输入图」，再写少量改动
            append("请基于输入的参考图进行编辑。")
            append("必须完美保留参考图中角色的面部特征、发型/耳型、体型轮廓、主色与标志性配饰，")
            append("一眼能认出是同一个角色，不要换成其他人物或物种。")
            if (deltas.isNotBlank()) {
                append("仅做以下改动：").append(deltas).append("。")
            } else if (style != null) {
                append("仅将画风轻微调整为").append(style).append("，外形与配色仍以参考图为准。")
            } else {
                append("仅做桌宠贴纸化处理：全身居中、边缘干净，不要改变角色长相与配色。")
            }
            if (style != null && deltas.isNotBlank()) {
                append("画风可偏向").append(style).append("，但身份特征以参考图为准。")
            }
            append("背景必须完全透明，不要底色、地面、阴影底板或任何场景。")
            append("禁止：换脸、换物种、文字、水印、多只角色、杂乱真实背景、不透明背景。")
            if (identityDescription.isNotBlank()) {
                append("补充可见特征：").append(identityDescription.take(80)).append("。")
            }
        }.take(500)
    }

    fun buildNegativePromptI2I(blueprint: Blueprint): String {
        val ban = blueprint.avoid.take(6).joinToString("，")
        return buildString {
            append("低分辨率，模糊，畸形肢体，多余手指，文字，水印，logo，多只角色，")
            append("真实照片杂乱背景，场景背景，房间，风景，地面，渐变背景，不透明底板，")
            append("白色方块背景，灰色底板，血腥恐怖，换脸，换物种，换发型，换主色，")
            append("改变五官比例，变成另一个角色，缺少原角色标志特征")
            if (ban.isNotBlank()) append("，").append(ban)
        }
    }

    private fun planWithQwen(
        client: QwenClient,
        idea: String,
        fallback: Blueprint,
        guide: PromptGuide
    ): Blueprint {
        val system = """
你是桌宠形象锁定助手。把用户字段转成 JSON，用于文生图严格贴合提示。
只返回 JSON，字段：
species(用用户主体原文或准确中文), colors(中文数组，来自用户核心特征), accessories(中文数组),
style(用户风格原文), mood, must_keep(中文数组,最多8，尽量直接摘录用户原词),
avoid(中文数组，列出容易画错的相反物种/颜色),
image_prompt_en(80-140英文词),
short_prompt_en(少于42英文词)。
硬性规则：
1. 禁止改换主体物种
2. 用户写的颜色、配饰必须原样进入 must_keep
3. 不要发明用户没写的花哨设定
""".trimIndent()
        val user = """
合成描述: $idea
主体: ${guide.subjectType}
风格: ${guide.artStyle}
核心特征: ${guide.coreFeatures}
补充: ${guide.freeIdea}
本地锁定: species=${fallback.species}; colors=${fallback.colors}; accessories=${fallback.accessories}; style=${fallback.style}
""".trimIndent()
        val raw = client.chatRaw(system, user, temperature = 0.05)
        return parseBlueprintJson(idea, raw, fallback)
    }

    private fun drawBestImage(
        longPrompt: String,
        shortPrompt: String,
        attempt: Int,
        openAi: OpenAiImageClient?,
        openAiBlocked: Boolean,
        deepSeek: DeepSeekClient,
        idea: String,
        blueprint: Blueprint,
        onOpenAiBlocked: (Boolean) -> Unit,
        onProgress: ((String) -> Unit)?
    ): ByteArray {
        if (openAi != null && !openAiBlocked) {
            try {
                return openAi.generatePng(longPrompt)
            } catch (_: Exception) {
                onOpenAiBlocked(true)
                onProgress?.invoke("fallback")
            }
        }

        // Try online hosts; if China DNS blocks them, fall back to local chibi.
        val onlineErrors = mutableListOf<String>()
        var bestPng: ByteArray? = null
        var bestScore = -1
        repeat(FLUX_CANDIDATES) { cand ->
            val png = runCatching {
                generatePollinationsImage(shortPrompt, attempt * 10 + cand + 1)
            }.getOrElse {
                onlineErrors += it.message?.take(80).orEmpty()
                null
            } ?: return@repeat
            val score = runCatching {
                critiqueWithVision(deepSeek, idea, blueprint, png).score
            }.getOrDefault(5)
            if (score >= bestScore) {
                bestScore = score
                bestPng = png
            }
            if (score >= PASS_SCORE) return png
        }
        if (bestPng != null) return bestPng!!

        onProgress?.invoke("local")
        val localIdea = buildString {
            append(idea).append(' ')
            append(blueprint.species).append(' ')
            append(blueprint.colors.joinToString(" ")).append(' ')
            append(blueprint.accessories.joinToString(" "))
        }
        return PetAppearanceGenerator.generateLocalAppearance(localIdea)
    }

    private fun generatePollinationsImage(promptBody: String, attempt: Int): ByteArray {
        val prompt = compactFluxPrompt(
            buildString {
                append(promptBody)
                if (!promptBody.contains("chibi", ignoreCase = true)) append(", cute chibi sticker")
                append(", full body, centered, plain background, no text")
            }
        )
        val encoded = java.net.URLEncoder.encode(prompt, Charsets.UTF_8.name()).replace("+", "%20")
        val seed = (prompt.hashCode().toLong() xor (attempt * 9973L) xor 42L).and(0x7fff_ffffL)
        val hosts = listOf(
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=768&nologo=true&seed=$seed&model=flux&enhance=false",
            "https://gen.pollinations.ai/image/$encoded?width=768&height=768&nologo=true&seed=$seed&model=flux",
            "https://pollinations.ai/p/$encoded?width=768&height=768&nologo=true&seed=$seed"
        )
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                return downloadImageUrl(host)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("备用出图失败")
    }

    private fun downloadImageUrl(urlSpec: String): ByteArray {
        val connection = (java.net.URL(urlSpec).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*")
            setRequestProperty("User-Agent", "ShoubanPetAgent/2.7.1")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("image http $code")
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size > 2_000) { "image too small" }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun plan(idea: String, client: DeepSeekClient, guide: PromptGuide): Blueprint {
        val local = planLocal(idea, guide)
        return runCatching { planWithDeepSeek(client, idea, local, guide) }.getOrDefault(local)
    }

    private fun planWithDeepSeek(
        client: DeepSeekClient,
        idea: String,
        fallback: Blueprint,
        guide: PromptGuide
    ): Blueprint {
        val system = """
You are a desktop-pet appearance locking agent.
Convert user fields into JSON for faithful image generation.
Return ONLY JSON with keys:
species, colors (English array), accessories (English array),
style, mood, must_keep (English array, max 8),
avoid (English array of wrong species/colors to ban),
image_prompt_en (80-140 words, detailed, for DALL-E),
short_prompt_en (UNDER 42 English words, for Flux; traits FIRST and repeated once).
Rules:
- Never invent a different species than the user subject
- Core features must appear in BOTH prompts as concrete nouns
- short_prompt_en format example:
  "mint-green cat with red scarf, mint-green cat with red scarf, anime chibi sticker, big eyes, full body"
- One character only, plain background, no text
""".trimIndent()
        val user = """
Composed idea: $idea
Subject type: ${guide.subjectType}
Art style: ${guide.artStyle}
Core features: ${guide.coreFeatures}
Free description: ${guide.freeIdea}
Local lock: species=${fallback.species}; colors=${fallback.colors}; accessories=${fallback.accessories}; style=${fallback.style}; must=${fallback.mustKeep}
""".trimIndent()
        val raw = client.chatRaw(system, user, temperature = 0.05)
        return parseBlueprintJson(idea, raw, fallback)
    }

    private fun critiqueWithVision(
        client: DeepSeekClient,
        idea: String,
        blueprint: Blueprint,
        pngBytes: ByteArray
    ): Critique {
        val jpeg = pngToJpeg(pngBytes)
        val system = """
You are a STRICT visual QA judge for desktop-pet stickers.
Compare the image to MUST KEEP traits.
If species is wrong => score <= 3.
If main color or required accessory missing => score <= 5.
Return ONLY JSON:
{"score":0-10,"matched":true/false,"missing":[],"wrong":[],"fix_prompt_en":"...","fix_short_en":"..."}
matched=true only if score>=8 and every must_keep trait is clearly visible.
fix_short_en: under 42 English words, traits first and repeated once, for Flux.
fix_prompt_en: 80-140 English words for DALL-E.
""".trimIndent()
        val userText = """
User request: $idea
MUST KEEP: ${blueprint.mustKeep.joinToString(", ")}
Species=${blueprint.species}; colors=${blueprint.colors}; accessories=${blueprint.accessories}; style=${blueprint.style}
Avoid: ${blueprint.avoid.joinToString(", ")}
""".trimIndent()
        val raw = client.chatVisionRaw(system, userText, jpeg, "image/jpeg", temperature = 0.0)
        return parseCritique(raw, blueprint)
    }

    private fun revisePrompts(
        client: DeepSeekClient,
        idea: String,
        blueprint: Blueprint,
        previousLong: String,
        previousShort: String,
        critique: Critique
    ): Pair<String, String> {
        val system = """
Rewrite TWO English prompts for a chibi desktop pet that FAILED QA.
Return ONLY JSON: {"image_prompt_en":"...","short_prompt_en":"..."}
- Put missing traits first
- Ban wrong traits explicitly in short_prompt_en with "not X"
- short_prompt_en under 42 words, repeat the locked subject+color+accessory once
""".trimIndent()
        val user = """
Original: $idea
Must keep: ${blueprint.mustKeep}
Missing: ${critique.missing}
Wrong: ${critique.wrong}
Previous long: $previousLong
Previous short: $previousShort
Critique fix hint: ${critique.fixPrompt}
""".trimIndent()
        val raw = runCatching { client.chatRaw(system, user, temperature = 0.1) }.getOrNull()
        if (raw != null) {
            runCatching {
                val obj = JSONObject(extractJsonObject(raw) ?: error("no json"))
                val longP = obj.optString("image_prompt_en").ifBlank { critique.fixPrompt }
                val shortP = obj.optString("short_prompt_en").ifBlank {
                    buildFluxPrompt(blueprint, blueprint.mustKeep + critique.missing)
                }
                return longP to shortP
            }
        }
        val emphasize = blueprint.mustKeep + critique.missing
        val longP = buildStrictPrompt(blueprint.copy(imagePrompt = critique.fixPrompt.ifBlank { previousLong }), emphasize)
        val shortP = buildFluxPrompt(blueprint, emphasize, critique.wrong)
        return longP to shortP
    }

    private fun parseBlueprintJson(idea: String, raw: String, fallback: Blueprint): Blueprint {
        val obj = JSONObject(extractJsonObject(raw) ?: error("no json"))
        val species = obj.optString("species").ifBlank { fallback.species }
        val colors = obj.optJSONArray("colors").toStringList().ifEmpty { fallback.colors }
        val accessories = obj.optJSONArray("accessories").toStringList().ifEmpty { fallback.accessories }
        val style = obj.optString("style").ifBlank { fallback.style }
        val mood = obj.optString("mood").ifBlank { fallback.mood }
        val mustKeep = obj.optJSONArray("must_keep").toStringList().ifEmpty {
            buildMustKeep(species, colors, accessories, mood, style)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(8)
        val avoid = obj.optJSONArray("avoid").toStringList().ifEmpty { fallback.avoid }
        val prompt = obj.optString("image_prompt_en").ifBlank {
            buildImagePrompt(species, colors, accessories, style, mood, mustKeep)
        }
        val short = obj.optString("short_prompt_en").ifBlank {
            buildFluxPrompt(
                Blueprint(idea, species, colors, accessories, style, mood, prompt, "", mustKeep, avoid),
                mustKeep,
                avoid
            )
        }
        val bp = Blueprint(idea, species, colors, accessories, style, mood, prompt, short, mustKeep, avoid)
        return bp.copy(
            imagePrompt = buildStrictPrompt(bp, emphasize = mustKeep),
            shortPrompt = compactFluxPrompt(short)
        )
    }

    private fun parseCritique(raw: String, blueprint: Blueprint): Critique {
        val obj = JSONObject(extractJsonObject(raw) ?: error("no critique json"))
        val score = obj.optInt("score", 0).coerceIn(0, 10)
        val missing = obj.optJSONArray("missing").toStringList()
        val wrong = obj.optJSONArray("wrong").toStringList()
        val matched = obj.optBoolean("matched", false) && score >= PASS_SCORE && wrong.isEmpty()
        val fix = obj.optString("fix_prompt_en").ifBlank {
            obj.optString("fix_short_en").ifBlank {
                buildFluxPrompt(blueprint, blueprint.mustKeep + missing, wrong)
            }
        }
        return Critique(score, matched, missing, wrong, fix)
    }

    private fun planLocal(idea: String, guide: PromptGuide): Blueprint {
        val combined = listOf(idea, guide.subjectType, guide.coreFeatures, guide.freeIdea, guide.artStyle)
            .joinToString(" ")
        val lower = combined.lowercase()
        val species = translateSubject(guide.subjectType).ifBlank { detectSpecies(lower) }
        val colors = detectColors(lower)
        val accessories = detectAccessories(lower)
        val style = translateStyle(guide.artStyle).ifBlank { detectStyle(lower) }
        val mood = detectMood(lower)
        val featureBits = guide.coreFeatures
            .split(Regex("[，,、；;\\n]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .map { translateFeatureBit(it) }
            .filter { it.isNotBlank() }
        val mustKeep = (buildMustKeep(species, colors, accessories, mood, style) + featureBits)
            .distinct()
            .take(8)
        val avoid = defaultAvoid(species)
        val prompt = buildImagePrompt(species, colors, accessories, style, mood, mustKeep)
        val short = buildFluxPrompt(
            Blueprint(idea, species, colors, accessories, style, mood, prompt, "", mustKeep, avoid),
            mustKeep,
            avoid
        )
        return Blueprint(idea, species, colors, accessories, style, mood, prompt, short, mustKeep, avoid)
    }

    private fun defaultAvoid(species: String): List<String> {
        val s = species.lowercase()
        return when {
            "cat" in s -> listOf("dog", "rabbit", "human adult")
            "dog" in s -> listOf("cat", "fox", "human adult")
            "rabbit" in s -> listOf("cat", "dog", "human adult")
            "dragon" in s -> listOf("dinosaur", "lizard monster", "human adult")
            "girl" in s || "boy" in s -> listOf("animal only mascot", "photorealistic adult")
            else -> listOf("photorealistic", "extra characters")
        }
    }

    private fun buildMustKeep(
        species: String,
        colors: List<String>,
        accessories: List<String>,
        mood: String,
        style: String
    ): List<String> = buildList {
        add(species)
        addAll(colors)
        addAll(accessories)
        add(style)
        if (mood.isNotBlank()) add(mood)
    }.distinct().filter { it.isNotBlank() }

    private fun buildImagePrompt(
        species: String,
        colors: List<String>,
        accessories: List<String>,
        style: String,
        mood: String,
        mustKeep: List<String>
    ): String {
        val colorText = if (colors.isEmpty()) "soft pastel colors" else colors.joinToString(" and ")
        val accessoryText = if (accessories.isEmpty()) "" else
            ", clearly wearing ${accessories.joinToString(" and ")}"
        return buildString {
            append("A single $species with $colorText$accessoryText. ")
            append("Art style: $style. Expression: $mood. ")
            append("Required visible traits: ${mustKeep.joinToString(", ")}. ")
            append("Full-body chibi desktop pet mascot, centered composition, soft shading, ")
            append("plain light background, sticker style, no text, no watermark, no extra characters.")
        }
    }

    /** Ultra-locked short prompt for Flux / Pollinations URL generation. */
    private fun buildFluxPrompt(
        blueprint: Blueprint,
        emphasize: List<String>,
        wrong: List<String> = blueprint.avoid
    ): String {
        val core = buildList {
            add(blueprint.species)
            addAll(blueprint.colors.take(2))
            addAll(blueprint.accessories.take(2))
            addAll(emphasize)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(6)
        val lock = core.joinToString(" ")
        val style = blueprint.style.ifBlank { "anime chibi sticker" }
        val ban = (wrong + blueprint.avoid).distinct().take(3).joinToString(", ")
        return compactFluxPrompt(
            buildString {
                append(lock)
                append(", ")
                append(lock)
                append(", ")
                append(style)
                append(", big eyes, full body chibi, centered")
                if (ban.isNotBlank()) append(", not ").append(ban)
            }
        )
    }

    private fun buildStrictPrompt(blueprint: Blueprint, emphasize: List<String>): String {
        val focus = emphasize.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(8)
        val focusText = focus.joinToString(", ")
        val base = blueprint.imagePrompt.trim().trim('"', '“', '”')
        return compactPrompt(
            buildString {
                if (focusText.isNotBlank()) append("Must depict: $focusText. ")
                append(base)
                if (focusText.isNotBlank()) {
                    append(" Emphasize $focusText. Do not change species or omit required accessories.")
                }
            }
        )
    }

    private fun compactPrompt(prompt: String): String {
        val cleaned = prompt.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
        if (cleaned.length <= MAX_PROMPT_CHARS) return cleaned
        return cleaned.take(MAX_PROMPT_CHARS).substringBeforeLast(' ')
            .ifBlank { cleaned.take(MAX_PROMPT_CHARS) }
    }

    private fun compactFluxPrompt(prompt: String): String {
        val cleaned = prompt.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
        if (cleaned.length <= MAX_FLUX_PROMPT_CHARS) return cleaned
        return cleaned.take(MAX_FLUX_PROMPT_CHARS).substringBeforeLast(' ')
            .ifBlank { cleaned.take(MAX_FLUX_PROMPT_CHARS) }
    }

    private fun revisePrompt(
        client: DeepSeekClient,
        idea: String,
        blueprint: Blueprint,
        previousPrompt: String,
        critique: Critique
    ): String {
        // Kept for compatibility; prefer revisePrompts.
        return revisePrompts(client, idea, blueprint, previousPrompt, blueprint.shortPrompt, critique).first
    }

    private fun pngToJpeg(pngBytes: ByteArray): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: return pngBytes
        val maxSide = 768
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        return out.toByteArray()
    }

    private fun compressForVision(
        imageBytes: ByteArray,
        maxSide: Int = 768,
        quality: Int = 88
    ): Pair<ByteArray, String> {
        val decoded = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return imageBytes to "image/jpeg"
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == decoded.width && h == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 98), out)
        if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (!decoded.isRecycled) decoded.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    private fun translateSubject(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("请选择") || t == "自定义 / 其他") return ""
        return when (t) {
            "小猫" -> "cute cat"
            "小狗" -> "cute dog"
            "兔子" -> "cute rabbit"
            "狐狸" -> "cute fox"
            "小龙" -> "cute tiny dragon"
            "熊猫" -> "cute panda"
            "企鹅" -> "cute penguin"
            "机器人" -> "cute robot companion"
            "Q版女孩" -> "cute chibi girl"
            "Q版男孩" -> "cute chibi boy"
            "团子生物" -> "cute round dumpling creature"
            else -> detectSpecies(t.lowercase())
        }
    }

    private fun translateStyle(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("请选择") || t == "自定义 / 其他") return ""
        return when (t) {
            "二次元Q版贴纸" -> "anime chibi sticker"
            "柔和水彩" -> "soft watercolor"
            "像素风" -> "pixel art"
            "极简干净" -> "minimal clean design"
            "柔和赛博" -> "soft cyber pastel"
            "半写实可爱" -> "semi-realistic cute"
            "粘土手办风" -> "clay figure toy style"
            else -> detectStyle(t.lowercase())
        }
    }

    private fun translateFeatureBit(bit: String): String {
        val lower = bit.lowercase()
        val colors = detectColors(lower)
        val accessories = detectAccessories(lower)
        if (colors.isNotEmpty() || accessories.isNotEmpty()) {
            return (colors + accessories).joinToString(" ")
        }
        return bit.take(24)
    }

    private fun detectSpecies(lower: String): String = when {
        listOf("初音", "miku").any { it in lower } -> "Hatsune Miku inspired chibi girl"
        listOf("猫", "喵", "cat", "neko").any { it in lower } -> "cute cat"
        listOf("狗", "汪", "dog", "puppy", "柴", "柯基").any { it in lower } -> "cute dog"
        listOf("兔", "bunny", "rabbit").any { it in lower } -> "cute rabbit"
        listOf("熊猫", "panda").any { it in lower } -> "cute panda"
        listOf("熊", "bear").any { it in lower } -> "cute bear"
        listOf("狐", "fox").any { it in lower } -> "cute fox"
        listOf("龙", "dragon").any { it in lower } -> "cute tiny dragon"
        listOf("企鹅", "penguin").any { it in lower } -> "cute penguin"
        listOf("机器", "robot").any { it in lower } -> "cute robot companion"
        listOf("女孩", "少女", "girl").any { it in lower } -> "cute chibi girl"
        listOf("男孩", "boy").any { it in lower } -> "cute chibi boy"
        listOf("团子", "dumpling").any { it in lower } -> "cute round dumpling creature"
        else -> "cute original desktop pet creature"
    }

    private fun detectColors(lower: String): List<String> = buildList {
        fun addIf(keys: List<String>, label: String) {
            if (keys.any { it in lower }) add(label)
        }
        addIf(listOf("薄荷绿", "mint"), "mint green")
        addIf(listOf("粉", "pink", "樱"), "pink")
        addIf(listOf("蓝", "blue", "青"), "blue")
        addIf(listOf("绿", "green", "翠"), "green")
        addIf(listOf("紫", "purple"), "purple")
        addIf(listOf("橙", "orange", "橘"), "orange")
        // 避免「金色眼睛」把身体主色误判成黄
        if (listOf("黄", "yellow").any { it in lower } ||
            (listOf("金", "gold").any { it in lower } && !lower.contains("眼睛") && !lower.contains("eye"))
        ) {
            add("yellow")
        }
        addIf(listOf("红", "red"), "red")
        addIf(listOf("黑", "black"), "black")
        addIf(listOf("白", "white"), "white")
        addIf(listOf("棕", "brown"), "brown")
        addIf(listOf("灰", "gray", "grey"), "gray")
    }.distinct()

    private fun detectAccessories(lower: String): List<String> = buildList {
        fun addIf(keys: List<String>, label: String) {
            if (keys.any { it in lower }) add(label)
        }
        addIf(listOf("围巾", "scarf"), "scarf")
        addIf(listOf("帽", "hat"), "hat")
        addIf(listOf("眼镜", "glasses"), "glasses")
        addIf(listOf("翅膀", "wing"), "small wings")
        addIf(listOf("皇冠", "crown"), "tiny crown")
        addIf(listOf("蝴蝶结", "bow"), "bow")
        addIf(listOf("背包", "bag"), "small backpack")
        addIf(listOf("耳机", "headset"), "headphones")
        addIf(listOf("铃铛", "bell"), "bell")
        addIf(listOf("项链", "necklace"), "necklace")
        addIf(listOf("领结", "necktie"), "bow tie")
    }.distinct()

    private fun detectStyle(lower: String): String = when {
        listOf("像素", "pixel").any { it in lower } -> "pixel art"
        listOf("水彩", "watercolor").any { it in lower } -> "soft watercolor"
        listOf("赛博", "cyber").any { it in lower } -> "soft cyber pastel"
        listOf("极简", "minimal").any { it in lower } -> "minimal clean design"
        listOf("写实", "realistic").any { it in lower } -> "semi-realistic cute"
        listOf("粘土", "clay").any { it in lower } -> "clay figure toy style"
        listOf("二次元", "动漫", "anime", "q版", "chibi", "贴纸").any { it in lower } -> "anime chibi sticker"
        else -> "anime chibi sticker"
    }

    private fun detectMood(lower: String): String = when {
        listOf("困", "睡", "sleepy").any { it in lower } -> "sleepy"
        listOf("开心", "笑", "happy").any { it in lower } -> "happy cheerful"
        listOf("酷", "cool").any { it in lower } -> "cool calm"
        listOf("害羞", "shy").any { it in lower } -> "shy blushing"
        listOf("傲娇").any { it in lower } -> "tsundere pout"
        listOf("温柔", "gentle").any { it in lower } -> "gentle warm"
        else -> "friendly gentle"
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
