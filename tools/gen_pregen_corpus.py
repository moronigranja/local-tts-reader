#!/usr/bin/env python3
"""Build the pregen-scale corpus for the D2 2-engine parallel pregen spike.

Sources are public-domain book texts (Project Gutenberg), phonemized with the
same backend + post-processing the device pipeline uses (espeak-ng via the
`phonemizer` package, `preserve_punctuation=True with_stress=True`, `espeak`
backend for en-us/pt-br). The exact phonemize call was validated byte-for-byte
against the staged `corpus.tsv` rows already on the device (2026-09-03) before
this generator was written.

Gutenberg IDs (pinned in the decision record):
  - pride-and-prejudice #1342 (en-us)
  - dom-casmurro #55752 (pt-br)

Output: corpus_pregen.tsv  (text<TAB>language<TAB>phonemes, one passage/line)
Staged to the device as `files/corpus_pregen.tsv`; consumed by
`PregenParallelRunner` (spike-tts). The standard 2-passage `corpus.tsv` used by
the other benchmark legs is left untouched.

Usage:
  python3 tools/gen_pregen_corpus.py --pp /tmp/pp.txt --dc /tmp/dc.txt \
      --out corpus_pregen.tsv --per-lang 8 --sentences 3
"""

import argparse
import re

from phonemizer import phonemize

# Chapter / volume heading lines to drop from the source prose.
HEADING = re.compile(
    r"^\s*(?:" +
    r"(?:Chapter|CHAPTER|Capítulo|CAPÍTULO|Capitulo|CAPITULO|VOLUME|Volume)" +
    r"(?:\s+\d+|[IVXLC]+)?\.?\s*$" +
    r"|[IVXLC]+\.\s*$" +
    r")\s*$"
)
# Widths vary, so protect via a lookahead marker on the following space+capital
# instead of a variable-width lookbehind (fixed-width only in Python re).
# "Mr. X" -> splitable only when the period is NOT after a known abbreviation.
ABBR_TRAIL = re.compile(r"\b(Mr|Mrs|Ms|Dr|St|No|Sr|Jr|Capt|Col|Gen|Lt|Rev|etc)\.", re.IGNORECASE)


def body_lines(path, start_marker, end_marker):
    lines = open(path, encoding="utf-8").read().splitlines()
    start = next(i for i, ln in enumerate(lines) if start_marker in ln)
    end = next(i for i, ln in enumerate(lines) if end_marker in ln)
    prose = []
    for ln in lines[start + 1:end]:
        stripped = ln.strip()
        if not stripped or HEADING.match(stripped):
            continue
        prose.append(stripped)
    return " ".join(prose)


def split_sentences(text):
    # Protect common abbreviations before splitting on sentence boundaries:
    # "Mr. Smith" must stay one fragment, but the period after a real
    # sentence word splits. Mark the abbreviation period, split, then restore.
    guarded = ABBR_TRAIL.sub(lambda m: m.group(0) + "<AB>", text)
    parts = re.split(r"(?<=[.!?…])\s+(?=[A-Z«\"“])", guarded)
    return [p.replace("<AB>", "") for p in parts if p]


def chunk_sentences(sentences, per):
    chunks = []
    for i in range(0, len(sentences), per):
        blob = " ".join(sentences[i:i + per])
        if len(blob) > 40:
            chunks.append(blob)
    return chunks


def build(passages, language):
    rows = []
    for blob in passages:
        phen = phonemize(
            blob, language=language, backend="espeak",
            preserve_punctuation=True, with_stress=True,
        )
        rows.append(f"{blob}\t{language}\t{phen.strip(' ')}")
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pp", required=True, help="Pride & Prejudice Gutenberg txt")
    ap.add_argument("--dc", required=True, help="Dom Casmurro Gutenberg txt")
    ap.add_argument("--out", default="corpus_pregen.tsv")
    ap.add_argument("--per-lang", type=int, default=8, help="passages per language")
    ap.add_argument("--sentences", type=int, default=3, help="sentences per passage")
    args = ap.parse_args()

    en_text = body_lines(
        args.pp,
        "It is a truth universally acknowledged",
        "*** END OF THE PROJECT GUTENBERG EBOOK PRIDE AND PREJUDICE ***",
    )
    pt_text = body_lines(
        args.dc,
        "Uma noite destas",
        "*** END OF THE PROJECT GUTENBERG EBOOK DOM CASMURRO ***",
    )

    en = chunk_sentences(split_sentences(en_text), args.sentences)[:args.per_lang]
    pt = chunk_sentences(split_sentences(pt_text), args.sentences)[:args.per_lang]
    rows = build(en, "en-us") + build(pt, "pt-br")

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    est_chars = sum(len(r.split("\t")[0]) for r in rows)
    print(f"wrote {len(rows)} passages to {args.out} "
          f"({len(en)} en-us, {len(pt)} pt-br); ~{est_chars} chars source text")


if __name__ == "__main__":
    main()
