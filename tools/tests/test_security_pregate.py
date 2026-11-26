"""Security gate beside the preview (PO 2026-11-23) and the fixes for the external review that blocked it:
fail-closed merge, verdict binding (commit, tree, policy, digests, gate day), reuse match/miss, the deployed scanner
scripts judging the candidate, the Git bundle round trip, SIGPIPE, unique temp names, and cancellation/cleanup."""
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import time

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "security"))
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
sys.path.insert(0, str(ROOT / "tools" / "tests"))
import security_host as host  # noqa: E402
import security_manifests as manifests  # noqa: E402
import security_summary  # noqa: E402
import security_verdict as sv  # noqa: E402
import standalone_orchestrate as so  # noqa: E402

REAL_START_PREGATE = so._start_security_pregate

COMMIT, TREE, OTHER = "a" * 40, "b" * 40, "c" * 40
POLICY = "d" * 64
IMG = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "1" * 64
IMG2 = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "2" * 64
NOW = 1_800_000_000
WRAPPER = ROOT / "tools" / "security" / "hosta_security_pregate.sh"
REMOTE = ROOT / "tools" / "security" / "security_pregate_remote.sh"


def summary(blocking_new=0):
    counts = {t: {s: dict(new=0, fixed=0, accepted=0, existing=0) for s in security_summary.SEVERITIES}
              for t in security_summary.TOOLS}
    first = security_summary.TOOLS[0]
    counts[first]["HIGH"]["new"] = blocking_new
    return dict(passed=blocking_new == 0, scan_errors=0, blocking=blocking_new, counts=counts)


def verdict(**over):
    v = sv.make_verdict(COMMIT, TREE, POLICY, [IMG], summary(), now=lambda: NOW)
    v.update(over)
    return v


def decide(v, tree=TREE, policy=POLICY, now=NOW):
    return sv.source_reuse_decision(v, tree, policy, now=lambda: now)


# ------------------------------------------------------------------------------------------ binding and reuse

def test_source_scan_reused_only_on_a_tree_policy_day_match():
    assert decide(verdict()) == (True, "tree, policy and gate day match the pre-merge source scan")


@pytest.mark.parametrize("kwargs,why", [
    (dict(tree=OTHER), "source tree differs"),
    (dict(policy="e" * 64), "gate policy"),
    (dict(now=NOW + sv.MAX_AGE_S + 1), "stale"),
    (dict(now=NOW - 1), "from the future"),
])
def test_rescan_when_anything_bound_differs(kwargs, why):
    ok, reason = decide(verdict(), **kwargs)
    assert not ok and why in reason


def test_a_pass_from_before_midnight_is_not_reused_after_it():
    # 20:59:30 UTC is 23:59:30 in the gate's zone (UTC+3); one minute later the accepted-finding day has changed.
    before = 1_800_000_000 - (1_800_000_000 % 86400) + 20 * 3600 + 59 * 60 + 30
    v = sv.make_verdict(COMMIT, TREE, POLICY, [IMG], summary(), now=lambda: before)
    assert decide(v, now=before + 10)[0] is True
    ok, why = decide(v, now=before + 60)
    assert not ok and "gate day changed" in why


def test_no_time_zone_database_means_no_gate_day_and_no_reuse(monkeypatch):
    import zoneinfo
    v = verdict()

    def missing(key):
        raise zoneinfo.ZoneInfoNotFoundError(key)
    monkeypatch.setattr(zoneinfo, "ZoneInfo", missing)
    with pytest.raises(RuntimeError, match="time zone unavailable"):
        sv.gate_day(lambda: NOW)
    assert decide(v) == (False, "gate time zone unavailable")


def test_failing_scan_errors_or_forged_verdict_is_never_reused():
    assert decide(verdict(summary=summary(blocking_new=1))) == (False, "verdict is not a pass")
    forged = verdict(summary=dict(summary(blocking_new=1), passed=True, blocking=0))
    assert decide(forged)[0] is False
    errors = verdict(summary=dict(summary(), scan_errors=1, passed=False))
    assert decide(errors)[0] is False
    assert decide(None) == (False, "no pre-merge verdict for this tree")
    assert decide(verdict(schema="nexus.security_verdict.v2"))[0] is False
    assert decide(verdict(day="yesterday"))[0] is False


def test_load_distinguishes_missing_unreadable_and_malformed(tmp_path):
    assert sv.load(tmp_path / "none.json") == (None, "no pre-merge verdict for this tree")
    (tmp_path / "dir.json").mkdir()
    assert sv.load(tmp_path / "dir.json")[1].startswith("pre-merge verdict unreadable")
    (tmp_path / "bytes.json").write_bytes(b"\xff\xfe\x00")
    assert sv.load(tmp_path / "bytes.json")[0] is None
    (tmp_path / "bad.json").write_text('{"schema": "x"}')
    assert sv.load(tmp_path / "bad.json") == (None, "verdict rejected: not a security verdict")
    (tmp_path / "ok.json").write_text(json.dumps(verdict()))
    assert sv.load(tmp_path / "ok.json")[0] == verdict()


def scanner_dir(path, rules=True):
    path.mkdir()
    for name in sv.SCANNER_SCRIPTS:
        (path / name).write_text(name)
    if rules:
        (path / "rules").mkdir()
        for pack in ("java", "typescript", "owasp-top-ten", "secrets"):
            (path / "rules" / (pack + ".yaml")).write_text("rules: []")
    return path


def test_policy_digest_covers_config_rules_and_scanner_scripts(tmp_path):
    cfg, rules, tools = tmp_path / "security.json", tmp_path / "rules", scanner_dir(tmp_path / "tools", rules=False)
    rules.mkdir()
    cfg.write_text("{}")
    (rules / "java.yaml").write_text("a")
    seen = {sv.policy_digest(cfg, rules, tools)}
    assert sv.policy_digest(b"{}", rules, tools) in seen  # the exact bytes hash like the file holding them
    (rules / "java.yaml").write_text("b")
    seen.add(sv.policy_digest(cfg, rules, tools))
    cfg.write_text('{"accepted": []}')
    seen.add(sv.policy_digest(cfg, rules, tools))
    (tools / "security_summary.py").write_text("changed")
    seen.add(sv.policy_digest(cfg, rules, tools))
    # security_manifests.py renders the gate Job (TREE_STAGES, commands, mounts, TZ): a change to it alone is a new
    # policy, so a merged manifest change can never be covered by a verdict made under the old one.
    (tools / "security_manifests.py").write_text("TREE_STAGES = ()")
    seen.add(sv.policy_digest(cfg, rules, tools))
    assert len(seen) == 5
    assert "security_manifests.py" in sv.SCANNER_SCRIPTS


def test_the_real_policy_covers_the_real_manifest_module(tmp_path):
    tools = tmp_path / "tools"
    tools.mkdir()
    for name in sv.SCANNER_SCRIPTS:
        (tools / name).write_bytes((host.HERE / name).read_bytes())
    cfg, rules = b"{}", scanner_dir(tmp_path / "cfg") / "rules"
    assert sv.policy_digest(cfg, rules, tools) == sv.policy_digest(cfg, rules, host.HERE)
    text = (tools / "security_manifests.py").read_text()
    stages = 'TREE_STAGES = ("semgrep", "gitleaks", "trivy")'
    (tools / "security_manifests.py").write_text(text.replace(stages, stages[:-1] + ', "history")'))
    assert (tools / "security_manifests.py").read_text() != text
    assert sv.policy_digest(cfg, rules, tools) != sv.policy_digest(cfg, rules, host.HERE)


def test_staged_tools_id_addresses_scripts_and_rules(tmp_path):
    staged = scanner_dir(tmp_path / "staged")
    seen = {sv.staged_tools_digest(staged)}
    (staged / "rules" / "java.yaml").write_text("other rules")
    seen.add(sv.staged_tools_digest(staged))
    (staged / "security_scan.sh").write_text("other script")
    seen.add(sv.staged_tools_digest(staged))
    assert len(seen) == 3


# --------------------------------------------------------------------------------------- merge-time verdict

class Clock:
    def __init__(self):
        self.t = 0.0

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def await_(path, timeout=30, required=True):
    c = Clock()
    return sv.await_ship_verdict(path, COMMIT, TREE, timeout, 10, c.sleep, c.monotonic, required=required)


def test_pass_for_this_commit_lets_the_merge_go_on(tmp_path):
    p = tmp_path / "v.txt"
    p.write_text(f"PASS {COMMIT} {TREE}\n")
    assert await_(p) == "PASS"


@pytest.mark.parametrize("content", [b"FAIL\n", b"FAIL (no verdict from the pre-merge security gate)\n",
                                     f"PASS {OTHER} {TREE}\n".encode(), f"PASS {COMMIT} {OTHER}\n".encode(),
                                     b"garbage\n", b"\xff\xfe garbage\n"])
def test_fail_foreign_or_garbage_verdict_blocks_merge(tmp_path, content):
    p = tmp_path / "v.txt"
    p.write_bytes(content)
    with pytest.raises(SystemExit, match="not merged"):
        await_(p)


def test_missing_verdict_times_out_and_blocks_merge(tmp_path):
    with pytest.raises(SystemExit, match="still missing after 30 s; not merged"):
        await_(tmp_path / "never.txt")


def test_unreadable_verdict_blocks_merge_at_once(tmp_path):
    (tmp_path / "v.txt").mkdir()
    with pytest.raises(SystemExit, match="unreadable"):
        await_(tmp_path / "v.txt")


@pytest.mark.parametrize("word", ["NOT_CONFIGURED", "UNAVAILABLE"])
def test_not_configured_or_unavailable_passes_only_before_the_pregate_is_configured(tmp_path, word):
    p = tmp_path / "v.txt"
    p.write_text(word + "\n")
    assert await_(p, required=False) == word
    with pytest.raises(SystemExit, match="although the pre-merge gate is configured"):
        await_(p, required=True)


def test_pregate_becomes_required_after_a_pass_and_stays_required(tmp_path, monkeypatch):
    monkeypatch.setattr(so, "STATE_DIR", tmp_path)
    monkeypatch.delenv("NEXUS_SECURITY_PREGATE_REQUIRED", raising=False)
    p = tmp_path / "v.txt"
    p.write_text("UNAVAILABLE\n")
    assert so._await_security_verdict(p, COMMIT, TREE, timeout=1) == "UNAVAILABLE"
    p.write_text(f"PASS {COMMIT} {TREE}\n")
    assert so._await_security_verdict(p, COMMIT, TREE, timeout=1) == "PASS"
    assert (tmp_path / "security-pregate-enabled").exists()
    p.write_text("UNAVAILABLE\n")
    with pytest.raises(SystemExit, match="configured"):
        so._await_security_verdict(p, COMMIT, TREE, timeout=1)


def test_pregate_required_by_env_or_when_the_marker_cannot_be_read(tmp_path, monkeypatch):
    monkeypatch.setattr(so, "STATE_DIR", tmp_path)
    monkeypatch.delenv("NEXUS_SECURITY_PREGATE_REQUIRED", raising=False)
    assert so._security_pregate_required() is False
    monkeypatch.setenv("NEXUS_SECURITY_PREGATE_REQUIRED", "1")
    assert so._security_pregate_required() is True
    monkeypatch.delenv("NEXUS_SECURITY_PREGATE_REQUIRED")

    class Unreadable:
        def stat(self):
            raise PermissionError("denied")
    monkeypatch.setattr(so, "_security_pregate_marker", lambda: Unreadable())
    assert so._security_pregate_required() is True


@pytest.mark.parametrize("stdout,rc,line", [
    (f"PASS {COMMIT} {TREE}\n" + '{"passed": true}\n', 0, f"PASS {COMMIT} {TREE}"),
    ("FAIL\n", 0, "FAIL"),
    ("", 0, "FAIL (no verdict from the pre-merge security gate)"),
    (f"PASS {COMMIT} {TREE}\n", 255, "FAIL (no verdict from the pre-merge security gate)"),
    (f"PASS {COMMIT} {TREE}\nFAIL\n", 0, "FAIL (no verdict from the pre-merge security gate)"),
    ("NOT_CONFIGURED\n", 141, "FAIL (no verdict from the pre-merge security gate)"),
])
def test_pregate_output_maps_to_one_verdict_line(stdout, rc, line):
    assert so._security_pregate_line(stdout, rc) == line


# ------------------------------------------------------------------------------- ship-side runner and cancel

class FakeProc:
    def __init__(self, stdout="", rc=0, error=None):
        self.stdout, self.returncode, self.error, self.pid = stdout, rc, error, 999999

    def communicate(self, timeout=None):
        if self.error:
            raise self.error
        return self.stdout, ""

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        return self.returncode


def run_pregate(tmp_path, popen, **kw):
    p = tmp_path / "state" / "v.txt"
    handle = so._start_security_pregate(tmp_path, COMMIT, TREE, p, popen=popen, **kw)
    handle.join(10)
    return p.read_text()


def test_pregate_runner_writes_the_verdict_file(tmp_path):
    seen = {}

    def popen(argv, **kw):
        seen.update(argv=argv, kw=kw)
        return FakeProc(f"PASS {COMMIT} {TREE}\n")
    assert run_pregate(tmp_path, popen) == f"PASS {COMMIT} {TREE}\n"
    assert seen["argv"][-6:] == ["--repo", str(tmp_path), "--commit", COMMIT, "--tree", TREE]
    assert seen["kw"]["start_new_session"] is True and seen["kw"]["errors"] == "replace"
    assert not list((tmp_path / "state").glob("*.tmp"))


@pytest.mark.parametrize("popen", [
    lambda argv, **kw: (_ for _ in ()).throw(OSError("no bash")),
    lambda argv, **kw: FakeProc(error=UnicodeDecodeError("utf-8", b"\xff", 0, 1, "bad")),
    lambda argv, **kw: FakeProc(error=RuntimeError("anything")),
    lambda argv, **kw: FakeProc("NOT_CONFIGURED\n", rc=141),
])
def test_every_runner_failure_still_writes_a_fail_verdict(tmp_path, popen):
    assert run_pregate(tmp_path, popen).startswith("FAIL")


def test_runner_timeout_cancels_and_fails(tmp_path, monkeypatch):
    cancelled = []
    monkeypatch.setattr(so._SecurityPregate, "cancel", lambda self, grace=10.0: cancelled.append(True))
    out = run_pregate(tmp_path, lambda argv, **kw: FakeProc(error=subprocess.TimeoutExpired(argv, 1)))
    assert out == "FAIL (pre-merge security gate timed out)\n" and cancelled


def test_verdict_paths_are_unique_per_run(tmp_path, monkeypatch):
    monkeypatch.setattr(so, "STATE_DIR", tmp_path)
    assert so._security_verdict_path(COMMIT) != so._security_verdict_path(COMMIT)


def test_cancel_kills_the_whole_process_group(tmp_path):
    script = tmp_path / "slow.sh"
    script.write_text("sleep 60 &\nwait\n")

    def popen(argv, **kw):
        return subprocess.Popen(["bash", str(script)], **kw)
    p = tmp_path / "state" / "v.txt"
    handle = so._start_security_pregate(tmp_path, COMMIT, TREE, p, popen=popen)
    deadline = time.monotonic() + 10
    while handle.proc is None and time.monotonic() < deadline:
        time.sleep(0.05)
    pgid = handle.proc.pid
    handle.cancel(grace=5)
    handle.join(10)
    assert p.read_text() == "FAIL (pre-merge security gate cancelled)\n"
    with pytest.raises(ProcessLookupError):
        os.killpg(pgid, 0)


@pytest.mark.parametrize("ignore_term", [False, True], ids=["term", "sigkill-escalation"])
def test_cancel_kills_surviving_child_in_process_group_after_leader_exits(tmp_path, ignore_term):
    """If the bash leader exits while a child still lives in PGID proc.pid and holds stdout open, cancel() must still
    signal the process group (escalating to SIGKILL when the child ignores SIGTERM) so communicate() and close() do not
    hang."""
    ready = tmp_path / "ready"
    script = tmp_path / "orphan-child.sh"
    trap_line = "trap '' TERM\n" if ignore_term else ""
    script.write_text(
        f"{trap_line}"
        f"(echo ready > {ready}; exec sleep 60) &\n"
        "exit 0\n"
    )

    def popen(argv, **kw):
        return subprocess.Popen(["bash", str(script)], **kw)
    p = tmp_path / "state" / "v.txt"
    handle = so._start_security_pregate(tmp_path, COMMIT, TREE, p, popen=popen)
    deadline = time.monotonic() + 10
    while (handle.proc is None or handle.proc.poll() is None or not ready.exists()) and time.monotonic() < deadline:
        time.sleep(0.05)
    assert handle.proc is not None and handle.proc.poll() is not None and ready.exists()
    pgid = handle.proc.pid
    # The leader has exited, yet the runner thread is still blocked in communicate() because the child holds stdout.
    assert handle.thread.is_alive()
    os.killpg(pgid, 0)
    handle.cancel(grace=0.5)
    handle.join(10)
    assert not handle.thread.is_alive()
    assert p.read_text() == "FAIL (pre-merge security gate cancelled)\n"
    with pytest.raises(ProcessLookupError):
        os.killpg(pgid, 0)


def test_close_before_the_process_is_attached_still_kills_it_and_never_leaves_a_verdict(tmp_path):
    """The review's orphan race: the ship gives up while the runner thread is still inside popen. close() must wait for
    the thread, which then kills the group it started; the verdict file is gone afterwards and stays gone."""
    import threading
    script = tmp_path / "slow.sh"
    script.write_text("sleep 60 &\nwait\n")
    entered, release, started = threading.Event(), threading.Event(), []

    def popen(argv, **kw):
        entered.set()
        release.wait(10)
        proc = subprocess.Popen(["bash", str(script)], **kw)
        started.append(proc)
        return proc
    p = tmp_path / "state" / "v.txt"
    handle = so._start_security_pregate(tmp_path, COMMIT, TREE, p, popen=popen)
    assert entered.wait(10)
    assert handle.proc is None
    threading.Timer(0.5, release.set).start()  # popen returns only after close() has begun
    so._close_security_pregate(handle, p, None)
    assert not handle.thread.is_alive()
    with pytest.raises(ProcessLookupError):
        os.killpg(started[0].pid, 0)
    assert not p.exists()
    assert handle.write_verdict("PASS late") is False and not p.exists()


def test_a_runner_that_outlives_close_cannot_recreate_the_deleted_verdict(tmp_path):
    import threading
    release = threading.Event()

    class Lingering(FakeProc):
        def communicate(self, timeout=None):
            release.wait(10)
            return f"PASS {COMMIT} {TREE}\n", ""

        def poll(self):
            return 0  # nothing left to kill: cancel() returns at once
    p = tmp_path / "state" / "v.txt"
    handle = so._start_security_pregate(tmp_path, COMMIT, TREE, p, popen=lambda argv, **kw: Lingering())
    handle.close(timeout=0.2)
    p.unlink(missing_ok=True)
    release.set()
    handle.join(10)
    assert not handle.thread.is_alive() and not p.exists()


def test_close_defers_sigterm_until_the_cleanup_is_done(tmp_path):
    order = []

    class Handle:
        def close(self):
            os.kill(os.getpid(), signal.SIGTERM)
            time.sleep(0.2)
            order.append("closed")
    p = tmp_path / "v.txt"
    p.write_text("x")
    before = signal.signal(signal.SIGTERM, so._raise_on_terminate)
    try:
        with pytest.raises(SystemExit, match="terminated by signal"):
            so._close_security_pregate(Handle(), p, before)
        assert order == ["closed"] and not p.exists()
        assert signal.getsignal(signal.SIGTERM) == before
    finally:
        signal.signal(signal.SIGTERM, before)


@pytest.mark.parametrize("signum", [signal.SIGTERM, signal.SIGHUP, signal.SIGINT], ids=lambda s: s.name)
def test_close_defers_every_terminating_signal_and_restores_the_ship_dispositions(tmp_path, signum):
    # The review's SIGHUP leak: a hang-up inside the cleanup used to abort it before pregate.close() had finished.
    order = []

    class Handle:
        def close(self):
            os.kill(os.getpid(), signum)
            time.sleep(0.2)
            order.append("closed")
    p = tmp_path / "v.txt"
    p.write_text("x")
    saved = {s: signal.getsignal(s) for s in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)}
    previous = so._install_terminate_handlers()
    try:
        assert set(previous) == {signal.SIGTERM, signal.SIGHUP}
        assert all(signal.getsignal(s) == so._raise_on_terminate for s in previous)
        with pytest.raises(SystemExit, match="terminated by signal " + str(int(signum))):
            so._close_security_pregate(Handle(), p, previous)
        assert order == ["closed"] and not p.exists()
        assert {s: signal.getsignal(s) for s in saved} == saved
    finally:
        for s, handler in saved.items():
            signal.signal(s, handler)


# ---------------------------------------------------------------------------------------- the ship itself

@pytest.fixture
def ship(monkeypatch, tmp_path):
    from test_ship_speed import ship_doubles, ship_args, text
    events = []
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)
    monkeypatch.setattr(so, "STATE_DIR", tmp_path)
    handles = []

    class Handle:
        def __init__(self, *args):
            self.args, self.closed = args, 0
            handles.append(self)
            events.append(("pregate-start",))

        def close(self):
            self.closed += 1
            events.append(("pregate-close",))

    real_git = so._git

    def git(*args, **kw):  # the tree answers with its own hash, so commit and tree can never be swapped unnoticed
        if args[:1] == ("rev-parse",) and args[-1].endswith("^{tree}"):
            events.append(tuple(args))
            return TREE
        return real_git(*args, **kw)
    monkeypatch.setattr(so, "_git", git)
    monkeypatch.setattr(so, "_start_security_pregate", Handle)
    monkeypatch.setattr(so, "_await_security_verdict", lambda *a: events.append(("verdict",) + a[1:]) or "PASS")
    return events, handles, ship_args


def test_ship_merges_the_scanned_commit_only(ship):
    events, handles, ship_args = ship
    assert so._ship(ship_args()) == 0
    merges = [e for e in events if e[:3] == ("gh", "pr", "merge")]
    assert merges and all(e[4:6] == ("--match-head-commit", COMMIT) for e in merges)
    pushes = [e for e in events if e[:2] == ("push", "-q") and "--force-with-lease" in e]
    assert pushes and all(e[-1] == COMMIT + ":refs/heads/feature/synthetic" for e in pushes)
    assert handles[0].args[1:3] == (COMMIT, TREE)
    assert ("verdict", COMMIT, TREE) in events
    assert [e[0] for e in events].index("verdict") < [e[:3] for e in events].index(("gh", "pr", "merge"))
    # The runner is closed (joined) on the passing path too, before the merge.
    assert handles[0].closed == 1
    assert [e[0] for e in events].index("pregate-close") < [e[:3] for e in events].index(("gh", "pr", "merge"))


def test_failed_preview_cancels_the_pregate(ship, monkeypatch):
    events, handles, ship_args = ship

    def red(*a):
        raise SystemExit("preview e2e failed; stopped before PR merge")
    monkeypatch.setattr(so, "_preview_e2e", red)
    with pytest.raises(SystemExit, match="preview"):
        so._ship(ship_args())
    assert handles[0].closed == 1
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events)


def test_failed_verdict_cancels_closes_and_never_merges(ship, monkeypatch):
    events, handles, ship_args = ship

    def red(*a):
        raise SystemExit("security gate did not pass before the merge (FAIL); not merged")
    monkeypatch.setattr(so, "_await_security_verdict", red)
    with pytest.raises(SystemExit, match="not merged"):
        so._ship(ship_args())
    assert handles[0].closed == 1
    assert any(e[:3] == ("gh", "pr", "close") for e in events)
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events) and ("deploy",) not in events


def test_sigterm_during_the_wait_unwinds_and_cancels(ship, monkeypatch):
    events, handles, ship_args = ship

    def terminated(*a):
        os.kill(os.getpid(), signal.SIGTERM)
        time.sleep(1)
        return "PASS"
    monkeypatch.setattr(so, "_await_security_verdict", terminated)
    before = signal.getsignal(signal.SIGTERM)
    with pytest.raises(SystemExit, match="terminated by signal"):
        so._ship(ship_args())
    assert handles[0].closed == 1 and signal.getsignal(signal.SIGTERM) == before
    assert not any(e[:3] == ("gh", "pr", "close") for e in events)
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events)


@pytest.mark.parametrize("signum", [signal.SIGHUP, signal.SIGTERM], ids=lambda s: s.name)
@pytest.mark.parametrize("phase", ["preview", "pretest", "verdict"])
def test_a_hangup_or_termination_unwinds_the_ship_and_cancels_the_pregate(ship, monkeypatch, tmp_path, signum, phase):
    """The review's SIGHUP/SIGTERM leak: a terminating signal must unwind straight into pre-gate cleanup without
    running `gh pr close` (with a false failure message) or `git push --delete` first."""
    events, handles, ship_args = ship
    real_run = so.subprocess.run

    def guard_run(argv, **kw):
        if argv[:3] == ["gh", "pr", "close"] or (argv[:2] == ["git", "push"] and "--delete" in argv):
            pytest.fail("signal termination must not run PR/branch close before pre-gate cleanup: " + repr(argv))
        return real_run(argv, **kw)
    monkeypatch.setattr(so.subprocess, "run", guard_run)

    def terminated(*a):
        os.kill(os.getpid(), signum)
        time.sleep(1)
        return "PASS"
    target = {"preview": "_preview_e2e", "pretest": "_await_pretest", "verdict": "_await_security_verdict"}[phase]
    monkeypatch.setattr(so, target, terminated)
    args = ship_args()
    if phase == "pretest":
        args.await_pretest = str(tmp_path / "pretest.txt")
    # A recording disposition stands in for the terminal's default (which would end the test run): a ship that does not
    # take the signal over leaves it here, carries on and fails the assertions below instead of killing pytest.
    ignored = []
    saved = {s: signal.signal(s, lambda n, frame: ignored.append(n)) for s in (signal.SIGTERM, signal.SIGHUP)}
    try:
        before = {s: signal.getsignal(s) for s in saved}
        with pytest.raises(SystemExit, match="terminated by signal " + str(int(signum))):
            so._ship(args)
        assert handles[0].closed == 1 and ignored == []
        assert {s: signal.getsignal(s) for s in before} == before
    finally:
        for s, handler in saved.items():
            signal.signal(s, handler)
    assert not any(e[:3] == ("gh", "pr", "close") for e in events)
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events)


def test_sigterm_releases_preview_lock_and_kills_slow_pregate_without_closing_pr(ship, monkeypatch, tmp_path):
    """End-to-end signal behaviour through cmd_ship: a slow pre-gate process group is killed and ship-preview.lock is
    released immediately even if `gh pr close` would have hung."""
    import fcntl
    events, _, ship_args = ship
    script = tmp_path / "slow-pregate.sh"
    script.write_text("sleep 60 &\nwait\n")
    started = []

    def start_real(repo, commit, tree, verdict_path):
        def popen(argv, **kw):
            proc = subprocess.Popen(["bash", str(script)], **kw)
            started.append(proc)
            return proc
        return REAL_START_PREGATE(repo, commit, tree, verdict_path, popen=popen)
    monkeypatch.setattr(so, "_start_security_pregate", start_real)
    real_run = so.subprocess.run

    def slow_close(argv, **kw):
        if argv[:3] == ["gh", "pr", "close"]:
            time.sleep(30)
        return real_run(argv, **kw)
    monkeypatch.setattr(so.subprocess, "run", slow_close)

    def terminate_once_running(*a):
        deadline = time.monotonic() + 10
        while not started and time.monotonic() < deadline:
            time.sleep(0.05)
        os.kill(os.getpid(), signal.SIGTERM)
        time.sleep(1)
    monkeypatch.setattr(so, "_preview_e2e", terminate_once_running)
    t0 = time.monotonic()
    with pytest.raises(SystemExit, match="terminated by signal"):
        so.cmd_ship(ship_args())
    assert time.monotonic() - t0 < 10
    assert started
    with pytest.raises(ProcessLookupError):
        os.killpg(started[0].pid, 0)
    with (tmp_path / "ship-preview.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(lock, fcntl.LOCK_UN)
    assert not any(e[:3] == ("gh", "pr", "close") for e in events)


def test_failed_premerge_gate_bounds_pr_close_timeouts_and_still_closes_pregate(ship, monkeypatch):
    """On a real gate failure, PR close and remote branch delete carry explicit timeouts and a timeout in `gh pr close`
    never skips branch deletion, pre-gate cleanup or the original failure exit."""
    events, handles, ship_args = ship
    real_run = so.subprocess.run
    cleanup_calls = []

    def timed_run(argv, **kw):
        if argv[:3] == ["gh", "pr", "close"]:
            cleanup_calls.append((argv[:3], kw.get("timeout")))
            raise subprocess.TimeoutExpired(argv, kw.get("timeout") or 0)
        if argv[:2] == ["git", "push"] and "--delete" in argv:
            cleanup_calls.append((argv[:4], kw.get("timeout")))
        return real_run(argv, **kw)
    monkeypatch.setattr(so.subprocess, "run", timed_run)
    monkeypatch.setattr(so, "_preview_e2e", lambda *a: (_ for _ in ()).throw(
        SystemExit("preview e2e failed; stopped before PR merge")))
    with pytest.raises(SystemExit, match="preview e2e failed"):
        so._ship(ship_args())
    assert cleanup_calls == [(["gh", "pr", "close"], so.PR_CLOSE_TIMEOUT_S),
                             (["git", "push", "-q", "origin"], so.PR_CLOSE_TIMEOUT_S)]
    assert handles[0].closed == 1


def test_a_branch_that_moved_after_the_scan_is_not_merged(ship, monkeypatch):
    events, handles, ship_args = ship
    real_git = so._git
    calls = []

    def git(*args, **kw):
        if args == ("rev-parse", "feature/synthetic"):
            calls.append(args)
            if len(calls) > 1:
                return OTHER
        return real_git(*args, **kw)
    monkeypatch.setattr(so, "_git", git)
    with pytest.raises(SystemExit, match="branch moved"):
        so._ship(ship_args())
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events)


def test_merge_is_pinned_to_the_head_commit_on_rebase_and_squash(monkeypatch):
    calls = []

    def fake_run(cmd, **kw):
        calls.append(cmd)
        if "--rebase" in cmd:
            return subprocess.CompletedProcess(cmd, 1, "", "This branch can't be rebased")
        return subprocess.CompletedProcess(cmd, 0, "", "")
    monkeypatch.setattr(so.subprocess, "run", fake_run)
    assert so._merge_pull_request("https://example.invalid/pr/1", head=COMMIT).returncode == 0
    assert [c[4:] for c in calls] == [["--match-head-commit", COMMIT, "--rebase"],
                                      ["--match-head-commit", COMMIT, "--squash"]]


def test_conftest_doubles_both_import_names():
    # This module opts out of the double; the others get it for either name, imported eagerly or lazily.
    conftest = (ROOT / "tools" / "tests" / "conftest.py").read_text()
    assert '"tools.delivery.standalone_orchestrate"' in conftest and "importlib.import_module(name)" in conftest


# -------------------------------------------------------------------------------- host side: pregate/reuse

def gate_config():
    digest = "sha256:" + "a" * 64
    return dict(images={t: manifests.REGISTRY + "/scan/" + t + "@" + digest for t in ("semgrep", "gitleaks", "trivy", "zap")},
                proxy_ip="192.0.2.10", registry_ip="192.0.2.11", service_ip="192.0.2.12", proxy_port=3128,
                node_name="node-a")


TOOLS_ID = "9" * 64
GATE = "security-gate-" + "f" * 12


@pytest.fixture
def hostenv(tmp_path, monkeypatch):
    import hosta_restamp_image
    cfg, rules = tmp_path / "security.json", tmp_path / "rules"
    cfg.write_text(json.dumps(gate_config()))
    rules.mkdir()
    (rules / "java.yaml").write_text("rules")
    calls = []
    monkeypatch.setattr(host, "run", lambda argv, **kw: COMMIT + "\n")
    monkeypatch.setattr(hosta_restamp_image, "git_tree", lambda repo, commit: TREE)
    monkeypatch.setattr(host, "snapshot",
                        lambda *a, **kw: calls.append(("snapshot", kw)) or (COMMIT, TOOLS_ID, POLICY))
    monkeypatch.setattr(host, "new_gate_name", lambda: GATE)

    def start_gate(config, commit, **kw):
        calls.append(("start", commit, kw))
        return kw.get("name") or GATE
    monkeypatch.setattr(host, "start_gate", start_gate)
    monkeypatch.setattr(host, "kubectl", lambda *a, **kw: calls.append(("kubectl",) + a) or "")
    return dict(cfg=cfg, rules=rules, calls=calls, verdicts=tmp_path / "state" / "verdicts", restamp=hosta_restamp_image)


def record(commit=COMMIT, created=NOW, digest="1" * 64):
    return dict(tree=TREE, commit=commit, service="sha256:" + digest, created=created)


def run_host_pregate(hostenv, **kw):
    clock = Clock()
    kw.setdefault("now", lambda: NOW)
    return host.pregate(hostenv["cfg"], Path("/repo"), hostenv["rules"], COMMIT, TREE, verdicts=hostenv["verdicts"],
                        sleep=clock.sleep, monotonic=clock.monotonic, **kw)


def test_pregate_scans_with_the_staged_tools_and_stores_a_verdict_bound_to_them(hostenv, monkeypatch):
    records = iter([None, record(created=NOW - 600), record(commit=OTHER, created=NOW + 5), record(created=NOW + 9)])
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: next(records))
    scanned = []
    monkeypatch.setattr(host, "gate", lambda config, images, commit, name: scanned.append((images, name)) or summary())
    result = run_host_pregate(hostenv)
    # The stale record of an earlier run and another commit's record are skipped; the fresh one is scanned.
    assert scanned == [([IMG], GATE)]
    start = next(c for c in hostenv["calls"] if c[0] == "start")
    # The Job mounts exactly the staged copy whose digest the verdict's policy carries (snapshot's answer).
    assert start[2]["pregate_tools"] == "pregate-tools/" + TOOLS_ID and start[2]["name"] == GATE
    snap = next(c for c in hostenv["calls"] if c[0] == "snapshot")[1]
    assert snap["publish"] is False and snap["config_bytes"] == hostenv["cfg"].read_bytes()
    path = hostenv["verdicts"] / (TREE + ".json")
    stored = json.loads(path.read_text())
    assert result == dict(commit=COMMIT, tree=TREE, stored=True)
    assert stored["commit"] == COMMIT and stored["tree"] == TREE and stored["images"] == [IMG]
    assert stored["policy"] == POLICY and stored["day"] == sv.gate_day(lambda: NOW)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(hostenv["verdicts"].stat().st_mode) == 0o700
    assert (hostenv["verdicts"].parent / "pregate-enabled").exists()
    assert not any(c[:2] == ("kubectl", "delete") for c in hostenv["calls"])


@pytest.mark.parametrize("days", [["2027-01-01", "2027-01-02"], [RuntimeError, RuntimeError]])
def test_a_scan_across_midnight_or_without_a_zone_passes_the_merge_but_is_not_stored(hostenv, monkeypatch, capsys, days):
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: record(created=NOW))
    monkeypatch.setattr(host, "gate", lambda *a: summary())
    answers = iter(days)

    def gate_day(now=None):
        answer = next(answers)
        if answer is RuntimeError:
            raise RuntimeError("gate time zone unavailable")
        return answer
    monkeypatch.setattr(sv, "gate_day", gate_day)
    assert run_host_pregate(hostenv) == dict(commit=COMMIT, tree=TREE, stored=False)
    assert not (hostenv["verdicts"] / (TREE + ".json")).exists()
    assert '"security_verdict_stored": false' in capsys.readouterr().out


def test_the_start_anchor_comes_from_the_host_side_session_start(hostenv, monkeypatch):
    # The preview finished (record at NOW - 250) while the history was still streaming; the session began at NOW - 300.
    seen = []
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: seen.append(1) or record(created=NOW - 250))
    monkeypatch.setattr(host, "gate", lambda *a: summary())
    assert run_host_pregate(hostenv, not_before=NOW - 300)["stored"] is True and len(seen) == 1
    # Without the anchor the same record would be taken for an older run's.
    with pytest.raises(RuntimeError, match="no preview image"):
        run_host_pregate(hostenv, wait_s=60)
    # A record from before the session is an older run's.
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: record(created=NOW - 301))
    with pytest.raises(RuntimeError, match="no preview image"):
        run_host_pregate(hostenv, not_before=NOW - 300, wait_s=60)


@pytest.mark.parametrize("previous_age", [0, 1, 30, 59])
def test_a_retried_ship_never_scans_the_previous_runs_preview_digest(hostenv, monkeypatch, previous_age):
    """Run 1 of COMMIT passed its preview (digest 1) and failed later; run 2 starts within a minute. Run 2's pre-gate
    must wait for run 2's own preview record (digest 2), never scan digest 1 -- not even one written in the very
    second run 2's session started."""
    started = NOW - 600
    records = iter([record(created=started - previous_age, digest="1" * 64)] * 3
                   + [record(created=started + 240, digest="2" * 64)])
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: next(records))
    scanned = []
    monkeypatch.setattr(host, "gate", lambda config, images, commit, name: scanned.append(images) or summary())
    assert run_host_pregate(hostenv, not_before=started)["stored"] is True
    assert scanned == [[hostenv["restamp"].REPOSITORY + "@sha256:" + "2" * 64]]
    stored = json.loads((hostenv["verdicts"] / (TREE + ".json")).read_text())
    assert stored["images"] == scanned[0]


def test_without_the_anchor_a_record_must_postdate_the_driver_start(hostenv, monkeypatch):
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: record(created=NOW))
    monkeypatch.setattr(host, "gate", lambda *a: summary())
    assert run_host_pregate(hostenv)["stored"] is True  # the same second, as before the anchor existed
    monkeypatch.setattr(hostenv["restamp"], "load_record", lambda tree: record(created=NOW - 1))
    with pytest.raises(RuntimeError, match="no preview image"):
        run_host_pregate(hostenv, wait_s=60)


@pytest.mark.parametrize("failure", ["no-image", "gate-refused", "terminated"])
def test_pregate_deletes_its_job_and_configmap_on_every_failure(hostenv, monkeypatch, failure):
    monkeypatch.setattr(hostenv["restamp"], "load_record",
                        lambda tree: None if failure == "no-image" else record(created=NOW))

    def gate(*a):
        if failure == "terminated":
            raise SystemExit(143)
        raise RuntimeError("security gate refused rollout")
    monkeypatch.setattr(host, "gate", gate)
    with pytest.raises((RuntimeError, SystemExit)):
        run_host_pregate(hostenv, wait_s=30)
    for kind in ("job", "configmap"):
        assert ("kubectl", "delete", kind, GATE, "--ignore-not-found", "--wait=false") in hostenv["calls"]
    assert not (hostenv["verdicts"] / (TREE + ".json")).exists()


def test_a_signal_during_job_creation_is_held_until_both_objects_exist_then_both_are_deleted(tmp_path, monkeypatch):
    """The review's leak: SIGTERM between `create configmap` and `create job` used to bypass every cleanup."""
    import hosta_restamp_image
    cfg, rules = tmp_path / "security.json", tmp_path / "rules"
    cfg.write_text(json.dumps(gate_config()))
    rules.mkdir()
    monkeypatch.setattr(host, "run", lambda argv, **kw: COMMIT + "\n")
    monkeypatch.setattr(hosta_restamp_image, "git_tree", lambda repo, commit: TREE)
    monkeypatch.setattr(host, "snapshot", lambda *a, **kw: (COMMIT, TOOLS_ID, POLICY))
    monkeypatch.setattr(hosta_restamp_image, "load_record", lambda tree: pytest.fail("must not get this far"))
    calls = []

    def kubectl(*args, **kw):
        calls.append(args[:3])
        if args[:2] == ("create", "-f") and b'"kind": "ConfigMap"' in kw["input"]:
            os.kill(os.getpid(), signal.SIGTERM)  # arrives right after the ConfigMap exists
            time.sleep(0.1)
        if args[:2] == ("get", "job"):
            return json.dumps({"metadata": {"uid": "u-1"}})
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    before = signal.signal(signal.SIGTERM, host._terminate_on_signal)
    try:
        with pytest.raises(SystemExit) as stopped:
            host.pregate(cfg, Path("/repo"), rules, COMMIT, TREE, verdicts=tmp_path / "state" / "verdicts")
    finally:
        signal.signal(signal.SIGTERM, before)
    assert stopped.value.code == 128 + signal.SIGTERM
    created = [c for c in calls if c[0] in ("create", "patch")]
    assert len(created) == 3  # ConfigMap, Job and the owner link all happened before the signal was acted on
    name = next(c[2] for c in calls if c[:2] == ("patch", "configmap"))
    assert calls[-2:] == [("delete", "job", name), ("delete", "configmap", name)]


def test_start_gate_deletes_both_objects_on_any_interruption(monkeypatch):
    calls = []

    def kubectl(*args, **kw):
        calls.append(args[:3])
        if args[:2] == ("create", "-f") and b'"kind": "Job"' in kw["input"]:
            raise KeyboardInterrupt
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    with pytest.raises(KeyboardInterrupt):
        host.start_gate(gate_config(), COMMIT, name=GATE)
    assert calls[-2:] == [("delete", "job", GATE), ("delete", "configmap", GATE)]


def test_concurrent_verdict_writes_use_unique_temp_files(tmp_path, monkeypatch):
    names = []
    real = host.tempfile.mkstemp

    def spy(**kw):
        fd, name = real(**kw)
        names.append(name)
        return fd, name
    monkeypatch.setattr(host.tempfile, "mkstemp", spy)
    host.write_verdict(tmp_path / "v", verdict())
    host.write_verdict(tmp_path / "v", verdict())
    assert len(set(names)) == 2 and not list((tmp_path / "v").glob("*.tmp"))


# ------------------------------------------------------------- deploy side: the merged commit, the deployed digests

MERGED = "e" * 40  # GitHub's rebase merge gives the scanned commit a new hash; the tree stays the same
RESTAMPED = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "3" * 64
PA = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "4" * 64


@pytest.fixture
def deploy(tmp_path, monkeypatch):
    """A deploy of MERGED (same tree as the scanned COMMIT) whose images are the re-stamped service image and the
    analysis image -- never the preview digest the pre-gate scanned. kubectl is a small in-memory Job store."""
    import hosta_restamp_image
    cfg, rules = tmp_path / "security.json", tmp_path / "rules"
    cfg.write_text(json.dumps(gate_config()))
    rules.mkdir()
    (rules / "java.yaml").write_text("rules")
    trees = {MERGED: TREE, COMMIT: TREE}
    monkeypatch.setattr(hosta_restamp_image, "git_tree", lambda repo, commit: trees[commit])
    verdicts = tmp_path / "verdicts"
    jobs, calls = {}, []

    def kubectl(*args, **kw):
        calls.append(args)
        if args[:2] == ("create", "-f"):
            obj = json.loads(kw["input"])
            if obj["kind"] == "Job":
                obj.setdefault("metadata", {}).setdefault("uid", "u-" + obj["metadata"]["name"])
                obj["status"] = {"conditions": [{"type": "Complete", "status": "True"}]}
                jobs[obj["metadata"]["name"]] = obj
            return ""
        if args[:2] == ("get", "job"):
            return json.dumps(jobs[args[2]])
        if args[0] == "logs":
            return json.dumps(summary()) if args[-1] == "summary" else ""
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    published = []
    # What the shared /source/rules holds once publish_rules ran, and how many Jobs existed at that moment.
    monkeypatch.setattr(host, "publish_rules", lambda config, rules: published.append(
        ({p.name: p.read_text() for p in Path(rules).iterdir()}, len(jobs))))

    def store(tree=TREE, policy=None, created=NOW):
        policy = policy or sv.policy_digest(cfg, rules, host.HERE)
        host.write_verdict(verdicts, sv.make_verdict(COMMIT, tree, policy, [IMG], summary(), lambda: created))
    return dict(cfg=cfg, rules=rules, verdicts=verdicts, jobs=jobs, calls=calls, store=store, trees=trees,
                published=published)


def deploy_start(d, now=NOW + 60):
    return host.start_deploy_gate(gate_config(), MERGED, d["cfg"], Path("/opt/nexus"), d["rules"], d["verdicts"],
                                  now=lambda: now)


def deploy_gate(d, name, now=NOW + 120):
    return host.gate(gate_config(), [RESTAMPED, PA], MERGED, name, d["cfg"], Path("/opt/nexus"), d["rules"],
                     d["verdicts"], now=lambda: now)


def stages(job_manifest):
    return [c["name"] for c in job_manifest["spec"]["template"]["spec"]["initContainers"]]


def test_a_pass_for_the_same_tree_skips_only_the_tree_scans_of_the_merged_commit(deploy, capsys):
    deploy["store"]()
    name = deploy_start(deploy)
    job_manifest = deploy["jobs"][name]
    expected_policy = sv.policy_digest(deploy["cfg"], deploy["rules"], host.HERE)
    assert job_manifest["metadata"]["annotations"] == {
        "nexus/commit": MERGED, host.SOURCE_REUSED: TREE, host.SOURCE_REUSED_POLICY: expected_policy}
    # The Git history and the images are scanned; semgrep, gitleaks over the tree and trivy fs/config are not.
    assert stages(job_manifest) == ["prepare", "history", "await-image", "trivy-image"]
    assert {"name": "TREE_SCANS_REUSED", "value": "1"} in job_manifest["spec"]["template"]["spec"]["initContainers"][0]["env"]
    assert json.loads(capsys.readouterr().err) == {"security_gate_source_reuse": True,
                                                   "why": "tree, policy and gate day match the pre-merge source scan"}
    assert deploy_gate(deploy, name)["passed"] is True
    out = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert out[0] == {"security_gate_source": "reused", "why": "tree, policy and gate day match the pre-merge source scan"}
    assert out[1]["passed"] is True
    # The Job is asked to scan EXACTLY the deployed digests (not the preview digest in the verdict).
    patch = next(c for c in deploy["calls"] if c[:2] == ("patch", "configmap") and "request" in c[-1])
    request = json.loads(json.loads(patch[-1])["data"]["request"])
    assert request == {"image": RESTAMPED, "commit": MERGED, "images": [RESTAMPED, PA]}
    assert len(deploy["jobs"]) == 1


@pytest.mark.parametrize("change,why", [
    ("none", "no pre-merge verdict for this tree"),
    ("tree", "no pre-merge verdict for this tree"),
    ("policy", "gate policy"),
    ("stale", "stale"),
])
def test_without_a_matching_pass_the_deploy_job_is_the_full_gate(deploy, capsys, change, why):
    if change == "tree":
        deploy["store"](tree=OTHER)
    elif change == "policy":
        deploy["store"](policy="e" * 64)
    elif change == "stale":
        deploy["store"](created=NOW - sv.MAX_AGE_S)
    name = deploy_start(deploy)
    job_manifest = deploy["jobs"][name]
    assert host.SOURCE_REUSED not in job_manifest["metadata"]["annotations"]
    assert stages(job_manifest) == ["prepare", "semgrep", "gitleaks", "history", "trivy", "await-image", "trivy-image"]
    decision = json.loads(capsys.readouterr().err)
    assert decision["security_gate_source_reuse"] is False and why in decision["why"]


@pytest.mark.parametrize("later", ["policy", "config", "midnight", "gone"])
def test_a_verdict_that_stops_holding_while_the_job_ran_means_a_full_rescan(deploy, capsys, later):
    # 20:59:00 UTC is 23:59:00 in the gate's zone: the verdict and the Job start before midnight, the Job ends after it.
    scanned = NOW - NOW % 86400 + 20 * 3600 + 59 * 60
    deploy["store"](created=scanned)
    name = deploy_start(deploy, now=scanned + 30)
    assert json.loads(capsys.readouterr().err)["security_gate_source_reuse"] is True
    now = scanned + 50
    upgraded = gate_config()
    if later == "policy":
        (deploy["rules"] / "java.yaml").write_text("rules changed by an operator")
    elif later == "config":
        # An operator re-pins a scanner (mirror_security_images.py) while the reduced Job runs.
        upgraded["images"]["semgrep"] = upgraded["images"]["semgrep"].split("@")[0] + "@sha256:" + "b" * 64
        deploy["cfg"].write_text(json.dumps(upgraded))
    elif later == "midnight":
        now = scanned + 120
    else:
        for path in deploy["verdicts"].iterdir():
            path.unlink()
    assert deploy_gate(deploy, name, now=now)["passed"] is True
    out = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert out[0]["security_gate_source"] == "rescanned"
    assert {"policy": "gate policy", "config": "gate policy", "midnight": "gate day changed",
            "gone": "no pre-merge verdict"}[later] in out[0]["why"]
    # A second, FULL Job scanned the source and every deployed digest.
    assert len(deploy["jobs"]) == 2
    full = next(j for n, j in deploy["jobs"].items() if n != name)
    assert stages(full) == ["prepare", "semgrep", "gitleaks", "history", "trivy"]
    env = {e["name"]: e["value"] for e in full["spec"]["template"]["spec"]["initContainers"][0]["env"]}
    assert env["GATE_IMAGE"] == RESTAMPED + " " + PA and "TREE_SCANS_REUSED" not in env
    # ... with the merged commit's tools/security from the source snapshot, never the installed ConfigMap ...
    pod = full["spec"]["template"]["spec"]
    assert pod["volumes"][0] == {"name": "scripts",
                                 "persistentVolumeClaim": {"claimName": "security-source", "readOnly": True}}
    assert not any(v.get("configMap", {}).get("name") == "security-scripts" for v in pod["volumes"])
    for c in pod["initContainers"] + pod["containers"]:
        assert next(m for m in c["volumeMounts"] if m["name"] == "scripts")["subPath"] == MERGED + "/tools/security"
    # ... and the shared rules were published again from the host's CURRENT offline rules before that Job existed.
    expected = "rules changed by an operator" if later == "policy" else "rules"
    assert deploy["published"] == [({"java.yaml": expected}, 1)]
    assert {"security_gate_rules_refreshed": True} in out
    # ... and that Job runs with the CURRENT security.json, never the one read when the deploy gate started.
    assert {"security_gate_config_reloaded": True} in out
    images = {c["name"]: c["image"] for c in pod["initContainers"] + pod["containers"]}
    assert images["semgrep"] == upgraded["images"]["semgrep"]
    if later == "config":
        assert images["semgrep"] != gate_config()["images"]["semgrep"]


@pytest.mark.parametrize("broken", ["deleted", "unpinned", "not json"])
def test_a_configuration_broken_while_the_job_ran_refuses_the_rollout(deploy, capsys, broken):
    # A security.json deleted or made invalid during the reduced Job invalidates the verdict; the fallback rescan must
    # fail closed on it, never run with the configuration read when the deploy gate started.
    deploy["store"]()
    name = deploy_start(deploy)
    if broken == "deleted":
        deploy["cfg"].unlink()
    elif broken == "unpinned":
        bad = gate_config()
        bad["images"]["trivy"] = bad["images"]["trivy"].split("@")[0] + ":latest"
        deploy["cfg"].write_text(json.dumps(bad))
    else:
        deploy["cfg"].write_text("{")
    with pytest.raises((OSError, ValueError)):
        deploy_gate(deploy, name)
    assert len(deploy["jobs"]) == 1 and deploy["published"] == []  # no full Job, no rules published


def test_a_reduced_job_without_the_inputs_to_recheck_it_never_passes_alone(deploy, capsys):
    deploy["store"]()
    name = deploy_start(deploy)
    host.gate(gate_config(), [RESTAMPED], MERGED, name)  # an older run_build.sh: no --repo/--rules on `gate`
    out = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert out[0]["security_gate_source"] == "rescanned" and len(deploy["jobs"]) == 2
    # Nothing to refresh from: the full rescan judges with the rules the deploy's snapshot published (no reuse).
    assert deploy["published"] == [] and {"security_gate_rules_refreshed": False, "why": "no rules given"} in out


def test_a_failed_rules_refresh_refuses_the_rollout(deploy, monkeypatch):
    deploy["store"]()
    name = deploy_start(deploy)
    (deploy["rules"] / "java.yaml").write_text("rules changed by an operator")

    def broken(config, rules):
        raise RuntimeError("security host command failed: kubectl")
    monkeypatch.setattr(host, "publish_rules", broken)
    with pytest.raises(RuntimeError):
        deploy_gate(deploy, name)
    assert len(deploy["jobs"]) == 1  # no full Job judged with the old rules


def test_the_rules_refresh_holds_the_configuration_lock(deploy, monkeypatch):
    import fcntl
    deploy["store"]()
    name = deploy_start(deploy)
    (deploy["rules"] / "java.yaml").write_text("rules changed by an operator")
    held = []

    def publish(config, rules):
        with deploy["cfg"].with_suffix(".lock").open("w") as other:
            try:
                fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                held.append(False)
            except BlockingIOError:
                held.append(True)
    monkeypatch.setattr(host, "publish_rules", publish)
    deploy_gate(deploy, name)
    assert held == [True]


def test_re_pinned_policy_with_a_newer_verdict_still_rescans_a_reduced_job(deploy, capsys):
    """If security.json is re-pinned while a reduced Job runs under old scanner digests and another pre-gate writes a
    verdict under the new policy before gate() finishes, gate() must still rescan because the reduced Job's bound
    SOURCE_REUSED_POLICY no longer matches live policy."""
    deploy["store"]()
    name = deploy_start(deploy)
    capsys.readouterr()
    upgraded = gate_config()
    upgraded["images"]["trivy"] = upgraded["images"]["trivy"].split("@")[0] + "@sha256:" + "c" * 64
    deploy["cfg"].write_text(json.dumps(upgraded))
    # Another pre-gate finishes under the upgraded policy and overwrites the verdict for TREE.
    deploy["store"](created=NOW + 90)
    assert deploy_gate(deploy, name, now=NOW + 120)["passed"] is True
    out = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert out[0] == {"security_gate_source": "rescanned",
                      "why": "gate policy changed since this deploy Job was started"}
    assert len(deploy["jobs"]) == 2
    full = next(j for n, j in deploy["jobs"].items() if n != name)
    images = {c["name"]: c["image"] for c in full["spec"]["template"]["spec"]["initContainers"]
              + full["spec"]["template"]["spec"]["containers"]}
    assert images["trivy"] == upgraded["images"]["trivy"]


def test_a_reduced_job_missing_policy_annotation_rescans_fail_closed(deploy, capsys):
    deploy["store"]()
    name = deploy_start(deploy)
    del deploy["jobs"][name]["metadata"]["annotations"][host.SOURCE_REUSED_POLICY]
    capsys.readouterr()
    assert deploy_gate(deploy, name)["passed"] is True
    out = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert out[0]["security_gate_source"] == "rescanned" and len(deploy["jobs"]) == 2


def test_start_deploy_gate_uses_its_config_bytes_without_rereading_security_json(deploy):
    """Passing config_bytes binds both the Job manifest and its SOURCE_REUSED_POLICY annotation to that single read."""
    old_bytes = deploy["cfg"].read_bytes()
    old_policy = sv.policy_digest(old_bytes, deploy["rules"], host.HERE)
    deploy["store"](policy=old_policy)
    upgraded = gate_config()
    upgraded["images"]["trivy"] = upgraded["images"]["trivy"].split("@")[0] + "@sha256:" + "d" * 64
    deploy["cfg"].write_text(json.dumps(upgraded))
    name = host.start_deploy_gate(json.loads(old_bytes), MERGED, deploy["cfg"], Path("/opt/nexus"),
                                  deploy["rules"], deploy["verdicts"], now=lambda: NOW + 60, config_bytes=old_bytes)
    assert deploy["jobs"][name]["metadata"]["annotations"][host.SOURCE_REUSED_POLICY] == old_policy


def test_a_failing_reduced_job_refuses_the_rollout_and_prints_counts_summary(deploy, capsys, monkeypatch):
    deploy["store"]()
    name = deploy_start(deploy)
    capsys.readouterr()
    deploy["jobs"][name]["status"] = {"conditions": [{"type": "Failed", "status": "True"}]}
    real_kubectl = host.kubectl

    def failing_summary(*args, **kw):
        if args[:1] == ("logs",) and args[-1] == "summary":
            return json.dumps(summary(blocking_new=1))
        return real_kubectl(*args, **kw)
    monkeypatch.setattr(host, "kubectl", failing_summary)
    with pytest.raises(RuntimeError, match="refused rollout"):
        deploy_gate(deploy, name)
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert len(lines) == 1 and lines[0]["passed"] is False and lines[0]["blocking"] == 1


def test_source_loader_bounds_kubectl_delete_timeout(monkeypatch):
    recorded = []

    def kubectl(*args, **kw):
        recorded.append((args, kw))
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    with host._source_loader(gate_config()):
        pass
    deletes = [(args, kw) for args, kw in recorded if args[:3] == LOADER_DELETE]
    assert len(deletes) == 2
    assert all(kw.get("timeout") == 60 for _, kw in deletes)


# ------------------------------------------------------------ the Job: deployed scripts, staged source, budgets

def test_every_gate_job_runs_the_snapshot_scripts_never_the_installed_configmap():
    """The unsplit gate (the deploy's fallback full rescan, a manual `gate`) mounts <commit>/tools/security as well:
    run_build.sh never refreshes ConfigMap/security-scripts. The daily/DAST CronJobs keep the installed copy."""
    pod = manifests.job(gate_config(), "gate", [IMG, IMG2], COMMIT)["spec"]["template"]["spec"]
    assert pod["volumes"][0] == {"name": "scripts",
                                 "persistentVolumeClaim": {"claimName": "security-source", "readOnly": True}}
    for c in pod["initContainers"] + pod["containers"]:
        assert next(m for m in c["volumeMounts"] if m["name"] == "scripts")["subPath"] == COMMIT + "/tools/security"
        assert next(m for m in c["volumeMounts"] if m["mountPath"] == "/rules")["subPath"] == "rules"
    assert not any(v["name"] == "gate-image" for v in pod["volumes"])
    for mode in ("daily", "dast"):
        daily = manifests.job(gate_config(), mode)["spec"]["template"]["spec"]
        assert daily["volumes"][0] == {"name": "scripts", "configMap": {"name": "security-scripts"}}


def test_pregate_job_never_mounts_the_candidates_scripts_or_the_shared_rules():
    tools = "pregate-tools/" + "9" * 64
    manifest = manifests.job(gate_config(), "gate", commit=COMMIT, gate_name="security-gate-" + "a" * 12,
                             pregate_tools=tools, await_timeout=7500, deadline=7500 + 3600)
    pod = manifest["spec"]["template"]["spec"]
    for c in pod["initContainers"] + pod["containers"]:
        mount = next(m for m in c["volumeMounts"] if m["name"] == "scripts")
        assert mount["subPath"] == tools and not mount["subPath"].startswith(COMMIT)
        rules = next(m for m in c["volumeMounts"] if m["mountPath"] == "/rules")
        assert rules["subPath"] == tools + "/rules"
    prepare = {e["name"]: e["value"] for e in pod["initContainers"][0]["env"]}
    assert prepare["SOURCE_COMMIT"] == prepare["EXPECTED_COMMIT"] == COMMIT
    waiting = next(c for c in pod["initContainers"] if c["name"] == "await-image")
    assert {"name": "AWAIT_IMAGE_TIMEOUT_S", "value": "7500"} in waiting["env"]
    assert manifest["spec"]["activeDeadlineSeconds"] == 11100
    for bad in (dict(pregate_tools=COMMIT + "/tools/security"), dict(pregate_tools="pregate-tools/../x"),
                dict(pregate_tools=tools, source_reused=True)):
        with pytest.raises(ValueError):
            manifests.job(gate_config(), "gate", commit=COMMIT, gate_name="security-gate-" + "a" * 12, **bad)
    with pytest.raises(ValueError):
        manifests.job(gate_config(), "gate", [IMG], commit=COMMIT, source_reused=True)  # never without the split gate
    # The deploy gate is unchanged: its own commit's scripts and the shared rules, one hour.
    deploy_job = manifests.job(gate_config(), "gate", commit=COMMIT, gate_name="security-gate-" + "a" * 12)
    assert deploy_job["spec"]["activeDeadlineSeconds"] == 3600
    assert all(e["name"] != "SOURCE_COMMIT" for e in deploy_job["spec"]["template"]["spec"]["initContainers"][0]["env"])
    mounts = deploy_job["spec"]["template"]["spec"]["initContainers"][0]["volumeMounts"]
    assert next(m for m in mounts if m["mountPath"] == "/rules")["subPath"] == "rules"


def staged_source(tmp_path, monkeypatch):
    import security_run as runner
    root, work, source = (tmp_path / n for n in ("reports", "work", "source"))
    for path in (root, work, source):
        path.mkdir()
    for name, value in (("ROOT", root), ("WORK", work), ("SOURCE", source)):
        monkeypatch.setattr(runner, name, value)
    monkeypatch.setattr(runner.os, "umask", lambda mask: None)
    monkeypatch.setenv("SCAN_MODE", "gate")
    monkeypatch.setenv("EXPECTED_COMMIT", COMMIT)
    (source / "current").write_text(OTHER)  # the deployed release, never the candidate
    (source / COMMIT / ".git").mkdir(parents=True)
    return runner, work


def test_prepare_reads_the_staged_candidate_by_name_without_the_deployed_inventory(tmp_path, monkeypatch):
    runner, work = staged_source(tmp_path, monkeypatch)
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    monkeypatch.setenv("SOURCE_COMMIT", COMMIT)
    runner.prepare()  # no images.json at all, `current` on another commit
    assert (work / "source-commit").read_text() == COMMIT


@pytest.mark.parametrize("env", [dict(SOURCE_COMMIT=OTHER, WAIT_FOR_IMAGE="1"), dict(SOURCE_COMMIT=COMMIT)])
def test_prepare_refuses_a_mismatched_or_unsplit_staged_source(tmp_path, monkeypatch, env):
    runner, work = staged_source(tmp_path, monkeypatch)
    (tmp_path / "source" / OTHER / ".git").mkdir(parents=True)
    monkeypatch.setenv("GATE_IMAGE", IMG)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    with pytest.raises(ValueError):
        runner.prepare()


def test_prepare_of_a_reduced_deploy_gate_lists_history_and_images_only(tmp_path, monkeypatch):
    runner, work = staged_source(tmp_path, monkeypatch)
    (tmp_path / "source" / "current").write_text(COMMIT)
    (tmp_path / "source" / "images.json").write_text(json.dumps(
        dict(images=[IMG], generated_at=__import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat())))
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    monkeypatch.setenv("TREE_SCANS_REUSED", "1")
    runner.prepare()
    assert json.loads((work / "manifest.json").read_text()) == [dict(tool="gitleaks", file="gitleaks-history.json")]
    output = Path((work / "output").read_text())
    assert output.name.startswith("gatereduced-")  # never "gate-*": no trend evidence for a full gate


def test_reduced_gate_prepare_await_image_and_finish_lifecycle(tmp_path, monkeypatch, capsys):
    """End-to-end reduced deploy gate inside the container (TREE_SCANS_REUSED=1): prepare -> await_image -> finish
    produces a gatereduced-* output, copies every SBOM, emits no notification, and is excluded from trend both ways."""
    from datetime import date, datetime, timedelta, timezone
    runner, work = staged_source(tmp_path, monkeypatch)
    source, root = tmp_path / "source", tmp_path / "reports"
    (source / "current").write_text(COMMIT)
    (source / "images.json").write_text(json.dumps(
        dict(images=[IMG], generated_at=datetime.now(timezone.utc).isoformat())))
    (source / COMMIT / "security").mkdir()
    (source / COMMIT / "security" / "baseline.yaml").write_text('{"accepted":[]}')
    # Yesterday's full gate had a semgrep finding: the reduced gate has no semgrep stage and must not count it as fixed.
    today = datetime.now(timezone.utc).astimezone().date()
    prev_day = (today - timedelta(days=1)).isoformat()
    prev_full = root / prev_day / "gate-previous"
    prev_full.mkdir(parents=True)
    (prev_full / "summary.json").write_text(json.dumps(summary()))
    (prev_full / "findings.json").write_text(json.dumps([
        security_summary.finding("semgrep", "old-rule", "src/A.java:1", "HIGH")]))

    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    monkeypatch.setenv("TREE_SCANS_REUSED", "1")
    runner.prepare()
    output = Path((work / "output").read_text())
    assert output.name.startswith("gatereduced-")
    request = tmp_path / "request"
    request.write_text(json.dumps(dict(commit=COMMIT, image=RESTAMPED, images=[RESTAMPED, PA])))
    runner.await_image(image_file=request)
    manifest = json.loads((work / "manifest.json").read_text())
    assert [m["file"] for m in manifest] == ["gitleaks-history.json", "trivy-image-0.json", "trivy-image-1.json"]
    for item in manifest:
        payload = [] if item["tool"] == "gitleaks" else {"SchemaVersion": 2, "Results": []}
        (work / item["file"]).write_text(json.dumps(payload))
        (work / (item["file"] + ".exit")).write_text("0")
    for idx in (0, 1):
        (work / f"sbom-{idx}.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
        (work / f"sbom-{idx}.json.exit").write_text("0")
    assert runner.finish() == 0
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert result["passed"] is True and result["blocking"] == 0 and result["scan_errors"] == 0
    assert result["counts"]["semgrep"]["HIGH"]["fixed"] == 0
    assert (output / "sbom-0.json").is_file() and (output / "sbom-1.json").is_file()
    assert not (root / "notifications").exists()

    # A reduced gate is also never trend evidence for a later full gate.
    shutil.rmtree(prev_full)
    prev_reduced = root / prev_day / output.name
    shutil.move(str(output), str(prev_reduced))
    (prev_reduced / "findings.json").write_text(json.dumps([
        security_summary.finding("trivy", "CVE-2026-0001", "img: pkg", "HIGH")]))
    monkeypatch.delenv("WAIT_FOR_IMAGE")
    monkeypatch.delenv("TREE_SCANS_REUSED")
    monkeypatch.setenv("GATE_IMAGE", RESTAMPED)
    runner.prepare()
    full_manifest = json.loads((work / "manifest.json").read_text())
    for item in full_manifest:
        payload = {"results": []} if item["tool"] == "semgrep" else [] if item["tool"] == "gitleaks" else {
            "SchemaVersion": 2, "Results": []}
        (work / item["file"]).write_text(json.dumps(payload))
        (work / (item["file"] + ".exit")).write_text("0")
    (work / "sbom-0.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
    (work / "sbom-0.json.exit").write_text("0")
    assert runner.finish() == 0
    full_result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert full_result["counts"]["trivy"]["HIGH"]["fixed"] == 0


@pytest.mark.parametrize("missing", ["gitleaks-history.json", "trivy-image-0.json", "sbom-0.json"])
def test_reduced_gate_finish_fails_closed_when_any_report_or_sbom_is_missing(tmp_path, monkeypatch, capsys, missing):
    from datetime import datetime, timezone
    runner, work = staged_source(tmp_path, monkeypatch)
    source = tmp_path / "source"
    (source / "current").write_text(COMMIT)
    (source / "images.json").write_text(json.dumps(
        dict(images=[IMG], generated_at=datetime.now(timezone.utc).isoformat())))
    (source / COMMIT / "security").mkdir()
    (source / COMMIT / "security" / "baseline.yaml").write_text('{"accepted":[]}')
    monkeypatch.setenv("WAIT_FOR_IMAGE", "1")
    monkeypatch.setenv("TREE_SCANS_REUSED", "1")
    runner.prepare()
    request = tmp_path / "request"
    request.write_text(json.dumps(dict(commit=COMMIT, image=RESTAMPED)))
    runner.await_image(image_file=request)
    for item in json.loads((work / "manifest.json").read_text()):
        payload = [] if item["tool"] == "gitleaks" else {"SchemaVersion": 2, "Results": []}
        (work / item["file"]).write_text(json.dumps(payload))
        (work / (item["file"] + ".exit")).write_text("0")
    (work / "sbom-0.json").write_text('{"bomFormat":"CycloneDX","components":[]}')
    (work / "sbom-0.json.exit").write_text("0")
    (work / missing).unlink()
    assert runner.finish() == 1
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert result["passed"] is False and result["scan_errors"] >= 1


@pytest.mark.parametrize("env", [dict(), dict(WAIT_FOR_IMAGE="1", SOURCE_COMMIT=COMMIT)])
def test_prepare_refuses_source_reuse_outside_the_split_deploy_gate(tmp_path, monkeypatch, env):
    runner, work = staged_source(tmp_path, monkeypatch)
    monkeypatch.setenv("GATE_IMAGE", IMG)
    monkeypatch.setenv("TREE_SCANS_REUSED", "1")
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    with pytest.raises(ValueError, match="source reuse"):
        runner.prepare()


def test_await_image_takes_its_budget_from_the_job(tmp_path, monkeypatch):
    import security_run as runner
    monkeypatch.setattr(runner, "WORK", tmp_path)
    (tmp_path / "source-start").write_text("0")
    monkeypatch.setenv("AWAIT_IMAGE_TIMEOUT_S", "7500")
    clock = Clock()
    with pytest.raises(ValueError, match="timed out"):
        runner.await_image(sleep=clock.sleep, monotonic=clock.monotonic, image_file=tmp_path / "missing")
    assert 7500 <= clock.t <= 7501


def test_unpublished_snapshot_stages_deployed_scripts_and_rules_and_leaves_current_alone(tmp_path, monkeypatch):
    """snapshot(publish=False) against a real Git repository; kubectl is doubled and the loader's shell and cleanup
    scripts run against a local stand-in for the /source volume."""
    repo = tmp_path / "repo"
    repo.mkdir()
    git = lambda *a: subprocess.run(["git", "-C", str(repo), *a], check=True, capture_output=True, text=True).stdout
    git("init", "-q")
    (repo / "a.txt").write_text("x")
    git("add", ".")
    git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-qm", "c")
    commit = git("rev-parse", "HEAD").strip()
    rules = tmp_path / "rules"
    rules.mkdir()
    for pack in ("java", "typescript", "owasp-top-ten", "secrets"):
        (rules / (pack + ".yaml")).write_text("rules: []")
    volume = tmp_path / "volume"
    (volume / "incoming" / ("f" * 40)).mkdir(parents=True)  # left by a pre-gate cancelled mid-extract
    (volume / "incoming" / ("f" * 40) / "half.txt").write_text("partial")
    (volume / "images.json").write_text("deployed")
    (volume / "rules").mkdir()
    (volume / "rules" / "java.yaml").write_text("the deployed release's rules")
    real_run = subprocess.run
    shells = []

    def fake(argv, **kw):
        if argv[0] != "kubectl":
            return real_run(argv, **kw)
        if "get" in argv and "deployments" in argv:
            out = json.dumps({"items": [{"spec": {"template": {"spec": {"containers": [{"image": IMG}]}}}}]})
            return subprocess.CompletedProcess(argv, 0, out.encode(), b"")
        if "exec" in argv and "-i" in argv:
            assert argv[-1].startswith("rm -rf /source/incoming && mkdir -p /source/incoming && tar ")
            shutil.rmtree(volume / "incoming", ignore_errors=True)
            (volume / "incoming").mkdir()
            with tarfile.open(fileobj=kw["stdin"], mode="r|") as archive:
                archive.extractall(volume / "incoming", filter="data")
            return subprocess.CompletedProcess(argv, 0, None, None)
        if "exec" in argv:
            shells.append(argv[argv.index("--") + 1:])
        return subprocess.CompletedProcess(argv, 0, b"", b"")
    monkeypatch.setattr(host.subprocess, "run", fake)
    monkeypatch.setenv("COPYFILE_DISABLE", "1")  # macOS bsdtar: no AppleDouble members (GNU tar on the host has none)
    config_bytes = json.dumps(gate_config()).encode()
    with pytest.raises(ValueError):
        host.snapshot(gate_config(), repo, rules, publish=False)  # never without the exact configuration bytes
    got_commit, tools_id, policy = host.snapshot(gate_config(), repo, rules, publish=False, config_bytes=config_bytes)
    assert got_commit == commit
    loader, cleanup = shells[-2:]
    script = loader[-1].replace("cd /source", "cd " + str(volume))
    real_run(["sh", "-c", script], check=True)
    tools = volume / "pregate-tools" / tools_id
    assert sorted(p.name for p in tools.iterdir()) == sorted([*sv.SCANNER_SCRIPTS, "rules"])
    assert sv.staged_tools_digest(tools) == tools_id
    # The policy is that of the staged copy -- and equals what the deploy computes from the live directories.
    assert policy == sv.policy_digest(config_bytes, tools / "rules", tools)
    assert policy == sv.policy_digest(config_bytes, rules, host.HERE)
    assert (volume / commit / "a.txt").read_text() == "x"
    assert not (volume / "current").exists() and (volume / "images.json").read_text() == "deployed"
    assert (volume / "rules" / "java.yaml").read_text() == "the deployed release's rules"
    assert not (volume / "incoming" / "pregate-tools").exists()
    assert not (volume / "incoming" / ("f" * 40)).exists()  # a cancelled run's leftovers do not outlive the next one
    # The two-day cleanup tolerates a volume without a published `current` (first ever snapshot).
    real_run([sys.executable, "-c", cleanup[-1].replace('Path("/source")', "Path(%r)" % str(volume))], check=True)


def loaded_manifests(monkeypatch, text="security_manifests.py"):
    """Pretend this process rendered its Jobs with a security_manifests.py holding `text` (scanner_dir's content)."""
    import hashlib
    monkeypatch.setattr(host, "LOADED_MANIFESTS", hashlib.sha256(text.encode()).hexdigest())


def test_the_loaded_manifest_module_is_what_the_driver_imported():
    import hashlib
    assert host.LOADED_MANIFESTS == hashlib.sha256((host.HERE / "security_manifests.py").read_bytes()).hexdigest()


def test_staging_refuses_manifests_the_running_driver_did_not_render_with(tmp_path, monkeypatch):
    """A `git pull` of /opt/nexus after this pre-gate started: the Job is rendered by the manifests in memory, so a
    verdict whose policy hashed the newer file would claim a TREE_STAGES its Job never ran."""
    live = scanner_dir(tmp_path / "live", rules=False)
    rules = scanner_dir(tmp_path / "cfg", rules=True) / "rules"
    monkeypatch.setattr(host, "HERE", live)
    loaded_manifests(monkeypatch, "the manifests this driver imported")
    root = tmp_path / "root"
    root.mkdir()
    with pytest.raises(RuntimeError, match="manifests changed"):
        host._stage_pregate_tools(root, b"{}", rules, sleep=lambda s: None)
    assert list(root.iterdir()) == []
    loaded_manifests(monkeypatch)
    tools_id, _ = host._stage_pregate_tools(root, b"{}", rules, sleep=lambda s: None)
    assert (root / "pregate-tools" / tools_id / "security_manifests.py").read_text() == "security_manifests.py"


def test_staging_retries_while_a_concurrent_pull_rewrites_the_scripts(tmp_path, monkeypatch):
    live = scanner_dir(tmp_path / "live", rules=False)
    loaded_manifests(monkeypatch)
    rules = scanner_dir(tmp_path / "cfg", rules=True) / "rules"
    monkeypatch.setattr(host, "HERE", live)
    real_copy = host.shutil.copyfile
    copies = []

    def racing_copy(src, dst, **kw):
        if Path(src).parent == live:
            copies.append(src)
            if len(copies) == 2:  # a `git pull` lands in the middle of the first attempt
                (live / "security_summary.py").write_text("pulled version")
        return real_copy(src, dst, **kw)
    monkeypatch.setattr(host.shutil, "copyfile", racing_copy)
    root = tmp_path / "root"
    root.mkdir()
    tools_id, policy = host._stage_pregate_tools(root, b"{}", rules, sleep=lambda s: None)
    staged = root / "pregate-tools" / tools_id
    assert (staged / "security_summary.py").read_text() == "pulled version"
    assert len(copies) == 2 * len(sv.SCANNER_SCRIPTS)
    assert policy == sv.policy_digest(b"{}", rules, live) == sv.policy_digest(b"{}", staged / "rules", staged)
    # A change AFTER staging cannot alter what the Job mounts or what the verdict's policy says.
    (live / "security_scan.sh").write_text("later pull")
    assert sv.staged_tools_digest(staged) == tools_id and policy == sv.policy_digest(b"{}", staged / "rules", staged)
    assert [p.name for p in root.iterdir()] == ["pregate-tools"]


def loader_double(monkeypatch, on=None):
    """host.run answers the git/deployment reads; host.kubectl records calls and can act (e.g. signal) on one."""
    deployments = json.dumps({"items": [{"spec": {"template": {"spec": {"containers": [{"image": IMG}]}}}}]})

    def run(argv, **kw):
        if argv[:1] == ["kubectl"]:
            return deployments
        return COMMIT + "\n" if "rev-parse" in argv else ""
    monkeypatch.setattr(host, "run", run)
    calls = []

    def kubectl(*args, **kw):
        calls.append(args[:3])
        if on is not None:
            on(args)
        return ""
    monkeypatch.setattr(host, "kubectl", kubectl)
    return calls


LOADER_DELETE = ("delete", "pod", host.LOADER)


@pytest.mark.parametrize("publish", [True, False])
def test_a_signal_while_the_loader_is_created_still_deletes_it(tmp_path, monkeypatch, publish):
    """The review's leak: cancellation while `kubectl apply` of the loader was in flight skipped its deletion, and the
    Succeeded pod then blocked every later snapshot."""
    def on(args):
        if args[:2] == ("apply", "-f"):
            os.kill(os.getpid(), signal.SIGTERM)  # arrives while the pod is being created
            time.sleep(0.1)
    calls = loader_double(monkeypatch, on)
    before = signal.signal(signal.SIGTERM, host._terminate_on_signal)
    try:
        with pytest.raises(SystemExit) as stopped:
            host.snapshot(gate_config(), tmp_path, tmp_path, publish=publish, config_bytes=b"{}")
    finally:
        signal.signal(signal.SIGTERM, before)
    assert stopped.value.code == 128 + signal.SIGTERM
    # A stale loader (a run killed between bytecodes) goes first; the one this run created is deleted after all.
    assert calls == [LOADER_DELETE, ("apply", "-f", "-"), LOADER_DELETE]


def test_a_signal_while_the_loader_is_deleted_lets_the_deletion_finish(tmp_path, monkeypatch):
    def on(args):
        if args[:2] == ("wait", "--for=condition=Ready"):
            raise RuntimeError("security host command failed: kubectl")
        if args[:3] == LOADER_DELETE and len(calls) > 1:
            os.kill(os.getpid(), signal.SIGTERM)
            time.sleep(0.1)
    calls = []
    recorded = loader_double(monkeypatch, lambda args: calls.append(args) or on(args))
    before = signal.signal(signal.SIGTERM, host._terminate_on_signal)
    try:
        with pytest.raises(SystemExit) as stopped:  # the deletion finished first, then the signal is acted on
            host.snapshot(gate_config(), tmp_path, tmp_path)
    finally:
        signal.signal(signal.SIGTERM, before)
    assert stopped.value.code == 128 + signal.SIGTERM
    assert recorded == [LOADER_DELETE, ("apply", "-f", "-"), ("wait", "--for=condition=Ready", "pod/" + host.LOADER),
                        LOADER_DELETE]


def test_publish_rules_replaces_the_shared_rules_through_a_fresh_incoming(tmp_path, monkeypatch):
    rules = scanner_dir(tmp_path / "cfg") / "rules"
    (rules / "java.yaml").write_text("the operator's new rules")
    volume = tmp_path / "volume"
    (volume / "incoming" / "rules").mkdir(parents=True)
    (volume / "incoming" / "rules" / "java.yaml").write_text("a cancelled run's leftover")
    (volume / "rules").mkdir()
    (volume / "rules" / "java.yaml").write_text("the snapshot's rules")
    real_run = subprocess.run
    shells = []

    def fake(argv, **kw):
        if argv[0] != "kubectl":
            return real_run(argv, **kw)
        if "exec" in argv and "-i" in argv:
            assert argv[-1].startswith("rm -rf /source/incoming && mkdir -p /source/incoming && tar ")
            shutil.rmtree(volume / "incoming")
            (volume / "incoming").mkdir()
            with tarfile.open(fileobj=kw["stdin"], mode="r|") as archive:
                archive.extractall(volume / "incoming", filter="data")
            return subprocess.CompletedProcess(argv, 0, None, None)
        if "exec" in argv:
            shells.append(argv[argv.index("--") + 1:])
        else:
            shells.append(tuple(argv[3:6]))
        return subprocess.CompletedProcess(argv, 0, b"", b"")
    monkeypatch.setattr(host.subprocess, "run", fake)
    monkeypatch.setenv("COPYFILE_DISABLE", "1")
    host.publish_rules(gate_config(), rules)
    script = next(sh for sh in shells if isinstance(sh, list))[-1].replace("cd /source", "cd " + str(volume))
    real_run(["sh", "-c", script], check=True)
    assert (volume / "rules" / "java.yaml").read_text() == "the operator's new rules"
    assert not (volume / "incoming" / "rules").exists()
    assert shells[0] == LOADER_DELETE and shells[-1] == LOADER_DELETE
    # A rule pack missing from the host cache is refused before anything is published.
    (rules / "secrets.yaml").unlink()
    with pytest.raises(ValueError):
        host.publish_rules(gate_config(), rules)
    assert (volume / "rules" / "java.yaml").read_text() == "the operator's new rules"


def test_staging_gives_up_when_the_scripts_never_settle(tmp_path, monkeypatch):
    live = scanner_dir(tmp_path / "live", rules=False)
    rules = scanner_dir(tmp_path / "cfg", rules=True) / "rules"
    monkeypatch.setattr(host, "HERE", live)
    real_copy = host.shutil.copyfile
    counter = iter(range(1000))
    monkeypatch.setattr(host.shutil, "copyfile",
                        lambda src, dst, **kw: (live / "security_run.py").write_text(str(next(counter)))
                        and real_copy(src, dst, **kw))
    root = tmp_path / "root"
    root.mkdir()
    with pytest.raises(RuntimeError, match="kept changing"):
        host._stage_pregate_tools(root, b"{}", rules, sleep=lambda s: None)
    assert list(root.iterdir()) == []


# ------------------------------------------------------------------- wrapper + remote side, real Git bundle

def git_repo(tmp_path, big=False):
    repo = tmp_path / "repo"
    repo.mkdir()
    run = lambda *a: subprocess.run(["git", "-C", str(repo), *a], check=True, capture_output=True, text=True).stdout
    run("init", "-q")
    (repo / "a.txt").write_text("synthetic")
    if big:  # larger than any pipe buffer: an undrained early answer would SIGPIPE the bundle writer
        (repo / "blob.bin").write_bytes(os.urandom(1 << 20))
    run("add", ".")
    run("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-qm", "c")
    return repo, run("rev-parse", "HEAD").strip(), run("rev-parse", "HEAD^{tree}").strip()


DRIVER = '''import json, os, signal, sys, time

def pregate(): pass
# accepts "--not-before"
args = dict(zip(sys.argv[2::2], sys.argv[3::2]))
open(os.environ["FAKE_DONE"] + ".argv", "w").write(json.dumps(sys.argv[1:]))
mode = os.environ.get("FAKE_MODE", "pass")
if mode == "slow":
    def stop(*a):
        open(os.environ["FAKE_DONE"], "w").write("job deleted")
        sys.exit(143)
    signal.signal(signal.SIGTERM, stop)
    open(os.environ["FAKE_DONE"] + ".pgid", "w").write(str(os.getpgid(0)))
    open(os.environ["FAKE_DONE"] + ".started", "w").write("1")
    time.sleep(60)
head = os.popen("git -C " + args["--repo"] + " rev-parse HEAD").read().strip()
if mode == "fail" or head != args["--commit"]:
    sys.exit(1)
commit = args["--commit"] if mode != "other" else "c" * 40
print("PREGATE: PASS " + commit + " " + args["--tree"])
print('{"passed": true, "scan_errors": 0, "blocking": 0}')
'''


# A stand-in for ssh. Default: run the remote command in place. FAKE_SSH_DETACH=1: like a real ssh session, the remote
# side runs in its OWN session (a ship-side kill never reaches it) and its stdout reaches the wrapper only through this
# relay; when the relay dies, the remote side learns it from a write into a closed pipe -- SIGPIPE, as with sshd.
SSH_STUB = '''#!/usr/bin/env bash
while [ "$#" -gt 2 ]; do shift; done
if [ "${FAKE_SSH_DETACH:-0}" != 1 ]; then exec bash -c "$2"; fi
exec python3 -c '
import os, signal, subprocess, sys
ignore = os.environ.get("FAKE_SSH_IGNORE_PIPE") == "1"
remote = subprocess.Popen(["bash", "-c", sys.argv[1]], start_new_session=True, stdout=subprocess.PIPE,
                          preexec_fn=(lambda: signal.signal(signal.SIGPIPE, signal.SIG_IGN)) if ignore else None)
open(sys.argv[2], "w").write(str(remote.pid))
for line in remote.stdout:
    sys.stdout.buffer.write(line)
    sys.stdout.flush()
sys.exit(remote.wait())
' "$2" "$FAKE_DONE.remote"
'''


@pytest.fixture
def wrapper_env(tmp_path):
    home, bindir = tmp_path / "home", tmp_path / "bin"
    (home / ".config" / "nexus").mkdir(parents=True)
    bindir.mkdir()
    (bindir / "ssh").write_text(SSH_STUB)
    (bindir / "ssh").chmod(0o755)
    driver = tmp_path / "driver.py"
    driver.write_text(DRIVER)
    env = dict(os.environ, HOME=str(home), PATH=str(bindir) + os.pathsep + os.environ["PATH"],
               NEXUS_HOST="synthetic-host", NEXUS_PREGATE_DRIVER=str(driver), NEXUS_PREGATE_HEARTBEAT_S="1",
               FAKE_DONE=str(tmp_path / "done"), GIT_CONFIG_GLOBAL=os.devnull)
    return dict(home=home, env=env, driver=driver, tmp=tmp_path)


def configure(w, config=True, marker=False):
    if config:
        (w["home"] / ".config" / "nexus" / "security.json").write_text("{}")
    if marker:
        (w["home"] / ".local" / "state" / "nexus-security").mkdir(parents=True, exist_ok=True)
        (w["home"] / ".local" / "state" / "nexus-security" / "pregate-enabled").write_text("")


def run_wrapper(w, repo, commit, tree, **env):
    return subprocess.run(["bash", str(WRAPPER), "--repo", str(repo), "--commit", commit, "--tree", tree],
                          env=dict(w["env"], **env), capture_output=True, text=True, timeout=120)


def test_bundle_round_trip_passes_and_removes_its_ref(wrapper_env):
    w = wrapper_env
    configure(w)
    repo, commit, tree = git_repo(w["tmp"])
    result = run_wrapper(w, repo, commit, tree)
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines()[0] == f"PASS {commit} {tree}"
    assert so._security_pregate_line(result.stdout, result.returncode) == f"PASS {commit} {tree}"
    assert subprocess.run(["git", "-C", str(repo), "for-each-ref", "refs/security-pregate"],
                          capture_output=True, text=True).stdout == ""


@pytest.mark.parametrize("config,marker,capable,expected", [
    (False, False, True, "NOT_CONFIGURED"),
    (False, True, True, "FAIL"),
    (True, False, False, "UNAVAILABLE"),
    (True, True, False, "FAIL"),
])
def test_bootstrap_answers_only_before_the_pregate_ever_ran_and_never_sigpipe(wrapper_env, config, marker, capable,
                                                                              expected):
    w = wrapper_env
    configure(w, config=config, marker=marker)
    if not capable:
        w["driver"].write_text("print('old tooling')\n")
    repo, commit, tree = git_repo(w["tmp"], big=True)
    result = run_wrapper(w, repo, commit, tree)
    assert result.returncode == 0  # the remote drained the bundle first: no SIGPIPE (rc 141)
    assert result.stdout.splitlines() == [expected]


@pytest.mark.parametrize("breakage", ["config-unreadable", "driver-unreadable", "driver-fails", "other-commit",
                                      "wrong-tree"])
def test_every_host_side_error_is_fail(wrapper_env, breakage):
    w = wrapper_env
    configure(w)
    repo, commit, tree = git_repo(w["tmp"])
    env = {}
    if breakage == "config-unreadable":
        (w["home"] / ".config" / "nexus" / "security.json").chmod(0)
    elif breakage == "driver-unreadable":
        w["driver"].chmod(0)
    elif breakage == "driver-fails":
        env["FAKE_MODE"] = "fail"
    elif breakage == "other-commit":
        env["FAKE_MODE"] = "other"
    try:
        result = run_wrapper(w, repo, commit, OTHER if breakage == "wrong-tree" else tree, **env)
    finally:
        (w["home"] / ".config" / "nexus" / "security.json").chmod(0o600)
        w["driver"].chmod(0o600)
    if os.geteuid() == 0 and breakage.endswith("unreadable"):
        pytest.skip("root reads mode-000 files")
    assert result.stdout.splitlines()[0] == "FAIL"
    assert so._security_pregate_line(result.stdout, result.returncode) == "FAIL"


def test_the_host_side_passes_its_session_start_only_to_a_driver_that_knows_it(wrapper_env):
    w = wrapper_env
    configure(w)
    repo, commit, tree = git_repo(w["tmp"])
    before = int(time.time())
    assert run_wrapper(w, repo, commit, tree).stdout.splitlines()[0] == f"PASS {commit} {tree}"
    argv = json.loads(Path(str(w["tmp"] / "done") + ".argv").read_text())
    assert before - 1 <= int(argv[argv.index("--not-before") + 1]) <= int(time.time())
    # An older installed driver (mid-rollout) is called exactly as before.
    w["driver"].write_text(DRIVER.replace('# accepts "--not-before"', ""))
    assert run_wrapper(w, repo, commit, tree).stdout.splitlines()[0] == f"PASS {commit} {tree}"
    assert "--not-before" not in json.loads(Path(str(w["tmp"] / "done") + ".argv").read_text())


@pytest.mark.parametrize("sigpipe", ["default", "ignored"])
def test_cancelling_the_ship_side_stops_the_host_driver_through_the_closed_session(wrapper_env, monkeypatch, sigpipe):
    """Production path: the ship kills only its own process group (bash, bundle, ssh). The host side is in another
    session; it notices the dead ssh session when its next heartbeat hits the closed pipe (SIGPIPE, or a write error
    when SIGPIPE was ignored on entry), stops the driver through its EXIT trap, and the driver deletes its Job. Without
    the heartbeat, the EXIT trap or the heartbeat's own exit on a write error this test fails."""
    w = wrapper_env
    configure(w)
    repo, commit, tree = git_repo(w["tmp"])
    for key, value in dict(w["env"], FAKE_MODE="slow", FAKE_SSH_DETACH="1",
                           FAKE_SSH_IGNORE_PIPE="1" if sigpipe == "ignored" else "0").items():
        monkeypatch.setenv(key, value)
    verdict_path = w["tmp"] / "state" / "v.txt"
    handle = so._start_security_pregate(repo, commit, tree, verdict_path)
    done = w["tmp"] / "done"
    started = Path(str(done) + ".started")
    deadline = time.monotonic() + 60
    while not started.exists() and time.monotonic() < deadline:
        time.sleep(0.1)
    assert started.exists()
    remote_pid = int(Path(str(done) + ".remote").read_text())
    # The host side is NOT in the group the ship kills: it can only learn of the cancel through the pipe.
    assert os.getpgid(remote_pid) != handle.proc.pid
    assert int(Path(str(done) + ".pgid").read_text()) != handle.proc.pid
    handle.cancel(grace=20)
    handle.join(30)
    assert verdict_path.read_text() == "FAIL (pre-merge security gate cancelled)\n"
    deadline = time.monotonic() + 20
    while not done.exists() and time.monotonic() < deadline:
        time.sleep(0.1)
    assert done.exists() and done.read_text() == "job deleted"
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            os.kill(remote_pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.1)
    with pytest.raises(ProcessLookupError):
        os.kill(remote_pid, 0)
    # The wrapper's EXIT trap deletes the run ref after its signal handler returns, in a process the ship does not
    # wait for: poll for the observable end state under a deadline instead of sampling it once.
    deadline = time.monotonic() + 20
    while True:
        refs = subprocess.run(["git", "-C", str(repo), "for-each-ref", "refs/security-pregate"],
                              capture_output=True, text=True).stdout
        if refs == "" or time.monotonic() >= deadline:
            break
        time.sleep(0.1)
    assert refs == ""


def test_remote_cleanup_disarms_signals_so_second_signal_never_leaks_work_dir(wrapper_env):
    """A second HUP/TERM while security_pregate_remote.sh is waiting for its driver in cleanup() must not abort
    cleanup before rm -rf "$work" removes the bundle and checkout."""
    w = wrapper_env
    configure(w)
    repo, commit, tree = git_repo(w["tmp"])
    done = w["tmp"] / "done"
    slow_driver = w["tmp"] / "slow_driver.py"
    slow_driver.write_text(
        'import os, signal, sys, time\n'
        'def pregate(): pass\n'
        '# accepts "--not-before"\n'
        'args = dict(zip(sys.argv[2::2], sys.argv[3::2]))\n'
        'def stop(*a):\n'
        '    open(os.environ["FAKE_DONE"] + ".cleaning", "w").write("1")\n'
        '    time.sleep(0.4)\n'
        '    open(os.environ["FAKE_DONE"], "w").write("job deleted")\n'
        '    sys.exit(143)\n'
        'signal.signal(signal.SIGTERM, stop)\n'
        'open(os.environ["FAKE_DONE"] + ".work", "w").write(os.path.dirname(args["--repo"]))\n'
        'open(os.environ["FAKE_DONE"] + ".started", "w").write("1")\n'
        'time.sleep(60)\n'
    )
    ref = "refs/security-pregate/test-" + commit
    subprocess.run(["git", "-C", str(repo), "update-ref", ref, commit], check=True)
    bundle = subprocess.run(["git", "-C", str(repo), "bundle", "create", "-", ref],
                            check=True, capture_output=True).stdout
    env = dict(w["env"], NEXUS_PREGATE_DRIVER=str(slow_driver))
    proc = subprocess.Popen(["bash", str(REMOTE), commit, tree],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    proc.stdin.write(bundle)
    proc.stdin.close()
    started = Path(str(done) + ".started")
    cleaning = Path(str(done) + ".cleaning")
    deadline = time.monotonic() + 30
    while not started.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    assert started.exists()
    work_dir = Path(Path(str(done) + ".work").read_text().strip())
    assert work_dir.is_dir()
    proc.send_signal(signal.SIGTERM)
    deadline = time.monotonic() + 10
    while not cleaning.exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    assert cleaning.exists()
    # While cleanup() is blocked in `wait "$child"`, send a second SIGTERM and a SIGHUP.
    proc.send_signal(signal.SIGTERM)
    proc.send_signal(signal.SIGHUP)
    proc.wait(timeout=15)
    assert done.exists() and done.read_text() == "job deleted"
    assert not work_dir.exists()


FAKE_KUBECTL = r'''#!/usr/bin/env python3
import json, os, shutil, subprocess, sys, tarfile
from pathlib import Path

args = sys.argv[1:]
state_path = Path(os.environ["FAKE_KUBE_STATE"])
volume = Path(os.environ["FAKE_KUBE_VOLUME"])
state = json.loads(state_path.read_text()) if state_path.exists() else {"jobs": {}, "configmaps": {}}
if args[:2] == ["-n", "ui2"] and "deployments" in args:
    print(json.dumps({"items": [{"spec": {"template": {"spec": {"containers": [{"image": os.environ["FAKE_DEPLOYED_IMG"]}]}}}}]}))
    raise SystemExit(0)
if args[:2] == ["-n", "ui2-security"]:
    args = args[2:]
cmd = args[0]
if cmd == "exec" and "-i" in args:
    shutil.rmtree(volume / "incoming", ignore_errors=True)
    (volume / "incoming").mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as archive:
        archive.extractall(volume / "incoming", filter="data")
elif cmd == "exec":
    sub = args[args.index("--") + 1:]
    if sub[:2] == ["sh", "-c"]:
        subprocess.run(["sh", "-c", sub[2].replace("cd /source", "cd " + str(volume))], check=True)
    elif sub[:2] == ["python3", "-c"]:
        subprocess.run([sys.executable, "-c", sub[2].replace('Path("/source")', "Path(%r)" % str(volume))], check=True)
elif cmd == "create" and "-f" in args:
    obj = json.loads(sys.stdin.read())
    name = obj["metadata"]["name"]
    if obj["kind"] == "Job":
        obj.setdefault("metadata", {}).setdefault("uid", "uid-" + name)
        obj["status"] = {"conditions": [{"type": "Complete", "status": "True"}]}
        state["jobs"][name] = obj
    elif obj["kind"] == "ConfigMap":
        state["configmaps"][name] = obj
    state_path.write_text(json.dumps(state))
elif cmd == "get" and args[1] == "job":
    print(json.dumps(state["jobs"][args[2]]))
elif cmd == "patch" and args[1] == "configmap":
    patch = json.loads(args[args.index("-p") + 1])
    cm = state["configmaps"][args[2]]
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(cm.get(k), dict):
            cm[k].update(v)
        else:
            cm[k] = v
    state_path.write_text(json.dumps(state))
elif cmd == "logs":
    if args[-1] == "summary":
        print(os.environ["FAKE_CLEAN_SUMMARY"])
elif cmd == "delete" and args[1] in ("job", "configmap"):
    bucket = "jobs" if args[1] == "job" else "configmaps"
    state[bucket].pop(args[2], None)
    state_path.write_text(json.dumps(state))
'''


def test_real_security_host_cli_pregate_start_and_gate_end_to_end(wrapper_env):
    """End-to-end CLI exercise through the real security_host.py __main__:
    1. hosta_security_pregate.sh -> security_pregate_remote.sh -> real security_host.py pregate -> snapshot(publish=False)
       -> verdict written under HOME.
    2. real security_host.py start -> creates a reduced deploy Job bound to SOURCE_REUSED and SOURCE_REUSED_POLICY.
    3. real security_host.py gate -> verifies reuse and prints the validated summary.
    4. broken config -> non-zero exit with SECURITY GATE FAILED."""
    w = wrapper_env
    home = w["home"]
    cfg = home / ".config" / "nexus" / "security.json"
    rules = home / ".config" / "nexus" / "security-rules"
    cfg.write_text(json.dumps(gate_config()))
    rules.mkdir(parents=True)
    for pack in ("java", "typescript", "owasp-top-ten", "secrets"):
        (rules / (pack + ".yaml")).write_text("rules: []")
    repo, commit, tree = git_repo(w["tmp"])
    # Preview reuse record written strictly after the session starts.
    reuse_dir = home / ".local" / "state" / "nexus-preview" / "reuse"
    reuse_dir.mkdir(parents=True)
    (reuse_dir / (tree + ".json")).write_text(json.dumps(dict(
        tree=tree, commit=commit, service="sha256:" + "1" * 64, created=int(time.time()) + 5)))
    bindir = w["tmp"] / "bin"
    kubectl_stub = bindir / "kubectl"
    kubectl_stub.write_text(FAKE_KUBECTL)
    kubectl_stub.chmod(0o755)
    kube_state = w["tmp"] / "kube_state.json"
    kube_volume = w["tmp"] / "kube_volume"
    kube_volume.mkdir()
    real_driver = ROOT / "tools" / "security" / "security_host.py"
    env = dict(w["env"], NEXUS_PREGATE_DRIVER=str(real_driver), FAKE_KUBE_STATE=str(kube_state),
               FAKE_KUBE_VOLUME=str(kube_volume), FAKE_DEPLOYED_IMG=IMG,
               FAKE_CLEAN_SUMMARY=json.dumps(summary()), COPYFILE_DISABLE="1")

    # 1. Full remote wrapper -> real security_host.py pregate -> snapshot(publish=False) -> verdict.
    res = subprocess.run(["bash", str(WRAPPER), "--repo", str(repo), "--commit", commit, "--tree", tree],
                         env=env, capture_output=True, text=True, timeout=120)
    assert res.returncode == 0, res.stderr
    lines = res.stdout.splitlines()
    assert lines[0] == f"PASS {commit} {tree}"
    assert json.loads(lines[1])["passed"] is True
    assert (kube_volume / commit / "a.txt").read_text() == "synthetic"
    assert not (kube_volume / "current").exists()
    verdict_path = home / ".local" / "state" / "nexus-security" / "verdicts" / (tree + ".json")
    assert verdict_path.is_file()
    stored = json.loads(verdict_path.read_text())
    expected_policy = sv.policy_digest(cfg, rules, host.HERE)
    assert stored["commit"] == commit and stored["tree"] == tree and stored["policy"] == expected_policy
    state = json.loads(kube_state.read_text())
    assert len(state["jobs"]) == 1
    pregate_job = next(iter(state["jobs"].values()))
    pregate_scripts = next(m for m in pregate_job["spec"]["template"]["spec"]["initContainers"][0]["volumeMounts"]
                           if m["name"] == "scripts")["subPath"]
    assert pregate_scripts.startswith("pregate-tools/")

    # 2. Real security_host.py __main__ start -> reduced deploy Job bound to tree and policy.
    start_res = subprocess.run(
        [sys.executable, str(real_driver), "start", "--config", str(cfg),
         "--commit", commit, "--repo", str(repo), "--rules", str(rules)],
        env=env, capture_output=True, text=True, timeout=60)
    assert start_res.returncode == 0, start_res.stderr
    job_name = start_res.stdout.strip()
    assert host.GATE_NAME.fullmatch(job_name)
    assert json.loads(start_res.stderr.strip())["security_gate_source_reuse"] is True
    state = json.loads(kube_state.read_text())
    deploy_job = state["jobs"][job_name]
    assert deploy_job["metadata"]["annotations"] == {
        "nexus/commit": commit, host.SOURCE_REUSED: tree, host.SOURCE_REUSED_POLICY: expected_policy}
    assert stages(deploy_job) == ["prepare", "history", "await-image", "trivy-image"]

    # 3. Real security_host.py __main__ gate -> confirms reuse and prints summary.
    gate_res = subprocess.run(
        [sys.executable, str(real_driver), "gate", "--config", str(cfg),
         "--image", RESTAMPED, "--image", PA, "--commit", commit,
         "--job-name", job_name, "--repo", str(repo), "--rules", str(rules)],
        env=env, capture_output=True, text=True, timeout=60)
    assert gate_res.returncode == 0, gate_res.stderr
    gate_out = [json.loads(line) for line in gate_res.stdout.splitlines()]
    assert gate_out[0]["security_gate_source"] == "reused" and gate_out[1]["passed"] is True
    state = json.loads(kube_state.read_text())
    cm = state["configmaps"][job_name]
    assert cm["immutable"] is True
    assert json.loads(cm["data"]["request"]) == {"image": RESTAMPED, "commit": commit, "images": [RESTAMPED, PA]}

    # 4. Broken configuration fails closed through __main__'s exit handler.
    bad = subprocess.run(
        [sys.executable, str(real_driver), "start", "--config", str(w["tmp"] / "missing.json"), "--commit", commit],
        env=env, capture_output=True, text=True, timeout=60)
    assert bad.returncode != 0 and "SECURITY GATE FAILED:" in bad.stderr


# ------------------------------------------------------------------------- run_build.sh: start, then the gate

def build_script():
    return (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()


def test_deploy_starts_its_gate_with_the_inputs_for_the_reuse_check():
    text = build_script()
    # The start runs in the background subshell beside the image builds (deploy speed, 502) and writes the Job name to a file.
    start = text[text.index('python3 "$REPO/tools/security/security_host.py" start'):]
    start = start[:start.index('> "$SECURITY_JOB_FILE"')]
    assert '--commit "$COMMIT_SHA" --repo "$REPO" --rules "$HOME/.config/nexus/security-rules"' in start
    assert "security_host.py\" reuse" not in text and " reuse --config" not in text


def gate_section():
    text = build_script()
    start = text.index('if [ -z "${NEXUS_SKIP_SECURITY_REASON:-}" ] && [ "$SECURITY_CONFIGURED" = 1 ]; then\n  gate_start')
    end = text.index("\nfi\n", start) + 4
    return text[start:end]


@pytest.mark.parametrize("gate_rc,ok", [(0, True), (1, False)])
def test_deploy_always_runs_the_gate_on_every_deployed_digest(tmp_path, gate_rc, ok):
    log = tmp_path / "calls"
    script = (
        "set -euo pipefail\n"
        f'python3() {{ printf "%s\\n" "$*" >> {log}; case "$2" in gate) return {gate_rc};; esac; }}\n'
        "timing() { :; }\n"
        f"REPO=/r HOME=/h SECURITY_CONFIGURED=1 IMAGE_DIGEST=sha256:{'1' * 64} PA_IMAGE={IMG2} SX_IMAGE= "
        "COMMIT_SHA=" + COMMIT + " SECURITY_JOB=security-gate-" + "e" * 12 + "\n" + gate_section())
    result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    calls = log.read_text().splitlines()
    assert (result.returncode == 0) is ok
    assert len(calls) == 1 and calls[0].split()[1] == "gate"
    assert [a for a in calls[0].split() if a.startswith("registry.")] == [IMG, IMG2]
    assert "--repo /r" in calls[0] and "--rules /h/.config/nexus/security-rules" in calls[0]
    assert "--job-name security-gate-" + "e" * 12 in calls[0]
