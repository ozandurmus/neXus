# NetworkPolicy restricting in-cluster reach to PostgreSQL

status: planned · target: UI2_0_B1_01C successor

UI2_0_B1_01C fixes ClusterIP-only Services, so the database has no address outside the cluster. It is NOT protected from another pod INSIDE the cluster: any workload there can open TCP 5432. A NetworkPolicy admitting only the service/worker/scheduler pods closes it. Raised by the Product Owner on 2026-09-13 asking what restricts database access; no contract covers it. Note this grows in importance under PO_DECISION_RECORD_2026_09_13D, which allows several deployable services sharing one database.
