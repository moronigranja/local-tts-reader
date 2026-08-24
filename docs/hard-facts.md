# Hard domain facts (do not re-litigate, design around these)

These are constraints, not options. Do not "just add an Amazon API layer" or assume
Kokoro ships inside the app.

## Kindle / ebook content

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

## Kindle sync / reading progress

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

## Text-to-speech

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
  opt-in "high quality" download behind the `TTSEngine` interface.

## Offline-first

- No network in the happy path. Any download (the model) is a single, explicit,
  user-consented, resumable operation. If you add any socket use, justify it in a PR.
