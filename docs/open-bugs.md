# Open bugs & known limitations

Register of reported-but-unfixed bugs and known limitations, as of 2026-08-27.
Each entry names where it was reported and its current status; entries are
removed (or marked fixed) when a fix lands. This is a tracking doc, not a work
plan — slicing happens through decisions.md.

## Product bugs

| Bug | Symptoms / impact | Status | Reported in |
|---|---|---|---|
| **LSTM traineddata unusable: OCR capped at legacy accuracy** | `tessdata_fast 4.0.0` LSTM models fail `init` on tess-two 9.1.0 (native build is pre-LSTM). Pinned English/Italian packs are the legacy `3.04.00` artifacts; OCR accuracy is below what current Tesseract LSTM models offer. | Open — workaround shipped (legacy packs, S1); fix = a maintained/newer tess-two binding in a future slice, re-pin LSTM packs | decisions #36 (Consequences + "Open:"), #37; `modules.md` (feature-ocr row) |
| **Opus audio tier impossible via MediaCodec on the S22** | MediaCodec opus DECODER is broken at the native level (every stream/decoder errors; async path SIGSEGVs inside the codec memcpy); the ENCODER emits non-conformant payloads (83-byte csd-0; #50 falsified the "maybe savable" read — reference libopus refuses the stream). An Opus cache would require bundled libopus for encode AND decode. | Open — decided "not a dependency"; PCM cache (≈170 MB/h) stays | decisions #46, #50 |
| **ktlintCheck green-goal unmet** | `./gradlew ktlintCheck` is part of the definition of done but no lint plugin is configured — the check doesn't exist yet. | Open — future slice | `conventions.md` (definition of done) |
| **Library search not built** | `feature-library` has no search UI; the index/matcher core (core-locate) is live and rebuilt at launch, the surface is not. | Open — feature gap, not a regression | `modules.md` (feature-library row), `architecture.md` §2/§4 |
| **CosyVoice3 bundle URLs not pinned in the repo** | The #49 on-device run staged models ad-hoc (HF snapshot `jiangzhuo9357/cosyvoice3-0.5b-onnx` + locally derived prompt wavs); the repo carries no URL/hash for the bundle, so the fallback tier isn't reproducible from the repo. | Open — revisit whether to pin exact URLs | decisions #49 |
| **Android Auto media controls unverified on device** | T4 acceptance lists "Auto verify" (MediaSession-based controls on Android Auto); no device-pass evidence is recorded yet. | Open — verification pending | `roadmap.md` T4 row, ideas.md |
| **No progress feedback during book import** | A SAF import batch shows no in-flight progress or cancellation — the "Added N · Unchanged M" summary appears only after completion, so large/multi-file imports look hung. (Roadmap C5/C6 claim "batch import with progress"; the UI doesn't surface it.) | Open — user report 2026-08-27 | user report; `roadmap.md` C5/C6 |

## Test-harness limitations (worked around, not fixed)

| Limitation | Workaround | Reported in |
|---|---|---|
| Instrumented test classes sharing one process trip Room-reopen races | Run each test CLASS in its own `am instrument` invocation | `build.md` (instrumented set), decisions #36/#45 |
| Hilt instrumented-test application override does not take effect in this AGP 9 project | Share-pipeline test builds the real components manually | decisions #37/#38 |

## Fixed items removed from this list

For the record, items reported in docs that have since been fixed (kept out of
the open list): sub-1% progress truncation to "0%" (fixed in #51/#52 — both
surfaces format `%.1f%%` below 1%), remove-book-from-library (#51), the #50
Dagger File-collision + FGS-timeout device findings, `INTERNET` permission +
typed download failure (#47), the three S-debug regressions (#39), and the
stale-resume crash (2026-08-27 `fix(player)` commit).