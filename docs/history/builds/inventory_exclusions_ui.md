# inventory_exclusions_ui — Read-only Inventory Exclusions module (phase 1)

## Summary

New utils/inventory_exclusions_ui.py pure payload builder (build_inventory_exclusions_payload), modeled on discovery_capability_ui.py: vendor + identity + reason per entry, fleet_summary.total_exclusions/.vendor_counts, source (missing vs runtime-policy). New Exclusions nav item + panel + renderExclusionsModule() in templates/index.html/static/app.js, following the Discovery module's structure. html_export.py loads the policy via the already-threaded load_inventory_exclusions(compliance_data_root) -- no main.py call-site changes needed, unlike discovery_ui's explicit store kwargs. A malformed local policy degrades to an explicit empty state on render rather than crashing the report; cp_runner.py's own collection-time load stays fail-closed and untouched. tests/fixtures/uitest/state/inventory_exclusions.json is read by the REAL builder (not hand-authored/injected like discovery_ui.json), so the render harness exercises the actual production code path for this module end-to-end. Deferred to a new backlog item (inventory_exclusions_management_ui, DEPLOY.1A-adjacent): add/restore/reason/audit write workflows.

## Evidence

- **automated**: 13 new tests in tests/test_phase0_6_1c_inventory_exclusions_ui.py. py -m pytest -q: 603 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in prior 0.6.x closures). Net +13 from baseline 590, zero regressions. tests/test_html_render_harness.py (JSON-payload checks): 3 passed, 1 skipped.
- **privacy_gate**: Payload carries only vendor/identity/reason per entry (test-asserted exact field set); no credential/raw-config/IP.
- **real_env**: not applicable -- read-only UI/payload change, no device or network path.
