# DEV.0.4 — Local Repository Privacy Gate

## Objective

Provide a local/offline, fail-closed privacy gate before the first Corporate Git baseline.
The gate scans the repository candidate only, never runtime evidence, and never prints matched values.

## Command

```powershell
py.exe -B .\main.py --repository-privacy-check
```

Exit codes:

- `0`: PASS
- `1`: privacy finding(s)
- `2`: scanner/configuration error

## Safety contract

- no network access
- no credential prompt
- no RuntimeRoot creation
- no matched value echo
- findings contain only file, line and rule category
- synthetic private-address/secret fixtures under `tests/` remain supported
- `.gitignore` is not treated as proof that a forbidden artifact is absent

## Current known blocker

The gate intentionally identifies any repository-default Check Point excluded-device identity list as `ENVIRONMENT_IDENTITY_LITERAL`. The current candidate still contains such a legacy environment-specific default. It is not silently rewritten in DEV.0.4 because removing it would change device-selection/network behavior. Externalizing that exclusion policy is required before the Corporate Git baseline can receive a final privacy PASS.

## Out of scope

- DLP bypass/obfuscation
- network behavior changes
- CP device-selection changes
- History/CAS migration
- Git history scanning (DEV.1, after Git initialization)
