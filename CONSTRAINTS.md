# Constraints — Xreader

Last reviewed: 2026-11-11, from the owner's settled rules (MEMORY.md).

## Floor (always enforced)
- No update reaches the owner until an independent re-scan passes AND his phone proof passes.
  Anything unproven is labelled unproven per item in the delivery message itself.
- No half-wired surface ships visible. A feature that is not finished is not visible.
- Release builds must NOT run R8 minification/obfuscation or resource shrinking
  (v1.4.0 crashed because R8 stripped a generic signature Injekt needs).
- APK under 100 MB (one hard size rule; every build reports size vs cap).
- Xreader stays MIT. No GPL/AGPL code enters the app. Librera is behaviour-only on a
  permissive engine; Komikku/Mihon wholesale copying stands (Apache-2.0).
- No test skipped, deleted, or made easier to get to green. A pass on re-run with no
  code change is flakiness, not a fix — it is not called settled.
- No source ships until catalogue, book, chapters, text and search are all proven live
  on his phone. A login/check gate is a solvable state, never a dead end.
- This file does not get weakened to make a change pass. Tightening is silent;
  loosening is loud and needs his word.

## Enforced with numbers
| Dimension | Rule | Checked by |
|-----------|------|-----------|
| Gate | Full test suite: 0 failures (last full gate 2,549 tests) | gradle test + re-gate on any failure |
| Size | APK < 100 MB, unminified | build report, size vs cap |
| Proof | Phone proof on Galaxy M31 (old Android 9 phone where it applies) | owner video proof, frame-by-frame |
| Delivery | One build per delivery, one gate, one APK | build log |

## Measured, not yet enforced
| Metric | Direction |
|--------|-----------|
| PDF open time | toward Moon+ ~2–3 s |
| Manga page-turn black flash | toward 0 (currently ~2 s, open defect) |
| 2026-11-06 resume crash | unnamed, unconvicted — must be named before it is called fixed |

## Exceptions
| ID | Rule | Reason | Owner | Expires |
|----|------|--------|-------|---------|
| (none) | | | | |
