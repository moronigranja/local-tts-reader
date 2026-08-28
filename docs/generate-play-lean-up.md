# Generate/play subsystem lean-up proposal — local-tts-reader (Ayvu)

Decision-support document. Builds on `docs/generate-play-structure.md` (the 9-section overview, saved 2026-08-28); every claim below was re-verified against the current tree at HEAD (`1e128da`). Abbreviations: P = feature-player `.../playback/PlaybackService.kt`, KR = `.../playback/KokoroRuntime.kt`, W = `.../playback/PregenWorker.kt`, M = `.../playback/PregenManager.kt`, PA = `.../playback/PlaybackActive.kt`, PO = `.../playback/PassageOutput.kt`, RS = `.../ui/ReaderScreen.kt`, RV = `.../ui/ReaderViewModel.kt`, Q = core-player `.../player/pregen/PregenQueue.kt`, O = `.../player/pregen/OfflinePregen.kt`, PC = `.../player/pregen/PcmPassageCache.kt`, PT = `.../player/pregen/PregenTypes.kt`, EST = `.../player/pregen/PregenSpaceEstimator.kt`, H = core-player `.../player/PlaybackUiState.kt`, KE = core-tts `.../tts/kokoro/KokoroEngine.kt`, OS = core-tts `.../tts/kokoro/OrtKokoroSession.kt`. Claims not verifiable from code are marked [INFERENCE].

**Note (2026-08-28): the reading-speed selector is removed (decisions #71) —
playback is always 1.0×; the speed cache dimension is retained, so S5's engine-swap
prep mentions of it stay accurate.**

---

## Goals impact (2026-08-28)

`docs/generate-play-goals.md` (owner-pinned targets) changes this proposal.

**Adds two work items the proposal lacked:**

- **Instrumentation first.** L1 (tap < 300 ms), L2 (skip < 300 ms), L3 (post-death
  resume < 5 s) and GAP1 (≤ 50 ms) are unverifiable without probes. The goals'
  "measure now" outranks this doc's original sequencing: debug-only tap-to-audio /
  resume-to-audio / boundary-gap probes become the first slice, before PR-0.
- **G2 yield reversal (decided, not optional).** Manual/offline pregen suspends while
  playback is active; reverses decisions #42 and needs its own decisions.md entry +
  slice. This doc's S1/O3 "engine-admission rule as a flagged decision" is thereby
  **required**, and QW5e's "`PlaybackActive` becomes dead" is **wrong** — PA becomes
  the manual-mode yield signal too, more load-bearing, not dead.

**Refined items:**

- **QW2** → L3-gating: the dead notification button makes the < 5 s post-death target
  impossible. Acceptance: < 5 s via the resume probe, LOADING visible. Whether 5 s
  holds including the fresh-process 325 MB model open is measured, not assumed
  [INFERENCE].
- **QW4** → acceptance: steady-state gap ≤ 50 ms, full-cushion start kept, no
  post-stop runaway.
- **S3** → the notification rebuild trigger list drops `speed` (pinned 1.0×, #71);
  the per-second details path must keep `segments`/`activeSentenceIndex` — the G3
  auto-scroll feed.
- **QW1** → goal-load-bearing: its field-set guard test is the durable parity guard
  G3 (read-along on `segments`/`offsetSeconds`) and G1 (honest LOADING) rely on.

**Edge interactions to define when implementing:**

1. Post-stop fill vs worker yield: STOP clears `PlaybackActive` (P:520-521) while the
   post-stop fill still runs (P:955-968) — a yielding worker would resume mid-fill,
   recreating the contention the goal kills. Yield predicate should be "service
   active/recent", not just "playing".
2. S3's split must not move `segments`/`activeSentenceIndex` off the per-second
   publish path (G3).

**Execution order (per goals doc Sequencing note):** instrument → PR-0
(QW1 + QW2 + QW5a/b) → yield-reversal slice → PR-1..5 with the adjustments above.

---

## 1. Executive summary

The subsystem is not over-designed in concept — two writers (service prefill, WorkManager offline pregen) into one disk cache is the right shape — but it carries **three duplicated fill loops, one disabled feature arm (overnight) that still ships all its code, one permanent engine-failure latch, and a live copy-block regression** (`PlaybackUiState.chapters` is read by the reader's chapter selector and written nowhere since commit `3bc2057`). The single biggest in-scope cut is **folding `startPrefill` / `bufferForPlayback`'s wait / `startPostStopPrefill` into one parameterized fill job** and, once the owner confirms overnight stays disabled, **deleting the overnight arm** (`ensureOvernightScheduled` + the `MODE_OVERNIGHT` budget/yield/notification code + `PlaybackActive`) — roughly 200+ lines of orchestration and the only consumer of cross-stream yield complexity. The top quick win is **restoring `chapters` in `publish()`** (dead chapter selector, one line + a field-set guard test) — it is a member of the exact CR-8/CR-9 regression family the repo has burned twice. Everything load-bearing (single-writer commands, CR-2 teardown ordering, cache-bootstrap, one engine per process) stays untouched; nothing here requires the rewrite.

## 2. Load-bearing core — do not touch casually

| Item | Why it exists | What breaks if removed |
|---|---|---|
| `commandGeneration` + `launchCommand` + `active(generation)` before every publish/foreground/loop side effect (P:974-1010; CR-5/CR-7, decisions #62) | A stale load/pause must never publish state or drop the foreground after a newer command won — two device-observed races | A superseded command can overwrite newer state or kill the foreground mid-session; CR-5/CR-7 regress |
| CR-2 ordering: capture `liveOffsetSeconds` BEFORE releasing `PassageOutput`; exactly one final write via `captureAndStop`/`teardownWrite`; 5 s checkpoint gate (P:543-550, 653-659, 1012-1042) | Persisted resume row silently rewinds to the PCM-slice start if the playhead is captured after teardown | Progress loss on every STOP/kill |
| `publish()`'s copy block sets **every** `PlaybackUiState` field (P:677-733) — incl. `segments`, `offsetSeconds`, `chapterPassages`, **and `chapters`** (see QW1 — currently missing!) | CR-8/CR-9 were collateral drops inside this block | Repetition of the read-along/chapter-text regressions |
| One engine per process (decisions #25/#32/#67: `KokoroRuntime.engine()` opens exactly once, CPU default provider) | Two processes share only the disk cache; no cross-process engine state, no ORT session sharing across processes | Any second engine host re-opens the 325 MB graph and doubles CPU; D2 measured decision overturned without a new gate |
| Disk cache as the only cross-process coordination point; `PcmPassageCache` bootstraps on open, converges over-cap at construction, pregen gates on `bytesRemaining()==0` (PC:51-94, 110; CR-4, decisions #63) | Reopened cache must still replace audio near the cap; a put must not evict the entry just written | Frozen LRU / cache thrash; CR-4 regresses |
| Terminal truth: `PregenProgress.terminal` typed; worker maps `Unavailable`/`FailureCap`/null to `Result.failure` (O:34-70, 158-163; W:178-196; CR-1, decisions #60) | Partial engine-failure runs must not settle as success | False-success jobs; missing terminal silently success |

## 3. Quick wins (small, safe, high value)

### QW1 — Restore `chapters` in `publish()` (live CR-8/CR-9-family regression)
- **Problem**: `H:35-36 PlaybackUiState.chapters` is read by the reader's chapter selector (`enabled = state.bookId != null && state.chapters.isNotEmpty()`, RS:135), the "Ch X/Y" label (RS:137), the top-bar chapter title (RS:270) and the chapter menu (RS:140-143) — but **no production path writes it**. `publish()`'s copy block (P:681-698) sets 18 fields and not `chapters`; the only holder writers are `publish`/`reset`/failure/bookmarks copies. Commit `3bc2057` (CR-9 fix) replaced the line `chapters = book?.chapters?.map { it.title.orEmpty() }` with the `chapterPassages` block and never restored it (`git show 3bc2057`). Today the chapter menu button is permanently disabled and the top-bar chapter title is empty. The sibling overview's CR-8/CR-9 field list (`segments`, `offsetSeconds`, `chapterPassages`, `activeSentenceIndex`) missed `chapters` — extend the trap list.
- **Change**: restore `chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()` in `publish()`; add a Robolectric test (mirror `PlaybackServiceA57Test` shape) that runs a full publish and asserts the complete field set is populated — a reflector over `PlaybackUiState::class` comparing the copy result to defaults is the cheapest durable guard.
- **Cost**: minutes of code + ~0.5 d test. **Risk**: low; the guard test is what makes future copy-block edits safe. **Invariant impact**: none — repairs CR-8/CR-9 family; update the contract-home list in the playa convention to include `chapters`.

### QW2 — Notification/MediaSession actions carry the book id (post-death dead-end)
- **Problem**: notification actions build `PendingIntent.getService(..., Intent(...).setAction(intentAction), ...)` with **no `EXTRA_BOOK_ID`** (P:804-810), and `mediaCallback.onPlay()` calls `resumePlayer()` with no id (P:746-751). After process death the restarted service has `machine == null`; `resumePlayer` dead-ends at `val id = bookId ?: return` (P:334-342) — the notification is a dead button. Only UI-initiated commands carry the id (RV:50-76 `resume() → ACTION_RESUME + openedBookId`; PlayerAdapters.kt:37-41). The service is `START_NOT_STICKY` (P:164) so nothing else revives it.
- **Change**: in `buildNotification`, `putExtra(PlaybackService.EXTRA_BOOK_ID, book?.id)` on each action intent (P:804-810); change `mediaCallback.onPlay()` to `resumePlayer(PlaybackStateHolder.state.value.bookId)` — the holder survives in-process and the existing machine-less rebuild path (P:340-343 `startPlayback(id, explicit=false)`) does the rest.
- **Cost**: ~0.5 d incl. a device test (play, kill process, tap notification Play → audio resumes). **Risk**: low; resumes go through the existing CR-5-guarded command path. **Invariant impact**: none.
- **Note**: headset/MediaSession buttons after process death are a system limitation (no live session) — not fixable here; see Deferred.

### QW3 — KokoroRuntime engine-failure retry seam (permanent latch)
- **Problem**: `KR:36-59` latches `failure` permanently for the process — `failure?.let { return null }` is checked before and inside `synchronized`, and nothing ever clears it. A first play before the espeak-ng/model packs finish async staging (SettingsViewModel.kt:120-124 auto-stage) permanently reports "engine unavailable" until the process is restarted, even after the pack lands.
- **Change**: make `engine()` re-attempt open when the previous failure was prerequisite-missing (the `check(model.isFile)`/`espeak` guards, KR:45-53) and the files now exist, with a per-process retry cap (e.g. 3) so a genuinely corrupt model does not hot-loop on every play tap. Simplest correct shape: drop the early `failure?.let` fast-path, keep the synchronized re-check; if `open` succeeds, clear `failure`.
- **Cost**: ~0.5-1 d + host test (fail → files staged → `engine() != null`). **Risk**: low-medium — relaxes the "opened exactly once" wording (decisions #25/#32); needs a short decisions.md note (coordinate with `cacique`). **Invariant impact**: none to CR-1/CR-5; failure semantics of the worker (W:74-76) unchanged.

### QW4 — Fold the three fill loops into one fill job
- **Problem**: three owners of the same `q.ensure(...)` loop: `startPrefill` (P:895-909, 200 ms, live playhead, no deadline), `bufferForPlayback`'s pre-wait (P:920-945, 50 ms poll of `q.aheadSeconds` + its own `ensure` before sync-synthesizing), and `startPostStopPrefill` (P:955-969, 200 ms, fixed `from`, `POST_STOP_MAX_MS` wall clock, `stopSelf()`). `PregenQueue.ensure` is single-flight (`inFlight`+lock, Q:66-80, 111-113) so there is no corruption — but the lifecycle is triplicated and the post-stop arm already ran past its budget once on-device (HANDOFF §2b/2d: "fill done log never shown").
- **Change**: one `fillJob(from, followPlayhead: Boolean, deadlineMs: Long?, onDone: () -> Unit)`; prefill = follow, no deadline; post-stop = fixed, `POST_STOP_MAX_MS`, `onDone = stopSelf()`; `bufferForPlayback` keeps its bounded wait but drops its own `ensure` calls (the long-lived prefill job already ensures toward the same target).
- **Cost**: ~1 d incl. `PlaybackServiceCr2Test`/`PlaybackServiceA57Test` + S22 device pass (post-stop self-stop timing is device-observed). **Risk**: medium — this is the CR-2-adjacent path that produced the HANDOFF runaway; keep `captureAndStop`/`teardownWrite`/`finalStopJob` byte-identical. **Invariant impact**: touches CR-2-adjacent code; the merge must not move the capture point.

### QW5 — Dead code
- (a) `PregenQueue.clear()` (Q:120) — no callers. **Remove.**
- (b) `PlaybackService.playerJob` (P:88; cancelled at P:983-984) — never launched anywhere. **Remove.**
- (c) `KokoroEngine.close()`/`OrtKokoroSession.close()` (KE:150-152; OS:70-75) — no production callers; only tests/benchmarks close (grep: closes in spike-tts + E2E DB teardowns). The engine is process-lifetime by design; keep the API for tests, note "process-scoped, never closed in production" in KR docs.
- (d) **Overnight wiring**: `PregenManager.ensureOvernightScheduled()` (M:66-79) has **zero callers** since the app-start hook was removed (LocalTtsReaderApp; HANDOFF:46-56). BUT a previously-enqueued periodic job survives in WorkManager's DB and can still fire once after an upgrade — the only way to un-enqueue it is `workManager.cancelUniqueWork(PregenWorker.OVERNIGHT_NAME)` at startup. Add that one-liner; it is the deterministic fix for a possible recurring CPU spike. Full arm removal is S1b.
- (e) `PlaybackActive` (PA:9-19) — written at P:327/363/521/1013, read **only** by W:144/154 (overnight yield). Becomes dead the day S1b lands.
- **Cost**: minutes each; (d) is its own tiny slice with a `dumpsys jobscheduler` verification. **Risk**: near-zero.

## 4. Structural lean-ups (the real meat)

### S1 — Merge the two pregen paths (option (a))
**What actually differs** (measured, not assumed):

| | Service prefill (`PregenQueue`) | Offline pregen (`OfflinePregen` via `PregenWorker`) |
|---|---|---|
| Walk | playhead-following, contiguous, strictly after `from` (Q:61-112) | whole book, spine order, skips cache hits (O:106-173) |
| Budget | `lookahead` 60 passages / `lookaheadSeconds` 45 s, re-checked per ensure | `maxPassages`/`maxChapters`/`maxTimeMs` + saturation + failure cap (O:131-163) |
| Yield | none — cancelled by `stopEverything` like any job | overnight only, via `PlaybackActive` (W:144, 154) |
| Executor | service coroutine, dies with the service | WorkManager, foreground dataSync, progress WorkInfo + notification id 43 (W:80-164) |
| Terminal | none (no surface) | typed `PregenTerminal`, CR-1-mapped to Result (O:34-70; W:178-196) |

**Options**:
- **O1 prefill-as-primary**: migrate whole-book walking into the service job. Rejected — the service self-stops (`START_NOT_STICKY`, P:164; `stopSelf()` P:966) and has no progress/notification contract; WorkManager's persistence and KEEP-dedup are the library UI's backstop (M:44-58).
- **O2 offline-worker-as-primary**: drop the service prefill; play loop sources disk + sync synthesis. Rejected — destroys the measured 20 ms gapless boundary (device, HANDOFF:37-41: "5/5 pregen in steady state") and the buffer-before-start contract; WorkManager cannot feed a live 45 s lookahead at passage boundaries.
- **O3 (recommended) shared planner, thin executors**: extract the walk/budget/skip logic from `OfflinePregen.run` (O:106-173) into a pure `PregenPlanner` in core-player consumed by both `PregenQueue.ensure` (short horizon from a playhead) and `OfflinePregen.run` (book horizon from the start). Keep both executors (their lifecycles are Android-mandated), but they stop re-implementing the spine walk; add the optional engine-admission rule (pause manual worker while `PlaybackActive`) only as a flagged decision — today manual runs deliberately do NOT yield (decisions #42), and changing that is a decisions.md entry, not a code default.
- The leanest true reduction is **QW4 (one fill loop) + S1b (delete the overnight arm) + O3's planner**, which is why S1's real size is ~3-4 d, not a rewrite.

### S1b — Delete the overnight arm (worker becomes single-mode) [needs owner confirmation]
- **Problem**: overnight is disabled at app start, yet the full arm ships: `ensureOvernightScheduled` (M:66-79), `MODE_OVERNIGHT` budget + yield + notification variant (W:34-36, 68-69, 104-106, 144-154, 160-163), `OVERNIGHT_NAME`/`OVERNIGHT_BUDGET` constants (W:260, 269-270), and `PlaybackActive` (PA) — ~80-100 lines plus every `mode` branch in the worker.
- **Change**: remove the mode switch (worker keeps one mode: manual), remove PA + its four service call sites, remove `ensureOvernightScheduled`, keep the QW5d startup-cancel for legacy jobs. The overnight *design* lives on in decisions #42 if it ever returns.
- **Cost**: ~0.5-1 d + PregenWorkerTest churn. **Risk**: low (feature already inert); touches decisions #42's documented contract, so a decisions.md entry is required (coordinate with `cacique`). If the owner expects overnight back soon, skip and keep only QW5d.

### S2 — Single-player-service command model (option (b))
**Verdict: `commandGeneration` + `commandLock` + `stopEverything` is already the minimal correct core — keep it.** An actor-style single dispatcher would *look* leaner but loses cancellation-with-supersession: commands must be preemptible mid-body (a stale load must die when a newer command arrives), which is exactly what generation bump + cancel + re-check gives (CR-5/CR-7). `commandLock` (P:392-405, 423-436, 477-490) is not redundant: `stopEverything` cancels cooperatively, so two command bodies can still interleave at cancellation boundaries; the lock serializes machine mutations inside them. The only trims worth doing: (i) dedupe the repeated `if (!active(generation)) return@launchCommand` tail-guards into one small helper (cosmetic; ~8 call sites); (ii) **do not** add a second publish path anywhere — every publish stays inside `launchCommand` per decisions #66/CR-6. Cost: 0. Risk: touching the guard raises CR-5/CR-7 regression risk, hence: the dedupe is optional, not required.

### S3 — Publication surface (option (c)): split per-second details from snapshot rebuilds
- **Problem**: while settled (PLAYING/PAUSED/LOADING ∈ `SETTLED_PHASES`, P:1063) the 1 s ticker calls full `publish()` every second (P:661-674 → P:677-733), which rebuilds the 18-field state, resets MediaSession state (`PLAYBACK_POSITION_UNKNOWN` always, P:721) and **re-`notify`s notification id 42 every second** (P:732) — pure IPC/serialization churn; the notification text changes only on passage/phase/speed change, and the session never carries a position.
- **Change**: split into `publishDetails()` (StateFlow-only: `offsetSeconds`, `elapsedSeconds`, `readFraction`, `timeLeftSeconds` — everything the read-along/progress UI needs each second) and `publishSnapshot()` (full state + session + notification) invoked on structural change (phase, passage, speed, book, canUndo). Ticker while PLAYING calls details; ticker/commands call snapshot on change. One code path keeps field parity — the QW1 guard test enforces it.
- **Cost**: ~0.5-1 d + device check (read-along still advances, notification updates at passage change). **Risk**: low-medium — CR-7's publish-ordering tests must stay green; the split must not reorder publish vs startLoop. **Invariant impact**: none if the fields published on each path stay identical to today's net behavior. Verdict: whole-state publish is fine; a per-field diff engine would be over-engineering.

### S4 — Passage-output model (option (d)): keep static track per passage
**Verdict: keep.** Reasons, in the design's own terms: (i) the passage is the natural unit of cache (PC), anchors (decisions #31) and persistence (CR-2) — a streamed track would fight that grain; (ii) marker-based completion was already evaluated and rejected in the code's own docs (PO:13-18: "static tracks park the head at the end without a reliable marker on some devices"); (iii) speed via `setPlaybackRate` keeps frames book-time at any speed (PO:57-61, decisions #52) — streaming must re-apply rate per buffer push; (iv) per-passage cost is one `AudioTrack` + one ~2 MB write per boundary (45 s @ 24 kHz) — bounded, measured gapless at 20 ms (HANDOFF:37-41). Optional micro-lean: reuse one `AudioTrack` across passages when format matches (skip alloc churn); relax the 50 ms poll to ~100 ms with no observable loss [INFERENCE — completion detection delta only]. A streaming tier pays off only if the *cache* becomes streaming — deferred, and currently unmotivated. **Cost**: 0 (or ~0.5 d for the optional track-reuse). **Invariant impact**: none; CR-2's `positionSamples`-based capture stays.

### S5 — Engine-swap prep, not the swap (rate-aware playhead + engine-dimension key)
- **Problem**: `liveOffsetSeconds` divides by `KokoroEngine.SAMPLE_RATE` (P:736-743); `FRAME_MARGIN = 240 // 10 ms at 24 kHz` (P:1056); `EST:52 BYTES_PER_SECOND = KokoroEngine.SAMPLE_RATE * 2` (a kokoro import in **core-player**); `PregenKey` = bookId/voice/speed with **no engine dimension** (PT:15-25; path layout PC:11-22 is the delete/usage unit). These are the two documented engine-swap blockers the owner flagged.
- **Change**: (i) track the last played `sampleRateHz` (already known at `output.play(sliced, audio.sampleRateHz, …)`, P:605-613) and divide there; derive `FRAME_MARGIN` from rate (10 ms); (ii) add `engine` to `PregenKey` + path and parse legacy paths as `kokoro` (already planned, decisions #54). Not the CosyVoice swap itself.
- **Cost**: ~1.5-2 d + cache-format migration test + decisions.md note. **Risk**: medium — every key construction and `PregenKey.parse` site changes; `PregenE2eTest` pins 24 kHz (PregenE2eTest.kt:164). **Invariant impact**: none; the cache bootstrap (CR-4) already treats unparseable paths as artifacts to delete, so a migration must land key-format and parse together.

## 5. Rewrite option (scoped sketch)

**Shape of the lean rewrite**: `PlaybackCoordinator` (single writer; one input channel; owns supersession — the actor the current `commandGeneration` approximates), per-book `PlaybackSession` (machine + loop + queue + output), per-session state/MediaSession/notification ids, shared `PregenPlanner` (S1/O3), generic `EngineRuntime` replacing `KokoroRuntime`, one synthesis admission lock (or session-per-stream), `PregenKey(+engine)` + rate-aware playhead. Roughly: `PlaybackService`'s ~700 lines of command+loop+media+notification+persistence split into one coordinator (~150), one session (~200), one media/notification presenter (~150), plus the planner/runtime/key work above.

**Size**: ~10-15 focused days including re-deriving every CR-2/CR-5/CR-7/E2E test against the new model and 2-3 device passes on the S22 (the post-death notification and boundary-gap properties are only provable on device; the B6 e-ink lacks the espeak bundle for audio tests).

**Buys**: N-session readiness (removes the singleton pile-up — holder, notification id 42, binary `PlaybackActive`, one machine per book), enforced field parity (one publish path with a schema), a `PlaybackService` small enough to reason about, and a cleaner engine seam.

**Honest negatives — what it does NOT buy**: CPU/RTF headroom. One physical CPU, one engine: RTF 0.66-0.76 on the S22 (hard-facts) already consumes the budget; N concurrent streams contend regardless of architecture (overview §9). It does not improve synthesis quality, cache convergence, or the disk tier. And it re-introduces, from zero, the exact race surface the CR series took four days to close. **Recommendation: do the structural path (QW1-5 + S1-S5) first; re-evaluate the rewrite only if `PlaybackService` remains the bottleneck after S1/S3 land.**

## 6. Deferred (explicitly out of scope this round)
- **N parallel playback** — singleton pile-up by design (holder, MediaSession, notification id 42, binary `PlaybackActive`, one resume row/undo ring per book); overview §9 sizes it in days and calls it counter-productive on S22-class CPUs.
- **Engine swap itself** (CosyVoice) — decisions #54 plan stands; S5 only unblocks it.
- **Overnight scheduling redesign** — decisions #42; only QW5d (leftover-job cancel) and optionally S1b (arm removal) happen now.
- **Streaming audio / ExoPlayer / markers / cross-fade** — unmotivated at a measured 20 ms gap (S4).
- **Screen-off policy**: no wakelock exists anywhere; audio continues via the foreground service, and deep-sleep behavior of the 50 ms poll is unhandled [INFERENCE] — needs its own decision, not a lean-up.
- **Opus/space tier** (decisions #46 spike exists) — storage economics, not structure.
- **Post-death headset replay** — MediaSession dies with the process; no restart path exists for hardware buttons. QW2 fixes the notification path, which is the actionable one.

## 7. Recommended sequencing

**PR-0 (first slice, independently shippable; ~1-1.5 d)**: QW1 (`chapters` restore + field-set guard test) + QW2 (book id in notification/MediaSession actions) + QW5a/b (dead `playerJob`, `PregenQueue.clear`). This is the natural first PR: it repairs a visible regression, fixes the post-death dead-end, removes dead weight, and ships the guard that de-risks every later publish()/P-edit. Verify: `:core-player:test :core-tts:test` + feature-player Robolectric (`PlaybackServiceA57Test`, `PlaybackServiceCr2Test`, new guard test) + S22 device pass (chapter selector visible, top-bar title, kill-process → notification Play resumes). Device instrumented runs are the only true proof for the notification path — say so in the PR.
- **PR-1**: QW3 (engine retry seam) + decisions.md note.
- **PR-2**: QW4 (one fill job) + QW5d (overnight leftover cancel) — needs the S22 post-stop self-stop pass.
- **PR-3**: S3 (publish details/snapshot split).
- **PR-4**: S5 (rate-aware playhead + engine-dimension key) — land key-format and parse atomically.
- **PR-5**: S1 (shared planner) + S1b (overnight arm removal) — requires owner confirmation that overnight stays disabled; decisions.md entry.
- **Gate**: after PR-2/PR-3, reassess whether the S2 dedupe or the S4 track-reuse micro-lean is still worth it; only then consider the §5 rewrite.

**Ownership guard**: every item above that touches `publish()` (P:677-733), `stopEverything`/`launchCommand` (P:974-1010), CR-2 capture/teardown (P:543-550, 1012-1042), or worker terminal mapping (W:178-196) must re-run the CR-2/CR-5/CR-7 suites and the field-set guard test; anything changing decisions #42/#54 semantics (S1b, S3's publish split, QW3's once-semantics) needs a numbered decisions.md entry coordinated with `cacique`.
