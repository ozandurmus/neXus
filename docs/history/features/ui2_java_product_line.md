# ui2_java_product_line — UI 2.0 -- Java product line (baseline adopted, B0 contracts open)

## summary

BASELINE ADOPTED 2026-09-09: docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md (rev 2) and docs/design/UI2_0_BASELINE_CONTRACT.md (FROZEN -- PRODUCT OWNER APPROVED). Java is the only product development line; Line-1 (Python) is the reference/maintenance line with no new product features. B0 contracts C1-C7, the baseline directory and the extraction tooling are the primary backlog (project/backlog.json category "UI 2.0 (Java product line)"); B1 (skeleton, collection engine core, first CP inventory capability, two acceptance scenarios, second PAN export flow) follows once C1-C4 and the authorization/audit rows are frozen.

## why

The Line-1 feature scripts were written to feed static, task-specific pages. The product goal (BackBox-style device operations, profile-driven backup and restore, multi-admin RBAC, jobs with audit) needs one runtime, one executor and one schema; the PO rejected any direct reuse of Python code in UI 2.0 and adopted the reference-not-runtime direction after a council round and three external second-opinion rounds.
