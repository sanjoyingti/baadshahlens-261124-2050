package com.aibuilt.baadshahlens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aibuilt.baadshahlens.data.appContainer
import com.aibuilt.baadshahlens.data.settings.AppSettings
import com.aibuilt.baadshahlens.nav.Route
import com.aibuilt.baadshahlens.ui.screens.AboutScreen
import com.aibuilt.baadshahlens.ui.screens.CameraScreen
import com.aibuilt.baadshahlens.ui.screens.DownloadsScreen
import com.aibuilt.baadshahlens.ui.screens.GalleryDetailScreen
import com.aibuilt.baadshahlens.ui.screens.GalleryScreen
import com.aibuilt.baadshahlens.ui.screens.LegalScreen
import com.aibuilt.baadshahlens.ui.screens.RecordScreen
import com.aibuilt.baadshahlens.ui.screens.ResultScreen
import com.aibuilt.baadshahlens.ui.screens.SettingsScreen
import com.aibuilt.baadshahlens.ui.theme.BaadshahTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root of the app. Owns the single route table and the navigation host.
 */
@Composable
fun PikaApp() {
    val context = LocalContext.current
    val container = remember { context.appContainer() }
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val nav = rememberNavController()
    val start = remember { if (settings.legalAccepted) Route.Camera.path else Route.Legal.path }

    LaunchedEffect(settings.autoDeleteGenerated) {
        if (settings.autoDeleteGenerated) {
            withContext(Dispatchers.IO) {
                container.gallery.deleteGeneratedOlderThan(System.currentTimeMillis() - 24L * 3600_000L)
            }
        }
    }

    BaadshahTheme {
        NavHost(navController = nav, startDestination = start) {

            composable(Route.Legal.path) {
                LegalScreen(
                    onBack = {
                        nav.navigate(Route.Camera.path) {
                            popUpTo(Route.Legal.path) { inclusive = true }
                        }
                    },
                    onAccepted = {
                        nav.navigate(Route.Camera.path) {
                            popUpTo(Route.Legal.path) { inclusive = true }
                        }
                    }
                )
            }

            composable(Route.Camera.path) {
                CameraScreen(
                    onBack = { nav.navigate(Route.Settings.path) },
                    onOpenGallery = { nav.navigate(Route.Gallery.path) },
                    onOpenResult = { before, after, mask, occ, tier ->
                        nav.navigate(Route.Result.of(before, after, mask, occ, tier))
                    }
                )
            }

            composable(Route.Record.path) {
                RecordScreen(onBack = { nav.navigate(Route.Camera.path) })
            }

            composable(Route.Gallery.path) {
                GalleryScreen(
                    onBack = { nav.navigate(Route.Camera.path) },
                    onOpenItem = { id -> nav.navigate(Route.GalleryDetail.of(id)) }
                )
            }

            composable(
                route = Route.GalleryDetail.path,
                arguments = listOf(navArgument(Route.GalleryDetail.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                GalleryDetailScreen(
                    onBack = { nav.popBackStack() },
                    itemId = entry.arguments?.getLong(Route.GalleryDetail.ARG_ID) ?: 0L
                )
            }

            composable(
                route = Route.Result.path,
                arguments = listOf(
                    navArgument(Route.Result.ARG_BEFORE) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Route.Result.ARG_AFTER) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Route.Result.ARG_MASK) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Route.Result.ARG_OCC) { type = NavType.StringType; defaultValue = "" },
                    navArgument(Route.Result.ARG_TIER) { type = NavType.StringType; defaultValue = "FAST" }
                )
            ) { entry ->
                ResultScreen(
                    onBack = { nav.popBackStack() },
                    before = Route.Result.decode(entry.arguments?.getString(Route.Result.ARG_BEFORE)),
                    after = Route.Result.decode(entry.arguments?.getString(Route.Result.ARG_AFTER)),
                    mask = Route.Result.decode(entry.arguments?.getString(Route.Result.ARG_MASK)),
                    occ = Route.Result.decode(entry.arguments?.getString(Route.Result.ARG_OCC)),
                    tier = entry.arguments?.getString(Route.Result.ARG_TIER).orEmpty()
                )
            }

            composable(Route.Settings.path) {
                SettingsScreen(
                    onBack = { nav.navigate(Route.Camera.path) },
                    onOpenDownloads = { nav.navigate(Route.Downloads.path) },
                    onOpenAbout = { nav.navigate(Route.About.path) },
                    onOpenLegal = { nav.navigate(Route.Legal.path) }
                )
            }

            composable(Route.Downloads.path) {
                DownloadsScreen(onBack = { nav.popBackStack() })
            }

            composable(Route.About.path) {
                AboutScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
