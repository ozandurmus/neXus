# Pre-existing: projectPlanData no longer carries __CRYPTO_JSON_PLACEHOLDER__ verbatim, test_project_plan_keeps_the_sentinel_token_as_data fails

status: planned · target: 

PRE-EXISTING (not from this branch). Proof: fails identically on the origin/main tree.

Failure:
tests/test_html_export_placeholder_integrity.py:65: in test_project_plan_keeps_the_sentinel_token_as_data
    assert "__CRYPTO_JSON_PLACEHOLDER__" in plan
E   assert '__CRYPTO_JSON_PLACEHOLDER__' in '{"schema_version":"1.0","generated_at":"...","current_build":"ui2_d1_restore_c7_c2_amend...",...,"archived_build_count":163,"metadata_warnings":[]}'

Cause: the rendered projectPlanData script literal no longer contains the sentinel token verbatim, so the test can no longer prove the token was embedded as text rather than expanded. Either the render path changed where the sentinel lands or the placeholder-integrity contract moved; needs investigation against the placeholder-integrity contract before either side is changed. Reporting only -- no source or test edited.

ADDENDUM -- this failure is state-dependent, which sharpens the classification.

It FAILS on a clean checkout (no gitignored data/ tree):
  clean 'git archive HEAD' export -> FAILED test_project_plan_keeps_the_sentinel_token_as_data (1 failed, 4 passed)
  clean 'git archive origin/main' export -> also FAILED (so: pre-existing, not a branch regression)
It PASSES in a working tree where an earlier suite run has populated data/derived:
  'python3 -m pytest tests/test_html_export_placeholder_integrity.py -q' -> 5 passed

So the sentinel-as-data proof depends on gitignored runtime state rather than on the render contract alone. A clean CI checkout is the failing case -- the test is therefore effectively broken on CI and only green on a warmed-up developer/agent tree. Needs investigation against the placeholder-integrity contract; resolve the state dependency, do not adjust the assertion to match whichever state is convenient.
