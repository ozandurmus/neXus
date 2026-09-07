# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-07. `gov_relay_1_canonical_agent_relay` (`GOV.RELAY.1`) —
  **AUTOMATED_VALIDATED** on `gov-relay-1-canonical-protocol`; governance-only
  PR/integration and relay #3 repair remain.
- Claude remains the sole M9 implementation owner; its branch is untouched.
- M8.3 stays deferred and M7 stays blocked.

## 2. What changed

- Added the frozen relay contract and one shared Codex/Claude bootstrap prompt.
- Added mandatory pointers from the constitution, cold start, Claude, Copilot,
  and build start/close instructions.
- Recorded the PO relay decision and added focused governance/convergence tests.
- Preserved `NEXUS_SESSION_PACKET` v2 schema/parser behavior unchanged.

## 3. Exact next action

Open and integrate the governance-only PR after required checks pass, verify
`origin/main` ancestry, then repair `ozandurmus/nexus-agent-relay#3` exactly per
GOV.RELAY.1 and return Claude to M9 ownership through `RELAY_READY`.

## 4. Test delta

Focused relay/session-packet/convergence: 149 passed. Application-package and
privacy tests: 14 passed. Repository privacy gate: PASS/0; state consistency,
history index and `git diff --check`: clean. No full regression, risk-based.

## 5. Risks / notes forward

- Relay #3 currently contains a malformed corrective handoff that must be
  preserved through `RELAY_CORRECTION`, not treated as authority.
- No device, product, UI, database, credential, deployment or production
  behavior belongs in this governance movement.
