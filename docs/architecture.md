# Architecture

Companion to [modules.md](modules.md) (module list) and [conventions.md](conventions.md)
(patterns). This is the load-bearing structure: dependency graph, data flows, and the
contracts every change must respect. If a change bends a contract, update this doc with
the change.

## 1. Guiding shape

- **Pure core, thin edges.** All business logic lives in `core-*` modules with no
  Android dependency — pure JVM, unit-testable in this environment. Android appears
  only as thin adapters: activities/receivers (feature-*), Room (core-persistence),
  SAF plumbing, Compose UI, Hilt wiring (app).
- **One canonical domain model.** `core-model` (Book, Chapter, TextPassage,
  LibraryEntry) is the single vocabulary; no module defines its own duplicate types.
- **Every public behavior has a test** (conventions.md §Definition of done).

## 2. Modules & dependency graph

```
core-model      canonical domain: Book(id, title, authors, chapters), Chapter, TextPassage, LibraryEntry
core-ebook      EBookParser + EBookFormats + EpubParser/MobiParser → Book;
                BookSegmentation (grain, front/back matter); BookImporter (parse→segment→index)
core-locate     TextIndex, TextMatcher, TextNormalizer, MatchResult; IndexRebuilder (launch-time sync)
core-ocr        (live) tess-two behind OcrEngine/TessTwoOcrEngine + stager; six pinned legacy-traineddata packs (#36)
core-tts        (live) TTSEngine interface + pack registry; model/language-pack download, verify + caching
core-player     (live) v1 player state machine: transport, transactional writes, ring, sleep timer, bookmarks; PlayerStore contract
core-persistence (live) Room v2: books, cached passages, progress (offset+speed), settings, bookmarks, position_history; LibraryStore + PlayerStore impls
feature-library (live) SAF import + library list (Compose, Hilt); search pending
feature-player (live, T4-2) PlaybackService (MediaSession, focus, foreground) + docked read-along ReaderScreen
app             (live) Hilt composition root, manifest, MainActivity → LibraryScreen
```

Current dependency edges:

```
core-model  ←  core-ebook  (parsers return Book)
core-model  ←  core-locate (TextIndex consumes Book)
core-model  ←  core-persistence  (persists LibraryEntry; LibraryStore contract)
core-locate ←  core-ebook  (BookImporter indexes into TextIndex — the import contract)
core-persistence ←  feature-library  (Hilt provides the Room-backed LibraryStore)
core-persistence ←  core-player  (RoomPlayerStore implements PlayerStore)
core-player  ←  feature-library  (Hilt provides the PlayerStore binding)
core-player/tts/persistence ← feature-player (PlaybackService + ReaderScreen drive the machine+engine)
core-ebook  ←  feature-library  (SAF sources → BookImporter)
feature-library ←  app          (Hilt wires the composition root to the library screen)
```

Rules:
- `core-*` modules contain no `android.*` imports, no framework — stdlib/JDK only.
- Dependencies point toward `core-model`; nothing depends on `app`/`feature-*`.
- `feature-*` never depend on each other; `app` wires them.
- A component lives in the module of its primary responsibility. Orchestration that
  spans modules (the import pipeline) lives in the module of its domain (core-ebook)
  rather than a new module, until a circular dependency forces a split.
- Add modules only when a circular dependency or real build isolation forces it
  (modules.md).

## 3. Content capability data flow

```
file (SAF) ─EBookSource─▶ EBookFormats.parserFor(fileName) ─▶ Parser.parse ─▶ Book (raw)
      ─▶ BookSegmentation.segment ─▶ TextIndex.add(book)  ─▶ LibraryEntry → Room
            (passages cached in the same transaction — the launch-time rebuild source)
```

- **Identity**: `Book.id` = SHA-256 of the container bytes. Content-addressed: no
  cloud, deterministic across machines, idempotent re-imports.
- **Passage grain**: the passage (paragraph; long passages split at sentence
  boundaries) is the unit of matching **and** of resume. Stable across re-parses.
  Front/back-matter chapters are stripped by segmentation (position-guarded).
- **Index contract**: import MUST run `BookSegmentation.segment` before
  `TextIndex.add` (docs/features/share-and-identify.md).
- **Failure contract**: bad input yields typed failures
  (`ImportOutcome.Failed` + `ImportFailureReason`), never throws, never mutates the
  index.

## 4. Identification capability data flow

```
shared snippet → normalize → word n-grams → recall vs every indexed passage
  → MatchResult(bookId, bookTitle, chapterIndex, chapterTitle, passageIndex, confidence) or null below threshold (0.6, configurable)
```

- `TextIndex`: in-memory, synchronized writes, snapshot reads (queries never block
  import); per-passage gram sets precomputed at add time; linear scan until the
  inverted-index follow-up. Populated on import and **rebuilt at launch** from Room's
  cached parses by `IndexRebuilder` — never re-parses a source file; mirror-set
  semantics (ids absent from the cache are purged), idempotent under concurrent
  imports (P2).
- OCR (live, core-ocr): tess-two behind `OCRService`, languages downloadable
  (`eng+spa+fra+deu+por+ita` start), screenshot downscale; feeds the same snippet path
  (S1 shipped, #36).

## 5. Concurrency model

- Coroutines: `Dispatchers.IO` for file/parse/OCR/engine work, `Main` for UI;
  cancellation propagates (long TTS queues, scans).
- Cross-thread state lives in the synchronized surfaces of `TextIndex`;
  query uses snapshots so concurrent import never blocks readers.
- Everything else passes immutable value types (Book, LibraryEntry, MatchResult).

## 6. Contracts that must not silently break

1. **One domain model** — core-model types everywhere; no parallel Book/Passage.
2. **Content-hash identity** — id comes from bytes, not metadata or file name.
3. **Import ⇒ index** — parsing without segment+index silently kills share-and-identify.
4. **Passages stable & bounded** — segmentation output must not drift between
   re-parses of the same file.
5. **TTS assets never bundled** — models/language packs are runtime downloads,
   explicit and resumable (docs/hard-facts.md).
6. **DRM never in-app** — encrypted files rejected up front; deDRM stays out-of-app.
7. **Share result = location** — MatchResult carries (bookId, chapter, passage);
   the player resumes there.

## 7. Status

2026-08-28: the reading-speed selector is removed — playback pinned 1.0×,
stored per-book speeds ignored on resume (decisions #71). The speed model is
retained unchanged: progress `speed` column, `PregenKey` speed + path layout,
`SynthesisRequest.speed`, `PlayerStateMachine.setSpeed`, and
`PassageOutput.play` tempo (setPlaybackRate, decisions #52) — "per-request
engine speed" (below) remains the engine contract. Revisit planned.

2026-08-26 (later): T4-2 lands — feature-player PlaybackService (MediaSession,
audio focus/ducking, becoming-noisy, media notification, 1 s sleep tick),
per-request engine speed, machine-advance LOADING fix, head-polling AudioTrack
completion, and the docked ReaderScreen with #31 sentence highlighting; verified
end-to-end on the S22 (instrumented full-book playback, decisions #34).

2026-08-26: core-player lands (T4-1, decisions #33) — the v1 player state
machine on top of schema v2 (progress offset/speed, bookmarks, position
ring); 37 new tests (20 core-player + 17 persistence). T4-2 (feature-player:
MediaSession, audio output, docked Compose) consumes it via LOADING →
PLAYING + PassageAdvanced/PauseRequested/PlaybackCompleted events.

2026-08-25: core-model, core-ebook (epub + mobi/kf8 + segmentation + importer),
core-locate, core-persistence (P1/P2 Room), and core-tts — TTSEngine interface,
pack registry, download manager (T1) **and the Kokoro-82M engine (T2, decisions
#25/#28)**: espeak-ng phonemization via JNA, vocab-filtered tokens, balanced
≤510-phoneme windows, ORT inference behind a compileOnly Java-API seam, librosa
trim port, timing-aware pauses, PCM16 out; first pinned pack descriptors
(kokoro-model fp32 + kokoro-voices, model-files-v1.1, exact SHA-256s). **179
tests green** (core-locate 32 + core-ebook 50 + core-persistence 9 +
feature-library 7 + core-tts 81). `app` scaffold (F1), `feature-library`
(C5/C6), and the launch-time index rebuild (P2) are live in the Docker
toolchain. Since then (2026-08-25 → 08-27): core-ocr (S1, #36), the share
receiver + resume wiring (S2/S3, #37/#38), the player (T4, #33/#34/#51/#52), and
the Android adapters (`AndroidHttpTransport` #36, espeak-ng phonemizer bundle as
a downloadable pack #32/#50) are all live — nothing pending in this paragraph.
