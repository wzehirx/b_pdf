package io.github.wzehirx.bpdf.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.wzehirx.bpdf.feature.merge.MergeScreen
import io.github.wzehirx.bpdf.feature.pages.PageSelectMode
import io.github.wzehirx.bpdf.feature.pages.PageSelectScreen
import io.github.wzehirx.bpdf.feature.split.SplitScreen
import io.github.wzehirx.bpdf.ui.home.HomeScreen

const val HOME_ROUTE = "home"

/**
 * App navigation graph. Each feature task registers its screen here with
 * `composable(Tool.X.route) { ... }` (use `navController.popBackStack()` for back).
 */
@Composable
fun BPdfNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            HomeScreen(onOpenTool = { navController.navigate(it.route) })
        }
        composable(Tool.MERGE.route) {
            MergeScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.SPLIT.route) {
            SplitScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.REMOVE_PAGES.route) {
            PageSelectScreen(PageSelectMode.REMOVE, onBack = { navController.popBackStack() })
        }
        composable(Tool.EXTRACT_PAGES.route) {
            PageSelectScreen(PageSelectMode.EXTRACT, onBack = { navController.popBackStack() })
        }
    }
}
