# AI_HANDOVER

> NON-AUTHORITATIVE DERIVED SUMMARY. CURRENT_STATE.md and project/*.json win.
> DO NOT USE AS PROJECT-STATE AUTHORITY.

## Snapshot

- 2026-09-11: `ui2_b1_01_skeleton_ci_docker` remains IN_PROGRESS; Flyway lifecycle is implemented but Docker-backed validation is unavailable on this host.
- Branch: `build/ui2-d1-c7-c2-frozen-amendments`; base `3993d95`.
- Runtime restore remains disabled; no device contact or remote Git operations.
- `scripts/orchestrator.py` now defaults to `codex exec --json`; legacy Claude dispatch is explicit via `--provider claude`.
- Dashboard now reads the latest provider usage from worker logs and flags high context, oversized contracts, and process-state drift; worker graphify guidance is scoped to named refs and small budgets.

## What changed

- Completed three fresh-context Codex/Astra High council reviews; advisory synthesis is in `docs/design/PRODUCT_DIRECTION_RECORD.md`, "UI2 v1 council review — 2026-09-11". No product source, frozen contract or deployment changed.
- Confirmed the user's Java-native redevelopment clarification already matches frozen baseline section 1: Python is validated know-how, not code to port or a runtime dependency.
- Seats returned substantive findings but invalid session-transfer envelopes; quota blocked successful correction. All seats are closed. No cross-model or hook-log verification claim is made.
- Implemented `MigrationRunner` with a B1-1 baseline Flyway migration and added migration-role/app-role integration coverage; strengthened dependency-boundary checks and reconciled stale B1-1 state/history wording.
- Converted integration validation to CRC-backed PostgreSQL: the CRC overlay now has disposable test Postgres, and the harness accepts an `oc port-forward` JDBC URL; Docker/Podman remains optional fallback only. The proposed frozen-contract amendment is `docs/design/UI2_0_B1_01_CRC_VALIDATION_AMENDMENT.md`.
- Made the local AI worker dispatch provider-neutral with Codex as the default; retained the existing Claude argv as an explicit compatibility path and verified the orchestrator/dashboard/PO-gate tests.
- Added PO-facing usage/attention signals to the dashboard; observed 1.35M and 1.23M input-token turns in the current worker logs, indicating broad context ingestion rather than prompt length alone.

## Exact next action

The B1-1 worker must complete CRC-backed integration validation (the migrate
entry point now invokes Flyway) and remaining image/secret/SBOM/privacy evidence.
B1-1M remains next, before B1-2: High-tier release/topology amendments
incorporating council CR-1..CR-6. C2 transition, crash and fencing
contradictions must be resolved before job-engine execution.
Formal governance publication remains pending; this observability fix does not authorize product or device behavior.

## Test delta

- This council round: source/document comparison only; no runtime tests, full
  regression, privacy scan or device validation. Historical pass counts are not
  new validation evidence. The earlier Testcontainers initialization failure
  remains open and is not the only acceptance question.
- Graphify queried; document semantics stale and worktree matches noisy. Canonical
  files supplied directly to read-only seats; no competing graph generated.

## New risks

- C2 section 9 item 5 already requires commit-before-send; review CR-3 concerns
  inconsistent prose and pause/fencing coverage, not a wholly missing rule.
- Backup retrieval must respect C7's no-HTTP-artefact-byte rule; controlled
  failover and privileged administration need their own bounded acceptance.
- CURRENT_STATE's "Only Testcontainers" and migration validation wording exceeds
  demonstrated evidence; owning B1-1 state reconciliation is still outstanding.
- Freshness numeric values and cached-evidence vendor semantics remain UNKNOWN.
- This implementation turn: `architectureTest`, `unitTest`, history-index check,
  architecture convergence (23 passed), and `git diff --check` passed.
- `integrationTest` reached Testcontainers but failed before test execution:
  no valid Docker environment (`/var/run/docker.sock` absent). This is not
  converted to a skip or success.
- CRC-backed path is implemented but not yet real-environment validated in this
  session; it requires the user's running CRC cluster, secret and port-forward.
- Provider-dispatch and dashboard-observability changes are locally validated; this change is ready for PO commit/push review.
