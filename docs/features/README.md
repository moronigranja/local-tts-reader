# Feature plans

One doc per planned feature, written when the feature is planned in detail (before its
implementation slice). Roadmap (docs/roadmap.md) tracks status.

| Feature | Doc | Status |
|---|---|---|
| Share-and-identify (resume-from-share) | [share-and-identify.md](share-and-identify.md) | Planned; match core + index + importer done; share receiver/OCR pending (roadmap S1/S2) |
| Content / import (library) | Roadmap C + [architecture.md](../architecture.md) §3; full UX doc pending | Done: parsers, segmentation, importer, SAF import + library UI (C1–C6, `feature-library`/`app`); 98 tests total |
| Persistence (Room library store, index rebuild) | Roadmap P1/P2 | Done: Room v1 schema, LibraryStore contract (InMemory + Room impls), launch-time index rebuild from cached parses |

**Convention:** a feature's doc is the source of truth for its decisions and status;
the roadmap stays the index of work items. Update a feature doc when a convention or
constraint for it changes (see [conventions.md](../conventions.md), definition of done).
