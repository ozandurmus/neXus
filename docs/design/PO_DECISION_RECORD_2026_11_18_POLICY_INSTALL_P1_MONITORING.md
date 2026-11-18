# PO Decision Record — Policy Installation Phase 1 (P1: Read-Only Monitoring) Freeze (2026-11-18)

## Status

`RATIFIED` — Product Owner decision, chat 2026-11-18 (`"Evet, dondur"`), following the multi-model decision council review of [OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md](OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md).

## Product Owner Instruction

Quoted from Product Owner chat (2026-11-18):

> *"Evet, dondur"*

Following the decision council review of [OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md](OPERATIONS_POLICY_INSTALL_CONTRACT_DRAFT_2026_11_17.md), the Product Owner authorized freezing **Phase 1 (P1 — Read-Only Monitoring)** as its own standalone `FROZEN` contract ([OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md](OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md)) incorporating the council's three mandatory fixes, and implementing P1 immediately.

## 1. What Is Approved

1. **Frozen Contract ([OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md](OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md)):**
   - Phase 1 (P1) read-only monitoring endpoint (`GET /api/v2/operations/policy-install/status`) and unified frontend tab (`Operations > Policy installation`, `?screen=operations&tab=policy-install`).
   - Consumes **only** data already stored by existing collections from reads that are already `SIGNED_OFF` in `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`.
   - Accessible to any authenticated role (`ActionRegistry.POLICY_INSTALL_READ`, `CLASS_0_READ`), with `aiview` (`role:replay_viewer`) receiving only the masked projection (`TopologyNamePseudonymizer`, no rule names or IP addresses, and search `q` applied after masking).
2. **Mandatory Decision Council Fixes Incorporated into P1:**
   - **Fix (a) — Check Point per-package `OUT_OF_DATE`:** Check Point `OUT_OF_DATE` is computed **per package** (comparing the package's own `last_change_at` against the gateway's `device_policy_install.installed_at` and `policy_name`), never from the MDS domain's last-published session (`cp_policy_last_published_session` / `cp_policy_domain_run.signal`), so publishing another package in the same domain never flags unchanged packages `OUT_OF_DATE`.
   - **Fix (b) — Cisco ASA and FortiGate / FortiManager semantics:**
     - Cisco ASA rows display `SAVED` or `UNSAVED` (comparing `running-config` vs `startup-config` from `asa_more_system_running_config` and `asa_show_startup_config`), **never** `INSTALLED`.
     - Directly managed FortiGate rows display `NOT_APPLICABLE` accompanied by an HA parity indicator (`EQUAL` / `DIFFER` / `UNKNOWN` from `fgt_failover_checksum_cluster` readiness results).
     - FortiManager-managed policy status stays `UNKNOWN` until measured.
   - **Fix (c) — Candidate reads `GI-1`, `GI-2`, `GI-3` excluded from P1:**
     - Candidate reads `GI-1`, `GI-2`, and `GI-3` are not approved in P1; rows that would require them remain `UNKNOWN` with reason `"needs a read not approved yet"`.
     - If `GI-1` is ever proposed for sign-off later, it may only use the normalized non-login shell form (`. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> >/dev/null && cpstat -f policy fw`), never `bash -lc`.
3. **Closed State Vocabulary and Evidence Law:**
   - States: `INSTALLED`, `OUT_OF_DATE`, `NOT_INSTALLED`, `SAVED`, `UNSAVED`, `NOT_APPLICABLE`, `UNKNOWN` (with reason; `"MANAGEMENT_ONLY"` when only the management server claims installed/in-sync without direct gateway runtime corroboration).
   - Management claim != gateway runtime truth: a row is never `INSTALLED` from management claims alone.

## 2. What Is NOT Approved

1. **Phases P2, P3, and P4 remain `DRAFT -- DO NOT FREEZE`:**
   - P2 (Verify Only, on-demand or scheduled), P3 (Manual Policy Installation), and P4 (Scheduled Automatic Policy Installation) are **not** approved and must not be implemented.
2. **Candidate command-gate rows `GI-1` through `GI-10` remain `UNAPPROVED`:**
   - None of `GI-1` (Check Point VSX per-VS `cpstat`), `GI-2` (Panorama `<show><jobs><all/></jobs></show>`), `GI-3` (FortiManager `exec /securityconsole/package/status`), `GI-4` (`verify-policy`), `GI-5` (`commit-all <validate-only/>`), `GI-6` (`<show><jobs><id>`), `GI-7` (`securityconsole/install/preview`), `GI-8` (`install-policy`), `GI-9` (`commit-all`), or `GI-10` (`securityconsole/install/package`) is approved or added to `gate_registry_fixture.yaml`.
3. **Zero write, verify, commit, approval, or scheduling paths:**
   - No `CLASS_4_POLICY_DEPLOYMENT` action, no `ActionRegistry` write action, no non-`GET` route, no database migration adding a device command, no schedule table, no approval table, and no credential reference.

## 3. Open Items Deferred to Later Phases (P2–P4)

The following items raised by the draft and the decision council remain open and must be resolved before freezing P2, P3, or P4:

1. **Constitutional Amendment for Policy Deployment (`ActionClass.CLASS_4_POLICY_DEPLOYMENT`):** Lifting the constitutional prohibition on class-4 policy writes for P3/P4 requires a dedicated PO constitutional amendment record and multi-model council review.
2. **Four-Eyes Approval Policy (`requester != approver`):** Binding requirement that `requester_fingerprint != approver_fingerprint` across both `security_admin` and `operation_admin` for every P3 manual install and P4 scheduled revision pin.
3. **Panorama `<include-template>` and `<validate-only/>` Literal Forms:** Exact XML nesting of `<validate-only/>` under `<commit-all><shared-policy>` on Panorama 12.1 (`GI-5`) and whether `<include-template>` is fixed to `<no/>` (`GI-9`) require lab measurement and PO command-gate sign-off.
4. **Check Point Revision Pinning (`install-policy ... revision '<SESSION_UID>'`):** Lab measurement on R82 MDS to verify whether `revision '<SESSION_UID>'` is honored per domain package when newer sessions exist in the same domain, and whether `threat-prevention` stays excluded (`false`).
5. **FortiManager Scope and Measurement:** Confirming whether the estate's FortiGate appliances are policy-managed via the enrolled FortiManager or directly on-box, and measuring `dvmdb/adom/<adom>/device` / `securityconsole/package/status` (`GI-3`) before any FMG verify/install phase.
