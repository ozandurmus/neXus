"""Frontend fast path of the ship (2026-11-13): path classification, the deployed-module guard, the steps the ship
takes and the deploy command it builds. Local doubles only; no cluster, no GitHub, no host."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import standalone_orchestrate as sa  # noqa: E402

COMMIT = "a" * 40
OLD_COMMIT = "1" * 40
DIGEST = "sha256:" + "d" * 64
DIGEST_2 = "sha256:" + "e" * 64


def raw(*entries):
    return "".join(":%s %s %s %s M\0%s\0" % (old, new, "1" * 40, "2" * 40, path) for old, new, path in entries)


def text(*paths):
    return raw(*[("100644", "100644", p) for p in paths])


# --------------------------------------------------------------------------------------------- path classification

@pytest.mark.parametrize("path", [
    "ui2/frontend/src/App.tsx", "ui2/frontend/src/screens/InventoryScreen.tsx", "ui2/frontend/src/theme/tokens.css",
    "ui2/frontend/src/assets/logo.svg", "ui2/frontend/src/jobs/labels.ts", "ui2/frontend/tests/App.test.tsx",
    "ui2/frontend/tests/fixtures/policy-live/x.json", "ui2/frontend/e2e/every-tab.spec.ts",
    "ui2/frontend/e2e/settings.d.mts", "ui2/frontend/e2e/login.mjs",
])
def test_frontend_source_tests_and_e2e_qualify(path):
    assert sa.is_frontend_path(path)
    verdict = sa.classify_change(text(path))
    assert verdict["kind"] == "frontend" and verdict["frontend_files"] == 1 and verdict["offenders"] == []


def test_frontend_plus_documentation_is_still_frontend():
    verdict = sa.classify_change(text("ui2/frontend/src/App.tsx", "docs/design/X.md", "CURRENT_STATE.md",
                                      "ui2/frontend/tests/App.test.tsx"))
    assert verdict["kind"] == "frontend" and verdict["frontend_files"] == 2 and len(verdict["paths"]) == 4
    assert "2 frontend files" in verdict["why"]


def test_a_frontend_deletion_and_addition_qualify():
    assert sa.classify_change(raw(("100644", "000000", "ui2/frontend/src/Old.tsx"),
                                  ("000000", "100644", "ui2/frontend/src/New.tsx")))["kind"] == "frontend"


def test_documentation_only_stays_docs_only_and_agrees_with_the_docs_classifier():
    paths = ["docs/a.md", "CURRENT_STATE.md", "project/QUEUE.md"]
    assert sa.classify_change(text(*paths))["kind"] == "docs_only"
    assert sa.classify_changed_paths(text(*paths))[0]


@pytest.mark.parametrize("path,cls", [
    ("ui2/frontend/package.json", "frontend_build_config"),
    ("ui2/frontend/package-lock.json", "frontend_build_config"),
    ("ui2/frontend/vite.config.ts", "frontend_build_config"),
    ("ui2/frontend/tsconfig.json", "frontend_build_config"),
    ("ui2/frontend/index.html", "frontend_build_config"),
    ("ui2/frontend/Dockerfile.e2e", "frontend_build_config"),
    ("ui2/frontend/playwright.config.ts", "frontend_build_config"),
    ("ui2/frontend/playwright.policy-local.config.ts", "frontend_build_config"),
    ("ui2/frontend/.dockerignore", "frontend_build_config"),
    ("ui2/frontend/public/favicon.svg", "frontend_build_config"),
    ("ui2/frontend/FIDELITY_NOTES.md", "frontend_build_config"),
    ("ui2/frontend/browser-tests/x.mjs", "frontend_build_config"),
    ("ui2/frontend/src/build.sh", "frontend_build_config"),          # wrong extension inside a qualifying tree
    ("ui2/frontend/src/vendor/node_modules/x.ts", "frontend_build_config"),
    ("ui2/frontend/src/dist/x.ts", "frontend_build_config"),
    ("ui2/frontend/src/../../service/A.java", "frontend_build_config"),   # traversal never qualifies
])
def test_build_configuration_and_lookalikes_take_the_full_path(path, cls):
    assert not sa.is_frontend_path(path)
    verdict = sa.classify_change(text("ui2/frontend/src/App.tsx", path))
    assert verdict["kind"] == "full" and verdict["offenders"] == [path] and verdict["classes"] == {cls: 1}
    assert path in verdict["why"]


@pytest.mark.parametrize("path", [
    "ui2/service/src/main/java/A.java", "ui2/worker/src/main/java/B.java", "ui2/Containerfile",
    "ui2/persistence/src/main/resources/db/migration/V999__x.sql", "ui2/build.gradle.kts", "ui2/README.md",
    "deploy/ui2/50-service-deployment.yaml", "deploy/ui2-image-build/run_build.sh",
    "tools/delivery/standalone_orchestrate.py", "tools/tests/test_x.py", "scripts/run.py", "utils/x.py",
    ".github/workflows/validation.yml",
])
def test_backend_paths_take_the_full_path(path):
    verdict = sa.classify_change(text("ui2/frontend/src/App.tsx", path))
    assert verdict["kind"] == "full" and verdict["classes"] == {"backend": 1}
    assert verdict["why"].startswith("backend change")


def test_mockups_and_other_paths_take_the_full_path():
    # the image build copies docs/design/ui2_mockups into the frontend stage
    verdict = sa.classify_change(text("ui2/frontend/src/App.tsx", "docs/design/ui2_mockups/index.html", "Makefile"))
    assert verdict["kind"] == "full" and verdict["classes"] == {"other": 2}


@pytest.mark.parametrize("modes", [("100644", "120000"), ("120000", "000000"), ("100644", "100755"),
                                   ("000000", "160000")])
def test_symlinks_executables_and_submodules_are_never_frontend(modes):
    verdict = sa.classify_change(raw((modes[0], modes[1], "ui2/frontend/src/App.tsx")))
    assert verdict["kind"] == "full" and verdict["classes"] == {"irregular_file": 1}


@pytest.mark.parametrize("diff", ["", "\0", ":100644 100644 a b M\0", "garbage\0ui2/frontend/src/a.ts\0"])
def test_empty_or_unparseable_diff_takes_the_full_path(diff):
    assert sa.classify_change(diff)["kind"] == "full"


def test_a_very_large_change_takes_the_full_path():
    many = text(*["ui2/frontend/src/f%d.ts" % i for i in range(sa.FASTPATH_MAX_FILES + 1)])
    assert sa.classify_change(many)["kind"] == "full"
    assert sa.classify_change(text(*["ui2/frontend/src/f%d.ts" % i for i in range(sa.FASTPATH_MAX_FILES)]))["kind"] == "frontend"


def test_the_qualifying_set_is_closed():
    assert sa.FRONTEND_TREE_PREFIXES == ("ui2/frontend/src/", "ui2/frontend/tests/", "ui2/frontend/e2e/")
    assert not {".sh", ".py", ".java", ".yaml", ".yml", ".html", ".sql", ".kts"} & set(sa.FRONTEND_EXTENSIONS)
    assert sa.FRONTEND_DEPLOY_TARGETS == ("service",)
    assert set(sa.OTHER_MODULES) | set(sa.FRONTEND_DEPLOY_TARGETS) == {
        "service", "worker", "configuration", "compliance", "policy", "packet-analysis"}


# ----------------------------------------------------------------------------------- the deployed-module guard

def listing(**modules):
    """module=(digest, commit) -> the probe's output lines."""
    rows = {"worker": (DIGEST, OLD_COMMIT), "configuration": (DIGEST, OLD_COMMIT), "compliance": (DIGEST, OLD_COMMIT),
            "policy": (DIGEST, OLD_COMMIT), "packet-analysis": (DIGEST_2, OLD_COMMIT)}
    rows.update({k.replace("_", "-"): v for k, v in modules.items()})
    return "\n".join("%s %s %s" % (m, d, c) for m, (d, c) in rows.items()) + "\n"


def frontend_since(commit):
    return sa.classify_change(text("ui2/frontend/src/App.tsx", "docs/a.md"))


def test_current_modules_allow_the_fast_path():
    ok, detail = sa.evaluate_deployed_modules(listing(), frontend_since)
    assert ok and "5 other modules" in detail and "1 commit(s)" in detail


def test_a_module_behind_by_backend_changes_takes_the_full_path():
    # e.g. an earlier merge whose deploy stopped after the merge: main carries Java the worker never received
    ok, detail = sa.evaluate_deployed_modules(
        listing(), lambda c: sa.classify_change(text("ui2/frontend/src/App.tsx", "ui2/worker/src/main/java/W.java")))
    assert not ok and "ui2/worker/src/main/java/W.java" in detail


def test_each_distinct_deployed_commit_is_checked():
    seen = []
    other = "2" * 40

    def since(commit):
        seen.append(commit)
        return sa.classify_change(text("ui2/frontend/src/App.tsx")) if commit == OLD_COMMIT else \
            sa.classify_change(text("ui2/service/src/main/java/S.java"))

    ok, detail = sa.evaluate_deployed_modules(listing(worker=("sha256:" + "f" * 64, other)), since)
    assert not ok and sorted(seen) == [OLD_COMMIT, other][:len(seen)] and other[:12] in detail


def test_a_scaled_to_zero_module_borrows_the_commit_of_its_digest():
    ok, _ = sa.evaluate_deployed_modules(listing(policy=(DIGEST, "-")), frontend_since)
    assert ok


@pytest.mark.parametrize("override", [
    {"worker": ("-", "-")},                                   # deployment missing
    {"packet_analysis": (DIGEST_2, "-")},                     # alone on its digest, no running pod
])
def test_an_unreadable_or_unknown_module_takes_the_full_path(override):
    assert not sa.evaluate_deployed_modules(listing(**override), frontend_since)[0]


def test_one_digest_reporting_two_commits_takes_the_full_path():
    ok, detail = sa.evaluate_deployed_modules(listing(policy=(DIGEST, "3" * 40)), frontend_since)
    assert not ok and "two commits" in detail


def test_a_commit_missing_from_the_local_history_takes_the_full_path():
    ok, detail = sa.evaluate_deployed_modules(listing(), lambda c: None)
    assert not ok and "not in the local history" in detail


@pytest.mark.parametrize("text_", ["", "garbage\n", "worker sha256:xyz abc\n"])
def test_an_empty_or_malformed_probe_takes_the_full_path(text_):
    assert not sa.evaluate_deployed_modules(text_, frontend_since)[0]


def test_a_missing_module_line_takes_the_full_path():
    lines = [l for l in listing().splitlines() if not l.startswith("compliance")]
    assert not sa.evaluate_deployed_modules("\n".join(lines), frontend_since)[0]


def test_the_host_probe_failing_takes_the_full_path(monkeypatch, tmp_path):
    monkeypatch.setenv("NEXUS_HOST", "user@host.example.invalid")
    monkeypatch.setattr(sa.subprocess, "run", lambda *a, **k: subprocess.CompletedProcess(a, 255, "", "refused"))
    ok, detail = sa._deployed_state_check("feature/x")
    assert not ok and "probe failed" in detail


@pytest.mark.parametrize("since_diff,expected", [
    ("", True),                                                   # the modules already run exactly this tree
    (text("ui2/frontend/src/App.tsx"), True),
    (text("ui2/worker/src/main/java/W.java"), False),
])
def test_the_probe_and_the_since_diff_are_wired_together(monkeypatch, since_diff, expected):
    monkeypatch.setenv("NEXUS_HOST", "user@host.example.invalid")
    seen = []

    def run(args, **kwargs):
        seen.append(args)
        if args[0] == "ssh":
            assert kwargs["input"] == sa.DEPLOYED_PROBE and "BatchMode=yes" in args
            return subprocess.CompletedProcess(args, 0, "Authorized access only\n" + listing(), "")
        assert args[:5] == ["git", "diff", "--raw", "-z", "--no-renames"] and args[-1] == "feature/x"
        return subprocess.CompletedProcess(args, 0, since_diff, "")

    monkeypatch.setattr(sa.subprocess, "run", run)
    assert sa._deployed_state_check("feature/x")[0] is expected
    assert seen[1][-2] == OLD_COMMIT


def test_no_configured_host_takes_the_full_path(monkeypatch, tmp_path):
    monkeypatch.delenv("NEXUS_HOST", raising=False)
    monkeypatch.setattr(sa.Path, "home", staticmethod(lambda: tmp_path))
    ok, detail = sa._deployed_state_check("feature/x")
    assert not ok and "no host" in detail


def test_the_probe_only_reads():
    # get/exec of one JSON file; nothing that changes the cluster
    assert "cat /app/project/deploy_info.json" in sa.DEPLOYED_PROBE
    for verb in ("apply", "delete", "patch", "scale", "rollout", "set image", "create", "edit"):
        assert "kubectl -n ui2 " + verb not in sa.DEPLOYED_PROBE


# ------------------------------------------------------------------------------------------------------ _ship steps

def ship_doubles(monkeypatch, diff, events, current=(True, "5 other modules run 1 commit(s) that differ in frontend/docs only"),
                 deploys=None):
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
        output = ""
        if "repository_privacy_check.py" in " ".join(args):
            output = "Gate:                 PASS"
        elif args[:3] == ["gh", "pr", "create"]:
            output = "https://example.invalid/pr/1"
        elif args[:2] == ["git", "diff"]:
            output = diff
        elif "tools/e2e/hosta_e2e.sh" in args:
            output = "E2E: PASS"
        elif "tools/delivery/hosta_integration.sh" in args:
            output = "INTEGRATION: PASS"
        return subprocess.CompletedProcess(args, 0, output, "")

    def deploy(*args, **kwargs):
        events.append(("deploy", args, kwargs))
        if deploys is not None:
            deploys.append((args, kwargs))

    monkeypatch.setattr(sa, "_git", git)
    monkeypatch.setattr(sa.subprocess, "run", run)
    monkeypatch.setattr(sa, "_preview_e2e", lambda *a: events.append(("preview",)))
    monkeypatch.setattr(sa, "_deploy", deploy)
    monkeypatch.setattr(sa, "_wait_for_checks", lambda url: events.append(("checks", url)))
    monkeypatch.setattr(sa, "_deployed_state_check", lambda branch: events.append(("deployed-check",)) or current)
    monkeypatch.setattr(sa.shutil, "which", lambda name: "/usr/bin/gitleaks")


def ship_args(**kw):
    base = dict(task=None, branch="feature/synthetic", title=None, notes=None, full=False,
                preview_e2e=False, no_preview_e2e=None)
    base.update(kw)
    return argparse.Namespace(**base)


def lines(capsys):
    return [json.loads(l) for l in capsys.readouterr().out.splitlines() if l.startswith("{")]


def index(events, pred):
    return next(i for i, e in enumerate(events) if pred(e))


FRONTEND_DIFF = text("ui2/frontend/src/screens/InventoryScreen.tsx", "ui2/frontend/tests/InventoryScreen.test.tsx")


def test_frontend_ship_takes_the_fast_path_and_says_why(monkeypatch, capsys):
    events, deploys = [], []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events, deploys=deploys)
    assert sa._ship(ship_args()) == 0
    out = lines(capsys)
    fast = next(o for o in out if o.get("fast_path") == "frontend")
    assert fast["frontend_files"] == 2 and fast["preview"] == "kept" and fast["early_pr"] is True
    assert fast["deploy_targets"] == ["service"] and fast["packet_analysis_image"] == "skipped"
    assert fast["privacy_gate"] == "pass" and fast["gitleaks"] == "pass"
    path = next(o for o in out if o.get("ship_path"))
    assert path["ship_path"] == "frontend" and "frontend" in path["why"]
    assert deploys == [((None,), {"targets": ("service",), "packet_analysis_image": False})]


def test_frontend_ship_keeps_every_gate_and_opens_the_pull_request_early(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events)
    assert sa._ship(ship_args()) == 0
    # privacy -> deployed-state guard -> gitleaks -> push -> PR (early) -> preview -> checks -> merge -> deploy -> e2e
    order = [index(events, pred) for pred in (
        lambda e: "repository_privacy_check.py" in " ".join(e),
        lambda e: e == ("deployed-check",),
        lambda e: e[0] == "gitleaks",
        lambda e: e[0] == "push",
        lambda e: e[:3] == ("gh", "pr", "create"),
        lambda e: e == ("preview",),
        lambda e: e[0] == "checks",
        lambda e: e[:3] == ("gh", "pr", "merge"),
        lambda e: e[0] == "deploy",
        lambda e: e[0] == "bash" and "tools/e2e/hosta_e2e.sh" in e)]
    assert order == sorted(order) and len(set(order)) == len(order)
    e2e = next(e for e in events if e[0] == "bash" and "tools/e2e/hosta_e2e.sh" in e)
    assert "--quick" in e2e                       # the production e2e still runs after the deploy
    body = next(e for e in events if e[:3] == ("gh", "pr", "create"))
    assert "Frontend-only change" in " ".join(body)


def test_failed_preview_closes_the_early_pull_request(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events)

    def failing(*a):
        raise SystemExit("preview e2e failed; stopped before PR merge")

    monkeypatch.setattr(sa, "_preview_e2e", failing)
    with pytest.raises(SystemExit, match="preview e2e failed"):
        sa._ship(ship_args())
    assert any(e[:3] == ("gh", "pr", "close") for e in events)
    assert not any(e[:3] == ("gh", "pr", "merge") or e[0] == "deploy" for e in events)


def test_frontend_ship_passes_the_security_bypass_through_unchanged(monkeypatch):
    events, deploys = [], []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events, deploys=deploys)
    assert sa._ship(ship_args(skip_security="emergency reason")) == 0
    assert deploys[0][0] == ("emergency reason",) and deploys[0][1]["targets"] == ("service",)


def test_other_modules_not_current_takes_the_full_path(monkeypatch, capsys):
    events, deploys = [], []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events, current=(False, "deployed commit abc differs beyond frontend/docs"),
                 deploys=deploys)
    assert sa._ship(ship_args()) == 0
    out = lines(capsys)
    path = next(o for o in out if o.get("ship_path"))
    assert path["ship_path"] == "full" and "not provably current" in path["why"]
    assert not any(o.get("fast_path") == "frontend" for o in out)
    assert deploys == [((None,), {})]                                      # the unchanged full deploy call
    order = [e[0] if e[0] != "gh" else "gh " + e[1] + " " + e[2] for e in events]
    assert order.index("preview") < order.index("push") < order.index("checks") < order.index("gh pr merge")


@pytest.mark.parametrize("diff,flags,why", [
    (text("ui2/frontend/src/App.tsx", "ui2/service/src/main/java/A.java"), {}, "backend change"),
    (text("ui2/frontend/src/App.tsx", "ui2/frontend/package-lock.json"), {}, "frontend build config change"),
    (text("ui2/frontend/src/App.tsx", "deploy/ui2/50-service-deployment.yaml"), {}, "backend change"),
    (FRONTEND_DIFF, {"full": True}, "--full requested"),
    (FRONTEND_DIFF, {"no_fast_path": True}, "--no-fast-path requested"),
    ("", {}, "no changed paths"),
])
def test_everything_else_takes_the_full_path_and_says_why(monkeypatch, capsys, diff, flags, why):
    events, deploys = [], []
    ship_doubles(monkeypatch, diff, events, deploys=deploys)
    assert sa._ship(ship_args(**flags)) == 0
    out = lines(capsys)
    path = next(o for o in out if o.get("ship_path"))
    assert path["ship_path"] == "full" and why in path["why"]
    assert ("deployed-check",) not in events                              # no host probe for a change that cannot qualify
    assert ("preview",) in events and len(deploys) == 1 and deploys[0][1] == {}
    assert not any(o.get("fast_path") in ("frontend", "docs_only") for o in out)


def test_docs_only_path_is_unchanged_and_reports_its_path(monkeypatch, capsys):
    events = []
    ship_doubles(monkeypatch, text("docs/a.md"), events)
    assert sa._ship(ship_args()) == 0
    out = lines(capsys)
    assert next(o for o in out if o.get("ship_path"))["ship_path"] == "docs_only"
    assert ("preview",) not in events and not any(e[0] == "deploy" for e in events)
    assert ("deployed-check",) not in events


def test_gitleaks_finding_stops_a_frontend_ship_before_anything_is_pushed(monkeypatch):
    events = []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events)
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


def test_frontend_ship_without_a_preview_waives_nothing_else(monkeypatch):
    events = []
    ship_doubles(monkeypatch, FRONTEND_DIFF, events)
    assert sa._ship(ship_args(no_preview_e2e="reason")) == 0
    assert ("preview",) not in events and any(e[0] == "checks" for e in events)
    assert next(e for e in events if e[0] == "deploy")[2]["targets"] == ("service",)


# ------------------------------------------------------------------------------------------------ _deploy command

def deploy_doubles(monkeypatch, tmp_path, calls, ssh_calls):
    state = tmp_path / "state"
    monkeypatch.setattr(sa, "STATE_DIR", state)
    monkeypatch.setattr(sa.Path, "home", staticmethod(lambda: tmp_path))
    (tmp_path / ".config" / "nexus").mkdir(parents=True)
    (tmp_path / ".config" / "nexus" / "hosta").write_text("user@host.example.invalid\n")
    snapshot = '{"snapshot": "20261113T012853949378Z_c0c782ecd526"}\n'
    summary = ('{"passed": true, "scan_errors": 0, "blocking": 0, "counts": {}}\n')
    monkeypatch.setattr(sa, "_registry_fetch_failed", lambda text: False)
    import tools.security.security_host as security_host
    monkeypatch.setattr(security_host, "safe_summary", lambda line: {"passed": True})

    def run(args, **kwargs):
        if args[0] == "bash":
            calls.append((args, kwargs))
            kwargs["stdout"].write(snapshot + summary + "TIMING image_build 11\nTIMING rollout 99\nUP after 100s\n"
                                   "site 200\nschema 208 true\nimage sha256:0123456789abcdef\n")
            return subprocess.CompletedProcess(args, 0)
        if args[0] == "ssh":
            ssh_calls.append(args)
            return subprocess.CompletedProcess(args, 0, "deployment successfully rolled out", "")
        raise AssertionError(args)

    monkeypatch.setattr(sa.subprocess, "run", run)


def test_full_deploy_command_is_unchanged(monkeypatch, tmp_path, capsys):
    sys.path.insert(0, str(ROOT))
    calls, ssh_calls = [], []
    deploy_doubles(monkeypatch, tmp_path, calls, ssh_calls)
    sa._deploy()
    argv, kwargs = calls[0]
    assert argv == ["bash", "tools/delivery/hosta_deploy.sh"]
    assert "NEXUS_BUILD_PACKET_ANALYSIS_IMAGE" not in kwargs["env"] or \
        kwargs["env"]["NEXUS_BUILD_PACKET_ANALYSIS_IMAGE"] == sa.os.environ.get("NEXUS_BUILD_PACKET_ANALYSIS_IMAGE")
    assert len(ssh_calls) == 1 and "ui2-configuration" in ssh_calls[0][-1]     # the configuration sync still runs
    out = capsys.readouterr().out
    assert "TIMING image_build 11" in out and "TIMING rollout 99" in out       # the deploy's timings reach the ship log


def test_frontend_deploy_rolls_out_only_the_service_and_builds_no_analysis_image(monkeypatch, tmp_path, capsys):
    sys.path.insert(0, str(ROOT))
    calls, ssh_calls = [], []
    deploy_doubles(monkeypatch, tmp_path, calls, ssh_calls)
    monkeypatch.setenv("NEXUS_BUILD_PACKET_ANALYSIS_IMAGE", "1")
    sa._deploy(None, targets=sa.FRONTEND_DEPLOY_TARGETS, packet_analysis_image=False)
    argv, kwargs = calls[0]
    assert argv == ["bash", "tools/delivery/hosta_deploy.sh", "--targets", "service"]
    assert kwargs["env"]["NEXUS_BUILD_PACKET_ANALYSIS_IMAGE"] == "0"
    assert ssh_calls == []                                                    # configuration keeps its image
    out = capsys.readouterr().out
    assert '"snapshot": "20261113T012853949378Z_c0c782ecd526"' in out         # the rollback snapshot is still taken
    assert '"targets": ["service"]' in out


def test_frontend_deploy_keeps_the_security_gate_requirement(monkeypatch, tmp_path):
    sys.path.insert(0, str(ROOT))
    calls, ssh_calls = [], []
    deploy_doubles(monkeypatch, tmp_path, calls, ssh_calls)
    import tools.security.security_host as security_host
    monkeypatch.setattr(security_host, "safe_summary", lambda line: {"passed": False})
    with pytest.raises(SystemExit, match="security gate success missing"):
        sa._deploy(None, targets=sa.FRONTEND_DEPLOY_TARGETS, packet_analysis_image=False)


def test_the_deploy_scripts_support_what_the_fast_path_relies_on():
    deploy = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    assert "--targets) TARGETS=" in deploy and "NEXUS_BUILD_PACKET_ANALYSIS_IMAGE" in deploy
    build = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    assert "release_snapshot.sh" in build and 'security_host.py" gate' in build   # snapshot and gate run on every deploy
    ingress = (ROOT / "deploy/ui2/60-ingress.yaml").read_text()
    assert ingress.count("name: ui2-service") == 2 and "ui2-worker" not in ingress and "ui2-configuration" not in ingress


# ------------------------------------------------------------------------------------------------------- classify

def test_classify_command_prints_the_path(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_change_verdict", lambda branch, base="origin/main", merge_base=False:
                        sa.classify_change(FRONTEND_DIFF))
    assert sa.main(["classify", "--branch", "HEAD"]) == 0
    assert json.loads(capsys.readouterr().out)["path"] == "frontend"


def test_classify_with_deployed_check_downgrades_a_stale_fleet(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_change_verdict", lambda branch, base="origin/main", merge_base=False:
                        sa.classify_change(FRONTEND_DIFF))
    monkeypatch.setattr(sa, "_deployed_state_check", lambda branch: (False, "worker: deployed commit unknown"))
    assert sa.main(["classify", "--branch", "HEAD", "--check-deployed"]) == 0
    out = json.loads(capsys.readouterr().out)
    assert out["path"] == "full" and "worker: deployed commit unknown" in out["why"]


def test_classify_reports_backend_changes_and_never_probes_the_host(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_change_verdict", lambda branch, base="origin/main", merge_base=False:
                        sa.classify_change(text("ui2/service/A.java")))
    monkeypatch.setattr(sa, "_deployed_state_check", lambda branch: pytest.fail("no probe for a backend change"))
    assert sa.main(["classify", "--branch", "HEAD", "--check-deployed"]) == 0
    out = json.loads(capsys.readouterr().out)
    assert out["path"] == "full" and out["first_paths"] == ["ui2/service/A.java"]


def test_classify_uses_the_merge_base_unless_the_branch_is_rebased(monkeypatch):
    seen = []

    def run(args, **kwargs):
        seen.append(args)
        return subprocess.CompletedProcess(args, 0, FRONTEND_DIFF, "")

    monkeypatch.setattr(sa.subprocess, "run", run)
    assert sa._change_verdict("HEAD", merge_base=True)["kind"] == "frontend"
    assert seen[-1][-1] == "origin/main...HEAD"
    sa._change_verdict("HEAD")
    assert seen[-1][-2:] == ["origin/main", "HEAD"]


def test_a_git_failure_is_a_full_verdict(monkeypatch):
    monkeypatch.setattr(sa.subprocess, "run", lambda args, **k: subprocess.CompletedProcess(args, 128, "", "fatal"))
    assert sa._change_verdict("HEAD")["kind"] == "full"
