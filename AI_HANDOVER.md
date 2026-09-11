# AI_HANDOVER

> NON-AUTHORITATIVE DERIVED SUMMARY. CURRENT_STATE.md and project/*.json win.
> DO NOT USE AS PROJECT-STATE AUTHORITY.

## Operating role for the next session

- **Role:** Product Owner + Orchestrator. The assistant scopes work, creates or resumes the correct relay movement, dispatches the engineer worker through `scripts/orchestrator.py`, and reviews the worker's diff/tests/relay closeout.
- **Not operator:** do not contact devices, run production operations, or silently perform worker implementation in the PO session.
- **Implementation boundary:** code changes belong to the worker's clean worktree. The current root UI diff is an unaccepted draft and must not be treated as worker delivery; either discard it under explicit authorization or have the worker reproduce/review the intended behavior.
- **Durable authority:** `AGENTS.md` (constitution) → `docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN PO role) → this handover (active-session pointer) → the movement's relay `SESSION_START` (task-specific scope).
- **Decision source:** durable PO/provider/Graphify/queue/status decisions live in `docs/reference/COPILOT_OPERATING_MODEL.md`; this file points to them and must not become a competing rulebook.
- **Default continuation:** before any implementation, inspect worker state and relay status; do not ask the human to restate this role.
- **Worker/council routing gate:** the PO chooses the lightest suitable current provider/model/effort per movement and per council seat. Prefer Claude when its license/credit is available; use Codex when Claude is unavailable. Council does not imply Fable, Astra, or maximum reasoning: Terra/Opus/Sol/Fable or another current model may be selected by scope; Astra is optional independent review; Codex synthesizes and reviews. OpenRouter is not a general worker fallback. Pass the actual provider, model, and effort explicitly and report all three; there is no fixed model route.
- **Current routing exception:** NXS-LOCAL-0069 was mistakenly dispatched to the Codex default with no explicit model. Its provider/model are **not compliant with the planned workload routing** and must not be relabeled. It has not yet produced code; preserve the error as an auditable relay/state fact.

## Snapshot

- 2026-09-11: `ui2_b1_01_skeleton_ci_docker` remains IN_PROGRESS; Flyway lifecycle is implemented but Docker-backed validation is unavailable on this host.
- Branch: `build/ui2-d1-c7-c2-frozen-amendments`; base `3993d95`.
- Runtime restore remains disabled; no device contact or remote Git operations.
- `scripts/orchestrator.py` has a Codex safety default, but PO dispatch must explicitly select and report the actual provider/model/effort; Claude is the preferred worker route when available, while the OpenRouter review POC is separate and advisory.
- Dashboard now reads the latest provider usage from worker logs and flags high context, oversized contracts, and process-state drift. Current local state: 0 live workers; NXS-LOCAL-0067 and NXS-LOCAL-0068 have dead PIDs but remain persisted as `running` with retry count 0.

## What changed

- Completed three fresh-context Codex/Astra High council reviews; advisory synthesis is in `docs/design/PRODUCT_DIRECTION_RECORD.md`, "UI2 v1 council review — 2026-09-11". No product source, frozen contract or deployment changed.
- Confirmed the user's Java-native redevelopment clarification already matches frozen baseline section 1: Python is validated know-how, not code to port or a runtime dependency.
- Seats returned substantive findings but invalid session-transfer envelopes; quota blocked successful correction. All seats are closed. No cross-model or hook-log verification claim is made.
- Implemented `MigrationRunner` with a B1-1 baseline Flyway migration and added migration-role/app-role integration coverage; strengthened dependency-boundary checks and reconciled stale B1-1 state/history wording.
- Converted integration validation to CRC-backed PostgreSQL: the CRC overlay now has disposable test Postgres, and the harness accepts an `oc port-forward` JDBC URL; Docker/Podman remains optional fallback only. The proposed frozen-contract amendment is `docs/design/UI2_0_B1_01_CRC_VALIDATION_AMENDMENT.md`.
- Made the local AI worker dispatch provider-neutral with Codex as the default; retained the existing Claude argv as an explicit compatibility path and verified the orchestrator/dashboard/PO-gate tests.
- Added PO-facing usage/attention signals to the dashboard; observed 1.35M and 1.23M input-token turns in the current worker logs, indicating broad context ingestion rather than prompt length alone.
- Started the UI2/Material 3 visual pass: live worker/stale counts, job name, provider/model, stage, last activity, input/output usage, and elapsed-open counter are projected by the existing dashboard path. No new worker or dependency was added.
- Added provider/model/effort fields to future orchestrator state records; older records intentionally render `unknown` rather than inferring a model from prose.

## Exact next action

Finish targeted validation of the dashboard UI pass, then decide the PO reconciliation for stale NXS-LOCAL-0067/0068 before any resume. Keep PR #179 topology as a DRAFT recommendation; it does not authorize B1-2 schema work. The next safe product step is an applet/one-click launcher that hides the local port and passes the existing in-memory bearer token without weakening API auth.

Graphify handover rule: the existing ~45k-node graph is known to include
`copilot-worktrees/` duplicates. Never run bare `graphify .` for a small task;
use only a narrow query/path/explain with a small budget, ignore
`copilot-worktrees/`, `graphify-out/`, `data/`, and `logs/`, and inspect only
canonical-repo/relay target files. Graphify locates relationships; it is not
an authority or decision source. Canonical-scope rebuild is a separate
maintenance movement.

Worker notification format: `Relay` → `Work` → `Provider` → `Model` →
`Reasoning` → `PID` → `Phase` → `Worktree` → `First activity` → `Relay
publication`; include live/stale counts, next PO action, and the PO
model/reasoning/token-use line. Report observed values only; never infer a
model from a movement name.

## Test delta

- This council round: source/document comparison only; no runtime tests, full
  regression, privacy scan or device validation. Historical pass counts are not
  new validation evidence. The earlier Testcontainers initialization failure
  remains open and is not the only acceptance question.
- Graphify queried with a narrow workbench observability question; no competing graph generated. After this UI change, run the incremental graph update only.
- Targeted validation: `tests/test_orchestrator_dashboard.py` 39 passed; `node --check scripts/dashboard_assets/dashboard.js` passed. Render harness: 44 passed, 1 Playwright smoke failure caused by the host Chromium Mach-port permission error, not an application assertion.

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
- The pasted prior-chat attachment was read from the recovered attachment path; its routing guidance is now reconciled into `docs/reference/COPILOT_OPERATING_MODEL.md`. Repository authority remains `AGENTS.md`, `CURRENT_STATE.md`, `project/*.json`, then the named contracts.
- OpenRouter worker routing is deliberately not a goal; the accepted `NXS-LOCAL-0065` POC (`3143bb6`) is advisory-only. Unattended night-time backlog continuation remains a real gap: the current orchestrator dispatches Codex/Claude movements but has no queue-runner.
