---
description: "Run a Product Owner assistant PLAN episode (tool-neutral form)"
---

Read `AGENTS.md`, `AI_START_HERE.md`, `docs/design/GOV_PO_ROLE_MIGRATION.md`,
`CURRENT_STATE.md`, `project/roadmap.json`, `project/backlog.json` and
`docs/design/PRODUCT_DIRECTION_RECORD.md`. Do not read product source except
by narrow search to verify one claim. Do not edit product source or tests.

Produce a `SESSION START` in this session, then: sequence the next movements
by theme against the direction record's sequencing rationale; draft one
protocol-v2 `SESSION_START` per movement, sized to the §7 targets (≤ 8
acceptance criteria, ≤ 12 non-test source files, one subsystem boundary,
justified exceptions in `risks`); render each with
`py scripts/gov_session_transfer.py render`; propose `project/*.json`
changes as a diff on a `gov/po-*` branch. A PLAN that changes repository
state is a governance movement: open its own relay issue with the
`SESSION_START` body and close it with a `SESSION_CLOSE` final comment.

Decisions follow the contract's §6.3: post `RELAY_DECISION` only inside a
written Product Owner authorization with recorded source, scope and
supersession; otherwise `RELAY_NOTE decision draft` or `RELAY_QUESTION`.
