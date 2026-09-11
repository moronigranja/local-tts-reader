# Kokoro-82M on-device performance and fidelity — reusable measurement report

Self-contained report of the cross-app Kokoro performance spike (this repo's decisions
#148/#150, 2026-09-11). Everything measured is reproduced inline here, so the document
stands on its own: no other file in this repo needs to be read, and the raw JSON/WAV
evidence (which lives in the gitignored `docs/prints/perfspike/`) is not required.

Written for anyone about to make the same comparisons on Android — a reader/TTS app,
a model-port project, or a framework choosing between ONNX Runtime execution paths.

**Devices**: Samsung Fold 8 (`SM-F971B`, Snapdragon 8 Elite, Android 16/SDK 37),
Samsung Galaxy S22 Ultra (`SM-S908U1`, Snapdragon 8 Gen 1, Android 14/SDK 36),
Bigme HiBreak (`MT6765`, 8×A53, 3.9 GB RAM, Android 14/SDK 34).
**Model**: Kokoro-82M v1.0 fp32 ONNX (325,505,369 B) with the v1.0 voices pack, synthesizing
English and Brazilian-Portuguese passages. **Runtime**: onnxruntime-android 1.29.0, CPU EP,
6 intra-op threads unless stated.

---

## 1. Headline findings

1. **The int8 Kokoro pack that Android apps actually ship is a net loss on CPU.** The
   92,361,271 B dynamic-QUInt8 export (NekoSpeak's mirror of the thewh1teagle lineage) ran
   **~2× slower than fp32 on the Fold 8** and **1.44× slower on the S22 Ultra** in the same
   session, drew **1.6–1.9× the energy per hour of audio**, and was **audibly worse on
   pt-br** ("a little frayed", both devices independently). en-us was
   near-indistinguishable with a slight preference for int8. Do not assume int8 pays off on
   ARM CPUs; dynamic activation quantization adds `DynamicQuantizeLinear`/`Cast` traffic on
   top of `MatMulInteger`/`ConvInteger`, and that path is not always faster than the fp32
   MLAS GEMMs.
2. **Window length is a latency lever, not a throughput lever.** Throughput is flat across
   the 150/300/510-phoneme caps, while time-to-first-audio grows with the cap — on the
   HiBreak, from **8.7 s at 150 to 143.6 s at 510**. If a weak device must start speaking
   quickly, cap the window small; there is no speed to be won by widening it.
3. **Per-window streaming into `AudioTrack` loses to a whole-passage static buffer.**
   Streaming reached first audio after 34/35/85 s (Fold/S22/HiBreak) versus 17–56 ms for
   `MODE_STATIC`, and underran twice per 64 s of audio on every device.
4. **The only measured speed lever is an Android ADPF hint session** (PerformanceHintManager):
   +23 % throughput on the Fold (RTF 0.596 → 0.459) — at 12 % *more* energy per audio-hour.
   Thread count, spinning, and thread priority did nothing measurable.
5. **Duty cycling lowers power but not energy per unit of audio** (2.42 → 2.72 Wh per
   audio-hour at 100 % → 33 % duty): the rest phases do not return the device to its idle
   floor. Use it for thermals, not battery.
6. **ORT's CPU EP does have a `MatMulNBits` (int4) kernel** (host 1.29.0 and
   onnxruntime-android 1.29, all three devices) — with stricter packed weight shapes than
   commonly assumed.
7. **The Kokoro vocoder amplifies float32 kernel-order noise to ~1e-2 on the waveform.**
   A waveform parity gate at 1e-4 (or a "max abs PCM diff ≤ 0.001" adoption gate) cannot be
   passed by *any* change of computation path, including one that is arithmetically exact —
   it rejects by construction. Gate on per-layer relative error, or on listening.

---

## 2. Artifacts (pinned)

| artifact | URL | size (B) | sha256 |
|---|---|---|---|
| `kokoro-v1.0.onnx` (fp32) | `github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx` | 325,505,369 | `beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a` |
| `voices-v1.0.bin` | `…/model-files-v1.1/voices-v1.0.bin` | 28,214,398 | `bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d` |
| int8 tier, as shipped | `github.com/siva-sub/NekoSpeak/releases/download/v1.0.0/kokoro-v1.0.int8.onnx` | 92,361,271 | `6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb` |

**Two artifact traps.**

* The int8 file is **not** the 114,119,327 B / `ae315a79…` int8 export that older
  benchmarks (e.g. this repo's decisions #86/#99) measured — different quantization, so any
  "int8 is ~30 % faster" figure from that lineage does not transfer.
* The released int8 graph names its waveform output **`audio`**, while the fp32 export names
  it `waveform`. An engine that hard-codes `waveform` fails at inference with
  `Unknown output name waveform, expected one of [audio]`. Two options: teach the engine the
  alias, or re-export with the expected name. For measurement we renamed the graph output
  (metadata only; outputs verified bit-identical to the original on the same inputs,
  `tools/adapt_kokoro_int8_output.py` in this repo) — staged digest
  `0b81e78af8e9266bc77164e737d9e9e80e0354a9cbd37dc3b51b4737f62f6d86`.
  The int8 export also has no `duration` output, so timing-based pause insertion and
  token-edge trimming are unavailable in that pack.

---

## 3. Method (what to copy, and why)

### 3.1 Harness shape

One ORT session per configuration (session options are fixed at creation), an **untimed
warm-up window** before the timed section, then best-of-N passes over a multi-passage
corpus. Per window record the wall time, and publish `first_window_ms` (pass 1's first
window = the time-to-first-audio proxy), plus p50/p95/max over all timed windows. Flush
results to JSON after **every** step — long legs get killed by low-memory killers and by
the device sleeping, and a leg that only writes at the end loses everything.

RTF = wall / produced audio; throughput = produced audio per wall second. Note that these
are inflated/deflated by *inserted silence*: a smaller window cap produces more inter-window
pauses, so the same text yields more "audio seconds". Compare caps by **wall time for the
same text**, not by RTF alone.

### 3.2 Energy measurement on Android — three traps

* Take `BATTERY_PROPERTY_CURRENT_NOW` × `ACTION_BATTERY_CHANGED`'s voltage, and **only count
  samples taken while unplugged** — while charging, the current is a charge current, not a
  load. Report `unplugged_fraction` per leg; below ~0.9 the power number is not a
  measurement.
* **Some devices lie.** On the S22 Ultra the fuel gauge reported ~0.3 mA (≈1 mW) and a frozen
  charge counter while the phone was burning ~4 W (battery level 66 → 43 % over the session).
  A floor on mean power (we used 50 mW) turns that into an explicit
  `energy_valid=false, energy_invalid_reason="gauge_below_floor"` instead of a plausible-looking
  wrong number. Battery *level* moved only 1 % per ~3 min, far too coarse for per-leg energy.
* Hold a `PARTIAL_WAKE_LOCK` for the leg's duration, and normalise energy to a fixed amount
  of produced audio (`Wh per audio-hour = mean power × RTF`) — otherwise a fast leg looks
  expensive and a slow leg cheap.

### 3.3 Screen state and thermal state dominate Android measurements

* On the Fold 8, **unplugged + screen-off** throttles synthesis hard: RTF 1.49–1.79 where
  screen-on measured 0.52–0.60 for the same window cap. It is not Doze
  (`cmd deviceidle whitelist +<pkg>` changes nothing) and not thermal (37.8 °C during the
  slow runs, 40.7 °C during the fast ones). Keep the screen on for measurement legs
  (`input keyevent 26` to wake — `KEYCODE_WAKEUP` is ignored by some ROMs — plus a long
  `settings put system screen_off_timeout`), and record the state in every result file.
* The same devices can be **2× faster cold than warm**: the identical fp32 config measured
  RTF 0.36 on a rested Fold and 0.60 minutes after a heavy leg. Any cross-config comparison
  that spans more than a few minutes needs a re-run of the first config at the end to
  quantify drift (on the S22 the same config measured 0.813 / 0.832 / 0.678 across the
  session — always fastest first).
* `android.os.ThermalManager` is hidden from apps on recent ROMs
  (`getCurrentThermalStatus` unresolvable), so battery temperature is often the only
  in-app thermal signal.

### 3.4 Wireless adb, low-memory devices, and pulling results

* `adb tcpip 5555` + `adb connect` survives a cable pull but **not a reboot**, and devices
  drop off the network when they sleep with the screen off — which strands results mid-leg.
  Pull between legs, and read outputs via `/storage/emulated/0/...` (through `run-as`),
  not `/sdcard/...` (permission denied on some ROMs).
* On a 3.9 GB device, a large model **plus** an fp32 oracle session is enough to drive the
  device into swap and an lmkd kill loop (load average 16–20; the instrumented process
  restarted mid-leg). Legs that need two sessions resident at once are not runnable there;
  budget one session per leg on low-RAM targets.

### 3.5 Deciding precision/quality changes: the oracle gate

The usual gate — "max abs PCM difference vs an fp32 reference ≤ 0.001" — is **not usable for
this model class**. Switching *only* the graph-optimization level of the unmodified fp32
graph (which changes fusion/kernel selection, not math) moved the waveform by
**0.012–0.030** max-abs, and a 1-D→2-D conv rewrite that is arithmetically exact (verified
per layer, ≤1.6e-06 relative on a clean input) moved it by **0.057–0.084**. The vocoder's
sine/harmonic source integrates tiny numerical differences into large sample-level ones, so
any change of computation path costs ~1e-2 on the waveform.

Better gates:

* **Per-layer relative error** against the reference, evaluated as an *onset* test: flag a
  layer only when its own relative output error exceeds tolerance while its input was still
  clean. (Our rewrite showed no onset across 97 convolutions.)
* **A listening pass** (below) for anything audible.
* If an absolute waveform threshold is required, set it from a measured *control*: run the
  same unmodified model under a different fusion/kernel configuration and use that delta as
  the noise floor.

### 3.6 Blind listening protocol (the actual adoption gate)

Level-match the candidate to the reference by RMS (so loudness cannot leak the answer),
shuffle the two into `1.wav`/`2.wav` with a seeded RNG, and keep the mapping in a `key.json`
next to them. Shuffle **per pair**, not per set — then two devices independently agreeing on
"the int8 slot is frayed" proves the difference is real rather than an order artifact. In our
run the listener reported "volume sounds about the same", confirming the level-match.

### 3.7 Instrumental companions (advisory, not gates)

Computed RMS-matched on the truncated pair, with Slaney mel (`n_fft` 1024, hop 256, 20–12000
Hz, 80 bins), 13-coefficient MCD (DCT-II of `log10(mel+1e-6)`), and 20 ms frame SNR clipped
to [−10, 35] dB:

| metric | advisory band | Fold int8 pt-br | Fold int8 en-us | S22 int8 pt-br | S22 int8 en-us |
|---|---|---|---|---|---|
| `max_abs_diff` | — | 0.678 | 0.680 | 0.782 | 0.635 |
| `logmel_l1_db` | ≤ 1.5 | 0.060 | 0.051 | 0.060 | 0.063 |
| `mcd_db` | ≤ 1.0 | **1.290** | **0.948** | **1.314** | **1.224** |
| `seg_snr_db` | ≥ 20 | **−3.39** | **−2.40** | **−3.55** | **−3.34** |
| `len_ratio` | informational | 1.000 | 1.000 | 1.000 | 1.000 |

All twelve pairs (3 passes × 2 languages × 2 devices) flag `instrumental_reject`. Read the
numbers as a *rank*: the language the listener called damaged (pt-br) is the one with the
larger deviation — but note that even a bit-identical-in-structure change can move
`seg_snr_db` below zero on this model (see §3.5), so treat the bands as triage, and the
listener as the gate.

---

## 4. Results

### 4.1 Leg A — int8 vs fp32 (same session, production pipeline)

The production path (chunker → ORT session → amplitude trim → pause insertion), corpus of 2
passages (~63 s audio), 3 passes; the fp32 control run in the same session immediately after.

| device | int8 RTF (en-us / pt-br) | fp32 RTF | ratio | int8 Wh/audio-h | fp32 Wh/audio-h | oracle max diff |
|---|---|---|---|---|---|---|
| Fold 8 (screen on) | 0.77–1.09 / 1.09–1.13 | 0.49–0.56 | ≈2.0× slower | 3.06–3.51 | 1.75–2.00 | 0.781 |
| S22 Ultra (screen off) | 1.02 / 1.01 | 0.71 / 0.70 | ≈1.44× slower | not measurable (gauge) | not measurable | 0.873 |

Blind-listening outcome (see §3.6): pt-br → fp32 preferred on both devices ("frayed" int8);
en-us → int8 marginally preferred on both ("very little diff").

### 4.2 Leg B — window cap vs latency and throughput (16 passages, 374–400 s audio)

| device | cap | windows | RTF (best pass) | throughput audio-s/s | first window ms | p50 / p95 window ms |
|---|---|---|---|---|---|---|
| Fold 8 | 150 | 65 | 0.524 | 1.91 | 960 | 3525 / 5096 |
| | 300 | 36 | 0.538 | 1.86 | 1620 | 7106 / 10416 |
| | 510 | 22 | 0.604 | 1.66 | 17385 | 10544 / 15830 |
| S22 Ultra | 150 | 65 | 1.193 | 0.84 | 3973 | 7452 / 12012 |
| | 300 | 36 | 1.192 | 0.84 | 4878 | 13233 / 35140 |
| | 510 | 22 | 1.292 | 0.77 | 19467 | 22327 / 32932 |
| HiBreak | 150 | 65 | 5.605 | 0.18 | 8674 | 32093 / 72718 |
| | 300 | 36 | 5.919 | 0.17 | 21774 | 53645 / 121860 |
| | 510 | 22 | 5.409 | 0.19 | **143600** | 81154 / 157070 |

Take-away: cap 150 for weak devices; TTFA at cap 510 costs the HiBreak **2.4 minutes of
silence** before the first sound. The S22 leg ran on a hot device (see §3.3) and its
absolute RTF is a degraded-state number; the cap-to-cap ordering is the transferable part.

### 4.3 Leg C — output path: whole-passage `MODE_STATIC` vs per-window `MODE_STREAM`

24 kHz mono PCM16, track gain 0.05 (−26 dB); `first_audio_ms` = `play()` → first audible
frame.

| device | mode | first audio ms | underruns | underruns/audio-h | max inter-write gap ms |
|---|---|---|---|---|---|
| Fold 8 | static | 17 | 0 | 0 | — |
| | stream | 34210 | 2 | 112.7 | 34209 |
| S22 Ultra | static | 56 | 0 | 0 | — |
| | stream | 35095 | 2 | 112.7 | 36130 |
| HiBreak | static | 17 | 0 | 0 | — |
| | stream | 84723 | 2 | 112.7 | 87713 |

A window takes 7–14 s (Fold/S22) to 55–65 s (HiBreak) to synthesize while a 1 s stream
buffer drains, so the track is empty before the next window is ready. Keep the static path.

### 4.4 Leg D — scheduling (8 passages, best of 3, unplugged, screen on)

Fold 8 — the only device where every config could be measured:

| config | RTF | first window ms | p50 / p95 ms | power mW | Wh/audio-h |
|---|---|---|---|---|---|
| baseline | 0.596 | 15395 | 11036 / 15278 | 3106 | 1.852 |
| `session.intra_op.allow_spinning=0` | 0.593 | 15472 | 11043 / 15048 | 2807 | **1.665** |
| `THREAD_PRIORITY_URGENT_AUDIO` | 0.593 | 15552 | 13636 / 58116 | 3385 | 2.007 |
| **ADPF hint session** (target = the previous p50) | **0.459** | **10742** | 10301 / 175775 | 4562 | 2.093 |
| ADPF + `setPreferPowerEfficiency` | 0.515 | 13258 | 9719 / 13311 | 3597 | 1.852 |

* ADPF is worth **+23 % throughput** and −30 % TTFA, at 12 % more energy per audio-hour:
  a speed lever, not a saving.
* ORT's spinning threads cost nothing measurable here; `URGENT_AUDIO` does nothing
  measurable (with a worse tail).
* Thread discovery matters: with the ORT worker threads named (matched by
  `/proc/self/task/<tid>/comm`), three of them were hinted. On the S22, where ORT's threads
  are unnamed, discovery falls back to a CPU-placement heuristic.
* **Platform availability differs**: on the S22 Ultra (SDK 36) `createHintSession` returns
  **null** rather than throwing — the ADPF service is absent on that ROM, and code that
  assumes non-null will NPE or silently skip. Handle the null.
* Config-to-config RTF differences smaller than the device's thermal drift are noise: the
  same baseline config measured 0.813 / 0.832 / 0.678 across one session (`±20 %`).
  HiBreak (unmeasurable energy, device thrashing): 2.866 / 2.940 / 2.863 — flat.

### 4.5 Leg E — duty cycling (16 passages, 374 s audio, per mode)

Fold 8, screen on, unplugged, idle baseline 742 mW:

| mode | measured duty | infer ms | idle ms | power mW | Wh/audio-h |
|---|---|---|---|---|---|
| continuous | 1.000 | 135683 | 0 | 6662 | **2.415** |
| 30 s work / 30 s rest | 0.551 | 147176 | 120000 | 3800 | 2.713 |
| 20 s work / 40 s rest | 0.367 | 139009 | 240000 | 2684 | 2.718 |

Power falls 2.5× from continuous to 33 % duty, but energy per audio-hour *rises* 12 %: the
wall time grows faster than the draw falls, and the rest phases do not reach the idle floor.
S22 (energy unmeasurable) reproduces the ratios — 1.000 / 0.554 / 0.379 with infer RTF
0.733 / 0.698 / 0.653 — so duty cycling is a thermal tool that incidentally makes a warm
device slightly faster.

### 4.6 Leg F1 — int4 (`MatMulNBits`) on the CPU EP

A single-node `com.microsoft::MatMulNBits` graph (opset 17 + `com.microsoft` 1) opens and
produces finite, non-degenerate output on **host onnxruntime 1.29.0 CPU EP** and on
**onnxruntime-android 1.29** on all three devices. A weight-only int4 pack is therefore not
blocked by a missing kernel — subject to the shapes below.

ORT 1.29 enforces packed shapes, which is worth knowing before exporting:

```
A            float32 [1, 1, K]
B            uint8   [N, K/block_size, block_size*bits/8]     # 4-bit, block 16 → [N, 16, 8]
scales       float32 [N, K/block_size]
zero_points  uint8   [N, block_size*bits/8]                    # packed like B, not [N, blocks]
attributes   K, N, bits=4, block_size=16, accuracy_level
```

Feeding the unpacked shapes (`B [N, N]`, `zero_points [N, blocks]`) fails at run time with
`Input 'quantized_weight' is expected to have shape {N, 16, 8}`, which is how the shapes
above were derived.

### 4.7 Leg F2 — making Kokoro's 1-D convolutions eligible for the XNNPACK EP

XNNPACK claims only 2-D Conv/ConvTranspose and 2-D Gemm/MatMul, and Kokoro's 97
convolutions are 1-D `(N, C, L)`. Rewriting them as 2-D — insert `H=1` via
`Reshape [0,0,1,-1]`, reshape the weight `[O, C/g, k] → [O, C/g, 1, k]`, rewrite
`kernel_shape`/`strides`/`dilations`/`pads`/`output_padding` with a leading 1/0, then
`Reshape [0,0,-1]` back (re-publishing the original tensor name so no consumer changes) —
produces a graph that is **arithmetically exact**:

* Per-conv onset test across all 97 convolutions: **no onset**; max relative output error
  over convolutions whose inputs were still bit-clean: **1.6e-06** (i.e. float32 round-off).
* Synthetic check against a float64 direct convolution: both the 1-D graph and the rewritten
  2-D graph agree with float64 to ≤1e-5 relative.

It nevertheless **failed a waveform parity gate at 1e-4** (measured 5.7e-02–8.4e-02)
because of §3.5: the unmodified graph's own BASIC-vs-ALL optimization delta is
1.2e-02–3.0e-02, and `ORT_DISABLE_ALL` vs `ORT_ENABLE_ALL` — same kernels, only fusion
removed — is 2.4e-06. So the rewrite is exact and the gate is the wrong instrument. If you
pursue XNNPACK, gate the rewrite per-layer and let the EP's own partition report
(`session.disable_cpu_ep_fallback=1`) tell you what it claimed.

---

## 5. Reproduction checklist

1. Pin artifacts by sha256 (§2) and verify the staged files' digests on-device.
2. Push models + corpora to `/data/local/tmp`, copy into the app's `files/` (models) and
   external files dir (results) — read results back via `/storage/emulated/0/...`.
3. Fix the leg's conditions and **record them in the result file**: screen on/off, plug
   state, thread count, model digest, chunker cap, corpus.
4. Energy legs: unplugged, wake lock held, `unplugged_fraction` reported, gauge floor applied.
5. Time-to-first-audio = first *window's* synthesis time; keep the window cap explicit.
6. Compare caps by wall time for the same text, and repeat the first config at the end to
   quantify drift.
7. For precision changes: per-layer relative onset test + blind listening, never a bare
   waveform threshold.
8. Expect interruptions: flush after every step, and pull results between legs.

## 6. Tools (tracked, reusable as-is)

| tool | what | usage |
|---|---|---|
| `tools/kokoro_perceptual.py` | the §3.7 metrics, the advisory bands, and a `--dir` mode that pairs `*_fp32_<lang>.wav` with `*_int8_<lang>.wav`; `--selftest` needs no model | `python3 tools/kokoro_perceptual.py --ref ref.wav --deg cand.wav --out m.json` |
| `tools/gen_blind_kokoro_set.py` | seeded, level-matched A/B set + `key.json` (§3.6); `--selftest` | `python3 tools/gen_blind_kokoro_set.py --dir wavs --out blind --seed 20260911` |
| `tools/gen_matmulnbits_probe.py` | builds the single-node int4 graph and reports the CPU-EP verdict (§4.6) | `python3 tools/gen_matmulnbits_probe.py --out <model dir>` |
| `tools/reshape_conv_1d_to_2d.py` | the 1-D→2-D conv rewrite, its float64 self-test and the per-conv/parity/control gates (§3.5, §4.7) | `python3 tools/reshape_conv_1d_to_2d.py --in a.onnx --out b.onnx --parity --conv-parity --control` |
| `tools/adapt_kokoro_int8_output.py` | renames the int8 graph's `audio` output to `waveform`, verifying bit-identical outputs (§2) | `python3 tools/adapt_kokoro_int8_output.py --in int8.onnx --out int8-waveform.onnx` |

The device-side harness (this repo's `spike-tts` module: `PerfSpikeRunner` +
`PerfSpikeBenchmarkTest`, `-e leg a|b|c|d|e|f1|f2`) is Android-app-specific; §3.1 states the
shape to reimplement in whatever runtime the target project uses.

## 7. Provenance

Measured 2026-09-11 in this repo's `spike-tts` harness (decisions #148/#150); the raw
per-device JSON and WAV pairs live in `docs/prints/perfspike/`, which is gitignored — this
document deliberately inlines every number it relies on. The reusable host tools are
tracked and named in §2/§3: perceptual metrics + bands, the seeded blind-set generator, the
int4 probe, and the 1-D→2-D conv rewriter with its parity gate.
