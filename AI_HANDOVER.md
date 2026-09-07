# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_gate_1_command_safety_correction` —
  **AUTOMATED_VALIDATED**, branch `gov-po-1-gate-fix` (isolated git
  worktree, deliberately never checked out in the primary working
  directory), PR open. Found and fixed during the first real Phase A
  `PLAN` episode (`gov_po_1_step_5_first_plan`, PR #113, unmerged,
  concurrent): the interactive PO's `PreToolUse` gate blocked its own
  contractually-required relay packet posting.
- `gov_po_1_step_4_direction_record_ratification` **MERGED** (PR #112);
  direction record **RATIFIED**. `M9` **MERGED** (PR #104).

## 2. What changed

- `scripts/nexus_po_tool_gate.py`: replaced the substring ban on
  `<`/`>`/`;`/`|`/`&&`/`||` with `shlex.shlex(..., punctuation_chars=True)`
  tokenization, which reproduces bash's real distinction between a
  metacharacter embedded in a quoted argument (data, harmless) and the
  same character standalone (a real operator). Backtick/`$(` stay banned
  unconditionally (they execute even inside double quotes). The
  destructive-word bans (`rm`/`mv`/`cp`/`sudo`/`chmod`/`curl`/`wget`/`ssh`/
  `main.py`) became exact-token checks instead of substrings — this also
  newly denies `find ... -exec rm ...`, which the old substring form
  missed. A raw embedded newline is denied outright. Added one narrow
  scratch-write allowance (`nexus_po_*.json`/`.txt` under a recognized
  system temp root, interactive form only, realpath-checked against
  traversal) and restricted `--body-file` to that same pattern.
- `tests/test_gov_po_role.py`: 9 new tests plus one corrected existing test
  that had accidentally asserted `--body-file` with an arbitrary filename
  should be allowed (it should not — that's an exfiltration path).
- Project state files for the build.
- **Not part of this PR:** `.claude/settings.local.json` on this
  workstation (untracked, gitignored) carried a blanket
  `Bash(gh pr merge:*)` allow rule that would have applied uncontested to
  a PO session launched from the VS Code extension without
  `--settings .claude/nexus-po.settings.json` (the extension cannot pass
  that flag) — emptied directly on the workstation; not repository state,
  cannot be enforced by a repository test.

## 3. Exact next action

1. Merge this PR first (small, isolating, unblocks packet posting).
2. `gov_po_1_step_5_first_plan` (PR #113) retries its blocked
   `SESSION_START`/`SESSION_CLOSE` packet posting against the fixed gate,
   then reconciles `project/roadmap.json` `now_next.now` with this PR's
   pointer (same pattern as the earlier M9/GOV.RELAY.1/GOV.GIT.1 stack).
3. A PO session must always launch via
   `claude --settings .claude/nexus-po.settings.json` in a terminal, never
   the VS Code extension — that remains an operational rule, not something
   this fix can enforce technically.

## 4. Test delta

`tests/test_gov_po_role.py` + relay + session-transfer + convergence: 209
passed. Repository privacy gate: PASS. `git diff --check`: clean.

## 5. Risks / notes forward

- `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` /
  `scripts/gov_session_transfer.py` were read but not touched; both stay
  exactly `FROZEN`.
- The scratch pattern is deliberately narrow; a future different staging
  need should extend the same regex, not add a second mechanism.
- This movement does not touch `gov/po-1-step-5-first-plan` or M10.1.
