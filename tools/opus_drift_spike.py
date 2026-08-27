#!/usr/bin/env python3
"""Opus round-trip drift spike for the pregen cache (decisions #44 review, 2026-08-27).

Measures what storing a 24 kHz mono Kokoro passage as Opus does to the PCM
stream and to the seconds-based sentence anchors (the `.meta` sidecar): size
ratio, duration drift, global alignment offset, and per-boundary residual
after a constant-offset + rate-ratio correction.

Host measurement (2026-08-27, real blobs pt-br 22.83 s / en-us 40.43 s,
libopus via ffmpeg, 24 + 32 kbps, 24k/48k rates):
- 24 kbps 24k-round-trip: ~16x smaller (~11 MB/h vs 170 MB/h PCM), duration
  drift +0.0 ms, constant lag 0.0 ms, worst boundary residual +20 ms = one
  20 ms Opus frame. No accumulating drift; `seconds x 24000` anchor mapping
  survives unchanged (ffmpeg trims pre-skip and pads the end).
- 20 ms quantization is inside the engine's own anchor jitter (31-37 ms,
  KokoroGrainSpike) and the 250-670 ms rendered sentence pauses.
- 48k decode path (what Android MediaCodec outputs) keeps the same local
  residual profile (<= ~28 ms); pre-skip handling is decoder-specific.

Device pass (S22, pending): export a passage + its `.meta` anchors (adb),
run with explicit args, confirm MediaCodec 48 kHz output + pre-skip behavior
match, then trust the 24k numbers.

Usage:
  python3 tools/opus_drift_spike.py [WAV ANCHORS_CSV]...
    ANCHORS_CSV = comma-separated sentence start seconds; the wav duration
    is appended as the final anchor automatically.
  Defaults (no args): the host Kokoro blob cache + its known anchor sets.
"""

import argparse
import os
import subprocess
import tempfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import correlate

HOST_CACHE = os.path.expanduser("~/.cache/local-tts-reader/packs")
default_cases = [
    (f"{HOST_CACHE}/blob-pt-br.wav", "0.05,7.34,12.68,16.93"),
    (f"{HOST_CACHE}/blob-en-us.wav", "0.08,7.65,21.76,27.26,29.36,34.63"),
]

TMP = tempfile.mkdtemp(prefix="opus-drift-")


def run(cmd):
    subprocess.run(cmd, check=True, capture_output=True)


def load(path):
    sr, data = wavfile.read(path)
    return sr, data.astype(np.float64)


def encode_decode(src, bitrate, in_rate, out_rate=None):
    """Round-trip: wav -> opus (libopus, in_rate) -> wav (out_rate or signaled)."""
    base = f"{os.path.basename(src).rsplit('.', 1)[0]}-{bitrate}-{in_rate}"
    opus = f"{TMP}/{base}.opus"
    run(["ffmpeg", "-y", "-v", "error", "-i", src, "-c:a", "libopus",
         "-b:a", str(bitrate), "-ar", str(in_rate), opus])
    out = f"{TMP}/{base}-{out_rate or 'sig'}.wav"
    args = ["ffmpeg", "-y", "-v", "error", "-i", opus]
    if out_rate:
        args += ["-ar", str(out_rate)]
    args += [out]
    run(args)
    return out, opus


def metrics(orig, sr, anchors, decoded, dsr):
    n_orig, n_dec = len(orig), len(decoded)
    dur_drift_ms = (n_dec / dsr - n_orig / sr) * 1000
    # Global alignment: correlate a 1 s excerpt over +-200 ms. Note: when the
    # sample rates differ the correlation peak is ambiguous; the local
    # per-boundary residuals (computed with the proper index mapping) are the
    # trustworthy signal for the 48k variants.
    c0 = n_orig // 2 - sr // 2
    win = decoded[max(0, int(c0 * dsr / sr) - int(0.2 * dsr)):
                  int(c0 * dsr / sr) + int(1.2 * dsr)]
    ref = orig[c0:c0 + sr]
    corr = correlate(win, ref, mode="valid")
    lag_samples = int(np.argmax(corr)) - int(0.2 * dsr)
    lag_ms = lag_samples / dsr * 1000
    # Per-boundary residual after constant-lag correction + rate mapping:
    # find where the 20 ms window around each anchor actually lands.
    res = []
    for s in anchors:
        t_orig = s * sr
        pred = int(t_orig * dsr / sr) + lag_samples
        r = int(dsr / sr)
        if pred - 480 * r < 0 or pred + 960 * r > len(decoded):
            continue
        w_orig = orig[int(t_orig):int(t_orig) + 480]
        w_dec = decoded[pred - 480 * r: pred + 960 * r]
        loc = correlate(w_dec, w_orig, mode="valid")
        residual = int(np.argmax(loc)) - 480 * r  # samples @ dsr
        res.append(residual / dsr * 1000)
    return dur_drift_ms, lag_ms, res


def report(src, anchors_csv):
    anchors = [float(a) for a in anchors_csv.split(",") if a]
    sr, orig = load(src)
    anchors.append(len(orig) / sr)  # final anchor = passage end
    print(f"\n=== {os.path.basename(src)} ({len(orig) / sr:.2f}s, {sr} Hz, "
          f"anchors at {[f'{a:.2f}' for a in anchors]} s) ===")
    print(f"raw PCM: {len(orig) * 2 / 1e6:.2f} MB")
    for in_rate, bps, out_rate in [
        (24_000, 24_000, 24_000), (24_000, 32_000, 24_000),
        (48_000, 24_000, 48_000), (24_000, 24_000, 48_000),
    ]:
        out, opus = encode_decode(src, bps, in_rate, out_rate)
        dsr, decoded = load(out)
        dur, lag, res = metrics(orig, sr, anchors, decoded, dsr)
        size = os.path.getsize(opus)
        print(f"  opus {bps // 1000}k in@{in_rate} out@{dsr}: "
              f"{size / 1e6:.2f} MB ({len(orig) * 2 / size:.1f}x smaller) | "
              f"dur drift {dur:+.1f} ms | lag {lag:+.1f} ms | "
              f"boundary residual n={len(res)} max={max(res, default=0):+.1f} ms "
              f"mean={np.mean(res):+.1f} ms")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", nargs="*", metavar="WAV ANCHORS_CSV",
                        help="wav + comma-separated anchor starts (pairs; "
                             "defaults to the host blob cache)")
    args = parser.parse_args()
    cases = list(zip(args.cases[::2], args.cases[1::2])) if args.cases else default_cases
    for wav, anchors in cases:
        report(wav, anchors)