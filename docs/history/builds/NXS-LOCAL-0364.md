# NXS-LOCAL-0364 — Configuration screen rebuilt on the old product's shape with a cluster DIFF view

status: automated_validated · completed: 2026-09-22 · movement: IMPLEMENTATION

Inventory tree on the left (no virtual-system nodes), operator snapshot and per-section SETTING / CURRENT VALUE / ORIGIN / CONTEXT tables from a pure client-side projection of the sanitized text, cluster members side by side with DIFF vs MEMBER-specific, platform identity card and members table. PAN text-available flag, member-specific rule and effective-running text fixed from the aiview tour; Overview and Operations metric cards now carry their figure.

## Authority and evidence

- `docs/history/backlog/ui2_inventory_and_configuration_design_language.md`

## Real-environment note

Deployed to HOST-A the same day (run_build.sh, rollout verified). Screens inspected under the aiview persona; device-facing reads graded automated_validated until the next Bulk Collect confirms them on gateways and VSX members.
