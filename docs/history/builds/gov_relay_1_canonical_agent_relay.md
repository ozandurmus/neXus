# gov_relay_1_canonical_agent_relay — GOV.RELAY.1 -- canonical GitHub-issue agent relay

## Summary

Defines RELAY_READY as a locator, freezes the issue-body/final-comment packet shape and intermediate relay vocabulary, and gives Codex and Claude one shared bootstrap prompt. Preserves the NEXUS_SESSION_PACKET v2 schema/parser unchanged and introduces no product, M9, device, UI, database, credential, deployment or production behavior.

## Evidence

Dedicated branch gov-relay-1-canonical-protocol from verified origin/main 06bab222a0274b350753c6abff0f28bbebd28c23. Added docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md and .github/prompts/relay-bootstrap.prompt.md; added pointers from AGENTS.md, AI_START_HERE.md, CLAUDE.md, .github/copilot-instructions.md and build-start/build-close prompts; added focused relay and architecture-convergence tests. Existing scripts/gov_session_transfer.py was not modified. Validation: focused relay/session-packet/convergence 149 passed; application-package/privacy tests 14 passed; repository privacy gate PASS with 0 findings; CURRENT_STATE.md exactly 200 lines; build-history index check and git diff --check clean. PR #105 validate passed and true-merged at 35e2fe883871f03cfbe4781edd1b48065a15d4c7; governance head a13c7d22fb4d29fa20d57d97683d25889d527a5c is its ancestor. Relay #3 then received RELAY_CORRECTION, its body was replaced with the officially rendered M9 SESSION_START, the GitHub-stored body validated true, and RELAY_NOTE recorded merge/movement/ownership.

## Risks forward

Claude's M9 branch remains untouched at reviewed head 8bd57cd18a9d703edd7610bfc320950bd666da79 and PR #104 remains open/unmerged. The preserved malformed relay comment is evidence only and explicitly not authority; Claude resumes from the validated issue body and current repository authority.
