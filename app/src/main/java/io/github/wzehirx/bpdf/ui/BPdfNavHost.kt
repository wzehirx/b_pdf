package io.github.wzehirx.bpdf.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.wzehirx.bpdf.feature.images.ImagesToPdfScreen
import io.github.wzehirx.bpdf.feature.merge.MergeScreen
import io.github.wzehirx.bpdf.feature.metadata.MetadataScreen
import io.github.wzehirx.bpdf.feature.organize.OrganizeScreen
import io.github.wzehirx.bpdf.feature.pages.PageSelectMode
import io.github.wzehirx.bpdf.feature.pages.PageSelectScreen
import io.github.wzehirx.bpdf.feature.pdfimages.PdfToImagesScreen
import io.github.wzehirx.bpdf.feature.protect.ProtectScreen
import io.github.wzehirx.bpdf.feature.repair.RepairScreen
import io.github.wzehirx.bpdf.feature.rotate.RotateScreen
import io.github.wzehirx.bpdf.feature.compress.CompressScreen
import io.github.wzehirx.bpdf.feature.split.SplitScreen
import io.github.wzehirx.bpdf.feature.unlock.UnlockScreen
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
        composable(Tool.ORGANIZE.route) {
            OrganizeScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.ROTATE.route) {
            RotateScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.IMAGES_TO_PDF.route) {
            ImagesToPdfScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.PDF_TO_IMAGES.route) {
            PdfToImagesScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.PROTECT.route) {
            ProtectScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.UNLOCK.route) {
            UnlockScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.COMPRESS.route) {
            CompressScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.METADATA.route) {
            MetadataScreen(onBack = { navController.popBackStack() })
        }
        composable(Tool.REPAIR.route) {
            RepairScreen(onBack = { navController.popBackStack() })
        }
    }
}
