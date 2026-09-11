# gov_relay_1_question_routing — GOV.RELAY.1 amendment -- durable Product Owner question routing

## Summary

Adds RELAY_QUESTION and a mandatory decision tree so every material question requiring Product Owner resolution is durable on the relay issue and closed only by a matching Product Owner RELAY_DECISION. Preserves NEXUS_SESSION_PACKET v2 and introduces no product, M9, device, UI, database, credential, deployment or production behavior.

## Evidence

Dedicated governance branch gov-relay-1-question-routing from origin/main 2758341bf6b80194bd80210d6382593ee11a7c76. Amended the frozen relay contract and shared bootstrap/build prompts; added focused tests for durable question routing and matching Product Owner decisions. scripts/gov_session_transfer.py and Claude's M9 branch were not modified. Validation: relay/session-packet/architecture-convergence 150 passed; application-package/privacy tests 14 passed; repository privacy gate PASS with 0 findings; CURRENT_STATE.md 194 lines; build-history index check and git diff --check clean.

## Risks forward

Claude remains the sole M9 implementation owner. A material question may pause only its dependent action; unrelated authorized work continues.
