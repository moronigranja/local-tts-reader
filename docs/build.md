# Build, run, test

```bash
./gradlew testDebugUnitTest             # unit tests (logic, parsers, state, Room DAOs via Robolectric)
./gradlew assembleDebugAndroidTest      # instrumented tests (compile; run on a device)
# without the Android SDK use the containerized toolchain (below) for anything
# that touches Android modules
```

No ktlint/detekt plugins are configured yet — those lint/format tasks arrive with the
CI slice (roadmap V2).

Four pure-JVM modules (`core-model`, `core-ebook`, `core-locate`, `core-tts`)
build and test without the Android SDK — `./gradlew :core-locate:test
:core-ebook:test :core-model:test :core-tts:test` runs their unit tests. The
Android modules (`app`, `feature-library`, `core-persistence`) are wired into the
same build; their unit tests need the SDK — `core-persistence` additionally runs
the Room DAO/store tests under Robolectric. The aggregate `./gradlew test` needs
the SDK once Android tasks run; use the containerized toolchain for the full
suite (`tools/docker-build.sh test`).

## T3 CosyVoice3 spike (`spike-tts`)

Experimental measurement harness (decision #21): runs the
CosyVoice3-0.5B ONNX pipeline (jiangzhuo9357 int4 export + sokuji-corrected
semantics) on a physical device, per-stage timing, RTF, VmHWM/RSS and thermal
headroom, and writes `out_runN.wav` + `results.json` to external filesDir.

```bash
tools/docker-build.sh :spike-tts:assembleDebug     # build (debug keystore pinned at
                                                   # repo root, decisions #45 —
                                                   # installs update cleanly)
adb install spike-tts/build/outputs/apk/debug/spike-tts-debug.apk
# stage models (3.5 GB) into internal storage — Android 11+ FUSE hides
# adb-pushed files under Android/data/<pkg>:
adb push /tmp/t3/models /data/local/tmp/models          # once
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \
  'mkdir -p files/models && cp -r /data/local/tmp/models/. files/models/'"
adb shell am start -n com.moronigranja.localttsreader.spiketts/.MainActivity
adb logcat -s T3Spike                                   # RTF / stage timings
# pull results:
adb exec-out run-as com.moronigranja.localttsreader.spiketts cat \
  /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/out_run1.wav > out.wav
```
The prompt voice ships pre-resampled (`voices/sarah16.wav` / `sarah24.wav`);
models are intentionally NOT committed (runtime download, decision #7).
## Kokoro on-device benchmark (decisions #30/#31, `spike-tts`)

Runs the raw Kokoro-82M port (core-tts) on a phone: RTF, RAM, thermal, engine
open time. The corpus ships pre-phonemized (host espeak-ng, written by
`:core-tts:kokoroGrainSpike`), so the harness runs without an Android espeak-ng
build; the full on-device phonemization bundle is decision #32.

```bash
tools/docker-build.sh :spike-tts:assembleDebug :spike-tts:assembleDebugAndroidTest
adb uninstall com.moronigranja.localttsreader.spiketts 2>/dev/null; adb uninstall com.moronigranja.localttsreader.spiketts.test 2>/dev/null
adb install spike-tts/build/outputs/apk/debug/spike-tts-debug.apk
adb install -r -t spike-tts/build/outputs/apk/androidTest/debug/spike-tts-debug-androidTest.apk
# stage packs + corpus (Android 11+ FUSE hides adb-pushed files under Android/data):
#   host-side corpus: ./gradlew :core-tts:kokoroGrainSpike -PkokoroCache=<dir>
adb push <dir>/kokoro-v1.0.onnx /data/local/tmp/kokoro-model
adb push <dir>/voices-v1.0.bin /data/local/tmp/kokoro-voices
adb push <dir>/kokoro-device-corpus.tsv /data/local/tmp/corpus.tsv
# precision candidates (D3 spike; q8 is generated host-side from the pinned fp32):
python3 tools/quantize_kokoro_q8.py <dir>/kokoro-v1.0.onnx <dir>/kokoro-v1.0.q8.onnx
adb push <dir>/kokoro-v1.0.fp16.onnx /data/local/tmp/kokoro-model-fp16
adb push <dir>/kokoro-v1.0.int8.onnx /data/local/tmp/kokoro-model-int8
adb push <dir>/kokoro-v1.0.q8.onnx   /data/local/tmp/kokoro-model-q8
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \\
  'cp /data/local/tmp/kokoro-model-fp16 files/models/ && \\
   cp /data/local/tmp/kokoro-model-int8 files/models/ && \\
   cp /data/local/tmp/kokoro-model-q8 files/models/'"
# run: locked/off screen is fine — instrumented tests are exempt from the
# process freezer (a launched-but-keyguarded Activity freezes in __refrigerator):
adb logcat -c
adb shell am instrument -w com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s KokoroSpike   # per-run RTF + DONE
# pull results (external files dir):
adb exec-out run-as com.moronigranja.localttsreader.spiketts cat \
  /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/kokoro_results.json
# precision JSONs + A/B WAVs (kokoro_<label>_run1_<lang>.wav vs _oracle_<lang>.wav):
adb exec-out run-as com.moronigranja.localttsreader.spiketts cat \
  /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/kokoro_precision_fp16.json
```

## D3 engine comparison staging (decisions #92/#93, `spike-tts`)

Stages the Kitten Nano + MOSS-TTS-Nano packs and the shared `d3_corpus.tsv`
alongside the existing Kokoro packs; the CosyVoice3 leg reuses the T3 staging
(see §"T3 CosyVoice3 spike" — 3.5 GB into `files/models`). Pinned sources:
`KittenML/kitten-tts-nano-0.8-fp32` (fp32 only — the int8 variant is broken
upstream) and `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX` @ `f52645cb…` +
`MOSS-Audio-Tokenizer-Nano-ONNX` @ `ceff0d07…` (note: the `OpenMOSS` org does
not serve the repos; it is `OpenMOSS-Team`). The MOSS pack merges under one
root keeping the repo-dir names the demo engine's manifest aliases expect.
Corpus columns (`id \t lang \t raw_text \t kokoro_phonemes \t kitten_tokens \t
moss_token_ids`) are host-generated: espeak-ng (repo `EspeakPhonemizer`) +
the upstream `TextCleaner`/framing for Kitten, sentencepiece for MOSS
(validated against the manifest's gold `text_token_ids`).

```bash
adb push /tmp/d3/models /data/local/tmp/d3-models
adb push /tmp/d3/d3_corpus.tsv /data/local/tmp/d3_corpus.tsv
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \\
  'mkdir -p files/models && cp -r /data/local/tmp/d3-models/. files/models/ && \\
   cp /data/local/tmp/d3_corpus.tsv files/d3_corpus.tsv'"
adb shell "run-as com.moronigranja.localttsreader.spiketts ls files/models/kitten \\
  files/models/moss/MOSS-TTS-Nano-100M-ONNX"
adb logcat -c
adb shell am instrument -w com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s D3Compare:V KittenSpike:V MossSpike:V
adb exec-out run-as com.moronigranja.localttsreader.spiketts cat \\
  /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/d3_results.json
```

Measurement notes carried from #93: MOSS sessions require
`setMemoryPatternOptimization(false)` + `setCPUArenaAllocator(false)` (lmkd
kills at 6.6 GB RSS otherwise) and the Kitten graph caps at 509 tokens incl.
framing (the runner chunks). During long CosyVoice3 activity runs keep the
screen on (`svc power stayon true`) — a dozed display lets the Samsung freezer
pause the benchmark thread mid-stage.

## ONNX closer-look staging (2026-08-31, `spike-tts`)

Stages the two closer-look candidates for `OnnxProbeBenchmarkTest` (landscape.md
HF trending sweep — open/run/finite gate on ORT-android; no corpus or quality
leg). Sources: `BricksDisplay/chatterbox-multilingual-ONNX-q4` (790 MB; keep
the `onnx/` subfolder) and `Audio8/audio8-TTS-0.1B-ONNX-INT8` online set only
(slow_ar/fast_ar/codec `*.onnx` + `.data`; skip `registration/` → 431 MB).

```bash
# host: hf download BricksDisplay/chatterbox-multilingual-ONNX-q4 --local-dir m/cbq4 --include 'onnx/*.onnx'
# host: hf download Audio8/audio8-TTS-0.1B-ONNX-INT8 --local-dir m/a8 --include 'slow_ar_int8.onnx*' 'fast_ar_int8.onnx*' 'codec_decoder_fp16.onnx*'
adb push m/cbq4/onnx /data/local/tmp/cb-q4-onnx
adb push m/a8-single-dir /data/local/tmp/a8
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \\
  'mkdir -p files/models/chatterbox-q4/onnx files/models/audio8 && \\
   cp /data/local/tmp/cb-q4-onnx/* files/models/chatterbox-q4/onnx/ && \\
   cp /data/local/tmp/a8/* files/models/audio8/'"
adb logcat -c
adb shell am instrument -w -e class com.moronigranja.localttsreader.spiketts.OnnxProbeBenchmarkTest \\
  com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
adb shell cat /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/onnx_probe_results.json
```

Measured B6 results (2026-08-31): both candidates open + run finite — no
Kitten/MOSS-class NaN. Watch items: chatterbox-q4's conditional_decoder opens
in ~326 s on the HiBreak; audio8's fabricated slow-AR step ~5.8 s and the fp16
codec ~RTF 10 (realtime thesis unsupported on B6). Full loop legs belong to
roadmap D4 (Audio8) / D5 (chatterbox-vs-CosyVoice3).

## espeak-ng Android bundle (decision #32)
## D4 small-tier staging (2026-08-31, `spike-tts`)

Stages the two D4 candidates for `D4ProbeBenchmarkTest` (roadmap D4, decisions
#99 — real end-to-end pipelines, HiBreak RTF/PSS verdicts). Sources:
`rhasspy/piper-voices` en_US-lessac-medium (63 MB) and
`Supertone/supertonic-3` @ `3cadd1ee6394adea1bd021217a0e650ede09a323` (onnx/
only, 380 MB). All model-dependent tensors are host-prepared by the probe
scripts (host espeak-ng 1.52 → the voice's phoneme_id_map; the reference
`supertonic` PyPI SDK for text_ids/mask/style/latent shape) into a single
`d4_inputs.json` — the device only runs graphs and measures.

```bash
hf download rhasspy/piper-voices --include 'en/en_US/lessac/medium/*' --local-dir m/piper
python3 - <<'PY'   # supertonic: snapshot_download Supertone/supertonic-3, allow_patterns=['onnx/*','voice_styles/M1.json']
PY
# build d4_inputs.json (piper ids + supertonic tensors) — probe scripts in the
# 2026-08-31 session; kept reproducible: espeak-ng -q --ipa -v en-us <blob> →
# phoneme_id_map, and the SDK UnicodeProcessor with lang="na".
adb push m/piper/en/en_US/lessac/medium/en_US-lessac-medium.onnx /data/local/tmp/d4-piper.onnx
adb push m/supertonic/onnx /data/local/tmp/d4-st-onnx
adb push d4_inputs.json /data/local/tmp/d4_inputs.json
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \
  'mkdir -p files/models/piper files/models/supertonic/onnx && \
   cp /data/local/tmp/d4-piper.onnx files/models/piper/en_US-lessac-medium.onnx && \
   cp /data/local/tmp/d4-st-onnx/*.onnx files/models/supertonic/onnx/ && \
   cp /data/local/tmp/d4_inputs.json files/d4_inputs.json'"
adb shell svc power stayon true   # #93: no doze mid-benchmark
adb shell am instrument -w -e class com.moronigranja.localttsreader.spiketts.D4ProbeBenchmarkTest \
  com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/d4_probe_results.json
adb pull /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/d4_piper.wav
adb pull /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/d4_supertonic.wav
```

Measured HiBreak results (2026-08-31, decisions #99): Piper RTF **0.50**,
Supertonic 3 RTF **3.92** — see `docs/prints/d4/` and decisions #99. The
supertonic run takes ~8 min (warmup + 3 timed full pipelines at RTF ~3.9);
piper ~1 min.

## espeak-ng Android bundle (decision #32)

Cross-compiles `libespeak-ng.so` (arm64-v8a) at the pinned espeak-ng release tag
and pairs it with the matching `espeak-ng-data` (arch-independent, from the
host installation) — the flat pack for the phonemizer adapter.

```bash
tools/build-espeak-android.sh   # outputs build/espeak-ng-152/{lib,espeak-ng-data}
```

## Android toolchain in Docker (recommended)

The Android SDK + NDK is tens of thousands of files. Baking it into an image keeps the
workspace source-only; Gradle/Maven caches live in named Docker volumes.

```bash
docker build -t localtts-android .       # one-time; downloads several GB
tools/docker-build.sh :core-locate:test  # JVM-only sanity check (no SDK needed)
tools/docker-build.sh assembleDebug      # full APK
```

- `Dockerfile` bakes in JDK 21, command-line tools, `platforms;android-36`,
  `build-tools;36.0.0` and `ndk;27.2.12479018` (NDK is required by tess-two in
  core-ocr later). Bump pins when the toolchain moves.
- `tools/docker-build.sh` runs any Gradle task in the image with `android-gradle` /
  `android-local` volumes for caches (mounted at `/builder/.gradle` and
  `/builder/.local`). Builds run as the **invoking host user** (`docker run --user`),
  so artifacts, the project `.gradle` cache, and volume contents are host-owned — a
  host `./gradlew` is never blocked by root-owned files after a containerized build.
- Cache volumes created by an earlier toolchain are root-owned; the script detects
  that and prints a one-time migration command (`chown` via the image as root). Run
  it once and it stays fixed; alternatively `docker volume rm android-gradle
  android-local` starts fresh (slow first build).
- **No emulator inside Docker** — KVM passthrough is host-dependent and flaky. Use a
  physical phone instead: enable Developer options → Wireless debugging, `adb connect
  <phone-ip>:<port>`, build, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  Keep `adb` on the host, not in the image.

- **Tests are how "done" is proven.** Add a unit test for every parser rule, every
  state transition, and every public function with non-trivial behavior. Parsers must
  have fixture-based tests (valid + malformed inputs).
- Keep instrumented tests minimal and deterministic; prefer them only for audio/
  playback and UI flows that unit tests cannot reach.
- CI must be green before a change is considered complete (roadmap V2, decisions
  #41): `.github/workflows/ci.yml` runs the JVM suite + Docker Android build and
  unit tests on every push/PR and assembles debug+release on tags.

## App preview on a device (T4-2 player)

The player's engine needs its packs + the espeak-ng bundle in the app's
internal storage. V1's settings screen downloads kokoro model/voices/OCR
languages and the espeak-ng pack (decisions #50) on consent (decision #7
stays: no model data is ever bundled); the adb-staging path below is the
offline/CI alternative — and the tessdata + sample-epub staging the
OCR/share/import probes need.

```bash
tools/docker-build.sh :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# stage the kokoro model/voices into the app (espeak-ng is a downloadable pack
# since #50 — settings downloads + auto-stages it; the manual path is gone):
adb push <cache>/packs/kokoro-82m/kokoro-model /data/local/tmp/kokoro-model
adb push <cache>/packs/kokoro-82m/kokoro-voices /data/local/tmp/kokoro-voices
adb shell "run-as com.moronigranja.localttsreader sh -c \\
  'mkdir -p files/packs/kokoro-82m && cp /data/local/tmp/kokoro-model files/packs/kokoro-82m/ && \\
   cp /data/local/tmp/kokoro-voices files/packs/kokoro-82m/'"
```

### Instrumented verification set (locked-screen safe; keep media volume low)

Stage the OCR/import test data alongside the packs (S1/S-debug):

```bash
adb push ~/.cache/local-tts-reader/tessdata/eng.traineddata /data/local/tmp/eng.traineddata
# a real epub (e.g. Gutenberg pg1342-images.epub) for the import probe:
adb push pp.epub /data/local/tmp/pp.epub
# an entity-laden real epub (decisions #53: XML-valid &amp; in OPF metadata) for the second probe case:
adb push nmmng.epub /data/local/tmp/nmmng.epub
adb shell "run-as com.moronigranja.localttsreader sh -c \
  'mkdir -p files/tesseract/tessdata files/import-probe && \
   cp /data/local/tmp/eng.traineddata files/tesseract/tessdata/eng.traineddata && \
   cp /data/local/tmp/pp.epub files/import-probe/pp.epub && \
   cp /data/local/tmp/nmmng.epub files/import-probe/nmmng.epub'"
```

End-to-end checks (run each test CLASS in its own invocation — the harness
trips Room-reopen races when classes share one process):
```
adb shell settings put system volume_music 0
tools/docker-build.sh :app:assembleDebug :app:assembleDebugAndroidTest   # one invocation:
adb uninstall com.moronigranja.localttsreader; adb uninstall com.moronigranja.localttsreader.test
adb install app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# re-stage (uninstall wiped the files) then, per class:
R=com.moronigranja.localttsreader.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.moronigranja.localttsreader.PlaybackE2eTest $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.VoiceSelectionE2eTest $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.PlayPositionE2eTest $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.SharePipelineInstrumentedTest $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.OcrSmokeInstrumentedTest $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.RealEpubImportProbe $R
adb shell am instrument -w -e class com.moronigranja.localttsreader.PtVoiceE2eTest $R
# each asserts its slice through the real service/engine/AudioTrack on the device.
```
Note: the debug keystore is pinned at the repo root (decisions #45), so app +
test APKs from any invocation share a signature — no pairing constraint.

### Staging packs by hand — the `.ready` marker is the Ready gate (2026-08-31)

Packs copied into `files/packs/<engineId>/<packId>` via `run-as`/adb (instead
of the app's download flow) have no `<packId>.ready` marker, so
`PackCache.isVerified` stays false: Settings shows "download required" for
present, size-correct artifacts and `ACTION_PLAY` silently no-ops. The
one-time hash-and-mark path only runs inside the download flow, never on
launch. Recovery: verify the artifact's sha256 against the pinned descriptor
(`KokoroPacks`) and write the marker the app would write:

```bash
adb shell "run-as com.moronigranja.localttsreader sha256sum files/packs/kokoro-82m/kokoro-model"
adb shell "run-as com.moronigranja.localttsreader sh -c \
  'printf \"verified:<descriptor-sha256>\\n\" > files/packs/kokoro-82m/kokoro-model.ready'"
```

Found on the HiBreak (B4 pass, decisions #98): packs staged 2026-08-29 were
marker-blind and playback silently refused until the markers were written.

Device note (2026-08-27, Bigme B6): the playback E2E windows (90 s to first
COMPLETED) assume an S22-class cold engine open. On the B6 e-ink SoC the cold
open of the 325 MB Kokoro model alone can exceed the window (verified at
`356a4ff` — pre-A1/A2/A5+A7 — with the identical "playback did not complete"
at duration 0.0, i.e. no loop logs yet). Use the S22 for the playback/pregen
E2E classes; the B6 suits import/share/OCR classes.
