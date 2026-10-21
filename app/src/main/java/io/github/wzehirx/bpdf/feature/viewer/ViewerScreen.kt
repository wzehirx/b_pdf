package io.github.wzehirx.bpdf.feature.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.stateDescription
import io.github.wzehirx.bpdf.ui.components.ComposeWhenNear
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.a11y.requestFocusWhenReady
import io.github.wzehirx.bpdf.feature.organize.parsePosition
import io.github.wzehirx.bpdf.ui.Tool
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.toUserMessage
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Tools that take one PDF and can be handed the open document ("Open with tool…"). */
private val OPEN_WITH_TOOLS = listOf(
    Tool.SPLIT, Tool.REMOVE_PAGES, Tool.EXTRACT_PAGES, Tool.ORGANIZE, Tool.ROTATE, Tool.PDF_TO_IMAGES,
    Tool.PDF_TO_TEXT, Tool.PROTECT, Tool.UNLOCK, Tool.COMPRESS, Tool.WATERMARK, Tool.PAGE_NUMBERS,
    Tool.METADATA, Tool.REPAIR, Tool.SIGN,
)

/** Largest width a page is drawn at, in pixels, however far it is zoomed (keeps bitmaps below ~100 MB). */
private const val MAX_RENDER_WIDTH_PX = 2400

/** Stateful entry point: wires the ViewModel; [onOpenInTool] opens another tool with the document. */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onOpenInTool: (Tool, Uri) -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    ViewerScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = { uris -> uris.firstOrNull()?.let(viewModel::openPicked) },
        onGoTo = viewModel::goTo,
        onSetMode = viewModel::setMode,
        onZoomIn = viewModel::zoomIn,
        onZoomOut = viewModel::zoomOut,
        onSetZoom = viewModel::setZoom,
        pageImage = viewModel::pageImage,
        pageText = viewModel::pageText,
        openWithTools = remember { OPEN_WITH_TOOLS.filter { it.available } },
        onOpenWithTool = { tool ->
            scope.launch {
                val uri = viewModel.prepareHandOff()
                if (uri != null) onOpenInTool(tool, uri) else announce(resources.getString(R.string.error_generic))
            }
        },
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        announce = announce,
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers or a renderer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreenContent(
    state: ViewerUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onGoTo: (Int) -> Unit,
    onSetMode: (ViewerMode, Int?) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onSetZoom: (Float) -> Unit,
    pageImage: suspend (pageIndex: Int, widthPx: Int) -> Bitmap?,
    pageText: suspend (pageIndex: Int) -> String?,
    openWithTools: List<Tool>,
    onOpenWithTool: (Tool) -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    announce: (String) -> Unit = rememberAnnouncer(),
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val file = state.file
    val count = state.pageCount
    val fileKey = file?.file?.path
    var goToOpen by rememberSaveable { mutableStateOf(false) }
    var textShown by rememberSaveable { mutableStateOf(false) }
    // The page whose node should take accessibility focus (after an action or the Go to dialog, which
    // would otherwise drop TalkBack focus when the old page node goes away).
    var focusPage by remember { mutableStateOf<Int?>(null) }
    // Text mode: every heading is composed; this is where each page starts in the scrolling column.
    val textScroll = key(state.mode, fileKey) { rememberScrollState() }
    val headingTops = remember(fileKey, count) { mutableStateListOf<Int>().apply { repeat(count) { add(0) } } }
    // The page being looked at: the shown one, or (text mode) the last heading at or above the top of the view.
    val viewedPage by remember(state.mode, state.currentPage, textScroll, headingTops) {
        derivedStateOf {
            if (state.mode == ViewerMode.TEXT) {
                val y = textScroll.value + 8
                var page = 0
                for (i in headingTops.indices) if (headingTops[i] <= y) page = i else break
                page
            } else {
                state.currentPage
            }
        }
    }
    var anchorJob by remember { mutableStateOf<Job?>(null) }
    // Scrolls text mode to a page and keeps it there while pages above load and change height.
    val scrollToHeading: (Int) -> Unit = { page ->
        anchorJob?.cancel()
        anchorJob = scope.launch {
            withTimeoutOrNull(800) { snapshotFlow { headingTops.getOrElse(page) { 0 } }.collect { textScroll.scrollTo(it) } }
        }
    }
    LaunchedEffect(state.mode, fileKey) {
        if (state.mode == ViewerMode.TEXT && state.currentPage > 0) scrollToHeading(state.currentPage)
    }

    // Opening a document: say what opened and put focus on its first page.
    var openedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(fileKey) {
        if (file != null && openedFor != fileKey) {
            openedFor = fileKey
            announce(resources.getQuantityString(R.plurals.viewer_opened, count, file.displayName, count))
            focusPage = state.currentPage
        }
    }

    // Moves are spoken here and only here: the page label is not a live region, so every move
    // (button, action, dialog or swipe) says "Page N of M" exactly once. Moves from an action or
    // the dialog also take focus to the destination; a button keeps focus on itself.
    val label: (Int) -> String = { resources.getString(R.string.viewer_page_label, it + 1, count) }
    val move: (Int, Boolean) -> Unit = { target, takeFocus ->
        if (count > 0) {
            val page = target.coerceIn(0, count - 1)
            onGoTo(page)
            if (state.mode == ViewerMode.TEXT) scrollToHeading(page)
            announce(label(page))
            if (takeFocus) focusPage = page
        }
    }
    val switchMode: () -> Unit = {
        onSetMode(if (state.mode == ViewerMode.PAGE) ViewerMode.TEXT else ViewerMode.PAGE, viewedPage)
    }
    val pageActions: (Int) -> List<CustomAccessibilityAction> = { index ->
        buildList {
            if (index < count - 1) add(CustomAccessibilityAction(resources.getString(R.string.viewer_next)) { move(index + 1, true); true })
            if (index > 0) add(CustomAccessibilityAction(resources.getString(R.string.viewer_previous)) { move(index - 1, true); true })
            add(CustomAccessibilityAction(resources.getString(R.string.viewer_go_to)) { goToOpen = true; true })
            add(
                CustomAccessibilityAction(
                    resources.getString(if (state.mode == ViewerMode.PAGE) R.string.viewer_text_mode else R.string.viewer_page_mode),
                ) { switchMode(); true },
            )
        }
    }

    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    if (goToOpen && file != null) {
        GoToPageDialog(
            count = count,
            current = viewedPage + 1,
            onConfirm = { goToOpen = false; move(it - 1, true) },
            onDismiss = { goToOpen = false },
        )
    }

    val pickAnother = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickFile(listOf(uri))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file?.displayName ?: stringResource(R.string.tool_viewer_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (file != null) {
                        ViewerMenu(
                            busy = state.importing,
                            tools = openWithTools,
                            onChooseAnother = { pickAnother.launch(arrayOf("application/pdf")) },
                            onOpenWithTool = onOpenWithTool,
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Large fonts or a short (landscape) window: the controls scroll inside this share, so the page keeps the rest.
            val controlsMaxHeight = maxHeight * 0.45f
            val controls: @Composable () -> Unit = {
                Controls(
                    viewedPage = viewedPage,
                    count = count,
                    mode = state.mode,
                    zoom = state.zoom,
                    onPrevious = { move(viewedPage - 1, false) },
                    onNext = { move(viewedPage + 1, false) },
                    onGoToDialog = { goToOpen = true },
                    onSwitchMode = switchMode,
                    onZoomIn = {
                        onZoomIn()
                        announce(resources.getString(R.string.viewer_zoom_value, (ViewerViewModel.zoomInTarget(state.zoom) * 100).roundToInt()))
                    },
                    onZoomOut = {
                        onZoomOut()
                        announce(resources.getString(R.string.viewer_zoom_value, (ViewerViewModel.zoomOutTarget(state.zoom) * 100).roundToInt()))
                    },
                )
            }
            Column(Modifier.fillMaxSize()) {
                val errorFocus = remember { FocusRequester() }
                val error = state.error
                if (error != null) {
                    LaunchedEffect(error) { errorFocus.requestFocus() }
                    val message = error.toUserMessage(resources) +
                        if (state.removedFromRecents) " " + stringResource(R.string.viewer_removed_from_recents) else ""
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(errorFocus).focusable(),
                    )
                }
                if (state.importing) {
                    Text(
                        stringResource(R.string.single_file_importing),
                        modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (file == null) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.viewer_instructions))
                        PickFilesButton(
                            label = stringResource(R.string.single_file_choose),
                            mimeTypes = arrayOf("application/pdf"),
                            multiple = false,
                            onPicked = onPickFile,
                            enabled = !state.importing,
                        )
                    }
                } else if (state.mode == ViewerMode.PAGE) {
                    Box(Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) { controls() }
                    PagePager(
                        state = state,
                        textShown = textShown,
                        onToggleText = { textShown = !textShown },
                        pageImage = pageImage,
                        pageText = pageText,
                        pageActions = pageActions,
                        onSwipedTo = { page -> onGoTo(page); announce(label(page)) },
                        onSetZoom = onSetZoom,
                        focusPage = focusPage,
                        onFocusHandled = { focusPage = null },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else {
                    TextPages(
                        count = count,
                        scrollState = textScroll,
                        headingTops = headingTops,
                        pageText = pageText,
                        pageActions = pageActions,
                        focusPage = focusPage,
                        onFocusHandled = { focusPage = null },
                        controls = controls,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerMenu(
    busy: Boolean,
    tools: List<Tool>,
    onChooseAnother: () -> Unit,
    onOpenWithTool: (Tool) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showTools = false; open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.viewer_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!showTools) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.single_file_replace)) },
                    enabled = !busy,
                    onClick = { open = false; onChooseAnother() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_open_with)) },
                    enabled = !busy && tools.isNotEmpty(),
                    onClick = { showTools = true },
                )
            } else {
                tools.forEach { tool ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tool.title)) },
                        onClick = { open = false; showTools = false; onOpenWithTool(tool) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(
    viewedPage: Int,
    count: Int,
    mode: ViewerMode,
    zoom: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoToDialog: () -> Unit,
    onSwitchMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    val minZoom = stringResource(R.string.viewer_zoom_min)
    val maxZoom = stringResource(R.string.viewer_zoom_max)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Plain text, not a live region: page changes are announced once by the screen.
        Text(
            stringResource(R.string.viewer_page_label, viewedPage + 1, count),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = viewedPage > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.viewer_previous), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onNext, enabled = viewedPage < count - 1, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.viewer_next), textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGoToDialog, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.viewer_go_to), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onSwitchMode, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (mode == ViewerMode.PAGE) R.string.viewer_text_mode else R.string.viewer_page_mode),
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (mode == ViewerMode.PAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onZoomOut,
                    enabled = zoom > ViewerViewModel.MIN_ZOOM,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { if (zoom <= ViewerViewModel.MIN_ZOOM) stateDescription = minZoom },
                ) { Text(stringResource(R.string.viewer_zoom_out), textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = onZoomIn,
                    enabled = zoom < ViewerViewModel.MAX_ZOOM,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { if (zoom >= ViewerViewModel.MAX_ZOOM) stateDescription = maxZoom },
                ) { Text(stringResource(R.string.viewer_zoom_in), textAlign = TextAlign.Center) }
            }
            Text(
                stringResource(R.string.viewer_zoom_value, (zoom * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** One page per screen. Swiping works for sighted users; every move is also a custom action on the page and a button. */
@Composable
private fun PagePager(
    state: ViewerUiState,
    textShown: Boolean,
    onToggleText: () -> Unit,
    pageImage: suspend (Int, Int) -> Bitmap?,
    pageText: suspend (Int) -> String?,
    pageActions: (Int) -> List<CustomAccessibilityAction>,
    onSwipedTo: (Int) -> Unit,
    onSetZoom: (Float) -> Unit,
    focusPage: Int?,
    onFocusHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = key(state.file?.file?.path) { rememberPagerState(initialPage = state.currentPage) { state.pageCount } }
    val currentOnSwiped by rememberUpdatedState(onSwipedTo)
    val shownPage by rememberUpdatedState(state.currentPage)
    // The ViewModel decides the page; the pager follows it, and reports swipes back.
    LaunchedEffect(state.currentPage, pagerState) {
        if (pagerState.currentPage != state.currentPage) pagerState.scrollToPage(state.currentPage)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { if (it != shownPage) currentOnSwiped(it) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = state.zoom <= ViewerViewModel.MIN_ZOOM,
        pageSpacing = 8.dp,
        key = { it },
    ) { index ->
        ViewerPage(
            index = index,
            count = state.pageCount,
            zoom = state.zoom,
            textShown = textShown,
            onToggleText = onToggleText,
            pageImage = pageImage,
            pageText = pageText,
            actions = pageActions(index),
            onSetZoom = onSetZoom,
            takeFocus = focusPage == index,
            onFocusHandled = onFocusHandled,
        )
    }
}

@Composable
private fun ViewerPage(
    index: Int,
    count: Int,
    zoom: Float,
    textShown: Boolean,
    onToggleText: () -> Unit,
    pageImage: suspend (Int, Int) -> Bitmap?,
    pageText: suspend (Int) -> String?,
    actions: List<CustomAccessibilityAction>,
    onSetZoom: (Float) -> Unit,
    takeFocus: Boolean,
    onFocusHandled: () -> Unit,
) {
    val label = stringResource(R.string.viewer_page_label, index + 1, count)
    val pageFocus = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (takeFocus) {
            pageFocus.requestFocusWhenReady()
            onFocusHandled()
        }
    }
    val currentZoom by rememberUpdatedState(zoom)
    BoxWithConstraints(
        Modifier.fillMaxSize().pinchToZoom { factor -> onSetZoom(currentZoom * factor) },
    ) {
        val pageWidth = maxWidth * zoom
        val containerPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // Draw at the next half step up so zooming in sharpens the page without a redraw per pinch frame.
        val renderWidth = min((containerPx * (ceil(zoom * 2) / 2)).roundToInt(), MAX_RENDER_WIDTH_PX).coerceAtLeast(1)
        var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(index) { mutableStateOf(false) }
        // The previous bitmap stays visible while a sharper one is drawn.
        LaunchedEffect(index, renderWidth) {
            val drawn = pageImage(index, renderWidth)
            if (drawn != null) {
                bitmap = drawn.asImageBitmap()
                failed = false
            } else if (bitmap == null) {
                failed = true
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .width(pageWidth)
                        .focusRequester(pageFocus)
                        .focusable()
                        .semantics {
                            contentDescription = label
                            role = Role.Image
                            customActions = actions
                        },
                ) {
                    val image = bitmap
                    if (image != null) {
                        Image(image, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.707f).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (failed) Text(stringResource(R.string.viewer_page_failed, index + 1), modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleText, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (textShown) R.string.viewer_hide_text else R.string.viewer_read_text))
                }
                if (textShown) PageTextBlock(index, pageText)
            }
        }
    }
}

/** Two-finger pinch zooms; one finger is left alone so the page can still be scrolled and swiped. */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    onZoom(factor)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Every page as a heading ("Page N") with its text. The column is not lazy on purpose: all headings
 * stay composed, so a screen reader's heading navigation reaches every page, however far away. Under
 * each heading the text is only built and loaded when the page comes near the window.
 */
@Composable
private fun TextPages(
    count: Int,
    scrollState: ScrollState,
    headingTops: SnapshotStateList<Int>,
    pageText: suspend (Int) -> String?,
    pageActions: (Int) -> List<CustomAccessibilityAction>,
    focusPage: Int?,
    onFocusHandled: () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(scrollState).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        controls()
        for (index in 0 until count) {
            val headingFocus = remember { FocusRequester() }
            LaunchedEffect(focusPage) {
                if (focusPage == index) {
                    headingFocus.requestFocusWhenReady()
                    onFocusHandled()
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { c ->
                        val top = c.positionInParent().y.roundToInt()
                        if (index < headingTops.size && headingTops[index] != top) headingTops[index] = top
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.text_page_header, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .focusRequester(headingFocus)
                        .focusable()
                        .semantics {
                            heading()
                            customActions = pageActions(index)
                        },
                )
                ComposeWhenNear(minHeight = 96.dp) { PageTextBlock(index, pageText) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The text of one page, extracted when this block first appears (so only visible pages are read). */
@Composable
private fun PageTextBlock(index: Int, pageText: suspend (Int) -> String?) {
    val text by produceState<LoadedText>(LoadedText.Loading, index, pageText) {
        value = pageText(index)?.let { LoadedText.Ready(it) } ?: LoadedText.Failed
    }
    when (val t = text) {
        LoadedText.Loading -> Text(stringResource(R.string.viewer_text_loading), style = MaterialTheme.typography.bodyMedium)
        LoadedText.Failed -> Text(stringResource(R.string.viewer_text_failed), style = MaterialTheme.typography.bodyMedium)
        is LoadedText.Ready ->
            if (t.text.isBlank()) {
                Text(stringResource(R.string.text_page_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                SelectionContainer { Text(t.text, style = MaterialTheme.typography.bodyLarge) }
            }
    }
}

private sealed interface LoadedText {
    data object Loading : LoadedText
    data object Failed : LoadedText
    data class Ready(val text: String) : LoadedText
}

/** Asks for a page number; an empty or invalid entry shows a specific error on the field and keeps the dialog open. */
@Composable
private fun GoToPageDialog(count: Int, current: Int, onConfirm: (page: Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val page = parsePosition(text, count)
    val message = when {
        page != null -> null
        text.isBlank() -> if (attempted) stringResource(R.string.viewer_goto_empty, count) else null
        else -> stringResource(R.string.viewer_goto_invalid, text.trim(), count)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_goto_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.viewer_goto_label)) },
                isError = message != null,
                supportingText = { Text(message ?: stringResource(R.string.viewer_goto_hint, count)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().semantics { if (message != null) error(message) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (page != null) onConfirm(page) else attempted = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.viewer_goto_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
