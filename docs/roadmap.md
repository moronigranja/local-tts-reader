# Roadmap & work breakdown

Planning doc (2026-08-24). Estimates are working **days** for a single experienced
developer; ranges reflect risk. "Sandbox-doable" = pure JVM, buildable + testable in
this repo's environment without the Android SDK.

## Assumptions

- Everything Android (foundation, player, share receiver, OCR, Room, Compose) needs the
  Android toolchain (SDK + Gradle) on a real machine — not available in this environment.
- TTS engine order is gated on a CosyVoice3 measurement spike on the reference device
  (Galaxy S22 Ultra / Snapdragon 8 Gen 1).
- v1 language scope: TTS primary = CosyVoice3's 9 langs; pt-BR via fallback engines
  (nice-to-have); OCR = eng+spa+fra+deu+por+ita.
- core-locate (match core) ships; **24 tests green**.

## Gaps this plan closes

- App foundation + identity decisions (package name, DI choice).
- Import pipeline: format parsers, passage segmentation grain, fixtures, import→index.
- core-model canonical types; core-locate unified onto them (no duplicate models).
- Room schema + TextIndex rebuild-at-launch.
- Player internals: pre-generation queue, audio output, language/engine fallback UX.
- CosyVoice3 gate + settings screen home for the 0.6 threshold + CI.
- Deferred by decision: Kindle highlights/export sync (manual resume covers v1), CJK
  matcher, non-primary engine tiers, store release/iconing.

## Phase 0 — Foundation & identity (~5–7 d)

| ID | Item | Est. | Notes |
|---|---|---|---|
| F1 | Android toolchain + app scaffold: SDK, Gradle wrapper, `app` module, manifest, minSdk 26, version catalog (androidx/compose/room/coroutines), debug signing | 3–4 d | Needs a real machine/CI. Package name decision upstream. |
| F2 | `core-model`: canonical Book/Chapter/TextPassage/LibraryEntry; re-point `core-locate` onto it (drop duplicate models) + tests | 2–3 d | **Sandbox-doable.** |

**Decisions before F1:** package name (default `com.localttsreader` — still to
confirm). DI = **Hilt** (decided 2026-08-24, see conventions.md).

## Phase 1 — Content: import pipeline (~14–19 d) — `core-ebook` + `feature-library`

| ID | Item | Est. | Notes |
|---|---|---|---|
| C1 | `EBook` abstraction + **epub** parser (OPF spine, TOC, chapter/paragraph extraction); public-domain fixtures (valid + malformed) + tests | 4–5 d | **Sandbox-doable.** |
| C2 | **azw3/kf8** parser (EPUB3 container quirks) + tests | 2–3 d | **Sandbox-doable.** |
| C3 | **mobi/azw** parser (MOBI container: PalmDOC/EXTH) + tests | 4–6 d | **Sandbox-doable.** Highest format risk. |
| C4 | Passage segmentation: grain decision (paragraph-level for index precision), chapter titles, front/back matter rules; contract with TextIndex | 2–3 d | **Sandbox-doable.** Grain directly affects match precision. |
| C5 | Import flow: SAF `ACTION_OPEN_DOCUMENT`, parse **and index into TextIndex** (cross-cutting requirement), progress/error states, re-import semantics | 3–4 d | |
| C6 | Library list UI (minimal Compose) — end-to-end import visible | 2–3 d | |

## Phase 2 — Persistence (~4–6 d, overlaps C) — `core-persistence`

| ID | Item | Est. | Notes |
|---|---|---|---|
| P1 | Room schema: books, passages (cached parse), progress, settings (incl. **match threshold 0.6**), language-pack state, engine prefs; migration strategy | 2–3 d | |
| P2 | Repository layer over DAOs; launch-time TextIndex rebuild from cached parse (never re-parse) | 2–3 d | |

## Phase 3 — TTS + player (~14–20 d) — `core-tts` + `feature-player`

| ID | Item | Est. | Notes |
|---|---|---|---|
| T1 | `TTSEngine` interface + pack registry + download manager (explicit, resumable, verified, cached; language packs never bundled) | 3–4 d | |
| T2 | Kokoro impl (reference `thewh1teagle/kokoro-onnx`) + pipeline tests + RTF baseline | 2–3 d | |
| T3 | **CosyVoice3 spike**: verify community ONNX export on S22 Ultra; measure RTF/RAM/thermal → engine-order decision | 2–4 d | Risk: port may need fixes; flips primary/fallback order. |
| T4 | Player: foreground service, MediaSession, audio focus/ducking, transport controls, progress persistence | 4–6 d | |
| T5 | Pre-generation queue (synthesize ahead of playback — non-realtime is acceptable), engine/language fallback UX (missing pack → download prompt) | 3–4 d | |

## Phase 4 — Share-and-identify completion (~7–11 d) — `core-ocr` + `feature-share`

| ID | Item | Est. | Notes |
|---|---|---|---|
| S1 | `core-ocr`: tess-two wrapper; traineddata as downloadable packs (eng+spa+fra+deu+por+ita); screenshot downscale | 2–3 d | |
| S2 | `feature-share`: `ACTION_SEND` receiver (text/plain + image/*), result UI ("Found: book · chapter · passage"), not-found UX, threshold from settings | 3–4 d | |
| S3 | Resume wiring: match result → open book at passage → player starts there | 1–2 d | Connects S → T. |

core-locate itself is done; this phase completes the feature slice.

## Phase 5 — Verify & ship (~4–7 d)

| ID | Item | Est. | Notes |
|---|---|---|---|
| V1 | Settings UI: match threshold, engine selection, language-pack management | 2–3 d | Home for the configurable 0.6. |
| V2 | Instrumented tests (playback, share flow) + CI (unit tests every push, assemble on tag) | 2–3 d | |
| V3 | Performance/battery pass on S22 Ultra; fix thermal/RAM issues | 1–2 d | |

## Totals & paths

- **~43–68 working days ≈ 8–13 weeks full-time; 12–20 weeks part-time.**
- Critical path: F → C → (T3 gate) → T4/T5 → S3 → V.
- Parallel lanes: P1/P2 with C5+; C6 UI with C; S1/S2 with T; V2 alongside V1/V3.
- Sandbox-doable now (no Android needed): F2, C1–C4 ≈ **12–17 days of work available
  in this environment**, with real tests, before the toolchain decision matters.
