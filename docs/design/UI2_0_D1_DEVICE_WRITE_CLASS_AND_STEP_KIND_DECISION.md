# UI 2.0 — D1: device write class and step kind, decision options

## Status

**DRAFT — OPTION A SELECTED IN RELAY; FROZEN BASELINE AMENDMENT PENDING.**
This document originally presented options for the Product Owner to choose
from, per
`relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json`'s
`SESSION_START`. The Product Owner selected Option A at relay seq 3 and
subsequently fixed its representation/signability/admission/scope details at
seq 5, 7, 9, 11, 13, 15, and 17. The two companion DRAFTs carry the current
literal proposal: `RESTORE_CONTROLLED_WRITE_LEDGER.md` and
`UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`. Restore stays disabled and
unimplementable in Java until the selected decision is recorded in
`docs/design/UI2_0_BASELINE_CONTRACT.md` §2 (see §6 below). No
`utils/action_taxonomy.py` edit, no `C4` edit, no FROZEN-document edit, and no
test-file edit accompanies this document.

---

## 1. Problem statement (quoting `C7` §9.1–§9.3 by section)

`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN)
specifies restore execution in full (§5–§6) per baseline `RESTORE-IN-
RELEASE-1` (D-7, ACCEPTED), then reports — not resolves — a structural gap
in its own §9.1–§9.3:

> **§9.1** "`CLASS_1_RECOVERY_WRITE`'s own docstring scopes it explicitly:
> *'Narrowly scoped recovery operations only: temporary backup creation,
> exact generated-artifact cleanup, recovery-specific device operations.'*
> A restore's write — pushing a fetched artefact to a device and invoking a
> vendor restore/apply procedure that replaces or merges configuration
> state — is not 'backup creation' or 'artefact cleanup,' and BAA §8
> independently classifies restore as *'a `config-write` at the highest
> blast radius available... sits at the `OP.2` bar.'*
> `CLASS_2_OPERATIONAL_STATE_CHANGE` 'has no member yet' and is scoped to
> failover/cluster-role transition, not configuration restore;
> `CLASS_3_CONFIGURATION_WRITE` is prohibited outright at current maturity.
> **No existing class cleanly names what a restore write is**, and this
> document does not invent one... Until it closes, §5's restore engine is
> fully specified but **not executable** on any device."

> **§9.2** "`C4` §2.3 lists `sftp_put` as *'reserved, refused at spec-
> validation time — no capability may push bytes to a device at current
> maturity.'* A restore necessarily transfers the artefact **to** the
> device... the opposite direction of every step kind `C4` §2.3 currently
> permits. Beyond transport, no step kind represents 'apply a transferred
> restore artefact via the vendor's own restore procedure' at all —
> `exec`/`poll` could plausibly carry the apply *command*, but the artefact
> transfer itself has no legal step kind today."

> **§9.3** "Closing §9.1... without closing §9.2... leaves restore
> classified but still inexpressible in `C4`'s registry; closing §9.2
> without §9.1 leaves a step kind with no class-2/3/4-adjacent name to
> resolve to under `C4` §3.3 step 7's 'a row whose `action_class` is class
> 2, 3 or 4 can never be `SIGNED_OFF`' rule. Both need the same successor
> movement's attention together."

Restated plainly: `C7` is a complete, frozen specification for a capability
that cannot be admitted anywhere in the running system. `C4` §3.5's
execution-eligible view (the subset of the capability registry `C2` job
admission may dispatch from) requires every step's gate resolution to be
`KNOWN` or `NOT_APPLICABLE`; a restore capability's transfer/apply steps can
never resolve `KNOWN` today because (a) no taxonomy class fits the write
(§9.1) and (b) no step kind exists to carry a device-directed write at all,
with `sftp_put` unconditionally refused at spec-validation time regardless
of gate sign-off (§9.2). This is not a missing gate-registry row that a
later `SIGNED_OFF` entry would fix — it is a missing *class* and a missing
*kind*, both closed sets by design (`AGENTS.md`: "No new class 2, 3 or 4
command"; `C4` §2.3: "adding a kind is an amendment to this document...
never a runtime configuration choice").

---

## 2. Constraints from FROZEN authority

These bind every option below; none is negotiable within this movement:

1. **`utils/action_taxonomy.py` is the single source of truth for the five
   action classes** (`AI_START_HERE.md`, `AGENTS.md` "Network action
   taxonomy") and is test-enforced
   (`tests/test_architecture_convergence.py::test_the_five_classes_exist_
   and_are_ordered`, `::test_recovery_write_and_operational_state_change_
   are_distinct_classes`, `::test_configuration_write_and_policy_
   deployment_stay_prohibited`, `::test_only_class_0_is_console_
   submittable`). Any option that adds, removes, renames, or reorders a
   class member changes at least one of these assertions.
2. **`AGENTS.md` "No new class 2, 3 or 4 command at the current product
   maturity"** (quoted verbatim by `C4` §3.3 step 7) is a standing
   prohibition, not a per-movement default. An option that resolves
   restore's write to class 2, 3, or 4 as currently defined does not
   change the day-one outcome: the write stays unauthorizable until that
   prohibition is separately lifted by whatever process governs it — this
   document does not lift it.
3. **`C4` §2.3's step-kind set is closed**; `sftp_put` is "reserved,
   refused at spec-validation time" unconditionally, independent of any
   gate's `sign_off_state`. Un-reserving it, or adding a new device-push
   kind, is a `C4`-successor amendment regardless of which taxonomy option
   is chosen.
4. **`C4` §3.3 step 7**: "a row whose `action_class` is class 2, 3, or 4 can
   never be `SIGNED_OFF`." A gate-registry row for a restore-apply step
   inherits whatever class the chosen option resolves to; if that class is
   2/3/4-as-currently-scoped, the row is permanently un-signable under
   today's rule, which is `C4`'s own enforcement of constraint 2 above —
   changing that outcome requires amending `C4` §3.3 itself, not just
   adding a gate row.
5. **`OP.2`'s prerequisite bar is the closest existing precedent for any
   device-write-capable class** (`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`
   §10/§10.1, `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
   mature `SEE`/`VERIFY`/`TRACE`/`RECOVER` planes, `DEPLOY.1A` OIDC + an
   RBAC role with full audit, the `cp_device_interaction_safety` audit, the
   network-device command gate for the specific write primitives and their
   rollbacks, and a signed change-management/safety review with the
   network-security leads. `BAA` §8 independently lists the same bar for
   restore specifically (plus "V4 restore-proven status for that exact
   vendor/platform/version class" and "a demonstrated rollback path") and
   states it is "not waivable." No option below is exempt from this bar;
   the options differ only in *which class/kind mechanism* the write would
   eventually run through once the bar is met, never in whether the bar
   applies.
6. **Baseline `RESTORE-IN-RELEASE-1` (D-7) is not reopened.** D-7 accepted
   that Release 1 includes restore on the declared-supported platform/
   version set; it did not accept, and this document does not decide,
   *how* the write becomes admissible. `APPROVAL-MODEL` (D-2c) is likewise
   not reopened: it already assigns restore "per-operation independent
   approval," distinct from routine backup's role-plus-reason model — every
   option below inherits that assignment unchanged; none narrows or widens
   it.
7. **`C3`'s closed six-token role vocabulary** (§4.1: `viewer`, `operator`,
   `onboarding_admin`, `backup_admin`, `compliance_admin`, `security_admin`)
   names no dedicated "restore approver" or "device-write operator" token.
   `role:security_admin` is structurally excluded from any device-facing
   action by `C3`'s own separation-of-duties rule. An option requiring a
   new role token is a `C3`-successor amendment, not a free addition.

---

## 3. Options

Four options, per the relay packet's minimum set. No fifth genuinely
distinct option was found: every variation considered (e.g. "scope restore
per-vendor instead of per-write-kind") reduces to a parameterization of
options A–C, not a different admission mechanism.

### Option A — new class between 1 and 2 (`CLASS_1B_CONTROLLED_RESTORE_WRITE` or similar)

A new `ActionClass` member, numerically and semantically between
`CLASS_1_RECOVERY_WRITE` (backup/cleanup only) and
`CLASS_2_OPERATIONAL_STATE_CHANGE` (failover, no member), scoped narrowly to
"push a validated, manifest-bound backup artefact for the same device
identity, and invoke the vendor's own restore procedure" — nothing broader.

| Dimension | Detail |
|---|---|
| **Taxonomy change** | Add one `ActionClass` (e.g. `id="controlled-restore-write"`, level between 1 and 2 — `ACTION_CLASSES` is presently a flat `(0,1,2,3,4)` tuple keyed by integer `level`; a new member needs either a non-integer level scheme (e.g. `1.5`) or a renumbering of 2/3/4, both of which are `AGENTS.md`/`C4`-visible taxonomy changes, not additive-only). `permitted=True` only once the class's own admission gate (a new contract, not this document) is met; `console_submittable=False` always, matching `CLASS_1`'s current posture and `UI-OPERATIONAL-RUN-NOW`'s "job request ≠ console submission" pattern. |
| **`C4` step-kind change** | A new device-directed write kind (e.g. `restore_push`) distinct from the refused `sftp_put`, with its own expectation-gate semantics (transfer integrity, not merely "bytes sent") — `C4` §2.3 amendment. `sftp_put` itself stays reserved/refused; this option does not un-reserve it, it adds a narrower sibling scoped only to restore's own artefact-transfer shape. |
| **Approval-model fit** | Clean fit: `APPROVAL-MODEL`'s existing "per-operation independent approval" for restore (D-2c) already matches a narrow class's per-operation gate; `C7` §6.2's `restore_approval` record needs no redesign. |
| **Audit/ledger needs** | A new ledger analogous to `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` but restore-specific (per-target, not per-24-hour-cadence, per `C7` §5.3's note that the existing `RB.x` ledger's cadence ceiling is `NOT_APPLICABLE` to restore) — new document, new table. |
| **Test files/assertions that would change** | `tests/test_architecture_convergence.py::test_the_five_classes_exist_and_are_ordered` (level list changes from `[0,1,2,3,4]`), `::test_recovery_write_and_operational_state_change_are_distinct_classes` (a third distinct class now sits between them — new assertions needed, existing ones likely still pass unchanged), `::test_only_class_0_is_console_submittable` (unaffected if the new class stays non-submittable), `::test_every_console_job_type_maps_to_a_taxonomy_class` and `::test_no_console_job_type_is_class_2_or_above` (needs an equivalent "or the new controlled-restore-write class" exclusion, or the new class's own not-console-submittable test), plus any `console/registry.py` `JOB_REGISTRY` fixture asserting exactly five classes. New test file for the new ledger contract, mirroring `tests/` coverage of `RECOVERY_OPERATIONAL_WRITE_LEDGER.md`. |
| **FROZEN documents needing amendment** | `utils/action_taxonomy.py` (new class), `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 (new step kind), §3.3 step 7 (the "class 2/3/4 never `SIGNED_OFF`" rule needs an explicit carve-in or restatement for the new class, since it is neither class 1 nor class 2/3/4 as written), `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §9.1–§9.3 (mark resolved, reference the new class), a new ledger design document. |
| **Blast-radius honesty** | Most honest option of the four: it names the write's real, narrower risk shape (a verified-artefact push to its own device, never operator-authored bytes) instead of forcing it into an existing box that either overstates it (class 2/3/4, full config-write/failover blast radius) or understates it (class 1, "backup creation"). Cost: it is new taxonomy surface, reviewed and tested from scratch rather than reusing an already-hardened admission path. |

### Option B — widen `CLASS_1_RECOVERY_WRITE`'s scope to cover restore under per-operation approval

Keep five classes; broaden `CLASS_1_RECOVERY_WRITE`'s docstring and admitted
operation set from "backup creation, exact generated-artifact cleanup,
recovery-specific device operations" to explicitly include "restore of a
validated backup artefact to its originating device, under per-operation
approval," with `permitted` unchanged (`True`) and `console_submittable`
unchanged (`False`).

| Dimension | Detail |
|---|---|
| **Taxonomy change** | Edit `CLASS_1_RECOVERY_WRITE`'s `why` text and docstring scope only; no new member, no renumbering. Smallest textual diff of the four options. |
| **`C4` step-kind change** | Same as Option A — a new device-directed write kind is still required (or `sftp_put` itself is un-reserved and re-scoped, which `C4` §2.3 treats as the same category of amendment either way); widening the taxonomy class alone does not give `C4` a kind to carry the write. |
| **Approval-model fit** | Requires an explicit split *within* one class: `CLASS_1_RECOVERY_WRITE` currently has one uniform admission story (`RB.x`'s ledger/credential/allowlist contracts, no per-operation human approval — the ledger's cadence ceiling *is* its safety mechanism). Folding in restore's `APPROVAL-MODEL`-mandated per-operation independent approval means the class's admission rule is no longer uniform across its own members — backup creation needs no per-run approval, restore under the same class now does. This is a real, if containable, conceptual weakening: a class boundary is supposed to describe one risk/gate shape, per `AGENTS.md`'s original complaint about the legacy `read \| operational-write` vocabulary conflating unlike risks ("[the old vocabulary] could not tell a Gaia backup ... apart from a failover"). Option B risks recreating a milder version of exactly that inside class 1 itself. |
| **Audit/ledger needs** | The existing `RB.x` ledger's per-entity/24-hour-cadence model is stated by `C7` §5.3 to be `NOT_APPLICABLE` to restore ("not recurring and not admitted by cadence at all"); restore would need its own admission mechanism layered inside the same class, which is most of Option A's new-ledger work anyway, just filed under the old class's docstring instead of a new one. |
| **Test files/assertions that would change** | `tests/test_architecture_convergence.py::test_recovery_write_and_operational_state_change_are_distinct_classes` (still passes — `permitted`/`console_submittable`/`level` values are unchanged; only prose changes, which this test does not assert on) — the smallest test surface of the four options, *if* no new admission-boundary test is added; but honest coverage of "restore has different admission rules than backup within the same class" would need new tests analogous to the ones the module docstring's own `UI-OPERATIONAL-RUN-NOW` amendment added when it needed to explain why a Run Now job request is still not a console submission. |
| **FROZEN documents needing amendment** | `utils/action_taxonomy.py` (docstring/`why` text), `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 (same step-kind gap as Option A — this option does not avoid it), `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §9.1 (mark resolved), possibly `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (state restore's exemption from its cadence model explicitly rather than leaving it to `C7` §5.3's note alone). |
| **Blast-radius honesty** | Weakest of the four on this criterion: it reuses a class whose own docstring, module comment, and every existing safety document describe it as "narrowly scoped... backup creation, exact generated-artifact cleanup" specifically *because* that scope is bounded and low-blast-radius (an archive file, cleanly reversible by deleting it). A restore that replaces device configuration is a materially larger blast radius wearing the same class label — the exact pattern `AGENTS.md`'s evidence laws warn against ("collection success != semantic correctness," applied here to a class label rather than a field). |

### Option C — place restore under `CLASS_2`'s gating machinery as a distinct member

Restore's write resolves to `CLASS_2_OPERATIONAL_STATE_CHANGE` (or a new
sibling member inside "class 2" numerically, e.g. `CLASS_2B`), inheriting
the full `OP.2` prerequisite bar (§2 constraint 5 above) rather than any
lighter-weight admission path.

| Dimension | Detail |
|---|---|
| **Taxonomy change** | Either (a) restore literally becomes a second member of `CLASS_2_OPERATIONAL_STATE_CHANGE` (same `level=2`, same `permitted=False` until its own OP.2-equivalent bar is met, same `console_submittable=False`), which the current dataclass shape supports without renumbering since `ActionClass` is a value, not a singleton enum member — but every place in the codebase and docs that treats "class 2" as synonymous with "failover" (most of `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1, `OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`) would need to be re-read as "class 2 = any operational state change, failover is one member, restore is another" — a bigger prose/precedent-consistency change than the taxonomy diff itself suggests; or (b) a distinct new class at the same severity tier, which collapses into Option A's mechanics with `CLASS_2`'s prerequisite bar attached instead of a lighter one. |
| **`C4` step-kind change** | Same underlying gap as Options A/B (no device-push kind exists); additionally, `C4` §3.3 step 7's "class 2/3/4 never `SIGNED_OFF`" rule then applies to restore's gate rows *by construction and by design* (not as an accidental side effect to explain away, as in Options A/B) — restore genuinely cannot have a `SIGNED_OFF` gate row until `C4` §3.3 itself is amended to carve out an `OP.2`-prerequisites-met exception, mirroring whatever mechanism (if any) eventually lets a failover gate row become signable. |
| **Approval-model fit** | Best structural fit of the four for the *severity* `BAA` §8 already assigns restore ("sits at the `OP.2` bar... inherits every one of its gates, plus two of its own": V4 restore-proven status, a demonstrated rollback path) — this option takes `BAA` §8 at its word rather than working around it. `APPROVAL-MODEL`'s per-operation approval (D-2c) still applies but becomes one input among the full `OP.2` prerequisite set (RBAC role, safety review, etc.), not the primary gate as in Options A/B. |
| **Audit/ledger needs** | Reuses whatever `OP.2` eventually builds for failover's audit trail (per-entity lock held preflight→act→verify, `OUTCOME_UNKNOWN` quarantine-until-acknowledged, no automatic retry) rather than inventing a parallel mechanism — but that machinery does not exist yet (`OP.2.A`/`OP.2.B` are "IMPLEMENTED," `OP.2.1` CP command gate is "DRAFTED," CLASS 2 "still has no member, unconditional `DENY`" per `CURRENT_STATE.md`). Restore would be blocked on infrastructure being built for an unrelated purpose (failover) rather than on its own dedicated path. |
| **Test files/assertions that would change** | `tests/test_architecture_convergence.py::test_no_console_job_type_is_class_2_or_above` (currently asserts `offenders == {}`; a restore job type at class 2 must stay excluded from `console/registry.py`'s `JOB_REGISTRY` exactly like failover, so this test's *pass* condition is unaffected, but its own docstring — "CLASS 2 has no member yet and must not gain one here... may only appear once every `OP.2` prerequisite... is met" — is written assuming failover is the only ever class-2 candidate; a second class-2 member changes what this test is guarding), `test_recovery_write_and_operational_state_change_are_distinct_classes` (unaffected if restore is a same-class sibling rather than merging into the failover member itself), `tests/test_op2_1_cp_clusterxl_command_gate.py`/`tests/test_op2_a_b_execution_foundation.py`/`tests/test_op2_c_cp_clusterxl_adapter.py` (would need review for any hard assumption that class 2's sole member is failover). |
| **FROZEN documents needing amendment** | `utils/action_taxonomy.py`, `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 and §3.3 step 7, `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1 (restate "class 2" as a tier with potentially more than one member rather than a failover synonym), `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` (same restatement), `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §9.1. Largest FROZEN-document surface of the four options. |
| **Blast-radius honesty** | Most conservative and arguably most honest about restore's *ceiling* risk (a botched restore genuinely can leave a production device in an unknown configuration state, the same order of consequence as a botched failover) — but likely over-states the *typical* case (`BAA` §8's own bar was written for restore-as-config-write-at-large, before `C7`'s narrower "verified, manifest-bound artefact for the same device identity only" scope existed; §4 below argues this narrower scope is a materially different, smaller-blast-radius operation than an arbitrary configuration write). Placing restore under `OP.2`'s full bar risks blocking restore on machinery and organizational review built and paced for failover, delaying `RESTORE-IN-RELEASE-1` (D-7) indefinitely without a restore-specific reason for that delay beyond "the two operations happen to share `BAA` §8's original prose." |

### Option D — keep restore disabled and rename Release 1 per D-7's own rule

Do not resolve the taxonomy/step-kind gap now. `C7`'s restore engine
(§5–§6) stays fully specified and unexecutable, exactly as it is today.
Per D-7's own text — *"Any release without restore is named 'platform
pilot / backup collection release', never Release 1"* — the current
release is renamed accordingly, and `RESTORE-IN-RELEASE-1` is either (a)
deferred to a Release 2 that reopens this document once a future movement
is ready to spend the review depth Options A/B/C require, or (b) kept
nominally targeting "Release 1" with restore's device-write admission as
the last blocking item, at the Product Owner's discretion.

| Dimension | Detail |
|---|---|
| **Taxonomy change** | None. `utils/action_taxonomy.py` is untouched. |
| **`C4` step-kind change** | None. `C4` §2.3's closed set is untouched; `sftp_put` stays reserved/refused. |
| **Approval-model fit** | Not applicable — there is no admission path to fit an approval model to, because there is nothing to admit. |
| **Audit/ledger needs** | None new. |
| **Test files/assertions that would change** | None. Zero test-file impact — the only option of the four with none. |
| **FROZEN documents needing amendment** | `docs/design/UI2_0_BASELINE_CONTRACT.md` §4 ("Release 1 definition") if the release is renamed; potentially §2's `RESTORE-IN-RELEASE-1` row itself, to record the deferral and the naming consequence D-7 already specifies for this exact situation (this is D-7's own rule being applied, not D-7 being reopened or reversed). `C7`'s own status is unaffected — it remains FROZEN and fully specified, simply unexecuted. |
| **Blast-radius honesty** | Zero blast radius, by construction — nothing changes about what the product can do to a device. The honesty cost is schedule/promise, not safety: D-7 was an explicit commitment ("Release 1 product acceptance includes a restore flow... Any release without restore is named 'platform pilot'... High risk strengthens the `C7` contract; it is not a reason to drop the promise") and this option is the one path that does not act on that commitment now. It is not a reversal of D-7 (D-7's own text already names this exact fallback), but it does mean Release 1 as currently scoped does not ship with a working restore, deferring the promise rather than keeping it on the current timeline. |

### Comparison table

| | A — new narrow class | B — widen `CLASS_1` | C — fold into `CLASS_2` | D — stay disabled, rename |
|---|---|---|---|---|
| Taxonomy edit | new member (renumber/level scheme) | docstring/`why` text only | new member or reuse `CLASS_2` | none |
| `C4` step-kind edit | new kind, `sftp_put` stays refused | new kind, `sftp_put` stays refused | new kind, `sftp_put` stays refused | none |
| `C4` §3.3 step 7 impact | needs explicit carve-in for the new class | unaffected (class 1 already `SIGNED_OFF`-eligible) | applies as designed; needs `OP.2`-bar-met carve-out | none |
| Approval-model fit | clean (matches D-2c directly) | requires a within-class split | correct-but-heavyweight (inherits `OP.2` bar) | not applicable |
| New ledger/audit | yes, restore-specific | yes, filed under class 1's docstring | reuses (unbuilt) `OP.2` machinery | none |
| Test-file surface | moderate (new class assertions + new contract tests) | small (docstring change; honest coverage adds more) | largest (class-2 semantics, `OP.2.*` tests) | none |
| FROZEN docs touched | `taxonomy`, `C4` §2.3/§3.3, `C7` §9 | `taxonomy`, `C4` §2.3, `C7` §9 | `taxonomy`, `C4` §2.3/§3.3, `FAILOVER_ENGINE_ARCHITECTURE.md`, `OP_2_0` phase doc, `C7` §9 | `UI2_0_BASELINE_CONTRACT.md` §2/§4 only |
| Blast-radius honesty | most accurate to `C7`'s actual (narrow) scope | understates (borrows class 1's low-risk framing) | overstates relative to `C7`'s narrowed scope; correct relative to `BAA` §8's original framing | N/A — nothing ships |
| Time to restore shipping | new contract + `OP.2`-adjacent review, but scoped only to restore | fastest to a taxonomy edit, but does not remove the real gate (§9.2 stays open either way) | slowest — blocked on `OP.2` infrastructure built for a different purpose | restore does not ship this release |

---

## 4. Is a restore write distinguishable from `CLASS_3_CONFIGURATION_WRITE`?

**Yes, on one specific criterion: artefact provenance, not operation
shape.** At the level of "bytes are written to a device that change its
configuration," a restore write and a `CLASS_3` configuration write are
identical — this is exactly why `BAA` §8 called restore "a `config-write` at
the highest blast radius available" and why naively admitting it under
`CLASS_1` (Option B) or inventing a new class without stating this
distinction (Option A done carelessly) would be misleading the Product
Owner about what is actually being authorized.

The distinguishing criterion `C7` establishes, and this document makes
explicit because `C7` itself only implies it: **a restore write's content
is never operator-authored.** Every restore this product's `C7` design can
ever execute is bound, by construction, to:

1. an existing `backup_artefact` row (§5.2's `source_artefact_id` is a
   mandatory foreign key — there is no "restore this text I am typing"
   path, no free-command authoring, no browser-composed configuration
   payload anywhere in `C7`'s model);
2. that artefact's own validation record at `V2` (`WELL_FORMED`) or higher
   (§5.3 check 2 — a `V1`-only or `FAILED` artefact is refused restore
   unconditionally, before any approval is even requested);
3. the **same device identity** the artefact was collected from (§5.3
   check 3 — target identity match is enforced at compile time, with no
   override path; "restoring artefact X onto device Y is never offered as
   a choice, not even behind an override"); and
4. (for version-locked classes) the **same software version** currently
   running on that target (§5.3 check 4).

A `CLASS_3` configuration write, by definition and by every existing use of
that term in this repository (`utils/action_taxonomy.py`: "Configuration /
object / policy-rule modification," prohibited outright), is
**operator-authored or operator-selected content applied to a device the
operator chooses at the time of the write** — a rule change, an object
edit, a policy install. Nothing in `C7`'s restore model permits that: there
is no step, field, or UI surface anywhere in `C7` §2–§6 that lets an
operator supply or select the *content* of what gets written; the operator
can only select *which prior, already-verified snapshot of the device's own
past state* to reapply, to the identical device it came from.

This is the same distinction `BAA` §8's original "config-write at the
highest blast radius" framing did not yet have available to it, because
`BAA` §8 predates `C7`'s artefact-provenance model (manifest binding,
`V2`+ validation floor, identity/version match) — `BAA` §8 was written when
restore was "designed but not buildable" at all, with no concrete
provenance chain to point to. `C7`'s subsequent design gives this document
a criterion `BAA` §8 could only gesture at: **provenance-bound replay of the
device's own prior verified state is not the same operation as
operator-authored configuration change**, even though both are, at the
byte level, a device configuration write.

**What this argument does not claim.** It does not claim restore is
*safe* in the sense of being low-blast-radius in its *failure* modes — §5.7
is explicit that an `OUTCOME_UNKNOWN` restore can leave a device
"partially applied," genuinely as consequential as a failed failover. It
does not claim the write should therefore be trivially admitted (that is
exactly what Option B's risk section warns against). It claims only that
restore's write is **categorically distinguishable from `CLASS_3` on
provenance**, which is the fact the Product Owner needs in order to decide
among §3's options without being misled into thinking "any of these options
reopens the door to arbitrary configuration writes" — none of them do;
`CLASS_3` stays prohibited outright under every option in §3.

---

## 5. Recommendation

**Recommended: Option A — a new, narrowly-scoped class between `CLASS_1`
and `CLASS_2`.**

**Argued.** Option D (stay disabled) is always available to the Product
Owner regardless of this recommendation — it requires no taxonomy work at
all and is compatible with deferring the decision entirely. Given that
`RESTORE-IN-RELEASE-1` (D-7) was an explicit, PO-ratified commitment "Any
release without restore is named 'platform pilot'... High risk strengthens
the contract; it is not a reason to drop the promise," this document treats
D-7 as still standing and evaluates A/B/C against each other as the paths
that actually deliver it, per the relay packet's own framing.

Between A, B, and C: **Option B understates restore's real blast radius**
by borrowing `CLASS_1_RECOVERY_WRITE`'s existing low-risk framing for an
operation `BAA` §8 and `C7` §5.7 both independently describe as capable of
leaving a device in a partially-applied, unknown configuration state — the
same category of consequence `CLASS_2` failover carries. Recommending
Option B would repeat, at class-1's boundary, exactly the "cannot tell a
Gaia backup apart from a failover" failure `utils/action_taxonomy.py`'s own
module docstring was written to prevent (its `RECOVERY_OPERATIONAL_WRITE_
LEDGER.md`-gated admission model — cadence ceiling, no per-operation human
approval — is a real safety mechanism tuned for a bounded-risk operation;
folding in a device-config-replacing write under the same class label
without changing that mechanism would misrepresent the class's own meaning,
not merely its docstring). **Option C over-corrects**: it inherits the full
`OP.2` prerequisite bar — including infrastructure (`utils/operate/`, the
`authorize()` boundary, `DEPLOY.1A`) that does not yet exist and is being
built for an unrelated purpose (failover) — meaning restore's delivery
becomes hostage to failover's own schedule and organizational review, with
no restore-specific justification for that coupling beyond `BAA` §8's
pre-`C7` framing. §4 above establishes that restore's write is narrower
than an arbitrary configuration write specifically because of artefact
provenance; Option C does not use that narrower fact anywhere in its own
gating model, and gains nothing from it that Option A does not also gain
via its own dedicated, restore-scoped admission path.

**Option A is the only one of the three that gives restore's write a class
whose scope statement is actually true of what `C7` specifies**: not "backup
creation" (Option B's borrowed scope), not "operational state change /
failover" (Option C's borrowed scope), but "a provenance-bound, per-target,
per-operation-approved device write of a previously-verified artefact back
to its own originating device" — precisely and only what §4 argues is
distinguishable from `CLASS_3`. It costs real new surface (a new class
member, a new `C4` step kind, a new ledger design, new tests) that Option B
avoids and Option C partially reuses from `OP.2`'s eventual build-out — but
that cost buys an admission gate that is honest about, and scoped to,
restore's actual risk shape, which is what `AGENTS.md`'s evidence and
identity laws require of any new safety boundary.

### Follow-up amendment list (files, sections, tests)

The Product Owner selected Option A in relay seq 3. A separately authorized
successor movement executes the
following, in this order (each item is a `C4`/`C7`/taxonomy-successor
amendment per §2's constraints — none of them ships in this movement):

1. **`utils/action_taxonomy.py`** — add the new `ActionClass` member
   `CLASS_1B_CONTROLLED_RESTORE_WRITE` with non-renumbering `level=1.5`
   (relay seq 5), with `permitted=True` gated on the
   new admission contract from item 3, `console_submittable=False`,
   a `refusal_code` distinct from `CLASS_1`'s and `CLASS_2`'s.
2. **`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`**
   §2.3 — add the new device-directed write step kind (e.g.
   `restore_push`, selected by the Option A amendment bundle), with its own expectation-gate semantics (transfer
   integrity, not merely "bytes sent"); `sftp_put` itself stays reserved/
   refused, unmodified. §3.3 step 7 — restate the "class 2/3/4 never
   `SIGNED_OFF`" rule so the new class is narrowly `SIGNED_OFF`-eligible
   for restore-scoped capability rows only (relay seq 7), while runtime
   admission remains a separate `C2` predicate.
3. **New design document** (working title:
   `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` or a `C7`-successor
   addendum) — the new class's own admission contract: the per-target
   (not per-24-hour-cadence, per `C7` §5.3's note) ledger/ credential/
   allowlist model, mirroring `RECOVERY_OPERATIONAL_WRITE_LEDGER.md`'s
   fail-closed-on-unreadable precedent but scoped to restore's actual
   admission needs: the separately amended check 1 plus new
   `C7_RESTORE_NO_UNRECONCILED_PRIOR` check 7 at compile time; `C2` §6
   checks 2 and 4 at claim; `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` through
   the registry/allowlist boundary before approval and at claim; and §6.2's
   per-operation approval record. ClusterXL member and VSX-context restore
   remain unsupported. This does not reuse `RB.x`'s cadence ceiling, which
   `C7` §5.3 states is `NOT_APPLICABLE` here.
4. **`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`** §9.1
   and §9.2 — mark resolved, referencing this document and the new class/
   kind by name; §9.3 — mark resolved (both halves closed together).
5. **`docs/design/UI2_0_BASELINE_CONTRACT.md`** §2 — record the Product
   Owner's decision (this is the recording act this document itself
   requires before restore may be implemented — see §6).
6. **`tests/test_architecture_convergence.py`** —
   `test_the_five_classes_exist_and_are_ordered` (level list gains the new
   member), `test_recovery_write_and_operational_state_change_are_
   distinct_classes` (add an equivalent assertion distinguishing the new
   class from both neighbors), `test_only_class_0_is_console_submittable`
   (assert unaffected if the new class stays `console_submittable=False`,
   update if the assertion form needs the new member named explicitly),
   `test_every_console_job_type_maps_to_a_taxonomy_class` and
   `test_no_console_job_type_is_class_2_or_above` (extend or add a sibling
   assertion excluding the new class from console submission the same
   way). New test file for item 3's ledger contract.
7. **Java implementation** (`ui2/` — out of scope for every document named
   above; only begins after items 1–6 land, per §6).

---

## 6. What stays true regardless of which option is chosen

- **Restore stays disabled and unimplementable in Java** until the Product
  Owner's relay-selected Option A is recorded in
  `docs/design/UI2_0_BASELINE_CONTRACT.md` §2 as a new row alongside
  `RESTORE-IN-RELEASE-1` and the other Phase 0 decisions. This document's
  own existence and the relay choice do not substitute for that frozen-
  baseline recording act.
- **No Java implementation of restore, and no `ui2/` source referencing a
  restore capability, lands before the chosen option's amendment list is
  executed** — for Option A, the six items in §5's follow-up list, in
  order; for Option B or C, the analogous items named in their own rows of
  §3; for Option D, no implementation happens at all this release.
- **`CLASS_3_CONFIGURATION_WRITE` and `CLASS_4_POLICY_DEPLOYMENT` remain
  prohibited outright**, unaffected by any option in §3 — none of the four
  options touches those two classes' `permitted=False` status, and §4's
  distinguishability argument is precisely what keeps that true even under
  Option A/B/C's new or widened admission path.
- **`utils/action_taxonomy.py`, every FROZEN document named in §2, and
  every test file named in §5/§3 remain unmodified by this movement** —
  this document is the only artefact this movement produces, per the relay
  packet's own scope boundary.
- **The `OP.2` prerequisite bar (§2 constraint 5) is untouched** by any
  option — even Option A's narrower class still requires its own
  from-scratch admission contract to be built and reviewed before restore
  executes anywhere; nothing in this document shortens that path, it only
  names which shape that contract should take.

---

## 7. Cross-references

- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN)
  — §5 (restore execution), §6 (approval model), §9.1–§9.3 (this document's
  problem statement, quoted verbatim in §1).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  (FROZEN) — §2.3 (closed step-kind set, `sftp_put` reservation), §3.3
  (resolution algorithm, step 7's class 2/3/4 never-`SIGNED_OFF` rule),
  §3.5 (execution-eligible view).
- `utils/action_taxonomy.py` — the five action classes; not edited by this
  document; every option in §3 states its exact proposed edit for a
  successor movement.
- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §8 — restore's original
  "config-write at the highest blast radius... sits at the `OP.2` bar"
  framing, the historical source of §4's distinguishability question;
  superseded in scope (not in severity) by `C7`'s narrower artefact-
  provenance model, per §4 above.
- `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1 and
  `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` —
  `CLASS_2`'s own prerequisite bar and current state (no member, `DENY`
  unconditional), the precedent Option C would fold restore into.
- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — §2
  `RESTORE-IN-RELEASE-1` (D-7, not reopened), `APPROVAL-MODEL` (D-2c, not
  reopened), the row this document's §5/§6 requires the Product Owner's
  decision be recorded in.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §4.1 — the
  closed six-role vocabulary, referenced in §2 constraint 7; no option in
  §3 mints a new role token without a `C3`-successor amendment.
- `docs/design/UI2_0_C5_AMENDMENTS_BUNDLE.md` — the precedent document for
  how a taxonomy amendment (`UI-OPERATIONAL-RUN-NOW`) was previously
  written up once decided; the successor movement in §5 would follow the
  same pattern for whichever option is chosen.
- `tests/test_architecture_convergence.py` — the taxonomy's test-enforced
  boundaries named throughout §2/§3/§5; not edited by this document.
- `AGENTS.md` — "Network action taxonomy" (no new class 2/3/4 command),
  "Architectural invariants," evidence and identity laws (§4's provenance
  argument applies the same discipline those laws require of device
  identity to an artefact's provenance instead).
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` — this
  movement's `SESSION_START`, acceptance criteria `AC-1`–`AC-8`, and scope
  boundary.

---

## 8. Contradictions and open items for the Product Owner (`AC-7`)

**No contradiction found.** This document is additive: it reports options
against `C7`'s own already-frozen §9.1–§9.3 gap report and does not dispute
any FROZEN document's content.

**Documents checked for contradiction, by name and status:**

| Document | Status | Engaged how |
|---|---|---|
| `docs/design/UI2_0_BASELINE_CONTRACT.md` | FROZEN — PRODUCT OWNER APPROVED, 2026-09-09 | §2 constraint 6 (D-7, D-2c not reopened); §5/§6 name the exact row a decision must be recorded in |
| `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` | FROZEN | §1 (verbatim problem statement), §5–§6 (restore execution/approval model, referenced throughout §3–§4), §9 (the gap this document addresses) |
| `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` | FROZEN — PRODUCT OWNER APPROVED, 2026-09-09 | §2.3 (closed step-kind set), §3.3 (resolution algorithm, step 7), §3.5 (execution-eligible view) — quoted/cited in §1, §2, §3, §5 |
| `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` | referenced by `C7` as FROZEN sibling | Not independently re-derived here; `C7`'s own citations of `C2` §3/§6/§8 (job state machine, pre-execution checks, `OUTCOME_UNKNOWN`) are taken as given, unaffected by any option in §3 |
| `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` | FROZEN | §4.1 role vocabulary — §2 constraint 7 |
| `docs/design/UI2_0_C5_AMENDMENTS_BUNDLE.md` | FROZEN sibling of C1–C4/C6 | Cited in §7 as the precedent format a successor amendment would follow; not itself amended here |
| `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` | design document (pre-`C7`) | §8 quoted/analyzed in §4 and §7; its "config-write at the highest blast radius" framing is treated as historically accurate but superseded in scope by `C7`'s narrower artefact-provenance model, not as contradicted |
| `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` | FROZEN (architecture); `OP.2.1` gate DRAFTED | §10/§10.1 — Option C's precedent and cost; not amended here |
| `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` | FROZEN (2026-09-04); `OP.2.A`/`OP.2.B` IMPLEMENTED, `OP.2.1` DRAFTED | §10.1's safety contract items, cited in §2 constraint 5 and Option C's row; CLASS 2 confirmed to have "no member... unconditional `DENY`" as of this document's drafting |
| `utils/action_taxonomy.py` | source, test-enforced | Read in full; every option in §3 states its exact proposed edit; not edited by this document |
| `tests/test_architecture_convergence.py` | source, test-enforced | Read in full; every taxonomy-affecting assertion named in §2/§3/§5; not edited by this document |
| `AGENTS.md` | durable constitution | "No new class 2/3/4 command," "Architectural invariants," evidence/identity laws — §2 constraint 2, §4's provenance argument, §7 |
| `AI_START_HERE.md` | cold-start entry point | Action taxonomy table, product maturity axis — background for §1/§2 |
| `project/backlog.json` | project-state | `ui2_taxonomy_device_write_class_and_step_kind` entry — updated by this movement (see build/backlog record accompanying this document) |
| `CURRENT_STATE.md` | hot checkpoint | `OP.2.0` status line ("no member, no adapter, unconditional `DENY`") — cited in Option C's row and §7 |

No document above required its own content to change for this document to
be internally consistent; every citation is read-only.
