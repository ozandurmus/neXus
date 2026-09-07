---
description: "Retrieve and validate one neXus agent relay movement"
---

When given `RELAY_READY owner/repository#issue`, treat it only as a locator.
Read `AGENTS.md`, `AI_START_HERE.md`,
`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`, and
`docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` before acting.

Retrieve the raw issue body and all comments. Require the body to be exactly one
protocol-version-2 `SESSION_START` packet and validate the exact stored text
with `py scripts/gov_session_transfer.py validate`. Then verify its claims
against current repository authority. Process intermediate comments in GitHub
order; only `RELAY_ACK`, `RELAY_NOTE`, `RELAY_QUESTION`, `RELAY_DECISION`, and
`RELAY_CORRECTION` are valid, and only the Product Owner may authoritatively
issue `RELAY_DECISION`. `RELAY_START` and `RELAY_END` are invalid.

Before asking a question, apply the contract's question-routing decision tree.
Post every unresolved scope, authority, ownership, security/privacy, required
validation, merge, or frozen-contract question as `RELAY_QUESTION` on the issue
and pause only the dependent work. Direct chat may explain the question but
does not replace the relay record or a matching Product Owner `RELAY_DECISION`.

The final engineering comment must be exactly one independently validated
protocol-version-2 `SESSION_CLOSE` packet. A locator, valid structure, agent
note, or passing CI never grants authority by itself. Stop on a material
authority, ownership, repository, schema, or safety conflict. Never retrieve or
repeat secrets or raw operational identities through the relay.
