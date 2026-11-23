package com.tumorrecorder.app.ui.records

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.ui.detail.RecordDetailScreen
import com.tumorrecorder.app.ui.detail.RecordDetailViewModel
import com.tumorrecorder.app.ui.theme.TumorRecorderTheme

/**
 * Dedicated Activity for searching and reviewing recorded oncology consultation
 * records, transcripts, post-call voice supplements, and AI summaries.
 */
class ConsultationRecordsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as TumorRecorderApp).container

        setContent {
            TumorRecorderTheme {
                val navController = rememberNavController()
                val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_LIST
                ) {
                    composable(ROUTE_LIST) {
                        val vm: ConsultationRecordsViewModel = viewModel(
                            factory = ConsultationRecordsViewModel.factory(container)
                        )
                        ConsultationRecordsScreen(
                            viewModel = vm,
                            onOpenRecord = { id ->
                                navController.navigate("detail/$id")
                            },
                            onBack = { finish() }
                        )
                    }

                    composable(
                        route = ROUTE_DETAIL,
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
                }
            }
        }
    }

    companion object {
        private const val ROUTE_LIST = "records_list"
        private const val ROUTE_DETAIL = "detail/{recordId}"
    }
}
