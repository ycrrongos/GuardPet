package com.geekathon.guardpet

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.pranav.reef.timer.OverlayFocusSession
import dev.pranav.reef.util.HabitHook
import dev.pranav.reef.util.KeyEventHook
import java.io.File
import kotlin.concurrent.thread

class GuardInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        FlashNoteStore.init(appContext)
        DayScheduleStore.init(appContext)
        HabitPolicyStore.init(appContext)
        HabitRewardTracker.init(appContext)
        AppActiveCatalog.init(appContext)
        runCatching { ScheduleEndScheduler.rescheduleAll(appContext) }
        HabitHook.evaluator = { host, packageName, activityClass ->
            HabitGuardian.evaluate(host, packageName, activityClass)
        }
        HabitHook.packageWatched = { pkg -> HabitGuardian.isWatched(pkg) }
        HabitHook.blockFeedback = { ctx, kind, pkg, reason ->
            PetBlockBubble.handle(ctx, kind, pkg, reason)
        }
        VolumeChordFlashNote.install(appContext)
        OverlayFocusSession.shared.addOnFocusStarted {
            HabitRewardTracker.onFocusSessionStarted()
        }
        OverlayFocusSession.shared.addOnFocusCompleted {
            HabitRewardTracker.onFocusSessionCompleted(appContext)
            android.widget.Toast.makeText(
                appContext,
                appContext.getString(R.string.focus_complete),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        KeyEventHook.filter = { ctx, event -> VolumeChordFlashNote.onKeyEvent(ctx, event) }
        KeyEventHook.onServiceDisconnected = { VolumeChordFlashNote.reset() }
        HabitAgent.scheduleInitial(appContext)
        FlashNoteStore.observe {
            if (HabitPolicyStore.autoRefresh && HabitPolicyStore.llmApiKey.isNotBlank()) {
                HabitAgent.refreshAsync(appContext, reason = "flash_note")
            }
        }
        runCatching {
            val jiebaDir = File(appContext.filesDir, "jieba")
            jiebaDir.mkdirs()
            System.setProperty("jieba.defaultDir", jiebaDir.absolutePath)
        }
        thread(name = "jieba-init") { TextTokenizer.prepare() }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
