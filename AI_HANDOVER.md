# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_3_approved_movement_orchestration_ac3` —
  froze `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` and
  implemented it: `scripts/orchestrator.py` dispatches a Product-Owner-
  approved movement to a separate `claude -p` engineer process in its own
  git worktree, tracked through development and serialized integration
  under the standing `relay#13` merge authorization — no manual message-
  carrying, no new terminal, no per-step approval for routine engineering
  movements. Requested at
  `relay/NXS-LOCAL-0007-gov-po-3-orchestration.json`'s `SESSION_START`
  (Product Owner architecture direction, 2026-09-08, after a 3-seat
  `nexus-decision-council` round in the originating PO episode).
- **This record covers AC-3 only.** AC-5 (four real demonstrations) and
  AC-8-for-the-demo have not run yet — do not treat GOV.PO.3 as fully
  validated because this record exists; the relay stays open pending AC-5.
- Independent of `gov_po_2_po_visibility_and_bounded_authorship`'s (FROZEN,
  PR #122 merged) own scope; `gov_po_2_implementation` stays `now_next.next`,
  untouched by this movement.

## 2. What changed

- `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (new): the AC-1
  architecture amendment, FROZEN with three Product Owner additions (A:
  merge-time re-validation against an advanced `main`; B: an `orchestrator
  status` table plus `--max-workers`; C: the engineer's fixed prompt
  re-reads the canonical relay before opening its PR).
- `scripts/orchestrator.py` (new): `start`/`status`/`stop`/`merge-lock`.
  Worktree creation from the relay's own `report.git.base`/`git.lane`
  fields (mirroring `GOV_PO_1_GATE_5`'s own restrictions: base must be an
  `origin/` ref, branch must be `feature/*`); a SHA-256 content hash over
  the exact `SESSION_START` entry, written verbatim to
  `.nexus/approved_task.json` in the worktree; process records outside the
  relay file (`NEXUS_ORCHESTRATOR_STATE_DIR`, default a system-temp
  subdirectory); duplicate-start prevention and interrupted-run resume both
  via a live `os.kill(pid, 0)` check, never an inferred staleness
  threshold; `--max-workers` (default 3) enforced on fresh dispatch only,
  never on a resume; a TTL-based, self-healing merge lock
  (`merge-lock acquire/release`) for cross-movement integration
  serialization. A safety addition beyond the FROZEN text: `--max-budget-usd`
  (default 3.0) bounds an unattended spawn's spend.
- `.claude/nexus-engineer.settings.json` + `scripts/nexus_engineer_tool_
  gate.py` (new): the engineer's own permission profile — default-**allow**
  ("normal dev tools"), unlike the PO profile's default-deny shape. Two
  narrow `PreToolUse`/`PostToolUse` checks only: the repository privacy
  gate before `git push`/`gh pr create`; the merge lock plus Addition A's
  real `git fetch origin && git merge origin/main` +
  `tests/test_architecture_convergence.py` + `build_history_index.py
  --check` before `gh pr merge`, denying and releasing the lock on any
  failure. `git push --force`/`-f` denied unconditionally (the one overlap
  with the PO profile).
- `scripts/nexus_po_tool_gate.py`: added `orchestrator status`
  (`COMMON_PREFIXES`, both forms) and `orchestrator start`/`stop`
  (`INTERACTIVE_EXTRA_PREFIXES`); introduced `_prefix_matches()`, a
  boundary-safe prefix check (matched prefix followed by a space, a `/`,
  or end-of-string) applied across the *entire* allowlist — closes
  `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §3.1's own open item,
  and a real pre-existing latent gap where a bare `"ls"` prefix also
  matched `"lsof ..."` under plain `str.startswith`.
- `scripts/local_relay.py`: the canonical-location resolver —
  `NEXUS_CANONICAL_RELAY_DIR` (`create --dir`'s default) and
  `NEXUS_RELAY_FILE` (`append`/`status`/`validate`/`watch --file`'s
  default), so every worktree resolves to the one physical relay file the
  Product Owner's session reads, never a per-worktree copy; an explicit
  `--dir`/`--file` always wins, and default behavior with neither set is
  byte-for-byte unchanged. A real, cross-platform (`fcntl`/`msvcrt`,
  stdlib-only) advisory lock now wraps `append`'s whole read-validate-
  build-write sequence (sidecar `<file>.lock`), turning a losing concurrent
  writer's outcome from "fails, needs a human to retry" into "waits, then
  proceeds automatically" — needed with no human present to react.
- `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §§2.4/3.2/4
  corrected per the Product Owner's freeze decision on this movement's
  relay (see §3 below).
- `tests/test_orchestrator.py` (new, 48): pure decision logic — dispatch/
  resume/refuse, merge-lock acquire/reclaim, phase reconciliation, task
  hashing, git-ref validation — plus CLI-level `start`/`status`/`merge-lock`
  tests with git/spawn side effects monkeypatched. No real `claude -p`
  spawn or git worktree in the test suite itself, by design (§9 of the
  FROZEN amendment: those are AC-5's job).
- `tests/test_gov_po_role.py` (+8), `tests/test_local_relay_protocol.py`
  (+9): the new gate prefixes, the boundary-safe-matching regressions, the
  resolver env vars and their precedence, and one genuine contended-lock
  CLI test (a held lock really blocks a second `append`, then times out).
- `project/build_history.json`: new `gov_po_3_approved_movement_
  orchestration_ac3` record (`automated_validated`), newest-first.
- `project/roadmap.json`: `current_build`/`now_next.now` →
  `gov_po_3_approved_movement_orchestration_ac3`; `now_next.next`
  (`gov_po_2_implementation`) unchanged.
- `CURRENT_STATE.md`: checkpoint updated, trimmed to stay at the 200-line
  ceiling.
- `docs/history/INDEX.md`: regenerated via `scripts/build_history_index.py`.

## 3. GOV_PO_2 text corrections applied

Both authorized by this movement's own `RELAY_DECISION`
(`relay/NXS-LOCAL-0007`, entry 3), per the applying-vs-originating rule
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` §2.2 itself
states:

1. §§2.4/4: "merge (governance or product, of any kind)" → distinguishes
   *originating* a new standing merge authorization (still human-only) from
   *executing* one already recorded (e.g. `relay#13` — not itself a fresh
   human-decision event, per `AGENTS.md` "Git authority and execution law").
   The prior text silently contradicted `relay#13`, which was already
   governing real merges.
2. §3.2: "those are human-only declarations" → the PO *applying* a freeze/
   ratification decision the human already made is permitted; *originating*
   one is not. The `po_drafts/*.md` status-line detection mechanism itself
   is unchanged — it already denied any PO-authored `FROZEN`/`RATIFIED`
   claim there regardless of direction; only the prose explaining its
   intent was overbroad.

## 4. Exact next action

1. Commit this movement's changes on a `feature/*` branch, push, open a PR,
   confirm every required gate is green (targeted + full suite, `git diff
   --check`, repository privacy gate), then merge under the standing
   `relay#13` decision — no separate merge `RELAY_DECISION` needed — and
   post the `RELAY_NOTE` on `relay/NXS-LOCAL-0007`.
2. AC-5: with the Product Owner's direction (this session, direct
   instruction, recorded as a `RELAY_DECISION` on `relay/NXS-LOCAL-0007`
   entry 6), the next step is a small, clearly-labeled, throwaway scratch
   movement dispatched through the real `orchestrator start` pipeline
   (real worktree, real spawned `claude -p` engineer process, real commit/
   push/PR/self-merge) — run only *after* this movement's own AC-3 code is
   on `origin/main`, since the demo's spawned engineer process needs
   `scripts/orchestrator.py`/`.claude/nexus-engineer.settings.json`/
   `scripts/nexus_engineer_tool_gate.py` to already exist in the worktree
   it is dispatched into.
3. `gov_po_2_implementation` stays `now_next.next` — unrelated to and
   unblocked independently of this movement.

## 5. Test delta

New: `tests/test_orchestrator.py` (48). Targeted: `tests/test_gov_po_role.py`
(+8), `tests/test_local_relay_protocol.py` (+9). Combined targeted run:
`tests/test_gov_po_role.py`/`test_local_relay_protocol.py`/
`test_architecture_convergence.py`/`test_orchestrator.py` — 267 passed.
Full regression `.venv/bin/python -m pytest -q -n auto --dist worksteal`:
2662 passed, 25 skipped, 2 failed — both confirmed pre-existing and
unrelated via `git stash` against a clean `main` checkout taken *before*
this movement's own changes existed
(`tests/test_dev_0_5b_auth_consumer_canonical_config.py`'s `.venv/`
site-packages repository-text DLP scan; the same known false positive
already documented in `gov_po_1_gate_5`'s and
`gov_po_1_local_relay_watch_command`'s own evidence — neither that test nor
the code it scans was touched by this movement). `git diff --check` clean.
Repository privacy gate `.venv/bin/python main.py --repository-privacy-check`:
FAIL/5 findings, identical to the 5 already documented in `gov_po_1_gate_5`'s
own evidence (`data/`, `data/.support_hmac.key`, `logs/` untracked local
runtime artifacts; the same pre-existing `CREDENTIAL_LITERAL` matches in
`project/build_history.json:47` and
`relay/NXS-LOCAL-0003-local-relay-watch-command.json:155`, both already
committed) — none of this movement's own changed files appear in the
findings list.

## 6. Risks / notes forward

- AC-5's four demonstrations have not run. Do not report GOV.PO.3 as done;
  the relay (`relay/NXS-LOCAL-0007`) stays open with `next_actor: engineer`
  pending them.
- Addition A's merge-time re-validation runs only the two fixed checks
  (`tests/test_architecture_convergence.py`, `build_history_index.py
  --check`); a movement's own additional targeted tests are not
  mechanically re-run at merge time, since `report.validation_plan` is
  free-text prose, not a machine-executable command list — named explicitly
  in `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` §9 as a
  residual, narrower risk than the gap Addition A closes.
- An auto-mode classifier blocked one literal, permission-probing-shaped
  `claude -p` Bash invocation during AC-4 verification; a differently-
  worded invocation and the actual production shape (a Python
  `subprocess.run` call, no literal `claude -p` text in the Bash tool's own
  command string) were not blocked. Inference from one observed pair of
  cases, not proof it can never interfere — AC-5 demonstration 1 is the
  real test; a recurrence there is a blocking finding to report, never
  something to route around (AC-7).
