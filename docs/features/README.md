# Feature plans

One doc per planned feature, written when the feature is planned in detail (before its
implementation slice). Roadmap (docs/roadmap.md) tracks status.

| Feature | Doc | Status |
|---|---|---|
| Share-and-identify (resume-from-share) | [share-and-identify.md](share-and-identify.md) | Planned; match core + index + importer done; share receiver/OCR pending F1 |
| Content / import (library) | Roadmap C + [architecture.md](../architecture.md) §3; full UX doc pending | Domain core done (parsers, segmentation, importer); SAF/UI pending F1 |
| Player (playback, transport, resume, pre-generation) | Pending (roadmap T4/T5) | Not planned in detail |
| TTS core (TTSEngine, language packs) | Pending (roadmap T1-T3); engine facts in [hard-facts.md](../hard-facts.md) | Design facts fixed; implementation pending F1 |
| Settings (threshold, engines, languages) | Pending (roadmap V1) | Not planned in detail |

**Convention:** a feature's doc is the source of truth for its decisions and status;
the roadmap stays the index of work items. Update a feature doc when a convention or
constraint for it changes (see [conventions.md](../conventions.md), definition of done).
