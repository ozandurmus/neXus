# pcp1_registry_uuid_call_count_test_defect_repair — M1 - PCP.1 Device Registry: repair two module-wide uuid4 call-count test defects

## Summary

Two tests/test_pcp1_device_registry.py cases (test_duplicate_enroll_refused_before_device_id_generated, test_lock_contention_on_enroll_never_generates_a_device_id) monkeypatched module-wide utils.device_registry.uuid.uuid4 to prove no device_id is generated on the refused-duplicate and lock-contention paths, but the same patched uuid4 is also called by _acquire_lock() to mint the unrelated lock owner_token, so both tests failed asserting calls == [] once the registry mutation lock (introduced in the same PCP.1 build) started calling uuid4 on every enroll() attempt. Repaired by adding a single narrow production seam, utils/device_registry.py::_generate_device_id() (device_id=_generate_device_id() replacing the inline uuid.uuid4().hex call), and repointing both tests' monkeypatch at that seam instead of the module-wide uuid4 -- no change to device_id format, the lock's own token generation, persistence, normalization, duplicate/lifecycle behavior, CLI output, or public API.

## Evidence

Reproduced the defect first (git stash of the fix) confirming both tests fail with calls == [1] against origin/main 310593e901737abe4b44741af01de77f5e6b8514 exactly as described. After the fix: tests/test_pcp1_device_registry.py -- 82 passed. Full suite (py -m pytest -q, environment lacked py/pytest/fastapi/paramiko until requirements.txt/requirements-console.txt/requirements-dev.txt were installed) -- 1931 passed, 24 skipped, 0 failed; one apparent failure on the first full-suite run (test_profile_output_html_is_identical_regardless_of_profiling) was confirmed a pre-existing test-order flake unrelated to this change (passes in isolation on both baseline and fixed trees; full suite rerun green). Repository privacy gate (main.py --repository-privacy-check): PASS, 0 findings, after removing this session's own gitignored data/logs runtime artifacts. scripts/build_history_index.py --check: up to date. git diff --check: clean.

## Risks forward

No frozen PCP.1 AC-1a..AC-15 behavior changed; the mutation lock, its opacity/uniqueness/instance-safety, duplicate-before-generation ordering, and fail-closed contention are all still proven, now by a device-id-specific seam instead of an incidental module-wide uuid4 count. No production behavior depends on a test-only flag. pcp_console_registry_write_gate and the PCP.2 Product Owner review remain untouched and open, as before this repair.
