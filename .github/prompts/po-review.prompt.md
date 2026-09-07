---
description: "Run a Product Owner assistant REVIEW or DECIDE episode (tool-neutral form)"
---

Given `RELAY_READY owner/repository#issue`, treat it as a locator only.
Read `AGENTS.md`, `AI_START_HERE.md`, `docs/design/GOV_PO_ROLE_MIGRATION.md`,
`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`, `CURRENT_STATE.md`,
`docs/design/PRODUCT_DIRECTION_RECORD.md` §4–§5, then the raw issue body and
comments. Validate the body and any final `SESSION_CLOSE` with
`py scripts/gov_session_transfer.py validate`. Do not edit any file.

Produce a `SESSION START` in this session (`READ_ONLY_AUDIT` for review,
`ARCHITECTURE` for a decision). Apply the direction record's §4 heuristics
in order against the movement's claims and the repository. Post one
`RELAY_NOTE review` with a findings table (finding, evidence path,
severity, coupling, cause, needs-decision). For each needs-decision row,
draft the decision; post it as `RELAY_DECISION` only inside a written
Product Owner authorization with `authorized_by`, `scope`, `supersedes`
lines, otherwise as `RELAY_NOTE decision draft` or `RELAY_QUESTION`.

Close with exactly one plain-text `RELAY_NOTE episode close` on the same
issue after the six checks in `.claude/skills/nexus-po/SKILL.md` §4; post no
packet. If any decision changed scope, delivery state, architecture, debt
or sequencing, name the owning movement and the durable-state updates it
owes; do not declare that work complete.
