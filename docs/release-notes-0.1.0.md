# Ayvu v0.1.0 — release notes (draft)

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

- Requires Android 8.0+ (API 26). ~100 MB APK (unminified 0.1.0; the ONNX
  Runtime TTS engine ships inside).
- Upgrading over a DEBUG install requires uninstall first (different signing
  key) — library and bookmarks can be restored via Backup & restore.
- First run downloads the free model/voice/OCR packs from this project's
  GitHub releases (explicit, resumable, SHA-verified); after that everything
  works offline.

## Known blockers for this build

- Roadmap A8 (Samsung install-time data handling may delete Room data) —
  mitigated by quarantine + clean rebuild; investigations continue.

## Licenses

GPL-3.0 app; bundled runtimes and downloaded packs attributed in `NOTICE.md`.