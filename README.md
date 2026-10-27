# UrsaPDF

A small, fast PDF reader for Android 8+. No libraries beyond the Kotlin standard library; pages render with the platform `PdfRenderer`.

- Home: recent PDFs, newest first. **Open a PDF** opens the system file picker. The ⋮ menu holds **Toggle Light/Dark Mode** and **Check for Updates** (installs the latest release from this repo). Light mode is the default on e-ink devices (Onyx Boox, Bigme, Meebook and others, detected by maker); dark everywhere else.
- Reader: fullscreen, one page at a time. Swipe left or right to turn pages; pinch to zoom; drag to pan while zoomed. Tap the centre, or swipe up or down, for the page strip, **Page Cropper** and **Invert Colours**.
- Cropper: drag the frame to cut margins. The crop applies to every page and is saved per file, with the last page and the invert setting.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Icons

UI icons are Tabler Icons (MIT, see `THIRD_PARTY_NOTICES.md`), converted with `tools/svg2vd.py`.

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK and attaches it to a GitHub release.
