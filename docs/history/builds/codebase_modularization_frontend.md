# codebase_modularization_frontend — Frontend modularization - static/app.js split into eight responsibility-owned files

## Summary

Behavior-preserving split of the flat 4,905-line static/app.js (173 top-level functions, one implicit-global scope) into the eight D-MOD5 files - app_core, inventory_ui, configuration_ui, compliance_ui, discovery_ui, project_plan_ui, overview_ui, app_bootstrap - concatenated by utils/html_export.py in fixed dependency order into the exact same single inline <script> (D-MOD1: no bundler, no ES modules, no build step). New compose_report_script() helper; sixteen source-string UI tests repointed to it; new tests/test_frontend_module_composition.py (AC-3 static dependency-order check + AC-1 completeness). Two contract-audit gaps found during extraction and resolved by the ownership rule: currentConfigurationFleet -> configuration_ui (shared with overview_ui, which loads later); switchModule/savedModule kept in app_bootstrap with a reasoned nav-dispatcher carve-out in the AC-3 check (every feature-module call site is a deferred event handler).

## Evidence

Rendered-report line-multiset diff before (main) vs after, on both the uitest fixture bundle and the empty-state render: zero original code lines lost - the only additions are eight file-header comments and a five-line relocation note. Render harness (bun DOM-execution) green on both renders: 'script parses, executes clean, 7 nav modules + all inner tabs switch with no console errors'. Repository privacy gate PASS/0 on a clean checkout (files scanned 432 -> 460, no new findings). Full suite py -m pytest -q: 887 passed / 26 skipped / 2 failed on the branch vs 882 / 27 / 2 on main at the same commit - +4 from tests/test_frontend_module_composition.py, one bun-gated test that skipped on the baseline run passing here, and the identical two pre-existing order-pollution failures (both pass in isolation), so zero regressions.

## Risks forward

The Playwright real-Chromium harness path is uninstalled in this sandbox (chromium not downloaded); the bun happy-dom path stood in and a human interactive open on a real workstation is a cheap non-blocking follow-up. Status stays in_progress: the backend half of codebase_modularization (main.py / vendor-collector seam extraction) is a separate later item under the same backlog id and is untouched here. CON.1 (operator console read-only) lists this frontend split as a hard precondition - it is now implemented but the phase doc DoD keeps the id at in_progress until that real-browser confirmation is recorded.
