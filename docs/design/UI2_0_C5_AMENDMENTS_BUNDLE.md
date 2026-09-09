# UI 2.0 — B0/C5: amendments bundle

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09), §5, against the four merged sibling contracts `C1`–`C4` (each
DRAFT — FOR PRODUCT OWNER FREEZE) and `docs/design/
UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE, revision 2). This is the last of
`C1`–`C5` to close before the platform-contract freeze (baseline §6).

---

## 1. Scope

This document is the single place that states, verbatim, every textual
amendment `C1`–`C4` already require of documents **outside** themselves.
`C1`–`C4` decided nothing new here; this movement writes the amendment text
their own decisions imply, into the source documents' own vocabulary. The
five items are baseline §5's literal table of contents, unchanged and not
re-argued:

| # | Item | Where |
| --- | --- | --- |
| 1 | `CON.0` — separate shell (approved) | §2 below |
| 2 | `utils/action_taxonomy.py` — `UI-OPERATIONAL-RUN-NOW` (job request ≠ console submission) | §3 below |
| 3 | `UI2_0_ARCHITECTURE_DESIGN.md` §6 — `APPROVAL-MODEL` replaces per-run four-eyes with version/schedule four-eyes plus per-operation approval for restore and controlled failover | §4 below |
| 4 | `UI2_0_ARCHITECTURE_DESIGN.md` §3.2/§8.5 — ladder replaced by `CAP-*` states; `AG-U2` restated as one Java execution path | §4 below |
| 5 | `AGENTS.md` — deferred: browser profile editor (free-command) and mediated terminal, only when Phase S opens | **out of scope**, stated for completeness only (context_not_loaded; Phase S) |

Items 2, 3 and 4 are applied directly to their target files in this same
movement (documentation/comment-only changes; no behavioral code change —
§3, §6). Item 1 is analysed in §2: the text that would need to change lives
in a **FROZEN** document (`OPERATOR_CONSOLE_ARCHITECTURE.md`) outside this
movement's edit scope, so §2 states precisely what that text is and defers
its actual application to the Product Owner's own freeze act. Item 5 is not
written here at all — it is named in the baseline only to record that it is
deliberately excluded, per Phase S scoping.

---

## 2. `CON.0` — the separate-shell amendment

**Decision cited.** Baseline §2, row `CON.0-AMENDMENT` (C-1): **ACCEPTED**
(2026-09-09, morning). The decision itself is settled; this section states
what document text it requires.

**`OPERATOR_CONSOLE_ARCHITECTURE.md`'s actual current status and text.** The
document's own status line reads `ARCHITECTURE FROZEN 2026-08-31`. Two
clauses are engaged by UI 2.0 existing as a separate shell:

- §6 ("Two delivery modes, one UI source"), closing invariant (current text,
  verbatim): *"Invariant: **the console never introduces a payload shape the
  exporter does not also produce.** If the console needs a field, the
  builder gains it and both surfaces get it. Enforced by an equality test in
  `CON.1` (AC-4 there)."*
- §3 ("What this is not"), the row: *"A frontend framework / bundler \|
  Breaks the 'one portable inline script, no build step' invariant just
  frozen \| `CODEBASE_MODULARIZATION_FRONTEND.md` D-MOD1"*

Both clauses, read literally, would forbid UI 2.0 from existing at all: a
byte-identical payload-shape rule and a no-framework/no-bundler rule both
presuppose one shared UI source, which the separate-shell decision (baseline
§1 items 1–2, design §4) explicitly ends.

**What text needs updating, precisely, and why nothing more does.**
`UI2_0_ARCHITECTURE_DESIGN.md` §4.3 already drafted the full replacement
text for exactly these two clauses — `UA-1` (§6's invariant → a
**projection-parity** invariant: every surface reads the same persisted
projections; no surface computes a fact from a source another surface
cannot read; wire shape, declared action set and liveness may differ, but
`primary_status`/`capability_qualifiers`/`evidence_presentation` may not,
per `AC-CS-39`) and `UA-2` (§3's row → narrowed to bind only **the shared
report bundle**, not a separate application that never ships that bundle).
Both are marked **PROPOSED**, status recorded in design §11 rows `UA-1`/
`UA-2`, and design §4.3 gives the literal replacement wording in full —
reproduced here by reference rather than duplicated, per `AGENTS.md`
"Handover economy" ("never copy the same paragraph into three files").

No other clause of `OPERATOR_CONSOLE_ARCHITECTURE.md` needs a text change
for the separate-shell fact alone: §4 (intent boundary), §4.1 (typed
enrollment intent), §7 rules 4–10 (credential/trust/audit rules) and §9
(honest affordances) are inherited by UI 2.0 verbatim (design §4.4) and
need no amendment; §7 rules 1–3 (loopback, per-launch token, data-free
shell) are superseded for UI 2.0 by design §7's own network-exposed/LDAP
session model, which is itself the `DEPLOY.1A`-class decision `CON.0` §7.1
already reserves — recorded as `UA-8` in design §11, not a `CON.0` text
edit. `PCP_STORAGE...` and `PCP §11`/`PCP §13` amendments belong to items 3
below and design §6.9/§4.3's companion note respectively, not to `CON.0`
itself.

**Why this movement does not edit `OPERATOR_CONSOLE_ARCHITECTURE.md`
directly.** The file is `ARCHITECTURE FROZEN`; per `AGENTS.md` "Contract-
status law" and "Authority hierarchy" item 2, amending a `FROZEN` document's
own text is itself a Product Owner freeze act, not a documentation edit a
`CONTRACT`-tier movement performs unilaterally — and this movement's scope
(`.nexus/approved_task.json` `scope.in`) does not list the file. **AC-1 is
satisfied by this section stating precisely what changed conceptually
(nothing — `CON.0`'s decision text is unaltered) and precisely what text
*would* need updating and where that text already exists** (design §4.3's
`UA-1`/`UA-2`), not by this movement performing that edit. Applying `UA-1`/
`UA-2` to `OPERATOR_CONSOLE_ARCHITECTURE.md`'s own file is recorded as an
open item for the Product Owner in §7 below.

---

## 3. `utils/action_taxonomy.py` — the `UI-OPERATIONAL-RUN-NOW` amendment

**Decision cited.** Baseline §2, row `UI-OPERATIONAL-RUN-NOW` (D-2b):
**ACCEPTED** — "the UI creates an *authorised job request*; the Java worker
executes it... This is a **taxonomy amendment**: class-1 actions remain
non-submittable from a console; a Run Now is a job request, not a console
submission." Invariant (task directive, verbatim): the amendment "must
preserve the existing law that class-1 (operational-write) actions are
never console-submittable... make explicit that a Run Now is a JOB REQUEST
subject to the full job-execution/gate chain (C2/C3), never a direct
console-submitted command — do not weaken the existing class-1 rule."

**Whether a new enum member or field value is needed — decided, one
sentence.** No: `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §2.2/§7.3
(already merged, DRAFT — FOR PRODUCT OWNER FREEZE) explicitly builds the Run
Now job record against the **unchanged** `CLASS_1_RECOVERY_WRITE.console_
submittable = False` flag ("There is no browser-executes-directly path and
no console-submission path for a class-1 Run Now... unchanged, design
§6.6"), so the ambiguity the task directive flagged is already resolved by
a sibling contract this movement reads, not reopened here — no `RELAY_
QUESTION` is needed.

**Diff/patch description against the real file (applied this movement,
`utils/action_taxonomy.py`).** Documentation/comment only; no dataclass
field value changed:

1. A new module-docstring subsection, **"UI 2.0 amendment — a job REQUEST is
   not a console SUBMISSION"**, inserted after "Relationship to the legacy
   term". States: `console_submittable` continues to mean "may this class's
   *execution* be triggered directly and synchronously from a browser
   request"; a Run Now creates a queued `REQUESTED` job row with no device
   contact from that request, a worker claims it and re-checks approval
   before executing under the unchanged `RB.x` contracts, and no route
   accepts a command or executes a step synchronously inside the HTTP
   cycle. States explicitly that no new `ActionClass` member is needed and
   cites `C2` §2.2/§7.3 as the sibling contract that already builds against
   this.
2. A four-line comment directly above the `CLASS_1_RECOVERY_WRITE =
   ActionClass(...)` construction, cross-referencing the docstring
   amendment and stating the flag is unchanged, so a future reader sees the
   amendment at the exact point they would otherwise expect a new member.

**Field-by-field: nothing in `CLASS_1_RECOVERY_WRITE`'s five fields
(`id`, `level`, `label`, `permitted`, `console_submittable`, `refusal_code`,
`why`) changed.** `permitted=True` (unchanged — a Run Now of an approved
profile is still permitted, exactly as a scheduled run already is);
`console_submittable=False` (unchanged — the invariant the task directive
names); `refusal_code="recovery_write_not_console_submittable"` (unchanged
— still returned for the case that remains refused: an unapproved profile
or target, per §4 below); `why` text (unchanged verbatim, per the AC-2
requirement).

**AC-2's regression test.** `tests/test_architecture_convergence.py`
already asserts, and continues to pass unchanged after this edit (run
2026-09-09, see §6):

- `test_recovery_write_and_operational_state_change_are_distinct_classes` —
  `CLASS_1_RECOVERY_WRITE.permitted is True`, distinct refusal code and
  level from `CLASS_2_OPERATIONAL_STATE_CHANGE`.
- `test_only_class_0_is_console_submittable` — `submittable == [CLASS_0_
  READ]`, i.e. `CLASS_1_RECOVERY_WRITE.console_submittable` is confirmed
  `False` on the taxonomy itself.
- `test_no_console_job_type_is_class_2_or_above` — `CLASS_2_OPERATIONAL_
  STATE_CHANGE` still has no console job type (no member exists at all).
- `test_configuration_write_and_policy_deployment_stay_prohibited` —
  classes 3/4 unchanged.

These are the "existing... regression test" AC-2 asks for: they pin exactly
the two invariants (class-1 permitted-but-not-console-submittable; class-2
membership empty) a docstring-only edit cannot silently weaken, and they
were re-run green against the amended file (§6).

---

## 4. `UI2_0_ARCHITECTURE_DESIGN.md` — the design-document amendments

Three amendments, all applied directly to the file this movement (target
scope includes it):

### 4.1 §6 — `APPROVAL-MODEL` replaces per-run four-eyes

**Decision cited.** Baseline §2, row `APPROVAL-MODEL` (D-2c): four-eyes on
**profile creation/change** (each version) and on **schedule
authorisation**; a **routine run inside an approved scope — including Run
Now — needs only the `D7` role and a mandatory reason, no second human**;
**restore, and later controlled failover, need per-operation independent
approval**; "this differs from design §6 ('four-eyes approval' per run) and
is an explicit amendment to it."

**What design §6 said before this amendment (the actual defect).** §6.1's
operating-model paragraph: *"execution is a class 1 typed job under the
`RB.x` ledger, credential and allowlist contracts, run by the scheduler or
the worker — **never started by a browser request**."* §6.6's reconciliation
table, "back up now" row: *"— **does not exist as a browser action** \|
class 1 \| refused at `E3` with `recovery_write_not_console_submittable`,
always, every role \| —"*. §6's "three consequences", item 1: *"'Back up
now' from the UI is out, deliberately... If the Product Owner later wants an
on-demand run from the browser, that is a taxonomy amendment... not
recommended here."* §13's `AG-J10`: *"'Back up now' is refused on every HTTP
route for every role..."* All four are the same "per-run refusal" framing
the baseline row names, in the document's own words — an unconditional
refusal for every role, not a role/reason gate on an approved scope. §6.7's
schedule-edit condition table required only `D7` role + mandatory reason for
every schedule edit including the first enable, with no second-approver
condition anywhere — the "schedule authorisation" half of D-2c's four-eyes
requirement was simply absent.

**Replacement text applied (verbatim as committed to the file).**

- A callout at the top of §6 stating the amendment and pointing to each
  affected subsection (§6.1, §6.6, "three consequences", §6.7, `AG-J10`),
  so a reader of any one of them sees the amendment is in force.
- §6.1: *"...run by the scheduler or the worker. **Amended
  (`UI-OPERATIONAL-RUN-NOW`, §6.6):** a browser request may create a Run Now
  **job request** for an approved profile against an approved target — it
  never executes a step or contacts a device itself; the worker claims and
  executes the queued request under the identical rules as a scheduled
  run."*
- §6.6's reconciliation table: the "back up now" row is replaced by a
  "Run Now" row: typed job request `{profile_id, version, device_id[],
  reason}` creates a `REQUESTED` row only; class 1 **job request**, not a
  console submission (`console_submittable` stays `False`, §3 above); gate
  is `D7` `role:backup_admin` + mandatory reason, **no second approver**,
  for an already-approved profile/target; refused with the original
  `recovery_write_not_console_submittable`-class reasoning **only** when
  the profile version or target is not yet approved; approval is re-checked
  fresh at claim time, never assumed from request time (`C2` §6/§7.3). The
  preceding paragraph ("every browser-originated request is class 0 or a
  policy/intent write...") is amended to add the third case: a class 1 job
  request that contacts no device itself.
- "Three consequences" item 1 replaced: Run Now for an approved
  profile/target requires `D7` + reason (no second approver) and queues a
  job; the unconditional refusal (with next-scheduled-run and connectivity-
  check alternative) survives only for the **unapproved** case.
- §6.7 gains a new condition row, **"Schedule authorisation is four-eyes
  (amended, `APPROVAL-MODEL`, D-2c)"**: the intent that first **enables** a
  schedule, or widens an already-enabled schedule's device set or shortens
  its interval, requires a second `role:backup_admin` (`authorized_by ≠
  created_by`, the same mechanism as profile-version approval, §6.5); a
  **disable** or a **narrowing** edit needs only `D7` + reason — it can only
  reduce unattended contact. A closing note states a routine Run Now is
  deliberately not in this table: it does not touch `BackupSchedule` and
  needs no schedule authorisation, only `D7` + reason, and cross-references
  `C2` §7.2's owner/approver/execution-identity split.
- §13's `AG-J10` replaced: no HTTP route ever executes a class 1 step
  directly or synchronously; a Run Now route only ever inserts a
  `REQUESTED` row under `D7` + reason for an approved profile/target,
  re-checked at claim time; an unapproved Run Now is refused with the named
  reason for every role.

**Restore / controlled failover.** Design §6 does not itself specify restore
(that is `C7`'s scope, per baseline §5 amendment-list scope and this
movement's own `context_not_loaded`); the replacement text names "restore
and, later, controlled failover need per-operation independent approval" as
`C7`'s obligation, satisfying AC-3's requirement that the replacement text
match D-2c exactly **without** this movement deciding `C7`'s own restore
approval mechanics — that stays open, correctly, for `C7`.

**AC-3 self-check.** The applied text states, in this document's own words:
profile-version four-eyes (§6.5, unchanged) + schedule-authorisation
four-eyes (§6.7, new row) + routine-run (including Run Now) = `D7` role +
reason only, no second approver (§6.1/§6.6/"consequences"/`AG-J10`) +
restore/controlled-failover = per-operation independent approval, owned by
`C7` (§6 callout). All four clauses of D-2c are present; none is softened
or re-scoped beyond what D-2c states.

### 4.2 §3.2/§3.3/§8.5 — ladder replaced by `CAP-*` states; `AG-U2` restated

**Decision cited.** Baseline §5 item 4; `UI2_0_DEVELOPMENT_WORKFLOW.md` §2
row 1 ("Withdrawn. The ladder collapses to capability maturity states:
`CAP-SPEC` → `CAP-OFFLINE` → `CAP-VALIDATED` → `CAP-RELEASED`... Feature
slices are named `REL-*` so maturity and slice labels never collide") and
row 2 ("`AG-U2` is re-stated as 'one job-execution path, in Java, test-
enforced'").

**What design §3.2 said before (the actual defect).** The section, titled
"The per-feature migration ladder", defined `F0`–`F5` with `F2` = "Python
writes; Java reads", `F3` = "Python executes; Java submits" (a `main.py
--worker` PostgreSQL job-queue consumer in Python claiming Java-submitted
jobs), and `F4` = "**Both**: Java implementation exists and runs in shadow;
Python remains authoritative" with a parity proof between the two runtimes.
All three directly contradict `RUNTIME-DIRECTION` (baseline §2, D-1: "no
Python in the UI 2.0 runtime") and workflow §2's explicit withdrawal of
exactly this ladder, `main.py --worker`, and the `F4` shadow-run model.
§8.5's worked example walked a real feature (`CE.2`) through this same
withdrawn ladder in full, including the `F3` Python-worker-executes step
and the `F4` Python-vs-Java parity proof.

**Replacement text applied.** §3.2 is retitled "Capability maturity states"
with a callout stating the withdrawal and citing workflow §2; the `CAP-SPEC`
→ `CAP-OFFLINE` → `CAP-VALIDATED` → `CAP-RELEASED` table replaces the `F0`–
`F5` table, each state mapped to its producing movement (workflow §3.3's
Extract/Implement/Validate/Integrate) with **no fifth state invented**
(AC-4) and the `REL-*` slice/maturity-state non-collision stated explicitly,
matching workflow §2 row 1's own wording. The six "rules that make the
ladder safe" are restated 1:1 against the new states (entry condition,
resting states, no state-skipping, spec-is-the-contract, no doubled device
contact, CLI parity survives) — same intent, no Python execution leg. A new
paragraph restates `AG-U2` exactly as workflow §2 row 2 states it: "one
job-execution path, in Java, test-enforced." §3.3's worked table for three
features is re-labelled in the same terms (`CAP-SPEC`/`CAP-OFFLINE`/
`CAP-VALIDATED`/`CAP-RELEASED` instead of `F1`/`F2`/`F4`/`F5`), preserving
each row's actual content. §8.5 is amended in place (callout + rewritten
entry-condition/`CAP-SPEC`/`CAP-OFFLINE` narrative replacing the `F1`/`F2`/
`F3` walkthrough; the `F4` "shadow, Python authoritative" step is removed —
there is no shadow state to walk through) and its closing proof table is
rewritten so the "real-environment" proof is the Java capability's own
result, never a Python-vs-Java comparison (there is no Python run to
compare against), consistent with workflow §2 row 3's replacement of the
`F4` parity model with "fixtures extracted from real captures... plus a
PO-run real-environment validation."

**Known residual scope (not fixed this movement, flagged in §7).** Several
other sections of the same DRAFT document (§3.1's `F2`/`F3` mention, §8.1–
8.3's extended `F1`–`F5` discussion, §12's `U-J5`/Phase-2 rows, `AG-J12`,
§14's "`CONTRACT` draft for `F2`") still use the withdrawn ladder's
vocabulary. Baseline §5 item 4 names §3.2 and §8.5 specifically; those two
are fully amended above. The remaining occurrences are a **known,
documented inconsistency** in a `DRAFT` (not `FROZEN`) document, reported
as an open item in §7 rather than silently swept — a full-document
terminology pass is a larger textual-consistency movement than this
bundle's "targeted section replacements" scope (`.nexus/approved_task.json`
`output_contract` item 1) and risks scope creep into unrelated sections
this movement did not audit line by line.

**AC-4 self-check.** The `CAP-*` table's four states and their names are
copied verbatim from workflow §2 row 1; no additional state name appears
anywhere in the replacement text.

---

## 5. `PRIVACY_AND_DATA_HANDLING.md` — the UI 2.0 database amendment

**Decision cited.** `C1`'s own open item #2 (`UI2_0_C1_PLATFORM_SCHEMA_
CONTRACT.md` §10): *"`PRIVACY_AND_DATA_HANDLING.md` does not yet name UI
2.0's database... the source document itself should be amended to name UI
2.0 explicitly."* Backlog item `ui2_privacy_doc_amendment` (`project/
backlog.json`): *"Amend `PRIVACY_AND_DATA_HANDLING.md` to name the UI 2.0
(PostgreSQL) database and its data classes... Fold into the C5 amendments
bundle."*

**Text applied.** A new section, **"UI 2.0 database (Java product line)"**,
inserted immediately after the existing "Distributed evidence store
(DEV.3.3, opt-in)" section (same document, same pattern, so a reader
comparing Line-1's opt-in instance against UI 2.0's mandatory one sees them
adjacent). States, citing `C1` §2/§4/§7 by name:

- the instance is **mandatory**, not opt-in (the one difference from
  `DEV.3.3`'s framing, stated explicitly so the two sections are not
  conflated);
- the same dedicated-instance / TLS-DSN / restricted-role / encryption-at-
  rest rules `DEV.3.3` already states, applied here by the same rationale
  `C1` §7 already used ("architecturally equivalent to local disk, not a
  CLASS 1/shareable artifact");
- the entire instance is **CLASS 2**;
- names, by table, the three data classes AC-5 requires: **`audit_log`**
  (append-only mutation record, indefinite retention), **`provenance_
  records`** (evidence-linkage to discarded raw output, bounded sanitized
  fragment where permitted), **`secrets_metadata`** (credential/trust
  references, never a secret value); and states every remaining table
  (`jobs`/`job_steps`, capability projections, control-plane rows, backup-
  artefact manifests) is CLASS 2 by the same instance-wide rule.

**AC-5 self-check.** The database is named (PostgreSQL, UI 2.0, mandatory);
`audit_log`, `provenance_records` and `secrets_metadata` are each named and
classed; no existing Line-1 rule in the document (runtime-directory policy,
raw-configuration handling, screenshot policy, source-code hygiene, `DEV.
3.3`'s own opt-in section) is altered — the new section is additive, placed
so it reads as a sibling of `DEV.3.3`, not a replacement of it.

---

## 6. Acceptance criteria

At least eight, each independently testable, restated from `.nexus/
approved_task.json`'s `acceptance_criteria` with the evidence produced this
movement:

1. **AC-1** (`CON.0` amendment states precisely what changed/why not, citing
   actual current text) — satisfied by §2 above: the verbatim current `CON.0`
   §3/§6 text is quoted, the amendment (`UA-1`/`UA-2`, already drafted in
   design §4.3) is cited by reference, and the reason no direct edit is made
   this movement (the file is FROZEN and outside this movement's edit scope)
   is stated.
2. **AC-2** (class-1 non-console-submittable rule preserved verbatim;
   regression test proves `DenyAllAuthorizer`/`CLASS_2` membership
   unchanged) — satisfied by §3: `CLASS_1_RECOVERY_WRITE`'s five fields are
   byte-for-byte unchanged (diff is additive comments/docstring only);
   `tests/test_architecture_convergence.py`'s four class-boundary tests
   re-run green (§3, §9 below runs them).
3. **AC-3** (`APPROVAL-MODEL` replacement text matches baseline D-2c
   exactly) — satisfied by §4.1: the applied text states all four D-2c
   clauses (profile-version four-eyes, schedule-authorisation four-eyes,
   routine-run/Run Now = `D7`+reason only, restore/failover = per-operation
   approval) without softening or re-scoping any of them.
4. **AC-4** (`CAP-*` states replacement matches workflow §2's table exactly,
   no fifth state invented) — satisfied by §4.2: the four states and their
   names are copied verbatim from the cited workflow row; verified by
   direct comparison, no additional state appears in the applied text.
5. **AC-5** (`PRIVACY_AND_DATA_HANDLING.md` amendment names the UI 2.0
   database and at least `audit_log`/`provenance_records`/`secrets_metadata`
   without contradicting Line-1's existing rules) — satisfied by §5: all
   three are named and classed; the new section is additive and does not
   edit any existing Line-1-scoped section of the document.
6. **AC-6** (every amendment traces to a specific citation; genuinely new
   decisions reported in §7, not silently made) — satisfied throughout §2–
   §5, each of which opens with "Decision cited" naming the exact baseline
   row, workflow row, or `C1`/`C2` section the amendment text is drawn from;
   §7 below lists every point this movement judged (not decided) while
   writing the text.
7. **AC-7** (if `action_taxonomy.py` required an actual code change, a
   targeted test proves it and full regression is unaffected elsewhere) —
   not triggered: §3 establishes the change is documentation/comment-only
   (no dataclass field value changed), so no new targeted test was required
   beyond re-running the existing four class-boundary tests (§9).
8. **AC-8** (§7 lists every contradiction with frozen authority found, or
   states none and which documents were checked; no PO-reserved decision
   reopened) — satisfied by §7 below.

---

## 7. Contradictions and open items for the Product Owner

**Contradictions with FROZEN authority: none found.** Documents checked
while writing this bundle: `AGENTS.md` (identity law, raw-evidence law,
UNKNOWN/fail-closed law, Git authority law, architectural invariants,
network action taxonomy), `AI_START_HERE.md` (the taxonomy table, reading
order), `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — every row of §2,
§5), `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (ARCHITECTURE FROZEN —
§3, §4, §6, §7, §9, read in full for the actual current text §2 quotes),
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (§2, §3, §5 Phase 0 agenda),
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT — §3.2, §3.3, §4.3, §6,
§8.4, §8.5, §11, §13, read before and after amendment),
`docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §4, §7, §10,
`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §2.2, §7.2, §7.3,
`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §1.3,
§8, `PRIVACY_AND_DATA_HANDLING.md` (in full, before amendment),
`utils/action_taxonomy.py` (in full), `tests/test_architecture_convergence.py`,
`project/backlog.json` (`ui2_b0_c5_amendments_bundle`, `ui2_privacy_doc_
amendment`, `ui2_b0_c1_platform_schema_contract` entries).

**No PO-reserved decision is reopened.** `UI-OPERATIONAL-RUN-NOW`,
`APPROVAL-MODEL`, `CON.0-AMENDMENT` and the `CAP-*` maturity model are
applied as already ruled (baseline §2); this movement chose only *where in
each target document's existing prose* to place the already-decided text
and *how to phrase the transition* from the withdrawn wording, per the task
directive's "decide with one sentence of reasoning unless genuinely
ambiguous" allowance — each such judgment call is listed below, not a new
ruling:

1. **No new `action_taxonomy.py` enum member** (§3) — resolved by reading
   `C2` §2.2/§7.3, which already builds the Run Now job record against the
   unchanged `console_submittable=False` flag; a `RELAY_QUESTION` would have
   asked a question a sibling contract already answered in-repository.
2. **"Schedule authorisation" (D-2c) interpreted as: first enable, or a
   widening edit, of an already-created schedule** — D-2c's own text names
   "schedule authorisation" without defining the exact edit boundary; this
   movement drew the boundary at "enable, or any edit that could increase
   unattended device contact" (widen device set, shorten interval) versus
   "disable, or any edit that could only decrease it" (narrow device set,
   lengthen interval, tighten window), reasoning that this is the natural
   reading of `C2` §7.2's own "the schedule's `authorized_by` actor" and
   §6.7's pre-existing "enable is stronger than edit" framing, and that a
   four-eyes requirement on every routine cadence tweak would conflict with
   D-2c's explicit "routine backup runs... do not need a second human each
   time" clause read broadly. **Flagged for explicit PO confirmation** — the
   boundary is a reasonable synthesis, not a re-derivation baseline itself
   states in so many words.
3. **`OPERATOR_CONSOLE_ARCHITECTURE.md` itself left unedited** (§2) — this
   movement's scope (`.nexus/approved_task.json` `scope.in`) does not list
   the file, and it is `FROZEN`; applying `UA-1`/`UA-2` to it is named here
   as the Product Owner's next mechanical step, not performed. **Open item,
   not a contradiction**: the amendment text already exists (design §4.3);
   only the act of writing it into the frozen file remains, gated on an
   explicit freeze decision the same way `C1`–`C4`'s own freeze is.
4. **Residual `F0`–`F5` vocabulary outside §3.2/§8.5** (§4.2) — baseline §5
   item 4 names exactly those two sections; §3.1, §8.1–8.3, §12, `AG-J12`
   and §14 of the same DRAFT document still read against the withdrawn
   ladder. Left unamended this movement, reported here rather than silently
   sweeping a wider scope than the baseline named. **Recommended follow-up**:
   a small `DOCS`-tier movement (Sonnet 5, normal) sweeping the remaining
   `F`-state mentions once `C1`–`C5` freeze, so the whole document reads
   consistently before `B1` implementation work cites it.
5. **`AG-J10`'s exact refusal wording amended, not only its scope** (§4.1) —
   the original text ("refused on every HTTP route for every role") is kept
   for the unapproved case and narrowed for the approved case; this is the
   direct, unavoidable consequence of applying D-2c, not a separate
   decision, but is called out because `AG-J10` was not itself named in
   baseline §5's amendment list (only design §6 was) — its amendment is a
   necessary corollary of §6's, included here for internal consistency
   (AC-6's "internally consistent with C1–C4" requirement) rather than left
   to silently contradict the amended §6.6 table two sections away.

**No other contradiction found.** In particular: `C2` §7.3's Run Now model,
`C2` §7.2's owner/approver/execution-identity split, and `C4` §1.3 item 7's
own note (design §6.3's illustrative sample and interactive-shell
assumption superseded by `C4` §2.4/§5, unrelated to this bundle's §6
amendment) are all read and found consistent with the text applied here —
no further change to `C1`–`C4`'s own documents was needed or made (this
movement edits only the SOURCE documents naming them, per its scope).

---

## 8. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — the authorizing
  contract; §2 rows `UI-OPERATIONAL-RUN-NOW`, `APPROVAL-MODEL`, `CON.0-
  AMENDMENT`; §5 the literal amendment list this document fulfils.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE rev 2) — §2 rows 1–2
  (ladder withdrawal, `AG-U2` restatement), §3.3 (movement pattern).
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (ARCHITECTURE FROZEN) —
  §2's subject; unedited by this movement.
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT, amended by this
  movement: §3.2, §3.3, §4.3 (cited, unedited), §6.1, §6.6, §6.7, §8.5,
  §13 `AG-J10`).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §4, §7, §10 — the
  privacy amendment's data-class source and its own flagged open item.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §2.2, §7.1–7.3 — the Run
  Now job-record model this bundle's §3/§4 text is written consistently
  with.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  §1.3 item 7, §8 — the design §6.3 sample/transport-assumption supersession
  noted as already resolved, unrelated to this bundle's own §6 amendment.
- `utils/action_taxonomy.py` — amended (§3).
- `PRIVACY_AND_DATA_HANDLING.md` — amended (§5).
- `project/backlog.json` — `ui2_b0_c5_amendments_bundle`, `ui2_privacy_doc_
  amendment`.
