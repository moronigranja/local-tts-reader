#!/usr/bin/env python3
"""Export + pin the NMT spike models to ONNX (roadmap Phase J, 2026-09-02).

Mirrors tools/quantize_kokoro_q8.py's pinning discipline for the Phase J
spike candidates: the four per-pair OPUS-MT baselines (decisions #101
direction) plus the single many-to-many M2M-100-418M. Per model:

  1. resolve + pin the HF revision (recorded, never assumed),
  2. export the seq2seq graphs (encoder / decoder / decoder_with_past) via
     `optimum-cli export onnx --task text2text-generation-with-past` (needs
     the `optimum-onnx` package on optimum>=2 hosts; `pip install optimum-onnx`),
  3. parity gate (the #86 fp16-stub lesson): PyTorch greedy decode vs the
     ONNX graphs on host ORT 1.29.0 — token-identical or the export fails,
  4. dynamic-int8 candidate per graph (onnxruntime.quantization, no
     exclusion — Marian/M2M-100 are attention/MatMul-only, no Conv, so the
     #86/#100 ConvInteger blocker does not apply), verified to run on host
     ORT and whether its greedy decode matches fp32 (recorded, not assumed),
  5. manifest: revision + sha256 + size for every artifact -> m/nmt/
     manifest.json (committed: the models themselves are runtime downloads,
     decision #7).

Greedy-decode contract (identical on device): step 0 runs decoder_model.onnx
(no past) which emits `present.{N}.{decoder,encoder}.{key,value}`; the
with-past graph requires ALL `past_key_values.*` inputs — the cross-attention
`*.encoder.*` entries are CONSTANT after step 0 (not re-emitted by the
with-past graph, which only outputs `present.N.decoder.*`) and are re-fed
unchanged every step.

Models are NEVER committed. Output layout: m/nmt/<name>/onnx/*.onnx and
m/nmt/<name>/onnx-int8/*.onnx (tokenizers are re-derived from the HF repo by
tools/gen_nmt_inputs.py, not exported).

Usage:
  python3 tools/export_nmt_onnx.py [--models NAME ...] [--out m/nmt]
                                   [--skip-parity] [--skip-int8]
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys

MODELS = {
    # name: (hf repo, decoder_start token for the parity sample — Marian pads,
    # M2M-100 forces the target lang id as the first decoder token)
    "opus-mt-it-es": ("Helsinki-NLP/opus-mt-it-es", None),
    "opus-mt-tc-big-en-pt": ("Helsinki-NLP/opus-mt-tc-big-en-pt", None),
    "opus-mt-en-it": ("Helsinki-NLP/opus-mt-en-it", None),
    "opus-mt-es-en": ("Helsinki-NLP/opus-mt-es-en", None),
    "m2m100-418M": ("facebook/m2m100_418M", None),
    "small100": ("alirezamsh/small100", None),
}

GRAPHS = ["encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def export(repo, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    subprocess.run(
        ["optimum-cli", "export", "onnx", "--model", repo,
         "--task", "text2text-generation-with-past", out_dir],
        check=True,
        stdout=sys.stderr,
        stderr=sys.stderr,
    )
    missing = [g for g in GRAPHS if not os.path.isfile(os.path.join(out_dir, g))]
    if missing:
        raise SystemExit(f"{repo}: export did not produce {missing}")


def onnx_greedy(model_dir, tokenizer, texts, decoder_start=None, max_new_tokens=128):
    """Manual encoder -> decoder(step 0, no past) -> decoder_with_past greedy
    loop — the exact device-runner contract — returning token id lists."""
    import numpy as np
    import onnxruntime as ort

    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 6
    enc = ort.InferenceSession(os.path.join(model_dir, "encoder_model.onnx"), opts)
    dec = ort.InferenceSession(os.path.join(model_dir, "decoder_model.onnx"), opts)
    decp = ort.InferenceSession(os.path.join(model_dir, "decoder_with_past_model.onnx"), opts)
    eos = tokenizer.eos_token_id
    enc_out_names = {i.name for i in dec.get_inputs()} | {i.name for i in decp.get_inputs()}
    dec_in = {i.name for i in dec.get_inputs()}
    decp_in = {i.name for i in decp.get_inputs()}
    start = decoder_start if decoder_start is not None else (
        tokenizer.pad_token_id if tokenizer.pad_token_id is not None else 0
    )

    outs = []
    for text in texts:
        enc_in = tokenizer(text, return_tensors="np", truncation=True, max_length=512)
        ids = enc_in["input_ids"].astype(np.int64)
        mask = enc_in["attention_mask"].astype(np.int64)
        enc_feeds = {
            k: v for k, v in {"input_ids": ids, "attention_mask": mask}.items()
            if k in {i.name for i in enc.get_inputs()}
        }
        hidden = enc.run(None, enc_feeds)[0]
        shared = {k: v for k, v in {
            "encoder_hidden_states": hidden,
            "encoder_attention_mask": mask,
        }.items() if k in enc_out_names}

        tokens = [start]
        past = None
        enc_past = None
        for _ in range(max_new_tokens):
            dec_ids = np.array([[tokens[-1]]], dtype=np.int64)
            if past is None:
                feeds = dict(shared)
                feeds["input_ids"] = dec_ids
                feeds = {k: v for k, v in feeds.items() if k in dec_in}
                run_out = dec.run(None, feeds)
                # step 0 emits present.{N}.{decoder,encoder}.* — map every entry
                present = {
                    o.name.replace("present", "past_key_values"): v
                    for o, v in zip(dec.get_outputs()[1:], run_out[1:])
                }
                past = dict(present)
                enc_past = {k: v for k, v in present.items() if ".encoder." in k}
            else:
                feeds = dict(shared)
                feeds["input_ids"] = dec_ids
                feeds.update(past)
                feeds = {k: v for k, v in feeds.items() if k in decp_in}
                run_out = decp.run(None, feeds)
                # with-past only re-emits the decoder self past; encoder cross
                # past stays constant — re-fed from step 0
                present = {
                    o.name.replace("present", "past_key_values"): v
                    for o, v in zip(decp.get_outputs()[1:], run_out[1:])
                }
                past = {**enc_past, **present}
            logits = run_out[0]
            nxt = int(logits[0, -1].argmax())
            tokens.append(nxt)
            if nxt == eos:
                break
        outs.append(tokens[1:])  # drop the decoder start token, like generate()
    return outs


def parity(model_dir, repo, tokenizer, texts, decoder_start=None):
    import torch
    from transformers import AutoModelForSeq2SeqLM

    torch_model = AutoModelForSeq2SeqLM.from_pretrained(repo)
    torch_model.eval()
    gen_kwargs = {}
    if decoder_start is not None:
        gen_kwargs["decoder_start_token_id"] = decoder_start
    ref = []
    with torch.no_grad():
        for text in texts:
            enc = tokenizer(text, return_tensors="pt", truncation=True, max_length=512)
            gen = torch_model.generate(
                **enc, num_beams=1, do_sample=False, max_new_tokens=128, **gen_kwargs,
            )
            ref.append(gen[0].tolist()[1:])
    onnx_out = onnx_greedy(model_dir, tokenizer, texts, decoder_start=decoder_start)
    for i, (a, b) in enumerate(zip(ref, onnx_out)):
        if a != b:
            return False, i, a, b
    return True, -1, None, None


def quantize_dir(src_dir, dst_dir):
    from onnxruntime.quantization import QuantType, quantize_dynamic

    os.makedirs(dst_dir, exist_ok=True)
    for g in GRAPHS:
        quantize_dynamic(
            os.path.join(src_dir, g),
            os.path.join(dst_dir, g),
            weight_type=QuantType.QUInt8,
        )


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--models", nargs="*", default=list(MODELS))
    ap.add_argument("--out", default="m/nmt")
    ap.add_argument("--skip-parity", action="store_true", help="NOT recommended")
    ap.add_argument("--skip-int8", action="store_true")
    args = ap.parse_args()

    from transformers import AutoTokenizer

    sample = [
        "La casa sulla collina ha una vista meravigliosa sul mare.",
        "The government announced a new plan to reduce traffic in the city centre.",
        "Le livre que tu m'as prêté était fascinant du début à la fin.",
    ]

    manifest = {}
    if os.path.isfile(os.path.join(args.out, "manifest.json")):
        with open(os.path.join(args.out, "manifest.json")) as f:
            manifest = json.load(f)

    for name in args.models:
        repo, m2m_tgt = MODELS[name]
        print(f"== {name} ({repo}) ==", file=sys.stderr)
        info = manifest.setdefault(name, {"repo": repo})
        base = os.path.join(args.out, name)

        # 1. pin revision + download via transformers (single source of truth)
        from huggingface_hub import snapshot_download
        snap = snapshot_download(repo)
        info["revision"] = os.path.basename(snap.rstrip("/"))
        tokenizer = AutoTokenizer.from_pretrained(repo)
        info["tokenizer"] = tokenizer.__class__.__name__

        decoder_start = tokenizer.eos_token_id if name in ("m2m100-418M", "small100") else None

        # 2. export
        onnx_dir = os.path.join(base, "onnx")
        export(repo, onnx_dir)

        # 3. parity gate
        if not args.skip_parity:
            ok, i, a, b = parity(onnx_dir, repo, tokenizer, sample, decoder_start)
            if not ok:
                raise SystemExit(f"{name}: PARITY FAILURE on sample {i}: pt={a} onnx={b}")
            print(f"{name}: parity OK (3 samples token-identical)", file=sys.stderr)

        # 4. int8 candidate + host run check
        if not args.skip_int8:
            int8_dir = os.path.join(base, "onnx-int8")
            quantize_dir(onnx_dir, int8_dir)
            int8_out = onnx_greedy(int8_dir, tokenizer, sample, decoder_start)
            fp32_out = onnx_greedy(onnx_dir, tokenizer, sample, decoder_start)
            info["int8_matches_fp32_greedy"] = int8_out == fp32_out
            print(f"{name}: int8 runs; greedy == fp32: {info['int8_matches_fp32_greedy']}",
                  file=sys.stderr)

        # 5. pin hashes + sizes
        dirs = [("fp32", onnx_dir)]
        if not args.skip_int8:
            dirs.append(("int8", os.path.join(base, "onnx-int8")))
        for tag, d in dirs:
            info.setdefault(tag, {})
            for g in GRAPHS:
                p = os.path.join(d, g)
                if os.path.isfile(p):
                    info[tag][g] = {"sha256": sha256(p), "bytes": os.path.getsize(p)}
        manifest[name] = info
        with open(os.path.join(args.out, "manifest.json"), "w") as f:
            json.dump(manifest, f, indent=2, sort_keys=True)

    print(f"manifest written to {os.path.join(args.out, 'manifest.json')}", file=sys.stderr)


if __name__ == "__main__":
    main()
