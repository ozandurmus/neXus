# GOV.ORCH.7 — Role entry files, vendor shims, and tool-neutral enforcement

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11). Direction approved by
the Product Owner in chat, 2026-09-11 ("go").** Depends on GOV.ORCH.3
(WORKER.md, pre-push hook), GOV.ORCH.5 (QUEUE.md), GOV.ORCH.6 (diet).
Amends `AGENTS.md` by one added section; does not rewrite any law.

## 1. Problem

Each tool auto-loads a different file: Claude Code loads `CLAUDE.md`
(which only names `AGENTS.md`, no import), Codex loads `AGENTS.md`,
Copilot loads `.github/copilot-instructions.md` and `AGENTS.md`. Skills,
agents and `PreToolUse` hooks exist only for Claude. Vendor and model
names sit inside governance text (`COPILOT_OPERATING_MODEL.md`,
`copilot-instructions.md`, `CLAUDE.md`, `GOV_PO_ROLE_MIGRATION.md`).
`GOV_PO_ROLE_MIGRATION.md` cites a tracked `.claude/settings.json` that
does not exist. Result: the same repository rule applies or not
depending on which tool opened it.

## 2. Design

### 2.1 One entry file per role, vendor-neutral (`roles/`)

| File | Content (one page each, ≤ 600 words) | Source today |
|---|---|---|
| `roles/PO.md` | `PO.md` moved here verbatim (root `PO.md` becomes a 2-line pointer for one release) | `PO.md` |
| `roles/ENGINEER.md` | reading order (QUEUE.md, CURRENT_STATE, AI_HANDOVER, then the named design doc), the SESSION START/CLOSE obligation (pointer to `AI_START_HERE.md` schemas, not a copy), validation ladder pointer, git lane rule, five stop questions adapted to engineering | `AI_START_HERE.md` reading order + `CLAUDE.md` engineer delta |
| `roles/WORKER.md` | the template `scripts/orchestrator.py::render_worker_md` uses, checked in; the orchestrator reads it from here instead of a Python string; `.nexus/WORKER.md` is its rendered instance | `orchestrator.py` template constants |
| `roles/REVIEWER.md` | the read-only reviewer procedure now in `.claude/agents/nexus-po-evidence-reviewer.md` and `nexus-council-seat.md`, without Claude frontmatter; the agent files keep their frontmatter and become 3-line pointers to this file | `.claude/agents/*.md` |

`AGENTS.md` gains one section `## Role dispatch` (≤ 80 words): if
`.nexus/WORKER.md` exists in the working directory you are a worker and
read only it; otherwise state your role in your first message and read
`roles/<ROLE>.md`; nothing else in this file is role-specific.

### 2.2 Vendor shims, pointer-only

- `CLAUDE.md`: first line `@AGENTS.md` (Claude Code import syntax) then
  ≤ 10 lines: the `nexus-po` launch line, the packet-rendering line, a
  pointer to `docs/reference/MODEL_TIER_MAP.md`. Everything else in it
  today moves to `roles/ENGINEER.md` (engineer delta) or is deleted as
  duplicate.
- `.github/copilot-instructions.md`: ≤ 10 lines: pointer to `AGENTS.md`
  role dispatch and `roles/`; the relay-bootstrap citation stays (test
  pinned); model names removed.
- Codex: `AGENTS.md` is already its entry; no file added.
- `docs/reference/MODEL_TIER_MAP.md` (new, ≤ 200 words): the neutral
  tier table from `AI_START_HERE.md` on the left, current model names
  per vendor on the right. Every other governance file refers to tiers
  by the neutral name (`normal`, `extended (high)`, `council seat`) and
  never to a model name. `COPILOT_OPERATING_MODEL.md`,
  `copilot-instructions.md`, `CLAUDE.md`, `PO.md`/`roles/PO.md` are
  edited accordingly; `GOV_PO_ROLE_MIGRATION.md` gets an amendment note
  only (FROZEN) stating that its `.claude/settings.json` references are
  read as `.claude/nexus-po.settings.json`.
- A test `tests/test_vendor_neutral_governance.py` asserts that no file
  in `AGENTS.md`, `AI_START_HERE.md`, `CURRENT_STATE.md`, `roles/*.md`,
  `docs/reference/COPILOT_OPERATING_MODEL.md`, `.github/prompts/*.md`
  contains a model or vendor product name from a fixed list (Sonnet,
  Opus, Haiku, Fable, Astra, Terra, Sol, GPT, Codex, Copilot, Claude)
  except inside the two shims, `MODEL_TIER_MAP.md`, and the literal
  strings the existing tests pin (list them in the test as allowed
  exceptions with the pinning test's name).

### 2.3 Enforcement that must be equal across tools

1. **Push gate in the main checkout.** `scripts/orchestrator.py
   install-hooks [--path <checkout>]` installs the same
   `nexus_worker_prepush.py` hook into any checkout via
   `core.hooksPath` (repo-local `.githooks/` directory committed, hook
   script executable). `roles/ENGINEER.md` step 0: run it once.
2. **Merge path.** `orchestrator integrate` is the only integration path
   for orchestrated movements (already in GOV.ORCH.2/3); the `gh pr
   merge` branch of `nexus_engineer_tool_gate.py` is removed and its
   test adjusted; the hook keeps only the privacy/force-push checks,
   which now duplicate the git hook and stay as defense in depth.
3. **PO write scope.** `scripts/nexus_po_scope_check.py`: given a
   branch and a base, exits 1 if a `gov/po-*` branch touches a path
   outside the governance set in `GOV_PO_ROLE_MIGRATION.md` §4 (read the
   set from a small JSON `config/po_write_scope.json` so the rule has
   one owner). Wired as a job in `.github/workflows/validation.yml` for
   `gov/po-*` branches and as a pre-push check by the same hook when
   the current branch matches `gov/po-*`. The `.claude/nexus-po.settings.json`
   deny list stays for Claude sessions; it is no longer the only gate.
4. **Preflight.** `orchestrator.py preflight --movement X` (also run by
   `run`/`start` first; `--skip-preflight` does not exist): fails closed
   when the packet's `baseline.authority` names a document whose status
   line is not FROZEN/RATIFIED, when `report.git.base` is not
   `origin/main` at the fetched HEAD, when `--provider/--model/--effort`
   are absent on the command line, when the packet fails
   `gov_session_transfer.py validate`, or when `roles/WORKER.md` is
   missing. Exit 2 with one line per failed check.
5. **CI parity.** `validation.yml` runs `test_vendor_neutral_governance.py`,
   `test_cold_start_budget.py`, `project_queue.py check`.

## 3. Scope

In: `roles/*.md` (new), `PO.md` (pointer), `AGENTS.md` (one added
section), `CLAUDE.md`, `.github/copilot-instructions.md`,
`docs/reference/MODEL_TIER_MAP.md` (new), `docs/reference/COPILOT_OPERATING_MODEL.md`,
`docs/design/GOV_PO_ROLE_MIGRATION.md` (amendment note only),
`.claude/agents/*.md` (pointers), `scripts/orchestrator.py`
(`install-hooks`, `preflight`, template from `roles/WORKER.md`),
`scripts/nexus_engineer_tool_gate.py`, `scripts/nexus_po_scope_check.py`
(new), `config/po_write_scope.json` (new), `.githooks/pre-push`,
`.github/workflows/validation.yml`, tests (`tests/test_vendor_neutral_governance.py`,
`tests/test_po_scope_check.py`, `tests/test_orchestrator.py`,
`tests/test_worker_brief.py`, `tests/test_nexus_engineer_tool_gate.py`,
`tests/test_gov_po_role.py`).

Out: any product code; relay tooling; packet schema; `.claude/skills`
bodies (they stay Claude-only PO conveniences); GitHub branch
protection settings.

## 4. Acceptance criteria

- AC-1: the four role files exist, each ≤ 600 words; `AGENTS.md` has
  `## Role dispatch`; `PO.md` is a pointer.
- AC-2: `CLAUDE.md` starts with `@AGENTS.md` and is ≤ 12 lines;
  `copilot-instructions.md` ≤ 12 lines; both keep the relay-bootstrap
  citation.
- AC-3: `test_vendor_neutral_governance.py` passes and its exception
  list names only pinned strings and the shims.
- AC-4: `render_worker_md` reads the template from `roles/WORKER.md`;
  the golden test in `test_worker_brief.py` is updated and passes.
- AC-5: `install-hooks` on a temporary clone sets `core.hooksPath` and
  a force push and a push with new privacy findings are denied there.
- AC-6: `nexus_po_scope_check.py` exits 1 for a fixture `gov/po-x`
  branch touching `utils/x.py` and 0 for one touching only governance
  paths; CI job present.
- AC-7: `preflight` fails closed on each of the five conditions in
  §2.3-4 (five tests) and `run` refuses to dispatch when it fails.
- AC-8: `gh pr merge` handling removed from the engineer hook; its
  tests updated; privacy/force-push gate tests unchanged.
- AC-9: `git diff --check` clean; privacy gate 0 new findings.

## 5. Validation plan (machine-readable)

```
python3 -m pytest -q tests/test_vendor_neutral_governance.py tests/test_po_scope_check.py tests/test_orchestrator.py tests/test_worker_brief.py tests/test_nexus_engineer_tool_gate.py tests/test_gov_po_role.py tests/test_cold_start_budget.py tests/test_architecture_convergence.py
python3 scripts/repository_privacy_check.py
git diff --check
```

## 6. Worker route

`Sonnet 5, normal (medium effort)`. Touches frozen text (amendment
notes only), CI, hooks and a fail-closed preflight; the shapes are
specified but the cross-file consistency needs a careful pass.
