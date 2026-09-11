# overview_device_lifecycle_enrichment — Overview fleet-composition card: model/software-version family aggregation (Increment 1)

## Summary

New deviceLifecycleFamilies() in static/app.js aggregates the already-embedded configUiData.devices by (vendor, model, sw_version), excluding virtual_system rows (they inherit the physical host's model/sw_version verbatim, so counting them would double-count one device). Rendered as a new 'Fleet composition' card on Overview, sorted by count descending. Pure client-side -- no new Python payload builder, no new sentinel, no main.py wiring, no schema bump; the fields were already present on configUiData.devices for the Configuration module's device tree. Also fixed a gap from the prior inventory_exclusions_ui build: exclusionsUiData was missing from tests/test_html_render_harness.py's JSON-validity check list. Increment 2 (EOS/next-release mapping) deferred -- no internet access in this session to identify/vet a public CP/PAN EOL dataset; full design (table shape, join logic, provenance requirement) written up in the contract's closure section and split into overview_eos_release_guidance.

## Evidence

- **automated**: 8 new tests in tests/test_phase0_6_1c_overview_device_lifecycle_enrichment.py, 2 of which execute the real aggregation function through a real JS engine (bun, no DOM) against a synthetic device list -- proving the aggregation/sort/exclusion logic, not just source-string presence. Existing tests/fixtures/uitest/configuration_ui.json already carried 2 distinct device families, so no fixture regeneration was needed. py -m pytest -q: 611 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in prior 0.6.x closures). Net +8 from baseline 603, zero regressions.
- **privacy_gate**: Aggregation only touches vendor/model/sw_version/entity_type -- test-verified the extracted function body never references serial/management_ip/device_name/id/name.
- **real_env**: not applicable -- client-side UI aggregation only, no device or network path.
