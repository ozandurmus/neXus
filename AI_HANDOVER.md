# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-03. Branch `claude/vendor-semantics-confirmation-pt2iiq`.
- This session: **CLOSURE CORRECTION** on `op0b_s2_pan_parse_scope_extension`
  — a PO/architecture acceptance review found the S2 SESSION CLOSE
  overclaimed its own status, and this session corrected it. **S2 is NOT
  accepted as closed.** No S3 work started. No PR opened. No device
  contacted.

## 2. What changed this session (correction, not new feature work)

Two real defects found and fixed, plus one honest downgrade:

**1. Field-trace overclaim.** The prior close marked six PAN field-groups
(`conn_status`/`conn_ha1`/`conn_ha2`, `running_sync`, software/content
parity, preemption/priority/hold, flap counters, failure state)
`COLLECTED_AND_PARSED` in the frozen contract's §25 table. That label means
the *current production collection path* parses the field — untrue here:
`include_preflight_fields` defaults `False` and the one production call
site (`_collect_device_row`) never passes `True`. Corrected: those rows now
read `PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING`; a new contract
§25a gives the full six-dimension reconciliation the review demanded
(response fetched / extraction implemented / production-invoked /
projected / tests executed / real-env validated — identical answers across
all six groups) plus the explicit **S2 vs. S5/S6 boundary**: S2 owns
implementing and proving the extraction/projection capability; S5/S6 (the
not-yet-built dedicated preflight collector) owns actually invoking it in
production, per the contract's own pre-existing "Current collector reuse
decision" (the inventory/config collector must never become the preflight
engine — production wiring inside S2 would have started implementing S5
early, inside the wrong collector). This means S2's dormancy was
*architecturally correct*, not merely cautious — only the field-trace
label was wrong.

**2. Single-extraction-authority gap.** Audit found three functions in
`configuration/panorama_config_collector.py` independently traversing the
same in-memory HA-state XML `root`: the baseline five-field parse (inline
in `get_target_ha_runtime_state`), `_tokenize_ha_field_diagnostics`
(pre-existing OP.0a diagnostic sweep), and S2's `_parse_pan_ha_preflight_
fields` — the exact "one production parser + one diagnostic parser + one
preflight parser" anti-pattern the review named. Fixed with a small,
behavior-preserving refactor: new `_pan_ha_group_text(root, path)` is now
the one canonical accessor for any `result/group/` leaf; both the baseline
extraction and S2's field map read through it (same paths, same
`None`-on-absent/whitespace semantics — re-verified by direct re-derivation
against the fixture shape using stdlib `ElementTree`, since `lxml` is
unavailable in this container). `_tokenize_ha_field_diagnostics` is
deliberately left untouched and unmerged: it enumerates arbitrary child
tag names rather than reading named paths, and it feeds the B1/B2-adjacent
peer-identity diagnostic — refactoring it would be a pair-identity change,
out of S2's authorized scope. Flagged, not hidden.

**3. Status downgrade: `automated_validated` → `in_progress`.** S2's
load-bearing extraction suite (20 `lxml`-based tests) has never executed
in *any* environment — only `py_compile`-checked and now also logic-
re-derived by hand (not equivalent to running the real suite). Per the
review's explicit instruction, this container's tooling was **not**
modified/installed to force a local green (even though `lxml` is already a
declared project dependency in `requirements.txt` — installing it here
would still be "making this environment pass," which the review forbade).
`project/build_history.json`, `project/roadmap.json` and
`CURRENT_STATE.md` all corrected to `in_progress`; do not re-advance to
`automated_validated` except on actual CI/full-dependency-environment
evidence that both `tests/test_op0b_s2_pan_extraction.py` (20) and
`tests/test_op0b_s2_pan_projection.py` (14) pass.

**Git topology, reported not hidden:** this branch carries 5 commits ahead
of `origin/main` — three doc-only `OP.0b.0` vendor-semantics-confirmation
sessions, S1, and S2 — none yet in a pull request. Per the review's own
instructions this is exactly the "opaque multi-build stack" case that
requires reporting and a decision before any PR is opened; **no PR has
been opened this session.** See the PRE-MERGE ACCEPTANCE REPORT delivered
in-conversation this session for the full topology and the explicit
`Merge ready: NO` blocker.

Files touched: `configuration/panorama_config_collector.py` (the
`_pan_ha_group_text` refactor only — no field/path/semantics change),
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(§25 status-vocabulary + six rows corrected; new §25a), `project/
build_history.json`, `project/roadmap.json`, `CURRENT_STATE.md`,
`docs/history/INDEX.md` (regenerated), this file. No test file, no CP
file, no UI, no readiness-verdict, no pair-identity file touched.

## 3. Exact next action

**Do not start S3.** S2 is still open. In order:

1. A human/product-owner decision on the git topology: does the S2 PR
   bundle all 5 unmerged commits (3 doc-only sessions + S1 + S2), or does
   something split first? This session intentionally did not decide this
   unilaterally — see the PRE-MERGE ACCEPTANCE REPORT's "Merge ready: NO"
   blocker.
2. Once that's decided: open the PR, let CI run with the real dependency
   set, and require green on `tests/test_op0b_s2_pan_extraction.py` (20),
   `tests/test_op0b_s2_pan_projection.py` (14), S1 (23), architecture
   convergence (19), and the relevant PAN regression before advancing S2's
   `project/build_history.json` status to `automated_validated`.
3. Only after S2 is merged: new session, new branch, for `OP.0b` S3 (CP
   parse-scope extension).

## 4. Test delta this session

No new test files. Existing suite re-run after the refactor:
`tests/test_op0b_s1_preflight_model.py` + `tests/test_op0b_s2_pan_
projection.py` + `tests/test_architecture_convergence.py` = **56/56
passed** (refactor is behavior-preserving for everything executable here).
`tests/test_op0b_s2_pan_extraction.py` (20 tests) remains **NOT EXECUTED**
in this container — this is the primary open item, not a formality.

## 5. New risks / debt

The correction itself introduces no new risk. What it removes: a false
`automated_validated` claim that would have let a later session believe
S2's extraction logic was proven, when it has only ever been
`py_compile`-checked and manually re-derived. Do not trust `COLLECTED_AND_
PARSED`-shaped claims elsewhere in the phase doc without checking whether
the same "capability implemented vs. production invoked" distinction
applies — this session only corrected the six rows S2 itself touched.

## 6. Continue or fresh chat

**Same session/branch until S2 reaches a real merge-ready state** (per the
task that drove this correction — do not start S3 in a fresh chat before
S2 merges).

## 7. main.py / UI effect

None, unchanged from before the correction — `include_preflight_fields`
still defaults `False`, still unwired from any production call site.
