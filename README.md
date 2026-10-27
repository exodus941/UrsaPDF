# UrsaPDF

A small, fast PDF reader for Android 8+. No libraries beyond the Kotlin standard library; pages render with the platform `PdfRenderer`.

- Home: recent PDFs, newest first. **Open a PDF** opens the system file picker. **Check for Updates** installs the latest release from this repo.
- Reader: fullscreen, pinch to zoom, pan, fling. Tap the centre for the page previewer and **View Cropper**.
- Cropper: drag the frame to cut margins. The crop applies to every page and is saved per file, with the last position.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK and attaches it to a GitHub release.
