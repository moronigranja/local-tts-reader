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
