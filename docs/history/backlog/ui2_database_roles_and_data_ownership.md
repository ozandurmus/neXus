# UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

status: planned · target: PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

Target (full): PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

2026-11-14 backlog truth: still open (P2, size M). Today: Every module can read/write every table; one compromised pod exposes the whole DB. Evidence: All services (policy, packet analysis, worker, service) use one shared app DB role (deploy/ui2/*-deployment.yaml UI2_DB_APP_USER_FILE)
