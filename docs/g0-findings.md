# G0 — Narration-quality listening findings (decisions: roadmap G0)

Status: **scaffold — listening pass pending owner.** The corpus (377 entries,
9 languages × 15 categories), the device WAVs, the IPA column, and the device
measurement are complete; mispronunciation classification for the five Roman
languages + en-GB requires the owner's native ear. ja/zh(cmn)/hi are limited
to "synthesizes finite, no crash, no obviously wrong length" plus the IPA
spot-checks below — mispronunciation classification for those three is
recorded as a limitation, not attempted by a non-native ear.

Artifacts:
- Corpus source: `tools/g0-corpus.tsv` (lang ␥ category ␥ text)
- Device corpus: `core-tts/g0_corpus.tsv` (id ␥ lang ␥ category ␥ text ␥ phonemes),
  produced by `./gradlew :core-tts:g0Corpus` through the production
  `NormalizingPhonemizer → EspeakPhonemizer` stack
- Device results: `build/g0-device/` — `g0_results.json` + one
  `g0_<id>_<lang>_<category>.wav` per entry (S22, `G0CorpusBenchmarkTest`,
  Kokoro fp32, first female voice per family)

## Method (how to finish this doc)

Listen to each WAV and cross-check the `phonemes` column. For every heard/read
mispronunciation, add a typed class in the template below. Every class that is
a normalization fix gets an explicit G1 rule (ordered literal regex +
replacement), so the G1 `PronunciationNormalizer` extension consumes this doc
directly. Engine-level defects (prosody, voice quality) get "none — engine
  level, not a normalization fix".

Template:

```
### <class-kebab-name>
- Category: <category token>
- Example: "<corpus text>"
- Produced (IPA/approx): "<phonemes or phonetic rendering>"
- Expected: "<correct spoken form>"
- Frequency: <count>/<corpus size>
- G1 rule: literal-ordered replacement (regex + replacement string) OR "none —
  engine level, not a normalization fix"
```

## Confirmed findings

### ms-honorific-letter-by-letter
- Category: `honorific`
- Example: "Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon."
- Produced (raw espeak, pre-normalizer): `ˌɛmˈɛs. dˈæləwˌeɪ …` ("M S" letter
  names) — the known espeak-ng 1.52.0 regression family.
- Expected: `/mɪz/`
- Status: **already fixed.** The shipped `PronunciationNormalizer` rule
  (`Ms. → Miz`, bounded by non-alphanumerics) renders `mˈɪz` in the corpus
  row `0004` — verified in `g0_corpus.tsv`. The corpus exercises the
  regression; the fix holds.
- Frequency: 2/377 (`Ms.` appears in the en-US and en-GB `honorific` lines)
- G1 rule: none (shipped rule confirmed by this corpus).

## Candidate classes (host IPA reading only — confirm by ear before G1)

These are IPA-column observations, not listening verdicts; each needs owner
confirmation before a G1 rule lands.

### year-decade-read-digit-by-digit
- Category: `date`
- Example: "The 1800s, the '90s, and 2024 C.E. all appear in the timeline."
- Produced (en-US row 0015 IPA): `ðə wˈʌn θˈaʊzənd ˈeɪthˈʌndɹɪd z` — "the one
  thousand eight hundred s"
- Expected (idiomatic): "the eighteen hundreds"
- Frequency: TBD
- G1 rule: TBD (candidate: `18xx`-range year + trailing `s` → "eighteen <rest>
  hundreds"; needs the full class enumerated by ear first).

## Non-findings worth recording

- `https://example.com/path?q=1&x=2` → espeak reads it as
  `ˌeɪtʃtˌiːtˈiːpˌiːˈɛs:slˈæʃslæʃ …` ("H T T P S colon slash slash …") —
  verbose but letter-accurate; whether to compact is an owner call, not a
  mispronunciation.
- `&&` → `ˈændænd` ("and and"); `e.g.` → `ˈiː.dʒˈiː.`; `i.e.` → `ˈaɪ.ˈiː.` —
  plausible renderings, confirm by ear.

## ja / cmn / hi spot-checks (IPA only, non-native limitation)

- ja `number` row 0241 ("人口は1,204,567人…"): espeak-ja emits
  `(en)tʃˈaɪniːz(ja)lˈe̞tə` — the literal words "Chinese letter" — for every kanji
  it cannot map. This is the espeak-ng ja voice's own dictionary gap, upstream of
  the engine; not a normalization fix.
- cmn `currency` row 0340 ("它花了1,234.56元…"): digits and symbols render as
  plausible Mandarin syllables with correct tone digits (`lˈə1 jˈi5 ˌər5pai2
  sˈa5ns.i.ɜsi̪5 tˈiɛɜn` ≈ 一千二百三十四点…); currency names carried through.
- hi `honorific` row 0313 ("श्रीमती दल्लो…"): `ʃɾˈiːmtˌi dˈʌlloː` — plausible
  Devanagari readings with retroflexes; needs a native ear for a verdict.

All three languages synthesized finite audio on device (no `error` rows in
`g0_results.json`); lengths are proportionate to text length. Per plan, their
mispronunciation classification is a recorded limitation, not attempted here.
