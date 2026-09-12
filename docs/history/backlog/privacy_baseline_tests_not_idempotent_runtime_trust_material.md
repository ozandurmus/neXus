# Pre-existing: full suite is not idempotent -- a first run creates gitignored data/.support_hmac.key, then AC6/AC1 privacy-baseline tests fail on the second run

status: planned · target: 

PRE-EXISTING test-isolation defect, found while establishing the first full-suite baseline. Not a regression from this branch.

Symptom: the full suite passes these two tests on a first run against a clean checkout and FAILS them on every subsequent run.

Failures (second run onwards):
tests/test_gov_po_3_ci_privacy_gate_baseline.py::test_ac6_live_repository_findings_are_pre_existing_against_origin_main
tests/test_gov_po_3_ci_privacy_gate_baseline.py:191: AssertionError
    assert finding_key(ROOT, finding) in keys, (finding, note)
E   AssertionError: (PrivacyFinding(path='data/.support_hmac.key', line=0, rule='PRIVATE_OR_TRUST_MATERIAL'), 'baseline=6464a29472bb')
E   assert ('data/.support_hmac.key', 'PRIVATE_OR_TRUST_MATERIAL', '') in frozenset()

tests/test_nexus_engineer_tool_gate.py::test_ac1_live_bug_regression_against_real_repository_state -- same root cause.

Proof:
- 'git check-ignore -v data/.support_hmac.key' -> '.gitignore:4:/data/' (gitignored, never committed, so no git baseline can ever call it pre-existing).
- The file is created by the suite itself (mtime matched the first full-suite run; it does not exist in 'git archive HEAD').
- Both test modules PASS in a clean 'git archive HEAD' export that has no data/ directory: '19 passed in 7.19s'.
- Both FAIL in the working tree once data/.support_hmac.key exists.

Cause: both tests deliberately exclude rule 'RUNTIME_DIRECTORY_PRESENT' from the pre-existing-against-origin/main comparison (test_nexus_engineer_tool_gate's own comment anticipates 'an untracked data/ or logs/ directory left by an earlier test run'), but they do NOT exclude 'PRIVATE_OR_TRUST_MATERIAL' for a file located inside that same gitignored runtime directory. So the suite's own artifact is treated as a new tracked finding.

Fix direction (NOT applied -- reporting only, per task scope): the baseline comparison should skip any finding whose path is git-ignored, not just the RUNTIME_DIRECTORY_PRESENT rule. Do not weaken the scanner itself, and do not skip/xfail the tests.
