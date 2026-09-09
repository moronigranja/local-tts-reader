# Ayvu v0.1.1 — release notes (draft)

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device.

## What's in this release

- **Import**: EPUB / AZW3 / MOBI / AZW, single files, folder import, "Open
  with Ayvu" and book-file shares, with progress and per-file failure
  isolation. Text screenshots and shares identify the book and passage via
  on-device OCR.
- **On-device TTS**: Kokoro-82M, sentence-grain read-along highlighting
  (engine-returned anchors), sleep timer, bookmarks, undo-skip, per-book
  pre-generation (whole-book or 30 m/1 h/2 h budgets), offline PCM cache.
- **Reader**: paginated books, chapter navigation, book-wide passage
  indicator, immersive mode (middle double tap), play-from-visible-page-top.
- **Privacy**: fully on-device. No account, no telemetry, no network beyond
  downloading the free TTS/OCR packs from this project's GitHub releases.

## Install notes

- Requires Android 8.0+ (API 26). ~165 MB APK (unminified 0.1.1; the ONNX
  Runtime TTS engine ships inside).
- Pre-release installs under the old id (`com.moronigranja.localttsreader`)
  are a separate app from this one (`io.github.moronigranja.ayvu`) — install
  fresh; data does not carry over (decisions #128).
- Upgrading over a DEBUG install of the same id requires uninstall first
  (different signing key) — library and bookmarks can be restored via Backup
  & restore.
- First run downloads the free model/voice/OCR packs from this project's
  GitHub releases (explicit, resumable, SHA-verified); after that everything
  works offline.

## Known blockers for this build

None. The one prior open item (roadmap A8 — Room-data durability across reinstall)
was classified and fixed 2026-09-01 (decisions #107): the anomaly was the E2E
teardowns deleting the live DB, not Samsung install-time handling.

## Licenses

GPL-3.0 app; bundled runtimes and downloaded packs attributed in `NOTICE.md`.