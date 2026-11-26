# Zone matrix step 4b-2: run lifecycle, triggers, routes and tabs

status: planned · target: After step 4b-1 (1588f36f, V267)

Heap ceiling of contract 5.1 not enforced, needs the in-pod measurement; V267 needs the BEGIN/ROLLBACK dry-run before rollout.

2026-11-25: Overview attention row (contract step 5) from the stored summary; severity mapping is an engineering default awaiting the PO

2026-11-25: Stored summary carries per-cell open-violation counts (`cell_counts` in `GET /api/v2/zone-matrix/summary`, contract 7.1 Matrix, 7.2); a rule in both headline sets counts only as a headline rule, and the Matrix and Violations tabs can read the key now
2026-11-25: Violations and Coverage tabs on GET /api/v2/zone-matrix/summary (frontend); evidence-report wiring remains.

2026-11-25: On-demand durable run reachable (contract 5.1 third trigger): `POST /api/v2/zone-matrix/runs` (existing action `zone_matrix_evaluate`, 202 with `run_id`, `already_running`, `state`; ZM-40 byte limit applies) and CLI `zone-matrix-run <policyId> [--version <versionId>]`; no UI button is added, `POST /api/v2/zone-matrix/evaluate` is unchanged
2026-11-25: Matrix tab shows per-cell open-violation counts on the active version and opens a pair's findings; Violations gets source/destination zone filters and a per-cell counts panel; Versions shows resolved-by-matrix-version counts (frontend); evidence-report wiring remains.
- 2026-11-26: Coverage strip on every tab (contract 7.1)
