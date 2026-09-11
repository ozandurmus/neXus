"""Deterministic assertions over the DEV.TEST.1 final CI topology.

Mostly text/regex-based, matching the repository's original no-new-
YAML-dependency pattern for the shape checks below: `pull_request` ->
`validate` (fast gate) only; `workflow_dispatch` -> `full-regression` (the
full suite, parallel -- `python -m pytest -q -n auto --dist worksteal`)
only, on demand, never automatically; there is no `push` trigger at all,
since with `full-regression` withheld from automatic triggers and
`validate` restricted to pull_request, a push event would match no job's
`if:` condition and produce an empty, zero-job workflow run. Also checks
that the cheap safety gates are not accidentally dropped from either job.

One exception, `test_workflow_yaml_parses` below, DOES take a real YAML
dependency (`pyyaml`, requirements-dev.txt): DEV.TEST.1's own post-merge
incident (2026-09-06) shipped a genuine YAML syntax defect in this exact
file -- an unquoted plain scalar containing a bare `: ` inside an f-string
label -- which silently broke GitHub Actions' ability to schedule ANY job
for ANY trigger (push, pull_request, workflow_dispatch alike) for two
commits and a merge, because nothing validated the YAML before push. The
regex checks below would not have caught it (they only inspect substrings,
never parse the document), which is exactly why a real parse check earns
its keep here despite the repository's general preference against one.
"""

import re
from pathlib import Path

import yaml

WORKFLOW_PATH = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "validation.yml"

FULL_SUITE_LINE = "run: python -m pytest -q -n auto --dist worksteal"


def _read_workflow() -> str:
    return WORKFLOW_PATH.read_text(encoding="utf-8")


def test_workflow_yaml_parses():
    """DEV.TEST.1 post-merge incident (2026-09-06): a bare `: ` inside an
    unquoted `run:` f-string label broke every trigger's ability to
    schedule any job at all, silently -- GitHub Actions returned a
    completed, zero-job, failing check suite with no readable error
    surfaced anywhere the earlier regex-only tests would see. Parsing the
    file for real is the only check that would have caught it."""
    with WORKFLOW_PATH.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    assert isinstance(data, dict)
    # GOV.ORCH.7 section 2.3 items 3/5: `po-scope-check` is a new,
    # gov/po-*-only job (PO write-scope enforcement) -- added, not a
    # replacement for either existing job.
    assert set(data.get("jobs", {})) == {"validate", "full-regression", "po-scope-check"}


def _job_block(text: str, job_id: str) -> str:
    """Return the body of one top-level job, up to the next top-level job or EOF."""
    match = re.search(rf"^  {re.escape(job_id)}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)", text, re.S | re.M)
    assert match, f"job `{job_id}` not found in {WORKFLOW_PATH}"
    return match.group(1)


def test_workflow_file_exists():
    assert WORKFLOW_PATH.is_file()


def test_triggers_are_pull_request_and_workflow_dispatch_only():
    """DEV.TEST.1 final topology (2026-09-06, Product Owner directed): no
    `push` trigger at all. With `full-regression` withheld from automatic
    triggers and `validate` restricted to pull_request, a push-to-main
    event would match no job's `if:` condition and produce an empty,
    zero-job workflow run -- removed rather than shipped that way."""
    text = _read_workflow()
    on_block = re.search(r"^on:\n(.*?)^permissions:", text, re.S | re.M)
    assert on_block, "no `on:` block found"
    on_text = on_block.group(1)
    assert "pull_request:" in on_text
    assert "workflow_dispatch:" in on_text
    assert "push:" not in on_text, (
        "the workflow must not carry a `push:` trigger -- full-regression "
        "no longer runs automatically on push-to-main, and validate is "
        "pull_request-only, so a push event would schedule zero jobs"
    )


def test_pr_job_does_not_invoke_full_suite():
    validate_block = _job_block(_read_workflow(), "validate")
    assert "if: github.event_name == 'pull_request'" in validate_block
    full_suite_lines = [line.strip() for line in validate_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert not full_suite_lines, (
        "the PR-triggered `validate` job must not run the unrestricted full "
        "pytest suite (with no target) -- that stays out of the PR critical "
        "path"
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


def test_full_regression_job_runs_only_via_workflow_dispatch():
    """DEV.TEST.1 final topology (2026-09-06, Product Owner directed):
    `full-regression` must never start automatically -- not on
    pull_request, not on push to main. A GitHub-hosted full-regression run
    is an exceptional, materially justified action; the normally-sufficient
    evidence is the already-proven local/agent-cloud parallel run."""
    full_block = _job_block(_read_workflow(), "full-regression")
    assert "if: github.event_name == 'workflow_dispatch'" in full_block
    full_suite_lines = [line.strip() for line in full_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert full_suite_lines, (
        "the `full-regression` job (workflow_dispatch only) must still run "
        "the unrestricted full pytest suite, in parallel (DEV.TEST.1) -- "
        "every test still executes, just distributed across pytest-xdist "
        "workers under one aggregate exit code"
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


def test_pull_request_schedules_validate_not_full_regression():
    text = _read_workflow()
    validate_block = _job_block(text, "validate")
    full_block = _job_block(text, "full-regression")
    assert "if: github.event_name == 'pull_request'" in validate_block
    assert "if: github.event_name == 'pull_request'" not in full_block
    assert "if: github.event_name == 'workflow_dispatch'" not in validate_block


def test_push_to_main_is_not_an_automatic_full_regression_trigger():
    """No `push:` trigger exists at all (see test_triggers_are_pull_
    request_and_workflow_dispatch_only), and full-regression's own `if:`
    only matches workflow_dispatch -- so even if a push trigger were ever
    reintroduced elsewhere, this job's condition alone would still refuse
    to run automatically on push."""
    full_block = _job_block(_read_workflow(), "full-regression")
    assert "github.event_name != 'pull_request'" not in full_block
    assert "if: github.event_name == 'workflow_dispatch'" in full_block
