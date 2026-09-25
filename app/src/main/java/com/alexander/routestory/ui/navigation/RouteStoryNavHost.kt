package com.alexander.routestory.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.ui.screens.HistoryScreen
import com.alexander.routestory.ui.screens.ProScreen
import com.alexander.routestory.ui.screens.SplashScreen
import com.alexander.routestory.ui.screens.HomeScreen
import com.alexander.routestory.ui.screens.TrackDetailScreen
import com.alexander.routestory.ui.screens.TrackingScreen

/** Route constants for app navigation. */
object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val TRACKING = "tracking"
    const val HISTORY = "history"
    const val PRO = "pro"
    const val ARG_TRACK_ID = "trackId"

    const val TRACK_DETAIL = "track/{$ARG_TRACK_ID}"
    const val TRACK_SUMMARY = "summary/{$ARG_TRACK_ID}"

    fun trackDetail(id: Long) = "track/$id"
    fun trackSummary(id: Long) = "summary/$id"
}

/**
 * Sprint 27: navigation also reacts to what the notification shade does outside the app
 * window:
 *  - a ride was just stored (Stop) -> open its summary even if the app was closed then;
 *  - a ride is recording or paused (e.g. paused from the shade) -> reopen the recording
 *    screen, so the route and its data are never hidden behind Home.
 */
@Composable
fun RouteStoryNavHost(
    modifier: Modifier = Modifier,
    rideState: TrackingState = TrackingState.IDLE,
    saveOutcome: TrackSaveOutcome? = null,
    openTrackingRequest: Int = 0,
    pendingSummaryTrackId: Long? = null,
    /** Ride the export notification wants to open (Sprint 47: no autoplay). */
    pendingTrackId: Long? = null,
    onPendingHandled: () -> Unit = {},
    onPendingTrackHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Stop (in-app button or notification action): show the summary of the stored ride.
    LaunchedEffect(saveOutcome, currentRoute) {
        // Deferred while the splash is up (Sprint 49); re-fires once Home is the route.
        if (currentRoute == null || currentRoute == Routes.SPLASH) return@LaunchedEffect
        val outcome = saveOutcome as? TrackSaveOutcome.Saved ?: return@LaunchedEffect
        navController.navigate(Routes.trackSummary(outcome.trackId)) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
        // Consumes the outcome and the persisted marker once the summary is on screen.
        onPendingHandled()
    }

    // The ride was stored while no window existed: open its summary on this start.
    LaunchedEffect(pendingSummaryTrackId, currentRoute) {
        if (currentRoute == null || currentRoute == Routes.SPLASH) return@LaunchedEffect
        val id = pendingSummaryTrackId ?: return@LaunchedEffect
        navController.navigate(Routes.trackSummary(id)) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
        onPendingHandled()
    }

    // "Видео готово" notification tap: open that ride WITHOUT autoplay (Sprint 47).
    LaunchedEffect(pendingTrackId, currentRoute) {
        if (currentRoute == null || currentRoute == Routes.SPLASH) return@LaunchedEffect
        val id = pendingTrackId ?: return@LaunchedEffect
        navController.navigate(Routes.trackDetail(id)) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
        onPendingTrackHandled()
    }

    // An active ride always wins over Home when the UI appears (notification tap, return from
    // background after a pause). Only keyed on the state, so going Back to Home on purpose is
    // not undone.
    LaunchedEffect(rideState, openTrackingRequest, currentRoute) {
        if (currentRoute == null || currentRoute == Routes.SPLASH) return@LaunchedEffect
        if (rideState == TrackingState.IDLE) return@LaunchedEffect
        if (currentRoute == Routes.TRACKING) return@LaunchedEffect
        navController.navigate(Routes.TRACKING) { launchSingleTop = true }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier
    ) {
        // Sprint 49: logo + app name for ~1 s, then Home (splash popped off the stack).
        composable(Routes.SPLASH) {
            SplashScreen(
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onStartTracking = { navController.navigate(Routes.TRACKING) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenPro = { navController.navigate(Routes.PRO) }
            )
        }
        composable(Routes.TRACKING) {
            // Saving is handled by the nav host, so the screen stays a pure view of the ride.
            TrackingScreen()
        }
        composable(Routes.PRO) {
            ProScreen(onBack = { navController.popBackStack() })
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
            TrackDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenPro = { navController.navigate(Routes.PRO) }
            )
        }
        composable(
            route = Routes.TRACK_SUMMARY,
            arguments = listOf(navArgument(Routes.ARG_TRACK_ID) { type = NavType.LongType })
        ) {
            TrackDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenPro = { navController.navigate(Routes.PRO) },
                isSummary = true
            )
        }
    }
}