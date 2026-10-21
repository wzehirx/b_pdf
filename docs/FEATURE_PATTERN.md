# Feature pattern: adding a tool

Merge (`feature/merge`, `core/pdf/MergeOperation.kt`) is the reference. Copy its shape.

## Files
- `core/pdf/<X>Operation.kt` - `object` with `run(inputs: List<File>, out: File, ..., progress)`. Takes `File`s only (no Context, no ContentResolver), opens PDFs through `PdfLoader.load`, accepts a `passwords: Map<File, String>` for encrypted inputs, throws `PdfError`/`IllegalArgumentException`, returns what the UI needs (e.g. page count).
- `feature/<x>/<X>ViewModel.kt`, `feature/<x>/<X>Screen.kt`.
- Tests: `core/pdf/<X>OperationTest.kt` (uses `TestPdfs`), `feature/<x>/<X>ViewModelTest.kt`, `feature/<x>/<X>ScreenTest.kt`.

## ViewModel
- Constructor: `FileRepository` plus `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`. Expose `companion object { val Factory = viewModelFactory { initializer { ... (this[APPLICATION_KEY] as BPdfApp).container.fileRepository } } }`.
- One `StateFlow<<X>UiState>` (inputs, `importing`, `operation: OperationState<Result>`); intents are plain functions (`addFiles`, `move`, `remove`, `merge`, `cancel`, `save`, `clearResult`, `retry`).
- Long work goes through `OperationRunner`; output file from `fileRepository.newOutputFile("pdf")`, deleted on failure. Import/save failures become a `Failed` state, never a crash. Changing inputs resets a stale result.
- One-shot UI effects (e.g. "saved") are a `Flow` backed by a buffered `Channel`, collected in the screen.

## Screen
- Stateful `<X>Screen(onBack, viewModel = viewModel(factory = ...Factory))` wires pickers (`rememberSaveDocumentLauncher`, `Context.shareFile`), the announcer and the ViewModel.
- Stateless `<X>ScreenContent(state, callbacks...)` holds all layout: `ToolScaffold` -> instructions -> `PickFilesButton` -> `SelectedFileList` -> primary action (disabled with a `stateDescription` that says why) -> `OperationStatusPanel`.
- Result text and success announcements come from string resources; the success summary is a plurals resource.

## Wiring
- `ui/Tool.kt`: set `available = true` on the entry.
- `ui/BPdfNavHost.kt`: `composable(Tool.X.route) { XScreen(onBack = { navController.popBackStack() }) }`.
- Strings: every visible text and announcement in `values/strings.xml` and `values-tr/strings.xml`.
- `CHANGELOG.md`: one line under `## [Unreleased]` -> `### Added`.

## Tests
- Engine: real PDFs from `TestPdfs`; assert page counts and per-page text.
- ViewModel: `Dispatchers.setMain(UnconfinedTestDispatcher())`, inject the same dispatcher, real `FileRepository` under Robolectric, `file://` Uris. Import/export hop to the real IO dispatcher, so wait on the state (`runBlocking { withTimeout { state.first { ... } } }`).
- Screen: drive `<X>ScreenContent` with hand-built state; assert custom actions, disabled state + `stateDescription`, and callbacks. Use `performScrollTo()` before clicking below the fold.
