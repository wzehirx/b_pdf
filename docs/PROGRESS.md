# Progress

Single source of truth for resuming work (after usage-limit resets or container restarts).
Plan: `docs/superpowers/plans/2026-10-21-b-pdf.md` · Spec: `docs/superpowers/specs/2026-10-21-b-pdf-design.md`

Resume recipe: `scripts/setup-android-sdk.sh` → `export ANDROID_HOME=$HOME/android-sdk` → continue with the first unchecked task below
(implement with a Sonnet subagent, review with a Sonnet subagent, escalate to Opus if review fails twice).

| Task | Status | Commit |
|---|---|---|
| Spec + plan | done | |
| 1 Scaffold | done | 4d6b34a..90c705a |
| 2 PDF engine foundation | done | ac68c59..cf9f300 |
| 3 Runner, files, a11y helpers | done | 6c05b3a..e93890b |
| 4 Shared UI, navigation, home | done | a082f4a..b3e336b |
| 5 CI, release, docs → v0.1.0 | next | |
| 6 Merge | todo | |
| 7 Split | todo | |
| 8 Remove/Extract pages | todo | |
| 9 Rotate → v0.2.0 | todo | |
| 10 Thumbnails, PageGrid, Organize | todo | |
| 11 Images → PDF | todo | |
| 12 PDF → Images → v0.3.0 | todo | |
| 13 Protect, Unlock, password flow | todo | |
| 14 Metadata, Repair → v0.4.0 | todo | |
| 15 Compress | todo | |
| 16 Watermark | todo | |
| 17 Page numbers → v0.5.0 | todo | |
| 18 PDF → Text | todo | |
| 19 Viewer, open-with → v0.6.0 | todo | |
| 20 Sign | todo | |
| 21 OCR → v0.7.0 | todo | |
| 22 Settings, polish, audit → v1.0.0 | todo | |
