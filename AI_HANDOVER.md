# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role for the next session

`roles/ENGINEER.md` carries the reading order. PO assistant: `PO.md`.

## 1. Snapshot

- Branch `claude/inspiring-maxwell-gazhy3`; PR #182 merged (GOV.ORCH.1-8), PR #183 open (UI 2.0 B1 core).
- GOV.ORCH.1-8 and B1-2/3/4/4b/5/7 contracts FROZEN; cross-contract gaps adjudicated in `docs/design/UI2_0_B1_ADJUDICATION_2026_09_12.md`.
- `ui2/` exists: eleven Gradle modules, ten proven ArchUnit rules, Flyway V1-V4, pinned dependency verification.

## 2. What changed

- Eight governance contracts replaced the Codex orchestrator: synchronous `run`, provider adapter, worker brief + git gates, workbench, `project/QUEUE.md`, documentation diet, role files, project data split.
- Public history rewritten across 134 branches to purge customer identities; privacy gate reports zero findings on tracked files.
- Backup outcome decided as `Partial` with per-vendor tables (`docs/design/UI2_0_BACKUP_OUTCOME_DECISION_2026_09_12.md`).

## 3. Exact next action

1. Install JDK 21 on the Mac (`brew install --cask temurin@21`) — nothing in `ui2/` builds without it.
2. Run V1-V4 against `quay.io/sclorg/postgresql-16-c9s` (CRC's imagestream maxes at 13) and prove two refusals: `INSERT INTO devices` as `ui2_app` fails `audit_context_missing`; `CREATE TABLE` as `ui2_app` fails on permissions.
3. Then write the 29 disabled test bodies.

## 4. Test delta

Architecture, state-consistency, cold-start-budget and privacy gates green. No real-environment evidence yet for migrations or collection.

## 5. New risks

- **Open PO decision:** `docs/design/CP_BACKUP_VENDOR_CONTRADICTION_2026_09_12.md` — Check Point documents `add backup local` as asynchronous; the frozen profile asserts blocking. Unresolved; do not implement against the blocking assumption.
- Five ASSERTED vendor claims remain, chiefly FAILOVER preflight conditions that would fail *open*.
- No Docker/Podman on the Mac; only Red Hat OpenShift Local.
- Stale branch `origin/build/ui2-d1-c7-c2-frozen-amendments` still needs deletion.
