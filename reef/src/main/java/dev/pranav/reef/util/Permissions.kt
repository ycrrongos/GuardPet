package dev.pranav.reef.util

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.pranav.reef.PermissionsCheckActivity
import dev.pranav.reef.R
import dev.pranav.reef.accessibility.BlockerService


fun Context.isAccessibilityServiceEnabledForBlocker(): Boolean {
    val enabledServices = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    val blockerComponent = ComponentName(this, BlockerService::class.java)

    return enabledServices
        ?.split(':')
        ?.mapNotNull(ComponentName::unflattenFromString)
        ?.any { it == blockerComponent } == true
}

fun Context.isBlockerServiceOperational(): Boolean {
    return isAccessibilityServiceEnabledForBlocker() && BlockerService.isConnected
}

fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        packageName
    )
    return if (mode == AppOpsManager.MODE_DEFAULT) {
        checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else {
        mode == AppOpsManager.MODE_ALLOWED
    }
}

fun Context.hasDndPermission(): Boolean {
    val notificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return false
    return notificationManager.isNotificationPolicyAccessGranted
}

fun Context.hasNotificationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Not required for older versions
    }
}

fun Context.isBatteryOptimizationDisabled(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}

enum class PermissionType {
    OVERLAY,
    ACCESSIBILITY,
    USAGE_STATS,
    NOTIFICATION,
    BATTERY_OPTIMIZATION,
    DND
}

data class PermissionStatus(
    val type: PermissionType,
    val isGranted: Boolean,
    val title: String,
    val description: String
)

fun Context.checkAllPermissions(): List<PermissionStatus> {
    val permissions = mutableListOf<PermissionStatus>()

    permissions.add(
        PermissionStatus(
            type = PermissionType.OVERLAY,
            isGranted = Settings.canDrawOverlays(this),
            title = getString(R.string.overlay_permission),
            description = getString(R.string.overlay_permission_description)
        )
    )

    val accessibilityEnabled = isAccessibilityServiceEnabledForBlocker()
    val accessibilityOperational = accessibilityEnabled && BlockerService.isConnected

    permissions.add(
        PermissionStatus(
            type = PermissionType.ACCESSIBILITY,
            isGranted = accessibilityOperational,
            title = getString(R.string.accessibility_service_name),
            description = if (accessibilityEnabled && !BlockerService.isConnected) {
                getString(R.string.accessibility_service_not_running_description)
            } else {
                getString(R.string.accessibility_service_description)
            }
        )
    )

    permissions.add(
        PermissionStatus(
            type = PermissionType.USAGE_STATS,
            isGranted = hasUsageStatsPermission(),
            title = getString(R.string.usage_access),
            description = getString(R.string.usage_access_description)
        )
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(
            PermissionStatus(
                type = PermissionType.NOTIFICATION,
                isGranted = hasNotificationPermission(),
                title = getString(R.string.notification_permission),
                description = getString(R.string.notification_permission_description)
            )
        )
    }

    permissions.add(
        PermissionStatus(
            type = PermissionType.BATTERY_OPTIMIZATION,
            isGranted = isBatteryOptimizationDisabled(),
            title = getString(R.string.battery_optimization_exception),
            description = getString(R.string.battery_optimization_exception_description)
        )
    )

    permissions.add(
        PermissionStatus(
            type = PermissionType.DND,
            isGranted = hasDndPermission(),
            title = getString(R.string.enable_dnd),
            description = getString(R.string.dnd_description)
        )
    )

    return permissions
}

fun Activity.checkAndRequestMissingPermissions() {
    val allPermissions = checkAllPermissions()
    val missingPermissions = allPermissions.filter { !it.isGranted }

    if (missingPermissions.isNotEmpty()) {
        startActivity(Intent(this, PermissionsCheckActivity::class.java))
    }
}
