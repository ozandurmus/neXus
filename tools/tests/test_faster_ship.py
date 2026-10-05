"""Local doubles only: runner identity, failure paths and deploy phase ordering."""
from datetime import datetime, timedelta, timezone
import copy
import json
from pathlib import Path
import sys

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
    with pytest.raises(RuntimeError):
        runner.ensure(COMMIT, ROOT, timeout=0 if kind == "timeout" else 1)


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
    assert source.index("security_host.py snapshot") < source.index("security_host.py start") < source.index("kubectl apply -f ~/build-job-proxy.yaml")
    assert source.index("hosta_e2e_image.py start") < source.index("Checking build job completion")
    assert source.index("security_host.py gate") < source.index("kubectl -n ui2 set image")
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


@pytest.mark.parametrize("mode", ["pass", "gate-failure", "not-configured", "skip"])
def test_build_script_refuses_rollout_on_gate_failure_and_preserves_bypasses(tmp_path, mode):
    import os
    import subprocess
    home, tools = tmp_path / "home", tmp_path / "bin"
    (home / "nexus/project").mkdir(parents=True)
    snapshot_script = home / "nexus/tools/delivery/release_snapshot.sh"
    snapshot_script.parent.mkdir(parents=True)
    snapshot_script.write_text('exec python3 tools/delivery/release_snapshot.py snapshot "$@"\n')
    (home / ".config/nexus").mkdir(parents=True)
    tools.mkdir()
    if mode != "not-configured":
        (home / ".config/nexus/security.json").write_text("{}")
    calls = tmp_path / "calls"
    stub = '''import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as stream:
    stream.write(json.dumps([name, *args]) + '\\n')
if name == 'git' and args[0] == 'rev-parse':
    print('a' * (12 if '--short=12' in args else 40))
elif name == 'python3':
    if args[:2] == ['tools/delivery/release_snapshot.py', 'snapshot']:
        assert args[2:] == ['--commit', 'a' * 40]
        print(json.dumps({'snapshot': '20261005T010000000000Z_' + 'a' * 12}))
    elif args[:2] == ['tools/security/security_host.py', 'start']:
        print('security-gate-' + 'b' * 12)
    elif args[:2] == ['tools/security/security_host.py', 'gate']:
        sys.exit(1 if os.environ['TEST_MODE'] == 'gate-failure' else 0)
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
    result = subprocess.run(["bash", str(ROOT / "deploy/ui2-image-build/run_build.sh")], capture_output=True,
        text=True, timeout=20, env={**os.environ, "HOME": str(home), "PATH": str(tools) + ":" + os.environ["PATH"],
        "TEST_CALLS": str(calls), "TEST_MODE": mode, "NEXUS_SKIP_SECURITY_REASON": "synthetic exercise" if mode == "skip" else ""})
    assert result.returncode == (1 if mode == "gate-failure" else 0), result.stderr
    executed = [json.loads(line) for line in calls.read_text().splitlines()]
    rolled = any(c[:5] == ["kubectl", "-n", "ui2", "set", "image"] for c in executed)
    assert rolled == (mode != "gate-failure")
    scanned = any(c[:3] == ["python3", "tools/security/security_host.py", "start"] for c in executed)
    assert scanned == (mode in ("pass", "gate-failure"))
    assert '"security_gate":"not_configured"' in result.stdout if mode == "not-configured" else True
