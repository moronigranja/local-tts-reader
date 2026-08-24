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

- **Tests are how "done" is proven.** Add a unit test for every parser rule, every
  state transition, and every public function with non-trivial behavior. Parsers must
  have fixture-based tests (valid + malformed inputs).
- Keep instrumented tests minimal and deterministic; prefer them only for audio/
  playback and UI flows that unit tests cannot reach.
- CI must be green before a change is considered complete.
