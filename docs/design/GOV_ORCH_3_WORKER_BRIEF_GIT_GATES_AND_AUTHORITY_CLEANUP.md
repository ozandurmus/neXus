# GOV.ORCH.3 — Worker brief, tool-neutral gates, and authority cleanup

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11).** Depends on GOV.ORCH.1
(`verify`) and is designed to coexist with GOV.ORCH.2. Part C amends
`docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN) §4 D1 and §9 and
`AGENTS.md`'s handover-economy rule; those edits are proposals for the
Product Owner to ratify and are written as amendment notes, never as
silent rewrites.

## Part A — WORKER.md: a real worker brief

### A.1 Problem

The only instruction a dispatched engineer receives is the fixed
`ENGINEER_PROMPT`, which tells it to proceed "under AGENTS.md,
AI_START_HERE.md, and CLAUDE.md exactly as an interactively started
session would". The interactive reading order costs a fresh worker
roughly 30k–90k tokens before its first edit, contradicts the intended
"read only the named files" discipline, and depends on Claude Code's
automatic `CLAUDE.md` load, which other tools do not perform.

### A.2 Design

At dispatch the orchestrator generates `.nexus/WORKER.md` in the
worktree from `approved_task.json`, using a fixed template (no model
call). Content, in this order, and nothing else:

1. Movement id, objective (verbatim), movement type.
2. **Files you may read**: `scope.in` plus the direct tests named in the
   validation plan. **Files you must not touch**: `scope.out`.
3. Acceptance criteria (verbatim list).
4. Invariants and risks (verbatim).
5. Validation commands (object-form `validation_plan` rendered as
   fenced commands; prose entries listed as "manual").
6. Git: base, lane, merge gate string, merge mode (GOV.ORCH.2).
7. Relay closeout: the exact `py scripts/local_relay.py append --file
   $NEXUS_RELAY_FILE --role engineer --marker SESSION_CLOSE ...` shape
   and the rule "re-read the relay file first".
8. Standing rules, fixed text, under 200 words: smallest diff; no repo
   scan, history, Graphify, devices, secrets, or scope expansion; run
   named tests in the foreground and wait; a relay-tool error is
   reported, not treated as a blocker; never end on a chat message
   without a SESSION_CLOSE.

The generated file is dispatch metadata like `approved_task.json`: never
committed (existing exclusion test extended to cover it).

`ENGINEER_PROMPT` is reduced to: "Read `.nexus/WORKER.md` and
`.nexus/approved_task.json` in the current directory and follow them.
Read nothing else unless WORKER.md names it." The sentences about
`AGENTS.md`/`AI_START_HERE.md`/`CLAUDE.md` and the interactive-session
comparison are removed. The pytest-foreground and relay re-read rules
move into WORKER.md §8 and §7.

Tool pointers: no per-tool file is generated. Both Claude Code and Codex
read the repository's `AGENTS.md`/`CLAUDE.md` from the checkout, and
those files stay as they are for interactive sessions. Instead
`AI_START_HERE.md` gains one short "Orchestrated worker" paragraph:
"If `.nexus/WORKER.md` exists in your working directory you are an
orchestrated worker: follow it and skip the reading order below." That
single paragraph is the only tool-neutral hook needed.

### A.3 Acceptance criteria

- AC-A1: `start`/`run` write `.nexus/WORKER.md`; a golden-file test
  renders a fixture `SESSION_START` and compares the output.
- AC-A2: WORKER.md contains every `scope.in`, `scope.out`,
  acceptance-criteria and invariant string verbatim, and the validation
  commands from object-form entries.
- AC-A3: `ENGINEER_PROMPT` no longer mentions `AGENTS.md`,
  `AI_START_HERE.md` or `CLAUDE.md`; a test asserts the new text.
- AC-A4: The "never committed" test covers `.nexus/WORKER.md`.
- AC-A5: `AI_START_HERE.md` carries the one-paragraph orchestrated-worker
  rule and nothing else changes in it.

## Part B — Tool-neutral gates

### B.1 Problem

Privacy scan before push and merge serialization before `gh pr merge`
are Claude `PreToolUse` hooks (`.claude/nexus-engineer.settings.json`).
A worker in any other tool has no gate at all.

### B.2 Design

1. **Worktree `pre-push` git hook.** At dispatch the orchestrator installs
   a `pre-push` hook for that worktree only: it sets
   `git config --worktree core.hooksPath <worktree>/.nexus/hooks` and
   writes `.nexus/hooks/pre-push` (a linked worktree shares the main
   checkout's hooks directory, so a per-worktree `core.hooksPath` is the
   only way to scope the hook). The hook denies a push that is
   `--force`/`-f`, or whose privacy scan (shared
   `scripts/orchestrator_verify.py` function from GOV.ORCH.1) reports
   new findings against `base_sha`. Same decision logic as the Claude
   hook, now enforced by git for every tool.
2. **Merge only through the orchestrator.** `gh pr merge` by the engineer
   is no longer the integration path for orchestrated movements:
   GOV.ORCH.2's `integrate` (merge lock, `git merge origin/main`,
   convergence tests, build-history check, targeted tests) is the only
   path, and WORKER.md says so. The Claude hook's `gh pr merge` branch
   stays for interactive sessions but is not relied upon.
3. **Branch protection statement.** `docs/design/GOV_PO_3_...` gains an
   amendment note that `main` protection (PR required, CI green) is the
   final tool-neutral gate; this contract does not change GitHub settings
   itself (PO action, recorded in the relay).

### B.3 Acceptance criteria

- AC-B1: After dispatch, `git config --worktree core.hooksPath` points at
  `.nexus/hooks` and `pre-push` is executable.
- AC-B2: A test drives the hook script directly with a fixture stdin/argv
  and asserts: force push denied; push with new privacy findings denied;
  clean push allowed.
- AC-B3: The hook script has no dependency on any AI-tool environment
  variable; it locates `base_sha` from `.nexus/approved_task.json` and
  the process record path passed at install time.
- AC-B4: Claude hook profile unchanged except a comment noting the
  git-level gate now exists.

## Part C — Authority cleanup

### C.1 Problem

`docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN, level 2) §4 D1 gives the
PO assistant role to Claude and §9 forbids the orchestrator from being
the independent reviewer. `docs/reference/COPILOT_OPERATING_MODEL.md`
(level 6, 2026-09-11) makes Codex PO + Orchestrator + final reviewer. The
SESSION START/CLOSE schemas and the reasoning-tier table exist in three
copies (`AI_START_HERE.md`, `COPILOT_OPERATING_MODEL.md` twice), against
`AGENTS.md`'s handover-economy rule.

### C.2 Design

1. Add to `GOV_PO_ROLE_MIGRATION.md` an "Amendment A-2026-09-11 (PO
   decision pending)" block after §4 stating: the PO assistant role is
   tool-neutral; the current holder is recorded in
   `COPILOT_OPERATING_MODEL.md` and may change without re-freezing this
   contract; §9's independence rule is kept, with the explicit
   consequence that when the orchestrator and the final reviewer are the
   same tool, an independent review is satisfied only by a different
   provider seat (`nexus-po-evidence-reviewer` or a council seat on a
   different provider). This replaces the "Codex is final reviewer" claim
   with "Codex synthesizes; an independent seat reviews".
2. `COPILOT_OPERATING_MODEL.md`: delete its SESSION START/CLOSE templates
   and its reasoning matrix; replace each with one line pointing to
   `AI_START_HERE.md` §"SESSION START" / §"SESSION CLOSE" / the tier
   table. Its decision record keeps only decisions, not schema.
3. `AI_START_HERE.md` becomes the single owner of both schemas and the
   tier table; add the Part A orchestrated-worker paragraph.
4. `AGENTS.md`: no rule change; one "Contradiction report" entry under
   the authority hierarchy section recording that item 1 above resolves a
   level-6-overrides-level-2 contradiction, as `AGENTS.md` itself
   requires.

### C.3 Acceptance criteria

- AC-C1: Exactly one SESSION START template and one SESSION CLOSE template
  remain in the repository outside `docs/history/**` (test greps for the
  heading strings).
- AC-C2: `COPILOT_OPERATING_MODEL.md` contains no "Codex is ... final
  reviewer" sentence; it names the independent-seat rule.
- AC-C3: The amendment block in `GOV_PO_ROLE_MIGRATION.md` is marked
  "PO decision pending" and the document's FROZEN status line is
  untouched.
- AC-C4: Existing docs/project-state tests green; `git diff --check`
  clean; privacy gate 0 new findings.

## Scope

In: `scripts/orchestrator.py` (WORKER.md generation, prompt text, hook
install), `scripts/orchestrator_verify.py` (hook entry point),
`scripts/nexus_worker_prepush.py` (new, the hook body), tests
(`tests/test_orchestrator.py`, new `tests/test_worker_brief.py`,
`tests/test_worker_prepush.py`, doc-uniqueness test in the existing
docs/project-state test module), `AI_START_HERE.md`,
`docs/reference/COPILOT_OPERATING_MODEL.md`,
`docs/design/GOV_PO_ROLE_MIGRATION.md` (amendment block only),
`AGENTS.md` (contradiction report entry only), GOV.PO.3 amendment note.

Out: Claude hook profile logic, packet schema, relay tooling, GitHub
branch-protection settings, any product code.

## Validation plan (machine-readable)

```
py -m pytest -q tests/test_orchestrator.py tests/test_worker_brief.py tests/test_worker_prepush.py tests/test_gov_po_role.py tests/test_gov_relay_protocol.py
py scripts/repository_privacy_check.py
git diff --check
```

## Worker route

`Sonnet 5, normal (low effort)`. Template rendering, a small shell/Python
git hook, and doc edits with exact target text given above. Not Haiku:
Parts B and C edit frozen governance text where a misplaced sentence is
an authority error, and the golden-file and grep tests need to be written
correctly the first time.

## Open items for the PO at freeze

- Ratify or reject the Part C amendment text.
- Whether Part C is split into its own docs-only movement so Parts A/B
  can merge independently (proposed: yes if the PO wants to think longer
  about C).
