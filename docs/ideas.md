# Ideas & candidate features

A candidate pool, not a commitment: each row records what the feature is, where it
came from, why it fits this app, and which roadmap slice would host it. A feature
graduates to the roadmap (and the decision log) only when it is decided on.

Logged 2026-08-25. Sources:
- **Audiobookify** — iOS EPUB reader with local TTS on the neural engine
  (`r/apple`, 2026-07, u/therysin): [post](https://www.reddit.com/r/apple/comments/1uo1b2d/epub_reader_with_local_tts_running_on_neural/)
  and its 28 comments (post body + thread captured in the PDF print; links there:
  TestFlight, streamable demo — the demo video is since deleted).
- **heard.quest** — a competitor mentioned in the thread (classic-books bundle).
- **Audiobookify in-app demo** — screen recording of the TestFlight build (2026-08,
  the owner's prints): home dashboard, onboarding, speed + sleep-timer menus, RSVP
  mode. Candidates below also cite these prints.
  Raw screenshots archived at `docs/prints/` (01–05). Screenshots of a third-party app;
  keep attribution ([Audiobookify](https://www.reddit.com/r/apple/comments/1uo1b2d/), @therysin) if reused.

## Candidates

| Idea | Source | Why it fits | Home | Notes |
|---|---|---|---|---|
| Accelerator delegates for TTS (GPU/NPU) + power-efficiency run | Audiobookify: TTS "on the GPU and CPU (neural engine)", pitched on battery/thermals | Extends decision #21 directly: the CPU-only gate failed for CosyVoice3, but small Kokoro-class models may gain from ORT NNAPI/GPU delegates. Nothing is decided until measured (RTF + power on the reference device) | T2/T3 follow-up (`core-tts` research; re-run of the `spike-tts` harness pattern) | Flow-DiT-class models showed no credible mobile acceleration path (decision #21) — do not assume; measure. Audiobookify does run Kokoro on the iPhone neural engine (in-app demo prints) — same direction, Android still unmeasured |
| Reading–listening progress sync (read-along) | Audiobookify: "progress tracks well between reading manually and listening" | The `progress` table (P1) already stores one resume passage per book; reader and player both write it | T4 + feature-reader | One source of truth: the `progress` row per book; keep chapter+passage semantics identical in both surfaces |
| TXT + Markdown import | Audiobookify formats: EPUB, MD, TXT only; PDF explicitly deferred | Cheap: plain text/Markdown flows straight through `BookSegmentation`; needs a small text `EBookParser` (MD headings → chapters) + SAF filter entry | C-lane quick win | PDF stays deferred — both apps agree |
| Android Auto media controls | Audiobookify comment: CarPlay shows working media controls via MediaSession | Conventions already mandate MediaSession (T4); Auto uses the same session, no extra architecture | T4 | Verify on device once the player exists |
| "Listen from here" (resume/play from any passage) | Audiobookify UX feedback: only chapter-start was reachable | S3 resume wiring already targets the passage grain; expose it as a reader gesture | S3 / feature-reader | Gesture choice: long-press a passage → "Listen from here" |
| Playback position preserved across speed changes | Audiobookify bug report: changing speed reset the play point | Player correctness requirement (passage + intra-passage offset must survive settings changes) | T4 | Explicit regression test |
| Playback robustness on output/hardware switch | Audiobookify bug report: playback stopped when CarPlay took over and on headphone resume | Audio focus/ducking + route-change handling; part of the conventions' audio contract | T4 | In the focus/ducking acceptance criteria |
| RSVP speed-reading (one word at a time) | Audiobookify commenter idea (OP: "added to my list") | Distinct from TTS; a reading-mode nicety | feature-reader (future) | Low priority; no dependency on TTS — shipped in the Audiobookify build (in-app demo prints): word-at-a-time, w/s speed, % position |
| Light/dark theme following system | Audiobookify commenter request | Compose Material 3 day/night; app is pre-1.0 so theming is not yet set | V-lane / UI polish | Trivial once the theme exists |
| Public-domain classics bundle (heard.quest ships 50+) | heard.quest | Fits the offline-free ethos only as downloadable packs (decision #7: never bundled); it is a content store, not a reader feature | Out of scope for v1 | Note-only; revisit if a "sample library" is ever wanted |
| Docked player with sentence-sync read-along | Audiobookify in-app demo (prints 2–5): reading page with the playback panel docked to it; narration highlights the current sentence as it speaks ("follow each sentence as the story moves"); "CC" surfaces the sentence text | The app's core loop — "import, open the page, tap Listen"; reader and player become one UI slice, not two; progress stays single-sourced (the `progress` row per book) | T4 + feature-reader | Sentence view and RSVP share one text renderer; the same print's onboarding copy states the product thesis in one sentence |
| TODAY stats dashboard (read vs. listened, streak) | Audiobookify home (print 1): "29m" today, weekly Read 7m / Listened 21m chart, 14-day streak, Analytics > | Fits the dual-surface design — read/listen split is native and measures both consumption modes | V-lane / UI polish; new slice (local stats table), not in roadmap | All on-device, so offline-first holds; needs per-day read/listened minutes (not in the P1 schema), a home restructure, and day-granularity streak logic |
| Saved speed presets | Audiobookify speed popover (print 3): "Hold a shortcut to save the current speed" | Speed changes already must preserve the play point (logged bug → T4 regression); presets are the same surface | T4 | No schema; global presets first, per-book override later |
| Sleep timer incl. end-of-chapter | Audiobookify sleep-timer menu (print 4): 5m–1h, End of chapter, Off | Chapter boundaries already exist in the domain model — end-of-chapter stop is nearly free; the player needs a stop condition anyway | T4 | Session-local timer, no schema; end-of-chapter = stop at the next boundary |
| Sync from Kindle (official export / Highlights-Reports API) | hard-facts "Legitimate sync sources" — Amazon returning the user's own data: the "Your Content and Documents"/"Download your data" export (reading position, last-read, highlights) and the read-only Highlights/Reports API (access-token, cursor pagination) | Manual resume (S3/T4) covers v1 without Amazon access; this automates position import from the user's own data, on-demand | Post-v1 slice (V-lane / new `core-sync`), not in roadmap | On-demand/scheduled only, never real-time; both paths are official read-only. The undocumented, rate-limited "Manage Your Contents and Documents" endpoint (DRM-free lending-eligible titles only) stays a possible future bridge, not a foundation (hard-facts) |
## Validated, no action

- **DRM-free stance + AZW3**: Audiobookify refuses DRM and defers AZW3; we already
  parse DRM-free `.azw3`/`.kf8` and refuse encrypted files (hard-facts #15). Our
  choice is confirmed by a sibling product.
- **Tiny app, models fetched in-app**: matches decision #7 (downloadable packs,
  nothing bundled). Confirmed direction.
- **Free, no account**: matches our offline-first/no-cloud ethos (hard-facts).

## Risk note (not a feature)

- **Background TTS can be taken away by OS policy**: iOS 27 beta removed background
  neural-engine access for third-party apps. Android analog: OEM background
  restrictions / Doze could kill long-running foreground playback — the player must
  use a proper foreground service and handle being re-killed gracefully (T4).
