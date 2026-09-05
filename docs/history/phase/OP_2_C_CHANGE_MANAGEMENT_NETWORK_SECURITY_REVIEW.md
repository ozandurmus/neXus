# OP.2.C — Change-management / network-security review package

## Status

**COMPILED, UNSIGNED — 2026-09-05.** This document is the written
change-management / safety review `FAILOVER_ENGINE_ARCHITECTURE.md` §10
requires "signed off with the network-security leads" before `OP.2.C`'s
first Check Point ClusterXL vendor adapter may ever be wired into a
production `adapter_resolver`. Drafting it is the smallest currently
engineering-actionable step toward `OP.2.C`
(`project/roadmap.json` `now_next.next.notes`,
`op2_c_release_gate_dependency_scoping` session, 2026-09-05). This build:

- Compiles already-frozen facts from `OP.2.0`, `OP.2.1`, `OP.2.1b`, and the
  implemented-but-unwired adapter/session/preflight-provider trio into one
  package ready for sign-off.
- Adds **no code, no schema change, no taxonomy member, no adapter wiring,
  no UI, no device command, no test**.
- Reopens **no** frozen `OP.2.0`/`OP.2.1`/`OP.2.1b` decision. Where those
  documents disagree with a superseded earlier text, this document defers
  to the later, more specific authority exactly as they already do.
- Does **not** sign itself. The two sign-off blocks in §6 are blank; sign-off
  is the product owner's and the network-security leads' own action,
  external to this build and to this repository's engineering process.
- Movement: `DOCS` (deterministic compilation of frozen contracts), `Sonnet
  5, normal reasoning`, per `CLAUDE.md`/`AGENTS.md` routing.

**This document does not make `CLASS 2` reachable, closer to reachable, or
production-ready.** `utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE`
has no member, `utils/operate/authorization.py::DenyAllAuthorizer` remains
the only production `Authorizer` (unconditional `DENY`), and no
`adapter_resolver`/`ActionCoordinator` is constructed anywhere outside
`tests/`. Nothing here changes any of that.

- Design parent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §8, §10,
  §10.1, §10.2.
- Contract parents: `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_
  ARCHITECTURE.md` (FROZEN 2026-09-04); `docs/history/phase/OP_2_1_CP_
  CLUSTERXL_MUTATION_COMMAND_GATE.md` (APPROVED 2026-09-05, docs only);
  `docs/history/phase/OP_2_1B_CP_PILOT_READINESS_POLICY_AMENDMENT.md`
  (IMPLEMENTED 2026-09-05).
- Structural precedent: `docs/history/phase/OP_2_A_B_EXECUTION_FOUNDATION.md`
  (the "what was built / what remains structurally unreachable" record shape
  this document reuses for its evidence matrix).

## 1. The two `APPROVED_FOR_OP2C` primitives and their reversal status

Both are `CLASS_2_OPERATIONAL_STATE_CHANGE`
(`utils/action_taxonomy.py`), Check Point ClusterXL only, non-VSX,
non-Load-Sharing, High Availability (New) mode. Approved
documentation-only by `OP.2.1`; approving them added **no member** to the
taxonomy class and changed nothing reachable.

| ID | Command | Purpose | Reverses | Gate decision |
| --- | --- | --- | --- | --- |
| `CP-M1` | `clusterXL_admin down` (no `-p`) | Graceful, planned ClusterXL failover of exactly one active physical member | — | `APPROVED_FOR_OP2C` (`OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`, "Per-command gate records") |
| `CP-M1-R` | `clusterXL_admin up` (no `-p`) | Explicit failback of the member `CP-M1` acted on | `CP-M1`, via `reverses_action_id` only | `APPROVED_FOR_OP2C` (same document) |

**Reversal status.** `CP-M1-R` is not an automatic rollback and is never
chained to `CP-M1`. Per `OP.2.0` P12 (`FAILOVER_ENGINE_ARCHITECTURE.md`
§10.2 supersedes the design parent's earlier auto-rollback text), reversal
is **a new, separately typed `CLASS 2` action** with its own `action_id`,
its own authorization check, its own fresh same-workflow preflight, its own
confirmation, its own HA-entity lock acquisition, its own single submission
and its own independent verification and audit record. Nothing in either
primitive's gate record, in the adapter implementation described in §2, or
in this document causes `CP-M1-R` to fire automatically after `CP-M1`
succeeds, fails, or produces `OUTCOME_UNKNOWN`.

`CP-M1-R`'s own reversal-preemption disclosure remains `UNKNOWN`: whether
running `up` on the reversed member causes a second impact depends on the
cluster's configured recovery method, which has no confirmed safe
machine-readable read (`D-V7b`, `STILL_UNKNOWN` as a vendor fact — see §4).
`OP.2.0` P12 tolerates this ("where it is `UNKNOWN`, the plan says
`UNKNOWN` and the operator decides") but this document does not resolve it
and does not weaken that disclosure requirement.

`-p` (persistent) variants of both commands are `DEFERRED_NOT_IN_INITIAL_
BATTERY` and are not part of this review — see `OP_2_1_CP_CLUSTERXL_
MUTATION_COMMAND_GATE.md` §"Persistence (`-p`) — deferred".

## 2. Evidence matrix — `OP.2` safety contract (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.1, items 1–9)

Each row restates one item of the design parent's numbered safety contract
and classifies the current, already-implemented, unwired code against it.
Classification vocabulary (fixed, this document introduces no fifth value):

- `SATISFIED_IN_UNWIRED_FOUNDATION` — the property is implemented and unit
  tested in `utils/operate/`/`checkpoint/`, but reachable only through
  `tests/`; no production entry point exercises it.
- `PARTIALLY_SATISFIED` — the mechanism exists and is tested, but a named
  fact or policy input it depends on is still open or unmeasured.
- `OPEN_RELEASE_GATE` — nothing in this repository can satisfy the item
  until a named external or organizational gate closes.
- `EXTERNAL_SIGN_OFF_REQUIRED` — an organizational decision or approval
  outside engineering's authority to make.

| # | Requirement (§10.1) | Implementation evidence | Classification | Notes |
| --- | --- | --- | --- | --- |
| 1 | **Action class.** Failover is `CLASS_2_OPERATIONAL_STATE_CHANGE`; that class has no member today and gaining one is what a future gate authorizes; it is not `CLASS 1`. | `utils/action_taxonomy.py::CLASS_2_OPERATIONAL_STATE_CHANGE` (`permitted=False`, `console_submittable=False`, no member); `tests/test_op2_a_b_execution_foundation.py` source-scans confirm no adapter exists anywhere in the product. | `SATISFIED_IN_UNWIRED_FOUNDATION` | The class's own `why` text is answered by `OP.2.1`'s gate record, not overridden — this row itself keeps the class memberless. |
| 2 | **Identity.** Fail closed when endpoint identity or HA membership is ambiguous; CP identity is physical endpoint + `group_id` mutual. | `checkpoint/clusterxl_preflight_provider.py`: subject/peer `physical_device_identity` tokens resolve only from this run's fresh `ha_local_role` fact; exactly one member must resolve `ACTIVE` and both roles must be known, or the module fails closed to no tokens (`insufficient_member_identity_evidence` in `checkpoint/clusterxl_capability_adapter.py::capability()`); `tests/test_op2_c_cp_clusterxl_real_preflight_wiring.py`. `OP.2.0` P17 (identity law verbatim). | `SATISFIED_IN_UNWIRED_FOUNDATION` | No hostname/address re-resolution anywhere in this path. |
| 3 | **Freshness.** A state-changing action requires operational evidence collected immediately before execution; stale topology or unknown active/standby state is independently disqualifying. | `OP.2.0` P4 (superseding the design parent's numeric-TTL text, §10.2): eligibility consumes only a preflight generated inside the same action workflow — `utils/operate/eligibility.py`, `utils/operate/coordinator.py::run_preflight`; `checkpoint/clusterxl_preflight_provider.py::run_preflight()` calls the approved `checkpoint.preflight_collector.run_cp_preflight` collector fresh, every call. | `SATISFIED_IN_UNWIRED_FOUNDATION` | Structural, same-workflow freshness — no persisted snapshot, no historical readiness, per `OP.2.0` §10.2 reconciliation row. |
| 4 | **Locking.** A per-HA-entity lock, held across preflight → act → verify — a grain the coordinator's pre-existing per-endpoint lock does not provide. | `utils/operate/store.py::ActionRecordStore` (HA-entity lock as record uniqueness, P8); `utils/operate/coordinator.py` (member admission held only during device-contact stages, never across the human confirmation wait); `tests/test_op2_a_b_execution_foundation.py` (concurrent-create-race, member-admission-scoping tests). Closes `project/backlog.json` `ha_entity_operational_lock`. | `SATISFIED_IN_UNWIRED_FOUNDATION` | — |
| 5 | **Confirmation.** Explicit human confirmation of the resolved target after the compiled plan is displayed; no implicit or batched confirmation, no multi-cluster scripting. | `utils/operate/approval_policy.py` (one-confirmation `approval_policy` boundary, digest-bound to one specific proposal, P5); `utils/operate/record.py::compute_proposal_digest`; `tests/test_op2_a_b_execution_foundation.py` (confirm/cancel race tests, exactly one winner). | `PARTIALLY_SATISFIED` | The one-confirmation mechanism itself is implemented and tested. What it does **not** resolve is `op_four_eyes` (§4): whether production requires one confirming operator, two, or a role combination is an undecided deployment/release-policy input to this same `approval_policy` boundary, not a code gap. No UI/CLI surface exists yet for a human to actually confirm anything (no protected entry point, §3). |
| 6 | **Exactly once, no blind retry.** One action per authorised run; a timeout or ambiguous result is `UNKNOWN`, never a reason to re-issue. | `OP.2.0` P6/P7; `utils/operate/coordinator.py` (guarded compare-and-set mutation-boundary commit, exactly one winner, exactly one `execute_once` call); `checkpoint/clusterxl_member_session.py` (`SUBMISSION_NOT_SENT` is the sole pre-boundary escape; everything else is `SUBMISSION_OUTCOME_UNKNOWN`); `tests/test_op2_a_b_execution_foundation.py`, `tests/test_op2_c1_cp_clusterxl_member_session.py`. `OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` reaffirms this per-primitive for `CP-M1`/`CP-M1-R` with no alternate-primitive fallback (no `-p`, no `cphastop`, no retry). | `SATISFIED_IN_UNWIRED_FOUNDATION` | — |
| 7 | **Post-verification.** Inability to verify the post-state is a failure, not a silent success; `UNKNOWN` is first-class alongside `SUCCESS`/`DEGRADED`. | `OP.2.0` P9/P10 (`OUTCOME_UNKNOWN` terminal and quarantining until an authorized, audited acknowledgement); `utils/operate/store.py` (derived quarantine predicate); `checkpoint/clusterxl_member_session.py::read_role()` reuses the already-approved class 0 battery (`CP-A3`/`CP-A5`) for postcondition observation; `tests/test_op2_a_b_execution_foundation.py` (`FAILED_NO_CHANGE` unreachability while `settle_observation` is unknown). | `PARTIALLY_SATISFIED` | The state machine and quarantine mechanism are implemented and tested. `settle_observation` (how long after submission the postcondition is stably observable) is `UNKNOWN` for both `CP-M1` and `CP-M1-R` — `OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` records this as unmeasured by any source in its evidence set; only `SUCCEEDED` or `OUTCOME_UNKNOWN` are reachable outcomes until the `OP.2.D` real-environment pilot measures it. This is a real-environment validation gap, not a code gap. |
| 8 | **Audit.** Immutable record: actor, target, pre-state, action class, exact supported operation, result, post-state, timestamps, evidence references. | `utils/operate/record.py::ActionRecord` (durable, append-only shape, P13); `utils/evidence_backend.py` (filesystem-default/opt-in-Postgres backend, no orphan-sweep-to-`failed` behaviour); `OP.2.0` privacy invariants (no credential, token, management address, or command text ever stored — command text is two hardcoded literals inside `checkpoint/clusterxl_member_session.py`, never surfaced above the adapter boundary, per P18); `tests/test_op2_a_b_execution_foundation.py`. | `SATISFIED_IN_UNWIRED_FOUNDATION` | Sensitive-output and safe-retained-field discipline confirmed per-primitive in `OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md` ("Sensitive output" / "Safe retained fields" rows for `CP-M1`/`CP-M1-R`). |
| 9 | **No generic cross-vendor primitive.** Explicit per-vendor capability adapters; one vendor proven before any abstraction is generalized. | `utils/operate/adapter.py` (typed `VendorCapabilityAdapter` boundary, no generic mutation primitive, P11); `checkpoint/clusterxl_capability_adapter.py::CPClusterXLCapabilityAdapter` (`capability()`/`build_plan()`/`check_precondition()`/`execute_once()`/`observe_postcondition()` for exactly `CP-M1`/`CP-M1-R`); no second vendor adapter exists anywhere in the repository (`OP.2.0` P16 — Check Point ClusterXL first, one vendor); `tests/test_op2_c_cp_clusterxl_adapter.py`. | `SATISFIED_IN_UNWIRED_FOUNDATION` | — |

**Reading this matrix.** Six of nine items are fully implemented and unit
tested, reachable only through `tests/` because no production entry point
exists (§3). Two items (5, 7) are implemented but depend on inputs this
document explicitly does not decide — `op_four_eyes` and `settle_
observation`/real-environment measurement, respectively. No item in this
matrix is classified `OPEN_RELEASE_GATE` or `EXTERNAL_SIGN_OFF_REQUIRED`;
those classifications apply to the prerequisites in §3, which sit outside
the §10.1 safety-contract list itself.

## 3. Still-open prerequisites — explicitly NOT closed by this document

None of the following is touched, narrowed, or brought closer to closure by
this document. Each keeps the exact classification already recorded in
`CURRENT_STATE.md` "Open blockers" and `project/roadmap.json`
`now_next.next.notes`.

| Prerequisite | Classification | Current state |
| --- | --- | --- |
| `DEPLOY.1A` OIDC boundary + RBAC `OPERATE` role | `OPEN_RELEASE_GATE` | `utils/operate/authorization.py::DenyAllAuthorizer` is the only production `Authorizer`, unconditional `DENY` for every actor/action/entity. Externally blocked on `DEPLOY.1` server availability (`op2_c_release_gate_dependency_scoping`, 2026-09-05). No `PermitAllAuthorizer` exists outside `tests/`. |
| Production Check Point SSH host-key trust hardening (`cp_production_ssh_host_key_trust_hardening`) | `OPEN_RELEASE_GATE` | `project/backlog.json` records its own target as "production container/pod runtime hardening (post-`DEPLOY.1`)" — it shares `DEPLOY.1`'s external server-availability blocker rather than being independently closable. |
| A protected CLI/console entry point constructing a live `ActionCoordinator` with a real `adapter_resolver` (`RealClusterXLMemberSession` as `session_resolver`; `ClusterXLPreflightProvider`/`ClusterXLReadinessEligibilityEvaluator` as `preflight_provider`/`eligibility_evaluator`) | `OPEN_RELEASE_GATE` | No such entry point exists anywhere in the repository outside `tests/`. Dependent on both rows above landing first — a live resolver in a genuinely production context needs both the auth boundary and the hardened transport in place (`op2_c_release_gate_dependency_scoping` dependency-order finding, 2026-09-05). This document does not design one. |
| **This review's own sign-off** | `EXTERNAL_SIGN_OFF_REQUIRED` | Both blocks in §6 are blank. Signing is the product owner's and the network-security leads' action, external to this engineering process; this document being drafted is not equivalent to it being signed. |
| `D-V7b` (CP configured recovery/preemption — vendor fact) | `EXTERNAL_SIGN_OFF_REQUIRED` (vendor evidence, not organizational) | `STILL_UNKNOWN`. No confirmed safe machine-readable read exists after exhaustive official-source research (`OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`). Its readiness-roll-up *role* is decided (advisory-exempt, `OP.2.1b`) but the underlying vendor question is unchanged and unresolved — not reopened or re-argued here. |
| `D-F3` (flap/failover threshold policy) | Decided at the readiness layer only (advisory-exempt, `OP.2.1b`, 2026-09-05) — carried forward here as context, not reopened. | No numeric threshold was invented; the product owner declined to fabricate one against evidence that cannot support one. |
| `OP.2.D` real-environment pilot (measures `settle_observation`) | `OPEN_RELEASE_GATE` | Not started; depends on every row above. |

Per `OP.2.0`'s own definition-of-done item 6 and the frozen contract's
"Explicit blockers" §F ("Blocked on deployment / infrastructure"), these
rows were already open before this document existed. This document adds
nothing to any of them and removes nothing from any of them — it removes
exactly one fact from `OP.2.C`'s blocker list going forward: "the
change-management review package has no drafted artifact."

## 4. Unresolved deployment/release-policy decisions carried into this review, not decided here

Per `project/roadmap.json` `open_decisions`, the following remain `status:
"open"` in repository authority. This document records them as inputs the
eventual sign-off should be aware of; it does not resolve either, per the
build task's explicit instruction not to decide them:

- **`op_four_eyes`** — whether production requires one confirming operator,
  two, or a role combination for the `approval_policy` boundary (§2, row 5).
  `decide_by`: "deployment/release policy before the `OP.2.D` pilot (not
  `OP.2.0` architecture freeze)." The architecture provides one
  `approval_policy` boundary whose inputs (second approver, role
  combinations, maintenance window, change reference) are shaped for this
  decision; no generic quorum framework is built. Initial implementation
  ships with one confirmation by the requesting operator and no
  configuration surface (`OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`
  P5).
- **`op_continuity_tolerance`** — connection/session continuity tolerance
  defaults, and whether a continuity observation ever becomes
  verdict-bearing (§2, row 7 is adjacent territory but distinct — this
  decision concerns post-action *continuity* observation, not postcondition
  `settle_observation` timing). `decide_by`: "`OP.2.D` pilot calibration,
  before any continuity observation becomes verdict-bearing (not `OP.2.0`
  architecture freeze)." Until decided, continuity observations are
  recorded but not independently verdict-bearing, and `SUCCEEDED_WITH_
  WARNINGS` was removed from the `OP.2.0` state set
  (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 reconciliation row; `OP_2_0_
  CONTROLLED_HA_OPERATION_ARCHITECTURE.md` §"Post-action verification").

Neither decision blocks the safety-contract evidence matrix in §2 from
reaching the classifications recorded there — both are deployment/release
policy inputs to an already-implemented boundary, not missing
implementation.

## 5. What this document is, and is not

- **Is:** a compilation of already-frozen `OP.2.0`/`OP.2.1`/`OP.2.1b` facts
  and already-implemented (unwired) code evidence, assembled into the one
  package `FAILOVER_ENGINE_ARCHITECTURE.md` §10 names as an `OP.2` hard
  prerequisite, ready for product-owner and network-security-lead review.
- **Is not:** a sign-off. A code change. An `Authorizer` implementation. A
  taxonomy member. An `adapter_resolver`/`ActionCoordinator` wiring change.
  A UI change. A device command. A re-litigation of `D-V7b`, `D-F3`,
  `op_four_eyes`, `op_continuity_tolerance`, `op_emergency_evac`,
  `op_aa_vsls_scope`, or any other decision `OP.2.0`/`OP.2.1`/`OP.2.1b`
  already resolved, reclassified, or explicitly left open.
- **Does not claim:** that `CLASS 2` is reachable, that `OP.2.C` is closer
  to production-ready than `DenyAllAuthorizer` and the memberless taxonomy
  class already make it, or that any row in §3 is any less open than it was
  before this document existed.

## 6. Sign-off

Sign-off below is required before any `adapter_resolver` construction may
reference `checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter`,
`checkpoint.clusterxl_member_session.RealClusterXLMemberSession`, or
`checkpoint.clusterxl_preflight_provider.ClusterXLPreflightProvider`/
`ClusterXLReadinessEligibilityEvaluator` outside `tests/`, and is itself only
one of the four still-open prerequisites named in §3 — signing this
document alone does not unblock `DEPLOY.1A`, SSH trust hardening, or the
protected entry point.

**Product Owner**

| Field | Value |
| --- | --- |
| Name | _(unsigned)_ |
| Date | _(unsigned)_ |
| Decision | _(unsigned — `APPROVED` / `APPROVED_WITH_CONDITIONS` / `REJECTED`)_ |
| Conditions (if any) | _(unsigned)_ |

**Network Security Lead**

| Field | Value |
| --- | --- |
| Name | _(unsigned)_ |
| Date | _(unsigned)_ |
| Decision | _(unsigned — `APPROVED` / `APPROVED_WITH_CONDITIONS` / `REJECTED`)_ |
| Conditions (if any) | _(unsigned)_ |

## Validation / merge gate

Documentation only, no code or test changes:

- Repository privacy gate (`python3 main.py --repository-privacy-check`).
- `tests/test_architecture_convergence.py` (project-state cross-authority
  consistency) — unaffected, re-run to confirm.
- `python3 scripts/build_history_index.py --check` after `project/
  build_history.json` and `project/roadmap.json` are updated to record this
  build.
- `git diff --check`.
- Full regression **not required** — no product code changes.

## Next movement / reasoning tier

Signing this document is external to engineering and is not scheduled by
this build. The next engineering-actionable items, independent of
sign-off, remain exactly as `OP.2.1b` and `op2_c_release_gate_dependency_
scoping` already recorded: `DEPLOY.1A`/SSH-trust-hardening are both
externally blocked on `DEPLOY.1` server availability; `op0b_0_close_d_v3a_
d_v7b_pre_class2`'s `D-V7b` half (vendor-fact closure, `Sonnet 5, extended
thinking (high)`) is independent of this review and of the deployment
gates. `OP.2.C` itself remains blocked on every row in §3 — this document
closes exactly one of them (the drafted-artifact gap), not the sign-off
itself.
