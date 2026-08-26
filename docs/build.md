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
tools/docker-build.sh :spike-tts:assembleDebug     # build (note: debug keystore
                                                   # regenerates per docker run —
                                                   # uninstall before install!)
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
adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \\
  'mkdir -p files/models && cp /data/local/tmp/kokoro-model files/models/ && \\
   cp /data/local/tmp/kokoro-voices files/models/ && cp /data/local/tmp/corpus.tsv files/'"
# run: locked/off screen is fine — instrumented tests are exempt from the
# process freezer (a launched-but-keyguarded Activity freezes in __refrigerator):
adb logcat -c
adb shell am instrument -w com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s KokoroSpike   # per-run RTF + DONE
# pull results (external files dir):
adb exec-out run-as com.moronigranja.localttsreader.spiketts cat \
  /sdcard/Android/data/com.moronigranja.localttsreader.spiketts/files/kokoro_results.json
```

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
- CI must be green before a change is considered complete — **lands with the CI
  slice (roadmap V2)**; no CI exists yet.

## App preview on a device (T4-2 player)

The player's engine needs its packs + the espeak-ng bundle staged in the app's
internal storage until V1 wires the consent/download UI (decision #7 stays: no
model data is ever bundled).

```bash
tools/docker-build.sh :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# stage model/voices + the espeak bundle (decisions #32) into the app:
adb push <cache>/packs/kokoro-82m/kokoro-model /data/local/tmp/kokoro-model
adb push <cache>/packs/kokoro-82m/kokoro-voices /data/local/tmp/kokoro-voices
adb push build/espeak-ng-152/lib/arm64-v8a/libespeak-ng.so /data/local/tmp/espeak-lib.so
adb push build/espeak-ng-152/espeak-ng-data /data/local/tmp/espeak-data
adb shell "run-as com.moronigranja.localttsreader sh -c \\
  'mkdir -p files/packs/kokoro-82m files/espeak && cp /data/local/tmp/kokoro-model files/packs/kokoro-82m/ && \\
   cp /data/local/tmp/kokoro-voices files/packs/kokoro-82m/ && \\
   cp /data/local/tmp/espeak-lib.so files/espeak/libespeak-ng.so && \\
   cp -r /data/local/tmp/espeak-data/. files/espeak/espeak-ng-data/'"
```

End-to-end playback check (locked-screen safe; keep media volume low):
```
adb shell settings put system volume_music 0
tools/docker-build.sh :app:assembleDebug :app:assembleDebugAndroidTest   # one invocation:
adb uninstall com.moronigranja.localttsreader; adb uninstall com.moronigranja.localttsreader.test
adb install app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# re-stage (uninstall wiped the files) then:
adb shell am instrument -w com.moronigranja.localttsreader.test/androidx.test.runner.AndroidJUnitRunner
# asserts the book plays through the real service+engine+AudioTrack to COMPLETED.
```
Note: app + test APK must come from the SAME `tools/docker-build.sh` invocation
— the debug keystore regenerates per run, and mismatched signatures abort the
instrumentation with a SecurityException.
