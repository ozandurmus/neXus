# Capability-state vocabulary and presentation contract (`M3`)

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-06.** Movement `M3`, reviewed on
branch `claude/capability-state-vocabulary-cube5f`. This document is
**implementation authority** for the capability-state vocabulary, the
resolution contract and the presentation matrix (`AGENTS.md` "Authority
hierarchy" item 2).

All six Product Owner decisions are **CLOSED / APPROVED** (§11.2). All nine
frozen-parent amendments `FA-1`…`FA-9` are **APPLIED** to
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (§2.7), which carries its
own amendment record.

**What this freeze does and does not do.** It fixes the vocabulary, the
resolution algebra and the presentation contract so later movements implement
against a settled shape. It **does not** authorize an implementation movement:
`M4`…`M14` remain separately authorized and none is begun by this freeze. No
runtime, UI, payload, registry, storage, authorization or job-lifecycle change
is made or authorized here.

**Normative model.** A tagged union `RESOLVED{primary_status,
capability_qualifiers, evidence_presentation, action_affordance}` |
`OMITTED{reason, diagnostic}`; `NOT_SHIPPED` is a `SurfaceOmissionReason`, not
a `CapabilityState`; `action_affordance[]` is per-action and
presentation-time-only, never claiming an `E7` check has passed; `E4` is total
over four outcomes including `NO_APPLICABLE_AUTHORITY`; `CX1` is a
subject-scoped unsafe contradiction while `RI-1`/`RI-2` are bounded
inconsistencies; `RI-1` comparability is total over `K1`–`K6`. Acceptance
criteria `AC-CS-1`…`97`.

**Open implementation obligation, not an approval gate.** `UCQ-1` (§5.5.4) —
which concrete `UNSUPPORTED` reason codes are `REVALIDATABLE` and which are
`TERMINAL` — is owned by `M10`. It does **not** block this freeze: `M3` defines
deterministic fail-closed fallback behaviour for an absent, malformed or
unknown remediation class, so the contract is complete without it.

**Revision history, historical.** Eight revisions before the freeze; the
per-defect record with clause references is at §11.1 (`X1`–`X49`), with full
detail in `project/build_history.json`. Earlier revisions used models
(`G1`–`G4` gates, `R1`–`R5` "authorization regimes", `CX2`/`CX3`,
`eligible: true|false`) that are **not normative here** and survive only inside
clearly-labelled historical notes and defect records. The council dissents at
§10 are preserved as **historical design dissent**; they are not open approval
gates.

## Decision grades used throughout

| Grade | Meaning |
| --- | --- |
| **VERIFIED** | read directly from repository source, tests or a frozen contract this session; the citation is given |
| **FROZEN** | already decided by a frozen contract or `AGENTS.md`; carried, never reopened |
| **APPROVED** | decided by the Product Owner at this freeze; §11.2 records each decision |
| **APPLIED** | a frozen-parent amendment carried into `NAVIGATION_INFORMATION_ARCHITECTURE.md` at this freeze (§2.7) |
| **UNKNOWN** | not established by repository evidence; stays `UNKNOWN` (`AGENTS.md` UNKNOWN law) |

---

## 0. How to read this document

§2 is the evidence base — every later claim traces to it. §3–§8 are the
**normative contract**. §9 is the falsifiable acceptance criteria a later
movement implements. §10 records the advisory council as historical design
dissent. §11 records the closed Product Owner decisions and the applied
frozen-parent amendments.

A reader who only wants the answer reads §4.1 (the result algebra) and §5
(the resolution contract).

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

### 2.7 Frozen-parent amendments `FA-1`…`FA-9` — **APPLIED**

`AGENTS.md` forbids *silently* amending a frozen contract. These nine
amendments were put to the Product Owner, **approved** under `PO-M3-1`,
`PO-M3-2` and `PO-M3-3`, and **applied** to
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` at this freeze; that
document carries its own amendment record naming `M3` and the approving
decisions. Each row records the clause amended, the rule it replaced, the
conflict resolved, the correction now in force, and its owning decision.

Producer semantics were verified before any label, severity or presentation is
proposed (V9a, V11, V12a in §2.1). Freshness claims are made only where actual
freshness evidence exists. Source-trust wording never suppresses an
independently valid collection timestamp and never implies that provenance
uncertainty makes a timestamp stale.

| # | Parent clause amended | Wording replaced | Conflict resolved | Correction now in force | Owner | Status |
| --- | --- | --- | --- | --- | --- | --- |
| **FA-1** | nav contract §8, l.627 (**Stale** row) | *"`last_known_good`; `STALE`; `PROVENANCE_UNVERIFIED` \| shown \| **selectable**, data shown **with age + provenance** \| enabled, with age stated \| muted badge + timestamp; never blank"* | `PROVENANCE_UNVERIFIED` carries no age and **no timestamp** (V12a). The row requires both. Its Action cell also reads unconditionally "enabled" — the same defect `FA-7` names | strike `PROVENANCE_UNVERIFIED` from the Stale row and add a new row **in the parent's own six-column shape** (UX semantic \| canonical states \| Nav entry \| View/tab \| Action \| Presentation): *"**Source trust insufficient** \| `PROVENANCE_UNVERIFIED` \| shown \| selectable, states that no override/drift claim can be made from this source \| **not blocked by this state** (per `FA-7`) \| muted, **no timestamp claim**"*. The Action column uses `FA-7`'s corrected semantics, so the two amendments are consistent | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-2** | nav contract §5.4, l.445 | *"Stale or incomparable evidence \| `PROVENANCE_UNVERIFIED`, `last_known_good` \| muted / provenance \| \"Stale evidence\" + timestamp"* | "stale" and "incomparable" are two different facts sharing one row, one label and one timestamp requirement | split: `last_known_good` keeps "Stale evidence" + timestamp; `PROVENANCE_UNVERIFIED` takes a label asserting no age | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-3** | nav contract §8, l.629 (**Unsupported** row) | *"`UNSUPPORTED(reason)` … ; `UNKNOWN_SHELL`"* | `UNKNOWN_SHELL` is the *unknown* branch of `plan_collection` (V9a), sibling to `INSUFFICIENT_EVIDENCE`. Treating it as a support conclusion inverts the UNKNOWN law | strike `UNKNOWN_SHELL` from the Unsupported row; it belongs in the existing §8 **Unknown / insufficient** row (l.636) | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-4** | `AC-DIF-7`, l.995 | *"Stale or incomparable evidence uses muted/provenance semantics **with its timestamp**"* | binds a timestamp requirement to classifications that have none (`D6d`, `D6e`) | require the timestamp **only** where a freshness anchor exists (`collected_at` / `last_successful_collection`); provenance/comparability semantics without a timestamp otherwise | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-5** | nav contract §8 *Not applicable* row (l.630) and §8.1 *"Omit a tab for an entity type"* | tab **may** be omitted for an entity type | contradicts §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8`, which the parent itself says closed this ambiguity, and contradicts D-NAV11 tab stability | replace both with the §6.5/D-NAV13 rule (tab stays visible and selectable, renders `NOT_APPLICABLE`, names supporting entity types, no enabled action); add a cross-pointer so the tables cannot drift again | **`PO-M3-1`** | **APPLIED** 2026-09-06 |
| **FA-6** | nav contract §8 **Failed** row vs the §8.1 empty-state grouping | Failed row says *"error state + last good evidence retained"*; §8.1 groups **failed** under *"Keep selectable + explanatory empty state"* | one state is required to retain and display last-good evidence **and** to present an explanatory empty state — mutually exclusive presentations | make §8.1's grouping conditional: an explanatory empty state applies **only when no displayable evidence is retained** | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-7** | nav contract §8 **Action** column, unconditional cells — e.g. Stale *"enabled, with age stated"*, Skipped *"enabled"* | states an action is **enabled**, unconditionally, as a property of the capability state | an action's affordance is never a property of the capability state alone, and no per-state row can express it: it is per-action, and it is split across phases. An unconditional "enabled" cell would read as granting an action past its taxonomy, authorization and admission gates | reword the Action column to ask only **"does this capability state contribute a presentation-time blocker?"** — the one question a per-state row can answer — and add a note that `action_affordance` is resolved per action from the **presentation-time gates** (`E1`–`E3`, `E5`, `E6`, and `E4`'s evaluable part), while submission, admission and immediately-before-execution authority, **`E7` included, remains server-owned** and outside any presentation-time cell | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-8** | nav contract §5.4, contradictory/unsafe row: *"`IDENTITY_TRANSLATION_REQUIRED`, `RELATIONSHIP_INCONSISTENT` … danger/error … 'Contradictory evidence'"* | groups an identity-**translation** requirement with unsafe contradictory state under one **danger/error** treatment and one "Contradictory evidence" label | `IDENTITY_TRANSLATION_REQUIRED` is a `semantic_exclusion` (V12a producer set) — a comparability limitation meaning the two sides use different identifier representations. It is **not** a contradiction: `CX1` (incompatible type/vendor resolution) is a distinct, narrower condition. Rendering it as danger overstates severity and conflicts with `AC-DIF-8`'s "red is reserved for actual fault" | separate the two: `IDENTITY_TRANSLATION_REQUIRED` takes comparability semantics (muted, "identity translation required", its own reason code preserved); genuine contradictory/unsafe state (`CX1`, `RELATIONSHIP_INCONSISTENT`) keeps danger/error and the "Contradictory evidence" label | `PO-M3-2` | **APPLIED** 2026-09-06 |
| **FA-9** | nav contract §8 table shape (one **UX semantic** row → one nav/view/action/presentation tuple) | each row collapses status, view content, action enablement and presentation into a single state's row | this draft demonstrates the result is a **tagged union** (§4.1) whose `RESOLVED` variant carries four independent outputs: `primary_status`, `evidence_presentation` and `action_affordance` vary independently for the same state — `H4a`/`H4f`/`H5a`/`H5c`/`H9a`/`H9a-t`/`H11a`/`H11c`, and scope test `S-RI-1`, each show a case the one-row-per-state shape cannot express | keep §8 as the **UX-semantic index** it is, and add one sentence stating that a row's View and Action columns are *defaults for that state*, resolved finally by the capability-state resolution contract | **`PO-M3-3`** | **APPLIED** 2026-09-06 |

**All nine are APPLIED.** The parent's §5.4, §8, §8.1 and `AC-DIF-7` now carry
the corrected semantics, and this document's §4.3, §5, §6 and §9 are aligned to
them. The declared divergence earlier revisions carried is **closed**: no
conflict remains between this contract and its frozen parent, and no acceptance
criterion is blocked by one.

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
| **Values** | `SURFACE_PRESENT`, `SURFACE_ABSENT`, `SURFACE_ELIGIBILITY_UNRESOLVABLE` |
| **Meaning** | `SURFACE_PRESENT` — **positively established** that all three of navigation contract §7.1 hold: a declared shipped read/action contract exists, this shell is permitted to expose it, and the surface is actually present. `SURFACE_ABSENT` — **positively established** that at least one does not hold. `SURFACE_ELIGIBILITY_UNRESOLVABLE` — the question could not be answered: the input was missing, malformed, or the shell integrity check could not run |
| **Evidence** | the build itself (a payload builder, an API route, a registry job type) plus the shell integrity check. Both `SURFACE_PRESENT` and `SURFACE_ABSENT` require a **positive** determination; neither is a default |
| **Persisted?** | derived, per render; never persisted |
| **Scope** | **build- and shell-scoped**. Never entity-, member-, evidence- or actor-scoped |
| **May affect** | the **surface disposition** (§5.0) — and it is the only input that may |
| **Must never control** | anything conditioned on the selected entity, evidence, policy, enrollment or authorization. It must never be reused as a permission proxy (D-NAV9) |

**The third value is the correction this revision makes.** The previous
revision had two values and mapped a missing or malformed input to
`NOT_SHIPPED` — a **positive product claim** ("this build does not ship this
surface") asserted from the *absence* of an answer. That is exactly the
fabricated certainty `AGENTS.md`'s UNKNOWN law forbids. An unresolvable `D1`
now has its own value, and §5.0 gives it a disposition that omits the surface
without asserting anything about the build.

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
| **Meaning** | `RECONCILED` — enrolled **and** observed. `EVIDENCE_ONLY` — observed in evidence, absent from the registry (`PO-NAV-7`'s "not enrolled"). `REGISTRY_ONLY` — an enrolled row with **no observation in the current evidence set**. It asserts nothing about history: no producer contract retains observation history, so "never observed" would be fabricated. `REGISTRY_DISABLED` — an enrolled row the operator disabled (existing V10 `DISABLED`). `RECONCILIATION_UNKNOWN` — the registry or the evidence side could not be read |
| **Evidence** | the registry file and the merged evidence model, joined on the canonical id — never on a hostname, label or inferred ordinal (`AGENTS.md` presentation-identity law) |
| **Persisted?** | the registry row is persisted (existing, unchanged); the **reconciliation is derived** and persists nothing new |
| **Scope** | **entity-scoped** |
| **May affect** | explanatory copy; offering the enrollment affordance where `M9`'s gate permits; and job admission refusal for `REGISTRY_DISABLED` (`AC-ST-4`, existing) |
| **Must never control** | visibility of any root, module or tab; and it must **never** be written or changed by a reachability result or a collection failure (C8, §8.1) |
| **Evidence today** | registry side VERIFIED (`PCP.1`, shipped); the join is **not implemented**. `M10`/`M9` era |

Each value earns its place by a distinct operator consequence:
`EVIDENCE_ONLY` → offer enrollment; `REGISTRY_ONLY` → run first contact or collection;
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
| **Persisted?** | the schedule record is **persisted** — durable product state, never a UI preference (companion contract §10.2: "Navigation state — rejected outright") |
| **Scope** | **(entity, capability)-scoped** |
| **May affect** | explanatory copy; the presence of an enable/create-schedule affordance; an honest statement about **automatic** refresh |
| **Must never control** | visibility; the **presence or usability of already-collected evidence**; the availability of **manual** collection, first contact or retry, which are independent paths (companion contract §10.1 lists "collect now" and a manual retrieval action separately from schedules). It must **never** be derived from `LifecycleState.EXCLUDED`, Device Registry `DISABLED`, or the global scheduler's default-disabled posture — those are three different facts (E2) |
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

#### D6 — Evidence state *(five independent facets, not one axis)*

Provenance, freshness, completeness, collection outcome and identity
translation are **five separate facts**. They frequently co-occur, and none
implies another. `M3` renames none of them and binds to the owning subsystems'
existing fields.

| Facet | Owner | Values / fields | Means | Does **not** mean |
| --- | --- | --- | --- | --- |
| **D6a — freshness** | `utils/snapshot.py::_status` | `fresh: bool`, `collected_at`, `last_successful_collection`, `stale_reason` | how old this evidence is, against an explicit anchor | anything about whether the source was trusted |
| **D6b — completeness** | `utils/snapshot.py::_status` | `data_state` ∈ `live` / `last_known_good` / `partial` / `no_data` | how much of the expected evidence is present | that the missing part failed, or that the present part is stale |
| **D6c — collection outcome** | run telemetry; `FactState.COLLECTION_FAILED` (V17); `Outcome` (V18) | `success` / `failed` / `unsupported` / `capability_gap` / `identity_mismatch` | what happened on the **last attempt** | that previously retained evidence is invalid or must be hidden |
| **D6d — expected-source trust** | `configuration/pan_setting_alignment.py` (V12a) | `PROVENANCE_UNVERIFIED`, with its source reason code | the **expected-state source** was not trusted enough to support an override or drift **claim**; the row is a `semantic_exclusion` | **not** age, **not** staleness, **no** timestamp; and **not** a statement about identity |
| **D6e — identity translation** | the same producer set (V12) | `IDENTITY_TRANSLATION_REQUIRED`, with its source reason code | the two sides are describing the same subject under **different identifier representations**, so the comparison cannot proceed until a proven translation exists; also a `semantic_exclusion` | **not** untrusted provenance, **not** age, and **not** an identity *contradiction* — that is `CX1`, and only when its own closed conditions independently hold |

**Why D6d and D6e are separate.** The previous revision collapsed both into one
`SOURCE_TRUST_LIMITED` meaning. They differ in every dimension that matters:

| | `D6d` `PROVENANCE_UNVERIFIED` | `D6e` `IDENTITY_TRANSLATION_REQUIRED` |
| --- | --- | --- |
| **Established fact** | the expected-state source's confidence is below the trust threshold | the identifier representations on the two sides do not correspond directly |
| **Operator explanation** | "the source of the expected state is not trusted enough to claim a difference" | "the two sides identify this subject differently; the comparison needs a proven translation" |
| **Remediation** | supply or configure a trusted expected-state source | establish a proven identifier translation, or accept that the comparison stays unevaluable |
| **Potential severity** | bounded — a comparison is withheld; nothing is asserted about the subject | potentially higher — an unproven translation is an identity question, and `AGENTS.md`'s opaque-identifier law forbids inventing an equivalence to make evidence match |
| **Never** | implies age | implies untrusted provenance, or a genuine identity contradiction |

Neither becomes `CX1`. A contradiction requires two sources to disagree about
the same fact for the same subject under the closed `CX1` conditions (§5.1);
a representation difference the product has not translated is an
**unevaluable comparison**, not a disagreement.

| Field | Contract |
| --- | --- |
| **Owner** | the existing evidence/snapshot projections — `utils/snapshot.py` (V11), `utils/config_ui.py` (V12), `utils/config_history.py` (V14). **Already exist; `M3` renames nothing** |
| **Evidence** | the collection run and its manifest |
| **Persisted?** | **persisted** — last-known-good state and content-addressed configuration evidence are durable today (`utils/snapshot.py` `last_known_good.json`; `utils/config_evidence.py` / `config_storage.py`) |
| **Scope** | per (entity, capability, fact) — device- and member-scoped where the underlying evidence is |
| **May affect** | tone, explanatory copy, timestamps **where a freshness anchor exists**, and the eligibility of actions that require the missing or current fact |
| **Must never control** | the surface disposition (`AC-WS-9`); promotion into `D3` (a failed collection is not proof of non-support); and **neither D6d nor D6e may be rendered as, or converted into, a freshness claim** — `STALE` is derived from D6a alone |
| **Evidence today** | VERIFIED, shipped and mature. D6a's four fields already exist, so freshness is **evidenced, not inferred** |

#### D7 — Authorization *(actor authorization only)*

**`D7` describes authorization and nothing else.** A bearer-token session gate,
a closed-registry membership check and a taxonomy/surface admissibility rule
are **server-owned enforcement gates** (§3.4), not actor authorization. They
keep their full enforcement power; they simply are not `D7`. *(Historical note,
non-normative: an earlier revision listed five "authorization regimes" that
folded those gates into `D7`, which overstated what the product decides about
actors and caused the taxonomy rule to be evaluated twice.)*

| Field | Contract |
| --- | --- |
| **Owner** | the **authorization authority applicable to the action in question**. Today exactly one exists: `utils/operate/authorization.py`, which governs **CLASS 2 actions only** and returns an unconditional `deny("authorization_not_configured")`. `DEPLOY.1A`'s OIDC/RBAC would add an actor-authorization authority where none exists today |
| **Values** | per action, one of four: `NO_APPLICABLE_AUTHORITY`, `PERMITTED`, `DENIED(authority, reason_code)`, `AUTHZ_NOT_EVALUATED(authority)` |
| **Meaning** | see the four-outcome table below. The algebra is **total**: every action resolves to exactly one outcome, including the common current case where *no actor-authorization authority applies at all* |
| **Persisted?** | derived per decision; the decision is audited |
| **Scope** | **action-scoped** (actor × action × entity), never capability-scoped |
| **May affect** | the eligibility and refusal copy of **that one action**, recorded under that action's id (§4.1) |
| **Must never control** | the surface disposition or any view/tab visibility; and it must never be inferred from `D1`…`D6`, nor may any of them be inferred from it. A shared or cached capability projection **never grants permission** |
| **Evidence today** | VERIFIED. `OP.2` `DenyAllAuthorizer` = CLASS 2 only, unconditional deny. `navigationAuthorizationContext()` returns `model: "none"` — there is no actor-authorization model, which is **neither an automatic grant nor a blanket denial** |

**The four outcomes.**

| Outcome | Holds when | Contributes | Is a grant? |
| --- | --- | --- | --- |
| `NO_APPLICABLE_AUTHORITY` | **no** actor-authorization authority applies to this action at all — today the case for every CLASS 0 console action, since `OP.2`'s authorizer governs CLASS 2 only and no OIDC/RBAC exists | **nothing — non-blocking** | **No.** It records that authorization was not a question here, not that permission was given. The action's other gates `E1`–`E3`, `E5`–`E7` still decide it |
| `PERMITTED` | an applicable authority was consulted and allowed the action | nothing | it is an authorization allowance, still subject to the other gates and to `E7` at its own phases |
| `DENIED(authority, reason)` | an applicable authority was consulted and **refused** | a blocking reason | no — a refusal |
| `AUTHZ_NOT_EVALUATED(authority)` | a **real, named, applicable** authority exists but **was not consulted** | an *undetermined* reason, not a blocker | no — and not a refusal either |

**`AUTHZ_NOT_EVALUATED` is valid only when a real, named, applicable authority
exists.** It must never stand in for "no authority applies" (that is
`NO_APPLICABLE_AUTHORITY`) and never for "this action has no implemented
contract" — an undeclared or unimplemented action produces **no affordance
entry at all** (§4.1.1), not an undetermined one.

**What the absence of OIDC/RBAC does and does not mean.** For a **CLASS 0**
action it means `NO_APPLICABLE_AUTHORITY`: `D7` contributes nothing and the
action rests on the other gates, which are shipped and enforcing. CLASS 0
console reads and job submissions are permitted today and this document does
not change that. For a **CLASS 2** action the applicable authority exists and
denies unconditionally. Nothing here treats the missing future model as a
grant, and nothing treats it as a blanket denial of CLASS 0.

### 3.4 Server-owned enforcement gates that are **not** authorization

These participate in **action affordance** (§5.4) with full enforcement power,
and `E7` additionally governs the server phases outside it. They are
listed here so that none of them is relabelled as actor authorization, and so
that each is evaluated **exactly once** (§5.4). Seven distinct concepts:

| id | Concept | Authoritative source today | What it decides | Not |
| --- | --- | --- | --- | --- |
| **E1** | **request/session authenticity and origin enforcement** | `console/app.py::_require_api_auth` — bearer launch token, `Origin` / `Sec-Fetch-Site` checks, loopback binding | whether this *request* may be served at all | not an actor-authorization decision; it authenticates the session, it does not evaluate who may do what |
| **E2** | **action identity and closed-registry membership** | `console/registry.py::JOB_REGISTRY` (module-level, not extensible at runtime); the surface's action declarations | whether the requested action is a known member of a closed vocabulary | not authorization, not taxonomy |
| **E3** | **taxonomy / surface admissibility** | `utils/action_taxonomy.py::console_refusal()` over the action's `ActionClass` | whether an action of this class may be submitted on this surface at all | not actor authorization, and **not** evaluated a second time inside `D7` |
| **E4** | **the action's applicable authorization authority** | `D7` (§3.3), four-valued and total | whether an authority applies at all and, if so, its outcome | not a re-evaluation of `E3`; and `NO_APPLICABLE_AUTHORITY` is not a grant |
| **E5** | **subject/target integrity required by that action** | the action's own contract; canonical-id resolution | whether the action's subject is resolvable and undisputed **where its contract requires one** | not a prerequisite that may be invented for a targetless action |
| **E6** | **action-specific semantic prerequisites** | the action's own declared prerequisite facts | whether the facts this action needs are present and current enough | not a general evidence gate over unrelated dimensions |
| **E7** | **admission / execution-time live checks** | the admission coordinator; the registry, read at admission **and again immediately before execution** (`AC-ST-4`) | whether the action may proceed **now**, against live authority | **not** satisfiable by a cached projection, and not replaced by anything `M3` defines |

**`E3` and `E4` are disjoint and are each evaluated once per decision phase.**
`E3` is the single evaluation of taxonomy admissibility; `D7`/`E4` never
re-reads it. *(Historical note, non-normative: earlier revisions of this draft
used a `G1`–`G4` gate set and an `R1`–`R5` "authorization regime" list in which
the taxonomy was evaluated twice. **Neither model is normative here**; `E1`–`E7`
and the four-valued `E4` above replace both entirely.)*

**`E7` is authoritative, and `M3` defines nothing that weakens it.** Everything
this document produces is advisory presentation. A live registry check at
admission and immediately before execution is unchanged, and a target that has
become disabled or unresolvable causes refusal or abort **before contact**.

---

## 4. Canonical vocabulary

### 4.1 Result algebra — a tagged union, not four always-present outputs

Resolution is a **tagged union**. Exactly one variant is produced, and the
variants carry disjoint payloads:

```
CapabilityResolution =
  | RESOLVED {
        primary_status        : CapabilityState          # 9 values, §4.2
        capability_qualifiers : set<CapabilityQualifier> # §4.3
        evidence_presentation : POPULATED{...} | EMPTY{reason}
        action_affordance     : list<ActionAffordance>   # keyed by action_id, §4.1.1
    }
  | OMITTED {
        reason     : SurfaceOmissionReason               # NOT_SHIPPED
                                                         # | SURFACE_ELIGIBILITY_UNRESOLVABLE
        diagnostic : optional<Diagnostic>                # §4.1.4
    }

SurfaceOmissionReason = NOT_SHIPPED | SURFACE_ELIGIBILITY_UNRESOLVABLE
```

**`OMITTED` carries no capability output.** It has no `primary_status`, no
qualifiers, no evidence presentation and no action affordance — not as empty
collections, not as null placeholders, not as a defaulted state. The previous
revision said the outputs were absent while also placing
`primary_status = NOT_SHIPPED` inside the omission record; both could not be
true, and the union above resolves it.

**`NOT_SHIPPED` is no longer a `CapabilityState`.** It is a
`SurfaceOmissionReason`, because it can only ever occur where no capability was
resolved. `CapabilityState` therefore contains **only states that can actually
occur under `RESOLVED`** (§4.2, nine values).

| Variant | Produced when | Payload | Asserts |
| --- | --- | --- | --- |
| `RESOLVED` | `D1 = SURFACE_PRESENT` | the four capability outputs | that this build ships the surface, plus whatever the capability outputs say |
| `OMITTED{NOT_SHIPPED}` | `D1 = SURFACE_ABSENT`, **positively established** | reason + optional diagnostic | the positive fact that this build does not ship the surface |
| `OMITTED{SURFACE_ELIGIBILITY_UNRESOLVABLE}` | `D1` missing, malformed or otherwise unresolvable | reason + optional diagnostic | **nothing about what the build ships.** Only that eligibility could not be determined and the surface was therefore withheld |

#### 4.1.1 `action_affordance[]` — presentation-time state, never execution permission

The fourth output is **not** an eligibility verdict, because no render-time
result can assert that every gate `E1`–`E7` has passed: `E7` covers admission
and a check performed **immediately before execution**, neither of which has
happened yet. *(Historical note, non-normative: an earlier revision emitted
`eligible: true|false` and made exactly that false claim.)*

```
ActionAffordance = {
    action_id            : <closed-registry id>          # always present
    affordance           : AVAILABLE_FOR_SUBMISSION      # no presentation-time blocker is known
                         | DISABLED_KNOWN_BLOCKER        # a presentation-time blocker exists
                         | UNDETERMINED                  # a required presentation-time decision
                                                         # could not be made
    blocking_reasons     : list<{gate, code, detail}>    # non-empty iff DISABLED_KNOWN_BLOCKER
    undetermined_reasons : list<{gate, code, detail}>    # non-empty iff UNDETERMINED
}
```

| Value | Means | Explicitly does **not** mean |
| --- | --- | --- |
| `AVAILABLE_FOR_SUBMISSION` | no presentation-time blocker is known for this action | **not** permission to execute, and **not** a claim that admission or the pre-execution check will pass |
| `DISABLED_KNOWN_BLOCKER` | a blocker evaluable now is known, and named | not a claim that it is the only blocker |
| `UNDETERMINED` | a required presentation-time decision — typically authorization — could not be made | **not** a refusal, and not a grant |

**Two boundaries, stated explicitly.**

1. **Presentation resolution reports only facts evaluable at that time.** It
   never claims a future check has passed.
2. **Server submission, admission and immediately-before-execution checks
   remain authoritative** and are re-evaluated in their own phases. An
   `AVAILABLE_FOR_SUBMISSION` control is **advisory**; it is never an execution
   grant.

Rules:

- **Every entry identifies its `action_id`**; there is no unkeyed entry.
- `ACTION_DENIED`, `ACTION_REFUSED`, `AUTHZ_NOT_EVALUATED` and
  `NO_APPLICABLE_AUTHORITY` appear **only** inside an `ActionAffordance`, never
  in `capability_qualifiers`.
- **Every reason is preserved** — one action's blocker never removes or masks
  another's, and none is dropped because a higher-ranked dimension supplied the
  headline label.
- `capability_qualifiers` carries only capability/evidence facts: `STALE`,
  `PARTIAL`, `SOURCE_TRUST_LIMITED`, `IDENTITY_TRANSLATION_REQUIRED`,
  `NOT_SCHEDULED`, `SCHEDULE_UNKNOWN`, `MEMBER_SPECIFIC`.
- An action **not declared** on this surface produces **no entry at all** — not
  an `UNDETERMINED` one.

#### 4.1.2 Which gates are evaluable at presentation time

| Gate | Evaluable at render? | Notes |
| --- | --- | --- |
| `E1` request/session authenticity | **yes** — the render request itself carries it | re-evaluated on the submission request, which is a different request |
| `E2` action identity + closed-registry membership | **yes** | static |
| `E3` taxonomy / surface admissibility | **yes** | static per (class, surface) |
| `E4` applicable authorization authority | **partly** — `NO_APPLICABLE_AUTHORITY` and a standing unconditional denial are evaluable; an actor decision requiring submission context is not, and yields `UNDETERMINED` | |
| `E5` subject/target integrity | **yes**, for the integrity facts already resolved (identity dispute, unresolvable subject) | the live registry state is `E7`'s |
| `E6` action-specific semantic prerequisites | **yes** | from the same evidence the capability outputs use |
| `E7` admission / immediately-before-execution checks | **no — never** | belongs to the submission and execution phases |

**"Evaluated exactly once" means once per decision phase**, not once per
lifecycle. `E3` is evaluated once within each decision. `E7`'s two registry
checks — at admission **and again immediately before execution** — are both
required by the frozen `AC-ST-4` and are **both preserved**; they are different
phases, not a duplicate evaluation.

#### 4.1.3 The resolver's complete input contract

| # | Required input | Authoritative source today | Feeds |
| --- | --- | --- | --- |
| I1 | **surface eligibility** `D1` (three-valued) | navigation model + shipped-contract set | the union tag — **and nothing else may** |
| I2 | **entity applicability** `D2` | logical-entity type model | ladder rank 2 |
| I3 | **vendor-support knowledge** `D3` | capability projection (`M10`; **no producer today**) | ranks 4, 8, 10; `RI-1` |
| I4 | **registry presence and lifecycle** `D4` | `utils/device_registry.py` joined on canonical id | ranks 3, 5, 8, 10; RI-1 |
| I5 | **capability-policy knowledge** `D5` | schedule/capability-policy record (`M12`; **no producer today**) | rank 6; `NOT_SCHEDULED` / `SCHEDULE_UNKNOWN` |
| I6 | **evidence existence and sufficiency** `D6b` + `INSUFFICIENT_EVIDENCE` | `utils/snapshot.py::_status` `data_state`; subsystem sufficiency verdicts | ranks 8, 9, 10; `PARTIAL` |
| I7 | **evidence timestamps and freshness, where actually known** `D6a` | `utils/snapshot.py::_status` — `fresh`, `collected_at`, `last_successful_collection`, `stale_reason` | `STALE(as_of)` — **and only this input may produce it** |
| I8 | **expected-source trust** `D6d`, with its source reason code | `configuration/pan_setting_alignment.py` | `SOURCE_TRUST_LIMITED` — **never** `STALE`, never `IDENTITY_TRANSLATION_REQUIRED` |
| I9 | **identity-translation requirement** `D6e`, with its source reason code | the same producer set (V12) | `IDENTITY_TRANSLATION_REQUIRED` qualifier — **never** `SOURCE_TRUST_LIMITED`, and never `CX1` on its own |
| I10 | **identity conflict** — two incompatible type/vendor resolutions for one canonical id | canonical-id resolution | `CX1` only |
| I11 | **configuration presence, or positively established absence** | subsystem verdicts asserting absence from present evidence | rank 9 — **positive absence only** |
| I12 | **retained displayable evidence** for this (entity, capability) | last-known-good state; CAS configuration evidence | `evidence_presentation` — **and the primary label may not** |
| I13 | **latest collection outcome** `D6c` | run telemetry; `Outcome` (V18) | rank 7 |
| I14 | **evidence comparability metadata** — canonical subject, exact capability, platform/entity kind at collection time, evidence generation/time, producer version, vendor support-rule version | run manifest; producer/rule versioning | `K1`–`K6` (§5.1.1); cache binding (§8.2.1) |
| I15 | **member-comparison context** — member set and per-member comparison | merged evidence model | `MEMBER_SPECIFIC` |
| I16 | **declared action set**, each with `action_id`, closed-registry membership, `utils.action_taxonomy` class and declared subject requirement | `console/registry.py::JOB_REGISTRY`; the surface's action declarations | `E2`, `E3`, `E5`, `action_affordance[]` keying |
| I17 | **each action's own declared prerequisite facts** | that action's contract | `E6` |
| I18 | **per-action authorization applicability and outcome** — one of `NO_APPLICABLE_AUTHORITY`, `PERMITTED`, `DENIED(authority, reason)`, `AUTHZ_NOT_EVALUATED(authority)` | `D7` / `E4` (§3.3) | `E4`; affordance value |
| I19 | **request/session authenticity outcome** for the render request | `console/app.py::_require_api_auth` | `E1` |
| I20 | **shell** (exported report vs console) | render context | the **declared action set**, and acquisition/liveness characteristics (live refresh vs snapshot). See §6.8 |

**`I20` may not change semantic evidence presentation.** The shell determines
which actions are declared and how fresh the underlying data can be; it must
**never** cause identical evidence to be presented with a different capability
state, different qualifiers or a different evidence-presentation result.

#### 4.1.4 The omission diagnostic pass — render-independent and OPTIONAL

Contradiction and authority-conflict detection under `OMITTED` is **not** stage
1 of capability resolution. Capability resolution does not run at all under
`OMITTED`. It is a **separate, render-independent diagnostic pass**, and this
contract makes it **OPTIONAL**:

- it is **optional** because an omitted surface has no operator-facing
  consequence, and mandating it would require computing `D2`–`D7` for
  capabilities that no shipped surface consumes — work with no consumer;
- when it **is** performed, its output is a `Diagnostic` on the `OMITTED`
  record and it **never** affects the disposition, the reason, or whether
  anything renders;
- it is never required in order to produce a correct `OMITTED` result.

Every table and acceptance criterion in this document states the same thing:
**optional under `OMITTED`**. Under `RESOLVED`, contradiction detection is part
of stage 1 and a diagnostic **may** be produced — this document makes no
unconditional claim that a `RESOLVED` result carries no diagnostic.

#### 4.1.5 Input-condition handling, resolved before the affected output

| Input condition | Definition | Handling |
| --- | --- | --- |
| **Absent** | a required input was not supplied | if `I1` — `OMITTED{SURFACE_ELIGIBILITY_UNRESOLVABLE}`. Otherwise ladder rank 11 `UNKNOWN(unclassified_input)`; an action declaring the absent fact is `DISABLED_KNOWN_BLOCKER` |
| **Malformed** | supplied but not a valid value of its domain, or internally inconsistent | same as absent, with reason `malformed_input:<input id>`. Never coerced to a default |
| **Contradictory** | `CX1` — two authoritative sources disagree about what the subject **is** | §5.1, within `CX1`'s declared subject scope |
| **Bounded inconsistent** | `RI-1` / `RI-2` — two product-internal projections disagree about a bounded fact (§5.1) | §5.1; muted, never danger |
| **Incomparable** | two facts exist but §5.1.1's comparability conditions are not met | **not** an inconsistency. Resolved per §5.1.1 without manufacturing either an inconsistency or a support conclusion |
| **Stale** | `D6a` `fresh == false` **with** a real anchor | valid input. `STALE(as_of)`; blocks only actions declaring currency of that fact |
| **Unevaluated** | `I18` returns `AUTHZ_NOT_EVALUATED` for a **real, named, applicable** authority | affordance `UNDETERMINED`, represented distinctly from a confirmed denial |
| **No applicable authority** | `I18` returns `NO_APPLICABLE_AUTHORITY` | **non-blocking**; contributes no reason and is **not** a grant (§3.3 D7) |
| **Validly unknown** | `SUPPORT_UNKNOWN`, `POLICY_UNKNOWN`, `APPLICABILITY_UNKNOWN`, `RECONCILIATION_UNKNOWN` | **not malformed.** `POLICY_UNKNOWN` must not erase usable evidence, must not reach rank 11, and yields `SCHEDULE_UNKNOWN` |

**Four inference bans.** Freshness is never inferred from `I8` or `I9`.
Capability support is never inferred from an unknown shell, missing evidence,
or a failed collection. `I9` never becomes `I10`. No input class is inferred
from `I18`, nor `I18` from any of them.

### 4.2 `CapabilityState` — the resolved-only primary vocabulary

**Nine values.** Every one can occur under `RESOLVED`; none is reachable under
`OMITTED`. `NOT_SHIPPED` is **not** here — it is a `SurfaceOmissionReason`
(§4.1).

| Value | Operator meaning | Distinct consequence | Source |
| --- | --- | --- | --- |
| `NOT_APPLICABLE` | this function does not apply to this kind of entity | visible, selectable, names the entity types that do support it; no action enabled by it | D2 |
| `DEVICE_DISABLED` | the operator disabled this device | visible, evidence retained; re-enable is the meaningful action | D4 |
| `UNSUPPORTED` | this vendor/platform cannot do this, and we have evidence of that | visible, evidence retained; states the vendor reason; **collecting again will not help** | D3 |
| `NOT_ENROLLED` | observed in evidence, absent from the Device Registry | visible, evidence retained | D4 (`EVIDENCE_ONLY`) |
| `POLICY_DISABLED` | an applicable schedule for this capability is intentionally off for this device | visible, **evidence retained and displayed**; states that **automatic** refresh is off — never that refresh is impossible | D5 |
| `COLLECTION_FAILED` | the **latest attempt** failed | visible; the failure and its time shown **alongside** retained evidence, which keeps its own age | D6c |
| `UNKNOWN` | we cannot conclude, and we name which fact is missing | visible; names the missing fact; only actions declaring that fact are blocked by it | D3 `SUPPORT_UNKNOWN`, D2 `APPLICABILITY_UNKNOWN`, D4 `REGISTRY_ONLY`/`RECONCILIATION_UNKNOWN`, D6b `no_data`, `INSUFFICIENT_EVIDENCE`, `CX1`, `RI-1`/`RI-2`, incomparability (§5.1.1), or unclassified input |
| `NOT_CONFIGURED` | supported and applicable, and evidence **positively shows** it is not set up | visible; states what configuring it requires | D6 (positive absence) |
| `AVAILABLE` | usable now | normal presentation, populated view | the rank-10 positive conjunction |

**Lexical disjointness (mandatory).** No `CapabilityState` value equals any
member of X1 (`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped`)
or X2 (`CREATED`…`OUTCOME_UNKNOWN`). `COLLECTION_FAILED` is deliberately not
`FAILED`; no value is named `BLOCKED`.

### 4.3 `CapabilityQualifier` — capability and evidence facts only

Seven values. Each binds to **one** facet and states the evidence that
establishes it. **No action outcome appears here** — those are action-keyed
(§4.1.1). Where the frozen parent's presentation table conflates two facets,
the source semantics govern and the divergence is declared (§2.7).

| Value | Established by | Means | Never |
| --- | --- | --- | --- |
| `STALE(as_of)` | **D6a only**: `fresh == false`, with `as_of` from `collected_at` / `last_successful_collection` and `stale_reason` carried through | this evidence is older than the current run | never derived from `PROVENANCE_UNVERIFIED` or `IDENTITY_TRANSLATION_REQUIRED`; never emitted without a real freshness anchor; never removes the view; never authorizes an action |
| `PARTIAL` | **D6b**: `data_state == partial` | some expected evidence is present, some is not | never presented as complete; never implies the absent part failed |
| `SOURCE_TRUST_LIMITED(reason_code)` | **D6d only**: `PROVENANCE_UNVERIFIED`, carrying its **source reason code** | the expected-state source is not trusted enough to support an override or drift claim; the row is a `semantic_exclusion` | **never** an age or staleness claim; **never** given a timestamp; **never** used for an identity-representation issue |
| `IDENTITY_TRANSLATION_REQUIRED(reason_code)` | **D6e only**, carrying its **source reason code** | the two sides identify this subject under different representations, so the comparison is unevaluable until a proven translation exists | **never** described as untrusted provenance; **never** an age claim; **never** promoted to `CX1` unless `CX1`'s own closed conditions independently hold |
| `NOT_SCHEDULED` | **D5 `NO_APPLICABLE_SCHEDULE` only** — an authoritative successful read returning no applicable entry | no automatic refresh is currently scheduled | never emitted from `POLICY_UNKNOWN`; never asserts that none has *ever* existed; never implies manual collection is impossible |
| `SCHEDULE_UNKNOWN` | **D5 `POLICY_UNKNOWN`** | scheduling information could not be determined | **never erases or devalues otherwise usable evidence**; never blocks an action that does not depend on scheduling |
| `MEMBER_SPECIFIC` | member-comparison context (`I15`) | an expected member difference | **never** a warning or failure icon or wording (`AC-DIF-3`) |

`SOURCE_TRUST_LIMITED` and `IDENTITY_TRANSLATION_REQUIRED` each earn their
place by a distinct operator action — establish a trusted expected source, or
establish a proven identifier translation — and their **source reason codes are
preserved** rather than flattened into the qualifier name, so the producer's
own distinction survives into presentation.

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
| `BLOCKED` | `blocked` already means a job record state and a job-type taxonomy fact (E2). Expressed as an `ACTION_REFUSED` blocking reason on the action instead |
| `UNREACHABLE` | a collection outcome, not a capability fact. It resolves to `COLLECTION_FAILED` with its reason; promoting it would invite C8 |
| `DEGRADED` | no distinct operator action that `PARTIAL` + `STALE` + the owning subsystem's own verdict (V15, V25) do not already carry |
| `REGISTRY_ONLY` as a primary | its consequence — run first contact or collection — is `UNKNOWN` with reason `registry_enrolled_not_in_current_evidence`. The reason code makes a **present-tense** claim; no repository source proves "never observed" |
| `FORBIDDEN` / `UNAUTHORIZED` as a primary | authorization must never change what the view *is*, only what a named action may *do*. It is an `ACTION_DENIED` blocking reason under that action's id |
| a combined "source trust" state | `D6d` and `D6e` are separate facts with separate remediation (§3.3 D6). One name for both was a corrected defect |
| `PENDING` / `IN_PROGRESS` / `SUCCEEDED` / `FAILED` | job lifecycle, X1, unchanged (`PO-NAV-7`). A capability never adopts a job's state |

---

## 5. Resolution contract

Resolution runs in ordered stages, per **(shell, entity, capability)**.
**Stage 0 is absolute**: it decides the union tag from `D1` alone, and no later
stage can override or reach past it.

```
stage 0  surface resolution  -> RESOLVED | OMITTED{reason}
         (stages 1-5 run ONLY under RESOLVED; under OMITTED no capability
          output is produced at all, and an OPTIONAL render-independent
          diagnostic pass may attach a Diagnostic to the OMITTED record)

stage 1  contradiction / inconsistency gate
                             -> CX1 (unsafe) | RI-1 | RI-2, each scoped
stage 2  primary ladder      -> exactly one CapabilityState (9 values)
stage 3  capability qualifiers -> independent of stage 2; no action outcomes
stage 4  evidence presentation -> from displayable evidence only
stage 5  action affordance   -> per action_id, PRESENTATION-TIME only:
                                E1-E3, E5, E6 and E4's evaluable part, each
                                once per decision phase.
                                E7 is NOT evaluated here.

server phases (authoritative, outside this contract's resolution):
         submission -> admission -> immediately before execution
         every server-owned gate is re-evaluated in its proper phase; both
         AC-ST-4 registry checks live here.
```

### 5.0 Stage 0 — surface resolution, and the union tag

> **Rule.** `D1` is evaluated **before every other input** and alone decides
> the union tag. Only `SURFACE_PRESENT` produces `RESOLVED`; capability
> resolution does not run under `OMITTED`. No qualifier, contradiction,
> evidence fact, policy fact or authorization outcome can overturn an omission.

| Case | `D1` | Variant | Payload | Rendered? | Diagnostic pass | Positive claim asserted |
| --- | --- | --- | --- | --- | --- | --- |
| **V-A** — surface positively absent | `SURFACE_ABSENT` | `OMITTED{NOT_SHIPPED}` | reason only — **no** capability outputs | **no** | **optional** (§4.1.4); if run, render-independent | "this build does not ship this surface" — supported by the positive determination |
| **V-B** — surface positively absent, with an authority conflict in the underlying facts | `SURFACE_ABSENT` | `OMITTED{NOT_SHIPPED}` | reason + optional `Diagnostic` | **no** | **optional**; if run, it names both disagreeing inputs and **changes no rendering** | same as `V-A`; the conflict adds no product claim |
| **V-C** — surface positively present | `SURFACE_PRESENT` | `RESOLVED` | the four capability outputs | yes | contradiction detection is **stage 1** of resolution; a diagnostic **may** be produced and rendered | the capability outputs' own claims |
| **V-D** — `D1` missing, malformed or unresolvable | `SURFACE_ELIGIBILITY_UNRESOLVABLE` | `OMITTED{SURFACE_ELIGIBILITY_UNRESOLVABLE}` | reason `d1_input_unresolvable` + optional `Diagnostic` | **no** — **fail closed** | **optional** | **none.** It says nothing about what the build ships |

**Why `V-D` omits rather than renders.** `D1` asserts that this build actually
ships the surface (navigation contract §7.1). An unresolvable `D1` cannot
support that assertion, and rendering an entry that may point at nothing is the
failure mode D-NAV6a's DOM integrity check exists to prevent. This is the one
place where fail-closed means "show less", because every other dimension's
fail-closed direction ("say `UNKNOWN`, keep the surface") presupposes the
surface exists.

**Why `V-D` is not `NOT_SHIPPED`.** Omitting is a *disposition*; `NOT_SHIPPED`
is a *claim*. `V-D` takes the safe disposition without making the claim — which
is why the two are separate `SurfaceOmissionReason` values and neither is a
`CapabilityState`.

### 5.1 Stage 1 — contradictions and bounded inconsistencies

Reached **only** under `RESOLVED`. Three closed classes, and the severity
distinction between them is normative.

**`CX1` is the only genuine contradiction.** Two authoritative sources disagree
about *what the subject is*. That is an unsafe identity condition and takes
**danger** semantics, consistent with the frozen `AC-DIF-6`
("contradictory/unsafe state … danger/error") and `AC-DIF-8` ("red is reserved
for actual fault, unsafe drift or failed state").

**`RI-1` and `RI-2` are bounded reconciliation inconsistencies**, not
contradictions. Two *product-internal projections* disagree about one bounded
fact; nothing about the device's identity or safety is in dispute, and the
operator's remedy is bounded and local. They take **muted** semantics and are
deliberately **not named "contradiction"**, so that "contradictory state =
danger" and their muted treatment cannot both be read off this document. They
*(Historical note, non-normative: revision 5 and earlier called these `CX2` and
`CX3`. Those identifiers are **not** current normative terms and appear below
only inside clearly-labelled historical defect records.)*

**Simultaneous independent facts are none of these.** `UNSUPPORTED` beside
retained historical evidence is not an inconsistency. `POLICY_DISABLED` beside
fresh evidence is not. `POLICY_UNKNOWN` beside usable evidence is not.
`IDENTITY_TRANSLATION_REQUIRED` is not — it is an unevaluable comparison
(§3.3 D6e), and it becomes `CX1` only if `CX1`'s own condition independently
holds.

| id | Class | Condition | Severity | Scope of effect |
| --- | --- | --- | --- | --- |
| **CX1** | **contradiction — unsafe** | the same canonical id resolves to two **incompatible entity types or vendors** (`I10`) | **danger** | `primary_status = UNKNOWN(identity_contradiction)`; **evidence withheld**; and — per §5.1.2 — `E5` blocks **only** actions whose declared subject or target depends on the disputed identity |
| **RI-1** | **bounded inconsistency** | `D3 = UNSUPPORTED` while **comparable** positive evidence for the same subject and capability exists — comparability per §5.1.1 | **muted** | `primary_status = UNKNOWN(support_inconsistency)`; **evidence retained and displayed**, labelled; `E6` blocks only actions declaring the support fact; all other actions unaffected |
| **RI-2** | **bounded inconsistency** | `D4 = EVIDENCE_ONLY` while a registry row for the same canonical id exists and is not disabled | **muted** | `primary_status = UNKNOWN(reconciliation_inconsistency)`; **evidence retained and displayed**; `E6` blocks only an enrollment action, if one is declared; all other actions unaffected |

Every class names **both** disagreeing inputs; the more favourable input is
never silently chosen. None of them ever *grants* an affordance — each only
removes one, within its declared scope, on top of the other gates.

**Reconciliation with `AC-CS-47` and the frozen parent.** Because `RI-1`/`RI-2`
are not classified as contradictory or unsafe state, `AC-DIF-6`/`AC-DIF-8` and
`AC-CS-47` apply to `CX1` alone and **no frozen-parent amendment is required
for this point**. This is a naming correction inside `M3`'s own vocabulary, not
a change to the parent.

#### 5.1.1 `RI-1` comparability — total over `K1`–`K6`

Each condition is `TRUE`, `FALSE` or `UNESTABLISHED` from `I14`.

| # | Condition |
| --- | --- |
| K1 | **same canonical subject** — identical canonical id, joined without an unproven identifier translation |
| K2 | **same exact capability** — not a sibling or superset capability |
| K3 | **same platform / entity kind** at evidence-collection time as the current support conclusion was computed against |
| K4 | **evidence generation not superseded** — produced at or after the last platform or entity-kind change known to the product |
| K5 | **same applicable support-rule version** as the one yielding today's `UNSUPPORTED` |
| K6 | **producer version comparable** — the evidence producer version is one the current support rules are defined over |

**Precedence, evaluated in this order; the first matching row decides:**

| Order | Condition | Semantic class | Result |
| --- | --- | --- | --- |
| 1 | `K1 = FALSE` **or** `K2 = FALSE` | **irrelevant evidence** — it concerns another subject or another capability | **No `RI-1`.** The evidence is **not displayed as evidence for this subject/capability** at all; it is out of scope, not historical. `primary_status = UNSUPPORTED` on `D3`'s own terms |
| 2 | `K1 = UNESTABLISHED` **or** `K2 = UNESTABLISHED` | **identity/scope unknown** — a join would have to be guessed | **No `RI-1`, no support conclusion from it.** `primary_status = UNKNOWN(support_comparability_unestablished)`, naming the unestablished condition. Evidence is shown only as **unattributed**, never joined to this subject/capability (`AGENTS.md` identity law) |
| 3 | `K6 = FALSE` | **known-incompatible producer** — the evidence exists and is correctly scoped, but was produced by a version the current rules are not defined over | **Treated as historical / non-comparable.** No `RI-1`; `primary_status = UNSUPPORTED`; evidence retained and **labelled historical (incompatible producer version)** |
| 4 | `K3 = FALSE` **or** `K4 = FALSE` **or** `K5 = FALSE` | **historical or rule-context mismatch** | **No `RI-1`.** The current positive `D3` conclusion stands: `primary_status = UNSUPPORTED`; correctly scoped evidence is retained and **labelled historical** |
| 5 | any of `K3`, `K4`, `K5`, `K6` `= UNESTABLISHED` | **comparability unknown** | `primary_status = UNKNOWN(support_comparability_unestablished)`, naming which condition; evidence retained and displayed; actions declaring the support fact are blocked |
| 6 | `K1`–`K6` **all `TRUE`** | **contemporaneous comparable evidence** | **`RI-1` holds.** `primary_status = UNKNOWN(support_inconsistency)` |

**Mixed cases are total by construction:** rows 1–2 dominate on scope/identity,
row 3 on a known-incompatible producer, row 4 on a known context mismatch, row
5 on any residual unknown, and row 6 only when nothing above matched. A
`FALSE` on a scope condition therefore always beats an `UNESTABLISHED`
elsewhere, and a known mismatch always beats an unknown one — the product never
reports "unknown" where it in fact knows the evidence does not apply.

**Nothing is manufactured in either direction.** A platform or support-rule
change alone never creates an inconsistency out of historical evidence, and
contemporaneous comparable evidence is never silently ignored.

#### 5.1.2 `CX1` subject scoping — it does not block every action

`CX1` blocks, via `E5`, **only** actions whose **declared subject or target
depends on the disputed identity**. It is a subject-integrity failure, not a
global halt.

| Action shape | Effect of `CX1` |
| --- | --- |
| declares the disputed subject as its target (`target_mode = "entity_ids"` naming that entity) | `DISABLED_KNOWN_BLOCKER`, reason `E5: subject_integrity_unmet` |
| **targetless** (`target_mode = "none"`) | **no `E5` prerequisite exists, so `CX1` contributes nothing.** The action resolves through its own gates, and **no subject prerequisite may be invented for it** |
| targets a **different**, undisputed subject | unaffected by this `CX1` |

Evidence for the disputed subject is still withheld (the identity law forbids
joining on unproven identity), but withholding *evidence about a subject* is
not the same as blocking *actions that never name it*.

### 5.2 Stage 2 — the primary ladder

Reached only under `RESOLVE`. First match wins. `AVAILABLE` is **not** a
fall-through: it requires the positive conjunction at rank 10, and any input
satisfying no rank lands at rank 11.

| Rank | Condition | Primary |
| --- | --- | --- |
| 0 | `CX1`, `RI-1` or `RI-2` holds (stage 1) | `UNKNOWN(<class reason>)` |
| 1 | `RI-1` comparability could not be established (§5.1.1 rows 2 and 5) | `UNKNOWN(support_comparability_unestablished)` |
| 2 | `D2 = NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 3 | `D4 = REGISTRY_DISABLED` | `DEVICE_DISABLED` |
| 4 | `D3 = UNSUPPORTED` (and `RI-1` does not hold) | `UNSUPPORTED` |
| 5 | `D4 = EVIDENCE_ONLY` | `NOT_ENROLLED` |
| 6 | `D5 = POLICY_DISABLED` | `POLICY_DISABLED` |
| 7 | `D6c = failed` on the latest attempt | `COLLECTION_FAILED` |
| 8 | `D3 = SUPPORT_UNKNOWN`, or `D2 = APPLICABILITY_UNKNOWN`, or `D4 ∈ {REGISTRY_ONLY, RECONCILIATION_UNKNOWN}`, or `D6b = no_data`, or `INSUFFICIENT_EVIDENCE` | `UNKNOWN(<named missing fact>)` |
| 9 | `D6` **positively** evidences absence of configuration (`I11`) | `NOT_CONFIGURED` |
| 10 | **all** of: `D2 = APPLICABLE` ∧ `D3 = SUPPORTED` ∧ `D4 = RECONCILED` ∧ `D6b ∈ {live, last_known_good, partial}` ∧ `D6c = success` ∧ no `CX1`/`RI-1`/`RI-2` ∧ every required input well-formed | `AVAILABLE` |
| 11 | anything else — a required input absent, malformed, or matching no rank | `UNKNOWN(unclassified_input)` — **fail closed** |

`D1` no longer appears in the ladder: stage 0 owns it entirely, and rank 10's
conjunction assumes `RESOLVE` because the ladder is unreachable otherwise.

**`D5 = POLICY_UNKNOWN` is absent from this ladder and is not malformed
input.** It is a valid value meaning "scheduling information could not be
determined": it must not erase otherwise usable evidence, must not reach rank
11, and is carried as `SCHEDULE_UNKNOWN`. `D6d` and `D6e` are likewise absent —
they are comparability facts, not usability facts, carried as their own
qualifiers.

**Why this order.** Rank 2 outranks the rest because no evidence, policy or
enrollment can make a capability apply to a type it does not apply to. Rank 3
above rank 4 because a device the operator disabled is their own most recent,
most reversible act — **contested, §10 dissent D2** — mitigated by
`AC-CS-16`. Rank 4 above 5–7 because `UNSUPPORTED` is the one state where
enrolling, re-enabling a schedule or collecting again is guaranteed not to
help. Rank 8 above rank 9 is fail-closed: asserting "not configured" without
positive evidence of absence is fabricated certainty. Rank 11 exists so
malformed input can never reach rank 10.

### 5.2.1 Stages 3–5 — decoupled from the primary

- **Stage 3, capability qualifiers.** Each is evaluated from its own facet
  (§4.3) regardless of which rank fired, and carries no action outcome.
- **Stage 4, evidence presentation.** `POPULATED` whenever displayable
  evidence exists under the existing identity and privacy contracts;
  `EMPTY{reason}` **only** when none does. The primary label is not an input.
- **Stage 5, action affordance.** Per `action_id`; §5.4 defines it. It reports presentation-time facts only and is never an execution grant.

### 5.2.2 Worked resolution

> Registry-enrolled and reconciled Check Point cluster, surface present. A
> configuration collection succeeded last week. The operator disabled the
> schedule. Yesterday's manual attempt failed. The expected-state source is
> untrusted.

- Stage 0: `SURFACE_PRESENT` → `RESOLVE`.
- Stage 1: no CX class holds — a disabled schedule beside a failed attempt is
  two independent facts.
- Stage 2: rank 6 → `primary_status = POLICY_DISABLED`.
- Stage 3: `STALE(as_of = last week)` from D6a; `SOURCE_TRUST_LIMITED(<source
  reason code>)` from D6d, **with no timestamp**; no `NOT_SCHEDULED` (a
  schedule exists and is disabled).
- Stage 4: `POPULATED` — last week's evidence displayed with its age, the
  failed latest attempt shown **beside** it, not instead of it.
- Stage 5, per declared `action_id`:
  - `config_refresh_cp` — **scheduling state alone does not block it**; `D5`
    contributes no reason. Presentation-time gates `E1`–`E3`, `E5`, `E6` pass
    and `E4` is `NO_APPLICABLE_AUTHORITY`, so the affordance is
    `AVAILABLE_FOR_SUBMISSION`. That is **not** permission to execute: `E7`'s
    admission and immediately-before-execution checks have not run and are
    re-evaluated in their own phases.
  - a drift/override-claim action, where one is declared —
    `DISABLED_KNOWN_BLOCKER`, reason `E6: trusted_expected_source_required`.

### 5.3 Authorization — independent, action-scoped, server-enforced

1. **Authorization is evaluated per action, independently of the ladder**, and
   recorded under that action's id. No ladder outcome may suppress, defer or
   substitute for it.
2. **"Stated last" is a copy rule, not an enforcement order.** When an action
   is ineligible for more than one reason, the authorization reason is always
   **included and stated last** in the explanation, so an operator cannot
   conclude that clearing another reason would grant the action. Enforcement is
   never postponed: the server evaluates the applicable authority whenever the
   action is attempted, in whatever order it likes.
3. **Visibility and eligibility are never the authorization boundary.** The
   server refuses independently (`AC-EN-14`); an eligible-looking control is
   not a grant and an ineligible one is not an enforcement.
4. **A capability projection never grants permission.** Shared and possibly
   cached (§8.2.1), it is an input to *presentation* only — never endpoint,
   authorization, admission or persistence authority.
5. **Regime scoping.** The `OP.2` authorizer governs **CLASS 2 only** and must
   never be described or implemented as the product-wide authorization state.
   CLASS 0 console reads and submissions are governed by `E1`–`E3` and are
   permitted today; the absence of OIDC/RBAC neither grants nor denies them.

### 5.4 Stage 5 — action affordance, per `action_id`

For each **declared** action the resolver emits one `ActionAffordance` keyed by
`action_id` (§4.1.1). An action that is **not declared** on this surface
produces **no entry**.

| Gate | Evaluated once per **decision phase** as | Presentation-time? | Contributes |
| --- | --- | --- | --- |
| `E1` | request/session authenticity and origin, for the render request | yes | `blocking_reasons`: `session_not_authenticated` |
| `E2` | action identity + closed-registry membership | yes | `blocking_reasons`: `unknown_action_identity` |
| `E3` | taxonomy / surface admissibility (`console_refusal()`) | yes | `blocking_reasons`: `ACTION_REFUSED(class)`, refusing class named |
| `E4` | the action's applicable **authorization** authority (`D7`, four-valued) | partly | `NO_APPLICABLE_AUTHORITY` → nothing; `PERMITTED` → nothing; `DENIED` → `blocking_reasons`; `AUTHZ_NOT_EVALUATED` → `undetermined_reasons` |
| `E5` | subject/target integrity **required by that action's own contract** | yes, for already-resolved integrity facts | `blocking_reasons`: `subject_integrity_unmet` |
| `E6` | the action's own declared semantic prerequisites | yes | `blocking_reasons`: the named prerequisite |
| `E7` | admission and immediately-before-execution live checks | **no — never** | nothing at presentation time; **both** checks run in their own phases |

**Resolution of the affordance value:**

| Condition | `affordance` |
| --- | --- |
| any presentation-time gate contributes a **blocking** reason | `DISABLED_KNOWN_BLOCKER` |
| no blocker, but any presentation-time gate contributes an **undetermined** reason — `E4`'s `AUTHZ_NOT_EVALUATED(authority)`, or an `E6` prerequisite whose value could not be established | `UNDETERMINED` |
| no blocker and no undetermined reason | `AVAILABLE_FOR_SUBMISSION` |

`UNDETERMINED` is **not** an `E4`-only outcome. Any gate that cannot reach a
决 decision at presentation time contributes an undetermined reason, and the
fail-closed direction is `UNDETERMINED` — never `AVAILABLE_FOR_SUBMISSION`,
because that would assert the absence of a blocker the resolver did not
establish.

`AVAILABLE_FOR_SUBMISSION` states only that **no presentation-time blocker is
known**. It never claims `E7` passed, and it is never an execution grant.

**`E3` is never re-evaluated inside `E4`.** Taxonomy admissibility is decided
once per decision. **"Once" is per decision phase**, not per lifecycle: `E7`'s
registry check at admission and its check immediately before execution are
**both required** (`AC-ST-4`) and both preserved — different phases, not a
duplicate evaluation.

**`E5` imports no prerequisite the action does not have.** A **targetless**
action (`target_mode = "none"` — today `inventory_refresh_cp`,
`inventory_refresh_vsx`, `config_refresh_pan`, `config_refresh_cp`,
`report_rebuild`) declares no subject requirement, so `E5` requires none and
**must not invent one**, including under `CX1` (§5.1.2). An entity-targeted
action (`target_mode = "entity_ids"` — today `recovery_attest_cp`,
`cp_gaia_backup`) inherits its contract's rules unchanged.

**`E6` is scoped to what the action declares** — the frozen parent's own
scoping, navigation contract §8 Unknown row: *"disabled **if the action needs
the missing fact**"*. An action does not become blocked merely because some
unrelated dimension is `UNKNOWN` or `STALE`.

**Evidence-gathering actions declare no `E6` prerequisite on the evidence they
produce** and are never blocked by its absence; requiring it would be a
deadlock. They remain subject to every other presentation-time gate, and to
`E7` at its own phases:

- **manual collection** — scheduling state alone (`D5`) does not block it; it
  is independent of nothing else, entering the same admission coordinator and
  single orchestration path and staying within the vendor
  interaction-safety budget;
- **retry** — additionally satisfies **its own retry contract**. A previous
  failure is never a grant. Retry is a **new typed job**, never a mutation of a
  historical outcome (`AC-RT-6`); a ledger-window or minimum-interval refusal is
  a correct outcome, and it is an `E7`-phase refusal, not a presentation-time
  claim.

#### 5.4.1 Undetermined, denied, and not-applicable are three different things

| | `DENIED(authority, reason)` | `AUTHZ_NOT_EVALUATED(authority)` | `NO_APPLICABLE_AUTHORITY` |
| --- | --- | --- | --- |
| Meaning | an applicable authority **refused** | a real, named, applicable authority **was not consulted** | **no** actor-authorization authority applies to this action |
| `affordance` | `DISABLED_KNOWN_BLOCKER` | `UNDETERMINED` | contributes nothing |
| Presentation | refusal tone and refusal copy | **neutral / undetermined** tone and copy — **never** refusal tone or refusal wording | invisible; nothing is said about authorization |
| Operator copy | "refused by \<authority\>: \<reason\>" | "permission for this action has not been determined" | — |
| Audit | a denial decision | an unevaluated-gate record, never a denial | not an authorization event |
| Is a grant? | no | no | **no** |

#### 5.4.2 The UI state is advisory

Everything stage 5 produces is **advisory presentation**. Server-side
**submission**, **admission** and **immediately-before-execution** checks remain
authoritative and are unchanged: an action shown `AVAILABLE_FOR_SUBMISSION` is
still fully checked server-side, and one shown `DISABLED_KNOWN_BLOCKER` is still
refused server-side.

**All reasons are preserved**, whatever the primary label, each naming its gate
under its own `action_id`.

### 5.4.3 What each output may read

| Output | Permitted inputs | Forbidden inputs |
| --- | --- | --- |
| the union tag (root / module / tab rendering) | `D1` only | D2–D7, contradictions, qualifiers, actions |
| `primary_status` | stages 1–2 inputs | `D1` (stage 0 owns it), `D7`, any action outcome |
| `capability_qualifiers` | the facet each binds to | any action outcome |
| `evidence_presentation` | existence of displayable evidence under the identity/privacy contracts | the primary label |
| action **visibility** | the action contract's existence + the shell | `E4` — an action must not vanish because you lack access |
| `action_affordance[action_id]` | the **presentation-time** gates only — `E1`–`E3`, `E5`, `E6`, and `E4`'s evaluable part — each once per decision | `E7`, which is not evaluable at render; a dimension the action does not depend on |
| explanatory copy | every input that contributed, named | a reason that is not the actual gate |
| audit / report representation | the resolved state as text | any action affordance in the report (`AC-SH-1`) |

### 5.5 Atomic resolution cases

Every case is written to be **executable as a test fixture**: one complete
subject, one concrete declared `action_id` (or explicitly action-free), complete
input assumptions, one deterministic result.

**Two orthogonal bases, tagged per case as `[ACT:… · IN:…]`.** A single class
cannot express a case's dependencies, because *does the named action exist?*
and *does the producer of the required input exist?* are independent questions.
The previous three-class model conflated them and, worse, claimed a `[CURRENT]`
case was "fully executable today" — false for every case whose premise includes
a `D3` or `D5` value, since neither dimension has a producer.

**Action basis — `ACT:`**

| Value | Meaning | Test obligation |
| --- | --- | --- |
| **`CURRENT`** | every named `action_id` is a current `JOB_REGISTRY` member | assert membership for each named id |
| **`ACTION_FREE`** | the case produces **no** `action_affordance` entry | assert the resolved result carries no affordance entry |
| **`FUTURE`** | no current action contract exists, so **no current `action_affordance` entry can be produced at all** | name **no** `action_id`; assert no current registry entry supplies the described semantics |

**Input basis — `IN:`**

| Value | Meaning | Test obligation |
| --- | --- | --- |
| **`CURRENT`** | every required authoritative input is produced today | assert the producing module exists and emits the required field |
| **`SYNTHETIC(owner)`** | the premise is a **valid future resolver fixture**, but its authoritative producer is **not shipped**; the value must be supplied synthetically | assert the named owner's producer is absent, and that the fixture supplies the value explicitly rather than reading it from a producer |
| **`FUTURE_PRODUCER(owner)`** | the case specifically tests an **output defined for a future producer contract** | assert the named producer/output is absent; the case exists to pin the contract that output must satisfy |

**Invariant across every case:** each concrete `action_id` appearing anywhere
in §5.5 is a real `JOB_REGISTRY` member. An `ACT:FUTURE` case never fabricates
an id — it describes the semantics and proves no current entry supplies them.

**What is and is not executable today — stated precisely.**

- The **source and registry assertions** in these cases are verifiable today:
  action-id membership, `target_mode`, taxonomy class, workflow mapping, and
  the presence or absence of a named producer.
- The **resolver cases become executable unit fixtures once the resolver
  exists**, supplying every `IN:SYNTHETIC(...)` premise explicitly as a
  declared test input.
- **The focused repository tests run today neither execute nor prove the
  resolver.** No resolver exists to exercise.
- **No case depending on an absent producer is described as fully executable
  in the current repository** — which, given that `D3` (`M10`) and per-(entity,
  capability) `D5` (`M12`) have no producers, is most of them.

Registry members verified at this baseline: targetless (`target_mode = "none"`)
— `inventory_refresh_cp`, `inventory_refresh_vsx`, `config_refresh_pan`,
`config_refresh_cp`, `report_rebuild`; entity-targeted (`target_mode =
"entity_ids"`) — `recovery_attest_cp` (CLASS 0), `cp_gaia_backup` (CLASS 1).

**`report_rebuild` is a report-rendering job** (`workflow = "render-only"`,
label "Rebuild report"). It is **not** a retry of collection, and no case may
describe it as one.

**Shared fixture assumptions unless a case states otherwise:** `D1 =
SURFACE_PRESENT` → `RESOLVED`; console shell; `E1` passes for the render
request; the named action is a registry member (`E2` passes); it is CLASS 0 and
`E3` passes; `E4 = NO_APPLICABLE_AUTHORITY` (no actor-authorization authority
applies to CLASS 0 today); `E7` is **not evaluated at presentation time**.

**No case asserts permission to execute.** `AVAILABLE_FOR_SUBMISSION` always
means *no presentation-time blocker is known*.

| # | Subject + input assumptions | `action_id` | `primary_status` | `capability_qualifiers` | `evidence_presentation` | `action_affordance` | Tone | Copy must say |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **H1** `[ACT:CURRENT · IN:SYNTHETIC(M10/M14)]` | CP cluster; supported, reconciled, `D6a fresh = true`, `D6c = success`; **`E4 = DENIED(authority, reason)`** for the named action (a fixture where an applicable authority exists and refuses) | `recovery_attest_cp` | `AVAILABLE` | — | `POPULATED` | `DISABLED_KNOWN_BLOCKER`, `[E4: ACTION_DENIED(authority, reason)]` | normal view; **refusal** tone on that control | who refused, under which authority; never "unavailable" |
| **H2** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | PAN device observed in evidence, absent from the registry; **`enroll_device` is not a member of the closed registry at this baseline** | *(none — no enrollment action is declared)* | `NOT_ENROLLED` | — | `POPULATED` — the evidence is what proves it exists | **no entry at all.** An undeclared action produces no affordance; it is **not** `UNDETERMINED`, and no authorization authority is invented for it | `info` | that it is observed but unenrolled, and that no enrollment path is available in this build. Enrollment remains gated by the frozen seventeen conditions, enforced through their own **non-authorization** gates; production/server exposure stays blocked on `DEPLOY.1A` |
| **H3** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | CP cluster; supported, reconciled; `D6a fresh = false` with `collected_at` present | `config_refresh_cp` | `AVAILABLE` | `STALE(as_of)` | `POPULATED` with age | `AVAILABLE_FOR_SUBMISSION` — staleness is not an `E6` prerequisite of the action that refreshes it | `muted` badge + timestamp | the age, its anchor and `stale_reason`; never blank |
| **H4a** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | `D3 = UNSUPPORTED` with reason class `REVALIDATABLE(platform_classification)`; retained evidence correctly scoped (`K1`, `K2` `TRUE`) but `K5 = FALSE` (support-rule change); **`D6a fresh = false` with a real anchor supplied by the fixture** | `config_refresh_cp` | `UNSUPPORTED` | `STALE(as_of)` — emitted **only** because `D6a` is explicitly false with an anchor; predating a rule change does **not** by itself prove staleness | `POPULATED`, labelled **historical (support-rule context differs)** | `AVAILABLE_FOR_SUBMISSION` | own `unsupported` token | the **vendor** reason; that the retained evidence is historical; and precisely **what a re-collection can re-evaluate** — the CP platform-family classification and its confidence grade (§5.5.4). It must **not** claim that collecting again cannot help |
| **H4b** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | `D3 = UNSUPPORTED`, reason class `REVALIDATABLE(platform_classification)`; `K1`–`K6` **all `TRUE`** → `RI-1` | `config_refresh_cp` | `UNKNOWN(support_inconsistency)` | none unless independently established | `POPULATED`, labelled as inconsistent with the support conclusion | `AVAILABLE_FOR_SUBMISSION` — a re-collection can re-evaluate the classification input the inconsistency rests on | **`muted`** — a bounded inconsistency, **not** danger | **both** disagreeing inputs, named; that the inconsistency is unresolved and what a re-collection re-evaluates |
| **H4c** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | `D3 = UNSUPPORTED`, reason class `REVALIDATABLE(platform_classification)`; `K6 = UNESTABLISHED` (producer version not comparable) | `config_refresh_cp` | `UNKNOWN(support_comparability_unestablished)` | none unless independently established | `POPULATED` | `AVAILABLE_FOR_SUBMISSION` — a re-collection produces a current-producer generation, which is what comparability lacks | `muted` | **which** comparability condition could not be established; that neither an inconsistency nor a support conclusion is claimed |
| **H4d** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | `D3 = UNSUPPORTED`, reason class `REVALIDATABLE(platform_classification)`; `K2 = FALSE` — the retained evidence is for a **different capability** | `config_refresh_cp` | `UNSUPPORTED` | — | **that evidence is not displayed for this capability at all** (out of scope, not historical); `EMPTY{no_evidence_for_this_capability}` if nothing else is retained | `AVAILABLE_FOR_SUBMISSION` | own `unsupported` token | the vendor reason; nothing about the out-of-scope evidence; and what a re-collection re-evaluates (§5.5.4) |
| **H4e** `[ACT:CURRENT · IN:FUTURE_PRODUCER(M10)]` | `D3 = UNSUPPORTED`, positively supported, with remediation class **`TERMINAL`** carried by the reason. **Missing producer, named:** the `M10` capability projection's **remediation classification of `UNSUPPORTED` reason codes** — no producer emits it today (§5.5.4 `UCQ-1`). The **action is current**: `config_refresh_cp` is a `JOB_REGISTRY` member | `config_refresh_cp` | `UNSUPPORTED` | — | `POPULATED` if retained, else `EMPTY{no_evidence_collected}` | `DISABLED_KNOWN_BLOCKER`, `[E6: support_conclusion_terminal]` | own `unsupported` token | the vendor reason, and that **no re-collection can change it** — permitted **only** because the reason positively carries `TERMINAL` |
| **H4f** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | `D3 = UNSUPPORTED`, **positively supported**, but its reason carries **no remediation class** (absent, malformed or unknown) — the state of every reason at this baseline, since no producer emits the classification | `config_refresh_cp` | `UNSUPPORTED` | — | `POPULATED` if retained, else `EMPTY{no_evidence_collected}` | **`UNDETERMINED`**, `undetermined_reasons = [E6: unsupported_remediation_class_unresolved]` — **never** `AVAILABLE_FOR_SUBMISSION` | own `unsupported` token; **neutral/undetermined** on the control | the vendor reason, and that whether re-collection would help **has not been determined**. It must **not** claim re-collection will help, and must **not** claim it cannot |
| **H5a** `[ACT:CURRENT · IN:SYNTHETIC(M12)]` | CP cluster; applicable schedule intentionally disabled; retained evidence present; `D6c = failed` on the latest attempt | `config_refresh_cp` | `POLICY_DISABLED` | `STALE(as_of)` if `D6a` is false with an anchor | `POPULATED` — retained evidence with the latest attempt beside it | `AVAILABLE_FOR_SUBMISSION` — `D5` contributes no reason | `muted` + explicit label | that **automatic** refresh is off and manual collection is unaffected; never that refresh is impossible |
| **H5b** `[ACT:CURRENT · IN:SYNTHETIC(M12)]` | as H5a; the operator wants the exported report regenerated from the retained evidence | `report_rebuild` *(workflow `render-only` — a report-rendering job, **not** a retry of collection)* | `POLICY_DISABLED` | as H5a | `POPULATED` | `AVAILABLE_FOR_SUBMISSION` — rendering reads retained evidence and declares no `E6` prerequisite on refresh | `muted` | that the report is rebuilt from the evidence shown, whose age is stated; **nothing** about retrying a collection |
| **H5c** `[ACT:CURRENT · IN:SYNTHETIC(M12/M14)]` | as H5a, plus a fixture where an applicable authority refuses the named action | `cp_gaia_backup` *(CLASS 1)* | `POLICY_DISABLED` | as H5a | `POPULATED` | `DISABLED_KNOWN_BLOCKER`, `[E3: ACTION_REFUSED(CLASS_1_RECOVERY_WRITE, not console-submittable), E4: ACTION_DENIED(authority, reason)]` — **both preserved** | refusal tone on that control only | **both** reasons — the refusing class and the authorization refusal, refusal last; the policy that disabled automatic refresh is stated separately as the capability's own status |
| **H6** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | ClusterXL logical entity; one member's value legitimately differs; `D6c = success` | *(action-free presentation case)* | `AVAILABLE` | `MEMBER_SPECIFIC` | `POPULATED`, side-by-side member comparison | — | `--member-specific` gold row emphasis | "Expected member difference"; **no** warning icon or failure wording |
| **H7a** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | the **global module** subject; its surface ships | *(action-free presentation case)* | `AVAILABLE` | — | `POPULATED` | — | normal | the module is a product plane and does not disappear (`AC-WS-10`) |
| **H7b** `[ACT:CURRENT · IN:SYNTHETIC(M10/M11)]` | the **selected entity** subject; a standalone firewall; the capability does not apply to its type | `recovery_attest_cp` | `NOT_APPLICABLE` | — | `EMPTY{not_applicable_to_entity_type}` | `DISABLED_KNOWN_BLOCKER`, `[E6: capability_not_applicable_to_entity_type]` | `info` | which entity types support the capability |
| **H8a** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | CP cluster; supported, reconciled, `D6a fresh = true`, `D6b = live`, `D6c = success`; **no CLASS 2 action is declared on this surface — the state at this baseline** | *(none declared)* | `AVAILABLE` | — | `POPULATED` | **no entry** — an undeclared action produces none | normal | nothing about a CLASS 2 action; there is no control to explain |
| **H9a** `[ACT:CURRENT · IN:CURRENT]` | one canonical id resolves to two incompatible entity types → `CX1`; evidence for that subject withheld | `recovery_attest_cp` *(entity-targeted, naming the disputed subject)* | `UNKNOWN(identity_contradiction)` | — | `EMPTY{identity_contradiction}` | `DISABLED_KNOWN_BLOCKER`, `[E5: subject_integrity_unmet]` | **`danger`** — a genuine unsafe identity contradiction (`AC-DIF-6`) | **both** disagreeing identity resolutions, named |
| **H9a-t** `[ACT:CURRENT · IN:CURRENT]` | the same `CX1` fixture as H9a | `report_rebuild` *(targetless)* | `UNKNOWN(identity_contradiction)` | — | `EMPTY{identity_contradiction}` | `AVAILABLE_FOR_SUBMISSION` — **`CX1` contributes nothing**: this action declares no subject, and none may be invented (§5.1.2) | `danger` on the capability chip; **normal** on this control | that the identity dispute concerns the subject, not this action |
| **H10** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | CP cluster; `D5 = POLICY_UNKNOWN`; `D6b = live`, `D6c = success` | `config_refresh_cp` | `AVAILABLE` | `SCHEDULE_UNKNOWN` | `POPULATED` — `POLICY_UNKNOWN` erases nothing | `AVAILABLE_FOR_SUBMISSION` — `POLICY_UNKNOWN` is valid input, not malformed | normal | that automatic-refresh state could not be determined; **evidence is unaffected** |
| **H11a** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | PAN device; `D6d = PROVENANCE_UNVERIFIED`; `D6a fresh = true` | *(action-free presentation case)* | `AVAILABLE` | `SOURCE_TRUST_LIMITED(<source reason code>)` | `POPULATED`, **with its own valid collection timestamp** — this qualifier neither supplies nor suppresses it | — | `muted`; **no age claim from this qualifier** | that no override or drift claim can be made from this source; **not** that the evidence is old |
| **H11b** `[ACT:CURRENT · IN:SYNTHETIC(M10)]` | as H11a | `config_refresh_cp` | `AVAILABLE` | `SOURCE_TRUST_LIMITED(<source reason code>)` | `POPULATED` | `AVAILABLE_FOR_SUBMISSION` — source trust is not this action's prerequisite | `muted` | as H11a; the collection timestamp stays displayed |
| **H11c** `[ACT:ACTION_FREE · IN:SYNTHETIC(M10)]` | PAN device; `D6e = IDENTITY_TRANSLATION_REQUIRED`; `D6a fresh = true`; **`CX1`'s condition does not hold** | *(action-free presentation case)* | `AVAILABLE` | `IDENTITY_TRANSLATION_REQUIRED(<source reason code>)` | `POPULATED`, with its own timestamp | — | **`muted`** — a comparability limitation, **not** danger | that the two sides identify this subject differently and the comparison needs a proven translation; **not** that the source is untrusted, and **not** that identities contradict |
| **H12** `[ACT:CURRENT · IN:SYNTHETIC(M10/M14)]` | CP cluster; a fixture where a real, named, applicable authority exists but **was not consulted** for the named action | `recovery_attest_cp` | `AVAILABLE` | — | `POPULATED` | `UNDETERMINED`, `undetermined_reasons = [E4: AUTHZ_NOT_EVALUATED(authority)]` | **neutral / undetermined** on the control — **never** refusal tone | "permission for this action has not been determined"; **no** refusal wording |

#### 5.5.1 Future contract scenarios — not atomic resolver fixtures

`ACT:FUTURE` cases have **no `action_id`**, and `ActionAffordance` requires a
closed-registry `action_id` (§4.1.1). They therefore **cannot produce an
`action_affordance` entry today** and are not atomic resolver fixtures. They
are recorded here so their safety requirements survive for the movement that
declares the action, and they are **excluded from `AC-CS-65`**.

Each states the affordance its owning movement must produce **once a concrete
action is declared in the closed registry** — not a result the resolver can
emit now.

| # | Basis | Scenario and future assumptions | Required affordance **once the action is declared** | Safety requirement preserved for the owning movement |
| --- | --- | --- | --- | --- |
| **S-FUT-1** *(was H5d)* | `[ACT:FUTURE · IN:SYNTHETIC(M12)]` | as `H5a`; a **retry-of-collection** action. **No current registry entry supplies retry-of-collection semantics** — asserted, not assumed. Future assumptions: the action is declared in the closed registry; it is a **new typed job**, never a mutation of a historical outcome (`AC-RT-6`); its own retry contract may impose a ledger window or minimum re-execution interval | `AVAILABLE_FOR_SUBMISSION` at presentation time, with the window enforced as an **`E7`-phase** decision and never claimed at render | **A prior failure is never a grant.** Retry must be a new typed job; any ledger or minimum-interval refusal is a correct outcome, enforced server-side at admission — owner `M7`/`M12` |
| **S-FUT-2** *(was H8b)* | `[ACT:FUTURE · IN:CURRENT]` | as `H8a`, but a CLASS 2 action is declared. **CLASS 2 has no member today** — asserted. Future assumptions: the action exists in the closed registry with class `CLASS_2_OPERATIONAL_STATE_CHANGE`; `OP.2`'s authorizer is the applicable authority and returns its unconditional deny (that authorizer **does** exist today, so the input basis is current) | `DISABLED_KNOWN_BLOCKER`, `[E3: ACTION_REFUSED(CLASS_2_OPERATIONAL_STATE_CHANGE), E4: ACTION_DENIED(OP.2 authority, authorization_not_configured)]` — **both preserved** | **Both refusing gates must be named**, never a bare greyed control (`CON.0` §9); the taxonomy refusal must not be masked by the authorization denial or vice versa — owner `M14`/`OP.2` |

#### 5.5.2 Multi-action scope tests — not atomic cases

`RI-1` and `RI-2` scope is a statement about **several** actions at once, so it
cannot be expressed as an atomic one-action case. They are therefore
**multi-action scope tests**, deliberately outside the atomic set. Both use
`[ACT:CURRENT · IN:SYNTHETIC(M10)]`. *(Historical
note, non-normative: revision 5 carried these as atomic cases `H9b` and `H9c`
with a false "one named action per row" claim; those identifiers are retired.)*

| id | Fixture | Declared actions | Required assertion |
| --- | --- | --- | --- |
| **S-RI-1** | `RI-1` holds (`K1`–`K6` all `TRUE`) | `config_refresh_cp` (declares the support fact as an `E6` prerequisite) **and** `report_rebuild` (does not) | `config_refresh_cp` → `DISABLED_KNOWN_BLOCKER [E6: support_inconsistency]`; `report_rebuild` → resolves on its own gates, **unaffected** by `RI-1` |
| **S-RI-2** | `RI-2` holds | **at this baseline: `report_rebuild` alone**, since no enrollment action is declared (`enroll_device` ∉ `JOB_REGISTRY`) | `report_rebuild` → resolves on its own gates, **unaffected** by `RI-2`; and the enrollment-shaped semantics are asserted supplied by **no** current registry entry, so **no entry** is produced for them. Should an enrollment action later be declared, it is blocked by `E6: reconciliation_inconsistency` |
| **S-CX1** | `CX1` holds | `recovery_attest_cp` (targets the disputed subject) **and** `report_rebuild` (targetless) | `recovery_attest_cp` → `DISABLED_KNOWN_BLOCKER [E5: subject_integrity_unmet]`; `report_rebuild` → `AVAILABLE_FOR_SUBMISSION`, with **no invented subject prerequisite** |

#### 5.5.3 Severity, settled

`CX1` (`H9a`, `S-CX1`) is the **only** case taking danger semantics, because it
is the only genuine unsafe contradiction. `RI-1` and `RI-2` are bounded
inconsistencies and take **muted** semantics; `SOURCE_TRUST_LIMITED` and
`IDENTITY_TRANSLATION_REQUIRED` are comparability limitations and take muted
semantics. Because `RI-1`/`RI-2` are not classified as contradictory or unsafe
state, `AC-DIF-6`, `AC-DIF-8` and `AC-CS-47` apply to `CX1` alone and **no
frozen-parent amendment is required for this point**.

#### 5.5.4 What a re-collection can re-evaluate — and the one unresolved question

The previous revision rendered `config_refresh_cp` as submittable while its
copy said collecting again could not help. That was a real contradiction, and
it is resolved from source rather than from the action's name.

**Source.** `config_refresh_cp` maps to workflow `cp-config`
(`console/registry.py::JOB_REGISTRY`), which runs
`configuration/checkpoint_config_collector.py`. That collector calls
`_classify_platform(...)` (l.926-941) on every host row it builds (l.1469-1471)
and emits a **platform-family classification with a confidence grade** —
`gaia_embedded` / `gaia` / `unknown`, at `HIGH` / `MEDIUM` / `LOW`, each with a
named evidence basis. The frozen `PCP.0` §8 lists **"platform classification
from the CP config collector"** among the capability projection's inputs.

**Therefore:** a `config_refresh_cp` run **can** re-evaluate a `D3` input. It
re-derives the platform family and its confidence, so a capability judged
`UNSUPPORTED` on a `LOW`-confidence `unknown` platform may resolve differently
once the classification improves. This satisfies §8.1's rule that a successful
collection may change `D3` **only if the run carried positive support
evidence** — here it does.

**Consequence for copy.** `H4a`–`H4d` render `AVAILABLE_FOR_SUBMISSION` and
must state *what* a re-collection re-evaluates. The blanket claim "collecting
again will not help" is **removed** from them; it is permitted only where the
reason is `TERMINAL` (`H4e`).

**`UNSUPPORTED` reason classes.** Every `UNSUPPORTED(reason)` must declare one:

| Reason class | Meaning | Affordance consequence |
| --- | --- | --- |
| `REVALIDATABLE(<input>)` | the reason rests on an input a named action can re-derive — e.g. `platform_classification` | the re-deriving action stays submittable; copy names the input |
| `TERMINAL` | no available action can re-evaluate the reason | the capability-specific action declares an `E6` prerequisite and is `DISABLED_KNOWN_BLOCKER` |

**Separation of responsibility.** `M3` defines **total behaviour when the
remediation classification is missing or malformed**. `M10` owns the future
mapping of concrete vendor reason codes to `REVALIDATABLE(<input>)` or
`TERMINAL`. Neither substitutes for the other.

**Fail-closed fallback — normative, and complete.** When a reason's remediation
class is **absent, malformed or unknown**:

| Question | Resolution |
| --- | --- |
| is the `UNSUPPORTED` conclusion itself still positively supported? | if **yes**, `primary_status` **may remain `UNSUPPORTED`** — the support conclusion and its remediation class are different facts |
| what does the capability-specific **collection** action resolve to? | **`UNDETERMINED`**, with `undetermined_reasons = [E6: unsupported_remediation_class_unresolved]` |
| may it resolve `AVAILABLE_FOR_SUBMISSION`? | **No.** Never |
| what may the copy say? | it may state the vendor reason. It must **not** claim that re-collection will help, and must **not** claim it cannot help |
| what if the support conclusion is itself malformed or insufficiently evidenced? | do **not** use this fallback — resolve `D3` through the existing honest-`UNKNOWN` rules (rank 8 / §8.2), which already cover it |

**Why `UNDETERMINED` rather than `AVAILABLE_FOR_SUBMISSION`.** The previous
revision defaulted an unclassified reason to `REVALIDATABLE` for presentation.
That renders the collection action submittable, which asserts — without
evidence — both that re-contact is **useful** and that it is **safe** to
attempt against a device the product has concluded does not support the
capability. Neither inference is available. `UNDETERMINED` states the actual
epistemic position: the product does not know whether re-collection would help,
and says so rather than inviting the attempt.

**`TERMINAL` still requires positive evidence.** The fallback never asserts
`TERMINAL`; it asserts only that the class is unresolved. `TERMINAL` copy
("no re-collection can change it") remains permitted **only** where the reason
positively carries that class — `H4e`.

**`UCQ-1` — open contract question, owned by `M10`.** *Which* concrete
`UNSUPPORTED` reason codes are `REVALIDATABLE` and which are `TERMINAL` is not
established by the repository, because `D3` has no producer and no reason codes
exist to classify. `UCQ-1` is scoped to that **membership** question only —
`M3`'s behaviour above is complete and deterministic without it. `UCQ-1` is
**not** a Product Owner decision and adds no seventh `PO-M3` item.

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

An action is **declared** against a domain with its availability and reason;
the renderer emits only those whose backend contract exists. A declared action
that is not `AVAILABLE_FOR_SUBMISSION` is **shown with its state named**, never
a bare greyed control. Three presentation states must remain distinguishable:

| Presentation state | Source | Tone | Copy shape |
| --- | --- | --- | --- |
| **confirmed refusal / denial** | `blocking_reasons` containing `ACTION_REFUSED(class)` or `ACTION_DENIED(authority, reason)` | refusal | names the refusing gate or authority and its reason |
| **undetermined permission** | `undetermined_reasons` containing `AUTHZ_NOT_EVALUATED(authority)` | **neutral / undetermined — never refusal** | "permission for this action has not been determined"; no refusal wording |
| **no applicable authority** | `E4 = NO_APPLICABLE_AUTHORITY` | invisible — nothing is said about authorization | the control's state comes from its other gates alone |

A control that is `AVAILABLE_FOR_SUBMISSION` is **advisory**: it says no
presentation-time blocker is known, never that execution is permitted.

### 6.4 Status badges / chips and tone tokens

One table per output. Action outcomes are **not** here — they are rendered on
the control they belong to, from that action's `blocking_reasons[]` (§4.1.1).

| `primary_status` | Tone | Rationale |
| --- | --- | --- |
| `AVAILABLE` | `success` / normal | — |
| `NOT_APPLICABLE` | `info` | structural, not a problem |
| `NOT_CONFIGURED` | `info` | actionable, not a fault |
| `NOT_ENROLLED` | `info` | actionable, not a fault |
| `POLICY_DISABLED` | `muted` + explicit "disabled by policy" label | intentional, not a failure |
| `DEVICE_DISABLED` | `muted` + explicit label | intentional |
| `UNSUPPORTED` | **its own token**, not `neutral` | C2: `neutral` is what an *unrecognised* value gets; a real product statement must not share that tone |
| `UNKNOWN(identity_contradiction)` | **`danger`** | `CX1`, the only genuine unsafe contradiction, per `AC-DIF-6` |
| `UNKNOWN(support_inconsistency)` / `UNKNOWN(reconciliation_inconsistency)` | `muted` | `RI-1` / `RI-2` are **bounded inconsistencies**, not contradictory or unsafe state (§5.1) |
| `UNKNOWN(<any other reason>)` | `muted` | an honest gap, not a fault |
| `COLLECTION_FAILED` | `danger` | actual fault |
| *(`NOT_SHIPPED` is not a `CapabilityState`)* | — | it is a `SurfaceOmissionReason`; under `OMITTED` nothing renders at all (§4.1) |

| `capability_qualifiers` | Tone |
| --- | --- |
| `STALE` | `muted` + timestamp |
| `PARTIAL` | `muted` |
| `SOURCE_TRUST_LIMITED` | `muted`, **no timestamp** |
| `IDENTITY_TRANSLATION_REQUIRED` | `muted`, **no timestamp**, **never `danger`** — a comparability limitation, not a fault (§2.7 `FA-8`) |
| `NOT_SCHEDULED` | `muted` |
| `SCHEDULE_UNKNOWN` | `muted` |
| `MEMBER_SPECIFIC` | **`--member-specific`** — see §6.6 |

| `action_affordance` on a control | Tone |
| --- | --- |
| `DISABLED_KNOWN_BLOCKER` — any `blocking_reasons` entry, including `ACTION_REFUSED` and `ACTION_DENIED` | **refusal** tone on **that control only**; never applied to the capability's own chip |
| `UNDETERMINED` — `AUTHZ_NOT_EVALUATED(authority)` | **neutral / undetermined** tone on that control only; **never refusal tone and never refusal wording** |
| `AVAILABLE_FOR_SUBMISSION` | normal; advisory, never presented as an execution grant |

**Distinguishability is by label and reason, not by unique colour.** Several
states legitimately share a tone — `POLICY_DISABLED`, `DEVICE_DISABLED` and
most `UNKNOWN` reasons are `muted`; `NOT_APPLICABLE`, `NOT_CONFIGURED` and
`NOT_ENROLLED` are `info`. Tone conveys *severity family*, and there are fewer
severity families than states. What must be unique per state is the **text
label** and the **reason it carries**. `AC-CS-27` is written to that rule, not
to a unique-colour rule.

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

### 6.8 Static report parity, and one structural divergence

Parity is compared **per shell-scoped surface**, and only where each shell
actually resolves that surface: a surface the report does not ship is not a
parity failure, it is a different `D1` for a different shell. `D1` — and
therefore the union tag — is an **internal generation decision** in the export
pipeline. It is **not** exported artifact content: the report contains the
surfaces it ships and no record of the ones it did not.

| Carried in the exported report | Withheld |
| --- | --- |
| every surface the report shell itself ships, resolved by its own `D1` | any trace of a surface it does not ship |
| `primary_status` and its copy | — |
| every `capability_qualifier`: `STALE`, `PARTIAL`, `SOURCE_TRUST_LIMITED`, `IDENTITY_TRANSLATION_REQUIRED`, `NOT_SCHEDULED`, `SCHEDULE_UNKNOWN`, `MEMBER_SPECIFIC` | — |
| `evidence_presentation`, with timestamps and provenance | — |
| the identical information architecture (`AC-SH-3`) | live refresh |
| — | **the entire `action_affordance[]` projection** |

**The divergence is structural, not selective.** The report declares **no
actions** (`AC-SH-1`: absent, never disabled). With no declared action there is
no `ActionAffordance` to produce, so the whole projection is **not generated**.
The report filters nothing: there is no shared flat list of action outcomes to
filter, because action outcomes live only inside `ActionAffordance` (§4.1.1).

**Where the shells legitimately differ, and where they may not.** The shell
(`I20`) determines the **declared action set** and the **acquisition/liveness**
characteristics (snapshot vs live refresh). It must **never** cause identical
evidence to yield a different `primary_status`, different
`capability_qualifiers` or a different `evidence_presentation`.

`NOT_ENROLLED` is reported as a state with no enrollment affordance —
enrollment is console-only and gated (`AC-SH-1`, `AC-EN-11`).

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
2. **`CX1`, `RI-1` and `RI-2` each resolve to `UNKNOWN` within their own
   declared scope** (§5.1), naming both disagreeing inputs. Never the more
   favourable one, never a silent reconciliation. Facts that merely co-occur
   are none of the three. Only `CX1` is a contradiction; `RI-1` and `RI-2` are
   bounded inconsistencies and are never treated as unsafe state.
3. **The absence of an actor-authorization model is neither a grant nor a
   blanket denial.** Where no such authority applies, `E4` is
   `NO_APPLICABLE_AUTHORITY` — non-blocking and **not a grant** — and the
   action rests on its other gates. `AUTHZ_NOT_EVALUATED` is reserved for a
   real, named, applicable authority that was not consulted. CLASS 0 console
   operations are permitted today under the session and taxonomy gates
   (`E1`–`E3`).
4. **Action blocking is prerequisite-scoped** (§5.4 rule 1). `UNKNOWN`,
   `INSUFFICIENT_EVIDENCE` and `STALE` block an action **only where that
   action declares the missing or current fact as its own prerequisite** —
   the frozen parent's "disabled if the action needs the missing fact". They
   never block evidence-gathering actions (§5.4 rule 2).
5. **Identity joins are on canonical ids only** — never a hostname, display
   label or inferred ordinal (`AGENTS.md` presentation-identity law). CX1
   withholds evidence rather than joining on unproven identity.

#### 8.2.1 Persistence — the capability projection versus the presentation resolution

Two different objects. Conflating them is what let action, taxonomy and
authorization state drift toward becoming persisted fields.

| Object | What it holds | Persistable? |
| --- | --- | --- |
| **capability projection** | capability-domain facts only: the dimension values `D2`–`D6` it computed, its **basis/run information**, its **semantic identity** (canonical subject, exact capability, vendor/platform/entity kind) and the **producer/support-rule context** permitted by the frozen storage contract | **yes** — `AC-ST-2` lists *capability projections* among what the approved local store may own. `M4` owns any schema; **none is designed here** |
| **presentation resolution** | the composed render-time result: the union tag, `primary_status`, `capability_qualifiers`, `evidence_presentation`, shell composition, `action_affordance`, authorization outcome and contradiction/inconsistency presentation | **no** — never persisted as authoritative truth |

**Action declarations, taxonomy class and authorization state must not become
fields of the persisted projection.** They belong to the action and to its
decision phase, not to the capability's domain facts. A projection that carried
them would silently become an authorization or admission record.

**Every persisted projection is bound to** its canonical subject, its exact
capability, the vendor/platform/entity kind, and the relevant producer and
support-rule versions — the same `I14` metadata `RI-1` comparability depends on.
A projection is reusable only for the exact binding it was computed under.

**Identity-translation uncertainty forbids cache reuse across an unproven
identity mapping.** Where `D6e` `IDENTITY_TRANSLATION_REQUIRED` holds, or a
canonical join would require a translation the product has not proven, a cached
projection may **not** be reused across that mapping (`AGENTS.md`
opaque-identifier law).

**Validity and invalidation** (contract, not schema):

1. the projection records every consumed authority **generation** and every
   producer and support-rule **version**;
2. a cache hit is valid only when the resolver **affirmatively establishes**
   that all of them still match current authority; an uncomparable generation
   or version is **not** a hit;
3. **missed or delayed invalidation is assumed, not excluded** — the absence of
   an invalidation signal is never evidence of validity;
4. a support-rule or platform change **invalidates every projection whose
   `RI-1` outcome depended on it**, so a cached inconsistency (or a cached
   absence of one) cannot outlive the rule that produced it;
5. it **fails closed** — an unvalidatable projection is discarded and
   recomputed, never served as truth (`AC-ST-6`);
6. it is RuntimeRoot-resident and excluded from the support bundle (`AC-ST-6`).

**A cached projection never becomes authority.**

| Never | Because |
| --- | --- |
| **endpoint authority** | endpoints resolve server-side at the authorized execution stage; no copied endpoint is retained as fallback (`AC-ST-5`) |
| **authorization authority** | the applicable authority is consulted at decision time (§5.3, `E4`) |
| **action-admission authority** | the registry stays authoritative at admission **and again immediately before execution** (`AC-ST-4`, `E7`); a cached projection substitutes for neither |
| **execution authority** | presentation never grants execution (§4.1.1, §5.4.2) |
| **persistence authority** | authoritative durable records are unchanged by `M3` and are never overwritten by a projection |

---

## 9. Acceptance criteria

Individually numbered, implementable and testable without subjective visual
judgment. Each names the movement expected to own it. A criterion whose surface
does not yet exist is **not waived** — it becomes that movement's criterion.

### 9.1 Coverage contract for the resolver

A later movement proves the resolver over **finite input classes plus named
boundary cases**, not an unbounded product and not a hand-picked sample.

| Input | Finite classes |
| --- | --- |
| D1 | **3** (`SURFACE_PRESENT`, `SURFACE_ABSENT`, `SURFACE_ELIGIBILITY_UNRESOLVABLE`) |
| D2 entity applicability | 3 |
| D3 vendor support | 3 |
| D4 reconciliation | 5 |
| D5 capability policy | 4 |
| D6a freshness | 2 |
| D6b completeness | 4 |
| D6c collection outcome | 5 |
| D6d expected-source trust | 2 |
| D6e identity translation | 2 |
| `RI-1` comparability (`I14`) | **6 semantic classes** — the six §5.1.1 precedence rows |
| member context | 3 |
| action taxonomy class | the 5 classes |
| `E4` applicability (`I18`) | **4** (`NO_APPLICABLE_AUTHORITY`, `PERMITTED`, `DENIED`, `AUTHZ_NOT_EVALUATED`) |
| action subject requirement | 2 (targetless / entity-targeted) |
| shell | 2 (report / console) |

Required coverage:

1. **All four stage-0 cases** `V-A`–`V-D`: `OMITTED{NOT_SHIPPED}` requires a
   positively established `SURFACE_ABSENT`; `OMITTED{SURFACE_ELIGIBILITY_UNRESOLVABLE}`
   asserts **nothing** about what the build ships; neither carries any
   capability output.
2. **Union discipline** — `OMITTED` is asserted to carry no `primary_status`,
   no qualifiers, no evidence presentation and no affordance, in any form.
   `NOT_SHIPPED` is asserted **absent** from `CapabilityState`, which is
   asserted to hold exactly nine values.
3. **The omission diagnostic pass is optional** — a build that performs it and
   a build that does not both produce the same `OMITTED` reason and the same
   (nothing) rendering. Under `RESOLVED`, a diagnostic **may** be present; no
   assertion requires it to be absent.
4. **Every reachable ladder rank** fires at least once, including ranks 0, 1
   and 11.
5. **`CX1`, `RI-1`, `RI-2`** each exercised, plus at least four
   non-inconsistent co-occurrences asserted **not** to trigger any of them:
   `UNSUPPORTED` with superseded historical evidence; `POLICY_DISABLED` with
   fresh evidence; `POLICY_UNKNOWN` with usable evidence;
   `IDENTITY_TRANSLATION_REQUIRED` without `CX1`'s condition.
6. **`CX1` subject scoping** — with one entity-targeted and one targetless
   declared action, the targeted one is blocked by `E5` and the targetless one
   is asserted **unaffected**, with no invented subject prerequisite (`S-CX1`).
7. **`RI-1` comparability enumerated over all six semantic classes** of
   §5.1.1: scope-`FALSE` (K1/K2) → evidence excluded from this
   subject/capability; scope-`UNESTABLISHED` → no guessed join;
   `K6 = FALSE` → historical/non-comparable; K3/K4/K5 `FALSE` → historical,
   `D3` conclusion stands; residual `UNESTABLISHED` → honest unknown;
   all-`TRUE` → `RI-1`. Mixed cases assert the stated precedence.
8. **Every capability qualifier** exercised alone and with a non-`AVAILABLE`
   primary; `SOURCE_TRUST_LIMITED` and `IDENTITY_TRANSLATION_REQUIRED`
   asserted independently settable with source reason codes preserved.
9. **Every input class of §4.1.5** exercised, with `POLICY_UNKNOWN` asserted
   **not** malformed and `NO_APPLICABLE_AUTHORITY` asserted non-blocking and
   **not a grant**.
10. **Presentation-time boundary** — `E7` is asserted **never** evaluated at
    render; an `AVAILABLE_FOR_SUBMISSION` action submitted afterwards is
    asserted still to be checked by the server, and `E7`'s **two** registry
    checks (admission, immediately before execution) are both asserted to run.
11. **`E4` totality** — all four outcomes exercised;
    `AUTHZ_NOT_EVALUATED` asserted emitted only where a real named applicable
    authority exists; an **undeclared** action asserted to produce **no entry**.
12. **Affordance keying** — with at least two declared actions resolving
    differently, each result carries its own `action_id` and reason lists, and
    no action outcome appears in `capability_qualifiers`.
13. **Named boundary cases**: `fresh == false` with no anchor;
    `data_state == partial` with `D6c == success`; `PROVENANCE_UNVERIFIED` with
    `fresh == true`; `IDENTITY_TRANSLATION_REQUIRED` with `fresh == true`;
    `POLICY_UNKNOWN` with fresh evidence; an action with an empty `E6` set
    asserted still to evaluate the other presentation-time gates; a targetless
    action asserted to acquire no invented subject prerequisite; a malformed
    dimension value; unresolvable `D1`.
14. **Generated combination coverage** over the D2–D6 cross-product for the
    ladder and `evidence_presentation`, making `AC-CS-12` falsifiable.
    Affordance is covered per (taxonomy class × `E4` outcome × `E6`
    present/absent × subject required/not), not crossed with the full product.
15. **Cache** — matching generation set; mismatched generation; mismatched
    producer/support-rule version; uncomparable generation; a **missed
    invalidation event**; a support-rule change invalidating a cached `RI-1`
    outcome; and an attempted reuse **across an unproven identity mapping**,
    asserted refused.
16. **Severity** — `CX1` asserted `danger`; `RI-1`, `RI-2`,
    `SOURCE_TRUST_LIMITED` and `IDENTITY_TRANSLATION_REQUIRED` asserted muted.

This is a contract for what must be proven, not an implementation of the tests.

### Ownership

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-1` | Every dimension `D1`…`D7` has exactly one authoritative owning module, and no second module writes it | `M10`/`M12` |
| `AC-CS-2` | `D1` is the only input any surface-disposition decision reads, and stage 0 decides it **before** every other stage. A test over the full cross-product shows no rendering change when `D2`…`D7` vary, **including when a contradiction class holds**: an absent or unresolvable surface never renders (§5.0 `V-B`/`V-D`) | `M10`/`M11` |
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
| `AC-CS-12` | Resolution is a pure function of the **complete input contract of §4.1.3** — `I1`–`I20`, including D6's **five** facets `D6a`–`D6e`, member-comparison context, the declared action set with taxonomy classes and declared subject requirements, per-action authorization applicability and outcome, and the shell. The same input always yields the same **tagged-union result**: either `RESOLVED` with its four outputs or `OMITTED` with its reason. The four capability outputs do **not** always exist | `M10` |
| `AC-CS-13` | The §5.2 ladder and `evidence_presentation` are exercised over the generated cross-product of §9.1's finite input classes, plus every named boundary case — not a hand-picked sample | `M10` |
| `AC-CS-14` | Qualifiers are each a pure function of **one facet** (§4.3) and are computed independently of the primary and of each other | `M10` |
| `AC-CS-15` | `UNKNOWN` outranks `NOT_CONFIGURED`: `NOT_CONFIGURED` is emitted only on **positive** evidence of absence | `M10` |
| `AC-CS-16` | `DEVICE_DISABLED` copy names every lower-ranked blocking dimension it masks | `M10` |
| `AC-CS-17` | *(superseded by `AC-CS-65`, which covers the atomic case set of §5.5 across all four `RESOLVED` outputs. Id retained so existing references resolve.)* | `M10` |

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
| `AC-CS-30` | A declared action that is not `AVAILABLE_FOR_SUBMISSION` is shown with its state named — never a bare greyed control. **Confirmed refusal**, **undetermined permission** and **no applicable authority** are three distinguishable presentations (§6.3): only the first uses refusal tone and refusal wording | `M11` |
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
| `AC-CS-38` | A control's accessible name states its actual presentation state: a refusal names the refusing gate or authority; an `UNDETERMINED` control announces that permission has not been determined and **never** uses refusal wording; an `AVAILABLE_FOR_SUBMISSION` control announces no authorization claim | `M11` |

### Report behaviour

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-39` | For a surface both shells ship, the exported report renders the same `primary_status` and the same `capability_qualifiers` as the console for the same evidence. Parity is asserted per shell-scoped surface only | `M11` |
| `AC-CS-40` | The report generates **no** `action_affordance[]` projection at all — not a filtered subset — and no enrollment affordance; both are absent, not disabled (`AC-SH-1`). `D1`/the union tag is an internal generation decision, not exported artifact content | `M11` |

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
| `AC-CS-47` | `--danger` / red is emitted only for `COLLECTION_FAILED`, `EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, **`CX1` genuine unsafe identity contradiction** and failed job outcomes (`AC-DIF-8`). `RI-1`/`RI-2` bounded inconsistencies, `SOURCE_TRUST_LIMITED` and `IDENTITY_TRANSLATION_REQUIRED` are **muted**, and are not classified as contradictory or unsafe state — so no frozen-parent amendment is needed for this point | later UI movement |

### Regression protection for existing vocabularies

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-48` | `console/jobs.py::TERMINAL_STATES` and the `queued`/`running` set are byte-identical to their pre-`M3` values — asserted directly | `M3` onward |
| `AC-CS-49` | `utils/operate/states.py::ActionState` membership is unchanged | `M3` onward |
| `AC-CS-50` | `utils/action_taxonomy.py`'s five classes and their permissions are unchanged | `M3` onward |
| `AC-CS-51` | `utils/compliance_posture.py::STATUS_VALUES` is unchanged; no capability state leaks into a compliance control status | `M3` onward |
| `AC-CS-52` | `utils/discovery_lifecycle.py::LifecycleState` is unchanged | `M3` onward |
| `AC-CS-53` | `utils/device_registry.py`'s lifecycle vocabulary is unchanged; `D4` adds a **projection**, not a registry state | `M3` onward |

### Facet separation

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-54` | `STALE` is emitted **only** from D6a (`fresh == false`) with a real anchor from `collected_at` / `last_successful_collection`; it is never emitted from `PROVENANCE_UNVERIFIED` or any D6d value (C9, `FA-1`/`FA-2`) | `M10` |
| `AC-CS-55` | `SOURCE_TRUST_LIMITED` never renders a timestamp and never renders age wording; `PROVENANCE_UNVERIFIED` carries no timestamp in source (V12a) | `M11` |
| `AC-CS-56` | `UNKNOWN_SHELL` maps to `SUPPORT_UNKNOWN` → `UNKNOWN`, never to `UNSUPPORTED` (V9a, C10, `FA-3`) | `M10` |
| `AC-CS-57` | `D6a`, `D6b`, `D6c`, `D6d` and `D6e` are independently settable and independently rendered; a test varying one asserts the other **four** unchanged | `M10` |
| `AC-CS-58` | A failed latest attempt (D6c) leaves retained evidence, its freshness and its completeness unchanged, and the view `POPULATED` | `M10`/`M11` |

### Schedule knowledge

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-59` | `NO_APPLICABLE_SCHEDULE` is emitted only after a **successful authoritative read** returning no applicable entry; a missing producer, a failed read, a malformed store or a granularity mismatch yields `POLICY_UNKNOWN` | `M12` |
| `AC-CS-60` | No output asserts that a schedule has **never** existed; the vocabulary makes only present-tense claims | `M12` |
| `AC-CS-61` | `D5` never changes `evidence_presentation`, and `POLICY_DISABLED` / `SCHEDULE_UNKNOWN` never make a manual collection, first-contact or retry action ineligible | `M10`/`M12` |

### Resolution determinism and scope

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-62` | Stage 1 recognises exactly three classes and keeps them distinct: **`CX1` is the only contradiction** and the only unsafe state; **`RI-1` and `RI-2` are bounded inconsistencies** and are never classified as contradictory, unsafe or danger. At least four co-occurrences are asserted to trigger none of them | `M10` |
| `AC-CS-63` | Each class applies only its declared scope: `CX1` withholds evidence for the disputed subject and blocks, via `E5`, **only actions whose declared subject or target depends on the disputed identity** — targetless and unrelated-subject actions are unaffected and receive **no invented subject prerequisite**; `RI-1` retains evidence and blocks only actions declaring the support fact; `RI-2` retains evidence and blocks only a declared enrollment-shaped action | `M10` |
| `AC-CS-64` | `AVAILABLE` requires the rank-10 positive conjunction; a malformed, absent or unclassifiable **required** input resolves to `UNKNOWN(unclassified_input)` and never to `AVAILABLE` or to `AVAILABLE_FOR_SUBMISSION`. A **validly unknown** value (`SUPPORT_UNKNOWN`, `POLICY_UNKNOWN`, `APPLICABILITY_UNKNOWN`, `RECONCILIATION_UNKNOWN`) is **not** malformed and never reaches rank 11 | `M10` |
| `AC-CS-65` | Every **atomic** case in §5.5 resolves exactly as its row states across all four `RESOLVED` outputs — `H1`, `H2`, `H3`, `H4a`–`H4f`, `H5a`–`H5c`, `H6`, `H7a`/`H7b`, `H8a`, `H9a`/`H9a-t`, `H10`, `H11a`–`H11c`, `H12` (**23 cases**) — plus the §5.5.2 scope tests `S-RI-1`, `S-RI-2`, `S-CX1`. The `ACT:FUTURE` scenarios `S-FUT-1` and `S-FUT-2` (§5.5.1) are **excluded**: they have no `action_id`, so no `action_affordance` entry exists to assert. Each case's two-axis basis is asserted per §5.5: an `ACT:CURRENT` case's named `action_id` **is** a `JOB_REGISTRY` member; an `ACT:ACTION_FREE` case produces **no** affordance entry; an `IN:SYNTHETIC(owner)` or `IN:FUTURE_PRODUCER(owner)` case asserts the named producer is **absent** and supplies the premise explicitly. **Every `action_id` appearing in §5.5 is a real registry member** | `M10`/`M11` |

### Authorization applicability and separation

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-66` | The `OP.2` authorizer is consulted for CLASS 2 only. For an action to which no actor-authorization authority applies, `E4` is `NO_APPLICABLE_AUTHORITY` — non-blocking, **not a grant**, and distinct from `PERMITTED`, `DENIED` and `AUTHZ_NOT_EVALUATED`. No CLASS 0 console operation is denied by reference to the `OP.2` authorizer | `M14` |
| `AC-CS-67` | `E4` is **total**: every action resolves to exactly one of `NO_APPLICABLE_AUTHORITY`, `PERMITTED`, `DENIED(authority, reason)` or `AUTHZ_NOT_EVALUATED(authority)`. `DENIED` and `AUTHZ_NOT_EVALUATED` each name a real authority; `AUTHZ_NOT_EVALUATED` is emitted **only** when a real, named, applicable authority exists and was not consulted, never for an undeclared or unimplemented action | `M11`/`M14` |
| `AC-CS-68` | A cached or shared capability projection is never an input to an authorization decision (§5.3 rule 4) | `M10`/`M14` |
| `AC-CS-69` | Evidence-gathering actions (first contact, collect now, retry) declare no `E6` prerequisite on the evidence they produce and are never blocked by its absence. They remain subject to **every** other gate `E1`–`E5`, `E7`, to their own other prerequisites, and — for retry — to its own retry contract; a prior failure never grants retry | `M7`/`M8` |

### Persistence

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-70` | Existing durable evidence — registry rows, last-known-good, CAS configuration evidence, job records — is neither invalidated nor overwritten by any capability state | `M4`/`M10` |
| `AC-CS-71` | A persisted capability projection records **every** consumed authority generation **and** producer/support-rule version. A cache hit is valid only when the resolver affirmatively establishes that all of them still match current authority; an uncomparable generation or version is not a hit. Missed or delayed invalidation is assumed, so the absence of an invalidation event is never evidence of validity. It fails closed rather than serving stale truth | `M4`/`M10` |
| `AC-CS-72` | No resolved `primary_status` or qualifier set is persisted as authoritative truth | `M10` |
| `AC-CS-73` | A cached projection is never endpoint, authorization, action-admission or persistence authority. The registry is still read at admission **and** immediately before execution (`AC-ST-4`), and a disabled or unresolvable target causes refusal or abort before contact | `M4`/`M6`/`M7` |

### Visibility isolation

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-74` | `D1 = SURFACE_ABSENT` yields `OMITTED{reason: NOT_SHIPPED}` and ends resolution: nothing renders, and no contradiction, inconsistency, qualifier, evidence, policy or authorization outcome can make it render (§5.0 `V-A`/`V-B`) | `M10`/`M11` |
| `AC-CS-75` | The omission diagnostic pass is **OPTIONAL** and render-independent: a build that performs it and a build that does not produce the same `OMITTED{reason}` and the same (nothing) rendering. When performed it attaches a `Diagnostic` to the `OMITTED` record and **cannot affect rendering**. Consistent with `AC-CS-96` | `M10` |
| `AC-CS-76` | `D1 = SURFACE_ELIGIBILITY_UNRESOLVABLE` yields `OMITTED{reason: SURFACE_ELIGIBILITY_UNRESOLVABLE}` with `d1_input_unresolvable`, failing closed toward omission and never toward rendering; it asserts **nothing** about what the build ships (§5.0 `V-D`) | `M10` |
| `AC-CS-77` | No stage after stage 0 can alter the union tag — asserted by varying every later input against each fixed `OMITTED` variant | `M10` |

### Mandatory action gates

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-78` | Presentation resolution evaluates **only** the presentation-time gates — `E1`–`E3`, `E5`, `E6` and `E4`'s evaluable part — each once per decision phase, regardless of an action's `E6` contents; an **empty `E6` set never shortens the others**. **`E7` is server-owned** and is never evaluated at presentation time | `M10`/`M11` |
| `AC-CS-79` | `E5` requires only what the action's own contract requires; a **targetless** read (`target_mode="none"`) has no device or target prerequisite invented for it | `M10` |
| `AC-CS-80` | `AUTHZ_NOT_EVALUATED` yields `UNDETERMINED` affordance and is represented, rendered and audited **distinctly** from a confirmed `ACTION_DENIED`; it uses neutral/undetermined tone and copy and is **never** rendered with refusal tone or refusal wording | `M11`/`M14` |
| `AC-CS-81` | No example, table cell or rule states that an action is enabled, permitted or executable unconditionally. `AVAILABLE_FOR_SUBMISSION` is asserted to mean **only** that no presentation-time blocker is known — never that `E7` passed and never permission to execute | `M3` onward |
| `AC-CS-82` | Retry satisfies its own retry contract — a new typed job, never a mutated historical outcome, and subject to any ledger or minimum-re-execution window; a prior failure alone never grants it | `M7` |
| `AC-CS-83` | Manual collection is independent of `D5` scheduling policy and of nothing else: it passes `E1`–`E3`, `E5`–`E7`, enters the same admission coordinator and single orchestration path, and stays within the vendor interaction-safety budget | `M7`/`M12` |

### Input contract

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-84` | Every input `I1`–`I20` is supplied to the resolver, and each is classified absent / malformed / contradictory / incomparable / stale / unevaluated / validly-unknown **before** any output consuming it resolves | `M10` |
| `AC-CS-85` | `REGISTRY_ONLY` and its reason code make only a **present-tense** claim about the current evidence set; no output asserts that an entity was never observed | `M10` |

### Result algebra

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-86` | `OMITTED{reason: SURFACE_ELIGIBILITY_UNRESOLVABLE}` **never** asserts `NOT_SHIPPED` or any other positive claim about what the build ships; only `OMITTED{reason: NOT_SHIPPED}`, reached from a positively established `SURFACE_ABSENT`, may | `M10` |
| `AC-CS-87` | The result is a **tagged union**: `OMITTED` carries a `SurfaceOmissionReason` and an optional diagnostic and **no** capability output — not empty collections, not null placeholders, not a defaulted state. `NOT_SHIPPED` is a `SurfaceOmissionReason`, not a `CapabilityState`, and `CapabilityState` contains exactly the nine states reachable under `RESOLVED` | `M10` |
| `AC-CS-88` | Every `action_affordance[]` entry carries its own `action_id`, one `affordance` value, and its own `blocking_reasons` / `undetermined_reasons`; **no** action outcome appears in `capability_qualifiers` — asserted with at least two declared actions resolving differently. An **undeclared** action produces **no entry** | `M10`/`M11` |
| `AC-CS-89` | `E3` is evaluated **exactly once per decision phase** and is never re-read inside `E4`. This does **not** prohibit checks at different lifecycle phases: `E7`'s registry check at admission **and** its check immediately before execution are both required (`AC-ST-4`) and both preserved | `M10`/`M7` |
| `AC-CS-90` | `D6d` (`PROVENANCE_UNVERIFIED`) and `D6e` (`IDENTITY_TRANSLATION_REQUIRED`) are independently settable, carry their **source reason codes** into presentation, and neither yields `STALE`, the other's qualifier, or `CX1` on its own | `M10`/`M11` |
| `AC-CS-91` | `RI-1` comparability is **total** over `K1`–`K6` ∈ {`TRUE`, `FALSE`, `UNESTABLISHED`} with the §5.1.1 precedence: scope-`FALSE` (K1/K2) excludes the evidence from this subject/capability entirely; scope-`UNESTABLISHED` forbids a guessed join; `K6 = FALSE` is historical/non-comparable; K3/K4/K5 `FALSE` is historical with the current `D3` conclusion standing; any residual `UNESTABLISHED` is honest unknown; all-`TRUE` is `RI-1` | `M10` |
| `AC-CS-92` | The exported report generates no `action_affordance[]` projection at all. The shell may change the declared action set and acquisition/liveness characteristics, but **identical evidence yields identical `primary_status`, `capability_qualifiers` and `evidence_presentation` in both shells** | `M11` |
| `AC-CS-93` | Every `FA-*` row in §2.7 names exactly one owning `PO-M3-*` decision, and every `PO-M3-*` decision that owns rows lists them — asserted as a two-way mapping over §2.7 and §11.2 | `M3` onward |

### Presentation-time boundary

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-94` | No presentation-time result asserts that `E7` has passed. `AVAILABLE_FOR_SUBMISSION` means only that no presentation-time blocker is known, and is never treated as permission to execute | `M10`/`M11` |
| `AC-CS-95` | Server submission, admission and immediately-before-execution checks are re-evaluated in their own phases regardless of any affordance value — asserted by submitting an action rendered `AVAILABLE_FOR_SUBMISSION` and showing the server still evaluates every gate | `M7`/`M11` |
| `AC-CS-96` | The omission diagnostic pass is **optional**, render-independent, and never affects the union tag, the omission reason, or whether anything renders. Under `RESOLVED`, a diagnostic **may** be produced; no rule asserts it is unconditionally absent | `M10` |
| `AC-CS-97` | A persisted capability projection contains only capability-domain facts, basis/run information, semantic identity and producer/support-rule context. Action declarations, taxonomy class and authorization state are **never** fields of it, and it is never reused across an unproven identity mapping | `M4`/`M10` |

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

*Forward note (GOV.PO.1, 2026-09-08): a `nexus-decision-council` skill with
fresh-context seats now exists (`.claude/skills/nexus-decision-council/SKILL.md`).
That does not change this record: the round above remains a single
authoring session's self-critique and is not re-described as council work.*

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

### 11.1 Draft defects corrected — no decision required

Recorded so a reviewer can confirm each. Revisions 2 and 3 are summarised;
revision 4's corrections are listed in full.

| # | Defect | Corrected in | Clause |
| --- | --- | --- | --- |
| X1–X14 | provenance-as-freshness; `UNKNOWN_SHELL`-as-unsupported; `POLICY_UNSCHEDULED` default; blanket empty view; contradiction vs ladder; incomplete input tuple; `AVAILABLE` fall-through; misaligned hard-case table; `DenyAllAuthorizer` as product-wide; over-broad action blocking; persistence claim; coverage arithmetic; `AC-CS-27` palette; E3 framing | rev 2 | §2.3, §3.3, §4.1, §5, §8.2.1, §9.1 |
| X15–X26 | contradiction ranked above `D1`; undefined `D1` failure; incomplete inputs; undefined input conditions; missing mandatory gates; unevaluated-vs-denied; `H9` inversion; retry/manual exemption; `REGISTRY_ONLY` history claim; cache generations; incomplete FA inventory; stale AC count | rev 3 | §5.0, §5.4, §8.2.1, §2.7 |
| **X27** | **the duplicated concatenated `### 4.2` heading survived into the pushed document.** Revision 3's closure report said a duplicated heading had been "found and fixed" — that was true of one instance (`### 5.4.5`) and **false as a general claim**: an identical concatenation at `### 4.2` remained. The audit used `uniq -d` over whole heading lines, which cannot detect two headings concatenated onto **one** line, so the check reported clean while the defect stood. **The closure report was wrong and is corrected here.** The structure check is now a rendered-structure audit that detects concatenated headings, orphan table fragments and out-of-order sections | rev 4 | §4.2; validation §9.1 note |
| **X28** | §2.6 and §2.7 were emitted out of order (§2.7 before §2.6) | rev 4 | §2 ordering |
| **X29** | §6.4's qualifier table was split, leaving `SOURCE_TRUST_LIMITED` and `SCHEDULE_UNKNOWN` as an orphan two-row fragment with no header | rev 4 | §6.4 |
| **X30** | missing/malformed `D1` was mapped to `NOT_SHIPPED`, asserting a positive product claim from the absence of an answer | rev 4 | §3.3 D1 (three-valued), §5.0 (`OMIT_UNRESOLVABLE`), §4.1 |
| **X31** | no result algebra: the four outputs were described as if they always existed, including under omission | rev 4 | §4.1, §5.0 ("absent is not empty") |
| **X32** | `ACTION_DENIED` / `ACTION_REFUSED` / `AUTHZ_NOT_EVALUATED` sat unscoped in the flat qualifier list, so with two declared actions no reader could tell which was refused | rev 4 | §4.1.1 (action-keyed), §4.3, §6.4 |
| **X33** | report parity described as selectively hiding "action qualifiers" from a shared list | rev 4 | §6.8 (the whole projection is not generated) |
| **X34** | four server-owned enforcement gates were relabelled "authorization regimes", and the action taxonomy was evaluated twice (`G2`, then again inside `G3`) | rev 4 | §3.3 D7 (authorization only), §3.4 `E1`–`E7`, §5.4 |
| **X35** | `PROVENANCE_UNVERIFIED` and `IDENTITY_TRANSLATION_REQUIRED` collapsed into one `SOURCE_TRUST_LIMITED` meaning despite differing in established fact, explanation, remediation and severity | rev 4 | §3.3 D6d/D6e, §4.3, §6.4, §2.7 `FA-8` |
| **X36** | `CX2` conflicted with `H4` and with the contract's own statement that `UNSUPPORTED` may coexist with retained historical evidence | rev 4 | §5.1.1 comparability K1–K6 |
| **X37** | `H1`–`H11` resolved several actions or subjects per row, contradicting the single-named-action baseline | rev 4 | §5.5 atomic subcases |
| **X38** | §5.2.2 stated `collect_now` and `retry` "remain eligible" unconditionally | rev 4 | §5.2.2, §5.4, `AC-CS-81` |
| **X39** | `FA-1`…`FA-9` had no owning PO decision, and the withheld-approval instruction told implementers to revert to semantics the draft had demonstrated false | rev 4 | §2.7 owner column; §11.2; the withheld-approval rule |
| **X40** | the result was described as "four outputs, absent under omission" while `primary_status = NOT_SHIPPED` sat inside the omission record — both could not be true | rev 5 | §4.1 tagged union; §4.2 (nine values); `AC-CS-87` |
| **X41** | `eligible: true\|false` claimed every gate `E1`–`E7` had passed, including `E7` checks that cannot run at render time | rev 5 | §4.1.1 `action_affordance`; §4.1.2; §5.4; `AC-CS-94`/`95` |
| **X42** | `E4` had no outcome for "no actor-authorization authority applies", the current CLASS 0 case | rev 5 | §3.3 D7 four outcomes; `I18`; `AC-CS-66`/`67`/`80` |
| **X43** | `H2` invented a "local-enrollment authority" and used `AUTHZ_NOT_EVALUATED` for an action absent from the closed registry | rev 5 | §5.5 `H2` (verified: `enroll_device` ∉ `JOB_REGISTRY`) |
| **X44** | `CX1` blocked **every** action, inventing a subject prerequisite for targetless ones | rev 5 | §5.1.2; `H9a`/`H9a-t`; `S-CX1`; `AC-CS-63` |
| **X45** | `CX2` comparability was partial — no `FALSE`/`UNESTABLISHED` split, no precedence for mixed cases | rev 5 | §5.1.1 six precedence rows; `AC-CS-91` |
| **X46** | "all contradictions are danger" stood beside muted `CX2`/`CX3` | rev 5 | §5.1 `RI-1`/`RI-2` renamed bounded inconsistencies; §5.5.2; `AC-CS-47` — no parent amendment needed |
| **X47** | H-cases used `any action` / `that action` / `as they hold` and were not executable fixtures | rev 5 | §5.5 real registry ids; §5.5.1 multi-action scope tests |
| **X48** | the cache section mixed the persistable projection with the composed render-time result | rev 5 | §8.2.1 two-object boundary; `AC-CS-97` |
| **X49** | stale `R1`/`R2`/`R5` regime language in §8.2, four-facet and action-qualifier wording, and `I20` shell scope | rev 5 | §8.2, §4.1.3 `I20`, §6.8, `AC-CS-12`/`39`/`40`/`57` |

### 11.2 Product Owner decisions — **CLOSED / APPROVED** 2026-09-06

All six were approved by the Product Owner on 2026-09-06 and are **closed**.

| id | Decision | Amendments authorized | Outcome |
| --- | --- | --- | --- |
| **PO-M3-1** | **Stable visible tabs.** The navigation contract contradicts itself: §8/§8.1 permit omitting a structurally inapplicable tab for an entity type; §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` forbid it. Which governs? | **`FA-5`** | **APPROVED.** Stable visible tabs preserved under D-NAV13 / `AC-WS-7` / `AC-WS-8`; `FA-5` applied, withdrawing the parent's tab-omission language |
| **PO-M3-2** | **Semantic parent corrections.** Approve the corrections that align the parent's presentation tables with verified producer semantics — freshness, provenance, identity translation, unsupported-vs-unknown, retained evidence, and unconditional action cells | **`FA-1`, `FA-2`, `FA-3`, `FA-4`, `FA-6`, `FA-7`, `FA-8`** | **APPROVED.** The verified semantic corrections `FA-1`, `FA-2`, `FA-3`, `FA-4`, `FA-6`, `FA-7`, `FA-8` are applied to the parent |
| **PO-M3-3** | **Composition model.** Retain the tagged union `RESOLVED{primary_status, capability_qualifiers, evidence_presentation, action_affordance} \| OMITTED{reason, diagnostic}`, with action outcomes keyed by `action_id` and presentation-time affordance separated from server execution authority? | **`FA-9`** | **APPROVED.** The tagged-union composition is adopted, with per-action keying and the presentation-time / server-authority boundary preserved; `FA-9` applied |
| **PO-M3-4** | **Directional `D4`.** Retain the directional `D4` value set, with `RECONCILIATION_UNKNOWN` covering ambiguous or unresolved identity and **no new identity authority**? | — | **APPROVED.** Directional `D4` retained; ambiguous identity never creates a guessed join or a new identity authority — it resolves to `RECONCILIATION_UNKNOWN` or `CX1` |
| **PO-M3-5** | **`--member-specific` token.** Approve decoupling the gold member emphasis from `--warning`, preserving the emphasis and its non-fault semantics? | — | **APPROVED** as the later UI contract direction: `--member-specific` is distinct from `--warning`. **No CSS or UI is implemented in this movement** |
| **PO-M3-6** | **Evidence-gated `NOT_SCHEDULED` qualifier**, emitted only where schedule absence is authoritatively established? | — | **APPROVED.** `NOT_SCHEDULED` is retained as an evidence-gated qualifier, emitted **only** from a positively established `NO_APPLICABLE_SCHEDULE` result |

Every `FA-1`…`FA-9` row is owned **and applied**: `FA-5` by `PO-M3-1`;
`FA-1`–`FA-4`, `FA-6`, `FA-7`, `FA-8` by `PO-M3-2`; `FA-9` by `PO-M3-3`.

**Reported for awareness, no decision requested.** C3 — compliance `UNKNOWN`
counting in the alignment denominator — is inside the compliance contract's
domain, not `M3`'s, and is unchanged here.

### 11.3 Post-freeze obligations

Nothing in §11 remains open. Two obligations carry forward, neither an approval
gate:

1. **`UCQ-1`** (§5.5.4) — `M10` maps concrete `UNSUPPORTED` reason codes to
   `REVALIDATABLE(<input>)` or `TERMINAL`. `M3`'s fail-closed fallback is
   complete without it: an absent, malformed or unknown class yields
   `UNDETERMINED`, never `AVAILABLE_FOR_SUBMISSION`.
2. **`PO-M3-5`'s token direction** is a contract for a later UI movement; no
   CSS or UI is implemented here.

The §10 council dissents are preserved as **historical design dissent**. They
were considered at approval and are not open gates.

---

## 12. Non-goals of this movement

`M3` is a **contract**, not an implementation. It changed no runtime, UI, CSS,
JavaScript, template, adapter, registry, storage, enrollment, authorization,
job or report-generation code, and no payload schema. It created no capability
engine. It altered no existing job lifecycle, action-state, taxonomy,
compliance, discovery-lifecycle or registry vocabulary. It did not reopen the
six-root navigation baseline, the workspace architecture, or `PO-NAV-1`…
`PO-NAV-8`; the only frozen acceptance criterion it amended is `AC-DIF-7`, via
the approved `FA-4`. It begins no later movement and contacts no device.

**This freeze is design authority, not implementation authority for any
movement.** `M4`…`M14` each still require their own separate go-ahead, and none
is begun here.
