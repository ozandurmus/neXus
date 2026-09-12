# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role for the next session

**PO+O** — Product Owner assistant and orchestrator. `roles/PO.md` is the whole
cold start. An engineering session instead reads `roles/ENGINEER.md`.

## 1. Snapshot

- `main` at `6e6ef3c`. The previous lane is merged history; start fresh from `main`.
- UI2.0 boots: Spring Boot + PostgreSQL 16 + Flyway V1-V7, serving a React 18 / MUI 5
  shell on the PO's own M3 scheme. Default screen is the empty state; populated
  screens are a labelled preview behind `?preview=`, fenced by a test.
- B1-1a/2/3/4/4b FROZEN, B1-5/B1-7 DRAFT. No B1 row has real-environment evidence.
- **Gates held:** no vendor data collection until the PO specifies, per vendor,
  collection type and methods (shell work exempt); new features are Java written
  from scratch, the Python scripts are know-how only.

## 2. Audit before you inherit — read `PO_DECISION_RECORD_2026_09_12.md` §6

- **§6.4 — the previous PO+O broke `roles/PO.md` §1.** It wrote the service boot,
  migrations V5-V7 and the whole frontend directly instead of dispatching them, and
  merged on its own verification rather than `verify.passed` + PO review.
  **Nothing in `82c4b70` has had an independent review.** Treat it as unreviewed
  code. Queue: `po_o_role_dispatch_boundary` — restore the §3 dispatch loop, or get
  an explicit PO decision that direct authoring is acceptable for this phase.
- **§6.1 — B1-1a and B1-2a were FROZEN by the agent, not PO-reviewed.**
  `roles/PO.md` §2 lets a worker implement FROZEN only, so check *who* froze a
  contract before treating it as dispatch authority. Queue:
  `agent_frozen_contract_audit`.
- §6.2 amended/superseded contracts and one citation flagged with no successor;
  §6.3 the merge executed under verbal authorization; §6.4 four withdrawn claims.

## 3. Exact next action

Decide `po_o_role_dispatch_boundary` first — it governs *how* everything below runs.
Then dispatch `ui2_device_add_discovery_and_manual`: discovery (vendor + IP,
Panorama or MDS) or manual, with operator multi-select over candidates before import.
It stops at the collection gate. `ui2_local_auth_successor_contract` blocks any local
login path; Phase 1 auth is LDAP + local with local always the fallback.

## 4. Test delta

Frontend 6/6; `ui2/` integration 84 executed / 0 failed; budget, queue, convergence,
authority-status and cross-reference suites green; privacy gate 3 findings, all
pre-existing git-ignored runtime directories.

## 5. New risks

- `project/backlog.json` is 148,286 bytes with **194 bytes of headroom** under its
  145 KiB ceiling — the next queue addition breaches it. The 60 KiB contract limit
  was never met: GOV.ORCH.8 dieted the other three files but backlog was out of
  scope and only had its notes moved. A second diet is the fix, not a raised ceiling.
  `project_queue.py retitle` now exists for correcting a title in place.
- `QUEUE.md` sits near its 1500-word budget; run `tests/test_project_files_budget.py`
  after any queue write.
- `scripts/project_queue.py` still cannot write `roadmap.json` `now_next.now`/
  `current_build`, so `build add` breaches GOV.ORCH.5/8.
- The preview screens have never been reconciled against the PO's M3 Configuration
  and M3 Network Inventory canvas frames: `ui2_m3_design_transfer_pass`.
- **Open PO decisions:** `po_cp_backup_async_semantics`, `po_ldap_tls_trust_policy`.
  PAN Active/Active stays latent — this estate is Active/Standby.
