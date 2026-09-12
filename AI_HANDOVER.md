# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role for the next session

**PO+O** — Product Owner assistant and orchestrator. `roles/PO.md` is the whole
cold start. An engineering session instead reads `roles/ENGINEER.md`.

## 1. Snapshot

- UI 2.0 shell now carries a navigation rail, a top app bar and six routed product
  screens built against the Product Owner's M3 design frames. The Product Owner has
  recorded UI 2.0 as **definitely incomplete**; `ui2_m3_design_transfer_pass` stays
  open as job 1 of the two next jobs.
- The `roles/PO.md` section 3 dispatch loop is **restored and proven to run here**.
  Three movements were dispatched, none authored by the PO+O session.
- **Gates held:** no vendor data collection until the Product Owner specifies, per
  vendor, collection type and methods (shell work exempt); new features are Java
  written from scratch, the Python scripts are know-how only.
- Default screen stays the empty state; populated screens stay behind the labelled
  preview route, still fenced by its test.

## 2. What this session did

- **NXS-LOCAL-0108** `ui2_m3_design_transfer_pass` — integrated. Vite build clean,
  frontend suite 9 passed (from 6), privacy gate PASS, no address-shaped literal in
  preview data. Reviewed by the PO+O session, not by the worker's own claim.
- **NXS-LOCAL-0110** `agent_frozen_contract_audit` — integrated.
  `docs/design/UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md` (DRAFT) audits the contracts the
  previous agent froze on its own hand. Nothing frozen, no contract edited.
- **NXS-LOCAL-0109** `ui2_b1_12_deployment_slice` — **BLOCKED, empty diff.** The
  packet assumed Docker Compose; the worker refused and reported the contradiction
  with the platform skeleton contract's Red Hat container target. The packet's
  runtime assumption was the PO+O session's own error.

## 3. Exact next action

Two jobs, in the Product Owner's order:

1. **UI 2.0 completion** — per-screen fidelity against the six M3 frames plus the
   M3Components drawer states. Row: `ui2_m3_design_transfer_pass`.
2. **Stand the UI up on `kubectl`** — the Product Owner runs OpenShift Local and has
   `kubectl` working, and moves to a local session for this. Re-scope
   `ui2_b1_12_deployment_slice` to plain Kubernetes manifests kept OpenShift-safe by
   construction: no root, no fixed UID, no `hostPath`, no privileged, resource limits
   set, `Service` plus `Ingress` (a `Route` replaces the `Ingress` on OpenShift).
   Build images with Podman, not Docker. Compose is not the product path.

## 4. Test delta

Frontend 9 passed (from 6); vite production build clean; repository privacy gate PASS;
`project_queue.py check` up to date. `ui2/` integration suite not re-run this session.

## 5. New risks

- **`pytest` is absent from the cloud container.** Every dispatched movement therefore
  fails its post-merge convergence check and reports `integration_failed` regardless of
  the worker's own result. Both integrated lanes were reviewed on direct evidence
  instead. A local session does not have this gap.
- **Dispatch packet defect, now known:** a `validation_plan` entry written as a prose
  string is recorded `skipped_prose` and never executes. Entries must be argv lists, or
  no build or test actually runs during verification.
- `NXS-LOCAL-0108` ended `engineer_exit_nonzero` despite a clean, passing diff; the
  cause was not identified and could recur.
- `project/backlog.json` has ~178 bytes of headroom under its 145 KiB ceiling. The next
  queue addition breaches it; a second diet is the fix, never a raised ceiling.
- The design frames were published to a private artifact for Product Owner review.
  Syncing the canvas's raw v4 artboards into the repository adds 44 privacy-gate
  findings — the committed copies are deliberately scrubbed. Do not re-sync them.
- **Open PO decisions:** `po_cp_backup_async_semantics`, `po_ldap_tls_trust_policy`.
  PAN Active/Active stays latent — the estate is Active/Standby.
