package com.tumorrecorder.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.ui.detail.RecordDetailScreen
import com.tumorrecorder.app.ui.detail.RecordDetailViewModel
import com.tumorrecorder.app.ui.home.HomeScreen
import com.tumorrecorder.app.ui.home.HomeViewModel
import com.tumorrecorder.app.ui.settings.SettingsScreen
import com.tumorrecorder.app.ui.settings.SettingsViewModel

private object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{recordId}"
    const val SETTINGS = "settings"

    fun detail(recordId: Long) = "detail/$recordId"
}

@Composable
fun AppNavHost(
    container: AppContainer,
    initialRecordId: Long?,
    hasRequiredPermissions: Boolean,
    isAccessibilityEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    val navController = rememberNavController()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()

    LaunchedEffect(initialRecordId) {
        if (initialRecordId != null && initialRecordId > 0L) {
            navController.navigate(Routes.detail(initialRecordId)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
            HomeScreen(
                viewModel = vm,
                hasRequiredPermissions = hasRequiredPermissions,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onRequestPermissions = onRequestPermissions,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                onOpenRecord = { id -> navController.navigate(Routes.detail(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("recordId") { type = NavType.LongType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getLong("recordId") ?: -1L
            val vm: RecordDetailViewModel = viewModel(
                factory = RecordDetailViewModel.factory(recordId, container)
            )
            RecordDetailScreen(
                viewModel = vm,
                settings = settings,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                viewModel = vm,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
