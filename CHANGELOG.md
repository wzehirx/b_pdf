# Changelog

All notable changes to b PDF are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/wzehirx/b_pdf/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wzehirx/b_pdf/releases/tag/v0.1.0
