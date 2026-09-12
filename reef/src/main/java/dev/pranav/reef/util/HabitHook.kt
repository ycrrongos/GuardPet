package dev.pranav.reef.util

import android.content.Context

data class HabitEval(
    val shouldBlock: Boolean,
    val sleepLock: Boolean,
    val blockReason: String? = null,
    val testHint: String? = null,
    val watchedForSurfaces: Boolean = false,
    /** true = GLOBAL_ACTION_BACK（面级禁用）；false = HOME（整包/催睡） */
    val pressBack: Boolean = false
) {
    companion object {
        val NONE = HabitEval(shouldBlock = false, sleepLock = false)
        val BLOCK = HabitEval(shouldBlock = true, sleepLock = false)
        val SLEEP_LOCK = HabitEval(shouldBlock = true, sleepLock = true)

        fun block(reason: String? = null, pressBack: Boolean = false) =
            HabitEval(
                shouldBlock = true,
                sleepLock = false,
                blockReason = reason,
                pressBack = pressBack
            )

        fun sleepLock(reason: String? = null) =
            HabitEval(shouldBlock = true, sleepLock = true, blockReason = reason, pressBack = false)

        fun test(hint: String, watched: Boolean = true) =
            HabitEval(
                shouldBlock = false,
                sleepLock = false,
                testHint = hint,
                watchedForSurfaces = watched
            )

        fun watch() =
            HabitEval(shouldBlock = false, sleepLock = false, watchedForSurfaces = true)
    }
}

/**
 * App module registers hooks so Reef's blocker can apply habit rules
 * without depending on GuardPet classes.
 */
object HabitHook {
    @Volatile
    var evaluator: ((Context, String, String?) -> HabitEval)? = null

    @Volatile
    var packageWatched: ((String) -> Boolean)? = null

    /**
     * 拦截反馈 UI。返回 true 表示已由桌宠气泡等处理，Blocker 不再发系统通知。
     * kind: `habit` | `focus`
     */
    @Volatile
    var blockFeedback: ((Context, String, String, String?) -> Boolean)? = null

    fun evaluate(
        context: Context,
        packageName: String,
        activityClass: String? = null
    ): HabitEval {
        return evaluator?.invoke(context, packageName, activityClass) ?: HabitEval.NONE
    }

    fun isPackageWatched(packageName: String): Boolean {
        return packageWatched?.invoke(packageName) ?: false
    }

    fun notifyBlock(
        context: Context,
        kind: String,
        packageName: String,
        reason: String? = null
    ): Boolean = blockFeedback?.invoke(context, kind, packageName, reason) == true
}
