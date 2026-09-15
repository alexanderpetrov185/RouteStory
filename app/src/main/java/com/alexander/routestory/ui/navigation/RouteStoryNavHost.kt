package com.alexander.routestory.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alexander.routestory.ui.screens.HistoryScreen
import com.alexander.routestory.ui.screens.HomeScreen
import com.alexander.routestory.ui.screens.TrackDetailScreen
import com.alexander.routestory.ui.screens.TrackingScreen

/** Route constants for app navigation. */
object Routes {
    const val HOME = "home"
    const val TRACKING = "tracking"
    const val HISTORY = "history"
    const val ARG_TRACK_ID = "trackId"
    const val TRACK_DETAIL = "track/{$ARG_TRACK_ID}"

    fun trackDetail(id: Long) = "track/$id"
}

@Composable
fun RouteStoryNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartTracking = { navController.navigate(Routes.TRACKING) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) }
            )
        }
        composable(Routes.TRACKING) {
            TrackingScreen()
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenTrack = { id -> navController.navigate(Routes.trackDetail(id)) }
            )
        }
        composable(
            route = Routes.TRACK_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_TRACK_ID) { type = NavType.LongType })
        ) {
            TrackDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}