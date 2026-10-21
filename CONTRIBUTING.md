# Contributing to b PDF

Thank you for helping. b PDF is an offline, accessibility-first PDF toolkit; accessibility is part of every change, not an add-on.

## Build

Requirements: JDK 21, Android SDK platform 36 and build-tools 36.0.0 (run `scripts/setup-android-sdk.sh` to provision them).

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug
```

## Test gate

Every change must pass this before you open a pull request:

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI runs the same checks on every push and pull request.

## Commit convention

[Conventional Commits](https://www.conventionalcommits.org/): `feat(merge): ...`, `fix(core): ...`, `docs: ...`, `ci: ...`, `chore: ...`, `test: ...`. Keep commits small and focused. Add a line to the `[Unreleased]` section of `CHANGELOG.md` for user-visible changes.

## Accessibility definition of done

A screen is not done until it meets the checklist in [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md):

- Every actionable element has a meaningful label; screen and section titles are headings.
- No gesture-only interaction: anything draggable also has custom actions and visible buttons.
- Touch targets are at least 48dp and the layout survives 200% font scale.
- Progress and results are announced (polite live region); errors are assertive and take focus.
- Selected/checked state is exposed to assistive technology.
- A Robolectric Compose semantics test asserts labels, headings and custom actions.
- You tried the screen with TalkBack.

## Strings: Turkish and English

All user-visible text, including content descriptions and announcements, goes in `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-tr/strings.xml` (Turkish). A change that adds a string to only one of them will not be merged. No hard-coded UI text.

## Engine rules

- `core/pdf` takes `File`s and never touches `Context`, `ContentResolver` or UI.
- Load PDFs only through `PdfLoader.load`.
- Text drawn into PDFs uses the bundled Unicode font so Turkish characters render.

## Reporting issues

Use the issue templates. For screen reader problems, choose "Accessibility issue" and include your TalkBack version and device.

## License

By contributing you agree that your contributions are licensed under Apache-2.0.
