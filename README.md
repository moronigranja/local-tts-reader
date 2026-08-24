# local-tts-reader

Offline-first Android app that reads your ebook library aloud using **on-device,
open-weight text-to-speech**. No cloud, no account, no telemetry.

**Status: early development.** Planning docs live in `docs/`; the text-identification
core (`core-locate`) is implemented and unit-tested. The Android app itself is not
started yet.

## Capabilities

1. **Content** — import the DRM-free ebooks you supply (`.epub` / `.azw3` / `.kf8` /
   `.mobi`) and parse them into chapters and passages, indexing each book so it can be
   identified later.
2. **Speech** — expressive on-device TTS (primary target: Fun-CosyVoice3-0.5B,
   fallback: Kokoro-82M). Models and language packs are downloaded on demand — never
   bundled in the app.
3. **Share-and-identify** — share text or a screenshot from your Kindle app; the app
   finds which book and passage it comes from (against books you've already imported)
   so playback can resume there.

## Repository layout

```
core-locate/   Book/passage matching core (pure JVM, no Android deps)
docs/          Planning, conventions, module layout, build, roadmap
agents.md      Entry point for AI agents working in this repo — read first
settings.gradle.kts, gradle/   JVM-only Gradle setup (Android modules come later)
```

## Build

Requirements for the JVM-only parts: JDK 17+ (any Gradle-compatible setup).

```bash
./gradlew :core-locate:test        # match core tests — no Android SDK needed
```

The Android app modules need the Android SDK + NDK. Recommended path: containerized
toolchain (see `Dockerfile` and `tools/docker-build.sh`, documented in
[docs/build.md](docs/build.md)) — keeps the SDK's tens of thousands of files out of
your workspace:

```bash
docker build -t localtts-android .
tools/docker-build.sh assembleDebug
```

## Docs

- [docs/hard-facts.md](docs/hard-facts.md) — domain constraints (ebook formats, sync,
  TTS engines, offline-first)
- [docs/conventions.md](docs/conventions.md) — tech stack, do's and don'ts, definition
  of done
- [docs/modules.md](docs/modules.md) — proposed module layout
- [docs/build.md](docs/build.md) — build/run/test, incl. the Docker toolchain
- [docs/roadmap.md](docs/roadmap.md) — work breakdown with estimates
- [docs/features/share-and-identify.md](docs/features/share-and-identify.md) — the
  share-and-identify feature plan

## License

Not yet chosen.
