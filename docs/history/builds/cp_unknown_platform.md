# cp_unknown_platform — Propagate CP platform-family classification into the discovery/capability lifecycle model

## Summary

CapabilityProfile gains platform_family/platform_confidence, fully independent of shell_type/collection-capability fields and of plan_collection()'s output (proven by a parametrized test across every platform_family value against an identical profile). platform_fields_from_classification() is the one sanctioned conversion from a real checkpoint_config_collector._classify_platform() dict; an integration test calls the actual collector function to prove the shapes stay compatible. discovery_capability_ui.py and app.js's Discovery module (new Platform column) surface it. Scope correction found mid-implementation: 0.6.1C Phase 4 (live collector-to-CapabilityStore wiring) does not exist anywhere in the repo yet for any capability field, not something specific to platform classification -- building that live wiring stays a separate, larger, un-started item, not silently pulled into this one. Also discovered and flagged (not fixed): tests/fixtures/uitest/discovery_ui.json's entity keys pre-existingly do not match the real builder's field names -- new backlog item discovery_fixture_shape_drift.

## Evidence

- **automated**: py -m pytest -q: 569 passed, 2 skipped, 2 failed (both pre-existing and unrelated -- same two tests already documented against the unmodified baseline in prior 0.6.x closures). Net +14 from baseline 555 (11 new tests in tests/test_phase0_6_1c_discovery_lifecycle.py / tests/test_phase0_6_1c_discovery_capability_ui.py). tests/fixtures/uitest/ regenerated via build_fixture.py; JSON-payload render-harness checks pass.
- **privacy_gate**: No credential/raw-config/asset-serial data added to any payload; only the coarse family/confidence classification already produced by _classify_platform().
- **real_env**: not applicable -- no device command touched; _classify_platform() itself unchanged.
