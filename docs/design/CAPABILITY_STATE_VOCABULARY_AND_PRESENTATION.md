# Capability-state vocabulary and presentation contract (`M3`)

## Status

**Revision 4 (final pre-decision consistency correction).** Revision 3
(`4b4339f`) was reviewed against the pushed file. This revision repairs the
document structure (a duplicated concatenated `### 4.2` heading that survived
revision 3's audit, out-of-order §2.6/§2.7, an orphan §6.4 table fragment),
separates **surface resolution** from **capability resolution** with an explicit
result algebra (§4.1, §5.0) so that an unresolvable `D1` omits without asserting
`NOT_SHIPPED`, makes action outcomes **action-keyed** rather than unscoped
qualifiers (§4.1.1), corrects gate ownership so that only actor authorization is
`D7` and the action taxonomy is evaluated once (§3.3 D7, §3.4), separates
expected-source trust from identity translation (§3.3 D6d/D6e), defines when a
support conclusion and retained evidence are **comparable** at all (§5.1.1), and
makes the resolution cases atomic (§5.5). Thirteen further defects are listed at
§11.1 (`X27`–`X39`). Acceptance criteria now `AC-CS-1`…`93`; no id was
renumbered or deleted. Still **DRAFT**; nothing here is frozen or approved.

**DRAFT — DO NOT FREEZE. NOT PRODUCT OWNER APPROVED. AUTHORIZES NO
IMPLEMENTATION.**

Prepared on branch `claude/capability-state-vocabulary-cube5f` from verified
`origin/main` head `d363b179fe8f552544402e070f5908ec10df2115` (PR #90,
`DEV.TEST.1`).

**Revision 3 (bounded consistency correction).** Revision 2 (`4b4339f`) was
independently reviewed. This revision makes `D1` visibility absolute and
independent of contradiction diagnostics (§5.0), completes the resolver input
and input-condition contract (§4.1.1, §4.1.2), separates mandatory server-owned
action gates from action-specific prerequisites (§5.4), distinguishes
unevaluated authorization from confirmed denial (§5.4.3), rebuilds `H1`–`H11`
with baselines and named actions and corrects `H9`'s inverted eligibility
(§5.5), strengthens cache validity to all consumed generations and producer
versions with missed-invalidation assumed (§8.2.1), removes the unsupported
"never observed" claim, and expands the frozen-parent conflict inventory to
`FA-1`…`FA-9` (§2.7). Twelve further defects are listed at §11.1 (`X15`–`X26`).
Acceptance criteria now `AC-CS-1`…`85`; no id was renumbered or deleted. Still
**DRAFT**; nothing here is frozen or approved.

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
  half: companion §9.2 amendments `A5`/`A6`, companion §10, companion
  §12/§12.1, companion §13.2.

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

### 2.7 Demonstrated conflicts with FROZEN parent wording — recorded, NOT applied

`AGENTS.md` forbids silently amending a frozen contract. **No frozen file is
edited by this movement.** Each row records the exact clause, its current frozen
rule, the demonstrated conflict, the minimal correction, the **owning PO
decision** (§11.2), and the consequence if approval is withheld.

Producer semantics were verified before any label, severity or presentation is
proposed (V9a, V11, V12a in §2.1). Freshness claims are made only where actual
freshness evidence exists. Source-trust wording never suppresses an
independently valid collection timestamp and never implies that provenance
uncertainty makes a timestamp stale.

| # | Exact parent clause | Current frozen wording / rule | Demonstrated conflict | Minimal proposed correction | Owner | Consequence if withheld |
| --- | --- | --- | --- | --- | --- | --- |
| **FA-1** | nav contract §8, l.627 (**Stale** row) | *"`last_known_good`; `STALE`; `PROVENANCE_UNVERIFIED` … data shown **with age + provenance** … muted badge + timestamp"* | `PROVENANCE_UNVERIFIED` carries no age and **no timestamp** (V12a). The row requires both | strike `PROVENANCE_UNVERIFIED` from the Stale row; give it its own row asserting no age: *"**Source trust insufficient** \| `PROVENANCE_UNVERIFIED` \| shown \| selectable, states that no override/drift claim can be made from this source \| muted, **no timestamp claim**"* | `PO-M3-2` | the parent requires a timestamp that does not exist; an implementer must fabricate one or ignore the clause. `AC-CS-54`/`55` blocked |
| **FA-2** | nav contract §5.4, l.445 | *"Stale or incomparable evidence \| `PROVENANCE_UNVERIFIED`, `last_known_good` \| muted / provenance \| \"Stale evidence\" + timestamp"* | "stale" and "incomparable" are two different facts sharing one row, one label and one timestamp requirement | split: `last_known_good` keeps "Stale evidence" + timestamp; `PROVENANCE_UNVERIFIED` takes a label asserting no age | `PO-M3-2` | two distinct facts keep one label; operators cannot tell old evidence from untrusted comparison. `AC-CS-54`/`55` blocked |
| **FA-3** | nav contract §8, l.629 (**Unsupported** row) | *"`UNSUPPORTED(reason)` … ; `UNKNOWN_SHELL`"* | `UNKNOWN_SHELL` is the *unknown* branch of `plan_collection` (V9a), sibling to `INSUFFICIENT_EVIDENCE`. Treating it as a support conclusion inverts the UNKNOWN law | strike `UNKNOWN_SHELL` from the Unsupported row; it belongs in the existing §8 **Unknown / insufficient** row (l.636) | `PO-M3-2` | an unobserved device is reported **unsupported** — a false product claim. `AC-CS-19`/`56` blocked |
| **FA-4** | `AC-DIF-7`, l.995 | *"Stale or incomparable evidence uses muted/provenance semantics **with its timestamp**"* | binds a timestamp requirement to classifications that have none (`D6d`, `D6e`) | require the timestamp **only** where a freshness anchor exists (`collected_at` / `last_successful_collection`); provenance/comparability semantics without a timestamp otherwise | `PO-M3-2` | a frozen criterion cannot be satisfied without fabricating data; any test written to it must fail or lie |
| **FA-5** | nav contract §8 *Not applicable* row (l.630) and §8.1 *"Omit a tab for an entity type"* | tab **may** be omitted for an entity type | contradicts §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8`, which the parent itself says closed this ambiguity, and contradicts D-NAV11 tab stability | replace both with the §6.5/D-NAV13 rule (tab stays visible and selectable, renders `NOT_APPLICABLE`, names supporting entity types, no enabled action); add a cross-pointer so the tables cannot drift again | **`PO-M3-1`** | the parent contradicts itself; implementers may cite either clause, producing flickering tabs on one reading. `AC-CS-25`/`26` unresolvable |
| **FA-6** | nav contract §8 **Failed** row vs the §8.1 empty-state grouping | Failed row says *"error state + last good evidence retained"*; §8.1 groups **failed** under *"Keep selectable + explanatory empty state"* | one state is required to retain and display last-good evidence **and** to present an explanatory empty state — mutually exclusive presentations | make §8.1's grouping conditional: an explanatory empty state applies **only when no displayable evidence is retained** | `PO-M3-2` | two incompatible presentations for one state; retained evidence may be hidden behind an empty state. `AC-CS-29`/`58` blocked |
| **FA-7** | nav contract §8 **Action** column, unconditional cells — e.g. Stale *"enabled, with age stated"*, Skipped *"enabled"* | states an action is **enabled**, unconditionally, as a property of the capability state | eligibility is never a property of the capability state alone: it is the conjunction of `E1`–`E7` (§3.4). An unconditional "enabled" cell would grant an action past its taxonomy, authorization and admission gates | reword the Action column as **"not blocked by this state"**, with a note that final eligibility remains the conjunction of the action's own gates | `PO-M3-2` | the table reads as granting actions; implemented literally it bypasses `console_refusal()` and the authorization authority — a safety defect. `AC-CS-30`/`32`/`67` blocked |
| **FA-8** | nav contract §5.4, contradictory/unsafe row: *"`IDENTITY_TRANSLATION_REQUIRED`, `RELATIONSHIP_INCONSISTENT` … danger/error … 'Contradictory evidence'"* | groups an identity-**translation** requirement with unsafe contradictory state under one **danger/error** treatment and one "Contradictory evidence" label | `IDENTITY_TRANSLATION_REQUIRED` is a `semantic_exclusion` (V12a producer set) — a comparability limitation meaning the two sides use different identifier representations. It is **not** a contradiction: `CX1` (incompatible type/vendor resolution) is a distinct, narrower condition. Rendering it as danger overstates severity and conflicts with `AC-DIF-8`'s "red is reserved for actual fault" | separate the two: `IDENTITY_TRANSLATION_REQUIRED` takes comparability semantics (muted, "identity translation required", its own reason code preserved); genuine contradictory/unsafe state (`CX1`, `RELATIONSHIP_INCONSISTENT`) keeps danger/error and the "Contradictory evidence" label | `PO-M3-2` | a comparability limitation is presented as a fault, and the parent's own `AC-DIF-8` is violated by its own row. `AC-CS-47` and `H11c` cannot both be satisfied |
| **FA-9** | nav contract §8 table shape (one **UX semantic** row → one nav/view/action/presentation tuple) | each row collapses status, view content, action enablement and presentation into a single state's row | this draft demonstrates these are **four independent outputs** under a separate surface disposition (§4.1): `primary_status`, `evidence_presentation` and `action_eligibility` vary independently for the same state — `H4a`/`H4b`/`H5a`/`H5c`/`H9b`/`H11a`/`H11c` each show a case the one-row-per-state shape cannot express | keep §8 as the **UX-semantic index** it is, and add one sentence stating that a row's View and Action columns are *defaults for that state*, resolved finally by the capability-state resolution contract | **`PO-M3-3`** | the parent's shape implies one state determines all four outputs; every corrected case in §5.5 then reads as a deviation rather than a resolution. The four-output model stays in permanent tension with the parent |

**If the Product Owner withholds approval on a row**, three things follow and
nothing else:

1. the **authority conflict remains recorded** here, unresolved;
2. the **affected implementation remains blocked** — the acceptance criteria in
   that row's consequence column cannot be implemented without contradicting
   the frozen parent or fabricating data;
3. **no implementer is instructed to reproduce semantics this document has
   demonstrated false**, and **this draft is not silently rewritten** to
   pretend the conflict disappeared.

`FA-1`…`FA-9` are all **prepared, unapplied, and subject to Product Owner
approval**. Until then this document follows verified source semantics (V9a,
V11, V12a) and says so at each point of divergence — declared, never silent.

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

**`D7` describes authorization and nothing else.** The previous revision listed
five "authorization regimes", four of which are not actor authorization at all —
a bearer-token session gate, a closed-registry membership check and a
taxonomy/surface admissibility rule are **server-owned enforcement gates**, and
relabelling them as authorization both overstated what the product decides
about actors and caused the same taxonomy rule to be evaluated twice (§3.4,
§5.4). Those gates keep their enforcement power; they simply are not `D7`.

| Field | Contract |
| --- | --- |
| **Owner** | the **authorization authority applicable to the action in question**. Today exactly one exists: `utils/operate/authorization.py`, which governs **CLASS 2 actions only** and returns an unconditional `deny("authorization_not_configured")`. `DEPLOY.1A`'s OIDC/RBAC would add an actor-authorization authority where none exists today |
| **Values** | per action: `PERMITTED`, `DENIED(authority, reason_code)`, `AUTHZ_NOT_EVALUATED(authority)` |
| **Meaning** | `AUTHZ_NOT_EVALUATED` means an applicable authorization authority has **not been consulted** — it is neither a grant nor a refusal, and it is **not** the same as "no authorization model exists" |
| **Persisted?** | derived per decision; the decision is audited |
| **Scope** | **action-scoped** (actor × action × entity), never capability-scoped |
| **May affect** | the eligibility and refusal copy of **that one action**, recorded under that action's id (§4.1) |
| **Must never control** | the surface disposition or any view/tab visibility; and it must never be inferred from `D1`…`D6`, nor may any of them be inferred from it. A shared or cached capability projection **never grants permission** |
| **Evidence today** | VERIFIED. `OP.2` `DenyAllAuthorizer` = CLASS 2 only, unconditional deny. `navigationAuthorizationContext()` returns `model: "none"` — there is no actor-authorization model, which is **neither an automatic grant nor a blanket denial** |

**What the absence of OIDC/RBAC does and does not mean.** For a **CLASS 0**
action it means no *actor* authorization authority is applicable today, so `D7`
contributes nothing and the action's eligibility rests on the other gates in
§3.4 — which are shipped and enforcing. CLASS 0 console reads and job
submissions are permitted today, and this document does not change that. For a
**CLASS 2** action the applicable authority exists and denies unconditionally.
Nothing here treats the missing future model as a grant, and nothing treats it
as a blanket denial of CLASS 0.

### 3.4 Server-owned enforcement gates that are **not** authorization

These participate in action eligibility with full enforcement power. They are
listed here so that none of them is relabelled as actor authorization, and so
that each is evaluated **exactly once** (§5.4). Seven distinct concepts:

| id | Concept | Authoritative source today | What it decides | Not |
| --- | --- | --- | --- | --- |
| **E1** | **request/session authenticity and origin enforcement** | `console/app.py::_require_api_auth` — bearer launch token, `Origin` / `Sec-Fetch-Site` checks, loopback binding | whether this *request* may be served at all | not an actor-authorization decision; it authenticates the session, it does not evaluate who may do what |
| **E2** | **action identity and closed-registry membership** | `console/registry.py::JOB_REGISTRY` (module-level, not extensible at runtime); the surface's action declarations | whether the requested action is a known member of a closed vocabulary | not authorization, not taxonomy |
| **E3** | **taxonomy / surface admissibility** | `utils/action_taxonomy.py::console_refusal()` over the action's `ActionClass` | whether an action of this class may be submitted on this surface at all | not actor authorization, and **not** evaluated a second time inside `D7` |
| **E4** | **the action's applicable authorization authority** | `D7` (§3.3) | whether this actor may perform this action | — |
| **E5** | **subject/target integrity required by that action** | the action's own contract; canonical-id resolution | whether the action's subject is resolvable and undisputed **where its contract requires one** | not a prerequisite that may be invented for a targetless action |
| **E6** | **action-specific semantic prerequisites** | the action's own declared prerequisite facts | whether the facts this action needs are present and current enough | not a general evidence gate over unrelated dimensions |
| **E7** | **admission / execution-time live checks** | the admission coordinator; the registry, read at admission **and again immediately before execution** (`AC-ST-4`) | whether the action may proceed **now**, against live authority | **not** satisfiable by a cached projection, and not replaced by anything `M3` defines |

**`E3` and `E4` are disjoint and are each evaluated once.** The previous
revision evaluated the action taxonomy in its `G2` gate and then again as an
"R2 regime" inside `G3`. `E3` is the single evaluation of taxonomy
admissibility; `D7`/`E4` never re-reads it.

**`E7` is authoritative, and `M3` defines nothing that weakens it.** Everything
this document produces is advisory presentation. A live registry check at
admission and immediately before execution is unchanged, and a target that has
become disabled or unresolvable causes refusal or abort **before contact**.

---

## 4. Canonical vocabulary

### 4.1 Result algebra — surface resolution, then capability resolution

The seven dimensions are facts. What an operator reads is a **resolution**, and
resolution happens in two phases that must not be collapsed.

**Phase 1 — surface resolution.** From `D1` alone, §5.0 produces a
`surface_disposition`. This phase always produces a result.

**Phase 2 — capability resolution.** Runs **only** when phase 1 returns
`RESOLVE`. It produces the four capability outputs.

```
surface_disposition  ∈ { RESOLVE, OMIT_NOT_SHIPPED, OMIT_UNRESOLVABLE }

if surface_disposition == RESOLVE:
    primary_status        : exactly one CapabilityState
    capability_qualifiers : zero or more CapabilityQualifier
    evidence_presentation : POPULATED{...} | EMPTY{reason}
    action_eligibility[]  : one entry per declared action, keyed by action_id
else:
    the four capability outputs are ABSENT — not empty, not defaulted, not null-
    valued placeholders. Nothing renders, and a diagnostic is returned instead.
```

| `surface_disposition` | Produced when | Capability outputs | Diagnostic returned | Asserts |
| --- | --- | --- | --- | --- |
| `RESOLVE` | `D1 = SURFACE_PRESENT` | all four | none | that this build ships the surface |
| `OMIT_NOT_SHIPPED` | `D1 = SURFACE_ABSENT` | **absent** | `not_shipped` | the positive fact that this build does not ship the surface — carried as `primary_status = NOT_SHIPPED` **in the disposition record only**, never as a rendered capability state |
| `OMIT_UNRESOLVABLE` | `D1 = SURFACE_ELIGIBILITY_UNRESOLVABLE` | **absent** | `d1_input_unresolvable` | **nothing about the build.** It records only that eligibility could not be determined and that the surface was therefore withheld |

**`OMIT_UNRESOLVABLE` never asserts `NOT_SHIPPED`.** Both dispositions omit;
only one makes a claim. Conflating them would report "this build does not ship
this surface" on the strength of a missing input — fabricated certainty
(`AGENTS.md` UNKNOWN law).

**Cardinality follows from this.** `NOT_SHIPPED` remains a member of
`CapabilityState` because the disposition record carries it as the reason for
`OMIT_NOT_SHIPPED`, but it is the one member that is **never rendered** and
never co-occurs with qualifiers, evidence presentation or action eligibility.
Every other `CapabilityState` member occurs only under `RESOLVE`.

#### 4.1.0 The four capability outputs

| Output | Answers | Cardinality |
| --- | --- | --- |
| `primary_status` | what is the headline fact about this capability right now | exactly one `CapabilityState` |
| `capability_qualifiers` | which capability/evidence refinements also hold | zero or more `CapabilityQualifier` |
| `evidence_presentation` | what should the view show | `POPULATED{as_of, completeness, source_trust, identity_translation}` \| `EMPTY{reason}` |
| `action_eligibility[]` | for each declared action, may it run, and if not why | one entry per declared action, **keyed by `action_id`** |

**`evidence_presentation` is decided by whether displayable evidence exists —
never by the primary label.** `COLLECTION_FAILED`, `POLICY_DISABLED`,
`DEVICE_DISABLED` and an incomplete `D4` reconciliation all retain and display
whatever evidence was previously collected, subject unchanged to the existing
identity, privacy and raw-evidence contracts. This matches the frozen parent,
which already requires "error state + **last good evidence retained**" for the
Failed row (navigation contract §8).

#### 4.1.1 Action outcomes are action-keyed, never capability qualifiers

The previous revision carried `ACTION_DENIED`, `ACTION_REFUSED` and
`AUTHZ_NOT_EVALUATED` in the same flat qualifier list as `STALE` and
`MEMBER_SPECIFIC`. That is a category error: a qualifier describes the
**capability/evidence presentation**, while those three describe **one
action's outcome**. In a flat list they are unscoped — with two declared
actions, a reader cannot tell which one was refused.

```
action_eligibility[] : [
  {
    action_id        : <closed-vocabulary id>,          # always present
    eligible         : true | false,
    blocking_reasons : [ { gate: E1..E7, code: <reason>, detail: <named gate> }, ... ]
  },
  ...
]
```

Rules:

1. **Every entry identifies its `action_id`.** There is no unkeyed action
   result.
2. `ACTION_DENIED(authority, reason)`, `ACTION_REFUSED(class)` and
   `AUTHZ_NOT_EVALUATED(authority)` appear **only** inside a
   `blocking_reasons[]` entry, never in `capability_qualifiers`.
3. **Every blocking reason is preserved** — one action's refusal never removes
   or masks another's, and no reason is dropped because a higher-ranked
   dimension supplied the headline label.
4. `capability_qualifiers` is limited to facts about the capability and its
   evidence: `STALE`, `PARTIAL`, `NOT_SCHEDULED`, `SCHEDULE_UNKNOWN`,
   `SOURCE_TRUST_LIMITED`, `IDENTITY_TRANSLATION_REQUIRED`, `MEMBER_SPECIFIC`.

#### 4.1.2 The resolver's complete input contract

Each row names the authoritative fact and the output it feeds.

| # | Required input | Authoritative source today | Feeds |
| --- | --- | --- | --- |
| I1 | **surface eligibility** `D1` (three-valued) | navigation model + shipped-contract set | §5.0 `surface_disposition` — **and nothing else may** |
| I2 | **entity applicability** `D2` | logical-entity type model | ladder rank 2 |
| I3 | **vendor-support knowledge** `D3` | capability projection (`M10`; **no producer today**) | ranks 4, 8, 10; CX2 |
| I4 | **registry presence and lifecycle** `D4` | `utils/device_registry.py` joined on canonical id | ranks 3, 5, 8, 10; CX3 |
| I5 | **capability-policy knowledge** `D5` | schedule/capability-policy record (`M12`; **no producer today**) | rank 6; `NOT_SCHEDULED` / `SCHEDULE_UNKNOWN` |
| I6 | **evidence existence and sufficiency** `D6b` + `INSUFFICIENT_EVIDENCE` | `utils/snapshot.py::_status` `data_state`; subsystem sufficiency verdicts | ranks 8, 9, 10; `PARTIAL` |
| I7 | **evidence timestamps and freshness, where actually known** `D6a` | `utils/snapshot.py::_status` — `fresh`, `collected_at`, `last_successful_collection`, `stale_reason` | `STALE(as_of)` — **and only this input may produce it** |
| I8 | **expected-source trust** `D6d`, with its source reason code | `configuration/pan_setting_alignment.py` | `SOURCE_TRUST_LIMITED` — **never** `STALE`, never `IDENTITY_TRANSLATION_REQUIRED` |
| I9 | **identity-translation requirement** `D6e`, with its source reason code | the same producer set (V12) | `IDENTITY_TRANSLATION_REQUIRED` qualifier — **never** `SOURCE_TRUST_LIMITED`, and never `CX1` on its own |
| I10 | **identity conflict** — two incompatible type/vendor resolutions for one canonical id | canonical-id resolution | `CX1` only |
| I11 | **configuration presence, or positively established absence** | subsystem verdicts that assert absence from present evidence (e.g. `restore_readiness` `UNPROTECTED`, config `not_configured`) | rank 9 — **positive absence only**; a missing verdict is rank 8 |
| I12 | **retained displayable evidence** for this (entity, capability) | last-known-good state; CAS configuration evidence | `evidence_presentation` — **and the primary label may not** |
| I13 | **latest collection outcome** `D6c` | run telemetry; `Outcome` (V18) | rank 7 |
| I14 | **evidence comparability metadata** — evidence generation/time, producer version, vendor support-rule version, platform/entity kind at collection time | run manifest; producer/rule versioning | **CX2 comparability** (§5.1.1); cache validity (§8.2.1) |
| I15 | **member-comparison context** — member set and per-member comparison | merged evidence model | `MEMBER_SPECIFIC` |
| I16 | **declared action set**, each with `action_id`, closed-registry membership and `utils.action_taxonomy` class | `console/registry.py`; the surface's action declarations | `E2`, `E3`, `action_eligibility[]` keying |
| I17 | **each action's own declared prerequisite facts and subject requirement** | that action's contract | `E5`, `E6` |
| I18 | **per-action authorization evaluation status**, with the applicable authority | `D7` / `E4` | `blocking_reasons[]` |
| I19 | **request/session authenticity outcome** | `console/app.py::_require_api_auth` | `E1` |
| I20 | **shell** (exported report vs console) | render context | `evidence_presentation`; which actions are declared at all (§6.8) |

#### 4.1.3 Input-condition handling, resolved before the affected output

| Input condition | Definition | Handling |
| --- | --- | --- |
| **Absent** | a required input was not supplied | if `I1` — `D1 = SURFACE_ELIGIBILITY_UNRESOLVABLE` → `OMIT_UNRESOLVABLE`. Otherwise ladder rank 11 `UNKNOWN(unclassified_input)`; any action declaring the absent fact is ineligible |
| **Malformed** | supplied but not a valid value of its domain, or internally inconsistent | same as absent, with reason `malformed_input:<input id>`. Never coerced to a default, never rounded to the nearest valid value |
| **Contradictory** | two authoritative sources disagree about the same fact for the same subject under a closed class's conditions — exactly CX1–CX3 | §5.1, within the class's declared scope |
| **Incomparable** | two facts exist but §5.1.1's comparability conditions are not met | **not** a contradiction. Resolved per §5.1.1 without manufacturing either a contradiction or a support conclusion |
| **Stale** | `D6a` `fresh == false` **with** a real anchor | valid input. `STALE(as_of)`; blocks only actions declaring currency of that fact |
| **Unevaluated** | `I18` returns `AUTHZ_NOT_EVALUATED` for an applicable authority | **non-executable** eligibility for that action, represented **distinctly from a confirmed denial** |
| **Validly unknown** | a well-formed value meaning "not determinable" — `SUPPORT_UNKNOWN`, `POLICY_UNKNOWN`, `APPLICABILITY_UNKNOWN`, `RECONCILIATION_UNKNOWN` | **not malformed.** `POLICY_UNKNOWN` in particular must not erase otherwise usable evidence, must not reach rank 11, and yields `SCHEDULE_UNKNOWN` |

**Four inference bans**, each a corrected defect. Freshness is never inferred
from `I8` or `I9`. Capability support is never inferred from an unknown shell,
missing evidence, or a failed collection. `I9` never becomes `I10`. And no
input class is inferred from `I18`, nor `I18` from any of them.

### 4.2 `CapabilityState` — the primary vocabulary

Ten values. Nine are rendered capability states produced under `RESOLVE`;
`NOT_SHIPPED` is the disposition reason for `OMIT_NOT_SHIPPED` and is never
rendered (§4.1).

| Value | Operator meaning | Distinct consequence | Source |
| --- | --- | --- | --- |
| `NOT_SHIPPED` | this build does not ship the surface | **disposition only** — carried in the `OMIT_NOT_SHIPPED` record; never rendered, never co-occurs with the other three outputs | `D1 = SURFACE_ABSENT` |
| `NOT_APPLICABLE` | this function does not apply to this kind of entity | visible, selectable, names the entity types that do support it, no enabled action | D2 |
| `DEVICE_DISABLED` | the operator disabled this device | visible, evidence retained; re-enable is the meaningful action; jobs refuse at admission | D4 |
| `UNSUPPORTED` | this vendor/platform cannot do this, and we have evidence of that | visible, evidence retained; states the vendor reason; **collecting again will not help** | D3 |
| `NOT_ENROLLED` | observed in evidence, absent from the Device Registry | visible, evidence retained; offers enrollment where `M9`'s gate permits | D4 (`EVIDENCE_ONLY`) |
| `POLICY_DISABLED` | an applicable schedule for this capability is intentionally off for this device | visible, **evidence retained and displayed**; names the policy; states that **automatic** refresh is off — never that refresh is impossible | D5 |
| `COLLECTION_FAILED` | the **latest attempt** failed | visible; the failure and its time shown **alongside** retained evidence, which keeps its own age | D6c |
| `UNKNOWN` | we cannot conclude, and we name which fact is missing | visible; names the missing fact; only actions declaring that fact become ineligible | D3 `SUPPORT_UNKNOWN`, D2 `APPLICABILITY_UNKNOWN`, D4 `REGISTRY_ONLY`/`RECONCILIATION_UNKNOWN`, D6b `no_data`, `INSUFFICIENT_EVIDENCE`, CX1–CX3, incomparability (§5.1.1), or unclassified input |
| `NOT_CONFIGURED` | supported and applicable, and evidence **positively shows** it is not set up | visible; states what configuring it requires; actionable empty state | D6 (positive absence) |
| `AVAILABLE` | usable now | normal presentation, populated view | the rank-10 positive conjunction |

**Lexical disjointness (mandatory).** No `CapabilityState` value equals any
member of X1 (`queued`, `running`, `succeeded`, `failed`, `blocked`, `skipped`)
or X2 (`CREATED`…`OUTCOME_UNKNOWN`). `COLLECTION_FAILED` is deliberately not
`FAILED`; no value is named `BLOCKED` (E2 in §2.2).

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
**Stage 0 is absolute**: it decides the surface disposition from `D1` alone and
no later stage can override or reach past it.

```
stage 0  surface resolution   -> RESOLVE | OMIT_NOT_SHIPPED | OMIT_UNRESOLVABLE
         (stages 1-5 run only under RESOLVE)
stage 1  contradiction gate    -> may force primary UNKNOWN, with a declared scope
stage 2  primary ladder        -> exactly one CapabilityState
stage 3  capability qualifiers -> independent of stage 2
stage 4  evidence presentation -> from displayable evidence only
stage 5  action eligibility    -> per action_id; E1-E7, each evaluated once
```

### 5.0 Stage 0 — surface resolution, and its result algebra

> **Rule.** `D1` is evaluated **before every other input**. Only
> `SURFACE_PRESENT` permits capability resolution. Neither omission
> disposition may be overturned by any qualifier, contradiction, evidence
> fact, policy fact or authorization outcome. Contradiction detection **may
> still run** under an omission — it may emit diagnostics, audit evidence,
> telemetry or an authority-conflict result — but a diagnostic is **never** a
> render decision.

| Case | `D1` | Contradiction | `surface_disposition` | Rendered? | Capability outputs | Diagnostic | Positive claim asserted |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **V-A** — surface positively absent, otherwise usable evidence | `SURFACE_ABSENT` | none | `OMIT_NOT_SHIPPED` (reason `NOT_SHIPPED`) | **no** | **absent** — all four | none required | "this build does not ship this surface" — supported by the positive `SURFACE_ABSENT` determination |
| **V-B** — surface positively absent, contradictory inputs | `SURFACE_ABSENT` | CX1–CX3 holds | `OMIT_NOT_SHIPPED` | **no** | **absent** | **emitted** — authority-conflict diagnostic naming both disagreeing inputs. It changes no rendering | same as `V-A`; the contradiction adds no product claim |
| **V-C** — surface positively present, contradictory inputs | `SURFACE_PRESENT` | CX1–CX3 holds | `RESOLVE` | yes | all four, per stage 1's declared scope | emitted **and** rendered | the contradiction, naming both inputs |
| **V-D** — `D1` missing, malformed or otherwise unresolvable | `SURFACE_ELIGIBILITY_UNRESOLVABLE` | irrelevant | `OMIT_UNRESOLVABLE` | **no** — **fail closed** | **absent** | **emitted** — `d1_input_unresolvable` | **none.** It does **not** assert `NOT_SHIPPED`, and says nothing about what the build ships |

**Why `V-D` omits rather than renders.** `D1` asserts that this build actually
ships the surface (navigation contract §7.1). An unresolvable `D1` cannot
support that assertion, and rendering an entry that may point at nothing is the
failure mode D-NAV6a's DOM integrity check exists to prevent. This is the one
place where fail-closed means "show less", because every other dimension's
fail-closed direction ("say `UNKNOWN`, keep the surface") presupposes the
surface exists.

**Why `V-D` is not `NOT_SHIPPED`.** Omitting is a *disposition*; `NOT_SHIPPED`
is a *claim*. `V-D` takes the safe disposition without making the claim. This
is the distinction the previous revision collapsed.

**Absent is not empty.** Under either omission the four capability outputs are
**not produced** — not produced as empty collections, not as null placeholders,
not as a defaulted `primary_status`. A consumer that receives an omission
disposition has no capability result to read, and must not synthesise one.

### 5.1 Stage 1 — contradiction, defined precisely and scoped

Reached **only** under `RESOLVE`. Contradiction is a **finite, closed set**;
anything outside it is not a contradiction and does not reach this gate.

**Simultaneous independent facts are not contradictions.** `UNSUPPORTED`
beside retained historical evidence is not a contradiction. `POLICY_DISABLED`
beside fresh evidence is not a contradiction. `POLICY_UNKNOWN` beside usable
evidence is not a contradiction. `IDENTITY_TRANSLATION_REQUIRED` is not a
contradiction — it is an unevaluable comparison (§3.3 D6e).

| id | Contradiction | Scope of effect |
| --- | --- | --- |
| **CX1** | the same canonical id resolves to two **incompatible entity types or vendors** (`I10`) — a genuine disagreement about what the subject *is*, not a difference in how it is identified | `primary_status = UNKNOWN(identity_contradiction)`; **evidence withheld** (`AGENTS.md` identity law forbids joining on unproven identity); every action ineligible via `E5`, since none can name a subject whose identity is in dispute |
| **CX2** | `D3 = UNSUPPORTED` while **comparable** positive evidence for the same subject and capability exists — comparability defined in §5.1.1 | `primary_status = UNKNOWN(support_contradiction)`; **evidence retained and displayed**, labelled; actions declaring the support fact as a prerequisite become **ineligible**; all other actions unaffected by this class |
| **CX3** | `D4 = EVIDENCE_ONLY` while a registry row for the same canonical id exists and is not disabled | `primary_status = UNKNOWN(reconciliation_contradiction)`; **evidence retained and displayed**; the **enrollment action** becomes ineligible; all other actions unaffected by this class |

Every contradiction names **both** disagreeing inputs; the more favourable
input is never silently chosen. A contradiction never *grants* eligibility to
any action — it only removes it, within its declared scope, on top of the
mandatory gates.

#### 5.1.1 CX2 comparability — when evidence and a support conclusion may be compared

The previous revision defined CX2 as "`UNSUPPORTED` while a successful
collection exists in the current evidence set", while elsewhere stating that
`UNSUPPORTED` may legitimately coexist with retained historical evidence (H4).
Both cannot hold. The rule is that a contradiction requires the two facts to be
**semantically comparable**, and comparability is established positively.

Positive evidence and a current `UNSUPPORTED` conclusion are **comparable**
only when **all** hold (`I14` supplies the metadata):

| # | Condition |
| --- | --- |
| K1 | **same canonical subject** — identical canonical id, joined without an unproven identifier translation |
| K2 | **same exact capability** — not a sibling or superset capability |
| K3 | **same platform / entity kind** — the platform family and entity kind at evidence-collection time match those the current support conclusion was computed against |
| K4 | **evidence generation is not superseded** — the evidence was produced at or after the last platform or entity-kind change known to the product |
| K5 | **same applicable support-rule version** — the vendor support-rule version that yields today's `UNSUPPORTED` is the version applicable to that evidence generation |
| K6 | **producer version comparable** — the evidence producer version is one the current support rules are defined over |

Outcomes:

| Situation | Result |
| --- | --- |
| **All of K1–K6 hold** — contemporaneous positive evidence under the same applicable rules | **CX2 holds.** Contemporaneous positive evidence must not be silently ignored merely because a support conclusion says otherwise |
| **K3, K4 or K5 fails** — the evidence predates a platform change or a support-rule change | **Not a contradiction.** Historical retained evidence never automatically contradicts a later support conclusion. `primary_status = UNSUPPORTED`; the evidence is retained and displayed, labelled as predating the current support determination |
| **K1, K2 or K6 cannot be established** — the metadata to compare is missing, or the join would need an unproven translation | **Neither a contradiction nor a support conclusion.** `primary_status = UNKNOWN(support_comparability_unestablished)`, naming which condition could not be established; the evidence is retained and displayed; actions declaring the support fact are ineligible. Nothing is manufactured in either direction |

**This is the honest-unknown case, not a tiebreak.** Where comparability cannot
be established the product says so, rather than inventing a contradiction or
promoting stale evidence into a support claim.

### 5.2 Stage 2 — the primary ladder

Reached only under `RESOLVE`. First match wins. `AVAILABLE` is **not** a
fall-through: it requires the positive conjunction at rank 10, and any input
satisfying no rank lands at rank 11.

| Rank | Condition | Primary |
| --- | --- | --- |
| 0 | a contradiction class CX1–CX3 holds (stage 1) | `UNKNOWN(<class>)` |
| 1 | CX2 comparability could not be established (§5.1.1) | `UNKNOWN(support_comparability_unestablished)` |
| 2 | `D2 = NOT_APPLICABLE` | `NOT_APPLICABLE` |
| 3 | `D4 = REGISTRY_DISABLED` | `DEVICE_DISABLED` |
| 4 | `D3 = UNSUPPORTED` (and CX2 does not hold) | `UNSUPPORTED` |
| 5 | `D4 = EVIDENCE_ONLY` | `NOT_ENROLLED` |
| 6 | `D5 = POLICY_DISABLED` | `POLICY_DISABLED` |
| 7 | `D6c = failed` on the latest attempt | `COLLECTION_FAILED` |
| 8 | `D3 = SUPPORT_UNKNOWN`, or `D2 = APPLICABILITY_UNKNOWN`, or `D4 ∈ {REGISTRY_ONLY, RECONCILIATION_UNKNOWN}`, or `D6b = no_data`, or `INSUFFICIENT_EVIDENCE` | `UNKNOWN(<named missing fact>)` |
| 9 | `D6` **positively** evidences absence of configuration (`I11`) | `NOT_CONFIGURED` |
| 10 | **all** of: `D2 = APPLICABLE` ∧ `D3 = SUPPORTED` ∧ `D4 = RECONCILED` ∧ `D6b ∈ {live, last_known_good, partial}` ∧ `D6c = success` ∧ no contradiction ∧ every required input well-formed | `AVAILABLE` |
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
- **Stage 5, action eligibility.** Per `action_id`; §5.4 defines it.

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
- Stage 5, per action:
  - `collect_now` — **scheduling state alone does not block it.** It is
    eligible **only if** `E1`–`E7` all pass for it; `D5` contributes no
    blocking reason.
  - `retry` — same, **and** it must additionally satisfy its own retry
    contract; yesterday's failure is not a grant.
  - a drift/override-claim action — `INELIGIBLE`, blocking reason
    `E6: trusted_expected_source_required`.

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

### 5.4 Stage 5 — action eligibility, per `action_id`

For each declared action the resolver emits one `action_eligibility[]` entry
keyed by `action_id` (§4.1.1). Eligibility is the conjunction of **all seven
gates of §3.4**, each evaluated **exactly once**.

| Gate | Evaluated once as | Fails when | Blocking reason |
| --- | --- | --- | --- |
| `E1` | request/session authenticity and origin | the session gate rejects the request | `session_not_authenticated` |
| `E2` | action identity + closed-registry membership | the `action_id` is unknown or outside the closed vocabulary | `unknown_action_identity` |
| `E3` | taxonomy / surface admissibility | `console_refusal()` refuses this class on this surface | `ACTION_REFUSED(class)`, the refusing class named |
| `E4` | the action's applicable **authorization** authority (`D7`) | it returns `DENIED`, **or** returns `AUTHZ_NOT_EVALUATED` | `ACTION_DENIED(authority, reason)` **or** `AUTHZ_NOT_EVALUATED(authority)` |
| `E5` | subject/target integrity **required by that action's own contract** | a required subject is unresolvable, or is in `CX1` dispute | `subject_integrity_unmet` |
| `E6` | the action's own declared semantic prerequisites | a declared prerequisite fact is missing, or not current enough for that action | the named prerequisite |
| `E7` | admission / execution-time live checks | the live check refuses — including a registry target that has become disabled or unresolvable | the live check's own reason |

**`E3` is never re-evaluated inside `E4`.** Taxonomy admissibility is decided
once, by `E3`. The previous revision evaluated it in a `G2` gate and again as
an "R2 regime" inside `G3`; that double evaluation is removed.

**`E5` imports no prerequisite the action does not have.** A **targetless**
read — today every collection job type is `target_mode="none"` — declares no
subject requirement, so `E5` requires none and must not invent one. A
device-targeted action inherits its contract's rules unchanged, including the
frozen requirement that the **registry is authoritative at job admission and
again immediately before execution** (`AC-ST-4`), with refusal or abort
**before contact**. `M3` neither relaxes that nor substitutes a cached value.

**`E6` is scoped to what the action declares.** Evidence blocks an action only
where the action declares that fact — the frozen parent's own scoping,
navigation contract §8 Unknown row: *"disabled **if the action needs the
missing fact**"*. An action does not become ineligible merely because some
unrelated dimension is `UNKNOWN` or `STALE`.

**Evidence-gathering actions declare no `E6` prerequisite on the evidence they
produce.** First contact, `collect_now` and `retry` are never blocked by the
absence of the evidence they exist to acquire — requiring it would be a
deadlock, not a safety property. They remain subject to **every** other gate:

- **manual collection** — scheduling state alone (`D5`) does not block it; it
  is independent of nothing else, still passing `E1`–`E3` and `E5`–`E7`,
  entering the same admission coordinator and single orchestration path, and
  staying within the vendor interaction-safety budget;
- **retry** — additionally satisfies **its own retry contract**. A previous
  failure is never a grant. Retry is a **new typed job**, never a mutation of a
  historical outcome (`AC-RT-6`); where a contract imposes a ledger window or
  minimum re-execution interval, a refusal there is a correct outcome.

#### 5.4.1 Unevaluated authorization is not a denial

| | `ACTION_DENIED(authority, reason)` | `AUTHZ_NOT_EVALUATED(authority)` |
| --- | --- | --- |
| Meaning | an applicable authority was consulted and **refused** | an applicable authority has **not been consulted** |
| Eligibility | `false` | `false` |
| Rendered as a refusal? | yes — name the authority and reason | **no** — it asserts nothing about permission |
| Operator copy | "refused by \<authority\>: \<reason\>" | "permission for this action has not been determined" |
| Audit | a denial decision | an unevaluated-gate record, never a denial |

Both are non-executable; only one is a statement about permission. Collapsing
them would fabricate a refusal the product never made.

#### 5.4.2 The UI state is advisory

Everything stage 5 produces is **advisory presentation**. Server-side
**admission** (`E7`) and **execution** checks remain authoritative and are
unchanged: an action shown eligible is still checked server-side, and one shown
ineligible is still refused server-side.

**All blocking reasons are preserved**, whatever the primary label, each naming
its actual gate under its own `action_id`.

### 5.4.3 What each output may read

| Output | Permitted inputs | Forbidden inputs |
| --- | --- | --- |
| `surface_disposition` (root / module / tab rendering) | `D1` only | D2–D7, contradictions, qualifiers, actions |
| `primary_status` | stages 1–2 inputs | `D1` (stage 0 owns it), `D7`, any action outcome |
| `capability_qualifiers` | the facet each binds to | any action outcome |
| `evidence_presentation` | existence of displayable evidence under the identity/privacy contracts | the primary label |
| action **visibility** | the action contract's existence + the shell | `E4` — an action must not vanish because you lack access |
| `action_eligibility[action_id]` | `E1`–`E7`, each once, each only where applicable to that action | a dimension the action does not depend on |
| explanatory copy | every input that contributed, named | a reason that is not the actual gate |
| audit / report representation | the resolved state as text | any action affordance in the report (`AC-SH-1`) |

### 5.5 Atomic resolution cases

**Every case below is atomic**: one resolution subject, one named action (or an
explicitly action-free presentation case), complete input assumptions, one
primary result, capability qualifiers only, one evidence presentation, and one
action-keyed eligibility result where an action exists. The previous revision's
table contradicted its own "single named action per row" baseline by resolving
several actions in one cell; those rows are split into lettered subcases here.
`H1`–`H11` identifiers are preserved.

**Shared assumptions unless a case states otherwise:** `D1 = SURFACE_PRESENT`
(stage 0 returned `RESOLVE` — no case below is reachable otherwise); console
shell; the named action is a member of the closed registry (`E2` passes); the
action is CLASS 0 and `E3` passes; `E1` passes; `E7` is evaluated live and is
not pre-satisfied by anything here.

**No case asserts unconditional permission.** "Eligible" always means *all
seven gates passed for that action*, never "permitted regardless".

| # | Subject + assumptions | Named action | `primary_status` | `capability_qualifiers` | `evidence_presentation` | `action_eligibility[action_id]` | Tone | Copy must say |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **H1** | supported, reconciled, fresh evidence; the applicable authority refuses this action | `collect_now` | `AVAILABLE` | — | `POPULATED` | `{collect_now: ineligible, [E4: ACTION_DENIED(authority, reason)]}` | normal view; refusal tone on the control | who refused, under which authority; never "unavailable" |
| **H2** | observed in evidence, absent from the registry | `enroll_device` | `NOT_ENROLLED` | — | `POPULATED` — the evidence is what proves it exists | `{enroll_device: ineligible, [E4: AUTHZ_NOT_EVALUATED(local-enrollment authority)]}` — `M9`'s conditioned gate is unimplemented, so no applicable authority has been consulted; **not** a denial | `info` | that it is observed but unenrolled, what enrolling does, and that the enrollment path is not yet available |
| **H3** | supported, reconciled; `fresh == false` with a real anchor | `collect_now` | `AVAILABLE` | `STALE(as_of)` | `POPULATED` with age | `{collect_now: eligible}` **if `E1`–`E7` all pass** — staleness is not an `E6` prerequisite of the action that refreshes it | `muted` badge + timestamp | the age, its anchor and `stale_reason`; never blank |
| **H4a** | `D3 = UNSUPPORTED`; retained evidence **predates** a platform or support-rule change (K3/K4/K5 fail — **not** CX2) | a support-dependent read | `UNSUPPORTED` | `STALE(as_of)` | `POPULATED` — retained evidence displayed, labelled as predating the current support determination | `{read: ineligible, [E6: capability_unsupported]}` | own `unsupported` token | the **vendor** reason; that the retained evidence predates the current support determination; that collecting again will not make it supported |
| **H4b** | `D3 = UNSUPPORTED`; **contemporaneous** positive evidence, K1–K6 all hold (**CX2 holds**) | a support-dependent read | `UNKNOWN(support_contradiction)` | `STALE(as_of)` if applicable | `POPULATED`, labelled as contradicting the support conclusion | `{read: ineligible, [E6: support_contradiction]}` | `muted` | **both** disagreeing inputs, named; that the contradiction is unresolved |
| **H4c** | `D3 = UNSUPPORTED`; comparability metadata missing (K1/K2/K6 unestablished) | a support-dependent read | `UNKNOWN(support_comparability_unestablished)` | as they hold | `POPULATED` | `{read: ineligible, [E6: support_comparability_unestablished]}` | `muted` | **which** comparability condition could not be established; that neither a contradiction nor a support conclusion is claimed |
| **H5a** | applicable schedule intentionally disabled; retained evidence present | `collect_now` | `POLICY_DISABLED` | `STALE(as_of)` if applicable | `POPULATED` — retained evidence with the latest attempt beside it | `{collect_now: eligible}` **if `E1`–`E7` all pass** — `D5` contributes no blocking reason | `muted` + explicit label | that **automatic** refresh is off and manual collection is unaffected; never that refresh is impossible |
| **H5b** | as H5a; the previous collection failed | `retry` | `POLICY_DISABLED` | as H5a | `POPULATED` | `{retry: eligible}` **only if `E1`–`E7` pass and its own retry contract is satisfied** — a prior failure is never a grant; a ledger/interval refusal is a correct outcome | `muted` | that retry is a new typed job, and any window that governs it |
| **H5c** | as H5a; a third action whose applicable authority refuses it | that action | `POLICY_DISABLED` | as H5a | `POPULATED` | `{action: ineligible, [E4: ACTION_DENIED(authority, reason)]}` | refusal tone on that control only | **both** reasons — the policy that disabled automatic refresh, and the authorization refusal, refusal last |
| **H6** | logical entity supported; one member's value legitimately differs | *(action-free presentation case)* | `AVAILABLE` | `MEMBER_SPECIFIC` | `POPULATED`, side-by-side member comparison | — | `--member-specific` gold row emphasis | "Expected member difference"; **no** warning icon or failure wording |
| **H7a** | the **global module** subject; its surface ships | *(action-free presentation case)* | `AVAILABLE` | — | `POPULATED` | — | normal | the module is a product plane and does not disappear (`AC-WS-10`) |
| **H7b** | the **selected entity** subject; the capability does not apply to its type | an entity-scoped action | `NOT_APPLICABLE` | — | `EMPTY{not_applicable_to_entity_type}` | `{action: ineligible, [E6: capability_not_applicable_to_entity_type]}` | `info` | which entity types support the capability |
| **H8a** | no CLASS 2 action is declared on this surface — **the state today** | *(none declared)* | any | as they hold | as it resolves | **no entry** — an undeclared action produces no `action_eligibility[]` entry at all | unaffected | nothing; there is no control to explain |
| **H8b** | a future CLASS 2 action whose contract exists and is declared | that CLASS 2 action | any | as they hold | as it resolves | `{action: ineligible, [E3: ACTION_REFUSED(CLASS_2), E4: ACTION_DENIED(OP.2 authority, authorization_not_configured)]}` — **both preserved** | refusal tone on that control only | every refusing gate, named — never a bare greyed control (`CON.0` §9) |
| **H9a** | `CX1` — one canonical id resolves to two incompatible entity types/vendors | any action | `UNKNOWN(identity_contradiction)` | as they independently hold | `EMPTY{identity_contradiction}` — evidence withheld | `{action: ineligible, [E5: subject_integrity_unmet]}` for **every** declared action | **`danger`** — a genuine unsafe contradiction, per `AC-DIF-6` | **both** disagreeing identity resolutions, named |
| **H9b** | `CX2` holds (K1–K6) | a support-dependent read, and one unrelated CLASS 0 read | `UNKNOWN(support_contradiction)` | as they hold | `POPULATED` | `{support_read: ineligible, [E6: support_contradiction]}`; `{unrelated_read: eligible}` if its own gates pass | `muted` | **both** disagreeing inputs, named |
| **H9c** | `CX3` — `EVIDENCE_ONLY` while a live registry row exists | `enroll_device`, and one unrelated CLASS 0 read | `UNKNOWN(reconciliation_contradiction)` | as they hold | `POPULATED` | `{enroll_device: ineligible, [E6: reconciliation_contradiction]}`; `{unrelated_read: eligible}` if its own gates pass | `muted` | **both** disagreeing inputs, named |
| **H10** | `D5 = POLICY_UNKNOWN`; evidence usable | `collect_now` | `AVAILABLE` | `SCHEDULE_UNKNOWN`; `STALE` if applicable | `POPULATED` — `POLICY_UNKNOWN` erases nothing | `{collect_now: eligible}` **if `E1`–`E7` all pass** — no schedule-dependent claim is asserted, and `POLICY_UNKNOWN` is valid input, not malformed | normal | that automatic-refresh state could not be determined; **evidence is unaffected** |
| **H11a** | `D6d` `PROVENANCE_UNVERIFIED`; evidence fresh | a drift/override-claim action | `AVAILABLE` | `SOURCE_TRUST_LIMITED(<source reason code>)` | `POPULATED`, **with its own valid collection timestamp** — this qualifier neither supplies nor suppresses it | `{drift_claim: ineligible, [E6: trusted_expected_source_required]}` | `muted`; **no age claim from this qualifier** | that no override or drift claim can be made from this source; **not** that the evidence is old |
| **H11b** | as H11a | `collect_now` | `AVAILABLE` | `SOURCE_TRUST_LIMITED(<source reason code>)` | `POPULATED` | `{collect_now: eligible}` **if `E1`–`E7` all pass** — source trust is not its prerequisite | `muted` | as H11a; the collection timestamp stays displayed |
| **H11c** | `D6e` `IDENTITY_TRANSLATION_REQUIRED`; evidence fresh; `CX1` conditions do **not** hold | a comparison action | `AVAILABLE` | `IDENTITY_TRANSLATION_REQUIRED(<source reason code>)` | `POPULATED`, with its own timestamp | `{comparison: ineligible, [E6: identity_translation_unproven]}` | **`muted`** — a comparability limitation, **not** danger | that the two sides identify this subject differently and the comparison needs a proven translation; **not** that the source is untrusted, and **not** that identities contradict |

**Severity reconciliation.** `H9a` is the only case here that takes
danger/error semantics, because `CX1` is a genuine unsafe contradiction and
`AC-DIF-6` reserves danger for exactly that. `H11a`/`H11b`/`H11c` are
**comparability limitations** and take muted semantics; they do not inherit
danger merely because the frozen parent formerly grouped
`IDENTITY_TRANSLATION_REQUIRED` with contradictory state (§2.7 `FA-8`).

**What changed from the previous revision.** `H4`, `H5`, `H7`, `H8`, `H9` and
`H11` each resolved several actions or several subjects in one row; `H9`'s
eligibility cells additionally named each class's *affected* actions under a
heading reading "Action eligible" — the inverse of the scope rule. Each is now
split into atomic subcases with one subject, one action and one deterministic
result.

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
| `UNKNOWN(identity_contradiction)` | **`danger`** | a genuine unsafe contradiction (`CX1`), per `AC-DIF-6` |
| `UNKNOWN(<any other reason>)` | `muted` | an honest gap, not a fault |
| `COLLECTION_FAILED` | `danger` | actual fault |
| `NOT_SHIPPED` | — | **never rendered** — a disposition reason only (§4.1) |

| `capability_qualifiers` | Tone |
| --- | --- |
| `STALE` | `muted` + timestamp |
| `PARTIAL` | `muted` |
| `SOURCE_TRUST_LIMITED` | `muted`, **no timestamp** |
| `IDENTITY_TRANSLATION_REQUIRED` | `muted`, **no timestamp**, **never `danger`** — a comparability limitation, not a fault (§2.7 `FA-8`) |
| `NOT_SCHEDULED` | `muted` |
| `SCHEDULE_UNKNOWN` | `muted` |
| `MEMBER_SPECIFIC` | **`--member-specific`** — see §6.6 |

| `blocking_reasons[]` on a control | Tone |
| --- | --- |
| any `E1`–`E7` reason, including `ACTION_DENIED`, `ACTION_REFUSED` and `AUTHZ_NOT_EVALUATED` | refusal tone on **that control only**; never applied to the capability's own chip |

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

| Carried in the exported report | Withheld from the report |
| --- | --- |
| `surface_disposition` — the report ships the same surfaces this build ships | — |
| `primary_status` and its copy | — |
| every `capability_qualifier`: `STALE`, `PARTIAL`, `SOURCE_TRUST_LIMITED`, `IDENTITY_TRANSLATION_REQUIRED`, `NOT_SCHEDULED`, `SCHEDULE_UNKNOWN`, `MEMBER_SPECIFIC` | — |
| `evidence_presentation`, with timestamps and provenance | — |
| the identical information architecture (`AC-SH-3`) | live refresh |
| — | **the entire `action_eligibility[]` projection** |

**The divergence is structural, not selective.** The report declares **no
actions** (`AC-SH-1`: no form, no submit control, no job submission, no
credential or trust selection, no enrollment — absent, never disabled). With no
declared action there is no `action_eligibility[]` entry to produce, so the
whole projection is simply **not generated**. The report is not filtering
"action qualifiers" out of a shared flat list — that list no longer exists
(§4.1.1), and there is nothing to filter.

**Every capability qualifier is at parity**, because every one of them
describes the capability's evidence rather than an action.

`NOT_ENROLLED` is reported as a state, with no enrollment affordance —
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

| Kind | Examples today | Rule |
| --- | --- | --- |
| **Authoritative durable records** | Device Registry rows (`utils/device_registry.py`); last-known-good evidence (`utils/snapshot.py`); content-addressed configuration evidence (`utils/config_evidence.py`, `config_storage.py`); console job records; schedule/capability-policy records (future, `M12`) | already durable and **unchanged by `M3`**. A capability state never overwrites or invalidates one |
| **Derived cache — permitted** | the **capability projection** | the frozen `AC-ST-2` explicitly lists *capability projections* among what the approved local store may own. This document **permits** a persisted projection and defines only its validity rules; the schema is `M4`'s and is not designed here |
| **Never persisted as truth** | a resolved `primary_status` / qualifier / eligibility set presented to an operator | a render-time composition over the inputs above |

**Cache validity and invalidation expectations** (contract, not schema; `M4`
owns any storage design, and none is proposed here). A cached projection is a
**presentation optimisation only**, and its validity is established positively:

1. **It records every generation and version it consumed** — the *generation*
   of each consumed authority (registry generation, evidence/last-known-good
   generation, CAS object generation, schedule/policy record generation,
   action-declaration set) **and** the version of each producer and support
   rule that computed them (capability-projection producer version, **vendor
   support-rule version**, **the `I14` comparability metadata that `CX2`
   depends on**, taxonomy version, resolver contract version).
2. **A cache hit is valid only if the resolver can establish that every
   recorded generation and version still matches current authority.** Matching
   is affirmative: if any consumed generation or version cannot be compared —
   unreadable, absent, or from a producer the resolver no longer recognises —
   the entry is **not** a hit.
3. **Missed or delayed invalidation is assumed, not excluded.** Invalidation
   events (§8.1) may be lost, reordered or delayed, so the absence of an
   invalidation signal is **never** evidence of validity. Validity rests on the
   comparison in rule 2, which does not depend on any event having been
   delivered.
4. **A support-rule or platform change invalidates every projection whose
   `CX2` outcome depended on it** — otherwise a cached contradiction or a
   cached "not a contradiction" would outlive the rule that produced it
   (§5.1.1 K3–K5).
5. **It fails closed** — a projection that cannot be validated is discarded and
   recomputed, never served as truth (`AC-ST-6`'s existing posture).
6. **It is RuntimeRoot-resident and excluded from the support bundle**, per the
   existing `AC-ST-6`.

**A cached presentation result never becomes authority.** Specifically it is
never:

| Never | Because |
| --- | --- |
| **endpoint authority** | endpoints resolve server-side at the authorized execution stage; no copied endpoint is retained as fallback (`AC-ST-5`) |
| **authorization authority** | the applicable authority is consulted at decision time (§5.3, `E4`) |
| **action-admission authority** | **the registry stays authoritative at admission and again immediately before execution** (`AC-ST-4`, `E7`); a cached projection never substitutes for either, and a target that has become disabled or unresolvable causes refusal or abort **before contact** |
| **persistence authority** | authoritative durable records are unchanged by `M3` and are never overwritten or invalidated by a projection |

---

## 9. Acceptance criteria

Individually numbered, implementable and testable without subjective visual
judgment. Each names the movement expected to own it. A criterion whose surface
does not yet exist is **not waived** — it becomes that movement's criterion.

### 9.1 Coverage contract for the resolver

A later movement proves the resolver over **finite input classes plus named
boundary cases**, not over an unbounded product and not over a hand-picked
sample.

| Input | Finite classes to enumerate |
| --- | --- |
| D1 | **3** (`SURFACE_PRESENT`, `SURFACE_ABSENT`, `SURFACE_ELIGIBILITY_UNRESOLVABLE`) |
| D2 | 3 |
| D3 | 3 |
| D4 | 5 |
| D5 | 4 |
| D6a freshness | 2 (`fresh` true/false) |
| D6b completeness | 4 |
| D6c outcome | 5 |
| D6d expected-source trust | 2 (absent / `PROVENANCE_UNVERIFIED`) |
| D6e identity translation | 2 (absent / `IDENTITY_TRANSLATION_REQUIRED`) |
| CX2 comparability (`I14`) | 3 (K1–K6 all hold / K3–K5 fail / K1, K2 or K6 unestablished) |
| member context | 3 (single-member / members agree / members differ) |
| action class | the 5 taxonomy classes |
| authorization | 3 outcomes (`PERMITTED`, `DENIED`, `AUTHZ_NOT_EVALUATED`) × the authorities applicable to that class |
| shell | 2 (report / console) |

Required coverage:

1. **All four stage-0 cases** `V-A`–`V-D`, including: a contradiction on an
   absent surface asserting **nothing renders** and a diagnostic is emitted;
   and `V-D` asserting the disposition is `OMIT_UNRESOLVABLE` and **never**
   `NOT_SHIPPED`.
2. **Absent is not empty** — under either omission, the four capability outputs
   are asserted **not produced**, not produced-as-empty.
3. **Every reachable rank of the §5.2 ladder fires at least once**, including
   ranks 0, 1 and 11.
4. **Every contradiction class CX1–CX3** is exercised, and at least four
   *non*-contradictory co-occurrences are asserted **not** to trigger the gate:
   `UNSUPPORTED` with superseded historical evidence; `POLICY_DISABLED` with
   fresh evidence; `POLICY_UNKNOWN` with usable evidence;
   `IDENTITY_TRANSLATION_REQUIRED` without `CX1`'s conditions.
5. **CX2 comparability** is exercised across all three `I14` classes, asserting
   contradiction / no-contradiction / honest-unknown respectively, and
   asserting that a support-rule or platform change alone never manufactures a
   contradiction from historical evidence.
6. **Every capability qualifier** is exercised alone and with at least one
   non-`AVAILABLE` primary, and `SOURCE_TRUST_LIMITED` and
   `IDENTITY_TRANSLATION_REQUIRED` are asserted **independently settable** with
   their source reason codes preserved.
7. **Every input class of §4.1.3** — absent, malformed, contradictory,
   incomparable, stale, unevaluated, validly-unknown — is exercised for at
   least one input, with `POLICY_UNKNOWN` asserted **not** malformed.
8. **Every gate `E1`–`E7`** is exercised as the sole blocking reason for one
   action, and `E3` is asserted to be evaluated **exactly once** (never again
   inside `E4`).
9. **Action keying** — with at least two declared actions, one refused and one
   eligible, each result is asserted to carry its own `action_id` and its own
   `blocking_reasons[]`, and no action outcome appears in
   `capability_qualifiers`.
10. **Named boundary cases**, asserted individually: `fresh == false` with no
    freshness anchor; `data_state == partial` with `D6c == success`;
    `PROVENANCE_UNVERIFIED` with `fresh == true`; `IDENTITY_TRANSLATION_REQUIRED`
    with `fresh == true`; `POLICY_UNKNOWN` with fresh evidence; an action whose
    `E6` set is empty, asserted still to evaluate `E1`–`E5` and `E7`; a
    targetless read asserted to acquire no invented subject prerequisite; a
    malformed dimension value; unresolvable `D1`.
11. **Generated combination coverage** over the D2–D6 cross-product for the
    ladder and `evidence_presentation`, which makes the determinism claim
    (`AC-CS-12`) falsifiable. Action eligibility is covered per (action class ×
    authorization outcome × `E6` present/absent × subject required/not), not by
    crossing it with the full dimension product.
12. **Cache validity** is exercised with a matching generation set, a
    mismatched generation, a mismatched producer/support-rule version, an
    uncomparable generation, a **missed invalidation event**, and a
    **support-rule change that invalidates a cached `CX2` outcome**.

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
| `AC-CS-64` | `AVAILABLE` requires the rank-10 positive conjunction; a malformed, absent or unclassifiable **required** input resolves to `UNKNOWN(unclassified_input)` and never to `AVAILABLE` or to executable eligibility. A **validly unknown** value (`SUPPORT_UNKNOWN`, `POLICY_UNKNOWN`, `APPLICABILITY_UNKNOWN`, `RECONCILIATION_UNKNOWN`) is **not** malformed and never reaches rank 11 | `M10` |
| `AC-CS-65` | Every atomic case in §5.5 (`H1`…`H11`, including every lettered subcase) resolves exactly as its row states, across all four outputs, for the **named action** in that row and under the row's stated assumptions. Each case has one subject and one named action, or is explicitly action-free | `M10`/`M11` |

### Authorization regime separation (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-66` | The `OP.2` authorizer is consulted for CLASS 2 only; no CLASS 0 console operation is denied by reference to it, and the absence of an actor-authorization model is neither an automatic grant nor a blanket denial | `M14` |
| `AC-CS-67` | Each `ACTION_DENIED` names the **applicable authorization authority** that refused it; `AUTHZ_NOT_EVALUATED` names the authority not consulted and is never rendered as either a grant or a refusal | `M11` |
| `AC-CS-68` | A cached or shared capability projection is never an input to an authorization decision (§5.3 rule 4) | `M10`/`M14` |
| `AC-CS-69` | Evidence-gathering actions (first contact, collect now, retry) declare no `E6` prerequisite on the evidence they produce and are never blocked by its absence. They remain subject to **every** other gate `E1`–`E5`, `E7`, to their own other prerequisites, and — for retry — to its own retry contract; a prior failure never grants retry | `M7`/`M8` |

### Persistence (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-70` | Existing durable evidence — registry rows, last-known-good, CAS configuration evidence, job records — is neither invalidated nor overwritten by any capability state | `M4`/`M10` |
| `AC-CS-71` | A persisted capability projection records **every** consumed authority generation **and** producer/support-rule version. A cache hit is valid only when the resolver affirmatively establishes that all of them still match current authority; an uncomparable generation or version is not a hit. Missed or delayed invalidation is assumed, so the absence of an invalidation event is never evidence of validity. It fails closed rather than serving stale truth | `M4`/`M10` |
| `AC-CS-72` | No resolved `primary_status` or qualifier set is persisted as authoritative truth | `M10` |
| `AC-CS-73` | A cached projection is never endpoint, authorization, action-admission or persistence authority. The registry is still read at admission **and** immediately before execution (`AC-ST-4`), and a disabled or unresolvable target causes refusal or abort before contact | `M4`/`M6`/`M7` |

### Visibility isolation (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-74` | `D1 = SURFACE_ABSENT` yields `OMIT_NOT_SHIPPED` and ends resolution: nothing renders, and no contradiction, qualifier, evidence, policy or authorization outcome can make it render (§5.0 `V-A`/`V-B`) | `M10`/`M11` |
| `AC-CS-75` | A contradiction on an absent surface emits a diagnostic / audit / telemetry record and changes **no** rendering (§5.0 `V-B`) | `M10` |
| `AC-CS-76` | `D1 = SURFACE_ELIGIBILITY_UNRESOLVABLE` yields `OMIT_UNRESOLVABLE` with `d1_input_unresolvable`, failing closed toward omission and never toward rendering (§5.0 `V-D`) | `M10` |
| `AC-CS-77` | No stage after stage 0 can alter the `surface_disposition` — asserted by varying every later input against each fixed omission disposition | `M10` |

### Mandatory action gates (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-78` | Every action evaluates gates `E1`–`E7` (§3.4) regardless of its `E6` contents; an **empty `E6` set never shortens the others** — asserted with an action declaring no semantic prerequisites | `M10`/`M11` |
| `AC-CS-79` | `E5` requires only what the action's own contract requires; a **targetless** read (`target_mode="none"`) has no device or target prerequisite invented for it | `M10` |
| `AC-CS-80` | `AUTHZ_NOT_EVALUATED` yields non-executable eligibility and is represented, rendered and audited **distinctly** from a confirmed `ACTION_DENIED`; it is never rendered as a refusal | `M11`/`M14` |
| `AC-CS-81` | No example, table cell or rule states that an action is enabled, permitted or eligible unconditionally; every eligibility statement is conditional on `E1`–`E7` passing for that action | `M3` onward |
| `AC-CS-82` | Retry satisfies its own retry contract — a new typed job, never a mutated historical outcome, and subject to any ledger or minimum-re-execution window; a prior failure alone never grants it | `M7` |
| `AC-CS-83` | Manual collection is independent of `D5` scheduling policy and of nothing else: it passes `E1`–`E3`, `E5`–`E7`, enters the same admission coordinator and single orchestration path, and stays within the vendor interaction-safety budget | `M7`/`M12` |

### Input contract (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-84` | Every input `I1`–`I20` is supplied to the resolver, and each is classified absent / malformed / contradictory / incomparable / stale / unevaluated / validly-unknown **before** any output consuming it resolves | `M10` |
| `AC-CS-85` | `REGISTRY_ONLY` and its reason code make only a **present-tense** claim about the current evidence set; no output asserts that an entity was never observed | `M10` |

### Result algebra (added by this revision)

| id | Criterion | Owner |
| --- | --- | --- |
| `AC-CS-86` | `OMIT_UNRESOLVABLE` **never** asserts `NOT_SHIPPED` or any other positive claim about what the build ships; only `OMIT_NOT_SHIPPED`, reached from a positively established `SURFACE_ABSENT`, may | `M10` |
| `AC-CS-87` | Under either omission disposition the four capability outputs are **absent** — not produced as empty collections, null placeholders or a defaulted `primary_status`. A consumer receiving an omission has no capability result to read and must not synthesise one | `M10` |
| `AC-CS-88` | Every `action_eligibility[]` entry carries its own `action_id` and its own `blocking_reasons[]`; **no** action outcome (`ACTION_DENIED`, `ACTION_REFUSED`, `AUTHZ_NOT_EVALUATED`) appears in `capability_qualifiers` — asserted with at least two declared actions, one refused and one eligible | `M10`/`M11` |
| `AC-CS-89` | `E3` (taxonomy/surface admissibility) is evaluated **exactly once** per action and is never re-read inside `E4`; each of `E1`–`E7` is exercised as the sole blocking reason for one action | `M10` |
| `AC-CS-90` | `D6d` (`PROVENANCE_UNVERIFIED`) and `D6e` (`IDENTITY_TRANSLATION_REQUIRED`) are independently settable, carry their **source reason codes** into presentation, and neither yields `STALE`, the other's qualifier, or `CX1` on its own | `M10`/`M11` |
| `AC-CS-91` | `CX2` holds only when comparability conditions `K1`–`K6` all hold. A platform or support-rule change alone never manufactures a contradiction from historical evidence (K3–K5 fail → `UNSUPPORTED`, evidence retained), and unestablished comparability (K1/K2/K6) resolves to `UNKNOWN(support_comparability_unestablished)` rather than either a contradiction or a support conclusion | `M10` |
| `AC-CS-92` | The exported report generates **no** `action_eligibility[]` projection at all, because it declares no actions — not a filtered subset of one. Every `capability_qualifier` is at parity with the console | `M11` |
| `AC-CS-93` | Every `FA-*` row in §2.7 names exactly one owning `PO-M3-*` decision, and every `PO-M3-*` decision that owns rows lists them — asserted as a two-way mapping over §2.7 and §11.2 | `M3` onward |

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
| **X39** | `FA-1`…`FA-9` had no owning PO decision, and the withheld-approval instruction told implementers to revert to semantics the draft had demonstrated false | rev 4 | §2.7 owner column; §11.2; the three-point withheld-approval rule |

### 11.2 Genuine choices — decision required

Six decisions, each explicitly owning its frozen-parent amendments.

| id | Decision | Owns | Recommendation |
| --- | --- | --- | --- |
| **PO-M3-1** | **Tab stability.** The navigation contract contradicts itself: §8/§8.1 permit omitting a structurally inapplicable tab for an entity type; §6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` forbid it. Which governs? | **`FA-5`** | **Preserve D-NAV13 / `AC-WS-7` / `AC-WS-8`.** Tab stability wins; §8/§8.1's omission language is residual revision-1 text that §6.5 explicitly closed |
| **PO-M3-2** | **Semantic parent corrections.** Approve the corrections that align the parent's presentation tables with verified producer semantics — freshness, provenance, identity translation, unsupported-vs-unknown, retained evidence, and unconditional action cells | **`FA-1`, `FA-2`, `FA-3`, `FA-4`, `FA-6`, `FA-7`, `FA-8`** | **Approve.** Each contradicts a verified source semantic (V9a, V11, V12a) or the parent's own `AC-DIF-8`. If declined, §4.3, §5 and §9 of this draft must be reverted to the parent's wording and the resulting false semantics recorded as known defects |
| **PO-M3-3** | **Composition model.** Retain the surface disposition plus four capability outputs, with action outcomes keyed by `action_id`? | **`FA-9`** | **Retain provisionally**, subject to §5's resolution contract and §9.1's coverage. §10 dissent D1 remains recorded |
| **PO-M3-4** | **Registry/evidence reconciliation shape.** Retain the directional `D4` value set, with `RECONCILIATION_UNKNOWN` covering ambiguous or unresolved identity and **no new identity authority**? | — | **Retain.** The concept is two-sided; a flat `NOT_ENROLLED` cannot express the reverse. Ambiguous identity resolves to `RECONCILIATION_UNKNOWN` or `CX1`, never a guessed join (`AGENTS.md` identity law; `AC-WS-2`) |
| **PO-M3-5** | **`--member-specific` token.** Approve decoupling the gold member emphasis from `--warning`, preserving the emphasis and its non-fault semantics? | — | **Approve as contract direction**; implementation belongs to a later UI movement |
| **PO-M3-6** | **`NOT_SCHEDULED` as a qualifier**, emitted only where schedule absence is authoritatively established? | — | **Retain as a qualifier**, gated on `NO_APPLICABLE_SCHEDULE`. Schedules govern future automatic refresh, not current usability |

Every `FA-1`…`FA-9` row is owned: `FA-5` by `PO-M3-1`; `FA-1`–`FA-4`, `FA-6`,
`FA-7`, `FA-8` by `PO-M3-2`; `FA-9` by `PO-M3-3`.

**Reported for awareness, no decision requested.** C3 — compliance `UNKNOWN`
counting in the alignment denominator — is inside the compliance contract's
domain, not `M3`'s, and is unchanged here.

### 11.3 Withheld approval — what follows

If the Product Owner withholds approval on any `FA-*` row:

1. the **authority conflict remains recorded** in §2.7, unresolved;
2. the **affected implementation remains blocked** — the acceptance criteria in
   that row's consequence column cannot be implemented without contradicting
   the frozen parent or fabricating data;
3. **no implementer is instructed to reproduce semantics already demonstrated
   false**;
4. **this draft is not silently rewritten** to pretend the conflict
   disappeared.

There is no fifth option, and in particular no instruction to "revert to the
parent's wording and proceed" — that would be option 3's opposite.

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
