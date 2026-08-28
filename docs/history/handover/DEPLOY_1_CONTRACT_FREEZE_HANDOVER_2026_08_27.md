# DEPLOY.1 Contract Freeze Handover

Date: 2026-08-27
Status: CONTRACT_FROZEN
Movement type: ARCHITECTURE -> RELEASE_HANDOVER
After this handover: return to 0.6.5 implementation track.

## 1. Objective

Freeze a deploy architecture contract for the Ubuntu + Docker server migration
without changing existing collector/evidence semantics and without waiting for
server availability.

Server lead time assumption: ~1 week.

## 2. Scope and non-scope

In scope:
- Deployment architecture contract and acceptance gates.
- Runtime/process model for app + scheduler in Docker.
- Data-plane storage decisions and alternatives for metadata/evidence job state.
- Security boundaries (Nginx edge, OIDC gate, evidence egress gate).
- Operational handover commands/checklist for server arrival.

Out of scope:
- Any collector command changes.
- Any new network device write/change path.
- Runtime environment bootstrap in this repository.
- Credential/content migration execution before server arrival.

## 3. Hard constraints (carry-forward)

1. Preserve read-only firewall interaction behavior.
2. Preserve coordinator safety semantics (no frequency/concurrency increase).
3. Keep secrets out of repository and browser payloads.
4. Keep configuration evidence and native backup/policy-package storage planes separate.
5. Do not run environment bootstrap flows in this workspace.

Python runtime policy for this project:
- Do NOT run interpreter selection, venv creation, or environment bootstrap UI.
- Use existing validated runtime only (`py` command).

## 4. Frozen architecture decisions

### 4.1 Edge and service topology

- Ubuntu host
- Docker Compose stack
- Nginx as ingress: TLS termination + IP allowlist
- App service (current read-only product logic)
- Scheduler service (one-shot/scheduled orchestration with coordinator lock semantics)

### 4.2 Data/storage plane

Primary choice: Postgres container for metadata/evidence index/job state.

Rationale:
- Strong transactional guarantees and mature Docker operations.
- Good fit for coordinator state, admission manifests, and audit rows.
- Compatible with incremental migration from local runtime artifacts.

Separate stores (frozen):
- Secrets store/vault component for credentials (encrypted, reversible by runtime policy; never hashed-only).
- Versioned backup/policy-package volume separate from Postgres evidence state.

### 4.3 Security gates

- DEPLOY.1A OIDC authenticated read-only viewer boundary is mandatory before internal opening.
- Evidence egress policy must be approved before any additional outbound path.
- Production trust controls remain explicit: CP strict host-key and PAN TLS corporate CA verification.

## 5. Postgres performance and Docker alternatives

Current recommendation: keep Postgres as default for DEPLOY.1.

Why this is acceptable now:
- Current workload shape is moderate write rate + read-heavy UI/report queries.
- Postgres handles this profile well with proper indexing and connection limits.
- Operational maturity in Docker is high compared with alternatives.

Alternatives considered (Docker-compatible):

1. MariaDB/MySQL
- Pros: familiar operations, broad tooling.
- Cons: weaker JSON/document ergonomics for evidence-like payloads vs Postgres JSONB workflows.
- Recommendation: not preferred for this phase.

2. SQLite
- Pros: simple, zero service ops.
- Cons: poor multi-writer and concurrency behavior for scheduler/server mode.
- Recommendation: reject for DEPLOY.1.

3. TimescaleDB (Postgres extension)
- Pros: strong time-series capabilities for long-term telemetry/time-window analytics.
- Cons: adds extension complexity; premature unless time-series queries become dominant.
- Recommendation: optional future optimization, not baseline DEPLOY.1.

4. DuckDB sidecar (analytics only)
- Pros: fast local analytics/export jobs.
- Cons: not a transactional operational store for scheduler state.
- Recommendation: optional adjunct, not primary runtime DB.

Performance guardrails to apply with Postgres:
- Connection pooling (PgBouncer or app-level bounded pool).
- Indexed keys for device identity/time-range/status lookup.
- Partition strategy only if evidence row growth proves necessary.
- Baseline SLO tests on server arrival before broad enablement.

## 6. Model selection and reasoning method

This repository uses a risk-based model routing contract:

1. DEPLOY.1 architecture/design decisions:
- Model: strongest approved reasoning tier (Terra High equivalent).
- Reasoning method: high-effort architecture trade-off analysis; explicit invariants and gate checks.

2. Deterministic implementation tasks after architecture freeze:
- Model: normal strong implementation tier (Sol equivalent).
- Reasoning method: bounded implementation reasoning; targeted tests first, then impacted regression.

3. Mechanical metadata/docs/test command updates:
- Model: normal/fast.
- Reasoning method: low-cost deterministic edits.

For Copilot session reporting:
- Agent name: GitHub Copilot
- Model family to report when asked: GPT-5.3-Codex

## 7. Server-arrival execution checklist (T+1 week)

1. Provision Ubuntu host and Docker runtime.
2. Bring up Compose with Nginx + app + scheduler + Postgres + secrets component.
3. Configure OIDC boundary (DEPLOY.1A) before internal opening.
4. Apply evidence egress policy controls.
5. Provision CP MDS host keys into server known_hosts and validate strict host-key R2 path.
6. Validate PAN TLS corporate CA trust path.
7. Run bounded production-safe smoke checks (read-only).

## 8. 0.6.5 handoff after this freeze

Immediate next product build candidate:
- 0.6.5 - PAN TLS/CA Trust Closure (P0)

Frozen transition rule:
- DEPLOY.1 remains architecture-frozen until server arrives.
- Product implementation returns to 0.6.5 track now.
