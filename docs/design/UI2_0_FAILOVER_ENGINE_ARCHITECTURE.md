# neXus Failover Engine Architecture (UI 2.0)

## Status
**PHASE A: IMPLEMENTED & RATIFIED** (Read-Only Pre-Flight Assessment Engine & Operations UI)  
*Phases B, C, D: DRAFT / ROADMAP (Subject to subsequent PO command-gate authorizations)*

## Consensus Authority
- `docs/design/CODEX_FAILOVER_ARCHITECTURE_REVIEW.md` (Astra - Lead Software Architect review via `codex exec`)
- `docs/design/CLAUDE_FAILOVER_SECURITY_REVIEW.md` (Fable - Enterprise Security Architect review via `claude -p`)

---

## 1. Phased Architecture Strategy

Following independent multi-model consultations with Codex and Claude, failover capability is partitioned into four distinct security and operational phases:

1. **Phase A (Implemented in this movement):**
   - **Scope:** Read-Only Pre-Flight Readiness Check Engine SPI, Required-Check Manifests, Two-Sided Corroboration, 12 Core Checks, REST API, and Operations Console UI.
   - **Mutation Risk:** ZERO (Pure read-only telemetry evaluation; no device writes or state mutations).
   - **Overall Verdict:** `NO_BLOCKING_CONDITIONS_OBSERVED` vs `BLOCKING_CONDITIONS_PRESENT` (never misleading terms like `SAFE_TO_FAILOVER` or `READINESS_CONFIRMED`).

2. **Phase B (Subsequent):**
   - **Scope:** Cryptographic 4-Eyes Authorization model (`requesterId != approverId`), single-use nonce & signed lease tokens, dry-run plan compilation for disclosure only.

3. **Phase C (Subsequent):**
   - **Scope:** Controlled Manual Failover Execution under formal command gates (`clusterXL_admin down`, `request high-availability state suspend`) in a dedicated non-production lab cluster.

4. **Phase D (Subsequent):**
   - **Scope:** Scheduled Maintenance Window Failover with T₀ JIT verification and automated abort on drift.

---

## 2. Pre-Flight Engine SPI & Evaluator Invariants (`job-engine`)

### Evaluator vs Transport Decoupling
Pre-flight checks implementing `PreflightCheck` are **pure evaluators** over an immutable, typed `ClusterEvidenceSnapshot`. They never receive credentials, transport handles, or network sockets, eliminating arbitrary command execution and privilege escalation risks.

```java
public interface PreflightCheck {
    String id();
    String name();
    String category();
    EnforcementPolicy defaultPolicy();
    boolean appliesTo(String vendor, String haMode);
    CheckResult evaluate(ClusterEvidenceSnapshot snapshot);
}
```

### Constitutional Verdict Vocabulary
Aligned with the UNKNOWN / Fail-Closed law in `AGENTS.md`:
- `CheckStatus`: `PASS`, `FAIL`, `WARNING`, `INSUFFICIENT_EVIDENCE`, `COLLECTION_FAILED`, `NOT_EVALUABLE`, `UNSUPPORTED`.
- `EnforcementPolicy`: `BLOCKING` (failure blocks failover eligibility), `ADVISORY` (surfaces operational warnings).
- `PreflightVerdict`: `NO_BLOCKING_CONDITIONS_OBSERVED`, `BLOCKING_CONDITIONS_PRESENT`.

### Built-In Pre-Flight Checks
1. **`ViableTargetCheck`**: Validates standby peer existence, reachability, and standby/passive status.
2. **`TwoSidedSplitBrainCheck`**: Corroborates independent observations from both members; strictly fails if both claim `ACTIVE` or if peer cannot be directly observed.
3. **`StateSyncCurrentCheck`**: Validates session sync health and verifies queue backlog delta ≤ 100 events.
4. **`PolicyParityCheck`**: Verifies OS jumbo/hotfix level and installed security policy hash equality between peers.
5. **`ControlSyncLinkHealthCheck`**: Verifies HA control (HA1), sync (HA2), and cluster VIP virtual interfaces are UP with 0 link errors.
6. **`CriticalDevicesPnotesCheck`** (Check Point): Validates that all critical problem notification devices (fwd, cphad, etc.) report OK.
7. **`PathMonitoringCheck`** (Palo Alto): Validates all monitored network destination groups and links are UP.
8. **`StandbyResourceHeadroomCheck`**: Validates CPU (< 80%) and memory (< 85%) on the standby member taking over live traffic.
9. **`PreemptionAwarenessCheck`**: Evaluates preemption flag; warns if automatic failback hazard exists.
10. **`FlapHistoryCheck`**: Evaluates cluster transition frequency over the last 24h; BLOCKING if ≥ 2 transitions observed.
11. **`PlatformAndModeGateCheck`**: Verifies supported Active/Passive mode; fails closed as `UNSUPPORTED` for VSLS, VRRP, Maestro, or Active/Active.
12. **`PendingCommitCheck`** (Palo Alto): Validates no uncommitted or in-progress configuration operations on either peer.

### Required Check Manifest & Coverage Fail-Closed
`PreflightRegistry` defines mandatory check manifests for Check Point and Palo Alto Networks. If any required check is omitted, fails to register, or throws an unhandled exception, the engine fails closed with `INSUFFICIENT_EVIDENCE` / `COLLECTION_FAILED`.

---

## 3. Service Layer & REST API (`service`)

- **Controller:** `com.securityexpert.nexus.ui2.service.api.FailoverPreflightController`
- **Endpoints:**
  - `GET /api/v2/failover/checks`: Catalog of registered checks with categories and default enforcement policies.
  - `GET /api/v2/failover/{clusterRef}/preflight`: Returns the latest preflight report for the cluster (5-minute display TTL cache).
  - `POST /api/v2/failover/{clusterRef}/preflight`: Triggers an on-demand pre-flight evaluation and returns the updated report.
- **RBAC & Persona:** Fully accessible under `role:replay_viewer` (`aiview`), ensuring safe read-only inspection with zero mutation capability.

---

## 4. Operations Console UI (`frontend`)

- **Screen:** `ui2/frontend/src/screens/OperationsScreen.tsx`
- **AIView Standard:** Strictly uses deterministic pseudonyms (`CLS-ROMEO-01`, `CLS-TANGO-01`, `FW-TANGO-04`, `FW-JULIET-06`) and synthetic IP addressing (`192.0.2.x`).
- **Components:**
  - Cluster Overview Card with member roles and live health tags.
  - Overall Verdict Banner (`NO_BLOCKING_CONDITIONS_OBSERVED` green banner).
  - Filter Tabs: All Checks, Blocking Only, Advisory Only.
  - Pre-Flight Checklist Table with status chips, category badges, and summary details.
  - Check Details Dialog with two-sided corroboration explanation.
  - Mutation Controls: "Initiate Failover" and "Schedule Maintenance Window" buttons rendered disabled with explicit tooltips explaining Phase B/C/D gate requirements.
