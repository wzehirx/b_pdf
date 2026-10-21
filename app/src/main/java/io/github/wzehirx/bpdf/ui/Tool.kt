package io.github.wzehirx.bpdf.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.wzehirx.bpdf.R

enum class ToolCategory(@StringRes val title: Int) {
    ORGANIZE(R.string.category_organize),
    CONVERT(R.string.category_convert),
    SECURITY(R.string.category_security),
    EDIT(R.string.category_edit),
    VIEW(R.string.category_view),
}

/**
 * Every tool of the app. [available] is flipped to true (and a `composable(route)` is added to
 * `BPdfNavHost`) by the task that implements the tool; until then the home screen shows the
 * tool as "coming soon".
 */
enum class Tool(
    val route: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val icon: ImageVector,
    val category: ToolCategory,
    val available: Boolean = false,
) {
    MERGE("tool/merge", R.string.tool_merge_title, R.string.tool_merge_desc, Icons.Default.Add, ToolCategory.ORGANIZE, available = true),
    SPLIT("tool/split", R.string.tool_split_title, R.string.tool_split_desc, Icons.Default.Menu, ToolCategory.ORGANIZE),
    REMOVE_PAGES("tool/remove_pages", R.string.tool_remove_pages_title, R.string.tool_remove_pages_desc, Icons.Default.Delete, ToolCategory.ORGANIZE),
    EXTRACT_PAGES("tool/extract_pages", R.string.tool_extract_pages_title, R.string.tool_extract_pages_desc, Icons.AutoMirrored.Filled.ExitToApp, ToolCategory.ORGANIZE),
    ORGANIZE("tool/organize", R.string.tool_organize_title, R.string.tool_organize_desc, Icons.AutoMirrored.Filled.List, ToolCategory.ORGANIZE),
    ROTATE("tool/rotate", R.string.tool_rotate_title, R.string.tool_rotate_desc, Icons.Default.Refresh, ToolCategory.ORGANIZE),
    IMAGES_TO_PDF("tool/images_to_pdf", R.string.tool_images_to_pdf_title, R.string.tool_images_to_pdf_desc, Icons.Default.AccountBox, ToolCategory.CONVERT),
    PDF_TO_IMAGES("tool/pdf_to_images", R.string.tool_pdf_to_images_title, R.string.tool_pdf_to_images_desc, Icons.Default.Share, ToolCategory.CONVERT),
    PDF_TO_TEXT("tool/pdf_to_text", R.string.tool_pdf_to_text_title, R.string.tool_pdf_to_text_desc, Icons.Default.Edit, ToolCategory.CONVERT),
    OCR("tool/ocr", R.string.tool_ocr_title, R.string.tool_ocr_desc, Icons.Default.Search, ToolCategory.CONVERT),
    PROTECT("tool/protect", R.string.tool_protect_title, R.string.tool_protect_desc, Icons.Default.Lock, ToolCategory.SECURITY),
    UNLOCK("tool/unlock", R.string.tool_unlock_title, R.string.tool_unlock_desc, Icons.Default.Lock, ToolCategory.SECURITY),
    SIGN("tool/sign", R.string.tool_sign_title, R.string.tool_sign_desc, Icons.Default.Create, ToolCategory.SECURITY),
    COMPRESS("tool/compress", R.string.tool_compress_title, R.string.tool_compress_desc, Icons.Default.KeyboardArrowDown, ToolCategory.EDIT),
    WATERMARK("tool/watermark", R.string.tool_watermark_title, R.string.tool_watermark_desc, Icons.Default.Star, ToolCategory.EDIT),
    PAGE_NUMBERS("tool/page_numbers", R.string.tool_page_numbers_title, R.string.tool_page_numbers_desc, Icons.Default.DateRange, ToolCategory.EDIT),
    METADATA("tool/metadata", R.string.tool_metadata_title, R.string.tool_metadata_desc, Icons.Default.Info, ToolCategory.EDIT),
    REPAIR("tool/repair", R.string.tool_repair_title, R.string.tool_repair_desc, Icons.Default.Build, ToolCategory.EDIT),
    VIEWER("tool/viewer", R.string.tool_viewer_title, R.string.tool_viewer_desc, Icons.Default.PlayArrow, ToolCategory.VIEW),
}
