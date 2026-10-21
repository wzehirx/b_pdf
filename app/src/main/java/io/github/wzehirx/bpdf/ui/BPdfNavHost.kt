package io.github.wzehirx.bpdf.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import io.github.wzehirx.bpdf.feature.pdftext.PdfToTextScreen
import io.github.wzehirx.bpdf.feature.protect.ProtectScreen
import io.github.wzehirx.bpdf.feature.repair.RepairScreen
import io.github.wzehirx.bpdf.feature.rotate.RotateScreen
import io.github.wzehirx.bpdf.feature.compress.CompressScreen
import io.github.wzehirx.bpdf.feature.watermark.WatermarkScreen
import io.github.wzehirx.bpdf.feature.pagenumbers.PageNumbersScreen
import io.github.wzehirx.bpdf.feature.split.SplitScreen
import io.github.wzehirx.bpdf.feature.unlock.UnlockScreen
import io.github.wzehirx.bpdf.feature.viewer.ViewerScreen
import io.github.wzehirx.bpdf.feature.viewer.ViewerViewModel
import io.github.wzehirx.bpdf.ui.home.HomeRoute

const val HOME_ROUTE = "home"

/**
 * App navigation graph. Each feature task registers its screen here with
 * `composable(Tool.X.route) { ... }` (use `navController.popBackStack()` for back).
 */
/** Registers [tool]'s screen; its route also accepts `?uri=` with a file to start from ("Open with tool…"). */
private fun NavGraphBuilder.toolScreen(tool: Tool, content: @Composable (initialUri: Uri?) -> Unit) {
    composable(
        route = "${tool.route}?uri={uri}",
        arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        content(entry.arguments?.getString("uri")?.takeIf { it.isNotBlank() }?.let(Uri::parse))
    }
}

@Composable
fun BPdfNavHost(navController: NavHostController = rememberNavController()) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            HomeRoute(
                onOpenTool = { navController.navigate(it.route) },
                onOpenRecent = { navController.navigate(IncomingIntents.viewerRoute(Uri.parse(it.uri))) },
            )
        }
        composable(Tool.MERGE.route) { MergeScreen(onBack = back) }
        toolScreen(Tool.SPLIT) { SplitScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.REMOVE_PAGES) { PageSelectScreen(PageSelectMode.REMOVE, onBack = back, initialUri = it) }
        toolScreen(Tool.EXTRACT_PAGES) { PageSelectScreen(PageSelectMode.EXTRACT, onBack = back, initialUri = it) }
        toolScreen(Tool.ORGANIZE) { OrganizeScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.ROTATE) { RotateScreen(onBack = back, initialUri = it) }
        composable(Tool.IMAGES_TO_PDF.route) { ImagesToPdfScreen(onBack = back) }
        toolScreen(Tool.PDF_TO_IMAGES) { PdfToImagesScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.PDF_TO_TEXT) {
            PdfToTextScreen(
                onBack = back,
                onOpenOcr = { navController.navigate(Tool.OCR.route) },
                initialUri = it,
            )
        }
        toolScreen(Tool.PROTECT) { ProtectScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.UNLOCK) { UnlockScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.COMPRESS) { CompressScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.WATERMARK) { WatermarkScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.PAGE_NUMBERS) { PageNumbersScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.METADATA) { MetadataScreen(onBack = back, initialUri = it) }
        toolScreen(Tool.REPAIR) { RepairScreen(onBack = back, initialUri = it) }
        composable(
            route = "${Tool.VIEWER.route}?${ViewerViewModel.URI_ARG}={${ViewerViewModel.URI_ARG}}",
            arguments = listOf(
                navArgument(ViewerViewModel.URI_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            ViewerScreen(
                onBack = back,
                onOpenInTool = { tool, uri -> navController.navigate(IncomingIntents.toolRoute(tool, uri)) },
            )
        }
    }
}
