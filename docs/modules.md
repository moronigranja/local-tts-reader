# Module layout (built modules are live; the rest are planned — split only when a cycle forces it)

```
core-model/       Book, Chapter/Section, TextPassage, LibraryEntry (no Android deps)
core-ebook/       EBookParser interface + format parsers (epub, azw3/kf8, mobi/azw: PalmDOC LZ77 + HUFF/CDIC), passage segmentation + BookImporter (parse→segment→index); defensive
core-locate/      TextIndex + matcher (n-gram recall over indexed books); no Android deps
core-ocr/         on-device OCR (tess-two) behind OCRService interface; eng+spa+fra+deu+por+ita
core-tts/         LIVE — TTSEngine interface + pack registry + download manager (explicit, resumable, verified, cached); engine impls land with T2 (Kokoro) / the gated CosyVoice3
core-persistence/ LIVE — Room schema v1 (books, cached passages, progress, settings), DAOs, LibraryStore impl + launch-time index rebuild (P1/P2)
feature-library/  SAF import + library list UI (Compose, Hilt) — C5/C6; search pending
feature-reader/   text display + navigation (planned)
feature-share/    ACTION_SEND receiver: text + screenshot → TextIndex → resume point (planned)
feature-player/   playback service, transport, progress (planned)
app/              Hilt composition root, MainActivity → LibraryScreen — F1/C5/C6; app bar/settings pending
```

Prefer one cohesive module per responsibility over feature sprawl. Add modules only when
a circular dependency or real build isolation forces it.
