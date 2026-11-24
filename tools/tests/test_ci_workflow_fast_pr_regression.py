"""Deterministic assertions over the conditional full-regression topology.

Mostly text/regex-based, matching the repository's original no-new-
YAML-dependency pattern for the shape checks below: `pull_request` always
runs the fast `validate` gate; a closed approved mapping selects targeted
LDAP checks, full regression, or a visible block. Full regression remains
available on demand via `workflow_dispatch`. Also checks that the cheap
safety gates are not accidentally dropped from either job.

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

WORKFLOW_PATH = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "validation.yml"
CODEQL_WORKFLOW_PATH = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "codeql.yml"
ACTION_SHA_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")

FULL_SUITE_LINE = "run: python -m pytest -q -n auto --dist worksteal"


def _read_workflow() -> str:
    return WORKFLOW_PATH.read_text(encoding="utf-8")


def _workflow_triggers(data: dict) -> dict:
    # PyYAML 1.1 compatibility parses bare `on:` as boolean True unless quoted.
    return data.get("on") or data.get(True) or {}


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
    # 2026-11-13: `integration` (real PostgreSQL 16 suite) gates the merge; see its own test below.
    # 2026-11-14: `dependency-scan` scans lockfiles against security/baseline.yaml.
    assert set(data.get("jobs", {})) == {
        "validate",
        "full-regression",
        "po-scope-check",
        "regression-scope",
        "scope-blocked",
        "targeted-regression",
        "integration",
        "dependency-scan",
    }


def _job_block(text: str, job_id: str) -> str:
    """Return the body of one top-level job, up to the next top-level job or EOF."""
    match = re.search(rf"^  {re.escape(job_id)}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)", text, re.S | re.M)
    assert match, f"job `{job_id}` not found in {WORKFLOW_PATH}"
    return match.group(1)


def test_workflow_file_exists():
    assert WORKFLOW_PATH.is_file()


def test_triggers_include_pull_request_push_to_main_and_workflow_dispatch():
    """Validation runs on pull_request, push to main (post-merge validation,
    2026-11-14 review finding), and manual workflow_dispatch."""
    text = _read_workflow()
    on_block = re.search(r"^on:\n(.*?)^permissions:", text, re.S | re.M)
    assert on_block, "no `on:` block found"
    on_text = on_block.group(1)
    assert "pull_request:" in on_text
    assert "push:" in on_text
    assert "workflow_dispatch:" in on_text
    data = yaml.safe_load(text)
    triggers = _workflow_triggers(data)
    assert "pull_request" in triggers
    assert "workflow_dispatch" in triggers
    assert triggers.get("push", {}).get("branches") == ["main"]


def test_pr_job_does_not_invoke_full_suite():
    validate_block = _job_block(_read_workflow(), "validate")
    assert "if: github.event_name == 'pull_request' || github.event_name == 'push'" in validate_block
    full_suite_lines = [line.strip() for line in validate_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert not full_suite_lines, (
        "the PR-triggered `validate` job must not run the unrestricted full "
        "pytest suite (with no target) -- that stays out of the PR critical "
        "path"
    )


def test_pr_job_retains_the_cheap_safety_gates():
    validate_block = _job_block(_read_workflow(), "validate")
    for expected in (
        "python3 tools/privacy/repository_privacy_check.py",
        "python -m pytest -q tools/tests/test_live_project_governance.py",
        "python tools/delivery/build_history_index.py --check",
        "git diff --check",
    ):
        assert expected in validate_block, f"PR gate missing: {expected!r}"


def test_regression_scope_uses_the_closed_fail_closed_selector():
    scope_block = _job_block(_read_workflow(), "regression-scope")
    assert "if: github.event_name == 'pull_request'" in scope_block
    steps = yaml.safe_load(scope_block)["steps"]
    fetch = next(step for step in steps if step.get("name") == "Fetch PR base for changed-path scope")
    selector = next(step for step in steps if step.get("id") == "scope")
    for step in (fetch, selector):
        assert step["env"]["BASE_REF"] == "${{ github.base_ref }}"
        assert "${{ github." not in step["run"]
    assert fetch["run"] == 'git fetch origin "+${BASE_REF}:refs/remotes/origin/${BASE_REF}"'
    assert selector["run"] == (
        'if ! git diff --name-only "origin/${BASE_REF}"...HEAD | '
        'python3 tools/delivery/ci_regression_scope.py --github-output "$GITHUB_OUTPUT"; then\n'
        '  echo "classification=blocked" >> "$GITHUB_OUTPUT"\n'
        'fi\n'
    )


def test_targeted_and_unmapped_paths_have_truthful_jobs():
    text = _read_workflow()
    targeted_block = _job_block(text, "targeted-regression")
    blocked_block = _job_block(text, "scope-blocked")
    assert "classification == 'ldap'" in targeted_block
    assert ":ldap-adapter:unitTest" in targeted_block
    assert ":architecture-tests:architectureTest" in targeted_block
    assert "npm --prefix ui2/frontend test -- tests/ProjectPlanPanel.test.tsx" in targeted_block
    assert "tools/tests/test_gov_po_3_ci_privacy_gate_baseline.py" in targeted_block
    assert "tools/tests/test_nexus_engineer_tool_gate.py" in targeted_block
    assert "frontendTest" not in targeted_block
    assert "classification == 'blocked'" in blocked_block
    assert "run: exit 1" in blocked_block


def test_component_selections_are_explicit_and_separate():
    targeted_block = _job_block(_read_workflow(), "targeted-regression")
    assert "classification == 'ldap'" in targeted_block
    assert "contains(needs.regression-scope.outputs.classification, 'discovery')" in targeted_block
    assert ":discovery:unitTest" not in targeted_block
    assert ":worker:unitTest" in targeted_block
    assert ":job-engine:unitTest" in targeted_block
    assert "contains(needs.regression-scope.outputs.classification, 'inventory')" in targeted_block
    assert "*JooqDeviceRepositoryTest" in targeted_block
    assert "*Device*" in targeted_block
    assert "*Inventory*" in targeted_block
    assert "contains(needs.regression-scope.outputs.classification, 'privacy')" in targeted_block
    assert "*TopologyNamePseudonymizerTest" in targeted_block
    assert "contains(needs.regression-scope.outputs.classification, 'shell')" in targeted_block
    assert "npm --prefix ui2/frontend test -- tests/App.test.tsx" in targeted_block


def test_full_regression_runs_on_manual_dispatch_only():
    """PO 2026-11-24: the delivery Mac's pretest runs the suite before the merge; GitHub runs the full suite only on dispatch."""
    full_block = _job_block(_read_workflow(), "full-regression")
    assert "needs: regression-scope" in full_block
    assert "if: always() && github.event_name == 'workflow_dispatch'" in full_block
    assert "github.event_name == 'push'" not in full_block
    assert "classification == 'full'" not in full_block
    full_suite_lines = [line.strip() for line in full_block.splitlines() if line.strip() == FULL_SUITE_LINE]
    assert full_suite_lines, (
        "the `full-regression` job must still run "
        "the unrestricted full pytest suite, in parallel (DEV.TEST.1) -- "
        "every test still executes, just distributed across pytest-xdist "
        "workers under one aggregate exit code"
    )


def test_full_regression_job_retains_the_same_cheap_gates():
    full_block = _job_block(_read_workflow(), "full-regression")
    for expected in (
        "python3 tools/privacy/repository_privacy_check.py",
        "python -m pytest -q tools/tests/test_live_project_governance.py",
        "python tools/delivery/build_history_index.py --check",
        "git diff --check",
    ):
        assert expected in full_block, f"full-regression gate missing: {expected!r}"


def test_pull_request_schedules_validate_but_not_full_regression():
    text = _read_workflow()
    validate_block = _job_block(text, "validate")
    full_block = _job_block(text, "full-regression")
    assert "if: github.event_name == 'pull_request' || github.event_name == 'push'" in validate_block
    assert "github.event_name == 'pull_request'" not in full_block
    assert "if: github.event_name == 'workflow_dispatch'" not in validate_block


def test_push_to_main_runs_validation_but_not_full_regression():
    """Post-merge push to main triggers validate and dependency-scan; full regression runs on manual dispatch only."""
    text = _read_workflow()
    validate_block = _job_block(text, "validate")
    dep_block = _job_block(text, "dependency-scan")
    full_block = _job_block(text, "full-regression")
    assert "github.event_name == 'push'" in validate_block
    assert "github.event_name == 'push'" in dep_block
    assert "github.event_name == 'push'" not in full_block
    assert "github.event_name == 'workflow_dispatch'" in full_block


def test_dependency_scan_job_gates_findings_against_baseline():
    dep_job = yaml.safe_load(_job_block(_read_workflow(), "dependency-scan"))
    assert dep_job["runs-on"] == "ubuntu-latest"
    assert dep_job["timeout-minutes"] <= 15
    assert "pull_request" in dep_job["if"] and "push" in dep_job["if"]
    trivy_step = next(s for s in dep_job["steps"] if "aquasecurity/trivy-action@" in s.get("uses", ""))
    assert trivy_step["with"]["scan-type"] == "fs"
    assert trivy_step["with"]["scanners"] == "vuln"
    assert "severity" not in trivy_step["with"]  # security_summary.py owns the HIGH/CRITICAL/UNKNOWN gate
    assert trivy_step["with"]["format"] == "json"
    assert trivy_step["with"]["exit-code"] == "0"
    gate_step = next(s for s in dep_job["steps"] if "security_summary.py" in s.get("run", ""))
    assert "--baseline security/baseline.yaml" in gate_step["run"]
    assert trivy_step["with"]["output"] in gate_step["run"] or "trivy-reports" in gate_step["run"]


def test_codeql_workflow_shape():
    assert CODEQL_WORKFLOW_PATH.is_file()
    raw = CODEQL_WORKFLOW_PATH.read_text(encoding="utf-8")
    data = yaml.safe_load(raw)
    assert isinstance(data, dict)
    triggers = _workflow_triggers(data)
    assert "pull_request" in triggers
    assert triggers.get("push", {}).get("branches") == ["main"]
    assert data.get("permissions") == {
        "actions": "read",
        "contents": "read",
        "security-events": "write",
    }
    analyze = data["jobs"]["analyze"]
    assert analyze["runs-on"] == "ubuntu-latest"
    assert analyze["timeout-minutes"] <= 30
    matrix = analyze["strategy"]["matrix"]["include"]
    by_lang = {entry["language"]: entry for entry in matrix}
    assert set(by_lang) == {"java-kotlin", "javascript-typescript"}
    assert by_lang["javascript-typescript"]["build-mode"] == "none"
    assert by_lang["java-kotlin"]["build-mode"] == "manual"
    uses = [s["uses"] for s in analyze["steps"] if "uses" in s]
    assert any(u.startswith("github/codeql-action/init@") for u in uses)
    assert any(u.startswith("github/codeql-action/analyze@") for u in uses)
    compile_steps = [s for s in analyze["steps"] if "gradlew" in s.get("run", "")]
    assert len(compile_steps) == 1
    assert "matrix.language == 'java-kotlin'" in compile_steps[0]["if"]
    assert "-PfrontendPrebuilt=true testClasses" in compile_steps[0]["run"]


def test_all_workflows_pin_actions_by_sha_and_use_no_secrets():
    workflows_dir = WORKFLOW_PATH.parent
    workflow_files = sorted(workflows_dir.glob("*.yml"))
    assert {p.name for p in workflow_files} >= {"validation.yml", "codeql.yml"}
    for wf_path in workflow_files:
        text = wf_path.read_text(encoding="utf-8")
        assert "secrets." not in text, f"{wf_path.name} must not require secrets"
        doc = yaml.safe_load(text)
        for job_name, job in doc.get("jobs", {}).items():
            assert job.get("timeout-minutes", 10) <= 30, f"{wf_path.name}:{job_name} timeout too high"
            for step in job.get("steps", []):
                if "uses" in step:
                    assert ACTION_SHA_RE.match(step["uses"]), (
                        f"{wf_path.name}:{job_name} unpinned action: {step['uses']!r}"
                    )


def test_ci_uses_only_tooling_python_dependencies_and_java_regression():
    workflow = yaml.safe_load(_read_workflow())
    commands = "\n".join(step.get("run", "") for job in workflow["jobs"].values() for step in job["steps"])
    for forbidden in ("-r requirements.txt", "-r requirements-console.txt", "compileall", "python main.py", "tests/test_architecture_convergence.py", ":discovery:unitTest"):
        assert forbidden not in commands
    assert "-r requirements-dev.txt" in commands
    full = _job_block(_read_workflow(), "full-regression")
    assert "unitTest architectureTest" in full
    assert "npx tsc --noEmit -p ." in full
    assert "npx vitest run" in full
    assert "npm run build" in full


def test_integration_job_gates_the_merge_against_a_real_postgres_16():
    """2026-11-13: ui2/integration-tests drifted red while it ran only after a `ship --full` merge. The job runs
    before the merge on every pull request that touches Java, migration or SQL paths, and on manual dispatch; the
    ship waits for it because it is not a post-merge check. It uses the fixture's UI2_TEST_JDBC_URL carrier against
    a throwaway postgres:16 on the runner -- no password and no secret in the workflow."""
    import sys
    sys.path.insert(0, str(WORKFLOW_PATH.parents[2] / "tools" / "delivery"))
    import standalone_orchestrate

    workflow = yaml.safe_load(_read_workflow())
    job = workflow["jobs"]["integration"]
    assert job["if"] == "github.event_name == 'pull_request' || github.event_name == 'workflow_dispatch'"
    assert "integration" not in standalone_orchestrate.POST_MERGE_CHECKS
    runs = "\n".join(step.get("run", "") for step in job["steps"])
    assert ":integration-tests:test" in runs
    assert "postgres:16" in runs and "POSTGRES_HOST_AUTH_METHOD=trust" in runs
    assert "POSTGRES_PASSWORD" not in _job_block(_read_workflow(), "integration")
    suite = next(step for step in job["steps"] if step.get("name") == "PostgreSQL 16 integration suite")
    assert suite["env"]["UI2_TEST_JDBC_URL"].startswith("jdbc:postgresql://127.0.0.1:5432/")
    scope = next(step for step in job["steps"] if step.get("id") == "scope")
    # Fail closed: a pull request whose changed paths cannot be listed runs the suite.
    assert scope["run"].count('echo "run=true"') >= 3
    assert "^ui2/frontend/" in scope["run"] and "docs/operations/sql/" in scope["run"]


def test_codeql_manual_build_cannot_be_served_from_the_gradle_cache():
    text = CODEQL_WORKFLOW_PATH.read_text()
    assert "--no-build-cache" in text and "--rerun-tasks" in text
    assert "mkdir -p ui2/frontend/dist" in text


def test_dependency_scan_leaves_the_severity_gate_to_security_summary():
    block = _job_block(WORKFLOW_PATH.read_text(), "dependency-scan")
    assert "severity:" not in block  # no HIGH,CRITICAL pre-filter: UNKNOWN must reach security_summary.py
    assert "security_summary.py" in block
    assert "printf '0" not in block  # the recorded exit code is Trivy's real outcome
    assert "steps.trivy.outcome" in block and "trivy_retry" in block  # bounded DB-download retry


def test_push_privacy_baseline_is_the_pre_push_commit():
    text = WORKFLOW_PATH.read_text()
    assert "repository_privacy_check.py --privacy-baseline-ref origin/main" not in text
    assert text.count('--privacy-baseline-ref "${{ steps.privacy_baseline.outputs.ref }}"') == 2
    assert text.count("PUSH_BEFORE: ${{ github.event.before }}") == 2 and text.count("ref=HEAD~1") == 2


def test_slow_security_scans_do_not_gate_the_merge():
    from tools.delivery import standalone_orchestrate as orchestrate

    assert {"dependency-scan", "Analyze (java-kotlin)", "Analyze (javascript-typescript)"} <= orchestrate.POST_MERGE_CHECKS


def test_architecture_test_heap_budget_and_single_import_cache():
    repo = WORKFLOW_PATH.parents[2]
    arch_build = (repo / "ui2" / "architecture-tests" / "build.gradle.kts").read_text(encoding="utf-8")
    root_build = (repo / "ui2" / "build.gradle.kts").read_text(encoding="utf-8")
    gradle_props = (repo / "ui2" / "gradle.properties").read_text(encoding="utf-8")

    assert 'maxHeapSize = "2g"' in arch_build
    assert "maxParallelForks = 1" in arch_build
    assert "forkEvery = 0" in arch_build
    assert "mustRunAfter(" in root_build
    assert "org.gradle.jvmargs=-Xmx1536m" in gradle_props

    arch_sources = sorted((repo / "ui2" / "architecture-tests" / "src" / "test" / "java").rglob("*.java"))
    importers = [p.name for p in arch_sources if "new ClassFileImporter(" in p.read_text(encoding="utf-8")]
    assert importers == ["Ui2ArchitectureTest.java"]
