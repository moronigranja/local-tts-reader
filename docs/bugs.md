# Bug log

Bugs found during device passes, especially the **weak-device pass** (a
second, lower-spec Android device the S22 Ultra is compared against). Log,
don't fix: the weak-device pass is a *measurement* pass — performance and
resource findings are recorded here for a later tuning slice, not patched
mid-pass. Functional bugs found anywhere get recorded here too and fixed in
their own slice.

## How to log

Each entry: date · device · app build/commit · what happened (repro steps) ·
observed vs expected · severity (blocker/major/minor/cosmetic) · whether it
is a **perf** finding (not to fix now) or a functional bug.

## Entries

(latest first)