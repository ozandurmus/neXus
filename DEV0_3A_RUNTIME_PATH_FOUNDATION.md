# DEV.0.3A — Runtime Path Foundation

Status: **AUTOMATED VALIDATED / REAL-ENV BOOTSTRAP VALIDATION PENDING**

## Objective
Establish an external runtime-path foundation without migrating current artifact consumers, history, CAS, or network collectors.

## Implemented
- Immutable `RuntimePaths` with `repository_root`, `runtime_root`, `data_root`, `output_root`, `logs_root`.
- Deterministic repository-root discovery from source location, independent of CWD.
- Runtime-root precedence: `--runtime-root` > `SECURITYEXPERT_RUNTIME_ROOT` > Windows `LOCALAPPDATA` default.
- Explicit CLI/environment roots must be absolute; invalid/empty explicit values fail closed.
- Repository/runtime equality and ancestor/descendant overlap are rejected after canonical resolution.
- Runtime/data/output/logs roots are created and verified with real create/write/delete probes.
- Active bootstrap `Config` carries one `runtime_paths` object; credentials/endpoints are not stored in `RuntimePaths`.
- Startup diagnostic explicitly reports `consumer_migration=legacy_pending` so the foundation cannot be mistaken for completed artifact migration.

## Intentionally unchanged
- CP/PAN/VSX commands, protocols, retries, timeouts and concurrency.
- Existing repository-relative artifact consumers.
- `data/`, `output/`, `logs/` internal layout.
- Configuration history/CAS format and SAME/CHANGED semantics.
- Legacy runtime data; no copy/move/delete/migration.
- Partial-mode bootstrap behavior.
- PAN TLS and CP SSH trust backlog.

## Automated validation
- Runtime-path targeted/bootstrap suite: PASS.
- Full regression: **205 passed, 2 known xfailed**.
- Known xfails remain VSX network canonicalization and PAN default-route classification.

## Real-environment acceptance
Preferred first probe uses an explicit external runtime root and a non-network mode so only bootstrap semantics are validated. DEV.0.3B/C are required before a read-only repository can support a full application run because current artifact consumers intentionally remain legacy in 0.3A.
