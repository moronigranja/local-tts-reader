# Bug log

Bugs found during device passes, especially the **weak-device pass** (a
second, lower-spec Android device the S22 Ultra is compared against). Log,
don't fix: the weak-device pass is a *measurement* pass — performance and
resource findings are recorded here for a later tuning slice, not patched
mid-pass. Functional bugs found anywhere get recorded here too and fixed in
their own slice.

## How to log

Each entry: date · device · app build/commit · what happened (repro steps) ·
observed vs expected · severity (blocker/major/minor/cosmetic) · whether it
is a **perf** finding (not to fix now) or a functional bug.

## Entries

### 2026-08-27 — Weak-device pass #1 · Bigme HiBreak (Android 14, MT6765 8×A53, 3.9 GB, e-ink) · build efa4081
Pass: cold start → play → card → ±30s → reader, Wind and Truth (Kokoro synth). No crashes, no ANRs; UI behaved. All findings **perf** — logged, not fixed.

- **[perf] Play-to-first-audio 25 s** — tap Play on the library row → first `loop: playing` logged ~25 s later (RTF collapses vs ~4–8 s on the S22 for the same first-listen synthesis path). The "Generating…" spinner holds the whole time (correct feedback; the wait is the problem).
- **[perf] ±30s cross-boundary seek ~58 s** — `+30s` (or −30s) onto a passage in neither the RAM queue nor the disk tier synthesizes ~50 s+ on this SoC. Same layering as the S22 (5–25 s there); adds urgency to the roadmap "instant ±30s seek horizon" slice (time-bounded look-ahead + survive-seek ensure).
- **[perf] App memory 834 MB PSS / 919 MB RSS** — `dumpsys meminfo` TOTAL during a play session on a 3.9 GB device (~26% of usable RAM). ONNX Runtime sessions + audio pipeline; candidates: session reuse, buffer pooling, or the 0.5 / accel path — to tune in the weak-device slice, not here.
- **[perf] UI frame-skip jank** — 14,978 `Choreographer: Skipped` events accumulated during the pass (e-ink panel + slow SoC; not app-crash). No interaction froze (card buttons responded), but scrolling/animations (card expand) will be rough on this class of device.
- **[observation, not a bug] Disk tier works** — 859 KB offline used from the first-listen persist path (fewer passages persisted this session, correct).
- **[observation, not a bug] Data migrated** — the same library/book appeared on the HiBreak (user restored/migrated); app data behaved identically to the S22 pass.

### 2026-08-27 — S22 pass (reference for the weak-device comparison)
Build 0bf2b2f/efa4081: play-to-first-audio ~4–8 s; ±30s cross-boundary 5–25 s; no crashes; card + menu + reader verified (decisions #55/#56).