"""Offline release capture and rollback checks; no host or cluster access."""
from copy import deepcopy
import gzip
import importlib.util
import json
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_snapshot", ROOT / "tools/delivery/release_snapshot.py")
rs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rs)
IMAGE = "example.invalid/synthetic-service@sha256:" + "a" * 64
COMMIT = "b" * 40


def workload(kind="Deployment", image=IMAGE, name="synthetic-service"):
    spec = {"containers": [{"name": "service", "image": image}],
            "initContainers": [{"name": "prepare", "image": IMAGE}]}
    template = {"metadata": {"labels": {"app": "synthetic"}}, "spec": spec}
    outer = {"template": template, "replicas": 1}
    if kind == "CronJob":
        outer = {"jobTemplate": {"spec": {"template": template}}, "schedule": "0 0 * * *"}
    return {"apiVersion": "apps/v1", "kind": kind,
            "metadata": {"name": name, "namespace": "ui2", "uid": "synthetic-uid",
                         "generation": 1, "resourceVersion": "5", "managedFields": [{}],
                         "annotations": {"kubectl.kubernetes.io/last-applied-configuration": "synthetic-value"}},
            "spec": outer, "status": {"readyReplicas": 1, "updatedReplicas": 1, "observedGeneration": 1}}


def mock_cluster(monkeypatch, version="89", items=None):
    calls = []
    objects = items if items is not None else [workload(), workload("Job", name="synthetic-job"),
                                              {"apiVersion": "v1", "kind": "PersistentVolumeClaim",
                                               "metadata": {"name": "synthetic-context"},
                                               "spec": {"accessModes": ["ReadWriteOnce"]}}]

    def run(*args, input=None):
        calls.append((args, input))
        if args[0] == "curl":
            return b"200"
        if "namespace" in args:
            return json.dumps({"apiVersion": "v1", "kind": "Namespace",
                               "metadata": {"name": args[3]}}).encode()
        if "get" in args:
            resource = args[args.index("get") + 1]
            if resource == "secrets":
                assert "jsonpath=" in args[-1] and ".data" not in args[-1]
                return b"synthetic-secret\t8\n"
            return json.dumps({"items": [] if resource == "pods" else objects}).encode()
        if "cat" in args:
            return json.dumps({"commit": COMMIT}).encode()
        command = args[-1]
        if "flyway_schema_history" in command:
            return version.encode()
        if "pg_dump" in command:
            assert "--schema-only" in command
            return b"CREATE TABLE synthetic_table (id integer);"
        if "count(*)" in command:
            return b"0"
        return b""

    monkeypatch.setattr(rs, "run", run)
    return calls


def capture(tmp_path, monkeypatch):
    calls = mock_cluster(monkeypatch)
    rs.snapshot(tmp_path)
    directory = next(p for p in tmp_path.iterdir() if p.name[0] != ".")
    return directory, calls


def diff_mock(monkeypatch, code=1):
    calls = []

    def diff(args, **kwargs):
        calls.append((args, kwargs))
        return subprocess.CompletedProcess(args, code, b"synthetic local diff\n", b"")

    monkeypatch.setattr(rs.subprocess, "run", diff)
    return calls


def test_prune_secret_and_embedded_last_applied():
    secret = {"kind": "Secret", "metadata": {"name": "synthetic-secret", "resourceVersion": "8"},
              "data": {"synthetic": "synthetic-value"}, "stringData": {"synthetic": "synthetic-value"}}
    assert rs.prune(secret) == {"name": "synthetic-secret", "resourceVersion": "8"}
    cleaned = rs.prune(workload())
    assert "status" not in cleaned
    assert not {"uid", "resourceVersion", "managedFields", "generation"} & cleaned["metadata"].keys()
    assert "last-applied" not in json.dumps(cleaned)


def test_capture_payloads_digests_schema_checksums_and_secret_projection(tmp_path, monkeypatch, capsys):
    directory, calls = capture(tmp_path, monkeypatch)
    manifest = rs.load_snapshot(directory)
    assert manifest["deployed_commit"] == COMMIT
    assert manifest["schema_version"] == "89"
    assert directory.stat().st_mode & 0o777 == 0o700
    assert json.loads(capsys.readouterr().out)["snapshot"] == directory.name
    assert gzip.decompress((directory / "schema.sql.gz").read_bytes()).startswith(b"CREATE TABLE")
    assert all(i["image"] == IMAGE for i in manifest["images"]["ui2"])
    assert json.loads((directory / "ui2-secrets.json").read_text()) == [{"name": "synthetic-secret", "resourceVersion": "8"}]
    assert all("last-applied" not in p.read_text() for p in directory.glob("*.yaml"))
    assert any("/app/project/deploy_info.json" in args for args, _ in calls)
    assert all("apply" not in args for args, _ in calls)


def test_resolve_tagged_init_and_regular_images_and_refuse_ambiguity():
    item = workload(image="example.invalid/synthetic:tag")
    pod = {"kind": "Pod", "spec": deepcopy(item["spec"]["template"]["spec"]),
           "status": {"containerStatuses": [{"name": "service", "imageID": "docker-pullable://" + IMAGE}]}}
    assert rs.pin_images([item], [pod])[0]["image"] == IMAGE
    assert item["spec"]["template"]["spec"]["containers"][0]["image"] == IMAGE
    with pytest.raises(RuntimeError, match="digest"):
        rs.pin_images([workload(image="example.invalid/synthetic:missing")], [])
    second = deepcopy(pod)
    second["status"]["containerStatuses"][0]["imageID"] = IMAGE.replace("a" * 64, "c" * 64)
    with pytest.raises(RuntimeError, match="ambiguous"):
        rs.pin_images([workload(image="example.invalid/synthetic:tag")], [pod, second])


def test_retention_only_prunes_complete_snapshot_directories(tmp_path):
    for i in range(23):
        directory = tmp_path / f"20261005T010000{i:06d}Z_aaaaaaaaaaaa"
        directory.mkdir()
        (directory / "manifest.json").write_text("{}")
    unrelated = tmp_path / "keep-me"
    unrelated.mkdir()
    partial = tmp_path / ".capture.partial"
    partial.mkdir()
    rs.retain(tmp_path)
    complete = sorted(p for p in tmp_path.iterdir() if rs.SNAPSHOT_ID.fullmatch(p.name))
    assert len(complete) == 20
    assert complete[0].name == "20261005T010000000003Z_aaaaaaaaaaaa"
    assert unrelated.exists() and partial.exists()


def test_unstable_or_incomplete_snapshot_fails_without_publishing(tmp_path, monkeypatch):
    item = workload()
    item["status"]["readyReplicas"] = 0
    mock_cluster(monkeypatch, items=[item])
    with pytest.raises(RuntimeError, match="stable"):
        rs.snapshot(tmp_path)
    assert not list(tmp_path.iterdir())


def test_dry_run_diff_never_mutates_and_reports_added_module(tmp_path, monkeypatch, capfd):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch, items=[workload(), workload(name="synthetic-new-module")])
    diffs = diff_mock(monkeypatch)
    rs.rollback(tmp_path, "latest", False, False)
    output = capfd.readouterr().out
    assert "synthetic local diff" in output and "Dry run only" in output
    assert "Remove added resource: ui2/Deployment/synthetic-new-module" in output
    assert len(diffs) == 3
    assert not any(set(args) & {"apply", "delete", "rollout"} for args, _ in calls)
    assert directory.name in json.loads((directory / "manifest.json").read_text())["snapshot"]


@pytest.mark.parametrize("version,override", [("90", False), ("88", True)])
def test_schema_mismatch_refuses_before_diff_or_apply(tmp_path, monkeypatch, version, override):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch, version=version)
    diffs = diff_mock(monkeypatch)
    with pytest.raises(RuntimeError, match="schema"):
        rs.rollback(tmp_path, str(directory), True, override)
    assert not diffs
    assert not any("apply" in args for args, _ in calls)


def test_apply_override_pins_images_waits_site_and_never_replays_jobs_or_secrets(tmp_path, monkeypatch):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch, version="90", items=[workload(), workload(name="synthetic-new-module")])
    diff_mock(monkeypatch)
    rs.rollback(tmp_path, str(directory), True, True)
    applied = [json.loads(payload) for args, payload in calls if "apply" in args]
    assert len(applied) == 3
    assert all(i["kind"] not in {"Job", "Pod", "Secret", "PersistentVolumeClaim"} for p in applied for i in p["items"])
    assert all(c["image"] == IMAGE for p in applied for i in p["items"]
               if rs.pod_spec(i) for c in rs.pod_spec(i)["containers"])
    assert any("rollout" in args for args, _ in calls)
    assert any("delete" in args and "synthetic-new-module" in args for args, _ in calls)
    assert any(args[0] == "curl" for args, _ in calls)
    assert not any("schema.sql.gz" in args for args, _ in calls)
    assert not any("delete" in args and "persistentvolumeclaim" in args for args, _ in calls)


def test_corrupt_snapshot_or_diff_error_refuses_apply(tmp_path, monkeypatch):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch)
    diff_mock(monkeypatch, 2)
    with pytest.raises(RuntimeError, match="diff failed"):
        rs.rollback(tmp_path, str(directory), True, False)
    assert not any("apply" in args for args, _ in calls)
    (directory / "ui2.yaml").write_text("{}")
    with pytest.raises(RuntimeError, match="checksum"):
        rs.load_snapshot(directory)


def test_new_statefulset_refuses_storage_unsafe_pruning(tmp_path, monkeypatch):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch, items=[workload(), workload("StatefulSet", name="synthetic-new-state")])
    diff_mock(monkeypatch)
    with pytest.raises(RuntimeError, match="storage-safe"):
        rs.rollback(tmp_path, str(directory), True, False)
    assert not any("apply" in args or "delete" in args for args, _ in calls)


def test_build_snapshot_precedes_every_mutation_and_optional_apply():
    script = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    snapshot = script.index("bash tools/delivery/release_snapshot.sh")
    assert snapshot < script.index("kubectl apply") < script.index("security_host.py snapshot")
    deploy = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    assert "for f in $APPLY_NAMES; do kubectl apply" not in deploy
    assert 'export NEXUS_DEPLOY_APPLY_FILES="$APPLY_NAMES"' in deploy
    assert "trap rollback_hint EXIT" in deploy


def test_configmap_data_is_preserved_verbatim():
    config = {"kind": "ConfigMap", "metadata": {"name": "synthetic-config"},
              "data": {"status": "synthetic-ready", "metadata": "synthetic-value"}}
    assert rs.prune(config)["data"] == config["data"]


def test_failed_deploy_snapshot_is_projected_once_into_ship_log(tmp_path, monkeypatch, capsys):
    import sys
    sys.path.insert(0, str(ROOT / "tools/delivery"))
    import standalone_orchestrate as sa
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    snapshot_id = "20261005T010000000000Z_aaaaaaaaaaaa"

    def deploy(args, **kwargs):
        kwargs["stdout"].write(json.dumps({"snapshot": snapshot_id}) + "\n")
        kwargs["stdout"].write(json.dumps({"snapshot": snapshot_id}) + "\n")
        return subprocess.CompletedProcess(args, 1)

    monkeypatch.setattr(sa.subprocess, "run", deploy)
    with pytest.raises(SystemExit, match="deploy failed"):
        sa._deploy()
    assert capsys.readouterr().out.splitlines() == [json.dumps({"snapshot": snapshot_id})]


def test_missing_snapshot_stops_ship_before_host_access(tmp_path, monkeypatch):
    import standalone_orchestrate as sa
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)

    def deploy(args, **kwargs):
        assert args[0] == "bash"
        kwargs["stdout"].write('{"security_gate":"not_configured"}\n')
        return subprocess.CompletedProcess(args, 0)

    monkeypatch.setattr(sa.subprocess, "run", deploy)
    with pytest.raises(SystemExit, match="release snapshot required"):
        sa._deploy()


def test_snapshot_shell_entrypoint_permissions_and_no_remote_access(tmp_path):
    import os
    import sys
    binary = tmp_path / "bin"
    binary.mkdir()
    fake = binary / "kubectl"
    fake.write_text(f"#!{sys.executable}\n" + '''import json, sys
args = sys.argv[1:]
if "diff" in args:
    print("synthetic shell diff")
elif "cat" in args:
    print(json.dumps({"commit": "b" * 40}))
elif "namespace" in args:
    print(json.dumps({"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": args[2]}}))
elif "get" in args:
    if "secrets" not in args:
        print('{"items": []}')
elif "pg_dump" in args[-1]:
    print("CREATE TABLE synthetic_table(id integer);")
elif "flyway_schema_history" in args[-1]:
    print("89")
else:
    sys.exit(99)
''')
    fake.chmod(0o755)
    env = dict(os.environ, HOME=str(tmp_path), PATH=str(binary) + os.pathsep + os.environ["PATH"])
    result = subprocess.run(["bash", str(ROOT / "tools/delivery/release_snapshot.sh")],
                            env=env, capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    snapshot_id = json.loads(result.stdout)["snapshot"]
    directory = tmp_path / "release-snapshots" / snapshot_id
    assert directory.stat().st_mode & 0o777 == 0o700
    assert all(p.stat().st_mode & 0o777 == 0o600 for p in directory.iterdir())
    assert (directory / "manifest.json").is_file()

    preview = subprocess.run(["bash", str(ROOT / "tools/delivery/rollback.sh"), "latest"],
                             env=env, capture_output=True, text=True, timeout=10)
    assert preview.returncode == 0, preview.stderr
    assert "Dry run only" in preview.stdout and "synthetic shell diff" in preview.stdout


@pytest.mark.parametrize("condition", ["inflight", "site"])
def test_apply_checks_inflight_and_site_status(tmp_path, monkeypatch, condition):
    directory, _ = capture(tmp_path, monkeypatch)
    calls = mock_cluster(monkeypatch)
    original = rs.run

    def guarded(*args, **kwargs):
        if condition == "inflight" and "count(*)" in args[-1]:
            return b"1"
        if condition == "site" and args[0] == "curl":
            return b"503"
        return original(*args, **kwargs)

    monkeypatch.setattr(rs, "run", guarded)
    diff_mock(monkeypatch)
    with pytest.raises(RuntimeError, match="jobs in flight" if condition == "inflight" else "site"):
        rs.rollback(tmp_path, str(directory), True, False)
    if condition == "inflight":
        assert not any("apply" in args for args, _ in calls)


def test_statefulset_scale_down_cannot_delete_claims(tmp_path, monkeypatch):
    saved = workload("StatefulSet", name="synthetic-state")
    mock_cluster(monkeypatch, items=[saved])
    rs.snapshot(tmp_path)
    live = deepcopy(saved)
    live["spec"]["replicas"] = 2
    live["spec"]["persistentVolumeClaimRetentionPolicy"] = {"whenScaled": "Delete"}
    calls = mock_cluster(monkeypatch, items=[live])
    diff_mock(monkeypatch)
    with pytest.raises(RuntimeError, match="delete PVCs"):
        rs.rollback(tmp_path, "latest", True, False)
    assert not any("apply" in args or "delete" in args for args, _ in calls)
