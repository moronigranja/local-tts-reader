# Ayvu v0.1.1 — release notes

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device. No account, no telemetry, no cloud.

## What's in this release

- **Import & library** — EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown; single-file and
  whole-folder import; "Open with Ayvu" and book-file shares; one import overlay with
  progress, stage and per-file failure isolation; library search over title/author.
- **Reading** — paginated chapter text with a chapter selector, book-wide passage
  indicator, and an immersive full-screen mode (title overlay + minimal player, follows
  the active sentence, middle double-tap toggles the chrome, Play starts at the top of
  the visible page, a manual page turn stops playback). Long-press a paragraph for
  **Play from here** / **Copy text**; chapter and bookmark jumps land without auto-play.
- **Listening** — Kokoro-82M narration with sentence-grain read-along highlighting;
  sleep timer (incl. end of chapter); bookmarks; undo-skip; playback volume; a
  configurable synthesis thread count so the phone stays responsive while generating;
  emit-early streaming that starts audio before a passage finishes.
- **Offline pre-generation** — render a book (or an arbitrary listening-time budget
  anchored at your reading position) to the on-device audio cache, with slice-relative
  progress, a Stop control on the library row, a generated/not-generated coverage bar
  and a generation notification; per-book audio usage with one-tap delete in Settings.
- **Voices & settings** — guided first run (privacy → packs → voice → import); one shared
  voice sheet (collapsible language sections, display names, upstream data grades,
  favourites, one-tap preview); settings grouped into Speech, Reading & sharing,
  Storage & data, Appearance, and Backup & restore.
- **Data safety** — Backup & restore export/import (optionally including the book
  files), with content-hash book ids so a restored library reattaches to its progress.

## Install

- **Requires a 64-bit ARM device (arm64-v8a) running Android 8.0+ (API 26).** The build
  is arm64-only because the espeak-ng phonemizer it ships is an arm64 native library;
  32-bit and x86 devices are not supported and the APK will not install there.
- Unminified signed release build, **≈51 MB** (ONNX Runtime and JNA are inside).
- Install the APK from this release (allow "install unknown apps" for your browser or
  file manager). The signing key is stable across releases, so later versions install
  straight over this one — no uninstall, and your library, progress, bookmarks and
  settings are kept.
- There is no in-app update check: watch this repository's Releases page.

### Verify the download

The APK attached to this release:

```
sha256  47ee826a424345c78194b695207a0c66832ae97366654b4c495bc03d34a34311
```

Check it with `sha256sum app-release.apk` (compare the value above), and confirm the
signer is this project's release certificate:

```
Signer #1 certificate DN: CN=Ayvu, O=moronigranja, C=BR
Signer #1 certificate SHA-256 digest: a5057984c0c285898a619b76c397f72030df124e55db88616de5e1a241902ae5
```

(`apksigner verify --print-certs app-release.apk` prints the same fingerprint; `keytool
-printcert -jarfile app-release.apk` works without the Android SDK.)

### First run

The app downloads its packs on first run — explicit, resumable and SHA-256-verified —
from three hosts, none of which is bundled (decisions #7):

| Pack | Size | Host |
|---|---|---|
| Kokoro-82M model `kokoro-v1.0.onnx` | 325 MB | `thewh1teagle/kokoro-onnx` release `model-files-v1.1` |
| Kokoro v1.0 voices (54) `voices-v1.0.bin` | 28 MB | same release |
| espeak-ng 1.52.0 phonemizer bundle | 9.9 MB | this project's `espeak-ng-1.52.0` release |
| OCR language packs (per language, optional) | 13–22 MB each | `tesseract-ocr/tessdata` (tag `3.04.00`) |

After the TTS packs land, everything works offline — no network is used again unless you
add OCR languages.

## Upgrading from a pre-release build

- Builds under the old id `com.moronigranja.localttsreader` are a **different app** from
  this one (`io.github.moronigranja.ayvu`): install fresh. Export a backup from the old
  build first and restore it here to carry the library, progress and bookmarks over.
- Installing over a **DEBUG** build of the same id requires an uninstall first — the
  debug key differs from the release key by design.

## Known limitations in this build

- **OCR accuracy** is capped by the bundled engine: tess-two 9.1.0 is pre-LSTM, so the
  pinned packs are legacy `3.04.00` tessdata (eng, spa, fra, deu, por, ita). A newer
  binding is required before LSTM models can be used.
- **Android Auto** media controls are wired through `MediaSession` but have not been
  verified on Auto hardware.
- **Playback runs at 1.0×.** The speed selector was removed in this cycle; the speed
  model is retained for a planned revisit.
- **Formats**: DRM-free files only; `.kfx` is detected and rejected. Share-and-identify
  recognizes only books already in your library, assumes contiguous plain text (non-space
  scripts are not supported by the matcher yet), and very short snippets are rejected by
  the confidence threshold.
- **Distribution**: unminified APK published as a GitHub Release (no Play listing, no
  AAB, no auto-update), and a native translation decorator (read a book aloud in another
  language) is not in this build.

## Licenses

Ayvu is GPL-3.0; the corresponding source for this build is this repository at the
`v0.1.1` tag — <https://github.com/moronigranja/local-tts-reader>. Bundled runtimes
(ONNX Runtime, JNA, AndroidX/Kotlin) and every downloaded pack are attributed in
[`NOTICE.md`](../NOTICE.md), including their upstream hosts.
