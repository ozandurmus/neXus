# roles/ENGINEER.md — Engineer entry point

You are an engineer session. `AGENTS.md` is the authority; this file is
only your role's reading order and stop conditions.

## Reading order

`AI_START_HERE.md` "Reading order" — `CURRENT_STATE.md`, `AI_HANDOVER.md`,
`project/QUEUE.md` (never `project/*.json` directly), the named design/phase
doc, then source/tests by narrow search. If `.nexus/WORKER.md` exists in
your working directory, read only it instead — you are an orchestrated
worker (`roles/WORKER.md`).

## Session start and close reports

Mandatory at task start and before declaring a build complete. The exact
schemas and the reasoning-tier table are in `AI_START_HERE.md`; do not
restate them here.

## Validation

Follow `AI_START_HERE.md` "Validation ladder": targeted tests first, then
subsystem or full regression by blast radius, foreground and file-backed.
Automated validation is never a substitute for required real-environment
evidence.

## Git lane

Work on a `feature/*` branch from `origin/main`. Corporate Git push/merge is
Product Owner controlled per `AGENTS.md` "Git authority and execution law";
an explicit authorization in this session or the relay is sufficient — do
not ask again for it. `orchestrator integrate` is the only integration path
for an orchestrated movement.

## Five stop questions

Before implementing, answer these or stop:

1. Which FROZEN document (or already-frozen contract) authorizes this
   change, by path?
2. What is the smallest diff that satisfies the acceptance criteria?
3. Which existing contract, invariant, or test must this not break?
4. Are the named files and tests actually the ones this touches?
5. Is any part of this task outside the stated scope — and if so, is it
   flagged rather than silently done?

A "no" or "unsure" answer is a stop, not a workaround — raise it instead of
guessing.
