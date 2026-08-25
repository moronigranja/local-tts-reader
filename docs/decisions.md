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
forces the built-in-Kotlin migration — deferred follow-up). Compose BOM stays
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
