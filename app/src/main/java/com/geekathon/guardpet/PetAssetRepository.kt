package com.geekathon.guardpet

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class PetAssetRepository(private val context: Context) {
    private val assetDir = File(context.filesDir, "pet_assets").apply { mkdirs() }
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        importBundledCategories()
        if (preferences.getString(PetState.IDLE.key, null) == null) {
            restoreBundledDefaults()
        }
    }

    fun fileFor(state: PetState): File? {
        // 自定义形象覆盖所有状态，避免行走/摸头切回默认素材
        customAppearanceFile()?.let { return it }
        val selected = preferences.getString(state.key, null)
        val direct = selected?.let { File(assetDir, it) }?.takeIf { it.isFile }
        if (direct != null) return direct
        if (state != PetState.IDLE) return fileFor(PetState.IDLE)
        return null
    }

    fun randomFileFor(state: PetState): File? {
        customAppearanceFile()?.let { return it }
        val category = categoryFor(state)
        val files = categoryFiles(category)
        if (files.isNotEmpty()) return files.random(Random)
        return fileFor(state)
    }

    fun hasCustomAppearance(): Boolean =
        preferences.getBoolean(KEY_HAS_CUSTOM, false) && customAppearanceFile() != null

    fun customIdea(): String? = preferences.getString(KEY_CUSTOM_IDEA, null)

    fun installGeneratedAppearance(pngBytes: ByteArray, idea: String): File {
        val target = File(assetDir, CUSTOM_FILE_NAME)
        val temp = File(assetDir, "$CUSTOM_FILE_NAME.tmp")
        FileOutputStream(temp).use { output ->
            output.write(pngBytes)
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            FileOutputStream(target).use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            temp.delete()
        } else if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        require(target.isFile && target.length() > 0L) { "无法保存自定义外观" }
        preferences.edit()
            .putBoolean(KEY_HAS_CUSTOM, true)
            .putString(KEY_CUSTOM_IDEA, idea.trim().take(120))
            .apply()
        invalidateAppearanceSyncCache()
        return target
    }

    fun clearCustomAppearance() {
        File(assetDir, CUSTOM_FILE_NAME).delete()
        preferences.edit()
            .putBoolean(KEY_HAS_CUSTOM, false)
            .remove(KEY_CUSTOM_IDEA)
            .apply()
        restoreBundledDefaults()
        invalidateAppearanceSyncCache()
    }

    /** 用于好友同步的当前形象字节与短哈希（会缩小；按源文件缓存，避免每次压缩哈希漂移）。 */
    fun appearancePayload(): Pair<String, ByteArray>? {
        val file = customAppearanceFile()
            ?: fileFor(PetState.IDLE)
            ?: categoryFiles("random").firstOrNull()
            ?: return null
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(syncLock) {
            val cachedHash = syncCacheHash
            val cachedBytes = syncCacheBytes
            if (key == syncCacheKey && cachedHash != null && cachedBytes != null) {
                return cachedHash to cachedBytes
            }
            val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
            if (raw.isEmpty()) return null
            val bytes = compressForFriendSync(raw) ?: raw
            if (bytes.isEmpty()) return null
            val hash = sha16(bytes)
            syncCacheKey = key
            syncCacheHash = hash
            syncCacheBytes = bytes
            return hash to bytes
        }
    }

    fun appearanceHash(): String = appearancePayload()?.first.orEmpty()

    private fun compressForFriendSync(raw: ByteArray): ByteArray? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val maxSide = 256
        val scale = minOf(
            1f,
            maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        )
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            }
        }
        val out = java.io.ByteArrayOutputStream()
        // 固定质量，尽量保证同图哈希稳定
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        if (!scaled.isRecycled) scaled.recycle()
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun customAppearanceFile(): File? {
        if (!preferences.getBoolean(KEY_HAS_CUSTOM, false)) return null
        return File(assetDir, CUSTOM_FILE_NAME).takeIf { it.isFile }
    }

    private fun restoreBundledDefaults() {
        setBundled(PetState.IDLE, R.drawable.pet_01)
        setBundled(PetState.TOUCH, R.drawable.pet_02)
        setBundled(PetState.HAPPY, R.drawable.pet_03)
        setBundled(PetState.FEED, R.drawable.pet_04)
        setBundled(PetState.SAD, R.drawable.pet_05)
        setBundled(PetState.SLEEP, R.drawable.pet_06)
    }

    private fun importBundledCategories() {
        val previousVersion = preferences.getInt(KEY_BUNDLED_VERSION, 0)
        if (previousVersion >= BUNDLED_VERSION) return
        if (previousVersion < 3) {
            File(assetDir, "sleep/17.jpg").delete()
        }
        val categories = context.assets.list("pet_assets") ?: emptyArray()
        categories.forEach { category ->
            val targetDir = File(assetDir, category).apply { mkdirs() }
            (context.assets.list("pet_assets/$category") ?: emptyArray()).forEach { name ->
                val target = File(targetDir, name)
                if (!target.isFile) {
                    context.assets.open("pet_assets/$category/$name").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        preferences.edit().putInt(KEY_BUNDLED_VERSION, BUNDLED_VERSION).apply()
    }

    private fun categoryFiles(category: String): List<File> =
        File(assetDir, category).listFiles()?.filter { it.isFile } ?: emptyList()

    private fun categoryFor(state: PetState): String = when (state) {
        PetState.IDLE -> "random"
        PetState.TOUCH -> "click"
        PetState.FEED -> "eat"
        PetState.PET -> "happy"
        PetState.WALK -> "walk"
        PetState.SLEEP -> "sleep"
        PetState.HAPPY -> "happy"
        PetState.SAD -> "sad"
        PetState.ANGRY -> "sad"
        PetState.AIR -> "play"
        PetState.HIDDEN -> "bored"
        PetState.PLAY -> "play"
        PetState.RANDOM -> "random"
        PetState.BORED -> "bored"
    }

    private fun setBundled(state: PetState, resourceId: Int) {
        val target = File(assetDir, "${state.key}_default.png")
        context.resources.openRawResource(resourceId).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        preferences.edit().putString(state.key, target.name).apply()
    }

    companion object {
        private const val PREFERENCES = "pet_assets"
        private const val KEY_BUNDLED_VERSION = "bundled_categories_version"
        private const val KEY_HAS_CUSTOM = "has_custom_appearance"
        private const val KEY_CUSTOM_IDEA = "custom_appearance_idea"
        private const val CUSTOM_FILE_NAME = "custom_generated.png"
        private const val BUNDLED_VERSION = 3

        private val syncLock = Any()
        @Volatile private var syncCacheKey: String? = null
        @Volatile private var syncCacheHash: String? = null
        @Volatile private var syncCacheBytes: ByteArray? = null

        fun invalidateAppearanceSyncCache() {
            synchronized(syncLock) {
                syncCacheKey = null
                syncCacheHash = null
                syncCacheBytes = null
            }
        }

        fun sha16(bytes: ByteArray): String {
            val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
            return dig.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
