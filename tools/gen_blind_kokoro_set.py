#!/usr/bin/env python3
"""Blind A/B listening set for the int8 tier (leg A, decisions #148).

The int8 adoption gate is a human judgement, so the artefacts handed to that
judgement must not leak the answer: this tool level-matches the candidate to the
reference (exactly as `kokoro_perceptual.py` does, so a pure gain difference
cannot masquerade as quality), shuffles the two into `1.wav`/`2.wav` with a
seeded RNG, and keeps the mapping in `key.json`. The seed plus the key are the
audit trail; no file is ever written with a name that reveals its precision.

Layout: one directory per (language, pass) pair — `<out>/<lang>/` for the first
pair of a language and `<out>/<lang>/<variant>/` for the later passes of the
same language, where `<variant>` is the shared stem without the `fp32`/`int8`
marker (e.g. `pass2`). `<out>/README.md` carries the listening checklist.

Usage:
  python3 tools/gen_blind_kokoro_set.py --dir docs/prints/perfspike --out docs/prints/perfspike/blind
  python3 tools/gen_blind_kokoro_set.py --selftest
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

import numpy as np
from scipy.io import wavfile

from kokoro_perceptual import SAMPLE_RATE, read_mono

REFERENCE, CANDIDATE = "fp32", "int8"

CHECKLIST = """# Blind listening set — Kokoro int8 vs fp32 (decisions #148)

1. Listen to `1.wav` then `2.wav` in each language directory, both at the same player volume.
2. State which is better, or that they are indistinguishable (there is no "correct" answer here).
3. Note any artefact you hear: fizz/noise, warble, clipped consonants, dropped or swallowed
   syllables, wrong stress, unnatural pauses.

Levels are RMS-matched and the order is seeded-random (`key.json`); do not open `key.json`
before listening.
"""


def _pairs(directory: str) -> list[tuple[str, str, str, str]]:
    """(language, head, ref_name, deg_name) for every fp32/int8 sibling pair."""
    found = []
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".wav") or f"_{REFERENCE}_" not in name:
            continue
        deg_name = name.replace(f"_{REFERENCE}_", f"_{CANDIDATE}_")
        ref_path, deg_path = os.path.join(directory, name), os.path.join(directory, deg_name)
        if not os.path.isfile(deg_path):
            continue
        stem = name[: -len(".wav")]
        head, language = stem.split(f"_{REFERENCE}_", 1)
        found.append((language, head, name, deg_name))
    return found


def _variant(head: str) -> str:
    """`perfspike_a_pass2` → `pass2` (a label for a repeat of the same comparison)."""
    return head.strip("_") or "pair"


def build(directory: str, out: str, seed: int) -> dict:
    pairs = _pairs(directory)
    if not pairs:
        raise SystemExit(f"no fp32/int8 WAV pairs found in {directory}")

    # Group by language so the first pair of each language lands on the plain
    # `<lang>/` directory the checklist refers to.
    by_language: dict[str, list[tuple[str, str, str, str]]] = {}
    for language, head, ref_name, deg_name in pairs:
        by_language.setdefault(language, []).append((head, ref_name, deg_name, language))

    rng = random.Random(seed)
    key = {"seed": seed, "source_dir": os.path.abspath(directory), "pairs": {}}
    for language in sorted(by_language):
        group = by_language[language]
        for index, (head, ref_name, deg_name, _) in enumerate(group):
            subdir = language if index == 0 else f"{language}-{_variant(head)}"
            ref = read_mono(os.path.join(directory, ref_name))
            deg = read_mono(os.path.join(directory, deg_name))
            n = min(len(ref), len(deg))
            ref, deg = ref[:n], deg[:n]
            ref_rms = float(np.sqrt(np.mean(ref**2)))
            deg_rms = float(np.sqrt(np.mean(deg**2)))
            if deg_rms == 0.0:
                print(f"skip {deg_name}: silent candidate")
                continue
            deg = np.clip(deg * (ref_rms / deg_rms), -1.0, 1.0)

            order = [REFERENCE, CANDIDATE]
            rng.shuffle(order)
            target = os.path.join(out, subdir)
            os.makedirs(target, exist_ok=True)
            for slot, label in enumerate(order, start=1):
                samples = ref if label == REFERENCE else deg
                wavfile.write(
                    os.path.join(target, f"{slot}.wav"),
                    SAMPLE_RATE,
                    np.round(samples * 32767.0).astype(np.int16),
                )
            key["pairs"][subdir] = {
                "1": order[0],
                "2": order[1],
                "ref": ref_name,
                "candidate": deg_name,
                "gain_applied": ref_rms / deg_rms,
                "samples": n,
            }
            print(f"{subdir}: 1.wav={order[0]} 2.wav={order[1]} (from {ref_name} / {deg_name})")

    with open(os.path.join(out, "key.json"), "w") as handle:
        handle.write(json.dumps(key, indent=2) + "\n")
    with open(os.path.join(out, "README.md"), "w") as handle:
        handle.write(CHECKLIST)
    return key


def _selftest() -> int:
    import tempfile

    t = np.arange(SAMPLE_RATE, dtype=np.float64) / SAMPLE_RATE
    ref = 0.3 * np.sin(2 * np.pi * 440.0 * t)
    deg = 0.19 * np.sin(2 * np.pi * 440.0 * t)  # same tone, 4 dB down
    with tempfile.TemporaryDirectory() as tmp:
        source = os.path.join(tmp, "src")
        target = os.path.join(tmp, "blind")
        os.makedirs(source)
        wavfile.write(
            os.path.join(source, "perfspike_a_pass1_fp32_en-us.wav"),
            SAMPLE_RATE,
            np.round(ref * 32767).astype(np.int16),
        )
        wavfile.write(
            os.path.join(source, "perfspike_a_pass1_int8_en-us.wav"),
            SAMPLE_RATE,
            np.round(deg * 32767).astype(np.int16),
        )
        seed = 20260911
        key = build(source, target, seed)

        entry = key["pairs"].get("en-us")
        if key["seed"] != seed or entry is None or {entry["1"], entry["2"]} != {REFERENCE, CANDIDATE}:
            print(f"selftest FAIL: key does not round-trip: {key}", file=sys.stderr)
            return 1
        for slot in ("1.wav", "2.wav"):
            if not os.path.isfile(os.path.join(target, "en-us", slot)):
                print(f"selftest FAIL: missing en-us/{slot}", file=sys.stderr)
                return 1
        if not os.path.isfile(os.path.join(target, "key.json")) or not os.path.isfile(
            os.path.join(target, "README.md")
        ):
            print("selftest FAIL: key.json or README.md missing", file=sys.stderr)
            return 1
        # Level matching: both slots must land on the reference's RMS.
        levels = [float(np.sqrt(np.mean(read_mono(os.path.join(target, "en-us", slot)) ** 2)))
                  for slot in ("1.wav", "2.wav")]
        if max(levels) - min(levels) > 5e-3:
            print(f"selftest FAIL: slots not level-matched: {levels}", file=sys.stderr)
            return 1
        # Determinism: the same seed must reproduce the same mapping.
        again = build(source, os.path.join(tmp, "blind2"), seed)
        if again["pairs"]["en-us"]["1"] != entry["1"]:
            print("selftest FAIL: seed did not reproduce the shuffle", file=sys.stderr)
            return 1
    print("ok")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", help="directory holding the fp32/int8 WAV pairs")
    parser.add_argument("--out", help="blind set destination")
    parser.add_argument("--seed", type=int, default=20260911)
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        return _selftest()
    if not arguments.dir or not arguments.out:
        parser.error("--dir and --out are required (or use --selftest)")
    key = build(arguments.dir, arguments.out, arguments.seed)
    print(f"{len(key['pairs'])} blind pair(s) written to {arguments.out} (seed {arguments.seed})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
