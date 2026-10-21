# Changelog

All notable changes to b PDF are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Organize pages tool: see every page as a thumbnail, then move (up, down, to a position), rotate left or right, duplicate or delete pages with screen-reader actions or a per-page menu, undo changes, and save or share the new PDF.
- Page thumbnails: Remove pages, Extract pages and Rotate pages can pick pages from an accessible grid (each page is one labelled, checkable item with "Select all", "Clear selection" and a selected count) instead of typing a range; both stay in sync.

## [0.2.0] - 2026-10-21

### Added
- Merge PDF tool: combine several PDFs in a chosen order (reorder and remove with buttons or screen-reader actions), then save or share the result.
- Split PDF tool: split by page ranges, every N pages, or one file per page; save all parts to a folder or as one ZIP, or share them.
- Remove pages and Extract pages tools: type the pages to delete or keep (ranges such as 2, 4-6); extracted pages keep the order you type.
- Rotate pages tool: turn all or selected pages 90° clockwise, 180° or 90° counter-clockwise; the turn is added to each page's existing rotation.

### Security
- Removing pages guarantees the deleted pages cannot survive inside the saved file (links, bookmarks, named destinations, form fields and other references are cleaned, and the output is verified).

## [0.1.0] - 2026-10-21

### Added
- Android project scaffold: Kotlin, Jetpack Compose (Material 3), PdfBox-Android, Robolectric test setup, Apache-2.0 license.
- PDF engine foundation: `PdfLoader` (temp-file memory mode, password handling), specific error types, and a page-range parser.
- `OperationRunner` with progress, cancel and error state that survives configuration changes.
- File helpers: Storage Access Framework import, safe file naming, save and share through `FileProvider`.
- Accessibility helpers: polite and assertive announcements, reorder custom actions (move up, down, to top, to bottom, remove).
- Shared accessible UI components: tool scaffold, page-range field, password dialog, selected-file list, operation status panel, choice rows.
- Navigation and home screen listing all planned tools (marked "Coming soon").
- English and Turkish string resources for all user-visible text.
- GitHub Actions CI (unit tests, lint, debug build) and a tag-triggered release workflow that publishes the APK with these notes.
- README, CONTRIBUTING, and accessibility guide (English and Turkish).

[Unreleased]: https://github.com/wzehirx/b_pdf/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/wzehirx/b_pdf/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/wzehirx/b_pdf/releases/tag/v0.1.0
