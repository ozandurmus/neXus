# OP.1 — Failover Plan Compiler and Dry-Run

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09.** Frozen together with the
`op_degraded_verdict` decision this contract depends on (§8): the Product
Owner chose **Option A — `DEGRADED_PROCEED_WITH_RISK` stays structurally
unreachable** until real-field calibration, so §3/§5's schema stands exactly
as specified and no `risk_items`/severity vocabulary is added. This freeze
authorizes exactly one implementation slice, `OP.1.S1` (§9), and nothing
else; it changes no code itself. (Drafted 2026-09-08 as
`op1_failover_plan_compiler_contract_draft`, PR #133.)

Parent authority: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10 ("`OP.1`
— Failover Plan Compiler + Dry-Run. §3 action/rollback compilation,
`FailoverPlan`, dry-run only. Still no write, same prerequisites as `OP.0`")
and §10.1 (`OP.1` row: "Plan compiler + dry-run", class 0, gate
"`op_degraded_verdict` decides at contract freeze"). This document does not
restate §1–§9 of that parent; it cites them.
CLASS 2 architecture authority: `docs/history/phase/
OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` (FROZEN 2026-09-04) — cited
for the typed vocabulary this contract reuses (§4), never for authority to
execute anything.
Evidence producers this contract consumes, unchanged by it:
`docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md` (P4, FROZEN) and
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES).
Command approval authority for the two primitives named below:
`docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`
(`CP-M1`/`CP-M1-R`, `APPROVED_FOR_OP2C`).

Movement: `ARCHITECTURE` (this document) → Product Owner freeze (with
`op_degraded_verdict`, done 2026-09-09, Option A) → `IMPLEMENTATION` (§9
slicing, `OP.1.S1`).

---

## 1. Objective and framing

`OP.1` compiles what a controlled Check Point ClusterXL failover **would**
look like — the exact primitive, the preconditions it depends on, the
postcondition it targets, the reads that would verify it, and its explicit
reversal — from evidence the platform **already holds**. It runs no
command, contacts no device, and constructs nothing that could execute one.
Architecture §1's design principle governs this contract directly: "the
engine never improvises. Every action it can take is compiled from evidence
into an explicit plan that a human authorises." `OP.1` builds the plan.
Nothing in this track authorises anyone to run it — that is `OP.2`,
separately gated, separately unimplemented at production scope
(`CURRENT_STATE.md` "Open blockers").

**Scope: classic Check Point ClusterXL only**, mirroring `OP.2.0` §10.2's
own pilot decision ("Check Point classic ClusterXL is the first and only
initial pilot"). Palo Alto HA and Check Point VSX/VSLS are explicitly
deferred — see §7.

**Why this document exists now, after `OP.2.A`/`OP.2.B`/`OP.2.C` already
implemented a typed execution plane.** The roadmap sequenced `OP.1` before
`OP.2` (§10: both write-free, `OP.2` gated); in practice `OP.2.A/B/C` were
implemented first (architecture + adapter + real preflight wiring, all
unwired, per `CURRENT_STATE.md`). That work produced typed vocabulary this
contract deliberately **reuses rather than re-invents** — `utils.operate.
adapter.ActionPlan`, `checkpoint.clusterxl_capability_adapter.
CPClusterXLCapabilityAdapter` — but `OP.1` is not a rebranding of `OP.2.C`.
It is a **structurally separate, standalone consumer** of two of that
package's methods, chosen precisely because they are already pure
(zero-I/O) and read-only by construction, and it never touches the methods,
types or orchestration that are not (§4, §5).

---

## 2. Scope

**In:**
- One CP ClusterXL operational unit's compiled `FailoverPlan` (§3) and its
  `DryRunReport` (§5), built entirely from evidence `compute_ha_readiness`
  and the OP.0b preflight surface already hold.
- The `op_degraded_verdict` decision table for the Product Owner (§8).
- An implementation slicing plan (§9).
- Bookkeeping (roadmap/build-history/state files).

**Out (per this movement's `SESSION_START` and the parent architecture's
`OP.2` gate):**
- Any code, test or `checkpoint/`/`utils/failover/` file change. This
  document is design only.
- Execution, adapters, `ActionCoordinator` construction, or any wiring that
  makes `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE` reachable.
- Palo Alto HA, Check Point VSX/VSLS (`op_aa_vsls_scope`, `OP.3`).
- The Operator Console / `OP.2.D` flow. Whether the compiled plan/dry-run is
  ever surfaced on the existing `OP.0c` Failover dashboard module is a later,
  separate UI decision — this contract defines the backend artifact only.
- Any new device command. Every fact `OP.1` reasons about already has an
  approved reader (§4); if a future need arose for a fact with no approved
  read, that is a new network-device command gate entry, not something this
  contract can authorise by using it.

---

## 3. The `FailoverPlan` artifact

A `FailoverPlan` is a class-0, read-derived, immutable value object for
**one** CP ClusterXL operational unit (`utils.failover.assessment.HaUnit.
unit_id`, `unit_type == "cp_clusterxl_cluster"` only — `cp_vsx_host`/
`cp_vsx_cluster`/`cp_vsx_virtual_system` units are out of scope per §7).

```
FailoverPlan
├── unit_id                       str   — HaUnit.unit_id (compute_ha_readiness)
├── vendor                        "checkpoint"
├── capability_id                 "cp_clusterxl_admin_state_v1"  (verbatim,
│                                 checkpoint.clusterxl_capability_adapter._CAPABILITY_ID)
├── evidence_basis                "op0a_stored_telemetry" | "op0b_preflight_snapshot"
│                                 (verbatim, utils.failover.assessment.
│                                 EVIDENCE_BASIS_STORED_TELEMETRY /
│                                 EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT)
├── readiness_verdict             str   — copied verbatim from compute_ha_readiness's
│                                 unit assessment; never re-derived (P4 below)
├── plan_compilable                bool  — could a step even be built (§3.1)
├── compilation_blocked_reason     str | None
├── step                          PlanStep | None   — exactly one (§3.2); None
│                                 when plan_compilable is False
├── reversal                      ReversalStep | None — CP-M1-R disclosure (§3.3)
└── generated_at                  UTC timestamp
```

### 3.1 Whether a plan can be compiled at all

Compiling `step` requires the same two facts `checkpoint.
clusterxl_capability_adapter.CPClusterXLCapabilityAdapter.capability()`
already gates on, unchanged:

1. `cluster_mode` resolves to the adapter's canonical HA token (`"ha"`,
   after the one fixed vendor-vocabulary translation
   `checkpoint.clusterxl_preflight_provider._map_cluster_mode` already
   performs for `"ha_new_mode"`). Any other mode (Load Sharing, VSX/VSLS,
   unresolved) is `unsupported_cluster_mode` — not a plan defect, a scope
   boundary already enforced upstream by `_verdict_for`'s own
   `NOT_A_FAILOVER_UNIT` handling for Load Sharing.
2. Exactly one member resolves `ha_local_role == "ACTIVE"` among exactly
   two observed members, giving unambiguous `subject_member_token`/
   `peer_member_token` opaque tokens — the same fail-closed resolution
   `checkpoint.clusterxl_preflight_provider._resolve_member_tokens`
   already performs (zero or more than one active, or fewer/more than two
   members, resolves to no tokens, never a guess).

**This is independent of `readiness_verdict`.** A plan can be — and, for an
operator's situational awareness, should be — compiled for a cluster whose
readiness verdict is `UNSAFE_DO_NOT_FAILOVER`: knowing exactly which
primitive would act on which member is part of *understanding why it is
unsafe*, not permission to run it (§6, P1). `plan_compilable` is `False`
only when the two structural facts above cannot be established — a
`NOT_A_FAILOVER_UNIT` cluster (Load Sharing) or a cluster whose member
identity/role evidence does not resolve.

### 3.2 `PlanStep` — the one compiled primitive

```
PlanStep
├── primitive_id           "CP-M1"  (fixed — the only forward primitive this
│                          contract's scope ever compiles)
├── action_type            "cp_clusterxl_ha_graceful_failover"  (verbatim,
│                          checkpoint.clusterxl_capability_adapter.
│                          ACTION_TYPE_HA_GRACEFUL_FAILOVER)
├── subject_member_token   opaque str (§3.1)
├── preconditions           tuple[PreconditionBinding, ...]  — the seven
│                          named stop-conditions (§4), never fewer, never a
│                          subset chosen by this module
├── intended_postcondition  str  — verbatim ActionPlan.intended_postcondition
│                          ("peer becomes ACTIVE")
├── impact_disclosure       str  — verbatim ActionPlan.impact_disclosure
├── verification_reads      tuple[str, ...]  — named facts observe_postcondition()
│                          would read (both members' ha_local_role, the
│                          admin_down pnote), never a command string (P18,
│                          reused unchanged from utils.operate.adapter)
└── settle_observation      None  (verbatim ActionPlan.settle_observation —
                            no numeric timer exists or is invented; unchanged
                            OP.2.1 finding)
```

`primitive_id`/`action_type` may **only** ever be one of the two
`OP.2.1`-`APPROVED_FOR_OP2C` values (`CP-M1` / `CP-M1-R`). A `FailoverPlan`
whose `step.action_type` is anything else is a defect, not a variant — this
contract compiles no primitive `OP.2.1` did not already approve, and
approving a third would itself need a new network-device command gate entry
(`AGENTS.md` "Network action taxonomy").

`PreconditionBinding` is not a new vocabulary: it is one of the seven
`utils.failover.assessment.STOP_CONDITIONS` entries, carrying the exact
`(id, label, status, reason, missing_evidence)` shape `compute_ha_readiness`
already produced for this unit — reused verbatim (§4), not re-evaluated.

### 3.3 `ReversalStep` — `CP-M1-R`, disclosed, never chained

```
ReversalStep
├── primitive_id            "CP-M1-R"
├── action_type             "cp_clusterxl_ha_graceful_failback"  (verbatim,
│                           ACTION_TYPE_HA_GRACEFUL_FAILBACK)
├── reverses                "the compiled step above" (structural reference
│                           only; not an action_id — no action exists yet)
├── intended_postcondition  str | "UNKNOWN"  — verbatim ActionPlan's own
│                           three-way disclosure (maintain-current-active /
│                           switch-to-higher-priority / UNKNOWN; see §3.4)
├── impact_disclosure        str  — verbatim ActionPlan.impact_disclosure
└── authorization_note       fixed string: "This is a separate, independently
                             confirmed CLASS 2 action (op_reversal_model,
                             decided 2026-09-04). Compiling this disclosure
                             does not schedule, queue or imply it will run."
```

This directly satisfies AC-2's reversal requirement and the parent
architecture's already-decided `op_reversal_model` (§10.2: "reversal/
failback is a new typed CLASS 2 action with its own authorization... never
issued automatically"). `OP.1` never proposes issuing it; it only discloses,
before the forward step is even shown to a human, what its exact reversal
would look like and what it depends on.

### 3.4 `D-V7b` and the reversal's own disclosed unknown

Check Point's configured cluster recovery method (maintain-current-active
vs. switch-to-higher-priority) has no approved machine-readable read
(`D-V7b`, `STILL_UNKNOWN`, `utils.failover.assessment.
ADVISORY_EXEMPT_CHECKS`). `checkpoint.clusterxl_preflight_provider` already
resolves this the only honest way — `recovery_mode="unknown"`, always — and
`CPClusterXLCapabilityAdapter.build_plan()` already turns that into a
disclosed `intended_postcondition="UNKNOWN"` with an explicit operator-facing
note (quoted in full at `checkpoint/clusterxl_capability_adapter.py` lines
257–268). **`OP.1` reuses this unchanged.** No part of this contract
re-litigates `D-V7b`, invents a value for it, or treats its
`ADVISORY_EXEMPT_CHECKS` membership as resolving the underlying vendor
question (`utils.failover.assessment` module docstring already states this
distinction: the exemption changes only what the *readiness roll-up*
requires, never the vendor fact itself). This is why `D-V7b` does **not**
need a fresh decision at this freeze, despite being "vendor-semantic
ambiguity" in the abstract: the consequence for `OP.1`'s own vocabulary was
already fully absorbed by `OP.2.0`/`OP.2.1`'s prior, frozen design.

`D-V3a` (the equivalent Palo Alto vendor-semantics gap) does not apply to
this contract at all — `OP.1`'s scope is classic ClusterXL only (§7).

---

## 4. Inputs — existing producers only, named exactly

No new collector, no new device command, no new evidence field. Every input
below already exists and is already approved for the purpose it is used for
here (`AGENTS.md` "Evidence laws" — "command presence in source != command
approval" is respected because these methods are already used for exactly
this shape of read-derived, non-mutating consumption, not merely present in
the source tree):

| Field OP.1 consumes | Source | Notes |
| --- | --- | --- |
| Unit identity, `cluster_mode`, `verdict`, `reason`, seven `checks[]`, `evidence{}` | `utils.failover.assessment.compute_ha_readiness()` | The one canonical readiness roll-up (P4-governed, `_verdict_for`). `OP.1` calls this the same way `OP.0a`/`OP.0c` already do — read-only, over `unified.json` + whatever `cp_ha_runtime`/`preflight_snapshots` the caller already collected in a prior, separate stage. `OP.1` performs no collection itself. |
| Fresh S5 `PreflightSnapshot` facts (`ha_local_role`, `ha_cluster_mode`, etc.), when available | `checkpoint.preflight_collector.run_cp_preflight()` output, **already collected by a prior `OP.0b` invocation** and passed into `compute_ha_readiness(preflight_snapshots=...)` by that caller | `OP.1` never calls `run_cp_preflight` itself — doing so would be a fresh device contact, which this contract's dry-run explicitly excludes (§5). It consumes only what `compute_ha_readiness` already folded in. |
| Vendor-mode → adapter-mode translation (`"ha_new_mode"` → `"ha"`) | `checkpoint.clusterxl_preflight_provider._map_cluster_mode()` (pure function; existing, unchanged) | Reused as the single fixed vocabulary translation; not a second mode decision (module's own docstring). |
| `subject_member_token`/`peer_member_token` resolution from a unit's `ha_local_role` facts | `checkpoint.clusterxl_preflight_provider._resolve_member_tokens()` (pure function; existing, unchanged) | Same fail-closed rule reused: exactly one `ACTIVE` among exactly two members, or no tokens. |
| `Capability` (supported/unsupported + reason) | `checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter.capability()` | Pure — zero I/O, confirmed by reading its implementation (§4.1). |
| `ActionPlan` (`intended_postcondition`, `impact_disclosure`, `reversal_note`, `material_action_parameters`) for both `CP-M1` and `CP-M1-R` | `checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter.build_plan()` | Pure — zero I/O (§4.1). Never `check_precondition()`, `execute_once()` or `observe_postcondition()` — see §5. |
| `PreflightSnapshot` value-object shape (`preflight_run_id`, `operational_entity_id`, `coherent`, `readiness_verdict`, `check_statuses`) | `utils.operate.eligibility.PreflightSnapshot` (dataclass; existing, unchanged) | `OP.1` constructs an instance of this **from already-collected evidence** (`compute_ha_readiness` + the two provider-style helpers above) — it never calls the live `checkpoint.clusterxl_preflight_provider.ClusterXLPreflightProvider.run_preflight()`, which performs real device I/O. This is the "ClusterXL preflight provider outputs" the `SESSION_START` names: the *output type* that provider produces, reused as a construction target, not the provider itself. |
| The seven stop-condition definitions | `utils.failover.assessment.STOP_CONDITIONS` (module-level constant) | Reused verbatim for `PreconditionBinding` (§3.2). |
| Command approval / primitive identity | `docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` (`CP-M1`/`CP-M1-R` rows) | Cited, not re-approved. No new primitive. |

### 4.1 Purity of the two reused adapter methods (verified, not assumed)

`AGENTS.md` "Evidence laws" forbids treating a function's name or its
caller's docstring as proof of its contract. `capability()` and
`build_plan()` were read in full (`checkpoint/clusterxl_capability_adapter.py`
lines 175–278) for this contract: neither references `self._session_resolver`
or any I/O primitive; both are deterministic pure functions of their
`entity_kind`/`action_type`/`evidence` arguments. `check_precondition()`,
`execute_once()` and `observe_postcondition()` all call
`self._session_resolver(...)` and are never invoked by this contract's
design (§5, P8). The implementation slice (§9) constructs
`CPClusterXLCapabilityAdapter` with a **poison resolver** — a callable that
raises `AssertionError` if ever invoked — as a structural, test-enforced
proof that the dry-run path never reaches device I/O, not merely a
documented intention.

---

## 5. The dry-run

A dry-run answers one question per unit: **"if this plan's preconditions
were evaluated right now against evidence we already have, which would
hold?"** It is not a live re-check (that is `check_precondition()`,
`OP.2`'s own job, run fresh at actual confirm-time) and it is not a second
readiness engine (the seven checks are `compute_ha_readiness`'s own,
re-surfaced, never re-evaluated — one readiness authority, per
`utils.failover.preflight_readiness`'s own module docstring, which this
contract does not relitigate).

```
DryRunReport
├── plan                    FailoverPlan               (§3)
├── precondition_results    tuple[PreconditionCheckResult, ...]
│                           — one per PlanStep.preconditions entry, each
│                           {id, label, status, reason, missing_evidence}
│                           copied verbatim from the plan's own
│                           preconditions (never re-evaluated a second time)
├── readiness_verdict       str  — same value as FailoverPlan.readiness_verdict,
│                           repeated here only because a report reader should
│                           not have to cross-reference two objects for it
├── would_proceed            bool — True only when plan_compilable AND
│                           readiness_verdict == "SAFE_TO_FAILOVER"; False
│                           for every other verdict, including
│                           INSUFFICIENT_EVIDENCE and (structurally
│                           unreachable today, §8) DEGRADED_PROCEED_WITH_RISK
├── authorization_note       fixed string (§6, verbatim, never conditional):
│                           "This dry-run grants no authorization. It issues
│                           no command and constructs no ActionCoordinator.
│                           Executing this plan requires a separate,
│                           independently authorized OP.2 action once that
│                           track's own prerequisites are met."
└── generated_at             UTC timestamp
```

`would_proceed` is disclosure, not a gate — nothing consumes it to permit
anything (§6). It exists because "would this plan's preconditions currently
hold" is the plain-language question a human asks when reading a dry-run,
and answering it explicitly is safer than making a reader infer it from five
separate check rows.

**What the dry-run never does** (each restates an explicit `SESSION_START`
requirement so the boundary is checkable, not merely asserted):

- Issues no device command — it reads no live evidence at all; every fact
  is already-collected (§4).
- Constructs no `utils.operate.coordinator.ActionCoordinator` (`OP.1`'s own
  code depends on `utils.operate.adapter`/`utils.operate.eligibility` types
  only — never `utils.operate.coordinator`, `utils.operate.authorization`,
  `utils.operate.approval_policy` or `utils.operate.store`).
- Never calls `check_precondition()`, `execute_once()` or
  `observe_postcondition()` on the capability adapter (§4.1).
- Never creates a `utils.operate.record.ActionRecord` — there is no action,
  only a plan and a report about it.

---

## 6. Non-authorization is structural (AC-3)

This section is the load-bearing one; every other section exists to make it
true by construction, not by policy statement:

- **A compiled plan grants nothing.** `FailoverPlan`/`DryRunReport` carry no
  field an authorization boundary could consume as a grant, token or
  approval. `utils.operate.authorization.DenyAllAuthorizer` remains the only
  production `Authorizer` and is untouched by this contract.
- **Readiness != authorization; a plan != permission** (`AGENTS.md`
  "Evidence laws", restated by `FAILOVER_ENGINE_ARCHITECTURE.md` throughout).
  A `SAFE_TO_FAILOVER` verdict flowing into `would_proceed=True` is a
  disclosure about evidence, never a decision about action.
  the fixed `authorization_note` (§5) is repeated on every `DryRunReport`,
  independent of its verdict, precisely so a reader cannot encounter a
  report that omits it.
- **`CLASS 2` keeps no taxonomy member.** This contract adds none.
  `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.permitted` and
  `.console_submittable` stay `False`; the implementation slice (§9) is
  required to include a regression test asserting this, mirroring
  `tests/test_op2_c_cp_clusterxl_adapter.py::
  test_class_2_still_has_no_member_and_deny_all_is_still_the_only_authorizer`.
- **The plan artifact is a class-0, read-derived document.** It is produced
  by a CLI mode (§9) that writes a report to the local filesystem, exactly
  like `--ha-readiness-check` already does for `data/state/ha_readiness.json`
  — never to a queue, job registry, or any surface `console/registry.py`
  resolves against. It must never be consumed by any execution path in this
  track: the implementation slice's own architecture-convergence test must
  assert that no module under `utils/operate/` or `checkpoint/
  clusterxl_capability_adapter.py` imports the new `OP.1` package (the
  dependency direction is one-way — `OP.1` reads from `utils.operate`'s
  types, `utils.operate` never reads from `OP.1`).
- **No Browser → device path is widened.** This contract defines no console
  job type. If a future movement wants the dry-run reachable from the
  Operator Console, that is its own, later decision against
  `console/registry.py`'s closed vocabulary — not implied or pre-authorized
  here.

---

## 7. Vendor scope boundary

Classic ClusterXL (`unit_type == "cp_clusterxl_cluster"`) only, for these
reasons, stated once so the implementation slice does not need to
re-derive them:

- `OP.2.0` §10.2 already scoped the **first and only initial CLASS 2
  pilot** to classic ClusterXL; compiling a plan for a vendor/topology
  `OP.2` itself has no adapter for would produce a `FailoverPlan` nobody
  could ever execute through this track's own execution plane, inviting
  exactly the confusion §6 exists to prevent.
- **Palo Alto (`pan_ha_pair` units):** blocked independently by `D-V3a`
  (`STILL_UNKNOWN`, PAN vendor semantics) and by `B2` bidirectional
  peer-identity corroboration being **NOT ESTABLISHED** for the one real
  approved PAN pair (`CURRENT_STATE.md` "PAN HA serial evidence"). Neither
  gap is `OP.1`'s to close; both predate and outlive this contract.
- **VSX / VSLS (`cp_vsx_host`/`cp_vsx_cluster`/`cp_vsx_virtual_system`
  units):** `op_aa_vsls_scope` stays open/deferred to `OP.3`
  (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2: "no VSID adapter exists and none
  is authorized"). A VSID unit could, in principle, reach a `SAFE_TO_
  FAILOVER` readiness verdict today (`OP.0b` S4-A' evaluates VSLS units
  independently) — but no capability adapter exists for it, so `OP.1` would
  have nothing to call `build_plan()` against and must report
  `plan_compilable=False, compilation_blocked_reason="vendor_scope_
  vsx_vsls_deferred"` for any such unit, never attempt a generic/
  cross-vendor compilation (`AGENTS.md` "no generic cross-vendor primitive",
  `OP.2.0` §10.1 item 9).

---

## 8. Open decision for this freeze: `op_degraded_verdict`

`project/roadmap.json` already names this decision's `decide_by` as **"OP.1
contract freeze"** — this is that freeze point. Presented as one decision,
per the `SESSION_START`'s requirement:

**Question:** does `utils.failover.assessment._verdict_for` gain a real path
to `DEGRADED_PROCEED_WITH_RISK` (per-risk operator acceptance) now, or does
`OP.1` ship with `DEGRADED` staying structurally unreachable (as `AC-6`
already proves it is today) until real-field calibration exists?

**Existing recommendation (`docs/design/PRODUCT_DIRECTION_RECORD.md` #18,
unchanged by this document):** keep `DEGRADED_PROCEED_WITH_RISK`
structurally unreachable until real-field calibration.

**Consequence for the `OP.1` plan/dry-run vocabulary, per option:**

| Option | Consequence for this contract's own artifacts |
| --- | --- |
| **A — keep `DEGRADED` unreachable (recommended, no change)** | `FailoverPlan.readiness_verdict` and `DryRunReport.readiness_verdict` can only ever show `SAFE_TO_FAILOVER`, `UNSAFE_DO_NOT_FAILOVER`, `INSUFFICIENT_EVIDENCE` or `NOT_A_FAILOVER_UNIT` (`AC-6`'s own proof is unaffected). `would_proceed` stays a plain boolean; `PreconditionCheckResult` needs no severity/acceptability field beyond `PASS`/`FAIL`/`INSUFFICIENT_EVIDENCE`. This contract's schema (§3, §5), as specified, already assumes Option A and needs no further change if it is chosen. |
| **B — make `DEGRADED` reachable now** | Requires, before `OP.1` could ship against it: (1) reopening `utils/failover/assessment.py::_verdict_for` and its `AC-6` structural-unreachability test — both explicitly out of this movement's scope (`SESSION_START` "Do not touch `utils/failover/`... code in this movement"); (2) a new, currently-undefined notion of which check failures are "acceptable risk" vs. hard-blocking, which is itself an unresolved vendor-safety judgment, not a data-modeling choice; (3) a new `risk_items` structure on both `FailoverPlan` and `DryRunReport` (a per-check operator-acceptance disclosure, distinct from a precondition result) that this contract would need to add; (4) eventually, a matching amendment to `utils.operate.approval_policy` so a real `OP.2` execution can require the same risk-acceptance evidence at confirm-time — work this contract cannot scope alone. Choosing B here does not ship a `DEGRADED`-capable `OP.1` today; it only reopens `OP.0a`'s frozen verdict contract and defers `OP.1` behind that reopening. |

Recommendation to the Product Owner: **choose Option A** at this freeze.
Nothing about `OP.1`'s own value (a reviewable plan + honest precondition
disclosure) depends on `DEGRADED` being reachable, and Option B's cost is a
new, separately-scoped `OP.0a` reopening, not a natural extension of this
contract.

### 8.1 Non-blocking open decisions — listed, not resolved here

Per the `SESSION_START`, these four stay **open and explicitly non-blocking**
for this freeze, each already classified this way by the Product Owner on
2026-09-04 (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 / `project/
roadmap.json`):

| Decision | One-line reason it does not block `OP.1` |
| --- | --- |
| `op_four_eyes` | A deployment/release-policy input to `OP.2`'s `approval_policy` confirmation boundary — `OP.1` compiles no confirmation step and constructs no `ActionRecord` for anyone to approve. |
| `op_emergency_evac` | Scoped to a future, separate `OP.3` emergency path with its own contract; `OP.1` compiles only the planned-maintenance primitive pair (`CP-M1`/`CP-M1-R`), never an emergency variant. |
| `op_continuity_tolerance` | Governs *post-action* continuity verdict-bearing at `OP.2` execution time; `OP.1` performs no action and observes no post-action state at all. |
| `op_aa_vsls_scope` | Already governs this contract's own vendor-scope boundary (§7) as "deferred" — `OP.1` does not need it *decided*, only respected, which §7 already does. |

---

## 9. Implementation slicing plan (AC-6)

Sized to `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 targets (≤ 8 acceptance
criteria, ≤ 12 non-test source files, one subsystem boundary, closes in one
engineering session).

**One slice is sufficient — no split is proposed.** Justification per §7's
own bar for not splitting: the scope is one subsystem boundary (a single new
package consuming two already-frozen, already-pure methods plus one already
canonical roll-up function), one vendor, one CLI mode, and the total file
count is well inside the target even without splitting.

### Slice `OP.1.S1` — plan compiler, dry-run, CLI surface

**Files (5 non-test, well under the 12 target):**

1. `utils/failover_plan/__init__.py` — new package. Deliberately **not**
   `utils/failover/` (that package's exact module allowlist is test-enforced,
   `tests/test_architecture_convergence.py::
   test_the_failover_package_still_contains_no_executor` — adding a plan
   module there would fail that test, correctly, since it exists precisely
   to keep a plan/executor/adapter out of that package) and **not**
   `utils/operate/` (that package's own exact allowlist is test-enforced,
   `tests/test_op2_c_cp_clusterxl_adapter.py::
   test_utils_operate_allowlist_and_transport_ban_are_unaffected`, and it is
   the CLASS-2-adjacent execution plane this contract must stay
   structurally separate from, §6).
2. `utils/failover_plan/model.py` — `FailoverPlan`, `PlanStep`,
   `ReversalStep`, `PreconditionBinding`, `DryRunReport`,
   `PreconditionCheckResult` dataclasses (§3, §5). No behaviour.
3. `utils/failover_plan/compiler.py` — `compile_failover_plan(unit_id, *,
   readiness_record, ...) -> FailoverPlan`: the vendor-mode/member-token
   resolution (§4, reusing or duplicating the two named
   `clusterxl_preflight_provider` helpers — the slice's own `RELAY_QUESTION`
   decides which, per §4's note that this is an implementation detail, not a
   contract-level one) and the two `CPClusterXLCapabilityAdapter` calls,
   constructed with the poison `session_resolver` (§4.1).
4. `utils/failover_plan/dry_run.py` — `evaluate_dry_run(plan) ->
   DryRunReport` (§5): pure formatting/aggregation over `plan`'s own
   preconditions plus the fixed `authorization_note`; no new evaluation
   logic.
5. `main.py` — one new CLI mode, `--failover-plan-dry-run [--failover-plan-unit
   <unit_id>]`, mirroring `--ha-readiness-check`'s existing shape: offline,
   no credential, no network, writes a report file under
   `data/state/failover_plan/`. Reuses the same `unified.json` +
   `cp_ha_runtime`/stored `preflight_snapshots` loading `--ha-readiness-check`
   already does — no new data source.

**Acceptance criteria (≤ 8):**

1. `compile_failover_plan` never performs I/O — asserted by constructing it
   with a poison resolver that raises if invoked, over every fixture
   (including a `SAFE_TO_FAILOVER` unit, so even the "would proceed" path is
   covered).
2. `plan_compilable` is `False`, with a named `compilation_blocked_reason`,
   for: an unresolved cluster mode, a non-HA cluster mode, ambiguous/missing
   member roles, and every non-ClusterXL unit type (§7) — never a fabricated
   plan.
3. `FailoverPlan.step.preconditions` always carries exactly the seven
   `STOP_CONDITIONS`, verbatim from `compute_ha_readiness`, for every
   `plan_compilable=True` case — a generated matrix test, mirroring `AC-6`'s
   own style.
4. `ReversalStep.intended_postcondition` reproduces `build_plan()`'s
   existing three-way `D-V7b` disclosure unchanged (maintain-current-active
   / switch-to-higher-priority / `UNKNOWN`) — a regression test proves no
   fourth value is ever invented.
5. `DryRunReport.would_proceed` is `True` if and only if `plan_compilable`
   and `readiness_verdict == "SAFE_TO_FAILOVER"` — exhaustive over every
   verdict in `utils.failover.assessment`'s enum.
6. `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE` gains no member;
   `DenyAllAuthorizer` remains the only production `Authorizer` — regression
   test, mirroring the `OP.2.C` precedent named in §6.
7. An architecture-convergence test asserts no module under `utils/operate/`
   or `checkpoint/clusterxl_capability_adapter.py` imports
   `utils.failover_plan` (one-way dependency, §6).
8. `--failover-plan-dry-run` performs no network/credential access — a CLI
   smoke test over a fixture `unified.json`, matching `--ha-readiness-check`'s
   own existing test pattern.

**Independence:** none required — this is the only slice.

**What is deliberately not in this slice:** any Operator Console job type;
any change to `utils/failover/`, `utils/operate/` (beyond the read-only
imports named above), or `checkpoint/clusterxl_capability_adapter.py`;
Palo Alto or VSX/VSLS support (§7); anything responsive to `op_degraded_verdict`
Option B (§8, out of scope by the Product Owner's expected Option A choice).

---

## 10. Cross-references

- `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §1–§10.2 — parent design and
  the `OP.2.0` reconciliation this contract's reused vocabulary depends on.
- `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` —
  `utils.operate.adapter`/`utils.operate.eligibility` typed shapes reused
  read-only (§3, §4).
- `docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` —
  `CP-M1`/`CP-M1-R` approval, the only two primitives this contract may name.
- `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md`,
  `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md` —
  the readiness/preflight evidence this contract consumes and never
  re-derives.
- `project/roadmap.json` → `open_decisions` — `op_degraded_verdict` (§8),
  `op_four_eyes`/`op_emergency_evac`/`op_continuity_tolerance`/
  `op_aa_vsls_scope` (§8.1).
