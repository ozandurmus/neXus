# pytest_feature_area_markers — pytest markers for feature-area test filtering

## Summary

User request: tests/ has grown to 79 phase-scoped files (AI_START_HERE.md's documented convention: one file per build/contract, for direct traceability from a phase doc's Evidence section or a build_history.json entry to the exact regression it introduced). Reorganizing by feature area was considered and explicitly recommended against -- it would break that traceability link across every prior build for a purely organizational change. Instead, new pytest.ini registers 7 feature-area markers (inventory, configuration, compliance, discovery, render, runtime_platform, security) and every test file gets exactly one module-level `pytestmark` line mapping it to its area -- purely additive, zero file moves, zero logic touched. `pytest -m compliance` now runs a 104-test feature slice instead of the full 639; all 7 markers partition the suite with no gaps or overlaps (68+188+104+165+11+59+44=639).

## Evidence

- **automated**: Caught and fixed two files where the marker's insertion point ended up before an existing (but non-top-of-file) `import pytest`, which would have raised a NameError at collection time -- found via a full ast-based line-order check across all 79 files after the initial pass, not assumed safe. `git diff` confirms every changed line across the 79 files is only an inserted `import pytest` / `pytestmark = ...` / blank line -- no other line touched. `pytest --collect-only`: 639 tests collected, zero warnings, zero errors (matches the pre-change count exactly). `pytest -q`: 635 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in every prior 0.6.x closure this session). Zero regressions.
- **privacy_gate**: No credential/device-identity/IP literal introduced; diff is markers and imports only.
- **real_env**: not applicable -- dev/CI test-tooling only.
