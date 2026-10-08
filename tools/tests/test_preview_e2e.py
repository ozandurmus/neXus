"""Offline candidate gate: manifests, streaming failures, teardown and ship ordering."""
import argparse
import base64
import copy
from contextlib import contextmanager
from datetime import datetime, timezone
import io
import json
import re
import shlex
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
ENDPOINTS = {"ui2-compliance": "http://192.0.2.30:8085",
             "ui2-configuration": "http://192.0.2.40:8084"}


def template(path, kind=None):
    docs = list(yaml.safe_load_all((ROOT / path).read_text()))
    return next(d for d in docs if kind is None or d["kind"] == kind)


def local_load(repo, path):
    docs = list(yaml.safe_load_all((repo / path).read_text()))
    return dict(kind="List", items=docs)


def container_image(deployment):
    return deployment["spec"]["template"]["spec"]["containers"][0]["image"]


@pytest.mark.parametrize("path", [
    "deploy/ui2/50-service-deployment.yaml",
    "deploy/ui2/52-worker-deployment.yaml",
    "deploy/ui2/54-configuration-deployment.yaml",
    "deploy/ui2/55-compliance-deployment.yaml",
    "deploy/ui2/57-policy-deployment.yaml",
])
@pytest.mark.parametrize("entry", [
    None,
    {"name": "UI2_JOB_WINDOW_MINUTES", "value": "60"},
    {"name": "UI2_JOB_WINDOW_MINUTES",
     "valueFrom": {"configMapKeyRef": {"name": "synthetic-config", "key": "window"}}},
])
def test_preview_job_windows_are_contiguous_without_changing_live_templates(path, entry):
    source = template(path, "Deployment")
    for container in source["spec"]["template"]["spec"]["containers"]:
        container["env"] = [e for e in container.get("env", []) if e["name"] != "UI2_JOB_WINDOW_MINUTES"]
        if entry is not None:
            container["env"].append(copy.deepcopy(entry))
    before = copy.deepcopy(source)
    candidate = preview.workload_manifest(source, IMAGE, "192.0.2.10", ENDPOINTS)
    assert candidate["metadata"]["namespace"] == preview.NS
    for container in candidate["spec"]["template"]["spec"]["containers"]:
        assert [e for e in container["env"] if e["name"] == "UI2_JOB_WINDOW_MINUTES"] == [
            {"name": "UI2_JOB_WINDOW_MINUTES", "value": "360"}]
    assert source == before
    assert all(e["name"] != "UI2_JOB_WINDOW_MINUTES"
               for container in preview.database_manifest(template("deploy/ui2/40-database-statefulset.yaml"))["spec"]["containers"]
               for e in container.get("env", []))


@pytest.mark.parametrize("entry", [
    None,
    {"name": "NEXUS_FAILOVER_MUTATION_ENABLED", "value": "true"},
    {"name": "NEXUS_FAILOVER_MUTATION_ENABLED",
     "valueFrom": {"configMapKeyRef": {"name": "synthetic-config", "key": "mutation"}}},
])
def test_preview_forces_failover_mutation_disabled_without_changing_source(entry):
    source = template("deploy/ui2/50-service-deployment.yaml")
    container = source["spec"]["template"]["spec"]["containers"][0]
    container["env"] = [e for e in container["env"] if e["name"] != "NEXUS_FAILOVER_MUTATION_ENABLED"]
    if entry is not None:
        container["env"].append(entry)
    before = copy.deepcopy(source)
    candidate = preview.workload_manifest(source, IMAGE, "192.0.2.10", ENDPOINTS)
    env = candidate["spec"]["template"]["spec"]["containers"][0]["env"]
    assert [e for e in env if e["name"] == "NEXUS_FAILOVER_MUTATION_ENABLED"] == [
        {"name": "NEXUS_FAILOVER_MUTATION_ENABLED", "value": "false"}]
    assert source == before


@pytest.mark.parametrize("size,expected", [(None, "32Gi"), ("48Gi", "48Gi")])
def test_database_data_capacity_is_configurable_and_isolated(monkeypatch, size, expected):
    if size is None:
        monkeypatch.delenv("NEXUS_PREVIEW_DB_DATA_SIZE_LIMIT", raising=False)
    else:
        monkeypatch.setenv("NEXUS_PREVIEW_DB_DATA_SIZE_LIMIT", size)
    source = template("deploy/ui2/40-database-statefulset.yaml")
    source["spec"]["template"]["spec"]["volumes"].append(
        dict(name="synthetic-scratch", persistentVolumeClaim=dict(claimName="synthetic-claim")))
    before = copy.deepcopy(source)
    database = preview.database_manifest(source)["spec"]["volumes"]
    ordinary = preview.pod_spec(source)["volumes"]
    assert next(v for v in database if v["name"] == "data")["emptyDir"] == {"sizeLimit": expected}
    assert next(v for v in database if v["name"] == "synthetic-scratch")["emptyDir"] == {"sizeLimit": "8Gi"}
    assert all(v["emptyDir"]["sizeLimit"] == "8Gi" for v in ordinary if "sizeLimit" in v.get("emptyDir", {}))
    assert source == before


@pytest.mark.parametrize("size", ["0Gi", "-1Gi", "", "32Gi; synthetic"])
def test_database_data_capacity_rejects_invalid_limits(monkeypatch, size):
    monkeypatch.setenv("NEXUS_PREVIEW_DB_DATA_SIZE_LIMIT", size)
    with pytest.raises(ValueError):
        preview.database_manifest(template("deploy/ui2/40-database-statefulset.yaml"))


@pytest.mark.parametrize("status,cause", [
    ({"phase": "Failed", "reason": "Evicted", "message":
      "synthetic-private 192.0.2.15 exceeded ephemeral-storage synthetic-secret"}, "ephemeral-storage"),
    ({"phase": "Failed", "reason": "Evicted"}, "Evicted"),
    ({"phase": "Running", "containerStatuses": [{"state": {"terminated": {"reason": "OOMKilled"}}}]}, "OOMKilled"),
    ({"phase": "Succeeded"}, "UNKNOWN"),
    ({"phase": "Failed", "reason": "synthetic-private"}, "UNKNOWN"),
    ({"phase": "Running"}, None),
])
def test_database_watch_reports_only_projected_causes(monkeypatch, status, cause, capsys):
    def get(namespace, *args, **kwargs):
        assert namespace == preview.NS
        assert args == ("get", "pod", preview.preview_name("ui2-preview-db"), "-o", "json")
        assert kwargs == {"timeout": 10}
        return json.dumps({"status": status})
    monkeypatch.setattr(preview, "k", get)
    if cause is None:
        preview.check_preview_database()
        return
    with pytest.raises(preview.PreviewFailure) as caught:
        with preview.timing("preview_db_copy"):
            preview.check_preview_database()
    line = preview.failure_line(caught.value)
    assert f"reason=DB_COPY_FAILED:PREVIEW_DB_EVICTED:{cause}" in line
    assert all(value not in line + str(caught.value) + capsys.readouterr().out
               for value in ("synthetic-private", "192.0.2.15", "synthetic-secret"))


def test_database_watch_distinguishes_missing_pod_from_unavailable_evidence(monkeypatch):
    for reason in ("NOT_FOUND", "FORBIDDEN", "TIMEOUT"):
        def get(*args, **kwargs):
            raise preview.PreviewFailure(reason)
        monkeypatch.setattr(preview, "k", get)
        with pytest.raises(preview.PreviewFailure) as caught:
            preview.check_preview_database()
        expected = "DB_COPY_FAILED_PREVIEW_DB_EVICTED:NOT_FOUND" if reason == "NOT_FOUND" else reason
        assert caught.value.reason == expected


def test_database_watch_detects_pod_deletion(monkeypatch):
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps({
        "metadata": {"deletionTimestamp": "2026-10-07T00:00:00Z"}, "status": {"phase": "Running"}}))
    with pytest.raises(preview.PreviewFailure) as caught:
        preview.check_preview_database()
    assert caught.value.reason == "DB_COPY_FAILED_PREVIEW_DB_EVICTED:UNKNOWN"


@pytest.mark.parametrize("cause", ["FORBIDDEN", "TIMEOUT", "DB_COPY_FAILED"])
def test_database_failure_line_surfaces_category_instead_of_exception_class(monkeypatch, cause):
    monkeypatch.setattr(preview, "PHASE", "preview_db_copy")
    line = preview.failure_line(preview.PreviewFailure(cause))
    assert line.endswith("reason=DB_COPY_FAILED:" + ("OTHER" if cause == "DB_COPY_FAILED" else cause))


def test_database_stream_watch_allows_success(local_database_stream, monkeypatch):
    checks = []
    monkeypatch.setattr(preview, "check_preview_database", lambda: checks.append(True))
    preview.database_stream("printf synthetic", "cat", timeout=5)
    assert len(checks) >= 2


@pytest.mark.parametrize("sleep", [False, True])
def test_database_stream_watch_catches_eviction_and_reaps_processes(local_database_stream, monkeypatch, sleep):
    processes, checks = [], []
    original = preview.subprocess.Popen
    def start(*args, **kwargs):
        process = original(*args, **kwargs)
        processes.append(process)
        return process
    monkeypatch.setattr(preview.subprocess, "Popen", start)
    def watch():
        checks.append(True)
        if len(checks) == 2:
            raise preview.PreviewFailure("DB_COPY_FAILED_PREVIEW_DB_EVICTED:ephemeral-storage")
    monkeypatch.setattr(preview, "check_preview_database", watch)
    source = "exec sleep 30" if sleep else "printf synthetic"
    with pytest.raises(preview.PreviewFailure) as caught:
        preview.database_stream(source, "cat", timeout=5)
    assert caught.value.reason == "DB_COPY_FAILED_PREVIEW_DB_EVICTED:ephemeral-storage"
    assert len(processes) == 1 and processes[0].poll() is not None


def test_runtime_manifests_are_ephemeral_and_use_only_the_preview_database():
    db_source = template("deploy/ui2/40-database-statefulset.yaml")
    service_source = template("deploy/ui2/50-service-deployment.yaml")
    db = preview.database_manifest(db_source)
    service = preview.workload_manifest(service_source, IMAGE, "192.0.2.10", ENDPOINTS)
    e2e = preview.e2e_manifest(template("deploy/ui2/70-e2e-job.yaml", "Job"), IMAGE, "192.0.2.20")
    assert db["spec"]["containers"][0]["image"] == db_source["spec"]["template"]["spec"]["containers"][0]["image"]
    assert service_source["spec"]["template"]["spec"]["containers"][0]["image"] != IMAGE
    compliance_source = template("deploy/ui2/55-compliance-deployment.yaml", "Deployment")
    before = copy.deepcopy(compliance_source)
    compliance = preview.workload_manifest(compliance_source, IMAGE, "192.0.2.10", ENDPOINTS)
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


def test_network_policies_have_no_dns_internet_or_cross_namespace_exception(monkeypatch):
    monkeypatch.setattr(preview, "load", local_load)
    workers = preview.worker_templates(ROOT)
    objects = preview.policies(workers)
    db, service, *rest = objects
    assert db["spec"]["egress"] == []
    assert len(db["spec"]["ingress"]) == len(workers) + 1
    assert service["spec"]["egress"] == [preview.peer("database", [5432], "to"),
                                         preview.peer("configuration", [8084], "to"),
                                         preview.peer("compliance", [8085], "to")]
    assert rest[-1]["spec"]["egress"] == [preview.peer("service", [8080, 8086], "to")]
    assert len(objects) == len(workers) + 3
    for obj in objects:
        assert obj["spec"]["policyTypes"] == ["Ingress", "Egress"]
        assert "namespaceSelector" not in json.dumps(obj) and "ipBlock" not in json.dumps(obj)
        assert all(p["port"] in (5432, 8080, 8084, 8085, 8086)
                   for rule in obj["spec"]["egress"] for p in rule["ports"])


@pytest.fixture
def database_network_objects():
    source = dict(metadata=dict(labels={"statefulset.kubernetes.io/pod-name": "ui2-db-0"}),
                  spec=dict(containers=[dict(ports=[dict(name="postgresql", containerPort=5432)])]))
    target = dict(metadata=dict(labels={preview.LABEL: preview.NS, "app.kubernetes.io/component": "database"}),
                  status=dict(podIP="192.0.2.25"))
    labels = {preview.LABEL: "synthetic-owner", "kubernetes.io/metadata.name": preview.NS}
    return source, target, labels


@pytest.mark.parametrize("rules,allowed", [
    ([], False), ([{}], True),
    ([{"ports": [{"port": 80}]}], False),
    ([{"ports": [{"port": 5432, "protocol": "UDP"}]}], False),
    ([{"ports": [{"port": "postgresql"}]}], True),
    ([{"ports": [{"port": 5400, "endPort": 5500}]}], True),
    ([{"from": [{"podSelector": {}}]}], False),
    ([{"from": [{}]}], True),
    ([{"from": [{"namespaceSelector": {}}]}], True),
    ([{"from": [{"namespaceSelector": {"matchLabels": {preview.LABEL: "other"}}}]}], False),
    ([{"from": [{"namespaceSelector": {"matchExpressions": [
        {"key": preview.LABEL, "operator": "In", "values": ["synthetic-owner"]}]}}]}], True),
    ([{"from": [{"namespaceSelector": {}, "podSelector": {"matchLabels": {
        "app.kubernetes.io/component": "service"}}}]}], False),
    ([{"from": [{"ipBlock": {"cidr": "192.0.2.0/24"}}]}], True),
    ([{"from": [{"ipBlock": {"cidr": "192.0.2.0/24", "except": ["192.0.2.25/32"]}}]}], False),
])
def test_database_ingress_evaluates_existing_allowances(database_network_objects, rules, allowed):
    source, target, labels = database_network_objects
    policies = [dict(spec=dict(podSelector={}, ingress=rules))]
    assert preview.database_ingress_allowed(policies, source, target, labels) is allowed
    assert preview.database_ingress_allowed([], source, target, labels)
    policies.append(dict(spec=dict(podSelector={}, ingress=[{}])))
    assert preview.database_ingress_allowed(policies, source, target, labels)
    policies = [dict(spec=dict(podSelector={"matchLabels": {"app": "unrelated"}}, ingress=[])),
                dict(spec=dict(podSelector={}, policyTypes=["Egress"], egress=[]))]
    assert preview.database_ingress_allowed(policies, source, target, labels)


@pytest.mark.parametrize("blocked", [False, True])
@pytest.mark.parametrize("failure", [None, "create", "copy"])
def test_database_network_allowance_is_temporary_and_scoped(monkeypatch, database_network_objects, blocked, failure):
    source, target, labels = database_network_objects
    calls, objects = [], []
    def k(ns, *args, **kwargs):
        calls.append((ns, args))
        if args[0] == "delete":
            assert args[1:3] == ("networkpolicy", preview.preview_name("db-copy"))
            return ""
        if args[1] == "pod":
            return json.dumps(source if ns == "ui2" else target)
        if args[1] == "namespace":
            return json.dumps(dict(metadata=dict(labels=labels)))
        assert ns == "ui2" and args[1] == "networkpolicy"
        return json.dumps(dict(items=[dict(spec=dict(podSelector={}, ingress=[]))] if blocked else []))
    def create(ns, obj):
        objects.append((ns, obj))
        if failure == "create":
            raise RuntimeError("synthetic failure")
    monkeypatch.setattr(preview, "k", k)
    monkeypatch.setattr(preview, "create", create)
    def transfer():
        with preview.database_network_access():
            if failure == "copy":
                raise RuntimeError("synthetic failure")
    if failure:
        with pytest.raises(RuntimeError, match="synthetic failure"):
            transfer()
    else:
        transfer()
    assert [(ns, args[0]) for ns, args in calls[-2:]] == [("ui2", "delete"), (preview.NS, "delete")]
    assert len(objects) == (2 if blocked and failure != "create" else 1)
    ns, egress = objects[0]
    assert ns == preview.NS and egress["spec"]["policyTypes"] == ["Egress"]
    assert egress["spec"]["podSelector"]["matchLabels"] == target["metadata"]["labels"]
    rule = egress["spec"]["egress"][0]
    assert rule["ports"] == [dict(protocol="TCP", port=5432)]
    assert rule["to"] == [dict(namespaceSelector=dict(matchLabels={"kubernetes.io/metadata.name": "ui2"}),
                               podSelector=dict(matchLabels=source["metadata"]["labels"]))]
    if len(objects) == 2:
        ns, ingress = objects[1]
        assert ns == "ui2" and ingress["spec"]["policyTypes"] == ["Ingress"]
        rule = ingress["spec"]["ingress"][0]
        assert rule["ports"] == [dict(protocol="TCP", port=5432)]
        assert rule["from"] == [dict(namespaceSelector=dict(matchLabels=labels),
                                     podSelector=egress["spec"]["podSelector"])]


def test_source_credentials_are_separate_secret_env_refs_and_never_logged(monkeypatch, capsys):
    objects = []
    log = io.StringIO()
    monkeypatch.setattr(preview, "LOG", log)
    placeholder = base64.b64encode(b"synthetic-value").decode()
    secret = dict(data={key: placeholder for key in
                        ("migrate-user", "migrate-password", "app-password")})
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(secret))
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    preview.copy_secret("ui2-db", "ui2-db-source", ("migrate-user", "migrate-password"))
    assert objects[0]["metadata"]["name"] == preview.preview_name("ui2-db-source")
    assert objects[0]["data"] == {key: secret["data"][key] for key in ("migrate-user", "migrate-password")}
    pod = preview.database_manifest(template("deploy/ui2/40-database-statefulset.yaml"))
    container = pod["spec"]["containers"][0]
    env = {entry["name"]: entry for entry in container["env"]}
    assert not any(name.startswith("PG") for name in env)
    for name, key in (("SOURCE_DB_USER", "migrate-user"), ("SOURCE_DB_PASSWORD", "migrate-password")):
        assert env[name] == dict(name=name, valueFrom=dict(secretKeyRef=dict(
            name=objects[0]["metadata"]["name"], key=key)))
    assert all(v["name"] != "source-db" for v in pod["spec"]["volumes"])
    assert all(v["name"] != "source-db" for v in container["volumeMounts"])
    output = capsys.readouterr()
    assert not output.out + output.err + log.getvalue()
    assert not any(value in json.dumps(pod) for value in secret["data"].values())



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
    assert spec["volumes"][-1] == dict(name="context", emptyDir={"sizeLimit": "8Gi"})
    assert all("persistentVolumeClaim" not in v and "hostPath" not in v for v in spec["volumes"])
    assert original == before


@pytest.mark.parametrize("failed", ["namespace", "policy", "build", "database", "configuration", "compliance", "worker", "service", "job", "wait", None])
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
    def workload(template, image, db_ip, endpoints):
        assert image == IMAGE and db_ip == "192.0.2.10" and endpoints == ENDPOINTS
        return phase(template["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/component"])
    def service(repo, image, db_ip, endpoints):
        assert image == IMAGE and db_ip == "192.0.2.10" and endpoints == ENDPOINTS
        return phase("service", "192.0.2.20")
    with monkeypatch.context() as loader_patch:
        loader_patch.setattr(preview, "load", local_load)
        workers = preview.worker_templates(ROOT)
    monkeypatch.setattr(preview, "worker_templates", lambda repo: workers)
    monkeypatch.setattr(preview, "setup_worker_services", lambda workers: ENDPOINTS)
    monkeypatch.setattr(preview, "setup_workload", workload)
    monkeypatch.setattr(preview, "setup_service", service)
    monkeypatch.setattr(preview, "load", lambda *a: {"items": [template("deploy/ui2/70-e2e-job.yaml", "Job")]})
    monkeypatch.setattr(preview, "assert_module_claims", lambda *a: phase("claims"))
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


@pytest.mark.parametrize("failure", ["build-delete", "namespace-delete", "build-remains", "namespace-remains", "policy-delete", "policy-remains", "other-owner", None])
def test_cleanup_attempts_both_scopes_and_verifies_ownership_and_absence(monkeypatch, failure):
    calls = []
    def fake(ns, *args, **kwargs):
        calls.append((ns, args))
        if args[0] == "delete" and ((ns == preview.BUILD_NS and failure == "build-delete")
                                   or (ns == preview.NS and failure == "namespace-delete")
                                   or (ns == "ui2" and failure == "policy-delete")):
            raise RuntimeError("synthetic failure")
        if args[:2] == ("get", "namespace"):
            if args[-1] == "json":
                return json.dumps({"metadata": {"labels": {preview.LABEL: "other" if failure == "other-owner" else "owner"}}})
            return "namespace/ui2-preview" if failure == "namespace-remains" else ""
        if args[:2] == ("get", "networkpolicy"):
            return json.dumps({"items": [{}] if failure == "policy-remains" else []})
        if args[:2] == ("get", "jobs,pods"):
            return json.dumps({"items": [{}] if failure == "build-remains" else []})
        return ""
    monkeypatch.setattr(preview, "k", fake)
    if failure:
        with pytest.raises(RuntimeError, match="teardown incomplete"):
            preview.cleanup("owner")
    else:
        preview.cleanup("owner")
    assert any(ns == "ui2" and a[0] == "delete" and preview.LABEL + "=" + preview.NS in a for ns, a in calls)
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


@pytest.mark.parametrize("value", ["0", "-1", "bad", "900; synthetic", "999999999"])
def test_database_timeout_rejects_unsafe_or_unbounded_values(monkeypatch, value):
    monkeypatch.setenv("NEXUS_PREVIEW_DB_STATEMENT_TIMEOUT_SECONDS", value)
    monkeypatch.setattr(preview.subprocess, "Popen", lambda *a, **kw: pytest.fail("Invalid timeout must prevent copy"))
    with pytest.raises(ValueError):
        preview.copy_database()


@pytest.fixture
def local_database_stream(monkeypatch):
    start = subprocess.Popen
    def local(argv, **kwargs):
        assert argv[:4] == ["kubectl", "-n", preview.NS, "exec"]
        assert argv[4:8] == [preview.preview_name("ui2-preview-db"), "--", "bash", "-c"]
        assert "-i" not in argv
        assert kwargs["stdin"] == kwargs["stdout"] == subprocess.DEVNULL
        return start(["bash", "-c", argv[-1]], **kwargs)
    monkeypatch.setattr(preview.subprocess, "Popen", local)
    monkeypatch.setattr(preview, "check_preview_database", lambda: None)
    return start


@pytest.mark.parametrize("producer_error,consumer_error,cause", [
    ("pg_dump: ERROR: canceling statement due to statement timeout", "", "TIMEOUT"),
    ('pg_dump: ERROR: invalid snapshot identifier "synthetic-private"', "", "SNAPSHOT_LOST"),
    ("pg_dump: could not write to output file: Broken pipe", "", "PIPE_BROKEN"),
    ("", "pg_restore: ERROR: violates foreign key constraint", "RESTORE_ERROR"),
    ("Broken pipe", "pg_restore: ERROR: syntax error", "RESTORE_ERROR"),
    ("statement timeout", "pg_restore: unexpected end of file", "TIMEOUT"),
    ("pg_dump: timed out", "pg_restore: unexpected end of file", "TIMEOUT"),
    ("server closed the connection unexpectedly", "pg_restore: unexpected end of file", "STREAM_BROKEN"),
    ("connection reset by peer", "", "STREAM_BROKEN"),
    ("connection to server failed: Connection timed out", "", "STREAM_BROKEN"),
    ("synthetic unknown error", "", None),
])
def test_database_stream_reports_only_safe_cause(local_database_stream, monkeypatch, producer_error, consumer_error, cause, capsys):
    log = io.StringIO()
    monkeypatch.setattr(preview, "LOG", log)
    private = '\nDETAIL: Failing row contains (synthetic-private, 192.0.2.15, synthetic-secret).'
    source = [sys.executable, "-c",
              "import sys; sys.stdout.write('synthetic'); sys.stderr.write(sys.argv[1]); sys.exit(int(sys.argv[2]))",
              producer_error + private if producer_error else "", "1" if producer_error else "0"]
    target = [sys.executable, "-c",
              "import sys; sys.stdin.read(); sys.stderr.write(sys.argv[1]); sys.exit(int(sys.argv[2]))",
              consumer_error + private if consumer_error else "", "1" if consumer_error else "0"]
    with pytest.raises(preview.PreviewFailure) as caught:
        with preview.timing("preview_db_copy"):
            preview.database_stream(shlex.join(source), shlex.join(target), timeout=5)
    reason = "DB_COPY_FAILED" + ("_" + cause if cause else "")
    assert caught.value.reason == reason
    line = preview.failure_line(caught.value)
    category = cause or "OTHER"
    assert f"phase=preview_db_copy" in line and f"reason=DB_COPY_FAILED:{category}" in line
    assert all(value not in str(caught.value) + line + capsys.readouterr().out + log.getvalue()
               for value in ("synthetic-private", "192.0.2.15", "synthetic-secret"))


def test_database_stream_drains_large_stderr_without_deadlock(local_database_stream):
    source = shlex.join([sys.executable, "-c",
                        "import sys; sys.stderr.write('statement timeout\\n' + 'synthetic diagnostic\\n' * 20000); sys.exit(1)"])
    with pytest.raises(preview.PreviewFailure) as caught:
        preview.database_stream(source, "cat", timeout=5)
    assert caught.value.reason == "DB_COPY_FAILED_TIMEOUT"


def test_database_stream_success_ignores_stderr_warning(local_database_stream, capsys):
    preview.database_stream("printf synthetic; echo 'synthetic warning' >&2", "cat", timeout=5)
    assert not capsys.readouterr().out


def test_database_stream_deadline_reports_safe_timeout(local_database_stream):
    with pytest.raises(preview.PreviewFailure) as caught:
        preview.database_stream("exec sleep 30", "cat", timeout=0.05)
    assert caught.value.reason == "DB_COPY_FAILED_TIMEOUT"
    assert "synthetic" not in preview.failure_line(caught.value)


@pytest.mark.parametrize("error", ["Copying stdin failed: i/o timeout", "websocket ping failed", "", "connection reset by peer"])
def test_exec_transport_failure_is_stream_broken(local_database_stream, monkeypatch, error, capsys):
    start = local_database_stream
    def failed_exec(argv, **kwargs):
        return start(["bash", "-c", "printf %s " + shlex.quote(error) + " >&2; exit 1"], **kwargs)
    monkeypatch.setattr(preview.subprocess, "Popen", failed_exec)
    with pytest.raises(preview.PreviewFailure) as caught:
        preview.database_stream("source", "target", timeout=1)
    assert caught.value.reason == "DB_COPY_FAILED_STREAM_BROKEN"
    assert not capsys.readouterr().out


@pytest.mark.parametrize("configured,seconds", [(None, 900), ("1200", 1200)])
def test_copy_is_pod_to_pod_read_only_and_filters_only_the_two_requested_tables(monkeypatch, configured, seconds):
    if configured is None:
        monkeypatch.delenv("NEXUS_PREVIEW_DB_STATEMENT_TIMEOUT_SECONDS", raising=False)
    else:
        monkeypatch.setenv("NEXUS_PREVIEW_DB_STATEMENT_TIMEOUT_SECONDS", configured)
    calls, order = [], []
    @contextmanager
    def snapshot(source):
        assert source == ["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]
        order.append("snapshot")
        yield "00000001-00000002-1"
        order.append("rollback")
    @contextmanager
    def network():
        order.append("network")
        yield
        order.append("teardown")
    monkeypatch.setattr(preview, "exported_snapshot", snapshot)
    monkeypatch.setattr(preview, "database_network_access", network)
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(dict(spec=dict(clusterIP="192.0.2.10"))))
    def stream(source, target, **kwargs):
        assert kwargs == {"timeout": 4 * seconds}
        order.append("copy")
        calls.append((source, target))
    monkeypatch.setattr(preview, "database_stream", stream)
    preview.copy_database()
    assert order == ["network", "snapshot", "copy", "copy", "copy", "rollback", "teardown"]
    assert "--format=custom" in calls[0][0]
    assert "--snapshot=00000001-00000002-1" in calls[0][0]
    assert calls[0][0].count("--exclude-table-data=") == 2
    assert "--exit-on-error --no-owner" in calls[0][1]
    assert preview.AUDIT_SELECT in shlex.split(calls[1][0])[-1]
    assert preview.ENTRY_SELECT in shlex.split(calls[2][0])[-1]
    assert all("SET TRANSACTION SNAPSHOT '00000001-00000002-1'" in shlex.split(s)[-1] for s, _ in calls[1:])
    for source, target in calls:
        assert "kubectl" not in source + target
        assert "export PGHOST=192.0.2.10" in source
        assert "PGHOST=" not in target and "-h " not in target
        assert "default_transaction_read_only=on" in source
        assert f"statement_timeout={seconds * 1000}" in source
        assert f"statement_timeout={seconds * 1000}" in target
        assert "default_transaction_read_only=on" not in target
        source_argv = shlex.split(source)
        command = "pg_dump" if source == calls[0][0] else "psql"
        assert source_argv[source_argv.index("exec") + 1:source_argv.index(command)] == [
            "env", "PGUSER=$SOURCE_DB_USER", "PGPASSWORD=$SOURCE_DB_PASSWORD"]
        assert "SOURCE_DB_" not in target
        assert target.startswith(preview.PG_ENV)
        assert "PGPASSWORD=" not in target
        assert "cat " not in source + target
        assert "/run/source-db" not in source + target
        assert "--file=" not in source + target


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
        if mode != "skip":
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
@pytest.mark.parametrize("seconds", [900, 1200])
def test_exported_snapshot_is_opaque_and_holder_rolls_back(monkeypatch, invalid, seconds):
    monkeypatch.setenv("NEXUS_PREVIEW_DB_STATEMENT_TIMEOUT_SECONDS", str(seconds))
    real_popen = subprocess.Popen
    holders = []
    def fake(argv, **kwargs):
        assert argv[:5] == ["kubectl", "-n", "ui2", "exec", "-i"]
        assert "PGPASSWORD=" not in argv[-1]
        assert "cat " not in argv[-1]
        assert "default_transaction_read_only=on" in argv[-1]
        assert f"idle_in_transaction_session_timeout={(12 * seconds + 60) * 1000}" in argv[-1]
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
        with pytest.raises(preview.PreviewFailure) as caught:
            with preview.exported_snapshot(["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]):
                pytest.fail("Invalid snapshot must never be used")
        assert caught.value.reason == "DB_COPY_FAILED_SNAPSHOT_LOST"
    else:
        with preview.exported_snapshot(["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]) as token:
            assert token == "00000001-00000002-1"
            assert holders[0].poll() is None
    assert holders[0].poll() == 0


def test_database_bootstrap_uses_new_secret_and_refuses_image_drift(monkeypatch, capsys):
    db = template("deploy/ui2/40-database-statefulset.yaml")
    pinned = db["spec"]["template"]["spec"]["containers"][0]["image"]
    objects = []
    monkeypatch.setattr(preview, "load", local_load)
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "copy_database", lambda: None)
    copied = []
    monkeypatch.setattr(preview, "copy_secret", lambda *a: copied.append(a))
    def fake(ns, *args, **kwargs):
        assert args[0] in ("get", "wait")
        if ns == "ui2":
            return json.dumps(dict(spec=dict(containers=[dict(image=pinned)])))
        return json.dumps(dict(status=dict(podIP="192.0.2.10"))) if args[0] == "get" else ""
    monkeypatch.setattr(preview, "k", fake)
    ticks = iter([10.0, 12.5])
    monkeypatch.setattr(preview.time, "monotonic", lambda: next(ticks))
    assert preview.setup_database(ROOT) == "192.0.2.10"
    assert copied == [("ui2-db", "ui2-db-source", ("migrate-user", "migrate-password"))]
    assert "TIMING preview_db_copy 2.500" in capsys.readouterr().out
    tuning = next(o for o in objects if o["kind"] == "ConfigMap"
                  and o["metadata"]["name"] == preview.preview_name("ui2-db-config"))
    source_tuning = template("deploy/ui2/40-database-statefulset.yaml", "ConfigMap")
    assert tuning["data"] == source_tuning["data"]
    pod = next(o for o in objects if o["kind"] == "Pod")
    config = next(v["configMap"] for v in pod["spec"]["volumes"] if v["name"] == "database-config")
    assert config["name"] == tuning["metadata"]["name"]
    assert config["optional"] is True
    assert objects.index(tuning) < objects.index(pod)
    assert pod["spec"]["containers"][0]["resources"] == db["spec"]["template"]["spec"]["containers"][0]["resources"]
    secret = next(o for o in objects if o["kind"] == "Secret")
    assert set(secret["data"]) == {"app-user", "app-password", "migrate-user", "migrate-password"}
    assert all(o["metadata"]["namespace"] == preview.NS for o in objects)
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(dict(spec=dict(containers=[dict(image=IMAGE)]))))
    objects.clear()
    with pytest.raises(RuntimeError, match="pinned bootstrap"):
        preview.setup_database(ROOT)
    assert not objects


@pytest.mark.parametrize("failed_rollout", [False, True])
@pytest.mark.parametrize("component", ["configuration", "compliance", "worker"])
def test_worker_setup_uses_candidate_preview_services_and_waits_for_readiness(monkeypatch, failed_rollout, component):
    objects, calls = [], []
    monkeypatch.setattr(preview, "load", local_load)
    workers = preview.worker_templates(ROOT)
    worker = next(w for w in workers if w["component"] == component)
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "copy_secret", lambda *a: None)
    def fake(ns, *args, **kwargs):
        assert ns == preview.NS
        calls.append(args)
        if args[0] == "rollout" and failed_rollout:
            raise RuntimeError("synthetic readiness failure")
        return json.dumps(dict(spec=dict(clusterIP="192.0.2.30")))
    monkeypatch.setattr(preview, "k", fake)
    endpoints = preview.setup_worker_services([worker])
    if failed_rollout:
        with pytest.raises(RuntimeError, match="readiness failure"):
            preview.setup_workload(worker["deployment"], IMAGE, "192.0.2.10", ENDPOINTS)
    else:
        preview.setup_workload(worker["deployment"], IMAGE, "192.0.2.10", ENDPOINTS)
    deployment = objects[-1]
    assert calls[-1][:3] == ("rollout", "status", "deployment/ui2-preview-" + component)
    assert container_image(deployment) == IMAGE
    assert all(o["metadata"]["namespace"] == preview.NS for o in objects)
    for service in objects[:-1]:
        assert service["metadata"]["name"] == "ui2-preview-" + component
        assert service["spec"]["type"] == "ClusterIP"
        assert service["spec"]["selector"] == deployment["spec"]["template"]["metadata"]["labels"]
        assert endpoints[worker["services"][0]["metadata"]["name"]] == "http://192.0.2.30:" + str(worker["ports"][0])


def test_service_copies_only_required_keys_and_has_namespace_only_service(monkeypatch):
    objects, copied = [], []
    monkeypatch.setattr(preview, "load", local_load)
    monkeypatch.setattr(preview, "copy_secret", copied.append)
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(obj))
    monkeypatch.setattr(preview, "k", lambda *a, **kw: json.dumps(dict(spec=dict(clusterIP="192.0.2.20"))))
    assert preview.setup_service(ROOT, IMAGE, "192.0.2.10", ENDPOINTS) == "192.0.2.20"
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
    monkeypatch.setattr(preview, "prepare_build_context", lambda *a: None)
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
    monkeypatch.setattr(preview, "load", local_load)
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
            assert obj["spec"]["template"]["spec"]["containers"][0]["env"] == (
                source["spec"]["template"]["spec"]["containers"][0]["env"] +
                [dict(name="UI2_IMAGE_TAG", value=COMMIT[:12])])
        assert all(target[-1] == preview.LOAD_CONTEXT for _, target in streamed)


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
    print('PREVIEW STEP: phase=preview_compliance step=apply Deployment reason=OTHER error=field is immutable', flush=True)
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
    assert "PREVIEW STEP: phase=preview_compliance" in result.stdout


@pytest.mark.parametrize("path", ["deploy/ui2/55-compliance-deployment.yaml",
                                  "deploy/ui2/54-configuration-deployment.yaml"])
def test_source_list_is_parsed_without_preview_namespace(monkeypatch, path):
    def run(argv, **kwargs):
        assert argv[:3] == ["kubectl", "create", "--dry-run=client"]
        assert "-n" not in argv
        return subprocess.CompletedProcess(argv, 0, json.dumps(local_load(ROOT, path)), "")
    monkeypatch.setattr(preview.subprocess, "run", run)
    assert [o["kind"] for o in preview.load(ROOT, path)["items"]] == ["Deployment", "Service"]


def test_apply_flattens_lists_and_sets_each_namespace_without_mutating_source(monkeypatch):
    source = local_load(ROOT, "deploy/ui2/55-compliance-deployment.yaml")
    before, applied = copy.deepcopy(source), []
    def run(argv, **kwargs):
        assert argv[:6] == ["kubectl", "-n", "ui2-preview-synthetic", "apply", "--server-side",
                           "--field-manager=nexus-preview"]
        applied.append(json.loads(kwargs["input"]))
        return subprocess.CompletedProcess(argv, 0, "", "")
    monkeypatch.setattr(preview.subprocess, "run", run)
    preview.create("ui2-preview-synthetic", source)
    assert source == before
    assert [o["kind"] for o in applied] == ["Deployment", "Service"]
    assert all(o["metadata"]["namespace"] == "ui2-preview-synthetic" for o in applied)


@pytest.mark.parametrize("stderr,reason", [
    ('Deployment "synthetic-deployment" is invalid: spec.selector: Invalid value: '
     '{"private": "synthetic-secret"}: field is immutable', "MANIFEST_INVALID"),
    ('Error from server (Forbidden): User "synthetic-principal" cannot create resource "deployments" '
     'in namespace "synthetic-namespace"', "FORBIDDEN"),
    ('Error from server: strict decoding error: unknown field "spec.syntheticField"', "OTHER"),
    ('connection refused https://example.invalid:6443 password=synthetic-value 192.0.2.10', "OTHER"),
])
def test_failed_apply_logs_masked_error_text_and_field_reason(tmp_path, monkeypatch, capsys, stderr, reason):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(preview.subprocess, "run", lambda argv, **kw:
                        subprocess.CompletedProcess(argv, 1, "", stderr))
    with preview.private_log():
        with preview.timing("preview_compliance"):
            with pytest.raises(preview.PreviewFailure) as error:
                preview.create(preview.NS, dict(kind="Deployment", metadata=dict(name="synthetic-deployment")))
    assert error.value.phase == "preview_compliance" and error.value.step == "apply Deployment"
    assert error.value.reason == reason
    assert preview.failure_line(error.value).endswith(f"{reason}:PreviewFailure")
    output = capsys.readouterr().out
    log = next((tmp_path / ".local/state/nexus-preview").glob("*.log"))
    assert log.stat().st_mode & 0o777 == 0o600
    assert "PREVIEW STEP: phase=preview_compliance step=apply Deployment" in output
    for target in (output, log.read_text()):
        assert all(s not in target for s in ("synthetic-principal", "synthetic-secret", "synthetic-value", "synthetic-namespace",
                                             "synthetic-deployment", "example.invalid", "192.0.2.10"))
        if reason == "MANIFEST_INVALID":
            assert "spec.selector" in target and "field is immutable" in target
        if reason == "FORBIDDEN":
            assert "Forbidden" in target and "cannot create resource" in target


def test_timeout_preserves_phase_and_masks_partial_stderr(monkeypatch, capsys):
    def run(*a, **kw):
        raise subprocess.TimeoutExpired(a, 1, stderr=b'connection to example.invalid timed out')
    monkeypatch.setattr(preview.subprocess, "run", run)
    with pytest.raises(preview.PreviewFailure) as error:
        preview.k(preview.NS, "get", "pod", "synthetic-pod")
    assert error.value.reason == "TIMEOUT"
    assert "example.invalid" not in capsys.readouterr().out


def test_generic_worker_discovery_and_rendering_uses_production_args_ports_and_labels(tmp_path, monkeypatch):
    monkeypatch.setattr(preview, "load", local_load)
    path = tmp_path / "deploy/ui2/57-synthetic-deployment.yaml"
    path.parent.mkdir(parents=True)
    deployment = template("deploy/ui2/54-configuration-deployment.yaml", "Deployment")
    service = template("deploy/ui2/54-configuration-deployment.yaml", "Service")
    deployment["metadata"]["name"] = service["metadata"]["name"] = "ui2-synthetic-module"
    for labels in (deployment["metadata"]["labels"], deployment["spec"]["selector"]["matchLabels"],
                   deployment["spec"]["template"]["metadata"]["labels"], service["spec"]["selector"]):
        labels["app.kubernetes.io/component"] = "synthetic-module"
    deployment["spec"]["template"]["spec"]["containers"][0]["args"] = ["worker", "synthetic-module"]
    path.write_text(yaml.safe_dump(deployment))
    path.with_name("58-synthetic-service.yaml").write_text(yaml.safe_dump(service))
    workers = preview.worker_templates(ROOT) + preview.worker_templates(tmp_path)
    assert len(workers) == 5
    before = copy.deepcopy(workers)
    for namespace in ("ui2-preview-first", "ui2-preview-second"):
        monkeypatch.setattr(preview, "NS", namespace)
        for worker in workers:
            obj = preview.workload_manifest(worker["deployment"], IMAGE, "192.0.2.10", ENDPOINTS)
            labels = obj["spec"]["template"]["metadata"]["labels"]
            assert obj["spec"]["selector"]["matchLabels"] == labels
            assert labels[preview.LABEL] == namespace
            assert obj["metadata"]["namespace"] == namespace
            assert obj["metadata"]["name"].startswith(namespace + "-")
            spec = obj["spec"]["template"]["spec"]
            assert spec["containers"][0]["args"] == worker["deployment"]["spec"]["template"]["spec"]["containers"][0]["args"]
            assert all("hostPath" not in v and "persistentVolumeClaim" not in v for v in spec["volumes"])
            assert all(v["secret"]["secretName"].startswith(namespace + "-") for v in spec["volumes"] if "secret" in v)
            for source in worker["services"]:
                svc = preview.service_object(source)
                assert svc["metadata"]["name"].startswith(namespace + "-")
                assert all(labels[k] == v for k, v in svc["spec"]["selector"].items())
                assert svc["spec"]["ports"] == source["spec"]["ports"]
        assert all(o["metadata"]["name"].startswith(namespace + "-") for o in preview.policies(workers))
    assert workers == before


@pytest.mark.parametrize("cleanup_failure", [False, True])
def test_complete_preview_uses_unique_scopes_all_workers_and_cleanup(monkeypatch, capsys, cleanup_failure):
    objects, scopes, cleanup_scopes = [], [], []
    monkeypatch.setattr(preview, "load", local_load)
    monkeypatch.setattr(preview.secrets, "token_hex", lambda n: "synthetic-owner")
    monkeypatch.setattr(preview, "create", lambda ns, obj: objects.append(copy.deepcopy(obj)))
    monkeypatch.setattr(preview, "build_images", lambda *a: [IMAGE, IMAGE])
    monkeypatch.setattr(preview, "setup_database", lambda *a: "192.0.2.10")
    monkeypatch.setattr(preview, "copy_secret", lambda *a: None)
    monkeypatch.setattr(preview, "assert_module_claims", lambda *a: None)
    monkeypatch.setattr(preview, "wait_job", lambda *a, **kw: None)
    def cleanup(owner):
        cleanup_scopes.append((preview.NS, owner))
        if cleanup_failure:
            raise RuntimeError("Preview teardown incomplete; ship blocked")
    monkeypatch.setattr(preview, "cleanup", cleanup)
    def k(ns, *args, **kwargs):
        scopes.append((ns, args))
        if args[:2] == ("get", "configmap"):
            return json.dumps(dict(data={}))
        if args[:2] == ("get", "service"):
            return json.dumps(dict(spec=dict(clusterIP="192.0.2.20")))
        return ""
    monkeypatch.setattr(preview, "k", k)
    if cleanup_failure:
        with pytest.raises(RuntimeError, match="teardown incomplete"):
            preview.run(ROOT, COMMIT)
    else:
        preview.run(ROOT, COMMIT)
    assert preview.NS == "ui2-preview"
    assert cleanup_scopes == [("ui2-preview-synthetic-owner", "synthetic-owner")]
    assert len([o for o in objects if o["kind"] == "Deployment"]) == 5
    for obj in objects:
        if obj["kind"] != "Namespace":
            assert obj["metadata"]["namespace"] == "ui2-preview-synthetic-owner"
            assert obj["metadata"]["name"].startswith("ui2-preview-synthetic-owner-")
    assert ("PREVIEW E2E: PASS" in capsys.readouterr().out) == (not cleanup_failure)
    assert any(a[:3] == ("rollout", "status", "deployment/ui2-preview-synthetic-owner-configuration") for ns, a in scopes)


def test_client_surfaces_masked_step_and_failure_and_total_time(monkeypatch, capsys):
    monkeypatch.setattr(sa, "_git", lambda *a, **kw: "# Synthetic wrapper\n")
    line = "PREVIEW STEP: phase=preview_compliance step=apply Deployment reason=MANIFEST_INVALID error=field is immutable"
    monkeypatch.setattr(sa.subprocess, "run", lambda *a, **kw:
                        subprocess.CompletedProcess(a, 1, line + "\nPREVIEW E2E: FAIL\n", ""))
    with pytest.raises(SystemExit, match="stopped before PR"):
        sa._preview_e2e(ROOT, COMMIT)
    output = capsys.readouterr().out
    assert line in output and "PREVIEW E2E: FAIL" in output and "TIMING preview_total " in output


def synthetic_host_inputs(home):
    paths = ("ui2/.ca/synthetic.pem", "ui2/.ca/second.pem",
             "ui2/.gradle-home/wrapper/dists/gradle-8.14.3-bin/synthetic-hash/gradle-8.14.3-bin.zip")
    for path in paths:
        file = home / "nexus" / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"synthetic build input")
    return paths


def test_context_preparation_matches_production_metadata_and_preserves_source(tmp_path, monkeypatch):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    inputs = synthetic_host_inputs(tmp_path)
    source = tmp_path / "nexus"
    (source / "project").mkdir()
    (source / "project/deploy_info.json").write_text('{"commit": "stale"}')
    before = {p: p.read_bytes() for p in source.rglob("*") if p.is_file()}
    candidate = tmp_path / "candidate"
    (candidate / "project").mkdir(parents=True)
    (candidate / "project/deploy_info.json").write_text('{"commit": "stale candidate"}')

    production = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    preparation = production.split('BUILT_AT="', 1)[1].split('echo "Starting loader...', 1)[0]
    # Fail if production gains another generated context input without preview parity.
    assert re.findall(r">\s*(\S+)", preparation) == ["project/deploy_info.json"]
    printf_line = next(line for line in preparation.splitlines() if line.startswith("printf "))
    production_context = tmp_path / "production"
    (production_context / "project").mkdir(parents=True)
    subprocess.run(["bash", "-c", 'COMMIT_SHA=$1; BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"\n' + printf_line,
                    "parity", COMMIT], cwd=production_context, check=True)
    expected = json.loads((production_context / "project/deploy_info.json").read_text())

    started = datetime.now(timezone.utc).replace(microsecond=0)
    preview.prepare_build_context(candidate, COMMIT)
    finished = datetime.now(timezone.utc).replace(microsecond=0)
    info = json.loads((candidate / "project/deploy_info.json").read_text())
    assert set(info) == set(expected) | {"preview"}
    assert info["commit"] == expected["commit"] == COMMIT
    assert info["preview"] is True
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", info["built_at"])
    assert started <= datetime.fromisoformat(info["built_at"].replace("Z", "+00:00")) <= finished
    assert {str(p.relative_to(candidate)) for p in candidate.rglob("*") if p.is_file()} == (
        set(inputs) | {"project/deploy_info.json"})
    assert before == {p: p.read_bytes() for p in source.rglob("*") if p.is_file()}


def test_host_overlay_copies_only_approved_globs_and_preserves_source(tmp_path, monkeypatch):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    paths = synthetic_host_inputs(tmp_path)
    excluded = (".env", "ui2/.env", "ui2/.ca/private.key", "ui2/.ca/nested/extra.pem",
                "ui2/.gradle-home/cache.bin",
                "ui2/.gradle-home/wrapper/dists/gradle-8.14.3-bin/synthetic-hash/extra.zip",
                "ui2/.gradle-home/wrapper/dists/gradle-8.14.2-bin/synthetic-hash/gradle-8.14.3-bin.zip")
    for path in excluded:
        file = tmp_path / "nexus" / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"synthetic excluded input")
    before = {p: p.read_bytes() for p in (tmp_path / "nexus").rglob("*") if p.is_file()}
    candidate = tmp_path / "candidate"
    candidate.mkdir()
    (candidate / "tracked.txt").write_text("candidate source")
    preview.overlay_host_inputs(candidate)
    copied = {str(p.relative_to(candidate)) for p in candidate.rglob("*") if p.is_file()}
    assert copied == set(paths) | {"tracked.txt"}
    assert (candidate / "tracked.txt").read_text() == "candidate source"
    assert all((candidate / path).read_bytes() == before[tmp_path / "nexus" / path] for path in paths)
    assert before == {p: p.read_bytes() for p in (tmp_path / "nexus").rglob("*") if p.is_file()}


@pytest.mark.parametrize("fault", ["ca-missing", "zip-missing", "file-link", "directory-link", "candidate-link"])
def test_host_overlay_fails_closed_before_build_for_missing_or_linked_inputs(tmp_path, monkeypatch, fault):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    paths = synthetic_host_inputs(tmp_path)
    source = tmp_path / "nexus"
    candidate = tmp_path / "candidate"
    candidate.mkdir()
    if fault == "ca-missing":
        for path in paths[:2]:
            (source / path).unlink()
    elif fault == "zip-missing":
        (source / paths[2]).unlink()
    elif fault == "file-link":
        (source / paths[0]).unlink()
        (source / paths[0]).symlink_to(source / paths[1])
    elif fault == "directory-link":
        (source / "ui2/.ca").rename(source / "ui2/other")
        (source / "ui2/.ca").symlink_to(source / "ui2/other", target_is_directory=True)
    else:
        (candidate / "ui2").symlink_to(source / "ui2", target_is_directory=True)
    monkeypatch.setattr(preview, "k", lambda *a, **kw: pytest.fail("Missing inputs must prevent cluster build"))
    with pytest.raises(preview.PreviewFailure) as caught:
        with preview.timing("preview_build"):
            preview.build_images(candidate, COMMIT, "synthetic-owner")
    assert caught.value.reason == "HOST_INPUT_MISSING"
    assert "step=overlay host inputs reason=HOST_INPUT_MISSING" in preview.failure_line(caught.value)
    assert not (candidate / "ui2/.ca").exists() or fault == "candidate-link"


@pytest.mark.parametrize("documents,expected", [
    ([{"kind": "Deployment"}, {"kind": "Service"}, {"kind": "ConfigMap"}],
     ["Deployment", "Service", "ConfigMap"]),
    ([{"kind": "Pod"}], ["Pod"]),
    ([{"kind": "List", "items": [{"kind": "Pod"}, {"kind": "Service"}]}], ["Pod", "Service"]),
    ([{"kind": "List", "items": [{"kind": "Pod"}, {"kind": "List", "items": [
        {"kind": "Service"}, {"kind": "List", "items": [{"kind": "ConfigMap"}]}]}]}],
     ["Pod", "Service", "ConfigMap"]),
])
@pytest.mark.parametrize("separator", ["", " \n\t\r\n"])
def test_load_concatenated_json_flattens_all_lists(monkeypatch, documents, expected, separator):
    stream = separator + separator.join(json.dumps(o) for o in documents) + separator
    monkeypatch.setattr(preview.subprocess, "run", lambda argv, **kw:
                        subprocess.CompletedProcess(argv, 0, stream, ""))
    assert preview.load(ROOT, "synthetic.yaml") == {
        "kind": "List", "items": [{"kind": kind} for kind in expected]}


def test_malformed_stream_failure_is_logged_without_message_before_log_closes(tmp_path, monkeypatch):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(preview.subprocess, "run", lambda argv, **kw:
                        subprocess.CompletedProcess(argv, 0, '{"kind":"Pod"}\nsynthetic-private', ""))
    with pytest.raises(preview.PreviewFailure) as caught:
        with preview.private_log():
            with preview.timing("preview_setup"):
                preview.load(ROOT, "synthetic.yaml")
    line = preview.failure_line(caught.value)
    assert line == "PREVIEW E2E: FAIL phase=preview_setup step=load templates reason=OTHER:JSONDecodeError"
    log = next((tmp_path / ".local/state/nexus-preview").glob("*.log"))
    assert log.read_text() == line + "\n"
    assert "synthetic-private" not in log.read_text()
    assert preview.LOG is None


@pytest.mark.parametrize("operation,step,phase", [
    ("create", "create namespace", "preview_setup"),
    ("worker_templates", "load templates", "preview_setup"),
    ("policies", "policies", "preview_setup"),
    ("build_images", "build images", "preview_build"),
    ("setup_database", "setup database", "preview_database"),
    ("setup_worker_services", "worker services", "preview_workers"),
    ("setup_workload", "setup worker", "preview_synthetic"),
    ("setup_service", "setup service", "preview_service"),
    ("wait_job", "wait e2e job", "preview_e2e"),
])
def test_run_failure_keeps_substep_despite_cleanup(tmp_path, monkeypatch, operation, step, phase):
    monkeypatch.setattr(preview.Path, "home", lambda: tmp_path)
    monkeypatch.setattr(preview, "k", lambda *a, **kw: "")
    monkeypatch.setattr(preview, "create", lambda *a: None)
    monkeypatch.setattr(preview, "assert_module_claims", lambda *a: None)
    monkeypatch.setattr(preview, "worker_templates", lambda *a: [dict(component="synthetic", deployment={}, ports=[8081])])
    monkeypatch.setattr(preview, "policies", lambda *a: [])
    monkeypatch.setattr(preview, "build_images", lambda *a: [IMAGE, IMAGE])
    monkeypatch.setattr(preview, "setup_database", lambda *a: "192.0.2.10")
    monkeypatch.setattr(preview, "setup_worker_services", lambda *a: {})
    monkeypatch.setattr(preview, "setup_workload", lambda *a: None)
    monkeypatch.setattr(preview, "setup_service", lambda *a: "192.0.2.20")
    monkeypatch.setattr(preview, "load", local_load)
    monkeypatch.setattr(preview, "e2e_manifest", lambda *a: {})
    monkeypatch.setattr(preview, "wait_job", lambda *a, **kw: None)
    cleaned = []
    def cleanup(owner):
        preview.STEP = "delete namespace"
        cleaned.append(owner)
    monkeypatch.setattr(preview, "cleanup", cleanup)
    def fail(*a, **kw):
        raise ValueError("synthetic withheld detail")
    monkeypatch.setattr(preview, operation, fail)
    with pytest.raises(preview.PreviewFailure) as caught:
        with preview.private_log():
            preview.run(ROOT, COMMIT)
    assert cleaned
    line = preview.failure_line(caught.value)
    assert f"phase={phase} step={step}" in line
    assert line.endswith(":ValueError")
    log = next((tmp_path / ".local/state/nexus-preview").glob("*.log"))
    assert log.read_text() == line + "\n"
    assert "synthetic withheld detail" not in log.read_text()


def test_postgres_error_keeps_schema_names_and_masks_entire_row():
    stderr = ('ERROR: new row for relation "jobs" violates check constraint '
              '"chk_jobs_diagnostic_port"\nDETAIL: Failing row contains '
              '(synthetic-account, 192.0.2.15, "nested (value)",\nsecret-value).')
    masked = preview.masked_error(stderr.encode())
    assert 'ERROR: new row for relation "jobs" violates check constraint "chk_jobs_diagnostic_port"' in masked
    assert 'DETAIL: Failing row contains ([MASKED])' in masked
    assert all(value not in masked for value in ('synthetic-account', '192.0.2.15', 'nested', 'secret-value'))
    null_error = preview.masked_error('ERROR: null value in column "state" of relation "jobs" violates not-null constraint')
    assert 'column "state" of relation "jobs"' in null_error
    assert 'null value' in null_error
    assert 'not-null constraint' in null_error
    assert 'synthetic-device' not in preview.masked_error('ERROR: value "synthetic-device" invalid')
    assert preview.masked_error('DETAIL: Failing row contains (relation "synthetic_data"') == 'DETAIL: Failing row contains ([MASKED])'


def jobs_ddl_database():
    """Evaluate migration-owned jobs checks offline; this is not a PostgreSQL integration test."""
    import sqlite3

    migrations = ROOT / 'ui2/service/src/main/resources/db/migration'
    declarations = []
    for path in sorted(migrations.glob('V*__*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
        sql = re.sub(r'--[^\n]*', '', path.read_text())
        initial = re.search(r'CREATE TABLE jobs\s*\((.*?)\);', sql, re.S)
        if initial:
            declarations.extend(re.split(r',\s*(?=[a-z_]+\s+(?:TEXT|TIMESTAMPTZ))', initial[1].strip()))
        for match in re.finditer(r'ALTER TABLE jobs\s+(.*?);', sql, re.S):
            operation = match[1].strip()
            if operation.startswith('ADD CONSTRAINT'):
                declarations.append(operation.removeprefix('ADD '))
            elif operation.startswith('ADD COLUMN'):
                declarations.extend(re.sub(r'^ADD COLUMN\s+', '', part.strip())
                                    for part in re.split(r',\s*(?=ADD COLUMN)', operation))
            else:
                column, change = re.fullmatch(r'ALTER COLUMN (\w+) (DROP DEFAULT|DROP NOT NULL)', operation).groups()
                for i, declaration in enumerate(declarations):
                    if declaration.startswith(column + ' '):
                        declarations[i] = (re.sub(r'\s+DEFAULT\s+[^,]+', '', declaration)
                                           if change == 'DROP DEFAULT' else declaration.replace('NOT NULL', ''))
    # SQLite requires table constraints after all column declarations.
    declarations.sort(key=lambda declaration: declaration.startswith('CONSTRAINT'))
    ddl = 'CREATE TABLE jobs (' + ','.join(declarations) + ')'
    ddl = ddl.replace('::jsonb', '').replace('DEFAULT now()', 'DEFAULT CURRENT_TIMESTAMP')
    ddl = re.sub(r'(\w+)\s+~\s+', r'\1 REGEXP ', ddl)
    connection = sqlite3.connect(':memory:')
    connection.create_function('regexp', 2, lambda pattern, value: value is not None and bool(re.search(pattern, value)))
    connection.execute(ddl)
    return connection


def test_preview_claim_rows_satisfy_migration_checks_and_rollback(monkeypatch):
    import sqlite3

    connection = jobs_ddl_database()
    statements = []
    def database(namespace, *args, input=None, **kwargs):
        statements.append(input)
        if input.startswith('SELECT count(*)=2'):
            return 't'
        if 'preview_policy_handover' in input:
            return ''
        capability = re.search(r"capability TEXT := '([^']+)'", input)[1]
        insert = re.search(r'INSERT INTO jobs.*?;', input, re.S)[0].replace('::jsonb', '')
        insert = re.sub(r'\bcapability\b', "'" + capability + "'", insert)
        assert input.endswith("SELECT 'CLAIM_PASS';ROLLBACK;")
        connection.execute('BEGIN')
        connection.execute(insert)
        row = connection.execute('SELECT job_type,capability_id,state,action_class,idempotency_key,precheck_results FROM jobs').fetchone()
        assert row == (capability, capability, 'REQUESTED', 'read', 'preview-claim-assertion', '[]')
        connection.execute("UPDATE jobs SET state='CLAIMED',lease_epoch=lease_epoch+1")
        if capability == 'fmg_interface_detail':
            with pytest.raises(sqlite3.IntegrityError, match='chk_jobs_diagnostic_port'):
                connection.execute('UPDATE jobs SET diagnostic_port=NULL')
        connection.rollback()
        assert connection.execute('SELECT count(*) FROM jobs').fetchone()[0] == 0
        return 'CLAIM_PASS\n'

    monkeypatch.setattr(preview, 'k', database)
    monkeypatch.setattr(preview, 'load', local_load)
    try:
        preview.assert_module_claims(ROOT, preview.worker_templates(ROOT))
        assert sum('preview_claim_assertion' in sql for sql in statements) > 20
        assert any("capability TEXT := 'fmg_interface_detail'" in sql for sql in statements)
    finally:
        connection.close()
