# DEV.0.3B — Runtime Artifact Consumer Migration

Status: automated implementation complete; real-environment filesystem validation pending.

## Scope

Normal runtime artifacts now resolve from the DEV.0.3A external `RuntimePaths` contract: output, run/state data, support-key state and logs. Repository templates/static remain repository-owned. Configuration History/CAS (`data/configs`, `data/artifacts/config/sha256`) is intentionally unchanged and remains DEV.0.3C.

## Invariants

- No legacy repository-output fallback after RuntimeRoot selection.
- No physical legacy data relocation.
- No network command/protocol/retry/concurrency changes.
- No CAS/history format or SAME/CHANGED changes.
- No partial-mode missing-baseline UX fix.

## Automated validation

- 207 passed / 2 known xfailed.
- Synthetic external RuntimeRoot render-only smoke: PASS.
- Generated HTML and logs written under external RuntimeRoot.
- Repository templates/static read from RepositoryRoot.

## Real-environment acceptance

Use a new external RuntimeRoot and validate filesystem-only render/partial behavior before any full network collection. Confirm normal runtime artifacts are created only below RuntimeRoot and no new repository `output/`, `data/runs`, `data/state`, or `logs/` artifacts are produced.
