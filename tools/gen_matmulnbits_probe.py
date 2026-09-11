#!/usr/bin/env python3
"""MatMulNBits CPU-EP probe — the int4 conflict in the #148 survey (leg F1).

The cross-app survey recorded two incompatible claims:

  * source inspection of onnxruntime's CPU EP said no `MatMulNBits` kernel exists
    (so a weight-only int4 Kokoro pack could never ship on the CPU EP we use), and
  * our own HiBreak closer-look probe opened and ran a `MatMulNBits` graph to a
    finite output on ORT-android 1.23.2.

This script builds the minimal single-node graph and settles it on the HOST
CPU EP. `opened && finite` means the kernel exists in that ORT build; a
"not implemented" / "Could not find an implementation" error at session
creation means it does not. The device half (leg F1) repeats the same graph on
ORT-android.

Two shapes are probed, in this order:

  1. `packed` — what the kernel itself demands. ORT 1.29 validates at run time
     and names the expected shapes, which is how they were derived here:
     `B [N, K/block_size, block_size*bits/8]` and `zero_points [N, K/block_size,
     block_size*bits/8]` (4-bit zero points are packed two per byte alongside
     the weights). The written `matmulnbits-probe.onnx` is this variant.
  2. `legacy` — the unpacked shapes the plan originally specified
     (`B [256,256]`, `zero_points [256,16]`). It is kept because its error
     message is the evidence that the kernel is present *and* validating on
     those axes rather than silently absent.

The device runner reads the input shapes off the session, so the file can be
regenerated with different shapes without touching the Kotlin.

Usage:
  python3 tools/gen_matmulnbits_probe.py --out ~/.cache/ayvu-spike/models
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper

DOMAIN = "com.microsoft"
K = 256
N = 256
BLOCK_SIZE = 16
BITS = 4
BLOCKS = K // BLOCK_SIZE
BLOB = BLOCK_SIZE * BITS // 8  # 8 bytes per 16-weight block at 4 bits


def _spec(shape_variant: str) -> dict[str, list[int]]:
    if shape_variant == "packed":
        return {"B": [N, BLOCKS, BLOB], "scales": [N, BLOCKS], "zero_points": [N, BLOB]}
    if shape_variant == "legacy":
        return {"B": [N, N], "scales": [N, BLOCKS], "zero_points": [N, BLOCKS]}
    raise ValueError(f"unknown variant {shape_variant}")


def build(shape_variant: str) -> onnx.ModelProto:
    shapes = _spec(shape_variant)
    A = helper.make_tensor_value_info("A", TensorProto.FLOAT, [1, 1, K])
    B = helper.make_tensor_value_info("B", TensorProto.UINT8, shapes["B"])
    scales = helper.make_tensor_value_info("scales", TensorProto.FLOAT, shapes["scales"])
    zero_points = helper.make_tensor_value_info("zero_points", TensorProto.UINT8, shapes["zero_points"])
    Y = helper.make_tensor_value_info("Y", TensorProto.FLOAT, [1, 1, N])
    node = helper.make_node(
        "MatMulNBits",
        ["A", "B", "scales", "zero_points"],
        ["Y"],
        domain=DOMAIN,
        K=K,
        N=N,
        bits=BITS,
        block_size=BLOCK_SIZE,
        accuracy_level=4,
    )
    graph = helper.make_graph([node], "matmulnbits_probe", [A, B, scales, zero_points], [Y])
    return helper.make_model(
        graph,
        opset_imports=[helper.make_opsetid("", 17), helper.make_opsetid(DOMAIN, 1)],
        producer_name="ayvu-perfspike",
    )


def run(path: str, shape_variant: str) -> dict:
    """Open on CPU EP and run once; non-trivial outputs prove it computes."""
    shapes = _spec(shape_variant)
    rng = np.random.default_rng(20260911)
    feeds = {
        "A": rng.standard_normal((1, 1, K)).astype(np.float32),
        # Non-zero packed nibbles + non-trivial scales so a kernel that returned
        # zeros or ignored the weights would show up as a wrong value.
        "B": rng.integers(0, 256, size=shapes["B"], dtype=np.uint8),
        "scales": (rng.random(shapes["scales"]) * 0.05 + 0.01).astype(np.float32),
        "zero_points": np.zeros(shapes["zero_points"], dtype=np.uint8),
    }
    result = {"variant": shape_variant, "shapes": shapes, "opened": False, "finite": False, "error": None}
    try:
        session = ort.InferenceSession(path, ort.SessionOptions(), providers=["CPUExecutionProvider"])
    except Exception as exc:  # session creation is where a missing kernel surfaces
        result["error"] = f"{type(exc).__name__}: {exc}"
        return result
    result["opened"] = True
    try:
        out = session.run(None, feeds)[0]
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
        return result
    result["finite"] = bool(np.isfinite(out).all())
    result["output_shape"] = list(out.shape)
    result["output_abs_mean"] = float(np.abs(out).mean())
    result["output_nonzero"] = bool(np.any(out != 0.0))
    result["non_degenerate"] = result["finite"] and result["output_nonzero"]
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="directory to write matmulnbits-probe.onnx into")
    parser.add_argument("--variant", default="packed", choices=["packed", "legacy"])
    arguments = parser.parse_args(argv)

    os.makedirs(arguments.out, exist_ok=True)
    reports = []
    for variant in ("packed", "legacy"):
        path = os.path.join(arguments.out, f"matmulnbits-probe.{variant}.onnx")
        onnx.save(build(variant), path)
        onnx.checker.check_model(onnx.load(path))  # structural gate: the run below is not a parse test
        reports.append(run(path, variant))

    primary = next(r for r in reports if r["variant"] == arguments.variant)
    if primary["opened"] and primary["finite"]:
        target = os.path.join(arguments.out, "matmulnbits-probe.onnx")
        onnx.save(build(arguments.variant), target)
        # Re-run the exact bytes the device will open.
        primary = run(target, arguments.variant)
        primary["path"] = target

    report = {
        "providers": ort.get_available_providers(),
        "onnxruntime": ort.__version__,
        "onnx": onnx.__version__,
        "primary": arguments.variant,
        "probes": reports,
        "opened": primary["opened"],
        "finite": primary["finite"],
        "error": primary["error"],
        "verdict": (
            "cpu_ep_kernel_present"
            if primary["opened"] and primary["finite"] and primary.get("non_degenerate")
            else "cpu_ep_kernel_absent_or_broken"
        ),
    }
    print(json.dumps(report, indent=2))
    return 0 if report["verdict"] == "cpu_ep_kernel_present" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
