# DEV.0.2 — Repository Sanitization & Corporate Git Candidate

## Objective
Produce a repository-safe corporate Git candidate without changing collector logic, network behavior, runtime path semantics, or evidence contracts.

## Changes
- Hardened `.gitignore` so runtime/operational state, credentials/trust material, generated archives/databases, caches and local AI/IDE state are outside the Git candidate.
- Moved Copilot repository instructions to the native `.github/` location.
- Replaced the remaining known real-environment identity/IP in active tests and current-state documentation with deterministic synthetic/documentation values.
- Removed the obsolete generated root `NewPage.txt` artifact.
- Updated living project metadata with DEV.0 engineering track, DEV.0.1 completion, DEV.0.2 current state, CP interactive real-environment validation, CP safety audit, repository privacy gate and clean-baseline dependency UX backlog.

## Explicitly out of scope
- moving `data/`, `output/`, `logs/` or CAS to a new runtime root
- collector path/API refactors
- server/container paths
- credential provider/Vault integration
- PAN TLS trust or CP SSH host-key hardening
- CP polling/concurrency changes
- network command changes
- Git initialization/push

## Acceptance
- No known production endpoint/operator/local-user identity remains in active source/current metadata/tests.
- Runtime/generated/secret-bearing classes are excluded from the corporate Git candidate.
- Existing automated regression remains unchanged except for synthetic test values and project metadata expectations.
- DEV.0.3 receives runtime path relocation/read-only application-root concerns rather than mixing them into this build.
