package dev.pranav.reef.screens

import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pranav.reef.util.Whitelist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistViewModel(
    private val launcherApps: LauncherApps,
    private val packageManager: PackageManager,
    private val currentPackageName: String
) : ViewModel() {

    private val _uiState = mutableStateOf<AllowedAppsState>(AllowedAppsState.Loading)
    private var allApps = listOf<WhitelistedApp>()

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _showSystemApps = mutableStateOf(false)
    val showSystemApps: State<Boolean> = _showSystemApps

    private val _onlyLaunchable = mutableStateOf(true)
    val onlyLaunchable: State<Boolean> = _onlyLaunchable

    val uiState: State<AllowedAppsState> = _uiState

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val profiles = launcherApps.profiles
                val allAppsList = mutableListOf<WhitelistedApp>()

                profiles.forEach { userHandle ->
                    val launcherActivities = launcherApps.getActivityList(null, userHandle)
                    val launchablePackages =
                        launcherActivities.map { it.applicationInfo.packageName }.toSet()

                    val allInstalledApps =
                        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

                    val combined = allInstalledApps
                        .filter { it.packageName != currentPackageName }
                        .map { appInfo ->
                            val isLaunchable = launchablePackages.contains(appInfo.packageName)
                            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                            WhitelistedApp(
                                packageName = appInfo.packageName,
                                label = appInfo.loadLabel(packageManager).toString(),
                                isWhitelisted = Whitelist.isWhitelisted(appInfo.packageName),
                                user = userHandle,
                                isSystemApp = isSystem,
                                isLaunchable = isLaunchable
                            )
                        }
                    allAppsList.addAll(combined)
                }
                allAppsList.distinctBy { it.packageName + it.user.hashCode() }.sortedBy { it.label }
            }
            allApps = apps
            updateFilteredList()
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        updateFilteredList()
    }

    fun toggleSystemApps() {
        _showSystemApps.value = !_showSystemApps.value
        updateFilteredList()
    }

    fun toggleOnlyLaunchable() {
        _onlyLaunchable.value = !_onlyLaunchable.value
        updateFilteredList()
    }

    private fun updateFilteredList() {
        val query = _searchQuery.value
        val showSystem = _showSystemApps.value
        val onlyLaunch = _onlyLaunchable.value

        val filtered = allApps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)

            val matchesSystem = showSystem || !app.isSystemApp
            val matchesLaunchable = !onlyLaunch || app.isLaunchable

            matchesQuery && matchesSystem && matchesLaunchable
        }
        _uiState.value = AllowedAppsState.Success(filtered)
    }

    fun toggleWhitelist(app: WhitelistedApp) {
        if (app.isWhitelisted) Whitelist.unwhitelist(app.packageName)
        else Whitelist.whitelist(app.packageName)

        allApps = allApps.map {
            if (it.packageName == app.packageName && it.user == app.user) {
                it.copy(isWhitelisted = !it.isWhitelisted)
            } else {
                it
            }
        }
        updateFilteredList()
    }
}
