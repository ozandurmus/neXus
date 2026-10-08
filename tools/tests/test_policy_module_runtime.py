"""Offline policy module release, render and ownership guards; never invokes a host."""
import importlib.util
import json
from pathlib import Path
import sys
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/delivery"))
import module_deploy as deploy

IMAGE = "registry.example.invalid/image@sha256:" + "a" * 64
OLD = "registry.example.invalid/image@sha256:" + "b" * 64


@pytest.mark.parametrize("value", ["", "policy policy", "unknown", "policy;false", "worker/other", "$(false)"])
def test_targets_are_closed_and_injection_is_rejected(value):
    with pytest.raises(deploy.Blocked):
        deploy.validate_targets(value)


def test_selected_modules_are_independent_and_never_wait_on_policy_for_general(monkeypatch):
    queries = []
    monkeypatch.setattr(deploy, "query", lambda sql: queries.append(sql) or "t")
    assert deploy.barrier("policy", 7)
    assert "ui2_job_module(j.capability_id)='policy'" in queries[-1]
    assert "drain_ack_generation=drain_generation" in queries[-1]
    assert "QUARANTINED" in queries[-1]
    assert deploy.barrier("worker", 8)
    assert "effective_owner='general'" in queries[-1]
    assert "owner_instance is null" in queries[-1]  # One-time legacy bootstrap drains all jobs.
    assert "ui2_job_module(j.capability_id)='policy'" not in queries[-1]


def test_bounded_wait_retains_old_digest_and_the_drain(monkeypatch):
    now, calls = [0], []
    monkeypatch.setattr(deploy, "barrier", lambda *args: False)
    def sleep(seconds):
        now[0] += seconds
    with pytest.raises(deploy.Blocked, match="old digest"):
        deploy.wait_drain("policy", 1, 20, sleep=sleep, clock=lambda: now[0])
    monkeypatch.setattr(deploy, "query", lambda sql: "f" if "fallback_enabled" in sql else "t")
    monkeypatch.setattr(deploy, "run", lambda *args: calls.append(args) or OLD)
    monkeypatch.setattr(deploy, "ensure_policy", lambda *args: OLD)
    monkeypatch.setattr(deploy, "request_drain", lambda *args: 1)
    monkeypatch.setattr(deploy, "wait_drain", lambda *args: (_ for _ in ()).throw(deploy.Blocked("bounded drain")))
    with pytest.raises(deploy.Blocked):
        deploy.replace("policy", IMAGE, "synthetic-snapshot", 20)
    assert not any("set" in args or "scale" in args for args in calls)


@pytest.mark.parametrize("target,role", [("worker", "general"), ("policy", "policy")])
def test_release_tool_bumps_drain_generation_and_requires_it_to_clear(monkeypatch, target, role):
    queries = []
    monkeypatch.setattr(deploy, "query", lambda sql: queries.append(sql) or "7")
    assert deploy.request_drain(target, "synthetic-snapshot") == 7
    request = queries[-1]
    assert "pg_advisory_xact_lock(294611)" in request
    assert "drain_requested=true,drain_generation=drain_generation+1" in request
    assert "where module='" + role + "'" in request
    deploy.clear_drain(target, 7, "synthetic-snapshot")
    clear = queries[-1]
    assert "pg_advisory_xact_lock(294611)" in clear
    assert "drain_requested=false,drain_ack_at=null,drain_ack_generation=null" in clear
    assert "where module='" + role + "' and drain_generation=7" in clear


def test_replace_waits_for_old_pod_deletion_before_clearing_the_claim_fence(monkeypatch):
    events = []
    def run(*args):
        events.append(args)
        if "jsonpath={.spec.template.spec.containers[0].image}" in args:
            return OLD
        if "pods" in args:
            uid = "old-owner" if not any("set" in event for event in events) else "new-owner"
            return json.dumps({"items": [{"metadata": {"uid": uid}}]})
        return ""
    monkeypatch.setattr(deploy, "query", lambda sql: "f" if "fallback_enabled" in sql else "t")
    monkeypatch.setattr(deploy, "run", run)
    monkeypatch.setattr(deploy, "ensure_policy", lambda *args: OLD)
    monkeypatch.setattr(deploy, "wait_heartbeat", lambda *args: events.append(("heartbeat",)))
    monkeypatch.setattr(deploy, "handover_policy", lambda *args: events.append(("handover",)))
    monkeypatch.setattr(deploy, "request_drain", lambda *args: events.append(("drain",)) or 7)
    monkeypatch.setattr(deploy, "wait_drain", lambda *args: events.append(("barrier",)))
    original_query = deploy.query
    monkeypatch.setattr(deploy, "query", lambda sql: events.append(("clear",)) or "t" if "owner_instance=null" in sql else original_query(sql))
    monkeypatch.setattr(deploy, "module_e2e", lambda *args: events.append(("e2e",)))
    deploy.replace("policy", IMAGE, "synthetic-snapshot")
    assert events.index(("drain",)) < events.index(("barrier",))
    changed = next(i for i, event in enumerate(events) if "set" in event)
    cleared = events.index(("clear",))
    assert changed < cleared
    assert any("pods" in event for event in events[changed + 1:cleared])
    assert all("ui2-worker" not in event for event in events)
    assert events.index(("heartbeat",)) < events.index(("handover",)) < events.index(("e2e",))


def test_rollback_requires_a_compatible_snapshot_and_uses_its_exact_module_digest():
    manifest = {"runtime": {"compatible_runtime": True}, "images": {"ui2": [
        {"kind": "Deployment", "workload": "ui2-policy", "container": "policy", "image": OLD}]}}
    assert deploy.rollback_image(manifest, "policy") == OLD
    with pytest.raises(deploy.Blocked):
        deploy.rollback_image(manifest, "worker")
    manifest["runtime"]["compatible_runtime"] = False
    with pytest.raises(deploy.Blocked, match="full rollback"):
        deploy.rollback_image(manifest, "policy")


def test_policy_manifest_has_only_its_required_dependencies_and_starts_disabled():
    documents = list(yaml.safe_load_all((ROOT / "deploy/ui2/57-policy-deployment.yaml").read_text()))
    deployment = next(d for d in documents if d["kind"] == "Deployment")
    assert deployment["spec"]["replicas"] == 0
    assert deployment["spec"]["strategy"]["type"] == "Recreate"
    spec = deployment["spec"]["template"]["spec"]
    assert spec["serviceAccountName"] == "ui2-policy" and spec["automountServiceAccountToken"] is False
    container = spec["containers"][0]
    assert container["args"] == ["worker", "policy"]
    assert container["resources"] == {"requests": {"cpu": "250m", "memory": "512Mi"}, "limits": {"memory": "8Gi"}}
    assert "-Dui2.db.pool.maximum-pool-size=6" in next(e["value"] for e in container["env"] if e["name"] == "JAVA_TOOL_OPTIONS")
    assert not any("hostPath" in volume for volume in spec["volumes"])
    assert {v["name"] for v in spec["volumes"]} == {"tmp", "home", "db-credentials", "credential-store-key", "artefact-store-key", "artefact-store", "corp-ca"}
    policy = next(d for d in documents if d["kind"] == "NetworkPolicy")
    assert policy["spec"]["ingress"] == []
    assert policy["spec"]["policyTypes"] == ["Ingress", "Egress"]


def test_claim_sql_constants_match_and_all_device_opens_are_admitted():
    def sql(relative):
        source = (ROOT / relative).read_text()
        return source.split('"""', 2)[1]
    assert sql("ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/jobrecords/JooqJobLeaseDao.java") == sql(
        "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java")
    shared = sql("ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java")
    assert "job_type = capability_id" in shared and "owner_control AS MATERIALIZED" in shared
    assert "lease_owner_generation" in shared and "admission_not_before" in shared
    base = ROOT / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport"
    ssh = (base / "ssh/SshExecTransport.java").read_text()
    assert ssh.index("EndpointRuntime.ssh(") < ssh.index("preSocket.connect(")
    assert "checkAdmission(session)" in ssh
    assert "EndpointRuntime.http(" in (base / "xmlapi/PanXmlApiTransport.java").read_text()
    assert "EndpointRuntime.http(" in (base / "https/HttpsDeviceClient.java").read_text()
    migration = (ROOT / "ui2/service/src/main/resources/db/migration/V128__module_endpoint_runtime.sql").read_text()
    for table in ("module_runtime_control", "endpoint_admission", "runtime_task_lease"):
        assert f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;" in migration
    assert "gate_registry" not in migration


def test_deploy_entry_points_pass_targets_without_a_global_job_wait():
    build = (ROOT / "deploy/ui2-image-build/run_build.sh").read_text()
    wrapper = (ROOT / "tools/delivery/hosta_deploy.sh").read_text()
    assert 'module_deploy.py" --targets "$NEXUS_DEPLOY_TARGETS"' in build
    assert "--targets)" in wrapper
    assert "select count(*) from jobs where state in ('CLAIMED','EXECUTING')" not in build + wrapper
    assert 'export NEXUS_DEPLOY_TARGETS="$TARGETS"' in wrapper


def test_module_e2e_resolves_runner_and_template_outside_checkout(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    calls = []
    def run(*args, **kwargs):
        calls.append(args)
        if args[0] == "python3":
            return "sha256:" + "a" * 64
        if "get" in args:
            return "1"
        return ""
    monkeypatch.setattr(deploy, "run", run)
    deploy.module_e2e("a" * 40)
    assert calls[0] == ("python3", str(ROOT / "tools/e2e/hosta_e2e_image.py"), "ensure", "--commit", "a" * 40)
    assert any("apply" in call for call in calls)


@pytest.mark.parametrize("output,ready", [
    ("BEGIN\nsystem:release\nmodule_release\nquarantine-drain\n2\nt\nCOMMIT", True),
    ("BEGIN\n2\nf\nCOMMIT", False), ("2\nCOMMIT", False), ("t\nf", False),
])
def test_drain_reconciles_orphans_in_same_audited_transaction_and_reads_only_boolean(monkeypatch, output, ready):
    queries = []
    monkeypatch.setattr(deploy, "query", lambda sql: queries.append(sql) or output)
    assert deploy.barrier("policy", 7) is ready
    sql = queries[0]
    assert sql.startswith("begin;") and sql.endswith(";commit;")
    assert "system:release" in sql
    assert sql.index("ui2_release_orphan_quarantines()") < sql.index("select (drain_requested")
    assert "drain_generation=7" in sql and "QUARANTINED" in sql


def test_drain_release_database_failure_propagates(monkeypatch):
    def unavailable(sql):
        raise deploy.Blocked("synthetic database unavailable")
    monkeypatch.setattr(deploy, "query", unavailable)
    with pytest.raises(deploy.Blocked, match="database unavailable"):
        deploy.barrier("policy", 7)
