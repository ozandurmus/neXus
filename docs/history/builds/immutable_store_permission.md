# immutable_store_permission — Content-addressed evidence store: retry the snapshot-directory publish on transient lock

## Summary

Root cause of the standing P1 (immutable_store_permission): ConfigEvidenceStore._ensure_blob already retried its os.replace on transient (OSError, PermissionError), but the snapshot-directory publish immediately after it (os.replace(tmp_dir, final_dir) in _write_snapshot) had none -- a single transient lock (Windows AV/indexer) failed the whole snapshot, surfacing as the already-classified LOCAL_IMMUTABLE_EVIDENCE_STORE / local_filesystem_permission_or_lock failure. Extracted the existing bounded retry (3 attempts, 0.1s exponential backoff) into ConfigEvidenceStore._replace_with_retry and applied it to the directory publish; no behavior change to the blob-write path. No collector/network/CAS-schema/UI change.

## Evidence

- **automated**: py -m pytest -q: 552 passed, 2 skipped, 2 failed (both pre-existing, reproduced identically on the unmodified baseline -- unrelated test-order pollution in test_phase0_6_1c_discovery_capability_ui.py / test_phase0_7_5_compliance_trend.py). Baseline is 550 passed / same 2 failures; net +2 passing from two new regression tests in tests/test_phase0_6_0a4_3_2_content_addressed_storage.py (transient-then-success self-heal; persistent-lock still raises cleanly with no leftover .tmp- directory).
- **privacy_gate**: PASS / 0 on a clean checkout (gitignored data/ + logs/ from the local test run removed before the gate).
- **real_env**: not applicable - retry/backoff logic only, no collector or network path touched.
