# DEV.2.1 — Non-Interactive Runtime Configuration

## Summary

AUTOMATED_VALIDATED 2026-08-28. _build_runtime_config now sources principal, secret and the CP-MDS / Panorama endpoints from <VAR>_FILE (secret-mount, fail-closed) > <VAR> (env) > interactive prompt, prompting only when sys.stdin.isatty(); non-TTY with a required value unresolved raises RuntimeConfigError naming every missing var before the lazy collector imports, mapped to parser.error (clean SystemExit 2) at the three call sites. New utils/runtime_config_source.py; .env.example (documentation only). P0 precondition for all container/server work; no server needed. Evidence: py -m pytest -q = 433 passed, 3 skipped, 0 failed (Python 3.12); 12 new tests + 5 fixed. Downstream Config/RuntimeAuth/collectors, read-only and value-free behaviour unchanged; interactive local runs byte-for-byte identical.

## Evidence

- **automated**: py -m pytest -q: 433 passed, 3 skipped, 0 failed (Python 3.12)
- **real_env**: not required (no new network behavior or device command)
