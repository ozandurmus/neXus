"""Lane harness v1 (tools/delivery/lane_harness.py, checks.yaml): typed exits, the report bound to the tree id, report
reuse, and the pin between the check manifest and the GitHub workflow. Every repository here is a throwaway `git init`
in a tmp directory; nothing reaches a host, a remote or Gradle."""
from __future__ import annotations

import copy
import json
import os
import re
import shutil
import socket
import stat
import subprocess
import sys
import textwrap
import time
from pathlib import Path

import pytest
import yaml

from tools.delivery import lane_harness as lh

ROOT = Path(__file__).resolve().parents[2]
REAL_MANIFEST = yaml.safe_load((ROOT / "tools/delivery/checks.yaml").read_text(encoding="utf-8"))
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/validation.yml").read_text(encoding="utf-8"))
MIG = "ui2/service/src/main/resources/db/migration"

pytestmark = [pytest.mark.runtime_platform, pytest.mark.skipif(shutil.which("git") is None, reason="git is required")]


# ---------------------------------------------------------------------------------------------------------------------
# Fixtures: a fake repository, a manifest with only the steps a test needs, and a runner for the harness


def git(repo: Path, *argv: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *argv], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    return result.stdout.strip()


def write(repo: Path, rel: str, text: str) -> None:
    path = repo / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def commit(repo: Path, message: str = "Change\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>") -> None:
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", message)


@pytest.fixture
def repo(tmp_path):
    root = tmp_path / "wt"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    for key, value in (("user.name", "Test"), ("user.email", "test@example.invalid"), ("commit.gpgsign", "false")):
        git(root, "config", key, value)
    write(root, "README.md", "base\n")
    write(root, "app/a.txt", "one\n")
    commit(root, "Base")
    git(root, "checkout", "-q", "-b", "lane/q-test")
    return root


def manifest_with(tmp_path: Path, steps: list, **overrides) -> Path:
    manifest = copy.deepcopy(REAL_MANIFEST)
    manifest["steps"] = steps
    manifest["derived"] = overrides.pop("derived", [])
    manifest.update(overrides)
    path = tmp_path / "checks.yaml"
    path.write_text(yaml.safe_dump(manifest), encoding="utf-8")
    return path


def run_harness(repo: Path, tmp_path: Path, steps: list, *args: str, **manifest_overrides) -> tuple:
    manifest = manifest_with(tmp_path, steps, **manifest_overrides)
    report = tmp_path / "out" / "harness-report.json"
    code = lh.main(["--worktree", str(repo), "--base", "main", "--manifest", str(manifest), "--report", str(report), *args])
    return code, json.loads(report.read_text(encoding="utf-8"))


def statuses(report: dict) -> dict:
    return {check["id"]: check["status"] for check in report["checks"]}


PRE = [{"id": "preconditions", "builtin": "preconditions"}]
FAKE_BIN_MODE = stat.S_IRWXU


# ---------------------------------------------------------------------------------------------------------------------
# Pure helpers


def test_path_matching_rules():
    assert lh.path_matches("ui2/worker/src/A.java", "ui2/worker/")
    assert lh.path_matches("ui2/worker/src/A.java", "ui2/worker")          # a bare directory is a prefix
    assert lh.path_matches("docs/x.md", "docs/x.md")
    assert not lh.path_matches("docs/x.mdx", "docs/x.md")
    assert lh.path_matches("ui2/frontend/src/a/B.tsx", "ui2/frontend/src/**/*.tsx")
    assert not lh.path_matches("ui2/frontend/src/a/B.ts", "ui2/frontend/src/**/*.tsx")
    assert lh.path_matches("a/b.py", "a/*.py") and not lh.path_matches("a/c/b.py", "a/*.py")
    assert not lh.path_matches("anything", "")


def test_meta_parsing_accepts_repeated_and_comma_separated_fields(tmp_path):
    meta = tmp_path / "x.meta"
    meta.write_text("base=origin/main\npaths=ui2/worker/, docs/\npaths=tools/tests/x.py\narea=worker\nbacklog_id=B-1\n"
                    "accept=echo one\naccept=test -f a b\nallow_paths=1\nname=lane-x\n", encoding="utf-8")
    parsed = lh.parse_meta(meta)
    assert parsed["paths"] == ["ui2/worker/", "docs/", "tools/tests/x.py"]
    assert parsed["accept"] == ["echo one", "test -f a b"]
    assert (parsed["area"], parsed["backlog_id"], parsed["allow_paths"], parsed["name"]) == ("worker", "B-1", True, "lane-x")
    assert lh.parse_meta(None)["paths"] == []


# ---------------------------------------------------------------------------------------------------------------------
# Typed exit codes


def test_no_commit_is_exit_30(repo, tmp_path):
    code, report = run_harness(repo, tmp_path, PRE)
    assert (code, report["result"]) == (30, "NO_CHANGE")
    assert report["exit_code"] == 30


def test_dirty_tree_is_a_check_failure(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    write(repo, "stray.txt", "left behind\n")
    code, report = run_harness(repo, tmp_path, PRE)
    assert code == 10 and "not clean" in report["checks"][0]["detail"]
    assert "repair_hint" in report


def test_pass_report_is_bound_to_the_tree_id(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE)
    assert code == 0 and report["result"] == "PASS" and report["complete"] is True
    assert report["tree_id"] == git(repo, "rev-parse", "HEAD^{tree}")
    assert report["head"] == git(repo, "rev-parse", "HEAD")
    assert report["base_sha"] == git(repo, "rev-parse", "main")
    assert report["changed_files"] == ["app/a.txt"]
    assert report["schema"] == "lane-harness-report/1" and len(report["manifest_sha256"]) == 64


def test_conflict_with_fresh_main_is_exit_12(repo, tmp_path):
    write(repo, "app/a.txt", "lane\n")
    commit(repo)
    git(repo, "checkout", "-q", "main")
    write(repo, "app/a.txt", "main moved\n")
    commit(repo, "Main moves")
    git(repo, "checkout", "-q", "lane/q-test")
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "main_conflict", "builtin": "main_conflict"}])
    assert code == 12 and report["result"] == "CONFLICT_WITH_MAIN"
    assert "app/a.txt" in statuses(report) or "app/a.txt" in report["checks"][1]["detail"]
    assert "a.txt" in report["repair_hint"]


def test_a_change_that_main_did_not_touch_merges_cleanly(repo, tmp_path):
    write(repo, "app/b.txt", "lane\n")
    commit(repo)
    git(repo, "checkout", "-q", "main")
    write(repo, "app/a.txt", "main moved\n")
    commit(repo, "Main moves")
    git(repo, "checkout", "-q", "lane/q-test")
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "main_conflict", "builtin": "main_conflict"}])
    assert code == 0 and statuses(report)["main_conflict"] == "pass"


def test_conflict_on_a_rendered_file_is_not_a_lane_conflict(repo, tmp_path):
    rendered = "tools/security/aiview_leak_audit/routes.json"
    write(repo, rendered, "[]\n")
    commit(repo, "Base routes")
    git(repo, "branch", "-f", "main", "HEAD")
    write(repo, rendered, '["/a"]\n')
    write(repo, "app/b.txt", "lane\n")
    commit(repo)
    git(repo, "checkout", "-q", "main")
    write(repo, rendered, '["/b"]\n')
    commit(repo, "Main re-renders")
    git(repo, "checkout", "-q", "lane/q-test")
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "main_conflict", "builtin": "main_conflict"}])
    assert code == 0 and report["checks"][1]["extra"]["rendered_conflicts"] == [rendered]


GUARDS = PRE + [{"id": "guarded_paths", "builtin": "guarded_paths"}]


def test_guarded_path_is_held_for_the_product_owner(repo, tmp_path):
    write(repo, "tools/delivery/new_tool.py", "print(1)\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, GUARDS)
    assert code == 20 and report["result"] == "HELD_FOR_PO" and report["tier"] == "C"
    assert "tools/delivery/new_tool.py" in report["checks"][1]["detail"]


def test_guarded_path_with_allow_paths_passes_as_tier_c(repo, tmp_path):
    write(repo, "deploy/x.yaml", "a: 1\n")
    commit(repo)
    meta = tmp_path / "lane.meta"
    meta.write_text("allow_paths=1\n", encoding="utf-8")
    code, report = run_harness(repo, tmp_path, GUARDS, "--meta", str(meta))
    assert code == 0 and report["tier"] == "C"
    code, report = run_harness(repo, tmp_path, GUARDS, "--allow-paths")
    assert code == 0


def test_tier_b_and_a_classification(repo, tmp_path):
    write(repo, "ui2/worker/src/main/java/A.java", "class A {}\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, GUARDS)
    assert code == 0 and report["tier"] == "B"
    write(repo, "ui2/frontend/src/estate/E.tsx", "export {}\n")
    git(repo, "rm", "-q", "ui2/worker/src/main/java/A.java")
    commit(repo, "Estate only")
    code, report = run_harness(repo, tmp_path, GUARDS)
    assert report["tier"] == "A"


def test_declared_paths_must_cover_every_changed_path(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    write(repo, "docs/note.md", "outside\n")
    commit(repo)
    meta = tmp_path / "lane.meta"
    meta.write_text("paths=app/\n", encoding="utf-8")
    steps = PRE + [{"id": "declared_paths", "builtin": "declared_paths"}]
    code, report = run_harness(repo, tmp_path, steps, "--meta", str(meta))
    assert code == 10 and "docs/note.md" in report["checks"][1]["detail"]
    meta.write_text("paths=app/ docs/\n", encoding="utf-8")
    code, report = run_harness(repo, tmp_path, steps, "--meta", str(meta))
    assert code == 0
    code, report = run_harness(repo, tmp_path, steps)            # nothing declared: skipped, not failed
    assert code == 0 and statuses(report)["declared_paths"] == "skipped"


def test_a_failed_step_stops_the_run_unless_keep_going(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    steps = PRE + [{"id": "boom", "cmd": ["{python}", "-c", "raise SystemExit(3)"]},
                   {"id": "later", "cmd": ["{python}", "-c", "pass"]}]
    code, report = run_harness(repo, tmp_path, steps)
    assert code == 10 and statuses(report)["later"] == "skipped"
    code, report = run_harness(repo, tmp_path, steps, "--keep-going")
    assert code == 10 and statuses(report)["later"] == "pass"


# ---------------------------------------------------------------------------------------------------------------------
# Agent outcome: 40 / 41 are never confused with 30


AGENT = [{"id": "agent_outcome", "builtin": "agent_outcome"}] + PRE


def test_agent_permission_denied_is_40_not_30(repo, tmp_path):
    log = tmp_path / "agent.log"
    log.write_text('the "run_command" permission was auto-denied\n[argon] exit=3\n', encoding="utf-8")
    code, report = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log), "--agent-exit", "3")
    assert code == 40 and report["result"] == "AGENT_PERMISSION_DENIED"


def test_agent_infrastructure_errors_are_41(repo, tmp_path):
    log = tmp_path / "agent.log"
    log.write_text("backend returned an empty conversation id\n", encoding="utf-8")
    code, report = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log), "--agent-exit", "1")
    assert code == 41
    log.write_text("429 usage limit reached\n", encoding="utf-8")
    code, _ = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log))        # no commit, no exit code
    assert code == 41


def test_agent_exit_without_a_marker_is_infra_and_clean_no_op_is_30(repo, tmp_path):
    log = tmp_path / "agent.log"
    log.write_text("something unexpected\n", encoding="utf-8")
    code, _ = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log), "--agent-exit", "137")
    assert code == 41
    code, _ = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log), "--agent-exit", "0")
    assert code == 30


def test_a_rate_limit_word_in_a_successful_run_is_ignored(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    log = tmp_path / "agent.log"
    log.write_text("the test named rateLimit429 passed\n", encoding="utf-8")
    code, _ = run_harness(repo, tmp_path, AGENT, "--agent-log", str(log), "--agent-exit", "0")
    assert code == 0


# ---------------------------------------------------------------------------------------------------------------------
# Derived files


DERIVED = {"id": "gen", "check": ["{python}", "-c", "import sys; sys.exit(0 if open('gen.txt').read() == 'x\\n' else 1)"],
           "render": ["{python}", "-c", "open('gen.txt','w').write('x\\n')"], "add": ["gen.txt"], "message": "Re-render gen"}
DERIVED_STEPS = PRE + [{"id": "derived_files", "builtin": "derived_files"}]


def test_stale_derived_file_is_rerendered_and_committed(repo, tmp_path):
    write(repo, "gen.txt", "x\n")
    commit(repo, "Base gen")
    git(repo, "branch", "-f", "main", "HEAD")
    write(repo, "gen.txt", "stale\n")
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    before = git(repo, "rev-parse", "HEAD^{tree}")
    code, report = run_harness(repo, tmp_path, DERIVED_STEPS, derived=[DERIVED])
    assert code == 0 and report["derived_commits"] == ["gen"]
    assert (repo / "gen.txt").read_text() == "x\n"
    assert git(repo, "status", "--porcelain") == ""
    assert "Re-render gen" in git(repo, "log", "-1", "--format=%B") and "Co-Authored-By" in git(repo, "log", "-1", "--format=%B")
    assert report["tree_id"] == git(repo, "rev-parse", "HEAD^{tree}") != before    # the report binds the final tree


def test_check_only_mode_fails_on_a_stale_derived_file_and_writes_nothing(repo, tmp_path):
    write(repo, "gen.txt", "stale\n")
    commit(repo)
    head = git(repo, "rev-parse", "HEAD")
    code, report = run_harness(repo, tmp_path, DERIVED_STEPS, "--no-commit-derived", derived=[DERIVED])
    assert code == 10 and git(repo, "rev-parse", "HEAD") == head and git(repo, "status", "--porcelain") == ""


def test_a_current_derived_file_makes_no_commit(repo, tmp_path):
    write(repo, "gen.txt", "x\n")
    commit(repo)
    head = git(repo, "rev-parse", "HEAD")
    code, report = run_harness(repo, tmp_path, DERIVED_STEPS, derived=[DERIVED])
    assert code == 0 and report["derived_commits"] == [] and git(repo, "rev-parse", "HEAD") == head


def test_a_renderer_that_touches_other_paths_is_a_harness_error(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    rogue = dict(DERIVED, check=["{python}", "-c", "raise SystemExit(1)"],
                 render=["{python}", "-c", "open('gen.txt','w').write('x'); open('other.txt','w').write('y')"])
    code, report = run_harness(repo, tmp_path, DERIVED_STEPS, derived=[rogue])
    assert code == 50 and "other.txt" in report["checks"][1]["detail"]


def test_repo_inventory_gains_and_loses_rows_for_live_tests(tmp_path):
    root = tmp_path / "r"
    (root / "tools/tests").mkdir(parents=True)
    (root / "docs").mkdir()
    for name in ("test_a.py", "test_c.py"):
        (root / "tools/tests" / name).write_text("", encoding="utf-8")
    header = "path,top_level,python_file,tracked,kind,classification,proposed_destination,evidence_kind,references,notes"
    row = lh.INVENTORY_ROW
    (root / "docs/repo_inventory.csv").write_text("\n".join([
        header, "scripts/x.py,false,true,true,file,LIVE_TOOLING,retain,reference,,n", row.format(name="test_a.py"),
        row.format(name="test_gone.py"),
        "tests/test_legacy.py,false,true,true,file,TEST_OF_LEGACY,history/tests/test_legacy.py,classification,,old"]) + "\n",
        encoding="utf-8")
    text, added, removed = lh.repo_inventory_plan(root)
    assert (added, removed) == (["test_c.py"], ["test_gone.py"])
    lines = text.splitlines()
    assert row.format(name="test_c.py") in lines and row.format(name="test_gone.py") not in lines
    assert lines.index(row.format(name="test_c.py")) == lines.index(row.format(name="test_a.py")) + 1
    assert any(line.startswith("tests/test_legacy.py") for line in lines)        # a legacy row stays
    (root / "docs/repo_inventory.csv").write_text(text, encoding="utf-8")
    assert lh.repo_inventory_plan(root) == (None, [], [])                          # idempotent


def test_this_repository_inventory_is_current():
    """The inventory row for this very test file (and every other live test) exists; the harness would add none."""
    assert lh.repo_inventory_plan(ROOT) == (None, [], [])


# ---------------------------------------------------------------------------------------------------------------------
# Static checks


def test_secret_shaped_content_fails_without_echoing_the_value(repo, tmp_path):
    secret = "gh" + "p_" + "A1b2C3d4E5" * 4              # built at runtime: this file holds no literal
    write(repo, "app/leak.txt", "token = %s\n" % secret)
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "secret_scan", "builtin": "secret_scan"}])
    assert code == 10
    detail = report["checks"][1]["detail"]
    assert "app/leak.txt:1" in detail and secret not in json.dumps(report)


def test_private_key_header_is_found(repo, tmp_path):
    header = "-----BEGIN " + "RSA PRIVATE " + "KEY-----"
    write(repo, "app/k.pem", header + "\nAAAA\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "secret_scan", "builtin": "secret_scan"}])
    assert code == 10 and "private key block" in report["checks"][1]["detail"]


def test_forbidden_words_under_ui2_fail_but_markdown_and_fixtures_do_not(repo, tmp_path):
    word = "pyth" + "on"
    write(repo, "ui2/worker/src/main/java/A.java", "// uses %s\n" % word)
    write(repo, "ui2/README.md", "%s allowed in markdown\n" % word)
    write(repo, "ui2/frontend/tests/fixtures/f.json", '{"x": "%s"}\n' % word)
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "forbidden_tokens", "builtin": "forbidden_tokens"}])
    detail = report["checks"][1]["detail"]
    assert code == 10 and "A.java" in detail and "README" not in detail and "fixtures" not in detail


def test_whitespace_findings_are_a_warning_not_a_failure(repo, tmp_path):
    write(repo, "app/a.txt", "trailing space \n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "whitespace", "builtin": "whitespace", "severity": "warn"}])
    assert code == 0 and statuses(report)["whitespace"] == "warn"


def test_test_integrity_flags_deleted_tests_and_new_skips_as_warnings(repo, tmp_path):
    write(repo, "tools/tests/test_x.py", "def test_a():\n    assert 1\n")
    commit(repo, "Base test")
    git(repo, "branch", "-f", "main", "HEAD")
    write(repo, "tools/tests/test_x.py", "import pytest\n@pytest.mark.skip\ndef test_a():\n    pass\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [{"id": "test_integrity", "builtin": "test_integrity", "severity": "warn"}])
    check = report["checks"][1]
    assert code == 0 and check["status"] == "warn"
    assert "assertion" in check["detail"] and "skip marker" in check["detail"]


# ---------------------------------------------------------------------------------------------------------------------
# Migration numbers


def seed_migrations(repo: Path, versions=(1, 2, 3)) -> None:
    for v in versions:
        write(repo, "%s/V%d__base.sql" % (MIG, v), "select %d;\n" % v)
    commit(repo, "Migrations on main")
    git(repo, "branch", "-f", "main", "HEAD")


MIGRATION = PRE + [{"id": "migration_rule", "builtin": "migration_rule"}]


def ship_entry(queue: Path, name: str, branch: str, inflight: bool = False) -> None:
    folder = queue / "inflight" / "D1" if inflight else queue
    folder.mkdir(parents=True, exist_ok=True)
    (folder / ("%s.ship" % name)).write_text("branch=%s\nname=%s\n" % (branch, name), encoding="utf-8")


def other_branch(repo: Path, name: str, rel: str) -> None:
    git(repo, "checkout", "-q", "-b", name, "main")
    write(repo, rel, "select 1;\n")
    commit(repo, "Other lane")
    git(repo, "checkout", "-q", "lane/q-test")


def test_migration_must_be_above_main(repo, tmp_path):
    seed_migrations(repo)
    write(repo, "%s/V3__again.sql" % MIG, "select 3;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION)
    assert code == 10 and "not above main's V3" in report["checks"][1]["detail"]


def test_migration_above_main_passes_and_an_edited_migration_fails(repo, tmp_path):
    seed_migrations(repo)
    write(repo, "%s/V4__new.sql" % MIG, "select 4;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION)
    assert code == 0 and report["migration"]["new_versions"] == [4] and report["migration"]["main_max"] == 3
    write(repo, "%s/V2__base.sql" % MIG, "select 'edited';\n")
    commit(repo, "Edit an applied migration")
    code, report = run_harness(repo, tmp_path, MIGRATION)
    assert code == 10 and "edited or deleted" in report["checks"][1]["detail"]


def test_migration_clash_with_a_queued_entry_fails(repo, tmp_path):
    seed_migrations(repo)
    other_branch(repo, "lane/q-other", "%s/V4__other.sql" % MIG)
    ship_entry(tmp_path / "queue", "other", "lane/q-other")
    write(repo, "%s/V4__mine.sql" % MIG, "select 4;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION, "--queue-dir", str(tmp_path / "queue"))
    assert code == 10 and "V4 in other" in report["checks"][1]["detail"]


def test_migration_order_with_the_queue_is_reported_ascending(repo, tmp_path):
    seed_migrations(repo)
    other_branch(repo, "lane/q-other", "%s/V4__other.sql" % MIG)
    ship_entry(tmp_path / "queue", "other", "lane/q-other")
    write(repo, "%s/V5__mine.sql" % MIG, "select 5;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION, "--queue-dir", str(tmp_path / "queue"))
    order = report["migration"]["required_order"]
    assert code == 0 and [(o["version"], o["entry"]) for o in order] == [(4, "other"), (5, "(this lane)")]
    assert statuses(report)["migration_rule"] == "warn"
    assert "ascending" in report["checks"][1]["detail"] and "V4(other) -> V5((this lane))" in report["checks"][1]["detail"]


def test_a_higher_inflight_version_makes_this_lane_out_of_order(repo, tmp_path):
    seed_migrations(repo)
    other_branch(repo, "lane/q-other", "%s/V5__other.sql" % MIG)
    ship_entry(tmp_path / "queue", "other", "lane/q-other", inflight=True)
    write(repo, "%s/V4__mine.sql" % MIG, "select 4;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION, "--queue-dir", str(tmp_path / "queue"))
    assert code == 10 and "out of order" in report["checks"][1]["detail"]


def test_gap_policy_warn_and_block(repo, tmp_path):
    seed_migrations(repo)
    write(repo, "%s/V7__mine.sql" % MIG, "select 7;\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, MIGRATION, "--queue-dir", str(tmp_path / "queue"))
    assert code == 0 and "gap" in report["checks"][1]["detail"]
    blocking = dict(REAL_MANIFEST["migrations"], gap_policy="block")
    code, _ = run_harness(repo, tmp_path, MIGRATION, "--queue-dir", str(tmp_path / "queue"), migrations=blocking)
    assert code == 10


def test_queue_overlap_is_reported_as_a_warning(repo, tmp_path):
    other_branch(repo, "lane/q-other", "app/a.txt")
    ship_entry(tmp_path / "queue", "other", "lane/q-other")
    write(repo, "app/a.txt", "mine\n")
    commit(repo)
    steps = PRE + [{"id": "queue_overlap", "builtin": "queue_overlap", "severity": "warn"}]
    code, report = run_harness(repo, tmp_path, steps, "--queue-dir", str(tmp_path / "queue"))
    assert code == 0 and statuses(report)["queue_overlap"] == "warn"
    assert report["queue_overlap"][0]["entry"] == "other"


# ---------------------------------------------------------------------------------------------------------------------
# Frontend: fail closed


@pytest.fixture
def frontend_repo(repo, tmp_path):
    (repo / "tools/delivery").mkdir(parents=True)
    shutil.copy(ROOT / "tools/delivery/pretest_scope.py", repo / "tools/delivery/pretest_scope.py")
    write(repo, "ui2/frontend/package.json", "{}\n")
    write(repo, "ui2/frontend/node_modules/.keep", "")
    write(repo, ".gitignore", "node_modules/\n")
    commit(repo, "Frontend base")
    git(repo, "branch", "-f", "main", "HEAD")
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")
    commit(repo)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    return repo, bin_dir


def fake_npx(bin_dir: Path, body: str) -> None:
    script = bin_dir / "npx"
    script.write_text("#!/bin/sh\n" + body + "\n", encoding="utf-8")
    script.chmod(FAKE_BIN_MODE)


def vitest_run(frontend_repo, tmp_path, monkeypatch, body: str) -> tuple:
    repo, bin_dir = frontend_repo
    fake_npx(bin_dir, body)
    monkeypatch.setenv("PATH", "%s%s%s" % (bin_dir, os.pathsep, os.environ["PATH"]))
    return run_harness(repo, tmp_path, PRE + [{"id": "frontend_vitest", "builtin": "frontend_vitest"}])


def test_vitest_crash_with_no_failing_file_listed_fails_closed(frontend_repo, tmp_path, monkeypatch):
    code, report = vitest_run(frontend_repo, tmp_path, monkeypatch, "echo 'Error: worker crashed' >&2; exit 1")
    check = report["checks"][1]
    assert code == 10 and "no failing test file listed" in check["detail"]


def test_vitest_exit_zero_without_a_summary_is_not_a_pass(frontend_repo, tmp_path, monkeypatch):
    code, report = vitest_run(frontend_repo, tmp_path, monkeypatch, "echo nothing useful; exit 0")
    assert code == 10 and "without a passing Test Files summary" in report["checks"][1]["detail"]


def test_vitest_failures_name_the_files_and_a_clean_run_passes(frontend_repo, tmp_path, monkeypatch):
    code, report = vitest_run(frontend_repo, tmp_path, monkeypatch,
                              "printf ' FAIL  tests/Foo.test.tsx > renders\\n Test Files  1 failed (1)\\n'; exit 1")
    assert code == 10 and report["checks"][1]["extra"]["failed_files"] == ["tests/Foo.test.tsx"]
    code, report = vitest_run(frontend_repo, tmp_path, monkeypatch,
                              "printf ' Test Files  3 passed (3)\\n      Tests  9 passed (9)\\n'; exit 0")
    assert code == 0 and "Test Files  3 passed" in report["checks"][1]["detail"]


def test_frontend_steps_skip_when_the_scope_has_no_frontend(repo, tmp_path):
    write(repo, "docs/note.md", "doc\n")
    commit(repo)
    steps = PRE + [{"id": "frontend_tsc", "builtin": "frontend_tsc"}, {"id": "frontend_vitest", "builtin": "frontend_vitest"}]
    code, report = run_harness(repo, tmp_path, steps)
    assert code == 0 and statuses(report)["frontend_vitest"] == "skipped"


# ---------------------------------------------------------------------------------------------------------------------
# Integration: the workflow's path rule, the HOST-A hand-off, the local carrier


def integration_step(exit_code: int) -> dict:
    real = next(s for s in REAL_MANIFEST["steps"] if s["id"] == "integration")
    step = dict(real)
    step["hosta_cmd"] = ["{python}", "-c", "raise SystemExit(%d)" % exit_code]
    return step


@pytest.mark.parametrize("path,runs", [("ui2/service/src/main/java/A.java", True), ("docs/operations/sql/x.sql", True),
                                        (".github/workflows/validation.yml", True), ("ui2/frontend/src/a.ts", False),
                                        ("docs/note.md", False), ("tools/delivery/x.py", False)])
def test_integration_runs_only_when_the_workflow_path_rule_would(repo, tmp_path, path, runs):
    write(repo, path, "x\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(0)], "--integration", "hosta")
    assert code == 0 and (statuses(report)["integration"] == "pass") is runs


def test_hosta_hand_off_maps_exit_codes(repo, tmp_path):
    write(repo, "ui2/service/src/main/java/A.java", "x\n")
    commit(repo)
    assert run_harness(repo, tmp_path, PRE + [integration_step(0)], "--integration", "hosta")[0] == 0
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(1)], "--integration", "hosta")
    assert code == 10 and report["checks"][1]["status"] == "fail"
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(2)], "--integration", "hosta")
    assert code == 50 and "not a test result" in report["checks"][1]["detail"]
    # 3 = the host cannot run this tree yet (no image or a stale image): a runner problem, never a test failure.
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(3)], "--integration", "hosta")
    assert code == 50 and "not a test result" in report["checks"][1]["detail"]


def test_the_manifest_hands_integration_to_the_ui2_test_namespace_runner():
    import yaml
    manifest = yaml.safe_load((ROOT / "tools" / "delivery" / "checks.yaml").read_text())
    step, = [s for s in manifest["steps"] if s["id"] == "integration"]
    assert step["hosta_cmd"][:2] == ["python3", "tools/delivery/hosta_test_run.py"]
    assert "--integration" in step["hosta_cmd"] and step["hosta_cmd"][step["hosta_cmd"].index("--java") + 1] == "none"


# ---------------------------------------------------------------------------------------------------------------------
# --carrier hosta: java_scoped, integration and frontend_vitest in one stubbed hosta_test_run.py call

CARRIER_STUB = """
import json, sys
from pathlib import Path
state = Path(sys.argv[1])
with open(state / "calls", "a") as handle:
    handle.write(json.dumps(sys.argv[2:]) + "\\n")
spec = json.loads((state / "spec.json").read_text())
print("ssh banner noise")
if spec.get("json") is not None:
    print(json.dumps(spec["json"]))
sys.exit(spec["exit"])
"""


def real_step(step_id: str) -> dict:
    return dict(next(s for s in REAL_MANIFEST["steps"] if s["id"] == step_id))


CARRIED_STEPS = [real_step("java_scoped"), real_step("frontend_vitest"), real_step("integration")]
FULL_SCOPE = {"scope": "full", "gradle_tasks": [], "frontend": True, "modules": [], "why": "test: full"}


def phase(name, status="PASS", tests=5, failed=(), duration=10):
    bad = len(failed) if status == "FAIL" else 0
    return {"name": name, "status": status, "reason": "ok" if status == "PASS" else "tests_failed",
            "exit_code": 0 if status == "PASS" else 1, "duration_s": duration, "tests": tests, "failures": bad,
            "errors": 0, "skipped": 0, "failed": list(failed)}


def host_answer(status, phases, scope=None):
    return {"status": status, "reason": "ok", "run": "ui2-test-run-0123456789ab", "commit": "c" * 40, "tree": "t" * 40,
            "summary": {"status": status, "reason": "ok", "phases": phases}, "scope": scope or {}, "wall_s": 400}


@pytest.fixture
def carrier(repo, tmp_path, monkeypatch):
    """A repository with a (fake, always passing) ui2/gradlew and a frontend on main, a stub runner and a fixed scope."""
    write(repo, "ui2/gradlew", "#!/bin/sh\nexit 0\n")
    (repo / "ui2/gradlew").chmod(FAKE_BIN_MODE)
    write(repo, "ui2/frontend/package.json", "{}\n")
    write(repo, "ui2/frontend/node_modules/.keep", "")
    write(repo, ".gitignore", "node_modules/\n")
    commit(repo, "Carrier base")
    git(repo, "branch", "-f", "main", "HEAD")
    state = tmp_path / "stub-state"
    state.mkdir()
    stub = tmp_path / "hosta_stub.py"
    stub.write_text(CARRIER_STUB, encoding="utf-8")
    scope = dict(FULL_SCOPE)
    monkeypatch.setattr(lh, "get_scope", lambda ctx: scope)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    fake_npx(bin_dir, "printf ' Test Files  2 passed (2)\\n      Tests  4 passed (4)\\n'; exit 0")
    monkeypatch.setenv("PATH", "%s%s%s" % (bin_dir, os.pathsep, os.environ["PATH"]))

    def run(exit_code, answer, *args, steps=None, cmd=None):
        (state / "spec.json").write_text(json.dumps({"exit": exit_code, "json": answer}), encoding="utf-8")
        (state / "calls").unlink(missing_ok=True)
        code, report = run_harness(repo, tmp_path, PRE + (steps or CARRIED_STEPS), "--carrier", "hosta", *args,
                                   hosta_carrier_cmd=cmd or ["{python}", str(stub), str(state)])
        calls = (state / "calls").read_text().splitlines() if (state / "calls").exists() else []
        return code, report, [json.loads(line) for line in calls]

    return {"repo": repo, "scope": scope, "run": run}


def checks_by_id(report: dict) -> dict:
    return {check["id"]: check for check in report["checks"]}


def test_carrier_hosta_runs_java_integration_and_vitest_in_one_call_with_phase_durations(carrier):
    repo = carrier["repo"]
    write(repo, "ui2/service/src/main/java/A.java", "class A {}\n")
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")
    commit(repo)
    answer = host_answer("PASS", [phase("java", duration=300, tests=6769), phase("integration", duration=200, tests=390),
                                  phase("vitest", duration=40)])
    code, report, calls = carrier["run"](0, answer)
    checks = checks_by_id(report)
    assert code == 0 and report["carrier"] == "hosta"
    assert len(calls) == 1, "the three steps must share ONE HOST-A run"
    for step_id, seconds in (("java_scoped", 300), ("integration", 200), ("frontend_vitest", 40)):
        assert checks[step_id]["status"] == "pass" and checks[step_id]["duration_s"] == seconds
        assert checks[step_id]["extra"]["carrier"] == "hosta"
    assert "6769 tests" in checks["java_scoped"]["detail"] and "390 tests" in checks["integration"]["detail"]
    argv = calls[0]
    assert argv[argv.index("--commit") + 1] == git(repo, "rev-parse", "HEAD")
    assert argv[argv.index("--base") + 1] == git(repo, "rev-parse", "main")
    assert argv[argv.index("--java") + 1] == "full" and "--integration" in argv and "--vitest" in argv
    assert report["hosta_run"]["used"] == "hosta" and report["hosta_run"]["asked"] == ["java", "integration", "vitest"]


def test_carrier_hosta_test_failure_names_the_failed_tests_in_the_step(carrier):
    repo = carrier["repo"]
    write(repo, "ui2/service/src/main/java/A.java", "class A {}\n")
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")
    commit(repo)
    failed = ["com.example.FooTest > rejectsStaleLease()", "com.example.BarTest > parses()"]
    answer = host_answer("FAIL", [phase("java", "FAIL", tests=50, failed=failed), phase("integration"),
                                  phase("vitest")])
    code, report, calls = carrier["run"](1, answer, "--keep-going")
    checks = checks_by_id(report)
    assert code == 10 and len(calls) == 1 and report["carrier"] == "hosta"
    java = checks["java_scoped"]
    assert java["status"] == "fail" and java["extra"]["failed"] == failed
    assert "FooTest > rejectsStaleLease()" in java["detail"] and "BarTest > parses()" in java["detail"]
    assert "FAIL com.example.FooTest > rejectsStaleLease()" in java["output_tail"]
    assert checks["integration"]["status"] == "pass" and checks["frontend_vitest"]["status"] == "pass"
    assert "java_scoped" in report["repair_hint"]


@pytest.mark.parametrize("exit_code,answer", [
    (2, {"status": "ERROR", "reason": "runner"}),
    (3, {"status": "IMAGE_STALE", "reason": "image_stale"}),
    (255, None),                                                     # ssh: no connection, no answer at all
    (0, {"status": "FAIL", "reason": "ok"}),                         # an answer that contradicts its exit code
])
def test_carrier_hosta_runner_error_falls_back_to_the_mac(carrier, exit_code, answer):
    repo = carrier["repo"]
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")      # frontend only: no integration step
    commit(repo)
    code, report, calls = carrier["run"](exit_code, answer, "--jobs", "1")
    checks = checks_by_id(report)
    assert code == 0 and len(calls) == 1
    assert report["carrier"] == "mac-fallback" and report["hosta_run"]["used"] == "mac-fallback"
    assert any(note.startswith("carrier=mac-fallback") for note in report["notes"])
    for step_id in ("java_scoped", "frontend_vitest"):
        assert checks[step_id]["status"] == "pass"
        assert checks[step_id]["detail"].startswith("carrier=mac-fallback")
        assert checks[step_id]["extra"]["carrier"] == "mac-fallback"
    assert "Test Files  2 passed" in checks["frontend_vitest"]["detail"]       # the Mac's own vitest ran
    assert statuses(report)["integration"] == "skipped"


def test_carrier_hosta_runner_that_cannot_start_falls_back(carrier, tmp_path):
    repo = carrier["repo"]
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")
    commit(repo)
    code, report, _ = carrier["run"](0, None, "--jobs", "1", cmd=[str(tmp_path / "no-such-runner")])
    assert code == 0 and report["carrier"] == "mac-fallback" and "exit 127" in report["hosta_run"]["reason"]


def test_carrier_hosta_passes_the_mac_scope(carrier):
    repo = carrier["repo"]
    write(repo, "ui2/worker/src/main/java/W.java", "class W {}\n")
    commit(repo)
    tasks = [":worker:test", ":architecture-tests:test"]
    carrier["scope"].update({"scope": "scoped", "gradle_tasks": tasks, "frontend": False, "why": "1 changed module"})
    scope = {"java": "scoped", "tasks": tasks, "integration": True, "vitest": False}
    answer = host_answer("PASS", [phase("java"), phase("integration")], scope)
    code, report, calls = carrier["run"](0, answer)
    argv = calls[0]
    checks = checks_by_id(report)
    assert code == 0 and argv[argv.index("--java") + 1] == "scoped"
    assert "--integration" in argv and "--vitest" not in argv
    assert checks["java_scoped"]["extra"]["scope_tasks_match"] is True and "scoped scope" in checks["java_scoped"]["detail"]
    assert checks["frontend_vitest"]["status"] == "skipped"
    # `--integration skip` keeps the integration phase out of the HOST-A run
    code, report, calls = carrier["run"](0, host_answer("PASS", [phase("java")], scope), "--integration", "skip")
    assert code == 0 and "--integration" not in calls[0] and statuses(report)["integration"] == "skipped"


def test_carrier_mac_is_the_default_and_never_calls_the_runner(carrier, tmp_path):
    repo = carrier["repo"]
    write(repo, "ui2/frontend/src/a.ts", "export const a = 1;\n")
    commit(repo)
    state = tmp_path / "stub-state"
    code, report = run_harness(repo, tmp_path, PRE + CARRIED_STEPS, "--jobs", "1",
                               hosta_carrier_cmd=["{python}", str(tmp_path / "hosta_stub.py"), str(state)])
    assert code == 0 and report["carrier"] == "mac" and not (state / "calls").exists()
    assert "carrier" not in checks_by_id(report)["java_scoped"].get("extra", {})


def test_the_manifest_names_the_hosta_test_runner_as_the_carrier():
    assert REAL_MANIFEST["hosta_carrier_cmd"][1] == "tools/delivery/hosta_test_run.py"
    assert 300 <= REAL_MANIFEST["hosta_carrier_timeout_s"] <= 3600 - 120


def test_integration_skip_flag_is_visible_and_marks_the_report_incomplete(repo, tmp_path):
    write(repo, "ui2/service/src/main/java/A.java", "x\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(0)], "--integration", "skip")
    assert code == 0 and report["complete"] is False and report["checks"][1]["extra"]["skipped_by_flag"] is True


def test_missing_local_carrier_fails_closed(repo, tmp_path, monkeypatch):
    write(repo, "ui2/service/src/main/java/A.java", "x\n")
    commit(repo)
    monkeypatch.setenv("LANE_HARNESS_PG_ARCHIVE", str(tmp_path / "no-such-archive"))
    code, report = run_harness(repo, tmp_path, PRE + [integration_step(0)], "--integration", "local")
    assert code == 50 and "no PostgreSQL 16 carrier" in report["checks"][1]["detail"]


def test_embedded_postgres_starts_and_stops_without_docker():
    try:
        lh.find_pg_archive()
    except lh.EmbeddedPostgresError:
        pytest.skip("no cached PostgreSQL 16 archive on this machine")
    with lh.EmbeddedPostgres() as database:
        with socket.create_connection(("127.0.0.1", database.port), timeout=10):
            pass
        assert database.jdbc_url.startswith("jdbc:postgresql://127.0.0.1:%d/postgres" % database.port)
        root = database.root
    assert not root.exists()
    with pytest.raises(OSError):
        socket.create_connection(("127.0.0.1", database.port), timeout=2)


def test_accept_commands_run_in_the_worktree(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    meta = tmp_path / "lane.meta"
    steps = PRE + [{"id": "accept", "builtin": "accept"}]
    meta.write_text("accept=test -f app/a.txt\naccept=grep -q two app/a.txt\n", encoding="utf-8")
    code, report = run_harness(repo, tmp_path, steps, "--meta", str(meta))
    assert code == 0 and report["checks"][1]["detail"] == "2 accept command(s) passed"
    meta.write_text("accept=test -f app/a.txt\naccept=test -f missing.txt\n", encoding="utf-8")
    code, report = run_harness(repo, tmp_path, steps, "--meta", str(meta))
    assert code == 10 and "#2" in report["checks"][1]["detail"]


def test_a_command_timeout_is_a_failure(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    step = {"id": "slow", "cmd": ["{python}", "-c", "import time; time.sleep(30)"], "timeout_s": 1}
    code, report = run_harness(repo, tmp_path, PRE + [step])
    assert code == 10 and "timed out" in report["checks"][1]["detail"]


# ---------------------------------------------------------------------------------------------------------------------
# Report reuse for the ship pretest


COUNTER = {"id": "tests", "cmd": ["{python}", "-c", "open('ran.log', 'a').write('x')"], "profiles": ["lane", "pretest"],
           "tree_bound": True}


def test_reuse_report_skips_tree_bound_steps_for_the_exact_tree(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    ran = tmp_path / "ran.log"
    step = dict(COUNTER, cmd=["{python}", "-c", "open(%r, 'a').write('x')" % str(ran)])
    code, first = run_harness(repo, tmp_path, [step])
    assert code == 0 and ran.read_text() == "x"
    saved = tmp_path / "first.json"
    saved.write_text(json.dumps(first), encoding="utf-8")
    code, second = run_harness(repo, tmp_path, [step], "--reuse-report", str(saved), "--profile", "pretest")
    assert code == 0 and ran.read_text() == "x" and statuses(second)["tests"] == "reused"
    assert second["tree_id"] == first["tree_id"]


def test_reuse_is_refused_for_another_tree_a_failed_report_or_another_manifest(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    ran = tmp_path / "ran.log"
    step = dict(COUNTER, cmd=["{python}", "-c", "open(%r, 'a').write('x')" % str(ran)])
    _, first = run_harness(repo, tmp_path, [step])
    saved = tmp_path / "first.json"
    saved.write_text(json.dumps(first), encoding="utf-8")
    write(repo, "app/a.txt", "three\n")                                  # another tree
    commit(repo, "Another change")
    _, other = run_harness(repo, tmp_path, [step], "--reuse-report", str(saved))
    assert statuses(other)["tests"] == "pass" and ran.read_text() == "xx"
    failed = dict(first, exit_code=10)
    saved.write_text(json.dumps(failed), encoding="utf-8")
    _, again = run_harness(repo, tmp_path, [step], "--reuse-report", str(saved))
    assert statuses(again)["tests"] == "pass" and ran.read_text() == "xxx"
    saved.write_text(json.dumps(dict(first, tree_id=again["tree_id"], manifest_sha256="0" * 64)), encoding="utf-8")
    _, changed = run_harness(repo, tmp_path, [step], "--reuse-report", str(saved))
    assert statuses(changed)["tests"] == "pass"


def test_reuse_never_covers_steps_that_are_not_tree_bound(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    ran = tmp_path / "ran.log"
    step = {"id": "against_main", "profiles": ["lane", "pretest"], "tree_bound": False,
            "cmd": ["{python}", "-c", "open(%r, 'a').write('x')" % str(ran)]}
    _, first = run_harness(repo, tmp_path, [step])
    saved = tmp_path / "first.json"
    saved.write_text(json.dumps(first), encoding="utf-8")
    _, second = run_harness(repo, tmp_path, [step], "--reuse-report", str(saved))
    assert statuses(second)["against_main"] == "pass" and ran.read_text() == "xx"


def test_profiles_select_steps(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    lane_only = {"id": "lane_only", "cmd": ["{python}", "-c", "pass"]}
    code, report = run_harness(repo, tmp_path, [lane_only, COUNTER], "--profile", "pretest")
    assert statuses(report) == {"lane_only": "skipped", "tests": "pass"}


# ---------------------------------------------------------------------------------------------------------------------
# Harness errors are typed, never a traceback


def test_unresolvable_base_is_exit_50_with_a_report(repo, tmp_path):
    manifest = manifest_with(tmp_path, PRE)
    report = tmp_path / "r.json"
    code = lh.main(["--worktree", str(repo), "--base", "no-such-ref", "--manifest", str(manifest), "--report", str(report)])
    data = json.loads(report.read_text())
    assert code == 50 and data["result"] == "HARNESS_ERROR" and "no-such-ref" in data["error"]


def test_broken_manifest_is_exit_50(repo, tmp_path):
    bad = tmp_path / "bad.yaml"
    bad.write_text("version: 2\nsteps: []\n", encoding="utf-8")
    report = tmp_path / "r.json"
    assert lh.main(["--worktree", str(repo), "--manifest", str(bad), "--report", str(report)]) == 50
    assert json.loads(report.read_text())["exit_code"] == 50


def test_fetch_uses_an_isolated_ref_and_never_touches_origin_main(repo, tmp_path):
    remote = tmp_path / "remote.git"
    subprocess.run(["git", "clone", "-q", "--bare", str(repo), str(remote)], check=True, capture_output=True)
    git(repo, "remote", "add", "origin", str(remote))
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE, "--fetch", "--lane-name", "lane-x")
    assert code == 0 and report["base_ref"] == "refs/harness/lane-x"
    assert git(repo, "rev-parse", "refs/harness/lane-x") == report["base_sha"]
    assert subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "-q", "origin/main"],
                          capture_output=True).returncode != 0


def test_command_line_exit_code_is_the_typed_code(repo, tmp_path):
    manifest = manifest_with(tmp_path, PRE)
    result = subprocess.run([sys.executable, str(ROOT / "tools/delivery/lane_harness.py"), "--worktree", str(repo),
                             "--base", "main", "--manifest", str(manifest), "--report", str(tmp_path / "r.json")],
                            capture_output=True, text=True, cwd=str(tmp_path))
    assert result.returncode == 30 and result.stdout.startswith("NO_CHANGE exit=30")


# ---------------------------------------------------------------------------------------------------------------------
# The manifest is pinned to the workflow


def workflow_steps(job: str) -> list:
    return WORKFLOW["jobs"][job]["steps"]


def test_real_manifest_loads_and_names_only_known_builtins():
    manifest, digest = lh.load_manifest(ROOT / "tools/delivery/checks.yaml")
    assert len(digest) == 64 and manifest["version"] == 1
    ids = [step["id"] for step in manifest["steps"]]
    assert ids == list(dict.fromkeys(ids))
    assert ids[:2] == ["agent_outcome", "preconditions"] and "integration" in ids and "accept" in ids


def test_every_validate_step_has_a_local_twin_or_an_explicit_exemption():
    twins = {step["ci_twin"] for step in REAL_MANIFEST["steps"] if step.get("ci_twin")}
    exempt = set(REAL_MANIFEST["ci_exempt"])
    named = {step["name"] for step in workflow_steps("validate") if step.get("run")}
    assert named - twins - exempt == set(), "a validate step the lane harness does not run: add it to checks.yaml"
    assert twins <= named | {s["name"] for s in workflow_steps("integration") if s.get("run")}, "a ci_twin names no workflow step"
    assert exempt <= named, "ci_exempt names a step the workflow no longer has"


def test_the_tooling_regression_runs_the_workflow_selection_minus_the_browser_test():
    ci = next(s for s in workflow_steps("validate") if s.get("name") == "Live tooling regression")["run"]
    local = next(s for s in REAL_MANIFEST["steps"] if s["id"] == "live_tooling_regression")["cmd"]
    ci_args = ci.replace("python -m pytest", "").split()
    assert [a for a in ci_args if a not in ("-q",)] == ["-n", "2", "--dist", "worksteal"]
    # Same xdist mode and distribution as `validate`; the worker count is the machine's (the workflow's own
    # `full-regression` job already runs the same suite with `-n auto`), taken from the CPU budget.
    assert "-n" in local and local[local.index("-n") + 1] == "{pytest_workers}"
    assert local[local.index("--dist") + 1] == "worksteal"
    full = next(s for s in workflow_steps("full-regression") if s.get("name") == "Full live tooling suite (parallel)")["run"]
    assert "-n auto --dist worksteal" in full
    ignored = [a for a in local if a.startswith("--ignore=")]
    assert ignored == ["--ignore=tools/tests/test_orchestrator_dashboard_browser.py"]
    assert (ROOT / "tools/tests/test_orchestrator_dashboard_browser.py").is_file()


def test_the_validate_commands_in_the_manifest_match_the_workflow_text():
    by_name = {s["name"]: s["run"] for s in workflow_steps("validate") if s.get("run")}
    local = {s["ci_twin"]: s["cmd"] for s in REAL_MANIFEST["steps"] if s.get("ci_twin") and s.get("cmd")}
    assert "tools/delivery/build_history_index.py --check" in by_name["Build history index is current"]
    assert local["Build history index is current"][-2:] == ["tools/delivery/build_history_index.py", "--check"]
    assert local["Project queue is current"][-2:] == ["tools/delivery/project_queue.py", "check"]
    assert "tools/tests/test_live_project_governance.py" in by_name["Project-state consistency"]
    assert local["Project-state consistency"][-1] == "tools/tests/test_live_project_governance.py"
    assert local["Project file size budgets"][-1] == "tools/tests/test_project_files_budget.py"
    assert "tools/privacy/repository_privacy_check.py --privacy-baseline-ref" in by_name["Repository privacy gate"]
    assert local["Repository privacy gate"][1:3] == ["tools/privacy/repository_privacy_check.py", "--privacy-baseline-ref"]


def test_the_integration_path_rule_is_the_workflows_rule():
    scope = next(s for s in workflow_steps("integration") if s.get("id") == "scope")["run"]
    step = next(s for s in REAL_MANIFEST["steps"] if s["id"] == "integration")
    assert "grep -vE '%s'" % step["when_not"] in scope
    assert "grep -qE '%s'" % step["when"] in scope
    args = next(s for s in workflow_steps("integration") if s.get("name") == "PostgreSQL 16 integration suite")["run"]
    assert ":integration-tests:test" in args and "-PfrontendPrebuilt=true" in args
    assert step["gradle_args"] == ["-PfrontendPrebuilt=true", ":integration-tests:test"]


def test_the_integration_carrier_matches_the_workflow_server_settings():
    source = (ROOT / "tools/delivery/lane_harness.py").read_text(encoding="utf-8")
    run = next(s for s in workflow_steps("integration") if s.get("name") == "Start a throwaway PostgreSQL 16")["run"]
    for setting in ("max_connections=200", "fsync=off", "synchronous_commit=off", "full_page_writes=off"):
        assert setting in run and setting in source


def test_manifest_forbidden_tokens_equal_the_architecture_test():
    test = (ROOT / "ui2/architecture-tests/src/test/java/com/securityexpert/nexus/ui2/architecture/Ui2ArchitectureTest.java")
    text = test.read_text(encoding="utf-8")
    block = text[text.index("dir10_no_line1_or_python_runtime_dependency"):]
    tokens = re.findall(r'"([^"]+)"(?: \+ "([^"]+)")?', block[block.index("List.of("):block.index("// architecture-tests/src/test")])
    java = {a + b for a, b in tokens}
    assert java == {"".join(parts) for parts in REAL_MANIFEST["forbidden_tokens_under_ui2"]}


def test_flaky_allowlist_refuses_security_names():
    manifest = {"flaky_allowlist": ["ScriptRunBrokerTest", "CredentialVaultTest"]}
    assert lh._flaky_ok(manifest, "x.y.ScriptRunBrokerTest")
    assert not lh._flaky_ok(manifest, "x.y.CredentialVaultTest")
    assert not lh._flaky_ok(manifest, "x.y.OtherTest")


# ---------------------------------------------------------------------------------------------------------------------
# The privacy gate judges what the commit contains, not what the directory holds


SCRIPT = ROOT / "tools/privacy/repository_privacy_check.py"
PRIVACY = [{"id": "privacy_gate", "cmd": ["{python}", str(SCRIPT), "--privacy-baseline-ref", "{base}",
                                            "--scan-root", "{snapshot}", "--git-cwd", "{worktree}"]}]
KEY_HEADER = "-----BEGIN " + "RSA PRIVATE " + "KEY-----"        # built at runtime: this file holds no literal


def test_privacy_gate_ignores_untracked_and_ignored_build_output(repo, tmp_path):
    write(repo, ".gitignore", "dist/\nbuild/\n")
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    write(repo, "ui2/frontend/dist/bundle.js", "// %s\n" % KEY_HEADER)       # ignored build output
    write(repo, "ui2/service/build/resources/x.json", '{"k": "%s"}\n' % KEY_HEADER)
    code, report = run_harness(repo, tmp_path, PRE + PRIVACY)
    assert code == 0, report["checks"]
    assert statuses(report)["privacy_gate"] == "pass"


def test_privacy_gate_still_fails_a_committed_secret_shaped_file(repo, tmp_path):
    write(repo, "ui2/frontend/dist/bundle.js", "// %s\n" % KEY_HEADER)       # not ignored here: it is in the commit
    commit(repo)
    code, report = run_harness(repo, tmp_path, PRE + PRIVACY)
    assert code == 10 and statuses(report)["privacy_gate"] == "fail"
    assert "ui2/frontend/dist/bundle.js" in json.dumps(report) and KEY_HEADER not in json.dumps(report)


def test_snapshot_is_the_head_tree_and_is_removed_after_the_run(repo, tmp_path):
    write(repo, ".gitignore", "*.ignored\n")
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    write(repo, "app/untracked.ignored", "x\n")
    probe = tmp_path / "probe.json"
    code_text = ("import json,sys,os; d=sys.argv[1]; json.dump({'dir': d, 'files': sorted(os.listdir(d+'/app'))}, "
                 "open(sys.argv[2], 'w'))")
    steps = PRE + [{"id": "snap", "cmd": ["{python}", "-c", code_text, "{snapshot}", str(probe)]}]
    code, _ = run_harness(repo, tmp_path, steps)
    seen = json.loads(probe.read_text())
    assert seen["files"] == ["a.txt"] and not Path(seen["dir"]).exists()


# ---------------------------------------------------------------------------------------------------------------------
# Concurrent groups: ordering, exit codes, cancellation


def stamp_step(step_id: str, group: str, out: Path, sleep: float = 0.0, exit_code: int = 0) -> dict:
    """A step that records its wall-clock start and end (one file per step) so overlap can be proven."""
    code = ("import sys,time,json; t0=time.time(); time.sleep(%s); "
            "open(sys.argv[1],'w').write(json.dumps([t0,time.time()])); sys.exit(%d)" % (sleep, exit_code))
    return {"id": step_id, "group": group, "cmd": ["{python}", "-c", code, str(out / step_id)]}


def spans(out: Path, *ids: str) -> list:
    return [json.loads((out / i).read_text()) for i in ids]


def two_groups(out: Path, *, a_exit: int = 0, b_exit: int = 0, a_sleep: float = 0.8, b_sleep: float = 0.8) -> list:
    out.mkdir(exist_ok=True)
    return PRE + [stamp_step("a1", "alpha", out, a_sleep, a_exit), stamp_step("a2", "alpha", out, 0.0),
                  stamp_step("b1", "beta", out, b_sleep, b_exit), stamp_step("b2", "beta", out, 0.0),
                  {"id": "after", "cmd": ["{python}", "-c", "pass"]}]


def test_groups_overlap_and_the_report_keeps_manifest_order(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    out = tmp_path / "stamps"
    code, report = run_harness(repo, tmp_path, two_groups(out, a_sleep=1.2, b_sleep=0.3), "--jobs", "2")
    assert code == 0
    assert [c["id"] for c in report["checks"]] == ["preconditions", "a1", "a2", "b1", "b2", "after"]
    (a1, b1) = spans(out, "a1", "b1")
    assert b1[0] < a1[1] and a1[0] < b1[1], "alpha and beta did not run at the same time"
    (a2,), (b2,) = spans(out, "a2"), spans(out, "b2")
    assert a2[0] >= a1[1] and b2[0] >= b1[1], "steps inside a group stay in order"
    assert {c["id"]: c.get("group") for c in report["checks"]}["a2"] == "alpha"
    assert report["parallel"]["concurrent"] is True and set(report["parallel"]["groups"]) >= {"alpha", "beta"}
    logs = tmp_path / "out" / "harness-report-logs"
    assert "a1" in (logs / "group-alpha.log").read_text() and "b2" in (logs / "group-beta.log").read_text()


def test_jobs_one_is_the_plain_manifest_order_without_overlap(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    out = tmp_path / "stamps"
    code, report = run_harness(repo, tmp_path, two_groups(out, a_sleep=0.3, b_sleep=0.3), "--jobs", "1")
    assert code == 0 and report["parallel"]["concurrent"] is False
    (a1, a2, b1) = spans(out, "a1", "a2", "b1")
    assert a1[1] <= a2[0] <= a2[1] <= b1[0]


def fake_held(ctx, step):
    return lh.Out("held", "held by the fake")


def test_exit_code_is_the_first_stopping_step_in_manifest_order_not_the_first_to_finish(repo, tmp_path, monkeypatch):
    monkeypatch.setitem(lh.BUILTINS, "fake_held", fake_held)
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    out = tmp_path / "stamps"
    out.mkdir()
    # alpha fails late (10), beta is held early (20): alpha is first in the manifest, so the run is 10
    slow_fail = stamp_step("a1", "alpha", out, 0.8, 3)
    quick_held = {"id": "b1", "group": "beta", "builtin": "fake_held"}
    code, report = run_harness(repo, tmp_path, PRE + [slow_fail, quick_held], "--jobs", "2", "--keep-going")
    assert code == 10 and statuses(report)["b1"] == "held"
    code, report = run_harness(repo, tmp_path, PRE + [quick_held, slow_fail], "--jobs", "2", "--keep-going")
    assert code == 20 and statuses(report)["a1"] == "fail"


def test_a_failure_cancels_the_other_group_unless_keep_going(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    out = tmp_path / "stamps"
    steps = two_groups(out, a_exit=3, a_sleep=0.3, b_sleep=30)
    started = time.time()
    code, report = run_harness(repo, tmp_path, steps, "--jobs", "2")
    assert time.time() - started < 20, "the 30 s step of the other group was not cancelled"
    got = statuses(report)
    assert code == 10 and got["a1"] == "fail" and got["b1"] == "cancelled"
    assert got["a2"] == "skipped" and got["b2"] == "skipped" and got["after"] == "skipped"
    out2 = tmp_path / "stamps2"
    code, report = run_harness(repo, tmp_path, two_groups(out2, a_exit=3, a_sleep=0.3, b_sleep=0.6), "--jobs", "2",
                               "--keep-going")
    got = statuses(report)
    assert code == 10 and got["b1"] == "pass" and got["b2"] == "pass" and got["a2"] == "pass" and got["after"] == "pass"


def test_sigterm_kills_running_commands_and_exits_50_with_a_report(repo, tmp_path):
    write(repo, "app/a.txt", "two\n")
    commit(repo)
    pids = tmp_path / "pids"
    pids.mkdir()
    sleeper = ("import os,sys,time; open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(120)")
    steps = PRE + [{"id": "s1", "group": "alpha", "cmd": ["{python}", "-c", sleeper, str(pids / "s1")]},
                   {"id": "s2", "group": "beta", "cmd": ["{python}", "-c", sleeper, str(pids / "s2")]},
                   {"id": "after", "cmd": ["{python}", "-c", "pass"]}]
    manifest = manifest_with(tmp_path, steps)
    report_path = tmp_path / "out" / "harness-report.json"
    proc = subprocess.Popen([sys.executable, str(ROOT / "tools/delivery/lane_harness.py"), "--worktree", str(repo), "--base",
                             "main", "--manifest", str(manifest), "--report", str(report_path), "--jobs", "2"],
                            cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.time() + 30
    while time.time() < deadline and not ((pids / "s1").exists() and (pids / "s2").exists()
                                          and (pids / "s1").read_text() and (pids / "s2").read_text()):
        time.sleep(0.1)
    children = [int((pids / n).read_text()) for n in ("s1", "s2")]
    proc.terminate()
    assert proc.wait(timeout=30) == 50
    report = json.loads(report_path.read_text())
    got = statuses(report)
    assert report["result"] == "HARNESS_ERROR" and "terminated" in report["error"]
    assert got["s1"] == "cancelled" and got["s2"] == "cancelled" and got["after"] == "cancelled"
    for pid in children:
        time.sleep(0.2)
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)


def test_pytest_workers_follow_the_cpu_budget(repo, tmp_path, monkeypatch):
    class Args:
        jobs, pytest_workers = 3, 0
    ctx = lh.Context(worktree=repo, manifest={}, manifest_sha="", args=Args, meta={}, base_ref="main")
    monkeypatch.setattr(os, "cpu_count", lambda: 10)
    assert ctx.pytest_workers() == 5
    monkeypatch.setattr(os, "cpu_count", lambda: 2)
    assert ctx.pytest_workers() == 2
    Args.jobs = 1
    monkeypatch.setattr(os, "cpu_count", lambda: 10)
    assert ctx.pytest_workers() == 8
    Args.pytest_workers = 7
    assert ctx.pytest_workers() == 7
    assert ctx.expand(["-n", "{pytest_workers}"]) == ["-n", "7"]


def test_real_manifest_groups_are_contiguous_and_gradle_lives_in_one_group():
    steps = REAL_MANIFEST["steps"]
    grouped = [i for i, s in enumerate(steps) if s.get("group")]
    assert grouped == list(range(grouped[0], grouped[-1] + 1)), "grouped steps must form one block"
    by_group = {}
    for step in steps:
        if step.get("group"):
            by_group.setdefault(step["group"], []).append(step["id"])
    assert set(by_group) == {"tools", "java", "frontend"}
    assert by_group["java"][0] == "java_scoped" and "integration" in by_group["java"]
    assert by_group["frontend"][0] == "frontend_build"
    assert all(not (s.get("cmd") and "./gradlew" in s["cmd"]) or s["group"] == "java" for s in steps if s.get("group"))
