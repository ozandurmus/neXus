# ui2_b1_01_skeleton_ci_docker — UI2 B1-1 -- skeleton, CI and Docker contract (DRAFT, for Product Owner freeze)

## Summary

The worker added the DRAFT B1-1 contract for ui2 module boundaries, dependency direction, reproducible builds, isolated CI, Docker, Testcontainers/Flyway, and 15 runnable checks. Contract only; no ui2 source, Line-1 source, validation workflow, or device behavior changed.

## Evidence

Worker relay NXS-LOCAL-0061; contract self-check AC-1..AC-9; architecture/state tests 22 passed; repository privacy gate 0 new findings against origin/main; diff check clean.

## Risks forward

The contract remains DRAFT pending Product Owner freeze. No implementation, CI run, PR, or merge was completed by the worker because GitHub push authorization was unavailable at worker close.

2026-09-12 (DOCS movement, branch claude/inspiring-maxwell-gazhy3): retired the never-frozen B1-1 draft as authority. docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md is now SUPERSEDED by the FROZEN docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md; body, amendments and corrections retained as the historical record. Sixteen citations across UI2_0_B1_02/03/04/04B/07 were repointed clause-by-clause to B1-1a (module map -> section 2, dependency direction -> section 3, build/reproducibility -> section 4, test harness -> section 5). One citation could NOT be repointed and is flagged in place as withdrawn predecessor material: UI2_0_B1_02 section 7 item 4's reference to the predecessor's single AuditContextIntegrationTest, which B1-1a section 5 and section 10 item 2 withdraw with no successor clause. A leaked absolute developer home path was redacted from 13 tracked files / 17 lines (9 docs, 4 relay records, redaction-marker only, no narrative rewritten). Six stale B1 queue rows were synced to their evidence; ui2_b1_02_schema_v1_flyway is AUTOMATED_VALIDATED on commit bad6596 (34 integration tests executed / 0 failed against a live PostgreSQL 16), and nothing advanced to REAL_ENV_VALIDATED or DONE because no device was contacted. New gate tests/test_contract_authority_status.py fails if a FROZEN contract cites a DRAFT or DO NOT FREEZE document as authority.
