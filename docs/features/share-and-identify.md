# Feature plan: share-and-identify (resume-from-share)

**Goal.** The user copies text or screenshots a page in the Kindle app and shares it to
this app. The app identifies which book + chapter/passage the shared text came from
(against books already in the local library) and offers to start listening there.

**Scope of this slice.** Match core + `TextIndex` + share receiver (`text/plain` +
`image/*` OCR). Player-resume wiring is the next slice.

**Constraints (all already in [hard-facts.md](../hard-facts.md) unless restated):**
- Offline, on-device only. No cloud, no account, no telemetry.
- **Matches only books already imported/indexed in the app.** The share feature can only
  recognize books the user has loaded. This is by design (no cloud text DB).
- Reflowable content ⇒ identify **chapter/passage**, not physical pages.
- **OCR is fully open-source: tess-two (Tesseract on Android). Not Google ML Kit.**
- **Multilingual OCR — languages `eng+spa+fra+deu+por+ita` to start** (English, Spanish,
  French, German, Portuguese, Italian). All roman-alphabet, all supported by Tesseract.
  More languages ⇒ slower OCR + larger app + marginally lower per-language accuracy. No
  auto-detection — run the bundled set. Non-roman scripts (CJK, etc.) need character-level
  matching in the matcher — a matcher follow-up, out of scope for this slice.

**Data flow.**
```
Share (text or image)
  → ShareReceiver (ACTION_SEND) extracts payload
  → image → OCRService (tess-two) → text;  text/plain → as-is
  → TextIndex.query(snippet, minConfidence) → MatchResult(bookId, bookTitle, chapterIndex, chapterTitle, passageIndex, confidence)?
  → "Found: book · chapter · passage" + "Start listening here"  (player wiring = next slice)
```
Low confidence (below threshold) ⇒ "not found in library — import it first".

**Match core (pure logic, no Android deps — lives in `core-locate`, see
[modules.md](../modules.md)):**
- `TextNormalizer`: NFKD (accents decomposed) → strip combining marks + apostrophes →
  lowercase → keep only `[a-z0-9 ]` → collapse spaces → trim. ("café" → "cafe",
  "It's" → "its" — matches OCR that drops apostrophes; other punctuation → word separator.)
- `TextMatcher`: word n-grams **n=4 with 3-gram fallback credit** for windows containing
  local OCR typos (chosen by measurement: matches stay ≥0.6 under realistic noise while
  cross-book text scores ≤0.05); snippets <4 tokens → unigram recall; best passage wins,
  ties → earliest.
- `TextIndex`: holds `IndexedBook`s (each with ordered `Passage`s), precomputes per-passage
  gram sets, `query(snippet, minConfidence): MatchResult?` (null below threshold).
- **Confidence threshold.** Recall fraction: 1.0 = every word-group in the shared text
  appears in a passage (verbatim/contiguous/truncated). Default threshold **0.6**,
  **configurable in settings**. Below threshold ⇒ "not found". Chosen high enough to avoid
  wrong matches; tunable. A normal shared sentence/paragraph chunk scores well above 0.6.

**Cross-cutting requirement.** Every book the **file-import pipeline parses must also be
added to the `TextIndex`** (capability 1 in [agents.md](../../agents.md)). The share
feature matches against this index; parsing without indexing silently breaks
share-and-identify. The index is populated during import; `TextIndex` itself is an
in-memory layer over the library store, rebuilt at launch.

**Index/segmentation contract (C4).** The import pipeline runs
`BookSegmentation.segment(parsedBook)` **before** `TextIndex.add(...)`: paragraph-grain
passages, front/back-matter chapters stripped (position-guarded), passages over 100
words split at sentence boundaries. The passage is the unit of both matching and
resume, so its grain decides match precision; passages must stay stable across
re-parses. Segmented chapters keep their original spine indexes.

**Status.** `core-locate` implemented and **24 JUnit tests pass** — verified in this
environment by compiling with the standalone Kotlin 2.4.10 compiler and running via JUnit
Platform console 6.1.3 (`java -jar junit-platform-console-standalone.jar execute`).
`TextMatcher`/`TextIndex` are pure JVM; the repo ships a Gradle wrapper plus three
JVM-only modules (`core-model`, `core-ebook`, `core-locate`), so `./gradlew test`
works on any machine without the Android SDK. OCR (`tess-two`) and the `ShareReceiver` are Android components — designed
here, verified on-device later.

**Recorded decisions.** Default confidence threshold **0.6, configurable in settings**;
OCR = tess-two, `eng+spa+fra+deu+por+ita`, roman-alphabet curated set; slice = match
core + index + share receiver, text + OCR; import pipeline must also index each book.
