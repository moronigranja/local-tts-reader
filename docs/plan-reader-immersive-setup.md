# Plan — reader immersive mode, pagination & follow fixes, setup wizard, realtime probe

Status: **landed** (executed 2026-09-04, committed f0f7cab; device pass S22
2026-09-04/05; post-land fixes 5c0a7cb + the immersive top-overlay reserve).
Owner-reviewed 2026-09-04; review-round decisions recorded under "Resolved up
front". Companion to `roadmap.md`, `decisions.md`, `ideas.md`, `open-bugs.md`.
Items landed and graduated into the decision ledger (#119-#125).
Follow-ups recorded in open-bugs: E2E fixture geometry (pre-existing) and the
immersive top-cut (fixed 2026-09-05, decisions #125).

Eight items, grouped by surface.

---

## Resolved up front

- **Voice selection order** — the user's "voice after download" **reverses**
  `roadmap.md` C1 ("choose language and voice **before** downloading", line 181).
  Decision: reorder and update C1 + add a decision entry. No asset difference:
  all 54 voices ship in one `kokoro-voices` pack (`voices-v1.0.bin`, 28 MB); the
  pre-download choice was only a persisted preference, and moving it after the
  packs are Ready is what makes **Preview** work during selection.
- **Middle tap** — becomes **toggle-chrome only**. The old tap-passage-to-play
  ("listen from here", S3) is superseded; it will return as a long-press menu
  (already planned — see cross-refs §G2 below). No long-press built this pass.

### Review-round decisions (2026-09-04, second pass)

- **Immersive pagination** — the title is drawn OVER the page with a margin
  (overlay; it enters no `reservedPx`). Dropping the full `PlayerCard` GROWS the
  body, so pages re-derive on toggle — reflow is accepted (it buys more text per
  page) and the reading place is kept by re-deriving from the top visible line,
  not the page index.
- **Footer indicator (both modes)** — the reader shows a book-wide
  **`Passage X/Y (P%)`**; the per-chapter "Page N of M" line is REMOVED from
  regular mode. Item 4 is re-scoped accordingly (no whole-book page measuring).
- **Follow vs manual page turn** — after a manual turn, follow pauses for a grace
  period (~3–5 s) before resuming (item 3).
- **RTF probe shape** — no dedicated probe step (item 8): measure from the C2
  Preview synthesis with a lazy playback fallback; persisted tri-state.
- **RTF sequencing** — item 8 is designed and built TOGETHER with D1 (both own
  `bufferForPlayback`/`PLAY_BUFFER_TIMEOUT_MS`).
- **Wizard rules** — a finished step never throws the user back; a reappearing
  step inserts without moving the pointer; system Back maps to wizard Back (item 6).
- **Ordering** — item 2 (crop) lands before item 1 (immersive): shared reserve
  math. Item 6 (wizard) is built against item 7's new step order.
- **Pregen notification update** — refresh via `NotificationManager.notify` in
  place; never per-tick `setForeground` (re-binds the FGS every second).
- **Pregen yield policy** — conditional yield (item 5): manual pre-gen runs while
  playback is fully cache-fed (buffer/queue/disk covers active + look-ahead);
  it pauses at the next passage boundary when playback needs the shared engine.
  Replaces the coarse `PlaybackActive` session boolean. Designed together with
  D1 + item 8 (same fill machinery); needs a device measurement gate.
- **Verification gates** — immersive adds 2.0× font scale + a TalkBack pass (B4
  gates); item 1 also updates the stale middle-tap doc comments in `ReaderScreen`.

---

## 1. Full-screen / immersive reader mode

**Goal:** distraction-free reading; middle tap toggles chrome; top shows the book
title as a single overlaid text line, bottom a minimal player + the book-wide
passage indicator (item 4).

**Files:** `feature-player/…/ui/ReaderScreen.kt` (and `ReaderViewModel.kt` only if
new commands are needed).

**Change:**
- `immersive: Boolean` via `rememberSaveable`, default off.
- Immersive on: hide system bars (`WindowInsetsControllerCompat`, sticky), drop the
  `Scaffold` top bar and the full `PlayerCard`.
- Immersive chrome: top = book title (`labelLarge`, centered — same visual family
  as the footer) drawn OVER the body with a margin — it enters no `reservedPx`, so
  the title is not a reflow source; bottom = minimal player (play/pause + thin
  progress line reusing `PlaybackUiState.readFraction`/`generatedAheadFraction`)
  beside the item 4 `Passage X/Y (P%)` indicator.
- Dropping the `PlayerCard` grows the body: pages re-derive on toggle (accepted
  reflow, more lines per page); the reading place is kept by re-deriving the page
  from the top visible line, not the page index.
- Gestures: middle tap toggles chrome both ways; side-zone taps + horizontal swipe
  keep page-turning. `PaginatedChapter`'s pointer handler loses the
  middle-tap-plays-passage branch.
- Update the stale doc comments in `ReaderScreen.kt` (the class and gesture docs
  at ~lines 88–89 and 283–286 still describe middle-tap-plays-passage).
- Restore system bars + full chrome on exit and `onDispose`.

**Verification:** S22 device pass — enter/exit immersive, bars hide/restore, title
+ minimal player + passage indicator render, page gestures still work, rotation
safe; re-run at 2.0× font scale (crop family) and a TalkBack pass over the
immersive chrome (B4 gates).

**Cross-refs:** new (no immersive item exists). See §G2/G3 for gesture interaction.

## 2. Bottom text crop (residual)

**Goal:** last text line never clipped, at any font scale.

**Files:** `feature-player/…/ui/ReaderScreen.kt` (`PaginatedChapter`),
`core-player/…/player/TextPagination.kt` if the fix is geometric.

**Change:** reproduce on-device at non-default font scales (the #87/#95 lesson:
OEM paint scale ≠ `Density.fontScale`); re-audit `indicatorReservedPx` (proxy
"Page 999 of 999" vs rendered "Page N of M") + title gap/padding + the
`linesPerPage` integer division; make the bottom reserve measured-exact and/or
conservative (extra line safety). Add a `TextPaginationTest` regression if the
fix is geometric.

Lands BEFORE item 1 — both touch the same reserve math, and immersive must not
ship on top of the crop bug.

**Verification:** device pass — full last line visible at 1.0×/2.0×/other scales.

**Cross-refs:** B3 (roadmap line 121) + `open-bugs.md` #29 (both marked FIXED —
this is the still-cropping residual).

## 3. Passage follow across page boundaries

**Goal:** narration turns the page when the spoken sentence crosses the boundary,
even inside a long paragraph.

**Files:** `feature-player/…/ui/ReaderScreen.kt` (`PaginatedChapter`).

**Change:** page-follow currently keys on `passageStartLines[passageIndex]` (the
paragraph's *start* line) — a long paragraph narrated across a break pins the page.
Re-key on the **active sentence's** line: `activeSentenceRange` → char offset →
`bodyLayout.getLineForOffset` → `TextPagination.pageOf`. No domain change —
paragraphs stay the resume/matching/TTS unit; the display already flows paragraphs
across pages by line.

Both follow effects re-key — the PLAYING/LOADING effect and the paused
reposition path (`ReaderScreen.kt` ~405–408 and ~417–420). A manual page turn
suppresses follow for a grace period (~3–5 s) before it resumes, so a hand turn
is not immediately yanked back by the next sentence tick.

**Verification:** device — read-along highlight stays visible across a long
paragraph's page break; page turns as the sentence moves, not the paragraph; a
manual page turn during playback holds for the grace period, then follow resumes.

**Cross-refs:** `ideas.md` #5 "Auto page/flip scroll synchronized with playback".

## 4. Book-wide passage indicator (replaces page numbers)

**Goal:** one book-wide position indicator — `Passage X/Y (P%)` — in BOTH modes;
the per-chapter "Page N of M" line is removed from regular mode.

**Files:** `feature-player/…/ui/ReaderScreen.kt`,
`core-player/…/player/PlaybackUiState.kt` (book-wide passage index/count),
`PlaybackService` (publish them).

**Change (re-scoped 2026-09-04 review):** NO page measuring, no cache, no
viewport/font keys. The book-wide count is Σ chapter passage counts (cheap,
text-free — Room counts, not full-book text) and the running index is the chapter
prefix sum + `passageIndex`; percent = index/count. Stable across font scale,
viewport width and immersive toggles — nothing to invalidate.

**Verification:** device — the indicator counts continuously across chapter
boundaries in both modes; chapter-menu jumps land on the right number; toggling
immersive does not change the shown numbers.

**Cross-refs:** `ideas.md` #3 "Progress & time-left indicators (chapter/book)".
`PlayerCard` already shows book-wide `readFraction`/`timeLeftSeconds`; the passage
indicator is the discrete, toggle-stable complement.

## 5. Pre-generation progress (manual context-menu run)

**Goal:** visible, moving progress during a manual "Pre-generate" (30 min+ budgets).

**Files:** `feature-player/…/playback/PregenWorker.kt`.

**Change:** `setProgress` already fires every passage, but the **foreground
notification** only refreshes on a chapter boundary (`chapterNotified` guard).
Update the notification continuously (integer-percent or ~1 s throttle) + a
synthesized-passage count. No change to `OfflinePregen` — it walks/caches correctly
(A1/CR-1 verified it's not a false-success no-op).

Update mechanism: once foreground, refresh via
`NotificationManager.notify(NOTIFICATION_ID, …)` in place — the FGS is already
up; a per-tick `setForeground` re-binds the service every second for the whole
run.

Background + terminal: the run is ALREADY background — WorkManager promotes a
`dataSync` foreground service at the first book (`PregenWorker.runBooks`
~153–161; survives app-close) and the notification already posts at start.
Missing: a TERMINAL notification. Today the run settles silently — success posts
nothing after the FGS stops, and on failure the ongoing notification vanishes
with the service (`KEY_ERROR` visible only in the library row). Post a
non-ongoing, autoCancel terminal notification: Completed/BudgetExhausted/
CacheSaturated → "offline audio ready — N passages cached"; Unavailable/
FailureCap → the typed `runErrorMessage`; user cancel → silent (cancellation is
deliberate, no nag).

Yield policy (2026-09-04 review decision): replace the blanket G2 yield with a
**conditional yield**. Today `PlaybackActive.isActive` pauses the run for the
WHOLE session, so a manual run makes zero progress while listening — even when
playback is purely cache-fed and the shared engine sits idle (playback resolves
`buffer` → `pregen` → `disk` before it ever calls the engine; the miss path is
the only engine user). New rule: the run continues while playback is fully
cache-fed (active passage + look-ahead covered by buffer/queue/disk) and pauses
at the next passage boundary when playback needs the engine (its fill is
synthesizing, or a sync synthesis starts). Priority: playback > pregen. Worst
case: a cold seek waits for at most one in-flight pregen passage (already
cancellable per batch via `shouldContinue`). One engine, one session — no new
memory; #116 CPU-saturation evidence is why the arbiter pauses instead of
co-running blindly. Sequenced WITH D1 + item 8 (same `PregenQueue`/fill
machinery); device measurement gate on the S22 before adoption (planning rule).

**Verification:** device — manual pre-gen notification bar/text advance during a
single long chapter; library-row `WorkInfo` progress already per-passage; let a
run finish and fail once each → the terminal notification appears and the
ongoing one is gone; cancel → no terminal notification. Conditional yield:
device pass — pregen advances while listening cache-fed; a seek into a cold
region pauses pregen at a passage boundary and playback's synthesis wins; no
audio dropouts attributable to concurrent pregen (measurement gate).

**Cross-refs:** A1/CR-1 complete; `ideas.md` #60/#61 ("pre-gen follow-up batch",
decisions #44 family); G2 yield (decisions #42 family) superseded by the
conditional yield when it lands, with its own decision entry.

## 6. Onboarding wizard with Next/Back

**Goal:** one step at a time with explicit Next, not the all-at-once checklist.

**Files:** `app/…/setup/SetupScreen.kt`, `app/…/setup/SetupViewModel.kt`.

**Change:** hold `currentStep` **by `StepKind` identity** (not raw index — the
derived list shrinks AND grows as pack facts change) with explicit clamp rules: a
step that disappears never throws the user back (land on the nearest preceding
surviving step); a step that reappears inserts without moving the pointer.
PRIVACY → Next → DOWNLOAD_PACKS → Next (enabled when packs Ready) → CHOOSE_VOICE →
Next → IMPORT_BOOK → Finish; Back returns; terminal steps auto-finish (existing
`LaunchedEffect`). System Back maps to wizard Back while a non-terminal step is
current, not to setup exit. Built against item 7's NEW order — sequence item 7
first.

**Verification:** host `SetupViewModel`/screen tests + device — walk the flow,
downloads complete mid-flow, Back/Next behave, terminal auto-finish, a pack lost
mid-flow re-inserts DOWNLOAD_PACKS without moving the pointer.

**Cross-refs:** C1 (complete) — this restructures its *presentation*; content
unchanged.

## 7. Voice selection after download

**Files:** `core-tts/…/setup/SetupState.kt`, `SetupStateTest.kt`.

**Change:** reorder `derive` full-plan branch to
`PRIVACY, DOWNLOAD_PACKS, CHOOSE_VOICE, IMPORT_BOOK`. Degraded path
(`PRIVACY, CHOOSE_VOICE, IMPORT_BOOK`) has no download step and is unchanged.
Update roadmap C1 + add a decision entry.

**Verification:** `SetupStateTest` updated; device first-run order.

**Cross-refs:** reverses C1 line 181 (see "Resolved up front").

Lands BEFORE item 6 — the wizard is built against this order.

## 8. Onboarding realtime capability (RTF via Preview)

**Goal:** detect whether the selected engine sustains realtime TTS; if it can
generate ahead of play, skip/shorten the buffer wait before first play.

**Files:** the `core-tts` Preview path (the C2 audition synthesis — the
measurement point), `AppSettings` (tri-state: realtime / slow / unmeasured),
`feature-player/…/playback/PlaybackService.kt` (`bufferForPlayback` /
`PLAY_BUFFER_TIMEOUT_MS`).

**Change (re-shaped 2026-09-04 review):** NO dedicated probe step — a probe pays
25–60 s of engine open + synthesis on exactly the devices where the answer is
negative (HiBreak cold open ≈ 25 s, RTF ≈ 3), and both reference answers are
already measured (S22 0.66–0.77, HiBreak 2.84–3.12). Instead:

- Measure RTF from the C2 **Preview** synthesis — the voice step sits AFTER
  DOWNLOAD_PACKS (item 7), so the engine is open and a sample is synthesized
  anyway; RTF = wall / audio-duration, zero extra opens, zero extra steps. The
  sample must yield ≥ ~10 s of speech (short probes overstate RTF — #93).
- Fallback: if the user never previews, derive `realtimeCapable` lazily from the
  first real passages' wall/audio ratio in `PlaybackService`.
- Persist the tri-state. Playback: realtime → start after first-passage synthesis
  (skip the cushion); slow → keep cushion + timeout (the HiBreak case,
  `bugs.md` B6); **unmeasured → keep today's behavior**. Probe failure/cancel
  never blocks the wizard. System TTS (`degraded`) treated as realtime.
- Designed and built TOGETHER with D1 and item 5's conditional yield — all three
  own the `bufferForPlayback`/`PregenQueue` fill machinery; one arbiter, one
  decision entry.

**Verification:** host (RTF math, tri-state persistence, fallback policy) +
device — Preview on the S22 persists realtime (first-play latency drops);
HiBreak persists slow; no preview → unmeasured keeps the cushion.

**Cross-refs:** D1 (first-audio baseline), D2 (RTF measurement methodology), `bugs.md`
B6 (live synthesis can't sustain playback → pregen mandatory); `bufferForPlayback`
is reactive today (timeout). Needs a decision entry + a measured acceptance.

---

## Roadmap & ideas cross-references added by review

- **G2 — Paragraph context menu** (`roadmap.md` §G2, line 718): "preserve
  middle-tap play and page-turn gesture discrimination." This plan's middle-tap
  toggle **supersedes that clause** — play-from-here moves to G2's long-press
  menu (the user's "long touch in future"). G2's three-way discrimination note
  (tap vs long-press vs swipe, `decisions #96`) still applies to the toggle.
- **G3 — Hardware/gestures** (`roadmap.md` §G3): full-screen tap zones and any
  volume-key nav must stay inside G3's narrow scope (no configurable gesture
  editor this pass).
- **D1/D2** (`roadmap.md` §D): the RTF work (item 8) reuses D2's measurement
  methodology and feeds D1's first-audio target; it is built together with D1's
  `bufferForPlayback` rework and lands with a fresh decision, not a silent
  behavior change.
- **`ideas.md` #3** (progress/time-left indicators) → item 4 (now passage-based).
- **`ideas.md` #5** (auto page/flip scroll) → item 3.
- **`ideas.md` #60/#61** (pre-gen follow-up batch) → item 5's umbrella.
- **`roadmap.md` Later — word-level highlighting**: explicitly **out of scope**
  here; follow stays sentence-granularity (item 3) on the existing anchors.

---

## Not in scope (this pass)

- Word-level highlighting (`roadmap.md` Later).
- Long-press paragraph menu implementation (G2 — future home of play-from-here).
- Configurable tap-zone editor (G3).
- Habit-driven / auto-delete pre-generation.
- Any change to paragraph segmentation (resume/matching/TTS contracts stay intact).
- Whole-book page-number measuring (superseded by the item 4 passage indicator).
