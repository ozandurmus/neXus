"""Local doubles only: runner identity, failure paths and deploy phase ordering."""
from datetime import datetime, timedelta, timezone
import copy
import getpass
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/delivery"))
sys.path.insert(0, str(ROOT / "tools" / "e2e"))
import hosta_e2e_image as runner

COMMIT = "a" * 40
DIGEST = "sha256:" + "b" * 64


def completed_job(commit=COMMIT):
    return dict(metadata=dict(uid="synthetic-job", annotations={"nexus/commit": commit},
                              creationTimestamp=datetime.now(timezone.utc).isoformat()),
                status=dict(conditions=[dict(type="Complete", status="True")]),
                spec=dict(template=dict(spec=dict(containers=[dict(name="builder")]))))


@pytest.mark.parametrize("kind", ["same", "other", "old", "failed", "missing", "unbound"])
def test_runner_reuse_requires_fresh_success_for_exact_commit(monkeypatch, kind):
    existing = completed_job("c" * 40 if kind == "other" else COMMIT)
    if kind == "old":
        existing["metadata"]["creationTimestamp"] = (datetime.now(timezone.utc) - timedelta(hours=3)).isoformat()
    if kind == "failed":
        existing["status"]["conditions"][0]["type"] = "Failed"
    if kind == "missing":
        existing = {}
    if kind == "unbound":
        existing["metadata"]["annotations"] = {}
    rebuilt = []
    def start(commit, repo):
        nonlocal existing
        rebuilt.append(commit)
        existing = completed_job()
    def kubectl(*args, **kwargs):
        if args[:2] == ("get", "job"):
            return json.dumps(existing)
        assert args[:2] == ("get", "pods")
        return json.dumps(dict(items=[dict(metadata=dict(ownerReferences=[dict(uid="synthetic-job")]),
            status=dict(containerStatuses=[dict(name="builder", state=dict(terminated=dict(exitCode=0, message=DIGEST)))]))]))
    monkeypatch.setattr(runner, "kubectl", kubectl)
    monkeypatch.setattr(runner, "start", start)
    assert runner.ensure(COMMIT, ROOT) == DIGEST
    assert rebuilt == ([] if kind == "same" else [COMMIT])


@pytest.mark.parametrize("kind", ["timeout", "failed", "wrong-owner", "missing-digest"])
def test_runner_never_uses_stale_logs_or_incomplete_build(monkeypatch, kind):
    current = completed_job()
    if kind == "timeout":
        current["status"] = {}
    if kind == "failed":
        current["status"]["conditions"][0]["type"] = "Failed"
    def fake(*args, **kwargs):
        if args[:2] == ("get", "job"):
            return json.dumps(current)
        assert args[:2] == ("get", "pods")
        return json.dumps(dict(items=[dict(metadata=dict(ownerReferences=[dict(uid="other" if kind == "wrong-owner" else "synthetic-job")]),
            status=dict(containerStatuses=[dict(name="builder", state=dict(terminated=dict(exitCode=0, message="" if kind == "missing-digest" else DIGEST)))]))]))
    monkeypatch.setattr(runner, "kubectl", fake)
    monkeypatch.setattr(runner, "start", lambda *args: None)
    elapsed = [0]
    with pytest.raises(RuntimeError):
        runner.ensure(COMMIT, ROOT, timeout=0 if kind == "timeout" else 1,
                      sleep=lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds),
                      monotonic=lambda: elapsed[0])


@pytest.mark.parametrize("delay", [10, 595, 605])
def test_runner_polls_delayed_digest_for_up_to_ten_minutes(monkeypatch, delay):
    elapsed = [0]
    def fake(*args, **kwargs):
        if args[:2] == ("get", "job"):
            return json.dumps(completed_job())
        return json.dumps(dict(items=[dict(metadata=dict(ownerReferences=[dict(uid="synthetic-job")]),
            status=dict(containerStatuses=[dict(name="builder", state=dict(terminated=dict(
                exitCode=0, message=DIGEST if elapsed[0] >= delay else "")))]))]))
    monkeypatch.setattr(runner, "kubectl", fake)
    kwargs = dict(sleep=lambda seconds: elapsed.__setitem__(0, elapsed[0] + seconds), monotonic=lambda: elapsed[0])
    if delay < 600:
        assert runner.ensure(COMMIT, ROOT, **kwargs) == DIGEST
        assert elapsed[0] == delay
    else:
        with pytest.raises(RuntimeError, match="timed out"):
            runner.ensure(COMMIT, ROOT, **kwargs)
        assert elapsed[0] == 600


def test_runner_build_inherits_proxy_ca_and_freezes_commit(monkeypatch):
    template = dict(metadata={}, spec=dict(template=dict(metadata={}, spec=dict(containers=[dict(
        name="builder", env=[dict(name="HTTPS_PROXY", value="http://192.0.2.10:3128")],
        volumeMounts=[dict(name="corp-ca", mountPath="/kaniko/ssl/certs")])],
        volumes=[dict(name="corp-ca", configMap=dict(name="corp-ca"))]))))
    e2e = copy.deepcopy(template)
    e2e["spec"]["template"]["spec"]["containers"][0]["args"] = ["--dockerfile=/workspace/ui2/frontend/Dockerfile.e2e", "--digest-file=/workspace/old.txt"]
    created = []
    def fake(*args, **kwargs):
        if args[:2] == ("get", "configmap"):
            return json.dumps(dict(data=dict(commit=COMMIT)))
        if "--dry-run=client" in args:
            return json.dumps(e2e if "32-e2e-build-job.yaml" in str(args) else template)
        if args[0] == "create":
            created.append(json.loads(kwargs["input"]))
        return ""
    monkeypatch.setattr(runner, "kubectl", fake)
    runner.start(COMMIT, ROOT)
    job = created[0]
    pod = job["spec"]["template"]["spec"]
    assert job["metadata"]["annotations"]["nexus/commit"] == COMMIT
    assert pod["volumes"] == template["spec"]["template"]["spec"]["volumes"]
    builder = pod["containers"][0]
    assert builder["volumeMounts"] == template["spec"]["template"]["spec"]["containers"][0]["volumeMounts"]
    assert builder["env"] == [dict(name="HTTPS_PROXY", value="http://192.0.2.10:3128"), dict(name="UI2_IMAGE_TAG", value=COMMIT[:12])]
    assert builder["args"][-1] == "--digest-file=/dev/termination-log"
    assert "--image-download-retry=5" in builder["args"]
    assert "--digest-file=/workspace/old.txt" not in builder["args"]


def test_context_mismatch_refuses_before_build_mutation(monkeypatch):
    calls = []
    def fake(*args, **kwargs):
        calls.append(args)
        return json.dumps(dict(data=dict(commit="c" * 40)))
    monkeypatch.setattr(runner, "kubectl", fake)
    with pytest.raises(ValueError, match="context"):
        runner.start(COMMIT, ROOT)
    assert len(calls) == 1


def test_deploy_starts_both_parallel_phases_before_waiting_and_gates_rollout():
    source = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    assert source.index('security_host.py" snapshot') < source.index('security_host.py" start') < source.index("kubectl apply -f ~/build-job-proxy.yaml")
    assert source.index('hosta_e2e_image.py" start') < source.index("Checking build job completion")
    assert source.index('security_host.py" gate') < source.index('python3 "$REPO/tools/delivery/module_deploy.py" --targets')
    assert '--job-name "$SECURITY_JOB"' in source
    assert '[ -z "${NEXUS_SKIP_SECURITY_REASON:-}" ] && [ "$SECURITY_CONFIGURED" = 1 ]' in source
    observer = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    assert "grep image-build" not in observer
    assert observer.count("grep -E '^ui2-image-build-'") == 2


@pytest.mark.parametrize("mode,expected", [("trivy-source", ["fs", "config"]),
                                           ("trivy-image", ["image", "image"]),
                                           ("trivy", ["fs", "config", "image", "image"])])
def test_scan_shell_runs_only_its_half(tmp_path, mode, expected):
    import os
    import subprocess
    work, source, cache, tools = (tmp_path / p for p in ("work", "source", "cache", "bin"))
    for p in (work, source, cache, tools):
        p.mkdir()
    (source / COMMIT).mkdir()
    (work / "source-commit").write_text(COMMIT)
    (work / "images").write_text("registry.kube-system.svc.cluster.local/app@" + DIGEST + "\n")
    stub = tools / "trivy"
    stub.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$TEST_CALLS"\nprintf "{}\\n"\n')
    stub.chmod(0o755)
    script = (ROOT / "tools/security/security_scan.sh").read_text()
    for old, new in (("/work", work), ("/source", source), ("/cache", cache)):
        script = script.replace(old, str(new))
    calls = tmp_path / "calls"
    result = subprocess.run(["sh", "-c", script, "scan", mode], capture_output=True, text=True,
                            env={**os.environ, "PATH": str(tools) + ":" + os.environ["PATH"], "TEST_CALLS": str(calls)})
    assert result.returncode == 0, result.stderr
    assert calls.read_text().splitlines() == expected
    assert (work / "trivy-fs.json").exists() == (mode != "trivy-image")
    assert (work / "trivy-image-0.json").exists() == (mode != "trivy-source")


TARGETS = ("service", "worker", "configuration", "compliance", "policy", "script-runner", "packet-analysis")


def _prepare_build_script_env(tmp_path, mode, extra_env=None):
    home, tools = tmp_path / "home", tmp_path / "bin"
    (home / "nexus/project").mkdir(parents=True)
    # The old checkout has no delivery scripts; the pull makes them available.
    assert not (home / "nexus/tools/delivery").exists()
    (home / ".config/nexus").mkdir(parents=True)
    tools.mkdir()
    if mode != "not-configured":
        (home / ".config/nexus/security.json").write_text("{}")
    calls = tmp_path / "calls"
    # mktemp writes here: whatever the outcome, run_build.sh must leave no temporary file behind.
    scratch = tmp_path / "tmp"
    scratch.mkdir()
    stub = '''import json, os, sys, time
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as stream:
    stream.write(json.dumps([name, *args]) + '\\n')
if name == 'git' and args[0] == 'rev-parse':
    print('a' * (12 if '--short=12' in args else 40))
elif name == 'git' and args == ['pull', 'origin', 'main']:
    delivery = Path(os.environ['REPO']) / 'tools/delivery'
    delivery.mkdir(parents=True)
    (delivery / 'module_deploy.py').write_text('# Synthetic target validator\\n')
    (delivery / 'release_snapshot.py').write_text('# Synthetic snapshot helper\\n')
    (delivery / 'release_snapshot.sh').write_text(
        'exec python3 tools/delivery/release_snapshot.py snapshot "$@"\\n')
    (delivery / 'hosta_packet_analysis_image.py').write_text('# Synthetic analysis image builder\\n')
    (delivery / 'hosta_side_images.py').write_text('# Synthetic side image builder\\n')
    (delivery / 'tree_modes.py').write_text('# Synthetic tree-mode check\\n')
elif name == 'python3':
    if args[0] == '-c':
        print(json.load(sys.stdin)['snapshot'])
        sys.exit(0)
    if Path(args[0]).is_absolute():
        args[0] = str(Path(args[0]).relative_to(os.environ['REPO']))
    if args[0].startswith('tools/delivery/'):
        assert (Path(os.environ['REPO']) / args[0]).is_file(), 'script missing before checkout update'
    if args[:2] == ['tools/delivery/module_deploy.py', '--validate-targets']:
        sys.exit(2 if os.environ['TEST_MODE'] == 'invalid-targets' else 0)
    if args[:2] == ['tools/delivery/release_snapshot.py', 'snapshot']:
        assert args[2:] == ['--commit', 'a' * 40]
        print(json.dumps({'snapshot': '20261005T010000000000Z_' + 'a' * 12}))
    elif args[:2] == ['tools/security/security_host.py', 'start']:
        if os.environ['TEST_MODE'] == 'start-failure':
            sys.exit(1)
        print('security-gate-' + 'b' * 12)
    elif args[:2] == ['tools/security/security_host.py', 'gate']:
        sys.exit(1 if os.environ['TEST_MODE'] == 'gate-failure' else 0)
    elif args[:2] == ['tools/security/security_host.py', 'snapshot']:
        if os.environ.get('TEST_SNAPSHOT_PIDS'):
            Path(os.environ['TEST_SNAPSHOT_PIDS']).write_text(
                json.dumps({'pid': os.getpid(), 'ppid': os.getppid()}))
        if os.environ.get('TEST_SNAPSHOT_STARTED'):
            Path(os.environ['TEST_SNAPSHOT_STARTED']).touch()
        if os.environ.get('TEST_SNAPSHOT_WAIT_UNTIL_NO_JOB_FILE') == '1':
            deadline = time.monotonic() + 5.0
            tmpdir = Path(os.environ['TMPDIR'])
            while time.monotonic() < deadline and any(tmpdir.glob('nexus-security-job.*')):
                time.sleep(0.02)
        if os.environ.get('TEST_SNAPSHOT_RELEASE'):
            release = Path(os.environ['TEST_SNAPSHOT_RELEASE'])
            deadline = time.monotonic() + 15.0
            while time.monotonic() < deadline and not release.exists():
                time.sleep(0.02)
        if os.environ.get('TEST_SNAPSHOT_FINISHED'):
            Path(os.environ['TEST_SNAPSHOT_FINISHED']).touch()
        if os.environ['TEST_MODE'] == 'snapshot-failure':
            sys.exit(1)
    elif args[:2] == ['tools/delivery/hosta_side_images.py', 'build']:
        assert args[2:8] == ['--commit', 'a' * 40, '--repo', os.environ['REPO'], '--service-image',
                             'registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:' + 'b' * 64], args
        assert set(args[8:]) <= {'--packet-analysis', '--script-exec'} and args[8:], args
        if os.environ.get('TEST_SIDE_WAIT_FOR_SNAPSHOT'):
            marker = Path(os.environ['TEST_SIDE_WAIT_FOR_SNAPSHOT'])
            deadline = time.monotonic() + 10.0
            while time.monotonic() < deadline and not marker.exists():
                time.sleep(0.02)
            assert marker.exists(), 'background security snapshot did not start alongside the build'
        print('TIMING side_images 1', file=sys.stderr)
        if os.environ['TEST_MODE'] == 'pa-failure':
            sys.exit(1)
        if '--packet-analysis' in args:
            print('PA_IMAGE=registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:' + 'c' * 64)
        if '--script-exec' in args and os.environ['TEST_MODE'] != 'sx-failure':
            print('SX_IMAGE=registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:' + 'd' * 64)
        if os.environ.get('TEST_BUILD_DONE'):
            Path(os.environ['TEST_BUILD_DONE']).touch()
    else:
        assert args[:2] in [
            ['tools/delivery/module_deploy.py', '--targets'],
            ['tools/e2e/hosta_e2e_image.py', 'start'],
            ['tools/e2e/hosta_e2e_image.py', 'ensure'],
            ['tools/delivery/tree_modes.py', 'check'],
        ], args
elif name == 'kubectl':
    if '-i' in args or ('-f' in args and args[args.index('-f') + 1] == '-'):
        sys.stdin.read()
    if any('Complete' in a for a in args): print('True')
    elif any('items[0].metadata.name' in a for a in args): print('synthetic-build-pod')
    elif '/workspace/image-digest.txt' in args: print('sha256:' + 'b' * 64)
    elif any('psql' in a for a in args): print('0')
'''
    for name in ("git", "python3", "kubectl", "tar", "sleep"):
        path = tools / name
        path.write_text("#!" + sys.executable + "\n" + stub)
        path.chmod(0o755)
    env = {
        **os.environ,
        "HOME": str(home),
        "PATH": str(tools) + ":" + os.environ["PATH"],
        "REPO": str(home / "nexus"),
        "NEXUS_TREE_OWNER": getpass.getuser(),
        "TEST_CALLS": str(calls),
        "TEST_MODE": mode,
        "TMPDIR": str(scratch),
        "NEXUS_DEPLOY_TARGETS": "unknown" if mode == "invalid-targets" else " ".join(TARGETS),
        "NEXUS_DEPLOY_APPLY_FILES": "synthetic-manifest.yaml",
        "NEXUS_SKIP_SECURITY_REASON": "synthetic exercise" if mode == "skip" else "",
        "NEXUS_BUILD_PACKET_ANALYSIS_IMAGE": "0" if mode in ("pa-off", "side-off") else "1",
        "NEXUS_BUILD_SCRIPT_EXEC_IMAGE": "0" if mode == "side-off" else "1",
    }
    if extra_env:
        env.update(extra_env)
    return home, calls, scratch, env


def _read_executed(calls, home):
    executed = [json.loads(line) for line in calls.read_text().splitlines()]
    for call in executed:
        if call[0] == "python3" and Path(call[1]).is_absolute():
            call[1] = str(Path(call[1]).relative_to(home / "nexus"))
    return executed


def _wait_until(predicate, timeout=10.0, step=0.02):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()


@pytest.mark.parametrize("mode", ["pass", "gate-failure", "not-configured", "skip", "invalid-targets",
                                  "pa-off", "pa-failure", "sx-failure", "snapshot-failure",
                                  "start-failure", "side-off"])
def test_build_script_refuses_rollout_on_gate_failure_and_preserves_bypasses(tmp_path, mode):
    home, calls, scratch, env = _prepare_build_script_env(tmp_path, mode)
    result = subprocess.run(["bash", str(ROOT / "deploy/ui2-image-build/run_build.sh")], capture_output=True,
        text=True, timeout=float(os.environ.get("NEXUS_TEST_SUBPROCESS_TIMEOUT", "120")), env=env)
    failing = ("gate-failure", "pa-failure", "snapshot-failure", "start-failure")
    assert result.returncode == (2 if mode == "invalid-targets" else 1 if mode in failing else 0), result.stderr
    executed = _read_executed(calls, home)
    assert executed[:3] == [["git", "fetch"], ["git", "checkout", "main"], ["git", "pull", "origin", "main"]]
    assert executed[3] == ["python3", "tools/delivery/module_deploy.py", "--validate-targets",
                           "unknown" if mode == "invalid-targets" else " ".join(TARGETS)]
    if mode == "invalid-targets":
        assert len(executed) == 4
        assert "no changes applied" in result.stderr
        assert not (home / "nexus/project/deploy_info.json").exists()
        return
    # The tree-mode check runs after target validation and before any change.
    assert executed[4] == ["python3", "tools/delivery/tree_modes.py", "check", "--repo", str(home / "nexus")]
    assert executed[5][:2] == ["git", "rev-parse"]
    assert executed[6][:3] == ["python3", "tools/delivery/release_snapshot.py", "snapshot"]
    rolled = any(c[:3] == ["python3", "tools/delivery/module_deploy.py", "--targets"] for c in executed)
    assert rolled == (mode not in failing)
    scanned = any(c[:3] == ["python3", "tools/security/security_host.py", "start"] for c in executed)
    if mode == "pa-failure":
        # With an instant snapshot stub, start may finish before or be skipped on pa-failure; the slow-snapshot
        # test below deterministically exercises cancellation while snapshot is still running.
        pass
    else:
        assert scanned == (mode in ("pass", "gate-failure", "pa-off", "sx-failure", "start-failure", "side-off"))
    # The side images are built from the main digest after the main build (both in one helper call, at the same time),
    # and gated with it before any rollout. NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0 + NEXUS_BUILD_SCRIPT_EXEC_IMAGE=0
    # skips the side-image helper entirely (frontend fast path).
    built = [i for i, c in enumerate(executed) if c[:3] == ["python3", "tools/delivery/hosta_side_images.py", "build"]]
    if mode == "side-off":
        assert built == []
    else:
        assert len(built) == 1
        assert ("--packet-analysis" in executed[built[0]]) == (mode != "pa-off")
        assert "--script-exec" in executed[built[0]]
    main_image = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "b" * 64
    pa_image = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "c" * 64
    sx_image = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "d" * 64
    gates = [c for c in executed if c[:3] == ["python3", "tools/security/security_host.py", "gate"]]
    if mode in ("pass", "gate-failure", "pa-off", "sx-failure", "side-off"):
        images = [gates[0][i + 1] for i, a in enumerate(gates[0]) if a == "--image"]
        # Every side image that was built is scanned with the main image; a failed (optional) script execution image
        # is not, and neither is a side image that was not built.
        assert images == {"pass": [main_image, pa_image, sx_image], "gate-failure": [main_image, pa_image, sx_image],
                          "pa-off": [main_image, sx_image], "sx-failure": [main_image, pa_image],
                          "side-off": [main_image]}[mode]
        assert gates[0][gates[0].index("--job-name") + 1] == "security-gate-" + "b" * 12
        assert not built or built[0] < executed.index(gates[0])
        # The background security snapshot and gate start finished before the gate, and both phases were timed.
        snap = next(i for i, c in enumerate(executed) if c[:3] == ["python3", "tools/security/security_host.py", "start"])
        assert snap < executed.index(gates[0])
        assert re.search(r"^TIMING source_snapshot \d+$", result.stdout, re.M)
        assert re.search(r"^TIMING security_snapshot_wait \d+$", result.stdout, re.M)
    else:
        assert gates == []
    rollout = next((c for c in executed if c[:3] == ["python3", "tools/delivery/module_deploy.py", "--targets"]), None)
    if rollout is not None:
        # What is rolled out, and with which side image: a failed script execution image drops script-runner and its
        # image flag; no Packet Analysis image drops packet-analysis and its image flag; side-off skips both side
        # builds, drops packet-analysis and keeps script-runner (with its existing execution image).
        dropped = {"sx-failure": {"script-runner"}, "pa-off": {"packet-analysis"},
                   "side-off": {"packet-analysis"}}.get(mode, set())
        assert rollout[3] == " ".join(t for t in TARGETS if t not in dropped)
        flag = lambda name: rollout[rollout.index(name) + 1] if name in rollout else None
        assert flag("--image") == main_image
        assert flag("--packet-analysis-image") == (None if mode in ("pa-off", "side-off") else pa_image)
        assert flag("--script-exec-image") == (None if mode in ("sx-failure", "side-off") else sx_image)
    if mode == "sx-failure":
        assert "script-runner is left out of this rollout" in result.stdout
    # The gate Job name file (mktemp) is removed on every path, including a failed gate, snapshot, start or image build.
    assert list(scratch.iterdir()) == []
    if mode in ("snapshot-failure", "start-failure"):
        assert "security source snapshot or gate start failed; rollout refused" in result.stderr
        assert not re.search(r"^TIMING source_snapshot \d+$", result.stdout, re.M)
    if mode in ("not-configured", "skip"):
        assert re.search(r"^TIMING source_snapshot \d+$", result.stdout, re.M)
    assert '"security_gate":"not_configured"' in result.stdout if mode == "not-configured" else True


def test_build_script_early_failure_during_slow_snapshot_drains_snapshot_and_skips_start(tmp_path):
    pids_file = tmp_path / "snapshot-pids.json"
    started = tmp_path / "snapshot-started"
    finished = tmp_path / "snapshot-finished"
    home, calls, scratch, env = _prepare_build_script_env(tmp_path, "pa-failure", extra_env={
        "TEST_SNAPSHOT_PIDS": str(pids_file),
        "TEST_SNAPSHOT_STARTED": str(started),
        "TEST_SNAPSHOT_WAIT_UNTIL_NO_JOB_FILE": "1",
        "TEST_SNAPSHOT_FINISHED": str(finished),
        "TEST_SIDE_WAIT_FOR_SNAPSHOT": str(started),
    })
    result = subprocess.run(["bash", str(ROOT / "deploy/ui2-image-build/run_build.sh")], capture_output=True,
                            text=True, timeout=30, env=env)
    assert result.returncode == 1, result.stderr
    assert started.exists() and finished.exists()
    assert list(scratch.iterdir()) == []
    pids = json.loads(pids_file.read_text())
    for pid in (pids["pid"], pids["ppid"]):
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)
    executed = _read_executed(calls, home)
    assert any(c[:3] == ["python3", "tools/security/security_host.py", "snapshot"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/security/security_host.py", "start"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/security/security_host.py", "gate"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/delivery/module_deploy.py", "--targets"] for c in executed)
    assert not re.search(r"^TIMING source_snapshot \d+$", result.stdout, re.M)


@pytest.mark.parametrize("sig,expected_rc", [(signal.SIGTERM, 143), (signal.SIGINT, 130)])
def test_build_script_signal_during_slow_snapshot_drains_snapshot_skips_start_and_cleans_up(tmp_path, sig, expected_rc):
    pids_file = tmp_path / "snapshot-pids.json"
    started = tmp_path / "snapshot-started"
    release = tmp_path / "snapshot-release"
    finished = tmp_path / "snapshot-finished"
    build_done = tmp_path / "build-done"
    home, calls, scratch, env = _prepare_build_script_env(tmp_path, "pass", extra_env={
        "TEST_SNAPSHOT_PIDS": str(pids_file),
        "TEST_SNAPSHOT_STARTED": str(started),
        "TEST_SNAPSHOT_RELEASE": str(release),
        "TEST_SNAPSHOT_FINISHED": str(finished),
        "TEST_BUILD_DONE": str(build_done),
    })
    proc = subprocess.Popen(
        ["bash", str(ROOT / "deploy/ui2-image-build/run_build.sh")],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env,
        preexec_fn=lambda: signal.signal(signal.SIGINT, signal.SIG_DFL),
    )
    try:
        assert _wait_until(lambda: started.exists() and build_done.exists(), timeout=10.0), \
            f"build did not reach security join (rc={proc.poll()})"
        assert proc.poll() is None
        assert len(list(scratch.glob("nexus-security-job.*"))) == 1
        # Signal only run_build.sh (not the background subshell) to reproduce the orphan-subshell defect.
        proc.send_signal(sig)
        # report_failure must run on the signal, delete SECURITY_JOB_FILE to cancel `start`, and stay alive
        # waiting for `snapshot` instead of exiting immediately and orphaning it.
        assert _wait_until(lambda: list(scratch.iterdir()) == [], timeout=10.0), \
            f"SECURITY_JOB_FILE was not removed on signal (rc={proc.poll()})"
        assert proc.poll() is None, "run_build.sh exited before draining the background security snapshot"
        release.touch()
        stdout, stderr = proc.communicate(timeout=10)
    finally:
        release.touch()
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=5)
    assert proc.returncode == expected_rc, stderr
    assert finished.exists()
    assert list(scratch.iterdir()) == []
    pids = json.loads(pids_file.read_text())
    for pid in (pids["pid"], pids["ppid"]):
        with pytest.raises(ProcessLookupError):
            os.kill(pid, 0)
    executed = _read_executed(calls, home)
    assert any(c[:3] == ["python3", "tools/security/security_host.py", "snapshot"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/security/security_host.py", "start"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/security/security_host.py", "gate"] for c in executed)
    assert not any(c[:3] == ["python3", "tools/delivery/module_deploy.py", "--targets"] for c in executed)
    assert not re.search(r"^TIMING source_snapshot \d+$", stdout, re.M)


def test_deploy_retries_only_a_base_image_download_failure():
    sys.path.insert(0, str(ROOT))
    from tools.delivery.standalone_orchestrate import _registry_fetch_failed
    fetch = ('time="x" level=info msg="Retrieving image registry.access.redhat.com/ubi9/nodejs-22-minimal"\n'
             't=122s build=Error new=[;]\nSTOP: build failed\n'
             'error building image: Get "https://cdn01.quay.io/quayio-production-s3/sha256/59/abc?X-Amz-Algorithm=REDACTED"\n')
    assert _registry_fetch_failed(fetch)
    compile_error = 't=90s build=Error new=[;]\nSTOP: build failed\n[ERROR] COMPILATION ERROR : Foo.java\n'
    assert not _registry_fetch_failed(compile_error)
    assert not _registry_fetch_failed('error building image: Get "https://x" but the build went on\nDone.\n')
    source = (ROOT / "tools/delivery/standalone_orchestrate.py").read_text()
    assert "registry_retries = 2" in source
