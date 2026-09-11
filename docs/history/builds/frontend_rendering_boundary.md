# frontend_rendering_boundary — Frontend rendering boundary (XSS/CSP) -- implementation

## Summary

Implemented the frozen contract (docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md, AC-1..AC-7 all green) in the same session it was frozen in. The AC-2 exhaustive sink audit reviewed all 97 static/app.js innerHTML sinks individually (not sampled) and found zero missing escapeHtml() coverage -- a real negative result confirming the prior sampling's finding held at full scale. Added the D-CSP1 <meta> CSP tag to templates/index.html, then corrected it in the same pass: frame-ancestors is spec-defined as unsupported via <meta> delivery and Chromium flags it as a console error, caught by this session's real-Chromium manual-browser-check step, so it was removed (the other nine directives are unaffected and meta-compatible).

## Evidence

New tests/test_frontend_rendering_boundary.py (5 tests: AC-1 CSP exact-match, AC-3 static hostile-label check, AC-4 real-Chromium hostile-label DOM check via Playwright, AC-5 x2 for _script_json breakout neutralization). Two hostile-label standalone devices added to tests/fixtures/uitest/unified.json via build_fixture.py. Full suite 888 passed / 23 skipped / 0 failed (-n auto --dist worksteal; at/above the 881/23/2 baseline, the 2 pre-existing test-order-pollution failures did not reproduce this run). Repository privacy gate PASS/0 on a clean checkout. Render harness green including the real-Chromium path, after the frame-ancestors fix. Manual browser check performed via real-Chromium Playwright automation (not literally human-interactive -- this sandbox has no display) across all seven modules, zero console errors, screenshots captured.

## Risks forward

'unsafe-inline' remains required for script-src/style-src (single-portable-file architecture, D-CSP2) -- the CSP is defense-in-depth (blocks exfiltration/framing/embedding), not a backstop for a missed escaping gap; AC-2's exhaustive review is the actual control and it passed clean. No generic tooling enforces the D-ESC1 escaping rule going forward (a written convention, not a linted one) -- worth a follow-up (e.g. a repo-hygiene regression test scanning for new unescaped .innerHTML sinks) but not required by this contract. A literal human-interactive browser open of the CSP'd report on a real workstation remains a cheap, worthwhile follow-up whenever next convenient.
