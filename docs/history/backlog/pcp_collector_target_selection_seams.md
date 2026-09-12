# Collector target-selection seams for registry-keyed typed jobs (CP inventory is plane-wide today)

status: in_progress · target: PCP.6

Recorded by the 2026-09-05 Product Control Plane architecture (section 9): CP inventory runs one MDS script over all gateways with name-based exclusions, PAN runtime is per-serial, recovery collection is already selective. Per-device typed jobs need each collector to accept a registry-derived target set. Real work, not assumed; must not raise aggregate per-endpoint contact frequency. M5 (2026-09-06, automated_validated) closed the first seam: cp-config's existing OP.0d --cp-config-targets selector is now reachable through utils.collection_executor.workflow_argv(), the shared scheduler/console argv path -- CP inventory (cp), VSX and pan-config still have no seam and remain honestly plane-wide.
