# agents.md — local-tts-reader

Context for AI coding agents working in this repo. Read this first, then the docs it
points to. When a decision in a doc conflicts with the existing code, resolve it by
reading the code and updating the doc (not by contradicting it silently).

## What this is

A **greenfield Android app** that reads the user's book library aloud using **on-device
text-to-speech** from an **open-weight model** (primary target: Kokoro-82M). Everything
runs offline. No cloud, no account, no telemetry.

Three capabilities, one pipeline:

1. **Content** — load a book (Kindle-sourced) and produce a flat, ordered sequence of
   text passages with navigation structure (chapters/spine). Importing a book also adds
   it to the text index so the share feature can match against it.
2. **Speech** — convert passages to audio with a local model and play it back with
   transport controls, bookmarking, and progress persistence.
3. **Share-and-identify** — when the user shares text or a screenshot (e.g., from the
   Kindle app), identify which book + chapter/passage it came from (against books in the
   local library) so playback can resume there.

## Docs

- [docs/hard-facts.md](docs/hard-facts.md) — domain constraints that are not options
  (Kindle/ebook content, DRM, sync/progress, TTS engines, offline-first).
- [docs/architecture.md](docs/architecture.md) — module dependency graph, capability
  data flows, cross-module contracts.
- [docs/conventions.md](docs/conventions.md) — tech stack, code conventions, do's and
  don'ts, definition of done.
- [docs/modules.md](docs/modules.md) — proposed module layout (keep it flat until it hurts).
- [docs/build.md](docs/build.md) — how to build, run, and test.
- [docs/features/](docs/features/) — feature plans (share-and-identify; player/TTS/settings pending).
- [docs/decisions.md](docs/decisions.md) — decision log (why things are the way they are).
- [docs/features/share-and-identify.md](docs/features/share-and-identify.md) — the
  share-and-identify (resume-from-share) feature plan and status.
