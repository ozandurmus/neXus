# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role for the next session

**PO+O** — Product Owner assistant and orchestrator. `roles/PO.md` is the whole
cold start, and its §3 now opens with a **pre-dispatch checklist**: read it, it
is the difference between a clean run and a movement that dies at the default
budget. An engineering session instead reads `roles/ENGINEER.md`.

## 1. Snapshot

- **UI 2.0 runs on plain Kubernetes**, built and served from the cluster with no
  host container tool and no host JDK. `ui2_b1_12_deployment_slice` is
  AUTOMATED_VALIDATED.
- **Check Point and VSX discovery is designed and measured**, not implemented.
  The design came from queries the Product Owner ran against a live management
  server, not from the existing Python.
- **The collection gate is lifted for Check Point discovery only.** Every
  device-facing path stays gated; Palo Alto is untouched.
- **UI 2.0 itself stays incomplete** — per-screen fidelity against the Material 3
  frames is the Product Owner's call and the row stays open.
- Gates unchanged otherwise: new features are Java written from scratch, the
  Python is know-how only.

## 2. What this session did

Twelve movements dispatched, ten integrated. Contracts and records:

- **`CP_AND_VSX_DISCOVERY_CONTRACT.md`** (DRAFT) — ten candidate kinds by flags
  and field presence; one host-resolution invariant over two address fields;
  member-to-cluster joins on stable identifiers, never names; liveness refused
  with a recorded three-plane negative search; and a connection-table channel
  state carried under its own name and explicitly not liveness.
- **`DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`** (DRAFT) —
  all 35 steps of the existing collector classified discovery / inventory /
  configuration / running-config, plus the command-gate table and three measured
  defects in that collector.
- **`UI2_0_B1_01C_...DEPLOYMENT_CONTRACT.md`** — FROZEN, Product Owner approved
  after review recorded in its own status block.
- **`PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md`** and
  **`PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`** — the
  runtime decision and the bounded gate lift.

Loop repairs, all merged: budget exhaustion is now its own failure reason and
`roles/PO.md` §3 carries the dispatch checklist; the worker brief's closeout
command is runnable and its pull-request instruction is singular; a
machine-dependent orchestrator test is isolated; `backlog.json` went 144.83 KiB
→ 62.31 KiB with no note text lost. UI: `M3Tabs` is a real tab control with one
panel per tab. The `ui2/` Java suites ran here for the first time — 126 unit
tests and all ten architecture direction tests pass.

## 3. Exact next action

**Freeze `CP_AND_VSX_DISCOVERY_CONTRACT.md`, then dispatch
`cp_discovery_java_implementation`.**

The contract is DRAFT, and `roles/PO.md` §2 allows a worker to implement FROZEN
only. Review §4 (classification), §5 (relationship resolution) and §7 (liveness)
before applying any status, and record the review in the status block — the
Product Owner has said an agent-applied FROZEN is not evidence of their review.

The implementation is bounded by the gate: management plane only, four methods,
no device contacted. Acceptance must include a test proving no liveness claim is
produced — §7's LV-1 protected by a test, not by intention.

## 4. Test delta

`ui2/` Java: 126 unit tests pass, `architectureTest` 11 pass including all ten
direction methods, module listing 11/11. `integrationTest` fails closed on the
missing database exactly as the contract requires. Frontend suite green with
per-tab assertions added. Orchestrator, provider, worker-brief and role suites
green. Repository privacy gate 0 findings. `integrationTest` can now be pointed
at the cluster's PostgreSQL, which no movement has done yet.

## 5. New risks

- **`backlog.json` is at 64,203 of 64,512 bytes — about 309 bytes of headroom.**
  The next queue addition breaches it. The agreed fix is to split the backlog
  into a small active set and a reserve the cold start never loads; that is a
  GOV.ORCH.5/8 contract change and is the work item after the CP discovery
  implementation. Raising the ceiling is not the fix.
- The local Kubernetes VM stopped responding after the host slept and would not
  rebuild; the deployment's manifests and image recipe are in the repository, so
  this costs a rebuild, not the work.
- `UI2_0_B1_01A` and `UI2_0_B1_02A` are still FROZEN by an agent rather than
  Product Owner reviewed (`UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md`).
- The earlier collector's Line 1 equipment could not be removed: a test enforces
  a security invariant against those files. The removal follows the Kubernetes
  manifests taking that invariant over, not the other way round.
- Discovery's channel-state signal records a hypothesis, not a finding: the
  count of non-answering channels equalled the count of failed collections in an
  earlier run, but identity was not verified.
- **Open Product Owner decisions unchanged:** `po_cp_backup_async_semantics`,
  `po_ldap_tls_trust_policy`. PAN Active/Active stays latent — the estate is
  Active/Standby.
