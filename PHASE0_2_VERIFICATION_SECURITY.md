# Phase 0.2 - Observe-only verification and credential hygiene

## Scope

This phase intentionally keeps the existing Check Point, VSX and Panorama collection methods unchanged.

Added:

- read-only `utils/verification.py`
- `python main.py --only verify`
- automatic verification after merge during `--only all`
- `output/verification.json`
- runtime log redaction for username, password and Panorama API key
- credential references are dropped after the run

## Credential handling

Passwords cannot be replaced by a hash for SSH/Panorama authentication: the remote systems require the original credential. Phase 0.2 therefore keeps the password only in process memory for the duration of collection and never intentionally persists it.

If an exact username reaches a log message, it is replaced with a SHA-256-based short fingerprint. If an exact password or Panorama API key reaches a log message, it is replaced with a redaction marker.

Python strings cannot be guaranteed to be securely zeroized from process memory. `Config.clear_credentials()` removes active references after the run as a best-effort lifetime reduction; future production deployment should source credentials from the enterprise secret-management mechanism and inject them only into collector pods.

## Verification behavior

Phase 0.2 is observation-only. Warnings do **not** stop merge, HTML generation or publication. This is deliberate while thresholds and vendor semantics are learned from real inventory data.

Current observations include:

- missing/invalid expected JSON files
- source object/interface/route counts
- completely empty inventory objects
- unresolved route-to-interface references (warning only)
- invalid route networks
- duplicate source identities
- unified object-count consistency

## Test commands

```powershell
py.exe -m pytest -q
py.exe .\main.py --only verify
py.exe .\main.py
```

After a full run, share:

- the new `logs/run_*.log`
- `output/verification.json`
- optionally the normal output JSON files if their counts differ from the previous baseline
