# Roadmap

Active sequencing for Ayvu after the v1 functional spine. Detailed implementation
history belongs in [decisions.md](decisions.md); candidate features remain in
[ideas.md](ideas.md) until they are promoted here.

## Current state

The v1 capability spine is complete and device-verified: import → index → local TTS
playback with read-along → share-and-resume, plus settings, OCR, offline
pre-generation, storage controls, and the app-wide player card. The current module and
test snapshot lives in the [README](../README.md#status).

The app is **feature-complete for v1 but not stabilization-complete**. The next work is
ordered around shipped-contract correctness, then measured performance and post-v1
product value. Open defects and their acceptance criteria are authoritative in
[open-bugs.md](open-bugs.md).

## Planning rules

- Correctness work precedes features that build on the affected contract.
- Room is durable truth; indexes and audio caches are derived, recoverable state.
- Performance work starts with a measurement gate on physical devices; no delegate,
  quantization, or engine path ships because it is plausible on paper.
- A roadmap item is complete only after its observable acceptance scenario is run.
- No date or total-duration forecast is maintained while the stabilization scope is
  still changing.

## Completed v1 — reference only

| Legacy IDs | Delivered capability | Evidence |
|---|---|---|
| F1–F2 | Android/Hilt foundation and canonical domain model | README module inventory; decisions #1–#13 |
| C1–C7 | EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown import; segmentation; SAF library flow | decisions #10–#13, #29, #50 |
| P1–P2 | Room persistence, cached parses, progress, settings and launch-time index rebuild | decisions #22, #33 |
| T1–T5 | Verified packs, Kokoro, player state machine, MediaSession, read-along, bookmarks, undo, sleep timer and pre-generation | decisions #23–#35, #42 |
| S1–S3 | OCR, share receiver, match result and listen-from-here | decisions #36–#38 |
| V1–V3 | Settings, CI, S22 performance/device passes | decisions #34, #36, #39–#41, #49 |
| Post-v1 shipped | Offline chapter pre-generation, storage transparency, reader/library polish, shared player card, Phase B design tokens + shared components, Phase I book start + chapter segmentation | decisions #42–#56, #68, #69/#70 |

Historical estimates and completed implementation specifications were removed from this
file. Git history and the decision ledger retain them.

## Phase A — stabilize shipped contracts

These are release-blocking because they can lie about completion, lose user state, or
make durable and derived state disagree.

| ID | Work | Required result |
|---|---|---|
| ~~A1~~ | ~~**Pre-generation terminal truth (CR-1)**~~ | ~~Whole-book mode reaches synthesis; unbounded and expired budgets remain distinct; unavailable/failure terminals do not report success.~~ **Complete (2026-08-27, decisions #60):** `PregenBudget.remainingTimeMs` keeps an absent deadline unbounded (never “expired”); `OfflinePregen.run` returns a `PregenTerminal` for every stop; the worker fails on `Unavailable`/`FailureCap` with typed `KEY_ERROR` + progress counts, and the library row surfaces the error. Host evidence: `PregenWorkerTest` (6), `PregenBudgetTest` (4), `OfflinePregenTest` (13). Device `PregenE2eTest` re-run pending on the Bigme B6 / S22. |
| ~~A2~~ | ~~**Live playhead persistence (CR-2)**~~ | ~~Capture the playhead before output teardown, make one final transactional write, and checkpoint active playback at a documented interval.~~ **Complete (2026-08-27, decisions #61):** `stopPlayer`/`onDestroy` capture `liveOffsetSeconds()` before `stopEverything()` releases the output; the graceful stop's write is a `finalStopJob` that `onDestroy` joins — exactly one authoritative write per session, never a stale teardown overwrite; the player coroutine checkpoints the resume row every 5 s (`CHECKPOINT_MS`, `dueCheckpoint` gate) so abrupt process death loses at most one interval. Host evidence: `PlaybackServiceCr2Test` (4 — STOP persists baseline 10 s + 5 s live = 15 s with a fake output that zeroes its head on stop; teardown single-write; join-no-double-write; checkpoint gate). Device stop-mid-passage/kill/reopen acceptance pending on the Bigme B6 / S22. |
| ~~A3~~ | ~~**Room/index consistency (CR-3)**~~ | ~~Parse without publication → commit Room → publish to `TextIndex`; serialize rebuild/import/delete reconciliation; storage failures become typed UI outcomes.~~ **Complete (2026-08-27, decisions #65):** one `ImportCoordinator` boundary (core-ebook over `LibraryStore` + `TextIndex` + the new `IndexLock`) enforces parse-without-publication → durable commit → index publish; `LibraryStore.contains` (Room-backed) is the re-import duplicate gate — a failed commit returns typed `ImportFailureReason.Storage` with the index untouched, and retry re-parses + re-commits exactly once; delete runs durable-first (index removal only after a successful Room delete); the launch rebuild (`LocalTtsReaderApp`) reconciles INSIDE the lock, reading its Room snapshot fresh, so a stale snapshot can never purge a concurrently committed book. Host evidence: `ImportCoordinatorTest` (6 — durable-then-index, duplicate gate, failed-commit retry, barrier-controlled stale-rebuild race, F1 progress + cancel moved with the batch loop), `LibraryViewModelTest` (9 — incl. failed-durable-delete keeps the surviving book indexed), `BookImporterTest` (11, parse-only contract). Device share-after-cold-start-import regression pending on the S22. |
| ~~A4~~ | ~~**Cross-process PCM LRU (CR-4)**~~ | ~~Reopen bootstraps valid entries into eviction order, removes invalid pairs, and enforces the byte cap without self-evicting every new entry.~~ **Complete (2026-08-27, decisions #63):** `PcmPassageCache` bootstraps every valid on-disk entry into `recency` by pcm mtime (oldest first — deterministic approximate LRU across restarts; in-process reads still refresh exact order) and converges an over-cap cache at construction, so `bytesRemaining`/the next `put` never start frozen; invalid artifacts (stale `.tmp`, meta-less PCM, pcm-less meta, unparseable paths) are deleted at open, killing permanent `contains` false hits; policy: an entry larger than the cap alone cannot be retained. Host evidence: `PcmPassageCacheTest` (12, incl. reopen-evicts-old, over-cap convergence, invalid-pair cleanup, oversized-entry policy). Device fill-cap/force-stop/relaunch acceptance pending on the S22. |
| ~~A5~~ | ~~**Single-writer player commands (CR-5)**~~ | ~~Superseded loads cannot publish state or foreground side effects; pause/resume/navigation ordering is deterministic.~~ **Complete (2026-08-27, decisions #62):** every control-plane command (open/openChapter/play/resume/pause/navigate/seek/undo/speed) runs as a tracked `commandJob` under a monotonic generation; `stopEverything` cancels and supersedes in-flight commands, and each command re-checks its generation before ANY publish/startForeground/stopForeground/startLoop side effect — a stale load can never publish, drop the foreground, or restart the loop (cancellation alone is not enough: `storeOp` swallowing cancellation was the CR-7 mechanism). Host evidence: `PlaybackServiceA57Test` (6). |
| ~~A6~~ | ~~**Composition and feature boundaries (CR-6)**~~ | ~~App-level bindings own shared infrastructure; features depend on core contracts, not other feature implementations; a build check rejects new `feature-* → feature-*` edges.~~ **Complete (2026-08-27, decisions #66):** `app.di` is the composition root — `PersistenceModule`, the import-core providers (`TextIndex`/`ImportCoordinator`/`IndexRebuilder`/`appScope`/`@IoDispatcher`) and `OcrModule` moved there; player state (`PlaybackStateHolder`/`PlaybackUiState`), commands (`PlayerCommands`), the pre-gen scheduling + storage contracts (`PregenScheduler`/`OfflineStorage`) moved to core-player; `TessDataStager`→core-ocr, `EspeakStager`/`formatBytes`→core-player; the shared `PlayerCard` composable moved to a new `core-ui` module. feature-library/settings/share now compile with ZERO feature-to-feature project edges, and the root `checkFeatureBoundaries` task fails the build on new ones. Host evidence: all feature host suites + core suites green after the cutover; `checkFeatureBoundaries` passes (5 feature modules, 0 edges). Device regression (import/share/pregen flows) pending on the S22. |
| ~~A7~~ | ~~**Player state agreement across surfaces (CR-7)**~~ | ~~Pause during synthesis/loading cancels the in-flight job and publishes `PAUSED` to UI, MediaSession, and notification; seek commands never resume a paused playhead; a superseded command never publishes after a newer state.~~ **Complete (2026-08-27, decisions #62):** pause during `LOADING`/generation now cancels the in-flight job via the A5 command model and publishes `PAUSED` (a superseded generation loop cannot republish `PLAYING`); seek/skip/undo capture `wasPaused` and reposition without starting audio; the paused-seek and generation-pause device evidence in open-bugs.md is now covered by `PlaybackServiceA57Test` (pause-settles, stale-loop-dead, paused-seek/skip-no-resume). Device MediaSession/notification re-verification pending on the B6 / S22. |
| ~~A8~~ | ~~**Room durability anomaly — FS-side root cause**~~ | ~~The app-side trace is done (decisions #88): no non-Room book path exists, and `CorruptDatabaseGuard` (quarantine + clean rebuild) already converts the observed 68 B garbage skeleton into a recoverable empty library instead of a crash loop. What remains: reproduce the FS-side deletion on the S22 (no `files/databases/` for hours after `adb install -r`, empty skeleton after) and classify the mechanism — Samsung install-time data handling vs somet...~~ **Complete (2026-09-01, decisions #107):** no FS-side deletion ever existed — Room's `getDatabasePath()` puts the DB at `<data-root>/databases/` (the 08-29 `find` was scoped under `files/`), and the "deletion" was our own E2E teardowns calling `context.deleteDatabase` on the live production DB (8/10 tests; #42 class). Samsung install-time handling excluded by experiment (`install -r` → DB+WAL checksums identical). Fix: the six remaining `deleteDatabase` teardowns removed (1df3a57). Phase C gate satisfied. |

Ordering constraints:

1. A1, A2, A4, A5 and A7 are the first repair batch; each has isolated acceptance
   criteria. A7 shares the CR-5 single-writer repair and should land with it.
2. A3 and A6 form one ownership cutover. Preserve exactly one database, `TextIndex`,
   settings mirror and player runtime while rewiring.
3. The instant-seek slice waits for A4/A5. Backup waits for A3. Cross-feature stats and
   backup UI wait for A6 and the Phase B design baseline; their pure core work may
   proceed independently.

## Phase B — visual system and UI redesign

Material 3 remains the component foundation. The missing work is a coherent Ayvu
design system—not a wholesale third-party UI kit—and a deliberate redesign of the
current surfaces before more screens are added.

### B1 — Design direction and tokens

- Define the visual direction against reference screens for library, active player,
  reader, settings and share result.
- Centralize an `AyvuTheme`: brand color roles derived from the ink/amber/teal identity,
  light/dark schemes, typography, shapes, elevation, spacing and motion.
- Replace the direct default `lightColorScheme()` / `darkColorScheme()` setup in
  `MainActivity` with the shared theme; keep dynamic color an explicit future choice,
  not an accidental override of the brand.
- Record component states—enabled, pressed, focused, selected, loading, empty, error
  and disabled—rather than styling only the happy path.

**Complete (2026-08-28, decisions #68):** `AyvuTheme` + tokens land in `core-ui`
(`AyvuLightColors`/`AyvuDarkColors` brand roles with M3 defaults elsewhere,
`AyvuTypography`/`AyvuShapes`/`AyvuSpacing`/`AyvuMotion`); both Compose hosts
(`MainActivity`, `ShareReceiverActivity`) wrap content in `AyvuTheme` with their own
`ThemeMode` resolution — no default scheme remains. Robolectric `AyvuThemeTest`
locks the palette/spacing/motion contract (9/9 green); `:app:assembleDebug` green.
The loading/empty/error state components are delivered with B2; on-device visual
acceptance stays in B4.

### B2 — Shared components and boundaries

Build the small reusable set the real screens need: app bars, book rows, player cards,
buttons, pills, progress, menus, dialogs, loading/empty/error panels and section
headers. Feature-specific composition stays in its feature; shared UI owns no business
logic, navigation, stores or ViewModels.

Settle the implementation home during A6. A small Android `core-ui` module is justified
only if it is needed to share tokens/components without recreating feature-to-feature
dependencies. Do not add a second design convention beside Material 3.

**Complete (2026-08-28, decisions #68):** the small shared set — `SectionHeader`,
`PillButton`, `ConfirmDialog`, `EmptyState`, `LoadingState`, plus the shared
`PlayerCard` (from A6) — lives in `core-ui`; library/settings/share call sites were
relocated with the private duplicates deleted, zero visual change.
`core-ui` is consumed by feature-library/feature-settings/feature-share and
feature-player without feature-to-feature edges (no component owns business logic
or ViewModels). B3 surface redesign and B4 device acceptance remain.

### B3 — Surface redesign

Apply the system in this order:

1. Shared player card and library rows—the highest-density, most visibly custom UI.
2. Reader chrome, pagination cues and paragraph interaction states, keeping the full
   last line visible above the shared player card (device-observed bottom crop;
   open-bugs.md product row).
3. Settings, pack/download state and offline-storage controls.
4. Share result, import progress and all empty/error states.

The backup, folder-import and TODAY-stat screens use these components when built; they
must not introduce another one-off visual language.

**Status (2026-08-31, decisions #94):** complete, host-verified. All four
steps landed: shared card + rows (cover unified in `BookCover`, compact card
with uniform 48.dp transport, two-tone `SegmentedProgress` fed by the new
`generatedAheadSeconds` state), reader chrome/margins/page-indicator/
pressed-passage/EmptyState, settings tokenization + Retry pill + delete
confirm, share-result cards with the error-role encoding. The #87
bottom-crop invariant holds by construction (indicator reserve flows through
the same `linesPerPage(reservedPx)` mechanism as the title). Device visual
acceptance ran as B4 the same day (decisions #95/#98).

### B4 — Visual and accessibility acceptance

- Verify light and dark themes, contrast, 48 dp touch targets, system font scaling,
  TalkBack labels/order and reduced-motion behavior.
- Verify the actual library, player, reader, settings and share surfaces on the S22.
- Verify a low-motion/e-ink presentation on the HiBreak; expensive animation must
  degrade without hiding state or controls.
- Capture approved reference screenshots so later UI work has a regression target.

Completion means the product surfaces use the shared tokens/components and have been
visually exercised on both devices—not merely that a theme file exists.

**Status (2026-08-31, decisions #95 + #98): complete.** The S22 pass (#95)
covered light (teal-led) and dark (ink-ramp) themes, 48 dp transport
measured pixel-exact (135 px @450 dpi), reader page indicator +
pressed-passage + last-line-visible at 1.0× and 2.0× font scale (2.0×
exposed the One UI font-scale/pitch mismatch — pagination now keys on the
layout's measured pitch), share Found/NotFound verdicts driven live via
the exported ShareReceiverActivity, settings delete confirm + live espeak
status, and TalkBack labels (uiautomator audit). The HiBreak pass (#98)
covered the rest: low-motion/e-ink presentation (light + dark surfaces,
full cold-open → LOADING → playing cycle), reduced-motion degradation
(`ANIMATOR_DURATION_SCALE = 0` → static loading ring + snap entrance,
state copy and controls stay visible; Compose ignores the scale so the
gate is explicit — core-ui `LocalReducedMotion`), reference screenshots
(`docs/prints/reference/`), and the #95 design decision: the amber
generated segment now measures against the fixed 120 s pregen horizon
(owner pick) and renders visibly on long books.

## Phase C — fresh install and voice selection

A clean installation currently has no books or engine assets: Kokoro model, voices and
espeak-ng are explicit downloads. The app must guide a new user to successful first
audio instead of requiring them to discover several settings screens and infer which
packs are mandatory. This phase reuses the Phase B design system and waits for A5 before
allowing a voice change to restart active playback.

### C1 — Guided first-run setup

- Explain the offline/no-account/no-telemetry model and why speech assets are downloaded
  separately.
- Let the user choose language and voice before downloading, then present one required
  setup plan for Kokoro model + voices + espeak-ng. OCR languages remain optional until
  the share-image path is used.
- Show exact download bytes, expected installed footprint and available storage before
  starting. Audio-cache growth is explained separately from engine assets.
- Run required downloads as one coordinated flow with per-pack progress, cancellation,
  resume, retry and checksum/network/storage failures that name the failed asset.
- End at import-a-book, then first playback. A bundled public-domain sample remains an
  explicit review decision; onboarding must work without it.

Acceptance: from clean app data on the S22 and HiBreak, a user can understand the data
cost, choose a voice, download only required speech assets, import a supported book and
hear first audio. Repeat with network loss, cancellation, insufficient storage and
process restart during setup; completed work is retained and the next action is clear.

**Decisions (2026-08-31, #102):** System TTS ships as an explicit opt-in
zero-download degraded fallback in the setup plan screen (a `SystemTtsEngine`
adapter with passage-level read-along, never auto-selected); no bundled
public-domain sample (onboarding works without it); A8's S22 device
reproduction runs first per the Phase C gate. The setup state derives from
durable facts (#102) and the voice selector is shared groundwork for C2.

### C2 — Voice selector in the primary flow

**Complete (2026-09-01, decisions #105):** ONE shared selector surface
(`core-ui` `VoiceSelector` + `buildVoiceSelectorState`) reused across
first-run setup, Settings and a new reader voice sheet; persistent
"Selected voice:" summary with exactly one selection indicator; the star is
independent; per-row Preview/Stop with a single audition (cancellable
"Generating sample…", narration capture/pause/resume only when playing, A5);
missing packs → explicit download action; saved-voice-absent renders
unavailable; voice change preserves the playhead and restarts once at the
same position (supersedes stale synthesis). Host tests: 14 new cases.
Device-verified on the HiBreak (Settings + reader sheets, Preview no ANR,
restart-persistent change). Full record in decisions #105.


The full voice picker + favorites already exists in Settings; the missing part is
discoverable selection where listening starts. Reuse one selector/state model in:

- first-run setup — now a compact catalog dropdown (`ExposedDropdownMenuBox`)
  consuming the SAME shared state builder, with the selected voice's
  Preview/Stop/Download action beneath; favorites stay a Settings surface
  (2026-09-02, decisions #112);
- the active player/reader surface, with exact placement settled during B3;
- Settings for full voice and pack management.

Initial scope remains one global active voice. Per-book overrides are a later product
decision. The selector shows language/family, favorite state and ready/missing-pack
status; an unavailable selection opens the required download flow rather than silently
falling back.

Selection and favorites must be visually distinct. The screen shows a persistent
**Selected voice: _name_** summary and a radio/check indicator on exactly one voice row.
The star remains a separate favorite action and never implies selection; tapping the
row selects, while tapping the star only toggles favorite state. If the saved voice is
absent from the current catalog, show it as unavailable with a download/reselect action
instead of leaving every row apparently unselected.

Every available row also exposes **Preview/Stop**. Preview synthesizes a short fixed,
language-appropriate phrase with that row's voice without selecting or favoriting it.
Only one audition may generate/play at a time; starting another cancels the first, and
slow synthesis shows cancellable **Generating sample…** feedback. Preview audio is
ephemeral—excluded from book progress, history and the passage disk cache.

If narration is active, auditioning captures and pauses the book playhead, plays the
sample, then resumes only if narration was playing beforehand. That transition is
serialized through A5 so sampling cannot publish stale book state. Missing engine
assets replace Preview with the same explicit download action used elsewhere.

Changing voice preserves the book playhead, supersedes stale synthesis through the A5
command model, and naturally selects a different voice-keyed queue/disk-cache entry.
The selected voice persists and every surface observes the same `AppSettings` value.

Acceptance: the current voice is identifiable without relying on text color; exactly one
available row has the selection indicator; stars independently represent favorites;
every ready voice can be previewed without changing selection; rapid previews leave
only the final sample audible; preview generation can be cancelled; active narration
returns to the same playhead/state; choose a voice before first playback; change it from
the listening flow; pause/restart occurs once at the same position; the following
passage uses the new voice; relaunch retains it; missing assets produce a download
action, never silence or an unannounced fallback.

### C3 — Setup recovery and re-entry

First-run state is derived from durable facts—required packs ready, a voice selected and
at least one book available—not a one-shot "onboarding complete" flag. A user who skips,
loses a pack, clears cached assets or re-enters setup later sees the actual missing step.
Every setup action remains reachable from normal settings after onboarding.

**Complete (2026-09-01, decisions #106):** the gate IS the contract —
`SetupGate` re-derives from durable facts (pack markers, espeak staging,
book count, persisted voice/engine) on every cold start and after
dismissal; no onboarding flag. "Skips" is the recorded system-TTS opt-in
(#102); no skip affordance added. New `SetupGateTest` C3 cases: a lost pack
or wiped espeak staging reactivates a completed setup; dismissal is
non-sticky. Every setup action was already reachable from Settings (C1.5
pack plan, C2 voice selector). Host-verified; no device leg required by the
spec.

## Phase D — playback latency and weak-device performance

### D1 — Instant ±30-second seek horizon — designed

Replace fixed two-passage look-ahead with an approximately 30-second audio horizon and
let in-flight `PregenQueue.ensure` survive a seek before refilling from the new
playhead. Persisting the look-ahead hot zone is optional unless measurement shows RAM
churn still causes misses.

Acceptance on both reference devices:

- Ten representative ±30-second seeks after normal listening resolve from
  `buffer|pregen|disk`, with zero synchronous synthesis at seek time.
- Cold first play after a process start (engine open) resolves without
  main-thread Choreographer skips attributable to playback, inside the D2
  first-audio baseline.
- Queue memory remains bounded and overnight/manual pre-generation behavior is
  unchanged.
- Record latency separately on the S22 and Bigme HiBreak. Current baselines are
  5–25 seconds and about 58 seconds respectively.

**Measurement status (2026-08-29):** cross-boundary ±30 s seek to an *uncached*
passage measured S22 **79.6 s** / HiBreak **107.0 s** (build af431c4+), decomposing
identically on both: ~1 s command + **60 s dead-owner ensure wait** (the fill was
cancelled with `stopEverything` on loop-restart commands and never restarted, so
`bufferForPlayback` polled `ahead=0.0s` for the full budget, then sync-synthesized)
+ RTF-scaled cold synthesis (S22 ~19 s @ 0.69; HiBreak ~46 s @ 2.9). The 60 s
dead-owner wait was fixed 2026-08-29 (`fix(player)` 7d27226, decisions #78
addendum — `startPrefill` restarts on every loop-restart command): re-measured
with the fill building cushion again (`ahead=5.79s after 60023ms` on the HiBreak,
seek target `source=pregen`). Full record in `bugs.md` (2026-08-27/29 entries).
Remaining cost is the cold target's synchronous synthesis — exactly what the
survive-seek `ensure` + 30 s horizon below targets; D1's design is unchanged.

### D2 — Accelerator, quantization and power research — promoted from ideas

A measurement slice, not an assumed implementation. Reuse the existing TTS spike
harness to compare the current CPU path with viable ONNX Runtime NNAPI/GPU delegates,
quantization-safe Kokoro variants, session reuse and buffer-pooling changes.

Required evidence:

- Time-to-first-audio at cold engine open, steady-state RTF, peak PSS/RSS, retained
  PSS/RSS 60 s after pause, main-thread Choreographer skips during launch/play/seek,
  thermal behavior and power draw.
- S22 and HiBreak runs against the same corpus and voice.
- Audio quality/oracle regression check for every graph change.
- Production adoption only when the result materially improves a measured bottleneck
  without increasing failure rate or breaking read-along timings.

The HiBreak baselines—about 25 seconds to first audio and roughly 834 MB PSS—make this a
real performance gate. Flow-DiT acceleration for CosyVoice remains a separate research
question; do not generalize a Kokoro result to it.

**Measurement results (2026-08-28/29):** the CPU-EP + pinned-fp32 decision stands.
Execution providers (#67) kept the CPU default after measured comparison. Kokoro
precision (#86): fp16/int8/q8 all rejected against the fp32 oracle — fp16 produced
a silent en-us stub and `max_abs_diff` 0.723, q8 failed the 0.001 gate at 0.700 and
was *slower* (RTF 1.73–1.79 vs 1.16–1.20), int8 is non-runnable on the CPU EP
(`ConvInteger` not implemented). RTF baselines now measured on both devices on
comparable corpora: S22 1.16–1.20 (#86 harness) and 0.66–0.76 (hard-facts
listening corpus), HiBreak 2.84–3.12 (`bugs.md` B6 — live synthesis cannot sustain
playback; pre-generation is the HiBreak's requirement). Peak/resident PSS and
Choreographer-skip counts are recorded (#67/#86/bugs.md). Still open from the
required-evidence list: retained PSS/RSS 60 s after pause, thermal behavior and
power draw.

**Candela-derived additions (2026-08-31, owner review):**

- **Baseline profiles** — a `:baselineprofile`-style producer walk (UI Automator
  over launch → open book → first audio) emitting `baseline-prof.txt` for the
  AndroidX Baseline Profile plugin. Targets the app-side share of cold
  time-to-first-audio (session open, JNA/phonemizer warmup, launch) — not RTF,
  which stays CPU-bound. Candela reference: cold launch 6.7 s → 0.8 s on a
  Tab A7 Lite; our gain is expected smaller but is measured, not assumed.
- **2-engine parallel pre-generation leg (S22 only)** — one spike-tts
  measurement: two Kokoro ORT sessions with separate thread pools synthesizing
  independent passage chunks vs the serial baseline, on pregen wall-time and
  peak PSS. Adoption bar: ≥1.5× pregen throughput without breaching the S22
  memory envelope or the 0.001 oracle gate. HiBreak excluded by arithmetic
  (~834 MB × 2 sessions vs the ~2.5 GB lmkd wall measured with MOSS, #93).


### D3 — Engine comparison spike: Nano, MOSS-TTS-Nano, CosyVoice3 vs Kokoro baseline

A measurement spike, not an assumed adoption. Four ONNX engines are compared on
one harness, one corpus set and one measurement gate, with the shipped engine as
the baseline everything must beat or lose to honestly. Candidate sweep and
rejections live in [landscape.md](landscape.md) §D3 comparison sweep (2026-08-29);
MOSS-TTS-Nano's promotion is decisions #92.

| Engine | Role in the comparison | Measured status |
|---|---|---|
| **Kokoro-82M fp32** (pinned packs, `model-files-v1.1`) | Baseline — the shipped v1 primary; every contender must beat it on a *measured* bottleneck or offer a distinct capability | S22 RTF 1.16–1.20 (#86 harness) / 0.66–0.76 (hard-facts corpus); HiBreak RTF 2.84–3.12 (`bugs.md` B6 — live synthesis cannot sustain playback, pre-generation mandatory); ~834 MB PSS on the HiBreak |
| **KittenTTS Nano v0.8** (KittenML, Apache-2.0) | Low-footprint / weak-device candidate: ~15M params, ~56 MB fp32 ONNX (the ~25 MB variant is int8, reported broken upstream — use fp32), 8 voices, 24 kHz, CPU-only; pinned pack `KittenML/kitten-tts-nano-0.8-fp32` (community export `onnx-community/KittenTTS-Nano-v0.8-ONNX` exists but only as fallback) | Never run on-device; English-only v0.x |
| **CosyVoice3 0.5B ONNX** | Quality / voice-cloning fallback candidate: multilingual (en/zh/ja voices in the pinned manifest) and prompt-based voice cloning — a capability class Kokoro and Nano do not have | Pinned reproducibility record in [cosyvoice3-pack.md](cosyvoice3-pack.md) (3.47 GiB, 26 files); T3 device spike (#49) measured RTF far from realtime — disk-only playback; gated on the Flow-DiT acceleration finding (decisions #21/#23) |
| **MOSS-TTS-Nano** (OpenMOSS, Apache-2.0) | Coverage/cloning/streaming candidate at a 0.1B footprint: 20 languages (incl. es/it/pt — the app's advertised set), streaming output, prompt-based voice cloning (a CosyVoice3-class capability at a quarter of its size); 48 kHz stereo output resampled into the 24 kHz pipeline | Standalone ONNX CPU packs pinned at the HF revisions: `MOSS-TTS-Nano-100M-ONNX` @ `f52645cb467506d8e18e746ddd59482685b74e58` (671.9 MB) + `MOSS-Audio-Tokenizer-Nano-ONNX` @ `ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae` (90.6 MB) ≈ **0.75 GiB total** — a coverage/cloning play, not a footprint play; official Android ONNX Runtime Kotlin example in-repo; not in the Picovoice benchmark, never run on-device — pure-AR decode RTF on HiBreak-class CPU is the open question (#92) |

**Measurement status (S22, 2026-08-30):** the S22 column of the table is
measured — decisions #93. Kokoro baseline avg RTF **0.77** (0.67–0.99, all
finite); KittenTTS Nano runs at RTF 0.31 but **every output is NaN on
ORT-android** (1.23.2 + 1.29.0, all session-option profiles swept; x86 is
finite) — measured drop for on-device use; MOSS-TTS-Nano RTF **~3.5** avg
(decode-dominated AR, 375-frame cap truncates long passages) with the blind
quality gate ranking it **first** — pregen-gated candidate; CosyVoice3 RTF
**12.5–31.1** (matches #49 on blobs, degrades on short probes) with
wrong-language/duplicated audio on the honorific probes — stays DiT-gated.

**Measurement status (HiBreak, 2026-08-30):** the HiBreak column is measured
(MT6765 8×A53, 3.97 GB RAM — decisions #93). Kokoro RTF **2.83–3.65, avg
3.01**, all finite (~3.9× the S22; confirms B6 — pre-generation mandatory);
KittenTTS Nano RTF 1.37–1.49 but **NaN again with identical output sizes** —
the ARM NaN bug is ORT-android-wide, drop reinforced; MOSS-TTS-Nano
**unavailable — lmkd kills the decode at ~2.5 GB RSS** (the 3.97 GB device
cannot hold the AR plateau; the wall is memory, not speed); CosyVoice3
skipped (3.22 GB VmHWM exceeds device RAM). D3's device matrix is complete:
both devices measured on every leg that physically fits.

**Owner decision (2026-08-31, decisions #96):** MOSS-TTS-Nano is **dropped**.
It can never sustain live synthesis (RTF ~3.5, decode-dominated AR), the HiBreak
cannot hold its decode plateau at all (lmkd kill at ~2.5 GB RSS on a 3.97 GB
device), and 0.75 GiB of pack weight would buy pregen-only quality while the
shipped Kokoro baseline (0.77 / 3.01) stands with an adequate blind-gate
ranking. The pinned provenance record stays in decisions #92/#93 for any future
re-evaluation; D3's comparison table above remains the measurement record.

The comparison runs in the `spike-tts` harness on the S22 and HiBreak: the CosyVoice3
leg reuses the existing T3 staging path (`cosyvoice3-pack.md` §Verify); the Nano leg is
added behind the same harness with its own tokenizer. Both run through the existing
`TTSEngine` seam shape — no second in-app inference convention is created to test.

Required evidence, identical for all three legs:

- Cold engine-open time-to-first-audio, steady-state RTF, peak/resident PSS on
  both devices against the same corpora (the #67/#86 corpora + the hard-facts
  listening corpus), plus the still-open D2 measurements: retained PSS 60 s after
  pause, thermal behavior, power draw.
- Quality/oracle comparison on the narration-quality benchmark corpus (names,
  honorifics, numbers, dialogue) across all three engines — a 15M-parameter model
  and a disk-only 0.5B model each clear the quality gate or the comparison records
  why not; size and capability never substitute for the oracle.
- Capability deltas recorded per engine, not folded into the RTF table: Nano's
  footprint (25 MB vs ~326 MB staged Kokoro vs 3.47 GiB CosyVoice3), CosyVoice3's
  voice cloning and language coverage, Nano's English-only v0.x vs the advertised
  language set.
- Integration-cost audit before any adoption decision: tokenizer/phonemizer path
  per engine vs the shared espeak-ng/JNA path; sample rates against the
  engine-agnostic `lastSampleRateHz` contract (S5, decisions #77); pack staging
  via the existing `TTSEngine`/`TtsPack` download flow; CosyVoice3's disk-only
  playback constraint vs pre-generation budgets.

Acceptance: a single comparison table in decisions.md — all three engines, both
devices, the same metrics — plus a typed per-engine keep/drop/defer decision.
No adoption without the quality gate and a materially better measured result for
a named bottleneck; Nano may only ever be a secondary engine behind the existing
seam (never a Kokoro replacement), and CosyVoice3 stays gated on the DiT
acceleration finding unless its measured RTF in this spike overturns it.

### D4 — Small tier for the HiBreak (B6): Piper vs Supertonic 3

**2026-08-31 (landscape.md closer look):** a third leg, **Audio8 0.1B INT8**
(`Audio8/audio8-TTS-0.1B-ONNX-INT8`, Apache-2.0, 11 langs, 431 MB online set),
was probed on the B6 — every graph opens and runs finite on ORT-android
(no NaN), but the fabricated slow-AR step measured **5.8 s** (vendor: 19 ms
on an 8-thread desktop) and the fp16 codec alone ran at **RTF ≈ 10**; the
realtime thesis is unsupported on B6-class and a full generation-loop leg is
required before any adoption call (pregen-only at best on this hardware).**

The three-tier engine strategy (decisions #97) places a small realtime engine on
weak devices. **The device is the Bigme HiBreak — "B6" is the same unit under
its other name in bugs.md/decisions records.** The baseline therefore already
exists and is measured (bugs.md B6 re-measure 2026-08-29; #93): Kokoro RTF
2.84–3.12 (avg 3.01), ~834 MB PSS on a 3.9 GB device — live synthesis cannot
sustain playback, pre-generation is mandatory. **The small tier is confirmed
necessary**; no further baseline measurement is needed.

Candidate legs, judged against a ≤1.0 RTF target on the HiBreak and the pack
gates (pin, oracle, blind quality gate):

- **Piper** — direct-ORT VITS port, NOT sherpa: keeps the shared espeak-ng/JNA
  phonemizer and enables an anchor-introspection audit on the VITS alignment
  outputs (#30b — upstream has no word timestamps; if introspection is
  impossible, the tier ships passage-level read-along only, recorded as a
  known degradation). Per-language packs (14–100 MB/voice, ~20+ languages
  incl. de/ko — Kokoro's gaps) through the `TtsPack` flow; tens-of-MB sessions
  fit the HiBreak's 834 MB PSS envelope where the ~326 MB Kokoro session is
  already 26% of RAM. Flat prosody → blind quality gate required.
- **Supertonic 3** — one multilingual model (~99M, 31 langs, vendor RTF ~0.3
  on an e-reader) instead of per-language packs; gated on the recorded
  supply-lifecycle terms (archived upstream — pin HF revision + hashes) and
  the unverified read-along duration-output introspection gate.

Acceptance: typed per-engine keep/drop/defer with HiBreak RTF/PSS measurements
against the Kokoro 3.01 baseline, and the quality/oracle gate.

**Status (2026-08-31, decisions #99): measurement legs complete — typed
verdicts recorded.** Real end-to-end pipelines on the HiBreak (spike-tts
`D4ProbeRunner`, host-prepared inputs, Kokoro grain-spike corpus blob):

| Leg | HiBreak RTF | PSS / VmHWM | Verdict |
|---|---|---|---|
| Piper en_US-lessac-medium (63 MB) | **0.50** (9.0–9.4 s / 18.0–18.5 s audio; open 7.4 s) | ~195 MB / ~507 MB | **KEEP candidate** — passes ≤1.0 where Kokoro is 2.84–3.12 |
| Supertonic 3 @ 3cadd1ee (380 MB) | **3.92** (111 s / 28.4 s) | ~536 MB / ~690 MB | **DEFER** — Kokoro-class speed; duration introspection PASSES (only candidate with a per-token time axis); host preview 0.16 |
| Audio8 0.1B INT8 | loop not runnable | 5.83 s per slow-AR TOKEN position | **DROP** — arithmetic: hundreds of positions/sentence → RTF in the hundreds |

Piper's #30b audit: the stock export exposes a single audio output — no
alignment, no word timestamps — so the small tier ships **passage-level
read-along only** (recorded degradation) unless a custom re-export surfaces
the alignments. Piper's flat prosody → **blind quality gate RESOLVED
(2026-09-02, decisions #110):** the first listen reported both Piper WAVs
unintelligible, but the cause was a probe-harness bug — the hand-rolled
`espeak-ng --ipa` char-map omitted Piper's inter-phoneme `_` tokens (715 vs 1465
ids). Corrected re-measure: RTF **0.57** (PSS ~236 MB / VmHWM ~873 MB), and the
owner's listening pass on the corrected reference WAV is fine (minor caveat:
inter-sentence pauses a little short). Piper stays the **KEEP** candidate.
Remaining for adoption: the TtsPack integration engine
(`PiperEngine : TTSEngine` behind the existing seam), per-language voice pins
+ hashes, and the es-IT/de/ko coverage check.

**2026-08-31 (landscape.md closer look):** the q4 weak-device variant
(`BricksDisplay/chatterbox-multilingual-ONNX-q4`, 790 MB) was probed on the
B6 — speech_encoder/embed/LLM open fast and the LLM prefill runs finite
(2.2 s, MatMulNBits + GroupQueryAttention work on ORT-android; no NaN), but
the conditional_decoder **opens in 326 s** on the HiBreak. Host A/B showed
the q4 vocoder decodes real AR-generated audio correctly (identical behavior
to the unquantized export under ORT 1.22.1 and 1.23.2 — not a quant
regression). The weak-device variant thesis is hit hard: 790 MB-vs-3.2 GB
saves disk but costs a 5.4-minute engine open on B6-class; A/B the original's
vocoder open before pinning, and the AR-KV memory gate for the LLM decode
still applies.

### D5 — High-end cloning comparison: Chatterbox-multilingual-ONNX vs CosyVoice3 — gated on G0

Owner decision (2026-08-31, decisions #97): the high-end pregen slot (voice
cloning, multilingual, not necessarily realtime) stays with CosyVoice3 until
this comparison runs, and the comparison waits on the G0 narration corpus so
the quality gate is strong from day one.

- Candidates: **CosyVoice3** (incumbent — 9 langs incl. es/it, zero-shot +
  cross-lingual cloning, pinned pack, measured 3.22 GB VmHWM on the S22) vs
  **Chatterbox Multilingual ONNX** (MIT, 23 langs incl. es/it/pt/de/ko,
  zero-shot cloning, 0.5B AR Llama backbone).
- Provenance gate first: the only ONNX export is community
  (`onnx-community/chatterbox-multilingual-ONNX`); `textagent/…` is a mirror of
  the same export (same card, same conversion script, internal code points at
  onnx-community) and is NOT a pin candidate. Pin revision + sha256 and verify
  output parity against the PyTorch reference before any measurement (the #86
  fp16-stub lesson). The official `ResembleAI/chatterbox-turbo-ONNX` export is
  English-only (350M Turbo) — fails the multilingual requirement.
- Measurement, in pregen-budget terms on the S22: per-passage wall time,
  peak/resident PSS through an AR KV-cache decode (the MOSS lesson — memory,
  not speed, kills weak-RAM devices), and the G0 blind gate against CosyVoice3's
  own #93 quality flag (duplicated honorific probes on the cloned voice).
- Integration-cost audit: HF BPE tokenizer is a new tokenization path vs
  espeak-ng (the advertised set en/es/it/pt/de needs no external normalizer;
  zh/ja/he do); 24 kHz output against `lastSampleRateHz`; watermark off by
  default.

### D6 — Cross-runtime inference spike: ORT vs GGUF/llama.cpp (TFLite gated)

A measurement spike, not an assumed adoption. The evidence gap it closes: ORT was
chosen by path-of-least-resistance — Kokoro's working Kotlin reference
(`thewh1teagle/kokoro-onnx`) plus the on-device speech ecosystem — then ratified by
convention (decisions #97: "one inference convention (ORT); no candidate may
introduce a second runtime"). It has never been benchmarked against an alternative
runtime. The only runtime-level measurements on record do not answer the question:
D2/#67 compared ORT *execution providers* (CPU vs XNNPACK vs NNAPI — CPU won), a
within-ORT result; #92 rejected GGUF candidates on the convention rule, not a
number; and #93's sherpa-onnx spot check is confounded (sherpa bundles its own
`libonnxruntime` with a Kokoro **v0.19** graph — the ~2.6× delta vs our v1.0 port
is graph/frontend/runtime together, not a runtime isolate).

This spike answers the question on its own terms: the same (or matched) graph run
through ORT and through an independent native runtime, judged on RTF, first-audio,
peak/resident memory and PCM-oracle fidelity on both reference devices. It runs in
the `spike-tts` harness only — no second in-app inference convention is created,
and decisions #97 stands unchanged until a measured decision overturns it.

Legs (one harness, one corpus set, one oracle gate):

| Leg | Graph | Role |
|---|---|---|
| ORT-android 1.29.0 | shipped Kokoro-82M fp32 (`model-files-v1.1`) | Known-good baseline |
| ORT-android 1.29.0 | CosyVoice3-0.5B int4 (jiangzhuo9357, #49) | ORT reference on a quantized DiT backbone |
| llama.cpp / GGUF | `cstr/cosyvoice3-0.5b-2512-GGUF` | The only tracked dual-export model (ONNX + GGUF) — isolates runtime on one backbone |
| TFLite / ExecuTorch | gated | No tracked TTS model ships a TFLite/ExecuTorch export; recorded as untestable, never assumed slower |

Required evidence, identical per leg: cold engine-open time-to-first-audio,
steady-state RTF, peak/resident PSS and VmHWM, and the #67 PCM oracle
(`max_abs_diff`) against the fp32 Kokoro baseline — S22 and HiBreak, same
corpus/voice as D2/D3.

Method caveat: GGUF vs ORT-int4 confounds runtime with quantization (llama.cpp's
integer kernels vs ORT's), so most legs prove "best available runtime for a
quantized graph", not "bare ORT vs bare GGUF at fp32" — each number states which
axis it actually isolates.

Acceptance: a single comparison table in decisions.md (all legs, both devices,
same metrics) plus a typed keep/drop per non-ORT runtime, and an explicit
statement of whether #97's one-convention rule is now evidence-backed or still
rests on ecosystem/licensing grounds alone.

## Phase E — data safety

**Status (2026-09-02, decisions #111):** complete. Phase 1 shipped the
pure-JVM `core-backup` archive codec + DTOs (#89); phases 2
(snapshot/merge in `core-persistence`) and 3 (SAF edge + settings section +
index resync) landed together with the opt-in book-byte capture and are
device-verified — full record in decisions #111, spec in
[post-v1-plan.md](post-v1-plan.md#slice-b-app-exportbackup--restore).


### E0 — Storage-location decision (gate)

One decision record before E1 phases 2/3 start:

- the archive lives in app storage vs a user SAF grant (including grant
  re-acquisition after reinstall);
- original book files are copied or referenced;
- whether generated audio ever belongs in the archive;
- measured SAF write throughput at PCM-file scale (thousands of cache files).

This promotes the "Data survival and user-owned storage" review (Further
reviews, below) from recorded-not-scheduled to the gate E1 implementation
waits on.

**Resolved (2026-09-02, decisions #109):** one-shot SAF export/import (no
persistent grant, no re-acquisition after reinstall); book bytes are an opt-in
copy, OFF by default; generated audio is excluded; SAF write throughput gets
measured for the export zip during E1 phase 3 (the PCM cache never enters it).

### E1 — App backup and restore

Build the versioned SAF zip described in [post-v1-plan.md](post-v1-plan.md): settings,
library metadata, cached passages, progress, bookmarks and undo history; original book
files remain opt-in and model/audio packs remain excluded.

Before implementation, sign off the documented merge precedence:

- local progress wins over restored progress;
- restored settings overwrite matching local keys;
- bookmarks and history merge idempotently;
- including original book files defaults off.

Acceptance: a populated export restores onto a fresh install, a second restore creates
no duplicates, cached parses rebuild the index without source re-parsing, and unknown
archive versions fail before any partial merge.

**Complete (2026-09-02, decisions #111):** `core-persistence` gained the
`BackupStore` snapshot/merge (one transactional consistent read; FK-ordered
merge with the signed-off precedence, history natural-key idempotent before
the ring-cap prune), `core-ebook` captures book bytes at import (one read
reused for cover + sidecar), `feature-settings` gained the SAF edge +
"Backup & restore" section, and after a merge the settings mirror reloads
and the index resyncs under `IndexLock` (searchable without relaunch).
Host: `BackupStoreTest` (8) + all suites + assemble + ktlint baseline green.
Device (S22, 2026-09-02): export zip with all six sections + book files
(md5-equal); `pm clear` → restore "6 books, 2 bookmarks, 3 resume points";
share flow matched a restored passage at 100% in-process; second restore
"0 books, 0 bookmarks, 0 resume points" — zero duplicate rows.

## Phase F — library completion

One cohesive library/import slice rather than separate UI patches.

| ID | Work | Required result |
|---|---|---|
| ~~F1~~ | ~~**Import progress and cancellation**~~ | ~~Large and multi-file imports show current/total progress; cancellation settles cleanly; per-file failures remain isolated and typed.~~ **Complete (2026-08-27, decisions #64):** `BookImporter.importAll` is now suspend with a per-file pre-parse progress event (a single large file shows "Importing 0/1 — …" immediately) and a 1 ms cooperative boundary so a cancelled batch stops between files without mutating the index for untouched files; `LibraryViewModel` publishes `Importing(0,total)` on `import()`, tracks the batch in `importJob`, exposes `cancelImport()` (Idle, never a partial `Done` — guarded by `ensureActive` before the final publish), and the library row/progress bar gains a Cancel control. Per-file failures stay typed via `ImportOutcome.Failed`. Host evidence: `BookImporterTest` (13, incl. pre/post progress sequence + mid-batch cancel skips later files) and `LibraryViewModelTest` (8, incl. start-state `Importing(0,2)`, mid-batch cancel settles Idle, later import unaffected). |
| ~~F2~~ | ~~**Library search**~~ | ~~Filter by title and author locally with deterministic empty/no-result states; content identification remains the separate `TextIndex` capability.~~ **Complete (2026-08-29, decisions #90):** UI-level filter over the Room `books` rows — case-insensitive title/any-author match, blank query shows all, `EmptyState` on zero matches; the continue-list stays unfiltered by design. Host: `LibraryViewModelTest` +4 (13/13 green). Runtime device verification done (2026-09-01, decisions #105): a non-matching query renders the "No books match" empty-state, the continue-list stays unfiltered, clearing restores. |
| ~~F3~~ | ~~**Folder import via SAF tree — promoted from ideas**~~ | ~~A persisted tree grant feeds supported files through the existing batch importer; recursion policy and a defensive per-batch cap are decided before build.~~ **Complete (2026-09-02, decisions #108):** a persisted `ACTION_OPEN_DOCUMENT_TREE` grant scans root + one nested level (`FolderScanPolicy.MAX_DEPTH = 1`) capped at 200 files (`MAX_FILES`) — the hostile-input audit's first concrete resource controls — feeding the shared `BookImporter.importAll` batch with the F1 per-file progress. Pure `FolderScanPolicy` (host-testable) + thin `FolderScanner` DocumentFile adapter; extension gate reuses `EBookFormats.parserFor` (no second list). Empty folder → typed "no supported book files found"; truncation surfaced in snackbar/dialog. Host: `FolderScanPolicyTest` 6/6, `:feature-library:testDebugUnitTest` 19/19, `:app:assembleDebug` + `ktlintCheck` green. Device-verified on the S22 (root + one-level scan, `.pdf` filtered, two-level `.txt` pruned; `docs/prints/f3/folder-import-s22.png`). |
| F4 | **External file intake — open from file manager + share a book** | Tap a supported ebook in a file manager (`ACTION_VIEW`) or share a book file to Ayvu (`ACTION_SEND` with `EXTRA_STREAM`) and land it in the library through the existing `BookImporter.importAll` batch — **no second import path**. Both entry points accept `.epub`, `.azw3`/`.kf8`, `.mobi`/`.azw`, `.txt`, `.md`; the shared `EBookFormats.parserFor` extension gate is the backstop (file managers type these MIME-inconsistently — no second extension list, F3), and `.kfx`/DRM/unsupported types get typed guidance, never a silent no-op. Duplicate content-hash re-import is a no-op via the existing `LibraryStore.contains` gate; per-file failure stays isolated and typed (F1); a persistable URI permission is taken where the provider offers it. A book-file share is distinguished from the existing text/image identify-resolve path (S2/S3) — text and image shares keep their current behavior. |

F1 is the shared prerequisite for F3: scanning a folder without visible progress would
amplify the existing import UX defect.

F4 rides the F1/F3 machinery (shared `BookImporter.importAll`, `EBookFormats.parserFor`
gate, per-file isolation, content-hash dedupe); the new work is the intent contracts —
manifest `ACTION_VIEW`/`ACTION_SEND` filters plus URI → import routing — not the
parse/import path itself.

## Phase G — narration and reader controls

### G0 — Narration-quality listening corpus — bounds G1

Build the listening corpus (names, honorifics, abbreviations, numbers, dates,
currencies, measurements, Roman numerals, dialogue, headings, footnotes, page
furniture, URLs/code-like text, long paragraphs, speed transitions, every
advertised language) and run it through the shipped Kokoro pipeline with the
existing `spike-tts` runner — the D3 corpus/harness infrastructure makes this
mostly curation, not tooling. Findings become a typed list of mispronunciation
classes; G1's rule scope is bounded by measured failures, not the single
`Ms.` regression.

Acceptance: the corpus synthesizes end-to-end on the S22; findings recorded as
typed classes with examples; G1's built-in rule set is derived from them.
(2026-08-31, decisions #96)

### G1 — TTS pronunciation replacements — promoted from ideas

Add a deterministic, testable normalization/replacement stage before phonemization for
names, honorifics, abbreviations, pauses and intentionally skipped page furniture. The
reported `Ms.` → “M S” defect is the first regression case, not a one-off special case.

Start with ordered literal rules plus a small built-in correction set. Regex and user
editing require explicit limits and preview because an unbounded rule can silently
rewrite an entire book. Matching/index text remains unchanged; replacements affect TTS
output only.

### G2 — Paragraph context menu — promoted from ideas

Long-press a rendered paragraph to expose **Play from here** and **Copy text**. Reuse the
existing passage hit-testing and playback-position command; preserve middle-tap play
and page-turn gesture discrimination.

Acceptance: the selected passage—not merely the current narrated passage—is copied or
played, including when several passages share one page.

The discrimination is now three-way against B3's middle-third pressed-passage
highlight (tap vs long-press vs page swipe); define it against the B3
interaction, not beside it. (decisions #96)

### G3 — Hardware and listening gestures — promoted from ideas

Add the narrow useful subset before building a configurable gesture editor:

- Media/headset play-pause and seek commands continue through `MediaSession`.
- Optional volume-key passage navigation is limited to the visible reader and is off by
  default; normal system volume behavior must remain the default.
- Screen-off behavior must use supported media-session commands rather than promising
  interception Android does not deliver to an inactive activity.

Configurable tap-zone maps remain in the idea pool until the fixed reader interactions
have device evidence and an accessibility review.

### G4 — Speed-selector revisit (decisions #71)

A bounded decision item, now **closed (2026-09-02, decisions #109):** playback
stays pinned 1.0× and stored per-book speeds remain ignored; the retained
`setPlaybackRate`/speed-command plumbing is kept but #71's "revisit planned" is
closed permanently. Pitch-preserving speed stays the only Later speed work.

## Phase H — TODAY reading and listening stats

Use the capture, aggregation and UI design in
[post-v1-plan.md](post-v1-plan.md#slice-a-today-stats-dashboard). Store whole seconds
and round only for display so short valid sessions are not discarded.

Reading/listening capture is decided (2026-09-02, decisions #109): listening =
wall-clock while `PLAYING`; reading = **page-flip-active** reader dwell (screen-on
foreground, accrued only while the user is actively turning pages) with
sub-10-second spans dropped — not raw foreground dwell.

A full event/session timeline is not a prerequisite for the dashboard. Add it later only
if a user-visible history view needs event-level data.

## Phase I — book start and chapter segmentation

Import segmentation so a listener starts at the story, not the cover, and a monolithic
book is navigable by chapters. Both items shipped as pure core-ebook work
(`BookSegmentation` + the parsers), host-tested like the existing eBook JVM suites —
decisions #69/#70, one commit (2026-08-28).

### I1 — Book start detection (skip cover, TOC, index)

**Complete (2026-08-28, decisions #69):** `stripPassageMatter` extends front/back-matter
stripping to the *passage* level inside the kept chapters — a contiguous leading run of
furniture passages (cover, half title, title page, copyright, contents, dedication, epigraph)
on the first kept chapter and a contiguous trailing run (index, about-the-author,
advertisements) on the last kept chapter, by the same containment rules already used for
chapter titles. Invariants hold: a *middle* run named "Index" or "Copyright" is untouched
(containment, not position), and stripping never removes the whole book — a chapter emptied
by the strip restores its original passages (deterministic re-parse stability).

Evidence: `BookSegmentationTest` — `single chapter front matter passages are stripped`,
`single chapter back matter is stripped`, `middle chapter mentioning index or copyright is
NOT stripped`, `single chapter with only furniture stays unchanged`; the acceptance scenario
(single-chapter EPUB resumes at the first story passage; re-import reproduces the identical
kept set) is the tested behavior (`:core-ebook:test` green at commit).

### I2 — Smart chapter detection in monolithic books

**Complete (2026-08-28, decisions #70):** `splitChaptersByHeading` gives a book parsed to
exactly one chapter — MOBI7 without an NCX, a one-entry EPUB spine, plain TXT (Markdown ATX
already splits) — a fallback split on credible headings: chapter/part keywords (`Chapter N` /
`CHAPTER N`, `Part/PART N`, plus the en/fr/es/pt/it/ja/zh/hi forms and CJK/Devanagari chapter
words), all-caps Latin title runs, and leading-numeric "N. Title" lines. Heading text becomes
the chapter title (TTS skips the title field); heading passages are removed from the bodies
so they are not read aloud twice; chapters renumber contiguously.

Heuristic gates as specified: it runs only when the book has a single chapter (NCX/nav/ATX
boundaries always take precedence; single segmentation path inside `BookSegmentation`, no
second convention); at least two headings of one uniform kind — a lone "Chapter 1" amid prose
or mixed heading kinds stays one chapter; a book of only headings never divides into empty
chapters; deterministic and stable across re-parses (same stable-index contract as
`BookSegmentation`).

Evidence: `BookSegmentationTest` — `monolith splits on Chapter N`, `roman and name-case
headings split`, `numeric heading lines split when consistent`, `a book with one chapter
heading and prose stays one chapter`, `mix of chapter-numeral and all-caps headings does not
split`, `book of only headings does not divide into empty chapters`, `chapter indexes
contiguous after split`, and the en/fr/es/pt/it/ja/zh-cmn/hi heading splits
(`:core-ebook:test` green at commit).

Ordering followed as planned: I1 landed first (I2's split trusts I1's kept-set stability); both
shipped together in the same commit. Covered by the existing `BookSegmentationTest` suite.

## Later — strategic and dependency-gated work

| Item | Gate / reason for position |
|---|---|
| Pitch-preserving speed | WSOLA/phase-vocoder DSP and cache-key compatibility; measure CPU/battery before replacing hardware rate conversion. |
| CosyVoice pre-generation + voice cloning | DiT-gated (decisions #21/#23) and D3-quality-flagged (duplicated honorific probes; RTF 12.5–31.1); disk-only playback. Exact model pins and the S22 research gate unchanged; A1/A4 long satisfied. |
| Kindle official export/API sync | External API/export contract and account UX; manual share/resume already covers the core use case. |
| Word-level highlighting | Requires a stable word/phoneme timing contract beyond current sentence anchors. |
| Auto language detection and voice routing | Needs per-language voice mappings, mixed-language policy and pack-availability UX. |
| Full read/listen session history | Build only with a concrete history/export/statistics consumer. |
| Auto-delete listened audio | Eviction design first: must preserve the current playhead and every position reachable by undo — a design that does not yet exist (A4's LRU repair is done and is not the eviction policy). |
| Habit-driven pre-generation | Stats/session evidence first; prediction may rank work but never override storage, charging or playback-yield limits. |
| Profiles, collections and book-map navigation | Valuable reader/library expansion after search, folder import and basic controls are complete. |

## Phase J — Offline translation (NMT spike)

A measurement spike, not an assumed adoption. Answers one question on the S22: is
ONE many-to-many NMT model (all languages, one pack) worth its extra
memory/speed/disk over the per-pair direction (decisions #101), once quality is
held equal?

Legs (one `spike-tts` harness, four source→target pairs — it→es, en→pt-br, en→it,
es→en — host-prepared tokens, ORT-android 1.29.0):

| Leg | Model(s) (license) | Role |
|---|---|---|
| OPUS-MT per-pair (4 models) | it→es `Helsinki-NLP/opus-mt-it-es` (Apache-2.0); en→pt-br `Helsinki-NLP/opus-mt-tc-big-en-pt` (CC-BY-4.0 — attribute); en→it `Helsinki-NLP/opus-mt-en-it` (Apache-2.0); es→en `Helsinki-NLP/opus-mt-es-en` (Apache-2.0) | Per-pair baseline; the decisions-#101 direction |
| M2M-100 418M | `facebook/m2m100_418M` (MIT) | Single many-to-many model — 100 langs, 9,900 pairs; run on all 4 pairs |
| SMaLL-100 | `alirezamsh/small100` (MIT) | Reduced-cost many-to-many fallback (run only if M2M-100 fails the memory/RTF gate) |

`NLLB-200-distilled-600M` stays blocked (CC-BY-NC — decisions #101). Required
evidence per leg: cold session open, encoder ms, per-token decoder ms, per-passage
wall time, PSS/VmHWM, output finiteness, and a per-pair quality sample (chr-F vs the
fixed FLORES-101 dev-set slices). Acceptance: a single comparison table in
decisions.md + a typed per-model keep/defer.

## Idea pool — not scheduled

RSVP speed-reading, downloadable public-domain classics, a fully configurable tap-zone
editor, and speculative multi-engine parallelism remain in [ideas.md](ideas.md). They
have no dependency that warrants placing them ahead of stabilization, data safety or
the promoted library/narration work.

## Further reviews — recorded, not scheduled

These are review subjects, not implementation commitments. Each should produce a
bounded decision or roadmap proposal before code starts.

### Data survival and user-owned storage

Reconcile the planned backup archive with the candidate SAF storage-folder grant.
Decide what survives upgrades, clear-data and uninstall; whether original books are
copied or referenced; whether generated audio ever belongs in user storage; how a grant
is reacquired after reinstall; and how moved, revoked, full or partially written folders
recover. Measure SAF performance before making thousands of PCM/cache files part of the
public storage contract.

**Promoted 2026-08-31 (decisions #96):** this review is now **E0**, the gate in
front of E1 phases 2/3 — see Phase E.

### Hostile-input and resource limits

Audit every untrusted boundary: EPUB/KF8 entry count, expanded bytes and compression
ratio; MOBI decompression ceilings; pathological chapter/passage counts; malformed
covers and shared images; backup path traversal, duplicate entries, oversized JSON and
unknown sections; pack archives; temporary-file cleanup; and disk-full behavior during
import, restore and pre-generation. Existing XXE hardening is a baseline, not the whole
resource-exhaustion contract.

### Release readiness

Review release signing/AAB production, versioning and install-over-existing-data,
privacy policy and Play Data Safety declarations, GPL/model/voice attribution, store
listing and screenshots, supported devices/ABIs, native crash symbols and shrink rules,
and compatibility promises for future backup versions. Current `0.1.0`/version-code 1
and debug signing are development configuration, not a store-release procedure.

### Narration-quality benchmark

Build a listening corpus for names, honorifics, abbreviations, numbers, dates,
currencies, measurements, Roman numerals, dialogue, headings, footnotes, page furniture,
URLs/code-like text, long paragraphs, speed transitions and every advertised language.
Use the findings to bound G1 pronunciation rules instead of designing from the single
`Ms.` regression.

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
- ~~Configure the promised `ktlintCheck` task or remove it from the definition of done.~~ **Done (2026-08-29):** baseline-gated `ktlintCheck` (ktlint 1.7.2, committed baseline, CI `jvm-tests` lane).
- Continue physical-device acceptance on the S22 and HiBreak for behavior or performance
  claims affecting playback.

| Source | Pending device evidence |
|---|---|
| ~~A1 (#60)~~ | ~~`PregenE2eTest` whole-book/no-budget re-run on the Bigme B6 / S22~~ **Done (2026-08-31, decisions #105):** B6 `OK (1 test)` in 222 s — `PregenWorker` SUCCESS + playback completed over the warm disk tier. |
| ~~A2 (#61)~~ | ~~stop-mid-passage / kill / reopen acceptance on the B6 / S22~~ **Done (2026-08-31, decisions #105):** S22 — STOP persisted live playhead `15.14s`; force-stop + relaunch resumed the same `10.16s`, within the 5 s checkpoint. |
| ~~A4 (#63)~~ | ~~fill-cap / force-stop / relaunch acceptance on the S22~~ **Done (2026-09-01, decisions #105):** UI whole-book pre-gen filled 2.6 MB / 8 files (Worker SUCCESS); `am force-stop` + COLD relaunch left the cache byte-identical (reopen bootstrap), and playback over it resolved `loop: source=disk` with zero synthesis; eviction-order guarantee host-pinned (`PcmPassageCacheTest` 15). |
| ~~A5/A7 (#62)~~ | ~~MediaSession/notification re-verification on the B6 / S22~~ **Done (2026-08-31, decisions #105):** S22 — double-play command race superseded cleanly; MediaSession held E2E Book metadata with PAUSED/PLAYING matching the UI; playback notification (id 42, 4 actions) stable. |
| ~~A6 (#66)~~ | ~~import/share/pregen flow regression on the S22~~ **Done (2026-09-01, decisions #105):** S22 — `RealEpubImportProbe` OK (2: P&P android-DOM import + entity-in-metadata case), `SharePipelineInstrumentedTest` OK (2: text + image share via TextIndex/OCR), `PregenE2eTest` green — all headless legs pass. |
| ~~F2 (#90)~~ | ~~runtime verification of the library search UI on the S22~~ **Done (2026-09-01, decisions #105):** S22 UI pass — a non-matching query filters the library list to the "No books match" empty-state (rendered, deterministic), the continue-list stays unfiltered by design, and clearing the query restores the row; matching-logic host-pinned (`LibraryViewModelTest` 13/13). |

B4's device checks are complete (2026-08-31, decisions #98); **C2's device
legs were verified on the HiBreak in the same session** (decisions #105):
the shared voice selector in Settings + the reader voice sheet, Preview with
the off-main engine fix (no ANR, cancellable "Generating sample…"), exactly
one selection indicator, star-independence, and a persisted mid-session
voice change that survives process restart.