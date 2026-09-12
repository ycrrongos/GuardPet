package dev.pranav.reef.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.MindfulLaunchActivity
import dev.pranav.reef.R
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.BLOCKER_CHANNEL_ID
import dev.pranav.reef.util.FocusStats
import dev.pranav.reef.util.HabitHook
import dev.pranav.reef.util.KeyEventHook
import dev.pranav.reef.util.MindfulLaunchManager
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.NotificationHelper.BLOCKER_GROUP_KEY
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
import dev.pranav.reef.util.NotificationHelper.syncRoutineNotification
import dev.pranav.reef.util.WebsiteBlocklist
import dev.pranav.reef.util.WebsiteLimits
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("AccessibilityPolicy")
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var keyguardManager: KeyguardManager? = null
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var screenReceiverRegistered = false
    private var lastCheckedPackage: String? = null
    private var lastCheckedClass: String? = null
    private var lastAppCheckAtMs = 0L
    private var foregroundPackage: String? = null
    private var foregroundClassName: String? = null
    private var enforcedForegroundPackage: String? = null
    private var lastBackBlockKey: String? = null
    private var lastBackBlockAtMs = 0L

    private var activeBrowserPackage: String? = null
    private var activeBrowserConfig: BrowserConfig? = null
    private val browserPackages = mutableSetOf<String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    WebsiteUsageTracker.stopTracking()
                }

                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    // Start tracking again if we are already in a browser with a domain
                    // but we will let onAccessibilityEvent handle the current domain state
                }
            }
        }
    }

    private val websiteLimitPollRunnable = object : Runnable {
        override fun run() {
            try {
                val currentDomain = WebsiteUsageTracker.getCurrentTrackingDomain()
                if (currentDomain != null && WebsiteLimits.hasLimit(
                        currentDomain
                    )
                ) {
                    val limit = WebsiteLimits.getLimit(currentDomain)
                    val usage = WebsiteUsageTracker.getDailyUsage(currentDomain)
                    Log.d("BlockerService", "limit=$limit, usage=$usage for $currentDomain")
                    if (usage >= limit) {
                        WebsiteUsageTracker.stopTracking()
                        activeBrowserConfig?.let { config ->
                            performRedirect(config)
                            showBlockedNotification(
                                currentDomain,
                                UsageTracker.BlockReason.DAILY_LIMIT,
                                isWebsite = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BlockerService", "Website limit poll error", e)
            }
            handler.postDelayed(this, 5000L) // Poll every 5 seconds
        }
    }

    private val routinePollRunnable = object : Runnable {
        override fun run() {
            try {
                RoutineSessionManager.evaluateAndSync(this@BlockerService)
                syncRoutineNotification(this@BlockerService)
            } catch (e: Exception) {
                Log.e("BlockerService", "Routine poll error", e)
            }
            handler.postDelayed(this, ROUTINE_POLL_INTERVAL_MS)
        }
    }

    private data class BrowserConfig(
        val urlBarId: String,
        val suggestionBoxId: String,
        val isSuggestionBoxEqualToGo: Boolean = false,
        val suggestionBoxChildIndex: Int = 0
    )

    private val commonResourceConfigs = listOf(
        Triple("url_bar", "omnibox_suggestions_dropdown", false), // Chrome, Brave, Edge, Vivaldi
        Triple("mozac_browser_toolbar_url_view", "sfcnt", false), // Firefox
        Triple("url_field", "right_state_button", true), // Opera
        Triple("location_bar_edit_text", "location_bar_edit_text", false), // Samsung
        Triple("omnibarTextInput", "omnibarTextInput", false) // DuckDuckGo
    )

    private val redirectUrl = "about:blank"

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connectionState.value = false
        configureService()
        createNotificationChannel()
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        WebsiteUsageTracker.init(this)
        WebsiteLimits.init(this)
        refreshBrowserPackages()

        if (!isPrefsInitialized) {
            val deviceContext = createDeviceProtectedStorageContext()
            prefs = deviceContext.getSharedPreferences("prefs", MODE_PRIVATE)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }

        handler.removeCallbacks(routinePollRunnable)
        handler.removeCallbacks(websiteLimitPollRunnable)
        handler.post(routinePollRunnable)
        handler.post(websiteLimitPollRunnable)
        instance = this
        _connectionState.value = true
    }

    private fun configureService() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (KeyEventHook.onKeyEvent(this, event)) return true
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (keyguardManager?.isKeyguardLocked == true) return

        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (foregroundPackage != pkg || foregroundClassName != className) {
                foregroundPackage = pkg
                foregroundClassName = className
                // 同包切换 Activity / 面时允许再次评估（返回键拦截不能整包 sticky）
                enforcedForegroundPackage = null
            }

            if (!browserPackages.contains(pkg)) {
                WebsiteUsageTracker.stopTracking()
                activeBrowserPackage = null
                activeBrowserConfig = null
            } else {
                activeBrowserPackage = pkg
            }
        }

        if (pkg == packageName) return

        // Content events can arrive after another app has already taken the foreground.
        // Ignoring those stale events prevents repeatedly forcing Home for the same launch.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            foregroundPackage != null &&
            pkg != foregroundPackage
        ) {
            return
        }

        if (activeBrowserPackage == pkg) {
            val root = rootInActiveWindow ?: event.source ?: return
            val config = findBrowserConfig(root, pkg)
            if (config != null) {
                activeBrowserConfig = config
                val urlBarNode = findUrlBarNode(root, config.urlBarId)
                if (urlBarNode != null) {
                    val url = extractUrlFromNode(urlBarNode)
                    if (url != null) {

                        Log.d("BlockerService", "Found url=$url in node $urlBarNode")
                        val domain = sanitizeUrl(url)

                        if (WebsiteBlocklist.isBlocked(domain)) {
                            WebsiteUsageTracker.stopTracking()
                            performRedirect(config)
                            showBlockedNotification(
                                domain,
                                UsageTracker.BlockReason.DAILY_LIMIT,
                                isWebsite = true
                            )
                            return
                        }

                        if (WebsiteLimits.hasLimit(domain)) {
                            WebsiteUsageTracker.startTracking(domain)
                            val limit = WebsiteLimits.getLimit(domain)
                            val usage = WebsiteUsageTracker.getDailyUsage(domain)
                            if (usage >= limit) {
                                WebsiteUsageTracker.stopTracking()
                                performRedirect(config)
                                showBlockedNotification(
                                    domain,
                                    UsageTracker.BlockReason.DAILY_LIMIT,
                                    isWebsite = true
                                )
                                return
                            }
                        } else {
                            WebsiteUsageTracker.stopTracking()
                        }
                    } else {
                        WebsiteUsageTracker.stopTracking()
                    }
                }
            }
        }

        val shouldCheckApp = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                HabitHook.isPackageWatched(pkg) ||
                    event.contentChangeTypes !=
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION

            else -> false
        }

        if (shouldCheckApp && shouldHandleAppCheck(
                pkg,
                activityClass = foregroundClassName,
                forceForHabitWatch = HabitHook.isPackageWatched(pkg)
            )
        ) {
            handleAppBlockCheck(pkg, foregroundClassName)
        }
    }

    /**
     * 同包内切 Activity（微信主界面→朋友圈）必须重新评估。
     * 旧逻辑只按 package debounce，800ms 内进娱乐面会被吞掉 → Active 拦截「完全没用」。
     */
    private fun shouldHandleAppCheck(
        pkg: String,
        activityClass: String?,
        forceForHabitWatch: Boolean = false
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val debounce = if (forceForHabitWatch) HABIT_SURFACE_DEBOUNCE_MS else APP_CHECK_DEBOUNCE_MS
        val classChanged = forceForHabitWatch &&
            !activityClass.isNullOrBlank() &&
            activityClass != lastCheckedClass
        val isDuplicate =
            !classChanged && pkg == lastCheckedPackage && now - lastAppCheckAtMs < debounce
        if (!isDuplicate) {
            lastCheckedPackage = pkg
            lastCheckedClass = activityClass
            lastAppCheckAtMs = now
        }
        return !isDuplicate
    }

    private fun refreshBrowserPackages() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            browserPackages.clear()
            for (info in resolveInfos) {
                browserPackages.add(info.activityInfo.packageName)
            }
            // Always include known ones as fallback
            browserPackages.addAll(
                listOf(
                    "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
                    "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser"
                )
            )
        } catch (e: Exception) {
            Log.e("BlockerService", "Error refreshing browsers", e)
        }
    }

    private fun findBrowserConfig(root: AccessibilityNodeInfo, pkg: String): BrowserConfig? {
        // 1. Try common resource names with current package first
        for ((urlRes, suggestRes, isGo) in commonResourceConfigs) {
            val urlId = "$pkg:id/$urlRes"
            val nodes = root.findAccessibilityNodeInfosByViewId(urlId)
            if (!nodes.isNullOrEmpty()) {
                return BrowserConfig(urlId, "$pkg:id/$suggestRes", isGo)
            }
        }

        // 2. Try hardcoded full IDs in priority order (Chrome, Mozilla, Opera)
        val hardcoded = listOf(
            Triple(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/omnibox_suggestions_dropdown",
                false
            ),
            Triple(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/sfcnt",
                false
            ),
            Triple(
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/right_state_button",
                true
            )
        )
        for ((urlId, suggestId, isGo) in hardcoded) {
            val nodes = root.findAccessibilityNodeInfosByViewId(urlId)
            if (!nodes.isNullOrEmpty()) {
                return BrowserConfig(urlId, suggestId, isGo)
            }
        }
        return null
    }

    private fun findUrlBarNode(
        root: AccessibilityNodeInfo,
        fullId: String
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        return if (!nodes.isNullOrEmpty()) nodes[0] else null
    }

    private fun extractUrlFromNode(node: AccessibilityNodeInfo): String? {
        if (node.isFocused) return null
        val text = node.text?.toString() ?: return null
        if (text.isBlank() || !text.contains('.') || text.contains(' ')) return null
        return text
    }

    private fun sanitizeUrl(url: String): String {
        return url.lowercase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .substringBefore('/')
    }

    private fun performRedirect(config: BrowserConfig) {
        val initialRoot = rootInActiveWindow ?: return
        val urlBar = findUrlBarNode(initialRoot, config.urlBarId) ?: return
        urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        handler.postDelayed({
            val editRoot = rootInActiveWindow ?: return@postDelayed
            val editText = findUrlBarNode(editRoot, config.urlBarId) ?: return@postDelayed
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    redirectUrl
                )
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val finalRoot = rootInActiveWindow ?: return@postDelayed
                performGoAction(finalRoot, config)
            }, 300)
        }, 300)
    }

    private fun performGoAction(root: AccessibilityNodeInfo, config: BrowserConfig) {
        val nodes = root.findAccessibilityNodeInfosByViewId(config.suggestionBoxId) ?: return
        val box = nodes.firstOrNull() ?: return
        if (config.isSuggestionBoxEqualToGo) {
            box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val child = box.getChild(config.suggestionBoxChildIndex)
            if (child != null) {
                child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun handleAppBlockCheck(pkg: String, activityClass: String?) {
        if (enforcedForegroundPackage == pkg) return

        if (prefs.getBoolean("focus_mode", false)) {
            if (Whitelist.isWhitelisted(pkg)) return

            FocusStats.recordBlockEvent(pkg, "focus_mode")
            enforcedForegroundPackage = pkg
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (!HabitHook.notifyBlock(this, "focus", pkg, null)) {
                showFocusModeNotification(pkg)
            }
            return
        }

        val habit = HabitHook.evaluate(this, pkg, activityClass)
        if (habit.testHint != null) {
            // Test mode: GuardPet already toasts; do not navigate away.
            return
        }
        if (habit.shouldBlock) {
            if (habit.pressBack) {
                val key = "$pkg:${activityClass.orEmpty()}:${habit.blockReason.orEmpty()}"
                val now = SystemClock.elapsedRealtime()
                if (key == lastBackBlockKey && now - lastBackBlockAtMs < 900L) {
                    return
                }
                lastBackBlockKey = key
                lastBackBlockAtMs = now
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                enforcedForegroundPackage = pkg
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            if (!HabitHook.notifyBlock(this, "habit", pkg, habit.blockReason)) {
                showHabitBlockNotification(pkg, habit.blockReason)
            }
            return
        }

        if (MindfulLaunchManager.isEnabled() && MindfulLaunchManager.isMindfulApp(pkg)) {
            if (!MindfulLaunchManager.isCurrentlyUnlocked(pkg)) {
                enforcedForegroundPackage = pkg
                val intent = Intent(this, MindfulLaunchActivity::class.java).apply {
                    putExtra("target_package", pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                return
            }
        }

        val blockReason = UsageTracker.checkBlockReason(this, pkg)
        if (blockReason != UsageTracker.BlockReason.NONE) {
            enforcedForegroundPackage = pkg
            performGlobalAction(GLOBAL_ACTION_HOME)
            showBlockedNotification(pkg, blockReason)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBlockedNotification(
        pkgOrUrl: String,
        reason: UsageTracker.BlockReason,
        isWebsite: Boolean = false
    ) {
        if (!notificationManager.areNotificationsEnabled()) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkgOrUrl, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkgOrUrl
        }
        val contentText = when (reason) {
            UsageTracker.BlockReason.ROUTINE_LIMIT -> if (isWebsite) getString(R.string.website_blocked_by_routine) else getString(
                R.string.blocked_by_routine,
                appName
            )

            else -> if (isWebsite) getString(
                R.string.website_reached_limit,
                appName
            ) else getString(R.string.reached_limit, appName)
        }

        val titleText =
            if (isWebsite) getString(R.string.website_blocked) else getString(R.string.app_blocked)

        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(BLOCKER_GROUP_KEY)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(pkgOrUrl.hashCode(), notification)

        val summary = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setGroup(BLOCKER_GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        notificationManager.notify(NotificationHelper.BLOCKER_SUMMARY_ID, summary)
    }

    @SuppressLint("MissingPermission")
    private fun showHabitBlockNotification(pkg: String, reason: String?) {
        if (!notificationManager.areNotificationsEnabled()) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        val body = reason?.takeIf { it.isNotBlank() }
            ?: getString(R.string.you_were_using, appName)
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.habit_guard_blocked))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("habit_$pkg".hashCode(), notification)
    }

    @SuppressLint("MissingPermission")
    private fun showFocusModeNotification(pkg: String) {
        if (!notificationManager.areNotificationsEnabled()) return
        if (!prefs.getBoolean("focus_reminders", true)) return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.distraction_blocked))
            .setContentText(getString(R.string.you_were_using, appName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("focus_$pkg".hashCode(), notification)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupConnection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupConnection()
        super.onDestroy()
    }

    fun dumpVisibleText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        try {
            collectVisibleText(root, texts)
        } finally {
            root.recycle()
        }
        return texts
    }

    /** 带 resource-id / text / contentDescription 的可见节点快照（视频简介定位用）。 */
    fun dumpVisibleNodes(): List<A11yNodeSnap> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<A11yNodeSnap>()
        try {
            collectVisibleNodes(root, out)
        } finally {
            root.recycle()
        }
        return out
    }

    fun clickNodeByText(candidates: List<String>): Boolean {
        if (candidates.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClickByText(root, candidates)
        } finally {
            root.recycle()
        }
    }

    /** contentDescription 以 suffix 结尾（如 B 站标题行「…，展开」）。 */
    fun clickByContentDescSuffix(suffix: String): Boolean {
        if (suffix.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClickByDescSuffix(root, suffix)
        } finally {
            root.recycle()
        }
    }

    /** viewId 以 idSuffix 结尾（如 `arrow` → `…/arrow`），点自身或可点父节点。 */
    fun clickByViewIdSuffix(idSuffix: String): Boolean {
        if (idSuffix.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClickByViewIdSuffix(root, idSuffix)
        } finally {
            root.recycle()
        }
    }

    private fun findAndClickByText(node: AccessibilityNodeInfo, candidates: List<String>): Boolean {
        if (!node.isVisibleToUser) {
            // still walk children; some parents marked not visible incorrectly
        }
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hit = candidates.any { c ->
            text.equals(c, ignoreCase = true) ||
                desc.equals(c, ignoreCase = true) ||
                (c.length >= 2 && (text == c || desc == c))
        }
        if (hit && clickSelfOrParent(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (findAndClickByText(child, candidates)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun findAndClickByDescSuffix(node: AccessibilityNodeInfo, suffix: String): Boolean {
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.endsWith(suffix) && clickSelfOrParent(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (findAndClickByDescSuffix(child, suffix)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun findAndClickByViewIdSuffix(node: AccessibilityNodeInfo, idSuffix: String): Boolean {
        val vid = node.viewIdResourceName.orEmpty()
        val hit = vid == idSuffix ||
            vid.endsWith("/$idSuffix") ||
            vid.endsWith(":$idSuffix")
        if (hit && clickSelfOrParent(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (findAndClickByViewIdSuffix(child, idSuffix)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        while (parent != null) {
            try {
                if (parent.isClickable &&
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
            } finally {
                val next = parent.parent
                parent.recycle()
                parent = next
            }
        }
        return false
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (!node.isVisibleToUser) return
        val raw = node.text?.toString()?.trim().orEmpty().ifBlank {
            node.contentDescription?.toString()?.trim().orEmpty()
        }
        if (raw.isNotEmpty()) out.add(raw)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectVisibleText(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun collectVisibleNodes(node: AccessibilityNodeInfo, out: MutableList<A11yNodeSnap>) {
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            out.add(
                A11yNodeSnap(
                    viewId = viewId,
                    text = text,
                    contentDescription = desc,
                    clickable = node.isClickable,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
            )
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectVisibleNodes(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun cleanupConnection() {
        instance = null
        _connectionState.value = false
        KeyEventHook.notifyDisconnected()
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            screenReceiverRegistered = false
        }
        handler.removeCallbacks(websiteLimitPollRunnable)
        handler.removeCallbacks(routinePollRunnable)
        foregroundPackage = null
        foregroundClassName = null
        enforcedForegroundPackage = null
        lastBackBlockKey = null
        activeBrowserPackage = null
        activeBrowserConfig = null
        WebsiteUsageTracker.stopTracking()
    }

    companion object {
        private const val ROUTINE_POLL_INTERVAL_MS = 30_000L
        private const val APP_CHECK_DEBOUNCE_MS = 500L
        private const val HABIT_SURFACE_DEBOUNCE_MS = 800L

        private val _connectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

        val isConnected: Boolean
            get() = _connectionState.value

        @Volatile
        var instance: BlockerService? = null
            private set

        fun captureVisibleText(): List<String> = instance?.dumpVisibleText().orEmpty()

        fun captureVisibleNodes(): List<A11yNodeSnap> = instance?.dumpVisibleNodes().orEmpty()

        /**
         * 框选提取：中心点在 rect 内 → 去掉父子重复 → 按屏幕 Y 聚类成行 → 行内按 X 排序。
         * 返回「一行一条」字符串，供分词在行间插入换行标记（对齐 NovaText 视觉换行）。
         */
        fun captureVisibleTextInRect(rect: android.graphics.Rect): List<String> {
            val raw = instance?.dumpVisibleNodes().orEmpty()
                .filter { it.centerInside(rect) && it.displayText.isNotBlank() }
            if (raw.isEmpty()) return emptyList()
            val leaves = preferLeafTextNodes(raw)
            if (leaves.isEmpty()) return emptyList()
            return clusterTextLines(leaves)
        }

        /** 丢掉包住更小文本节点的父节点，减少「整段 + 子词」重复。 */
        private fun preferLeafTextNodes(nodes: List<A11yNodeSnap>): List<A11yNodeSnap> {
            return nodes.filter { node ->
                val area = node.area().takeIf { it > 0 } ?: return@filter true
                nodes.none { other ->
                    if (other === node) return@none false
                    val otherArea = other.area()
                    otherArea in 1 until area &&
                        other.centerInside(
                            android.graphics.Rect(node.left, node.top, node.right, node.bottom)
                        )
                }
            }
        }

        private fun clusterTextLines(nodes: List<A11yNodeSnap>): List<String> {
            val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
            val lines = mutableListOf<MutableList<A11yNodeSnap>>()
            for (node in sorted) {
                val cy = (node.top + node.bottom) / 2
                val last = lines.lastOrNull()
                if (last == null) {
                    lines.add(mutableListOf(node))
                    continue
                }
                val ref = last.first()
                val refCy = (ref.top + ref.bottom) / 2
                val refH = (ref.bottom - ref.top).coerceAtLeast(1)
                val nodeH = (node.bottom - node.top).coerceAtLeast(1)
                val thresh = maxOf(18, minOf(refH, nodeH) * 2 / 3)
                if (kotlin.math.abs(cy - refCy) <= thresh) {
                    last.add(node)
                } else {
                    lines.add(mutableListOf(node))
                }
            }
            return lines.map { line ->
                line.sortedBy { it.left }
                    .map { it.displayText.trim() }
                    .filter { it.isNotEmpty() }
                    .fold("") { acc, piece ->
                        if (acc.isEmpty()) piece
                        else if (needsLatinSpace(acc, piece)) "$acc $piece"
                        else acc + piece
                    }
            }.filter { it.isNotBlank() }
        }

        private fun needsLatinSpace(left: String, right: String): Boolean {
            val a = left.lastOrNull() ?: return false
            val b = right.firstOrNull() ?: return false
            fun latin(c: Char) = c.isLetterOrDigit() && c.code < 0x3000
            return latin(a) && latin(b)
        }

        fun currentForegroundPackage(): String? = instance?.foregroundPackage

        fun currentForegroundClassName(): String? = instance?.foregroundClassName

        fun performGlobalBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        /** 点击可见文案（用于展开简介等）；优先精确匹配。 */
        fun clickVisibleText(vararg labels: String): Boolean =
            instance?.clickNodeByText(labels.toList()) == true

        fun clickByContentDescSuffix(suffix: String): Boolean =
            instance?.clickByContentDescSuffix(suffix) == true

        fun clickByViewIdSuffix(idSuffix: String): Boolean =
            instance?.clickByViewIdSuffix(idSuffix) == true

        fun tryStartActivity(intent: Intent): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

/** 无障碍树可见节点快照。 */
data class A11yNodeSnap(
    val viewId: String,
    val text: String,
    val contentDescription: String,
    val clickable: Boolean,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val idSuffix: String
        get() = viewId.substringAfterLast('/', viewId.substringAfterLast(':'))

    val displayText: String
        get() = text.ifBlank { contentDescription }

    fun intersects(rect: android.graphics.Rect): Boolean {
        if (right <= left || bottom <= top) return false
        return !(right < rect.left || left > rect.right || bottom < rect.top || top > rect.bottom)
    }

    fun centerInside(rect: android.graphics.Rect): Boolean {
        if (right <= left || bottom <= top) return false
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        return rect.contains(cx, cy)
    }

    fun area(): Int {
        val w = (right - left).coerceAtLeast(0)
        val h = (bottom - top).coerceAtLeast(0)
        return w * h
    }
}
