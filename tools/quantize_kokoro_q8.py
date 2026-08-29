#!/usr/bin/env python3
"""Dynamic q8 quantizer for the pinned Kokoro-82M fp32 export (spike plan, 2026-08-28).

Generates the "q8" precision candidate for the KOKORO_PRECISION_SPIKE: dynamic
uint8 quantization of the fp32 ONNX export, with the post-conv node excluded
(the load-bearing fidelity rule from kokoro-onnx-export — quantizing it "adds
a ton of static"). Chroma/precision comparisons happen on-device in spike-tts
(KokoroBenchmarkRunner.runPrecision), oracle-gated against the pinned fp32.

Dependency: `pip install onnx onnxruntime` (or `uv run --with onnx --with onnxruntime`).

Usage:
  python3 tools/quantize_kokoro_q8.py <input.onnx> <output.onnx> [--exclude NODE_NAME]
    --exclude NODE_NAME  exclude a specific Conv node; default: the single Conv
                         node whose name contains "conv_post" (case-insensitive)
"""

import argparse
import hashlib
import os
import sys


def conv_nodes(model):
    """Every Conv node in the graph (Conv in any nested graph is included if named)."""
    found = []

    def walk(graph):
        for node in graph.node:
            if node.op_type == "Conv":
                found.append(node)
            for attr in node.attribute:
                if attr.type == 5:  # GRAPH
                    walk(attr.g)

    walk(model.graph)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="pinned fp32 export (kokoro-v1.0.onnx)")
    parser.add_argument("output", help="q8 output path (kokoro-v1.0.q8.onnx)")
    parser.add_argument("--exclude", help="Conv node name to exclude (default: the conv_post node)")
    args = parser.parse_args()

    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic

    model = onnx.load(args.input)
    convs = conv_nodes(model)
    excluded = args.exclude
    if excluded is None:
        matches = [n for n in convs if "conv_post" in (n.name or "").lower()]
        if len(matches) == 1:
            excluded = matches[0].name
        elif len(matches) == 0:
            print("no conv_post node found; pass --exclude <name> from the list above:", file=sys.stderr)
            for n in convs:
                print(f"  {n.name}", file=sys.stderr)
            sys.exit(1)
        else:
            print(f"{len(matches)} conv_post nodes found; pass --exclude <name>:", file=sys.stderr)
            for n in matches:
                print(f"  {n.name}", file=sys.stderr)
            sys.exit(1)
    else:
        names = {n.name for n in convs}
        if excluded not in names:
            print(f"node '{excluded}' not found among {len(convs)} Conv nodes; pass one of:", file=sys.stderr)
            for n in convs:
                print(f"  {n.name}", file=sys.stderr)
            sys.exit(1)

    quantize_dynamic(
        args.input,
        args.output,
        weight_type=QuantType.QUInt8,
        nodes_to_exclude=[excluded],
    )

    in_bytes = os.path.getsize(args.input)
    out_bytes = os.path.getsize(args.output)
    with open(args.output, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    print(f"input:  {args.input} ({in_bytes:,} bytes)")
    print(f"output: {args.output} ({out_bytes:,} bytes)")
    print(f"excluded node: {excluded}")
    print(f"sha256: {digest}")
    sys.exit(0)


if __name__ == "__main__":
    main()