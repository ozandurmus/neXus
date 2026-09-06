# Capability-state vocabulary and presentation contract (`M3`)

## Status

**DRAFT — DO NOT FREEZE. NOT PRODUCT OWNER APPROVED. AUTHORIZES NO
IMPLEMENTATION.**

Prepared on branch `claude/capability-state-vocabulary-cube5f` from verified
`origin/main` head `d363b179fe8f552544402e070f5908ec10df2115` (PR #90,
`DEV.TEST.1`).

Per `AGENTS.md` "Contract-status law" this document may guide investigation.
It must **not** be cited as approving a name, a schema, a projection rule or a
presentation rule, and its `UNKNOWN`s must not be silently reinterpreted as
decided. It becomes implementation authority only when a Product Owner review
closes §11 and changes this status line to `FROZEN`.

**Movement.** `M3` — *capability-state vocabulary + presentation contract* —
in the `M1`…`M14` sequence frozen by
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12/§12.1.
Movement type: `ARCHITECTURE` (contract + evidence). No runtime, UI, CSS,
JavaScript, template, adapter, registry, storage, enrollment, authorization,
job or report-generation change is made or authorized here.

**Parent contracts (both FROZEN, 2026-09-05, reviewed head `ba56d2b`).**

- `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` — the presentation half:
  §5.4, §6.5, §7, §8, §8.1, §9, §12, §13, `PO-NAV-6`, `PO-NAV-7`, `PO-NAV-8`,
  `AC-NAV-*`, `AC-WS-*`, `AC-DIF-*`, `AC-SH-*`.
- `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` — the runtime
  half: §9.2 amendments `A5`/`A6`, §10, §12/§12.1, §13.2.

This document does not restate them. Where it appears to disagree with either,
**they win** and the disagreement is a defect in this draft — except for the
one contradiction *inside* the navigation contract that §2.5 reports and §11
puts to the Product Owner.

## Decision grades used throughout

| Grade | Meaning |
| --- | --- |
| **VERIFIED** | read directly from repository source, tests or a frozen contract this session; the citation is given |
| **FROZEN** | already decided by a frozen contract or `AGENTS.md`; carried, never reopened |
| **PROPOSED** | this document's recommendation; requires Product Owner approval |
| **OPEN** | a genuine Product Owner decision, listed in §11 |
| **UNKNOWN** | not established by repository evidence; stays `UNKNOWN` (`AGENTS.md` UNKNOWN law) |

---

## 0. How to read this document

§2 is the evidence base — every later claim traces to it. §3–§8 are the
proposal. §9 is the falsifiable acceptance criteria a later movement
implements. §10 records the advisory council. §11 is the smallest set of
decisions the Product Owner must actually make.

A reader who only wants the answer reads §4.1 (the composition model), §5.1
(the precedence ladder) and §11 (what is still open).

---

## 1. What `M3` owes, and what it may not touch

`M3`'s mandate, VERIFIED from `project/roadmap.json` `now_next.next` and
`LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12 (`M3` row) / §12.1:

1. map the ten UX capability-state semantics of navigation contract §8 onto
   canonical states;
2. settle the two `PO-NAV-7` concepts **in their correct owning domains**;
3. own the `PO-NAV-6` colour/label contract;
4. deliverable: *a contract doc + `docs/ARCHITECTURE.md`*; validation:
   *state/doc checks*; non-goals: *no UI, no payload*.

Hard constraint, FROZEN (`PO-NAV-7`, §12.1 `M3` row): the job lifecycle
vocabulary `queued` / `running` / `succeeded` / `failed` / `blocked` /
`skipped` **gains no member**, and the schedule/capability-policy concept
never joins it.

Also carried, FROZEN and not reopened here: the six-root baseline and its
order (`PO-NAV-8`, `AC-NAV-1`); Recovery reserved (`PO-NAV-2`, `AC-NAV-2`);
Jobs under Operations (`AC-NAV-3`); root-set stability (D-NAV11, `AC-NAV-4`);
no placeholder entries (D-NAV6b, `AC-NAV-5`); no simulated authorization
(D-NAV9, `AC-NAV-8`); the entity-workspace model (§6, `AC-WS-1`…`AC-WS-11`);
the shell boundary (`AC-SH-1`…`AC-SH-4`).

---

## 2. Evidence inventory

Everything in this section is VERIFIED against source at the baseline head.
File and symbol names are given so each row is independently re-checkable.

### 2.1 Canonical state vocabularies located, and their owners

| # | Vocabulary | Values | Owning module | Scope |
| --- | --- | --- | --- | --- |
| V1 | **Console job lifecycle** | `queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped` | `console/jobs.py` (`JobRecord.state`, `TERMINAL_STATES`), `console/runner.py` | job record |
| V2 | **Coordinator job status** | `pending`, `running`, `completed`, `failed`, `cancelled`, `coalesced`, `orphaned` | `utils/coordinator_backend.py::JobStatus` | admission/coordination record |
| V3 | **Coordinator decision** | `admitted`, `coalesced`, `rejected_budget`, `rejected_locked` | `utils/coordinator_backend.py::CoordinatorDecision` | one admission attempt |
| V4 | **Provenance** | `manual`, `scheduled`, `console` (`event` reserved) | `utils/coordinator_backend.py::Provenance` | run |
| V5 | **Discovery lifecycle** | `DISCOVERED`, `VALIDATED`, `STABLE`, `EXCLUDED`, `REMOVED` | `utils/discovery_lifecycle.py::LifecycleState` | discovered entity |
| V6 | **Lifecycle transition reason** | 10 codes incl. `RUNTIME_POLICY_EXCLUDE`, `IDENTITY_FAILURE` | `utils/discovery_lifecycle.py::TransitionReason` | transition |
| V7 | **Shell type** | `expert`, `direct_clish`, `unknown` | `utils/capability_registry.py::ShellType` | device |
| V8 | **Collection mode** | 7 incl. `deferred_standby`, `deferred_lifecycle`, `unknown` | `utils/capability_registry.py::CollectionMode` | device plan |
| V9 | **Plan reason code** | 11 incl. `INSUFFICIENT_EVIDENCE`, `UNKNOWN_SHELL`, `LIFECYCLE_EXCLUDED` | `utils/capability_registry.py::PlanReasonCode` | device plan |
| V10 | **Device Registry lifecycle** | `ENROLLED_UNVERIFIED`, `DISABLED`, `RETIRED`, `CONTACT_VERIFIED`, `OBSERVED` (only the first two reachable) | `utils/device_registry.py` | registered device |
| V11 | **Inventory/evidence data state** | `live`, `last_known_good`, `partial`, `no_data` | `utils/snapshot.py::_status` | per-entity evidence |
| V12 | **Configuration difference classification** | `DIFFERENCE_OBSERVED`, `LOCAL_OVERRIDE`, `EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, `MEMBER_SPECIFIC`, `PROVENANCE_UNVERIFIED`, `IDENTITY_TRANSLATION_REQUIRED`, `INSUFFICIENT_EVIDENCE` | `utils/config_ui.py`; producers in `configuration/pan_setting_alignment.py`, `checkpoint_config_alignment.py`, `pan_semantic_policy.py` | setting/row |
| V13 | **Config artifact availability** | `available`, `unavailable`, `not_configured` | `utils/config_ui.py` | artifact |
| V14 | **Config history status** | `available`, `insufficient_evidence`, `unavailable` | `utils/config_history.py` | timeline |
| V15 | **HA readiness verdict** | `SAFE_TO_FAILOVER`*, `DEGRADED_PROCEED_WITH_RISK`*, `UNSAFE_DO_NOT_FAILOVER`, `INSUFFICIENT_EVIDENCE`, `NOT_A_FAILOVER_UNIT` (*unreachable by construction) | `utils/failover/assessment.py` | HA unit |
| V16 | **HA check status** | `PASS`, `FAIL`, `INSUFFICIENT_EVIDENCE` | `utils/failover/assessment.py` | one check |
| V17 | **Preflight fact state** | `known`, `unknown`, `not_applicable`, `unsupported`, `collection_failed` | `utils/failover/preflight_model.py::FactState` | one evidence fact |
| V18 | **Preflight read outcome** | `success`, `failed`, `unsupported`, `capability_gap`, `identity_mismatch` | `utils/failover/preflight_model.py::Outcome` | one read |
| V19 | **`OP.2` action state** | `CREATED`…`OUTCOME_UNKNOWN` (4 non-terminal, 6 terminal) | `utils/operate/states.py::ActionState` | class 2 action |
| V20 | **Precondition / submission outcome** | `HOLDS`/`CHANGED`/`UNKNOWN`; `NOT_SENT`/`UNKNOWN` | `utils/operate/adapter.py` | class 2 action |
| V21 | **Adapter capability support** | `Capability.supported: bool` + `reason` | `utils/operate/adapter.py` | (entity kind, action type) |
| V22 | **Authorization decision** | `permitted: bool` + `reason_code`; today unconditional `deny("authorization_not_configured")` | `utils/operate/authorization.py` | actor/action/entity |
| V23 | **Action taxonomy** | `CLASS_0_READ`…`CLASS_4_POLICY_DEPLOYMENT` + `console_refusal()` | `utils/action_taxonomy.py` | job/action type |
| V24 | **Compliance control status** | `PASS`, `FINDING`, `UNKNOWN`, `NOT_APPLICABLE`, `PLANNED`, `WAIVED` | `utils/compliance_posture.py::STATUS_VALUES` | control × subject |
| V25 | **Restore readiness state** | `READY`, `STALE`, `PARTIAL`, `UNPROTECTED`, `UNKNOWN` | `utils/restore_readiness.py` | entity |
| V26 | **Crypto posture status** | `INSUFFICIENT_EVIDENCE` + rule verdicts; PQC `LIKELY`/`NO`/`UNKNOWN` | `utils/crypto_posture.py` | crypto fact |
| V27 | **Recovery validation applicability** | `NOT_APPLICABLE` and siblings | `utils/recovery_validation.py` | artifact |
| V28 | **UI tone mapping** | `danger`, `warning`, `success`, `info`, `muted`, `neutral` | `static/app_core.js::statusTone()` | any chip |
| V29 | **Navigation availability** | `navigationEntryAvailable()` → DOM panel presence; `navigationAuthorizationContext()` → `model: "none"` | `static/navigation_ui.js` | nav entry |
| V30 | **Contextual action availability** | `available: boolean` + `unavailable_reason` | `static/navigation_ui.js::NAVIGATION_CONTEXTUAL_ACTIONS` | action |

**Finding E1.** There are at least **thirty** state vocabularies across nine
subsystems. None of them is a *capability* state. `PCP.0` §8's capability
projection — the layer that would produce one — is architecture, not code:
`utils/capability_registry.py` produces a *collection plan*, not a capability
projection, and `utils/operate/adapter.py::Capability.supported` is a
class-2-scoped boolean. **VERIFIED: no capability-state vocabulary exists in
the repository today.** `M3` is therefore defining a new vocabulary, not
renaming an existing one, and every value it defines must name the producer
that will populate it (§3.3, "evidence today" column).

### 2.2 Duplicated terms with different meanings

| Term | Meaning A | Meaning B | Meaning C |
| --- | --- | --- | --- |
| **`UNKNOWN`** | evidence absent for one fact (V17, V11-adjacent) | a compliance control could not be concluded and **counts in the alignment denominator** (V24, `_DENOMINATOR_STATUSES`) | a class-2 precondition/outcome could not be established (V19/V20) |
| **`NOT_APPLICABLE`** | this fact does not apply to this context (V17) | this control does not apply to this vendor (V24) | this recovery artifact class does not apply (V27) |
| **`failed` / `FAILED`** | a console job's terminal state (V1) | a coordinator record's terminal state (V2) | a read attempt's outcome (V18) |
| **`blocked`** | a console job **record state**: refused at run time (V1, `console/runner.py`) | a **job type property** on `GET /api/job-types`: `"blocked": console_refusal(...) is not None` — a static taxonomy fact about the *type*, not a runtime outcome (`console/app.py`) | a roadmap/backlog delivery status |
| **`available`** | a nav entry's panel exists in this shell (V29) | a contextual action has a backend contract (V30) | a config artifact has evidence (V13/V14); also `crypto_facts`, `completeness`, `support_bundle` each use it for "this projection parsed/was produced" |
| **disabled / excluded** | `LifecycleState.EXCLUDED` — runtime **polling policy** suppresses collection for a discovered entity (V5/V6) | Device Registry `DISABLED` — the operator retired a **device** from operation (V10) | the scheduler's own **default-disabled** posture (`--scheduler-once`, `interval_minutes >= 10`) |
| **`STALE`** | restore-readiness entity state (V25) | `PROVENANCE_UNVERIFIED` / `last_known_good` evidence age (V11/V12) | — |
| **`PARTIAL`** | restore-readiness entity state (V25) | snapshot data state (V11) | compliance coverage marker |

**Finding E2.** `available` and `blocked` are the two most dangerous. `blocked`
already carries a *capability/taxonomy* meaning and a *job lifecycle* meaning
in the **same subsystem**, one HTTP route apart — precisely the conflation
`PO-NAV-7` forbids. Any new capability state named `BLOCKED` would make it
three.

### 2.3 Different terms for the same meaning

| Meaning | Terms in use |
| --- | --- |
| a job is waiting to start | `queued` (V1) **and** `pending` (V2) |
| a job finished successfully | `succeeded` (V1) **and** `completed` (V2) |
| evidence is old but usable | `last_known_good` (V11), `PROVENANCE_UNVERIFIED` (V12), `STALE` (V25) |
| we cannot conclude | `UNKNOWN` (V17/V24), `INSUFFICIENT_EVIDENCE` (V9/V12/V15/V16/V26), `unknown` (V7/V8) |
| the vendor/platform cannot do this | `UNSUPPORTED` (V17/V18), `Capability.supported=False` (V21), `UNKNOWN_SHELL` (V9) |

**Finding E3.** V1 and V2 are **two live job-lifecycle vocabularies**, and the
UI surfaces the second: `utils/discovery_capability_ui.py::JOB_STATUS_LABELS`
labels `pending`/`running`/`completed`/`failed`/`cancelled`/`coalesced` — the
coordinator's set — while the console renders the `queued`…`skipped` set.
`PO-NAV-7`'s protection names only V1. This is reported, **not fixed here**:
reconciling them is a job-plane change and `M3`'s non-goals forbid it. It is
`OPEN` decision **PO-M3-4** (§11).

### 2.4 Conflations found in shipped logic

| # | Conflation | Evidence | Severity |
| --- | --- | --- | --- |
| C1 | **member-specific evidence presented as warning/fault** | `static/inventory_ui.js` paints every member-scoped row `.difference-row` (l.1916, l.2035) and `.scope-chip.diff` (l.1924, l.2042) and `.divergence-badge` (l.1763-4); `static/style.css` binds all three to `--warning` (l.393-6, l.648-9, l.863-6). Meanwhile `static/app_core.js::statusTone()` maps `MEMBER_SPECIFIC` → `info` (l.170). Two planes, one fact, opposite tone | **the defect `PO-NAV-6` exists to fix** |
| C2 | **unsupported is indistinguishable from an unrecognised value** | `statusTone()` has no branch for `UNSUPPORTED`, `NOT_APPLICABLE` or `NOT_CONFIGURED`; each falls through to `return "neutral"` — the same tone an unmapped or misspelled classification receives | high: a product-integrity failure of the kind navigation contract §2.8 names |
| C3 | **unknown depresses posture like a finding** | `utils/compliance_posture.py`: `_ALIGNED_STATUSES = {"PASS"}` but `_DENOMINATOR_STATUSES = {"PASS","FINDING","UNKNOWN","PLANNED"}` — missing evidence lowers the alignment percentage exactly as a real gap does. `static/compliance_ui.js` does separate them by tone (`FINDING` → `danger`, `UNKNOWN` → `muted`, l.95-6) and by copy | medium; **in the compliance contract's domain, not `M3`'s** — reported, not changed |
| C4 | **not enrolled has no representation at all** | `utils/device_registry.py` has no state for "present in evidence, absent from the registry" or its reverse; no reconciliation projection exists in `utils/` | the `A6` gap `M3` is chartered to close in contract |
| C5 | **policy-disabled has no representation at all** | no per-(entity, capability) schedule state exists; the three existing "disabled" facts (E2) are a *discovery-polling* policy, a *device* lifecycle and a *global scheduler* posture. None of them means "this capability's schedule is off for this device" | the `A5` gap `M3` is chartered to close in contract |
| C6 | **capability vs authorization** | today **not** conflated: `navigationAuthorizationContext()` returns `model: "none"` and nothing in the navigation path reads a role. But `available` already means three different things (E2), and `console/app.py`'s job-type `blocked` mixes a taxonomy refusal into a payload beside runtime job state | latent; §5.3 exists to keep it latent |
| C7 | **job lifecycle vs capability state** | `GET /api/job-types` returns `blocked`/`blocked_reason` (a static class-refusal fact) in the same object family the UI renders job records from | latent, and the direct precedent for `PO-NAV-7`'s rule |
| C8 | **not enrolled vs unreachable** | no code conflates them today because C4 means neither concept is projected. The risk is entirely forward-looking: a first-contact failure must never write a registry state | prevented by §8.1 |

`statusTone()` does keep `UNKNOWN`/`INSUFFICIENT_EVIDENCE` (`muted`) apart from
`FAILED`/`COLLECTION_FAILURE` (`danger`) — **unknown is not conflated with
fault** in that function. VERIFIED, and preserved by `AC-CS-27`.

### 2.5 One contradiction inside the frozen navigation contract

Reported rather than reconciled, per `AGENTS.md` "Authority hierarchy".

`NAVIGATION_INFORMATION_ARCHITECTURE.md` says two different things about a
structurally inapplicable tab:

- **§8** (capability-state presentation matrix), row *Not applicable*: "tab may
  be **omitted for that entity type** or shown as N/A — never shown broken";
  and **§8.1**: "**Omit a tab for an entity type** | not applicable (e.g. HA
  tab on a standalone device)".
- **§6.5 / D-NAV13**: "Revision 1 left this ambiguous by allowing an
  inapplicable tab to be 'either omitted or shown'. **That ambiguity is
  closed.**" — keep the tab **visible and selectable**, render
  `NOT_APPLICABLE`, name the supporting entity types, render no enabled action.
- **`AC-WS-7`** restates §6.5. **`AC-WS-8`**: "A tab is **omitted only** when
  its product surface does not exist in that build/shell (a P1 failure) —
  **never** because of entity applicability or evidence state."

§8/§8.1 is residual revision-1 text that §6.5 explicitly closed; the acceptance
criteria — the document's own testable statements — agree with §6.5. **This
draft therefore proposes that §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` govern and that
§8/§8.1's "may be omitted for that entity type" is superseded**, and builds §5
on that reading. It is `OPEN` decision **PO-M3-1** (§11) and the Product Owner,
not this document, closes it. Every rule in §5–§9 that depends on it is marked
`[PO-M3-1]`.

### 2.6 Evidence grading of every claim below

- **VERIFIED** rows in §2 are repository facts at head `d363b179`.
- §3–§8 are **PROPOSED** unless a row says FROZEN.
- §7's vendor rows are FROZEN where they restate `AGENTS.md` or an existing
  frozen contract, and **UNKNOWN** where the vendor question is genuinely open
  (`D-V3a`, `D-V7b`, `D-V8` remain `STILL_UNKNOWN` per `CURRENT_STATE.md` and
  `project/roadmap.json` `open_decisions`; this document does not close them
  and must not be read as narrowing them).
- No claim here rests on a prior chat transcript.

---

## 3. State dimensions and ownership

### 3.1 The seven owned dimensions

Eligibility is a **conjunction of independent facts with independent owners**.
A dimension answers exactly one question, and no dimension may be computed from
another except where §8.1 explicitly permits it.

`D1`…`D4` restate and name navigation contract §7's `P1`…`P4`; `D5`…`D7` are
the additional dimensions §7.3 identified but did not name.

| id | Dimension | Question it answers | `P`-mapping |
| --- | --- | --- | --- |
| **D1** | **Surface eligibility** | does this build/shell ship this surface at all? | `P1` |
| **D2** | **Entity applicability** | does this function apply to this logical-entity *type*? | `P2` |
| **D3** | **Vendor/platform capability support** | can this vendor/platform do this, per positive evidence? | part of `P3` |
| **D4** | **Registry ↔ evidence reconciliation** | is this entity enrolled, observed, both, neither, or disabled? | part of `P3`; `A6` |
| **D5** | **Capability policy (schedule)** | is this capability's collection intentionally on, off, or unscheduled for this entity? | part of `P3`; `A5` |
| **D6** | **Evidence state** | how current, complete and provenanced is the evidence? | part of `P3` |
| **D7** | **Authorization** | is this actor permitted to perform this action? | `P4` |

### 3.2 The three bound-but-unchanged vocabularies

`M3` **binds** to these and changes none of them.

| id | Vocabulary | Owner | `M3`'s only obligation |
| --- | --- | --- | --- |
| **X1** | Console job lifecycle (V1) | `console/jobs.py`, `CON.2` | gain no member; share no token with §4 |
| **X2** | `OP.2` action state (V19) | `utils/operate/states.py`, `OP.2.0` frozen | share no token with §4 |
| **X3** | Action taxonomy classes (V23) | `utils/action_taxonomy.py` | remain the sole authority on what may execute |

### 3.3 Ownership matrix

For each dimension: authoritative owner, allowed values, exact meaning,
evidence required, persistence, scope, what it may affect, what it must never
control.

#### D1 — Surface eligibility

| Field | Contract |
| --- | --- |
| **Owner** | the navigation model + the shipped-contract set (`static/navigation_ui.js`; `console/registry.py` for action contracts) |
| **Values** | `SURFACE_PRESENT`, `SURFACE_ABSENT` |
| **Meaning** | all three of navigation contract §7.1 hold: a declared shipped read/action contract exists; this shell is permitted to expose it; the surface is actually present |
| **Evidence** | the build itself (a payload builder, an API route, a registry job type) plus the shell integrity check |
| **Persisted?** | derived, per render; never persisted |
| **Scope** | **build- and shell-scoped**. Never entity-, member-, evidence- or actor-scoped |
| **May affect** | visibility — and it is the **only** dimension that may |
| **Must never control** | anything conditioned on the selected entity, evidence, policy, enrollment or authorization. It must never be reused as a permission proxy (D-NAV9) |

#### D2 — Entity applicability

| Field | Contract |
| --- | --- |
| **Owner** | the logical-entity type model (navigation contract §5.2) |
| **Values** | `APPLICABLE`, `NOT_APPLICABLE`, `APPLICABILITY_UNKNOWN` |
| **Meaning** | a **type** question, answered from the entity model, never from evidence: a standalone firewall has no HA unit; a PAN device has no VSID |
| **Evidence** | the resolved logical-entity type. `APPLICABILITY_UNKNOWN` when the type itself is not established (e.g. a PAN pair whose `B₂` corroboration is NOT ESTABLISHED — `AC-WS-5`) |
| **Persisted?** | derived from the entity model |
| **Scope** | **entity-type-scoped**; identical for every entity of that type, which is what makes it stable |
| **May affect** | content, enablement, tone, explanatory copy; and — **only if `PO-M3-1` decides so** — tab omission consistently for a type. This draft proposes it may **not** |
| **Must never control** | root/module visibility (`AC-WS-10`); it must never be derived from evidence or from a collection outcome |

#### D3 — Vendor/platform capability support

| Field | Contract |
| --- | --- |
| **Owner** | the capability projection (`PCP.0` §8; producer arrives at `M10`). Inputs are existing: `utils/capability_registry.py`, CP platform classification, the PAN identity gate, `utils/failover` unit derivation, `utils/operate/adapter.py::Capability` |
| **Values** | `SUPPORTED`, `UNSUPPORTED(vendor_reason)`, `SUPPORT_UNKNOWN` |
| **Meaning** | can this capability run against this vendor/platform/entity kind, given implemented support **and** positive vendor evidence |
| **Evidence** | `SUPPORTED` and `UNSUPPORTED` each require **positive** evidence: a frozen vendor contract, official vendor documentation, or real-environment corroboration (`AGENTS.md` vendor-semantics law). Anything else is `SUPPORT_UNKNOWN` |
| **Persisted?** | derived; may be cached with its evidence timestamp, never persisted as standalone truth |
| **Scope** | (vendor, platform, entity kind, capability) — **device/entity-scoped** |
| **May affect** | enablement, tone, explanatory copy, action availability |
| **Must never control** | visibility of a root, module or tab; and it must **never** be inferred from menu presence, reachability, enrollment, a successful collection of a *different* capability, a vendor hint string, or a collection failure (§7.3) |
| **Evidence today** | **no producer**. `Capability.supported` exists only for class 2. `M10` builds this |

#### D4 — Registry ↔ evidence reconciliation *(the `A6` home)*

| Field | Contract |
| --- | --- |
| **Owner** | the **registry/evidence reconciliation projection** — a projection over `utils/device_registry.py` and the merged evidence model. **Not** a device capability state (`PO-NAV-7`) |
| **Values** | `RECONCILED`, `EVIDENCE_ONLY`, `REGISTRY_ONLY`, `REGISTRY_DISABLED`, `RECONCILIATION_UNKNOWN` |
| **Meaning** | `RECONCILED` — enrolled **and** observed. `EVIDENCE_ONLY` — observed in evidence, absent from the registry (`PO-NAV-7`'s "not enrolled"). `REGISTRY_ONLY` — enrolled, never observed. `REGISTRY_DISABLED` — an enrolled row the operator disabled (existing V10 `DISABLED`). `RECONCILIATION_UNKNOWN` — the registry or the evidence side could not be read |
| **Evidence** | the registry file and the merged evidence model, joined on the canonical id — never on a hostname, label or inferred ordinal (`AGENTS.md` presentation-identity law) |
| **Persisted?** | the registry row is persisted (existing, unchanged); the **reconciliation is derived** and persists nothing new |
| **Scope** | **entity-scoped** |
| **May affect** | explanatory copy; offering the enrollment affordance where `M9`'s gate permits; and job admission refusal for `REGISTRY_DISABLED` (`AC-ST-4`, existing) |
| **Must never control** | visibility of any root, module or tab; and it must **never** be written or changed by a reachability result or a collection failure (C8, §8.1) |
| **Evidence today** | registry side VERIFIED (`PCP.1`, shipped); the join is **not implemented**. `M10`/`M9` era |

Each value earns its place by a distinct operator consequence:
`EVIDENCE_ONLY` → offer enrollment; `REGISTRY_ONLY` → run first contact;
`REGISTRY_DISABLED` → re-enable, and jobs refuse meanwhile;
`RECONCILIATION_UNKNOWN` → offer nothing, state the gap; `RECONCILED` → the
baseline.

#### D5 — Capability policy (schedule) *(the `A5` home)*

| Field | Contract |
| --- | --- |
| **Owner** | the **schedule / capability-policy contract** (`M12`; `LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §10.2 — a schedule is a property of the device capability). **Never** the job lifecycle (`PO-NAV-7`) |
| **Values** | `POLICY_ACTIVE`, `POLICY_DISABLED`, `POLICY_UNSCHEDULED`, `POLICY_UNKNOWN` |
| **Meaning** | `POLICY_ACTIVE` — a schedule exists and is enabled. `POLICY_DISABLED` — a schedule exists and was **intentionally** turned off without disabling the device. `POLICY_UNSCHEDULED` — no schedule was ever defined (today's universal default). `POLICY_UNKNOWN` — the policy store is unreadable or contradictory |
| **Evidence** | the schedule/capability-policy record for this (entity, capability). Nothing else |
| **Persisted?** | **persisted** — it is durable product state, never a UI preference (§10.2: "Navigation state — rejected outright") |
| **Scope** | **(entity, capability)-scoped** |
| **May affect** | explanatory copy; the presence of a "resume/enable schedule" affordance; the honest statement that evidence will not refresh |
| **Must never control** | visibility; and it must **never** be derived from `LifecycleState.EXCLUDED`, from Device Registry `DISABLED`, or from the global scheduler's default-disabled posture — those are three different facts (E2) |
| **Evidence today** | **no producer**. `M12` builds it. Until then every capability is `POLICY_UNSCHEDULED` |

`POLICY_UNSCHEDULED` and `POLICY_DISABLED` are separate because the operator
action differs — *create a schedule* versus *re-enable one* — and because
`POLICY_DISABLED` is a deliberate act that must be attributable, while
`POLICY_UNSCHEDULED` is merely the absence of one.

#### D6 — Evidence state

| Field | Contract |
| --- | --- |
| **Owner** | the existing evidence/snapshot projection — `utils/snapshot.py` (V11), `utils/config_ui.py` (V12/V13), `utils/config_history.py` (V14). **Already exists; `M3` renames nothing** |
| **Values** | `LIVE`, `LAST_KNOWN_GOOD`, `PARTIAL`, `NO_DATA`, `COLLECTION_FAILED`, `INSUFFICIENT_EVIDENCE`, `PROVENANCE_UNVERIFIED` |
| **Meaning** | as the owning modules already define them; `M3` binds to them and adds none |
| **Evidence** | the collection run and its manifest |
| **Persisted?** | persisted as part of the evidence model |
| **Scope** | per (entity, capability, fact) — **device- and member-scoped** where the underlying evidence is |
| **May affect** | tone, explanatory copy, timestamps, and enablement of actions that need the missing fact |
| **Must never control** | visibility of any root, module or tab (`AC-WS-9`); and it must never be promoted into `D3` — a failed collection is not proof of non-support (`AGENTS.md` UNKNOWN law) |
| **Evidence today** | VERIFIED, shipped and mature |

#### D7 — Authorization

| Field | Contract |
| --- | --- |
| **Owner** | `utils/operate/authorization.py` today; `DEPLOY.1A`'s OIDC/RBAC later. Enforcement is **server-side** (`AC-EN-14`) |
| **Values** | `PERMITTED`, `DENIED(reason_code)`, `AUTHZ_NOT_CONFIGURED` |
| **Meaning** | may this actor perform this action against this entity. `AUTHZ_NOT_CONFIGURED` is the current, honest state — **no model exists**, which is not a permissive model |
| **Evidence** | the authorizer's decision. Nothing else |
| **Persisted?** | derived per decision; the decision is audited |
| **Scope** | **action-scoped** (actor × action × entity) |
| **May affect** | **action enablement and refusal copy only**, until a `NAV.2` amendment says otherwise |
| **Must never control** | visibility of any entry, module or tab; and it must never be inferred from `D1`…`D6`, nor may any of them be inferred from it. UI visibility or enablement is **never** the authorization boundary |
| **Evidence today** | VERIFIED: unconditional `deny("authorization_not_configured")` for class 2; `model: "none"` in navigation |

---

## 4. Canonical vocabulary

### 4.1 Composition model — one primary state plus non-exclusive qualifiers

The seven dimensions are facts. What an operator reads is a **resolution**.
Two options were considered:

- **a single flat state.** Rejected: a capability can be simultaneously usable
  and unscheduled, or usable and stale, or policy-disabled *and* unauthorized.
  Flattening loses whichever fact loses the tie, and the loser is exactly the
  one the operator needed (a `POLICY_DISABLED` view that silently hides
  `ACTION_DENIED` teaches the operator that re-enabling the schedule will grant
  the action — the authorization conflation §5.3 exists to prevent).
- **primary + qualifiers.** PROPOSED and adopted here.

> A capability's presentation is **exactly one `CapabilityState`** (why it is or
> is not usable) plus **zero or more `CapabilityQualifier`s** (facts that
> refine it but never replace it).

The qualifier set is **independently derived** — each qualifier is a pure
function of one dimension — so the test matrix is *primary count + qualifier
count*, not their product (§10, Test seat dissent).

**Distinction that must not be lost:** a *dimension value* (§3.3) is a fact
about the world; a *`CapabilityState`* is the resolved presentation. The
mapping is many-to-one and is defined by §5.1. They are not the same
vocabulary and must not be serialized in the same field.

### 4.2 `CapabilityState` — the primary vocabulary

Ten values. Each has a distinct operator consequence; none exists for symmetry.

| Value | Operator meaning | Distinct consequence | Source dimension |
| --- | --- | --- | --- |
| `NOT_SHIPPED` | this build does not ship the surface | **omitted** — nothing renders; the only omission case | D1 |
| `NOT_APPLICABLE` | this function does not apply to this kind of entity | visible, selectable, names the entity types that do support it, no enabled action | D2 |
| `DEVICE_DISABLED` | the operator disabled this device | visible; re-enable is the one meaningful action; jobs refuse at admission | D4 |
| `UNSUPPORTED` | this vendor/platform cannot do this, and we have evidence of that | visible; states the vendor reason; action disabled; **collecting again will not help** | D3 |
| `NOT_ENROLLED` | observed in evidence, absent from the Device Registry | visible; offers enrollment where `M9`'s gate permits | D4 (`EVIDENCE_ONLY`) |
| `POLICY_DISABLED` | this capability's schedule was intentionally turned off for this device | visible; names the policy and who set it; offers re-enable; states that evidence will not refresh | D5 |
| `COLLECTION_FAILED` | the last attempt failed | visible; shows the failure and its time; retry offered **as a new typed job**; last good evidence retained | D6 |
| `UNKNOWN` | we cannot conclude, and we say which fact is missing | visible; names the missing fact; any action needing that fact is disabled | D3 `SUPPORT_UNKNOWN`, D4 `REGISTRY_ONLY`/`RECONCILIATION_UNKNOWN`, D6 `NO_DATA`/`INSUFFICIENT_EVIDENCE`, or a contradiction |
| `NOT_CONFIGURED` | supported and applicable, and evidence **positively shows** it is not set up | visible; states what configuring it requires; actionable empty state | D6 (positive absence: `UNPROTECTED`, `not_configured`) |
| `AVAILABLE` | usable now | normal presentation, populated view | default |

**Lexical disjointness (mandatory).** No `CapabilityState` value equals any
member of X1 (`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped`)
or X2 (`CREATED`…`OUTCOME_UNKNOWN`). `COLLECTION_FAILED` is deliberately not
`FAILED`; no value is named `BLOCKED` (E2 — `blocked` already carries two
meanings).

### 4.3 `CapabilityQualifier` — non-exclusive

| Value | Meaning | Source | Never |
| --- | --- | --- | --- |
| `STALE(as_of)` | evidence is old but usable | D6 `LAST_KNOWN_GOOD` / `PROVENANCE_UNVERIFIED` | never removes the view; never authorizes an action (`AC-WS-11`) |
| `PARTIAL` | some facts collected, some not | D6 `PARTIAL` | never presented as complete |
| `NOT_SCHEDULED` | no schedule has ever been defined | D5 `POLICY_UNSCHEDULED` | never rendered as a fault |
| `MEMBER_SPECIFIC` | values legitimately differ across members of the logical entity | member comparison | **never** a warning or failure icon or wording (`AC-DIF-3`) |
| `ACTION_DENIED(reason)` | the authorizer refused, or no authorization model exists | D7 | **action-scoped only**; never affects view selectability or entry visibility |
| `ACTION_REFUSED(class)` | the action taxonomy refuses this class on this surface | X3 `console_refusal()` | action-scoped only; the refusing class is always named (`CON.0` §9) |

`MEMBER_SPECIFIC` **reuses** the existing V12 classification name rather than
inventing a synonym — E3 is a defect this document must not repeat.
`ACTION_REFUSED` is deliberately not `ACTION_BLOCKED`, for the same reason
`BLOCKED` is absent from §4.2.

### 4.4 Namespacing rule — why `UNKNOWN` is not one word

`UNKNOWN` and `NOT_APPLICABLE` already mean three different things each (E2).
Adding capability-scoped values with the same spelling is safe **only** under
an explicit namespacing rule:

> A `CapabilityState` is carried on the wire under the key `capability_state`,
> and a `CapabilityQualifier` list under `capability_qualifiers`. A bare state
> string never crosses a subsystem boundary without its key. A renderer resolves
> a label from the vocabulary the key names, never from a global string map.

This is what allows `capability_state: "UNKNOWN"` and a compliance control's
`status: "UNKNOWN"` to coexist without either inheriting the other's tone,
denominator behaviour or copy.

### 4.5 Concepts deliberately **not** given a state

| Concept | Why not |
| --- | --- |
| `BLOCKED` | `blocked` already means a job record state and a job-type taxonomy fact (E2). Expressed as the `ACTION_REFUSED` qualifier instead |
| `UNREACHABLE` | it is a collection outcome, not a capability fact. It resolves to `COLLECTION_FAILED` with its reason; promoting it would invite C8 |
| `DEGRADED` | no distinct operator action exists that `PARTIAL` + `STALE` + the owning subsystem's own verdict (V15, V25) do not already carry. Adding it would duplicate `DEGRADED_PROCEED_WITH_RISK`, which `OP.0a` makes unreachable by construction |
| `REGISTRY_ONLY` as a primary | its consequence — run first contact — is `UNKNOWN` with reason `registry_enrolled_never_observed`. A separate primary would be symmetry, not consequence |
| `FORBIDDEN` / `UNAUTHORIZED` as a primary | authorization must never change what the view *is*, only what may be *done* (§5.3). It is the `ACTION_DENIED` qualifier |
| `PENDING` / `IN_PROGRESS` / `SUCCEEDED` / `FAILED` | job lifecycle, X1, unchanged (`PO-NAV-7`). A capability never adopts a job's state |

---

## 5. Precedence and composition

### 5.1 The precedence ladder

Evaluated per **(surface, entity, capability)**. First match wins for the
primary state; qualifiers are then computed independently and all that hold are
attached.

| Rank | Condition | Primary |
| --- | --- | --- |
| 1 | D1 = `SURFACE_ABSENT` | `NOT_SHIPPED` — omit; evaluate nothing further |
| 2 | D2 = `NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 3 | D4 = `REGISTRY_DISABLED` | `DEVICE_DISABLED` |
| 4 | D3 = `UNSUPPORTED` | `UNSUPPORTED` |
| 5 | D4 = `EVIDENCE_ONLY` | `NOT_ENROLLED` |
| 6 | D5 = `POLICY_DISABLED` | `POLICY_DISABLED` |
| 7 | D6 = `COLLECTION_FAILED` | `COLLECTION_FAILED` |
| 8 | D3 = `SUPPORT_UNKNOWN`, or D2 = `APPLICABILITY_UNKNOWN`, or D4 ∈ {`REGISTRY_ONLY`, `RECONCILIATION_UNKNOWN`}, or D5 = `POLICY_UNKNOWN`, or D6 ∈ {`NO_DATA`, `INSUFFICIENT_EVIDENCE`}, or any two dimensions contradict | `UNKNOWN` (+ `missing_fact` reason code) |
| 9 | D6 positively evidences absence of configuration | `NOT_CONFIGURED` |
| 10 | otherwise | `AVAILABLE` |

**Why this order.**

- Rank 1 is the only omission (D-NAV11, `AC-WS-8`) `[PO-M3-1]`.
- Rank 2 above everything else because no amount of evidence, policy or
  enrollment can make a capability apply to a type it does not apply to.
- Rank 3 above rank 4 because a device the operator disabled is the operator's
  own most recent, most reversible act, and the most actionable. **Contested —
  see §10 dissent D2** — mitigated by the mandatory rule that
  `DEVICE_DISABLED`'s copy names every lower-ranked blocking dimension it masks.
- Rank 4 above 5–7 because `UNSUPPORTED` is the one state where enrolling,
  re-enabling a schedule or collecting again is guaranteed not to help; telling
  the operator to try is worse than telling them it cannot work.
- Rank 8 (`UNKNOWN`) above rank 9 (`NOT_CONFIGURED`) is a **fail-closed
  requirement**: asserting "not configured" without positive evidence of
  absence is fabricated certainty (`AGENTS.md` UNKNOWN / fail-closed law).
- Ranks 5, 6, 7 are ordered by how far upstream the blocker sits: an entity
  that is not enrolled has no policy to speak of, and a capability whose
  schedule is off has no collection to have failed.

**Qualifier attachment is unconditional on rank.** `STALE`, `PARTIAL`,
`NOT_SCHEDULED`, `MEMBER_SPECIFIC`, `ACTION_DENIED`, `ACTION_REFUSED` attach
whenever their source dimension holds, including on top of `UNSUPPORTED` or
`POLICY_DISABLED`, so retained evidence keeps its age and an action keeps its
true refusal reason.

### 5.2 The required hard cases, resolved

| Case | Primary | Qualifiers | Root/module | Tab | Action visible | Action enabled | Tone | Copy must say |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| supported but **not authorized** | `AVAILABLE` | `ACTION_DENIED` | unchanged | visible | yes | **no** | normal view; refusal tone on the action | who refused and why; never "unavailable" |
| supported but **not enrolled** | `NOT_ENROLLED` | — | unchanged | visible | enrollment only, where `M9` permits | per `M9` gate | muted | that it is observed but unenrolled, and what enrolling does |
| supported with **stale evidence** | `AVAILABLE` | `STALE(as_of)` | unchanged | visible, **populated** | yes | muted badge + timestamp | the age and provenance; never blank |
| **unsupported** with stale or missing evidence | `UNSUPPORTED` | `STALE` if data retained | unchanged | visible | yes | **no** | the **vendor** reason — not "no data"; that collecting again will not help |
| **policy-disabled and unauthorized** | `POLICY_DISABLED` | `ACTION_DENIED` | unchanged | visible | yes | **no** | **both**: the policy that disabled it **and** the authorization refusal (§5.3) |
| logical entity supported, **one member differs** | `AVAILABLE` | `MEMBER_SPECIFIC` | unchanged | visible | yes | yes | pale-yellow/gold row emphasis + "Expected member difference"; **no warning icon or wording** |
| global module applicable, **selected entity is not** | module: `AVAILABLE`; entity view: `NOT_APPLICABLE` | — | **unchanged — the module never disappears** (`AC-WS-10`) | visible, selectable `[PO-M3-1]` | yes | no | which entity types support it |
| action visible but **not currently executable** | any | `ACTION_DENIED` and/or `ACTION_REFUSED` | unchanged | visible | **yes** | no | the refusing gate, named — never a bare greyed control (`CON.0` §9) |
| **contradictory** dimensions | `UNKNOWN` | as they hold | unchanged | visible | yes | no | both contradicting inputs, named; never the more favourable one |

### 5.3 Authorization is evaluated last and is never masked

Three rules, and the reason the Security seat was seated (§10):

1. **Authorization is computed independently of ranks 1–10** and attached as a
   qualifier. No precedence outcome may suppress it.
2. **When an action is disabled for more than one reason, the authorization
   reason is always stated**, and stated last, so an operator cannot conclude
   that clearing the other reason would grant the action.
3. **Visibility and enablement are never the authorization boundary.** The
   server refuses independently (`AC-EN-14`); an enabled control is not a grant
   and a disabled one is not an enforcement. A future `NAV.2` amendment may
   make D7 a conjunct on *entries*; until then it touches actions only
   (`AC-NAV-8`).

### 5.4 What each case may change

| Effect | Permitted inputs | Forbidden inputs |
| --- | --- | --- |
| root / module visibility | D1 only | D2, D3, D4, D5, D6, D7 |
| tab visibility | D1 only `[PO-M3-1]` | D2–D7 |
| action **visibility** | the action contract's existence (D1) | D7 (an action must not vanish because you lack access) |
| action **enablement** | D2, D3, D4, D5, D6, D7, X3 | — |
| tone / icon | the resolved primary + qualifiers | colour alone as sole carrier |
| explanatory copy | every dimension that contributed, named | a reason that is not the actual gate |
| audit / report representation | the resolved state as text | any action affordance in the report (`AC-SH-1`) |

---

## 6. Presentation contract

### 6.1 Global navigation

Governed entirely by D1. `AC-NAV-1`…`AC-NAV-8` are carried unchanged. No
capability state, evidence state, enrollment state, policy state or
authorization state may add, remove, reorder, grey or annotate a rail entry.
Emptiness is expressed **inside** the module, in words (§2.7 of the parent).

### 6.2 Entity workspace

Tab position is canonical and stable across entity types. A tab is omitted only
on D1 `[PO-M3-1]`. Every other state renders **inside** the tab as: a state
chip (label + tone), one sentence of explanatory copy naming the actual gate,
the evidence timestamp where `STALE` or `PARTIAL` applies, and — for
`NOT_APPLICABLE` — the entity types that do support the capability.

### 6.3 Contextual actions

An action is **declared** against a domain with its availability and reason
(the parent's "declare, don't draw" pattern); the renderer emits only those
whose backend contract exists. A rendered action that is not currently
executable is **shown disabled with the refusing gate named**, never a bare
greyed control. Where `ACTION_DENIED` and `ACTION_REFUSED` both hold, both are
named (§5.3 rule 2).

### 6.4 Status badges / chips and tone tokens

| Primary | Tone | Rationale |
| --- | --- | --- |
| `AVAILABLE` | `success` / normal | — |
| `NOT_APPLICABLE` | `info` | structural, not a problem |
| `NOT_CONFIGURED` | `info` | actionable, not a fault |
| `NOT_ENROLLED` | `info` | actionable, not a fault |
| `POLICY_DISABLED` | `muted` + explicit "disabled by policy" label | intentional, not a failure |
| `DEVICE_DISABLED` | `muted` + explicit label | intentional |
| `UNSUPPORTED` | **its own token**, not `neutral` | C2: `neutral` is what an *unrecognised* value gets; a real product statement must not share that tone |
| `UNKNOWN` | `muted` | honest gap |
| `COLLECTION_FAILED` | `danger` | actual fault |
| `NOT_SHIPPED` | — | never rendered |

| Qualifier | Tone |
| --- | --- |
| `STALE` | `muted` + timestamp |
| `PARTIAL` | `muted` |
| `NOT_SCHEDULED` | `muted` |
| `MEMBER_SPECIFIC` | **`--member-specific`** — see §6.6 |
| `ACTION_DENIED` / `ACTION_REFUSED` | refusal tone on the control only |

**`AC-CS-19` consequence:** `statusTone()`'s fall-through `neutral` must stop
being the answer for `UNSUPPORTED`, `NOT_APPLICABLE` and `NOT_CONFIGURED` (C2).

### 6.5 Empty states

Every non-`AVAILABLE` primary renders a real empty state with three parts:
**what is missing**, **why** (the actual gate, named), and **what would change
it** — or an explicit statement that nothing the operator can do will (the
`UNSUPPORTED` case). A blank panel, a spinner that never resolves, and a bare
"No data" are each a contract violation.

### 6.6 Warnings, faults, and the `PO-NAV-6` member-specific token

`PO-NAV-6` and `AC-DIF-1`…`AC-DIF-9` are FROZEN and carried verbatim. `M3`'s
contribution is to name the mechanism that makes them satisfiable, given C1:

> **PROPOSED.** The pale-yellow/gold member-comparison emphasis is preserved
> and moved onto **its own design token, `--member-specific`**, seeded from the
> current gold value and **decoupled from `--warning`**. `.difference-row`,
> `.scope-chip.diff` and `.divergence-badge` bind to `--member-specific`, not
> to `--warning`. `--warning` keeps attention semantics for `LOCAL_OVERRIDE`
> and `DIFFERENCE_OBSERVED`; red/`--danger` stays reserved for actual fault,
> unsafe drift, out-of-sync and failure (`AC-DIF-8`).

Consequences that make it testable: every member-specific row carries the text
"Expected member difference" or "Member-specific"; carries **no** warning or
failure icon and no failure wording; `.divergence-badge`'s current
"Interface diff" / "Route diff" text is a *difference kind*, not a state label,
and must be accompanied by the state label. `MEMBER_SPECIFIC` resolves to one
tone in **both** planes — Inventory and Configuration — ending C1's two-planes
problem without Inventory copying Configuration's blue.

**Implementation is not in `M3`** (non-goal: no UI, no CSS). This is the
contract a later movement implements.

### 6.7 Accessible name and non-colour meaning

- Every state chip has a **text label**; colour is never the only carrier
  (`AC-DIF-9`).
- Every chip has an **accessible name** that includes the state label and,
  where present, the qualifier list and the evidence timestamp.
- Qualifiers are announced, not merely painted: a `STALE` view announces its
  age; a `MEMBER_SPECIFIC` row announces which member.
- A disabled action's accessible name includes its **refusal reason**, so the
  gate is available to assistive technology and not only to sighted hover.
- Every state must remain distinguishable in high contrast and to a
  colour-blind operator without hue.
- `AC-A11Y-1`…`AC-A11Y-5` (closed by `M2`) are not reopened.

### 6.8 Static report parity, and one intentional divergence

| Carried in the exported report | Withheld from the report |
| --- | --- |
| the resolved `CapabilityState` and its copy | `ACTION_DENIED` and `ACTION_REFUSED` qualifiers |
| `STALE` / `PARTIAL` / `NOT_SCHEDULED` / `MEMBER_SPECIFIC` qualifiers | any enrollment affordance for `NOT_ENROLLED` |
| the evidence timestamp and provenance | any control, form or submission of any kind (`AC-SH-1`) |
| the identical information architecture (`AC-SH-3`) | live refresh |

**The divergence and its reason.** The report is action-free **by contract**.
An authorization or taxonomy refusal is a statement *about an action*;
rendering it in an artifact that has no actions would assert that an action
exists there, which is exactly the "disabled control in the report" the parent
forbids (`AC-SH-1` — "Absent, never disabled"). The **state** is reported; the
**refusal** is not, because there is nothing to refuse. Every other state and
qualifier is at parity.

`NOT_ENROLLED` is reported as a state (it is an evidence fact) with no
enrollment affordance — enrollment is console-only and gated (`AC-SH-1`,
`AC-EN-11`).

---

## 7. Vendor semantics

### 7.1 May be normalized across Check Point and Palo Alto

| Normalizable | Why it is safe |
| --- | --- |
| the `CapabilityState` / `CapabilityQualifier` vocabularies themselves | they describe the **product's** knowledge, not the vendor's behaviour |
| the seven capability **names** (`inventory`, `configuration_collection`, `backup`, `ha_readiness`, `controlled_operations`, `telemetry`, `diagnostics`) | already FROZEN by `PCP.0` §8 |
| the evidence-state vocabulary (D6) | already vendor-neutral and shipped |
| the precedence ladder (§5.1) | it orders *our* dimensions, not vendor facts |
| `NOT_APPLICABLE` at the **entity-type** level | entity types are already vendor-scoped, so the normalization is of the rule, not of the fact |

### 7.2 Must remain vendor-specific

| Must stay vendor-scoped | Reason |
| --- | --- |
| every `UNSUPPORTED(vendor_reason)` **reason code** | a reason is a vendor semantic; a shared reason enum would let a Check Point conclusion justify a Palo Alto refusal. Reason codes are namespaced by vendor and never compared across vendors |
| what **counts as evidence** of support | CP: shell/collection interface profile + platform-family classification. PAN: identity-gate outcome + `effective-running` availability. These are not interchangeable |
| HA / failover unit semantics | ClusterXL cluster, VSX host/cluster/VS and PAN HA pair are distinct units (`utils/failover/assessment.py` `_UNIT_*`); `VSYS` is never a failover unit |
| member-difference semantics | CP ClusterXL member differences are `MEMBER_SPECIFIC` unless expected-state evidence proves otherwise (`AGENTS.md`). For a PAN pair whose `B₂` bidirectional corroboration is **NOT ESTABLISHED**, the workspace shows what it can prove and states the relationship is `UNKNOWN` (`AC-WS-5`) — it must not render a confident member comparison |
| Panorama vs direct-firewall evidence grade | management-plane observation is not direct-device runtime truth (`AGENTS.md`) |
| the open vendor facts `D-V3a`, `D-V7b`, `D-V8` | `STILL_UNKNOWN`; they map to `UNKNOWN`, never to `UNSUPPORTED`, and this document does not narrow them |

### 7.3 What may **never** prove capability support

`D3` may not be set to `SUPPORTED` **or** `UNSUPPORTED` from any of:

- presence or absence of a menu, tab, or UI entry;
- device reachability, or unreachability;
- enrollment, or absence of enrollment;
- a successful collection of a **different** capability;
- a **failed** collection of this capability;
- a vendor **hint** supplied at enrollment (`AC-EN-5`: vendor and identity
  require positive evidence);
- landing directly in Gaia Clish — that is a *capability*, not a platform
  identity, and must never become `UNSUPPORTED(quantum_spark)`
  (`AGENTS.md` Check Point section);
- a field's **presence** in a response (`AGENTS.md`: field presence is not
  field-semantic proof);
- general model or product knowledge not backed by repository evidence,
  official vendor documentation or real-environment corroboration.

Anything not on the permitted-evidence list yields `SUPPORT_UNKNOWN`, which
resolves to `UNKNOWN` — never `UNSUPPORTED`. **Unknown vendor behaviour stays
unknown.**

---

## 8. Transition and evidence rules

### 8.1 Event → dimension

An event may change only the dimensions its row marks. Any other write is a
contract violation, and each forbidden edge corresponds to a conflation in §2.4.

| Event | May change | Must **not** change | Prevents |
| --- | --- | --- | --- |
| enrollment (`M9`) | D4 | D3, D5, D6, D7 | enrollment implying support |
| registry disable / re-enable | D4 | all others | C4 |
| successful collection | D6; **D3 only if the run carried positive support evidence** | D4, D5, D7 | collection success implying semantic correctness (`AGENTS.md`) |
| failed collection | D6 (`COLLECTION_FAILED`) | **D3, D4, D5** | C8; failure implying non-support or unenrollment |
| evidence expiry | D6 (adds `STALE`) | all others | staleness implying failure |
| registry reconciliation | D4 | all others | C4 |
| schedule / policy change | D5 | all others | C5; policy implying failure |
| authorization change | D7 | **all others** | C6; the authorization conflation |
| logical-member selection | **nothing** — it changes the *scope of presentation* only | every dimension | selection changing product truth (D-NAV11) |
| a new build / shell | D1 | all others | build state leaking into device state |

### 8.2 Fail-closed and honest-unknown

1. **Absence of a producer is `UNKNOWN`, not a favourable default.** Until
   `M10` ships D3 and `M12` ships D5, D3 is `SUPPORT_UNKNOWN` and D5 is
   `POLICY_UNSCHEDULED` — neither may be read as `SUPPORTED` or `POLICY_ACTIVE`.
2. **Contradiction resolves to `UNKNOWN`, naming both inputs.** Never the more
   favourable of the two, and never a silent reconciliation.
3. **No authorization model is not a permissive one.** D7
   `AUTHZ_NOT_CONFIGURED` denies the action and states why.
4. **Anything feeding an action decision fails closed** (`AC-WS-11`,
   `OP.2.0` P4/P14): `UNKNOWN` and `INSUFFICIENT_EVIDENCE` never read as
   permitted, and a `STALE` projection never authorizes a job.
5. **A capability state is never persisted as truth.** Only the registry row
   (D4) and the schedule record (D5) persist; everything else is derived per
   render, so a stale cache can never outlive the evidence that justified it.
6. **Identity joins are on canonical ids only** — never on a hostname, display
   label or inferred ordinal (`AGENTS.md` presentation-identity law).

---

## 9. Acceptance criteria

Individually numbered, implementable and testable without subjective visual
judgment. Each names the movement expected to own it. A criterion whose surface
does not yet exist is **not waived** — it becomes that movement's criterion.

### Ownership

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-1` | Every dimension `D1`…`D7` has exactly one authoritative owning module, and no second module writes it | `M10`/`M12` |
| `AC-CS-2` | `D1` is the only dimension any visibility decision reads; a test over the full dimension cross-product shows no root, module or tab visibility changes when `D2`…`D7` vary | `M10`/`M11` |
| `AC-CS-3` | `D4` is produced by the registry/evidence reconciliation projection and is **not** a member of any capability-support vocabulary | `M10` |
| `AC-CS-4` | `D5` is produced by the schedule/capability-policy contract and is **not** a member of the job lifecycle vocabulary | `M12` |
| `AC-CS-5` | No dimension is computed from `D7`, and `D7` is computed from no other dimension | `M14` |
| `AC-CS-6` | `D5` is never derived from `LifecycleState.EXCLUDED`, Device Registry `DISABLED`, or the global scheduler enabled flag | `M12` |

### Vocabulary

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-7` | `CapabilityState` has exactly the ten §4.2 values; adding one requires naming its distinct operator consequence | `M10` |
| `AC-CS-8` | No `CapabilityState` or `CapabilityQualifier` value string equals any member of the console job lifecycle (`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped`) — asserted as a set-intersection test | `M10` |
| `AC-CS-9` | No `CapabilityState` value string equals any `utils.operate.states.ActionState` member | `M10` |
| `AC-CS-10` | A capability state is carried under `capability_state` and its qualifiers under `capability_qualifiers`; no bare state string crosses a subsystem boundary without its key | `M10` |
| `AC-CS-11` | Every `CapabilityState` other than `AVAILABLE` and `NOT_SHIPPED` carries a machine-readable reason code, and every reason code has an operator-facing sentence | `M10` |

### Precedence

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-12` | Resolution is a pure function of the seven dimension values: the same input tuple always yields the same `(primary, qualifiers)` pair | `M10` |
| `AC-CS-13` | The §5.1 ladder is exercised over the **full generated cross-product** of dimension values, not a hand-picked sample | `M10` |
| `AC-CS-14` | Qualifiers are each a pure function of one dimension and are computed independently of the primary | `M10` |
| `AC-CS-15` | `UNKNOWN` outranks `NOT_CONFIGURED`: `NOT_CONFIGURED` is emitted only on **positive** evidence of absence | `M10` |
| `AC-CS-16` | `DEVICE_DISABLED` copy names every lower-ranked blocking dimension it masks | `M10` |
| `AC-CS-17` | Each of the nine §5.2 hard cases resolves exactly as its row states | `M10` |

### Vendor separation

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-18` | No `UNSUPPORTED` reason code is shared between Check Point and Palo Alto; reason codes are vendor-namespaced and never compared across vendors | `M10` |
| `AC-CS-19` | `D3` is never set from any item in §7.3's forbidden list — asserted per item | `M10` |
| `AC-CS-20` | A collection failure leaves `D3` unchanged | `M10` |
| `AC-CS-21` | A PAN pair without established `B₂` corroboration renders the relationship as `UNKNOWN` and no confident member comparison | `M10`/`M11` |
| `AC-CS-22` | Direct-Clish shell behaviour never yields an `UNSUPPORTED` platform-identity reason | `M10` |

### Navigation stability

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-23` | The rendered root set is unchanged across every dimension permutation for every entity (`AC-NAV-4` extended to the full vocabulary) | `M10`/`M11` |
| `AC-CS-24` | No capability state renders an entry disabled, greyed or "coming soon" (`AC-NAV-5`) | `M11` |

### Workspace behaviour

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-25` | A tab is omitted only on `D1 = SURFACE_ABSENT` `[PO-M3-1]` | `M11` |
| `AC-CS-26` | `NOT_APPLICABLE` renders visible, selectable, with no enabled action, and **names the entity types that support the capability** | `M11` |
| `AC-CS-27` | `UNSUPPORTED`, `NOT_CONFIGURED`, `STALE`, `COLLECTION_FAILED` and `UNKNOWN` each keep their view selectable and each render a distinct tone and distinct copy — no two collapse to the same presentation (closes C2) | `M11` |
| `AC-CS-28` | A `STALE` view is **populated**, with its age and provenance shown; it is never blank | `M11` |
| `AC-CS-29` | Every non-`AVAILABLE` primary renders an empty state containing what is missing, why, and what would change it (or that nothing will) | `M11` |

### Action behaviour

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-30` | A rendered but non-executable action is shown **disabled with its refusing gate named** — never a bare greyed control | `M11` |
| `AC-CS-31` | Retry after `COLLECTION_FAILED` is offered as a **new typed job**; no historical job record is mutated (`AC-RT-6`) | `M7` |
| `AC-CS-32` | Where `ACTION_DENIED` and `ACTION_REFUSED` both hold, both are named | `M11` |

### Authorization independence

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-33` | `D7` changes no entry, module or tab visibility; a test varying `D7` across all values shows an identical rendered structure | `M11`/`M14` |
| `AC-CS-34` | When an action is disabled for a non-authorization reason **and** authorization denies it, the authorization reason is still stated, and stated last | `M11` |
| `AC-CS-35` | Server-side refusal is independent of UI enablement: an action disabled in the UI is refused by the server, and an action enabled in the UI is still checked server-side (`AC-EN-14`) | `M14` |

### Accessibility

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-36` | Every state chip carries a text label; no state is conveyed by hue alone (`AC-DIF-9`) | `M11` |
| `AC-CS-37` | Every state chip's accessible name includes the state label, its qualifiers and, where applicable, the evidence timestamp | `M11` |
| `AC-CS-38` | A disabled action's accessible name includes its refusal reason | `M11` |

### Report behaviour

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-39` | The exported report renders the same `CapabilityState` and non-action qualifiers as the console for the same evidence | `M11` |
| `AC-CS-40` | The report renders **no** `ACTION_DENIED` / `ACTION_REFUSED` qualifier and no enrollment affordance; both are absent, not disabled (`AC-SH-1`) | `M11` |

### Unknown / stale evidence

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-41` | With no `D3` producer, every capability resolves `SUPPORT_UNKNOWN` → `UNKNOWN`, never `SUPPORTED` or `UNSUPPORTED` | `M10` |
| `AC-CS-42` | Contradictory dimension inputs resolve to `UNKNOWN` naming **both** inputs | `M10` |
| `AC-CS-43` | `UNKNOWN`, `INSUFFICIENT_EVIDENCE` and `STALE` never read as permitted at any action decision point (`AC-WS-11`) | `M10`/`M7` |

### Difference presentation (`PO-NAV-6`)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-44` | `.difference-row`, `.scope-chip.diff` and `.divergence-badge` bind to `--member-specific`, not `--warning` | later UI movement |
| `AC-CS-45` | `MEMBER_SPECIFIC` resolves to one tone and one label in **both** Inventory and Configuration | later UI movement |
| `AC-CS-46` | A `MEMBER_SPECIFIC` row carries no warning or failure icon and no failure wording (`AC-DIF-3`) | later UI movement |
| `AC-CS-47` | `--danger` / red is emitted only for `COLLECTION_FAILED`, `EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, contradictory/unsafe state and failed job outcomes (`AC-DIF-8`) | later UI movement |

### Regression protection for existing vocabularies

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-48` | `console/jobs.py::TERMINAL_STATES` and the `queued`/`running` set are byte-identical to their pre-`M3` values — asserted directly | `M3` onward |
| `AC-CS-49` | `utils/operate/states.py::ActionState` membership is unchanged | `M3` onward |
| `AC-CS-50` | `utils/action_taxonomy.py`'s five classes and their permissions are unchanged | `M3` onward |
| `AC-CS-51` | `utils/compliance_posture.py::STATUS_VALUES` is unchanged; no capability state leaks into a compliance control status | `M3` onward |
| `AC-CS-52` | `utils/discovery_lifecycle.py::LifecycleState` is unchanged | `M3` onward |
| `AC-CS-53` | `utils/device_registry.py`'s lifecycle vocabulary is unchanged; `D4` adds a **projection**, not a registry state | `M3` onward |

---

## 10. Advisory council record

**Composition.** Five bounded seats plus Security, admitted under the stated
condition: the vocabulary introduces `ACTION_DENIED` / `ACTION_REFUSED`
qualifiers that sit directly beside action enablement, so an operator could
mistake UI enablement for authorization. Security materially changed the
outcome — §5.3's rule 2 ("the authorization reason is always stated, and stated
last") and `AC-CS-34` exist because of it; without them a `POLICY_DISABLED` +
`ACTION_DENIED` control would have taught the operator that re-enabling the
schedule grants the action.

Seats: NSPM Product Strategist · NSPM UX and Information Architect ·
Network/Vendor Semantics Specialist · Control-Plane Architect · Test and
Verification Strategist · Security.

**Diversity disclosure.** All seats were run by one model family. This is
**role diversity, not independent cross-model validation**, and it is not
evidence that the proposal is correct. The installed `nexus-decision-council`
skill named in the movement brief is **not present in this environment**
(`.claude/skills/` contains only `session-start-hook`); the panel was run
inline against the same bounded seat list. No transcript is stored in the
repository.

**Supported consensus.**

1. Eligibility must be a conjunction of independently owned dimensions, and D1
   must be the sole visibility input.
2. `UNSUPPORTED` requires positive evidence; the honest default is `UNKNOWN`.
3. The two `PO-NAV-7` concepts belong in the reconciliation projection (D4) and
   the schedule/capability-policy contract (D5), never in the job lifecycle.
4. Authorization is an independent enforcement boundary and never a visibility
   or enablement proxy.
5. No state may be added for symmetry.

**Material dissent, recorded unresolved.**

| id | Seat | Dissent | Response in this draft |
| --- | --- | --- | --- |
| **D1** | Test & Verification | The primary + qualifier model risks a `10 × 2⁶` state matrix if qualifiers are ever allowed to depend on the primary. Preferred a flat single state | Accepted as a real risk. Mitigated by making each qualifier a pure function of exactly **one** dimension (`AC-CS-14`), so the matrix is additive. **The dissent stands** if a future movement introduces a primary-dependent qualifier |
| **D2** | UX / IA | Ranking `DEVICE_DISABLED` (rank 3) above `UNSUPPORTED` (rank 4) gives a two-step disappointment: the operator re-enables the device only to learn the capability was never supported | Not accepted; `DEVICE_DISABLED` is the operator's own most recent and most reversible act. Mitigated by `AC-CS-16` (its copy must name what it masks). **Dissent stands** and is a legitimate alternative ordering the Product Owner may choose |
| **D3** | Vendor Semantics | `UNSUPPORTED` as a cross-vendor normalized state invites a Check Point conclusion being reused to justify a Palo Alto refusal | Partially accepted: the **state** is shared, the **reason** is vendor-namespaced and never compared across vendors (`AC-CS-18`). The seat holds that a shared state name still exerts pressure toward shared reasons over time |
| **D4** | Control-Plane Architect | `M3` defines a vocabulary before `M10` builds the projection that must populate it; values with no producer risk being unimplementable | Accepted. §3.3 carries an explicit "evidence today" row per dimension, and D3/D5 are marked **no producer**; §8.2 rule 1 makes their absence resolve to `UNKNOWN` rather than a favourable default |
| **D5** | Product Strategist | `NOT_CONFIGURED` and `NOT_ENROLLED` may read as the same thing to an operator ("it isn't set up") | Not accepted: the operator actions differ (configure the capability vs enroll the device) and the owning dimensions differ (D6 vs D4). Copy must make the distinction explicit; **dissent noted** as a copywriting risk, not a model defect |

---

## 11. Open Product Owner decisions

The smallest set that must be closed before this document can be frozen.
Everything else in §3–§9 is either FROZEN by a parent contract or PROPOSED and
falsifiable.

| id | Decision | Recommendation |
| --- | --- | --- |
| **PO-M3-1** | **The navigation contract contradicts itself** on omitting a structurally inapplicable tab: §8/§8.1 permit omission for an entity type; §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` forbid it. Which governs? | **§6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` govern**; §8/§8.1's "may be omitted for that entity type" is residual revision-1 text that §6.5 explicitly closed. Requires a one-line correction to the frozen navigation contract |
| **PO-M3-2** | Adopt the **primary + qualifier** composition model (§4.1), or require a single flat state? | Adopt primary + qualifier; see §10 dissent D1 |
| **PO-M3-3** | The `PO-NAV-7` "not enrolled" concept: adopt the **directional D4 value set** (`RECONCILED` / `EVIDENCE_ONLY` / `REGISTRY_ONLY` / `REGISTRY_DISABLED` / `RECONCILIATION_UNKNOWN`) with `NOT_ENROLLED` retained as the *operator-facing state name* for `EVIDENCE_ONLY`? Or keep a single flat `NOT_ENROLLED`? | Adopt the directional set — the concept is two-sided ("present in evidence but absent from the registry, **or the reverse**") and a flat name cannot express the reverse |
| **PO-M3-4** | **Two live job-lifecycle vocabularies exist** (E3): `console/jobs.py` (`queued`…`skipped`) and `utils/coordinator_backend.JobStatus` (`pending`…`orphaned`), and the UI surfaces the second via `discovery_capability_ui.JOB_STATUS_LABELS`. `PO-NAV-7` protects only the first. Does a later movement reconcile them, and is `JOB_STATUS_LABELS` in that scope? | Out of `M3`'s scope (job-plane change). Recommend booking it as backlog against `PCP.5`/`M12`, **not** folding it into `M3` |
| **PO-M3-5** | Approve the **`--member-specific` token decoupling** from `--warning` as the `PO-NAV-6` implementation direction (§6.6)? | Approve as contract; implementation belongs to a later UI movement |
| **PO-M3-6** | `POLICY_UNSCHEDULED` as a **qualifier** (`NOT_SCHEDULED`) rather than a primary state? | Qualifier — a capability with usable evidence and no schedule is still `AVAILABLE`; the absence of a schedule is a refresh fact, not a usability fact |

Reported for awareness, **no decision requested**: C3 (compliance `UNKNOWN`
counts in the alignment denominator) is inside the compliance contract's
domain, not `M3`'s, and is unchanged here.

---

## 12. Non-goals of this movement

`M3` implements nothing. It changes no runtime, UI, CSS, JavaScript, template,
adapter, registry, storage, enrollment, authorization, job or report-generation
code, and no payload schema. It creates no capability engine. It alters no
existing job lifecycle, action-state, taxonomy, compliance, discovery-lifecycle
or registry vocabulary. It does not reopen the six-root navigation baseline,
the workspace architecture, `PO-NAV-1`…`PO-NAV-8`, or any frozen acceptance
criterion. It begins no later movement, contacts no device, and claims no
Product Owner approval or freeze.
