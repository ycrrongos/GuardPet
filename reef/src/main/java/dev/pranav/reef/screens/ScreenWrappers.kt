package dev.pranav.reef.screens

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.pranav.reef.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreenWrapper(
    context: Context,
    usageStatsManager: UsageStatsManager,
    launcherApps: LauncherApps,
    packageManager: PackageManager,
    currentPackageName: String,
    onBackPressed: () -> Unit,
    onAppClick: (AppUsageStats) -> Unit
) {
    val viewModel: AppUsageViewModel = viewModel(
        key = "app_usage_viewmodel",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppUsageViewModel(
                    context,
                    usageStatsManager,
                    launcherApps,
                    packageManager,
                    currentPackageName
                ) as T
        }
    )

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.app_usage)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        AppUsageScreen(
            viewModel = viewModel,
            contentPadding = paddingValues,
            onBackPressed = onBackPressed,
            onAppClick = onAppClick
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreenWrapper(
    navController: NavController,
    launcherApps: LauncherApps,
    packageManager: PackageManager,
    currentPackageName: String
) {
    val viewModel: WhitelistViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WhitelistViewModel(launcherApps, packageManager, currentPackageName) as T
        }
    )

    WhitelistScreen(
        onBackPress = { navController.popBackStack() },
        uiState = viewModel.uiState.value,
        onToggle = viewModel::toggleWhitelist,
        searchQuery = viewModel.searchQuery.value,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        showSystemApps = viewModel.showSystemApps.value,
        onlyLaunchable = viewModel.onlyLaunchable.value,
        onToggleSystemApps = viewModel::toggleSystemApps,
        onToggleOnlyLaunchable = viewModel::toggleOnlyLaunchable
    )
}
