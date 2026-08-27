# Decision log
## 64. F1 — Import progress and cancellation (2026-08-27)

Closed the user-reported "import looks hung" defect: `importAll`'s progress
callback fired only AFTER each file completed, so a single large file showed
nothing until its parse landed, and nothing could cancel a batch.

- **Pre-parse progress.** `BookImporter.importAll` (now suspend) fires
  `onProgress(current, done, total)` before each file's parse AND after
  completion — "Importing 0/1 — book.epub" is visible the moment the batch
  starts. `LibraryViewModel.import` publishes `Importing(0, total, name)`
  before launching, so even the first file's parse period is covered.
- **Clean cancellation.** The batch boundaries are 1 ms cooperative delays —
  a real suspension (noise against multi-megabyte parses) so a cancelled
  batch stops between files and never indexes a file it never started.
  `importJob` tracks the batch; `cancelImport()` cancels and publishes
  `Idle`; the Done transition is guarded by `coroutineContext.ensureActive()`
  so a racing cancel can never land a partial `Done` summary (a cancelled
  non-suspending tail would otherwise run to completion). The library row's
  import progress gains a Cancel button. Per-file failure isolation stays
  typed (`ImportOutcome.Failed` + reason mapping).
- **Contract note (A3 handoff):** a cancelled batch can leave files parsed
  and INDEXED but not persisted (the ViewModel commits Room rows only in
  `buildSummary` after the loop). This is exactly the CR-3 divergence the
  A3 reorder (commit Room in the loop, publish index after) will close.

Evidence: `BookImporterTest` (13 — 6-event progress sequence incl. pre-parse
events; mid-batch cancel at the file boundary leaves the index untouched for
later files) and `LibraryViewModelTest` (8 — start state `Importing(0,2)`;
mid-batch cancel settles Idle and a later import still works; scheduler
advances replaced eager no-op expectations now that batches park on their
1 ms boundary). `core-ebook` gained `kotlinx-coroutines-core`/`-test` for
the suspend contract.

## 63. A4 — Cross-process PCM LRU bootstrap (CR-4) (2026-08-27)

Closed the CR-4 frozen-cache failure: `PcmPassageCache` built its eviction
map only from in-process `put`/`get`, so after a restart the old on-disk
entries counted toward the byte cap but were never eviction candidates —
near the 4 GiB cap every new passage evicted itself and replacement froze.

- **Bootstrap on open.** Construction scans the tier, parses each `.pcm`'s
  [PregenKey] path, validates the `.meta` sidecar, and loads valid entries
  into the access-ordered map by pcm mtime, oldest first. Between restarts
  the eviction order is a deterministic approximation of true LRU (the
  repair record's accepted option; in-process reads still refresh exact
  order). The persisted-logical-order alternative was rejected: a write per
  `get` for the same approximate benefit.
- **Converge at construction.** An over-cap cache (old entries alone above
  the cap) is converged below it at open — not lazily on first mutation —
  because the pregen planner gates on `bytesRemaining() == 0` BEFORE any
  put; lazy convergence would have kept pre-generation frozen.
- **Invalid-artifact cleanup.** Stale `.tmp` writes, PCM without a valid
  sidecar, metadata without PCM, and paths that do not map to a key are
  deleted at open — `contains` can no longer report a permanent false hit
  that makes `OfflinePregen` skip a passage forever.
- **Oversized policy (explicit).** An entry alone larger than the cap
  cannot be retained; it is evicted like any other overflow (regenerable).

Evidence: `PcmPassageCacheTest` now 12 tests — reopen evicts the OLD entry
(not the new one), over-cap open converges at construction, invalid pairs
are removed and the passage regenerates, oversized-entry policy, all
pre-existing round-trip/LRU tests unchanged. Device acceptance (fill a
small cap, force-stop, relaunch, play an uncached passage, confirm an old
entry is reclaimed) pending on the S22.

## 62. A5+A7 — Single-writer player commands + state agreement (CR-5/CR-7) (2026-08-27)

Closed the CR-5/CR-7 control-plane races: book loads ran in untracked
coroutines (`stopEverything()` had no handle on them; the declared
`playerJob` was never assigned by any path), so an older load could publish
or drop the foreground after a newer command. On the device, pause during
first-audio generation left `dumpsys media_session` at `PLAYING(3)` with a
“Pause” notification, and ±30 s seeks re-synthesized instead of repositioning
a stopped playhead.

- **Tracked, generation-guarded commands.** Every control-plane command
  (open, openChapter, play, resume, pause, navigate, seek, undo, speed) now
  runs via `launchCommand` as a tracked `commandJob` under a monotonic
  `commandGeneration`. `stopEverything()` bumps the generation FIRST, then
  cancels: a command captures its generation at launch and re-checks it
  before ANY `publish`/`startForeground`/`stopForeground`/`startLoop` side
  effect. Cancellation alone is insufficient — the machine's `storeOp`
  swallows `CancellationException`, exactly how a stale load kept running to
  its publish tail (the CR-7 mechanism).
- **Pause during LOADING/“Generating…”** now cancels the in-flight job via
  the command model and publishes `PAUSED` on UI, MediaSession and the
  notification; a superseded generation loop cannot republish `PLAYING`.
- **Navigation never resumes a paused playhead.** `seekBy`/`navigate`/
  `navigateUndo` capture `wasPaused` before teardown and re-`pause()` after
  repositioning; a paused ±30 s seek moves the playhead, writes the row and
  stays silent (the device evidence showed re-synthesis).
- **Boring over actor:** the accepted “smaller repair” instead of a command
  actor — one launcher + generation checks, no command queue; commands stay
  responsive and the long-running synthesis/play loop remains cancellable.

Evidence: `PlaybackServiceA57Test` (6 — pause during generation publishes
PAUSED and nothing synthesizes; a superseded publish-loop is cancelled and
stays dead; paused seek and paused skip reposition without resuming; a
superseded OPEN never publishes; launched commands are cancelled by
`stopEverything`). All prior feature-player host tests and the instrumented
compile stay green. Device re-verification (MediaSession/notification during
generation-pause; two-library-row play stress) pending on the B6 / S22.

## 61. A2 — Live playhead persistence (CR-2) (2026-08-27)

Closed the CR-2 progress-loss: STOP and service teardown rewound the
persisted resume row to the current PCM slice's start because
`stopEverything()` releases `PassageOutput` (its head reads 0 after stop)
before `machine.stop(liveOffsetSeconds())` sampled it; `stopPlayer()` also
raced `onDestroy()` for the final write, and no checkpoint persisted the
live intra-passage playhead between transitions (abrupt death lost the whole
current passage).

- **Capture-before-teardown.** `stopPlayer()` now calls `captureAndStop()`,
  which computes `liveOffsetSeconds()` FIRST, then tears down output/loops,
  then performs the single final write (`machine.stop(finalOffset)`) in a
  tracked `finalStopJob`. `onDestroy()` runs `teardownWrite()`: it joins a
  graceful stop's in-flight write (never double-writing with a stale
  post-release offset), or else captures the live playhead itself before
  tearing down and writes exactly once. The book-time offset is captured
  verbatim — `positionSamples` is book-time at every speed, no speed math
  (decisions #52 preserved).
- **Throttled checkpoint.** While a passage plays, the player coroutine
  (the machine's single-writer edge — cannot race the machine's own passage
  transitions) commits the live playhead when `dueCheckpoint(clock())`
  passes: at most one write per `CHECKPOINT_MS = 5 s`. Abrupt process death
  therefore loses at most one interval, not a whole passage; the 1 s UI
  ticker still publishes live offsets but never persists by accident.
- **Test seams.** `output`, `machine` and `baselineOffset` became `internal`
  so host tests drive the real service with a fake `PassageOutput` (zeroing
  its head on stop, mirroring `AudioTrackPassageOutput`) + the real machine
  over `InMemoryPlayerStore`/a commit-counting wrapper.

Evidence: `PlaybackServiceCr2Test` (4 — STOP persists baseline 10 s + 5 s
live = 15 s; teardown writes exactly once at the captured playhead;
teardown joins a graceful stop without a second write; the checkpoint gate
allows one commit per 5 s). All prior feature-player host tests and the
instrumented compile stay green. Device stop-mid-passage/kill/reopen and
abrupt-death acceptance pending on the Bigme B6 / S22 (one session after
A5+A7).

## 60. A1 — Pre-generation terminal truth (CR-1) (2026-08-27)

Closed the CR-1 false-success: choosing “Whole book” in the pre-generation
overlay silently did nothing and settled as a successful job. Two defects and
their repairs:

- **Deadline conflation.** `PregenWorker` computed
  `budget.maxTimeMs?.minus(elapsed)?.takeIf { it > 0 } ?: break`, so a null
  deadline (“whole book”) was treated as an already-expired deadline and the
  loop broke before constructing `OfflinePregen`. `PregenBudget` now exposes
  `remainingTimeMs(elapsed): Long?` (null = unbounded by construction) and the
  worker breaks only when the value is non-null and `<= 0`, passing null
  through as an unbounded `maxTimeMs`.
- **False success.** `doWork` returned `Result.success()` unconditionally,
  hiding every `OfflinePregen` stop. `PregenProgress` now carries a
  `PregenTerminal` (`Completed`, `BudgetExhausted`, `CacheSaturated`,
  `Yielded`, `Unavailable`, `FailureCap`) set by `run()` on every return path
  (the terminal event is the final progress event, so the observed tail equals
  the result). The worker maps `Unavailable`/`FailureCap` to
  `Result.failure` with `KEY_ERROR` + per-run counts (overnight stops the whole
  job: engine conditions are global), and `LibraryScreen.BookRow` surfaces the
  error text. A `null` terminal is itself a failure, never a silent success.

Testability: `KokoroRuntime` became `open` with `engine(): TTSEngine?` (the
only surface any caller uses — zero behavior change) so host tests inject a
fake engine; `feature-player` gained Robolectric + `work-testing` (+
`vintage-engine`, `kotlinx-coroutines-test`) and `PregenWorkerTest` drives the
real worker over in-memory Room (`RoomLibraryStore` + `AppSettings` over an
in-memory `LibraryDatabase`) and the real `PregenCache` tier. The book loop
lives in `runBooks` with an injectable clock for deterministic budget tests.

Evidence: `PregenWorkerTest` (6 — whole-book without budget synthesizes and
caches, expired finite budget does nothing, unbounded is not expired,
`Unavailable`/meltdown fail with typed errors, missing engine fails),
`PregenBudgetTest` (4), `OfflinePregenTest` (13, terminals asserted). Device
`PregenE2eTest` (no-budget whole-book path) re-run pending on the Bigme B6 /
S22.

## 59. Fresh-install journey, primary-flow voice selector, review backlog (2026-08-27)

The roadmap gains Phase C after the visual system and before performance/data feature
work. A clean install has no books or speech assets; successful first audio must be a
designed journey rather than a sequence the user infers from Settings.

- **Guided setup:** explain offline/privacy behavior, choose language + voice, present
  exact required-pack/storage cost, coordinate Kokoro model + voices + espeak-ng
  downloads, keep OCR optional, then import a book and reach first audio. Network loss,
  cancellation, low storage and process restart are acceptance cases.
- **Durable facts, not a completion bit:** setup derives from ready packs, selected
  voice and library contents. Missing or cleared assets reopen the actual missing step;
  every action remains reachable later.
- **Voice selector:** the existing Settings picker/favorites remains the management
  surface, but one shared selector also appears in first-run and the primary
  player/reader flow. Initial scope is one global voice; per-book voice is not implied.
  Selection is explicit: a persistent "Selected voice: …" summary plus a radio/check on
  exactly one available row. Stars mean favorite only; row taps select and star taps
  only toggle favorite. A saved voice missing from the catalog is shown unavailable
  with a download/reselect action, never as an all-unselected list. A switch preserves
  the playhead, supersedes stale synthesis after CR-5/A5, uses the voice-keyed cache,
  persists through `AppSettings`, and never silently falls back when an asset is missing.
- **Voice sampling:** every ready voice row has Preview/Stop using a short fixed phrase
  appropriate to its language. Auditioning never selects/favorites the voice and never
  writes book progress, history or passage-cache entries. One sample owns the audition
  path; a newer preview cancels the old one and slow generation is visible/cancellable.
  Active narration pauses at a captured playhead and resumes only if it was previously
  playing, serialized through CR-5/A5. Missing assets show the normal download action.
- **Sample content:** not decided. First-run must work with a user-imported book; a
  public-domain sample can be reviewed independently.
- **Further reviews recorded, not scheduled:** data survival/user-owned storage,
  hostile-input/resource ceilings, release readiness, narration-quality corpus,
  Android lifecycle/interruption matrix, OCR replacement, library metadata, local
  diagnostics and battery/storage policy.

The former roadmap phases C–G shift to D–H. Promoted-idea destinations now point to D2,
F3 and G1–G3.


## 58. Material 3 design-system and UI-redesign slice (2026-08-27)

The roadmap gains Phase B after stabilization and before new product screens. Ayvu
already depends on Compose Material 3; the gap is a shared visual system and deliberate
surface design, not the absence of a component toolkit.

- **Foundation:** keep Material 3; centralize branded light/dark color roles,
  typography, shapes, elevation, spacing and motion in `AyvuTheme` instead of applying
  default schemes directly in `MainActivity`.
- **Components:** build only the shared cards, rows, controls, progress, dialogs and
  state panels required by real screens. Shared UI owns no business logic, navigation,
  stores or ViewModels.
- **Boundary:** settle the component home during CR-6. A small Android `core-ui` module
  is allowed only when it prevents feature-to-feature dependencies; it is not a new
  application layer.
- **Redesign order:** player card/library, reader, settings/storage, then share/import
  states. Backup, folder import and stats reuse the resulting system rather than adding
  one-off styling.
- **Third-party rule:** adopt a Compose library only for a named missing component after
  accessibility, maintenance, license and APK-cost review. No wholesale UI-kit swap.
- **Acceptance:** approved reference screenshots plus light/dark, font-scale, TalkBack,
  touch-target, reduced-motion and contrast checks on the S22; low-motion/e-ink behavior
  on the HiBreak.

The former roadmap phases B–F shift to C–G. Idea dispositions now point to C2, E3 and
F1–F3.


## 57. Roadmap reset: stabilization first + five idea promotions (2026-08-27)

The completed build-era phase plan was no longer an active roadmap: it mixed shipped
implementation history, stale estimates and unfinished post-v1 work. `roadmap.md` is
now a current sequence with the v1 phases compressed to a reference table.

- **First gate: shipped-contract stabilization.** CR-1 through CR-5 are release-blocking
  correctness work; CR-3 + CR-6 form the Room/index ownership and feature-boundary
  cutover. Instant seeking waits for cache/command correctness; backup waits for durable
  Room/index consistency; cross-feature work waits for clean composition boundaries.
- **Next sequence:** instant-seek + weak-device performance, backup/restore, library
  completion, narration/reader controls, then TODAY stats. Translation, CosyVoice,
  Kindle sync and timing-heavy reader features remain later strategic work.
- **Promoted from `ideas.md`, superseding #29's ideas-only disposition:**
  (1) accelerator/quantization/power measurement gate,
  (2) folder import paired with import progress/cancellation, (3) general TTS
  pronunciation replacements, (4) paragraph long-press Play/Copy menu, and (5) the
  narrow hardware/listening gesture subset. A fully configurable tap-zone editor is
  explicitly not promoted.
- **Safety boundaries:** pronunciation changes are output-side only; volume keys keep
  normal system behavior by default; accelerator changes ship only after device and
  audio-quality evidence.
- Historical estimates and completed player-card specifications remain recoverable in
  this ledger and git history; they no longer obscure active work.


## 56. Player card refinement: in-list on the library, channel cuts (2026-08-27)

User design review of #53 card placement + controls; shipped + verified.

- **Library placement**: the card now REPLACES the top "Continue listening"
  row in place (no bottom dock — the dock was my scope, the user's original
  "modified library book card" was the brief) and expands in place when the
  session starts (`AnimatedVisibility` expandVertically+fadeIn, 300 ms).
- **Library-only extras** (the replaced row's info: `topRight`
  overflow menu — pre-generate/delete-offline/remove-from-library — and the
  `badge` offline disk usage) are parameters on the shared `PlayerCard`;
  the reader passes nothing.
- **Card→reader**: adding the extras removed the only tappable row for the
  playing book — the card's cover/title area now opens the book (`onOpen`),
  restoring navigation (verified: tap card → reader at the resume point).
- **Chapter skip cut to core** (user: "just 30s skips for now"): card loses
  ◀Ch/Ch▶, the 4 service actions + both VMs' overrides are deleted; the
  tested core stays (`machine.skipChapter`, `BookLayout.nextChapter/
  previousChapter` + tests) for future need.
- **Verified on S22**: in-list card (Generating… spinner, elapsed/%/
  remaining, −30s/+30s/1×), ⋮ menu ("Pre-generate (≈8532.3 MB)", "Remove
  from library"; delete-offline shows only when usage>0), reader card
  without extras, open-book via card tap, no bottom dock.
- **Weak-device pass**: a second, weaker device is being plugged in; perf
  findings are LOGGED, not fixed — docs/bugs.md created (2026-08-27),
  entries (latest first), with the perf-vs-functional split.

## 55. App-wide player card shipped + device-verified (2026-08-27)

#53 built end-to-end; unit suite green; S22 device pass DONE.

- Verification captured live: card docks on the library (with the row-bounds
  entrance animation) and on the reader (old transport row + footer gone;
  sleep timer + undo stay in the top bar); spinner + "Generating…" shown
  while the engine loads; elapsed / % / remaining track correctly
  (1:52:16 → 2:14:43, 13%→17%, ≈8h 50m→8h 27m at 1×); play from the card
  resumes the saved position; speed pill + chapter/seek buttons present.
- **Instant-seek work (user-prompted "keep 30s ahead cached")**:
  - **Buffer reuse**: the loop keeps the last rendered passage
    (`lastAudio`, keyed by book/chapter/passage/voice/speed) and resolves it
    FIRST — a seek within the same passage replays with zero synthesis. On
    this book passages run ~6–24 s (< 30 s), so ±30s always crosses a
    boundary; the buffer path is code-verified, long-passage books get it.
  - **Deterministic disk**: first-listen persists are tracked and never
    cancelled (a new first-listen used to cancel the previous write, dropping
    passages from the tier); every seek path joins in-flight writes, so a
    played passage is always on disk. Verified: −30s hit `source=pregen`
    (instant, queue) on a recent passage.
  - **Command serialization**: transport commands now take a `commandLock`
    (state mutation inside, `startLoop` outside — the loop must never hold
    the lock) and `stopEverything` cancels the tracked `loopJob` directly.
    Rapid ±30s taps no longer race: an 8-tap burst previously produced
    corrupted `PassageAdvanced` (stale loops finishing after the position
    moved); now each seek is a clean serialized move.
  - **Honest residual**: a cross-boundary ±30s to a passage in neither the
    RAM queue (lookahead=2) nor the disk tier still synthesizes (~5–25 s on
    the S22, RTF ≈ 0.5); the spinner holds through it. The real fix for
    "±30s always instant" is the roadmap follow-up: time-bounded look-ahead
    (~30 s of audio queued), and NOT cancelling `queue.ensure()` on seek so
    the in-flight pre-generation survives the jump.
- Evidence: /tmp/pc5-…/pc6/pc8/pc9 (library + reader card states).

## 55. App-wide player card shipped (2026-08-27) — device pass pending

Roadmap #53 built end-to-end; unit suite green; on-device verification
PENDING (S22 not connected).

- **Shared `PlayerCard`** (feature-player/ui/PlayerCard.kt) + `PlayerCommands`
  interface: cover thumb (`files/covers/<bookId>`, decoded in-card), title,
  subtitle (authors · Ch · P or **"Generating…"** while `LOADING`),
  book-wide progress bar, times row (book-time elapsed · % · remaining at
  current speed — `BookProgress.elapsedSeconds`/`totalSeconds`/`positionAt`
  added, chars/15 model shared with `PregenSpaceEstimator`), transport row
  (−30s · ◀ Ch · play/pause with spinner · Ch ▶ · +30s · speed pill).
- **Both screens dock it**: reader `bottomBar` (DockedControls + footer
  removed; sleep timer + undo stay in the top bar), library `bottomBar`
  whenever a session is active, with an entrance animation from the tapped
  row's bounds (`onGloballyPositioned` row-centers → graphicsLayer translate
  + scale, `Animatable`, 360 ms fast-out-slow-in).
- **Rolling ±30s seeks** (service `seekBy`): playhead → global book-time →
  delta → clamp → `BookProgress.positionAt` → `machine.seekTo` (ring push).
- **Chapter skip**: `BookLayout.nextChapter/previousChapter` (empty-chapter
  gaps) + `machine.skipChapter(dir)` — one ring entry, undo restores the
  exact playhead (`notePlaybackOffset` test).
- **Service/state**: `PlaybackUiState.authors` + `elapsedSeconds`;
  `ACTION_SEEK_FORWARD/BACKWARD`, `ACTION_CHAPTER_FORWARD/BACKWARD`;
  `LibraryViewModel.playerState` + full command surface.
- Tests: `BookProgressTest` (elapsed/total/positionAt round-trips + bound
  clamps), `PlayerStateMachineTest` (skipChapter fwd/back/bounds/empty-gap/
  undo-exact-playhead). All green.
- Edit-tooling note: `PlayerCard.kt` first draft carried drafting garbage
  (stray Box/Spacer, double-wrapped play button) — full-file rewrite fixed;
  two python scripts failed to write because an assert died BEFORE the write
  (partial-apply illusion) — scripts now write after ALL asserts.

## 54. CosyVoice as a pre-gen engine (2026-08-27) — designed, not started

Plan on the roadmap (full detail there); grounded in the shipped seams.

- **Scope**: CosyVoice (2-0.5B first, 3-0.5B only if the research gate
  prefers it) as a pre-generation-only engine — output goes to the disk
  tier, never live playback (RTF gate #21 stands; the #42 overnight window
  was sized for this class).
- **Zero-shot voice cloning is in scope for the first slice** (user choice,
  over bundled-prompts-only): SAF audio picker (3–10 s, decoded to 24 kHz
  mono PCM) + pasted transcript → local prompt store → cloned-voice
  pre-gen; one bundled prompt voice ships so the slice works standalone.
- **Architecture reuses the Kokoro port pattern** (raw onnxruntime Java,
  decisions #25 — no new native dependency); `TTSEngine` seam already
  anticipates the segment-less tier (`SynthesisOutcome.Audio.segments =
  null` → no read-along highlight on those passages, accepted).
- **`PregenKey` gains an `engine` dimension** (legacy paths parse as
  `kokoro`), and voice slugs namespace per engine — the current
  voice-only slug would otherwise collide across engines on the disk cache.
- **Research-gate decision deferred**: CosyVoice2 vs 3 pinned by ONNX
  availability + S22 RTF at the start of the slice (cosyvoice3-0.5b stays
  the metadata stub in `DefaultEngines`).
- Cost reality: ~300–500 MB int8 pack; RTF 8–17; those numbers get pinned
  at the gate, not planned from.

User-scoped during design discussion (mockup-driven); roadmap entry carries
the full build detail; tree clean at HEAD `85bc5af`.

- **Scope chosen over the reader-only option**: one shared `PlayerCard`
  docked at the bottom of BOTH the library and the reader (replaces the
  reader's transport row); opening a book from the library animates the card
  from the tapped row into the docked slot (library-first appearance; the
  slot is identical on both screens, so it persists through navigation).
- **Loading feedback**: synthesis latency is surfaced as a spinner inside the
  play button + a "Generating…" subtitle on the card (chosen over an
  indeterminate bar).
- **±30s seeks roll across passages**: from the edge, convert the current
  position to global book-time (chars/15′ speech model), apply the delta,
  clamp, walk back to `(chapter, passage, offset)` via pure
  `BookProgress.positionAt`; jumps push the undo ring. Rejected: clamping
  inside the current passage.
- **Chapter skip** enters via `BookLayout.nextChapter/previousChapter` (skip
  empty chapters), tail-collapsed ring push, publisher disabled mid-flight.
- **Known tooling hazard re-confirmed**: this environment's `edit` `＋`
  insertions intermittently replace adjacent lines AND, in one blocked
  attempt, wrote the full-width `＋` marker literally with `+` prefixes —
  re-verify every insertion and prefer full-line python rewrites for
  multi-line inserts (this session: restored `android.app.*` imports,
  `resumeOnGain`/`ducking`, then reverted the aborted core insert via
  `git restore`).

The rationale behind load-bearing decisions. New decisions get an entry here with date,
context, alternatives considered, and consequences. Keep entries short — this is a log,
not a spec (specs live in architecture.md / feature docs).

## 1. Package name `com.moronigranja.localttsreader` (2026-08-24)
Namespace for all modules. Confirmed by the owner; applied to core-locate before the
Android app existed so nothing needs a later rename. Alternatives: `com.localttsreader`.

## 2. DI = Hilt (2026-08-24)
Compile-time dependency graph with free ViewModel/Service/Compose integration.
Alternatives: minimal hand-rolled container (explicit but no cycle checks, manual
ViewModel factories; churns past ~30 objects; this app has ~35-45). Chosen before F1:
switching is cheap early, expensive later.

## 3. Match confidence threshold: default 0.6, configurable in settings (2026-08-24)
Recall semantics: "fraction of the snippet's word-groups found in a passage".
1.0 = verbatim; realistic OCR stays ≥0.6; cross-book noise measures ≤0.05; reordered
text ≈0.06 — a clean separation. 0.3 was proposed first; owner raised to 0.6.

## 4. Matcher: 4-gram recall with 3-gram fallback credit (2026-08-24)
Measured against plain n=4 (collapses under a few OCR typos) and n=3 (degraded
distinctiveness). n=4 + sub-3-gram credit keeps matches ≥0.6 under realistic noise
while cross-book text stays ≤0.05. Short snippets (<4 words) fall back to unigrams.

## 5. OCR = tess-two (Tesseract), languages eng+spa+fra+deu+por+ita (2026-08-24)
Fully open-source (owner rejected Google ML Kit). Languages are a curated roman-alphabet
starter set; more languages ⇒ slower OCR + larger app + slightly lower accuracy.
No auto-detection — the bundled set runs.

## 6. TTS primary target = Fun-CosyVoice3-0.5B-2512; Kokoro-82M demoted to fallback (2026-08-24)
Owner reviewed Kokoro as flat. Survey of open-weight engines (HF-verified): nothing
under 0.5B is genuinely expressive; Orpheus is 3B-only (desktop); KittenTTS/MeloTTS
are tiny but flat/English-only. CosyVoice3-0.5B is Apache-2.0, 9 languages + dialects,
emotion/speed/volume instruct, ONNX/GGUF ports exist. **Gated on an on-device
RTF/RAM/thermal measurement on an S22 Ultra** before it becomes default — audiobook-style
pre-generation means non-realtime synthesis is acceptable.
pt-BR is a nice-to-have: covered via Kokoro (3 pt-BR voices) and Piper packs.

## 7. TTS model & language packs: downloadable, never bundled (2026-08-24)
All engine assets are runtime downloads — explicit, consented, resumable, cached.
The APK ships no TTS data. This was the owner's requirement to keep the APK lean and
per-language coverage flexible.

## 8. Android toolchain: plain Ubuntu + cmdline-tools Docker image (2026-08-24)
vs. prebuilt SDK images (thyrlian/android-sdk etc.): exact version pins in-repo,
smaller image (one platform/build-tools/NDK), smaller trust chain (Ubuntu + Google
only). Tradeoff: a one-time ~5 min `docker build`. No emulator in Docker (KVM
flakiness) — physical device + host adb instead.

## 9. Docs split: agents.md = entry point; planning lives in docs/ (2026-08-24)
agents.md holds basic app description + pointers only (owner request). Topic docs:
hard-facts, conventions, modules, architecture, build, roadmap, decisions, features/.

## 10. License Apache-2.0, repo public (2026-08-24)
Owner chose public + Apache-2.0 (fits the open-weight/offline ethos). DRM specifics
sanitized before publishing (no tool names, no key-derivation mechanics in public docs).

Superseded by #27 (2026-08-25): the license is now GPL-3.0.

## 11. Book identity = SHA-256 of container bytes (2026-08-24)
Content-addressed: no cloud, deterministic across machines, idempotent re-import
(same file twice → "Unchanged", no re-parse); same name + changed content = distinct
book. Alternatives: UUID/file-path ids (unstable across re-imports).

## 12. Pure-JVM core modules + thin Android edges; import orchestration in core-ebook (2026-08-24)
All logic testable without the Android SDK (this environment proved it: 70 tests on a
standalone Kotlin compiler). BookImporter (parse→segment→index) lives in core-ebook
with a dependency on core-locate rather than a new module — a component lives in the
module of its primary responsibility; split only when a cycle forces it.

## 13. Segmentation contract: passage = unit of matching + resume (C4, 2026-08-24)
Paragraph grain; long passages (>100 words) split at sentence boundaries
(abbreviation-safe); front/back-matter chapters stripped position-guarded; never strip
a whole book; kept chapters keep spine indexes. Import MUST segment before indexing.

## 14. Slice order: match core + index + share receiver first (2026-08-24)
Owner's scope call: identification core before the player, so the riskiest logic was
proven first. Player resume wiring is the next slice.

## 15. DRM stays out-of-app, always (pre-planning, re-affirmed 2026-08-24)
The app never touches DRM: encrypted files are rejected up front with a clear message;
removal is the user's own out-of-app act. KU books excluded. This is a legal stance,
not a technical gap (hard-facts.md).

## 16. Offline-first: no network in the happy path (pre-planning)
Any download is a single explicit, consented, resumable operation; every socket use
must be justified in a PR (hard-facts.md).

## 17. Sandbox verification rig: standalone Kotlin 2.4.10 + JUnit Platform 6.1.3 (2026-08-24)
This environment lacks Gradle/Android SDK; the pure-JVM modules are compiled and
tested with the downloaded Kotlin compiler + JUnit console. Gradle (`gradlew`) configs
exist for normal machines; the rig is the proof source until then (build.md).

## 18. Gradle wrapper pulled forward from F1 (2026-08-24)
The wrapper is pure-JVM and README/build.md/docker-build.sh already document
`./gradlew` commands; landing it now makes those true and shrinks F1 by one item.
The sandbox Kotlin-compiler rig (decision #17) remains the proof source until the
Android toolchain lands.

## 19. Toolchain revision: Gradle 9.1.0 + AGP 9.0.1 for Hilt (built-in-Kotlin opt-out) (2026-08-24)
Hilt's gradle plugin requires AGP ≥ 9.0 since 2.59, and Hilt 2.58's processor
cannot read Kotlin 2.4 metadata — with Kotlin 2.4.10 pinned, only AGP 9 works.
AGP 9.0 requires Gradle ≥ 9.1.0, so the wrapper moved 8.14.3 → 9.1.0 (KGP
2.4.10 band: 7.6.3–9.5.0). AGP 9's new DSL + built-in Kotlin break the classic
kotlin-android/kapt path, so `android.newDsl=false` and
`android.builtInKotlin=false` opt out (both supported until AGP 10, which
forces the built-in-Kotlin migration — deferred to post-v1, decided 2026-08-25; the KSP2 finding under #22 removes the kapt wall but not the rest). Compose BOM stays
2026.06.01 (newer BOMs need compileSdk 37/AGP 9.1). Docker image unchanged
(build-tools 36.0.0 = AGP 9 default).


## 21. T3 CosyVoice3 gate result: CPU-only fails on the S22 Ultra; Kokoro stays v1 primary (2026-08-25)
Measured via the `spike-tts` harness (final, audio-verified run): jiangzhuo9357
int4 ONNX export (sokuji-audio-verified semantics), ORT 1.23.2 CPU-only, 6
threads, S22 Ultra (SM-S908U1), 3 runs on a cool device. Final RTF 14.7–17.5
per 10.1–13.1 s of audio (LLM 32–50 s, flow DiT 107–133 s ≈ 72% of cost, HiFT
8–10 s); VmHWM ≈ 2.4 GB, totalPss ≈ 333 MB, no thermal throttle. The flow DiT
has no credible mobile acceleration path (ORT Vulkan EP / NNAPI / LiteRT-LM
cover LLM-style ops only; bounded best case ≈ RTF 10–19). The spike's open
fidelity defect is closed: a stale diffusion-input snapshot (flow `x2` never
rebuilt per step) produced a hot/compressed mel (mean −0.9 vs prompt −5.6) and
clipped buzzing audio; fixed in `Pipeline.flowGenerate`, device mels now match
host (−5.3 ± 0.3, prompt scale) and audio is clean (RMS 0.05–0.08, peak < 0.7,
no clip). Gate verdict unchanged and now final: **CPU fails the ~0.5–1×-realtime
bar by ~15–30×; v1 primary = Kokoro-82M** (decisions #6).
Consequences: v1 = Kokoro-82M primary, CosyVoice3 remains behind the gate in the
fallback tier; `spike-tts` stays in the repo to re-run the gate when a DiT
acceleration path exists; AR codec-token engines (hard-facts watch item) are the
documented bypass if narration quality checks out.

## 22. Persistence stack: Room 2.8.4 on kapt + forced kotlin-metadata-jvm; LibraryStore in core-model (2026-08-25)
P1/P2 landed Room on the existing kapt path (Hilt already there; no KSP plugin
exists for Kotlin 2.4 as of this date — KSP tops out at 2.3.11). Room 2.8.4's
kapt processor bundles a kotlin-metadata-jvm reader capped at metadata **2.3.0**,
while Kotlin 2.4.10's own stdlib/coroutines classes carry 2.4.0 metadata — so
processing any suspend DAO method crashes the processor, no matter what the
module emits (the decision #19 class of problem, now inside a dependency).
Fix: `resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")`
on the module's kapt configurations — the reader API is backward-compatible.
Scoped to `core-persistence`; lift the force when Room/KSP support Kotlin 2.4
metadata.
**Follow-up (2026-08-25):** the "no KSP for Kotlin 2.4" fact above refers to
the KSP1 line; KSP2 — the analysis-API reimplementation — decoupled from
per-Kotlin versioning: release **2.3.11** (its 2.3.10 fix targets Kotlin 2.4.0
default module names) works with Kotlin 2.4.10. **Migration done, verified
same day:** all three kapt consumers moved to KSP2 (`com.google.devtools.ksp`
2.3.11) — Room in core-persistence, Hilt in feature-library + app; the forced
kotlin-metadata-jvm and the `kotlin-kapt` catalog alias are gone; the crash
class was kapt's metadata-jar parsing, and KSP2 reads symbols via the analysis
API instead. Bar met in the Docker toolchain: **179 tests green, 0 failures
(ebook 50, locate 32, persistence 9, tts 81, feature-library 7)** and
`assembleDebug` builds. One infra find: Gradle's default 384 MiB metaspace
(no `org.gradle.jvmargs`) is insufficient under KSP2 + R8 dexing —
`org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g` added to gradle.properties.
The AGP 10 built-in-Kotlin conversion stays deferred to post-v1 (owner,
2026-08-25): KSP2 removes the kapt wall from that migration but not the other
costs (new DSL, KGP band ceiling, compileSdk/BOM unfreeze, toolchain).
Schema: version 1, `exportSchema = false` (schema-drift check arrives with CI/V2),
migrations forward-only, **no destructive fallback** — a schema bump without a
migration fails loudly rather than wiping the library.
`LibraryStore` (books flow + add) is the domain contract in core-model;
`InMemoryLibraryStore` (unit tests) and `RoomLibraryStore` (production) implement
it. Authors are stored U+001F-joined in one column; if a real author name ever
contains U+001F, switch to a child table first.
The cached-parses table is the launch-time rebuild input; the rebuild is
mirror-set (purge ids absent from the cache), so it is idempotent and merges
safely with concurrent imports.
## 23. T1: TTSEngine contract + pack registry + download manager in core-tts (2026-08-25)
T1 ships `core-tts` (pure JVM): the `TTSEngine` interface, the pack registry
(engine → pack → status), and the download manager. Design decisions:

- **Pack descriptors are data; hashes are never fabricated.** A descriptor
  (HTTPS URL + pinned SHA-256 + size) exists only once a real artifact has been
  downloaded once and hashed — that happens during the engine slice (T2).
  `DefaultEngines` ships engine metadata only until then, so no placeholder
  URLs/hashes can ship by accident.
- **The cache is the persistent pack state; no Room pack-state column.** A pack
  is Ready iff a `.ready` marker sits next to its full-size artifact under
  `<root>/packs/<engineId>/`. A full-size unverified file is hashed exactly once
  on first use, then marked; the marker survives restarts, and deleting the pack
  files deletes the marker with them. (P1's "language-pack state" settings key
  is served by cache probing, not a separate table.)
- **Transport seam = the single sanctioned socket use** (hard-facts
  offline-first). `DownloadTransport` is the only network path; `JdkHttpTransport`
  (java.net.http) covers JVM/testing, and an Android adapter lands behind the
  same interface later — `java.net.http` isn't available on minSdk 26 devices.
- **Four statuses** — NotDownloaded / Downloading / Ready / Failed. Failed is
  per-session last-attempt memory (survives `refresh()`, cleared by the next
  attempt) so the UI can surface it instead of silently resetting.
- **Downloads:** resume via `Range` (206 append, 200 clean restart), per-chunk
  cancellation that keeps the `.part`, streamed SHA-256 verify before promote,
  corrupt artifacts deleted not cached, and concurrent requests for one pack
  coalesced into a single transfer (a join bug here — awaiting the caller's own
  fresh deferred instead of the in-flight one — was caught by the coalescing
  test and fixed).
- Consequence: **136 tests green** (+38 core-tts); T2 = Kokoro-82M impl behind
  `TTSEngine` + the first real descriptor.
## 24. GPL boundary: no reuse from candela or VoxSherpa-TTS (2026-08-25)
The landscape review (docs/landscape.md) found candela — a shipped Android
audiobook/reader that is the closest existing implementation of this app — and its
engine AAR VoxSherpa-TTS are GPL-3.0. GPL code and GPL AARs cannot link into this
Apache-2.0 project (#10) without contaminating it. Decision: candela/VoxSherpa are
design references only — their docs, issues, and documented behavior, never their
code. sherpa-onnx (Apache-2.0, the engine beneath VoxSherpa) stays a legal dependency
option.

**Superseded in part by #27 (2026-08-25):** this project is now GPL-3.0, so reuse
from candela/VoxSherpa is license-permitted; the reference-only posture stands by
choice (learning + architecture fit), not by law.

## 25. T2 engine layer: raw JVM port of kokoro-onnx; sherpa-onnx documented pivot (2026-08-25)
The landscape review (docs/landscape.md) surfaced sherpa-onnx (Apache-2.0; Java/Kotlin
Android API; prebuilt TTS demo APK; Kokoro v1_0/v1_1 bundles incl. phonemizer assets)
as the packaged engine candela ships in-process, vs the raw ONNX-Runtime port of
thewh1teagle/kokoro-onnx (MIT). The review's key finding — Kokoro is not a bare ONNX
call; phonemization is part of the pipeline — informed the implementation rather than
reversing the choice: T2 (in progress in the working tree) implements the engine as a
JVM port of kokoro-onnx (`SpeechPipeline` semantics) with espeak-ng phonemization via
JNA (system shared library), ONNX Runtime behind a `compileOnly` Java-API seam (JVM
jar for host tests/benchmark; the Android runtime ships inside the app, minSdk 26),
and the first real descriptors pinned as flat fp32 packs (kokoro-onnx
`model-files-v1.1`: `kokoro-v1.0.onnx` 325 MB + `voices-v1.0.bin` 28 MB, 54 voices —
decisions #23/#26). The advertised languages follow the pinned pack (en, fr, es, it,
pt, ja, zh, hi — v1.0 ships no German/Korean voices). sherpa-onnx remains the
documented pivot if the V3 device pass (RTF on the S22 Ultra, APK-size/ABI cost)
misses the bar — a `TTSEngine` impl swap; core-tts contracts and the pack seam are
unchanged.

## 26. Voice-pack defaults: flat single-file artifacts; fp32 weights (2026-08-25)
Evidence from candela (docs/landscape.md): voice packs are re-hosted pre-extracted
because on-device `.tar.bz2` extraction delayed first chapters tens of seconds on
low-end hardware; and INT8 packs were regressed to fp32 because INT8 dynamic
quantization added audible vocoder noise. Decision: pack descriptors (#23) point at
single flat files — upstream tarballs get a server-side extraction re-host (the
pack-servicing step candela's `voices-v2` plays); fp32 is the default until a
measured RTF gate forces quantization, and even then only quantization-safe ops are
candidates. Applied at T2 pack pinning.

## 27. License: GPL-3.0, building stays in-house (2026-08-25)
Owner decision after the landscape review: the repo publishes under GPL-3.0 from now
on (supersedes #10) and keeps building the app itself — no copying from candela or
VoxSherpa even though it is now license-permitted.
Context/evidence: core-ebook's `HuffCdic.kt` and `MobiNcx.kt` are direct Kotlin ports
of KindleUnpack code (GPL-3.0) — an Apache-2.0 repo already contained GPL-3.0-derived
code, a latent compliance gap. Single author, so relicensing is the owner's call.
Alternatives: keep Apache-2.0 + clean-room redo of the two parser files (extra days,
and GPL reuse stays forbidden); GPL-3.0 + copy candela wholesale (fastest to a shipped
player, forfeits the build-it-yourself learning and adopts a mismatched architecture).
Consequences: `LICENSE` = GPL-3.0 full text; the KindleUnpack-derived parser code is
now legally consistent with the repo license; Apache-2.0 (sherpa-onnx, CosyVoice3)
and MIT (kokoro-onnx) dependencies stay compatible (one-way into GPL-3.0); candela/
VoxSherpa code may be reused with attribution if ever wanted, but the clean-room
posture is kept by choice (learning + architecture fit — landscape.md). Attribution
obligations apply to any GPL code adopted later.
## 28. T2 landed: Kokoro-82M engine + first pinned pack descriptors (2026-08-25)
T2 ships the Kokoro engine behind `TTSEngine` in core-tts and closes the
"hashes never fabricated" debt of #23. The engine is a JVM port of the
kokoro-onnx `SpeechPipeline` (the #25 decision), with the following
implementation facts worth keeping:

- **Phonemization = phonemizer's espeak backend, ported.** espeak-ng is driven
  through JNA with the exact phonemizer semantics (punctuation preserve/restore
  with positions B/E/I/A, decimal-separator protection, stress kept, espeak's
  line post-processing, `_`-separator removal). Ground-truth tests freeze the
  reference strings (phonemizer 3.4.0 + system espeak-ng 1.52). Two port traps:
  Kotlin's `String.split(String)` is literal — `Regex.escape` produces `\Q..\E`
  quoting that silently never matches; and empty chunks MUST be filtered after
  punctuation stripping or stray lines leak into the output.
- **The graph contract is introspected, not assumed** (input_ids vs tokens,
  int vs float speed, duration output presence, embedded `kokoro_config` vocab)
  — the v1.1 export uses `input_ids`/float speed and carries durations + vocab;
  packaged `config.json` was validated identical to the embedded metadata.
- **JNA 5.17 moved `PointerByReference` to `com.sun.jna.ptr`** (the old package
  is gone, not deprecated); JNA `Structure.newInstance` needs a public no-arg
  class, so the espeak `VoiceStruct` is top-level, not a private inner class.
  ONNX Runtime 1.23's `OrtSession.Result.get` returns `Optional`.
- **Kotlin 2.4 dropped `kotlin.math.round(Double)`** — use `roundToLong()`.
- **Pause insertion is ±1 frame (±0.01 s) unstable near the quiet threshold**:
  numpy float32 pairwise vs sequential sums flip borderline quiet-frames. The
  reference python pipeline itself varies 83264↔83504 samples across runs for
  the same input, so this is accepted variance, not a bug; the oracle
  comparison (correlation 0.995–0.997, exact sample counts on clean runs)
  validates the port.
- **First pinned descriptors (model-files-v1.1, flat fp32, #26):**
  `kokoro-model` = kokoro-v1.0.onnx (325,505,369 B, sha beb0d184…df3a) and
  `kokoro-voices` = voices-v1.0.bin (28,214,398 B, 54 voices, sha bca610b8…fbf7d).
  Languages advertised = the pack's actual coverage (en, fr, es, it, pt, ja, zh,
  hi); German/Korean have no v1.0 voices.
- **Host RTF baseline (Ryzen 9 8945HS, fp32, ORT JVM): 0.15–0.23** vs the
  realtime bar; device pass stays V3. Android adapters (OkHttp transport,
  bundled espeak-ng) arrive with the player/settings slices.
## 29. Idea review: what graduates to the roadmap (2026-08-25)
Walked the ideas.md pool with the owner; each candidate got a disposition, and the
pool now carries a decision-status table:

- **Docked player with sentence-sync read-along = the v1 player UX.** T4 is
  reshaped into reader+player in one slice (docked playback panel on the reading
  page; narration highlights the current sentence). Feasible, not speculative: T2
  already computes per-phoneme timings from the graph's duration output. This is
  the product thesis ("import, open the page, tap Listen"), so it anchors the v1
  UI instead of competing with a plain player.
- **C7: TXT + Markdown import lands in v1** — a small sandbox-doable text parser
  through `BookSegmentation`; PDF stays deferred (both sibling apps agree).
- **Free-riders folded into existing slices as acceptance criteria** (no new work
  items): read/listen progress single-source (one `progress` row per book), speed
  changes preserve the play point, output/route-switch robustness, Android Auto
  verification, sleep timer incl. end-of-chapter, speed presets + per-book restore
  (T4); "Listen from here" passage gesture (S3); theme-follows-system + voice
  picker with favorites (V1; quality tiers parked until a second engine exists).
  Review follow-ups folded into T4: **user bookmarks** (migration v2 `bookmarks`
  table, long-press add, reader menu) and a **per-book position ring with an
  undo-skip action** (`position_history`, capped rows/book) — undo over confirm
  dialogs for accidental plays/skips.
- **Post-v1 roadmap markers:** offline chapter pre-generation (T5 extension —
  WorkManager job core + config-keyed PCM cache; the lever that makes the
  CosyVoice3 tier viable despite #21), pt-BR translation decorator (new
  `core-translate`, CC-BY-4.0 NMT with attribution, degrades to the original),
  TODAY stats dashboard (new local per-day minutes table), Kindle official-export
  sync (already deferred), **app export/backup + restore** (positions, library,
  settings, optional books — added at review follow-up; small versioned-zip
  `core-backup` slice; content-hash book ids make restore idempotent), **full
  read/listen session log** (the T4 position ring grows into a timeline; the same
  table feeds the stats dashboard), RSVP, classics bundle, auto language
  detection.
- **Ideas-only:** accelerator/int8 delegates (do not assume — measure at V3) and
  multi-engine parallel tuning (design reference until the S22 pass).
- Consequence: pt-BR moves from "fallback-engines nice-to-have" to first-class via
  the v1 primary — the pinned Kokoro pack ships pf_dora/pm_alex/pm_santa (hard-facts
  updated); the roadmap's stale assumptions (CosyVoice3 primary) are corrected to
  the #21/#25 outcome.
## 30. DRAFT (pending owner ratification) Engine layer: resolve raw-port vs sherpa-onnx before T4 (2026-08-25)

The #25 pivot question ("sherpa-onnx the pivot if the V3 pass misses") was scheduled
to be answered at the V3 device gate — after the player slice. T4 would build on the
raw port's contracts with the espeak-ng Android-packaging scar still open (landscape
open item: "the Android packaging of espeak-ng data/library is the open piece").
Draft: close that scar with a focused on-device spike BEFORE T4, and pre-commit the
fallback so the choice cannot drift.

Evidence:
- sherpa-onnx's Kokoro bundle ships the phonemizer as packaged assets (espeak-ng-data
  ~26 MB, lexicons, tokens, voices.bin, rule FSTs — landscape); candela proves the
  in-process path on Helio P22T-class hardware. The raw port's remaining edge is our
  ground-truth phonemization oracle (byte-identical refs, #28) — fidelity insurance,
  not product value.
- The raw port is otherwise finished and measured on host (RTF 0.15–0.23; 81 core-tts
  tests incl. oracle comparisons). The open risk is device-side: Android espeak-ng
  packaging + S22 Ultra RTF/RAM/thermal + APK-size/ABI cost — all three measurable by
  a small spike, none need T4.
- Post-#27 both paths are license-clean: espeak-ng GPL-3.0 into a GPL-3.0 app;
  sherpa-onnx Apache-2.0 one-way into GPL-3.0.

Alternatives:
- A. Status quo — V3 gate after T4: T4's engine-facing contracts, pre-gen queue keys
  and Android TTSEngine adapters land on the raw port; a V3 miss refunds that wiring.
- B. Pivot to sherpa now: closes the open scar immediately, but discards/relegates the
  finished oracle-verified port and adopts a new native AAR surface before any
  measurement says the port fails.
- C. Draft choice — a pre-T4 "engine-on-device" spike (bundle espeak-ng for the
  target ABI, run the port's Kokoro on the S22 Ultra, measure RTF/RAM/thermal +
  APK-size/ABI), with the sherpa pivot pre-committed if it misses; T4 proceeds on
  whichever engine the spike vindicates. The spike also carries a hard capability
  check: sherpa's Kokoro path must expose per-phoneme timing anchors — the
  sentence-sync read-along (the v1 thesis, #29) depends on them, and the raw
  port's `KokoroTimings` (7 tests) are the current source. If sherpa cannot, the
  pivot fails on that ground alone, regardless of RTF.

## 31. T4 synthesis grain: one passage blob + engine-computed sentence anchors (2026-08-26)
Spike A settled the T4 carry-over question ("sentence-grain synthesis vs one PCM
blob + anchors" — roadmap note 2) by measurement on the pinned real model
(`:core-tts:kokoroGrainSpike`, two passages: 6-sentence en/af_heart, 4-sentence
pt-br/pf_dora):

- **Per-sentence calls inflate audio.** Joined sentence audios vs one blob:
  en +0.07 s (+0.2 %, blob = 2 windows vs 6 sentence calls); pt +2.47 s
  (+10.8 %, 1 window vs 4 calls) — each call re-renders window-start
  context and its own prosodic contour. Listen-time drift is unbounded per
  call and per text.
- **No compute win.** Total synthesis ms: en blob 7.6 s vs joined 8.1 s
  (+6.7 %); pt 3.7 s both (~±4 %) — extra windows/pads cancel any savings;
  per-sentence RTF looks better only because the inflated audio is divided
  into the same total compute.
- **Seam behavior differs.** Sentence calls force uniform 241–273 ms pauses at
  every boundary; the blob renders natural 250–670 ms pauses at sentence and
  clause marks (the model's own phrasing, only topped up by insertPauses).
- **Anchors.** The blob's boundary pauses are NOT reliably alignable to
  sentences by silence scanning alone (clause marks render ≥200 ms pauses
  too) — the engine's `KokoroTimings` (per-phoneme, already computed and
  pause-shifted inside `insertPauses`, 7 tests; currently discarded) are the
  only reliable boundary source. Sentence calls give trivial anchors but at
  the seam cost above.

Decision: **T4 synthesizes one passage per request (blob) and the engine exposes
sentence-grain anchors in the outcome** — the player never re-splits or silence-
scans. Concrete contract to land with T4:

- `SynthesisOutcome.Audio` gains an optional `segments: List<SegmentAnchor>?`
  (sentence spans in seconds produced from the phoneme timings each mark
  boundary; null for engines without duration output — CosyVoice3 tier
  degrades to no-read-along highlight, never estimated highlights).
- Pause-shifted timings from `insertPauses` thread through instead of being
  discarded.
- Speed changes stay engine-side (timing anchors scale with the speed input).
- Post-v1 pre-gen cache keys per passage (blob), not per sentence (cache keys
  would otherwise freeze the seam artifacts).

Consequences: T4's player contract and T5's cache keys are fixed before the
player slice starts; the read-along highlight derives from one source of truth
(the engine), not text re-segmentation. Spike files: `kokoroGrainSpike` task +
`KokoroGrainSpike.kt` (measurements reproducible with `-PkokoroCache`); the same
run wrote the on-device corpus for the #30 device spike (`kokoro-device-corpus.tsv`,
raw pre-vocab-filter espeak-ng phonemes per corpus text).

## 30b. Device half MEASURED — raw port passes on the S22 Ultra (2026-08-26)
The #30 spike ran the raw port on the S22 (SM-S908U1, arm64, screen off/locked)
via a new instrumented harness in spike-tts (`KokoroBenchmarkRunner` +
`KokoroDeviceBenchmarkTest`, 2 passages — 40.4 s en/af_heart + 22.8 s pt-br/
pf_dora, 3 runs, ORT with ALL_OPT + 6 intra-op threads):

- **RTF 0.66–0.76 across runs** (en 0.69–0.74, pt 0.66–0.76) — the realtime bar
  clears with ~35 % headroom; engine open 1.5 s. vs CosyVoice3's 14.7–17.5
  (decisions #21): the engine-order verdict the draft was waiting for.
- **Platform parity:** device audio matches the host render (en sample count
  exact 970431 = 970431, correlation 0.996, RMS identical; pt off by one 10 ms
  pause-frame = the documented #28 variance). Phonemization was the only
  excluded stage (host-precomputed corpus; espeak-ng cost is ~ms/sentence) —
  closed by #32.
- **RAM:** VmHWM 1.41 GB / totalPss 1.31 GB (fp32 325 MB weights + ORT arenas);
  V3 tuning candidates `enableCpuMemArena`/`doCopyInKernels`, fp16/int8 only
  past the #26 measured gate.
- **APK-size/ABI:** arm64-only debug APK 21.1 MiB (onnxruntime-android +
  engine; models stay runtime downloads, decision #7).
- Two harness findings: (1) a launched-but-keyguarded Activity freezes at
  `__refrigerator` — benchmarks must run as instrumented tests or on an
  unlocked screen; (2) ORT's no-options `createSession` stalled on the 325 MB
  graph on device while the optioned path loads in ~1.5 s —
  `OrtKokoroSession.open` now sets explicit `SessionOptions` (ALL_OPT + 6
  threads, the T3-verified settings); host benchmarks shift slightly from #28.

Gate conclusion: the raw port passes; the sherpa pivot is not needed for v1
(and its TTS path cannot serve the read-along anchors yet — #3705/#3727 still
open upstream). T4 proceeds on the raw port; #32's bundle closes the packaging
scar; V3's device pass is now a routine RTF/RAM re-check, not a gate.

## 32. espeak-ng Android bundle: 1.52.0 tag + matching data, flat pack (2026-08-26)
The raw port's open scar (landscape.md "Android packaging of espeak-ng
data/library") is closed: cross-compiled `libespeak-ng.so` with the NDK
(arm64-v8a, ~2.1 MB) at the **1.52.0 release tag** — the exact version the #28
ground-truth phonemizer oracle is frozen against — paired with the version's
compiled `espeak-ng-data` (19 MB, arch-independent; taken from the system
1.52.0 installation, byte-for-byte the data the host tests use).

- Data generation cannot run in a cross build (the compiled generator is an
  arm64 binary), and master's committed data dir is a 1.4 MB stub — hence
  tag-pinned lib + tag-matched data.
- Ships as a flat pack per #23/#26 (app loads `libespeak-ng.so` by explicit
  path with `espeak-ng-data` staged next to it); pinned SHA-256 for the pack
  descriptor: `734cc95a93217a68…` (lib), committed when T4 wires the download.
- `tools/build-espeak-android.sh` reproduces the build in the toolchain image
  (root container, apt cmake/ninja/git, NDK 27.2.12479018; `build/` outputs
  gitignored).
- 1.52.0's CMake fetches `sonic` via FetchContent (needs git in the
  container); the library target builds clean, the data target must be
  skipped in cross builds (it executes the arm64 binary on the host, fails).
Consequences: T4's Android phonemizer adapter has no packaging risk left; a
device re-run with phonemization on-device is a one-command exercise once the
pack is wired.

**Addendum (same day): the bundle RUNS on-device, phonemes byte-parity.**
Wired into the spike harness (JNA + `libespeak-ng.so` staged under
`files/espeak/` with `espeak-ng-data` next to it): on-device phonemization
reproduces the host output exactly (en 730 chars, pt 496 chars — the #28
oracle survives the platform), and the full-pipeline RTF (phonemization
included) is 0.774 (en) / 0.754 (pt) on the S22 — the realtime bar still
clears end-to-end. espeak-ng cost: ~10–40 ms per passage.

**JNA-on-Android finding (load-bearing for T4):** the plain `jna` jar (5.17.0)
ships NO Android natives (its 21 `jnidispatch.so` are all desktop), so T4's
Android phonemizer adapter must depend on the **AAR**
(`net.java.dev.jna:jna:5.17.0@aar`, ships `jni/<abi>/libjnidispatch.so`,
resolved via `System.loadLibrary`) and exclude the jar that core-tts brings —
the spike-tts wiring
(`implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }`
+ `@aar`) is the reference for the app module.

**Landed (2026-08-26): the T4-0 contract is code + tests.** `SynthesisOutcome.Audio.segments: List<SegmentAnchor>?` (contiguous sentence spans in seconds; `SegmentAnchor(startSeconds, endSeconds)`); `KokoroTimings.sentenceSegments` groups pause-shifted phoneme timings at `.!?…` marks — span *i* runs to the next sentence's first phoneme, the last to the audio end, so boundaries are gap-free and exact in the final audio; null for graphs without a duration output. Engine threads the shifted timings out of `insertPauses` (previously discarded). Tests: 3 new engine tests (mark split + contiguous spans, null without durations, single-span unmarked text) + real-model validation in `kokoroGrainSpike` (anchor count == sentence count; each interior boundary sits at the end of a rendered ≥150 ms pause run within 37/31 ms — 1–4 frames of the documented ±1-frame tolerance, no silence scanning needed). CosyVoice3 tier: null → read-along degrades to no per-sentence highlight, never estimated.

## 33. Player state machine: single transactional write point + position ring (2026-08-26)
T4-1 lands `core-player` (pure JVM) — the logic half of the v1 player
(decisions #29); audio/synthesis stay with the engine (#31) and the Android
edges (T4-2):

- **The machine is the ONLY writer of player state.** Every write goes
  through `PlayerStore.commitProgress` = progress row + optional ring push in
  **one transaction** (Room `withTransaction`) — the resume row and the undo
  ring can never drift (T4 carry-over note 3). `PlayerStore` is a
  core-player contract with a Room impl (`RoomPlayerStore`) and an in-memory
  impl for tests.
- **Ring semantics:** a user-directed move **away** from the current position
  (skip forward/backward, seek, play-from-elsewhere, accidental play) pushes
  what is being left; natural forward advance never pushes; cap 10/book;
  `popRing` = one-shot undo; completion pushes the ending so undo replays it.
- **Positions are book-time** (offset at 1.0×): speed changes never move the
  play point, and the per-book speed persists in the progress row (preset
  restore, decisions #29 free-rider).
- **Sleep timer:** Off / EndOfChapter (pauses at the chapter's last passage,
  before the new chapter) / Duration (wall clock, fires once);
  `advance(now)` is the tick.
- **Bookmarks** snapshot the machine's position, so a bookmark is always
  consistent with the resume row.
- **Schema v2:** `progress` gains `offsetSeconds` + `speed` (backfilled 0 /
  1.0 in the migration); new `bookmarks` + `position_history` tables;
  forward-only `MIGRATION_1_2` (decisions #22). Verified by a legacy-DB
  migration test: exact v1 DDL → open v2 → Room's post-migration TableInfo
  validation + defaults + end-to-end store writes.
- The edge contract is the events: `PassageAdvanced` / `PauseRequested` /
  `PlaybackCompleted` + `LOADING→PLAYING` (`onAudioStarted`); T4-2 wires
  MediaSession/audio/Compose to it.
Consequences: T4-2 is thin wiring; the read-along highlight consumes
`segments` (#31) against `position.offsetSeconds`. Test surface: 20
core-player (transitions, ring, sleep, speed, bookmarks, failures) + 17
persistence (store round-trips, cap, migration) — 37 new, all green.

## 34. T4-2 landed: the player surface (PlaybackService + docked reader) (2026-08-26)
The Android half of T4 rides on the #33 machine and the #31 anchors:

- **PlaybackService** (feature-player, foreground `mediaPlayback`): runs the
  machine against the engine + an AudioTrack (`PassageOutput` seam),
  MediaSessionCompat (transport + prev/next), audio focus/ducking
  (GAIN/TRANSIENT/LOSS/CAN_DUCK) with auto-resume after transient loss,
  becoming-noisy pause (route switch), 1 s sleep-timer tick + 500 ms state
  publish, media notification.
- **Speed is per-request now**: `SynthesisRequest.speed` reaches the graph;
  positions/anchors stay book-time and sample math scales by speed
  (`sliceForSpeed`); a speed change pauses at the live offset and re-synthesizes
  from it — the play point is preserved (decisions #29 acceptance).
- **Read-along docked panel** (ReaderScreen): the passage text with the active
  sentence highlighted from engine segments (#31), transport dock (play/pause,
  prev/next, speed cycle, sleep cycle, undo-skip, bookmark at playhead).
  App navigation: LibraryScreen → ReaderScreen on book tap.
- **Engine/JNA wiring on device**: `KokoroRuntime` opens the engine lazily
  over `PackCache(filesDir)` + the staged espeak-ng bundle (#32); feature-player
  excludes core-tts's jar JNA and ships the `@aar` (the seam in practice).
- **Two device bugs found & fixed while wiring:** (1) the machine's natural
  advance kept `phase = PLAYING`, so the service loop exited after the first
  passage — `onPassageFinished` now marks the advanced passage `LOADING` (it
  needs its audio); (2) static AudioTrack completion via `onMarkerReached`
  never fires on this S22 build — completion is now head-position polling
  against the buffer frame count.
- **Verified on the S22** (locked, instrumented `PlaybackE2eTest`): a
  two-passage book plays through the real service/engine/AudioTrack to
  `COMPLETED`, segments surface for the read-along, and the resume row lands
  on the ending. Packs + espeak bundle staged per build.md (the V1 download
  UI owns the consent flow later).
Consequences: T4's player UX is functional and measured on-device; the
remaining free-riders (speed presets UI polish, Android Auto confirmation,
sleep-timer UI text) are V1 surfaces, not architecture. T5 (pre-gen queue)
keys off the passage blob + speed (+ voice) as designed.

## 35. T5-core: pre-generation queue + cache keying (2026-08-26)
The in-v1 pre-generation core (roadmap T5) lands in `core-player` — pure JVM,
engine-agnostic, fully unit-tested:

- **PregenQueue**: bounded (default lookahead 2) in-memory look-ahead —
  synthesizes the passages after the playhead while the current one plays, so
  a passage change is a `take` fast path, not a synthesize-then-play gap.
  Prunes entries at/before the playhead on re-anchor (a jump forward drops the
  stale look-ahead and refills), dedups, is single-flight, and stops at the
  first failed synthesis (the player's synchronous path is the typed-failure
  fallback). `take` runs lock-free against the map — the play loop never waits
  on synthesis.
- **PregenKey** = bookId + spine + voice + speed; its stable path form is the
  post-v1 disk cache layout (engine + voice + speed + passage keying per #31/
  #34; content-hash book ids make book removal a subtree delete, #11).
- **PcmPassageCache** (the post-v1 disk tier's logic, now): raw PCM + `.meta`
  sidecar (sample rate + sentence anchors) under `<root>/<bookId>/<voice>/
  <speed>/c<ch>p<passage>.pcm`, atomic tmp+rename writes, LRU eviction by a
  byte cap tracked in-process (filesystem mtime was unreliable on tmpfs),
  book-level delete.
- Wired into PlaybackService: the play loop takes from the queue when warm
  and launches look-ahead after playback starts (`ensure(position)` per
  passage, cancelled on jumps); a speed change rebuilds the queue at the new
  speed (the key carries speed). App builds; the two-call sequence that
  previously gated on a ~synthesize-then-play gap is now a take.
Consequences: the audible inter-passage gap closes (device re-verification
pending the S22 reconnecting); the post-v1 WorkManager slice is now pure
scheduling over a tested cache. Host suite: 36 core-player tests + 216 total
green; feature-player excluded core-tts's jar JNA at the new core-player edge
(the app AAR seam, #25/#32).

## 36. V1 + S1: settings UI and the OCR core (2026-08-26)
V1's settings surface and S1's OCR core land together, both fully verified:

- **Settings UI (V1)**: SettingsScreen (engine + pack download/progress/error,
  voice picker + favorites, share match threshold, OCR languages, theme
  system/light/dark) routed from the library top bar; theme palette owned by
  MainActivity off AppSettings.themeMode; match threshold and voice/favorites/
  ocr-langs persisted in the existing `settings` table (SettingsStore extended,
  Room schema untouched). **AppSettings** (core-persistence, pure JVM) is the
  hot-path mirror: play loop + theme read fields/flows, writes go through the
  store; PlaybackService reloads it at every play/resume/speed action, so
  settings written by the UI apply at the next transport action.
- **Packs (V1)**: the settings download UI drives the repository PackRegistry
  over a new AndroidHttpTransport (HttpURLConnection, Range-resumable,
  canonical redirect-following — decision #7 consent/resume/verify semantics,
  the only sanctioned socket use on-device). Kokoro model/voices download to
  the shared PackCache(filesDir) used by KokoroRuntime; the espeak-ng bundle
  stays a staged artifact (no pinned host exists — status shown, manual path
  documented). tessdata languages download to the same cache and stage into
  the tess-two data path.
- **core-ocr (S1)**: OcrEngine seam + OcrImage/OcrResult + bilinear
  ScreenshotDownscaler (1600 px long-side cap, per-channel, edge-clamped) + the
  six pinned traineddata desciptors — pure JVM, host-tested. feature-ocr wraps
  tess-two 9.1.0: TessTwoOcrEngine (fresh TessBaseAPI per pass, IO dispatcher,
  typed missing-tessdata failures) + TessDataStager (idempotent copy from the
  pack cache into `<filesDir>/tesseract/tessdata/`).
Consequences/tuning found on the S22:
  - **tessdata_fast 4.0.0 LSTM models FAIL init on tess-two 9.1.0** (native
    build is pre-LSTM; `init` returns false) — the pinned packs are now the
    **legacy tessdata 3.04.00** artifacts (eng 21.9 MB … ita 14.2 MB; real
    SHAs produced by one-time downloads; on-device ocr smoke test reads
    rendered glyphs, and the device hash matched the pin exactly). Revisit
    LSTM (accuracy) with a newer binding in a future slice.
  - A test-only wiring fix surfaced a real bug: the service must reload
    AppSettings — settings written by the UI were silently ignored by a
    service that never refreshed its singleton.
  - Instrumented verification: PlaybackE2eTest (full-book pregen completion),
    VoiceSelectionE2eTest (persisted voice reaches the engine —
    `voice=bm_george` in logs), OcrSmokeInstrumentedTest (tess reads
    "HELLO WORLD 123" from rendered pixels); each ran as its own instrument
    invocation (test classes sharing one process tripped Room-reopen races in
    the harness — signature pairing across app/test APKs demands one build+run
    invocation anyway, decision #34).
Open: LSTM models via a maintained binding when accuracy demands it — the only
remaining #36 item (S2/S3 shipped, #37/#38).

## 37. S2: the share receiver (2026-08-26)
The share gate from the roadmap, exactly scoped: an ACTION_SEND receiver
(text/plain + image/*), a found/not-found result UX with the threshold from
settings, and a typed pipeline. S3 (match → open book at passage) is next.

- **feature-share**: `ShareReceiverActivity` — launcher-less, exported,
  excludeFromRecents — with two SEND intent-filters. `ShareViewModel` reads
  the intent once (config changes re-deliver the verdict, never re-run the
  pipeline). Text goes straight to the index; images go [ImageDecoder]
  (bounds-first sampled decode, then ARGB) → core-ocr downscale → the real
  tess-two engine → the index.
- **ShareSnippetResolver** (pure JVM, host-tested): normalizes, awaits the app's
  async index rebuild (cold-start shares otherwise race an empty index) for up
  to 10 s, then queries with the settings threshold (AppSettings mirror, V1).
  Resolution is a sealed type: Found(book·chapter·passage·confidence) /
  NotFound(reason + closest-candidate hint, dimmed) / Failed(message).
- **core-locate additions**: `IndexRebuilder.readiness` (CompletableDeferred,
  completes after the first rebuild) and `TextIndex.best()` (threshold-free
  closest candidate — feeds the not-found hint; query() is now best() + gate).
Three on-device findings:
- tess-two/harness quirks consumed most of the verification budget: the Hilt
  instrumented-test application override (test-manifest HiltTestApplication /
  @CustomTestApplication) does not take effect in this AGP 9 project — the
  share pipeline test builds the real components manually instead; the hilt
  androidTest deps were removed again.
- `BitmapFactory.decodeStream(inJustDecodeBounds=true)` returns **null by
  design** — the decoder must not treat it as a read failure (fixed).
- JUnit4 rejects non-`void` test methods: a trailing Boolean expression in a
  runBlocking body (file.delete()) fails validation.
Verified: 9 host tests (text/image/threshold/gate/cold-start) in Docker +
real-engine runs green; on device `SharePipelineInstrumentedTest` OK (2 tests)
— text branch resolves the quote, image branch decodes a rendered screenshot,
OCRs it with legacy eng tessdata and resolves back to the passage. Host JVM
suite 240 green. The manifest carries the activity; the app builds.

## 38. S3: match → open at passage + "listen from here" (2026-08-26)
The resume wiring closes the S → T loop; the share feature is now actionable.

- **Share "Listen here"**: the found card carries a Listen action →
  [ShareOpenHandler] (app-owned navigation seam, feature-share never names
  MainActivity) → MainActivity with the [OpenTarget] extras contract
  (bookId/chapter/passage, owned by feature-share, pure fromExtras parse).
  MainActivity routes any such intent (onCreate, onNewIntent, process-death
  replay) to ReaderScreen with startAt = the passage; READER plays there via
  the existing ACTION_PLAY_POSITION and completes through the book.
- **Reader gesture** (ideas #2 → "listen from here"): tapping the passage
  text (re)starts playback at that passage — the single-passage reader makes
  the ideas.md long-press moot (tap is discoverable; deviation recorded).
  The docked path reuses Resume-style semantics.
- **Verified on the S22** (PlayPositionE2eTest, OK 1 test): ACTION_PLAY_POSITION
  1/0 on a 4-passage book lands `playing 1/0` first (not the book start),
  runs 1/0 → 1/1 → 1/2 → PlaybackCompleted. The service seam the share gate
  and the gesture both drive is measured; the UI glaze (button → activity
  route) is compile-verified + extras-contract host-tested (fromExtras
  round-trips, blank bookId rejected, absent chapter/passage → 0).
Consequences: S or T are now reachable from each other; the roadmap's
S-column is functionally complete (share + resume + gestures). Free-riders
noted: ReaderScreen gained a startAt param (default null — no behavior
change for normal opens); MainActivity consumes the extras exactly once
via compose state. Open: none for this slice.

## 39. S-debug: three manual-test regressions fixed (2026-08-26)
The user's manual smoke found three real defects — the very class V2 exists to
catch; all fixed with regression coverage:

1. **Epub import: "could not read container.xml"** — root cause found ON DEVICE
   with a real 24.8 MiB Gutenberg EPUB (staged via adb): Android's Expat-backed
   `DocumentBuilderFactory` **throws `UnsupportedOperationException` on
   `setXIncludeAware(false)` / `setExpandEntityReferences(false)`** (host
   Xerces accepts them) — every OPF/NCX parse died in "could not read
   content.opf" after the container itself failed or passed. Fixes: the two
   factory configs are now tolerance-guarded (doctypes are stripped up front,
   so entity/external expansion is unreachable either way); a single-quoted
   XML declaration is normalized (Gutenberg publishes `<?xml version='1.0'
   encoding='UTF-8'?>`); container.xml's full-path is extracted by regex (no
   XML parse on that path at all). Verified: RealEpubImportProbe OK on-device
   — 8 chapters / 2413 passages import, BookImporter lands Added.
2. **Settings → download voices crashes the app** — `StackOverflowError` from
   the private `operator fun Map.minus`/`plus` extensions in
   SettingsViewModel: they shadow the stdlib operators and recurse on
   themselves (line 193, confirmed in logcat twice). Deleted — the stdlib
   map operators serve. Regression: SettingsViewModelTest (fake transport +
   pinned descriptor) — download completes, clears progress, Ready; a
   corrupt payload surfaces "checksum mismatch" typed, no recursion.
3. **Theme radio doesn't reflect the change** — the settings screen polled
   `settings.reload()` on a 2 s loop, so the radio lagged (the global palette
   followed via a proper flow and changed instantly). AppSettings is now
   push-based: one `StateFlow<Snapshot>` updated on every write; the screen
   and MainActivity observe it — no polling anywhere. PlaybackService/Share
   hot paths read `state.value.*` (still non-suspending). Regression:
   setTheme is observed immediately (host VM test).
Also fixed along the way: `SettingsViewModel.setOcrLanguage` read a removed
field; AppSettings readers migrated (voice=`state.value.voice`, etc.).
Verification: 4 device tests green (RealEpubImportProbe, VoiceSelectionE2e
`voice=bm_george` ×2, PlayPositionE2e, PlaybackE2e); host JVM 240+; Docker
unit sets green including the 3 new VM regression tests. docs: none beyond
this entry; V2 remains the missing gate (CI wiring + running this growing
instrumented set as a job) — the user's question, answered: testing is NOT
done before v1 is done, and this smoke showed exactly why.

## 40. pt-BR spot check (2026-08-26, user request)
The pinned Kokoro pt-BR voices were never exercised; both sides now are
(core-tts test + instrumented test only — no main-code changes, the
voice→language map already routed `pf_`/`pm_` to pt-br):
- **Host** (PtBrVoiceHostTest, real model + espeak-ng pt-br G2P): `pf_dora`
  and `pm_alex` synthesize "O rato roeu a roupa do rei de Roma." into Audio
  (~24 kHz, sane duration, sentence anchors present for the read-along);
  a wrong-family name (`pf_dora_typo`) is rejected typed with "unknown voice".
- **Device** (PtVoiceE2eTest, S22): the pt-BR voice selected via settings
  plays a Portuguese book through the real service to COMPLETED —
  `voice=pf_dora` in the loop logs.
Verified the pt-br espeak-ng identifier resolves in the staged bundle
(phonemizer scan) through the successful synthesis itself. The post-v1
pt-BR translation DECORATOR (core-translate, NMT output-side) remains a
separate deferred slice — this test only covers the voice path, not it.

## 41. V2: CI wiring (2026-08-26)
The roadmap's V2 gate is in place up to the device: `.github/workflows/ci.yml`
with two lanes matching the repo's tooling split:
- **jvm-tests** (every push/PR): the pure-JVM modules — core-model/ebook/locate/
  tts/player/ocr — no Android SDK on the runner. Real-model pack-dependent
  tests (PtBrVoiceHostTest) skip via JUnit assumptions instead of failing on a
  clean cache (the on-device PtVoiceE2eTest covers the same path).
- **android-build** (every push/PR): `docker build -t localtts-android .` then
  the docker-build.sh gate — both APKs (app + androidTest) plus every Android
  module's unit tests (app, core-persistence, feature-settings/share/player/
  library). Instrumented tests stay on the connected S22 (the staging + per-
  class invocation workflow in build.md); CI compiles them.
- **assemble-on-tag**: after both lanes, `:app:assembleDebug` +
  `:app:assembleRelease` — the "assemble on tag" acceptance.
Docs updated with it: modules.md stale "planned" lines removed (feature-player/
reader/share now LIVE; app line rewritten; header reworded), roadmap carries
the #39/#40/#41 markers. Both lanes verified locally command-for-command:
host JVM 255 tests 0 failed; docker lane + release assemble BUILD SUCCESSFUL.
Remaining for V2: nothing in CI scope — what stays manual is device-side
instrumentation (no runner hardware), deliberately.

## 42. Offline chapter pre-generation: WorkManager job core over the tested cache (2026-08-26)
The roadmap's post-v1 T5 slice (decisions #29) lands as pure scheduling over
the #35 disk tier, plus its playback wiring:

- **core-player `OfflinePregen`**: spine-order walk over a book, skipping
  cache hits (the cache is the source of truth — a run resumes anywhere),
  with run budgets (passages/chapters/time) and two stops that prevent
  thrash: the cache's byte cap (free space below the last synthesized
  passage size — a put would only evict) and a consecutive-failure cap
  (isolated failures are counted and skipped; `Unavailable` stops at once —
  missing packs won't heal mid-run). Cooperative cancellation per passage;
  `onProgress` fires per passage.
- **Disk tier in the play path**: PlaybackService fast path is now queue →
  disk cache → synchronous synthesis; a first listen of any passage persists
  it (async IO put), so normal use fills the offline cache for free. Saved
  audio is the full pre-slice passage (speed-keyed like the queue: #35).
- **`PregenWorker`** (`@HiltWorker`, foreground dataSync with a progress
  notification): manual mode = one book — wall budget **unbounded from
  2026-08-27** (whole-book runs; ends when cached, saturated, or cancelled) —
  unique-name KEEP
  (a second tap is a no-op); overnight mode = 24h periodic, charging +
  battery-not-low, 3h wall budget, yields to an active playback session
  ([`PlaybackActive`]) and to WorkManager cancellation. Voice from settings,
  speed 1.0; cache keyed engine+voice+speed means other speeds synthesize on
  demand as always. WorkManager + androidx.hilt added to the catalog
  (work-runtime-ktx 2.10.1, androidx-hilt 1.2.0).
- **UI**: library-row "Pre-generate" action with live WorkManager progress
  (KEEP-deduplicated; flips to "Pre-gen again" after a success); the app
  schedules the overnight job once at start.
- Budget sizing: a Kokoro book fits a night (~60–90 min audio ≈ RTF 0.7
  → ~1–2 h CPU at 1.0×); CosyVoice3's fallback tier gets its ≈3 ch/night.
- Verification: 13 new core-player tests (49 total; order, resume, budgets,
  saturation, failure caps, cancellation, progress). Host JVM suite green;
  Docker lane green (both APKs + all Android unit tests incl.
  feature-player/library). **S22 device pass DONE (2026-08-26):** new
  `PregenE2eTest` runs the real manual worker end-to-end — tier filled
  (PCM + sample rate + anchors round-trip, all 4 keys), playback completes
  over the warm cache, and logcat proves the fast paths: `source=disk` and
  `source=pregen` served every passage, zero live synthesis. `PlaybackE2eTest`
  re-verified over the new loop. Findings logged as decisions #43.

## 43. Brand icon: owner's leaf+arcs trace, ink palette, vector-only packaging (2026-08-27)
The launcher icon pick: the owner's own SVG trace of the leaf + sound-arcs
concept (supersedes every procedural draft; the trace's organic S-midrib,
curled stem, and natural arc ends won). Locked with the brand: ink `#1B2430`
background, amber `#E8A33D` leaf/dot/inner arcs, teal `#1FA8C5` outer arc —
the source image's gold harmonized to amber, no wordmark (dies below 64px;
the name lives in the launcher label / store listing).
Packaging is adaptive-only (`minSdk = 26`): vector foreground scaled ×0.5 into
the 66dp safe zone (measured 60.1% of the 108dp layer), vector background,
Android-13 monochrome with alpha steps (leaf+dot 1.0, inner arcs 0.67, outer
arc 0.43). The trace's off-center dot (37px left of the fitted ring center)
was re-centered during review, then reverted at the owner's eye — the
as-traced dot ships. The arc's extra sliver gap at 285–290° kept — reads
organically. Canonical source `docs/assets/ayvu-icon-master.svg` (==
production geometry); res vectors are generated from it, not hand-edited.
Alternatives: pixel-copying the JPEG (AI-loose geometry — off-center arcs,
thick bands), legacy PNG density packs (unneeded at minSdk 26), wordmark
variant (illegible at launcher sizes).
Verified: docker-lane `:app:assembleDebug` green; `aapt dump badging` shows
`application: label='Ayvu' icon='res/mipmap-anydpi-v26/ic_launcher.xml'`.
## 44. Storage transparency on pre-generated audio (2026-08-27)
Owner idea-batch follow-ups to shipped pre-gen (#42) and settings (#36). The
pregen disk tier is real storage the user currently can't see or reclaim.

- **Pre-generate space estimate**: the library-row Pre-generate flow states the
  expected footprint before enqueuing, computed without synthesizing — bytes =
  24_000 Hz × 2 B × estimated duration (segmented text length → speaking time at
  the active voice/speed); exact for chapters already cached (the cache is the
  source of truth, #42).
- **Per-book audio usage + delete**: settings lists a per-book breakdown and the
  total; delete is one tap per book (settings row, mirrored on the library row
  next to "Pre-generate"). Exclusion: never evict passages queued or currently
  playing (the fast-path invariant) — cancel the book's queued work first.
  Eviction only, no migration: cache keys are engine+voice+speed, so a settings
  change invalidates naturally (#35).
- Same review, left in the ideas pool: auto-delete of listened passages (needs
  undo-ring semantics against `position_history`), habit-driven pre-generation
  (needs the post-v1 session log), translate-then-read language coverage beyond
  pt-BR (settle when `core-translate` starts).
- Consequences: estimates keep the user aware that one listened hour ≈ 170 MB
  (24 kHz 16-bit mono) on disk; per-book delete makes re-listen cache retention
  a user choice instead of an LRU-only policy.

## 45. Pinned debug keystore + device pass findings (2026-08-26)
The S22 device pass for #42 surfaced three things worth logging:

- **Debug signing is now pinned (`debug.keystore`, repo root, gitignored-exempt).**
  AGP's default `~/.android/debug.keystore` lives per-toolchain: every Docker
  container on a fresh machine recreates it, so each docker-built APK carried a
  new signature and `adb install -r` failed with
  INSTALL_FAILED_UPDATE_INCOMPATIBLE. A committed debug key (password
  android/android, debug-only, not a secret) makes debug builds signature-stable
  across hosts. Release signing stays out of the repo.
- **Hilt workers need explicit wiring — there is no auto-initializer.**
  androidx.hilt:hilt-work 1.2.0 ships only `HiltWorkerFactory` (+ a Hilt module);
  the app must implement `Configuration.Provider` (note: `workManagerConfiguration`
  is a Kotlin *property*, not `getWorkManagerConfiguration()` — the method form
  fails compilation on this metadata), and the default
  `WorkManagerInitializer` must be removed from the startup graph or it wins the
  once-only init with the reflection factory. `PregenManager` constructs its
  `WorkManager` lazily — a constructor-time call reads the provider before Hilt
  finishes injecting.
- **FGS: manifest type alone is not enough on this API-34 device.** Even with
  `android:foregroundServiceType="dataSync"` merged onto WorkManager's
  `SystemForegroundService` (PM records type=1 correctly), `setForeground` died
  with `InvalidForegroundServiceTypeException: type none` — implicit MANIFEST
  resolution is rejected. The fix: pass
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` explicitly in every
  `ForegroundInfo` (#42 doWork). Also needed for targetSdk 34+: the manifest
  merge of `SystemForegroundService` with the dataSync type.
- **Device e2e basics:** instrumented tests share the app's live Room file; a
  tearDown `deleteDatabase` unlinks it under the app's Hilt singleton and
  silently starves the worker in later tests of the same run — e2e tearDowns
  now keep the DB (PlaybackE2eTest + PregenE2eTest stop services and close
  their own instances only).

**SHIPPED (2026-08-27, #44):** `PregenSpaceEstimator` in core-player (cached
passages count exact on-disk bytes via the new `PcmPassageCache.sizeOf`;
uncached passages estimate at ~150–180 wpm English, speed-scaled;
`usageByBook()` sums the per-book subtrees). `PregenStorage` (feature-player)
is the façade — one-pass `estimateAll()` over the cached parses at the active
voice, per-book usage, and `deleteBook` = cancel the book's WorkManager work
FIRST, then delete the subtree (the fast-path invariant: a running worker
would re-write passages right after the delete; active playback is unaffected
— a disk miss falls back to synthesis). UI: the library row states the
footprint in the Pre-generate label (`Pre-generate (≈1.2 MB)`), shows live
usage + one-tap Delete, and refreshes the disk facts when a run settles;
settings gains an "Offline audio" section — per-book rows, total, one-tap
delete (`formatBytes` shared from feature-player). New tests: estimator math
(cached-exact vs formula, speed scaling, custom rates), `sizeOf`,
`usageByBook`. Host suite green; Docker lane green; visual verification on
the S22 pending reconnect (the rows are thin state + the tested core).

**#42 follow-up (2026-08-27, whole-book storage):** the disk-tier byte cap
was 256 MiB and the manual run 60 min — both too small to persist a whole
large book (a real ~5.3k-passage book needs ≈0.9 GiB ≈ 4.4 h at the measured
~20 passages/min). `PcmPassageCache.DEFAULT_MAX_BYTES` raised to 4 GiB
(≈24 h of audio; LRU still bounds growth; the S22 has 544 GB free) and
`MANUAL_BUDGET` is now unbounded time — a tap runs until the book is fully
cached, the tier saturates, or the job is cancelled. Overnight stays 3 h.
## 46. Opus storage spike — host profile + S22 MediaCodec finding (2026-08-27)
Host (`tools/opus_drift_spike.py`, libopus via ffmpeg, real Kokoro passages):
24 kbps 24 kHz mono round-trip is duration-exact (0.0 ms drift), worst
sentence-boundary residual +20 ms = one Opus frame — inside the engine's own
anchor jitter (31–37 ms) and the 250–670 ms sentence pauses. Storage ~11 MB/h
vs 170 MB/h PCM (15–16×). Anchors are seconds-based in the `.meta` sidecar, so
a 48 kHz decode would only need a ×2 sample map.

Device (S22 SM-S908U1, BP2A.250605.031.A3): the MediaCodec opus DECODER is
broken at the native level — every stream (device-encoded and canonical host
libopus) and every decoder (`c2.android.opus.decoder`, `OMX.google.opus.decoder`;
sync and async API) errors; the async path SIGSEGVs inside the codec's memcpy.
The encoder (`c2.android.opus.encoder`, 24 kHz native) emits size-plausible
payloads with a non-standard 83-byte csd-0 (OpusHead + 64 trailing bytes).
Consequence: on-device Opus via MediaCodec is not a dependency; an Opus cache
would need a bundled libopus (native seam) or stay PCM. The host drift numbers
stand as the codec truth; `OpusDriftInstrumentedTest` pins the encoder behavior
and stages evidence files (`files/opusdrift/{input.pcm,device.opus}`).

## 47. Download hardening: INTERNET permission + typed network failure (2026-08-27)
Two bugs made the settings pack download crash the app on-device:

- **Missing `android.permission.INTERNET`**: the app manifest never declared it,
  so every HTTP call failed at DNS with an unchecked `GaiException` (EPERM)
  that escaped the download coroutine and killed the process. Added to the app
  manifest (decision #7's explicit-download path is the app's only network use).
- **Unchecked runtime failures in `AndroidHttpTransport.open`**: DNS/resolve
  failures are not `IOException` on this Android — the transport now maps any
  non-IO, non-cancellation exception to a typed `IOException` so
  `PackDownloader` surfaces `IoError`, and the settings row shows
  `failed: network error: …` with Retry instead of crashing.
Verified on-device: offline Download no longer crashes (typed failure + Retry);
with network, the voices pack (28 MB) and the Kokoro model (310 MB) both
downloaded and staged ("ready") through the UI.

## 48. UI polish batch (2026-08-27, user review)
- **Library covers**: `EpubParser.coverOf` extracts the standard EPUB2/3 cover
  (content-hash book id → `files/covers/<bookId>` sidecar, written at import;
  no schema change) and the library row shows a 56×80 thumbnail with a
  title-initial fallback. TXT/MD/MOBI have no covers (placeholder).
- **Overflow menu**: the library row's Pre-generate (with space estimate) and
  Delete-offline-audio moved into a ⋮ `DropdownMenu`; progress stays inline.
- **Reader**: docked transport is icons + labels (prev/play-pause/next, speed
  cycle kept as text); Back/Bookmark/Undo are icons; a chapter selector
  (`Ch n/N` dropdown from `book.chapters`) jumps with `playPosition(ch, 0)`; a
  passage progress bar renders `offsetSeconds / passageDurationSeconds`
  (duration published from the last segment anchor); the view scrolls to the
  top on passage change (auto-advance already existed in the service loop).
- **Settings**: OCR languages moved to a sub-screen (back-navigating pane);
  the root row shows a chevron entry. Material icons extended added to
  feature-player for SkipNext/SkipPrevious/Bookmark/Pause/PlayArrow.
- **Import result consume-once**: `LibraryViewModel.consumeImportResult()`
  resets the batch summary after the snackbar/dialog, so revisiting the
  library no longer re-shows "Added N · Unchanged M".
Verified visually on the S22 (cover, overflow, reader chrome, settings panes)
plus the JVM suites (core-ebook cover tests; feature-library). Playback
progress/auto-advance require a book + packs on device; the service advance
path is covered by `PregenE2eTest` (decisions #45).
## 49. T3 device pass — CosyVoice3-0.5B int4 ONNX on the S22 (2026-08-27)
First full on-device run of the `spike-tts` harness (14 graphs, jiangzhuo9357
int4 export, `voices/sarah` prompt) on the S22 (SM-S908U1, SDK 36, 6 ORT
threads; models 3.7 GB staged ad-hoc — the repo still carries no URL for the
bundle, the snapshot uses HF `jiangzhuo9357/cosyvoice3-0.5b-onnx` + locally
derived `sarah16/24.wav`):

- **RTF**: 12.64 / 14.37 / 13.16 (mean ≈ **13.4**) for 10.1/8.9/13.1 s of
  audio — inside decision #21's 14.7–17.5 gate estimate, so the CosyVoice3
  fallback tier stays viable at the #42 overnight sizing (3 h wall ≈ **3.5+ ch
  per night** at this RTF). Stage breakdown: flow 92–121 s dominates (68–72%),
  llm 28–42 s, hift 6–10 s.
- **Memory**: VmHWM **2.27 GiB** peak native, total PSS 336 MiB — the
  per-stage session release keeps peak ~2.3 GiB on the 8 GiB device, well
  inside lmkd limits; a production player using the tier would budget the
  same way.
- **Thermal**: peak status -1 (no throttling trip across the ~7 min run),
  headroom 0. Prompt path 6324 ms; `spkNorm` 15.28 and mel mean −5.63 —
  host-comparable sanity (sokuji-verified melodics) for the pipeline.
- All runs finite, audio written (`out_run1–3.wav`); artifacts pulled to
  `/tmp/t3/`. Bundle provenance: HF snapshot + ffmpeg-derived 16/24 k prompt
  wavs; worth revisiting whether the repo should pin the exact URLs.
## 50. User-review batch — espeak-ng download, navigation, paging, progress, pre-gen budget (2026-08-27)
Seven small items from a live review pass on the S22 + the host:
- **espeak-ng is now a downloadable pack** (was: manual adb staging; the
  settings/reader kept telling the user to build it). Pinned descriptor
  `espeak-ng` under KokoroPacks — url = `moronigranja/local-tts-reader`
  release `espeak-ng-1.52.0`, zip of arm64 `libespeak-ng.so` +
  `espeak-ng-data`, 9,857,162 B, sha `6b2edca7…` (the staged lib's sha
  matches the #38 pin `734cc95a…`). Flows through the existing verified-pack
  machinery (decision #7) and `EspeakStager` (feature-player) extracts into
  `files/espeak/` — the layout KokoroRuntime already reads. Settings gains
  the pack row; the espeak status is now live (filesystem check) instead of
  an injected snapshot, so it flips to ready right after staging.
  `EspeakBundleStatus` + `EspeakModule` binding removed.
- **Front-matter stripping is run-based, not window-based** (book
  navigation): a contiguous leading run of front matter (Title Page,
  Copyright*, Dedication, Contents — at any spine depth) and a trailing back
  matter run are dropped; kept chapters are renumbered densely from 0. The
  test book (Impulse) had its TOC at spine index 4 — past the old 3-window —
  so the reader landed in the Contents chapter and "Next" marched through
  TOC lines, never reaching "1. Millie: The Underlying Problem".
  `BookLayout.next/previous` now skip zero-passage chapters and gain
  `first()`; a fresh start uses `firstPosition()` instead of a hard (0,0)
  `require` (which would have crashed on sparse layouts). Titles match
  furniture by containment ("Copyright Notice"), not equality.
- **Reader paging**: swipe (≥ 64 dp horizontal) or tap the left/right third
  of the passage pages forward/backward at passage grain; the middle tap
  still (re)starts at the current passage ("listen from here"). The system
  back gesture on the reader and settings returns to the library instead of
  exiting the app (`BackHandler` mirrors the top-bar arrows).
- **Library read/listened progress bar**: fraction from the resume rows over
  the cached passage counts (`PassageDao.chapterCounts()` +
  `ProgressDao.observeAll()`), shown as a bar + % when started
  (passage-granular, the player's resume unit).
- **Pre-generate budget overlay**: the library row's Pre-generate offers
  30 min / 1 h / 2 h / 3 h / whole book (each with the linear byte cost,
  ≈ 2.88 MB/min at the #44 estimate rate); `PregenWorker` takes a
  `budgetTimeMs` input, whole book stays the default. Delete-offline-audio
  only shows when usage > 0, and the label is always "Pre-generate" (the
  "Pre-gen again" distinction is gone).
- **Opus encoder forensics (#46 follow-up)**: regenerated device evidence on
  the S22 (`OpusDriftInstrumentedTest#opusRoundTripDriftOnDevice`,
  `files/opusdrift/{input.pcm,device.opus}`, payload 35,613 B, sha
  `5febd75a…`). Reference libopus (host 1.4) parses only 2 packets of the
  stream and refuses to decode the rest (150-byte leading `d8fffe` pattern +
  mis-sized packet headers; the decoder even SIGSEGVs on part of it). **The
  c2 opus encoder output is also non-conformant** — the #46 "encoder is
  size-plausible, maybe savable with a hand-written OpusHead" read is now
  falsified. MediaCodec is not a dependency for Opus in either direction on
  this device; any Opus cache would need bundled libopus for encode AND
  decode.
- Verified: the JVM unit suites across modules (ebook/player/tts/
  persistence/library/settings/app/share/ocr/locate/model) and the
  instrumented encoder test are green; the new APK installs.
- Verified on-device (S22, unlocked pass): the espeak-ng pack downloads,
  verifies, and auto-stages into `files/espeak/`; settings flips to
  "staged (lib + data)"; the engine opens and playback synthesizes
  (`loop: source=synthesized`); 0 FATAL. Library shows the read-progress %
  and "N MB offline"; the pre-generate dialog renders all five budget
  options with byte estimates; the reader pages on swipe/tap-zone taps and
  the system back returns to the library from settings and reader.
- **Device-pass finding A — Dagger File type collision (pre-existing).**
  `OcrModule.provideTessDataDir` returns an unqualified `File` (the
  tess-two data dir), so Hilt bound ANY bare `File` request to
  `files/tesseract`. `SettingsViewModel`'s injected `filesDir` silently
  resolved there: OCR staging would nest `tesseract/tesseract`, and the new
  espeak staging extracted into `files/tesseract/espeak/` (settings said
  "ready"; the engine, which reads `context.filesDir`, never saw it).
  Fixed by qualifying the app files dir (`@Named("app_files_dir")`
  provider in SettingsModule; VM param qualified). Staging roots are now
  absolute.
- **Device-pass finding B — ForegroundServiceDidNotStartInTimeException.**
  `startPlayback` returned early when the engine was unavailable
  (packs/espeak missing) without calling `startForeground`, so opening a
  book crashed ~10 s later. Fixed by entering the foreground FIRST in
  `onStartCommand`; early returns can no longer trip the timeout.
- **Self-heal**: a verified-but-unstaged espeak-ng pack (reinstall after
  download, or a failed extract) auto-stages when the settings screen
  opens — the pack can't be "already ready" and stuck behind a missing
  extract.
- Known follow-ups from the same pass: sub-1% read progress truncates to
  "0%", and there is still no remove-book-from-library action (re-importing
  an existing book hits "Unchanged" and keeps its old parse — the
  front-matter fix reaches books on the next fresh import).
## 51. Library gap pass — remove book, reader %, time-left, continue-list (2026-08-27)
Three items from the R7 ereader comparison (kindle et al.) + the #50
follow-ups, chosen by review:
- **Remove book from library**: menu → confirm → deletes the book's cached
  passages, progress row, bookmarks, undo ring, covers, offline-audio
  subtree (pre-gen cancelled first) and the search-index entry, and stops
  live playback (the service holds its own book reference). Re-importing the
  same file re-creates the content-hash row — which is how a manually
  re-parsed book picks up the #50 segmentation fix. `LibraryStore.delete`
  + the per-table deletes (progress/bookmarks/history/passages/book) run in
  one Room transaction; `InMemoryLibraryStore` mirrors it; `TextIndex.remove`
  already existed.
- **Reader footer: % + time-left**: the Ch·P·offset line now reads
  `Ch 4 · P 4 · 0.1% · ≈10h 41m left` — position fraction (passage-granular,
  same as the library progress bar) plus estimated remaining listening time at
  the current speed, computed by the new pure `BookProgress` helper
  (core-player; same chars-per-second model as PregenSpaceEstimator, so the
  estimate is consistent with the pre-gen sizing). Verified live on the S22.
- **Continue-list / recent-reads**: the library shows a "Continue listening"
  section (books with a resume row, most recent first, capped at 5) above the
  main import-ordered "Library" list — no duplicates (recent ids are filtered
  from the main list).
- Unit tests: `BookProgressTest` (fraction incl. current passage, offset
  handling, speed scaling, empty book). Full unit suite + assemble green;
  remove-book and continue-list verified by code-path + pending a live pass
  once the S22 is free (verified live: the reader footer).
- **Follow-up (same day): the reader stitches the chapter.** Instead of one
  passage per page with a scroll reset on every advance, the current
  chapter's passages render as one continuous scrollable surface
  (`PlaybackUiState.chapterPassages` — the service publishes the whole
  current chapter; the page-view gestures survive). The read-along follow
  animates only when the active passage actually leaves the viewport
  (24 dp slack) so browsing ahead of the narration never yanks; a middle
  tap starts playback at the passage under the finger; chapter changes
  return to the top. Verify note: Compose 1.12 removed
  `positionInParent()` — passage offsets now come from
  `localPositionOf(column, Offset.Zero)`. Verified live on the S22: six
  prose passages visible in one scroll, footer intact, 0 FATAL — replaced by
  real pagination in #52 (user review: page breaks at overflow, not scroll).
## 52. Reader pagination, speed, open-without-play, library play, bookmarks (2026-08-27)
User-review batch #2 — five items, all verified live on the S22:
- **Real pagination, no scroll**: the chapter's text flows and breaks
  exactly where it would overflow the viewport; a new chapter always starts
  on a fresh page. Lines are fixed-height (30 sp); pagination is measured
  with `TextMeasurer` over the joined chapter text, pages are contiguous
  line ranges sliced at `multiParagraph.getLineStart/End` (greedy wrap
  reproduces the slice identically). Playback turns the page only when the
  spoken passage leaves it. `TextPagination` (core-player) holds the page
  math, unit-tested.
- **Speed actually works** (was a no-op). `sliceForSpeed` never resampled —
  only offset-skipped with an inverted `× speed`. Speed is now
  `AudioTrack.setPlaybackRate(sampleRate × speed)` (hardware rate
  conversion; frames stay book-time), the offset skip is exactly
  `offsetSeconds × sampleRate`, and `liveOffsetSeconds` no longer divides by
  speed. Device pass also caught a regression: an earlier publish() edit had
  eaten `speed = state.speed` — the UI label stuck at 1.0× while audio
  cycled; restored (label now shows 1.5×, time-left ÷1.25/1.5).
- **Opening a book no longer auto-plays**: new `ACTION_OPEN` →
  `openBook()` — stops current audio, positions the machine at the resume
  point or the start (`openPosition()`/`present()`, no commit, phase/ring
  untouched), publishes the text, drops the foreground. `resumePlayer` gained
  a fresh-book fallback; `positioned` is true whenever a passage is shown
  (bookmark/skip were disabled at open until the fix).
- **Library play button**: each row gets a Play icon → `ACTION_PLAY`
  (resume audio without opening the reader). First layout stacked the icon
  under the ⋮ (two IconButtons in a Box) — wrapped in a Row; verified: tap →
  `loop: source=synthesized`.
- **Bookmarks**: the top-bar bookmark opens a menu — add at playhead + list
  all bookmarks (label or Ch·P) and jump via playPosition; `PlaybackUiState`
  gains `bookmarks` (fetched after machine setup and after each add).
- Verified live: pagination (page turns at overflow, chapter fresh page,
  no scroll), open shows the resume position with Play not Pause, bookmark
  add/list/jump plays, speed label + rate, library play, continue-list
  (#51); 0 FATAL; full unit suites green.
- **Notification branding (user review)**: the player notification now
  shows the book cover (`files/covers/<bookId>` decoded, downsampled to
  ≤512 px, cached per book) as MediaStyle album art, the app name is "Ayvu"
  (fallback title + "Ayvu playback" channel; the launcher label was already

  Ayvu), and the book title remains the content title. Verified on-device:
  `largeIcon=Bitmap`, title=Impulse, MediaStyle template.

## 53. EPUB import: XML-valid entities killed the parse (2026-08-27)
User reported "No More Mr Nice Guy" (from a real bundled epub) failing to
import on the device. Reproduced on host with the exact pipeline
(`BookImporter.import` → parse → segment → index): `ParseError(ParseError(
message=malformed content.opf))`, SAX fatal "The entity name must
immediately follow the '&'" at the OPF's `&amp;`.

Root cause: `parseXml` decoded ALL entities (`&amp;` → `&`) before handing
the document to the DOM parser (the 2026-08-26 HTML-entity pre-pass for
real-world OPFs). A legitimately-escaped `&amp;` in metadata text became a
bare `&` — invalid XML — and the parse died. Any OPF/NCX with `&` in a
title/creator/subject failed, regardless of parser (host or Android).
Books without `&` in metadata were unaffected, which is why earlier
imports passed.

Fix: the pre-parse pass now decodes ONLY what the XML parser cannot —
HTML-named extras (`&nbsp;` `&mdash;` …) become their characters and a
bare `&` is escaped to `&amp;`. XML-valid entities (`&amp;` `&lt;` `&quot;`
`&apos;` `&gt;`, numeric refs) stay for the parser, which resolves them
into `textContent`. `decodeEntities` (post-tag-strip chapter text, MOBI
NCX labels) is unchanged. Regression: `XmlPreprocessingTest` — "Love
&amp; Romance &mdash; R&amp;D" parses with correct textContent; bare "a &
b" survives. Verified: the real epub imports Added — title "No More Mr
Nice Guy", authors [Robert A. Glover], 3 chapters / 1809 passages; full
core-ebook JVM suite green (host) — and on the S22, the rebuilt APK +
the new `RealEpubImportProbe.niceGuyEntityEpubImportsOnDevice` case
(pp.epub + nmmng.epub staged) both pass: 2 tests OK.
