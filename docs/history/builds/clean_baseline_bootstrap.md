# clean_baseline_bootstrap — Clean Baseline Bootstrap & Dependency UX

## Summary

main.py now models which prior artifacts each partial/dev mode reuses (_ARTIFACT_PRODUCERS / _MODE_PREREQUISITES) and _require_bootstrap() fails --render-only / --cp-config-probe / --cp-config-collect / --only cp|vsx|pan-config fast with exit 2 and an actionable 'Missing X, produced by <cmd>' block before any credential prompt or collector, instead of a deep traceback. --only all and legacy diagnostic modes unaffected; also fixed RFC1918 literals in scripts/render_sample.py that were failing the repository privacy gate on main. Evidence: pytest 449 passed / 3 skipped / 0 failed; privacy gate PASS/0. No collector/network/CAS/UI change.

## Evidence

- **automated**: py -m pytest -q: 449 passed, 3 skipped, 0 failed (Python 3.12); repository privacy gate PASS/0
- **real_env**: not required (no network-facing behavior)
