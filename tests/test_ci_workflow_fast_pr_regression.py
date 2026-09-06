"""Deterministic, no-new-dependency assertions over the CI job split.

Text/regex-based on purpose: the repository has no existing YAML-parsing
test pattern and the kaizen scope explicitly forbids adding a YAML
dependency merely for this. These checks pin the observable shape that
AI_START_HERE.md / docs/AI_DEVELOPMENT_PROTOCOL.md now promise: `validate`
is the cheap fast-fail PR gate and never invokes the full suite itself;
`full-regression` runs the full parallel suite on every pull_request, every
push to main, and workflow_dispatch alike (DEV.TEST.1, 2026-09-06 -- the
original PR/push split withheld the full suite from pull_request only
because it was too slow to afford there; now parallelized and fast enough,
it runs on pull_request too, giving pre-merge proof on the PR's own exact
head); and the cheap safety gates are not accidentally dropped from either
job.
"""

import re
from pathlib import Path

WORKFLOW_PATH = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "validation.yml"

FULL_SUITE_LINE = "run: python -m pytest -q -n auto --dist worksteal"


def _read_workflow() -> str:
    return WORKFLOW_PATH.read_text(encoding="utf-8")


def _job_block(text: str, job_id: str) -> str:
    """Return the body of one top-level job, up to the next top-level job or EOF."""
    match = re.search(rf"^  {re.escape(job_id)}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)", text, re.S | re.M)
    assert match, f"job `{job_id}` not found in {WORKFLOW_PATH}"
    return match.group(1)


def test_workflow_file_exists():
    assert WORKFLOW_PATH.is_file()


def test_triggers_cover_pr_main_push_and_manual_dispatch():
    text = _read_workflow()
    on_block = re.search(r"^on:\n(.*?)^permissions:", text, re.S | re.M)
    assert on_block, "no `on:` block found"
    on_text = on_block.group(1)
    assert "pull_request:" in on_text
    assert "workflow_dispatch:" in on_text
    push_block = re.search(r"push:\n(.*?)(?=\n\S|\Z)", on_text, re.S)
    assert push_block, "no `push:` trigger found"
    assert "branches: [main]" in push_block.group(1)


def test_pr_job_does_not_invoke_full_suite():
    validate_block = _job_block(_read_workflow(), "validate")
    assert "if: github.event_name == 'pull_request'" in validate_block
    full_suite_lines = [line.strip() for line in validate_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert not full_suite_lines, (
        "the PR-triggered `validate` job must not run the unrestricted full "
        "pytest suite (with no target) -- that is the exact behavior this "
        "kaizen build removes from the PR critical path"
    )


def test_pr_job_retains_the_cheap_safety_gates():
    validate_block = _job_block(_read_workflow(), "validate")
    for expected in (
        "python -m compileall -q",
        "python main.py --repository-privacy-check",
        "python -m pytest -q tests/test_architecture_convergence.py",
        "python scripts/build_history_index.py --check",
        "git diff --check",
    ):
        assert expected in validate_block, f"PR gate missing: {expected!r}"


def test_full_regression_job_runs_unconditionally_on_every_triggered_event():
    """DEV.TEST.1 (2026-09-06): `full-regression` is no longer withheld from
    pull_request events -- now that the full suite is parallelized and fast
    enough to afford there, it runs on every event this workflow's `on:`
    block triggers on (pull_request, push to main, workflow_dispatch alike),
    giving pre-merge proof on the PR's own exact head instead of only a
    post-merge safety net."""
    full_block = _job_block(_read_workflow(), "full-regression")
    assert "if:" not in full_block, (
        "the `full-regression` job must not carry an `if:` condition that "
        "would withhold it from any event this workflow already triggers on "
        "(in particular pull_request) -- it must run unconditionally"
    )
    full_suite_lines = [line.strip() for line in full_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert full_suite_lines, (
        "the `full-regression` job must still run the unrestricted full "
        "pytest suite, in parallel (DEV.TEST.1) -- every test still "
        "executes, just distributed across pytest-xdist workers under one "
        "aggregate exit code"
    )


def test_full_regression_job_retains_the_same_cheap_gates():
    full_block = _job_block(_read_workflow(), "full-regression")
    for expected in (
        "python -m compileall -q",
        "python main.py --repository-privacy-check",
        "python -m pytest -q tests/test_architecture_convergence.py",
        "python scripts/build_history_index.py --check",
        "git diff --check",
    ):
        assert expected in full_block, f"full-regression gate missing: {expected!r}"


def test_both_jobs_run_together_on_pull_request_events():
    """DEV.TEST.1 (2026-09-06): unlike the original kaizen split, `validate`
    and `full-regression` are now intentionally BOTH triggered by the same
    pull_request event -- the fast gate is not a substitute for full-suite
    proof any more, it is just the cheapest possible fast-fail signal
    running alongside it. They are separate jobs with no `needs:` dependency
    between them, so GitHub Actions runs them concurrently, not serially."""
    text = _read_workflow()
    validate_block = _job_block(text, "validate")
    full_block = _job_block(text, "full-regression")
    assert "if: github.event_name == 'pull_request'" in validate_block
    assert "if:" not in full_block
    assert "needs:" not in validate_block
    assert "needs:" not in full_block
