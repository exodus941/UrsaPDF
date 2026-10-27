# UrsaPDF

Minimal, fast Android PDF reader. Repo `exodus941/UrsaPDF`; a `vX.Y.Z` tag builds
and publishes the release APK. Toolchain, signing key and emulator notes are in
memory `ursapdf.md`.

- Load `ui-layout` before any interface work.

## Releases

- **Only the newest release exists on GitHub.** The release workflow deletes every older release and its tag after it publishes; never restore or keep an old one.

## Icons

- **Every UI icon comes from Tabler Icons; never draw one by hand.** Download the outline SVG at a pinned version and convert it with `python -I tools/svg2vd.py <svg dir> app/src/main/res/drawable name=ic_x`. List new icons in `THIRD_PARTY_NOTICES.md`.

## Testing

- **Run the emulator in a visible window when testing, so they can watch.** Never start it with `-no-window`.
- **Compare frame times only within one emulator GPU mode.** Host GPU in a window reads about 22 ms median for any build; software GPU reads about 17 ms. Take a baseline in the same mode.

## Motion

- **UI transitions run 250 ms: ease-out coming in, ease-in going out.** This covers the reader chrome, the cropper, invert and the theme switch.
