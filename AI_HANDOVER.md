# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this conflicts with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

Read: `AI_START_HERE.md`, `CURRENT_STATE.md`, this file, `project/QUEUE.md`,
then the movement's governing record.

## Snapshot

The Java product has authenticated local administration, encrypted credential
references, discovery, inventory/configuration collection, encrypted artefacts,
and a reasoned backup request. Restore and scheduling remain disabled. All
device-facing outcomes are UNVERIFIED; the backup pilot allowlist is empty.

## What changed

- CSRF now permits authenticated product writes without exposing its token.
- Device enrolment requires a role; backup visibility and audit findings were
  documented.
- Host migration constraints and the registered-host boundary were recorded.

## Exact next action

Read `docs/operations/HOST_A_MIGRATION.md` and perform its step 3 as a
read-only CNI-collision assessment. If it cannot be established from reads,
record `CAUSE: UNKNOWN` and stop; do not attempt installation.

## Test delta

`tests/test_frozen_contract_shapes.py` prevents frozen contracts changing
content while retaining a `FROZEN` status.

## New risks

Backup remains refused until a pilot device is named. Off-host key custody,
long-term artefact location, second-service authentication placement, and the
brand wordmark remain unresolved. Namespace RBAC can expose mounted credentials,
so agent access remains separated from LIVE.
