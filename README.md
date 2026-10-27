# UrsaPDF

A small, fast PDF reader for Android 8+. No libraries beyond the Kotlin standard library; pages render with the platform `PdfRenderer`.

- Home: recent PDFs, newest first, each with its page, when it was last read and its file size. **Open a PDF** opens the system file picker. Two icons at the top right toggle light/dark mode and check for updates; updates also install themselves from the latest release here. Light mode is the default on e-ink devices (Onyx Boox, Bigme, Meebook and others, detected by maker); dark everywhere else.
- Reader: fullscreen, one page at a time. Swipe left or right to turn pages; pinch, or double-tap and drag (up to zoom in, down to zoom out), to zoom; double-tap to zoom in or back to fit; drag to pan while zoomed, and push past a zoomed edge to turn. Tap the centre, or swipe up or down, for the page strip, the scrubber bar under it (press or drag to jump), **Page Cropper** and **Invert Colours**.
- Cropper: drag the frame to cut margins. The crop applies to every page and is saved per file, with the last page and the invert setting.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK, attaches it to a GitHub release and deletes every older release, so only the newest exists.

## Credits

- **[Tabler Icons](https://tabler.io/icons)** by Paweł Kuna, MIT licence: every icon in the app. Converted to Android vectors with `tools/svg2vd.py`.
- **[Five by Five](https://fontenddev.com/fonts/five-by-five/)** by jeti, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): the "PDF" lettering in the app icon.

Full licence texts are in `THIRD_PARTY_NOTICES.md`.
