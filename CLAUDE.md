# UrsaPDF

Minimal, fast Android PDF reader. Repo `exodus941/UrsaPDF`; a `vX.Y.Z` tag builds
and publishes the release APK. Toolchain, signing key and emulator notes are in
memory `ursapdf.md`.

- Load `ui-layout` before any interface work.

## Releases

- **Only the newest release exists on GitHub.** The release workflow deletes every older release and its tag after it publishes; never restore or keep an old one.

## Icons

- **Every UI icon comes from Tabler Icons; never draw one by hand.** Download the outline SVG at a pinned version and convert it with `python -I tools/svg2vd.py <svg dir> app/src/main/res/drawable name=ic_x`. List new icons in `THIRD_PARTY_NOTICES.md`.
- **Every third-party asset is credited in the README's Credits section as well as in `THIRD_PARTY_NOTICES.md`:** name, author, licence, link. Check it whenever the README changes.

## Testing

- **Run the emulator in a visible window when testing, so they can watch.** Never start it with `-no-window`.
- **Compare frame times only within one emulator GPU mode.** Host GPU in a window reads about 22 ms median for any build; software GPU reads about 17 ms. Take a baseline in the same mode.

## System bars

- **The app never hides the Android navigation bar**, in the reader or anywhere else, with or without the chrome up. The reader hides only the status bar.

## Motion

- **`%ease250%` is the motion preset: 250 ms, ease-out `cubic-bezier(0, 0, .2, 1)` sliding in, ease-in `cubic-bezier(.42, 0, 1, 1)` sliding out.** When they write `%ease250%`, use exactly this. In code it is `Motion.IN` / `Motion.OUT` with `Motion.MS`.
- **Nothing appears or disappears in one frame.** Every new menu, popup, panel, overlay or control that shows or hides gets its transition in the same change, unasked: a 250 ms linear fade, or `%ease250%` if it slides. Check each new surface for it before shipping.
- **UI transitions use `%ease250%` unless they say otherwise.** This covers the reader chrome, search strip, cropper, modals, invert and the theme switch. Opacity fades run linear over the same 250 ms: an ease-out curve makes a fade read as a pop.
