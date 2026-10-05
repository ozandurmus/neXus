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
        if "to_regclass" in command:
            return b"f"
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
    assert snapshot < script.index("kubectl apply") < script.index('security_host.py" snapshot')
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
elif "to_regclass" in args[-1]:
    print("f")
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


def resource_fixture(kind):
    """Synthetic API objects with the structural fields returned by Kubernetes."""
    if kind in {"Deployment", "StatefulSet", "DaemonSet", "CronJob", "Job"}:
        item = workload(kind)
        item["apiVersion"] = "batch/v1" if kind in {"CronJob", "Job"} else "apps/v1"
        if kind in {"CronJob", "Job", "DaemonSet"}:
            item["spec"].pop("replicas", None)
        if kind == "DaemonSet":
            item["status"] = {"desiredNumberScheduled": 1, "numberReady": 1,
                              "updatedNumberScheduled": 1, "observedGeneration": 1}
        if kind == "Job":
            item["spec"]["selector"] = {"matchLabels": {"batch.kubernetes.io/controller-uid": "synthetic"}}
            item["spec"]["template"]["metadata"]["labels"]["batch.kubernetes.io/job-name"] = "synthetic-job"
    else:
        item = {"apiVersion": "v1", "kind": kind,
                "metadata": {"name": "synthetic-resource", "namespace": "ui2"}}
        fields = {
            "Service": {"spec": {"ports": [{"port": 80, "targetPort": 8080}],
                                   "selector": {"app": "synthetic"}, "clusterIP": "192.0.2.10"}},
            "ConfigMap": {"data": {"status": "synthetic", "metadata": "preserve"}},
            "NetworkPolicy": {"spec": {"podSelector": {"matchLabels": {"app": "synthetic"}},
                                         "policyTypes": ["Ingress"], "ingress": []}},
            "ServiceAccount": {"automountServiceAccountToken": False,
                               "secrets": [{"name": "synthetic-token"}]},
            "Role": {"rules": [{"apiGroups": [""], "resources": ["pods"], "verbs": ["get"]}]},
            "RoleBinding": {"roleRef": {"apiGroup": "rbac.authorization.k8s.io", "kind": "Role",
                                         "name": "synthetic-role"},
                            "subjects": [{"kind": "ServiceAccount", "name": "synthetic-reader", "namespace": "ui2"}]},
            "PodDisruptionBudget": {"spec": {"minAvailable": 1, "selector": {"matchLabels": {"app": "synthetic"}}}},
            "PersistentVolumeClaim": {"spec": {"accessModes": ["ReadWriteOnce"],
                                               "resources": {"requests": {"storage": "1Gi"}}}},
            "Namespace": {},
            "Pod": {"spec": {"containers": [{"name": "synthetic", "image": IMAGE}]}}
        }
        item.update(fields[kind])
        item["apiVersion"] = {"NetworkPolicy": "networking.k8s.io/v1", "Role": "rbac.authorization.k8s.io/v1",
                              "RoleBinding": "rbac.authorization.k8s.io/v1", "PodDisruptionBudget": "policy/v1"}.get(kind, "v1")
    item["metadata"].update({"uid": "synthetic-uid", "resourceVersion": "5", "generation": 1,
                               "creationTimestamp": "2026-10-05T00:00:00Z", "managedFields": [{}],
                               "annotations": {"kubectl.kubernetes.io/last-applied-configuration": "synthetic",
                                               "synthetic.example/keep": "preserve"}})
    item.setdefault("status", {"synthetic": "server-state"})
    return item


CAPTURE_KINDS = ("Deployment", "StatefulSet", "DaemonSet", "CronJob", "Job", "Service", "ConfigMap",
                 "NetworkPolicy", "ServiceAccount", "Role", "RoleBinding", "PodDisruptionBudget",
                 "PersistentVolumeClaim", "Namespace", "Pod")


@pytest.mark.parametrize("kind", CAPTURE_KINDS)
def test_prune_realistic_captured_kind(kind):
    item = resource_fixture(kind)
    original = deepcopy(item)
    cleaned = rs.prune(item)
    assert item == original
    assert "status" not in cleaned
    assert not {"uid", "resourceVersion", "generation", "creationTimestamp", "managedFields"} & cleaned["metadata"].keys()
    assert cleaned["metadata"]["annotations"] == {"synthetic.example/keep": "preserve"}
    for field in ("data", "rules", "subjects", "roleRef", "automountServiceAccountToken"):
        if field in item:
            assert cleaned[field] == item[field]
    if kind == "ServiceAccount":
        assert "secrets" not in cleaned
    if kind == "Job":
        assert "selector" not in cleaned["spec"]
        assert "batch.kubernetes.io/job-name" not in cleaned["spec"]["template"]["metadata"]["labels"]
    if rs.pod_spec(item) is not None:
        assert rs.pod_spec(cleaned) == rs.pod_spec(item)


@pytest.mark.parametrize("kind", CAPTURE_KINDS)
@pytest.mark.parametrize("missing", ["spec", "template"])
def test_prune_missing_spec_or_template(kind, missing):
    item = resource_fixture(kind)
    if missing == "spec":
        item.pop("spec", None)
    else:
        spec = item.get("spec", {})
        spec.pop("template", None)
        spec.get("jobTemplate", {}).get("spec", {}).pop("template", None)
    assert "status" not in rs.prune(item)
    assert rs.pod_spec(item) is None or kind == "Pod"


def test_snapshot_all_captured_shapes(tmp_path, monkeypatch):
    mock_cluster(monkeypatch, items=[resource_fixture(k) for k in CAPTURE_KINDS if k not in {"Namespace", "Pod"}])
    rs.snapshot(tmp_path)
    directory = next(tmp_path.iterdir())
    assert rs.load_snapshot(directory)["schema_version"] == "89"
    captured = json.loads((directory / "ui2.yaml").read_text())["items"]
    assert {i["kind"] for i in captured} == set(CAPTURE_KINDS) - {"Pod"}
    assert all("status" not in i for i in captured)


@pytest.mark.parametrize("prefix", ["docker-pullable://", ""])
def test_status_digest_formats_for_regular_and_init_containers(prefix):
    item = workload(image="example.invalid/synthetic:tag")
    spec = item["spec"]["template"]["spec"]
    spec["initContainers"][0]["image"] = "example.invalid/synthetic-init:tag"
    pod = {"kind": "Pod", "spec": deepcopy(spec), "status": {
        "containerStatuses": [{"name": "service", "imageID": prefix + IMAGE}],
        "initContainerStatuses": [{"name": "prepare", "imageID": prefix + IMAGE}]}}
    assert [i["image"] for i in rs.pin_images([item], [pod])] == [IMAGE, IMAGE]


@pytest.mark.parametrize("response", [b"89\n", b"89.1\n", b"89_2\n"])
def test_flyway_query_shape(monkeypatch, response):
    def query(*args):
        assert args[:7] == ("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh")
        assert "psql" in args[-1] and "-Atc" in args[-1]
        assert "where success and version is not null order by installed_rank desc limit 1" in args[-1]
        return response
    monkeypatch.setattr(rs, "run", query)
    assert rs.schema_version() == response.decode().strip()


@pytest.mark.parametrize("response", [b"", b"version\n89\n(1 row)", b"synthetic-invalid"])
def test_flyway_invalid_result_refuses(monkeypatch, response):
    monkeypatch.setattr(rs, "run", lambda *args: response)
    with pytest.raises(RuntimeError, match="Flyway"):
        rs.schema_version()


@pytest.mark.parametrize("response", [b"{}", b"not-json", b'{"commit": null}', b'{"commit": "invalid"}', b"[]"])
def test_deployed_commit_invalid_metadata_falls_back(monkeypatch, response):
    monkeypatch.setattr(rs, "run", lambda *args: response)
    assert rs.deployed_commit(COMMIT) == COMMIT
    with pytest.raises(RuntimeError, match="fallback"):
        rs.deployed_commit(None)


def test_deployed_commit_exec_failure_falls_back(tmp_path, monkeypatch):
    mock_cluster(monkeypatch)
    original = rs.run
    def run(*args, **kwargs):
        if "cat" in args:
            assert "deployment/ui2-service" in args
            raise RuntimeError("synthetic diagnostic must not escape")
        return original(*args, **kwargs)
    monkeypatch.setattr(rs, "run", run)
    rs.snapshot(tmp_path, COMMIT)
    assert rs.load_snapshot(next(tmp_path.iterdir()))["deployed_commit"] == COMMIT


def test_deployed_commit_prefers_running_release(monkeypatch):
    mock_cluster(monkeypatch)
    assert rs.deployed_commit("c" * 40) == COMMIT


def test_completed_jobs_and_e2e_pods_do_not_block_snapshot(tmp_path, monkeypatch):
    job = resource_fixture("Job")
    job["spec"]["template"]["spec"]["containers"][0]["image"] = "example.invalid/synthetic:old"
    job["status"] = {"conditions": [{"type": "Complete", "status": "True"}], "succeeded": 1}
    pod = resource_fixture("Pod")
    pod["spec"]["containers"][0]["image"] = "example.invalid/synthetic-e2e:old"
    pod["status"] = {"phase": "Succeeded"}
    mock_cluster(monkeypatch, items=[resource_fixture("Deployment"), job])
    original = rs.get
    monkeypatch.setattr(rs, "get", lambda ns, resources: {"items": [pod]} if resources == "pods" else original(ns, resources))
    rs.snapshot(tmp_path)
    manifest = rs.load_snapshot(next(tmp_path.iterdir()))
    assert all(i["kind"] == "Deployment" for i in manifest["images"]["ui2"])


@pytest.mark.parametrize("failure,expected", [
    (KeyError("synthetic-private-value"), "required resource field missing"),
    (ValueError("synthetic-private-value\nsecond line"), "invalid response or snapshot format"),
    (OSError("synthetic-private-value"), "local prerequisite or filesystem operation failed"),
    (subprocess.TimeoutExpired(["synthetic-private-value"], 1), "local command timed out"),
    (RuntimeError("synthetic-private-value"), "raw exception details withheld")])
def test_main_reports_one_safe_step_class_message(tmp_path, monkeypatch, capsys, failure, expected):
    import sys
    monkeypatch.setattr(rs.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(sys, "argv", ["release_snapshot.py", "snapshot"])
    mock_cluster(monkeypatch)
    def prune(item):
        raise failure
    monkeypatch.setattr(rs, "prune", prune)
    assert rs.main() == 1
    output = capsys.readouterr()
    assert output.out == ""
    assert len(output.err.splitlines()) == 1
    assert f"resource pruning: {type(failure).__name__}:" in output.err
    assert expected in output.err
    assert "synthetic-private-value" not in output.err
    assert not list((tmp_path / "release-snapshots").glob("*.partial"))


def test_build_passes_commit_before_snapshot():
    script = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    assert script.index('COMMIT_SHA="$(git rev-parse HEAD)"') < script.index("bash tools/delivery/release_snapshot.sh")
    assert 'release_snapshot.sh --commit "$COMMIT_SHA"' in script


@pytest.mark.parametrize("kind", ["Deployment", "StatefulSet", "DaemonSet", "CronJob", "Job"])
def test_nested_template_server_metadata_is_pruned(kind):
    item = resource_fixture(kind)
    spec = item["spec"]
    if kind == "CronJob":
        spec = spec["jobTemplate"]["spec"]
        item["spec"]["jobTemplate"]["metadata"] = deepcopy(item["metadata"])
    spec["template"]["metadata"].update(deepcopy(item["metadata"]))
    cleaned = rs.prune(item)
    text = json.dumps(cleaned)
    assert "kubectl.kubernetes.io/" not in text
    assert "resourceVersion" not in text and "creationTimestamp" not in text
    assert "managedFields" not in text and "synthetic-uid" not in text
    assert rs.pod_spec(cleaned) == rs.pod_spec(item)


@pytest.mark.parametrize("kind", ["Deployment", "StatefulSet", "DaemonSet"])
def test_stability_refuses_real_workload_rollout(tmp_path, monkeypatch, kind):
    item = resource_fixture(kind)
    item["status"]["observedGeneration"] = 0
    mock_cluster(monkeypatch, items=[item])
    with pytest.raises(RuntimeError, match="stable") as raised:
        rs.snapshot(tmp_path)
    assert raised.value.release_step == "workload stability"
    assert not list(tmp_path.iterdir())


def test_schema_change_during_capture_refuses_publication(tmp_path, monkeypatch):
    mock_cluster(monkeypatch)
    versions = iter(["89", "90"])
    monkeypatch.setattr(rs, "schema_version", lambda: next(versions))
    with pytest.raises(RuntimeError, match="schema changed") as raised:
        rs.snapshot(tmp_path)
    assert raised.value.release_step == "Flyway version verification"
    assert not list(tmp_path.iterdir())


def test_command_failure_does_not_expose_output(monkeypatch):
    monkeypatch.setattr(rs.subprocess, "run", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args, 1, b"synthetic-private-output", b"synthetic-private-error"))
    with pytest.raises(rs.SafeReleaseError) as raised:
        rs.run("kubectl", "synthetic-argument")
    assert rs.failure_message(raised.value) == "kubectl failed (exit 1)"


def test_main_accepts_commit_fallback(tmp_path, monkeypatch):
    import sys
    monkeypatch.setattr(rs.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(sys, "argv", ["release_snapshot.py", "snapshot", "--commit", COMMIT])
    calls = []
    monkeypatch.setattr(rs, "snapshot", lambda root, commit: calls.append((root, commit)))
    assert rs.main() == 0
    assert calls == [(tmp_path / "release-snapshots", COMMIT)]
