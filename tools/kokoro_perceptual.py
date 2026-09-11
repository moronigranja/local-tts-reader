#!/usr/bin/env python3
"""Instrumental quality metrics for a Kokoro precision candidate (leg A, decisions #148).

The adoption gate for the int8 tier is a human blind A/B listen, not a number.
This tool exists so the blind pass has a companion record: it answers "how far
is the candidate from the fp32 reference" with four metrics that a listener can
corroborate, plus the max-abs-diff already used by the on-device oracle gate
(decisions #67 rule b / #86).

Metrics (all computed after RMS level-matching the candidate to the reference,
which is exactly what the blind set does, so a pure gain difference cannot look
like damage):

  max_abs_diff / mean_abs_diff  waveform continuity with the #86 gate
  logmel_l1_db                  80-bin Slaney log-mel |delta|, mean over frames
  mcd_db                        13-coefficient mel-cepstral distortion
  seg_snr_db                    20 ms frame SNR, clipped, non-finite frames skipped

Only the waveform pair semantics matter for the #86 comparison; the other three
are per-band/per-frame views of the same error, which is where a 4-bit weight
change shows up even when the peak sample delta stays under the gate.

Bands (`mcd_db <= 1.0 and seg_snr_db >= 20 and logmel_l1_db <= 1.5`) are
ADVISORY: they flag a candidate as likely-damaged so the blind pass is not the
only thing standing between a broken pack and a ship. Do not treat
`instrumental_reject` as a veto and do not amend ORACLE_REJECT_THRESHOLD.

Usage:
  python3 tools/kokoro_perceptual.py --ref ref.wav --deg cand.wav --out m.json
  python3 tools/kokoro_perceptual.py --dir docs/prints/perfspike --out summary.json
  python3 tools/kokoro_perceptual.py --selftest
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

import numpy as np
from scipy.fft import dct
from scipy.io import wavfile

SAMPLE_RATE = 24_000
N_FFT = 1024
HOP = 256
N_MELS = 80
FMIN = 20.0
FMAX = 12_000.0
MEL_FLOOR = 1e-6
SEG_MS = 20.0

BAND_MCD_DB = 1.0
BAND_SEG_SNR_DB = 20.0
BAND_LOGMEL_L1_DB = 1.5

# The metric keys every report must carry — the selftest asserts on this set.
METRIC_KEYS = ("max_abs_diff", "mean_abs_diff", "logmel_l1_db", "mcd_db", "seg_snr_db")


def read_mono(path: str) -> np.ndarray:
    """Read a WAV as float64 in [-1, 1) via int16/32768 (the device's PCM scale)."""
    rate, data = wavfile.read(path)
    if rate != SAMPLE_RATE:
        raise ValueError(f"{path}: sample rate {rate} != {SAMPLE_RATE}")
    if data.ndim > 1:
        data = data[:, 0]
    if data.dtype == np.int16:
        return data.astype(np.float64) / 32768.0
    if data.dtype == np.float32 or data.dtype == np.float64:
        return data.astype(np.float64)
    raise ValueError(f"{path}: unsupported sample type {data.dtype}")


def _hz_to_mel(f: np.ndarray) -> np.ndarray:
    """Slaney's auditory-style mel scale (librosa `htk=False`)."""
    f_min, f_sp = 0.0, 200.0 / 3.0
    min_log_hz = 1000.0
    min_log_mel = (min_log_hz - f_min) / f_sp
    logstep = math.log(6.4) / 27.0
    linear = (f - f_min) / f_sp
    log = min_log_mel + np.log(np.maximum(f, min_log_hz) / min_log_hz) / logstep
    return np.where(f < min_log_hz, linear, log)


def _mel_to_hz(m: np.ndarray) -> np.ndarray:
    f_min, f_sp = 0.0, 200.0 / 3.0
    min_log_hz = 1000.0
    min_log_mel = (min_log_hz - f_min) / f_sp
    logstep = math.log(6.4) / 27.0
    linear = f_min + f_sp * m
    log = min_log_hz * np.exp(logstep * (m - min_log_mel))
    return np.where(m < min_log_mel, linear, log)


def mel_filterbank() -> np.ndarray:
    """80 Slaney-normalized triangular filters over the rfft bins, shape (bins, 80)."""
    points = _mel_to_hz(np.linspace(_hz_to_mel(np.array(FMIN)), _hz_to_mel(np.array(FMAX)), N_MELS + 2))
    bins = np.linspace(0.0, SAMPLE_RATE / 2.0, N_FFT // 2 + 1)
    fb = np.zeros((len(bins), N_MELS))
    for i in range(N_MELS):
        lower, center, upper = points[i], points[i + 1], points[i + 2]
        rising = (bins - lower) / (center - lower)
        falling = (upper - bins) / (upper - center)
        fb[:, i] = np.maximum(0.0, np.minimum(rising, falling)) * (2.0 / (upper - lower))
    return fb


_FB = mel_filterbank()


def log_mel(x: np.ndarray) -> np.ndarray:
    """log10 mel spectrogram, shape (frames, 80). Hann, n_fft 1024, hop 256."""
    if len(x) < N_FFT:
        return np.zeros((0, N_MELS))
    _, _, z = _stft(x)
    power = (np.abs(z) ** 2).T  # (frames, bins)
    return np.log10(power @ _FB + MEL_FLOOR)


def _stft(x: np.ndarray):
    from scipy.signal import stft

    return stft(x, fs=SAMPLE_RATE, window="hann", nperseg=N_FFT, noverlap=N_FFT - HOP, boundary=None, padded=False)


def seg_snr(ref: np.ndarray, deg: np.ndarray) -> tuple[float, int, int]:
    """Mean clipped frame SNR in dB, plus (frames_used, frames_skipped)."""
    frame = int(SAMPLE_RATE * SEG_MS / 1000.0)
    frames = min(len(ref), len(deg)) // frame
    values = []
    skipped = 0
    for i in range(frames):
        r = ref[i * frame : (i + 1) * frame]
        d = deg[i * frame : (i + 1) * frame]
        err = r - d
        num = float(np.sum(r * r))
        den = float(np.sum(err * err))
        if den <= 0.0:
            values.append(35.0)  # bit-identical frame
            continue
        if num <= 0.0:
            skipped += 1  # silent reference: SNR undefined
            continue
        snr = 10.0 * math.log10(num / den)
        if not math.isfinite(snr):
            skipped += 1
            continue
        values.append(min(max(snr, -10.0), 35.0))
    if not values:
        return float("-inf"), 0, skipped
    return float(np.mean(values)), len(values), skipped


def compare(ref: np.ndarray, deg: np.ndarray) -> dict:
    """All leg A metrics for one (reference, candidate) pair."""
    ref_rms = float(np.sqrt(np.mean(ref**2)))
    deg_rms = float(np.sqrt(np.mean(deg**2)))
    if deg_rms == 0.0:
        return {"error": "silent candidate"}
    gain = ref_rms / deg_rms
    deg = deg * gain

    n = min(len(ref), len(deg))
    ref, deg = ref[:n], deg[:n]
    diff = np.abs(ref - deg)

    lm_ref, lm_deg = log_mel(ref), log_mel(deg)
    frames = min(len(lm_ref), len(lm_deg))
    logmel_l1 = float(np.mean(np.abs(lm_ref[:frames] - lm_deg[:frames]))) if frames else float("nan")
    if frames:
        c_ref = dct(lm_ref[:frames], type=2, norm="ortho")[:, :13]
        c_deg = dct(lm_deg[:frames], type=2, norm="ortho")[:, :13]
        mcd = float(np.mean(np.sqrt(2.0 * np.sum((c_ref - c_deg) ** 2, axis=1))))
    else:
        mcd = float("nan")

    snr, snr_frames, skipped = seg_snr(ref, deg)

    plausible = mcd <= BAND_MCD_DB and snr >= BAND_SEG_SNR_DB and logmel_l1 <= BAND_LOGMEL_L1_DB
    return {
        "max_abs_diff": float(diff.max()) if n else 0.0,
        "mean_abs_diff": float(diff.mean()) if n else 0.0,
        "logmel_l1_db": logmel_l1,
        "mcd_db": mcd,
        "seg_snr_db": snr,
        "gain_applied": gain,
        "ref_rms": ref_rms,
        "deg_rms": deg_rms,
        "len_ratio": len(deg) / len(ref) if len(ref) else float("nan"),
        "aligned_samples": n,
        "logmel_frames": frames,
        "seg_frames_used": snr_frames,
        "frames_skipped": skipped,
        "bands": {"mcd_db": BAND_MCD_DB, "seg_snr_db": BAND_SEG_SNR_DB, "logmel_l1_db": BAND_LOGMEL_L1_DB},
        "plausible_fidelity": bool(plausible),
        "instrumental_reject": not plausible,
    }


# --------------------------------------------------------------------------- #
# drivers


def _write(obj: dict, path: str | None) -> None:
    text = json.dumps(obj, indent=2)
    if path:
        with open(path, "w") as fh:
            fh.write(text + "\n")
    print(text)


def one(ref_path: str, deg_path: str, out: str | None) -> dict:
    report = {"ref": os.path.basename(ref_path), "deg": os.path.basename(deg_path)}
    report.update(compare(read_mono(ref_path), read_mono(deg_path)))
    _write(report, out)
    return report


def directory(path: str, out: str | None) -> dict:
    """Pair every `*_fp32_<lang>.wav` with its `*_int8_<lang>.wav` sibling."""
    pairs = []
    for name in sorted(os.listdir(path)):
        if not name.endswith(".wav") or "_fp32_" not in name:
            continue
        deg_name = name.replace("_fp32_", "_int8_")
        deg_path = os.path.join(path, deg_name)
        if not os.path.isfile(deg_path):
            continue
        pairs.append((name, deg_name))

    summary = {"dir": path, "pairs": [], "skipped": [], "rejected": 0}
    for ref_name, deg_name in pairs:
        ref_path, deg_path = os.path.join(path, ref_name), os.path.join(path, deg_name)
        report = {"ref": ref_name, "deg": deg_name}
        report.update(compare(read_mono(ref_path), read_mono(deg_path)))
        pair_out = os.path.join(path, deg_name.replace(".wav", ".perceptual.json"))
        with open(pair_out, "w") as fh:
            fh.write(json.dumps(report, indent=2) + "\n")
        if "error" in report:
            summary["skipped"].append({"ref": ref_name, "deg": deg_name, "error": report["error"]})
            print(f"{deg_name}: SKIPPED ({report['error']})")
            continue
        if report["instrumental_reject"]:
            summary["rejected"] += 1
        summary["pairs"].append(
            {
                "ref": ref_name,
                "deg": deg_name,
                "report": os.path.basename(pair_out),
                "max_abs_diff": report["max_abs_diff"],
                "mean_abs_diff": report["mean_abs_diff"],
                "logmel_l1_db": report["logmel_l1_db"],
                "mcd_db": report["mcd_db"],
                "seg_snr_db": report["seg_snr_db"],
                "instrumental_reject": report["instrumental_reject"],
            }
        )
        print(
            f"{deg_name}: maxdiff={report['max_abs_diff']:.6f} "
            f"logmel_l1={report['logmel_l1_db']:.3f} mcd={report['mcd_db']:.3f} "
            f"segsnr={report['seg_snr_db']:.2f} "
            f"{'REJECT' if report['instrumental_reject'] else 'plausible'}"
        )
    summary["pair_count"] = len(summary["pairs"])
    _write(summary, out)
    return summary


def _selftest() -> int:
    """Two 1 s tones: one +2 dB with the 3 kHz band attenuated — must be rejected."""
    import tempfile

    t = np.arange(SAMPLE_RATE, dtype=np.float64) / SAMPLE_RATE
    ref = 0.30 * np.sin(2 * math.pi * 440.0 * t) + 0.30 * np.sin(2 * math.pi * 3000.0 * t)
    deg = 1.2589 * (0.30 * np.sin(2 * math.pi * 440.0 * t) + 0.015 * np.sin(2 * math.pi * 3000.0 * t))

    with tempfile.TemporaryDirectory() as tmp:
        ref_path = os.path.join(tmp, "a_fp32_en-us.wav")
        deg_path = os.path.join(tmp, "a_int8_en-us.wav")
        wavfile.write(ref_path, SAMPLE_RATE, np.clip(ref * 32768, -32768, 32767).astype(np.int16))
        wavfile.write(deg_path, SAMPLE_RATE, np.clip(deg * 32768, -32768, 32767).astype(np.int16))

        report = compare(read_mono(ref_path), read_mono(deg_path))
        missing = [k for k in METRIC_KEYS if k not in report]
        if missing:
            print(f"selftest FAIL: missing metric keys {missing}", file=sys.stderr)
            return 1
        if not report["instrumental_reject"]:
            print(f"selftest FAIL: attenuated pair judged plausible: {report}", file=sys.stderr)
            return 1
        # A bit-identical pair must come back plausible with zero error.
        same = compare(read_mono(ref_path), read_mono(ref_path))
        if same["instrumental_reject"] or same["max_abs_diff"] != 0.0:
            print(f"selftest FAIL: identical pair flagged: {same}", file=sys.stderr)
            return 1
        summary = directory(tmp, os.path.join(tmp, "perceptual_summary.json"))
        if summary["pair_count"] != 1 or summary["rejected"] != 1:
            print(f"selftest FAIL: directory mode summary {summary}", file=sys.stderr)
            return 1

    print("ok")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ref")
    parser.add_argument("--deg")
    parser.add_argument("--dir")
    parser.add_argument("--out")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)

    if args.selftest:
        return _selftest()
    if args.dir:
        directory(args.dir, args.out or os.path.join(args.dir, "perceptual_summary.json"))
        return 0
    if not (args.ref and args.deg):
        parser.error("--ref/--deg, --dir, or --selftest is required")
    one(args.ref, args.deg, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
