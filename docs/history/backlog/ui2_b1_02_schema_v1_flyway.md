# UI2 B1-2 -- schema V1 via Flyway (C1 tables, audit table, projections for the first capability)

status: automated_validated · target: workflow §5 B1-2

2026-09-12: AUTOMATED_VALIDATED. Contract docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md is FROZEN (2026-09-12) and V1 is implemented; commit bad6596 proved the ui2/integration-tests suite against a live PostgreSQL 16 server -- 34 executed / 0 failed, including the audit fail-closed, DDL-denied (SQLState 42501) and second-migrate-no-op proofs. Not REAL_ENV_VALIDATED and not DONE: no device was contacted, and UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md section 9 states REAL_ENV_VALIDATED is unreachable for every B1 row until device contact is separately authorized.
