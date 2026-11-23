# UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

status: planned · target: PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 database roles: least-privilege runtime roles per service, decided together with 13D DATA-OWNERSHIP (one schema vs. one per service)

Target (full): PO_DECISION_RECORD_2026_09_13D section 3 DATA-OWNERSHIP; UI2_0_C1 (Flyway sole authority, audit trigger)

2026-11-14 backlog truth: still open (P2, size M). Today: Every module can read/write every table; one compromised pod exposes the whole DB. Evidence: All services (policy, packet analysis, worker, service) use one shared app DB role (deploy/ui2/*-deployment.yaml UI2_DB_APP_USER_FILE)

2026-11-16 step 1 built (V242, per-module NOLOGIN roles and the least-privilege grant matrix, nothing switched); AUTOMATED_VALIDATED by DatabaseRoleGrantMatrixIntegrationTest, REAL_ENV pending (BEGIN/ROLLBACK dry run). Step 0 is larger than nine triggers (26 INVOKER trigger functions, finding F1), then cutover steps 2-6. Detail: docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md section 6-7.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): step 1 only is live: V242__module_database_roles_grants.sql (renumbered from V238) creates the per-module NOLOGIN roles and the least-privilege grant matrix (DatabaseRoleGrantMatrixIntegrationTest); no workload is switched to its role yet, every module still uses the shared app role. Ingress NetworkPolicy ui2-db-ingress (deploy/ui2/46-database-networkpolicy.yaml) was applied by hand live 2026-11-16 as the layer-2 reach limit. Remaining: step 0 trigger functions (26 INVOKER functions, finding F1), cutover steps 2-6 per module, external-DB path. Real-env: the V242 BEGIN/ROLLBACK dry run is not recorded here.

2026-11-23 branch review: old branch feature/ui2-db-roles-ownership (PR #415, DatabaseOwnershipBoundaryTest, per-role launcher/deployment split) is unmerged and was cut before the repository restructure; main has no equivalent ownership-boundary test. Re-implement on main under this item; do not merge the branch. See docs/reviews/BRANCH_REVIEW_20261123.md.
