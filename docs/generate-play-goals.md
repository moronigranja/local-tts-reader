# Generate/play subsystem goals — local-tts-reader (Ayvu)

Decision-support document. Owner-clarified 2026-08-28; supersedes earlier informal
"lowest delay / no hiccups / consistent quality" phrasing with concrete targets and
explicit non-goals. Companions: `docs/generate-play-structure.md` (how it works today),
`docs/generate-play-lean-up.md` (how to get leaner without violating these goals).

**Format**: numeric SLOs where measurable, principles where not. SLOs are defined at
**1.0× speed, passage cached** unless stated otherwise (see Speed policy, G1.4).

---

## G1 — Lowest delay to start playing

Delay is minimized by **pregeneration coverage**, not by changing the start policy.
The full-cushion wait stays: playback waits for the queue to build
(≤ 60 passages / 45 s ahead, `PregenQueue.ensure`, P:1059-1063) before starting
(`bufferForPlayback`, P:920-945). That wait is what buys G2.

### SLOs (measured: interaction → audible audio)

| # | Trigger | Target | Notes |
|---|---|---|---|
| L1 | Play tap (library / open book), passage cached | **< 300 ms** | Warm process: intent → foreground → machine rebuild → `cache.get` → `AudioTrack`. Code path is ~50-150 ms today with a cached passage. |
| L2 | Skip / jump / seek to cached passage | **< 300 ms** | Same path as L1 (machine rebuild + `cache.get`). One SLO for both keeps it honest. |
| L3 | Notification resume after process death | **< 5 s** | Fresh process pays model open (~325 MB) + fetch/synthesize passage 1. Requires pregen coverage of the resumed book. LOADING state visible. |

**Cold-path principle**: when a target passage is not cached, the UI must show an
honest LOADING state immediately, and audio begins as soon as the first passage is
rendered **and** the cushion policy is satisfied — never silently pretend to be playing.
Visible LOADING states are an accepted cost (budget, G4).

**Non-target triggers (documented, not SLO'd)**:
- First play after fresh process start *while the service is warm* — covered by L1/L3 in
  practice; engine open happens once per process (`KokoroRuntime.engine()`, KR:23-59).
- Headset/media-button resume after process death — **deferred, system limitation**:
  MediaSession dies with the process; there is no restart path for hardware buttons.
  Only the notification path (L3) is actionable. In-process headset resume works today
  via the session callback (P:746-751) and keeps the L1 spirit.
- Screen-off start — out of scope (see Non-goals).

## G2 — Avoiding hiccups

**The only hiccup metric in scope is the passage-boundary gap** (owner-selected; all
other candidate metrics explicitly rejected — see Non-goals).

### SLO

| # | Metric | Target | Today |
|---|---|---|---|
| GAP1 | Gap between passage N end and passage N+1 start, steady state, 1.0× | **≤ 50 ms** | ~20 ms measured on device with `source=pregen` (HANDOFF:37-41) — full-cushion wait exists to hold this |

### Policy consequence — pregen yields while playing

Manual/offline pregen currently runs **concurrently** with playback in the same process
and deliberately does **not** yield (decisions #42; W:152-160). Concurrent synthesis
steals CPU from passage-2 rendering and can stretch the boundary gap.

**Decision (reverses decisions #42):** offline/manual pregen **suspends while playback
is active** and resumes on pause/stop. Overnight mode already yields; the reversal
extends that to manual runs. This is a decisions.md entry when implemented (cacique).
The lean-up doc's S1/O3 "engine-admission rule" option is thereby **decided yes**.

## G3 — Consistent quality = segment visibility

Owner's definition of quality, clarified to four behaviors, sentence-level granularity:

| # | Behavior | Detail |
|---|---|---|
| Q1 | Auto-scroll to keep the spoken segment visible | When the currently-spoken sentence's text is (or becomes) off-screen (long passages, passage outruns viewport), auto-scroll so it stays in view. |
| Q2 | User scroll wins | If the user manually scrolls away from the playing segment, the screen stays where the user put it; the highlight keeps tracking audio in the background. Auto-follow resumes at the next passage change. |
| Q3 | Highlight holds on pause | Pause / sleep-timer stop leaves the highlight on the segment where audio stopped; resume continues from there. |
| Q4 | Sentence-level granularity | A "segment" = the sentence-level chunk from engine timings (today: kokoro `segments`, KE:138-147). Word-level is a future upgrade, not a goal now. |

**Coupling this goal depends on**: read-along highlight requires
`SynthesisOutcome.Audio.segments`; kokoro produces it, other engines may return null
→ no highlight, by contract (E:84-89). The quality goal therefore **pins the
engine-seam requirement**: any replacement engine must provide sentence segment
timings or the Q1-Q4 behaviors degrade.

Today the reader follows only at passage boundaries (`LaunchedEffect` on
`passageIndex`, RS:306-313); Q1 (intra-passage auto-scroll) is net-new.

## G4 — Resource budget (granted)

- **CPU/battery on pre-generation**: allowed. Aggressive prefill, warm-up work, and
  re-synthesis are acceptable costs to hit L1-L3.
- **Visible LOADING states**: accepted (see G1 cold-path principle).
- **NOT granted**: storage for multi-speed pregen (rejected via G1.4); pregen-yield
  policy was decided separately (G2) and is CPU-free.

## Non-goals (explicitly out of scope this round)

- **Mid-passage stutter / underrun** — not a target metric. Static-track output
  (`AudioTrack` MODE_STATIC, PO:27-69) makes internal underrun structurally unlikely;
  revisit only if measured.
- **CPU-contention stalls as a standalone metric** — folded into GAP1 via the yield
  policy; not tracked separately.
- **Cached-vs-cold latency variance** — cold paths get LOADING states, not a latency SLO.
- **UI/audio desync** (state says PLAYING, no audio) — tracked in open-bugs.md, not a goal.
- **Streaming audio / ExoPlayer / markers / cross-fade** — unmotivated at a measured
  ~20 ms gap; the lean-up doc's S4 verdict stands.
- **N parallel playback sessions** — singleton pile-up is by design; CPU-bound anyway.
- **Engine swap itself** (e.g. CosyVoice) — goals only pin the segment-timings seam
  (G3); swap mechanics stay in decisions #54.
- **Screen-off policy** — no wakelock exists; audio continues via foreground service;
  deep-sleep behavior of the 50 ms poll is unhandled [INFERENCE]. Needs its own
  decision, not a lean-up goal.

## Speed policy (G1.4)

**Selector removed 2026-08-28 (decisions #71):** playback is always **1.0×** — the
speed pill and its command chain are gone, and stored per-book speeds are ignored on
resume (rows normalize to 1.0 on the next progress write). The speed model is
retained (progress column, `PregenKey` speed + path, `SynthesisRequest.speed`,
`PlayerStateMachine.setSpeed`, `PassageOutput.play` speed) and SLOs are unchanged —
they were already 1.0×-only. A revisit is planned (decisions #29's per-book-speed
restore acceptance is suspended, not deleted).

## Measurement (instrument now)

SLOs require a harness. Land **debug-build timestamp instrumentation** before the
lean-up PRs (BuildConfig.DEBUG-gated; no production cost):

- `tap-to-audio`: interaction (intent dispatch) → first frame written to `AudioTrack`
  (L1, L2).
- `resume-to-audio`: notification tap → audio, including process spawn (L3).
- `boundary-gap`: passage N last frame → passage N+1 first frame; log per boundary,
  aggregate p50/p95 (GAP1).

Probes must not perturb the poll loop or CR-2/CR-5/CR-7 ordering (log, don't block).
Aggregate output: logcat tags consumed by a dev script; no UI.

## Invariants the goals must not violate

- CR-2: exactly one authoritative final write; capture `liveOffsetSeconds` before
  releasing the output (P:543-550, 1012-1042) — L1/L2 speed work must not move this.
- CR-5/CR-7: generation-guarded single-writer commands (P:974-1010) — any
  start-path optimization runs inside `launchCommand`.
- CR-8/CR-9: `publish()` copy-block field parity (P:677-733) — LOADING-state changes
  must re-check the full field set (see lean-up QW1, a live regression today).
- Cache as the only cross-process coordination point (CR-4) — L3's post-death resume
  relies on pregen writes landing in `PcmPassageCache` before the process died.

## Sequencing note

Instrumentation lands first (measurement), then lean-up PR-0 (QW1 `chapters` restore
+ guard test, QW2 book-id in notification actions, QW5a/b dead code), then the G2
yield reversal as its own decision-bearing slice. Goals doc is the acceptance
reference for each.

PR-0 shipped 2026-08-28 (decisions #72): QW1 `chapters` restore + field-set guard
test, QW2 book-id in notification/MediaSession actions, QW5a/b dead code. QW2's
device acceptance — kill process → notification Play resumes (L3) — is pending
(no device this round).
The instrumentation probes landed 2026-08-28 (decisions #73 — `AyvuTap`/
`AyvuGap` debug-gated logcat in PlaybackService); device-number collection
(L1/L2/L3/GAP1) is PENDING — no device this round.
The final lean-up batch landed 2026-08-28 (decisions #76-#79): the G2 yield
reversal now spans the full session window — manual/offline pregen suspends from
session start through the post-stop fill's completion (`PlaybackActive`, #76) —
plus S5 engine-dimension + rate-aware prep (#77), the S3 publish
details/snapshot split and QW4 one-fill-job merge (#78), and the S4 AudioTrack
reuse micro-lean (#79). Device-leg items remain PENDING on hardware: QW2
post-death notification resume (L3 acceptance), L1/L2/L3/GAP1 number collection,
QW4's post-stop self-stop acceptance, and a screen-off sanity pass — a device is
now available.

Goal-driven deltas to the proposal and the resulting execution order:
`docs/generate-play-lean-up.md` → **Goals impact (2026-08-28)**.

---

*Owner decisions recorded 2026-08-28: start policy kept (full cushion), latency scope
= play tap + notification resume + skip/jump, hiccup = boundary gap only, budget = CPU + LOADING states, speed selector removed (playback 1.0×, revisit
planned), contention = pregen yields, quality = sentence segment visibility
(auto-scroll, user-scroll wins, hold on pause), measure now. Reversals to carry into
decisions.md (via cacique): #42 yield semantics.*