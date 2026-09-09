# UI 2.0 — D1 Option A: amendment proposal bundle (steps 1, 2, 4, 5)

**Status: DRAFT — REVIEWABLE PROPOSAL, NOT APPLIED.** This document edits no
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

---

## Step 1 — `utils/action_taxonomy.py`: new `ActionClass` member (proposed)

### What this changes and why it is not applied here

`utils/action_taxonomy.py` is FROZEN-equivalent authority (test-enforced,
`AGENTS.md` "No new class 2, 3 or 4 command") and is explicitly out of this
movement's scope. The text below is the exact proposed diff a successor
movement would apply, for review now rather than invention later.

### Proposed representation question (flagged, not decided here)

`ActionClass.level` is currently typed `int` and `ACTION_CLASSES` is a flat
tuple ordered `(0, 1, 2, 3, 4)`. A class sitting strictly between 1 and 2
needs one of:

- **(a) Recommended — a non-integer ordering value.** Widen
  `level`'s type to `int | float` and give the new member `level=1.5`.
  Ordering (`ACTION_CLASSES` tuple order, any `sorted(..., key=lambda c:
  c.level)` call site) is preserved with a one-line type-annotation change
  and zero renumbering of the existing four members. This is the minimal,
  most surgical option and is what the diff below assumes.
- **(b) Alternative — renumber.** Keep `level: int`; renumber
  `CLASS_2_OPERATIONAL_STATE_CHANGE` → 3, `CLASS_3_CONFIGURATION_WRITE` → 4,
  `CLASS_4_POLICY_DEPLOYMENT` → 5, and give the new class `level=2`. This
  touches every call site, doc, and test that hard-codes "class 2" /
  "class 3" / "class 4" as a literal integer (`C4` §3.3 step 7's own text,
  `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate table,
  `FAILOVER_ENGINE_ARCHITECTURE.md` §10, every `CLASS 2/3/4` prose
  reference across the repository) — a far larger and riskier diff for a
  purely representational question.

This document recommends (a) and asks the reviewer to confirm or override
it explicitly; it is not treated as decided by this bundle alone, per
`AGENTS.md`'s "explicit `UNKNOWN` over invented certainty" — a
representational choice this consequential is named, not silently picked.

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

### §3.3 step 7 — proposed restatement

Current text: *"A row whose `action_class` is class 2, 3 or 4 can never be
`SIGNED_OFF` by construction... the write-marker denylist is therefore
enforced once, at gate-row creation, and every step resolution inherits it
automatically."*

Proposed restatement (the substantive change: naming the new class
explicitly as a fourth, narrower case, rather than silently falling through
either side of the existing binary):

> A row whose `action_class` is class 2, 3 or 4 can never be `SIGNED_OFF` by
> construction (`AGENTS.md`: "No new class 2, 3 or 4 command at the current
> product maturity"). A row whose `action_class` is `controlled-restore-
> write` (`utils/action_taxonomy.py`, the D1/Option A class) **may** be
> `SIGNED_OFF`, but only when its `canonical_command_key`'s step kind is
> `restore_push` or an `exec`/`poll` step whose gate entry is itself scoped
> to a restore-apply command **and** the admission contract in
> `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` §3's three-leg gate
> (approval, ledger, precondition battery) is satisfied at claim time — the
> gate-registry row's own `sign_off_state` governs whether the *command* is
> authorized to exist at all; the ledger/approval check governs whether
> *this specific execution* may proceed. A `controlled-restore-write` row
> whose step kind is anything else (e.g. a bare `exec` sending an
> unscoped, non-restore command) is refused exactly as any class 2/3/4 row
> would be — the new class's signability is narrow and step-kind-scoped,
> never a blanket exception.

This restatement is the substantive policy choice D1 §5's follow-up list
item 2 flagged as "left to the successor movement" (whether the new class
is a fourth never-signable tier or a signable-under-conditions tier) —
resolved here as **signable, narrowly, under the three-leg gate**, because
Option A's entire rationale (D1 §4/§5) is that this class's write is
categorically narrower than class 2/3/4's and an unconditional
never-`SIGNED_OFF` rule would make the new class functionally identical to
"disabled," reproducing Option D by a different route. A reviewer may
reject this and keep the class permanently unsignable if that reading of
D1's intent is preferred — flagged as the one place in this bundle where a
genuine policy call, not a mechanical transcription, is being proposed.

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

## Step 5 — `UI2_0_BASELINE_CONTRACT.md` §2: proposed new decision row

`docs/design/UI2_0_BASELINE_CONTRACT.md` is FROZEN and is not edited by this
document. Proposed new row for §2's Phase 0 decisions table (next available
lettered id after `RESTORE-IN-RELEASE-1` (D-7)):

```markdown
| **DEVICE-WRITE-CLASS** (D-8) | How restore's device write is admitted, per `C7` §9.1–§9.3's reported (not fixed) gap | **ACCEPTED: Option A** (`docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`) — a new, narrowly-scoped `utils/action_taxonomy.py` class (`CLASS_1B_CONTROLLED_RESTORE_WRITE`) between `CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`, plus a new `C4` §2.3 device-directed step kind (`restore_push`), both scoped to a provenance-bound replay of a previously-verified backup artefact back to its own originating device only — never operator-authored content, never a different target, never console-submittable. Admission gated by a dedicated ledger/approval/precondition contract (`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`), not by `RECOVERY_OPERATIONAL_WRITE_LEDGER.md`'s cadence model (`NOT_APPLICABLE` to restore, `C7` §5.3). `CLASS_3_CONFIGURATION_WRITE`/`CLASS_4_POLICY_DEPLOYMENT` remain prohibited, unaffected. Restore stays disabled in Java until the amendments named in `D1` §5's follow-up list (this row is item 5 of that list) are all applied and, for the admission contract specifically, taken through `GOV_PO_ROLE_MIGRATION.md` §7 council review before its own freeze | `utils/action_taxonomy.py`; `C4` §2.3, §3.3 step 7; `C7` §9.1–§9.3; `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`; `REL-BACKUP` |
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
- **Step 6** (test-file edits) and **step 7** (Java implementation) are
  explicitly out of this preparation movement's scope; §"Step 1" above
  names the specific assertions a successor `TARGETED_TEST` phase would
  touch, without touching them here.
- **No `utils/action_taxonomy.py`, `C4`, `C7`, `UI2_0_BASELINE_CONTRACT.md`,
  or test file is modified by this document's existence** — every block
  above is proposed text for a reviewer, not an applied diff; `git diff
  --check` against this PR shows only two new files under `docs/design/`.

## Cross-references

- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` §5 —
  the follow-up list this bundle executes items 1, 2, 4, 5 of.
- `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` — item 3, the admission
  contract this bundle's step-1/step-2 proposals presuppose exists.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — council-trigger process
  governing this bundle's own path to becoming an executable movement.
- `AGENTS.md` "Network action taxonomy", "Identity law", "Evidence laws" —
  the standing constraints every proposed edit above is checked against.
