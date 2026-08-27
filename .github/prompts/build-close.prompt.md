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
