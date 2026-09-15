# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this conflicts with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

Read: `AI_START_HERE.md`, `CURRENT_STATE.md`, this file, `project/QUEUE.md`,
then the one record the task names. You are the Product Owner assistant;
`roles/PO.md` is your whole cold start and `roles/PO_TAKEOVER_CHECK.md` gates
your first dispatch.

## Snapshot

The Java product runs on HOST-A under k3s, reachable over Traefik with a
self-signed certificate. Authenticated local administration, discovery, device
add, inventory and configuration collection, encrypted artefacts, a backup tab
gated to an empty pilot allowlist. Restore and the scheduler stay disabled.
Every live outcome is UNVERIFIED.

## What changed

The environment moved off the laptop. Phases A, B and C are in
`docs/operations/HOST_LEDGER_HOST-A.md`; how it is actually operated, and the
four places the corporate proxy bites, are in `HOST_A_RUNBOOK.md`.

Dispatched workers could never commit: a linked worktree's index and objects sit
outside the sandbox's writable roots. Fixed. They could not run Java either.
Fixed. The Java test tree had not compiled on main for weeks and CI never ran
it; both fixed.

A failed job now records why. Retrieval is fail-closed on its audit. Session
idle timeout is five minutes.

## Exact next action

`NXS-LOCAL-0219`: the service crash-loops on deployment. `#374` moved migrations
into a `BeanFactoryPostProcessor`, which runs before placeholder resolution, so
the migrate credential path arrives unresolved. The previous image is the
rollback. Do not touch the mount or the Secret — both are correct.

## Test delta

Java tree compiles again; regression runs on core changes.

## New risks

Start-up ordering is provable only by a real deployment. No packet required one,
and a green movement crash-looped. A movement that changes start-up order is not
done until it has started.
