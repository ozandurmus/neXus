# dev_kaizen_fast_pr_ci — DEV Kaizen - fast PR CI / risk-based full regression

## Summary

Splits .github/workflows/validation.yml into a fast pull_request gate (`validate`: import sanity, repository privacy gate, project-state consistency, build-history-index check, a small fixed PR smoke/safety-regression set, whitespace check -- no full pytest suite) and a `full-regression` job (push-to-main + workflow_dispatch, same gates plus the full serial pytest suite). Canonical policy documented once in docs/AI_DEVELOPMENT_PROTOCOL.md "CI validation policy"; AI_START_HERE.md points at it instead of restating it. New tests/test_ci_workflow_fast_pr_regression.py statically asserts the split (text-based, no new YAML dependency). PR #38's CI confirmed the `validate` job green against this exact head (mergeable_state=clean, zero review threads); merged to main via merge commit 6778ee9. Local full regression 1215 passed/24 skipped/0 failed stands as the full-suite evidence (this build's own full-regression trigger).

## Evidence

.github/workflows/validation.yml (split into `validate` + `full-regression` jobs); docs/AI_DEVELOPMENT_PROTOCOL.md (new "CI validation policy" section, canonical); AI_START_HERE.md (pointer only, no restatement); tests/test_ci_workflow_fast_pr_regression.py (new, 7 tests, text/regex-based -- no pyyaml dependency added). Locally executed this session: full pytest suite 1215 passed/24 skipped/0 failed (serial, full dependency set); repository privacy gate PASS; tests/test_architecture_convergence.py 19/19; build_history_index.py --check PASS.

## Risks forward

None remaining for this build's own scope. PR #38 merged 2026-09-03 (merge commit 6778ee9); job id `validate` did keep matching branch protection (the PR's own CI ran and reported normally, mergeable_state stayed clean throughout).
