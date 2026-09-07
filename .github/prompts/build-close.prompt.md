---
description: "Close a SecurityExpert build and persist durable project state"
---

Work in `RELEASE_HANDOVER` movement.

Review the accepted implementation, tests and supplied real-environment evidence.
Update CURRENT_STATE.md and only the project metadata/build docs whose semantics
changed. Preserve historical outcomes.

Produce `SESSION CLOSE` using the repository template: status reached, completed
work, changed components, preserved invariants, tests, real-environment evidence,
known gaps, durable state updated, rollback, exact next build/task, next movement
type, recommended reasoning level, chat continuation recommendation, one
preferred next validation/first command, recommended branch/PR target, explicit
main-merge decision (approved/blocked with reason), exact non-interactive
Git dispatch commands for the recommended path, and explicit `main.py/UI effect`
statement (expected visible behavior or backend-only/no visible UI delta).

If this `SESSION CLOSE` crosses a session/tool boundary (must be returned to
a Product Owner or another session/tool), transport it as exactly one
sentinel-wrapped protocol-version-2 `NEXUS_SESSION_PACKET` per
`docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` — build and validate it with
`py scripts/gov_session_transfer.py render` first. The packet's `report`
object carries the complete content above, field by field; no heading,
summary, or explanation outside the sentinel pair in that case.

For a GitHub-issue relay, also follow
`.github/prompts/relay-bootstrap.prompt.md`: intermediate comments use only the
frozen relay markers, and the final engineering comment is the raw validated
`SESSION_CLOSE` packet.
