# Clean Baseline Bootstrap & Dependency UX

status: automated_validated · target: workflow hardening

AUTOMATED_VALIDATED 2026-08-28. main.py gains _ARTIFACT_PRODUCERS / _MODE_PREREQUISITES and _require_bootstrap(mode, output_root): --render-only, --cp-config-probe, --cp-config-collect and --only cp/vsx/pan-config now fail fast with exit 2 and an actionable 'Missing: X produced by <cmd>' block plus a bootstrap sequence, before any credential prompt or collector, instead of a deep traceback. --only all and legacy diagnostic modes are unaffected. Also replaced RFC1918 route literals in scripts/render_sample.py with RFC 5737 doc ranges (they were tripping the repository privacy gate on main). Evidence: pytest 449 passed / 3 skipped / 0 failed; privacy gate PASS/0; tests/test_dev_clean_baseline_bootstrap.py (9). No collector/network/CAS/UI change.
