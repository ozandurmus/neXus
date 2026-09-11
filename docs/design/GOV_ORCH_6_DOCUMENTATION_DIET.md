# GOV.ORCH.6 — Documentation diet: shrink the cold start without losing meaning

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11). Direction approved by
the Product Owner in chat, 2026-09-11 ("go").** Docs-only movement.
Depends on GOV.ORCH.5 (QUEUE.md) having landed on the same branch.

## 1. Problem

The engineering cold start (`AGENTS.md`, `AI_START_HERE.md`,
`CURRENT_STATE.md`, `AI_HANDOVER.md`, `CLAUDE.md`) is 8,712 words. About
3,400 of them are duplicates of other files, historical narrative whose
owner is `project/build_history.json` or `docs/history/`, or rules about
tooling that does not exist in the repository. Sixteen test-pinned
headings and sentences constrain what may move.

## 2. Cuts (each one: file, section, target, why meaning is preserved)

| # | File and section | Action | Why meaning is preserved |
|---|---|---|---|
| 1 | `AGENTS.md` `## graphify` (whole section) | delete | No graphify skill or `graphify-out/` exists in the repo; no test references it; a rule about an absent tool is noise |
| 2 | `AGENTS.md` `## Mandatory session start / close`, the comment-only-PO-episode paragraph | move to `docs/design/GOV_PO_ROLE_MIGRATION.md` §5 as an appended block; keep in `AGENTS.md` the two pinned strings `**Comment-only Product Owner assistant episodes.**` and `never a substitute for a` … `RELAY_DECISION` as one two-sentence paragraph with a pointer | PO-only law leaves the constitution every engineer reads; the law itself stays, pointed to |
| 3 | `AGENTS.md` `Contradiction report (2026-09-11)` entry | shorten to one sentence keeping the heading text `Contradiction report` | test pins the phrase, not the length |
| 4 | `AI_START_HERE.md` `### Directory map` | 25 rows → at most 12 subsystem rows, one line each; history phrases (NAV.1, M2, M3) removed; the existing pointer to `docs/ARCHITECTURE.md` stays | detail already lives in `docs/ARCHITECTURE.md` |
| 5 | `AI_START_HERE.md` `### CLI modes` | keep the six modes a cold session runs; move the rest to `docs/ARCHITECTURE.md` (append a "CLI reference" section there) | nothing deleted, relocated to the lookup file |
| 6 | `AI_START_HERE.md` PCP.1 provenance note and NAV.1 history in the map | delete | history is in `docs/history/` |
| 7 | Action taxonomy table, four copies (`CURRENT_STATE.md`, `README.md`, `PROJECT_VISION.md`, `AGENTS.md` `## Network action taxonomy` if it restates the table) | one canonical table stays in `AI_START_HERE.md`; each other copy becomes one pointer line; `AGENTS.md` keeps its rule text and the phrase `network-device command gate` | test-pinned phrases stay; the table has one owner |
| 8 | `CURRENT_STATE.md` `## Active build` predecessor list (20 builds) | replace with one line pointing to `project/build_history.json`; keep `now_next.now.build` mention (pinned) | owner is build_history |
| 9 | `CURRENT_STATE.md` `## Predecessor — M3`, `OP.0b.0`, PAN HA blocks | reduce to the live blocker rows under `## Open blockers`; each removed block becomes one line naming its design doc | each has its own design document |
| 10 | `AI_HANDOVER.md` `## Operating role` | replace with one pointer to `PO.md` and `docs/reference/COPILOT_OPERATING_MODEL.md`; keep the two pinned banner lines | its own text says the source is elsewhere |
| 11 | `docs/reference/COPILOT_OPERATING_MODEL.md` decision record narrative | keep the bullet list; delete the narrative restatement and the per-role restatement; keep `independent seat` wording and the `AI_START_HERE.md` pointer (pinned) | same decisions, said once |
| 12 | `docs/reference/SECURITYEXPERT_TARGET_ARCHITECTURE.md`, `docs/reference/UI_EXPERT_REVIEW.md` | `git mv` to `docs/history/reference/` | zero inbound references from agent-path files or tests |
| 13 | `.github/prompts/po-knowledge-extraction.prompt.md` | `git mv` to `docs/history/prompts/` | one-time extraction, completed; output is `PRODUCT_DIRECTION_RECORD.md` |
| 14 | `PRIVACY_AND_DATA_HANDLING.md` `## UI 2.0 database` | move to `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` as an appendix if not already covered there, else delete | unbuilt feature text belongs with its contract |
| 15 | `PO.md` §"Read it, then" | point "Exact next action" to `AI_HANDOVER.md` (where the heading lives) | fixes a broken pointer |
| 16 | `CLAUDE.md` reasoning routing | keep the tier bullet list, delete the prose paragraph that restates it | duplicate |

Do not touch: `docs/design/GOV_PO_*.md` bodies beyond cut 2, packet
schema docs, `relay/`, any test file except as §4 requires.

## 3. Test pins that must survive (verify each with grep before commit)

`tests/test_architecture_convergence.py`: `CURRENT_STATE.md` ≤ 200
lines and contains the roadmap `now_next.now.build`; `AI_HANDOVER.md`
keeps `NON-AUTHORITATIVE DERIVED SUMMARY` and `DO NOT USE AS
PROJECT-STATE AUTHORITY`; the string `No device write/change automation
is permitted` never reappears; `AGENTS.md` keeps `Engineering-output
language law`, `English by default`, `opaque`, `MATCH`, `MISMATCH`,
`NOT_EVALUABLE`, `network-device command gate`, `AUTOMATED_VALIDATED`,
`REAL_ENV_VALIDATED`, `Evidence identity != operational identity`,
`Readiness != authorization`, `## Git authority and execution law`,
`does not require the human to type the command or click`, `Do not ask
for the same permission again`; `.github/prompts/relay-bootstrap.prompt.md`
cited in `AGENTS.md`, `AI_START_HERE.md`, `CLAUDE.md`,
`.github/copilot-instructions.md`, `build-start`, `build-close`.
`tests/test_worker_brief.py`: the orchestrated-worker sentence in
`AI_START_HERE.md` verbatim; exactly one `SESSION START` and one
`SESSION CLOSE` heading outside `docs/history/**`; `Contradiction
report` in `AGENTS.md`; `GOV_PO_ROLE_MIGRATION.md` amendment heading and
FROZEN line. `tests/test_gov_po_role.py`: the two comment-only-episode
strings in `AGENTS.md`; `CLAUDE.md` keeps `never invokes` …
`nexus-decision-council`. `tests/test_gov_relay_protocol.py`: relay
bootstrap citations.

## 4. Acceptance criteria

- AC-1: Word counts after the movement: `AGENTS.md` ≤ 2,700,
  `AI_START_HERE.md` ≤ 1,900, `CURRENT_STATE.md` ≤ 800,
  `AI_HANDOVER.md` ≤ 250, `CLAUDE.md` ≤ 400 (measured with `wc -w`);
  a new test `tests/test_cold_start_budget.py` asserts these ceilings so
  they cannot silently regrow.
- AC-2: Every pin in §3 still holds (the existing tests are the
  proof; run them).
- AC-3: Every removed passage either exists verbatim in its new
  location or is listed in the SESSION_CLOSE `changed` list as deleted
  with the cut number; nothing else is reworded.
- AC-4: The action taxonomy table exists exactly once outside
  `docs/history/**` (add this assertion to `test_cold_start_budget.py`).
- AC-5: `git mv` used for cuts 12 and 13 (history preserved); no file
  deleted.
- AC-6: `git diff --check` clean; privacy gate 0 new findings; full
  docs-related test set green.

## 5. Validation plan (machine-readable)

```
python3 -m pytest -q tests/test_cold_start_budget.py tests/test_architecture_convergence.py tests/test_worker_brief.py tests/test_gov_po_role.py tests/test_gov_relay_protocol.py tests/test_project_queue.py
python3 scripts/repository_privacy_check.py
git diff --check
```

## 6. Worker route

`Sonnet 5, normal (low effort)`. Every cut names its section, action
and pin; no design judgement is left open.
