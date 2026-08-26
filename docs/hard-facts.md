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
  3. **Amazon-DRM path (DRM-encrypted .azw3/.kf8/.kfx)** — unlocking these requires
     DRM removal, which **is DRM circumvention** (legally fraught; see stance below).
     - **Out-of-app only.** Any DRM removal runs on the user's own machine, using
       tools the user chooses and runs themselves. The app NEVER logs into Amazon,
       NEVER touches the user's Amazon account or credentials, and NEVER ships keys,
       decryption, or DRM-removal code. Accepting the user's Amazon login in-app turns
       a personal reader into a circumvention-with-capture device — the highest-risk
       path — and is off the table. The app consumes only DRM-free files the user
       supplies.
     - **Legal stance:** the app itself performs no circumvention and facilitates
       none; any DRM removal outside the app is the end user's own act and risk. Flag
       prominently; never default to it.
     - **Kindle Unlimited (KU) books are excluded.** They are borrowed, not owned;
       DRM unlocks the container, not the borrow window, so a KU copy goes dead when
       the loan expires. Only handle books the user owns.
- **File formats to parse:**
  - `.azw3` / `.kf8` are essentially **EPUB3** (a ZIP with `content.opf`, a spine, and
    XHTML/CSS media). Parse the OPF spine for chapter order, extract body text.
  - `.mobi` / older `.azw` are MOBI (PalmDOC/EXTH container semantics).
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
  — brittle, ToS-violating, and excludes all non-rooted users.
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

- **Expressive narration is a requirement, not a nice-to-have** (user review 2026-08).
  Kokoro-82M is natural but flat in prosody; the primary engine target is now the
  lightest genuinely expressive open model, with Kokoro demoted to the light fallback.
- **Primary target: Fun-CosyVoice3-0.5B-2512** (Apache-2.0, `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`,
  Dec 2025). At 0.5B it is the lightest model with real emotion control: instruct support
  for emotion, speed, volume and dialect; 9 languages (zh, en, fr, es, ja, ko, it, ru, de)
  plus 18+ Chinese dialects; zero-shot and cross-lingual voice cloning; bi-streaming with
  ~150 ms latency. ONNX (community exports) and GGUF (`cstr/cosyvoice3-0.5b-2512-GGUF`)
  exist, so Android is feasible. **Gate: measured RTF + RAM on the reference device
  before enabling by default** (see "Reference device" below).
- **Non-realtime synthesis is acceptable.** This is an audiobook player, not a chatbot:
  the engine only has to stay ahead of playback by pre-generating upcoming passages in
  the background. Even ~0.5–1x realtime is fine if generation keeps up — this
  de-risks a 0.5B engine on phone CPU.
- **Fallback: Kokoro-82M** (`hexgrad/kokoro-82M`). ~82M ONNX (~300 MB fp32, smaller
  quantized), 8 languages, Apache-2.0, fast and light but flat. Keep it behind
  `TTSEngine` as the low-battery/speed path and the measured baseline.
- **Engine tiers** (all behind `TTSEngine`; select per measured need):
  | Engine | Size | Expressiveness | Languages | Notes |
  |---|---|---|---|---|
  | **Fun-CosyVoice3-0.5B** (primary target) | 0.5B; int8/GGUF ~0.4–0.6 GB | High: emotion/speed/volume instruct, zero-shot voices | 9 + 18 dialects | Apache 2.0. Gate: measured RTF on reference device. |
  | **Kokoro-82M** (fallback) | 82M ONNX | Natural but flat | 9 groups incl. pt-BR | Apache 2.0. Light/fast path + baseline. |
  | **Piper** | VITS, tens of MB per voice | Mostly flat | many incl. pt | Cheapest per-language voice files. |
  | **KittenTTS** | 15–80M ONNX (25–80 MB) | Unproven; tiny | en only (dev preview) | Apache 2.0. Ultra-light watch item. |
  | **MeloTTS** | small | Moderate | 6 | MIT, CPU real-time. Watch item. |
  | **Orpheus-TTS** | 3B only | High (emotion tags) | multilingual research family | Apache 2.0 but desktop-only at 3B — out of phone scope. |
  | CosyVoice-300M | 300M (v1) | Moderate | 5 | Older gen; superseded by 0.5B v3. |
  | Qwen3-TTS | 1.7B | High | ~10 | Heavy; only with strong NPU quant. Long-term option. |
  | Coqui XTTS v2 | large | High | multilingual | Restrictive Coqui license — avoid. |
- **AR codec-token family — watch item (2026-08-25).** Parallel-lane candidate that
  removes the DiT entirely: an autoregressive LLM predicting discrete audio tokens
  (Mini-Omni / Qwen2-Audio class, 0.5–1.5B) + a neural codec (SNAC / Mimi / EnCodec).
  Motivated by the measured T3 result (flow DiT = ~72% of CosyVoice3's on-device RTF —
  see decisions #21). Gate before it can be a primary: measured RTF/TTFA on the S22
  Ultra **and** a narration-quality review (expressiveness for the audiobook use case
  is unproven in that family).
- **Portuguese (pt-BR) is a nice-to-have, not a blocker** (user 2026-08) — and the
  v1 primary covers it directly: the pinned Kokoro pack ships 3 pt-BR voices
  (pf_dora, pm_alex, pm_santa), so pt is first-class at v1 (decisions #29), with
  Piper per-language packs as the fallback tier. CosyVoice3's official 9 languages
  still exclude pt; revisit when a primary release adds it.
- **Language/voice packs are downloadable, never bundled.** All engine assets — model
  weights, per-language assets, voice packs — are runtime downloads: explicit,
  user-consented, resumable, cached after first fetch (consistent with offline-first).
  `core-tts` owns a pack registry (engine → pack → status) plus download/verify/cache
  management; the APK ships no TTS model data. A language that is not downloaded is
  surfaced in settings with a "download" action, never a silent failure. CosyVoice3
  covers its 9 languages in one pack; engines like Piper would add per-language packs.
- **Reference device:** Galaxy S22 Ultra (Snapdragon 8 Gen 1) — the performance gate for
  engine selection. Measure RTF, peak RAM, and thermal behavior for CosyVoice3-0.5B
  (int8) before committing it as default.
- **Android path:** ONNX Runtime Mobile. Kokoro has a working Kotlin reference
  (`thewh1teagle/kokoro-onnx`, its `SpeechPipeline`). CosyVoice3 needs a spike to port a
  community ONNX export (e.g. `Lourdle/Fun-CosyVoice3-0.5B-2512_ONNX`); all engines stay
  behind `TTSEngine` so swapping is a config change.

## Offline-first

- No network in the happy path. Any download (model weights, language/voice packs) is a
  single, explicit, user-consented, resumable operation. If you add any socket use,
  justify it in a PR.
