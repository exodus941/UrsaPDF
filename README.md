# UrsaPDF

A small, fast PDF reader for Android 8+. No libraries beyond the Kotlin standard library in the app itself; pages render with the platform `PdfRenderer`.

- Home: recent PDFs, newest first, each with its page, when it was last read and its file size. **Open a PDF** and a name search (filters as you type) stay pinned at the top; a soft shadow shows where the list runs under them or off the screen. Each file's ⋮ menu removes it from the list, renames it or deletes it; a long press selects several to remove or delete at once. Two icons at the top right toggle light/dark mode and check for updates; updates also install themselves from the latest release here. Light mode is the default on e-ink devices (Onyx Boox, Bigme, Meebook and others, detected by maker); dark everywhere else.
- Reader: fullscreen apart from the navigation bar, one page at a time. Swipe left or right to turn pages; pinch, or double-tap and drag (up to zoom in, down to zoom out), to zoom; double-tap to zoom in or back to fit; drag to pan while zoomed, and push past a zoomed edge to turn. Tap anywhere on the page, or swipe up or down, for the page strip, the scrubber bar under it (press or drag to jump), **Search**, **Page Cropper**, a **screen timeout** (30 seconds to 5 minutes after the last touch, or none) and a ⋮ menu with **Invert Colours** and **Dark Mode**.
- Search: finds text on every page, highlights each match and steps through them with previous and next. Back steps back one result; from the first, it closes search.
- Forms: on a PDF form, the pencil in the top bar outlines the fields; tap one to type, tick, pick a radio button or choose from a list. Closing with changes offers Save Changes to File, Save as New File or Close Without Saving.
- Older phones: Android 15 has search and forms built in (so do Android 11–14 phones with Google's system update for it). Other phones download the engine once, about 3.5 MB, on first launch: a screen says "Downloading required libraries" until it is in place (also when a PDF is opened from another app first), and again only when a newer engine is published.
- Cropper: drag the frame to cut margins. The crop applies to every page and is saved per file, with the last page and the invert setting.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK, attaches it to a GitHub release and deletes every older release, so only the newest exists.

The engine lives in `engine/`: one zip per phone processor type, each with a signature, and `latest.json`, which the app reads. Its source is in `engine/src/`. Bump `engine/src/VERSION` and push to main: a workflow builds the engine (`engine/src/build.sh`), signs it with the release key (`engine/src/sign.sh`) and commits it into `engine/`, and phones then download it. The app loads an engine only if its signature matches the app's own signing certificate. Bump `ENGINE_API` in `engine/src/ursa.c` and `Pdfium.API` together when its functions change.

## Credits

- **[Tabler Icons](https://tabler.io/icons)** by Paweł Kuna, MIT licence: every icon in the app. Converted to Android vectors with `tools/svg2vd.py`.
- **[Five by Five](https://fontenddev.com/fonts/five-by-five/)** by jeti, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): the "PDF" lettering in the app icon.
- **[PDFium](https://pdfium.googlesource.com/pdfium/)** by the PDFium authors (Google and Foxit), BSD 3-Clause licence, with the libraries it bundles under their own licences: search and form filling on phones without them built in, in the downloaded engine only.
- **[pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)** by Benoît Blanchon, MIT licence: the prebuilt Android builds of PDFium the engine uses.

Full licence texts are in `THIRD_PARTY_NOTICES.md`.
