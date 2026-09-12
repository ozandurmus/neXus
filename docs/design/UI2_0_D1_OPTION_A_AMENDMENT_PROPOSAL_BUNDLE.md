# UI 2.0 — D1 Option A: amendment proposal bundle (steps 1, 2, 4, 5)

**Status: FROZEN — PRODUCT OWNER APPROVED AMENDMENT CONTRACT, APPLIED
2026-09-12 (originally approved 2026-09-10).** Every step this bundle carries
has now been applied to its target document: step 1
(`utils/action_taxonomy.py`), step 2 (`C4` §2.3, §3.3 step 7), step 2b (`C7`
§5.3 check 1 and new check 7; `C2` §6 checks 2 and 4; `C2` §5.3 retry row),
step 4 (`C7` §9.1–§9.3 closing notes), step 4b (`C7` §5.3's check-4 note), and
step 5 (`UI2_0_BASELINE_CONTRACT.md` §2 row `DEVICE-WRITE-CLASS`, D-8). Steps
1, 2 and 5 were applied by earlier movements without an amendment record; `C4`
and `UI2_0_BASELINE_CONTRACT.md` now carry one. Steps 2b, 4 and 4b were applied
2026-09-12. Items 6 (tests) and 7 (Java implementation) of the D1 §5 follow-up
list remain explicitly out of this bundle's scope and are **not** done.

**Read every "is not applied here", "is not edited by this document", and
"proposed" below as historical.** The prose is preserved exactly as approved,
because it is the authority for what each amended clause says; it is no longer
an accurate description of the repository's state. The one sentence this
supersedes most directly is the closing claim that "no
`utils/action_taxonomy.py`, `C4`, `C2`, `C7`, `UI2_0_BASELINE_CONTRACT.md` ...
file is modified by this document's existence" — true of the document's
existence, no longer true of the repository. Council disclosure: two same-model-family, fresh-context seats
(Security Reviewer and Senior Python Architect) reviewed independently; this
was not a cross-model review. Both returned `FREEZE WITH CHANGES`, and relay
`NXS-LOCAL-0060` seq 21 records that their required changes were satisfied
before Product Owner approval. This document edits no
other file. It carries the literal proposed text for four of the seven items
in `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` §5's
follow-up amendment list, prepared after the Product Owner selected
**Option A**. It is a **proposal a reviewer can approve, amend, or reject
per section**, not an executed edit — no target file's status line, content,
or test suite is touched by this document existing. Item 3 of the same
follow-up list (the new admission contract) is drafted separately as
`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`; items 6 (tests) and 7
(Java implementation) are explicitly out of scope for this preparation
movement and are not proposed here.

**Scope note on "Product Owner selected Option A."** This bundle proceeds on
that selection as communicated to this preparation movement
(2026-09-10, relay successor to `NXS-LOCAL-0060`). Per D1 §6 and per
`docs/design/UI2_0_BASELINE_CONTRACT.md`'s own recording discipline
("Restore stays disabled and unimplementable in Java until a decision is
recorded in `UI2_0_BASELINE_CONTRACT.md` §2"), **the selection itself is not
authoritative until the row proposed in step 5 below is actually added to
that FROZEN document by an authorized movement** — this bundle's own
existence is preparation, not that recording act.

**Revision note (this pass).** Two representational/policy questions this
bundle originally left open for reviewer confirmation are now **decided**
by Product Owner relay entries and are treated as binding inputs below,
not proposals: **seq 5** (`ActionClass.level=1.5`, non-renumbering, with a
required consumer/wire-field inventory — Step 1 below is revised to record
that inventory) and **seq 7** (narrowly restore-scoped `SIGNED_OFF`
eligibility, with independent `C2` runtime admission as a **separate**
predicate — Step 2's §3.3 step 7 restatement below is rewritten to state
that separation explicitly, replacing the earlier draft's blended framing).
See `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md` for the full
list of council findings this pass addresses and where each is resolved.

**Second revision note (this pass, relay seq 9 + PO freshness
clarification).** Two further changes: **relay seq 9** withdraws this
bundle's originally-proposed standalone `C2` §6 admission check in favor
of folding the ledger's admission facts into `C7` §5.3's existing checks
1/2 (Step 2b, rewritten below); and a subsequent PO clarification
**rejects the 15-minute connectivity freshness value** this bundle had
proposed, replacing it with configurable policy (specified in full in
`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3, summarized in
Step 2b below).

**Third revision note (this pass, relay seq 13).** Relay seq 13 **narrowly
supersedes seq 9** for exactly one fact: an explicit claim-time
reconciliation-pending check is now required, because that fact "cannot
remain compile-time-only." Step 2's "necessary, never sufficient"
paragraph and Step 2b are both rewritten below to state the resulting
two-tier model (a non-authoritative early pass at `C7` §5.3 compile time,
plus an authoritative check at `C2` §6's already-existing, already-class-
scoped check-4 slot at claim time) — `C4` static sign-off remains fully
independent of runtime admission throughout, exactly as seq 13's own text
reaffirms.

**Fourth revision note (this pass, PO review decision).** Two changes:
(1) a factual correction — the sibling ledger document's prior VSX
treatment (a `virtual_system_ref`-scoped restore admission choice) is
retracted; this repository's own evidence (`C7` §5.2's `restore_plan`
schema, `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 7/§7.7's frozen VSX
exclusion for Check Point Gaia backup) shows no current capability
targets an individual virtual system, so the current restore/ledger
scope is **physical `device_id`/`endpoint_id` only**; VSX-context restore
is explicitly unsupported, and any future VSX-context restore is its own
separate contract, not this document's concern. (2) **new Step 4b**, the explicit `C7`
§5.3 companion amendment the Product Owner requested, resolving a direct
contradiction between `C7` §5.3's own frozen check-4 note and this
document's claim-time check-4-slot proposal.

**Fifth revision note (relay seq 17).** The compile-time reconciliation
predicate is a new, independently recorded `C7` §5.3 check 7, appended
after check 6; it is not folded into check 2 or check 5. The same decision
corrects the ClusterXL boundary: per-member `device_id` separation proves
identity separation only, not cross-member restore safety. `C7` §9.8 is
about CP management-HA consistency groups, not ClusterXL gateway members.
ClusterXL member restore is therefore unsupported and out of current
scope; any future capability needs its own vendor/platform, target-scope,
and cross-member safety contract.

---

## Step 1 — `utils/action_taxonomy.py`: new `ActionClass` member (proposed)

### What this changes and why it is not applied here

`utils/action_taxonomy.py` is FROZEN-equivalent authority (test-enforced,
`AGENTS.md` "No new class 2, 3 or 4 command") and is explicitly out of this
movement's scope. The text below is the exact proposed diff a successor
movement would apply, for review now rather than invention later.

### Representation: `level=1.5`, non-renumbering — DECIDED (relay seq 5)

`ActionClass.level` is currently typed `int` and `ACTION_CLASSES` is a flat
tuple ordered `(0, 1, 2, 3, 4)`. The initial DRAFT of this bundle presented
two options (a non-integer ordering value vs. renumbering classes 2/3/4)
and recommended, without deciding, option (a). **The Product Owner has
since decided option (a)**: `level: int | float`, new member `level=1.5`,
**zero renumbering** of the four existing classes. This is no longer an
open question in this bundle; the diff below implements exactly that
decision. The decision's own text additionally requires "a full consumer/
wire-field inventory" before this diff may be applied — that inventory is
new in this revision, immediately below, and is also carried in full in
`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.2 (the two documents
state the same inventory; it is not duplicated by accident, it is
cross-referenced from both because both readers need it).

**Required consumer/wire-field inventory (relay seq 5), read-only against
current repository state, no file below edited by this bundle:**

| Consumer | Location | Impact |
|---|---|---|
| Class-list ordering assertion | `tests/test_architecture_convergence.py::test_the_five_classes_exist_and_are_ordered` | `[c.level for c in tax.ACTION_CLASSES] == [0, 1, 2, 3, 4]` — literal list equality. **Must be edited** to `[0, 1, 1.5, 2, 3, 4]` by whichever movement applies this diff; not edited here. |
| Console-submittability guard | `tests/test_architecture_convergence.py::test_no_console_job_type_is_class_2_or_above` | Uses `level >= 2`; `1.5 >= 2` is `False`, so today's assertion is unaffected, but it also means this test does **not** guard against a future restore job type being added to `JOB_REGISTRY` — a genuinely new sibling assertion naming `1.5` explicitly is needed (not merely relying on this test's existing threshold). |
| Browser/API wire field | `console/app.py:294`, `"action_class_level": jt.action_class.level` | No job type currently maps to the new class, so no live API response emits `1.5` today. JSON does not distinguish int/float at the wire level (both are a JSON `number`), so no schema break occurs from the type widening itself; the only residual risk is an external client parsing this field with a strict-integer schema, which cannot be ruled out from this repository alone. |
| Wire-field int-equality tests | `tests/test_con2_console_job_engine.py:354,357` | Assert `== 1` / `== 0` against **existing** classes, whose `level` values are unchanged by this decision — unaffected. |
| Ordering/comparison call sites (`sorted`, `key=lambda c: c.level`, direct comparisons) | repository-wide search across `utils/`, `console/`, `ui2/` | **None found** beyond the taxonomy module's own tuple literal and the two test files above; `console/registry.py` references `ActionClass` only via its `action_class` property, never `.level`. |
| Frontend (JS/TS/template) consumers | `templates/`, `static/`, any `*.js`/`*.ts` | **None found** in this repository. |

**Conclusion:** exactly one non-test source consumer (`console/app.py:294`,
unaffected in practice today since no job type resolves to the new class)
and two test files (`test_the_five_classes_exist_and_are_ordered`,
`test_no_console_job_type_is_class_2_or_above`) are the entire blast
radius the successor movement must edit alongside the taxonomy diff
itself; no closed-set invariant currently enforced in code is broken by
`level=1.5`.

### `C4`'s "five classes" wording — three literal references need updating alongside the taxonomy diff

`utils/action_taxonomy.py`'s own module docstring heading ("`## The five
classes`") and three literal mentions in `C4` (FROZEN) all say "five" and
would be inaccurate the moment a sixth class exists:

- `utils/action_taxonomy.py` docstring: `## The five classes` (heading) and
  its narrative description of exactly five — proposed replacement heading
  `## The classes` (drop the count from the heading entirely, since a
  fixed number in a heading is exactly the kind of text that goes stale
  the next time this closed set changes) with narrative revised to
  describe six, `CLASS_1B_CONTROLLED_RESTORE_WRITE` inserted between the
  `CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`
  descriptions, narrowly scoped per its own `why` text.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  line ~80 (§1.3 authority-chain table): *"`utils/action_taxonomy.py` — the
  five action classes, ported verbatim."* → proposed: *"...the action
  classes (six as of the `controlled-restore-write` addition, `D1`/relay
  `NXS-LOCAL-0060`), ported verbatim."*
- Same document, §2.2 (capability row shape), *"one of
  `utils.action_taxonomy`'s five classes (field 1)"* → proposed: *"...one
  of `utils.action_taxonomy`'s action classes (field 1)."* — drop the
  literal count here too rather than keeping it in sync by hand at every
  future addition.
- Same document, §9 cross-references, *"`utils/action_taxonomy.py` — the
  five action classes."* → same treatment as the first bullet.

All four are **prose-only** changes (no schema/table/algorithm text
changes) proposed for the same successor `C4`-amendment movement that adds
`restore_push` (Step 2 below); none is applied by this bundle.

### Proposed diff (illustrative; final identifier/wording is the successor movement's to fix)

```diff
 @dataclass(frozen=True)
 class ActionClass:
     id: str
-    level: int
+    level: int | float  # 1.5 admits CLASS_1B between CLASS_1 and CLASS_2 without renumbering
     label: str
     #: May this class execute anywhere in the product today?
     permitted: bool
     #: May the operator console submit it today? Strictly narrower than
     #: ``permitted``: CLASS 1 runs from the CLI under RB.x's contracts but is
     #: deliberately not reachable from a browser.
     console_submittable: bool
     #: Machine-readable reason surfaced when a surface refuses this class.
     refusal_code: str | None
     why: str


 CLASS_0_READ = ActionClass(...)   # unchanged
 CLASS_1_RECOVERY_WRITE = ActionClass(...)   # unchanged

+# UI2_TAXONOMY_DEVICE_WRITE_CLASS_AND_STEP_KIND (D1, Option A, PO decision
+# recorded at UI2_0_BASELINE_CONTRACT.md §2 row DEVICE-WRITE-CLASS, D-8):
+# a narrowly-scoped device write, distinguishable from CLASS_3 on artefact
+# provenance alone (D1 §4) -- every write this class ever permits is a
+# previously-verified, manifest-bound backup artefact replayed to the exact
+# device it was collected from, never operator-authored or operator-selected
+# content, never a different target. Gated by the admission contract in
+# docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md (ledger + C7 restore_approval
+# + C7 SS5.3 precondition battery, all three, per that document SS3).
+CLASS_1B_CONTROLLED_RESTORE_WRITE = ActionClass(
+    id="controlled-restore-write",
+    level=1.5,
+    label="Controlled restore write",
+    permitted=True,   # gated: see docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md
+    console_submittable=False,
+    refusal_code="controlled_restore_write_not_console_submittable",
+    why=(
+        "Permitted only for a provenance-bound restore of a previously "
+        "verified backup artefact back to its own originating device, "
+        "under C7's per-operation restore_approval and this class's own "
+        "reconciliation-pending admission ledger (docs/design/"
+        "RESTORE_CONTROLLED_WRITE_LEDGER.md). Never operator-authored "
+        "content, never a different target device, never console-"
+        "submittable at the current maturity."
+    ),
+)
+
 CLASS_2_OPERATIONAL_STATE_CHANGE = ActionClass(...)   # unchanged
 CLASS_3_CONFIGURATION_WRITE = ActionClass(...)   # unchanged
 CLASS_4_POLICY_DEPLOYMENT = ActionClass(...)   # unchanged

 ACTION_CLASSES: tuple[ActionClass, ...] = (
     CLASS_0_READ,
     CLASS_1_RECOVERY_WRITE,
+    CLASS_1B_CONTROLLED_RESTORE_WRITE,
     CLASS_2_OPERATIONAL_STATE_CHANGE,
     CLASS_3_CONFIGURATION_WRITE,
     CLASS_4_POLICY_DEPLOYMENT,
 )
```

The module docstring's "five classes" framing (`## The five classes`)
becomes six and needs a rewritten introduction naming the new class's
narrower scope explicitly, distinguished from both neighbors — this bundle
does not draft that prose rewrite in full; the successor movement writes it
against whatever final wording review settles on above.

### Test-enforced boundaries this proposed edit touches (named per `AGENTS.md` "Architectural invariants")

`tests/test_architecture_convergence.py`:
- `test_the_five_classes_exist_and_are_ordered` — name/count assertion
  changes from five to six; ordering assertion (`0 < 1 < 1.5 < 2 < 3 < 4`
  under option (a) above) needs a new form.
- `test_recovery_write_and_operational_state_change_are_distinct_classes`
  — likely still passes unchanged (it asserts a property of the existing
  two classes) but should gain a sibling assertion distinguishing the new
  class from both neighbors, per D1's own recommendation.
- `test_only_class_0_is_console_submittable` — must continue to pass
  un-amended if the new class stays `console_submittable=False`; a
  reviewer should confirm the assertion's exact form does not hard-code
  "exactly one non-class-0 tuple" in a way this addition would break.
- `test_every_console_job_type_maps_to_a_taxonomy_class`,
  `test_no_console_job_type_is_class_2_or_above` — the latter's own
  docstring ("CLASS 2 has no member yet... may only appear once every
  `OP.2` prerequisite... is met") is written assuming class 2 is the only
  ever-restricted tier; a new class 1.5 needs an equivalent, separately
  named exclusion, not silent inclusion under the existing class-2 check.

No test file is edited by this bundle; the above is the inventory a
successor movement's `TARGETED_TEST` step starts from.

---

## Step 2 — `C4` §2.3 new step kind, §3.3 step 7 restatement (proposed)

`docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` is
FROZEN and is not edited by this document. The following is the proposed
amendment text for the successor `C4`-amendment movement.

### §2.3 — proposed new row in the closed step-kind table

Insert after the `sftp_put` row (which stays exactly as written — reserved,
refused, unconditionally, for every capability that is not this one narrow
exception):

| kind | What it does | Expectation gate | Gate-reference applicability (§3) |
|---|---|---|---|
| `restore_push` | pushes one previously-fetched, manifest-bound backup artefact (identified only by its `backup_artefact.artefact_id`, never a free-form path or operator-supplied byte stream) to the device, for the vendor's own subsequent restore/apply command to consume; legal **only** on a capability whose resolved `action_class` is `controlled-restore-write` (§3.3 step 7's restated rule below) | transfer integrity (size/digest match against the artefact's own recorded `ciphertext_sha256`/plaintext digest per `C7` §3.2); never merely "bytes sent" — mirrors `sftp_get`'s own integrity bar, direction reversed | `KNOWN`/`UNKNOWN` per §3, same resolution algorithm, no special-case bypass |

Proposed accompanying prose (for the paragraph immediately following the
table, alongside the existing `sftp_put` reservation sentence): *"`restore_
push` is the one device-directed write kind this registry permits, and it
is legal only for a capability whose resolved `action_class` is `controlled-
restore-write` (`utils/action_taxonomy.py`); a capability of any other
`action_class` declaring a `restore_push` step is refused at spec-validation
time, mirroring the existing write-marker denylist's own enforcement shape
(§3.3 step 7). `sftp_put` itself is not un-reserved by this addition — it
remains refused unconditionally for every other purpose."*

### §3.3 step 7 — proposed restatement (revised this pass: static sign-off and runtime admission are separate predicates, per relay seq 7)

Current text: *"A row whose `action_class` is class 2, 3 or 4 can never be
`SIGNED_OFF` by construction... the write-marker denylist is therefore
enforced once, at gate-row creation, and every step resolution inherits it
automatically."*

**Revision note.** This bundle's initial DRAFT proposed a restatement that
made a `controlled-restore-write` row's `SIGNED_OFF` eligibility
conditional on `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`'s
admission contract being satisfied "at claim time" — i.e., it blended
spec-time sign-off with runtime admission into one combined test. The
Product Owner's relay seq 7 decision states these are **separate
predicates** ("`C4` static sign-off and `C2` runtime admission are separate
predicates... no generic execution, operator-authored content, console
submission, or device contact is authorized by this decision"). The
restatement below is rewritten to remove that blending: `SIGNED_OFF`
eligibility depends **only** on the gate row's own static shape (its
step kind and command scope), never on any runtime/ledger fact; the ledger
admission contract is entirely `C2`'s concern (`RESTORE_CONTROLLED_WRITE_
LEDGER.md` §3.1/§3.4), evaluated at every claim, independent of the row
having been `SIGNED_OFF` once.

Proposed restatement:

> A row whose `action_class` is class 2, 3 or 4 can never be `SIGNED_OFF` by
> construction (`AGENTS.md`: "No new class 2, 3 or 4 command at the current
> product maturity"). A row whose `action_class` is `controlled-restore-
> write` (`utils/action_taxonomy.py`, the D1/Option A class, `level=1.5`)
> **may** be `SIGNED_OFF`, narrowly: only when its `canonical_command_key`'s
> step kind is `restore_push`, or an `exec`/`poll` step whose gate entry is
> itself scoped to a restore-apply command for the same capability. This is
> a **static, spec-time predicate only** — it depends solely on the row's
> own step kind and command scope, evaluated once at gate-row creation
> exactly like every other `sign_off_state` determination in this section,
> and **never** on any runtime fact (a restore approval's validity, this
> class's admission ledger state, or any precondition battery result). A
> `controlled-restore-write` row whose step kind is anything else (e.g. a
> bare `exec` sending an unscoped, non-restore command) is refused exactly
> as any class 2/3/4 row would be — narrow and step-kind-scoped, never a
> blanket exception for the class as a whole.
>
> **`SIGNED_OFF` is necessary, never sufficient, for execution.** Per
> `UI2_0_BASELINE_CONTRACT.md` §2's `DEVICE-WRITE-CLASS` (D-8) decision
> (relay `NXS-LOCAL-0060` seq 7): a `SIGNED_OFF` `controlled-restore-write`
> row makes a capability **admissible into `C4` §3.5's execution-eligible
> view** — the same role `SIGNED_OFF` plays for every other class. Whether
> any **one specific job** against **one specific target** may actually
> execute is a wholly separate, `C7`/`C2`-side runtime predicate, evaluated
> fresh at every claim, independent of this row's own `sign_off_state`:
> `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1's fact set (valid
> per-operation `restore_approval`; connectivity evidence meeting §3.3's
> configurable freshness policy, with an early pass at `C7` §5.3 check 1
> and an authoritative re-check at `C2` §6 check 2; no unreconciled prior
> restore-write against the target, with an early pass at **`C7` §5.3's
> new check 7** (council-informed, relay seq 17 — checks 2/5 were both
> rejected as fold targets by two independent council seats) and, **per
> relay seq 13**, an authoritative, independently named check at `C2`
> §6's existing check-4 slot — the one fact this repository's own
> revision history shows cannot safely remain compile-time-only). A
> `SIGNED_OFF` row with no valid approval, or against a target carrying an
> unreconciled prior outcome at claim time, is refused at claim exactly as
> if the row were not `SIGNED_OFF` at all — the two predicates are
> independent, and **both** must hold for a device to be contacted;
> neither one, alone, ever authorizes execution, and `C4`'s own predicate
> is untouched by any of this document's revisions to date.


This restatement resolves what the original DRAFT flagged as "the one
genuine policy call in the bundle" — not by choosing narrow-signability
over permanent-unsignability (that choice was already made informally in
the DRAFT), but by fixing the more consequential ambiguity the council
found underneath it: whether narrow signability *by itself* would have
been read as authorizing execution. It does not, and this text now says so
in the same place a reviewer would look for the answer.

---

## Step 2b — `C7` §5.3 checks 1 (amended) and new check 7 (early, non-authoritative); `C2` §6 check 2/check-4-slot (claim-time, authoritative); `C2` §5.3 retry rule (revised this pass: council-informed PO decision replaces the check-2 fold with a new check 7)

`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` and
`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (both FROZEN) are not
edited by this document. This step is new relative to the original DRAFT's
four steps — a council finding named "a `C2` admission/retry row" as its
own item, distinct from `C4`'s static sign-off (Step 2 above).

**Revision history, stated plainly.** Four Product Owner inputs have
shaped this step in sequence, and each is recorded honestly rather than
silently overwritten:

1. The first revision of this bundle proposed a standalone `C2` §6 check
   (row 7) to carry the ledger's admission facts, alongside a proposed
   15-minute connectivity freshness bound.
2. Relay seq 9 folded the admission facts into `C7` §5.3's existing
   checks 1/2 instead of a new `C2` row, and a subsequent chat
   clarification rejected the 15-minute bound outright, requiring
   configurable policy instead.
3. Relay seq 13 narrowly superseded seq 9's "fold, no separate check"
   framing for exactly one fact: the reconciliation-pending check "cannot
   remain compile-time-only" and must have an explicit claim-time
   instance (`C2` §6 check 4's slot).
4. **A council-informed PO decision (relay seq 17) narrows the remaining
   open question — which `C7` §5.3 check absorbs the compile-time,
   non-authoritative reconciliation pass.** Two independent, fresh-context
   council seats (a Security Reviewer and a Senior Python Architect)
   reviewed folding it into check 2 or check 5 and **both rejected folding
   into either**, citing the same maintainability/fail-closed risk: mixing
   two independently-failing predicates under one recorded result loses
   which one actually failed, inconsistent with how the claim-time
   check-4-slot extension was already done (independently named and
   tracked, never blended). The Product Owner accepted this input and
   decided: **a new `C7` §5.3 check — check 7 — appended after check 6**,
   never reordering or renumbering checks 1–6. Recorded as
   **council-informed, not council-decided**: the seats named the risk,
   the Product Owner made the placement call, per this movement's own
   procedure. This step is rewritten below to reflect check 7 in place of
   the withdrawn check-2 fold; cross-referencing
   `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1/§3.1.0/§3.7 (including its
   §3.1.1/§3.1.2/§3.1.3 subsections) rather than duplicating them.

**Compile-time (`C7` §5.3) — early, non-authoritative:**

- **Check 1 (Connectivity)** — amended in place to consult a configurable
  `RestoreConnectivityFreshnessPolicy` (`RESTORE_CONTROLLED_WRITE_LEDGER.md`
  §3.3.1) instead of an unstated "bounded freshness window": a mandatory
  active-probe path (always available, using the actual restore-write
  transport) and an optional cached-telemetry/SNMP path usable only when
  TTL, identity-binding, semantic-sufficiency (now keyed per vendor/
  platform, and itself requiring `GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)
  council review before any pair is added), and explicit-configuration
  conditions are all met, falling back to the active probe otherwise. No
  numeric default is proposed for the probe timeout or cache TTL — both
  are `UNKNOWN`, left to the successor movement.
- **Checks 2–6 — unchanged, no fold.** The prior revision's proposed
  reframing of check 2 ("Backup validity" → a broader "artefact and
  target admission validity" check absorbing the reconciliation-pending
  fact) is **withdrawn** per the council-informed decision above; check 2
  reverts to its own existing FROZEN meaning, untouched by this document.
- **Check 7 (new, `check_id=C7_RESTORE_NO_UNRECONCILED_PRIOR`) — "No unreconciled prior restore-write outcome"**,
  appended after check 6; seq 17 preserves the separate earlier check-1
  connectivity amendment and leaves checks 2–6 unchanged: an
  early, non-authoritative, staleness-tolerant pass against
  `RESTORE_CONTROLLED_WRITE_LEDGER.md`'s own `has_unreconciled_prior`
  check for `controlled-restore-write` plans only; `NOT_APPLICABLE` for
  every other class's plan. The literal proposed table row and full
  rationale are in that document's §3.7, cross-referenced not duplicated.

  Plan compilation also evaluates the named predicate
  `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` from authoritative registry plus
  current topology evidence: only `STANDALONE_PHYSICAL_DEVICE` proceeds;
  a known member yields `UNSUPPORTED_CLUSTERXL_MEMBER`, while missing,
  stale, or conflicting membership evidence yields `NOT_EVALUABLE` and
  refuses compilation before approval. At claim time, the
  existing `C2` §6 check 5 registry/allowlist re-checks the same eligibility
  and refuses with distinct named reasons. This is target eligibility, not a
  folding of the ledger predicate into check 5; the two predicates remain
  independently recorded.

**Claim-time (`C2` §6) — authoritative, re-verified fresh regardless of
how long ago the compile-time pass above ran:**

- **Check 2 (Connectivity precondition)** — its existing FROZEN scope
  ("for a class-1 profile... re-verified at every claim") is proposed to
  be read as extending to `controlled-restore-write` (class 1.5) jobs,
  consulting the same `RestoreConnectivityFreshnessPolicy` as the
  compile-time pass above, but as the **authoritative** result.
- **Check 4 (currently the `RB.x` operational-write ledger, stated
  `NOT_APPLICABLE` for restore) — proposed, per relay seq 13, to gain an
  explicit second class-scoped clause**: *for `controlled-restore-write`
  jobs, this document's own `RestoreWriteLedger.has_unreconciled_prior`
  check (fail-closed on an unreadable ledger); `NOT_APPLICABLE` for every
  other class, unchanged.* This reuses check 4's existing, already-class-
  scoped row — **zero new rows in `C2`'s own table** — while making the
  claim-time instance an independently named, independently tracked,
  always-run check, per seq 13's explicit requirement that this fact not
  remain compile-time-only. Unaffected by this pass's check-7 decision,
  which concerns `C7`'s battery, not `C2`'s.

**Genuinely additive (unaffected by any of the above): one new `C2` §5.3
retry-rule row** stating `CLASS_1B_CONTROLLED_RESTORE_WRITE` never
auto-retries, mirroring `CLASS_1_RECOVERY_WRITE`'s existing row, for a
reason `C7` §5.7 already establishes at the job-state level. This row
answers a retry-*behavior* question (what happens after a `CLAIMED`/
`EXECUTING` job fails), distinguished explicitly (per
`RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.3) from a `BLOCKED`-at-claim
job's own retry eligibility (which is ordinary, unaffected by this row,
since such a job never reached `EXECUTING` at all) — this row is
unrelated to the admission-fact placement and is therefore unaffected by
seq 9, seq 13, or seq 17.

---

## Step 4 — `C7` §9.1–§9.3: proposed resolved text


`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` is FROZEN
and is not edited by this document. Proposed replacement text for the
successor movement, to be inserted as each subsection's own closing note
(the subsections' existing analysis text is not proposed to change — only a
closing "Resolved" paragraph is added to each, so the historical record of
what the gap was stays intact):

**§9.1, proposed closing note:**

> **Resolved** (`docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_
> DECISION.md`, Option A, Product Owner decision recorded at
> `UI2_0_BASELINE_CONTRACT.md` §2 row `DEVICE-WRITE-CLASS` (D-8)): restore's
> write resolves to `CLASS_1B_CONTROLLED_RESTORE_WRITE`
> (`utils/action_taxonomy.py`), a new class distinct from both
> `CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`, scoped
> exactly to the provenance-bound write §4 above already argues is
> distinguishable from `CLASS_3`. This class's own admission contract is
> `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`.

**§9.2, proposed closing note:**

> **Resolved** (same decision as §9.1): the closed step-kind set gains
> `restore_push` (`C4` §2.3), legal only for a `controlled-restore-write`
> capability (`C4` §3.3 step 7, restated). `sftp_put` remains reserved and
> refused unconditionally for every other purpose, unchanged.

**§9.3, proposed closing note:**

> **Resolved, both halves together**: `CLASS_1B_CONTROLLED_RESTORE_WRITE`
> and `restore_push` land in the same successor movement, per this
> subsection's own original warning that neither gap alone unblocks
> restore. `C7` §5's restore engine (§5.2–§5.7, §6.2) is unchanged by this
> resolution — no field, table, or precondition named there is modified;
> only the class/kind that carries its already-specified execution now
> exists.

---

## Step 4b — `C7` §5.3's check-4 note: proposed companion amendment resolving a contradiction (new this pass, per PO instruction)

`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` is FROZEN
and is not edited by this document. This step is new relative to the prior
passes' four/five steps — the Product Owner identified that `C7` §5.3's
own frozen note on `C2` §6 check 4 (*"that check is `NOT_APPLICABLE` for a
restore job"*, written before `CLASS_1B_CONTROLLED_RESTORE_WRITE` existed)
now **directly contradicts** `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.0's
own proposal to repurpose check 4's slot as restore's authoritative,
claim-time reconciliation-pending check. The full proposed replacement
text — quoted in full, with its own rationale — lives in
`RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.6, cross-referenced rather than
duplicated here; summarized: `C7` §5.3's note is proposed to be rewritten
so that check 4 reads as **class-scoped**, not restore-blanket
`NOT_APPLICABLE` — `CLASS_1_RECOVERY_WRITE` keeps the existing `RB.x`
cadence-ledger interpretation unchanged; `CLASS_1B_CONTROLLED_RESTORE_
WRITE` reads this ledger's own reconciliation-pending check instead; every
other class remains `NOT_APPLICABLE`. Check 5 (no concurrent operation
against the same target) is explicitly restated as unaffected — a live-job
concurrency lock, answering a different temporal question than check 4's
now-class-scoped, terminal-outcome reconciliation question.

---

## Step 5 — `UI2_0_BASELINE_CONTRACT.md` §2: proposed new decision row

`docs/design/UI2_0_BASELINE_CONTRACT.md` is FROZEN and is not edited by this
document. Proposed new row for §2's Phase 0 decisions table (next available
lettered id after `RESTORE-IN-RELEASE-1` (D-7)), **revised across three
passes** to fold in the governing PO decisions (relay seq 5, 7, 9, 13, plus
the freshness clarification) so the baseline row records the actual
decided shape, not any earlier draft's open questions:

```markdown
| **DEVICE-WRITE-CLASS** (D-8) | How restore's device write is admitted, per `C7` §9.1–§9.3's reported (not fixed) gap | **ACCEPTED: Option A** (`docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`) — a new, narrowly-scoped `utils/action_taxonomy.py` class (`CLASS_1B_CONTROLLED_RESTORE_WRITE`, `level=1.5`, non-renumbering — relay `NXS-LOCAL-0060` seq 5) between `CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`, plus a new `C4` §2.3 device-directed step kind (`restore_push`), both scoped to a provenance-bound replay of a previously-verified backup artefact back to its own **physical device/endpoint** only (`device_id`/`endpoint_id` — VSX virtual-system-context restore and ClusterXL member restore are both explicitly unsupported and out of current scope; either future capability requires its own vendor/platform-and-target-scope contract, and ClusterXL additionally requires a cross-member safety contract) — never operator-authored content, never a different target, never console-submittable. The class is narrowly `SIGNED_OFF`-eligible at `C4`'s static/spec level for restore-scoped rows only, independent of a wholly separate runtime admission predicate (relay seq 7), gated by a dedicated ledger/approval/precondition contract (`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`) that uses the separately amended `C7` §5.3 check 1 for early connectivity and a new, independently recorded `C7_RESTORE_NO_UNRECONCILED_PRIOR` check 7 for the early no-unreconciled-prior pass (relay seq 17; appended after check 6, checks 2–6 unchanged), plus authoritative claim-time checks at `C2` §6 check 2 and the existing check-4 slot (relay seq 13). `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` must resolve to `STANDALONE_PHYSICAL_DEVICE` both before approval and at claim; known ClusterXL members and missing/stale/conflicting topology evidence are refused fail-closed through the existing target-eligibility/allowlist boundary. `C7` §5.3's own check-4 note itself requires a companion amendment (class-scoped: class 1 keeps the `RB.x` cadence interpretation, class 1.5 reads the restore reconciliation ledger, every other class stays `NOT_APPLICABLE`). Connectivity-freshness is configurable policy, not a fixed number (no value frozen; 15 minutes explicitly rejected), with any cached-telemetry semantic-sufficiency contract keyed per vendor/platform and itself requiring council review before being referenced. `CLASS_3_CONFIGURATION_WRITE`/`CLASS_4_POLICY_DEPLOYMENT` remain prohibited, unaffected. Restore stays disabled in Java until the amendments named in `D1` §5's follow-up list (this row is item 5 of that list) are all applied and, for the admission contract specifically, taken through `GOV_PO_ROLE_MIGRATION.md` §7 council review before its own freeze | `utils/action_taxonomy.py`; `C4` §2.3, §3.3 step 7; `C7` §5.3 check 1/new check 7/check-4-note companion amendment, §9.1–§9.3; `C2` §5.3, §6 check 2/check 4/check 5; `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`; `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md`; `REL-BACKUP` |
```

The row id `DEVICE-WRITE-CLASS (D-8)` is this bundle's own proposal (the
next unused lettered id in that table's sequence); a reviewer may rename it.
Adding this row is, per D1 §6, **the actual act that authorizes restore's
device-write admission to be built** — nothing in this bundle or in
`RESTORE_CONTROLLED_WRITE_LEDGER.md` substitutes for it, and until an
authorized movement applies this row to the real FROZEN document, restore
remains disabled and unimplementable exactly as it is today.

---

## What this bundle does not include, and why

- **Step 3** (the new admission-contract design document) is delivered
  separately as `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`, per this
  movement's own instruction to draft it as an independent DRAFT candidate
  rather than folding it into this proposal-diff-shaped document.
- **Step 2b** (`C7`/`C2` amendment text) is new this pass and is
  cross-referenced to, not duplicated from,
  `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1 (the compile/claim check
  mapping) and §3.4 (the `C2` retry-rule row), where their own rationale
  lives alongside them.
- **Step 4b** (the `C7` §5.3 companion amendment) is new this pass and is
  cross-referenced to, not duplicated from,
  `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.6, where the full proposed
  replacement text and its rationale live.
- **A genuine future VSX-context restore capability is explicitly out of
  scope** — this bundle's Step 1/Step 5 text now states plainly that
  restore's current scope is physical `device_id`/`endpoint_id` only, per
  the PO's own review decision; no VSX-scoped target model, step kind, or
  admission rule is proposed anywhere in this bundle.
- **Step 6** (test-file edits) and **step 7** (Java implementation) are
  explicitly out of this preparation movement's scope; Step 1 above names
  the specific assertions a successor `TARGETED_TEST` phase would touch,
  without touching them here.
- **No `utils/action_taxonomy.py`, `C4`, `C2`, `C7`,
  `UI2_0_BASELINE_CONTRACT.md`, `docs/AI_DEVELOPMENT_PROTOCOL.md`, or test
  file is modified by this document's existence** — every block above is
  proposed text for a reviewer, not an applied diff; `git diff --check`
  against this revision shows only edits to this file, its sibling
  admission-contract document, and the consolidated review document
  under `docs/design/`.

## Cross-references

- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` §5 —
  the follow-up list this bundle executes items 1, 2, 4, 5 of (plus the new
  Step 2b/4b, council-raised and PO-raised additions not in the original
  seven-item list).
- `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` — item 3, the admission
  contract this bundle's Step 1/Step 2/Step 2b/Step 4b proposals
  presuppose exists; §3.2's inventory, §3.4's `C2` proposal, §3.6's `C7`
  companion amendment, and §3.1.1's physical-scope-only correction are
  shared with this bundle by cross-reference, not duplicated.
- `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md` — the primary
  review entry point for this revision pass, naming every council finding
  and PO review decision and where each is resolved across both documents.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 7, §7.7 (FROZEN)
  — the Check Point Gaia VSX-exclusion evidence grounding this pass's
  scope correction.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — council-trigger process
  governing this bundle's own path to becoming an executable movement.
- `AGENTS.md` "Network action taxonomy", "Identity law", "Evidence laws" —
  the standing constraints every proposed edit above is checked against.
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` seq 5, 7 —
  the two PO decisions governing this revision.
