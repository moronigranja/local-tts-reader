# CosyVoice3 fallback pack — pinned reproducibility record

Metadata-only reproducibility manifest for the **fallback** `cosyVoice3` tier
(`core-tts` `DefaultEngines.kt`: `EngineDescriptor(cosyVoice3, emptyList())`) —
gated on the DiT acceleration finding (decisions #21/#23). Decision #49 ran
the T3 spike ad-hoc without recording a URL/hash; this file pins the exact
snapshot so the on-device pass is reproducible from the repo. The app **ships
Kokoro** as v1 primary; no pack download is wired for CosyVoice3 — this is
the provenance record for a future slice, not a shipped descriptor.
`TtsPack`/`DefaultEngines` descriptor wiring stays `emptyList()`.

Hashes are real, never fabricated (decision #23): every value below was
computed at implementation time from the pinned revision.

## Source revision

- HF repo: `jiangzhuo9357/cosyvoice3-0.5b-onnx`
- Pinned revision (commit): `d1b9350096e2099f0fbbd1001714bf27247d1582`
  (resolved 2026-08-29 from the repo API `sha` field)
- Snapshot `createdAt`: `2026-07-17T05:17:39.000Z`
- Total: 26 files, 3,721,012,656 bytes ≈ 3.47 GiB (fits the #49 "3.7 GB
  staged" figure)

## File manifest (26 files at the pinned revision)

Each row: relative path · size (bytes) · sha256.

Hash provenance:
- ONNX graphs + prompt wavs (17): `lfs.sha256` from the HF files API
  (`…?revision=<sha>&blobs=true`), which is the content hash of the LFS
  object — download-verified for `voices/sarah.wav` (fetched bytes hash to
  the `lfs.sha256` below). The API also returns `size`; these match the
  served bytes.
- Small support files (9): the API exposes only a `blobId` (an internal
  pointer hash — NOT the content hash, verified), so `sha256` was computed
  from the bytes served by
  `resolve/<rev>/<path>` (`curl -sL … | sha256sum`), see "Verify" below.

| Path | Size | sha256 |
| --- | ---: | --- |
| onnx/campplus.onnx | 28,303,423 | `a6ac6a63997761ae2997373e2ee1c47040854b4b759ea41ec48e4e42df0f4d73` |
| onnx/flow_estimator.onnx | 1,326,776,646 | `f1ae972dda5c8dcdae01c9d9a12cec859caddadc5a078902994520656c50f1bf` |
| onnx/flow_pre_lookahead.onnx | 2,309,664 | `9533d542c9e72732beaeec952080f3e1a3187a11f2dadf0e3d3d70880b95f7a2` |
| onnx/flow_speaker_projection.onnx | 62,590 | `e1a2cc0ba9bb2dfc03a66cce6ff85a2e62cff5cf422c384844a582472f75a960` |
| onnx/flow_token_embedding.onnx | 2,100,317 | `0b3d7aec5548e934292a8a83c1bcdbe080e4224eb79fff84c5657655f6d22bcc` |
| onnx/hift_decoder.onnx | 70,163,545 | `91b83f4e68683cde55d35fc4f2fb5829505895f93b767c2622d0d05e48a72683` |
| onnx/hift_f0_predictor.onnx | 13,264,386 | `0f2f80a71cd61c06ae6d67413869401e0c70c68770c76186f0ebf2fe478b59bb` |
| onnx/hift_source_generator.onnx | 259,217,882 | `54fc9e0cfff0a1ec45f1a3a4d8c89fede835d82091f47718b2b1458485da5f6e` |
| onnx/llm_backbone_decode_int4.onnx | 225,410,266 | `919c1a038b9a406cc6a931bba9f01b9b7e9ae9527a25a75bef18643696ee9c31` |
| onnx/llm_backbone_initial_int4.onnx | 224,931,365 | `dac39fc0f75aa2da76377165aa98343a2cb156b99670888ed4233de34451085b` |
| onnx/llm_decoder.onnx | 24,232,223 | `35bb8b8ccea08dfa4d359432e256f60b60e88f88882ee6cae14ff3cf968728dd` |
| onnx/llm_speech_embedding.onnx | 24,231,892 | `d54dc13a167dccf2f8865c8226ebbf6155508ecd4c62800148a36382f0cb0cfc` |
| onnx/speech_tokenizer_v3.onnx | 969,451,503 | `23236a74175dbdda47afc66dbadd5bcb41303c467a57c261cb8539ad9db9208d` |
| onnx/text_embedding.onnx | 544,538,870 | `a3f14afc64468e5574b02aff8a73687a69c6677d5ccc46127cccf93818664965` |
| voices/classic-ja.wav | 295,724 | `cdf9ce6f4dda60fb960ebb620baa7aad80cd8e3e1bf866bf061901689bb37777` |
| voices/classic-zh.wav | 334,138 | `c7b31d6dbe7cc6a716dded00550db5b50940bf209e424e4ad207b12e657c8ff6` |
| voices/sarah.wav | 312,044 | `c590d41595abacb6f6a4ac3f8004c34d5c1452c4b44dc9cc607b031fcab677b8` |
| .gitattributes | 1,688 | `9d7de433d16e5719ff2f43be3ae77729f51d1fb152d6d348e91c33ba84b2dff2` |
| README.md | 668 | `6a7220951300ad52680bdd0911a0069f50ef797625e31c34a24cbeb03157c108` |
| merges.txt | 1,537,044 | `5e1f64c105c72ee838aacc639f5074116a97dc29399bde3ae3fd4a12314c63e9` |
| tokenizer_config.json | 1,287 | `482bd979881423375ca5414e4e0d94cd7c5349dbb17fffd46b4d36d71e62a1bc` |
| vocab.json | 3,535,130 | `dc4fc0fa09f311a7f01d64988d8994e99ee8807f0f382ab0bfd3fc54be02f1e2` |
| voices/classic-ja.txt | 84 | `7a32c19494cbf3a7f77f740c7b863f29be429844c358447736d9f3c4e9412d2d` |
| voices/classic-zh.txt | 45 | `4ff1a7dd8cb643e4f769735733e7547ff66aa5b29d99f674131f3fb448446efa` |
| voices/manifest.json | 120 | `40d97fdc2bf1046dbd1469a0dd31c4246ad20d66b885f735c1ef458e68532d89` |
| voices/sarah.txt | 45 | `4964b1df7faced5ccc2261df438cea032ace328be91a9a468d2d08a5d3460137` |

Small-file sizes come from the files API and match the fetched bytes (verified
for `README.md`, 668 B).

## Derived prompt wavs (`voices/sarah16.wav`, `voices/sarah24.wav`)

The T3 harness consumes pre-resampled prompts (build.md: "the prompt voice
ships pre-resampled (`voices/sarah16.wav` / `sarah24.wav`)"). Derivation
commands (source `sarah.wav` = the pinned-revision file above):

```bash
ffmpeg -i sarah.wav -ac 1 -ar 16000 sarah16.wav
ffmpeg -i sarah.wav -ac 1 -ar 24000 sarah24.wav
```

| Output | Size | sha256 |
| --- | ---: | --- |
| sarah16.wav | 208,078 | `654497c27231fcb1bcdaa250e0fc01dbe0b2429a8cfc4599dfb64b753ed46fa2` |
| sarah24.wav | 312,078 | `9f83deef80b9a81f90f65023cce1a138b113596837d3ee3a60c7b14dc3e55360` |

## Verify (reproduces the #49 device pass)

1. Spot-check one manifest row (content hash, no 3.7 GB download):
   ```bash
   curl -sL "https://huggingface.co/jiangzhuo9357/cosyvoice3-0.5b-onnx/resolve/d1b9350096e2099f0fbbd1001714bf27247d1582/voices/sarah.wav" | sha256sum
   # expect c590d41595abacb6f6a4ac3f8004c34d5c1452c4b44dc9cc607b031fcab677b8
   ```
2. Re-derive the prompts and confirm the recorded hashes:
   ```bash
   ffmpeg -i sarah.wav -ac 1 -ar 16000 sarah16.wav && sha256sum sarah16.wav   # expect 654497c2…fa2
   ffmpeg -i sarah.wav -ac 1 -ar 24000 sarah24.wav && sha256sum sarah24.wav   # expect 9f83deef…5360
   ```
3. Stage onto the device exactly as `docs/build.md` (T3 CosyVoice3 spike):
   ```bash
   adb push /tmp/t3/models /data/local/tmp/models          # once
   adb shell "run-as com.moronigranja.localttsreader.spiketts sh -c \
     'mkdir -p files/models && cp -r /data/local/tmp/models/. files/models/'"
   adb shell am start -n com.moronigranja.localttsreader.spiketts/.MainActivity
   adb logcat -s T3Spike                                   # RTF / stage timings
   ```
   (14 ONNX graphs staged as `files/models/onnx/**`; the two prompt wavs as
   `voices/sarah16.wav` / `sarah24.wav` — the layout the #49 run used.)
