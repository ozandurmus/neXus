"""Offline candidate gate: manifests, streaming failures, teardown and ship ordering."""
import argparse
import copy
from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import sys

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/delivery"))
sys.path.insert(0, str(ROOT / "tools" / "e2e"))
import hosta_preview_e2e as preview
import standalone_orchestrate as sa

COMMIT = "a" * 40
IMAGE = "registry.example.invalid/candidate@sha256:" + "b" * 64


def template(path, kind=None):
    docs = list(yaml.safe_load_all((ROOT / path).read_text()))
    return next(d for d in docs if kind is None or d["kind"] == kind)


def container_image(deployment):
    return deployment["spec"]["template"]["spec"]["containers"][0]["image"]


def test_runtime_manifests_are_ephemeral_and_use_only_the_preview_database():
    db_source = template("deploy/ui2/40-database-statefulset.yaml")
    service_source = template("deploy/ui2/50-service-deployment.yaml")
    db = preview.database_manifest(db_source)
    service = preview.service_manifest(service_source, IMAGE, "192.0.2.10", "192.0.2.30")
    e2e = preview.e2e_manifest(template("deploy/ui2/70-e2e-job.yaml", "Job"), IMAGE, "192.0.2.20")
    assert db["spec"]["containers"][0]["image"] == db_source["spec"]["template"]["spec"]["containers"][0]["image"]
    assert service_source["spec"]["template"]["spec"]["containers"][0]["image"] != IMAGE
    compliance_source = template("deploy/ui2/55-compliance-deployment.yaml", "Deployment")
    before = copy.deepcopy(compliance_source)
    compliance = preview.compliance_manifest(compliance_source, IMAGE, "192.0.2.10")
    assert compliance_source == before
    assert compliance["spec"]["selector"]["matchLabels"] == compliance["spec"]["template"]["metadata"]["labels"]
    worker = compliance["spec"]["template"]["spec"]["containers"][0]
    assert worker["image"] == container_image(service) == IMAGE
    assert worker["args"] == ["worker", "compliance"]
    worker_env = {e["name"]: e["value"] for e in worker["env"]}
    assert worker_env["UI2_DB_URL"] == "jdbc:postgresql://192.0.2.10:5432/ui2"
    assert worker_env["UI2_SCHEDULING_ENABLED"] == "false"
    assert worker_env["NEXUS_COMPLIANCE_PORT"] == "8085"
    for obj in (db, service, compliance, e2e):
        assert obj["metadata"]["namespace"] == preview.NS
        spec = obj["spec"] if obj["kind"] == "Pod" else obj["spec"]["template"]["spec"]
        assert spec["automountServiceAccountToken"] is False
        assert spec["securityContext"]["runAsNonRoot"] is True
        assert spec["securityContext"]["seccompProfile"]["type"] == "RuntimeDefault"
        for volume in spec["volumes"]:
            assert "hostPath" not in volume and "persistentVolumeClaim" not in volume
        for container in spec["containers"]:
            security = container["securityContext"]
            assert security["allowPrivilegeEscalation"] is False
            assert security["readOnlyRootFilesystem"] is True
            assert security["capabilities"]["drop"] == ["ALL"]
            assert set(container["resources"]) == {"requests", "limits"}
            assert all(set(container["resources"][s]) == {"cpu", "memory"} for s in ("requests", "limits"))
    container = service["spec"]["template"]["spec"]["containers"][0]
    env = {e["name"]: e for e in container["env"]}
    assert container["args"] == ["service"]
    assert env["UI2_DB_URL"]["value"] == "jdbc:postgresql://192.0.2.10:5432/ui2"
    assert env["UI2_SCHEDULING_ENABLED"]["value"] == "false"
    assert env["UI2_COMPLIANCE_SERVICE_URL"]["value"] == "http://192.0.2.30:8085"
    assert "UI2_CP_BACKUP_CREDENTIAL_REF" not in env
    assert all("configMapKeyRef" not in e.get("valueFrom", {}) for e in env.values())
    runner = {e["name"]: e for e in e2e["spec"]["template"]["spec"]["containers"][0]["env"]}
    assert runner["NEXUS_E2E_MACHINE_URL"]["value"] == "http://192.0.2.20:8086/internal/machine-session"
    assert runner["NEXUS_E2E_MODE"]["value"] == "quick"
    assert e2e["spec"]["suspend"] is False and e2e["spec"]["backoffLimit"] == 0


def test_network_policies_have_no_dns_internet_or_cross_namespace_exception():
    db, service, compliance, e2e = preview.policies()
    assert db["spec"]["egress"] == []
    assert service["spec"]["egress"] == [preview.peer("database", [5432], "to"),
                                          preview.peer("compliance", [8085], "to")]
    assert compliance["spec"]["egress"] == [preview.peer("database", [5432], "to")]
    assert compliance["spec"]["ingress"] == [preview.peer("service", [8085], "from")]
    assert db["spec"]["ingress"] == [preview.peer("service", [5432], "from"),
                                       preview.peer("compliance", [5432], "from")]
    assert e2e["spec"]["egress"] == [preview.peer("service", [8080, 8086], "to")]
    for obj in (db, service, compliance, e2e):
        assert obj["spec"]["policyTypes"] == ["Ingress", "Egress"]
        assert "namespaceSelector" not in json.dumps(obj) and "ipBlock" not in json.dumps(obj)


def test_build_uses_pinned_kaniko_new_tag_emptydir_and_corporate_ca():
    original = template("deploy/ui2-image-build/30-build-job.yaml")
    loader = template("deploy/ui2-image-build/20-context-loader.yaml")
    before = copy.deepcopy(original)
    obj = preview.build_manifest(original, loader, "synthetic-build", COMMIT, "synthetic-owner",
                                 "ui2/Containerfile", "registry.example.invalid/service:preview-" + COMMIT[:12])
    spec = obj["spec"]["template"]["spec"]
    builder = spec["containers"][0]
    assert builder["image"] == original["spec"]["template"]["spec"]["containers"][0]["image"]
    assert "--destination=registry.example.invalid/service:preview-" + COMMIT[:12] in builder["args"]
    assert "--digest-file=/dev/termination-log" in builder["args"]
    assert "--context=dir:///workspace" in builder["args"]
    assert obj["metadata"]["annotations"]["nexus/commit"] == COMMIT
    assert spec["initContainers"][0]["securityContext"]["runAsNonRoot"] is True
    assert any(v.get("configMap", {}).get("name") == "corp-ca" for v in spec["volumes"])
    assert all("persistentVolumeClaim" not in v and "hostPath" not in v for v in spec["volumes"])
    assert original == before


@pytest.mark.parametrize("failed", ["namespace", "policy", "build", "database", "compliance", "service", "job", "wait", None])
def test_every_orchestration_failure_tears_down(monkeypatch, failed, capsys):
    calls = []
    def phase(name, result=None):
        calls.append(name)
        if name == failed:
            raise RuntimeError("synthetic failure")
        return result
    monkeypatch.setattr(preview, "k", lambda *a, **kw: "")
    monkeypatch.setattr(preview, "create", lambda ns, obj: phase(
        "namespace" if obj["kind"] == "Namespace" else "policy" if obj["kind"] == "NetworkPolicy" else "job"))
    monkeypatch.setattr(preview, "build_images", lambda *a: phase("build", [IMAGE, IMAGE]))
    monkeypatch.setattr(preview, "setup_database", lambda *a: phase("database", "192.0.2.10"))
    def compliance(repo, image, db_ip):
        assert image == IMAGE and db_ip == "192.0.2.10"
        return phase("compliance", "192.0.2.30")
    def service(repo, image, db_ip, compliance_ip):
        assert image == IMAGE and db_ip == "192.0.2.10" and compliance_ip == "192.0.2.30"
        return phase("service", "192.0.2.20")
    monkeypatch.setattr(preview, "setup_compliance", compliance)
    monkeypatch.setattr(preview, "setup_service", service)
    monkeypatch.setattr(preview, "load", lambda *a: {"items": [template("deploy/ui2/70-e2e-job.yaml", "Job")]})
    monkeypatch.setattr(preview, "wait_job", lambda *a, **kw: phase("wait"))
    monkeypatch.setattr(preview, "cleanup", lambda owner: calls.append("cleanup"))
    if failed:
        with pytest.raises(RuntimeError):
            preview.run(ROOT, COMMIT)
        assert "PREVIEW E2E: PASS" not in capsys.readouterr().out
    else:
        preview.run(ROOT, COMMIT)
        assert "PREVIEW E2E: PASS" in capsys.readouterr().out
    assert calls[-1] == "cleanup" and calls.count("cleanup") == 1
    if failed is None:
        assert calls.index("database") < calls.index("compliance") < calls.index("service") < calls.index("job")


@pytest.mark.parametrize("failure", ["build-delete", "namespace-delete", "build-remains", "namespace-remains", "other-owner", None])
def test_cleanup_attempts_both_scopes_and_verifies_ownership_and_absence(monkeypatch, failure):
    calls = []
    def fake(ns, *args, **kwargs):
        calls.append((ns, args))
        if args[0] == "delete" and ((ns == preview.BUILD_NS and failure == "build-delete")
                                   or (ns == preview.NS and failure == "namespace-delete")):
            raise RuntimeError("synthetic failure")
        if args[:2] == ("get", "namespace"):
            if args[-1] == "json":
                return json.dumps({"metadata": {"labels": {preview.LABEL: "other" if failure == "other-owner" else "owner"}}})
            return "namespace/ui2-preview" if failure == "namespace-remains" else ""
        if args[:2] == ("get", "jobs,pods"):
            return json.dumps({"items": [{}] if failure == "build-remains" else []})
        return ""
    monkeypatch.setattr(preview, "k", fake)
    if failure:
        with pytest.raises(RuntimeError, match="teardown incomplete"):
            preview.cleanup("owner")
    else:
        preview.cleanup("owner")
    assert any(ns == preview.BUILD_NS and a[0] == "delete" for ns, a in calls)
    assert any(ns == preview.NS and a[:2] == ("get", "namespace") for ns, a in calls)
    assert any(ns == preview.NS and a[0] == "delete" for ns, a in calls) == (failure != "other-owner")


def test_existing_namespace_is_never_reused_or_removed(monkeypatch):
    monkeypatch.setattr(preview, "k", lambda *a, **kw: "namespace/ui2-preview")
    monkeypatch.setattr(preview, "cleanup", lambda *a: pytest.fail("Must not delete another run"))
    with pytest.raises(RuntimeError, match="already exists"):
        preview.run(ROOT, COMMIT)


@pytest.mark.parametrize("producer,consumer", [(0, 0), (1, 0), (0, 1)])
def test_stream_checks_both_exit_codes(producer, consumer):
    source = [sys.executable, "-c", f"import sys; sys.stdout.write('synthetic'); sys.exit({producer})"]
    target = [sys.executable, "-c", f"import sys; sys.stdin.read(); sys.exit({consumer})"]
    if producer or consumer:
        with pytest.raises(RuntimeError, match="stream failed"):
            preview.pipe_commands(source, target)
    else:
        preview.pipe_commands(source, target)


def test_stream_timeout_kills_both_processes():
    with pytest.raises(subprocess.TimeoutExpired):
        preview.pipe_commands([sys.executable, "-c", "import time; time.sleep(30)"],
                              [sys.executable, "-c", "import sys; sys.stdin.read()"], timeout=0.05)


def test_copy_is_streamed_read_only_and_filters_only_the_two_requested_tables(monkeypatch):
    calls = []
    @contextmanager
    def snapshot(source):
        yield "00000001-00000002-1"
    monkeypatch.setattr(preview, "exported_snapshot", snapshot)
    monkeypatch.setattr(preview, "pipe_commands", lambda source, target: calls.append((source, target)))
    preview.copy_database()
    assert len(calls) == 3
    assert "--format=custom" in calls[0][0][-1]
    assert "--snapshot=00000001-00000002-1" in calls[0][0][-1]
    assert calls[0][0][-1].count("--exclude-table-data=") == 2
    assert "--exit-on-error --no-owner" in calls[0][1][-1]
    assert preview.AUDIT_SELECT in calls[1][0][-1]
    assert preview.ENTRY_SELECT in calls[2][0][-1]
    assert all("SET TRANSACTION SNAPSHOT '00000001-00000002-1'" in s[-1] for s, _ in calls[1:])
    for source, target in calls:
        assert source[:4] == ["kubectl", "-n", "ui2", "exec"]
        assert "default_transaction_read_only=on" in source[-1]
        assert target[:4] == ["kubectl", "-n", preview.NS, "exec"]
        assert "-i" in target and "-i" not in source
        assert "--file=" not in source[-1] and "--file=" not in target[-1]


@pytest.mark.parametrize("mode", ["auto", "force", "skip", "unrelated", "red"])
def test_ship_gate_before_push_pr_merge_and_deploy(tmp_path, monkeypatch, mode):
    events = []
    def git(*args, **kwargs):
        events.append(tuple(args))
        if args[0] == "status": return ""
        if args[0] == "rev-list": return "1"
        if args[0] == "diff": return "ui2/service/synthetic.java" if mode in ("auto", "red", "skip") else "scripts/synthetic.py"
        if args[0] == "rev-parse": return COMMIT
        return "synthetic-commit"
    def run(args, **kwargs):
        events.append(tuple(args))
        output = "Gate:                 PASS" if "repository_privacy_check.py" in " ".join(args) else ""
        if args[:3] == ["gh", "pr", "create"]: output = "https://example.invalid/pr/1"
        if "tools/e2e/hosta_e2e.sh" in args: output = "E2E: PASS"
        return subprocess.CompletedProcess(args, 0, output, "")
    def gate(repo, commit):
        events.append(("preview",))
        assert commit == COMMIT
        if mode == "red": raise SystemExit("synthetic preview red")
    monkeypatch.setattr(sa, "_git", git)
    monkeypatch.setattr(sa.subprocess, "run", run)
    monkeypatch.setattr(sa, "_preview_e2e", gate)
    monkeypatch.setattr(sa, "_deploy", lambda *args: events.append(("deploy",)))
    args = argparse.Namespace(task=None, branch="feature/synthetic", title=None, notes=None,
                              preview_e2e=mode == "force", no_preview_e2e="synthetic exercise" if mode == "skip" else None)
    if mode == "red":
        with pytest.raises(SystemExit, match="preview red"): sa._ship(args)
        assert not any(e[0] in ("push", "gh", "deploy") for e in events)
    else:
        assert sa._ship(args) == 0
        if mode in ("auto", "force"):
            assert events.index(("preview",)) < next(i for i, e in enumerate(events) if e[0] == "push")
        else:
            assert ("preview",) not in events


@pytest.mark.parametrize("reason", ["", " ", "two\nlines", "x" * 201])
def test_empty_or_unsafe_skip_reason_refused_before_git(monkeypatch, reason):
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: pytest.fail("No Git operation allowed"))
    with pytest.raises(SystemExit, match="requires"):
        sa._ship(argparse.Namespace(no_preview_e2e=reason))


def test_preview_cli_modes_are_mutually_exclusive(monkeypatch):
    monkeypatch.setattr(sa, "cmd_ship", lambda args: (args.preview_e2e, args.no_preview_e2e))
    assert sa.main(["ship", "--branch", "feature/synthetic"]) == (False, None)
    assert sa.main(["ship", "--preview-e2e"]) == (True, None)
    assert sa.main(["ship", "--no-preview-e2e", "synthetic exercise"]) == (False, "synthetic exercise")
    with pytest.raises(SystemExit):
        sa.main(["ship", "--preview-e2e", "--no-preview-e2e", "synthetic exercise"])


@pytest.mark.parametrize("invalid", [False, True])
def test_exported_snapshot_is_opaque_and_holder_rolls_back(monkeypatch, invalid):
    real_popen = subprocess.Popen
    holders = []
    def fake(argv, **kwargs):
        assert argv[:5] == ["kubectl", "-n", "ui2", "exec", "-i"]
        assert "default_transaction_read_only=on" in argv[-1]
        code = """import sys
for line in sys.stdin:
    if 'pg_export_snapshot' in line:
        print(sys.argv[1], flush=True)
    if line.startswith('\\\\q'):
        break
"""
        holder = real_popen([sys.executable, "-c", code, "invalid" if invalid else "00000001-00000002-1"], **kwargs)
        holders.append(holder)
        return holder
    monkeypatch.setattr(preview.subprocess, "Popen", fake)
    if invalid:
        with pytest.raises(RuntimeError, match="snapshot unavailable"):
            with preview.exported_snapshot(["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]):
                pytest.fail("Invalid snapshot must never be used")
    else:
        with preview.exported_snapshot(["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]) as token:
            assert token == "00000001-00000002-1"
            assert holders[0].poll() is None
    assert holders[0].poll() == 0


def test_database_bootstrap_uses_new_secret_and_refuses_image_drift(monkeypatch, capsys):
    db = template("deploy/ui2/40-database-statefulset.yaml")
    pinned = db["spec"]["template"]["spec"]["containers"][0]["image"]
    objects = []
    monkeypatch.setattr(preview, "load", lambda repo, path: template(path))
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "copy_database", lambda: None)
    def fake(ns, *args, **kwargs):
        assert args[0] in ("get", "wait")
        if ns == "ui2":
            return json.dumps(dict(spec=dict(containers=[dict(image=pinned)])))
        return json.dumps(dict(status=dict(podIP="192.0.2.10"))) if args[0] == "get" else ""
    monkeypatch.setattr(preview, "k", fake)
    ticks = iter([10.0, 12.5])
    monkeypatch.setattr(preview.time, "monotonic", lambda: next(ticks))
    assert preview.setup_database(ROOT) == "192.0.2.10"
    assert "TIMING preview_db_copy 2.500" in capsys.readouterr().out
    secret = next(o for o in objects if o["kind"] == "Secret")
    assert set(secret["data"]) == {"app-user", "app-password", "migrate-user", "migrate-password"}
    assert all(o["metadata"]["namespace"] == preview.NS for o in objects)
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(dict(spec=dict(containers=[dict(image=IMAGE)]))))
    objects.clear()
    with pytest.raises(RuntimeError, match="pinned bootstrap"):
        preview.setup_database(ROOT)
    assert not objects


@pytest.mark.parametrize("failed_rollout", [False, True])
def test_compliance_setup_uses_candidate_preview_service_and_waits_for_readiness(monkeypatch, failed_rollout):
    objects, calls = [], []
    monkeypatch.setattr(preview, "load", lambda repo, path: {"items": list(
        yaml.safe_load_all((repo / path).read_text()))})
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "copy_secret", lambda *a: pytest.fail("Compliance needs no credentials"))
    def fake(ns, *args, **kwargs):
        assert ns == preview.NS
        calls.append(args)
        if args[0] == "rollout" and failed_rollout:
            raise RuntimeError("synthetic readiness failure")
        return json.dumps(dict(spec=dict(clusterIP="192.0.2.30")))
    monkeypatch.setattr(preview, "k", fake)
    if failed_rollout:
        with pytest.raises(RuntimeError, match="readiness failure"):
            preview.setup_compliance(ROOT, IMAGE, "192.0.2.10")
        assert len(calls) == 1
    else:
        assert preview.setup_compliance(ROOT, IMAGE, "192.0.2.10") == "192.0.2.30"
        assert calls[-1] == ("get", "service", "ui2-preview-compliance", "-o", "json")
    deployment, service = objects
    assert calls[0][:3] == ("rollout", "status", "deployment/ui2-preview-compliance")
    assert container_image(deployment) == IMAGE
    assert all(o["metadata"]["namespace"] == preview.NS for o in objects)
    assert service["metadata"]["name"] == "ui2-preview-compliance"
    assert service["spec"]["type"] == "ClusterIP"
    assert service["spec"]["selector"] == deployment["spec"]["template"]["metadata"]["labels"]
    assert service["spec"]["ports"] == [dict(name="http", port=8085, targetPort=8085, protocol="TCP")]


def test_service_copies_only_required_keys_and_has_namespace_only_service(monkeypatch):
    objects, copied = [], []
    monkeypatch.setattr(preview, "load", lambda repo, path: template(path))
    monkeypatch.setattr(preview, "copy_secret", copied.append)
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(dict(spec=dict(clusterIP="192.0.2.20"))))
    assert preview.setup_service(ROOT, IMAGE, "192.0.2.10", "192.0.2.30") == "192.0.2.20"
    assert "ui2-db" not in copied and "ui2-e2e-machine-token" in copied
    service = next(o for o in objects if o["kind"] == "Service")
    assert service["spec"]["type"] == "ClusterIP"
    assert all("nodePort" not in p for p in service["spec"]["ports"])
    assert all(o["kind"] != "Deployment" or o["metadata"]["name"] == "ui2-preview-service" for o in objects)


@pytest.mark.parametrize("code,marker", [(0, "PREVIEW E2E: PASS"), (1, "PREVIEW E2E: PASS"), (0, "missing")])
def test_client_requires_pass_exit_and_verdict(monkeypatch, code, marker, capsys):
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: "# Synthetic wrapper\n")
    monkeypatch.setattr(sa.subprocess, "run", lambda *a, **kw:
        subprocess.CompletedProcess(a, code, "TIMING preview_e2e 0.001\n" + marker + "\nsynthetic withheld detail", ""))
    if code or marker == "missing":
        with pytest.raises(SystemExit, match="stopped before PR"):
            sa._preview_e2e(ROOT, COMMIT)
    else:
        sa._preview_e2e(ROOT, COMMIT)
    assert "synthetic withheld detail" not in capsys.readouterr().out


@pytest.mark.parametrize("fault", [None, "upload", "replaced", "wrong-owner", "missing-digest"])
def test_build_pipeline_binds_candidate_to_owner_and_digest(monkeypatch, fault):
    source = template("deploy/ui2-image-build/30-build-job.yaml")
    source["spec"]["template"]["spec"]["containers"][0]["env"] = [
        dict(name="HTTPS_PROXY", value="http://192.0.2.30:3128")]
    created, streamed = [], []
    def fake(ns, *args, **kwargs):
        if args[0] == "create":
            return json.dumps(source)
        if args[:2] == ("get", "pods"):
            return json.dumps(dict(items=[dict(metadata=dict(name="synthetic-builder", ownerReferences=[dict(
                uid="other" if fault == "wrong-owner" else "synthetic-job")]), status=dict(
                    initContainerStatuses=[dict(name="context-loader", state=dict(running={}))],
                    containerStatuses=[dict(name="builder", state=dict(terminated=dict(exitCode=0,
                        message="" if fault == "missing-digest" else "sha256:" + "b" * 64)))]))]))
        pytest.fail("Unexpected cluster operation")
    def create(ns, obj):
        created.append(obj)
    def wait(ns, name):
        job = copy.deepcopy(created[-1])
        job["metadata"]["uid"] = "synthetic-job"
        if fault == "replaced": job["metadata"]["annotations"]["nexus/commit"] = "c" * 40
        return job
    def stream(source, target, **kwargs):
        streamed.append((source, target))
        if fault == "upload": raise RuntimeError("synthetic upload failure")
    monkeypatch.setattr(preview, "k", fake)
    monkeypatch.setattr(preview, "load", lambda repo, path: template(path))
    monkeypatch.setattr(preview, "create", create)
    monkeypatch.setattr(preview, "wait_job", wait)
    monkeypatch.setattr(preview, "pipe_commands", stream)
    if fault:
        with pytest.raises(RuntimeError): preview.build_images(ROOT, COMMIT, "synthetic-owner")
    else:
        images = preview.build_images(ROOT, COMMIT, "synthetic-owner")
        assert len(images) == 2 and all("@sha256:" in image for image in images)
        assert len(streamed) == 2
        for obj in created:
            assert obj["metadata"]["namespace"] == preview.BUILD_NS
            assert obj["metadata"]["labels"][preview.LABEL] == "synthetic-owner"
            assert obj["spec"]["template"]["spec"]["containers"][0]["env"] == source["spec"]["template"]["spec"]["containers"][0]["env"]
        assert all("/run/corp-ca/" in target[-1] for _, target in streamed)


@pytest.mark.parametrize("mode", ["pass", "fail", "signal"])
def test_remote_wrapper_trap_deletes_candidate_source_on_failure_and_signal(tmp_path, mode):
    import io
    import os
    import tarfile
    home, tools, scratch = tmp_path / "home", tmp_path / "bin", tmp_path / "scratch"
    (home / ".config/nexus").mkdir(parents=True)
    (home / ".config/nexus/hosta").write_text("example.invalid\n")
    tools.mkdir()
    scratch.mkdir()
    archive = tmp_path / "candidate.tar"
    with tarfile.open(archive, "w") as tar:
        content = b"# Synthetic archived controller\n"
        member = tarfile.TarInfo("tools/e2e/hosta_preview_e2e.py")
        member.size = len(content)
        tar.addfile(member, io.BytesIO(content))
    stub = '''import os, signal, sys, time
from pathlib import Path
name = Path(sys.argv[0]).name
if name == 'git':
    sys.stdout.buffer.write(Path(os.environ['TEST_ARCHIVE']).read_bytes())
elif name == 'ssh':
    os.execv('/bin/bash', ['bash', '-c', sys.argv[-1]])
else:
    Path(os.environ['TEST_WORK']).write_text(sys.argv[sys.argv.index('--repo') + 1])
    print('synthetic detail withheld', flush=True)
    print('TIMING preview_e2e 0.001', flush=True)
    if os.environ['TEST_MODE'] == 'signal':
        os.kill(os.getppid(), signal.SIGTERM)
        time.sleep(30)
    print('PREVIEW E2E: PASS' if os.environ['TEST_MODE'] == 'pass' else 'PREVIEW E2E: FAIL', flush=True)
    sys.exit(0 if os.environ['TEST_MODE'] == 'pass' else 1)
'''
    for name in ("git", "ssh", "python3"):
        path = tools / name
        path.write_text("#!" + sys.executable + "\n" + stub)
        path.chmod(0o755)
    work = tmp_path / "work-path"
    result = subprocess.run(["bash", str(ROOT / "tools/e2e/hosta_preview_e2e.sh"), "--repo", str(ROOT), "--commit", COMMIT],
        capture_output=True, text=True, timeout=10, env={**os.environ,
            "PATH": str(tools) + ":" + os.environ["PATH"], "HOME": str(home), "TMPDIR": str(scratch),
            "TEST_ARCHIVE": str(archive), "TEST_MODE": mode, "TEST_WORK": str(work)})
    assert (result.returncode == 0) == (mode == "pass"), result.stderr
    assert work.exists() and not Path(work.read_text()).exists()
    assert list(scratch.iterdir()) == []
    assert "synthetic detail" not in result.stdout
