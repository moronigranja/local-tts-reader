# agents.md — local-tts-reader

Context for AI coding agents working in this repo. Read this first, before editing
or adding anything. When a decision here conflicts with the existing code, resolve
it by reading the code and updating this doc (not by contradicting it silently).

---

## 1. What this is

A **greenfield Android app** that reads the user's book library aloud using **on-device
text-to-speech** from an **open-weight model** (primary target: Kokoro-82M). Everything
runs offline. No cloud, no account, no telemetry.

Three capabilities, one pipeline:

1. **Content** — load a book (Kindle-sourced) and produce a flat, ordered sequence of
   text passages with navigation structure (chapters/spine). Importing a book also adds
   it to the text index (§8); the share feature matches against that index.
2. **Speech** — convert passages to audio with a local model and play it back with
   transport controls, bookmarking, and progress persistence.
3. **Share-and-identify** — when the user shares text or a screenshot (e.g., from the
   Kindle app), identify which book + chapter/passage it came from (against books in the
   local library) so playback can resume there. See §8.

---

## 2. Hard domain facts (do not re-litigate, design around these)

These are constraints, not options. Do not "just add an Amazon API layer" or assume
Kokoro ships inside the app.

### Kindle / ebook content

- **Amazon has no public personal-library API.** Do not build against one. The
  "Manage Your Contents and Documents" export endpoint exists but is undocumented,
  rate-limited, and only DRM-free lending-eligible titles — treat it as a possible
  future bridge, not a foundation.
- **Realistic on-device content paths, in order of priority:**
  1. **User-provided local files** — the user opens/sends `.azw3` / `.kf8` / `.mobi`
     (`.azw`), or `.epub`. Parse directly. This is the v1 scope. No DRM involved.
  2. **Kindle-for-PC/Mac local library** — a SQLite DB (`content store` /
     `metadata.db`) points at encrypted book files. Reading this on the **Android
     device is not feasible** (that DB lives on a PC/Mac). Support this via a
     companion export step or by the user transferring files, not by shelling out to
     the desktop app.
  3. **Amazon-DRM path (DRM-encrypted .azw3/.kf8/.kfx)** — requires deriving the
     book key from the user's own Kindle credentials/keys (DeDRM-style key
     derivation from the desktop DB's `rec209`/ASIN fields, or from the user's Amazon
     session cookies). **This is DRM circumvention.**
     - **Out-of-app only.** Key derivation runs on the user's own machine or an
       external tool. The app NEVER logs into Amazon, NEVER harvests cookies/session
       tokens, and NEVER ships keys or key-derivation code. Accepting the user's Amazon
       login in-app turns a personal reader into a circumvention-with-capture device
       — the highest-risk path — and is off the table. The app consumes only DRM-free
       files the user supplies.
     - **Legal stance:** personal/offline use lowers *distribution* exposure but does
       not erase the circumvention act itself; the end user owns that risk. Flag
       prominently; never default to it.
     - **Kindle Unlimited (KU) books are excluded.** They are borrowed, not owned;
       DRM unlocks the container, not the borrow window, so a KU copy goes dead when
       the loan expires. Only handle books the user owns.
- **File formats to parse:**
  - `.azw3` / `.kf8` are essentially **EPUB3** (a ZIP with `content.opf`, a spine, and
    XHTML/CSS media). Parse the OPF spine for chapter order, extract body text.
  - `.mobi` / older `.azw` are MOBI (KindleUnpack semantics).
  - `.epub` is EPUB2/3.
  - `.kfx` is Amazon's newer, closed container — **out of scope for v1**; detect it and
    guide the user to convert/export rather than attempting to parse.
- Treat every file as untrusted and malformed-able. Parse defensively; never crash on
  a bad spine or missing metadata. Prefer a single `EBook` abstraction that adapts a
  format parser to a common domain model.

### Kindle sync / reading progress

- **Reading the Kindle app on-device for progress is not feasible on a normal device.**
  The Kindle app's data lives in its Android sandbox (`/data/data/com.amazon.kindle`),
  is encrypted, and its schema is undocumented. Reading position is also **server-side**
  (Amazon syncs it through the account). Touching it needs root + adb-backup/extract
  (what DeDRM does) — brittle, ToS-violating, and excludes all non-rooted users.
  **Do not build this.**
- **Legitimate sync sources (Amazon returning the user's own data):**
  - **Official export** — "Your Content and Documents" / "Download your data" gives
    per-book reading position, last-read time, highlights, and notes.
  - **Read-only Kindle Highlights/Reports API** (access-token, cursor pagination) —
    the sanctioned programmatic path for highlights/notes/position.
  - Both are **on-demand/scheduled, not real-time**; expose an explicit
    "sync from Kindle" action rather than assuming live updates.
- **Manual resume** is always available: the user sets their location in the reader and
  it is persisted. Because the TTS reader is a separate app, a manual resume point is
  often the cleanest UX and needs no Amazon access.

### Text-to-speech

- **Kokoro-82M is the primary engine.** It is distributed as a **~300 MB ONNX model**
  (from `hexgrad/kokoro-82M`), **not bundled**. Download once (first run) into
  `getExternalFilesDir(null)/kokoro.onnx` (or app cache) and cache. Ship a quantized
  small variant if you test it, but default to on-download to keep the APK lean.
- **Languages: ~8** (English primary; a handful of others). Multi-language works but is
  **limited** — surface the model's supported `lang` codes explicitly and fail gracefully
  when a passage's language is unsupported, rather than garbling output.
- **Android path:** use the ONNX Runtime Mobile runtime. A working Kotlin reference
  exists (`thewh1teagle/kokoro-onnx`, its `SpeechPipeline`). Reference it, don't reinvent
  the model I/O, but keep TTS behind your own `TTSEngine` interface so it can be swapped.

- **Engine tiers** (default = Kokoro; gate the rest on measured need + hardware):
  | Engine | Size / on-device | Languages | Notes |
  |---|---|---|---|
  | **Kokoro-82M** (primary) | ~82M ONNX, CPU/low-end OK | ~8 | Confirmed default. |
  | **Piper** | VITS, very light, CPU | many | Cheap-hardware / broader-language fallback. |
  | **CosyVoice 2** | 0.5B / Lite (~150MB), needs NPU+int8 | multilingual | High quality; gated, hardware-dependent. Apache 2.0. |
  | **Qwen3-TTS** | 1.7B PyTorch, heavy, needs NPU+quant | ~10 | Best long-term quality fallback. Apache 2.0. |
  | Coqui XTTS v2 | large, GPU-oriented | multilingual | Restrictive Coqui license — avoid unless cleared. |
- Add a second engine only after measuring Kokoro on target hardware, and only as an
-  opt-in "high quality" download behind the `TTSEngine` interface.

### Offline-first

- No network in the happy path. Any download (the model) is a single, explicit,
  user-consented, resumable operation. If you add any socket use, justify it in a PR.

---

## 3. Tech stack & conventions

- **Language:** Kotlin (JVM). No Java in new code.
- **Build:** Gradle Kotlin DSL. Version catalogs (`libs.versions.toml`).
- **Kotlin:** K2 compiler, `-Xjsr305=strict`, coroutines + `kotlin-flow`.
- **JVM target:** 17+. **minSdk 26** (needed for ONNX Runtime Mobile; document why).
- **Architecture:** MVVM + Repository, unidirectional-ish data flow.
  - UI observes `StateFlow`; user intents go one way in.
  - Business logic in `ViewModel`/`StateHolder`; keep it testable and UI-agnostic.
- **Concurrency:** Coroutines on `Dispatchers.IO` for file/model I/O,
  `Main` for UI. Cancellations matter (long TTS queues, file scans) — propagate them.
- **DI:** Hilt (or a minimal hand-rolled graph if keeping deps tiny — decide early,
  keep it consistent everywhere).
- **Persistence:** Room for the library index, reading progress, settings, and cached
  parsed content pointers. Never re-parse a book on every launch.
- **UI:** Jetpack Compose + Material 3. Keep composition functions pure; hoist state.
- **Audio:** Foreground playback service. Use a dedicated audio playback path with
  `AudioManager`/`AudioAttributes` (PLAYBACK_MEDIA), ducking/interruption handling,
  and MediaSession so lock-screen/Bluetooth controls work.
- **Naming:** camelCase code, snake_case resources/DB columns, PascalCase types,
  UPPER_SNAKE for constants. Resource IDs and package segments lowercase.

---

## 4. Module layout (proposed; keep it flat until it hurts)

```
core-model/       Book, Chapter/Section, TextPassage, LibraryEntry (no Android deps)
core-ebook/       EBook interface + format parsers (azw3/kf8, mobi, epub); defensive
core-locate/      TextIndex + matcher (n-gram recall over indexed books); no Android deps
core-ocr/         on-device OCR (tess-two) behind OCRService interface; eng+spa+fra+deu+por+ita
core-tts/         TTSEngine interface + Kokoro ONNX impl; model download/caching
core-persistence/ Room schema, daos, migrations
feature-library/  list/search/import books (Compose)
feature-reader/   text display + navigation
feature-share/    ACTION_SEND receiver: text + screenshot → TextIndex → resume point
feature-player/   playback service, transport, progress
app/              DI graph, composition root, app bar, settings
```

Prefer one cohesive module per responsibility over feature sprawl. Add modules only when
a circular dependency or real build isolation forces it.

---

## 5. Build, run, test

```bash
./gradlew assembleDebug                 # build an installable APK
./gradlew testDebugUnitTest             # unit tests (logic, parsers, state)
./gradlew assembleDebugAndroidTest      # instrumented tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew ktlintCheck                   # or detekt, per repo config
```

- **Tests are how "done" is proven.** Add a unit test for every parser rule, every
  state transition, and every public function with non-trivial behavior. Parsers must
  have fixture-based tests (valid + malformed inputs).
- Keep instrumented tests minimal and deterministic; prefer them only for audio/
  playback and UI flows that unit tests cannot reach.
- CI must be green before a change is considered complete.

---

## 6. Do and don't

**Do**
- Design against the abstractions in §4 (`EBook`, `TTSEngine`, `LibraryStore`), not
  concrete formats/models. New formats become another implementor, not new call sites.
- Keep the model download and file I/O off the main thread and cancellable.
- Persist reading progress and per-book settings; restoring where the user was is core.
- Respect Android storage/permissions norms; use SAF (`ACTION_OPEN_DOCUMENT`) for
  opening user files, scoped storage, and a foreground service notification for playback.
- When the spec is unclear, choose the conservative, reversible option and say why.

**Don't**
- Don't add network/cloud/account features without an explicit reason in §2.
- Don't automate or hide DRM removal. Don't ship keys, key-derivation, or DRM tools.
- Don't scrape the Kindle app on-device for reading progress — use the official export
  API or a manual resume point instead.
- Don't bundle the ~300 MB model in the APK; download it once, cache it, allow re-download.
- Don't ship a bare `android.TextToSpeech` system-TTS wrapper as the engine — the point
  is the open-weight on-device model. System TTS is acceptable only as a documented
  degraded fallback behind the same interface.
- Don't introduce a second convention (folder, pattern, DI style) when one already
  exists elsewhere. Match the nearest existing pattern.
- Don't leave dead parsers, dead engines, or half-migrated callers. Clean cutover.

---

## 7. Definition of done for a change

- Behavior covered by a test that fails without the change.
- No silent fallbacks that mask failure (missing language, corrupt file → clear state).
- No new blocking permissions or dependencies without noting the tradeoff.
- `./gradlew ktlintCheck` and `./gradlew testDebugUnitTest` green.
- This doc updated if a convention or fact changed.

---

## 8. Feature plan: share-and-identify (resume-from-share)

**Goal.** The user copies text or screenshots a page in the Kindle app and shares it to
this app. The app identifies which book + chapter/passage the shared text came from
(against books already in the local library) and offers to start listening there.

**Scope of this slice.** Match core + `TextIndex` + share receiver (`text/plain` +
`image/*` OCR). Player-resume wiring is the next slice.

**Constraints (all already in §2 unless restated):**
- Offline, on-device only. No cloud, no account, no telemetry.
- **Matches only books already imported/indexed in the app.** The share feature can only
  recognize books the user has loaded. This is by design (no cloud text DB).
- Reflowable content ⇒ identify **chapter/passage**, not physical pages.
- **OCR is fully open-source: tess-two (Tesseract on Android). Not Google ML Kit.**
- **Multilingual OCR — languages `eng+spa+fra+deu+por+ita` to start** (English, Spanish,
  French, German, Portuguese, Italian). All roman-alphabet, all supported by Tesseract.
  More languages ⇒ slower OCR + larger app + marginally lower per-language accuracy. No
  auto-detection — run the bundled set. Non-roman scripts (CJK, etc.) need character-level
  matching in the matcher — a matcher follow-up, out of scope for this slice.

**Data flow.**
```
Share (text or image)
  → ShareReceiver (ACTION_SEND) extracts payload
  → image → OCRService (tess-two) → text;  text/plain → as-is
  → TextIndex.query(snippet) → MatchResult(bookId, passageIndex, confidence)?
  → "Found: book · chapter · passage" + "Start listening here"  (player wiring = next slice)
```
Low confidence (below threshold) ⇒ "not found in library — import it first".

**Match core (pure logic, no Android deps — lives in `core-locate`):**
- `TextNormalizer`: NFKD (accents decomposed) → strip combining marks + apostrophes →
  lowercase → keep only `[a-z0-9 ]` → collapse spaces → trim. ("café" → "cafe",
  "It's" → "its" — matches OCR that drops apostrophes; other punctuation → word separator.)
- `TextMatcher`: word n-grams **n=4 with 3-gram fallback credit** for windows containing
  local OCR typos (chosen by measurement: matches stay ≥0.6 under realistic noise while
  cross-book text scores ≤0.05); snippets <4 tokens → unigram recall; best passage wins,
  ties → earliest.
- `TextIndex`: holds `IndexedBook`s (each with ordered `Passage`s), precomputes per-passage
  gram sets, `query(snippet): MatchResult?` (null below threshold).
- **Confidence threshold.** Recall fraction: 1.0 = every word-group in the shared text
  appears in a passage (verbatim/contiguous/truncated). Default threshold **0.6**,
  **configurable in settings**. Below threshold ⇒ "not found". Chosen high enough to avoid
  wrong matches; tunable. A normal shared sentence/paragraph chunk scores well above 0.6.

**Cross-cutting requirement.** Every book the **file-import pipeline parses must also be
added to the `TextIndex`** (§1, capability 1). The share feature matches against this index;
parsing without indexing silently breaks share-and-identify. The index is populated during
import; `TextIndex` itself is an in-memory layer over the library store, rebuilt at launch.

**Status.** `core-locate` implemented and **24 JUnit tests pass** — verified in this
environment by compiling with the standalone Kotlin 2.4.10 compiler and running via JUnit
Platform console 6.1.3 (`java -jar junit-platform-console-standalone.jar execute`).
`TextMatcher`/`TextIndex` are pure JVM; the repo has a minimal JVM-only Gradle setup
(settings.gradle.kts + `core-locate`) so `gradle test` works on any machine without the
Android SDK. OCR (`tess-two`) and the `ShareReceiver` are Android components — designed
here, verified on-device later.

**Recorded decisions.** Default confidence threshold **0.6, configurable in settings** (§8);
OCR = tess-two, `eng+spa+fra+deu+por+ita`, roman-alphabet curated set (§8); slice = match
core + index + share receiver, text + OCR (§8); import pipeline must also index each book (§1).
