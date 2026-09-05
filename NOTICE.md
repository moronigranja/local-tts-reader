# Third-party notices

Ayvu (local-tts-reader) — Copyright © 2026 moronigranja — is licensed under the
GNU General Public License v3.0 (see `LICENSE`).

This distribution and its source bundle or invoke the following third-party
components (revisited at each release — decisions #126):

## Bundled in the APK

- **ONNX Runtime** (Android AAR) — MIT License —
  <https://github.com/microsoft/onnxruntime>
- **JNA** (Java Native Access) — Apache-2.0 OR LGPL-2.1-or-later —
  <https://github.com/java-native-access/jna>
- **Jetpack Compose / Material 3 / Room / Hilt / WorkManager,
  kotlinx-coroutines, Kotlin stdlib** — Apache-2.0 (per the AndroidX and
  Kotlin project licenses)

## Downloaded at runtime (packs; never embedded in the APK)

Packs are fetched from this project's GitHub releases into the app's internal
storage (decision #7: no model data is bundled):

- **Kokoro-82M TTS** model + voices — Apache-2.0 —
  <https://github.com/hexgrad/kokoro>
- **espeak-ng** phonemizer bundle (`libespeak-ng.so` + `espeak-ng-data`) —
  GPL-3.0-or-later with the espeak-ng toolchain exception —
  <https://github.com/espeak-ng/espeak-ng>
- **Tesseract OCR** data (tessdata) — Apache-2.0 —
  <https://github.com/tesseract-ocr/tessdata>

Full license texts accompany their upstream distributions.