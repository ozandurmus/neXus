# operator_console_read_only_real_browser_close — Operator Console -- CON.1 real-browser open, closes the phase to DONE

## Summary

Human interactive real-browser open of the CON.1 console, same session as its implementation: launched main.py --console against the tests/fixtures/uitest bundle (same monkeypatch pattern as scripts/render_uitest.py) and drove it live in a real Chromium tab through all seven modules. Found and fixed two regressions neither pytest nor the render harness caught: (1) JS-set inline style="" attributes in compliance_ui.js's segmented coverage bar and project_plan_ui.js's roadmap progress bar were blocked outright by the console's CSP (style-src 'self', no unsafe-inline) -- replaced with a fixed w-pct-0..w-pct-100 CSS class set (static/style.css), CSP-safe in both modes; (2) inventory_ui.js/configuration_ui.js/compliance_ui.js each computed a derived collection (inventory, configDevices, complianceSubjects) once at module-load time against the still-empty default payload, before initializeReport(payloads) (CON.1's own C1-3 refactor) assigns the real one -- this affected the exported static report too, not only the console -- fixed with rebuildX() functions called both at load and from initializeReport.

## Evidence

Fresh-tab live walk through all seven console modules (Overview, Network Inventory, Configuration, Compliance, Discovery, Exclusions, Project Plan) against the uitest fixture: zero browser console errors, real device/compliance/roadmap data rendered and visually correct (18 inventory entities, 14 assessed compliance devices, segmented framework-coverage bars, roadmap progress bars). Full suite 907 passed / 27 skipped / 2 failed (unchanged pre-existing order-pollution pair). Repository privacy gate PASS/0.

## Risks forward

The render harness (tools/render-harness/check-render.mjs, bun/happy-dom) asserts scripts execute cleanly and nav/tabs are clickable, but never asserted an actual rendered device/entity count -- exactly the class of bug this real-browser open exists to catch, and did. Worth considering whether check-render.mjs should gain a minimal device-count assertion for the uitest fixture specifically, to close this coverage gap for future phases; not done this session (scope discipline -- CON.1's own contract only required the human open, not a harness enhancement).
