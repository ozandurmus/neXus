"""A failed e2e Job names its failing tests by title only; no other log text leaves (PO 2026-11-23)."""
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "e2e"))
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import e2e_failure_titles as titles
import hosta_preview_e2e as preview
import module_deploy as deploy
import standalone_orchestrate as sa

COMMIT = "a" * 40

SPEC = '''import { test, expect } from "./fixtures";
const SCREENS = ["overview", "estate"];

test.describe("group one", () => {
  test("a plain title", async () => {});
});

for (const screen of SCREENS) {
  test(`${screen}: renders its screen without loading errors`, async () => {});
}

test('single quoted title', async () => {});
'''


def tree_with_spec(tmp_path, text=SPEC):
    directory = tmp_path / "ui2/frontend/e2e"
    directory.mkdir(parents=True)
    (directory / "screens.spec.ts").write_text(text)
    return tmp_path


def line_of(text, needle):
    return next(i for i, row in enumerate(text.splitlines(), 1) if needle in row)


PLAIN = line_of(SPEC, 'test("a plain title"')
DYNAMIC = line_of(SPEC, "test(`${screen}")
SINGLE = line_of(SPEC, "test('single quoted title'")


def log(*rows):
    return "\n".join(rows) + "\n"


def test_failed_titles_are_kept_in_the_list_reporter_shape(tmp_path):
    tree = tree_with_spec(tmp_path)
    text = log(
        "Running 4 tests using 1 worker",
        f"  ✘  1 [chromium] › e2e/screens.spec.ts:{DYNAMIC}:3 › overview: renders its screen without loading errors (5.0s)",
        f"  ✘  2 e2e/screens.spec.ts:{SINGLE}:1 › single quoted title (retry #1) (1.2s)",
        f"  ✘  3 [chromium] › e2e/screens.spec.ts:{PLAIN}:3 › group one › a plain title (900ms)",
        "  2 failed", "  1 flaky", "  10 passed (1.2m)")
    lines = titles.report_lines(text, tree)
    assert lines == [
        f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{DYNAMIC} overview: renders its screen without loading errors",
        f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{SINGLE} single quoted title",
        f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{PLAIN} group one › a plain title",
        "PREVIEW E2E COUNTS: failed=2 passed=10 flaky=1"]
    for row in lines:
        assert re.fullmatch(titles.TITLE_LINE_PATTERN + "|" + titles.COUNTS_LINE_PATTERN, row)
    assert titles.first_failed(lines).startswith(f"e2e/screens.spec.ts:{DYNAMIC} overview")


def test_ansi_colour_codes_do_not_hide_a_title(tmp_path):
    tree = tree_with_spec(tmp_path)
    row = f"  \x1b[31m✘\x1b[39m  1 e2e/screens.spec.ts:{SINGLE}:1 › single quoted title \x1b[2m(1.0s)\x1b[22m"
    assert titles.extract(row, tree)[0] == [("e2e/screens.spec.ts", SINGLE, "single quoted title")]


def test_anything_that_is_not_a_spec_title_is_dropped(tmp_path):
    tree = tree_with_spec(tmp_path)
    text = log(
        "  Error: expect(locator).toContainText(expected) failed",
        "    Locator: getByRole('heading')   Received: FW-TANGO-04 at 192.0.2.7",
        # A failure header whose title is page text or an unknown name.
        f"  ✘  1 e2e/screens.spec.ts:{SINGLE}:1 › single quoted title for host 192.0.2.7",
        f"  ✘  2 e2e/screens.spec.ts:{SINGLE}:1 › FW-TANGO-04 is down",
        f"  ✘  3 e2e/screens.spec.ts:{PLAIN}:3 › not a title in the spec",
        # Wrong line, wrong spec file, no spec at all.
        f"  ✘  4 e2e/screens.spec.ts:{SINGLE + 1}:1 › single quoted title",
        f"  ✘  5 e2e/other.spec.ts:{SINGLE}:1 › single quoted title",
        "  ✘  6 something › single quoted title",
        # A dynamic title whose placeholder carries an address or a long run of text.
        f"  ✘  7 e2e/screens.spec.ts:{DYNAMIC}:3 › 192.0.2.7: renders its screen without loading errors",
        f"  ✘  8 e2e/screens.spec.ts:{DYNAMIC}:3 › {'x' * 200}: renders its screen without loading errors",
        f"  ✘  9 e2e/screens.spec.ts:{DYNAMIC}:3 › overview: renders its screen without loading errors; Received: FW-TANGO-04")
    assert titles.report_lines(text, tree) == []


def test_a_missing_spec_directory_names_nothing(tmp_path):
    row = "  ✘  1 e2e/screens.spec.ts:3:1 › a plain title"
    assert titles.report_lines(row, tmp_path) == []


def test_at_most_twenty_titles_and_a_flaky_test_is_not_failed(tmp_path):
    many = "".join(f'test("case {i}", async () => {{}});\n' for i in range(30))
    tree = tree_with_spec(tmp_path, many)
    rows = [f"  ✘  {i} e2e/screens.spec.ts:{i + 1}:1 › case {i} (1.0s)" for i in range(30)]
    lines = titles.report_lines(log(*rows, "  30 failed"), tree)
    assert len([row for row in lines if row.startswith(titles.TITLE_LINE)]) == 20
    assert lines[-1] == "PREVIEW E2E COUNTS: failed=30 passed=? flaky=?"
    flaky = log("  ✘  1 e2e/screens.spec.ts:1:1 › case 0 (1.0s)", "  ✓  2 e2e/screens.spec.ts:1:1 › case 0 (retry #1) (1.0s)")
    assert titles.extract(flaky, tree)[0] == []


def test_counts_alone_are_reported_with_unknown_markers(tmp_path):
    tree = tree_with_spec(tmp_path)
    assert titles.report_lines("  1 failed\n", tree) == ["PREVIEW E2E COUNTS: failed=1 passed=? flaky=?"]
    assert titles.report_lines("Error: nothing useful at 192.0.2.7\n", tree) == []


def test_the_real_spec_sources_parse_into_titles():
    found = titles.spec_titles(ROOT)
    source = (ROOT / "ui2/frontend/e2e/screens.spec.ts").read_text()
    line = line_of(source, "renders its screen and populated estate data without loading errors")
    title = "overview: renders its screen and populated estate data without loading errors"
    assert titles._valid(found, "e2e/screens.spec.ts", line, title)
    assert not titles._valid(found, "e2e/screens.spec.ts", line, title + " 192.0.2.7")
    assert not titles._valid(found, "e2e/screens.spec.ts", line + 1, title)


# ----------------------------------------------------------------------------- preview script, wrapper, client

def test_preview_reports_titles_before_the_fail_line_and_withholds_the_log(tmp_path, monkeypatch, capsys):
    tree = tree_with_spec(tmp_path)
    secret = "Received: FW-TANGO-04 on 192.0.2.7"
    served = log(f"  ✘  1 e2e/screens.spec.ts:{SINGLE}:1 › single quoted title (1.0s)", secret, "  1 failed")
    calls = []
    monkeypatch.setattr(preview, "k", lambda ns, *a, **kw: calls.append((ns, a)) or served)
    preview.report_failed_tests(tree, "ns-x", "job-x")
    output = capsys.readouterr().out
    assert f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{SINGLE} single quoted title" in output
    assert "PREVIEW E2E COUNTS: failed=1 passed=? flaky=?" in output
    assert "FW-TANGO-04" not in output and "192.0.2.7" not in output
    assert calls[0][1][:3] == ("logs", "-l", "job-name=job-x")


def test_preview_log_read_failure_adds_nothing(tmp_path, monkeypatch, capsys):
    def fail(*a, **kw):
        raise preview.PreviewFailure("OTHER")
    monkeypatch.setattr(preview, "k", fail)
    preview.report_failed_tests(tmp_path, "ns-x", "job-x")
    assert capsys.readouterr().out == ""


def test_the_wrapper_filter_passes_only_the_two_new_line_shapes():
    source = (ROOT / "tools/e2e/hosta_preview_e2e.sh").read_text()
    awk = source[source.index("awk '") + 5:source.rindex("'")]
    assert "PREVIEW E2E FAILED TEST: e2e" in awk and "PREVIEW E2E COUNTS: failed=" in awk
    if subprocess.run(["which", "awk"], capture_output=True).returncode:
        pytest.skip("no awk")
    sample = "\n".join([
        f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{SINGLE} single quoted title",
        "PREVIEW E2E COUNTS: failed=1 passed=? flaky=2",
        "PREVIEW E2E FAILED TEST: ../etc/passwd:1 x",
        "PREVIEW E2E COUNTS: failed=1 passed=9 flaky=2 extra",
        "Error: page text", ""])
    out = subprocess.run(["awk", awk], input=sample, capture_output=True, text=True).stdout.splitlines()
    assert out == sample.splitlines()[:2]


def test_client_prints_titles_and_counts_and_names_the_first_in_the_stop_reason(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: "# Synthetic wrapper\n")
    out = "\n".join([
        "PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:113 overview: renders its screen",
        "PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:99 estate: renders its screen",
        "PREVIEW E2E COUNTS: failed=2 passed=8 flaky=0",
        "PREVIEW E2E FAILED TEST: /etc/passwd:1 not a spec path",
        "PREVIEW E2E COUNTS: failed=one passed=8 flaky=0",
        "free text with 192.0.2.7",
        "PREVIEW E2E: FAIL phase=preview_e2e step=get job reason=OTHER:RuntimeError", ""])
    monkeypatch.setattr(sa.subprocess, "run", lambda *a, **kw: subprocess.CompletedProcess(a, 1, out, ""))
    with pytest.raises(SystemExit) as stop:
        sa._preview_e2e(ROOT, COMMIT)
    assert "first failed test: e2e/screens.spec.ts:113 overview: renders its screen" in str(stop.value)
    printed = capsys.readouterr().out.splitlines()
    assert "PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:99 estate: renders its screen" in printed
    assert "PREVIEW E2E COUNTS: failed=2 passed=8 flaky=0" in printed
    assert not any("/etc/passwd" in row or "failed=one" in row or "192.0.2.7" in row for row in printed)
    assert any(row.startswith("PREVIEW E2E: FAIL") for row in printed)


def test_client_caps_printed_titles_at_twenty(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: "# Synthetic wrapper\n")
    out = "\n".join(f"PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:{i} case {i}" for i in range(1, 31))
    monkeypatch.setattr(sa.subprocess, "run", lambda *a, **kw: subprocess.CompletedProcess(a, 1, out + "\n", ""))
    with pytest.raises(SystemExit):
        sa._preview_e2e(ROOT, COMMIT)
    assert capsys.readouterr().out.count("PREVIEW E2E FAILED TEST: ") == 20


# ------------------------------------------------------------------------------------ production module e2e

def test_a_failed_module_e2e_names_failing_tests_and_the_stop_reason(tmp_path, monkeypatch, capsys):
    tree = tree_with_spec(tmp_path)
    served = log(f"  ✘  1 e2e/screens.spec.ts:{SINGLE}:1 › single quoted title (1.0s)", "Received: FW-TANGO-04", "  1 failed")
    monkeypatch.setattr(deploy, "REPO", tree)
    def run(*args, **kwargs):
        if args[0] == "python3":
            return "sha256:" + "a" * 64
        if "logs" in args:
            return served
        if any("conditions" in a for a in args):
            return "Failed=True\n"
        return ""
    monkeypatch.setattr(deploy, "run", run)
    (tree / "deploy/ui2").mkdir(parents=True)
    (tree / "deploy/ui2/70-e2e-job.yaml").write_text("kind: Job\n  suspend: true\nnexus-ui2-e2e:SET_AT_DEPLOY\n")
    with pytest.raises(deploy.Blocked, match="first failed test: e2e/screens.spec.ts:%d single quoted title" % SINGLE):
        deploy.module_e2e("a" * 40)
    output = capsys.readouterr().out
    assert "PREVIEW E2E FAILED TEST: e2e/screens.spec.ts:%d single quoted title" % SINGLE in output
    assert "FW-TANGO-04" not in output
