# Module layout (proposed; keep it flat until it hurts)

```
core-model/       Book, Chapter/Section, TextPassage, LibraryEntry (no Android deps)
core-ebook/       EBookParser interface + format parsers (epub, azw3/kf8, mobi/azw: PalmDOC LZ77 + HUFF/CDIC); defensive
core-locate/      TextIndex + matcher (n-gram recall over indexed books); no Android deps
core-ocr/         on-device OCR (tess-two) behind OCRService interface; eng+spa+fra+deu+por+ita
core-tts/         TTSEngine interface + engine impls (CosyVoice3 primary, Kokoro fallback); model + language-pack download/caching
core-persistence/ Room schema, daos, migrations
feature-library/  list/search/import books (Compose)
feature-reader/   text display + navigation
feature-share/    ACTION_SEND receiver: text + screenshot → TextIndex → resume point
feature-player/   playback service, transport, progress
app/              DI graph, composition root, app bar, settings
```

Prefer one cohesive module per responsibility over feature sprawl. Add modules only when
a circular dependency or real build isolation forces it.
