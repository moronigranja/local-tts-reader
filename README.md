<p align="center">
  <img src="docs/assets/ayvu-icon-master.svg" alt="Ayvu launcher icon" width="96" height="96">
</p>

# Ayvu

*Ayvu — your books, in voice.*

Offline-first Android app that reads your ebook library aloud using **on-device,
open-weight text-to-speech**. No cloud, no account, no telemetry. Share a quote or a
screenshot from your Kindle app and it finds the book and jumps playback to that
passage.

## Status

v1 functional spine is built and verified on the S22 Ultra: import → index →
playback (with read-along sentence highlighting) → share-and-resume, plus settings
(voice picker, language/voice pack downloads, match threshold, theme) and OCR.

**Live modules (host JVM tests, no Android SDK needed):**

| Module | What it does |
|---|---|
| `core-model` | Canonical domain: Book, Chapter, TextPassage, LibraryEntry |
| `core-ebook` | EPUB2/3 + AZW3/KF8 + MOBI/AZW + TXT/Markdown parsers, passage segmentation, import pipeline (C7) |
| `core-locate` | N-gram book/passage identification, launch-time index rebuild, `TextIndex.best` for below-threshold hints |
| `core-tts` | TTSEngine + Kokoro-82M (onnxruntime behind a compileOnly seam), espeak-ng phonemization, pinned pack descriptors, Pt-BR voices verified |
| `core-player` | Player state machine, transactional progress + bookmarks + undo ring, sleep timer, speed; T5 pre-generation queue + PCM cache |
| `core-ocr` | OCR engine seam, screenshot downscaler, six pinned legacy-traineddata packs (tess-two 9.1.0 can't init LSTM models — decisions #36) |

**Live modules (Android, Docker toolchain):**

| Module | What it does |
|---|---|
| `core-persistence` | Room schema v2 (books, cached passages, progress + offset/speed, settings, bookmarks, position_history); stores + launch-time rebuild |
| `feature-library` | SAF multi-file + folder import (F3 tree grant, progress, typed failures, idempotent) + library list UI |
| `feature-player` | Foreground playback service (MediaSession, focus/ducking, notification), pre-generation wiring, reader surface with sentence highlight + S3 gestures |
| `feature-settings` | Settings screen: engine/voice/OCR pack downloads, voice picker + favorites, match threshold, OCR languages, theme; Android HTTP transport |
| `feature-share` | ACTION_SEND gateway (text + image), typed resolver (found / not-found with closest hint), OpenTarget contract |
| `feature-ocr` | TessTwoOcrEngine (tess-two 9.1.0) + tessdata stager, Hilt wiring |
| `app` | Hilt composition root: Library / Reader / Settings routes, S3 open-target intent handling |

Host JVM suite: **255 tests, 0 failed**. Android unit suites (Docker): green across
app + all features. Device instrumented set (S22 staging, see docs/build.md):
PlaybackE2e (full-book completion + pre-generation fast path), VoiceSelectionE2e,
PlayPositionE2e, SharePipeline (text + image OCR), OCR smoke, RealEpubImportProbe
(a real 24.8 MiB Gutenberg epub), PtVoiceE2e — all passing.

## Limitations (current)

- **Formats:** `.epub`, `.azw3`/`.kf8`, `.mobi`/`.azw`, `.txt`, `.md`/`.markdown`,
  **DRM-free files only**. `.kfx` (closed container) is detected and rejected with
  guidance. DRM removal is never performed in-app; encrypted files are refused up
  front.
- **MOBI7 chapters:** with an NCX index the text splits into chapters at the
  navPoint filepos boundaries, titled from the navPoint labels; without one the
  book stays a single chapter and headings surface as passages.
- **Identification:** assumes contiguous, in-order text (copied or OCR'd). Non-space
  scripts (CJK) are not supported by the matcher yet; very short snippets are
  unreliable and rejected by the confidence threshold (configurable, default 0.6).
- **Share-and-identify** only recognizes books already imported into the library.
- **TTS voices:** v1 ships Kokoro-82M as the primary engine (CosyVoice3 gated behind
  the fallback tier — far from realtime on the S22 CPU, decisions #21); the pinned
  v1.0 voice pack serves en/en-GB, fr, es, it, pt-BR, ja, zh, hi. Model and language
  packs are on-demand downloads, never bundled (decisions #7). Portuguese is a
  first-class voice family (`pf_`/`pm_`, verified end-to-end, decisions #40); the
  post-v1 pt-BR translation decorator (`core-translate`) is a separate deferred
  slice.
- **OCR engine:** tess-two 9.1.0's native build is pre-LSTM, so the pinned language
  packs are legacy 3.04.00 tessdata (decisions #36) — accuracy upgrade waits on a
  maintained binding.

## Capabilities

1. **Content** — import your DRM-free ebooks (`.epub`, `.azw3`/`.kf8`, `.mobi`/`.azw`,
   `.txt`, `.md`) and parse them into chapters and passages, indexing each book so it
   can be identified later. Cached parses never re-read a source file on launch.
2. **Speech** — expressive on-device Kokoro-82M TTS with sentence-grain read-along
   highlighting (engine-returned anchors), sleep timer, bookmarks and
   undo-skip; pre-generation hides the inter-passage gap. Pre-generation also
   prepares the post-v1 disk PCM cache (same keying). Playback runs at 1.0×:
   the speed selector was removed 2026-08-28 (decisions #71) — the speed model
   is retained for a planned revisit.
3. **Share-and-identify** — share text or a screenshot from your Kindle app; the app
   finds which book and passage it comes from (text directly, screenshots via on-device
   OCR) and offers "Listen here" — opening the book at that passage and starting
   playback. Reader supports the flip side: tap the passage to replay from here.
4. **Settings** — voice picker with favorites, engine/voice/OCR-language pack
   downloads (explicit, resumable, SHA-verified), match threshold, theme
   (system/light/dark), OCR language selection.

## Repository layout

```
core-model/   canonical domain
core-ebook/   parsers + segmentation + importer
core-locate/  identification + index
core-tts/     engine seam + Kokoro impl + pack descriptors
core-player/  playback state machine + pre-generation
core-ocr/     OCR seam + downscaler + traineddata packs
core-persistence/  Room stores (library, player, settings)
feature-library/   SAF import + library UI
feature-player/    playback service + reader UI
feature-settings/  settings UI + pack downloads
feature-share/     ACTION_SEND gateway + resolver
feature-ocr/       tess-two adapter + stager
app/          Hilt composition root (Library / Reader / Settings routes)
spike-tts/    measurement harnesses (benchmark, grain spike, device spikes)
tools/        docker-build.sh (containerized Android toolchain), gen_mobi_fixtures.py
docs/         decisions (#1–#41), roadmap, conventions, build, module layout, ideas, brand
.github/      CI: JVM tests + Docker Android build + unit tests every push; tag-gated assemble
agents.md     Entry point for AI agents working in this repo — read first
```

## Build & test

- **JVM-only parts** (no Android SDK): JDK 17+.

```bash
./gradlew :core-model:test :core-ebook:test :core-locate:test :core-tts:test \
          :core-player:test :core-ocr:test
```

- **Android parts**: containerized toolchain keeps the SDK's tens of thousands of
  files out of your workspace (`tools/docker-build.sh`, image: `localtts-android`):

```bash
docker build -t localtts-android .
tools/docker-build.sh :app:assembleDebug :app:assembleDebugAndroidTest
```

- **Device verification** (S22 staging: packs, espeak bundle, tessdata, a stock epub;
  per-class `am instrument` invocations): see [docs/build.md](docs/build.md).
- **CI**: `.github/workflows/ci.yml` — JVM suite + Docker Android build/unit tests on
  every push/PR, `assembleDebug`+`assembleRelease` on tags (decisions #41).

## Docs

- [docs/hard-facts.md](docs/hard-facts.md) — domain constraints (ebook formats, sync, TTS engines, offline-first)
- [docs/conventions.md](docs/conventions.md) — tech stack, do's and don'ts, definition of done
- [docs/modules.md](docs/modules.md) — module layout (LIVE as of #41)
- [docs/landscape.md](docs/landscape.md) — sherpa-onnx / candela boundary and validated patterns
- [docs/decisions.md](docs/decisions.md) — the decision ledger (#1–#59)
- [docs/roadmap.md](docs/roadmap.md) — active stabilization and post-v1 sequencing
- [docs/build.md](docs/build.md) — build/run/test, Docker toolchain, device staging
- [docs/features/share-and-identify.md](docs/features/share-and-identify.md) — the share-and-identify feature plan

## License

[GNU General Public License v3.0](LICENSE) — the repo contains GPL-3.0-derived parser
code (KindleUnpack ports), see decisions #27.