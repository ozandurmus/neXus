# dev_python_env_tooling_friction — POSIX runtime-root default + pytest_one_shot.ps1 interpreter pin

## Summary

Two frictions closed, both hit first-hand in this session's Linux cloud environment. (1) utils/runtime_paths.py required an explicit --runtime-root/SECURITYEXPERT_RUNTIME_ROOT on every non-Windows platform with no default at all (Windows already had a LOCALAPPDATA default); added a mirrored POSIX default ($XDG_DATA_HOME/SecurityExpert/runtime, else $HOME/.local/share/SecurityExpert/runtime), explicitly scoped in docs/ARCHITECTURE.md as a local dev/AI-session convenience only -- DEV.3 container/server deployments must still set the env var explicitly to a mounted volume. (2) scripts/pytest_one_shot.ps1 called bare `py`, already documented in AI_HANDOVER.md as resolving to Python 3.14 without dev deps on at least one validated box; the script now probes `py -0p` and prefers `-V:3.12` (the already real-environment-validated combination with -m pytest) when registered, falling back to bare `py` otherwise.

## Evidence

- **automated**: 4 new tests (net +3, one superseded) in tests/test_dev0_3a_runtime_paths.py covering XDG_DATA_HOME precedence, HOME fallback, env-var-still-wins-over-default, and the fail-closed case when neither is available. py -m pytest -q: 620 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in every prior 0.6.x closure this session). Zero regressions. Live-verified: resolve_runtime_paths() resolves cleanly with zero env vars set on this Linux box.
- **privacy_gate**: No credential/device-identity/IP literal introduced.
- **real_env**: the POSIX default was live-verified in this session's Linux container. The pytest_one_shot.ps1 change could NOT be executed (no pwsh in this cloud session) -- owed on the next Windows session under on_hardware_real_env_validation.
