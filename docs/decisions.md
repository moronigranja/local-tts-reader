# Decision log

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
Open: S2 (share receiver) consumes OcrEngine + match threshold; the threshold's
S3 "listen from here" usage; LSTM models via a maintained binding when
accuracy demands it.

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
  notification): manual mode = one book, 60-min wall budget, unique-name KEEP
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
