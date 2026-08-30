# Landscape — adjacent projects & validated patterns

Companion to decisions.md (#24–#27) and roadmap.md (T2). Reviewed 2026-08-25.
Purpose: record what the two closest projects (sherpa-onnx, candela) are, what their
existence proves for this app, and what their license/design boundaries imply. Re-read
before any TTS engine or pack-format work.

## The three projects at a glance

| | sherpa-onnx (k2-fsa) | candela (techempower-org) | this repo |
|---|---|---|---|
| Layer | inference **engine** (library) | Android **app** (reader + audiobook player) | Android **app** (ebook reader + TTS) |
| Stack | C++ core + onnxruntime; 12 language bindings incl. Java/Kotlin | Kotlin multi-module; Hilt + Compose | Kotlin multi-module; Hilt + Compose |
| License | Apache-2.0 | GPL-3.0 (app **and** its engine AAR VoxSherpa-TTS) | GPL-3.0 (decision #27) |
| Scale | ~14.4k stars, 625 open issues, prebuilt APKs/WASM/NPU support | shipped app (v1.2.3+), ~8 stars, 43 open issues | early: T1 done, 136 tests green |
| TTS | one capability (ASR, TTS, VAD, diarization, KWS, enhancement, source sep.) | 4 voice families in-process (Piper, Kokoro, KittenTTS, Supertonic) via VoxSherpa-TTS — a Java re-project of sherpa-onnx; System TTS fallback; Azure BYOK | Kokoro-82M primary, CosyVoice3 gated fallback (decisions #21) |
| Content | n/a | 34 sources (RSS, Royal Road, GitHub, EPUB, PDF, OCR, …) | EPUB + MOBI/KF8; share-and-identify |

## What each is

**sherpa-onnx** — the de-facto open engine for on-device speech: offline/streaming
ASR, TTS, VAD, diarization, identification, verification, spoken-language ID, audio
tagging, enhancement, KWS, source separation. Runs x86/ARM/RISC-V, Android/iOS/
HarmonyOS/WearOS/WASM, and several NPUs (RKNN, QNN, Ascend, Axera). Its TTS model
catalog includes Kokoro (v1_0: 53 zh/en speakers; v1_1: 103 speakers), Piper, Matcha,
and more. A Kokoro model bundle is **not a bare `.onnx`**: sherpa packages it with
`espeak-ng-data` (~26 MB), lexicons, tokens, `voices.bin`, and rule FSTs — the
phonemizer is part of the pipeline a client must ship.

**candela** — a shipped, distributed Android audiobook/reader app: hybrid
reader/audiobook view with sentence highlight, 34 fiction/reference sources, chapter
auto-advance with eager pre-generation, PCM chapter cache with pause/refill/resume,
multi-engine parallel synthesis, voice library, per-fiction speed, Wear OS + Android
Auto, AI chat, Voice Notes. It is the closest existing implementation of this app's
core loop. Its TTS runs in-process on sherpa-onnx via the GPL-3.0 VoxSherpa-TTS AAR;
voice packs download on demand from a `voices-v2` GitHub release.

**this repo** — offline-first ebook reader + on-device TTS, differentiating on
MOBI/KF8 parsing, share-and-identify (Kindle workaround), no accounts, GPL-3.0.

## Validated patterns (from candela; evidence in its README / docs / issues)

1. **In-process native engine ships on low-end Android.** Candela runs sherpa-onnx
   in-process on Helio P22T-class chips — no second APK, no engine-binding handshake.
   Proves the architecture our T4/T5 plan and conventions assume; T2's V3 device
   pass re-confirms on our hardware.
2. **Flat single-file voice packs.** Candela re-hosts k2-fsa tarballs pre-extracted
   on a `voices-v2` release: on-device `.tar.bz2` extraction delayed first chapters
   tens of seconds on low-end devices. → decision #26.
3. **fp32, not int8.** Candela shipped INT8 packs, then regressed to fp32: INT8
   dynamic quantization added audible vocoder noise ("fuzz" symptom on Samsung
   tablets). → decision #26.
4. **Pre-generation player pipeline.** Sentence-level synthesis ahead of playback;
   1–8 engine instances with per-engine thread pools; producer pinned to a dedicated
   audio-priority thread; PCM cache pauses/refills/resumes; eager next-chapter
   synthesis. This is the shape our T4/T5 pre-generation queue should take.
5. **System TTS as zero-download degraded fallback** — matches our conventions'
   "documented degraded fallback" allowance; a fallback, not a feature.

## License boundary (decisions #24, #27)

- **This repo is GPL-3.0** since 2026-08-25 (decision #27, supersedes #10) — the
  consistent license given the KindleUnpack-derived parser code; single-author
  relicense.
- **candela and VoxSherpa-TTS are GPL-3.0**: reuse is now **license-permitted** with
  attribution, but they stay design references only — a clean-room posture by choice
  (learning + architecture fit), not by law (#27).
- **sherpa-onnx is Apache-2.0** — compatible one-way into GPL-3.0; the legal engine
  path both candela (via a GPL wrapper) and we (directly) can use.
- **kokoro-onnx** (thewh1teagle, MIT) — compatible; the semantics reference.

## Differentiation (why this app still exists)

- **MOBI/KF8**: candela imports EPUB/PDF only; our core-ebook already parses DRM-free
  MOBI7/KF8 (50 tests).
- **Share-and-identify**: no competitor matches a shared snippet to a passage and
  resumes there; candela's OCR is import-side, not the Kindle workflow.
- **No accounts, no cloud paths, no AI chat**: deliberate scope; offline-first stays.

## Decisions taken

- #24→#27 — License: GPL-3.0 (pivot 2026-08-25); candela/VoxSherpa reuse is
  permitted but not chosen — clean-room by choice.
- #25 — T2 engine layer: raw kokoro-onnx JVM port (in progress); sherpa-onnx is the
  pivot if the V3 RTF/APK/ABI pass misses.
- #26 — Voice packs: flat single-file artifacts; fp32 default, int8 only if measured.

## Open items / gates

- **V3 device pass — DONE** (decisions #25 → #34/#49): the raw kokoro-onnx port
  passes on the S22 (RTF 0.66–0.76, engine open 1.5 s, arm64 APK 21.1 MiB); the
  espeak-ng Android packaging is closed — bundle built + pinned (#32), downloadable
  pack that auto-stages (#50); sherpa-onnx pivot dropped (no word timestamps).
- **int8 only if measured** (#26): re-test quantization when a real RTF gate demands
  it; prefer quantization-safe ops only.
- **candela's 43 open issues** are a free bug list for player/perf work — review
  before writing T4/T5 code.
## Second-engine candidates (owner, 2026-08-26)

Moved from ideas.md — engine research, not app features. The parked quality-tier
slot (decisions #29) still has no occupying engine: CosyVoice3 keeps the fallback
slot but stays gated off-device (#21). Neither candidate is a quality upgrade over
Kokoro — each is a different axis:

- **Supertonic 3** (supertone-inc): ~99M params ≈ Kokoro; flow-matching; 31
  languages vs Kokoro's 8; CPU-only ONNX; vendor RTF 0.200 (16-thread desktop
  CPU), ~0.3 on an e-reader at the 8-step baseline; inline expression tags;
  style-vector voices (same integration shape as Kokoro). Published quality is
  reading accuracy (EN WER 2.8% on Minimax-MLS) — no MOS/Elo, and the
  independent head-to-head listening test ranks Kokoro's naturalness above it at
  5 steps ("lacks warmth and prosody variation") — a coverage/robustness/speed
  axis, not quality. Gates before a tier: duration-output/anchor introspection
  (read-along contract, decisions #30b/#31 — unverified), OpenRAIL-M license
  review (use restrictions + attribution vs this GPL-3.0 public repo), S22
  device RTF. Known blockers: cloning is cloud-only (hosted Voice Builder —
  offline-first conflict); only 3/10 expression tags documented, user reports of
  ignored tags.
- **Piper** (VITS, per-voice): ~20+ languages — the only ready catalog for the
  languages the Kokoro pack lacks (de/ko, decisions #28); ~14–100 MB/voice. Not
  a quality play (plain prosody, no emotion/cloning); via sherpa it loses the
  read-along anchors (#30b — upstream has no word timestamps); per-voice
  licenses vary (some non-commercial — check per voice before pinning into a
  GPL distribution). Matcha is the same VITS/flow-matching class with a handful
  of corpus voices (ljspeech/vctk/zh) — no breadth or quality advantage, not a
  candidate.
Home if adopted: a second `TTSEngine` impl behind the existing seam (EngineTier,
PackRegistry, feature-settings engine list already built) — Supertonic as the
coverage/robustness tier, Piper as the language-gap fallback.


### D3 comparison sweep (2026-08-29)

Sweep for the roadmap D3 comparison spike (Kitten Nano vs CosyVoice3 vs Kokoro).
Primary source: the [Picovoice on-device TTS benchmark](https://picovoice.ai/blog/on-device-tts/)
(2026-07-14, updated 2026-08-18; independent, benchmark code Apache-2.0 at
`Picovoice/text-to-speech-benchmark`, Ryzen 7 5700X desktop CPU — relative
ordering transfers, absolute numbers do not), plus per-repo verification.
Additions to the 2026-08-26 candidates:

| Engine | License / stack | External benchmark (desktop CPU) | Verdict for D3 |
|---|---|---|---|
| **MOSS-TTS-Nano** (OpenMOSS, 2026-04) | Apache-2.0; 0.1B AR audio-tokenizer + LLM; 20 languages (incl. es/it/pt — the app's advertised set); streaming; voice cloning; 48 kHz stereo; standalone ONNX CPU pack (`OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`); official **Android ONNX Runtime Kotlin example** in-repo | Not in the Picovoice set; vendor claims realtime on a 4-core CPU | **Add as a D3 leg** — the only candidate matching both Nano's footprint ambition and Kokoro's language coverage, with the read-along risk being pure-AR decode on the HiBreak-class CPU and 48 kHz resample |
| **Pocket TTS** (Kyutai, MIT) | 242 MB model, streaming output | 0.37× core-hour, 610 MB peak, FTTS 1.71 s — best CPU ratio in the mid tier | **Watch, not a leg** — no Android runtime path (Rust/PyTorch stack); the port cost exceeds a spike's scope. Revisit only if MOSS/Supertonic legs miss |
| **Soprano TTS** (MIT) | ~280 MB | 4.1× core-hour — slower than realtime even on desktop | **Reject** — dominated by every candidate on speed at similar size |
| **Neu-TTS-Nano Q4** (Neuphonic) | GGUF, not ONNX | 7.3× core-hour, 2.1 GB peak | **Reject** — wrong runtime (would force a second inference convention) and slower than realtime |
| **Chatterbox-TTS-Turbo** (Resemble, MIT) | 0.5B-class | 13.4× core-hour, 7.5 GB peak, FTTS 48 s | **Reject** — audiobook-server class, not edge |
| F5-TTS / Fish Speech / Dia / Orpheus / Spark / CSM / Zonos | various | 0.5–2B+ LLM/flow backbones, CPU-infeasible | **Reject** — the CosyVoice3 #21 gate already measured this class |
| Picovoice **Orca** | Commercial only | 0.065× / 41 MB / 106 ms FTTS — the benchmark's floor | **Reference point only** — not open source; bounds what a commercial embedded engine achieves |

Two corrections to the 2026-08-26 entries:

- **Kitten Nano external evidence**: Picovoice measured it at **3.1× core-hour
  (slower than realtime on one desktop core), 320 MB peak, FTTS 10.5 s — no
  streaming output** (full-audio-then-play). The 25 MB disk size is real, but the
  assumed HiBreak RTF win over Kokoro (1.28× there, 2.0 GB peak) is **not
  supported** by this data point. D3's Nano leg must treat the RTF question as
  open and device-measured, not as a premise.
- **Supertonic 3 maintenance risk**: the vendor **announced 2026-07-23 that the
  repo will be archived with no further development or official support** for the
  open-source models (Voice Builder closed 2026-08-31). Weights stay downloadable
  (`Supertone/supertonic-3` HF; a community sherpa-onnx int8 export exists,
  `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`), and the repo GitHub header now
  shows MIT — but the OpenRAIL-M review gate from 2026-08-26 is replaced by a
  supply-lifecycle gate: pin the HF revision + hashes exactly like
  [cosyvoice3-pack.md](cosyvoice3-pack.md), and treat upstream fixes as our
  responsibility. Still the strongest coverage/speed candidate on paper (99M,
  31 languages, 44.1 kHz, Java ONNX example); the read-along duration-output
  introspection gate is unchanged.

Sources: [Supertonic repo/archive notice](https://github.com/supertone-inc/supertonic),
[MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano),
[Pocket TTS](https://github.com/kyutai-labs/pocket-tts),
[Picovoice benchmark](https://picovoice.ai/blog/on-device-tts/).
