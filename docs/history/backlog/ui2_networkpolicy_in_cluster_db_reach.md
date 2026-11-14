# NetworkPolicy restricting in-cluster reach to PostgreSQL

status: planned · target: UI2_0_B1_01C successor

UI2_0_B1_01C fixes ClusterIP-only Services, so the database has no address outside the cluster. It is NOT protected from another pod INSIDE the cluster: any workload there can open TCP 5432. A NetworkPolicy admitting only the service/worker/scheduler pods closes it. Raised by the Product Owner on 2026-09-13 asking what restricts database access; no contract covers it. Note this grows in importance under PO_DECISION_RECORD_2026_09_13D, which allows several deployable services sharing one database.

2026-11-14 backlog truth: still open (P1, size S). Today: Any pod in the cluster can open the database port. Evidence: Contract frozen PR 395; deploy/ui2 has policies only for service/policy/packet-analysis/advisory pods, none selecting the database pod

2026-11-14 verified live: the ui2 namespace has five NetworkPolicies (advisory-fetch x2, packet-analysis, policy, service machine port); none selects the database pod.
