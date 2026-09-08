# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_gate_5_worktree_management` — `git worktree
  add`/`list` added to `scripts/nexus_po_tool_gate.py`'s interactive-form
  (add) and both-forms (list) allowlists, requested at
  `relay/NXS-LOCAL-0006-gate-5-worktree-management.json`'s `SESSION_START`
  (Product Owner direction, after two same-checkout collision risks were
  caught and avoided this session).
- Small, independent GOV.PO.1 tooling movement — does not touch
  `gov_po_2_po_visibility_and_bounded_authorship`'s (FROZEN, PR #122 merged)
  own scope or `gov_po_2_implementation`'s sequencing, still `now_next.next`.

## 2. What changed

- `scripts/nexus_po_tool_gate.py`: `git worktree add` added to
  `INTERACTIVE_EXTRA_PREFIXES`; a new `_check_worktree_add()` helper
  enforces, on top of the prefix match, that the base commit-ish (`git
  worktree add <path> <commit-ish>`) starts with `origin/` — mirroring the
  existing `git merge origin/<ref>` restriction — and, when `-b`/`-B
  <branch>` is present, that `<branch>` starts with `feature/` (`gov/po-`
  stays reserved for the PO's own branch). `git worktree list` added to
  `COMMON_PREFIXES` (both forms, read-only, mirroring `git branch --list`).
  `git worktree remove`/`prune`/`move` deliberately not allowlisted — fall
  through to the existing default-deny path in both forms. Module docstring
  updated.
- `tests/test_gov_po_role.py` (+10): see §4.
- `project/build_history.json`: new `gov_po_1_gate_5_worktree_management`
  record (`automated_validated`), newest-first.
- `project/roadmap.json`: `current_build`/`now_next.now` →
  `gov_po_1_gate_5_worktree_management`; `now_next.next`
  (`gov_po_2_implementation`) unchanged.
- `CURRENT_STATE.md`: checkpoint updated.
- `docs/history/INDEX.md`: regenerated via `scripts/build_history_index.py`.

## 3. Exact next action

1. Merge this movement's branch to main per the standing relay#13 decision
   (this relay's `merge_gate`): once tests are green plus `git diff --check`
   and the repository privacy gate (run for real), the engineer merges
   itself and reports via `RELAY_NOTE` — no separate merge `RELAY_DECISION`
   needed.
2. `gov_po_2_implementation` is next: build the five mechanisms in
   `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §3 —
   unrelated to and unblocked independently of this movement.

## 4. Test delta

New: `tests/test_gov_po_role.py` +10 (`git worktree add` allowed interactive
with an `origin/` base and a `feature/` branch; denied delegated; base ref
not starting `origin/` denied — local path, other remote, and omitted-base
forms; `-b`/`-B` branch name not starting `feature/` denied, including an
attempted `gov/po-` name; add without `-b` allowed; `git worktree list`
allowed both forms; `remove`/`prune`/`move` denied both forms). Targeted:
105 passed. Full regression: 2597 passed, 25 skipped, 2 failed — both
pre-existing and unrelated (`.venv/` site-packages incidentally scanned by
`tests/test_dev_0_5b_auth_consumer_canonical_config.py`'s repository-text
scan; neither that test nor the code it scans was touched here). `git diff
--check` clean. Repository privacy gate FAIL/5 findings via
`.venv/bin/python main.py --repository-privacy-check` — all pre-existing
and outside this movement's changed files (`data/`, `data/.support_hmac.key`,
`logs/` untracked local runtime artifacts; a pre-existing `CREDENTIAL_LITERAL`
match in `project/build_history.json`'s own `gov_po_1_local_relay_watch_command`
evidence text, and one in `relay/NXS-LOCAL-0003-local-relay-watch-command.json:155`
— both already committed at `4533edb`).

## 5. Risks / notes forward

- This movement adds worktree creation only, not the portability mechanic:
  `relay/*.json`'s own Edit/Write path-matching stays anchored to the main
  checkout's `cwd`, so a relay file for a worktree-hosted movement is still
  authored in the main checkout and handed to the engineer by absolute path
  (or via a small premerge to `origin/main` first) — per this movement's own
  `SESSION_START` sequencing note. A future movement should decide whether
  relay path-matching ever needs to become worktree-portable.
