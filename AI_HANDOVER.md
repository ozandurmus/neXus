# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-05. Branch: `claude/nexus-control-plane-arch-mutgr9`
  (from `main` `ff700e38`).
- Build: `product_control_plane_architecture_draft` (`PCP.0`) — **still IN
  PROGRESS, freeze withheld pending further Product Owner review.** This
  session is a bounded correction pass: the PO accepted the product
  direction from the prior session but asked for five specific corrections
  to `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` before freezing.
  All five applied; status stays **DRAFT**.
- Docs/state only. No product code, test, taxonomy, console route, device
  command, schema or UI change.
- **Merge to `main` is still blocked** pending the next Product Owner
  review. No PR merged by this session.

## 2. What changed this session (correction round only — see prior handover
entry in git history for what `PCP.0`'s first pass built)

1. **Widened `pcp_console_registry_write_gate`** to cover *both* manual and
   candidate-based console enrollment, not manual alone. A closed
   `candidate_id[]` narrows *what* could be written; it does not by itself
   authorize *that* a persistent registry write may reach the console
   before `DEPLOY.1A`. Removed the doc's pre-decided "candidate-based
   enrollment: yes now" from §19's open-decision row, §13's console-intent
   table, §18's reconciliation table and §22's amendment plan. `PCP.1`
   stays CLI-only and independent of `DEPLOY.1` — unaffected.
2. **Corrected the §4 truth-model table** so the execution-preflight row
   matches `OP.2.0` P8 exactly: the durable operational-entity lock is
   acquired at action-record `CREATED`, *before* preflight runs — not
   after confirmation as the prior table cell implied. Member admission is
   a separate, narrower lock held only per device-contact stage. Added a
   prose paragraph under the table stating this precisely.
3. **Completed §21's deterministic registry contract** for `PCP.1`:
   explicit lifecycle-transition table (only `ENROLLED_UNVERIFIED` →
   `DISABLED` reachable, no new CLI verb, `DISABLED` one-way in `PCP.1`);
   endpoint normalization (case-fold hostnames, strip one trailing dot,
   never DNS-resolve); duplicate detection keyed on normalized endpoint
   only, independent of vendor hint or lifecycle state; repeated-operation/
   idempotency rules for enroll/disable/list; concurrent-CLI-write behavior
   reusing `utils/inventory_exclusions.py`'s exact atomic read-modify-write
   pattern, with the known non-exclusive duplicate race recorded (not
   closed — no new lock primitive, no concurrency framework, per the PO's
   explicit constraint); fail-closed handling of corrupt/unsupported
   persisted data mirroring the same module's `DeviceRegistryError`-style
   posture.
4. **Fixed AC-1 and AC-2.** AC-1 previously contradicted itself ("two
   enrollments... yield different ids and the second is refused" — if
   refused, no second id exists). Split into AC-1a (opacity/uniqueness) and
   AC-1b (pre-generation refusal on duplicate). AC-2's unfalsifiable
   "password-like value in any field" guarantee replaced with concrete
   AC-2a (closed schema, no secret-shaped field, no unknown-key merge),
   AC-2b (`credential_ref` format constraint; never resolved in `PCP.1`),
   AC-2c (redaction-bounded free text). Added AC-10 (no corruption under a
   simulated write race), AC-11 (fail-closed corrupt-data cases), AC-12
   (idempotency assertions).
5. **§9 job-plane correction**: stated explicitly that unsupported
   per-device target selection fails closed *before any device contact* —
   existing plane-wide workflows retain their explicit plane-wide scope,
   and running a whole-plane collector then filtering the result to the
   requested `device_id`s never counts as targeted execution (it still
   contacts every device on the plane and would misrepresent the audit
   record).
- `project/roadmap.json` `now.goal`/§21-reference text and `PCP.1` `next`
  note's AC-range updated (`AC-1a..AC-12`, not `AC-1..AC-9`);
  `AI_HANDOVER.md`, `CURRENT_STATE.md` same AC-range fix.
- `project/build_history.json`: the existing `in_progress` head record
  (`product_control_plane_architecture_draft`) amended in place — summary,
  evidence and `risks_forward` extended to describe this correction round;
  no new record (same still-open build). `docs/history/INDEX.md`
  regenerated.

## 3. Exact next action

1. Open (or update, if already open) a PR from
   `claude/nexus-control-plane-arch-mutgr9` to `main` (fast PR CI is
   sufficient — docs/state only). **Do not merge.** Return to the Product
   Owner for a second review pass of the corrected
   `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`: §4 (lock timing),
   §9 (fail-closed targeting), §13/§18/§19/§22 (widened write gate), §21
   (completed deterministic contract).
2. On approval: apply §22 amendments, flip the doc status to `FROZEN`, set
   the build record to `done`, keep `next` = `PCP.1`, merge, sync `main`.
   `Sonnet 5, extended thinking (high)`.
3. Then `PCP.1` (`Sonnet 5, normal`): one short prompt pointing at §21 and
   `tests/test_pcp1_device_registry.py`. No device contact, no UI.

Unchanged and independent: `op2_c_cp_clusterxl_adapter_scoping` stays
blocked on `DEPLOY.1`; `op0b_0_close_d_v3a_d_v7b_pre_class2`; PAN serial
identity closure (hardware-blocked); `cp_remote_collection_done_marker_
diagnostics` (needs a recurrence).

## 4. Test delta

- No product code changed; full `pytest` **not run** — this sandbox has no
  `pytest`/`lxml`/`paramiko` (reported, not bootstrapped, per `CLAUDE.md`).
  Verified directly instead: `utils.project_plan.build_project_plan_payload()
  ["metadata_warnings"] == []`; `scripts/build_history_index.py --check`
  clean; `CURRENT_STATE.md` names `now.build` and is ≤ 200 lines; every
  `build_history.json` doc link resolves; `git diff --check` clean.
- Repository privacy gate re-run this session: **PASS / 0 findings**
  (484 files scanned).
- Baseline 1825 passed / 24 skipped / 0 failed carried forward, not re-run
  (no product code touched).

## 5. New risks

- None to any reachable capability — no code changed.
- Process: the head `build_history.json` record stays `in_progress` (a
  DRAFT doc may not back a terminal record). Do not flip it to `done`
  before the PO's next review actually freezes the document.
- Product: **no enrollment intent from the console (manual or
  candidate-based) may be implemented** before `pcp_console_registry_write_
  gate` is decided — this session widened the gate specifically to close a
  reading where candidate-based enrollment looked pre-approved. CLI-only
  enrollment in `PCP.1`/`PCP.2` is unaffected and does not wait on this
  decision.
