# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-07. `gov_relay_1_canonical_agent_relay` (`GOV.RELAY.1`) —
  **AUTOMATED_VALIDATED, MERGED** via PR #105; relay #3 repaired and its stored
  M9 `SESSION_START` revalidated.
- Claude remains the sole M9 implementation owner; its branch is untouched.
- M8.3 stays deferred and M7 stays blocked.

## 2. What changed

- Added the frozen relay contract and one shared Codex/Claude bootstrap prompt.
- Added mandatory pointers from the constitution, cold start, Claude, Copilot,
  and build start/close instructions.
- Recorded the PO relay decision and added focused governance/convergence tests.
- Preserved `NEXUS_SESSION_PACKET` v2 schema/parser behavior unchanged.

## 3. Exact next action

Claude resumes `M9_ENROLLMENT_PREVIEW_CONFIRMATION_UI` from the validated body
of `ozandurmus/nexus-agent-relay#3`; PR #104 stays open and unmerged pending a
later Product Owner integration decision.

## 4. Test delta

Focused relay/session-packet/convergence: 149 passed. Application-package and
privacy tests: 14 passed. Repository privacy gate: PASS/0; state consistency,
history index and `git diff --check`: clean. No full regression, risk-based.

## 5. Risks / notes forward

- Relay #3 preserves the malformed corrective handoff under `RELAY_CORRECTION`;
  it is evidence only and not authority.
- No device, product, UI, database, credential, deployment or production
  behavior belongs in this governance movement.
