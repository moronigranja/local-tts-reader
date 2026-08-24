# Build, run, test

```bash
./gradlew assembleDebug                 # build an installable APK
./gradlew testDebugUnitTest             # unit tests (logic, parsers, state)
./gradlew assembleDebugAndroidTest      # instrumented tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew ktlintCheck                   # or detekt, per repo config
```

The repo currently contains a JVM-only Gradle setup (`settings.gradle.kts` + the
`core-locate` module), so `./gradlew :core-locate:test` works without the Android SDK.
The Android modules and their targets arrive with the app foundation slice.

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
  `android-local` volumes for caches. Built artifacts are root-owned (fine for
  `adb install`).
- **No emulator inside Docker** — KVM passthrough is host-dependent and flaky. Use a
  physical phone instead: enable Developer options → Wireless debugging, `adb connect
  <phone-ip>:<port>`, build, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  Keep `adb` on the host, not in the image.

- **Tests are how "done" is proven.** Add a unit test for every parser rule, every
  state transition, and every public function with non-trivial behavior. Parsers must
  have fixture-based tests (valid + malformed inputs).
- Keep instrumented tests minimal and deterministic; prefer them only for audio/
  playback and UI flows that unit tests cannot reach.
- CI must be green before a change is considered complete.
