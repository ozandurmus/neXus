"""jt.sh, jt_java_results.py, main-green.sh and the flaky allowlist (tools/delivery/ops): fail closed, never retry
security tests, diff against the fetched ref, Gradle without a daemon."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from delivery_ops_support import OPS, OpsEnv  # noqa: E402

ANSI_FAIL = "\x1b[31m FAIL \x1b[39m"


@pytest.fixture
def ops(tmp_path):
    return OpsEnv(tmp_path)


def allow(ops, text):
    (ops.tmp / "allowlist.txt").write_text(text)


def fake_npx(ops, rerun_rc):
    """npx stub: records every invocation; `vitest run <files>` (a re-run) answers rerun_rc."""
    log = ops.tmp / "npx.log"
    ops.fake("npx", f'echo "$@" >> "{log}"; [ "$1" = vitest ] && [ -n "$3" ] && exit {rerun_rc}; exit 0')
    return log


def gate(ops, output, rc, label="t"):
    out = ops.tmp / "vitest.out"
    out.write_text(output)
    fe = ops.tmp / "fe"
    fe.mkdir(exist_ok=True)
    r = ops.source("ops_lib.sh", f'vitest_gate "{out}" {rc} "{fe}" {label}; echo "rc=$?"')
    lines = r.stdout.strip().splitlines()
    return lines[:-1], int(lines[-1].split("=")[1])


# ---- vitest verdict ------------------------------------------------------------------------------------------------

def test_a_green_vitest_run_passes_silently(ops):
    assert gate(ops, " Test Files  3 passed\n Tests  9 passed\n", 0) == ([], 0)


def test_a_vitest_crash_before_listing_failed_files_fails_closed(ops):
    """2026-11-24 bug: the empty failed-file list took the 'flaky re-run passed' branch and passed."""
    npx = fake_npx(ops, 0)
    lines, rc = gate(ops, "Error: cannot find module 'x'\n Test Files  1 failed\n", 1)
    assert rc == 1 and lines[0].startswith("vitest-FAIL") and "no failed test file" in lines[0]
    assert not npx.exists()   # nothing was re-run
    lines, rc = gate(ops, "segmentation fault\n", 139)
    assert rc == 1 and lines[0].startswith("vitest-FAIL")


def test_a_nonzero_exit_with_a_clean_summary_is_still_a_failure(ops):
    assert gate(ops, " Test Files  3 passed\n Tests  9 passed\n", 1)[1] == 1


def test_a_failed_file_that_is_not_on_the_allowlist_is_never_re_run(ops):
    npx = fake_npx(ops, 0)
    lines, rc = gate(ops, f" FAIL  tests/Other.test.tsx > x\n Test Files  1 failed\n", 1)
    assert rc == 1 and "not on the flaky allowlist" in lines[0] and "tests/Other.test.tsx" in lines[0]
    assert not npx.exists()


def test_an_allowlisted_file_gets_one_re_run_and_passes_as_flaky(ops):
    allow(ops, "# comment\nvitest tests/Flaky.test.tsx  # evidence\n")
    npx = fake_npx(ops, 0)
    lines, rc = gate(ops, " FAIL  tests/Flaky.test.tsx > case\n Test Files  1 failed\n", 1)
    assert rc == 0 and lines == ["vitest-FLAKY re-run passed: tests/Flaky.test.tsx "]
    assert npx.read_text().strip() == "vitest run tests/Flaky.test.tsx"
    assert "tests/Flaky.test.tsx" in (ops.tmp / "flaky.log").read_text()


def test_an_allowlisted_file_that_fails_again_is_a_failure(ops):
    allow(ops, "vitest tests/Flaky.test.tsx\n")
    fake_npx(ops, 1)
    lines, rc = gate(ops, " FAIL  tests/Flaky.test.tsx\n Test Files  1 failed\n", 1)
    assert rc == 1 and lines[0].startswith("vitest-FAIL") and "re-run" in lines[0]


def test_one_unlisted_file_among_flaky_ones_fails_without_a_re_run(ops):
    allow(ops, "vitest tests/Flaky.test.tsx\n")
    npx = fake_npx(ops, 0)
    lines, rc = gate(ops, " FAIL  tests/Flaky.test.tsx\n FAIL  tests/Real.test.tsx\n Tests  2 failed\n", 1)
    assert rc == 1 and "tests/Real.test.tsx" in lines[0] and not npx.exists()


@pytest.mark.parametrize("name", ["tests/AuthzScreen.test.tsx", "tests/CredentialForm.test.tsx", "tests/Redaction.test.tsx",
                                  "tests/SessionTimeout.test.tsx", "tests/TokenMask.test.tsx"])
def test_a_security_test_is_never_re_run_even_when_listed(ops, name):
    allow(ops, f"vitest {name}\n")
    npx = fake_npx(ops, 0)
    lines, rc = gate(ops, f" FAIL  {name} > x\n Tests  1 failed\n", 1)
    assert rc == 1 and "not on the flaky allowlist" in lines[0] and not npx.exists()


def test_ansi_coloured_vitest_output_is_understood(ops):
    allow(ops, "vitest tests/Flaky.test.tsx\n")
    fake_npx(ops, 0)
    assert gate(ops, f"{ANSI_FAIL.replace(' FAIL ', ' FAIL')}  tests/Flaky.test.tsx\n", 1)[1] == 0


def test_the_shipped_allowlist_lists_no_security_test_and_has_valid_entries():
    entries = []
    for line in (OPS / "flaky_allowlist.txt").read_text().splitlines():
        line = line.split("#", 1)[0].split()
        if line:
            assert len(line) == 2 and line[0] in ("java", "vitest"), line
            entries.append(tuple(line))
    assert entries, "the allowlist documents the known flaky tests"
    assert len(entries) == len(set(entries))
    sys.path.insert(0, str(OPS))
    import jt_java_results as jr
    for kind, name in entries:
        assert not jr.is_security(name), (kind, name)
        if kind == "vitest":
            assert re.fullmatch(r"tests/[A-Za-z0-9_.-]+\.test\.tsx?", name), name


def test_shell_and_python_security_patterns_agree(ops):
    sys.path.insert(0, str(OPS))
    import jt_java_results as jr
    for name in ("LogRedactorTest", "AuthzDecisionTest", "ScriptRunBrokerTest", "CredentialVaultTest", "PolicyTabs",
                 "SessionReaperTest", "HealthControllerTest", "TlsTrustTest", "BackgroundSnapshotTest"):
        shell = ops.source("ops_lib.sh", f'is_security_test "{name}" && echo yes || echo no').stdout.strip() == "yes"
        assert shell == jr.is_security(name), name


# ---- project/ check diffs against the fetched ref --------------------------------------------------------------------

def test_project_change_is_detected_against_the_given_ref_not_origin_main(ops):
    wt = ops.git_repo(ops.tmp / "wt", {"README.md": "x\n", "project/a.json": "{}\n"})
    base = ops.git(wt, "rev-parse", "HEAD")
    (wt / "project" / "a.json").write_text('{"v": 2}\n')
    ops.git(wt, "commit", "-q", "-am", "project change")
    head = ops.git(wt, "rev-parse", "HEAD")
    ops.git(wt, "update-ref", "refs/shipq/x", base)             # the ref this ship fetched
    ops.git(wt, "update-ref", "refs/remotes/origin/main", head)   # a stale origin/main that already contains the change
    changed = lambda ref: ops.source("jt.sh", f'jt_project_changed "{wt}" {ref} && echo yes || echo no').stdout.strip()  # noqa: E731
    assert changed("refs/shipq/x") == "yes"
    assert changed("refs/remotes/origin/main") == "no"          # what the old code compared against


def test_no_or_unknown_base_ref_fails_closed_to_running_the_check(ops):
    wt = ops.git_repo(ops.tmp / "wt")
    for ref in ("''", "refs/shipq/missing"):
        assert ops.source("jt.sh", f'jt_project_changed "{wt}" {ref} && echo yes || echo no').stdout.strip() == "yes"


def test_a_ref_without_project_changes_skips_the_reader_test(ops):
    wt = ops.git_repo(ops.tmp / "wt")
    ops.git(wt, "update-ref", "refs/shipq/x", "HEAD")
    assert ops.source("jt.sh", f'jt_project_changed "{wt}" refs/shipq/x && echo yes || echo no').stdout.strip() == "no"


# ---- jt.sh end to end with fake gradle / npx -------------------------------------------------------------------------

def jt_worktree(ops, fake_gradle_body):
    wt = ops.git_repo(ops.tmp / "wt", {"README.md": "x\n", "project/a.json": "{}\n"})
    (wt / "ui2" / "frontend" / "node_modules").mkdir(parents=True)
    gradle = wt / "ui2" / "gradlew"
    gradle.write_text("#!/bin/bash\n" + fake_gradle_body + "\n")
    gradle.chmod(0o755)
    ops.git(wt, "update-ref", "refs/shipq/x", "HEAD")
    return wt


def test_jt_runs_gradle_without_a_daemon_and_fails_a_vitest_crash(ops):
    calls = ops.tmp / "gradle.calls"
    wt = jt_worktree(ops, f'echo "$@" >> "{calls}"; exit 0')
    ops.fake("npx", 'if [ "$1" = vitest ]; then echo "Error: boom"; exit 1; fi; exit 0')
    scope = json.dumps({"scope": "scoped", "gradle_tasks": [":service:test"], "frontend": True})
    r = ops.bash(f'bash "{OPS}/jt.sh" "{wt}" \'{scope}\' ""')      # no base ref: the reader test must run
    lines = r.stdout.splitlines()
    assert any(l.startswith("vitest-FAIL") for l in lines), r.stdout + r.stderr
    assert "tsc-ok" in lines and "java tests 0 failures 0" in lines
    recorded = calls.read_text().splitlines()
    assert any("ProjectPlanReaderTest" in c for c in recorded) and any(":service:test" in c for c in recorded)
    assert recorded and all(c.startswith("--no-daemon ") for c in recorded), recorded


def test_jt_skips_the_reader_test_when_the_ref_shows_no_project_change_and_skips_frontend_when_scoped_out(ops):
    calls = ops.tmp / "gradle.calls"
    wt = jt_worktree(ops, f'echo "$@" >> "{calls}"; exit 0')
    scope = json.dumps({"scope": "scoped", "gradle_tasks": [":service:test"], "frontend": False})
    r = ops.bash(f'bash "{OPS}/jt.sh" "{wt}" \'{scope}\' refs/shipq/x')
    assert "frontend tests skipped" in r.stdout
    assert not any("ProjectPlanReaderTest" in c for c in calls.read_text().splitlines())


def test_a_gradle_run_that_wrote_no_results_and_failed_is_a_failure(ops):
    wt = jt_worktree(ops, "exit 1")
    scope = json.dumps({"scope": "scoped", "gradle_tasks": [":service:test"], "frontend": False})
    out = ops.bash(f'bash "{OPS}/jt.sh" "{wt}" \'{scope}\' refs/shipq/x').stdout
    assert "java tests 0 failures 1" in out and "gradle" in out


# ---- Java flaky re-run ---------------------------------------------------------------------------------------------

XML = '<testsuite name="{cls}" tests="2"><testcase classname="{cls}" name="a"/>{case}</testsuite>'
FAILING = '<testcase classname="{cls}" name="b"><failure message="boom"/></testcase>'


def java_results(ops, classes, allowlist, rerun_passes=True, rc=0):
    ui2 = ops.tmp / "ui2"
    res = ui2 / "service" / "build" / "test-results" / "test"
    res.mkdir(parents=True)
    for cls in classes:
        (res / f"TEST-{cls}.xml").write_text(XML.format(cls=cls, case=FAILING.format(cls=cls)))
    calls = ops.tmp / "gradle.calls"
    passing = XML.format(cls="CLS", case='<testcase classname="CLS" name="b"/>')
    ops.fake("fakegradle", f'echo "$@" >> "{calls}"\ncls="${{@: -2:1}}"\n'
             + (f'echo \'{passing}\' | sed "s/CLS/$cls/g" > "service/build/test-results/test/TEST-$cls.xml"' if rerun_passes else 'exit 1'))
    allow(ops, allowlist)
    r = ops.bash(f'python3 -I "{OPS}/jt_java_results.py" "{ui2}" {rc}', extra_env={"NEXUS_GRADLE": str(ops.bin / "fakegradle")})
    return r.stdout.splitlines(), (calls.read_text().splitlines() if calls.exists() else [])


def test_a_listed_flaky_class_is_re_run_once_without_a_daemon(ops):
    lines, calls = java_results(ops, ["com.x.ScriptRunBrokerTest"], "java ScriptRunBrokerTest\n")
    assert "java tests 2 failures 0" in lines and "FLAKY ScriptRunBrokerTest.b passed on re-run" in lines
    assert calls == ["--no-daemon :service:test --tests com.x.ScriptRunBrokerTest -q"]
    assert "ScriptRunBrokerTest.b" in (ops.tmp / "flaky.log").read_text()


def test_an_unlisted_class_is_never_re_run(ops):
    lines, calls = java_results(ops, ["com.x.PolicyThingTest"], "java ScriptRunBrokerTest\n")
    assert "java tests 2 failures 1" in lines and calls == []
    assert any("not re-run" in l and "PolicyThingTest" in l for l in lines)


def test_a_security_class_is_never_re_run_even_when_listed(ops):
    lines, calls = java_results(ops, ["com.x.redaction.LogRedactorTest"], "java LogRedactorTest\njava com.x.redaction.LogRedactorTest\n")
    assert "java tests 2 failures 1" in lines and calls == []


def test_a_listed_class_that_fails_again_stays_failed(ops):
    lines, calls = java_results(ops, ["com.x.ScriptRunBrokerTest"], "java ScriptRunBrokerTest\n", rerun_passes=False)
    assert any(l.startswith("java tests") and l.endswith("failures 1") for l in lines) or "failures 1" in " ".join(lines)
    assert not any(l.startswith("FLAKY") for l in lines) and len(calls) == 1


def test_more_than_five_failures_are_never_re_run(ops):
    classes = [f"com.x.ScriptRunBrokerTest{i}" for i in range(6)]
    lines, calls = java_results(ops, classes, "".join(f"java ScriptRunBrokerTest{i}\n" for i in range(6)))
    assert calls == [] and "failures 6" in " ".join(lines)


def test_shell_and_python_allowlist_semantics_match(ops):
    allow(ops, "java Foo\nvitest tests/A.test.tsx\n")
    sh = lambda kind, name: ops.source("ops_lib.sh", f'flaky_allowed {kind} "{name}" && echo y || echo n').stdout.strip()  # noqa: E731
    assert (sh("java", "Foo"), sh("java", "pkg.Foo"), sh("java", "Bar"), sh("vitest", "tests/A.test.tsx"), sh("vitest", "Foo")) == \
        ("y", "y", "n", "y", "n")


# ---- main-green ----------------------------------------------------------------------------------------------------

def test_main_green_re_runs_only_allowlisted_vitest_files_and_goes_red_on_a_crash(ops):
    wt = ops.tmp / "mgwt"
    (wt / "ui2" / "frontend").mkdir(parents=True)
    ops.fake("python3", "exit 0")
    ops.fake("npx", 'if [ "$1" = tsc ]; then exit 0; fi; if [ "$3" = "--reporter=dot" ]; then cat "$MG_OUT"; exit "$MG_RC"; fi; exit 0')
    base = {"NEXUS_MAIN_GREEN_WT": str(wt), "MG_OUT": str(ops.tmp / "mg.out")}
    (ops.state / "logs").mkdir(exist_ok=True)
    def check(output, rc):
        (ops.tmp / "mg.out").write_text(output)
        return ops.source("main-green.sh", 'mg_check_sha deadbeefcafe', extra_env=dict(base, MG_RC=str(rc))).stdout.strip()
    assert check(" Tests 5 passed\n", 0) == ""
    assert check("crash: out of memory\n", 1) == "vitest"                       # no failed file listed: RED, not "flaky"
    assert check(" FAIL  tests/Real.test.tsx\n Tests 1 failed\n", 1) == "vitest"
    allow(ops, "vitest tests/Flaky.test.tsx\n")
    assert check(" FAIL  tests/Flaky.test.tsx\n Tests 1 failed\n", 1) == ""    # allowlisted and passes on its re-run


# ---- hygiene -------------------------------------------------------------------------------------------------------

def test_every_ops_script_parses_and_none_hardcodes_a_user_path():
    for path in sorted(OPS.glob("*.sh")):
        assert subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True).returncode == 0, path
        assert os.access(path, os.X_OK), f"{path.name} must be executable"
        text = path.read_text()
        code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
        assert "/Users/" not in text, path.name
        assert "~/Codo" not in code, f"{path.name}: paths come from ops_lib.sh (NEXUS_OPS_*), not a literal home directory"
    for path in sorted(OPS.glob("*.py")):
        compile(path.read_text(), str(path), "exec")
        assert "/Users/" not in path.read_text()


def test_the_trailer_address_is_assembled_for_the_privacy_gate(ops):
    assert ops.source("ops_lib.sh", 'echo "$OPS_NOREPLY"').stdout.strip() == "noreply@anthropic.com"
    for path in OPS.glob("*.sh"):
        assert "noreply@" not in path.read_text(), path.name
