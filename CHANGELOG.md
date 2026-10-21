# Changelog

All notable changes to b PDF are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.6.0] - 2026-10-21

### Added
- PDF to text tool: read the text of all or selected pages (each page under its own "Page N" heading), then save it as a .txt file, copy it or share it; a PDF without a text layer says so and points to text recognition (OCR).
- Accessible reader: read a PDF one page per screen (each page is labelled "Page N of M", with a "Read page text" button) or in text mode, a list with one heading per page so screen-reader heading navigation jumps from page to page. Next page, Previous page, Go to page… and Switch to text mode are screen-reader actions on the page and also buttons; zoom with the Zoom in / Zoom out buttons or by pinching. Page changes are announced once. Text of a page is read only when it is shown, so large documents open quickly.
- Open with: PDFs opened or shared from other apps (file managers, mail) open in the reader; "Open with tool…" in the reader hands the document to any single-file tool.
- Recent files: the home screen lists the documents last opened in the reader; each can be opened again or removed (screen-reader action "Remove from recent files" and a visible button).

## [0.5.0] - 2026-10-21

### Added
- Compress tool: shrink a PDF by saving its pictures at lower quality and resolution (light, balanced or strong), optionally dropping title, author and keywords; the result says "Reduced from X to Y (Z%)" or that nothing could be saved.
- Watermark tool: stamp text on all or selected pages with an adjustable size, opacity and rotation, a position (center, top, bottom or repeated) and a named color; Turkish and other special letters are drawn with a bundled Noto Sans font.
- Page numbers tool: add numbers at one of six positions with presets such as "1 / 5" and "Page 1 of 5" (Turkish: "Sayfa 1 / 5") or a custom format, a start number and a size, on all or selected pages.
- Size, opacity and rotation are labelled sliders that announce their value and unit and can be changed with swipes or the volume keys.
- Third-party notice: Noto Sans Regular (SIL Open Font License 1.1) is bundled in the app.

## [0.4.0] - 2026-10-21

### Added
- Protect tool: encrypt a PDF with AES-256, a password you confirm, an optional owner password and switches to allow or block printing, copying and changing the document.
- Unlock tool: remove the password and restrictions from a PDF you can open, saving a copy that opens without one.
- Password prompts in all tools: choosing a password-protected PDF in Merge (per file), Split, Remove pages, Extract pages, Rotate, Organize, PDF to images and the new tools asks for the password, asks again after a wrong one, and never stores it.
- Metadata tool: see pages, protection, PDF version and producer, and edit title, author, subject, keywords and creator (Turkish and other Unicode text is kept).
- Repair tool: rebuild a damaged PDF from whatever can be read (broken cross-reference table, trailing garbage, truncated file).

## [0.3.0] - 2026-10-21

### Added
- Organize pages tool: see every page as a thumbnail, then move (up, down, to a position), rotate left or right, duplicate or delete pages with screen-reader actions or a per-page menu, undo changes, and save or share the new PDF.
- Page thumbnails: Remove pages, Extract pages and Rotate pages can pick pages from an accessible grid (each page is one labelled, checkable item with "Select all", "Clear selection" and a selected count) instead of typing a range; both stay in sync.
- Images to PDF tool: pick pictures from the gallery or take photos, reorder or remove them with buttons or screen-reader actions, choose page size (A4, US Letter or fit to picture), orientation and margin, then save or share a PDF with one page per picture.
- PDF to images tool: turn all or selected pages into JPG or PNG pictures at 72, 150 or 300 dpi; save them to a folder or as one ZIP, or share them.

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

[Unreleased]: https://github.com/wzehirx/b_pdf/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/wzehirx/b_pdf/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/wzehirx/b_pdf/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/wzehirx/b_pdf/releases/tag/v0.1.0
