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

**2026-08-31 update (#97):** Piper is **promoted to the D4 small-tier
comparison** (with Supertonic 3) for the Bigme B6, on the owner's three-tier
engine strategy. A direct-ORT VITS port is required — NOT the sherpa path —
to keep the shared espeak-ng/JNA phonemizer and to audit the VITS alignment
outputs for read-along anchors (#30b).

### D3 comparison sweep (2026-08-29)
Sweep for the roadmap D3 comparison spike (Kitten Nano vs CosyVoice3 vs Kokoro).
Primary source: the [Picovoice on-device TTS benchmark](https://picovoice.ai/blog/on-device-tts/)
(2026-07-14, updated 2026-08-18; independent, benchmark code Apache-2.0 at
`Picovoice/text-to-speech-benchmark`, Ryzen 7 5700X desktop CPU — relative
ordering transfers, absolute numbers do not), plus per-repo verification.
Additions to the 2026-08-26 candidates:

| Engine | License / stack | External benchmark (desktop CPU) | Verdict for D3 |
|---|---|---|---|
| **MOSS-TTS-Nano** (OpenMOSS, 2026-04) | Apache-2.0; 0.1B AR audio-tokenizer + LLM; 20 languages (incl. es/it/pt — the app's advertised set); streaming; voice cloning; 48 kHz stereo; standalone ONNX CPU packs, **pinned**: `MOSS-TTS-Nano-100M-ONNX` @ `f52645cb467506d8e18e746ddd59482685b74e58` (671.9 MB) + `MOSS-Audio-Tokenizer-Nano-ONNX` @ `ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae` (90.6 MB) | Not in the Picovoice set; vendor claims realtime on a 4-core CPU | **Add as a D3 leg** (#92) — the only candidate matching Kokoro's language coverage while adding cloning + streaming. Footprint correction after pinning: the runtime pack is ~**0.75 GiB** (two HF repos), so "tiny" refers to parameters, not the shipped artifact — the axis is coverage/cloning/streaming, not size. Open risks: pure-AR decode RTF on HiBreak-class CPU, 48 kHz stereo resample into the 24 kHz pipeline |
| **Soprano TTS** (MIT) | ~280 MB | 4.1× core-hour — slower than realtime even on desktop | **Reject** — dominated by every candidate on speed at similar size |
| **Neu-TTS-Nano Q4** (Neuphonic) | GGUF, not ONNX | 7.3× core-hour, 2.1 GB peak | **Reject** — wrong runtime (would force a second inference convention) and slower than realtime |
| **Chatterbox-TTS-Turbo** (Resemble, MIT) | 0.5B-class | 13.4× core-hour, 7.5 GB peak, FTTS 48 s | **Reject for D3 (2026-08-29)** — audiobook-server class, not edge. **Partially reopened 2026-08-31 (#97):** ONNX exports now exist — official `ResembleAI/chatterbox-turbo-ONNX` (en-only, 350M Turbo) and community `onnx-community/chatterbox-multilingual-ONNX` (23 langs, cloning, 0.5B AR; `textagent/…` is a mirror, not a pin candidate) — Chatterbox Multilingual becomes the **D5 high-end challenger vs CosyVoice3, gated on G0**; AR KV-cache memory on-device is the open risk. |
| F5-TTS / Fish Speech / Dia / Orpheus / Spark / CSM / Zonos | various | 0.5–2B+ LLM/flow backbones, CPU-infeasible | **Reject** — the CosyVoice3 #21 gate already measured this class |
| Picovoice **Orca** | Commercial only | 0.065× / 41 MB / 106 ms FTTS — the benchmark's floor | **Reference point only** — not open source; bounds what a commercial embedded engine achieves |

Two corrections to the 2026-08-26 entries:

- **Kitten Nano external evidence**: Picovoice measured it at **3.1× core-hour
  (slower than realtime on one desktop core), 320 MB peak, FTTS 10.5 s — no
  streaming output** (full-audio-then-play). The 25 MB disk size is real, but the
  assumed HiBreak RTF win over Kokoro (1.28× there, 2.0 GB peak) is **not
  supported** by this data point. D3's Nano leg must treat the RTF question as
  open and device-measured, not as a premise. **S22 measured (#93): the engine
  runs at RTF 0.28–0.36 but every output is NaN on ORT-android (1.23.2 + 1.29.0,
  all session-option profiles swept; identical bytes are finite on x86) — blank
  audio, blind quality gate fail. Measured drop for on-device use.** HiBreak
  measured too (#93): RTF 1.37–1.49 with the **same NaN bug and identical
  output sizes** — ORT-android/ARM-wide, not S22-specific.
- **MOSS-TTS-Nano S22 evidence** (#93): RTF ~3.5 avg (2.75–4.96,
  decode-dominated AR; the 375-frame manifest cap truncates long passages),
  48 kHz mono out, sessions ~0.95 GB PSS with a ~1.4 GB decode plateau —
  `setMemoryPatternOptimization(false)` + `setCPUArenaAllocator(false)` are
  required or lmkd kills the process at 6.6 GB RSS. pt-BR attempted fine
  (coverage measured, not claimed). **Blind quality gate rank 1/4 — pregen-gated
  candidate; live synthesis ruled out.** HiBreak measured too (#93):
  **unavailable — lmkd reclaims the process at ~2.5 GB RSS during the first
  AR decode** (3.97 GB device): the wall on weak-RAM devices is memory, not
  speed, and no HiBreak RTF exists.
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

### HF trending sweep — text-to-speech + onnx (2026-08-31)

Hub scan (`pipeline_tag=text-to-speech & library=onnx`, 1,054 models; first
trending page) for candidates beyond the D3/D5 set. New finds:

| Model | Size / footprint | Languages | License | Verdict |
|---|---|---|---|---|
| **Audio8 0.1B INT8** (`Audio8/audio8-TTS-0.1B-ONNX-INT8`) | 0.1B DualAR (attention+Mamba), INT8 per-token graphs; online files ≈ 443 MB (slow AR 133 MB + fast AR 37 MB + codec decoder 261 MB + tokenizer 5.9 MB; the 414 MB codec-encoder is registration-only); ~0.6 GB loaded; 19 ms/slow-AR token, 8 ms/fast-AR frame (8-thread CPU); 44.1 kHz | 11 (yue, zh, nl, en, fr, de, it, ja, ko, pl, es) | Apache-2.0 | **EVALUATE — small-tier / weak-phone realtime candidate** (B6-class): per-token recurrent decode (not full-sequence AR), zero-shot cloning, streaming PCM. Fresh (2026-08-25, sha `317c12d4…`) — expect churn. Vendor runtime is Python → needs a Kotlin port + read-along anchor audit (#30b). |
| **Audio8 0.6B INT4** (`Audio8/Audio8-TTS-Preview-0.6B-ONNX-INT4`) | 601M, weight-only INT4, ~1.0–1.2 GiB peak, 44.1 kHz | same 11 | Apache-2.0 | **EVALUATE — mid-tier step**: same family/quality axis, heavier. Graphs INCOMPATIBLE with the 0.1B pack (separate vendor runtime dir) — pin independently, not as a size variant. |
| **Chatterbox multilingual Q4** (`BricksDisplay/chatterbox-multilingual-ONNX-q4`) | Q4 weight-only quant of the D5 challenger: verified 828 MB single-file (conditional_decoder 226 MB, embed_tokens 68 MB, language_model 354 MB, speech_encoder 180 MB) vs 3.2 GB; MatMulNBits block-32; sha `171d2d6…` | 23 (same set) | MIT | **EVALUATE — D5 weak-device variant**. Open questions: Q4 quality delta + ORT-android (pinned 1.23.2) support for weight-only quant ops — open/run test before any pin. |
| **Higgs Audio v3 TTS 4B** (`onnx-community/higgs-audio-v3-tts-4b`) | 4B AR (36 L, hidden 2560, GQA 32/8), 8-codebook delay pattern, 24 kHz | 102 langs (single-digit WER/CER on 85) | **Non-commercial** (Boson research license) | **WATCH only** — expressive/multilingual state of the art (emotion/style/prosody/SFX tokens, zero-shot cloning), but the license blocks a GPL-3.0 public distribution; revisit only if licensing changes. |

**Quantization déjà vu — why chatterbox-q4 is not the failed q8/int8 path.** Prior
quant attempts were *dynamic*: Kokoro q8/int8 (decisions #67 — int8 `ConvInteger`
unimplemented on ORT CPU EP; q8 slower AND `max_abs_diff` 0.700 > 0.001 oracle
gate), KittenTTS int8 NaN on ORT-android (#93), and **CosyVoice3's int4 export is
already in use** (jiangzhuo9357, #49). Chatterbox-q4 is **weight-only**
MatMulNBits (no runtime re-quant, no ConvInteger) — a different op surface — but
still needs an ORT-android open test (pinned 1.23.2) before any pin.
Neu-TTS-Nano Q4 was GGUF (rejected for runtime, not quality, above).

**Alternate repos for tracked candidates (2026-08-31, Hub-verified):**

| Candidate | Pin / primary | Alternates & relations |
|---|---|---|
| Chatterbox multilingual (D5) | `onnx-community/chatterbox-multilingual-ONNX` | **Mirror, NOT pin**: `textagent/chatterbox-multilingual-ONNX` (identical card/script; sample code still points at onnx-community). **Q4 quant**: `BricksDisplay/chatterbox-multilingual-ONNX-q4` (above). En-only original: `onnx-community/chatterbox-ONNX`. LoRA adapter on the ONNX base exists (`franclarke/chatterbox-es-ar`, Apache-2.0). NOT candidates: `calcuis/chatterbox-gguf` (GGUF), `mlx-community/Chatterbox-TTS-{fp16,4bit,8bit}` (MLX/Apple Silicon only), single-lang fine-tunes (`ResembleAI/Chatterbox-Multilingual-{pt-br,hi}`, `Thomcles/…`, `grandhigh/…`) — PyTorch or wrong runtime. |
| Chatterbox turbo | `ResembleAI/chatterbox-turbo-ONNX` (en-only, 350M) | Base `ResembleAI/chatterbox-turbo` (PyTorch). `ResembleAI/chatterbox-flash` (en, block-diffusion) and `chatterbox-nano` (en) — PyTorch, no ONNX export. |
| Audio8 0.1B | `Audio8/audio8-TTS-0.1B-ONNX-INT8` | Community conversion `Masterx/Audio8-TTS-Preview-0.1B-ONNX-INT8` — **superseded** (official export adapted from it); PyTorch base `Audio8/Audio8-TTS-Preview-0.1b`. |
| Audio8 0.6B | `Audio8/Audio8-TTS-Preview-0.6B-ONNX-INT4` | PyTorch base `Audio8/Audio8-TTS-Preview-0.6b`; graphs incompatible with 0.1B (separate runtime). |
| CosyVoice3 (incumbent) | jiangzhuo9357 int4 export (S22-measured, #49) | Community export `Lourdle/Fun-CosyVoice3-0.5B-2512_ONNX`; official PyTorch `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`; `Sariel00/cosyvoice2_rknn` is RKNN (NPU), not general ONNX. |
| MOSS-TTS-Nano | `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX` (pinned, #92) | Companion codec `OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX`; PyTorch `OpenMOSS-Team/MOSS-TTS-Nano`; code `OpenMOSS/MOSS-TTS-Nano` + Reader app repo. |
| Supertonic 3 | `Supertone/supertonic-3` | Community sherpa-onnx int8 `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`; v2 sibling `Supertone/supertonic` (superseded). |
| Kokoro (primary) | `onnx-community/Kokoro-82M-v1.0-ONNX` (pinned) | Semantics ref `thewh1teagle/kokoro-onnx` (MIT). Variants: `Godelaune/Kokoro-82M-ONNX-German-Martin`, `contextboxai/Kokoro-Vietnamese` (40.3k↓), `msgflux/Kokoro-82M-streaming-onnx`. |
| Higgs 4B | `onnx-community/higgs-audio-v3-tts-4b` | PyTorch base `bosonai/higgs-audio-v3-tts-4b`. |

Also reconfirmed on the sweep (already tracked above): `Supertone/supertonic-3`
(27.6k↓), `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`, `Fun-CosyVoice3-0.5B`
(incumbent), `onnx-community/Kokoro-82M-v1.0-ONNX` (1.13M↓) and the chatterbox
pins. Niche voice packs (Piper/MMS/Kokoro extras, `Xenova/mms-tts-rus`);
`pltobing/XTTSv2-Streaming-ONNX` (Coqui lineage — restrictive license, avoid);
out of scope by size: TASTE2-8B, MCLP-RPTTS 8B, Ming-omni 16.8B-A3B (×2).

**Closer look (2026-08-31, measured host + HiBreak/B6):**

Host gate (ORT 1.23.2 CPU — same as the Android pin): both candidates open and
run every graph; chatterbox-q4's full pipeline (speech_encoder → embed_tokens →
30-layer GroupQueryAttention AR prefill → conditional_decoder) **decodes real
audio** (1.6 s, finite, peak 1.36) from AR-generated tokens; audio8's
slow/fast AR + fp16 codec all finite. The words "vocoder invalid shape /
Mul 480 vs 960" from fabricated token streams are **a token-stream artifact,
not a graph break** — the original unquantized export fails identically
(host, ORT 1.22.1 AND 1.23.2), and real AR tokens work. q4 was never a
quantization regression.

Device (Bigme HiBreak B6, ORT-android 1.23.2, 6 threads, mem-pattern/arena
off — MOSS lesson; `OnnxProbeRunner` in spike-tts):

| | chatterbox-q4 (790 MB) | audio8 0.1B (431 MB online set) |
|---|---|---|
| Engine open | speech_encoder 8.9 s, embed 0.7 s, LLM 4.8 s, **conditional_decoder 326 s** | slow_ar 15.5 s, fast_ar 1.2 s, codec 12.7 s (~29 s total) |
| Fabricated pass | enc 831 ms, embed 3 ms, LLM prefill 2243 ms — **all finite**; vocoder error identical to host (artifactual) | slow_ar step 5.8 s, fast_ar 21 ms, codec 3.8 s / 8 frames (**RTF ≈ 10** for the codec alone, 44.1 kHz) — **all finite** |
| Memory | — | vm_hwm 1.44 GB, total_pss 216 MB w/ all 3 sessions resident |
| NaN check | **none** (Kitten/MOSS class absent) | **none** |

Verdict hits: **audio8's realtime-on-weak-phones thesis is UNSUPPORTED on the
B6** — the fabricated slow-AR step at 5.8 s vs the vendor's 19 ms/token
(8-thread desktop) is ~300× and the fp16 codec alone is ~10× realtime; a full
generation-loop D4 leg (warm caches, incremental state) is required before any
realtime claim, and the current numbers say pregen-only at best on B6-class.
**chatterbox-q4's weak-device-variant thesis is HIT HARD** — the 326 s vocoder
open on the HiBreak (5.4 min before a single sample) makes the 790 MB-vs-3.2 GB
trade a net loss on this device; the 3.2 GB original's vocoder open should be
A/B'd before concluding, and D5's AR-KV memory lesson still applies to the LLM
decode. Both remain EVALUATE for D4/D5 full-loop legs, with the realtime/pregen
split as the decision.

Sources: [Supertonic repo/archive notice](https://github.com/supertone-inc/supertonic),
[MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano),
[Pocket TTS](https://github.com/kyutai-labs/pocket-tts),
[Picovoice benchmark](https://picovoice.ai/blog/on-device-tts/),
[Audio8 TTS](https://github.com/Audio8-AI/Audio8_TTS),
[chatterbox-multilingual-ONNX-q4](https://huggingface.co/BricksDisplay/chatterbox-multilingual-ONNX-q4),
[Higgs Audio v3](https://www.boson.ai/blog/higgs-audio-v3-tts),
[HF TTS+onnx hub sweep](https://huggingface.co/models?pipeline_tag=text-to-speech&library=onnx&sort=trending).
