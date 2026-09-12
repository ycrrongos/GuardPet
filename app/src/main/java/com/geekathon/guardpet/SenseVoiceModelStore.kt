package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/** Locates / copies SenseVoice model files from the separate model-pack APK. */
object SenseVoiceModelStore {
    const val PACK_PACKAGE = "com.geekathon.guardpet.sensevoice"

    private const val ASSET_DIR = "sensevoice"
    private const val MODEL_NAME = "model.int8.onnx"
    private const val TOKENS_NAME = "tokens.txt"
    /** int8 SenseVoice is ~229MB; reject tiny/corrupt copies. */
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    private const val MIN_TOKENS_BYTES = 100L

    fun isPackInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(PACK_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun localModelDir(context: Context): File =
        File(context.filesDir, ASSET_DIR)

    fun hasLocalModel(context: Context): Boolean {
        val dir = localModelDir(context)
        val model = File(dir, MODEL_NAME)
        val tokens = File(dir, TOKENS_NAME)
        return model.isFile && model.length() >= MIN_MODEL_BYTES &&
            tokens.isFile && tokens.length() >= MIN_TOKENS_BYTES
    }

    /**
     * Returns filesDir/sensevoice when model+tokens are ready.
     * Copies from the installed pack APK assets on first use.
     */
    fun ensureLocalModel(context: Context): File? {
        if (hasLocalModel(context)) return localModelDir(context)
        if (!isPackInstalled(context)) return null
        val packCtx = runCatching {
            context.createPackageContext(
                PACK_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrElse {
            return null
        }
        val dest = localModelDir(context).apply { mkdirs() }
        return runCatching {
            copyAsset(packCtx, "$ASSET_DIR/$MODEL_NAME", File(dest, MODEL_NAME))
            copyAsset(packCtx, "$ASSET_DIR/$TOKENS_NAME", File(dest, TOKENS_NAME))
            if (hasLocalModel(context)) dest else null
        }.getOrNull()
    }

    fun openInstallHint(context: Context) {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(PACK_PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Toast.makeText(
            context,
            context.getString(R.string.voice_model_install_hint, PACK_PACKAGE),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun copyAsset(packCtx: Context, assetPath: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        packCtx.assets.open(assetPath).use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
