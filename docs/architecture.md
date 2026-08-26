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
core-ocr        (pending) tess-two OCR behind OCRService; language packs downloadable
core-tts        (live) TTSEngine interface + pack registry; model/language-pack download, verify + caching
core-persistence (live) Room: books, cached passages, progress, settings; LibraryStore impl
feature-library (live) SAF import + library list (Compose, Hilt); search pending
feature-reader/share/player (pending) Compose UI + Android services
app             (live) Hilt composition root, manifest, MainActivity → LibraryScreen
```

Current dependency edges:

```
core-model  ←  core-ebook  (parsers return Book)
core-model  ←  core-locate (TextIndex consumes Book)
core-model  ←  core-persistence  (persists LibraryEntry; LibraryStore contract)
core-locate ←  core-ebook  (BookImporter indexes into TextIndex — the import contract)
core-persistence ←  feature-library  (Hilt provides the Room-backed LibraryStore)
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
- OCR (pending, core-ocr): tess-two behind `OCRService`, languages downloadable
  (`eng+spa+fra+deu+por+ita` start), screenshot downscale; feeds the same snippet path.

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

2026-08-25: core-model, core-ebook (epub + mobi/kf8 + segmentation + importer),
core-locate, core-persistence (P1/P2 Room), and core-tts (T1: TTSEngine interface,
pack registry, download manager) implemented — **136 tests green** (core-locate 32 +
core-ebook 50 + core-persistence 9 + feature-library 7 + core-tts 38).
`app` scaffold (F1), `feature-library` (C5/C6), and the launch-time index rebuild
(P2) are live in the Docker toolchain. Pending: the Kokoro engine impl (T2),
core-ocr, the share receiver, and the player.
