# b PDF — Design Spec

Date: 2026-10-21
Status: Approved by owner (decisions below were chosen by the owner; owner delegated
remaining decisions and asked for a finished, fully tested app at the end).

## 1. Goal

b PDF is a free, open-source (Apache-2.0), fully offline Android PDF toolkit with
iLovePDF-like functionality, built **accessibility-first**: every flow must be fully
usable with TalkBack (and Switch Access / keyboard), without relying on sight or on
drag gestures.

Success criteria:
- Every tool works end-to-end on real PDFs and is covered by automated engine tests.
- Every screen passes the accessibility checklist in §5 and has Compose semantics tests.
- Each milestone builds a debug APK, is tagged, has a CHANGELOG entry and a GitHub
  release, so regressions can be bisected per version.

## 2. Owner decisions

| Topic | Decision |
|---|---|
| UI | Kotlin + Jetpack Compose (Material 3) |
| PDF engine | PdfBox-Android (com.tom-roush:pdfbox-android) + Android `PdfRenderer` for rasterising |
| License | Apache-2.0 |
| Languages | Turkish + English (system locale; per-app language on Android 13+) |
| Scope | All feasible offline tools (§4) |

## 3. Architecture

Single Gradle module `app` (package `io.github.wzehirx.bpdf`), minSdk 26, targetSdk 36,
Java 17 bytecode. Layers:

```
ui/          theme, navigation, home tool grid, shared components (a11y-aware)
feature/<t>/ one package per tool: <T>Screen.kt (Compose) + <T>ViewModel.kt
core/pdf/    engine: one class per operation, pure input->output, no UI
core/files/  Storage Access Framework helpers, file naming, recent files (DataStore)
core/a11y/   announcement helper, semantics helpers, reorderable-list actions
core/work/   OperationRunner: coroutine job + progress/cancel/error state
```

Data flow for every tool:
1. User picks input(s) via SAF (`OpenDocument` / `OpenMultipleDocuments` / `GetContent`
   for images, `TakePicture` for camera).
2. User sets options on a form (all standard, labelled controls).
3. `OperationRunner` runs the `core/pdf` operation on `Dispatchers.IO`, streaming to a
   cache temp file, exposing `Idle | Running(progress) | Success(result) | Error(msg)`.
4. On success the user saves via SAF `CreateDocument` (default name suggested) and/or
   shares via `FileProvider`. Result screen is announced and receives focus.

Engine API shape (each operation is independently testable):

```kotlin
interface PdfOperation<O> { suspend fun run(input: OperationInput, options: O, out: File, progress: (Float) -> Unit): OperationResult }
```
Inputs are `File`s copied from content URIs into cache (so the engine never touches
`ContentResolver`), which keeps engine tests JVM/Robolectric-only.

Errors: encrypted-without-password, corrupt file, out-of-memory (large scans), invalid
page ranges → mapped to localized, specific messages; never a raw stack trace.
PDFs are loaded with `MemoryUsageSetting.setupTempFileOnly()` to survive large files.

## 4. Tools

| # | Tool | Notes |
|---|---|---|
| 1 | Merge | ordered file list; reorder via TalkBack custom actions (move up/down/top/bottom, remove) |
| 2 | Split | by ranges ("1-3,5"), every N pages, or one file per page; multi-output saved to a chosen folder (`OpenDocumentTree`) or zipped |
| 3 | Remove pages | page range input + page grid selection |
| 4 | Extract pages | page range input + page grid selection |
| 5 | Organize | page thumbnails; per-page custom actions: move up/down/to position, rotate left/right, delete, duplicate |
| 6 | Rotate | all / selected pages, 90/180/270 |
| 7 | Compress | low/medium/high presets: re-encode images as JPEG with quality + max DPI downscale, strip metadata optional; reports size before/after |
| 8 | Images → PDF | gallery (multi) + camera; page size (A4/Letter/fit image), orientation, margin; reorder like Merge |
| 9 | PDF → Images | JPG/PNG, DPI (72/150/300), selected pages; via `PdfRenderer` |
| 10 | PDF → Text | `PDFTextStripper`, save .txt or read in-app |
| 11 | Protect | AES-256 user/owner password, permission toggles (print, copy, modify) |
| 12 | Unlock | remove password (requires the password) |
| 13 | Watermark | text, font size, opacity, rotation, position (center/diagonal/tiled), pages |
| 14 | Page numbers | position (6 spots), format ("{n}", "{n} / {total}", "Page {n}"), start number, font size, pages |
| 15 | Metadata | view/edit title, author, subject, keywords, creator |
| 16 | Repair | lenient load + re-save |
| 17 | Sign | typed signature (font) or drawn signature (drawing is optional; typed path is fully accessible), placement by page + named position |
| 18 | Accessible viewer | page images with text alternative; "Text mode" shows extracted text as a scrollable, heading-per-page list; page navigation actions; open from other apps (`ACTION_VIEW` application/pdf) |
| 19 | OCR | Tesseract4Android (Apache-2.0) with bundled `tessdata_fast` tur+eng; produces searchable text and .txt; scanned PDF → text for screen-reader users |

Out of scope (need a server / proprietary libs): Office/HTML ↔ PDF, cloud storage, AI.

## 5. Accessibility requirements (definition of done for every screen)

1. Every actionable element has a meaningful label (`contentDescription` or text); icons
   that duplicate text are `contentDescription = null`.
2. Screen titles marked `heading()`; section titles too. Logical focus order.
3. No gesture-only interaction: anything draggable has equivalent custom actions
   (`CustomAccessibilityAction`) **and** visible buttons.
4. Touch targets ≥ 48dp; text supports 200% font scale without clipping (scrollable).
5. Progress and results announced via polite live regions; errors via assertive
   live region and focus moves to the error/result.
6. Selected/checked state exposed via `selected`/`toggleableState`/`stateDescription`.
7. Colour contrast WCAG AA in light and dark themes; no information by colour alone.
8. Page thumbnails expose "Page N of M" plus rotation/selection state.
9. Every screen has a Compose UI test asserting labels, headings and custom actions.

## 6. Testing

- Engine: Robolectric unit tests per operation using PDFs generated in-test
  (create N pages with text → run op → reload and assert page count/rotation/text/
  encryption/size).
- UI: Compose UI tests on Robolectric (semantics assertions per §5).
- Lint: Android Lint with accessibility checks as errors.
- CI: GitHub Actions — build, unit tests, lint on every push; on tag `v*` build APK
  and publish a GitHub Release with the APK and CHANGELOG notes.

## 7. Versioning & delivery

Semantic versions; one tag per milestone; `CHANGELOG.md` (Keep a Changelog).
Milestones:
- v0.1.0 — scaffold, theme, home grid, file picking/saving, OperationRunner, a11y core, CI
- v0.2.0 — Merge, Split, Remove, Extract, Rotate
- v0.3.0 — Organize, Images→PDF, PDF→Images
- v0.4.0 — Protect, Unlock, Metadata, Repair
- v0.5.0 — Compress, Watermark, Page numbers
- v0.6.0 — PDF→Text, Accessible viewer, open-with intent
- v0.7.0 — Sign, OCR
- v1.0.0 — polish, full a11y audit, README/screens, release

## 8. Progress & resumption

`docs/PROGRESS.md` is the single source of truth for what is done and what is next,
updated and pushed after every task, so work can resume after usage-limit resets or a
container restart (`scripts/setup-android-sdk.sh` re-provisions the SDK).
