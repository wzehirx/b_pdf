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
- Cache hygiene: `remove()` deletes the input's cache copy; `resetResult()` deletes the discarded output (any input change, `clearResult`, or a new run); `onCleared()` deletes all imports and the output; a failed run deletes its own output. `BPdfApp` also purges cache entries older than 24h at start.
- Busy guard: `move/remove/addFiles/merge` return early while importing or `Running`, and the UI disables the picker, row buttons and custom actions (`enabled = !busy`), so a late import error can never replace a running panel.
- Cancellation: the engine takes `isCancelled: () -> Boolean` (the VM passes `{ !context.isActive }`, captured from `currentCoroutineContext()` inside `runner.start`), polls it between inputs and after saving, throws `CancellationException` and leaves no output. Only parsing maps to `PdfError.Corrupt`; disk/temp IO maps to `PdfError.Io`.
- One-shot UI effects (e.g. "saved") are a `Flow` backed by a buffered `Channel`, collected in the screen.

## Screen
- Stateful `<X>Screen(onBack, viewModel = viewModel(factory = ...Factory))` wires pickers (`rememberSaveDocumentLauncher`, `Context.shareFile`), the announcer and the ViewModel.
- Stateless `<X>ScreenContent(state, callbacks...)` holds all layout, built from the shared components (16dp gutters, 24dp between sections, 12dp inside): `ToolScaffold` -> `ToolSection { instructions, PickFilesButton, SelectedFileList/SelectedPdfCard }` -> `OptionsCard { ChoiceRow, fields, switches }` -> `PrimaryButton` (full width, `disabledReason` is spoken while disabled) -> `OperationStatusPanel`. Do not add ad-hoc paddings or spacers in a feature screen.
- `OperationStatusPanel<T>` is typed: `successText = { result -> ... }` receives the result. It moves focus to the result or error text; "Saved" is announced from `savedEvents`.
- Result text and success announcements come from string resources; the success summary is a plurals resource.

## Wiring
- `ui/Tool.kt`: set `available = true` on the entry.
- `ui/BPdfNavHost.kt`: `composable(Tool.X.route) { XScreen(onBack = { navController.popBackStack() }) }`.
- Strings: every visible text and announcement in `values/strings.xml` and `values-tr/strings.xml`.
- `CHANGELOG.md`: one line under `## [Unreleased]` -> `### Added`.

## Tests
- Engine: real PDFs from `TestPdfs`; assert page counts and per-page text.
- ViewModel: `Dispatchers.setMain(UnconfinedTestDispatcher())`, inject the same dispatcher, real `FileRepository` under Robolectric, `file://` Uris. Import/export hop to the real IO dispatcher, so wait on the state (`runBlocking { withTimeout { state.first { ... } } }`).
  - Never assert on `vm.state.value` right after an intent or an async step. The combined state is republished by a collector that can still be busy on another thread (an import/export just finished there), so a read can be stale. Wait for the exact state you expect with a small helper, e.g. `awaitState { it.files.map { f -> f.displayName } == listOf("c.pdf", "a.pdf") }`, and make wait conditions specific (a count or name, not just `!importing`, which a stale initial state already satisfies). Reading `state.value` is fine only after such an await has returned.
  - Inject the same test dispatcher as `ioDispatcher` for everything the ViewModel runs itself (runner, page-count read on pick). For a gated `StandardTestDispatcher` ("slow" VM) keep calling `scheduler.advanceUntilIdle()` while waiting for the picked file.
- Screen: drive `<X>ScreenContent` with hand-built state; assert custom actions, disabled state + `stateDescription`, and callbacks. Use `performScrollTo()` before clicking below the fold.
- Custom actions: read `SemanticsActions.CustomActions` from the row node, pick the action by label, call `rule.runOnUiThread { action.action() }`, and assert the callback (`onMove(1, 0)`, `onRemove(1)`). Also assert the first row has no "Move up" and the last no "Move down", and a busy state removes all actions.
- Also test a `Failed` state: error text focused, Retry fires the callback.

## Single-input variant (one PDF in, one PDF out, e.g. Rotate)
- Engine: `run(input: File, out: File, <options>, password: String? = null, progress, isCancelled): Int` (page count). Load with `PdfLoader.load(input, password)`; same Io/Corrupt mapping and delete-on-failure.
- State: `file: ImportedFile?` instead of a list, plus option fields; no reorder list, a single-file row with Remove/Replace. Picker is `multiple = false`; busy guard and cache cleanup are the same (replacing or removing the file deletes the old copy).
- Page count comes from the engine result (or `PdfLoader.pageCount` right after import, to validate page ranges). Password: on `PdfError.PasswordRequired`/`WrongPassword` show `PasswordDialog`, keep the entered password in the ViewModel and rerun with it (Task 13 wires this for all tools).
