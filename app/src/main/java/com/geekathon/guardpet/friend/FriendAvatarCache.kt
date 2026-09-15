package com.geekathon.guardpet.friend

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/** 局域网同步的好友形象缓存：`filesDir/friend_avatars/<hash>.png`。 */
object FriendAvatarCache {
    private const val DIR = "friend_avatars"
    const val MAX_BYTES = 480_000

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun fileFor(context: Context, hash: String): File? {
        if (hash.isBlank()) return null
        return File(dir(context), "$hash.png").takeIf { it.isFile && it.length() > 0L }
    }

    fun has(context: Context, hash: String): Boolean = fileFor(context, hash) != null

    fun putBytes(context: Context, hash: String, bytes: ByteArray): File? {
        if (hash.isBlank() || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val target = File(dir(context), "$hash.png")
        val temp = File(dir(context), "$hash.png.tmp")
        return runCatching {
            FileOutputStream(temp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.takeIf { it.isFile }
        }.getOrNull()
    }

    fun putBase64(context: Context, hash: String, b64: String): File? {
        if (b64.isBlank() || b64.length > MAX_BYTES * 2) return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return putBytes(context, hash, bytes)
    }

    fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
