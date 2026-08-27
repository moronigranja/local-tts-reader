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
| **"Ms." read as "M S" (spelled out)** | TTS narrates the honorific as two letters instead of "miz". The segmentation layer is abbreviation-safe ("Dr."/"e.g.") for sentence boundaries, but phonemization still spells the title — a G2P/normalization gap on the synthesis path, not segmentation. Impact: titles/honorifics misread in narration. | Open — user report 2026-08-27 | user report; ideas.md "TTS pronunciation replacements" (Librera) |
| ~~**CR-1: whole-book manual pre-generation is a false-success no-op**~~ | ~~Choosing “Whole book” opens the engine, skips every book before `OfflinePregen.run`, and reports successful work without adding audio. Finite 30 min–3 h budgets take a different path.~~ | Fixed — A1, 2026-08-27 (decisions #57; record below) | code review 2026-08-27; decisions #42/#50 |
| **CR-2: STOP and service teardown lose the live intra-passage playhead** | STOP releases `PassageOutput` before sampling it, so persistence rewinds to the offset at which the current buffer started rather than the actual playhead. | Open — **major progress-loss bug**; detailed repair record below | code review 2026-08-27; T4 progress contract |
| **CR-3: Room library state and `TextIndex` can diverge** | Index mutation happens before Room import/delete commits and startup rebuild is unsynchronized. Persistence failure or a cold-start race can leave a visible book unsearchable, or an indexed book absent from the library. | Open — **major cross-capability consistency bug**; detailed repair record below | code review 2026-08-27; P1/P2 import⇒index contract |
| **CR-4: PCM cache LRU state is lost across process restart** | Existing disk entries are absent from the in-memory eviction map. Near the 4 GiB cap, every newly synthesized passage can evict itself while old entries remain, effectively freezing cache replacement. | Open — **major storage/cache bug**; detailed repair record below | code review 2026-08-27; decisions #35/#42/#44 |
| **CR-5: asynchronous book commands can overwrite newer player state** | `ACTION_OPEN`/`ACTION_PLAY` load books in untracked coroutines. `stopEverything()` cannot cancel them; an older load may publish after a newer command or remove its foreground notification. | Open — **major player concurrency bug**; detailed repair record below | code review 2026-08-27; T4 single-writer contract |
| **CR-6: feature-module dependency rules are no longer enforced** | Feature modules depend directly on other feature modules and concrete Android/persistence implementations, while `feature-library` owns application-wide DI. `app` is not the effective composition root described by the architecture. | Open — **architectural debt with active correctness/test costs**; detailed repair record below | code review 2026-08-27; `architecture.md` §2 |
| **CR-7: pause/navigation during first-audio generation does not settle the session** | While first-play synthesis is in flight ("Generating…"), pressing pause (reader center control and media-session dispatch) left `dumpsys media_session` reporting `PLAYING(3)` with the notification still advertising "Pause"; ±30s presses re-synthesized instead of repositioning a stopped playhead. No transport action stopped the session mid-generation; only process teardown ended it. Evidence: `docs/prints/player-generating-after-play.png`, `player-generating-after-seek.png`, `player-pause-unsettled.png`, `notification-state-desync.png`. | Open — **major player state-agreement bug**; detailed repair record below | device run 2026-08-27; CR-5 single-writer contract |
| **Cold-launch main-thread block and first-audio latency on device** | At cold launch Choreographer skipped 124 frames (≈2 s main-thread block, 16:02:15 log). First play then stayed in "Generating…" with time-left static ("10:41:20 left") across a 12 s+ observation; every −30/+30 press re-ran synchronous synthesis. Memory while idle after the session: 1.69 GB PSS / 1.4 GB native heap (1.80 GB RSS). Evidence: `docs/prints/player-generating-after-play.png`, `player-generating-after-seek.png`. | Open — verify against D1/D2 baselines; fix under Phase D | device run 2026-08-27; decisions #53 |
| **Reader bottom line cropped under the shared player card** | On the reader, the last text line is clipped at the boundary with the bottom transport card — the visible slice ends mid-line ("…He") with no way to scroll the remainder above the card. Evidence: `docs/prints/reader-bottom-crop.png`, `reader-bottom-crop-2.png`. | Open — reader/card inset fix (Phase B3 step 2) | user report + device run 2026-08-27 |
| **Chapter menu doubles the ordinal ("1. 1. Millie…")** | `ReaderScreen` chapter dropdown prefixes `${index + 1}.` to chapter titles that already carry their own ordinal (Impulse navPoint labels "1. Millie: The Underlying Problem"), rendering "1. 1. Millie: …". Evidence: `docs/prints/reader-chapter-menu-doubled-ordinal.png`. | Open — cosmetic; fix in B3 reader chrome pass | code trace + device run 2026-08-27 |

## Serious code-review findings — detailed repair records (2026-08-27)

These findings were discovered by tracing production call paths and their existing
tests. They are not roadmap feature requests and are not duplicates of the known
limitations above. “Major” means the current implementation can silently violate a
shipped product contract, lose user-visible state, or make a completed operation lie
about its result. File/symbol references are preferred over line numbers so the record
survives nearby edits.

### CR-1 — ~~Whole-book manual pre-generation is a false-success no-op~~ (FIXED — A1, 2026-08-27)

**Severity/status:** Major; open; deterministic from the current control flow.

**Shipped contract:** The pre-generation picker offers 30 min, 1 h, 2 h, 3 h, and
“Whole book.” A whole-book manual run is unbounded by time and should stop only after
the book is cached, the disk tier saturates, or WorkManager cancels it (decisions
#42/#50; `PregenWorker.MANUAL_BUDGET`).

**Affected path:**

1. `LibraryScreen.PregenBudgetDialog` maps “Whole book” to `onPick(null)`.
2. `LibraryViewModel.pregenerate(bookId, null)` forwards the null budget.
3. `PregenManager.pregenerate` omits `KEY_BUDGET_TIME_MS` when the budget is null.
4. `PregenWorker.doWork` maps the absent input to `MANUAL_BUDGET`.
5. `MANUAL_BUDGET = PregenBudget()` correctly represents an unbounded run with
   `maxTimeMs == null`.
6. The worker then evaluates:

   ```kotlin
   val remaining = budget.maxTimeMs
       ?.minus(elapsed)
       ?.takeIf { it > 0 }
       ?: break
   ```

   Null means “unbounded” in `PregenBudget`, but this expression treats it as
   “deadline exhausted” and exits before constructing/running `OfflinePregen`.
7. `doWork` reaches `Result.success()` after the loop. WorkManager and the library UI
   therefore see a successful settled job; `refreshOffline()` still reports no new
   bytes.

**User impact:** The most prominent/highest-cost pre-generation choice silently does
nothing. The engine may still be opened before the loop, so the user can pay model-load
latency and memory cost before receiving a false success. Finite budget buttons are not
affected because they create a non-null `maxTimeMs`.

**Broader false-success mapping:** Even after the null-budget branch is fixed, the
worker currently ignores the `PregenProgress` returned by `OfflinePregen.run` and
unconditionally returns `Result.success()`. The runner can return early because the
engine produced `SynthesisOutcome.Unavailable` or hit the consecutive-failure cap, as
well as for legitimate completion/budget/saturation/yield reasons. Its return type
carries counts but not the terminal reason, so the Android adapter cannot currently
distinguish “completed/safely bounded” from “engine failed and generation stopped.” A
repair should close both false-success paths instead of fixing only the null Elvis.

**Why existing verification missed it:**

- `OfflinePregenTest` exercises the pure runner, where null budgets correctly mean
  unbounded. The defect is in the Android worker adapter before the runner starts.
- `PregenE2eTest.workerPregensTheBookThenPlaybackCompletesOverTheCache` supplies manual
  mode with no `KEY_BUDGET_TIME_MS`, exactly the broken case, but this is a physical-
  device instrumented test and is not executed by `testDebugUnitTest`/normal host CI.
  Compiling the test does not exercise the worker.

**Required repair properties:**

- Preserve the semantic distinction between an absent deadline and an expired finite
  deadline. A safe shape is: calculate nullable `remainingMs`; break only when it is
  non-null and `<= 0`; pass null through to `OfflinePregen` for an unbounded run.
- Do not replace null with a very large synthetic duration; overflow and clock math
  should remain unnecessary.
- Do not report `Result.success()` merely because the outer loop never started. A
  successful whole-book run must have reached one of the documented terminal
  conditions (complete or cache saturated); cancellation remains cancellation.
- Keep the 3 h overnight budget and finite manual budgets unchanged.
- Give `OfflinePregen.run` an explicit terminal reason/result (completed, budget
  exhausted, cache saturated, yielded to playback, unavailable, failure cap) or an
  equivalent unambiguous contract. Map failure terminals to WorkManager failure/retry
  deliberately; do not collapse all partial runs into success. Preserve progress counts
  for UI diagnostics.

**Regression coverage/acceptance:**

- Worker-level test: manual input without `KEY_BUDGET_TIME_MS` invokes synthesis and
  writes at least the first uncached passage.
- Worker-level test: a finite expired budget performs no synthesis; an unbounded budget
  is not classified as expired.
- Existing device `PregenE2eTest` must run and reach 100% with its current no-budget
  input.
- UI acceptance: choose “Whole book” on a book with zero cached bytes; WorkManager
  progress advances and per-book usage becomes non-zero before settlement.
- Failure-result test: repeated synthesis failures or `Unavailable` must not settle as
  an indistinguishable successful job; the WorkManager output should expose a useful
  error for the row/UI.

### CR-2 — STOP and service teardown lose the live intra-passage playhead

**Severity/status:** Major; open; deterministic from `PassageOutput` semantics.

**Shipped contract:** Reading/listening progress is single-source and persists the live
book-time playhead. Pause, stop, focus loss, service destruction, and relaunch must not
move the user backwards within a passage (roadmap T4; `PlayerStateMachine.stop`).

**Affected path:**

- `PlaybackService.liveOffsetSeconds()` calculates
  `baselineOffset + output.positionSamples / sampleRate`.
- `AudioTrackPassageOutput.stop()` releases the track; afterward
  `positionSamples == 0` by contract.
- `PlaybackService.stopPlayer()` currently calls `stopEverything()` first. That method
  calls `output.stop()`. Only afterward does the launched coroutine call
  `machine.stop(liveOffsetSeconds())`.
- `PlaybackService.onDestroy()` repeats the same order inside `runBlocking`:
  `stopEverything()` then `machine.stop(liveOffsetSeconds())`.

The final write therefore uses `baselineOffset`, the offset from which the current PCM
slice started, not the physical playhead at STOP. A passage started at 0 rewinds to its
beginning. A passage resumed at 30 s and stopped at 42 s rewinds to approximately 30 s.

There is an additional lifecycle race: `stopPlayer()` launches an asynchronous store
write and immediately calls `stopSelf()`. `onDestroy()` can run before that coroutine,
perform its own baseline write, and then cancel the service scope. There should be one
authoritative final write, not two competing teardown paths.

**Abrupt process-death gap:** `onDestroy()` is not guaranteed when Android kills the
process. The 1 s `ticker()` publishes a live UI offset but does not call
`PlayerStateMachine.notePlaybackOffset`; the core method's contract says the edge will
call it “throttled during playback,” but the service currently calls it only around
specific commands. Between passage transitions, Room can therefore still contain the
buffer's starting offset. Abrupt process death loses the same current-passage progress
even after the STOP ordering is repaired. This belongs in the same issue because both
failures come from lacking one explicit live-playhead checkpoint policy.

**Reachability/user impact:**

- MediaSession exposes `ACTION_STOP`; Bluetooth/Auto/media controls can invoke it.
- The notification includes an explicit Stop action.
- Android/OEM destruction of the foreground service reaches `onDestroy()`.
- Progress loss is bounded by the current passage/slice, but passages are intentionally
  paragraph-sized and can be long; repeated stop/resume cycles repeatedly replay text.

**Why existing verification missed it:**

- `PlayerStateMachineTest.stop at the playhead writes before going idle` passes an
  already-captured `offsetSeconds = 2.0` directly. It proves the core machine honors a
  supplied playhead; it does not test the service capturing that playhead.
- Playback E2E covers natural completion, not STOP mid-buffer or service teardown.

**Required repair properties:**

- Capture `val finalOffset = liveOffsetSeconds()` before any call that pauses, flushes,
  releases, or replaces `PassageOutput`.
- Feed that captured value to exactly one final `machine.stop(finalOffset)` operation.
- Make service shutdown wait for the final transactional store write without allowing
  `onDestroy()` to overwrite it with a stale offset. Avoid indefinite main-thread
  blocking; a bounded structured teardown job is preferable to duplicated writes.
- Add a throttled persistence checkpoint while `PlayerPhase == PLAYING` so abrupt
  process death loses at most the documented checkpoint interval. Do not write every UI
  tick by accident; choose and test an explicit interval, and force a final checkpoint
  on every graceful phase exit.
- Preserve book-time semantics at all speeds; do not multiply/divide the captured
  offset by speed because `positionSamples` is already book-time.

**Regression coverage/acceptance:**

- Service-edge test with a fake `PassageOutput`: baseline 10 s + 5 s played, STOP must
  persist 15 s even though fake `stop()` resets its head to zero.
- Repeat for `onDestroy()` without an explicit STOP.
- Abrupt-death test: play beyond one checkpoint without reaching the passage end, kill
  the app process without a graceful service callback, relaunch, and verify progress is
  no older than one checkpoint interval.
- Device test: stop midway through a long passage, kill/reopen the app, and verify the
  resumed sentence/playhead is within the existing timing tolerance rather than at the
  passage/slice start.
- Verify pause, focus loss, noisy-route pause, speed change, and natural completion
  retain their current behavior.

### CR-3 — Room library state and `TextIndex` can diverge

**Severity/status:** Major; open; architectural correctness issue with multiple concrete
failure interleavings.

**Shipped contract:** Room is durable library truth; `TextIndex` is a derived in-memory
search accelerator. Every durably imported book must be indexed, every deleted book
must be absent, failed imports must not mutate the index, and launch-time rebuild must
be safe when racing an import (architecture §3/§4/§6; decisions #13/#22).

**Current ownership split:**

- `BookImporter.import` uses `TextIndex.contains(bookId)` as its duplicate check.
- After parse/segmentation, `BookImporter.import` executes `index.add(segmented)` and
  returns `ImportOutcome.Added`.
- Only later, in `LibraryViewModel.buildSummary`, does
  `repository.add(outcome.entry)` commit the book and cached passages to Room.
- Startup independently runs
  `indexRebuilder.rebuild(libraryStore.cachedBooks())` on `appScope`.
- Removal executes `index.remove(bookId)` and deletes offline audio before
  `repository.delete(bookId)` commits the durable deletion.

**Failure A — persistence failure poisons retry:**

1. Parse succeeds and `BookImporter` adds ID X to `TextIndex`.
2. Room `repository.add(X)` throws (disk full, SQLite failure, cancellation, or process
   interruption).
3. The ViewModel coroutine exits without producing a typed failed outcome; UI can
   remain on its last importing state. The index still contains X, but the library does
   not.
4. Retry hashes the same file, sees `index.contains(X)`, returns `Unchanged`, and never
   retries Room persistence.

The user cannot durably add that file again in the same process without an index purge.

**Failure B — cold-start rebuild purges a successful concurrent import:**

1. `LocalTtsReaderApp` obtains cached Room snapshot S before new book X is persisted.
2. The import adds X to the index and persists it to Room.
3. `IndexRebuilder.rebuild(S)` computes `cachedIds` from stale S and removes every
   indexed ID absent from S, including X.
4. Room/library UI contains X, but share-and-identify cannot match it until a later
   process rebuild or explicit re-index.

All individual `TextIndex` methods are synchronized, but synchronization of individual
map operations does not make this multi-component sequence atomic.

**Failure C — deletion failure creates the inverse mismatch:**

1. `LibraryViewModel.removeBook` removes X from `TextIndex` and may delete regenerated
   audio.
2. `repository.delete(X)` fails.
3. Room and the library UI still contain X, but matching is disabled and expensive
   derived audio may already be gone.

**Why existing verification missed it:**

- `BookImporterTest` validates parse failures before `index.add`; it has no persistence
  dependency and cannot model a Room failure after indexing.
- `LibraryViewModelTest` uses an always-successful `InMemoryLibraryStore`.
- `IndexRebuilderTest` tests sequential snapshots (populate, replace, clear), not a
  barrier-controlled stale snapshot racing an import.
- The “concurrent import safe” claim is documented but not represented by a concurrent
  contract test.

**Required repair properties:**

- Treat Room as the duplicate/idempotency source of truth. `TextIndex` membership must
  not decide whether durable persistence work is necessary.
- Establish one import orchestration boundary with this order: parse/segment without
  externally visible mutation; commit book+passages transactionally; then update the
  derived index. If post-commit index update is interrupted, a rebuild must recover it.
- Serialize rebuild reconciliation with import/delete index publication, or use a
  generation/snapshot protocol that prevents a stale rebuild from purging IDs committed
  after its snapshot.
- Delete durable state first, then remove derived index/cache state, or provide a
  compensating rebuild when durable deletion fails.
- Convert storage failures into typed import/remove UI results; do not leave the
  ViewModel in `Importing` or count an uncommitted entry as `added`.
- Preserve content-hash identity and the requirement that share queries never parse the
  source file.

**Regression coverage/acceptance:**

- Failing-store test: first add throws after parsing; retry with the same bytes must
  persist and index exactly one book.
- Barrier-controlled race test: rebuild captures S, import X commits, rebuild completes;
  final Room IDs and index IDs must be equal and include X.
- Delete-failure test: failed durable deletion must not leave a surviving Room book
  missing from the index.
- Property/invariant assertion after every tested import/delete/rebuild interleaving:
  `index.bookIds() == persistedBookIds` once operations settle.
- Share acceptance: immediately after a cold-start import, a snippet from the new book
  resolves without relaunch.

### CR-4 — PCM cache LRU state is lost across process restart

**Severity/status:** Major; open; deterministic once a reopened cache approaches its
4 GiB cap.

**Shipped contract:** `PcmPassageCache` is a process-independent disk tier with an LRU
byte cap. Old regenerable audio should be evicted so newly requested audio can enter;
relaunch must not disable replacement (decisions #35/#42/#44).

**Current implementation:**

- Disk usage is discovered by walking `root`.
- Eviction candidates exist only in the in-memory access-ordered `recency` map.
- `recency` starts empty for every `PcmPassageCache` instance and is populated only by
  `put` or successful `get` during that process.
- Construction does not scan existing PCM paths into `recency`, and `contains` does not
  refresh recency.
- `evictLocked` stops when `recency.keys.firstOrNull()` returns null, even if actual disk
  usage still exceeds `maxBytes`.

**Failure sequence:**

1. Process A fills the disk tier near the 4 GiB cap and exits.
2. Process B constructs a new cache over the same root; old files count toward
   `totalBytesLocked()`, but none are eviction candidates.
3. Playback synthesizes and `put`s new key N, temporarily exceeding the cap.
4. N is the only key in `recency`, so eviction deletes N and its metadata.
5. Old files remain. Repeated new passages repeat the same self-eviction. If old files
   are already over cap, eviction deletes N, finds no next candidate, and exits still
   over cap.

The cache can therefore become effectively frozen across relaunch: old audio remains,
new listening repeatedly pays synthesis cost, and “normal use fills the cache” is no
longer true. Manual per-book deletion is the only reliable escape.

**Related integrity gap:** `contains` checks only that the PCM file exists. An orphan or
invalid `.meta` sidecar makes `get` fail but still tells `OfflinePregen` to skip the
passage. Reopen/bootstrap is the natural place to remove invalid/tmp pairs rather than
preserve permanent false hits.

**Why existing verification missed it:** Both LRU tests create one cache instance, put
all entries, and evict without reopening. No test constructs a second
`PcmPassageCache` over a populated root.

**Required repair properties:**

- Bootstrap every valid on-disk entry into an eviction structure before enforcing the
  cap, or persist the eviction index/access sequence alongside the cache.
- Since filesystem timestamps were deliberately rejected as reliable LRU truth, decide
  explicitly between:
  - persisted logical access order (true cross-process LRU), or
  - deterministic approximate startup order that still guarantees the hard byte cap.
- The hard invariant is more important than perfect recency: after a successful put,
  `totalBytes() <= maxBytes` unless one individual entry itself exceeds the cap, which
  must have an explicit policy.
- Startup/bootstrap should delete stale `.tmp`, PCM-without-valid-meta, and
  meta-without-PCM artifacts, or at least exclude them from `contains` and recency.
- Keep all public operations thread-safe and preserve atomic reader visibility.

**Regression coverage/acceptance:**

- Reopen test: instance A writes two entries, instance B opens the same root and writes
  a third over a two-entry cap; an old entry, not the new one, is evicted.
- Pre-populated-over-cap test: opening/first mutation converges below the cap.
- Invalid-pair test: PCM without valid metadata is not considered cached and can be
  regenerated.
- Device acceptance: fill a small test cap, force-stop/relaunch, play an uncached
  passage, and confirm it remains on disk while an older entry is reclaimed.

### CR-5 — Asynchronous book commands can overwrite newer player state

**Severity/status:** Major; open; race in the player control plane.

**Shipped contract:** `PlaybackService` is the single writer for active book/player
state. A newer user command must supersede an older one; stale loops or loads must never
advance/publish after navigation moved elsewhere (T4 and decisions #55).

**Current implementation:**

- `openBook`, `openChapter`, and `startPlayback` call `scope.launch` for settings/Room
  work and later assign shared `book`, `machine`, `queue`, bookmarks, foreground state,
  and `PlaybackStateHolder`.
- The service declares `playerJob` and `stopEverything()` cancels it, but no production
  path assigns any launched load job to `playerJob`.
- `commandLock` serializes seek/skip/speed/undo mutations only. It does not serialize
  book open/play loads.
- Cancellation of `loopJob` fixes stale audio loops after transport changes but does not
  cancel pending control-plane loads.

**Representative race:**

1. `ACTION_OPEN(A)` stops current work and launches load job A.
2. Before A finishes `settings.reload()`/`cachedBooks()`, `ACTION_PLAY(B)` calls
   `stopEverything()` and launches load job B. Job A is not tracked, so it survives.
3. B finishes first, assigns machine/book B, enters foreground, and starts its loop.
4. A finishes later, assigns machine/book A and publishes it. Its open path can also
   call `stopForeground(...REMOVE)`, removing B’s required playback notification.

Possible outcomes include the wrong book appearing/playing, an audio loop operating on
shared state replaced by another job, or foreground-service state no longer matching
active playback. Similar races exist between repeated card taps, reader open plus a
quick middle-tap play, share-target playback, and chapter opens.

The race is not limited to selecting books. `pausePlayer()` stops output and launches
`active.pause(live)` asynchronously without the command lock. A Resume intent arriving
before that coroutine updates the phase can still observe `PLAYING`/`LOADING` and return
as a no-op; the delayed pause then lands, leaving playback paused after the user asked
to resume. Navigation coroutines also capture `active` before acquiring `commandLock`,
so a concurrent book switch can let a queued command mutate an obsolete machine and
then call `startLoop()` against whichever global machine won the race.

**Why existing verification missed it:**

- Existing player tests target the pure state machine and sequential service scenarios.
- Rapid-tap verification in decision #55 covers transport commands after a machine is
  loaded; it does not delay/reorder two book-loading commands.
- No test asserts that `playerJob` owns the launched loading coroutine or injects a
  delayed library load to force out-of-order completion.

**Required repair properties:**

- Restore a single-writer command model for all control-plane transitions, not only
  transport. Preferred boring design: one service command actor/queue processes intents
  in order and owns `book`, `machine`, `queue`, focus, loop, and foreground state.
- A smaller repair may track a dedicated load job and cancel/join it before starting a
  superseding load, but cancellation alone needs a monotonically increasing generation
  check before every publish/foreground side effect in case the underlying operation
  reaches a non-cancellable section.
- Keep the long-running synthesis/play loop outside the command lock/actor critical
  section; commands must remain responsive enough to cancel it.
- Separate load/control job ownership from `loopJob`, `tickerJob`, `pregenJob`, and disk
  persist jobs. An unused `playerJob` field is not protection.
- A stale command must never call `publish`, `startForeground`, or `stopForeground`.

**Regression coverage/acceptance:**

- Deterministic service test with a fake/delayed library store: hold load A, complete
  load B, then release A; final state and active loop must remain B.
- Repeat for OPEN(A)→PLAY(B), PLAY(A)→PLAY(B), and OPEN(A)→STOP.
- Assert only the winning generation can update `PlaybackStateHolder` and foreground
  ownership.
- Device stress: rapidly alternate two library-row Play actions and open the reader;
  title, audio, notification, persisted progress, and controls must all refer to the
  final selected book.

### CR-6 — Feature-module dependency rules are no longer enforced

**Severity/status:** Architectural; open; already increasing correctness and test risk.

**Documented invariant:** `architecture.md` §2 says feature modules never depend on one
another and `app` wires them. `app` is described as the Hilt composition root.

**Actual Gradle edges:**

- `feature-library -> feature-player`
- `feature-settings -> feature-player`
- `feature-settings -> feature-ocr`
- `feature-share -> feature-ocr`
- `feature-share -> feature-library`

These are direct `implementation(project(":feature-*"))` dependencies, not test-only
edges. `feature-share` depends on `feature-library` primarily to discover
`ImportModule`/`PersistenceModule` singletons. `feature-library.PersistenceModule`
constructs the application-wide Room database and binds library/player/settings stores.

**Concrete leakage caused by the graph:**

- `LibraryViewModel` directly imports `PlaybackService`, `PlaybackStateHolder`,
  `PregenManager`, `PregenStorage`, Room DAOs/entities, `TextIndex`, WorkManager types,
  filesystem cover storage, and Android `Context`.
- `SettingsViewModel` imports player-owned pre-generation storage and espeak staging.
- `PlaybackService`, `PregenWorker`, and `PregenStorage` depend on concrete
  `RoomLibraryStore` rather than the `LibraryStore` contract because the existing
  contract does not expose the cached-book query they need.
- Unit-test constructors use nullable production dependencies with defaults to avoid
  assembling the real feature graph. This creates silent no-op branches that exist only
  because boundaries are not injectable at the correct level.

**Why this is serious rather than cosmetic modularity:**

- A share receiver transitively pulls in unrelated library/player/WorkManager wiring to
  obtain singleton identity. The feature cannot be composed or tested independently.
- Application-scoped ownership is hidden in `feature-library`; replacing the library
  UI can accidentally remove database/player/settings bindings.
- Post-v1 backup/stats work both cross persistence, library, settings, and player. With
  the current graph, each addition is likely to create more cross-feature edges or a
  real cycle.
- Contract tests cannot substitute focused fakes cleanly, contributing directly to
  CR-3 and CR-5 coverage gaps.
- The code and architecture document disagree, so future work cannot reliably infer
  which dependency rule is authoritative.

**Required repair properties:**

- Move application-wide Hilt bindings (Room database, shared stores, index/rebuilder,
  Android adapters) to `app` or an explicitly named infrastructure/composition module.
  Do not keep them in an arbitrary UI feature.
- Put narrow interfaces at the lowest stable owner:
  - playback commands/state source in `core-player` or a small player API surface;
  - cached-library reads in `core-model`/persistence contract rather than concrete
    `RoomLibraryStore`;
  - offline-audio management behind a core-player contract;
  - OCR implementation binding in the composition root, while share depends only on
    `OcrEngine`.
- Feature modules should depend on core contracts and Android UI libraries, never on
  another feature’s implementation.
- Replace nullable “Hilt supplies it, tests omit it” dependencies with required
  contracts and explicit fakes. Optional behavior should be represented by an explicit
  optional contract, not constructor defaults that silently disable production paths.
- Preserve one singleton `TextIndex`, database, and runtime through app-level bindings;
  removing feature edges must not create duplicate instances.

**Suggested migration order:**

1. Move existing Hilt provider modules to the composition root without changing
   behavior.
2. Extract the minimum contracts needed by library/settings/share; implement adapters in
   player/OCR/persistence modules.
3. Rewire each feature to contracts and remove direct feature project dependencies.
4. Move cross-feature navigation/orchestration (`Intent` commands, open-target handling)
   to app-owned adapters.
5. Add a build check that rejects `feature-* -> feature-*` project edges, making the
   documented rule executable.

**Regression coverage/acceptance:**

- Gradle dependency graph contains no direct feature-to-feature edges.
- Each feature unit-test target can compile against core contracts/fakes without pulling
  another feature implementation.
- Hilt app build proves exactly one database, `TextIndex`, `IndexRebuilder`, player
  runtime, and settings mirror.
- Existing import, playback, settings, OCR/share, and pre-generation device scenarios
  remain behaviorally unchanged after the cutover.

### CR-7 — Pause and navigation during first-audio generation do not settle the session

**Severity/status:** Major; open; device-observed state disagreement in the player
control plane.

**Shipped contract:** UI, `MediaSession`, and the notification must report one player
state. A pause command during generation (`LOADING`) must cancel the in-flight
synthesis and publish `PAUSED`; a seek command while paused repositions without
resuming audio (T4; decisions #53/#55).

**Device observation (S22, debug build, 2026-08-27):**

1. Play pressed from reader P1 → mini-card shows "Generating…".
2. +30s pressed → still "Generating…"; `dumpsys media_session` reports
   `state=PLAYING(3)`.
3. Center transport pressed (pause path: `PlayerCard` → `commands.pause()` →
   `ACTION_PAUSE`), then `cmd media_session dispatch pause` → subsequent
   `dumpsys media_session` still `PLAYING(3)`; the notification is still built with
   the Pause action.
4. −30s from that state re-synthesized ("Generating…", "10:41:20 left" unchanged)
   instead of repositioning a stopped playhead.
5. Only process teardown (force-stop) ended the session.

**Current implementation (relevant facts):**

- `PlayerCard` renders a spinner while `loading`, but the center box stays clickable
  (`if (playing) commands.pause() else commands.resume()`) — no visual confirmation
  the command landed.
- `pausePlayer(reason)` runs `stopEverything()` then
  `scope.launch { active.pause(live); publish() }` on `Dispatchers.Default`
  (`scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`), outside
  `commandLock`.
- `startLoop()` synthesizes synchronously in its loop (the cold/jumped-passage
  fallback `runtime.engine()?.synthesize(...)`) and calls `publish()` after each
  iteration; `publish()` writes MediaSession state from `machine.state.phase` and
  posts the notification.
- `seekBy`/`navigate` also run outside the pause path: they `stopEverything()` and
  launch a `commandLock`-serialized mutation, then `startLoop()` — a seek pressed
  while generation is in flight replaces the loop with a new synchronous synthesis
  from the new position.

**Open trace:** whether the dropped pause is (a) a superseded `publish()` from the
finishing loop overwriting the paused state (the CR-5 unpublish class), or (b) the
pause landing while the loop's synchronous synthesize occupied the Default dispatcher
and a queued seek re-entered `LOADING` — plus the missing serialization between
`pausePlayer` and the loop epoch (`commandLock` covers seek/skip/speed/undo, not
pause).

**Required repair properties:**

- Pause during `LOADING` cancels the in-flight synthesis and any queued loop publish;
  UI, MediaSession, and notification all report `PAUSED`.
- One command actor/generation for every transport transition (pause, resume, seek,
  navigate, undo, speed, sleep); a superseded command must never `publish` or change
  foreground state after a newer state.
- Loading feedback distinguishes "pause requested" from "generating" — the spinner
  stays interactive and cancels on tap.
- Seek while genuinely paused repositions silently (current `seekTo` semantics) and
  never starts audio.

**Regression coverage/acceptance:**

- Service test: `ACTION_PLAY` with a delayed fake synthesis; while `LOADING`, issue
  `ACTION_PAUSE`; assert MediaSession `STATE_PAUSED`, notification action "Play", no
  further synthesis call, `PlaybackStateHolder.phase == PAUSED`.
- Same for `PAUSE`→`SEEK` during generation: paused, moved position, no synthesis.
- Device: press play on a cold engine, press pause during "Generating…"; within 2 s
  the notification shows "Play", `dumpsys media_session` reports `PAUSED`, and no
  further synthesis work is observable.

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
typed download failure (#47), the three S-debug regressions (#39), and the stale-resume crash (2026-08-27 `fix(player)` commit), scene-break marker passages (2026-08-27 — `BookSegmentation.segment()` now drops letter-free passages, so `* * *` spacers are never read aloud), and chapter-boundary page turns (2026-08-27 — ReaderScreen side-taps/swipes now advance to the next chapter past the last page and back to the previous chapter before the first, repositioning silently via a new `ACTION_OPEN_CHAPTER`), and CR-1 (2026-08-27, A1 — whole-book manual pre-generation now runs: an absent budget is unbounded, never an expired deadline, and `Unavailable`/failure-cap terminals fail the job with a typed error and progress counts instead of a false success).