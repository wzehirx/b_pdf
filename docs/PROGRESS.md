# Progress

Single source of truth for resuming work (after usage-limit resets or container restarts).
Plan: `docs/superpowers/plans/2026-10-21-b-pdf.md` · Spec: `docs/superpowers/specs/2026-10-21-b-pdf-design.md`

Resume recipe: `scripts/setup-android-sdk.sh` → `export ANDROID_HOME=$HOME/android-sdk` → continue with the first unchecked task below
(implement with a Sonnet subagent, review with a Sonnet subagent, escalate to Opus if review fails twice).
Budget mode (user-approved): batch remaining tasks per milestone (11+12, 13+14, 15+16+17, 18+19, 20+21, 22); one review per batch; fix only Critical/Important; minors go to the final review.

| Task | Status | Commit |
|---|---|---|
| Spec + plan | done | |
| 1 Scaffold | done | 4d6b34a..90c705a |
| 2 PDF engine foundation | done | ac68c59..cf9f300 |
| 3 Runner, files, a11y helpers | done | 6c05b3a..e93890b |
| 4 Shared UI, navigation, home | done | a082f4a..b3e336b |
| 5 CI, release, docs → v0.1.0 | next | |
| 6 Merge | done | c263686..9ed5e5d |
| 7 Split | done | b8dde4a..6be877a |
| 8 Remove/Extract pages | done | eb3d865..b5b392d |
| 9 Rotate → v0.2.0 | done | eb3d865..b5b392d |
| 10 Thumbnails, PageGrid, Organize | done | 2554fee..0b26e27 |
| 11 Images → PDF | done | e6e3d52..72322bb |
| 12 PDF → Images → v0.3.0 | done | e6e3d52..72322bb |
| 13 Protect, Unlock, password flow | done | 2c91e2b..517d4c9 |
| 14 Metadata, Repair → v0.4.0 | done | 2c91e2b..517d4c9 |
| 15 Compress | done | 51255f6..c8f6e78 |
| 16 Watermark | next | |
| 17 Page numbers → v0.5.0 | todo | |
| 18 PDF → Text | todo | |
| 19 Viewer, open-with → v0.6.0 | todo | |
| 20 Sign | todo | |
| 21 OCR → v0.7.0 | todo | |
| 22 Settings, polish, audit → v1.0.0 | todo | |
