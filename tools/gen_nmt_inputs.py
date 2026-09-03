#!/usr/bin/env python3
"""Host-tokenize the NMT spike inputs (roadmap Phase J, 2026-09-02).

One corpus, four source->target pairs: it->es, en->pt-br, en->it, es->en.
Corpus is FLORES-101 (meta, dev+devtest): 20 aligned sentence pairs per
direction for the quality leg (chr-F vs `ref`) and 10 longer passages
(~120-250 tokens, consecutive devtest sentences joined) per direction for
the RTF leg. The host folds in every family-specific target-language
conditioning — the device runner never re-derives it (the D3/D4
host-prepared-inputs pattern, decisions #93):

  - Marian base (opus-mt-it-es / -en-it / -es-en): plain normalized source,
    decoder_start = [<pad>].
  - Marian tc (opus-mt-tc-big-en-pt): target token PREPENDED TO THE SOURCE
    (">>pob<< " — Brazilian Portuguese; >>por<< is European pt),
    decoder_start = [<pad>].
  - M2M-100 / SMaLL-100: source unchanged, decoder_start = [lang_id(tgt)].

Output: translate_inputs.json
  {"pairs": {"<dir>": {"models": {"<id>": [ {src, input_ids, attention_mask,
     decoder_start, ref, kind} ]}}}}

en->pt note: FLORES `eng-por` reference is EUROPEAN Portuguese, so its
automated chr-F slightly understates pt-BR quality; the owner's blind read
is the authoritative pt-BR gate (decisions #101).

Usage:
  python3 tools/gen_nmt_inputs.py [--flores m/flores/flores101_dataset]
                                 [--out translate_inputs.json]
                                 [--quality 20] [--passages 10]
"""

import argparse
import json
import os
import sys

# pair dir -> (flores src, flores ref, m2m100 lang codes src/tgt)
PAIRS = {
    "it-es": ("ita", "spa", ("it", "es")),
    "en-pt-br": ("eng", "por", ("en", "pt")),
    "en-it": ("eng", "ita", ("en", "it")),
    "es-en": ("spa", "eng", ("es", "en")),
}

MODELS = {
    "it-es": ["opus-mt-it-es", "m2m100-418M", "small100"],
    "en-pt-br": ["opus-mt-tc-big-en-pt", "m2m100-418M", "small100"],
    "en-it": ["opus-mt-en-it", "m2m100-418M", "small100"],
    "es-en": ["opus-mt-es-en", "m2m100-418M", "small100"],
}

MARIAN_BASE = {"opus-mt-it-es", "opus-mt-en-it", "opus-mt-es-en"}
TC_PREFIX = {"opus-mt-tc-big-en-pt": ">>pob<< "}


def flores_lines(flores_dir, split, lang):
    path = os.path.join(flores_dir, split, f"{lang}.{split}")
    with open(path, encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip()]
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--flores", default="m/flores/flores101_dataset")
    ap.add_argument("--out", default="translate_inputs.json")
    ap.add_argument("--quality", type=int, default=20)
    ap.add_argument("--passages", type=int, default=10)
    args = ap.parse_args()

    from transformers import AutoTokenizer

    out = {"pairs": {}}
    for pair, (fsrc, fref, (m2m_src, m2m_tgt)) in PAIRS.items():
        src_dev = flores_lines(args.flores, "dev", fsrc)
        ref_dev = flores_lines(args.flores, "dev", fref)
        src_dt = flores_lines(args.flores, "devtest", fsrc)
        ref_dt = flores_lines(args.flores, "devtest", fref)
        assert len(src_dev) == len(ref_dev) and len(src_dt) == len(ref_dt)

        quality = list(zip(src_dev[: args.quality], ref_dev[: args.quality]))
        # aligned passages: devtest is sentence-aligned across languages, so
        # joining the same sentence count on both sides gives aligned pairs
        ptexts = []
        i = 0
        while i < len(src_dt) and len(ptexts) < args.passages:
            words, start = 0, i
            while i < len(src_dt) and words < 110:
                words += len(src_dt[i].split())
                i += 1
            ptexts.append((" ".join(src_dt[start:i]), " ".join(ref_dt[start:i])))


        models_out = {}
        for model in MODELS[pair]:
            if model in MARIAN_BASE or model in TC_PREFIX:
                tok = AutoTokenizer.from_pretrained(
                    {"opus-mt-it-es": "Helsinki-NLP/opus-mt-it-es",
                     "opus-mt-en-it": "Helsinki-NLP/opus-mt-en-it",
                     "opus-mt-es-en": "Helsinki-NLP/opus-mt-es-en",
                     "opus-mt-tc-big-en-pt": "Helsinki-NLP/opus-mt-tc-big-en-pt"}[model]
                )
                prefix = TC_PREFIX.get(model, "")
                decoder_start = [tok.pad_token_id]
            elif model == "small100":
                # SMALL100Tokenizer (MBART-style): the TARGET lang id is
                # PREPENDED to the source and eos appended ([tgt_lang, X, eos])
                # — AutoTokenizer wrongly falls back to M2M100Tokenizer, which
                # prepends the SRC lang (the "adget..." artifacts, decisions
                # #114). Generation: decoder starts on eos; the model's own
                # argmax after it is the first real token (no forced bos).
                import importlib.util
                from huggingface_hub import hf_hub_download
                spec = importlib.util.spec_from_file_location(
                    "tokenization_small100",
                    hf_hub_download("alirezamsh/small100", "tokenization_small100.py"),
                )
                tmod = importlib.util.module_from_spec(spec)
                sys.modules["tokenization_small100"] = tmod
                spec.loader.exec_module(tmod)
                tok = tmod.SMALL100Tokenizer.from_pretrained(
                    "alirezamsh/small100", tgt_lang=m2m_tgt
                )
                prefix = ""
                decoder_start = [tok.eos_token_id]
            else:  # m2m100-418M
                # M2M-100 generate() contract (decoder_start_token_id=eos +
                # forced_bos_token_id=target lang): feed EOS as decoder start,
                # DISCARD its argmax (HF forces the lang id there — the model's
                # own argmax after eos is pair-dependent, often __fr__), then
                # feed the lang id; the argmax after IT is the first real
                # token. The runner feeds decoder_start tokens sequentially
                # and discards their argmaxes (Marian's [pad] start behaves
                # identically under that rule).
                tok = AutoTokenizer.from_pretrained("facebook/m2m100_418M")
                tok.src_lang = m2m_src
                prefix = ""
                decoder_start = [tok.eos_token_id, tok.get_lang_id(m2m_tgt)]
            items = []
            for kind, corpus in (("sent", quality), ("passage", ptexts)):
                for src, ref in corpus:
                    enc = tok(prefix + src, truncation=True, max_length=512)
                    items.append({
                        "kind": kind,
                        "src": src,
                        "input_ids": enc["input_ids"],
                        "attention_mask": enc["attention_mask"],
                        "decoder_start": decoder_start,
                        "eos": tok.eos_token_id,
                        "ref": ref,
                    })
            models_out[model] = items
            print(f"{pair} {model}: {len(items)} items "
                  f"(sent {args.quality}, passage {len(ptexts)}, "
                  f"decoder_start {decoder_start})", flush=True)
        out["pairs"][pair] = {"models": models_out}

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"wrote {args.out} ({os.path.getsize(args.out):,} bytes)")


if __name__ == "__main__":
    main()
