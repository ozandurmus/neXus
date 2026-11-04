"""Ship-speed changes (2026-11-03): docs-only fast path, merge gated on GitHub checks, preview/database concurrency,
reusable preview images and the module rollout timings/concurrency. Local doubles only; no cluster, no GitHub."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import threading

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
sys.path.insert(0, str(ROOT / "tools" / "e2e"))
import hosta_preview_e2e as preview  # noqa: E402
import hosta_restamp_image as restamp  # noqa: E402
import module_deploy as deploy  # noqa: E402
import standalone_orchestrate as sa  # noqa: E402

COMMIT = "a" * 40
TREE = "c" * 40
IMAGE = "registry.example.invalid/candidate@sha256:" + "b" * 64
OLD = "registry.example.invalid/image@sha256:" + "d" * 64
NEW = "registry.example.invalid/image@sha256:" + "e" * 64


def raw(*entries):
    """git diff --raw -z --no-renames output: (old mode, new mode, path)."""
    return "".join(":%s %s %s %s M\0%s\0" % (old, new, "1" * 40, "2" * 40, path) for old, new, path in entries)


def text(*paths):
    return raw(*[("100644", "100644", p) for p in paths])


# --------------------------------------------------------------------------------------------- path classification

@pytest.mark.parametrize("path", [
    "CURRENT_STATE.md", "AI_HANDOVER.md", "AGENTS.md", "README.md",
    "docs/design/SOMETHING.md", "docs/reference/x.txt", "docs/repo_inventory.csv", "docs/img/diagram.svg",
    "project/QUEUE.md", "project/backlog.json", "project/archive/build_history_2026.json",
    "roles/ENGINEER.md", ".github/copilot-instructions.md", ".github/prompts/relay-bootstrap.prompt.md",
])
def test_documentation_paths_are_docs_only(path):
    assert sa.is_docs_path(path)
    assert sa.classify_changed_paths(text(path)) == (True, [], [path])


@pytest.mark.parametrize("path", [
    "ui2/service/src/main/java/A.java", "ui2/README.md", "ui2/frontend/src/App.tsx",
    "tools/delivery/standalone_orchestrate.py", "tools/tests/test_x.py", "tools/README.md",
    "scripts/run.py", "deploy/ui2/50-service-deployment.yaml", "deploy/README.md", "utils/x.py",
    "tests/test_x.py", "config/po_write_scope.json", "history/old.md", "relay/NXS-1.json",
    "main.py", "pytest.ini", ".gitleaks.toml", ".github/workflows/validation.yml", ".githooks/pre-push",
    "docs/design/ui2_mockups/index.html", "docs/design/ui2_mockups/readme.md",
    "docs/tool.py", "docs/schema.sql", "docs/data.json", "docs/page.html", "docs/run.sh",
    "project/deploy_info.json", "project/helper.py", "requirements-dev.txt", "bin/nexus-workbench-observer",
    "docs/../ui2/x.md", "/etc/passwd", "docs//x.md", "docs\\x.md", "", "NOTES",
])
def test_everything_else_takes_the_full_path(path):
    assert not sa.is_docs_path(path)
    assert not sa.classify_changed_paths(text(path))[0]


def test_one_code_path_among_documents_disqualifies_the_whole_branch():
    ok, offenders, paths = sa.classify_changed_paths(text("docs/a.md", "ui2/service/A.java", "CURRENT_STATE.md"))
    assert not ok and offenders == ["ui2/service/A.java"] and len(paths) == 3


@pytest.mark.parametrize("modes", [("100644", "120000"), ("120000", "000000"), ("100644", "100755"),
                                   ("100755", "100644"), ("000000", "160000"), ("100644", "100664")])
def test_symlinks_submodules_and_executables_are_never_documentation(modes):
    assert not sa.classify_changed_paths(raw((modes[0], modes[1], "docs/looks-like-a-doc.md")))[0]


def test_deleting_and_adding_regular_documents_is_fine():
    assert sa.classify_changed_paths(raw(("100644", "000000", "docs/old.md"), ("000000", "100644", "docs/new.md")))[0]


def test_a_rename_out_of_a_code_directory_is_checked_on_both_sides():
    # git diff --no-renames reports the delete of the source and the add of the target as two records.
    ok, offenders, _ = sa.classify_changed_paths(raw(("100644", "000000", "ui2/service/Notes.md"),
                                                     ("000000", "100644", "docs/Notes.md")))
    assert not ok and offenders == ["ui2/service/Notes.md"]


@pytest.mark.parametrize("diff", ["", "\0", ":100644 100644 a b M\0", "garbage\0docs/a.md\0", ":100644 100644\0docs/a.md\0"])
def test_empty_or_unparseable_diff_is_not_docs_only(diff):
    assert not sa.classify_changed_paths(diff)[0]


def test_a_very_large_change_is_not_fast_pathed():
    many = text(*["docs/f%d.md" % i for i in range(sa.FASTPATH_MAX_FILES + 1)])
    assert not sa.classify_changed_paths(many)[0]
    assert sa.classify_changed_paths(text(*["docs/f%d.md" % i for i in range(sa.FASTPATH_MAX_FILES)]))[0]


def test_every_allowed_extension_prefix_is_documentation_text_never_a_script():
    allowed = {e for exts in sa.DOCS_TREE_EXTENSIONS.values() for e in exts} | set(sa.DOCS_ROOT_SUFFIXES)
    assert allowed == {".md", ".txt", ".csv", ".png", ".svg", ".json"}
    assert set(sa.DOCS_TREE_EXTENSIONS) == {"docs/", "project/", "roles/", ".github/"}


# --------------------------------------------------------------------------------------------------- check gating

def rollup(*items):
    return json.dumps(list(items))


def run_item(name, status="COMPLETED", conclusion="SUCCESS"):
    return {"__typename": "CheckRun", "name": name, "status": status, "conclusion": conclusion}


@pytest.mark.parametrize("items,expected", [
    ([run_item("validate")], "pass"),
    ([run_item("validate"), run_item("scope-blocked", conclusion="SKIPPED")], "pass"),
    ([run_item("validate"), run_item("x", conclusion="NEUTRAL")], "pass"),
    ([{"__typename": "StatusContext", "context": "ci", "state": "SUCCESS"}], "pass"),
    ([run_item("validate"), run_item("slow", status="IN_PROGRESS", conclusion="")], "pending"),
    ([run_item("validate"), run_item("slow", status="QUEUED", conclusion="")], "pending"),
    ([{"__typename": "StatusContext", "context": "ci", "state": "PENDING"}], "pending"),
    ([run_item("validate", conclusion="FAILURE")], "fail"),
    ([run_item("a"), run_item("b", conclusion="CANCELLED")], "fail"),
    ([run_item("a"), run_item("b", conclusion="TIMED_OUT")], "fail"),
    ([run_item("a"), run_item("b", conclusion="ACTION_REQUIRED")], "fail"),
    ([run_item("a", status="IN_PROGRESS", conclusion=""), run_item("b", conclusion="FAILURE")], "fail"),
    ([{"__typename": "StatusContext", "context": "ci", "state": "ERROR"}], "fail"),
    ([run_item("only", conclusion="SKIPPED")], "none"),
    ([], "none"),
    ([run_item("odd", conclusion="SOMETHING_NEW")], "pending"),
])
def test_check_verdict(items, expected):
    assert sa._check_verdict(items)[0] == expected


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def gh_returning(monkeypatch, *outputs):
    """subprocess.run double for `gh pr view`: successive stdout values (a Exception instance = non-zero exit)."""
    calls = []
    queue = list(outputs)

    def run(argv, **kwargs):
        calls.append(argv)
        value = queue.pop(0) if len(queue) > 1 else queue[0]
        if isinstance(value, Exception):
            return subprocess.CompletedProcess(argv, 1, "", "boom")
        return subprocess.CompletedProcess(argv, 0, value, "")

    monkeypatch.setattr(sa.subprocess, "run", run)
    return calls


def test_merge_waits_for_pending_checks_then_proceeds(monkeypatch, capsys):
    clock = Clock()
    calls = gh_returning(monkeypatch, rollup(run_item("validate", "IN_PROGRESS", "")),
                         rollup(run_item("validate", "IN_PROGRESS", "")), rollup(run_item("validate")))
    sa._wait_for_checks("https://example.invalid/pr/1", poll=15, sleep=clock.sleep, clock=clock)
    assert len(calls) == 3 and clock.now == 30
    assert calls[0][:4] == ["gh", "pr", "view", "https://example.invalid/pr/1"] and "statusCheckRollup" in calls[0]
    out = capsys.readouterr().out
    assert out.count('"checks": "waiting"') == 1 and '"checks": "pass"' in out


def test_a_failing_check_stops_the_ship_immediately(monkeypatch):
    clock = Clock()
    gh_returning(monkeypatch, rollup(run_item("validate", conclusion="FAILURE"), run_item("other", "IN_PROGRESS", "")))
    with pytest.raises(SystemExit, match="checks failed.*validate.*not merged"):
        sa._wait_for_checks("u", sleep=clock.sleep, clock=clock)
    assert clock.now == 0


def test_checks_that_never_finish_time_out_without_merging(monkeypatch):
    clock = Clock()
    gh_returning(monkeypatch, rollup(run_item("validate", "IN_PROGRESS", "")))
    with pytest.raises(SystemExit, match="still pending after 900 s"):
        sa._wait_for_checks("u", timeout=900, poll=15, sleep=clock.sleep, clock=clock)
    assert clock.now == 900


def test_no_check_appearing_at_all_is_a_failure_after_the_grace_period(monkeypatch):
    clock = Clock()
    gh_returning(monkeypatch, rollup())
    with pytest.raises(SystemExit, match="no pull request check appeared within 120 s"):
        sa._wait_for_checks("u", poll=15, empty_grace=120, sleep=clock.sleep, clock=clock)


def test_checks_appearing_late_are_still_waited_for(monkeypatch):
    clock = Clock()
    gh_returning(monkeypatch, rollup(), rollup(), rollup(run_item("validate")))
    sa._wait_for_checks("u", poll=15, empty_grace=120, sleep=clock.sleep, clock=clock)
    assert clock.now == 30


def test_only_skipped_checks_do_not_count_as_a_pass(monkeypatch):
    gh_returning(monkeypatch, rollup(run_item("a", conclusion="SKIPPED")))
    with pytest.raises(SystemExit, match="skipped"):
        sa._wait_for_checks("u", sleep=lambda s: None, clock=Clock())


def test_transient_gh_errors_are_tolerated_but_persistent_ones_stop_the_ship(monkeypatch):
    clock = Clock()
    gh_returning(monkeypatch, RuntimeError(), RuntimeError(), rollup(run_item("validate")))
    sa._wait_for_checks("u", poll=15, sleep=clock.sleep, clock=clock)
    gh_returning(monkeypatch, RuntimeError())
    with pytest.raises(SystemExit, match="could not read the pull request checks"):
        sa._wait_for_checks("u", poll=15, sleep=clock.sleep, clock=Clock())


@pytest.mark.parametrize("stdout", ["not json", "{}", '"text"'])
def test_garbage_from_gh_is_never_a_pass(monkeypatch, stdout):
    gh_returning(monkeypatch, stdout)
    with pytest.raises(SystemExit, match="could not read"):
        sa._wait_for_checks("u", poll=15, sleep=lambda s: None, clock=Clock())


# ------------------------------------------------------------------------------------------------ _ship ordering

def ship_doubles(monkeypatch, diff, events, checks=None):
    def git(*args, **kwargs):
        events.append(tuple(args))
        if args[0] == "status":
            return ""
        if args[0] == "rev-list":
            return "1"
        if args[0] == "rev-parse":
            return COMMIT
        return "synthetic"

    def run(args, **kwargs):
        events.append(tuple(args))
        output, code = "", 0
        if "repository_privacy_check.py" in " ".join(args):
            output = "Gate:                 PASS"
        elif args[:3] == ["gh", "pr", "create"]:
            output = "https://example.invalid/pr/1"
        elif args[:2] == ["git", "diff"]:
            output = diff
        elif args and args[0] == "gitleaks":
            output = ""
        elif "tools/e2e/hosta_e2e.sh" in args:
            output = "E2E: PASS"
        elif "tools/delivery/hosta_integration.sh" in args:
            output = "INTEGRATION: PASS"
        return subprocess.CompletedProcess(args, code, output, "")

    monkeypatch.setattr(sa, "_git", git)
    monkeypatch.setattr(sa.subprocess, "run", run)
    monkeypatch.setattr(sa, "_preview_e2e", lambda *a: events.append(("preview",)))
    monkeypatch.setattr(sa, "_deploy", lambda *a: events.append(("deploy",)))
    monkeypatch.setattr(sa, "_wait_for_checks", checks or (lambda url: events.append(("checks", url))))
    monkeypatch.setattr(sa.shutil, "which", lambda name: "/usr/bin/gitleaks")


def ship_args(**kw):
    base = dict(task=None, branch="feature/synthetic", title=None, notes=None, full=False,
                preview_e2e=False, no_preview_e2e=None)
    base.update(kw)
    return argparse.Namespace(**base)


def names(events):
    return [e[0] if e[0] != "gh" else "gh " + e[1] + " " + e[2] for e in events]


def test_docs_only_branch_skips_preview_build_and_deploy_but_keeps_every_gate(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, text("docs/design/X.md", "CURRENT_STATE.md", "project/QUEUE.md"), events)
    assert sa._ship(ship_args()) == 0
    out = capsys.readouterr().out
    fast = [json.loads(line) for line in out.splitlines() if line.startswith("{") and '"fast_path"' in line]
    assert fast[0] == {"fast_path": "docs_only", "files": 3, "privacy_gate": "pass", "gitleaks": "pass",
                       "preview": "skipped", "image_build": "skipped", "deploy": "skipped",
                       "not_deployed_until_next_code_deploy": []}
    assert fast[-1] == {"fast_path": "docs_only", "deployed": False, "pr": "https://example.invalid/pr/1"}
    seq = names(events)
    assert ("preview",) not in events and ("deploy",) not in events
    assert not any("hosta_e2e.sh" in " ".join(e) for e in events if e and e[0] == "bash")
    # privacy gate -> gitleaks -> push -> PR -> checks -> merge -> host mirror
    order = [next(i for i, e in enumerate(events) if pred(e)) for pred in (
        lambda e: "repository_privacy_check.py" in " ".join(e),
        lambda e: e[0] == "gitleaks",
        lambda e: e[0] == "push",
        lambda e: e[:3] == ("gh", "pr", "create"),
        lambda e: e[0] == "checks",
        lambda e: e[:3] == ("gh", "pr", "merge"),
        lambda e: e[:3] == ("push", "-q", "hosta"))]
    assert order == sorted(order), seq


def test_docs_only_with_baked_project_state_reports_that_it_is_not_deployed(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, text("project/roadmap.json", "docs/a.md"), events)
    assert sa._ship(ship_args()) == 0
    first = json.loads(next(l for l in capsys.readouterr().out.splitlines() if '"preview": "skipped"' in l))
    assert first["not_deployed_until_next_code_deploy"] == ["project/roadmap.json"]


@pytest.mark.parametrize("diff,flags", [
    (text("docs/a.md", "ui2/service/A.java"), {}),
    (text("docs/a.md"), {"full": True}),
    (text("docs/a.md"), {"no_fast_path": True}),
    ("", {}),
])
def test_anything_else_takes_the_full_path(monkeypatch, capsys, diff, flags):
    events = []
    ship_doubles(monkeypatch, diff, events)
    assert sa._ship(ship_args(**flags)) == 0
    assert ("preview",) in events and ("deploy",) in events
    assert '"fast_path": "docs_only"' not in capsys.readouterr().out
    order = names(events)
    assert order.index("preview") < order.index("push") < order.index("checks") < order.index("gh pr merge")


def test_gitleaks_finding_stops_a_docs_only_ship_before_anything_is_pushed(monkeypatch):
    events = []
    ship_doubles(monkeypatch, text("docs/a.md"), events)
    original = sa.subprocess.run

    def run(args, **kwargs):
        if args and args[0] == "gitleaks":
            events.append(("gitleaks",))
            return subprocess.CompletedProcess(args, 1, "", "")
        return original(args, **kwargs)

    monkeypatch.setattr(sa.subprocess, "run", run)
    with pytest.raises(SystemExit, match="gitleaks"):
        sa._ship(ship_args())
    assert not any(e[0] == "push" or e[:3] == ("gh", "pr", "create") for e in events)


def test_missing_gitleaks_is_reported_not_silent(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, text("docs/a.md"), events)
    monkeypatch.setattr(sa.shutil, "which", lambda name: None)
    assert sa._ship(ship_args()) == 0
    assert '"gitleaks": "unavailable"' in capsys.readouterr().out


def test_failing_checks_stop_the_ship_before_merge_and_before_deploy(monkeypatch):
    events = []

    def failing(url):
        raise SystemExit("pull request checks failed (" + url + "): validate; not merged")

    ship_doubles(monkeypatch, text("ui2/service/A.java"), events, checks=failing)
    with pytest.raises(SystemExit, match="checks failed"):
        sa._ship(ship_args())
    assert ("deploy",) not in events and not any(e[:3] == ("gh", "pr", "merge") for e in events)


def test_early_pr_is_opt_in_and_a_red_preview_closes_the_pull_request(monkeypatch):
    events = []
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)
    assert sa._ship(ship_args(early_pr=True)) == 0
    order = names(events)
    assert order.index("push") < order.index("gh pr create") < order.index("preview") < order.index("checks")
    events.clear()
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)

    def red(*a):
        events.append(("preview",))
        raise SystemExit("preview e2e failed; stopped before PR merge")

    monkeypatch.setattr(sa, "_preview_e2e", red)
    with pytest.raises(SystemExit, match="preview e2e failed"):
        sa._ship(ship_args(early_pr=True))
    assert any(e[:3] == ("gh", "pr", "close") for e in events)
    assert any(e[:5] == ("git", "push", "-q", "origin", "--delete") for e in events)
    assert ("deploy",) not in events and not any(e[:3] == ("gh", "pr", "merge") for e in events)


def test_cli_flags(monkeypatch):
    monkeypatch.setattr(sa, "cmd_ship", lambda args: (args.early_pr, args.no_fast_path))
    assert sa.main(["ship", "--branch", "feature/x"]) == (False, False)
    assert sa.main(["ship", "--branch", "feature/x", "--early-pr", "--no-fast-path"]) == (True, True)


# ------------------------------------------------------------------------- preview: concurrency + diagnostics

def test_run_concurrently_runs_branches_at_the_same_time_and_returns_in_order():
    barrier = threading.Barrier(2, timeout=5)

    def first():
        barrier.wait()
        return "build"

    def second():
        barrier.wait()
        return "database"

    assert preview.run_concurrently(first, second) == ["build", "database"]


def test_run_concurrently_waits_for_every_branch_then_raises_the_first_failure_in_order():
    finished = []
    release = threading.Event()

    def failing_early():
        raise RuntimeError("database failed")

    def slow_but_failing():
        release.wait(5)
        finished.append("build")
        raise ValueError("build failed")

    threading.Timer(0.05, release.set).start()
    with pytest.raises(ValueError, match="build failed"):          # argument order, not completion order
        preview.run_concurrently(slow_but_failing, failing_early)
    assert finished == ["build"]                                    # nothing is left running when it returns


def test_each_branch_keeps_its_own_phase_and_step():
    seen = {}
    gate = threading.Barrier(2, timeout=5)

    def branch(name):
        def run():
            with preview.timing("preview_" + name):
                preview._set_step("step of " + name)
                gate.wait()                              # both branches are inside their phase at the same time
                seen[name] = preview._where()
                error = preview.PreviewFailure("OTHER")
                seen[name + "_error"] = (error.phase, error.step)
        return run

    preview.run_concurrently(branch("build"), branch("database"))
    assert seen["build"] == ("preview_build", "step of build")
    assert seen["database"] == ("preview_database", "step of database")
    assert seen["build_error"] == seen["build"] and seen["database_error"] == seen["database"]
    assert preview._where() == (preview.PHASE, preview.STEP)     # the main thread's state was not touched


def test_run_starts_build_and_database_together(monkeypatch):
    meeting = threading.Barrier(2, timeout=5)
    started = []
    monkeypatch.setattr(preview, "worker_templates", lambda repo: [])
    monkeypatch.setattr(preview, "policies", lambda workers: [])
    monkeypatch.setattr(preview, "create", lambda ns, obj: None)
    monkeypatch.setattr(preview, "setup_worker_services", lambda workers: {})
    monkeypatch.setattr(preview, "setup_service", lambda *a: "192.0.2.20")
    monkeypatch.setattr(preview, "reset_preview_runtime", lambda: None)
    monkeypatch.setattr(preview, "assert_module_claims", lambda *a: None)
    monkeypatch.setattr(preview, "copy_secret", lambda *a: None)
    monkeypatch.setattr(preview, "wait_job", lambda *a, **kw: None)
    monkeypatch.setattr(preview, "e2e_manifest", lambda *a: {})
    monkeypatch.setattr(preview, "cleanup", lambda owner: None)
    monkeypatch.setattr(preview, "load", lambda *a: {"items": [{"kind": "Job"}]})
    monkeypatch.setattr(preview, "k", lambda *a, **kw: "")

    def build(*a):
        started.append("build")
        meeting.wait()                                   # only returns if the database branch is running too
        return [IMAGE, IMAGE]

    def database(*a):
        started.append("database")
        meeting.wait()
        return "192.0.2.10"

    monkeypatch.setattr(preview, "build_images", build)
    monkeypatch.setattr(preview, "setup_database", database)
    preview.run(ROOT, COMMIT)
    assert sorted(started) == ["build", "database"]


# ----------------------------------------------------------------------------------- reusable image records

def test_a_passing_preview_records_the_images_it_verified_under_the_tree(tmp_path, monkeypatch):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    service, e2e = "registry/nexus-ui2-service@sha256:" + "1" * 64, "registry/nexus-ui2-e2e@sha256:" + "2" * 64
    line = preview.record_reusable_images(TREE, COMMIT, [service, e2e])
    assert line == "PREVIEW IMAGES: tree=%s service=sha256:%s e2e=sha256:%s" % (TREE, "1" * 64, "2" * 64)
    record = tmp_path / ".local/state/nexus-preview/reuse" / (TREE + ".json")
    data = json.loads(record.read_text())
    assert data["tree"] == TREE and data["commit"] == COMMIT and data["service"] == "sha256:" + "1" * 64
    assert (record.stat().st_mode & 0o777) == 0o600 and (record.parent.stat().st_mode & 0o777) == 0o700
    assert not list(record.parent.glob("*.tmp"))


@pytest.mark.parametrize("tree,images", [
    (None, [IMAGE, IMAGE]), ("short", [IMAGE, IMAGE]), (TREE, [IMAGE]), (TREE, ["registry/x:tag", IMAGE]),
    (TREE, [IMAGE, "registry/x@sha256:abc"]),
])
def test_no_record_for_a_missing_tree_or_malformed_images(tmp_path, monkeypatch, tree, images):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    assert preview.record_reusable_images(tree, COMMIT, images) is None
    assert not (tmp_path / ".local/state/nexus-preview/reuse").exists()


def test_old_records_are_pruned(tmp_path, monkeypatch):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(preview, "REUSE_RECORD_LIMIT", 2)
    for index in range(4):
        preview.record_reusable_images("%040x" % (index + 1), COMMIT, [IMAGE, IMAGE])
    assert len(list((tmp_path / ".local/state/nexus-preview/reuse").glob("*.json"))) == 2


def test_the_wrapper_and_the_client_pass_and_surface_the_tree_only_when_supported(monkeypatch, capsys):
    captured = []
    wrapper_new = "#!/bin/bash\n# accepts --tree\n"
    out = ("TIMING preview_e2e 1.000\nPREVIEW IMAGES: tree=%s service=sha256:%s e2e=sha256:%s\nPREVIEW E2E: PASS\n"
           % (TREE, "1" * 64, "2" * 64))

    def git(*args, **kwargs):
        return TREE if args[0] == "rev-parse" else wrapper_new

    monkeypatch.setattr(sa, "_git", git)
    monkeypatch.setattr(sa.subprocess, "run", lambda argv, **kw: captured.append(argv) or
                        subprocess.CompletedProcess(argv, 0, out, ""))
    sa._preview_e2e(ROOT, COMMIT)
    assert captured[0][-2:] == ["--tree", TREE]
    assert "PREVIEW IMAGES: tree=" + TREE in capsys.readouterr().out
    captured.clear()
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: "#!/bin/bash\n# an older wrapper\n")
    sa._preview_e2e(ROOT, COMMIT)
    assert "--tree" not in captured[0]


def test_the_wrapper_script_accepts_an_optional_tree_and_forwards_it():
    source = (ROOT / "tools/e2e/hosta_preview_e2e.sh").read_text()
    assert '[ "$#" != 4 ] && [ "$#" != 6 ]' in source and "--tree" in source
    assert '${2:+--tree "$2"}' in source and "PREVIEW IMAGES: tree=" in source


# ------------------------------------------------------------------------------------------- restamp image tool

def write_record(directory, **override):
    record = dict(tree=TREE, commit=COMMIT, service="sha256:" + "1" * 64, e2e="sha256:" + "2" * 64, created=1000)
    record.update(override)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / (TREE + ".json")).write_text(json.dumps(record))


def test_record_lookup_requires_the_exact_tree_a_valid_digest_and_a_fresh_record(tmp_path):
    write_record(tmp_path)
    assert restamp.load_record(TREE, tmp_path, now=lambda: 1000 + 60)["service"] == "sha256:" + "1" * 64
    assert restamp.load_record(TREE, tmp_path, now=lambda: 1000 + restamp.MAX_AGE_S + 1) is None   # stale
    assert restamp.load_record(TREE, tmp_path, now=lambda: 500) is None                            # from the future
    assert restamp.load_record("d" * 40, tmp_path, now=lambda: 1001) is None                       # other tree
    for bad in (dict(tree="d" * 40), dict(service="latest"), dict(service="sha256:abc"), dict(created="1000")):
        write_record(tmp_path, **bad)
        assert restamp.load_record(TREE, tmp_path, now=lambda: 1001) is None, bad
    (tmp_path / (TREE + ".json")).write_text("{not json")
    assert restamp.load_record(TREE, tmp_path, now=lambda: 1001) is None
    with pytest.raises(ValueError):
        restamp.load_record("../../etc/passwd", tmp_path)


def test_restamp_without_a_record_builds_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr(restamp.builds, "kubectl", lambda *a, **kw: pytest.fail("no cluster call without a record"))
    assert restamp.build(COMMIT, TREE, tmp_path, now=lambda: 1001) is None


def test_builder_arguments_pin_the_recorded_digest_and_the_deploy_tag():
    base = restamp.REPOSITORY + "@sha256:" + "1" * 64
    args = restamp.builder_args(["--context=dir:///workspace", "--dockerfile=/workspace/ui2/Containerfile",
                                 "--destination=x", "--cache=true", "--insecure", "--digest-file=/x"], COMMIT, base)
    assert "--dockerfile=/workspace/ui2/Containerfile.restamp" in args
    assert "--dockerfile=/workspace/ui2/Containerfile" not in args and "--cache=true" not in args
    assert "--destination=" + restamp.REPOSITORY + ":" + COMMIT[:12] in args
    assert "--build-arg=SERVICE_IMAGE=" + base in args and "--insecure" in args
    assert args[-1] == "--digest-file=/dev/termination-log"


def restamp_cluster(monkeypatch, tmp_path, outcome):
    write_record(tmp_path)
    jobs = {}
    base = restamp.REPOSITORY + "@sha256:" + "1" * 64

    def kubectl(*args, **kwargs):
        if args[:2] == ("get", "configmap"):
            return json.dumps({"data": {"commit": COMMIT}})
        if args[0] == "create" and "--dry-run=client" in args:
            return json.dumps({"metadata": {}, "spec": {"template": {"spec": {"containers": [
                {"name": "builder", "args": ["--context=dir:///workspace"]}]}}}})
        if args[:2] == ("delete", "job"):
            return ""
        if args[0] == "create":
            job = json.loads(kwargs["input"])
            job["metadata"]["uid"] = "uid-1"
            job["metadata"]["creationTimestamp"] = restamp.builds.datetime.now(restamp.builds.timezone.utc).isoformat()
            job["status"] = {"conditions": [{"type": outcome, "status": "True"}]}
            jobs["job"] = job
            return ""
        if args[:2] == ("get", "job"):
            return json.dumps(jobs["job"])
        if args[:2] == ("get", "pods"):
            return json.dumps({"items": [{"metadata": {"ownerReferences": [{"uid": "uid-1"}]}, "status": {
                "containerStatuses": [{"name": "builder", "state": {"terminated": {
                    "exitCode": 0, "message": "sha256:" + "9" * 64}}}]}}]})
        pytest.fail(str(args))

    monkeypatch.setattr(restamp.builds, "kubectl", kubectl)
    return jobs, base


def test_restamp_returns_the_new_digest_of_the_job_it_started(monkeypatch, tmp_path):
    jobs, base = restamp_cluster(monkeypatch, tmp_path, "Complete")
    assert restamp.build(COMMIT, TREE, tmp_path, now=lambda: 1001, sleep=lambda s: None) == "sha256:" + "9" * 64
    annotations = jobs["job"]["metadata"]["annotations"]
    assert annotations == {"nexus/commit": COMMIT, "nexus/service-image": base, "nexus/tree": TREE}
    assert jobs["job"]["spec"]["backoffLimit"] == 0


def test_a_failed_restamp_job_means_a_normal_build_follows(monkeypatch, tmp_path):
    restamp_cluster(monkeypatch, tmp_path, "Failed")
    assert restamp.build(COMMIT, TREE, tmp_path, now=lambda: 1001, sleep=lambda s: None) is None


def test_the_restamp_containerfile_only_adds_the_deploy_info_layer_on_a_digest_pinned_base():
    source = (ROOT / "ui2/Containerfile.restamp").read_text()
    lines = [l for l in source.splitlines() if l and not l.startswith("#")]
    assert lines[0] == "ARG SERVICE_IMAGE" and lines[1] == "FROM ${SERVICE_IMAGE}"
    assert "COPY project/deploy_info.json /app/project/deploy_info.json" in lines
    assert sum(l.startswith("COPY") for l in lines) == 1 and lines[-1] == "USER 185"
    assert "nexus-ui2-service@sha256:" in source
    # The main Containerfile bakes exactly this file and ends as user 185.
    main = (ROOT / "ui2/Containerfile").read_text()
    assert "project/deploy_info.json /app/project/" in main and main.rstrip().endswith("USER 185")


def test_run_build_reuses_only_through_the_restamp_tool_and_falls_back_to_the_full_build():
    source = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    reuse = source.index('hosta_restamp_image.py" build --commit "$COMMIT_SHA"')
    assert source.index("hosta_e2e_image.py\" start") < reuse < source.index("kubectl apply -f ~/build-job-proxy.yaml")
    assert '|| REUSED_IMAGE=""' in source and 'NEXUS_REUSE_PREVIEW_IMAGE:-1' in source
    fallback = source[source.index('if [ -n "$REUSED_IMAGE" ]'):source.index('echo "Digest is $IMAGE_DIGEST"', source.index('else\n  kubectl apply'))]
    assert "kubectl apply -f ~/build-job-proxy.yaml" in fallback and "image-digest.txt" in fallback
    # The digest that is scanned and rolled out is IMAGE_DIGEST in both paths.
    assert source.index("IMAGE_DIGEST=") < source.index("security_host.py\" gate")


# --------------------------------------------------------------------------------------- module rollout speed

def test_concurrent_steps_group_only_barrier_free_neighbours():
    steps = deploy.rollout_steps(["service", "worker", "configuration", "compliance", "policy", "packet-analysis"])
    assert steps == [["service"], ["worker"], ["configuration", "compliance"], ["policy"], ["packet-analysis"]]
    assert deploy.rollout_steps(["configuration", "worker", "compliance"]) == [["configuration"], ["worker"], ["compliance"]]
    assert deploy.rollout_steps(["configuration", "compliance"], concurrent=False) == [["configuration"], ["compliance"]]
    for dangerous in ("service", "worker", "policy", "packet-analysis"):
        assert dangerous not in deploy.CONCURRENT_SAFE
    assert all(target not in deploy.ROLES for target in deploy.CONCURRENT_SAFE)       # no drain barrier exists for them


def test_drain_barrier_conditions_and_polling_are_unchanged_except_the_interval(monkeypatch):
    conditions = dict(deploy.barrier_conditions("worker", 3))
    assert set(conditions) == {"drain_ack", "owner_live", "busy_jobs", "admissions", "task_leases",
                               "quarantined_task_leases"}
    assert deploy.DRAIN_POLL_S == 2 and deploy.OLD_POD_POLL_S == 2
    sleeps, now = [], [0]
    monkeypatch.setattr(deploy, "barrier", lambda *a: False)

    def sleep(seconds):
        sleeps.append(seconds)
        now[0] += seconds

    with pytest.raises(deploy.Blocked, match="old digest"):
        deploy.wait_drain("worker", 1, 7, sleep=sleep, clock=lambda: now[0])
    assert sleeps == [2, 2, 2, 1]


def module_cluster(monkeypatch, order, delay=None, fail=None):
    lock = threading.Lock()

    def log(*event):
        with lock:
            order.append(event)

    monkeypatch.setattr(deploy, "run", lambda *a, **kw: json.dumps({"items": []}) if "pods" in a else OLD)
    monkeypatch.setattr(deploy, "query", lambda sql: "t")
    monkeypatch.setattr(deploy, "check_synced_manifest", lambda *a: None)
    monkeypatch.setattr(deploy, "live_replicas", lambda target: 1)

    def replace_image(target, image, sync_manifest=False):
        log("image", target)
        if delay is not None and target in deploy.CONCURRENT_SAFE:
            delay.wait(5)
        if fail == target:
            raise deploy.Blocked("synthetic " + target)

    monkeypatch.setattr(deploy, "replace_image", replace_image)
    monkeypatch.setattr(deploy, "module_e2e", lambda commit=None: log("e2e"))
    return log


def test_replace_prints_phase_timings_and_can_defer_its_e2e(monkeypatch, capsys):
    order = []
    module_cluster(monkeypatch, order)
    result = deploy.replace("compliance", NEW, "snap", defer_e2e=True)
    assert result["module"] == "compliance" and result["e2e"] == "PASS" and ("e2e",) not in order
    out = capsys.readouterr().out
    assert "TIMING module_compliance_replace " in out and "TIMING module_compliance_rollout " in out
    assert "TIMING module_compliance_e2e" not in out and '"module"' not in out        # the caller reports it
    deploy.replace("compliance", NEW, "snap")
    out = capsys.readouterr().out
    assert "TIMING module_compliance_e2e " in out and '"module": "compliance"' in out


def test_worker_timings_include_the_drain_wait(monkeypatch, capsys):
    order = []
    module_cluster(monkeypatch, order)
    monkeypatch.setattr(deploy, "request_drain", lambda *a: 4)
    monkeypatch.setattr(deploy, "wait_drain", lambda *a: None)
    monkeypatch.setattr(deploy, "clear_drain", lambda *a: None)
    monkeypatch.setattr(deploy, "wait_heartbeat", lambda target: None)
    deploy.replace("worker", NEW, "snap")
    out = capsys.readouterr().out
    for name in ("drain", "replace", "rollout", "e2e"):
        assert "TIMING module_worker_%s " % name in out


def run_main_steps(monkeypatch, tmp_path, targets, capsys):
    import release_snapshot as snapshot
    monkeypatch.setattr(deploy, "prepare_policy_public_trust", lambda: None)
    monkeypatch.setattr(snapshot, "load_snapshot", lambda directory: {"runtime": {"compatible_runtime": True}, "images": {"ui2": []}})
    monkeypatch.setattr(sys, "argv", ["module_deploy.py", "--snapshot", str(tmp_path / "snap"), "--targets", targets,
                                      "--image", NEW, "--packet-analysis-image", NEW.replace("e" * 64, "f" * 64)])
    try:
        deploy.main()
        code = 0
    except SystemExit as stop:
        code = stop.code
    return code, capsys.readouterr().out


def test_configuration_and_compliance_roll_at_the_same_time_with_one_shared_e2e(monkeypatch, tmp_path, capsys):
    order = []
    meeting = threading.Barrier(2, timeout=5)           # passes only if both members are inside replace_image at once
    module_cluster(monkeypatch, order, delay=meeting)
    code, out = run_main_steps(monkeypatch, tmp_path, "service configuration compliance", capsys)
    assert code == 0
    assert [e for e in order if e[0] == "image"][0] == ("image", "service")
    assert sorted(e[1] for e in order if e[0] == "image") == ["compliance", "configuration", "service"]
    assert [e for e in order].count(("e2e",)) == 2       # one for the service, ONE for the group (was one per module)
    assert order[-1] == ("e2e",) and order.index(("e2e",)) < min(i for i, e in enumerate(order) if e[:2] == ("image", "configuration"))
    reported = [json.loads(l)["module"] for l in out.splitlines() if l.startswith('{"module"')]
    assert reported == ["service", "configuration", "compliance"]
    assert "TIMING module_group_configuration_compliance_e2e " in out


def test_a_failing_group_member_blocks_the_shared_e2e_and_reports_the_failure(monkeypatch, tmp_path, capsys):
    order = []
    module_cluster(monkeypatch, order, fail="compliance")
    code, out = run_main_steps(monkeypatch, tmp_path, "configuration compliance policy", capsys)
    assert code == 5
    assert ("e2e",) not in order
    assert '"module": "compliance", "status": "BLOCKED"' in out and "synthetic compliance" in out
    assert not any(e[:2] == ("image", "policy") for e in order)      # a hard failure stops the release as before


def test_barrier_modules_are_never_started_by_a_group(monkeypatch, tmp_path, capsys):
    order = []
    module_cluster(monkeypatch, order)
    monkeypatch.setattr(deploy, "query", lambda sql: "f" if "fallback_enabled" in sql else "t")
    monkeypatch.setattr(deploy, "ensure_policy", lambda image: OLD)
    monkeypatch.setattr(deploy, "request_drain", lambda *a: 1)
    monkeypatch.setattr(deploy, "wait_drain", lambda *a: order.append(("barrier",)))
    monkeypatch.setattr(deploy, "clear_drain", lambda *a: None)
    monkeypatch.setattr(deploy, "wait_heartbeat", lambda target: None)
    monkeypatch.setattr(deploy, "handover_policy", lambda *a: None)
    monkeypatch.setattr(deploy, "ensure_packet_analysis", lambda image, check=True: OLD)
    code, out = run_main_steps(monkeypatch, tmp_path, "worker configuration compliance policy packet-analysis", capsys)
    assert code == 0, out
    sequence = [e for e in order if e[0] in ("barrier", "image")]
    # worker and policy and packet-analysis each wait for their own drain barrier BEFORE their image is replaced
    for target in ("worker", "policy", "packet-analysis"):
        position = sequence.index(("image", target))
        assert sequence[position - 1] == ("barrier",)


def test_hosta_deploy_notices_the_finished_build_within_five_seconds():
    source = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    assert "for _ in 1 2 3; do sleep 5; grep -q '^Done\\.' /tmp/nexus_build.log && break; done" in source


@pytest.mark.parametrize("reason,ok", [("GitHub Actions outage", True), ("", False), (" ", False), ("two\nlines", False), ("x" * 201, False)])
def test_skip_checks_needs_a_recorded_reason(monkeypatch, capsys, reason, ok):
    events = []
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)
    if ok:
        assert sa._ship(ship_args(skip_checks=reason)) == 0
        assert "EMERGENCY: PULL REQUEST CHECKS NOT AWAITED: " + reason in capsys.readouterr().out
        assert not any(e[0] == "checks" for e in events)
    else:
        with pytest.raises(SystemExit, match="--skip-checks requires"):
            sa._ship(ship_args(skip_checks=reason))
        assert not events


def test_fast_merge_does_not_wait_for_the_regression_suites(monkeypatch, capsys):
    # PO decision 2026-11-04: full/targeted regression keep running after the merge; validate still gates it.
    monkeypatch.delenv("NEXUS_WAIT_FULL_REGRESSION", raising=False)
    clock = Clock()
    gh_returning(monkeypatch, rollup(run_item("validate"), run_item("full-regression", "IN_PROGRESS", ""),
                                     run_item("targeted-regression", "IN_PROGRESS", "")))
    sa._wait_for_checks("u", sleep=clock.sleep, clock=clock)
    assert clock.now == 0 and '"checks": "pass"' in capsys.readouterr().out


def test_fast_merge_still_stops_on_a_red_validate_or_scope_block(monkeypatch):
    monkeypatch.delenv("NEXUS_WAIT_FULL_REGRESSION", raising=False)
    clock = Clock()
    gh_returning(monkeypatch, rollup(run_item("validate"), run_item("scope-blocked", conclusion="FAILURE"),
                                     run_item("full-regression", "IN_PROGRESS", "")))
    with pytest.raises(SystemExit, match="checks failed.*scope-blocked"):
        sa._wait_for_checks("u", sleep=clock.sleep, clock=clock)


def test_waiting_for_full_regression_can_be_restored(monkeypatch):
    monkeypatch.setenv("NEXUS_WAIT_FULL_REGRESSION", "1")
    clock = Clock()
    gh_returning(monkeypatch, rollup(run_item("validate"), run_item("full-regression", conclusion="FAILURE")))
    with pytest.raises(SystemExit, match="full-regression"):
        sa._wait_for_checks("u", sleep=clock.sleep, clock=clock)
