#!/usr/bin/env python3
"""Rewrite Kokoro's 1-D convolutions as 2-D ones so XNNPACK can claim them.

The XNNPACK execution provider (leg F2) only claims 2-D Conv/ConvTranspose/
Pool and 2-D Gemm/MatMul. Every convolution in the Kokoro export is a 1-D
convolution carried as `(N, C, L)`, so nothing partitions and the EP has never
been able to run: any XNNPACK attempt so far measured pure CPU fallback. Adding
a singleton `H` axis to each conv input/weight/attribute and removing it again
afterwards changes no arithmetic — it only gives the EP a 2-D shape to match.
That is why the parity gate below runs in the same process and why the device
leg refuses to run without it.

What is rewritten, per Conv / ConvTranspose node (flat graph, topological
order):

  input  `X`                       -> `X__2d`   via Reshape [0, 0, 1, -1]
  weight [O, C/g, k]               -> [O, C/g, 1, k]
  kernel_shape [k] -> [1, k]        strides [s] -> [1, s]
  dilations [d]    -> [1, d]        pads [b, e] -> [0, b, 0, e]
  output `Y`                       -> `Y__1d`   via Reshape [0, 0, -1]
  (ConvTranspose) output_padding [p] -> [0, p]

Any conv whose attributes are missing or whose weight is not a rank-3
initializer aborts the rewrite: a silently skipped conv would make the leg F2
partition verdict meaningless.

Usage:
  python3 tools/reshape_conv_1d_to_2d.py --in <fp32.onnx> --out <2d.onnx> \\
    [--parity] [--voices voices-v1.0.bin] [--report parity.json]
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import AttributeProto, TensorProto, helper, numpy_helper

CONV_OPS = ("Conv", "ConvTranspose")
DATA_INPUT, WEIGHT_INPUT = 0, 1

# Parity gate: identical inputs through both graphs; max |delta| must stay at
# float32 noise level (the reshape itself is exact, so anything above this is a
# rewrite bug, not quantization).
PARITY_TOLERANCE = 1e-4

# Per-conv arithmetic check: relative to the conv's own output scale, float32
# accumulation-order differences between the 1-D and 2-D kernels sit at ~1e-7;
# a structural rewrite error is orders of magnitude larger.
CONV_RELATIVE_TOLERANCE = 1e-5


def _ints_attribute(node, name: str, expected: int, default: list[int] | None = None) -> list[int]:
    """Read a 1-D ints attribute; `default` covers ONNX defaults left implicit."""
    for attribute in node.attribute:
        if attribute.name == name:
            if attribute.type != AttributeProto.INTS or len(attribute.ints) != expected:
                raise ValueError(f"{node.name}: attribute {name} is not {expected}-D ints")
            return list(attribute.ints)
    if default is not None:
        return list(default)
    raise ValueError(f"{node.name}: attribute {name} missing")


def _set_ints(node, name: str, values: list[int]) -> None:
    """Set a 1-D ints attribute, creating it when the export left the default implicit."""
    for attribute in node.attribute:
        if attribute.name == name:
            del attribute.ints[:]
            attribute.ints.extend(int(v) for v in values)
            return
    attribute = node.attribute.add()
    attribute.name = name
    attribute.type = AttributeProto.INTS
    attribute.ints.extend(int(v) for v in values)


def rewrite(model: onnx.ModelProto) -> dict:
    """In-place 1-D -> 2-D conv rewrite; returns a count summary.

    Name discipline: the conv keeps its 2-D output under a fresh name and the
    post-reshape *re-publishes the original value name*. Every existing
    consumer — other nodes, graph outputs, and values referenced implicitly
    from the `Loop`/`If` subgraphs — therefore keeps reading the name it always
    read, and no reference rewriting is needed anywhere.
    """
    graph = model.graph
    initializers = {init.name: init for init in graph.initializer}
    taken = {value.name for value in graph.input} | {value.name for value in graph.output} | set(initializers)
    for node in graph.node:
        taken.update(name for name in node.input if name)
        taken.update(name for name in node.output if name)
        for attribute in node.attribute:
            for subgraph in ([attribute.g] if attribute.HasField("g") else []) + list(attribute.graphs):
                taken.update(value.name for value in subgraph.input)
                taken.update(value.name for value in subgraph.output)
                taken.update(value.name for value in subgraph.value_info)

    def fresh(base: str) -> str:
        name = base
        index = 0
        while name in taken:
            index += 1
            name = f"{base}.{index}"
        taken.add(name)
        return name

    out_nodes = []
    convs = 0
    for node in graph.node:
        out_nodes.append(node)
        if node.op_type not in CONV_OPS:
            continue

        weight_name = node.input[WEIGHT_INPUT]
        init = initializers.get(weight_name)
        if init is None:
            raise ValueError(f"{node.name}: weight {weight_name} is not a graph initializer")
        weight = numpy_helper.to_array(init)
        if weight.ndim != 3:
            raise ValueError(f"{node.name}: weight rank {weight.ndim} != 3")

        kernel = _ints_attribute(node, "kernel_shape", 1)
        strides = _ints_attribute(node, "strides", 1)
        dilations = _ints_attribute(node, "dilations", 1)
        pads = _ints_attribute(node, "pads", 2)
        output_padding = (
            _ints_attribute(node, "output_padding", 1, default=[0]) if node.op_type == "ConvTranspose" else None
        )

        data = node.input[DATA_INPUT]
        value = node.output[0]  # the name every consumer already reads
        data_2d = fresh(f"{data}__2d")
        value_2d = fresh(f"{value}__conv2d")

        pre = onnx.helper.make_node("Reshape", [data, f"{data_2d}/shape"], [data_2d], name=f"{node.name}/to2d")
        post = onnx.helper.make_node("Reshape", [value_2d, f"{value_2d}/shape"], [value], name=f"{node.name}/to1d")
        graph.initializer.append(numpy_helper.from_array(np.array([0, 0, 1, -1], dtype=np.int64), f"{data_2d}/shape"))
        graph.initializer.append(numpy_helper.from_array(np.array([0, 0, -1], dtype=np.int64), f"{value_2d}/shape"))
        graph.initializer.remove(init)
        graph.initializer.append(
            numpy_helper.from_array(weight.reshape(weight.shape[0], weight.shape[1], 1, weight.shape[2]), weight_name)
        )

        node.input[DATA_INPUT] = data_2d
        node.output[0] = value_2d
        _set_ints(node, "kernel_shape", [1, kernel[0]])
        _set_ints(node, "strides", [1, strides[0]])
        _set_ints(node, "dilations", [1, dilations[0]])
        _set_ints(node, "pads", [0, pads[0], 0, pads[1]])
        if output_padding is not None:
            _set_ints(node, "output_padding", [0, output_padding[0]])

        # The pre-reshape must sit before the conv it feeds and the post-reshape
        # after it; `out_nodes` currently ends with the conv.
        out_nodes.insert(len(out_nodes) - 1, pre)
        out_nodes.append(post)
        convs += 1

    del graph.node[:]
    graph.node.extend(out_nodes)
    return {"convs_rewritten": convs, "reshapes_added": convs * 2}


def _style_row(ref_path: str, voices_path: str | None, length: int) -> tuple[np.ndarray, str]:
    """The `af_heart` style row for a `length`-token window, else a neutral fill.

    A real style row keeps the parity comparison in the model's normal
    amplitude range (~0.3 peak) instead of the degenerate output that a
    constant fill produces, so a rewrite bug cannot hide behind saturation.
    """
    import io
    import zipfile

    candidates = [voices_path] if voices_path else []
    candidates.append(os.path.join(os.path.dirname(os.path.abspath(ref_path)), "voices-v1.0.bin"))
    for path in candidates:
        if path and os.path.isfile(path):
            with zipfile.ZipFile(path) as archive:
                tensor = np.load(io.BytesIO(archive.read("af_heart.npy")))
            return tensor[length - 1].reshape(1, -1).astype(np.float32), path
    return np.full((1, 256), 0.5, dtype=np.float32), "none (constant 0.5 fallback)"


def parity(ref_path: str, candidate_path: str, voices_path: str | None = None) -> dict:
    """Identical inputs through both graphs on CPU EP; NaN in either is inconclusive.

    Inputs: `input_ids = [0, 16, 0]` — BOS + one phoneme token + EOS, the shape
    the graph's own callers always feed (a bare `[0]` underflows an internal
    `Gather` inside the reference model too, so it cannot be the parity input).
    """
    report: dict = {"ref": ref_path, "candidate": candidate_path, "verdict": "inconclusive"}
    feeds, style_source, token_ids = _feeds(ref_path, voices_path)
    report["style_source"] = style_source
    report["input_ids"] = token_ids
    try:
        ref = ort.InferenceSession(ref_path, ort.SessionOptions(), providers=["CPUExecutionProvider"])
        candidate = ort.InferenceSession(candidate_path, ort.SessionOptions(), providers=["CPUExecutionProvider"])
    except Exception as exc:
        report["error"] = f"{type(exc).__name__}: {exc}"
        return report

    try:
        a = ref.run(["waveform"], feeds)[0]
        b = candidate.run(["waveform"], feeds)[0]
    except Exception as exc:
        report["error"] = f"{type(exc).__name__}: {exc}"
        return report

    report["ref_shape"] = list(a.shape)
    report["candidate_shape"] = list(b.shape)
    report["nan_ref"] = bool(np.isnan(a).any())
    report["nan_candidate"] = bool(np.isnan(b).any())
    if report["nan_ref"] or report["nan_candidate"] or a.shape != b.shape:
        return report
    diff = np.abs(a - b)
    report["max_abs_diff"] = float(diff.max()) if diff.size else 0.0
    report["mean_abs_diff"] = float(diff.mean()) if diff.size else 0.0
    report["tolerance"] = PARITY_TOLERANCE
    report["verdict"] = "pass" if report["max_abs_diff"] <= PARITY_TOLERANCE else "fail"
    return report


def _feeds(ref_path: str, voices_path: str | None) -> tuple[dict, str, list[int]]:
    token_ids = [0, 16, 0]
    style, style_source = _style_row(ref_path, voices_path, len(token_ids))
    feeds = {
        "input_ids": np.array([token_ids], dtype=np.int64),
        "style": style,
        "speed": np.array([1.0], dtype=np.float32),
    }
    return feeds, style_source, token_ids


def _session(path: str, optimization: ort.GraphOptimizationLevel | None = None) -> ort.InferenceSession:
    options = ort.SessionOptions()
    if optimization is not None:
        options.graph_optimization_level = optimization
    return ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])


def conv_parity(ref_path: str, candidate_path: str, voices_path: str | None = None) -> dict:
    """Per-conv output comparison: the rewrite's arithmetic check.

    The waveform gate cannot separate "the rewrite is wrong" from "the model
    amplified float32 round-off" — this can. Each conv's data input and output
    is exposed on both graphs and compared relative to its own scale, so the
    question asked per conv is: *given* the difference already present in its
    input, did the rewritten conv introduce any of its own? A structural error
    shows up as an **onset** — a conv whose output relative difference crosses
    the tolerance while its input was still clean. Convs downstream of an onset
    inherit the divergence and say nothing about the rewrite.
    """
    import tempfile

    ref_model = onnx.load(ref_path)
    triples = [
        (node.name, node.input[0], node.output[0])
        for node in ref_model.graph.node
        if node.op_type in CONV_OPS
    ]
    watches: list[str] = []
    for _, source, sink in triples:
        for name in (source, sink):
            if name and name not in watches:
                watches.append(name)

    feeds, _, _ = _feeds(ref_path, voices_path)
    with tempfile.TemporaryDirectory() as tmp:
        outputs: dict[str, dict[str, np.ndarray]] = {}
        for label, path in (("ref", ref_path), ("candidate", candidate_path)):
            model = onnx.load(path)
            for name in watches:
                model.graph.output.append(helper.make_tensor_value_info(name, TensorProto.FLOAT, None))
            instrumented = os.path.join(tmp, f"{label}.onnx")
            onnx.save(model, instrumented)
            session = _session(instrumented)
            outputs[label] = dict(zip([o.name for o in session.get_outputs()], session.run(None, feeds)))

    relative: dict[str, float] = {}
    for name in watches:
        a, b = outputs["ref"][name], outputs["candidate"][name]
        scale = max(float(np.abs(a).max()) if a.size else 0.0, 1e-12)
        relative[name] = (float(np.abs(a - b).max()) / scale) if a.size else 0.0

    rows = [
        {
            "conv": conv,
            "input": source,
            "output": sink,
            "relative_in": relative[source],
            "relative_out": relative[sink],
        }
        for conv, source, sink in triples
    ]
    onset = next(
        (row for row in rows if row["relative_out"] > CONV_RELATIVE_TOLERANCE and row["relative_in"] <= CONV_RELATIVE_TOLERANCE),
        None,
    )
    clean = [row for row in rows if row["relative_in"] <= CONV_RELATIVE_TOLERANCE]
    return {
        "convs": len(rows),
        "clean_convs": len(clean),
        "max_relative_out_over_clean_convs": max((row["relative_out"] for row in clean), default=0.0),
        "divergence_onset": onset["conv"] if onset else None,
        "conv_parity_ok": onset is None,
        "tolerance": CONV_RELATIVE_TOLERANCE,
        "rows": rows,
    }


def control_amplification(ref_path: str, voices_path: str | None = None) -> dict:
    """The model's own kernel-order sensitivity: same graph, different fusion.

    `ORT_ENABLE_BASIC` and `ORT_ENABLE_ALL` differ only in which fused kernels
    run, so the delta between them is what an *arithmetically identical* kernel
    change does to the final waveform on this model. It is the scale against
    which the rewrite's waveform diff must be read.
    """
    feeds, _, _ = _feeds(ref_path, voices_path)
    basic = _session(ref_path, ort.GraphOptimizationLevel.ORT_ENABLE_BASIC).run(["waveform"], feeds)[0]
    all_opt = _session(ref_path, ort.GraphOptimizationLevel.ORT_ENABLE_ALL).run(["waveform"], feeds)[0]
    diff = np.abs(basic - all_opt)
    return {
        "config_a": "ORT_ENABLE_BASIC",
        "config_b": "ORT_ENABLE_ALL",
        "max_abs_diff": float(diff.max()),
        "mean_abs_diff": float(diff.mean()),
        "note": "same graph, same weights, same math — only fused-kernel selection differs",
    }


def _selftest() -> int:
    """The rewrite must be arithmetically exact on a synthetic 1-D conv pair."""
    import tempfile

    rng = np.random.default_rng(20260911)
    length, in_channels, out_channels, kernel, stride, dilation, pad = 17, 3, 4, 3, 2, 2, 2
    weight = rng.standard_normal((out_channels, in_channels, kernel)).astype(np.float32)
    bias = rng.standard_normal((out_channels,)).astype(np.float32)
    x = rng.standard_normal((1, in_channels, length)).astype(np.float32)

    def manual_conv(x1d: np.ndarray) -> np.ndarray:
        dilated = dilation * (kernel - 1) + 1
        out_length = (length + 2 * pad - dilated) // stride + 1
        padded = np.zeros((in_channels, length + 2 * pad), dtype=np.float64)
        padded[:, pad : pad + length] = x1d[0]
        out = np.zeros((out_channels, out_length), dtype=np.float64)
        for o in range(out_channels):
            for l in range(out_length):
                acc = 0.0
                for c in range(in_channels):
                    for k in range(kernel):
                        acc += weight[o, c, k] * padded[c, l * stride + k * dilation]
                out[o, l] = acc + bias[o]
        return out[None, ...]

    model = onnx.helper.make_model(
        onnx.helper.make_graph(
            [
                onnx.helper.make_node(
                    "Conv",
                    ["x", "w", "b"],
                    ["y"],
                    name="conv1d",
                    kernel_shape=[kernel],
                    strides=[stride],
                    dilations=[dilation],
                    pads=[pad, pad],
                )
            ],
            "selftest",
            [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, in_channels, length])],
            [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, out_channels, "l"])],
            [
                numpy_helper.from_array(weight, "w"),
                numpy_helper.from_array(bias, "b"),
            ],
        ),
        opset_imports=[helper.make_opsetid("", 17)],
    )

    with tempfile.TemporaryDirectory() as tmp:
        source, target = os.path.join(tmp, "one_d.onnx"), os.path.join(tmp, "two_d.onnx")
        onnx.save(model, source)
        rewritten = onnx.load(source)
        summary = rewrite(rewritten)
        onnx.checker.check_model(rewritten)
        onnx.save(rewritten, target)

        feeds = {"x": x}
        one_d = _session(source).run(["y"], feeds)[0]
        two_d = _session(target).run(["y"], feeds)[0]
        truth = manual_conv(x)
        if one_d.shape != two_d.shape or one_d.shape != truth.shape:
            print(f"selftest FAIL: shapes {one_d.shape} {two_d.shape} {truth.shape}", file=sys.stderr)
            return 1
        rel = float(np.abs(one_d - two_d).max()) / max(float(np.abs(truth).max()), 1e-12)
        if rel > CONV_RELATIVE_TOLERANCE:
            print(f"selftest FAIL: rewrite changed the result (relative {rel:.3e})", file=sys.stderr)
            return 1
        for label, got in (("1d", one_d), ("2d", two_d)):
            error = float(np.abs(got - truth).max()) / max(float(np.abs(truth).max()), 1e-12)
            if error > 1e-5:
                print(f"selftest FAIL: {label} differs from float64 truth ({error:.3e})", file=sys.stderr)
                return 1
        if summary["convs_rewritten"] != 1 or len([n for n in rewritten.graph.node if n.op_type in CONV_OPS]) != 1:
            print(f"selftest FAIL: conv count changed {summary}", file=sys.stderr)
            return 1
    print("ok")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--in", dest="source", help="source fp32 ONNX")
    parser.add_argument("--out", dest="target", help="destination rewritten ONNX")
    parser.add_argument("--parity", action="store_true", help="run the CPU-EP parity gate after writing")
    parser.add_argument("--voices", help="voices-v1.0.bin for the parity style row (default: next to --in)")
    parser.add_argument("--report", help="write the parity report JSON here")
    parser.add_argument("--conv-parity", action="store_true", help="add the per-conv arithmetic check")
    parser.add_argument("--control", action="store_true", help="add the same-graph fusion-sensitivity control")
    parser.add_argument("--selftest", action="store_true", help="synthetic exactness check, no model needed")
    arguments = parser.parse_args(argv)

    if arguments.selftest:
        return _selftest()
    if not (arguments.source and arguments.target):
        parser.error("--in and --out are required (or use --selftest)")

    model = onnx.load(arguments.source)
    summary = rewrite(model)
    onnx.checker.check_model(model)
    onnx.save(model, arguments.target)
    print(f"rewrote {summary['convs_rewritten']} convs, added {summary['reshapes_added']} reshapes -> {arguments.target}")

    if arguments.conv_parity:
        conv_report = conv_parity(arguments.source, arguments.target, arguments.voices)
        print(
            f"conv parity: ok={conv_report['conv_parity_ok']} "
            f"onset={conv_report['divergence_onset']} "
            f"max relative out over clean convs={conv_report['max_relative_out_over_clean_convs']:.3e} "
            f"({conv_report['clean_convs']}/{conv_report['convs']} convs had clean inputs)"
        )
    else:
        conv_report = None

    if arguments.control:
        control = control_amplification(arguments.source, arguments.voices)
        print(f"control (BASIC vs ALL on the same graph): max_abs_diff={control['max_abs_diff']:.3e}")
    else:
        control = None

    if arguments.parity:
        report = parity(arguments.source, arguments.target, arguments.voices)
        report["rewrite"] = summary
        report["source"] = arguments.source
        report["target"] = arguments.target
        if conv_report:
            report["per_conv"] = conv_report
        if control:
            report["fusion_control"] = control
        if arguments.report:
            with open(arguments.report, "w") as handle:
                handle.write(json.dumps(report, indent=2) + "\n")
        if report["verdict"] == "pass":
            print(f"parity max_abs_diff={report['max_abs_diff']:.3e} verdict=pass")
        elif report["verdict"] == "fail":
            print(f"parity max_abs_diff={report['max_abs_diff']:.3e} verdict=fail (tolerance {PARITY_TOLERANCE})")
        else:
            print(f"parity_inconclusive: {report}")
        return 0 if report["verdict"] == "pass" else 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
