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

### B4 — Visual and accessibility acceptance

- Verify light and dark themes, contrast, 48 dp touch targets, system font scaling,
  TalkBack labels/order and reduced-motion behavior.
- Verify the actual library, player, reader, settings and share surfaces on the S22.
- Verify a low-motion/e-ink presentation on the HiBreak; expensive animation must
  degrade without hiding state or controls.
- Capture approved reference screenshots so later UI work has a regression target.

Completion means the product surfaces use the shared tokens/components and have been
visually exercised on both devices—not merely that a theme file exists.

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

### C2 — Voice selector in the primary flow

The full voice picker + favorites already exists in Settings; the missing part is
discoverable selection where listening starts. Reuse one selector/state model in:

- first-run setup;
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

## Phase E — data safety

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

## Phase F — library completion

One cohesive library/import slice rather than separate UI patches.

| ID | Work | Required result |
|---|---|---|
| ~~F1~~ | ~~**Import progress and cancellation**~~ | ~~Large and multi-file imports show current/total progress; cancellation settles cleanly; per-file failures remain isolated and typed.~~ **Complete (2026-08-27, decisions #64):** `BookImporter.importAll` is now suspend with a per-file pre-parse progress event (a single large file shows "Importing 0/1 — …" immediately) and a 1 ms cooperative boundary so a cancelled batch stops between files without mutating the index for untouched files; `LibraryViewModel` publishes `Importing(0,total)` on `import()`, tracks the batch in `importJob`, exposes `cancelImport()` (Idle, never a partial `Done` — guarded by `ensureActive` before the final publish), and the library row/progress bar gains a Cancel control. Per-file failures stay typed via `ImportOutcome.Failed`. Host evidence: `BookImporterTest` (13, incl. pre/post progress sequence + mid-batch cancel skips later files) and `LibraryViewModelTest` (8, incl. start-state `Importing(0,2)`, mid-batch cancel settles Idle, later import unaffected). |
| F2 | **Library search** | Filter by title and author locally with deterministic empty/no-result states; content identification remains the separate `TextIndex` capability. |
| F3 | **Folder import via SAF tree — promoted from ideas** | A persisted tree grant feeds supported files through the existing batch importer; recursion policy and a defensive per-batch cap are decided before build. |

F1 is the shared prerequisite for F3: scanning a folder without visible progress would
amplify the existing import UX defect.

## Phase G — narration and reader controls

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

### G3 — Hardware and listening gestures — promoted from ideas

Add the narrow useful subset before building a configurable gesture editor:

- Media/headset play-pause and seek commands continue through `MediaSession`.
- Optional volume-key passage navigation is limited to the visible reader and is off by
  default; normal system volume behavior must remain the default.
- Screen-off behavior must use supported media-session commands rather than promising
  interception Android does not deliver to an inactive activity.

Configurable tap-zone maps remain in the idea pool until the fixed reader interactions
have device evidence and an accessibility review.

## Phase H — TODAY reading and listening stats

Use the capture, aggregation and UI design in
[post-v1-plan.md](post-v1-plan.md#slice-a-today-stats-dashboard). Store whole seconds
and round only for display so short valid sessions are not discarded.

Open product decision: reading time capture. Recommended default remains reader-foreground
dwell with screen-on gating and a 10-second accidental-open floor. Listening time is
wall-clock duration while the player is actually `PLAYING`.

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
| pt-BR translation | New offline NMT stage and model/license/quality gate; output-side only so matching remains original-language. |
| CosyVoice pre-generation + voice cloning | A1/A4 first; exact model pins and S22 research gate; disk-only playback because measured RTF is far from realtime. |
| Kindle official export/API sync | External API/export contract and account UX; manual share/resume already covers the core use case. |
| Word-level highlighting | Requires a stable word/phoneme timing contract beyond current sentence anchors. |
| Auto language detection and voice routing | Needs per-language voice mappings, mixed-language policy and pack-availability UX. |
| Full read/listen session history | Build only with a concrete history/export/statistics consumer. |
| Auto-delete listened audio | A4 first; eviction must preserve the current playhead and every position reachable by undo. |
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

### Data survival and user-owned storage

Reconcile the planned backup archive with the candidate SAF storage-folder grant.
Decide what survives upgrades, clear-data and uninstall; whether original books are
copied or referenced; whether generated audio ever belongs in user storage; how a grant
is reacquired after reinstall; and how moved, revoked, full or partially written folders
recover. Measure SAF performance before making thousands of PCM/cache files part of the
public storage contract.

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
- Configure the promised `ktlintCheck` task or remove it from the definition of done.
- Continue physical-device acceptance on the S22 and HiBreak for behavior or performance
  claims affecting playback.
