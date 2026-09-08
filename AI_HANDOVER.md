# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_local_relay_protocol` — a new, additional,
  git-tracked, file-based relay transport (`relay/*.json` +
  `scripts/local_relay.py`) for same-machine Product-Owner/engineer
  coordination, alongside the existing GitHub-based relay (unchanged).
- Reuses `NEXUS_AGENT_RELAY_PROTOCOL.md`'s five markers plus `GOV.SESSION.1`'s
  `SESSION_START`/`SESSION_CLOSE` report schema verbatim; `next_actor` turn
  ownership is enforced by the tool, not convention.
- Filed from relay `ozandurmus/nexus-agent-relay#15`. Status is
  `complete_with_followup`, not `automated_validated`:
  `docs/design/LOCAL_RELAY_PROTOCOL.md` deliberately stays DRAFT, and its
  ratification is a future Product Owner `DECIDE` question, not this
  movement's own scope.

## 2. What changed

- `docs/design/LOCAL_RELAY_PROTOCOL.md` (new, DRAFT): file shape, marker/
  entry grammar, status vocabulary, turn ownership, the `good_to_go` fast
  path, an honest non-daemon notification model, non-supersession of the
  GitHub relay, and security/privacy notes.
- `scripts/local_relay.py` (new): `create`/`append`/`status`/`validate`.
  Imports `scripts/gov_session_transfer.py` directly for report-schema
  validation (`_validate_node`, `SESSION_START_REPORT_SCHEMA`/
  `SESSION_CLOSE_REPORT_SCHEMA`, `OUTCOMES`) rather than re-deriving it.
- `tests/test_local_relay_protocol.py` (new, 67 tests).
- `scripts/nexus_po_tool_gate.py`: new `relay/*.json` Edit/Write pattern
  (interactive only; `GOVERNANCE_PATHS` itself untouched, still 8 exact-match
  entries) via a new `_path_is_local_relay_file` (shares a factored-out
  `_repo_relative` helper with the existing `_path_is_governance`); new
  `scripts/local_relay.py status`/`validate` prefixes in `COMMON_PREFIXES`
  (both forms) and `create`/`append` in `INTERACTIVE_EXTRA_PREFIXES`
  (interactive only), 4 interpreter spellings each.
- `tests/test_gov_po_role.py` (+8): relay-file Edit/Write scoping (including
  a subdirectory and a non-`.json` file correctly rejected), delegated form
  still denies it, the existing `GOVERNANCE_PATHS` list unchanged, and
  `local_relay.py`'s four subcommands gated correctly in both forms.
- `relay/README.md` (new) — the tracked, empty-otherwise directory.
- `project/build_history.json` head record + `docs/history/INDEX.md`
  regenerated; `CURRENT_STATE.md` checkpoint/active-build rewritten, kept
  at exactly 200 lines; `project/roadmap.json` `now_next.now`/`current_build`
  updated to this build (status `complete_with_followup`).

## 3. Exact next action

1. Merge this PR — needs relay `#15`'s `SESSION_CLOSE` posted and validated,
   per the standing merge decision on relay `#13`.
2. A Product Owner `REVIEW`/`DECIDE` episode ratifies (or amends)
   `docs/design/LOCAL_RELAY_PROTOCOL.md` before it is used for anything
   beyond small, gate-fix-scale movements. A `nexus-decision-council`
   `REVIEW` round is recommended once relay `#14`'s own council-path fix has
   proven itself in real use (this movement could not run one, for the same
   reason relay `#11`'s `DECIDE` episode recorded).

## 4. Test delta

New: `tests/test_local_relay_protocol.py`, 67 tests, all passing. Targeted
addition to `tests/test_gov_po_role.py`: 6 new test functions (2
parametrized) taking the file from 79 to 90 passing, all pre-existing tests
unmodified. Combined sweep (local-relay + `gov_po_role` +
`gov_session_transfer` + `gov_relay_protocol` + `architecture_convergence`):
309 passed. `git diff --check` clean.
Repository privacy gate PASS/0 findings (`data/`, `logs/` cleared first). No
full regression: no schema/storage/console/UI change, two new files plus a
small, behavior-preserving gate extension, all covered by their own targeted
suites. No render harness: no template/static/payload change.

## 5. Risks / notes forward

- `RELAY_DECISION`'s `authorized_by`/`scope`/`supersedes` are enforced as
  real structured fields here (stricter than the GitHub transport's own
  free-text convention for the same three concepts) — a deliberate choice,
  not a divergence to reconcile later.
- Concurrency control (`append` re-reads and byte-compares the file
  immediately before writing) is optimistic, not a durable lock — sufficient
  for the turn-taking usage this protocol describes, insufficient for any
  future claim of true concurrent writers.
- A real bug was found and fixed during testing, not shipped: `fnmatch`'s
  `*` matches `/` too, so `relay/*.json` alone also matched
  `relay/sub/x.json`; fixed with an explicit single-`/` flatness check
  alongside the `fnmatch` call. Worth remembering if any future path pattern
  is added to this gate the same way.
- This movement retroactively migrates none of relay `#11`–`#14`'s existing
  GitHub history into the new local format, by design.
