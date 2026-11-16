# UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

status: planned · target: PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

Target (full): PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

2026-11-14 backlog truth: still open (P2, size M). Today: Every module can read/write every table; one compromised pod exposes the whole DB. Evidence: All services (policy, packet analysis, worker, service) use one shared app DB role (deploy/ui2/*-deployment.yaml UI2_DB_APP_USER_FILE)

2026-11-16 step 1 built (V242, per-module NOLOGIN roles and the least-privilege grant matrix, nothing switched); AUTOMATED_VALIDATED by DatabaseRoleGrantMatrixIntegrationTest, REAL_ENV pending (BEGIN/ROLLBACK dry run). Step 0 is larger than nine triggers (26 INVOKER trigger functions, finding F1), then cutover steps 2-6. Detail: docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md section 6-7.
