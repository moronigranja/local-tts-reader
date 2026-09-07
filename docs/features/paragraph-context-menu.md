# G2 — Paragraph context menu (long-press: Play from here / Copy text)

Status: **landed** (executed 2026-09-06, decisions #127). Host gate green
(Docker tests + ktlint baseline regenerated — it was stale since the immersive
batch — + assemble). **Device pass S22 2026-09-07: PASSED** — see results
below; the pass found and fixed the menu-anchor bug before shipping.

Written 2026-09-06 before any code, per the features/ plan convention. Owned by
the reader surface (feature-player). Promoted from `ideas.md` #65; roadmap G2.

## Device pass results (S22, 2026-09-07)

- **Long-press a paragraph** → `DropdownMenu` opens with both items, anchored at
  the press point. **Bug found + fixed during the pass:** the first build
  anchored the menu with `Modifier.offset { … }`, which rendered an empty box
  at the screen origin — DropdownMenu's dedicated `offset = DpOffset(…)`
  parameter is the correct anchor (the modifier offset is content-space, not
  window-space). Rebuilt + reinstalled; menu renders at the press point.
- **Play from here plays the SELECTED passage**: on a single page window of
  passages, a top-of-page long-press played the page-top passage (footer
  212) and a bottom-of-page long-press played the page-bottom passage
  (footer ~219, read 222 after short advance) — a 7-passage delta across one
  page, matching the paragraph layout. Not the currently-narrated passage.
- **Copy text**: clipboard verified end-to-end — pasted into the library
  search field on the device, and the text matches the app DB passage exactly
  (`chapter 9, passage 14` = "“If it comes to that,” Kal responded…").
- **Tap-away** dismisses the menu and does NOT turn the page (page gestures
  inert while the menu is open).
- **Discrimination**: quick side-zone tap still turns the page (no menu);
  middle double-tap still toggles immersive both ways (bars hide/restore,
  title overlay + minimal player render); a drag that starts like a hold
  (long-press-then-drag) turns the page and never fires the menu.
- **Immersive**: the long-press menu opens and maps passages inside immersive
  (watch item: mid-page presses consistent with regular mode; boundary-
  adjacent presses not exhaustively probed — the `topInset` mapping remains a
  low-priority watch note).
- **Rotation**: landscape ↔ portrait safe.
- **Not covered**: TalkBack pass (B4 gate, deferred — the menu is standard
  `DropdownMenuItem`s and long-press is the standard a11y affordance).

## Goal

Long-press a rendered paragraph on the reader page to expose a context menu with
**Play from here** and **Copy text**. This returns play-at-passage (superseded
from the middle tap by decisions #122) as the long-press gesture, and adds copy
(cheap: the passage text is already in memory).

Acceptance (roadmap G2): the **selected** passage — not merely the currently
narrated one — is copied or played, including when several passages share one page.

## Scope of this slice

`feature-player/.../ui/ReaderScreen.kt` only. The seams already exist:

- tapped-line→passage mapping (`passageStartLines`, `passageAt(y)`);
- per-passage text (`PlaybackUiState.chapterPassages[passageIndex]`);
- play-at-position command (`ReaderViewModel.playPosition`, the same
  `ACTION_PLAY_POSITION` the bookmark menu and play-from-view use);
- the anchored-menu surface (`DropdownMenu`/`DropdownMenuItem` already imported
  for the chapter selector).

No domain change, no ViewModel change, no schema.

## Gesture discrimination (decisions #96, against B3, not beside it)

`PaginatedChapter`'s single `awaitEachGesture` loop currently discriminates
**swipe** (drag beyond `SWIPE_PAGE_THRESHOLD`) vs **tap** (release before
threshold), with the middle-zone tap feeding the immersive double-tap detector
(items 1-4, 21850a3). This slice adds the third leg:

| Gesture | Discriminator | Result |
|---|---|---|
| Quick tap (any zone) | up before long-press timeout, no swipe | side zones: page turn; middle: double-tap feed (unchanged) |
| Long-press (any zone) | **down held past `longPressTimeoutMillis`** without swiping | context menu at the press point |
| Swipe | drag beyond threshold before up/timeout | page turn (unchanged) |

- The long-press deadline is computed once per gesture from
  `down.uptimeMillis`; the loop races `awaitPointerEvent()` against
  `withTimeoutOrNull(remaining)` per event, so a resting finger fires the menu
  at the platform timeout, while slop-level jitter does not reset the deadline
  (faithful to `detectTapGestures`'s deadline model).
- A middle-zone long-press breaks out **before** the tap logic, so it never
  records a tap time and cannot corrupt the double-tap window.
- The B3 pressed-passage highlight is untouched: still set at DOWN for middle
  zone only, cleared on up. A side-zone long-press gets its feedback from the
  menu itself.
- While the menu is open the page's `pointerInput` block is **inert** (keyed on
  the menu state; early-returns): a tap-away dismisses the menu instead of
  turning the page — the classic DropdownMenu-inside-a-tap-surface trap.

## Change

1. **Gesture loop** (`PaginatedChapter`): add the long-press race described
   above; on timeout compute the passage under the finger and set
   `longPressTarget = (passageIndex, down.position)`, clear `pressedPassage`,
   then drain pointer events until all pointers are up (the loop already does
   this for taps; the menu must not appear under a still-down finger's later
   up-event churn). `passageAt` gains a `requirePositioned` flag — the B3
   highlight keeps the `state.positioned` guard; the menu maps any real passage
   (a freshly opened, never-played book can still "Play from here").
2. **Menu**: a `DropdownMenu` child of the page Column (popup escapes padding
   and clipping), `Modifier.offset { IntOffset(press) }` so it anchors at the
   press point in the same content-local coordinate space the pointer events
   use:
   - **Play from here** → `viewModel.playPosition(bookId, state.chapterIndex, passageIndex)`
     (same command as bookmark jumps / play-from-view).
   - **Copy text** → `LocalClipboard.current.setClipEntry(ClipEntry(passageText))`
     (BOM 2026.06.01 surface — the deprecated `LocalClipboardManager` is not
     used) + a short Toast.
3. **Doc comments**: the class and `PaginatedChapter` gesture docs are updated
   to state the three-way discrimination (they currently describe swipe /
   side-taps / middle double tap only).
4. **pointerInput keys**: add `longPressTarget == null` so opening the menu
   restarts the block inert (see discrimination table).

## Watch item (pre-existing, not fixed here)

The immersive top-cut fix (08a1d81) added `topInset` to `passageAt`'s
`titleBlock` because the body content is padded down under the floating title
band. Rendering was pixel-verified on the S22; **tap-mapping accuracy in
immersive was not** — the press highlight and (now) the long-press menu share
the mapping, so any boundary-adjacent misfire is B3's own gauge and will show
up in the same device pass. If the long-press menu opens on the wrong passage
near a boundary in immersive, the fix is the one-line `topInset` correction;
it is deliberately not guessed at here.

## Verification

- Host: Docker build (`tools/docker-build.sh` assembleDebug + feature-player
  unit tests) + `ktlintCheck` (baseline-gated, must stay green).
- Device (S22), both regular and immersive:
  - Long-press a paragraph → menu appears at/near the press point with both items.
  - Play from here starts narration at the **pressed** passage — including when
    several passages share one page, and on a book that was opened but never played.
  - Copy text puts exactly that passage on the clipboard (paste into another app).
  - Tap-away dismisses without turning the page.
  - Quick taps still turn pages (side) and double-tap still toggles immersive (middle).
  - Swipes still turn pages; a swipe that starts as a long-press-then-drag never fires the menu.
  - Rotation safe; TalkBack pass (B4 gate): long-press is the standard
    accessibility affordance and the menu items are standard `DropdownMenuItem`s.
- Watch item above: boundary-adjacent long-press in immersive selects the
  highlighted passage.

## Docs to update on landing

- `docs/decisions.md` — new entry (#127).
- `docs/roadmap.md` — G2 status.
- `docs/ideas.md` — #65 row status.
- `README.md` — capability 3 (reader gestures) + feature-player module row.
- This plan's status header.