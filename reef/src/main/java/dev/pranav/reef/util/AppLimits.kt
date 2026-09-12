package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

private const val PREF_LIMITS = "app_limits"

object AppLimits {

    private lateinit var prefs: SharedPreferences
    private val limits = mutableMapOf<String, Long>()

    private val reminderSent = mutableMapOf<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_LIMITS, Context.MODE_PRIVATE)
        limits.clear()
        prefs.all.forEach { (k, v) ->
            if (v is Long) limits[k] = v
        }
    }

    fun setLimit(pkg: String, minutes: Int) {
        limits[pkg] = minutes * 60_000L
    }

    fun getLimit(pkg: String): Long = limits[pkg] ?: 0L

    fun hasLimit(pkg: String): Boolean = limits.containsKey(pkg)

    fun removeLimit(pkg: String) {
        limits.remove(pkg)
    }

    fun save() {
        check(::prefs.isInitialized)
        prefs.edit {
            clear()
            limits.forEach { putLong(it.key, it.value) }
        }
    }

    private fun startOfToday(): Long =
        LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun reminderSentToday(pkg: String): Boolean =
        reminderSent[pkg]?.let { it >= startOfToday() } ?: false

    fun markReminder(pkg: String) {
        reminderSent[pkg] = System.currentTimeMillis()
    }
}


object Whitelist {
    private lateinit var sharedPreferences: SharedPreferences

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences("whitelist", Context.MODE_PRIVATE)

        if (sharedPreferences.all.isEmpty()) {
            whitelistAll(allowedApps)

            // Whitelist all system apps by default
            context.packageManager.getInstalledPackages(Process.myUserHandle().hashCode())
                .forEach { pkgInfo ->
                    if ((pkgInfo.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                        whitelist(pkgInfo.packageName)
                    }
                }
        }

        // Whitelist all installed input methods (keyboards)
        val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val inputMethods = inputMethodManager.enabledInputMethodList
        inputMethods.forEach { imi ->
            whitelist(imi.packageName)
        }

        // Whitelist the default SMS app
        val defaultSmsPackage = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
        if (defaultSmsPackage != null) {
            whitelist(defaultSmsPackage)
        }

        // Whitelist the default Phone app
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
        val defaultPhonePackage = telecomManager.defaultDialerPackage
        if (defaultPhonePackage != null) {
            whitelist(defaultPhonePackage)
        }

        // Whitelist the default assistant app
        val intentAssist = android.content.Intent(android.content.Intent.ACTION_ASSIST).apply {
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        val resolveInfoAssist = context.packageManager.resolveActivity(intentAssist, 0)
        val defaultAssistPackage = resolveInfoAssist?.activityInfo?.packageName
        if (defaultAssistPackage != null) {
            whitelist(defaultAssistPackage)
        }

        // Whitelist the default launcher
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, 0)
        val defaultLauncherPackage = resolveInfo?.activityInfo?.packageName
        if (defaultLauncherPackage != null) {
            whitelist(defaultLauncherPackage)
        }

        // Whitelist apps with SYSTEM_ALERT_WINDOW permission
        context.packageManager.getPackagesHoldingPermissions(
            arrayOf(android.Manifest.permission.SYSTEM_ALERT_WINDOW),
            0
        ).forEach { pkg ->
            whitelist(pkg.packageName)
        }
    }

    fun isWhitelisted(packageName: String): Boolean {
        return sharedPreferences.getBoolean(packageName, false)
    }

    fun whitelist(packageName: String) {
        sharedPreferences.edit { putBoolean(packageName, true) }
    }

    fun whitelistAll(set: Set<String>) {
        set.forEach { whitelist(it) }
    }

    fun unwhitelist(packageName: String) {
        sharedPreferences.edit { putBoolean(packageName, false) }
    }

    fun getWhitelistedLaunchableCount(launcherApps: android.content.pm.LauncherApps): Int {
        val launchablePackages =
            launcherApps.getActivityList(null, android.os.Process.myUserHandle())
                .map { it.applicationInfo.packageName }
                .toSet()
        return sharedPreferences.all.count { (pkg, isWhitelisted) ->
            isWhitelisted == true && launchablePackages.contains(pkg)
        }
    }

    val allowedApps = hashSetOf(
        "com.geekathon.guardpet",
        "dev.pranav.reef",
        "dev.pranav.applock",

        "com.google.android.deskclock",
        "com.google.android.calendar",
        "com.google.android.keep",
        "com.google.android.contacts",

        "com.google.android.apps.docs",
        "com.google.android.apps.drive",
        "com.google.android.apps.sheets",
        "com.google.android.apps.slides",
        "com.google.android.apps.maps",
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.google.android.apps.authenticator2",
        "com.google.android.apps.paidtasks",
        "com.google.android.apps.docs.editor.docs",
        "com.google.android.apps.docs.editor.sheets",
        "com.google.android.apps.classroom",
        "com.google.android.apps.giant",
        "com.google.android.apps.tachyon",

        "app.revanced.android.gms", // MicroG / ReVanced GMS
        "net.osmand",
        "com.fsck.k9",
        "bin.mt.plus.canary",
        "com.sadellie.unitto",
        "com.lineageos.aperture.dev",
        "com.lineageos.aperture",
        "com.shazam.android",
        "com.synapsetech.compass",
        "me.jmh.authenticatorpro",
        "md.obsidian",

        "com.slack",
        "com.google.android.gm",
        "com.google.android.apps.meet",
        "com.microsoft.teams",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.nbu.paisa.user",
        "com.fampay.in",
    )
}
