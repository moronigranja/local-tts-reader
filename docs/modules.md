# Module layout (built modules are live; the rest are planned — split only when a cycle forces it)

```
core-model/       Book, Chapter/Section, TextPassage, LibraryEntry (no Android deps)
core-ebook/       EBookParser interface + format parsers (epub, azw3/kf8, mobi/azw: PalmDOC LZ77 + HUFF/CDIC), passage segmentation + BookImporter (parse→segment→index); defensive
core-locate/      TextIndex + matcher (n-gram recall over indexed books); no Android deps
core-ocr/         on-device OCR (tess-two) behind OCRService interface; eng+spa+fra+deu+por+ita
core-tts/         LIVE — TTSEngine + Kokoro-82M impl (T2, decisions #25/#28): espeak-ng phonemization (JNA), ORT session behind a compileOnly seam, pinned fp32 packs (kokoro-model + kokoro-voices @ model-files-v1.1). CosyVoice3 stays gated metadata-only
core-persistence/ LIVE — Room schema v2 (books, cached passages, progress + offset/speed, settings, bookmarks, position_history), DAOs, LibraryStore + PlayerStore impls, launch-time index rebuild (P1/P2, T4-1 #33)
core-player/      LIVE — v1 player state machine (T4-1, #33): transport, transactional writes, undo ring, sleep timer, speed, bookmarks; PlayerStore contract; T5 pre-generation (PregenQueue + PcmPassageCache, #35)
core-ocr/         LIVE — S1: OcrEngine seam, OcrImage/OcrResult, screenshot downscaler, six pinned legacy-traineddata packs (#36)
feature-ocr/     LIVE — S1: TessTwoOcrEngine (tess-two 9.1.0) + TessDataStager + Hilt; LSTM models unusable on this binding (see #36)
feature-settings/ LIVE — V1: settings screen, packs download UI, voice picker + favorites, threshold, OCR langs, theme; AndroidHttpTransport
feature-share/    LIVE — S2: ACTION_SEND gateway (text+image), typed ShareSnippetResolver over the shared index, found/not-found UX (#37)
feature-library/  SAF import + library list UI (Compose, Hilt) — C5/C6; search pending
feature-reader/   text display + navigation (planned)
feature-share/    ACTION_SEND receiver: text + screenshot → TextIndex → resume point (planned)
feature-player/   playback service, transport, progress (planned)
app/              Hilt composition root, MainActivity → LibraryScreen — F1/C5/C6; app bar/settings pending
```

Prefer one cohesive module per responsibility over feature sprawl. Add modules only when
a circular dependency or real build isolation forces it.
