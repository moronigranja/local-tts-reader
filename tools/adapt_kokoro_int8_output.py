#!/usr/bin/env python3
"""Make the mirrored NekoSpeak int8 Kokoro export consumable by our engine.

The 92,361,271 B `kokoro-v1.0.int8.onnx` (release digest `6e742170…`) that three
independent peer apps ship names its waveform output `audio`, while the pinned
thewh1teagle fp32 export — and therefore `OrtKokoroSession` — expects
`waveform`. Measured on the S22 Ultra 2026-09-11:

    candidate int8 unavailable: java.lang.IllegalArgumentException:
    synthesis failed [en-us]: Unknown output name waveform, expected one of [audio]

That is a name, not arithmetic: the graph's inputs (`tokens`, `style`, `speed`)
already negotiate, and nothing downstream of the session reads the tensor name.
This tool rewrites the single output name (metadata only) so leg A can measure
the int8 *numbers* on the production path, and proves the rewrite is a no-op by
running both files on the host CPU EP and comparing the outputs exactly.

Shipping this pack would still need either an output-name alias in
`OrtKokoroSession` or a renamed re-export — that cost is recorded in the leg A
JSON (`artifact.output_rename`) and must be part of the adoption decision.

Usage:
  python3 tools/adapt_kokoro_int8_output.py \
    --in ~/.cache/ayvu-spike/models/kokoro-v1.0.int8-neko.onnx \
    --out ~/.cache/ayvu-spike/models/kokoro-v1.0.int8-neko-waveform.onnx
"""

from __future__ import annotations

import argparse
import json
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper

SOURCE_OUTPUT = "audio"
TARGET_OUTPUT = "waveform"


def rename_output(model: onnx.ModelProto) -> dict:
    graph = model.graph
    names = [output.name for output in graph.output]
    if TARGET_OUTPUT in names:
        return {"renamed": False, "outputs": names}
    if SOURCE_OUTPUT not in names:
        raise ValueError(f"neither {TARGET_OUTPUT} nor {SOURCE_OUTPUT} among graph outputs {names}")

    producers = [node for node in graph.node if SOURCE_OUTPUT in node.output]
    if len(producers) != 1:
        raise ValueError(f"{len(producers)} producers of {SOURCE_OUTPUT}; refusing an ambiguous rename")
    for index, name in enumerate(producers[0].output):
        if name == SOURCE_OUTPUT:
            producers[0].output[index] = TARGET_OUTPUT
    for index, output in enumerate(graph.output):
        if output.name == SOURCE_OUTPUT:
            graph.output[index].name = TARGET_OUTPUT
    for value_info in list(graph.value_info) + list(graph.input):
        if value_info.name == SOURCE_OUTPUT:
            value_info.name = TARGET_OUTPUT
    return {"renamed": True, "producers": producers[0].name, "outputs": [output.name for output in graph.output]}


def _inputs(graph: onnx.GraphProto) -> dict:
    rng = np.random.default_rng(20260911)
    feeds = {}
    for value in graph.input:
        shape = [dim.dim_value if dim.dim_value > 0 else 3 for dim in value.type.tensor_type.shape.dim]
        dtype = helper.tensor_dtype_to_np_dtype(value.type.tensor_type.elem_type)
        if np.issubdtype(dtype, np.floating):
            feeds[value.name] = (rng.random(shape) * 0.5).astype(dtype)
        elif np.issubdtype(dtype, np.integer):
            feeds[value.name] = np.zeros(shape, dtype=dtype)
        else:
            raise ValueError(f"{value.name}: unsupported dtype {dtype}")
    return feeds


def verify(source: str, target: str) -> dict:
    """The renamed file must produce bit-identical output to the original."""
    session_source = ort.InferenceSession(source, ort.SessionOptions(), providers=["CPUExecutionProvider"])
    session_target = ort.InferenceSession(target, ort.SessionOptions(), providers=["CPUExecutionProvider"])
    feeds = _inputs(onnx.load(source).graph)
    shape_key = {k: (0 if np.issubdtype(v.dtype, np.integer) else v) for k, v in feeds.items()}
    a = session_source.run(None, feeds)[0]
    b = session_target.run(None, feeds)[0]
    return {
        "ref_shape": list(a.shape),
        "candidate_shape": list(b.shape),
        "max_abs_diff": float(np.abs(a - b).max()) if a.size else 0.0,
        "identical": bool(a.shape == b.shape and np.array_equal(a, b)),
        "target_outputs": [o.name for o in session_target.get_outputs()],
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--in", dest="source", required=True)
    parser.add_argument("--out", dest="target", required=True)
    arguments = parser.parse_args(argv)

    model = onnx.load(arguments.source)
    report = rename_output(model)
    onnx.checker.check_model(model)
    onnx.save(model, arguments.target)
    report["source"] = arguments.source
    report["target"] = arguments.target
    report.update(verify(arguments.source, arguments.target))
    print(json.dumps(report, indent=2))
    if not report["identical"] or report["target_outputs"] != [TARGET_OUTPUT]:
        print("rename changed behaviour — refusing", file=sys.stderr)
        return 1
    print(f"ok: {TARGET_OUTPUT} output, bit-identical to the original", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
