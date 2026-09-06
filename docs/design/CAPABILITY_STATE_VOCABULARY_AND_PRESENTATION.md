# Capability-state vocabulary and presentation contract (`M3`)

## Status

**DRAFT — DO NOT FREEZE. NOT PRODUCT OWNER APPROVED. AUTHORIZES NO
IMPLEMENTATION.**

Prepared on branch `claude/capability-state-vocabulary-cube5f` from verified
`origin/main` head `d363b179fe8f552544402e070f5908ec10df2115` (PR #90,
`DEV.TEST.1`).

**Revision 2 (bounded correction).** Revision 1 (`5be42b1`) was independently
reviewed; this revision applies the resulting corrections. Fourteen draft
defects are corrected and listed at §11.1 — chiefly the separation of
provenance / freshness / completeness / collection outcome into four
independent facets (§3.3 D6), the separation of status label from displayed
evidence and action eligibility (§4.1, §5.4, §6.5), a precise and scoped
definition of contradiction (§5.1), positive prerequisites for `AVAILABLE`
(§5.2), five distinct authorization regimes replacing a single implied one
(§3.3 D7, §5.3), and corrected persistence rules (§8.2.1). Two false
equivalences carried by the FROZEN parent are recorded with prepared
amendments at §2.7 and **not applied**. The revision is still **DRAFT**;
nothing here is frozen or approved.

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
| V9a | **`UNKNOWN_SHELL`, read exactly** | returned from `plan_collection`'s step 9, commented *"Unknown / insufficient evidence"*, as the branch taken when `profile.shell_type == ShellType.UNKNOWN`; its sibling in the same `return` is `INSUFFICIENT_EVIDENCE`. Its docstring meaning is *"shell behavior not yet observed"* | `utils/capability_registry.py` l.276-283 | device plan |
| V10 | **Device Registry lifecycle** | `ENROLLED_UNVERIFIED`, `DISABLED`, `RETIRED`, `CONTACT_VERIFIED`, `OBSERVED` (only the first two reachable) | `utils/device_registry.py` | registered device |
| V11 | **Inventory/evidence status envelope** | `data_state` (`live`, `last_known_good`, `partial`, `no_data`) **plus four independent freshness/outcome fields**: `fresh: bool`, `collected_at`, `last_successful_collection`, `stale_reason`, and `availability_state` / `current_run` / `current_run_observed` | `utils/snapshot.py::_status` | per-entity evidence |
| V12 | **Configuration difference classification** | `DIFFERENCE_OBSERVED`, `LOCAL_OVERRIDE`, `EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, `MEMBER_SPECIFIC`, `PROVENANCE_UNVERIFIED`, `IDENTITY_TRANSLATION_REQUIRED`, `INSUFFICIENT_EVIDENCE` | `utils/config_ui.py`; producers in `configuration/pan_setting_alignment.py`, `checkpoint_config_alignment.py`, `pan_semantic_policy.py` | setting/row |
| V12a | **`PROVENANCE_UNVERIFIED`, read exactly** | emitted when `policy.expected_source_confidence not in TRUSTED_EXPECTED_SOURCE_CONFIDENCE`, with `reason = expected_source_confidence_is_not_sufficient_for_override_or_drift_claim` and `confidence = "none"`; counted as a **`semantic_exclusion`** beside `MEMBER_SPECIFIC` and `IDENTITY_TRANSLATION_REQUIRED`, not as a stale row | `configuration/pan_setting_alignment.py` l.438-443, l.649-652 | setting/row |
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
| evidence is old but usable | `data_state = last_known_good` **with** `fresh = false` (V11), `STALE` (V25) |
| we cannot conclude | `UNKNOWN` (V17/V24), `INSUFFICIENT_EVIDENCE` (V9/V12/V15/V16/V26), `unknown` (V7/V8), `UNKNOWN_SHELL` (V9a) |
| the vendor/platform cannot do this | `UNSUPPORTED` (V17/V18), `Capability.supported=False` (V21) |
| **the expected-state source is not trusted enough to claim a difference** — *no synonym; V12a stands alone* | `PROVENANCE_UNVERIFIED` (V12a) |

**Observation E3 — two subsystem job vocabularies, no mapping defect found.**
V1 and V2 are two live job-lifecycle vocabularies serving two different
subsystems: V1 is the **console job record**, V2 is the **admission
coordinator record**. They are not synonyms of one another and having both is
not, by itself, a defect.

**Verified: the renderers keep them apart correctly.**
`static/discovery_ui.js` l.30 reads `payload.job_status_labels` — the
coordinator vocabulary — for the coordinator/discovery payload, and
`static/console_actions.js::_consoleJobStatePill` (l.150-152) carries its own
map over the console vocabulary (`succeeded` / `failed` / `blocked` /
`running`). **No renderer resolves one vocabulary's value through the other's
label or tone map.** A search for such a cross-mapping found none.

The residual risk is forward-looking and is what §4.4's namespacing rule
exists for: a future renderer that resolves a **bare** status string through a
global map could cross them. Convergence of the two vocabularies is
**not** proposed, **not** a defect claim, and **not** a prerequisite for `M3`.
This revision withdraws the earlier framing of E3 as a finding and the PO
question that rested on it.

### 2.4 Conflations found in shipped logic

| # | Conflation | Evidence | Severity |
| --- | --- | --- | --- |
| C1 | **member-specific evidence presented as warning/fault** | `static/inventory_ui.js` paints every member-scoped row `.difference-row` (l.1916, l.2035) and `.scope-chip.diff` (l.1924, l.2042) and `.divergence-badge` (l.1763-4); `static/style.css` binds all three to `--warning` (l.393-6, l.648-9, l.863-6). Meanwhile `static/app_core.js::statusTone()` maps `MEMBER_SPECIFIC` → `info` (l.170). Two planes, one fact, opposite tone | **the defect `PO-NAV-6` exists to fix** |
| C2 | **unsupported is indistinguishable from an unrecognised value** | `statusTone()` has no branch for `UNSUPPORTED`, `NOT_APPLICABLE` or `NOT_CONFIGURED`; each falls through to `return "neutral"` — the same tone an unmapped or misspelled classification receives | high: a product-integrity failure of the kind navigation contract §2.8 names |
| C9 | **source-trust limitation read as evidence age** | `PROVENANCE_UNVERIFIED` (V12a) carries **no age semantics and no timestamp** — it says the expected-state source was not trusted enough to support an override or drift claim. Treating it as "stale evidence + timestamp" both misstates the fact and fabricates a timestamp the classification does not carry | high; **present in the frozen parent** — §2.7 |
| C10 | **"shell not yet observed" read as "platform cannot do this"** | `UNKNOWN_SHELL` (V9a) is the *unknown* branch of `plan_collection`, sibling to `INSUFFICIENT_EVIDENCE`. Reading it as evidence of non-support inverts `AGENTS.md`'s UNKNOWN law and would let an unobserved device be reported as unsupported | high; **present in the frozen parent** — §2.7 |
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

### 2.7 The same two conflations exist in the FROZEN parent — recorded, not amended

`AGENTS.md` forbids silently amending a frozen contract. The exact clauses are
recorded here with a proposed correction each; **no frozen file is edited by
this movement**, and each correction is a Product Owner action (§11).

| # | File / clause (verbatim) | Why it is wrong | Proposed amendment |
| --- | --- | --- | --- |
| **FA-1** | `NAVIGATION_INFORMATION_ARCHITECTURE.md` §8, l.627 — *"\| **Stale** \| `last_known_good`; `STALE`; `PROVENANCE_UNVERIFIED` \| shown \| **selectable**, data shown **with age + provenance** \| enabled, with age stated \| muted badge + timestamp; never blank \|"* | `PROVENANCE_UNVERIFIED` (V12a) has no age semantics and no timestamp; the row requires both | strike `PROVENANCE_UNVERIFIED` from the Stale row and give it its own row: *"**Source trust insufficient** \| `PROVENANCE_UNVERIFIED` \| shown \| selectable, states that no override/drift claim can be made from this source \| enabled \| muted, **no timestamp claim**"* |
| **FA-2** | `NAVIGATION_INFORMATION_ARCHITECTURE.md` §5.4, l.445 — *"\| Stale or incomparable evidence \| `PROVENANCE_UNVERIFIED`, `last_known_good` \| muted / provenance \| \"Stale evidence\" + timestamp \| none \|"* | same conflation; "incomparable" and "stale" are two different facts sharing one row and one required label | split into two rows, keeping `last_known_good` under "Stale evidence + timestamp" and `PROVENANCE_UNVERIFIED` under a label that does not assert age, e.g. "Source not verified" |
| **FA-3** | `NAVIGATION_INFORMATION_ARCHITECTURE.md` §8, l.629 — *"\| **Unsupported** \| `UNSUPPORTED(reason)` (`PCP.0` §8, not yet implemented); `UNKNOWN_SHELL` \| …"* | `UNKNOWN_SHELL` is the *unknown* branch (V9a), not a support conclusion | strike `UNKNOWN_SHELL` from the Unsupported row; it belongs in the existing §8 **Unknown / insufficient** row (l.636), which already reads *"disabled if the action needs the missing fact"* — the correct treatment |
| **FA-4** | `AC-DIF-7` (l.995) — *"Stale or incomparable evidence uses **muted/provenance** semantics with its timestamp"* | binds a timestamp requirement to a classification that has none | reword to require the timestamp **only** where a freshness anchor exists (`collected_at` / `last_successful_collection`), and provenance semantics without a timestamp otherwise |

`FA-1`…`FA-4` are **four one-to-two-line corrections**, all inside the
navigation contract's presentation tables. None changes a frozen decision
(`PO-NAV-1`…`PO-NAV-8`), a root, a tab rule or an authorization boundary.

Until they are applied, this document's §4.3 and §9 follow the **source
semantics** (V11, V12a, V9a) rather than the parent's conflating rows, and
says so at each point. That is a deliberate, declared divergence from a frozen
document, not a silent one; if the Product Owner declines `FA-1`…`FA-4`, the
divergence is a defect in **this** draft and §4.3/§9 must be reverted to the
parent's wording.

---

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
| **Values** | `POLICY_ACTIVE`, `POLICY_DISABLED`, `NO_APPLICABLE_SCHEDULE`, `POLICY_UNKNOWN` |
| **Meaning** | `POLICY_ACTIVE` — an applicable schedule exists and is enabled. `POLICY_DISABLED` — an applicable schedule exists and is **intentionally** turned off, without disabling the device. `NO_APPLICABLE_SCHEDULE` — the policy was read successfully and **authoritatively contains no applicable entry**. `POLICY_UNKNOWN` — the policy store is absent, unreadable, malformed, or does not express this granularity |
| **Evidence** | the schedule/capability-policy record for this (entity, capability), **read successfully**. `NO_APPLICABLE_SCHEDULE` requires a **successful read that returned no applicable entry** — never a failed read, never a missing producer |
| **Persisted?** | the schedule record is **persisted** — durable product state, never a UI preference (§10.2: "Navigation state — rejected outright") |
| **Scope** | **(entity, capability)-scoped** |
| **May affect** | explanatory copy; the presence of an enable/create-schedule affordance; an honest statement about **automatic** refresh |
| **Must never control** | visibility; the **presence or usability of already-collected evidence**; the availability of **manual** collection, first contact or retry, which are independent paths (§10.1 lists "collect now" and a manual retrieval action separately from schedules). It must **never** be derived from `LifecycleState.EXCLUDED`, Device Registry `DISABLED`, or the global scheduler's default-disabled posture — those are three different facts (E2) |
| **Evidence today** | **no per-(entity, capability) producer.** `utils/collection_executor.py` expresses scheduler policy as a global `enabled: bool` plus a **workflow-scoped** `ScheduledWorkflow(workflow, interval_minutes, targets)` list — a coarser granularity than D5. Because that source cannot answer the (entity, capability) question, D5 is **`POLICY_UNKNOWN`** today, **not** `NO_APPLICABLE_SCHEDULE`. `M12` builds the producer |

**Three separations this dimension must keep.**

1. **Missing knowledge is not confirmed absence.** A missing producer, an
   unreadable store, or a granularity mismatch yields `POLICY_UNKNOWN`.
   `NO_APPLICABLE_SCHEDULE` is a *positive* finding and requires a successful
   authoritative read.
2. **"None currently applies" is not "none has ever existed."**
   `NO_APPLICABLE_SCHEDULE` asserts only the **current** policy. This document
   defines **no** historical claim, because no repository source retains
   schedule history; a "never scheduled" statement would be fabricated.
3. **Schedule state governs future automatic refresh only.** It says nothing
   about whether current evidence is usable, and nothing about whether a
   manual collection, first-contact or retry job may run.

#### D6 — Evidence state *(four independent facets, not one axis)*

The single biggest correction in this revision: **provenance, freshness,
completeness and collection outcome are four separate facts.** They frequently
co-occur, and none implies another. `M3` renames none of them and binds to the
owning subsystems' existing fields.

| Facet | Owner | Values / fields | Means | Does **not** mean |
| --- | --- | --- | --- | --- |
| **D6a — freshness** | `utils/snapshot.py::_status` | `fresh: bool`, `collected_at`, `last_successful_collection`, `stale_reason` | how old this evidence is, against an explicit anchor | anything about whether the source was trusted |
| **D6b — completeness** | `utils/snapshot.py::_status` | `data_state` ∈ `live` / `last_known_good` / `partial` / `no_data` | how much of the expected evidence is present | that the missing part failed, or that the present part is stale |
| **D6c — collection outcome** | run telemetry; `FactState.COLLECTION_FAILED` (V17); `Outcome` (V18) | `success` / `failed` / `unsupported` / `capability_gap` / `identity_mismatch` | what happened on the **last attempt** | that previously retained evidence is invalid or must be hidden |
| **D6d — source trust / comparability** | `configuration/pan_setting_alignment.py` (V12a) | `PROVENANCE_UNVERIFIED`, `IDENTITY_TRANSLATION_REQUIRED` | the expected-state source was not trusted enough to support an override or drift **claim**; the row is a `semantic_exclusion` | **not** age, **not** staleness, and it carries **no timestamp** |

| Field | Contract |
| --- | --- |
| **Owner** | the existing evidence/snapshot projections — `utils/snapshot.py` (V11), `utils/config_ui.py` (V12), `utils/config_history.py` (V14). **Already exist; `M3` renames nothing** |
| **Evidence** | the collection run and its manifest |
| **Persisted?** | **persisted** — last-known-good state and content-addressed configuration evidence are durable today (`utils/snapshot.py` `last_known_good.json`; `utils/config_evidence.py` / `config_storage.py`) |
| **Scope** | per (entity, capability, fact) — device- and member-scoped where the underlying evidence is |
| **May affect** | tone, explanatory copy, timestamps **where a freshness anchor exists**, and the eligibility of actions that require the missing or current fact |
| **Must never control** | visibility of any root, module or tab (`AC-WS-9`); promotion into `D3` (a failed collection is not proof of non-support); and **D6d must never be rendered as, or converted into, a freshness claim** — `STALE` is derived from D6a alone |
| **Evidence today** | VERIFIED, shipped and mature. D6a's four fields already exist, so freshness is **evidenced, not inferred** |

#### D7 — Authorization *(five regimes, not one)*

Authorization in this repository is **not** a single model, and the `OP.2`
`DenyAllAuthorizer` is **not** the product-wide authorization model. Reading it
as universal would falsely deny the CLASS 0 console operations that are
permitted and shipped today.

| Regime | What it is | Status today | Governs |
| --- | --- | --- | --- |
| **R1 — console transport/session gate** | `console/app.py::_require_api_auth`: bearer launch token plus `Origin` / `Sec-Fetch-Site` checks, over the loopback binding | **shipped and enforcing** | every `/api/*` route, including CLASS 0 reads and job submission |
| **R2 — action-class refusal** | `utils/action_taxonomy.py::console_refusal()`, surfaced per job type on `GET /api/job-types` | **shipped and enforcing** | which classes may be submitted at all: CLASS 0 yes, everything else refused |
| **R3 — conditioned local enrollment write gate** | `pcp_console_registry_write_gate`, seventeen conditions (companion §9.1) | **decided in direction, unimplemented** (`M9`) | enrollment writes only; loopback only; server exposure blocked on `DEPLOY.1A` |
| **R4 — `OP.2` authorizer** | `utils/operate/authorization.py`, unconditional `deny("authorization_not_configured")` | **shipped**, unconditional deny | **CLASS 2 actions only** |
| **R5 — actor authorization (OIDC/RBAC)** | roles, permissions, scopes, tenants | **absent** — `navigationAuthorizationContext()` returns `model: "none"` | nothing yet; a `NAV.2` amendment at `DEPLOY.1A` |

| Field | Contract |
| --- | --- |
| **Owner** | the regime that governs the action in question — R1/R2 today, R3/R4 where they apply, R5 later. Enforcement is **server-side** in every regime (`AC-EN-14`) |
| **Values** | per action: `PERMITTED`, `DENIED(regime, reason_code)`, `AUTHZ_NOT_EVALUATED` |
| **Meaning** | `AUTHZ_NOT_EVALUATED` means no regime has yet been consulted for this action — it is **not** a value the UI may render as either grant or refusal, and it is **not** the same as "no model exists". Absence of **R5** is not a permissive model and is not a denial of R1/R2-governed actions |
| **Persisted?** | derived per decision; the decision is audited |
| **Scope** | **action-scoped** (actor × action × entity), never capability-scoped |
| **May affect** | **eligibility of the specific action, and its refusal copy** |
| **Must never control** | visibility of any entry, module or tab; and it must never be inferred from `D1`…`D6`, nor may any of them be inferred from it. A **shared or cached capability projection never grants permission** — it is an input to presentation, never to enforcement |
| **Evidence today** | VERIFIED per regime above |

---

## 4. Canonical vocabulary

### 4.1 Composition model — four outputs, one resolver

The seven dimensions are facts. What an operator reads is a **resolution**.
A single flat state was rejected: a capability can be simultaneously usable and
unscheduled, or usable and stale, or policy-disabled *and* refused — and
flattening loses whichever fact loses the tie, which is usually the one the
operator needed.

The correction this revision makes is that a status **label** is not the same
thing as **what is displayed** or **what may be done**. Collapsing those three
is what produced the blanket empty-state rule and the over-broad action
blocking that the review found. The resolver therefore emits **four
independent outputs**:

| Output | Answers | Cardinality |
| --- | --- | --- |
| `primary_status` | what is the headline fact about this capability right now | exactly one `CapabilityState` |
| `qualifiers` | which refinements also hold | zero or more `CapabilityQualifier` |
| `evidence_presentation` | what should the view show | `POPULATED{as_of, completeness, source_trust}` \| `EMPTY{reason}` |
| `action_eligibility[]` | for each declared action, may it run, and if not why | one entry per declared action, each `ELIGIBLE` \| `INELIGIBLE{blocking_reasons[]}` |

**`evidence_presentation` is decided by whether displayable evidence exists —
never by the primary label.** `COLLECTION_FAILED`, `POLICY_DISABLED`,
`DEVICE_DISABLED` and an incomplete `D4` reconciliation all retain and display
whatever evidence was previously collected, subject unchanged to the existing
identity, privacy and raw-evidence contracts (`AGENTS.md` raw-evidence law;
`PRIVACY_AND_DATA_HANDLING.md`). This matches the frozen parent, which already
requires "error state + **last good evidence retained**" for the Failed row
(navigation contract §8).

**`action_eligibility` preserves every blocking reason regardless of the
primary.** A reason is not dropped because a higher-ranked dimension supplied
the headline label.

#### 4.1.1 The resolver's complete input tuple

The resolver is a pure function of **all** of the following. Claiming it
depends only on the seven dimensions would be false, because three qualifiers
and every action decision need more:

| Input | Needed by |
| --- | --- |
| `D1`…`D7` dimension values (§3.3), including D6's four facets `D6a`–`D6d` | the ladder, `STALE`, `PARTIAL`, `SOURCE_TRUST_LIMITED`, `SCHEDULE_*` |
| **member-comparison context** — the logical entity's member set and the per-member value comparison | `MEMBER_SPECIFIC` |
| **the declared action set** for this (surface, capability), each with its `utils.action_taxonomy` class | `ACTION_REFUSED`, `action_eligibility[]` |
| **per-action authorization decision** with its governing regime (R1–R5, §3.3 D7) | `ACTION_DENIED`, `action_eligibility[]` |
| **each action's own declared prerequisite facts** | scoping evidence-based blocking to the actions that actually need the fact (§5.5) |
| **the shell** (exported report vs console) | `evidence_presentation` and which actions are declared at all (§6.8) |

Any input absent or malformed is an `UNKNOWN`-producing condition, never a
permissive default (§5.4).

### 4.2 `CapabilityState` — the primary vocabulary

Ten values. Each has a distinct operator consequence; none exists for symmetry.

| Value | Operator meaning | Distinct consequence | Source dimension |
| --- | --- | --- | --- |
| `NOT_SHIPPED` | this build does not ship the surface | **omitted** — nothing renders; the only omission case | D1 |
| `NOT_APPLICABLE` | this function does not apply to this kind of entity | visible, selectable, names the entity types that do support it, no enabled action | D2 |
| `DEVICE_DISABLED` | the operator disabled this device | visible; re-enable is the one meaningful action; jobs refuse at admission | D4 |
| `UNSUPPORTED` | this vendor/platform cannot do this, and we have evidence of that | visible; states the vendor reason; action disabled; **collecting again will not help** | D3 |
| `NOT_ENROLLED` | observed in evidence, absent from the Device Registry | visible, evidence retained; offers enrollment where `M9`'s gate permits | D4 (`EVIDENCE_ONLY`) |
| `POLICY_DISABLED` | an applicable schedule for this capability is intentionally off for this device | visible, **evidence retained and displayed**; names the policy; offers re-enable; states that **automatic** refresh is off — never that refresh is impossible, and never that manual collection or retry is unavailable | D5 |
| `COLLECTION_FAILED` | the **latest attempt** failed | visible; shows the failure and its time **alongside retained last-good evidence, which stays displayed with its own age**; retry offered **as a new typed job** | D6c |
| `UNKNOWN` | we cannot conclude, and we name which fact is missing | visible; names the missing fact; **only** actions declaring that fact as a prerequisite become ineligible (§5.4) | D3 `SUPPORT_UNKNOWN`, D2 `APPLICABILITY_UNKNOWN`, D4 `REGISTRY_ONLY`/`RECONCILIATION_UNKNOWN`, D6b `no_data`, `INSUFFICIENT_EVIDENCE`, CX1–CX3, or unclassified input |
| `NOT_CONFIGURED` | supported and applicable, and evidence **positively shows** it is not set up | visible; states what configuring it requires; actionable empty state | D6 (positive absence: `UNPROTECTED`, `not_configured`) |
| `AVAILABLE` | usable now | normal presentation, populated view | default |

**Lexical disjointness (mandatory).** No `CapabilityState` value equals any
member of X1 (`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped`)
or X2 (`CREATED`…`OUTCOME_UNKNOWN`). `COLLECTION_FAILED` is deliberately not
`FAILED`; no value is named `BLOCKED` (E2 — `blocked` already carries two
meanings).

### 4.3 `CapabilityQualifier` — non-exclusive

Each qualifier binds to **one** facet and states the evidence that establishes
it. Where the frozen parent's presentation table conflates two facets, the
source semantics govern and the divergence is declared (§2.7, `FA-1`…`FA-4`).

| Value | Established by | Means | Never |
| --- | --- | --- | --- |
| `STALE(as_of)` | **D6a only**: `fresh == false`, with `as_of` taken from `collected_at` / `last_successful_collection` and `stale_reason` carried through | this evidence is older than the current run | never derived from `PROVENANCE_UNVERIFIED` (C9/`FA-1`); never emitted without a real freshness anchor; never removes the view; never authorizes an action (`AC-WS-11`) |
| `PARTIAL` | **D6b**: `data_state == partial` | some expected evidence is present, some is not | never presented as complete; never implies the absent part failed |
| `SOURCE_TRUST_LIMITED` | **D6d**: `PROVENANCE_UNVERIFIED` / `IDENTITY_TRANSLATION_REQUIRED` | the expected-state source is not trusted enough to support an override or drift claim; the row is a `semantic_exclusion` | **never** rendered as an age or staleness claim, and **never** given a timestamp (V12a carries none) |
| `NOT_SCHEDULED` | **D5 `NO_APPLICABLE_SCHEDULE` only** — an authoritative successful read returning no applicable entry | no automatic refresh is currently scheduled | never emitted from `POLICY_UNKNOWN`; never asserts that none has *ever* existed; never implies manual collection is impossible |
| `SCHEDULE_UNKNOWN` | **D5 `POLICY_UNKNOWN`** | scheduling information could not be determined | **never erases or devalues otherwise usable evidence**; never blocks an action that does not depend on scheduling |
| `MEMBER_SPECIFIC` | member-comparison context: values legitimately differ across members of the logical entity | an expected member difference | **never** a warning or failure icon or wording (`AC-DIF-3`) |
| `ACTION_DENIED(regime, reason)` | a consulted authorization regime R1–R5 returning `DENIED` | this actor may not perform **this action** | **action-scoped only**; never affects entry visibility or view selectability; never asserted from `AUTHZ_NOT_EVALUATED` |
| `ACTION_REFUSED(class)` | `utils.action_taxonomy::console_refusal()` | the action's class is not permitted on this surface | action-scoped only; the refusing class is always named (`CON.0` §9) |

`SOURCE_TRUST_LIMITED` earns its place by a distinct operator action —
establish a trusted expected-state source — which is neither "re-collect"
(`STALE`) nor "we cannot conclude" (`UNKNOWN`). `SCHEDULE_UNKNOWN` earns its
place because the alternative is to assert a schedule fact the product cannot
read.

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

Resolution runs in four ordered stages, per **(shell, entity, capability)**.
Stages 3 and 4 are **not** gated by stage 2's outcome — that decoupling is the
correction the review required.

```
stage 1  contradiction gate      -> may force primary UNKNOWN, with a declared scope
stage 2  primary ladder          -> exactly one CapabilityState
stage 3  qualifiers              -> independent of stage 2
stage 4  evidence presentation   -> from displayable evidence only
stage 5  action eligibility      -> per declared action, all blocking reasons kept
```

### 5.1 Stage 1 — contradiction, defined precisely and scoped

The previous draft said "any two dimensions contradict" while also placing
`UNKNOWN` at rank 8 of a first-match ladder. Those cannot both hold: a
first-match ladder never reaches rank 8 once an earlier rank fires. The rule is
replaced by a **finite, closed set of contradiction classes evaluated before
the ladder**, each with a declared scope.

**Simultaneous independent facts are not contradictions.** `UNSUPPORTED` beside
retained evidence is not a contradiction (the evidence may predate a platform
change, or belong to a different capability). `POLICY_DISABLED` beside fresh
evidence is not a contradiction (a manual collection explains it). Only the
three classes below are contradictions, and only because each one means two
sources disagree about **the same fact for the same subject**.

| id | Contradiction | Scope of effect |
| --- | --- | --- |
| **CX1** | the same canonical id resolves to two incompatible entity types or vendors | `primary_status = UNKNOWN(identity_contradiction)`; **evidence withheld** — `AGENTS.md` identity law forbids joining on unproven identity; **all** actions ineligible |
| **CX2** | `D3 = UNSUPPORTED` for this capability while a **successful collection of this same capability for this same entity exists in the current evidence set** | `primary_status = UNKNOWN(support_contradiction)`; **evidence retained and displayed**, labelled; only actions whose prerequisite is the support fact become ineligible |
| **CX3** | `D4 = EVIDENCE_ONLY` while a registry row for the same canonical id exists and is not disabled | `primary_status = UNKNOWN(reconciliation_contradiction)`; **evidence retained and displayed**; the enrollment action alone is withheld |

Anything outside CX1–CX3 is not a contradiction and does not reach this gate.
Every contradiction names **both** disagreeing inputs in its reason; the more
favourable input is never silently chosen.

### 5.2 Stage 2 — the primary ladder

First match wins. `AVAILABLE` is **not** a fall-through: it requires the
positive conjunction at rank 10, and any input that satisfies no rank lands at
rank 11.

| Rank | Condition | Primary |
| --- | --- | --- |
| 0 | a contradiction class CX1–CX3 holds | `UNKNOWN(<class>)` |
| 1 | `D1 = SURFACE_ABSENT` | `NOT_SHIPPED` — omit; evaluate nothing further |
| 2 | `D2 = NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 3 | `D4 = REGISTRY_DISABLED` | `DEVICE_DISABLED` |
| 4 | `D3 = UNSUPPORTED` | `UNSUPPORTED` |
| 5 | `D4 = EVIDENCE_ONLY` | `NOT_ENROLLED` |
| 6 | `D5 = POLICY_DISABLED` | `POLICY_DISABLED` |
| 7 | `D6c = failed` on the latest attempt | `COLLECTION_FAILED` |
| 8 | `D3 = SUPPORT_UNKNOWN`, or `D2 = APPLICABILITY_UNKNOWN`, or `D4 ∈ {REGISTRY_ONLY, RECONCILIATION_UNKNOWN}`, or `D6b = no_data`, or `D6 = INSUFFICIENT_EVIDENCE` | `UNKNOWN(<named missing fact>)` |
| 9 | `D6` **positively** evidences absence of configuration | `NOT_CONFIGURED` |
| 10 | **all** of: `D1 = SURFACE_PRESENT` ∧ `D2 = APPLICABLE` ∧ `D3 = SUPPORTED` ∧ `D4 = RECONCILED` ∧ `D6b ∈ {live, last_known_good, partial}` ∧ `D6c = success` ∧ no contradiction | `AVAILABLE` |
| 11 | anything else — an input absent, malformed, or matching no rank | `UNKNOWN(unclassified_input)` — **fail closed** |

**`D5 = POLICY_UNKNOWN` is deliberately absent from this ladder.** Unknown
scheduling information must not erase otherwise usable evidence; it is carried
as the `SCHEDULE_UNKNOWN` qualifier and affects only schedule-dependent copy
and actions. `D6d` (source trust) is likewise absent — it is a comparability
fact, not a usability fact, and is carried as `SOURCE_TRUST_LIMITED`.

**Why this order.** Rank 1 is the only omission (D-NAV11, `AC-WS-8`)
`[PO-M3-1]`. Rank 2 outranks the rest because no evidence, policy or
enrollment can make a capability apply to a type it does not apply to. Rank 3
above rank 4 because a device the operator disabled is their own most recent,
most reversible act — **contested, §10 dissent D2** — mitigated by
`AC-CS-16`. Rank 4 above 5–7 because `UNSUPPORTED` is the one state where
enrolling, re-enabling a schedule or collecting again is guaranteed not to
help. Rank 8 above rank 9 is a fail-closed requirement: asserting "not
configured" without positive evidence of absence is fabricated certainty
(`AGENTS.md` UNKNOWN law). Rank 11 exists so that malformed input can never
reach rank 10.

### 5.2.1 Stages 3–5 — decoupled from the primary

- **Stage 3, qualifiers.** Each qualifier is evaluated from its own facet
  (§4.3) regardless of which rank fired. `STALE`, `PARTIAL`,
  `SOURCE_TRUST_LIMITED`, `NOT_SCHEDULED`, `SCHEDULE_UNKNOWN`,
  `MEMBER_SPECIFIC` all attach on top of `UNSUPPORTED`, `POLICY_DISABLED` or
  `COLLECTION_FAILED` where they hold, so retained evidence keeps its true
  age, completeness and comparability.
- **Stage 4, evidence presentation.** `POPULATED` whenever displayable
  evidence exists for this (entity, capability) under the existing identity
  and privacy contracts; `EMPTY{reason}` **only** when none does. The primary
  label is not an input to this stage.
- **Stage 5, action eligibility.** Per declared action; §5.5 defines it.

### 5.2.2 Worked resolution — the case the previous draft got wrong

> Registry-enrolled and reconciled Check Point cluster. A configuration
> collection succeeded last week. The operator disabled the schedule.
> Yesterday's manual attempt failed. The expected-state source is untrusted.

- Stage 1: no CX class holds — a disabled schedule beside a failed attempt is
  two independent facts, not a contradiction.
- Stage 2: rank 6 fires → `primary_status = POLICY_DISABLED`.
- Stage 3: `STALE(as_of = last week)` from D6a; `SOURCE_TRUST_LIMITED` from
  D6d — **no timestamp on that qualifier**; no `NOT_SCHEDULED` (a schedule
  exists, it is disabled).
- Stage 4: `POPULATED` — last week's evidence is displayed with its age. The
  **failed latest attempt is shown alongside it**, not instead of it.
- Stage 5: "collect now" and "retry" remain **eligible** — their prerequisite
  is a reachable target and an authorized class-0 submission, not a schedule.
  Any action whose prerequisite is a *trusted expected source* is ineligible,
  with `SOURCE_TRUST_LIMITED` as its reason.

The previous draft would have rendered an empty view and implied no refresh was
possible. Both were wrong.

### 5.3 Authorization — independent, action-scoped, server-enforced

Four rules. The Security seat produced rules 2 and 4 (§10).

1. **Authorization is evaluated per action, independently of the primary
   ladder.** No ladder outcome may suppress, defer or substitute for it.
2. **"Stated last" is a copy rule, not an enforcement order.** When an action
   is ineligible for more than one reason, the authorization reason is always
   **included in the explanation and stated last**, so an operator cannot
   conclude that clearing the other reason would grant the action. Enforcement
   itself is never postponed: the server evaluates the governing regime
   whenever the action is attempted, in whatever order it likes.
3. **Visibility and enablement are never the authorization boundary.** The
   server refuses independently (`AC-EN-14`); an enabled control is not a grant
   and a disabled one is not an enforcement. A future `NAV.2` amendment may
   make D7 a conjunct on *entries*; until then it touches actions only
   (`AC-NAV-8`).
4. **A capability projection never grants permission.** It is shared and may be
   cached (§8.2); a cached or shared projection is an input to *presentation*
   only. No action may be authorized from it, and a projection value must never
   be consulted in place of the governing regime.

**Regime scoping, restated because the previous draft got it wrong.** The
`OP.2` `DenyAllAuthorizer` (R4) governs **CLASS 2 only**. It must not be
described, modelled or implemented as the product-wide authorization state.
CLASS 0 console reads and job submissions are governed by R1 and R2 today and
are **permitted**; the absence of R5 (OIDC/RBAC) neither grants nor denies
them.

### 5.4 Stage 5 — action eligibility

For each declared action the resolver emits `ELIGIBLE` or
`INELIGIBLE{blocking_reasons[]}`. Three rules keep this honest:

1. **Evidence blocks an action only when that action's own declared
   prerequisite is the missing or current fact.** An action does not become
   ineligible merely because *some* dimension is `UNKNOWN` or `STALE`. This is
   the frozen parent's own wording — navigation contract §8, Unknown row:
   *"disabled **if the action needs the missing fact**"* — and `AC-WS-11`'s
   "anything **feeding an action decision** fails closed" is scoped the same
   way.
2. **Evidence-gathering actions are never blocked by the absence of the
   evidence they gather.** First contact, "collect now" and retry remain
   eligible whenever *their own* prerequisites hold — a resolvable target, a
   permitted class, a passing authorization regime. Making the acquisition of
   a missing fact depend on already having it is a deadlock, not a safety
   property.
3. **All blocking reasons are preserved**, whatever the primary label, and each
   names its actual gate (a dimension, a taxonomy class, or an authorization
   regime).

### 5.4.1 What each output may read

| Effect | Permitted inputs | Forbidden inputs |
| --- | --- | --- |
| root / module visibility | D1 only | D2–D7 |
| tab visibility | D1 only `[PO-M3-1]` | D2–D7 |
| action **visibility** | the action contract's existence (D1) + the shell | D7 (an action must not vanish because you lack access) |
| action **eligibility** | D2, D3, D4, D5, D6, D7, X3 — **each only where that action declares the fact as a prerequisite** | a dimension the action does not depend on |
| `evidence_presentation` | existence of displayable evidence, under the identity/privacy contracts | the primary label |
| `primary_status` tone / icon | the resolved primary + qualifiers | colour alone as sole carrier |
| explanatory copy | every input that contributed, named | a reason that is not the actual gate |
| audit / report representation | the resolved state as text | any action affordance in the report (`AC-SH-1`) |

### 5.5 The required hard cases, resolved

Complete rows: every column is populated for every case. "Module" is the global
navigation root; "Tab" is the entity-workspace surface.

| # | Case | `primary_status` | Qualifiers | `evidence_presentation` | Module | Tab | Action visible | Action eligible | Tone | Copy must say |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| H1 | supported, **not authorized** | `AVAILABLE` | `ACTION_DENIED(regime)` | `POPULATED` | unchanged | visible | yes | **no**, for that action only | normal view; refusal tone on the control | who refused, under which regime; never "unavailable" |
| H2 | supported, **not enrolled** | `NOT_ENROLLED` | — | `POPULATED` (evidence is what proves it exists) | unchanged | visible | enrollment, where `M9`'s gate permits | per `M9` gate | `info` | that it is observed but unenrolled, and what enrolling does |
| H3 | supported, **stale evidence** | `AVAILABLE` | `STALE(as_of)` | `POPULATED` with age | unchanged | visible | yes | yes | `muted` badge + timestamp | the age, its anchor and `stale_reason`; never blank |
| H4 | **unsupported**, stale or missing evidence | `UNSUPPORTED` | `STALE(as_of)` if retained data exists | `POPULATED` if any retained; else `EMPTY{no_evidence_collected}` | unchanged | visible | yes | **no**, for support-dependent actions | own `unsupported` token | the **vendor** reason — not "no data"; that collecting again will not help |
| H5 | **policy-disabled and refused** | `POLICY_DISABLED` | `ACTION_DENIED(regime)`; `STALE` if applicable | `POPULATED` — retained evidence is shown | unchanged | visible | yes | **no** for the refused action; **manual collect/retry stay eligible** | `muted` + explicit label | **both** reasons: the policy that disabled automatic refresh **and** the authorization refusal, refusal last |
| H6 | logical entity supported, **one member differs** | `AVAILABLE` | `MEMBER_SPECIFIC` | `POPULATED`, side-by-side member comparison | unchanged | visible | yes | yes | `--member-specific` gold row emphasis | "Expected member difference"; **no** warning icon or failure wording |
| H7 | global module applicable, **selected entity is not** | module `AVAILABLE`; entity view `NOT_APPLICABLE` | — | entity view `EMPTY{not_applicable_to_entity_type}` | **unchanged — never disappears** (`AC-WS-10`) | visible, selectable `[PO-M3-1]` | yes | no | `info` | which entity types support the capability |
| H8 | action visible, **not currently executable** | any | `ACTION_DENIED` and/or `ACTION_REFUSED(class)` | unaffected by the refusal | unchanged | visible | **yes** | no | refusal tone on the control only | the refusing gate, named — never a bare greyed control (`CON.0` §9) |
| H9 | **contradictory** inputs (CX1–CX3) | `UNKNOWN(<class>)` | as they independently hold | CX1 `EMPTY{identity_contradiction}`; CX2/CX3 `POPULATED` | unchanged | visible | yes | CX1 none; CX2 support-dependent only; CX3 enrollment only | `muted` | **both** disagreeing inputs, named |
| H10 | **schedule state unreadable**, evidence fine | `AVAILABLE` | `SCHEDULE_UNKNOWN`; `STALE` if applicable | `POPULATED` | unchanged | visible | yes | yes — nothing schedule-dependent is asserted | normal | that automatic-refresh state could not be determined; **evidence is unaffected** |
| H11 | **untrusted expected source**, evidence fresh | `AVAILABLE` | `SOURCE_TRUST_LIMITED` | `POPULATED`, **no age claim from this qualifier** | unchanged | visible | yes | drift/override-claim actions only are ineligible | `muted`, **no timestamp** | that no override or drift claim can be made from this source; **not** that evidence is old |

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

| `SOURCE_TRUST_LIMITED` | `muted`, **no timestamp** |
| `SCHEDULE_UNKNOWN` | `muted` |

**Distinguishability is by label and reason, not by unique colour.** Several
states legitimately share a tone — `POLICY_DISABLED`, `DEVICE_DISABLED` and
`UNKNOWN` are all `muted`; `NOT_APPLICABLE`, `NOT_CONFIGURED` and
`NOT_ENROLLED` are all `info`. That is correct: tone conveys *severity family*,
and there are fewer severity families than states. What must be unique per
state is the **text label** and the **reason it carries**. `AC-CS-27` is
written to that rule, not to a unique-colour rule.

The one hard palette requirement is the C2 fix: `statusTone()`'s fall-through
`neutral` must stop being the answer for `UNSUPPORTED`, `NOT_APPLICABLE` and
`NOT_CONFIGURED`, because `neutral` is what an *unrecognised* value receives —
a real product statement must never be indistinguishable from a typo.

### 6.5 Empty states — only when there is nothing to display

**There is no rule that a non-`AVAILABLE` primary empties the view.** The
previous draft's blanket rule was wrong, and it contradicted the frozen parent,
which requires "error state **+ last good evidence retained**" for the Failed
row (navigation contract §8).

`evidence_presentation = EMPTY{reason}` **only** when no displayable evidence
exists for this (entity, capability) under the existing identity, privacy and
raw-evidence contracts. Otherwise the view is `POPULATED`, and the status,
retained evidence and latest-attempt outcome are rendered as **three separate
statements**:

| Statement | Source | Example |
| --- | --- | --- |
| what the capability's status is | `primary_status` + qualifiers | "Automatic refresh disabled by policy" |
| what evidence is held, and how old | `evidence_presentation` + `STALE` / `PARTIAL` / `SOURCE_TRUST_LIMITED` | "Showing evidence collected 6 days ago; partial" |
| what the latest attempt did | D6c | "Last attempt yesterday: failed — <reason>" |

A view is never blank merely because a label is not `AVAILABLE`; an empty state
is never shown while retained evidence exists; and last-good evidence, latest
attempt outcome and freshness are never collapsed into one sentence.

Where `EMPTY` **is** correct, it carries three parts: **what is missing**,
**why** (the actual gate, named), and **what would change it** — or an explicit
statement that nothing the operator can do will (the `UNSUPPORTED` case). A
blank panel, a spinner that never resolves, and a bare "No data" each remain
contract violations.

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
- `UNKNOWN_SHELL` or any other *unknown*-branch reason code — it means "shell
  behaviour not yet observed" (V9a), which is the absence of evidence, not
  evidence of absence;
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
| failed collection | **D6c only** (latest-attempt outcome) | **D3, D4, D5, D6a, D6b** — retained evidence keeps its own freshness and completeness | C8; failure implying non-support, unenrollment, or invalidation of retained evidence |
| evidence expiry | **D6a only** (adds `STALE`) | D6d, and all others | staleness implying failure or source distrust |
| expected-source trust change | **D6d only** | D6a, D6b, D6c | C9 — source trust read as evidence age |
| registry reconciliation | D4 | all others | C4 |
| schedule / policy change | D5 | **all others, and never `evidence_presentation`** | C5; policy implying failure or erasing evidence |
| authorization change | D7, action-scoped | **all others** | C6; the authorization conflation |
| logical-member selection | **nothing** — it changes the *scope of presentation* only | every dimension | selection changing product truth (D-NAV11) |
| a new build / shell | D1 | all others | build state leaking into device state |

### 8.2 Fail-closed, honest-unknown, and what persists

1. **Absence of a producer is `UNKNOWN`, not a favourable default and not a
   confirmed absence.** Until `M10` ships D3 it is `SUPPORT_UNKNOWN`; until
   `M12` ships D5 at (entity, capability) granularity it is `POLICY_UNKNOWN`
   — **not** `NO_APPLICABLE_SCHEDULE`, which is a positive finding requiring a
   successful authoritative read.
2. **Contradiction resolves to `UNKNOWN` within its declared scope** (CX1–CX3,
   §5.1), naming both inputs. Never the more favourable one, never a silent
   reconciliation. Facts that merely co-occur are not contradictions.
3. **No R5 authorization model is neither a grant nor a blanket denial.** It is
   `AUTHZ_NOT_EVALUATED` for R5-governed decisions, and it says nothing about
   R1/R2-governed CLASS 0 operations, which are permitted today.
4. **Action blocking is prerequisite-scoped** (§5.4 rule 1). `UNKNOWN`,
   `INSUFFICIENT_EVIDENCE` and `STALE` block an action **only where that
   action declares the missing or current fact as its own prerequisite** —
   the frozen parent's "disabled if the action needs the missing fact". They
   never block evidence-gathering actions (§5.4 rule 2).
5. **Identity joins are on canonical ids only** — never a hostname, display
   label or inferred ordinal (`AGENTS.md` presentation-identity law). CX1
   withholds evidence rather than joining on unproven identity.

#### 8.2.1 Persistence — authoritative records versus derived caches

The previous draft claimed only the registry row and the schedule record
persist. That was wrong on two counts and is corrected here.

| Kind | Examples today | Rule |
| --- | --- | --- |
| **Authoritative durable records** | Device Registry rows (`utils/device_registry.py`); last-known-good evidence (`utils/snapshot.py`, `last_known_good.json`); content-addressed configuration evidence (`utils/config_evidence.py`, `config_storage.py`); console job records; schedule/capability-policy records (future, `M12`) | already durable and **unchanged by `M3`**. A capability state never overwrites or invalidates one |
| **Derived cache — permitted** | the **capability projection** | the frozen `AC-ST-2` explicitly lists *capability projections* among what the approved local store may own. This document therefore **permits** a persisted projection and defines only its validity rules; the schema is `M4`'s and is not designed here |
| **Never persisted as truth** | a resolved `primary_status` / qualifier set presented to an operator | it is a render-time composition over the inputs above |

**Cache validity and invalidation expectations** (contract, not schema):

- a cached projection records **which dimension inputs and which evidence
  generation it was computed from**;
- it is invalidated by any event in §8.1 that may change one of those inputs;
- it **fails closed** on a version or integrity mismatch — a projection that
  cannot be validated is discarded and recomputed, never served stale as truth
  (`AC-ST-6`'s existing fail-closed posture);
- it is **never** an authorization input (§5.3 rule 4);
- it is RuntimeRoot-resident and excluded from the support bundle, per the
  existing `AC-ST-6`.

## 9. Acceptance criteria

Individually numbered, implementable and testable without subjective visual
judgment. Each names the movement expected to own it. A criterion whose surface
does not yet exist is **not waived** — it becomes that movement's criterion.

### 9.1 Coverage contract for the resolver

Replaces the withdrawn "additive matrix" claim. A later movement proves the
resolver over **finite input classes plus named boundary cases**, not over an
unbounded product and not over a hand-picked sample.

| Input | Finite classes to enumerate |
| --- | --- |
| D1 | 2 |
| D2 | 3 |
| D3 | 3 |
| D4 | 5 |
| D5 | 4 |
| D6a freshness | 2 (`fresh` true/false) |
| D6b completeness | 4 |
| D6c outcome | 5 |
| D6d source trust | 3 (none / `PROVENANCE_UNVERIFIED` / `IDENTITY_TRANSLATION_REQUIRED`) |
| member context | 3 (single-member / members agree / members differ) |
| action class | the 5 taxonomy classes |
| authorization | 3 outcomes × 5 regimes, restricted to the regimes that govern each class |
| shell | 2 (report / console) |

Required coverage:

1. **Every rank of the §5.2 ladder fires at least once**, including rank 0 and
   rank 11.
2. **Every contradiction class CX1–CX3** is exercised, and at least three
   *non*-contradictory co-occurrences are asserted **not** to trigger the gate.
3. **Every qualifier** is exercised alone and in combination with at least one
   non-`AVAILABLE` primary.
4. **Named boundary cases**, each asserted individually: `fresh == false` with
   no freshness anchor; `data_state == partial` with `D6c == success`;
   `PROVENANCE_UNVERIFIED` with `fresh == true`; `POLICY_UNKNOWN` with fresh
   evidence; `UNSUPPORTED` with retained evidence; an action whose prerequisite
   set is empty; a malformed dimension value.
5. **Generated combination coverage** over the D1–D6 cross-product for the
   *ladder and evidence-presentation outputs*, which is what makes the
   determinism claim (`AC-CS-12`) falsifiable. Action eligibility is covered
   per (action class × authorization regime × prerequisite-present/absent),
   not by crossing it with the full dimension product.

This is a contract for what must be proven, not an implementation of the tests.

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
| `AC-CS-12` | Resolution is a pure function of the **complete input tuple of §4.1.1** — the seven dimensions including D6's four facets, member-comparison context, the declared action set with taxonomy classes, per-action authorization decisions and their regimes, each action's declared prerequisites, and the shell. The same tuple always yields the same four outputs | `M10` |
| `AC-CS-13` | The §5.2 ladder and `evidence_presentation` are exercised over the generated cross-product of §9.1's finite input classes, plus every named boundary case — not a hand-picked sample | `M10` |
| `AC-CS-14` | Qualifiers are each a pure function of **one facet** (§4.3) and are computed independently of the primary and of each other | `M10` |
| `AC-CS-15` | `UNKNOWN` outranks `NOT_CONFIGURED`: `NOT_CONFIGURED` is emitted only on **positive** evidence of absence | `M10` |
| `AC-CS-16` | `DEVICE_DISABLED` copy names every lower-ranked blocking dimension it masks | `M10` |
| `AC-CS-17` | *(superseded by `AC-CS-65`, which covers the eleven §5.5 hard cases across all four outputs. Id retained so existing references resolve.)* | `M10` |

### Vendor separation

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-18` | No `UNSUPPORTED` reason code is shared between Check Point and Palo Alto; reason codes are vendor-namespaced and never compared across vendors | `M10` |
| `AC-CS-19` | `D3` is never set from any item in §7.3's forbidden list — asserted per item, **including `UNKNOWN_SHELL`, which resolves to `SUPPORT_UNKNOWN`, never `UNSUPPORTED`** (V9a, C10) | `M10` |
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
| `AC-CS-27` | `UNSUPPORTED`, `NOT_CONFIGURED`, `STALE`, `COLLECTION_FAILED` and `UNKNOWN` each keep their view selectable and each carry a **distinct text label and a distinct reason**. Sharing a tone is permitted; being indistinguishable is not. Separately: none of them may resolve to `statusTone()`'s fall-through `neutral`, which is reserved for unrecognised values (closes C2) | `M11` |
| `AC-CS-28` | A `STALE` view is **populated**, with its age and provenance shown; it is never blank | `M11` |
| `AC-CS-29` | An empty state renders **only** when `evidence_presentation == EMPTY`, and then contains what is missing, why, and what would change it (or that nothing will). A non-`AVAILABLE` primary with retained evidence renders `POPULATED` | `M11` |

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
| `AC-CS-41` | With no `D3` producer, every capability resolves `SUPPORT_UNKNOWN` → `UNKNOWN`, never `SUPPORTED` or `UNSUPPORTED`. With no per-(entity, capability) `D5` producer, every capability resolves `POLICY_UNKNOWN`, never `NO_APPLICABLE_SCHEDULE` | `M10`/`M12` |
| `AC-CS-42` | Contradictory dimension inputs resolve to `UNKNOWN` naming **both** inputs | `M10` |
| `AC-CS-43` | `UNKNOWN`, `INSUFFICIENT_EVIDENCE` and `STALE` never read as permitted **at an action decision that declares the missing or current fact as a prerequisite** (`AC-WS-11`, scoped as the frozen parent scopes it). An action that does not declare the fact is unaffected | `M10`/`M7` |

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

### Facet separation (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-54` | `STALE` is emitted **only** from D6a (`fresh == false`) with a real anchor from `collected_at` / `last_successful_collection`; it is never emitted from `PROVENANCE_UNVERIFIED` or any D6d value (C9, `FA-1`/`FA-2`) | `M10` |
| `AC-CS-55` | `SOURCE_TRUST_LIMITED` never renders a timestamp and never renders age wording; `PROVENANCE_UNVERIFIED` carries no timestamp in source (V12a) | `M11` |
| `AC-CS-56` | `UNKNOWN_SHELL` maps to `SUPPORT_UNKNOWN` → `UNKNOWN`, never to `UNSUPPORTED` (V9a, C10, `FA-3`) | `M10` |
| `AC-CS-57` | D6a, D6b, D6c and D6d are independently settable and independently rendered; a test varying one asserts the other three unchanged | `M10` |
| `AC-CS-58` | A failed latest attempt (D6c) leaves retained evidence, its freshness and its completeness unchanged, and the view `POPULATED` | `M10`/`M11` |

### Schedule knowledge (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-59` | `NO_APPLICABLE_SCHEDULE` is emitted only after a **successful authoritative read** returning no applicable entry; a missing producer, a failed read, a malformed store or a granularity mismatch yields `POLICY_UNKNOWN` | `M12` |
| `AC-CS-60` | No output asserts that a schedule has **never** existed; the vocabulary makes only present-tense claims | `M12` |
| `AC-CS-61` | `D5` never changes `evidence_presentation`, and `POLICY_DISABLED` / `SCHEDULE_UNKNOWN` never make a manual collection, first-contact or retry action ineligible | `M10`/`M12` |

### Resolution determinism and scope (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-62` | Contradiction is exactly CX1–CX3; at least three non-contradictory co-occurrences are asserted **not** to trigger the gate | `M10` |
| `AC-CS-63` | Each contradiction class applies only its declared scope: CX1 withholds evidence and blocks all actions; CX2 retains evidence and blocks support-dependent actions only; CX3 retains evidence and withholds enrollment only | `M10` |
| `AC-CS-64` | `AVAILABLE` requires the rank-10 positive conjunction; a malformed, absent or unclassifiable input resolves to `UNKNOWN(unclassified_input)` and never to `AVAILABLE` | `M10` |
| `AC-CS-65` | Every one of the eleven §5.5 hard cases resolves exactly as its row states, across all four outputs | `M10`/`M11` |

### Authorization regime separation (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-66` | The `OP.2` authorizer (R4) is consulted for CLASS 2 only; no CLASS 0 console operation is denied by reference to it | `M14` |
| `AC-CS-67` | Each `ACTION_DENIED` names its governing regime (R1–R5); `AUTHZ_NOT_EVALUATED` is never rendered as either a grant or a refusal | `M11` |
| `AC-CS-68` | A cached or shared capability projection is never an input to an authorization decision (§5.3 rule 4) | `M10`/`M14` |
| `AC-CS-69` | Evidence-gathering actions (first contact, collect now, retry) remain eligible whenever their own prerequisites hold, regardless of the missing evidence they would gather | `M7`/`M8` |

### Persistence (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-70` | Existing durable evidence — registry rows, last-known-good, CAS configuration evidence, job records — is neither invalidated nor overwritten by any capability state | `M4`/`M10` |
| `AC-CS-71` | A persisted capability projection records the dimension inputs and evidence generation it was computed from, is invalidated by any §8.1 event touching those inputs, and fails closed on version or integrity mismatch rather than serving stale truth | `M4`/`M10` |
| `AC-CS-72` | No resolved `primary_status` or qualifier set is persisted as authoritative truth | `M10` |

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

**Disclosure — what this record is, and is not.** The `nexus-decision-council`
skill named in the movement brief is **not installed in this environment**
(`.claude/skills/` contains only `session-start-hook`; no plugin or filesystem
match exists). No installed skill was used and no separately executed panel
ran.

What the seats below record is a **single authoring session's structured
self-critique against a bounded role list** — one agent reasoning from several
stated perspectives in one pass. It is **not** independent execution, **not**
cross-model validation, and **not** evidence that the proposal is correct. It
is included because the recorded dissents are load-bearing (they produced
§5.3's rules and §9.1's coverage contract), not because it carries
verification weight. No transcript is stored in the repository.

**No new council round was run for this revision.** The dissent table below is
carried forward, with D1 amended where this revision withdrew the claim it
rested on.

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
| **D1** | Test & Verification | The primary + qualifier model risks a large composition space if qualifiers are ever allowed to depend on the primary. Preferred a flat single state | Partly accepted. Each qualifier is a pure function of exactly one facet (`AC-CS-14`), which makes **derivation** additive — but this revision withdraws the earlier claim that total coverage therefore reduces to primary-count plus qualifier-count. **Presentation and action composition remain combinatorial**, and §9.1 replaces the arithmetic with finite input classes, named boundary cases and generated combination coverage. **The dissent stands** if a future movement introduces a primary-dependent qualifier |
| **D2** | UX / IA | Ranking `DEVICE_DISABLED` (rank 3) above `UNSUPPORTED` (rank 4) gives a two-step disappointment: the operator re-enables the device only to learn the capability was never supported | Not accepted; `DEVICE_DISABLED` is the operator's own most recent and most reversible act. Mitigated by `AC-CS-16` (its copy must name what it masks). **Dissent stands** and is a legitimate alternative ordering the Product Owner may choose |
| **D3** | Vendor Semantics | `UNSUPPORTED` as a cross-vendor normalized state invites a Check Point conclusion being reused to justify a Palo Alto refusal | Partially accepted: the **state** is shared, the **reason** is vendor-namespaced and never compared across vendors (`AC-CS-18`). The seat holds that a shared state name still exerts pressure toward shared reasons over time |
| **D4** | Control-Plane Architect | `M3` defines a vocabulary before `M10` builds the projection that must populate it; values with no producer risk being unimplementable | Accepted. §3.3 carries an explicit "evidence today" row per dimension, and D3/D5 are marked **no producer**; §8.2 rule 1 makes their absence resolve to `UNKNOWN` rather than a favourable default |
| **D5** | Product Strategist | `NOT_CONFIGURED` and `NOT_ENROLLED` may read as the same thing to an operator ("it isn't set up") | Not accepted: the operator actions differ (configure the capability vs enroll the device) and the owning dimensions differ (D6 vs D4). Copy must make the distinction explicit; **dissent noted** as a copywriting risk, not a model defect |

---

## 11. Open Product Owner decisions

Separated into **defects already corrected** (no decision needed) and
**genuine choices** (decision needed).

### 11.1 Draft defects corrected in this revision — no decision required

Recorded so the reviewer can confirm each, not to ask anything.

| # | Defect in the reviewed draft (`5be42b1`) | Correction |
| --- | --- | --- |
| X1 | `PROVENANCE_UNVERIFIED` treated as evidence age | §2.1 V12a, §2.3, §3.3 D6d, §4.3 `SOURCE_TRUST_LIMITED`, `AC-CS-54`/`55` |
| X2 | `UNKNOWN_SHELL` treated as proof of non-support | §2.1 V9a, §2.3, §7.3, `AC-CS-19`/`56` |
| X3 | missing D5 producer defaulted to `POLICY_UNSCHEDULED` | §3.3 D5 (`POLICY_UNKNOWN` default, `NO_APPLICABLE_SCHEDULE` renamed and evidence-gated), `AC-CS-59`/`60` |
| X4 | blanket empty view for every non-`AVAILABLE` primary | §4.1 `evidence_presentation`, §6.5, `AC-CS-29`/`58` |
| X5 | "any contradiction yields `UNKNOWN`" conflicting with a first-match ladder | §5.1 CX1–CX3 pre-ladder gate with declared scopes, `AC-CS-62`/`63` |
| X6 | resolver claimed to depend on seven dimensions while qualifiers needed more | §4.1.1 complete input tuple, `AC-CS-12` |
| X7 | `AVAILABLE` reachable by fall-through | §5.2 rank 10 positive conjunction + rank 11 fail-closed, `AC-CS-64` |
| X8 | hard-case table columns misaligned and incomplete | §5.5, eleven complete rows, `AC-CS-65` |
| X9 | `DenyAllAuthorizer` implied as the product-wide authorization model | §3.3 D7 regimes R1–R5, §5.3, `AC-CS-66`/`67` |
| X10 | action blocking over-broad; evidence-gathering paths at risk of deadlock | §5.4 rules 1–2, `AC-CS-43`/`69` |
| X11 | "only registry and schedule records persist" | §8.2.1, `AC-CS-70`/`71`/`72` |
| X12 | coverage claimed as primary-count plus qualifier-count | §9.1 finite input classes and boundary cases; §10 dissent D1 amended |
| X13 | `AC-CS-27` demanded unique tone while the palette shared tones | §6.4, `AC-CS-27` rewritten to label-and-reason distinguishability |
| X14 | E3 framed two subsystem job vocabularies as a defect | §2.3 Observation E3 — verified no incorrect mapping exists; claim withdrawn, and the PO question it carried is withdrawn with it |

### 11.2 Genuine choices — decision required

| id | Decision | Recommendation |
| --- | --- | --- |
| **PO-M3-1** | **Tab omission.** The navigation contract contradicts itself: §8/§8.1 permit omitting a structurally inapplicable tab for an entity type; §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` forbid it. Which governs? | **Preserve D-NAV13 / `AC-WS-7` / `AC-WS-8`** — tab stability wins; §8/§8.1's omission language is residual revision-1 text that §6.5 explicitly closed. Exact correction prepared, **not applied** (§11.3 `FA-5`) |
| **PO-M3-2** | **Frozen-parent amendments `FA-1`…`FA-4`** (§2.7): correct the parent's `PROVENANCE_UNVERIFIED`-as-stale and `UNKNOWN_SHELL`-as-unsupported rows. Approve, or require this draft to follow the parent's current wording instead? | **Approve.** Both parent rows contradict verified source semantics (V12a, V9a). If declined, §4.3 and §9 of this draft must be reverted to the parent's wording — the divergence is declared, not silent |
| **PO-M3-3** | **Composition model.** Retain primary + qualifiers + evidence presentation + action eligibility (four outputs), now that resolution is corrected? | **Retain provisionally**, subject to the §5 resolution contract and §9.1 coverage. §10 dissent D1 remains recorded |
| **PO-M3-4** | **Registry/evidence reconciliation shape.** Retain the directional D4 value set, with `RECONCILIATION_UNKNOWN` covering ambiguous or unresolved identity and **no new identity authority**? | **Retain.** The concept is two-sided; a flat `NOT_ENROLLED` cannot express the reverse. Ambiguous identity resolves to `RECONCILIATION_UNKNOWN` or CX1, never to a guessed join (`AGENTS.md` identity law; `AC-WS-2`) |
| **PO-M3-5** | **`--member-specific` token.** Approve decoupling the gold member emphasis from `--warning`, preserving the emphasis and its non-fault semantics? | **Approve as contract direction**; implementation belongs to a later UI movement |
| **PO-M3-6** | **`NOT_SCHEDULED` as a qualifier**, emitted only where schedule absence is authoritatively established? | **Retain as a qualifier**, gated on `NO_APPLICABLE_SCHEDULE`. Schedules govern future automatic refresh, not current usability |

**Withdrawn from the previous revision.** The job-lifecycle-convergence
question is withdrawn: §2.3 verifies that the two vocabularies are correctly
separated in every renderer and that no incorrect mapping exists. Convergence
is not proposed and is not a prerequisite for `M3`.

**Reported for awareness, no decision requested.** C3 — compliance `UNKNOWN`
counting in the alignment denominator — is inside the compliance contract's
domain, not `M3`'s, and is unchanged here.

### 11.3 Prepared frozen-parent corrections — NOT APPLIED

`FA-1`…`FA-4` are in §2.7. One more follows from `PO-M3-1`:

| # | File / clause | Proposed amendment |
| --- | --- | --- |
| **FA-5** | `NAVIGATION_INFORMATION_ARCHITECTURE.md` §8 *Not applicable* row (l.630) and §8.1 *"Omit a tab for an entity type"* row | Replace both with the §6.5/D-NAV13 rule: the tab stays **visible and selectable**, renders `NOT_APPLICABLE`, names the entity types that support it, and offers no enabled action. Add a pointer to §6.5 so the two tables cannot drift again |

All five are **prepared, not applied**. No frozen document is edited by this
movement.

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
