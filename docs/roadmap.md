# Roadmap

Forward sequencing for Ayvu. Shipped work is reference-only below; the open
items are the active queue. Implementation history belongs in
[decisions.md](decisions.md); candidate features remain in [ideas.md](ideas.md)
until they are promoted here.

## Current state

v0.1.1 has shipped — GitHub Releases signed unminified APK,
`io.github.moronigranja.ayvu` (decisions #126, #128). The v1 capability spine is
complete and device-verified: import → index → local TTS playback with read-along →
share-and-resume, plus settings, OCR, offline pre-generation, storage controls,
backup & restore, and the app-wide player card. The current module and test snapshot
lives in the [README](../README.md#status).

Remaining work is ordered around narration quality (Phase G) and measured
performance (Phase D), then stats, gestures and the recorded review subjects. Open
defects and their acceptance criteria are authoritative in
[open-bugs.md](open-bugs.md).

## Planning rules

- Correctness work precedes features that build on the affected contract.
- Room is durable truth; indexes and audio caches are derived, recoverable state.
- Performance work starts with a measurement gate on physical devices; no delegate,
  quantization, or engine path ships because it is plausible on paper.
- A roadmap item is complete only after its observable acceptance scenario is run.
- No date or total-duration forecast is maintained while the stabilization scope is
  still changing.

## Shipped — reference only

The v1 spine:

| Legacy IDs | Delivered capability | Evidence |
|---|---|---|
| F1–F2 | Android/Hilt foundation and canonical domain model | README module inventory; decisions #1–#13 |
| C1–C7 | EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown import; segmentation; SAF library flow | decisions #10–#13, #29, #50 |
| P1–P2 | Room persistence, cached parses, progress, settings and launch-time index rebuild | decisions #22, #33 |
| T1–T5 | Verified packs, Kokoro, player state machine, MediaSession, read-along, bookmarks, undo, sleep timer and pre-generation | decisions #23–#35, #42 |
| S1–S3 | OCR, share receiver, match result and listen-from-here | decisions #36–#38 |
| V1–V3 | Settings, CI, S22 performance/device passes | decisions #34, #36, #39–#41, #49 |

Post-v1 phases, one line each:

| Phase | Shipped | Evidence |
|---|---|---|
| A1–A8 | Player/pregen/Room correctness repair: pregen terminal truth, live playhead persistence, single `ImportCoordinator` (Room→index), cross-process PCM LRU bootstrap, single-writer player commands + state agreement, composition root + feature boundaries, and the Room "deletion" classified as our E2E teardowns (not Samsung) | decisions #60–#66, #107 |
| B1–B4 | `AyvuTheme` tokens + shared `core-ui` set; four-surface redesign; S22 + HiBreak visual/a11y acceptance | decisions #68, #94, #95, #98 |
| C1–C3 | Guided first-run setup (PRIVACY → DOWNLOAD_PACKS → CHOOSE_VOICE → IMPORT_BOOK); one shared voice selector (Settings/reader/first-run); setup recovery re-derived from durable facts | decisions #102, #105, #106, #112, #119 |
| E0–E1 | Storage-location decision (one-shot SAF, no persistent grant); versioned SAF backup/restore with opt-in book bytes | decisions #109, #111 |
| F1–F4 | Import progress + cancel, library search, SAF folder import, external-file intake (ACTION_VIEW / book-share → one import overlay) | decisions #64, #90, #108, #117, #118 |
| I1–I2 | Book start detection (skip cover/TOC/index) + smart chapter detection in monolithic books | decisions #69, #70 |
| G2 | Paragraph context menu: long-press **Play from here** / **Copy text** | decisions #127 |
| G4 | Speed selector removed; playback pinned 1.0× | decisions #71, #109 |
| Immersive reader | Full-screen reader: overlay title + minimal player, book-wide passage indicator, follow-active-sentence, middle double-tap chrome toggle, play-from-view, stop-on-turn, keep-page | decisions #120–#122, #125 |
| TTS/player polish | Playback volume gain; manual pregen budget anchors at the reading position and is listening-time; player-card coverage-bar redo + generation notification; slice-relative pregen progress + library Stop control; configurable synthesis thread count; emit-early per-window streaming | decisions #129, #132–#138 |

Historical estimates and completed implementation specifications were removed from this
file. Git history and the decision ledger retain them.

## Measured engine and performance verdicts

These are the non-obvious measured conclusions a future re-evaluation starts from. Full
numbers live in the cited decisions.

### D2 — execution providers, precision, parallelism (decisions #67, #86, #115, #116, #139)

- **CPU-EP default stands.** Precision is fp32 only: fp16 produced a silent en-us stub
  (`max_abs_diff` 0.723); q8 failed the 0.001 gate at 0.700 *and* was slower
  (RTF 1.73–1.79 vs 1.16–1.20); int8 is non-runnable (`ConvInteger` on CPU EP).
- **Hexagon NPU (QNN EP) does not offload the fp32 Kokoro graph** (StridedSlice fails
  HTP op validation → 100% CPU, oracle diff 0). Re-open only as a battery/thermal play
  with a static-shape re-export; ANE prior art `laishere/kokoro-coreml` (17× realtime).
- **2-engine parallel pregen is slower**: serial 1.43 audio-s/s vs parallel 1.21
  (1.18×) at +76% VmHWM / +84% PSS. Window-parallel re-ask at candela granularity:
  serial wins at every config.
- RTF baseline: S22 1.16–1.20 (#86) / 0.66–0.76 (listening corpus); HiBreak 2.84–3.12.

### D3 — engine comparison (decisions #93, #96)

| Engine | S22 RTF | Verdict |
|---|---|---|
| Kokoro-82M fp32 | 0.77 | shipped baseline |
| KittenTTS Nano v0.8 | 0.31 | DROP — NaN on ORT-android (ARM-wide) |
| MOSS-TTS-Nano | ~3.5 | DROP — decode-AR; lmkd kill ~2.5 GB on the HiBreak |
| CosyVoice3 0.5B | 12.5–31.1 | DiT-gated; duplicated honorific probes |

### D4 — small tier for the HiBreak (decisions #99, #110)

| Leg | HiBreak RTF | Verdict |
|---|---|---|
| Piper en_US-lessac-medium | 0.57 | KEEP — passage-level read-along only (#30b); adoption pending |
| Supertonic 3 | 3.92 | DEFER — duration introspection passes |
| Audio8 0.1B INT8 | N/A | DROP — slow-AR 5.8 s/token |

### Phase J — offline NMT (decisions #114)

| Model | Verdict |
|---|---|
| M2M-100-418M | DEFER — fp32 fails the memory gate; int8 24–31 ms/token, lower chr-F than SMaLL-100 |
| SMaLL-100 int8 | ADOPTED for translate-then-read — one 915 MB pack, 8.9–9.9 ms/token, chr-F 51.9–63.2 |
| OPUS-MT per-pair | measured record; specialist alternative (tc-big int8 speed-disqualified; fp32 quality fallback) |

## Active work

### Phase D — playback latency and weak-device performance

#### D1 — Instant ±30-second seek horizon (designed, measured — not implemented)

Replace fixed two-passage look-ahead with an approximately 30-second audio horizon and
let in-flight `PregenQueue.ensure` survive a seek before refilling from the new
playhead. Persisting the look-ahead hot zone is optional unless measurement shows RAM
churn still causes misses.

Acceptance on both reference devices:

- Ten representative ±30-second seeks after normal listening resolve from
  `buffer|pregen|disk`, with zero synchronous synthesis at seek time.
- Cold first play after a process start (engine open) resolves without main-thread
  Choreographer skips attributable to playback, inside the D2 first-audio baseline.
- Queue memory remains bounded and overnight/manual pre-generation behavior is
  unchanged.
- Record latency separately on the S22 and Bigme HiBreak.

**Measured 2026-08-29:** cross-boundary ±30 s seek to an uncached passage is
79.6 s (S22) / 107.0 s (HiBreak). The 60 s dead-owner ensure wait was fixed
(decisions #78) — remaining cost is the cold target's synchronous synthesis,
which is exactly this item's target. Design unchanged.

#### D4 adoption — PiperEngine

Integrate `PiperEngine : TTSEngine` behind the existing seam, pin per-language voice
packs + hashes, and complete the es-IT/de/ko coverage check. Ships passage-level
read-along only (stock Piper export exposes no word timestamps — #30b).

#### D5 — High-end cloning: Chatterbox vs CosyVoice3 — gated on G0

- Candidates: **CosyVoice3** (incumbent — 9 langs incl. es/it, zero-shot + cross-lingual
  cloning, pinned pack, measured 3.22 GB VmHWM on the S22) vs **Chatterbox Multilingual
  ONNX** (MIT, 23 langs incl. es/it/pt/de/ko, zero-shot cloning, 0.5B AR Llama backbone).
- Provenance gate first: the only ONNX export is community
  (`onnx-community/chatterbox-multilingual-ONNX`); `textagent/…` is a mirror of the same
  export, NOT a pin candidate. Pin revision + sha256 and verify output parity against the
  PyTorch reference before measurement (the #86 fp16-stub lesson). The official
  `ResembleAI/chatterbox-turbo-ONNX` is English-only — fails multilingual.
- Measurement (pregen-budget terms on the S22): per-passage wall time, peak/resident PSS
  through an AR KV-cache decode (the MOSS lesson — memory, not speed, kills weak RAM),
  and the G0 blind gate against CosyVoice3's #93 quality flag.
- Integration-cost audit: HF BPE tokenizer (new tokenization path vs espeak-ng; the
  advertised set en/es/it/pt/de needs no external normalizer, zh/ja/he do); 24 kHz output
  vs `lastSampleRateHz`; watermark off by default.

#### D6 — Cross-runtime inference spike: ORT vs GGUF/llama.cpp (TFLite gated)

Closes the evidence gap that ORT was chosen by path-of-least-resistance and never
benchmarked against an alternative runtime. Runs in the `spike-tts` harness only — no
second in-app inference convention, and decisions #97 stands until a measured decision
overturns it.

| Leg | Graph | Role |
|---|---|---|
| ORT-android 1.29.0 | shipped Kokoro-82M fp32 | Known-good baseline |
| ORT-android 1.29.0 | CosyVoice3-0.5B int4 (#49) | ORT reference on a quantized DiT |
| ~~llama.cpp / GGUF~~ | ~~`cstr/cosyvoice3-0.5b-2512-GGUF`~~ | Dropped — decision #56: no audited llama.cpp path runs CosyVoice3-class flow TTS (no CFM/DiT/vocoder ops, no multi-GGUF loading); the GGUFs target the unaudited CrispASR whisper.cpp fork, also dropped |
| TFLite / ExecuTorch | gated | No tracked TTS export ships one; recorded untestable |

Required evidence per leg: cold engine-open time-to-first-audio, steady-state RTF,
peak/resident PSS + VmHWM, and the #67 PCM oracle (`max_abs_diff`) against the fp32
Kokoro baseline — S22 and HiBreak, same corpus/voice as D2/D3.

Method caveat: GGUF vs ORT-int4 confounds runtime with quantization, so each number
states which axis it actually isolates. The llama.cpp leg is closed by the host-side
feasibility probe (decisions #56, 2026-09-09): the #97 one-convention rule is now
evidence-backed — no audited on-device runtime other than ORT runs this class — with
the typed keep/drop recorded there. Remaining acceptance: the ORT legs comparison
(Kokoro fp32 baseline vs CosyVoice3 int4 reference — cold engine-open
time-to-first-audio, steady-state RTF, peak/resident PSS + VmHWM, #67 PCM oracle,
S22 and HiBreak, same corpus/voice as D2/D3). The Android leg (plan Step 3) is not
built; the Step-2 fallback applies per #56.

#### G0 — Narration-quality listening corpus — bounds G1

Build the listening corpus (names, honorifics, abbreviations, numbers, dates,
currencies, measurements, Roman numerals, dialogue, headings, footnotes, page
furniture, URLs/code-like text, long paragraphs, speed transitions, every advertised
language) and run it through the shipped Kokoro pipeline with the existing `spike-tts`
runner — the D3 corpus/harness infrastructure makes this mostly curation, not tooling.
Findings become a typed list of mispronunciation classes; G1's rule scope is bounded by
measured failures, not the single `Ms.` regression.

Acceptance: the corpus synthesizes end-to-end on the S22; findings recorded as typed
classes with examples; G1's built-in rule set is derived from them.

Status: corpus (377 entries, 9 languages × 15 categories) built and synthesized
end-to-end on the S22; device corpus, WAVs and measurements complete
([g0-findings.md](g0-findings.md)). Roman-language mispronunciation classes await the
owner's listening pass; ja/cmn/hi are recorded as a non-native-ear limitation.

#### G1 — TTS pronunciation replacements — promoted from ideas

Add a deterministic, testable normalization/replacement stage before phonemization for
names, honorifics, abbreviations, pauses and intentionally skipped page furniture. The
reported `Ms.` → "M S" defect was the first regression case, already shipped
(`PronunciationNormalizer` + `NormalizingPhonemizer`, `Ms.` → `Miz`, 2026-08-29). The
remaining built-in correction set is bounded by G0's typed findings.

Start with ordered literal rules plus a small built-in correction set. Regex and user
editing require explicit limits and preview because an unbounded rule can silently
rewrite an entire book. Matching/index text remains unchanged; replacements affect TTS
output only.

#### G3 — Hardware and listening gestures — promoted from ideas

Add the narrow useful subset before building a configurable gesture editor:

- Media/headset play-pause and seek commands continue through `MediaSession`.
- Optional volume-key passage navigation is limited to the visible reader and is off by
  default; normal system volume behavior must remain the default.
- Screen-off behavior must use supported media-session commands rather than promising
  interception Android does not deliver to an inactive activity.

Configurable tap-zone maps remain in the idea pool until the fixed reader interactions
have device evidence and an accessibility review.

### Phase H — TODAY reading and listening stats

Use the capture, aggregation and UI design in
[post-v1-plan.md](post-v1-plan.md#slice-a-today-stats-dashboard). Store whole seconds
and round only for display so short valid sessions are not discarded.

Reading/listening capture is decided (2026-09-02, decisions #109): listening =
wall-clock while `PLAYING`; reading = **page-flip-active** reader dwell (screen-on
foreground, accrued only while the user is actively turning pages) with sub-10-second
spans dropped — not raw foreground dwell.

A full event/session timeline is not a prerequisite for the dashboard. Add it later only
if a user-visible history view needs event-level data.

### Phase K — Settings review and improvements

The settings surface has accreted without an information-architecture pass since B3.
`AppSettings.Snapshot` grew from five keys (threshold, voice, favorites, theme, OCR
languages) to nine (adding `ttsEngine`, `playbackGain`, `ttsThreads`,
`realtimeCapable`), and the screen now spans engines/packs, voice + favorites, match
threshold, OCR languages, theme, offline audio, playback volume, generation threads and
backup & restore — with no grouping beyond stack order. Review first, then land the
concrete improvements:

1. **Grouping and discoverability.** Reorganize into coherent sections (speech vs
   reading vs storage/data) with headers; review the "applies after restart" knobs
   (`ttsThreads`, `realtimeCapable`) for user-comprehensible copy, and keep the B4
   accessibility bar (TalkBack, 48 dp targets, theme) intact.
2. **Engine-agnostic pack rows.** `SettingsScreen` hardcodes `KOKORO_PACK_IDS` /
   `OCR_PACK_IDS`, and `VoiceCatalog` npz parsing is kokoro-specific. Derive pack rows
   from the registered engine's descriptors so D4's Piper (per-language packs) or D5's
   CosyVoice adds its packs without a settings-surface edit.
3. **Arbitrary pre-generation budget.** The library pre-gen dialog ships fixed presets
   (30 m / 1 h / 2 h / 3 h / whole book); the backend already accepts any
   `PregenBudget.maxTimeMs` (A1) — parse an arbitrary listening-time input, UI-only.
4. **Per-book overrides — explicit decision.** Record a keep/defer for per-book speed
   (ideas #50) and per-book voice; no silent global-only assumption.

Acceptance: settings are grouped and navigable without losing any existing knob or its
persistence; adding an engine adds its packs without a settings-screen change; an
arbitrary pre-gen duration works alongside the presets; the per-book-override decision
is recorded.

## Later — strategic and dependency-gated work

| Item | Gate / reason for position |
|---|---|
| Pitch-preserving speed | WSOLA/phase-vocoder DSP and cache-key compatibility; measure CPU/battery before replacing hardware rate conversion. |
| CosyVoice pre-generation + voice cloning | DiT-gated (decisions #21/#23) and D3-quality-flagged (duplicated honorific probes; RTF 12.5–31.1); disk-only playback. A1/A4 long satisfied. |
| Kindle official export/API sync | External API/export contract and account UX; manual share/resume already covers the core use case. |
| Word-level highlighting | Requires a stable word/phoneme timing contract beyond current sentence anchors. |
| Auto language detection and voice routing | Needs per-language voice mappings, mixed-language policy and pack-availability UX. |
| Full read/listen session history | Build only with a concrete history/export/statistics consumer. |
| Auto-delete listened audio | Eviction design first: must preserve the current playhead and every position reachable by undo — a design that does not yet exist (A4's LRU repair is not the eviction policy). |
| Habit-driven pre-generation | Stats/session evidence first; prediction may rank work but never override storage, charging or playback-yield limits. |
| Profiles, collections and book-map navigation | Valuable reader/library expansion after search, folder import and basic controls are complete. |

## Idea pool — not scheduled

RSVP speed-reading, downloadable public-domain classics, a fully configurable tap-zone
editor, and speculative multi-engine parallelism remain in [ideas.md](ideas.md). They
have no dependency that warrants placing them ahead of stabilization, data safety or
the promoted library/narration work.

## Further reviews — recorded, not scheduled

These are review subjects, not implementation commitments. Each should produce a
bounded decision or roadmap proposal before code starts.

### Hostile-input and resource limits

Audit every untrusted boundary: EPUB/KF8 entry count, expanded bytes and compression
ratio; MOBI decompression ceilings; pathological chapter/passage counts; malformed
covers and shared images; backup path traversal, duplicate entries, oversized JSON and
unknown sections; pack archives; temporary-file cleanup; and disk-full behavior during
import, restore and pre-generation. Existing XXE hardening is a baseline, not the whole
resource-exhaustion contract.

### Release readiness

Distribution decision made (decisions #126, 2026-09-05): **GitHub Releases signed APK,
manual local signing** — CI stays a gate (tag assemble only). The local signing pipeline
ships: release keystore outside the repo, gitignored `keystore.properties`, unminified
`release` buildType, `tools/release.sh` (build + apksigner verify + draft/publish
release), `NOTICE.md` attribution. Remaining before the first public tag: press publish —
the release notes (`docs/release-notes-0.1.1.md`) are written and the on-device sanity
pass on the SIGNED 0.1.1 build is done (decisions #128 follow-up covers the
manifest-component fix it caught).

Deferred until a store listing is actually wanted: AAB + Play Data Safety, store privacy
policy, listing/screenshots, supported-devices declaration. Native crash symbols and
shrink rules are moot while unminified; a shrink pass (R8 rules + device regression) is
the gate for enabling minify. Backup versioning: `versionCode` increments per release;
the backup codec carries its own version, so restore compatibility stays codec-scoped.

### Android lifecycle and interruption matrix

Exercise wired/Bluetooth disconnect and reconnect, calls/assistant/navigation focus,
permanent vs. transient loss, lock screen, process recreation/low-memory kill, reboot
during scheduled work, notification restoration, Android Auto and rapid commands from
multiple surfaces. Fold failures into A2/A5 acceptance rather than creating parallel
player state machinery.

### Targeted follow-up reviews

- OCR replacement technology for the known legacy-tessdata accuracy ceiling.
- Library metadata: series, author normalization, duplicate editions and sorting.
- A privacy-preserving local diagnostic export containing versions, pack/storage state
  and typed failures, never book text.
- Battery/storage policy for overnight pre-generation defaults, charging constraints
  and cache-budget consequences.

## Outstanding verification and tooling debt

- Verify Android Auto controls on real or emulator-backed Auto hardware; MediaSession
  wiring alone is not device evidence.
- Continue physical-device acceptance on the S22 and HiBreak for behavior or performance
  claims affecting playback.

The A1/A2/A4/A5–A7/A6/F2 device-evidence rows and the ktlint gate are all closed
(decisions #105, 2026-08-31/09-01); B4/C2 device checks are complete (decisions
#98, #105).
