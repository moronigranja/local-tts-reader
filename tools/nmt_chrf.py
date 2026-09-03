#!/usr/bin/env python3
"""Score the NMT spike results: chr-F per pair x model x precision (Phase J).

Reads the device-pulled `translate_results.json` (produced token ids per leg
item) + the host-tokenized `translate_inputs.json` (FLORES refs), decodes each
item's `output_ids` with the producing model's tokenizer (strip EOS + pad) and
computes sacrebleu chr-F (`--remove_whitespace`) per `pair x model x precision`
over the `sent` items (FLORES dev slice) against the refs. Passages are decoded
and printed for eyeballing but not scored (refs exist; wall-time is the
passage leg's metric).

NOTE: for en->pt-br the FLORES `por` reference is EUROPEAN Portuguese — chr-F
there slightly understates pt-BR quality; the owner's blind read is the
authoritative pt-BR gate (decisions #101).

Usage:
  python3 tools/nmt_chrf.py [--results translate_results.json]
                            [--inputs translate_inputs.json]
"""

import argparse
import json
import sys

REPOS = {
    "opus-mt-it-es": "Helsinki-NLP/opus-mt-it-es",
    "opus-mt-en-it": "Helsinki-NLP/opus-mt-en-it",
    "opus-mt-es-en": "Helsinki-NLP/opus-mt-es-en",
    "opus-mt-tc-big-en-pt": "Helsinki-NLP/opus-mt-tc-big-en-pt",
    "m2m100-418M": "facebook/m2m100_418M",
    "small100": "alirezamsh/small100",
}
# m2m100 target lang per pair (src_lang set per item's pair)
M2M_TGT = {"it-es": "es", "en-pt-br": "pt", "en-it": "it", "es-en": "en"}
M2M_SRC = {"it-es": "it", "en-pt-br": "en", "en-it": "en", "es-en": "es"}


def decode(tokenizers, pair, model, output_ids, eos):
    tok = tokenizers[model]
    ids = [t for t in output_ids if t not in (eos, tok.pad_token_id)]
    return tok.decode(ids, skip_special_tokens=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--results", default="translate_results.json")
    ap.add_argument("--inputs", default="translate_inputs.json")
    args = ap.parse_args()

    import sacrebleu
    from transformers import AutoTokenizer

    with open(args.results) as f:
        results = json.load(f)
    with open(args.inputs) as f:
        inputs = json.load(f)

    tokenizers = {name: AutoTokenizer.from_pretrained(repo) for name, repo in REPOS.items()}

    rows = []
    for pair, pv in inputs["pairs"].items():
        for model in pv["models"]:
            eos = pv["models"][model][0]["eos"]
            for precision in ("fp32", "int8"):
                leg = results.get(pair, {}).get(f"{model}/{precision}")
                if leg is None or "unavailable" in leg:
                    rows.append((pair, model, precision, None, "unavailable"))
                    continue
                items = leg["items"]
                hyps, refs = [], []
                decoded_sents = []
                sent_i = 0
                for item in items:
                    text = decode(tokenizers, pair, model, item["output_ids"], eos)
                    if item["kind"] == "sent":
                        ref = pv["models"][model][sent_i]["ref"]
                        hyps.append(text)
                        refs.append(ref)
                        decoded_sents.append(text)
                        sent_i += 1
                chrf = sacrebleu.corpus_chrf(hyps, [refs], remove_whitespace=True)
                rows.append((pair, model, precision, chrf.score, ""))

    print(f"{'pair':10} {'model':22} {'prec':5} {'chrF':>7}")
    for pair, model, precision, score, note in rows:
        s = f"{score:7.2f}" if score is not None else "     n/a"
        print(f"{pair:10} {model:22} {precision:5} {s} {note}")


if __name__ == "__main__":
    main()
