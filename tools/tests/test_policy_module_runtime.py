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
    def run(*args, input=None):
        events.append(args)
        if "jsonpath={.spec.template.spec.containers[0].image}" in args:
            return OLD
        if "jsonpath={.spec.replicas}" in args:
            return "1"
        if "pods" in args:
            uid = "old-owner" if not any("apply" in event for event in events) else "new-owner"
            return json.dumps({"items": [{"metadata": {"uid": uid}}]})
        return ""
    monkeypatch.setattr(deploy, "query", lambda sql: "f" if "fallback_enabled" in sql else "t")
    monkeypatch.setattr(deploy, "run", run)
    monkeypatch.setattr(deploy, "ensure_policy", lambda *args: OLD)
    monkeypatch.setattr(deploy, "kubectl_diff", lambda manifest: False)
    monkeypatch.setattr(deploy, "wait_heartbeat", lambda *args: events.append(("heartbeat",)))
    monkeypatch.setattr(deploy, "handover_policy", lambda *args: events.append(("handover",)))
    monkeypatch.setattr(deploy, "request_drain", lambda *args: events.append(("drain",)) or 7)
    monkeypatch.setattr(deploy, "wait_drain", lambda *args: events.append(("barrier",)))
    original_query = deploy.query
    monkeypatch.setattr(deploy, "query", lambda sql: events.append(("clear",)) or "t" if "owner_instance=null" in sql else original_query(sql))
    monkeypatch.setattr(deploy, "module_e2e", lambda *args: events.append(("e2e",)))
    deploy.replace("policy", IMAGE, "synthetic-snapshot")
    assert events.index(("drain",)) < events.index(("barrier",))
    changed = next(i for i, event in enumerate(events) if "apply" in event)
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
    assert {v["name"] for v in spec["volumes"]} == {"tmp", "home", "db-credentials", "credential-store-key", "artefact-store-key", "hostname-fingerprint-key", "artefact-store", "corp-ca"}
    # PO decision 2026-10-18: the policy pod mounts the existing fingerprint key exactly like the worker pod (no new Secret)
    worker = next(d for d in yaml.safe_load_all((ROOT / "deploy/ui2/52-worker-deployment.yaml").read_text()) if d["kind"] == "Deployment")
    worker_spec = worker["spec"]["template"]["spec"]
    worker_container = next(c for c in worker_spec["containers"] if c["name"] == "worker")
    def key_wiring(pod, ctr):
        env = next(e for e in ctr["env"] if e["name"] == "UI2_HOSTNAME_FINGERPRINT_KEY_FILE")
        mount = next(m for m in ctr["volumeMounts"] if m["name"] == "hostname-fingerprint-key")
        volume = next(v for v in pod["volumes"] if v["name"] == "hostname-fingerprint-key")
        return env, mount, volume
    assert key_wiring(spec, container) == key_wiring(worker_spec, worker_container)
    assert key_wiring(spec, container)[2]["secret"]["secretName"] == "ui2-hostname-fingerprint-key"
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


def test_a_false_barrier_reports_which_condition_holds_it_with_counts_only(monkeypatch):
    # Pilot 2026-10-21/22: a quarantined fleet task lease held the general drain with no visible reason.
    queries = []
    output = ("BEGIN\nsystem:release\nmodule_release\nquarantine-drain\n0\nf\n"
              "barrier drain_ack=true owner_live=true busy_jobs=0 admissions=0 task_leases=0 quarantined_task_leases=1\nCOMMIT")
    monkeypatch.setattr(deploy, "query", lambda sql: queries.append(sql) or output)
    why = []
    assert deploy.barrier("worker", 8, why) is False
    assert why == ["barrier drain_ack=true owner_live=true busy_jobs=0 admissions=0 task_leases=0 quarantined_task_leases=1"]
    sql = queries[-1]
    explain = sql[sql.index("select 'barrier '"):]
    for name in ("drain_ack=", "owner_live=", "busy_jobs=", "admissions=", "task_leases=", "quarantined_task_leases="):
        assert "'" + name + "'" in explain
    assert "count(*)" in explain and "owner_instance ||" not in explain and "task_key" not in explain
    assert explain.index("from module_runtime_control where module='general'") > 0
    # The diagnostic line is never mistaken for the barrier boolean.
    monkeypatch.setattr(deploy, "query", lambda sql: output.replace("\nf\n", "\nt\n"))
    why = []
    assert deploy.barrier("worker", 8, why) is True and why == []


def test_the_drain_wait_prints_and_keeps_the_reason(monkeypatch, capsys):
    reason = "barrier drain_ack=true owner_live=true busy_jobs=2 admissions=0 task_leases=0 quarantined_task_leases=0"
    monkeypatch.setattr(deploy, "barrier", lambda target, generation, why=None: why.append(reason) or False)
    now = [0]
    def sleep(seconds):
        now[0] += seconds
    with pytest.raises(deploy.Blocked, match="busy_jobs=2") as blocked:
        deploy.wait_drain("worker", 3, 20, sleep=sleep, clock=lambda: now[0])
    assert blocked.value.continuable
    assert "Module waiting: worker; generation=3; " + reason in capsys.readouterr().out


def test_a_quarantine_left_by_an_ended_job_does_not_hold_the_drain():
    # Stopping the old pod is what releases it (OWNER_GONE); counting it would wait on itself.
    sql = dict(deploy.barrier_conditions("policy", 7))["admissions"]
    assert "state='LEASED'" in sql and "state='QUARANTINED'" in sql
    assert "t.state in ('COMPLETED','FAILED')" in sql and "OUTCOME_UNKNOWN" not in sql
    # CANCELLED is not an outcome the worker recorded: it releases only when the attempt record proves no write began.
    assert "'COMPLETED','FAILED','CANCELLED'" not in sql
    assert "t.state='CANCELLED' and not exists(select 1 from job_step_attempt a" in sql
    assert "a.mutation_boundary_crossed or a.action_class<>'read'" in sql


# --- Database-backed drain barrier admissions (SECURITY_REVIEW_2026_11_05 M-1) ---------------------------------------
# The admissions condition runs against a throwaway local PostgreSQL cluster holding only the columns the condition
# reads, with the production CHECK and foreign-key shape. Skipped, with a reason, where no PostgreSQL server binaries
# exist; never touches a host or the release database.

def _pg_bin():
    import glob
    import shutil
    found = shutil.which("initdb")
    if found:
        return Path(found).parent
    candidates = sorted(glob.glob("/usr/lib/postgresql/*/bin/initdb"))
    return Path(candidates[-1]).parent if candidates else None


@pytest.fixture(scope="module")
def scratch_pg():
    import os
    import pwd
    import shutil
    import subprocess
    import tempfile
    bin_dir = _pg_bin()
    if bin_dir is None:
        pytest.skip("no PostgreSQL server binaries (initdb) available")
    # Its own directory, not pytest's tmp root: that root is private to the invoking user, and the cluster may run as
    # another account.
    base = Path(tempfile.mkdtemp(prefix="drain_pg_"))
    prefix = []
    if os.geteuid() == 0:
        # initdb refuses to run as root; run the scratch cluster as the packaged service account when one exists.
        try:
            account = pwd.getpwnam("postgres")
        except KeyError:
            pytest.skip("running as root with no postgres account for initdb")
        if not shutil.which("runuser"):
            pytest.skip("running as root without runuser for initdb")
        os.chown(base, account.pw_uid, account.pw_gid)
        prefix = ["runuser", "-u", "postgres", "--"]
    data, sock = base / "data", base

    def sh(*args, **kw):
        return subprocess.run(prefix + [str(a) for a in args], capture_output=True, text=True, timeout=60, **kw)

    init = sh(bin_dir / "initdb", "-A", "trust", "-U", "drain", "-D", data)
    if init.returncode:
        shutil.rmtree(base, ignore_errors=True)
        pytest.skip("initdb unavailable here: exit " + str(init.returncode))
    start = sh(bin_dir / "pg_ctl", "-D", data, "-w", "-l", base / "log",
               "-o", "-c listen_addresses='' -k " + str(sock) + " -c fsync=off", "start")
    if start.returncode:
        shutil.rmtree(base, ignore_errors=True)
        pytest.skip("scratch PostgreSQL did not start: exit " + str(start.returncode))

    def psql(sql):
        result = sh(bin_dir / "psql", "-v", "ON_ERROR_STOP=1", "-h", sock, "-U", "drain", "-d", "postgres", "-Atc", sql)
        assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    psql("""
        create table jobs (job_id text primary key, state text not null);
        create table job_step_attempt (attempt_id text primary key, job_id text not null references jobs(job_id),
            lease_epoch bigint not null, action_class text not null,
            mutation_boundary_crossed boolean not null default false, outcome text);
        create table endpoint_admission (request_id text primary key, purpose_class text not null,
            job_id text references jobs(job_id), job_epoch bigint, operation_ref text, operation_epoch bigint,
            owner_role text not null, state text not null check (state in ('WAITING','LEASED','QUARANTINED')),
            check ((job_id is not null and job_epoch is not null and operation_ref is null and operation_epoch is null)
                or (job_id is null and job_epoch is null and operation_ref is not null and operation_epoch is not null)));
    """)
    try:
        yield psql
    finally:
        sh(bin_dir / "pg_ctl", "-D", data, "-m", "immediate", "-w", "stop")
        shutil.rmtree(base, ignore_errors=True)


# (job state, [(lease_epoch, action_class, mutation_boundary_crossed, outcome)], holds the drain?)
ADMISSION_CASES = {
    "completed": ("COMPLETED", [(1, "recovery-write", True, "SUCCESS")], False),
    "failed": ("FAILED", [(1, "read", False, None)], False),
    "cancelled_before_any_attempt": ("CANCELLED", [], False),
    "cancelled_after_read_steps_only": ("CANCELLED", [(1, "read", False, "SUCCESS"), (2, "read", False, None)], False),
    # The M-1 scenario: a CLASS 1 write crossed its boundary, cancel was requested, the lease expired.
    "cancelled_after_crossing_the_boundary": ("CANCELLED", [(1, "read", False, "SUCCESS"),
                                                            (1, "recovery-write", True, None)], True),
    # A recorded outcome does not prove the device session ended cleanly; crossing alone holds.
    "cancelled_after_a_write_with_an_outcome": ("CANCELLED", [(1, "recovery-write", True, "SUCCESS")], True),
    # The backup path records CLASS 1 and never flips the flag; it still contacts the device.
    "cancelled_with_an_uncrossed_write_class_attempt": ("CANCELLED", [(1, "recovery-write", False, None)], True),
    "cancelled_with_a_failover_attempt": ("CANCELLED", [(1, "operational-state-change", False, None)], True),
    # Any lease epoch counts, not only the current one.
    "cancelled_with_a_crossing_in_an_earlier_epoch": ("CANCELLED", [(1, "recovery-write", True, None),
                                                                     (2, "read", False, None)], True),
    "outcome_unknown": ("OUTCOME_UNKNOWN", [(1, "read", False, None)], True),
    "executing": ("EXECUTING", [], True),
    "claimed": ("CLAIMED", [], True),
}


def _admissions_count(psql, target="worker"):
    return int(psql(dict(deploy.barrier_conditions(target, 7))["admissions"]))


@pytest.mark.parametrize("case", sorted(ADMISSION_CASES))
def test_a_quarantine_holds_the_drain_unless_its_job_provably_ended_without_a_write(scratch_pg, case):
    state, attempts, holds = ADMISSION_CASES[case]
    psql = scratch_pg
    psql("truncate endpoint_admission, job_step_attempt, jobs")
    rows = ["insert into jobs values ('job-1', " + deploy.literal(state) + ")"]
    for n, (epoch, action, crossed, outcome) in enumerate(attempts):
        rows.append("insert into job_step_attempt values ('att-%d', 'job-1', %d, %s, %s, %s)" % (
            n, epoch, deploy.literal(action), "true" if crossed else "false",
            deploy.literal(outcome) if outcome else "null"))
    rows.append("insert into endpoint_admission values ('req-1', 'BACKUP', 'job-1', 1, null, null, 'general', 'QUARANTINED')")
    psql(";".join(rows))
    assert _admissions_count(psql) == (1 if holds else 0)


def test_a_leased_or_job_less_quarantine_always_holds_and_other_owners_never_count(scratch_pg):
    psql = scratch_pg
    psql("truncate endpoint_admission, job_step_attempt, jobs")
    psql("insert into jobs values ('job-1', 'COMPLETED');"
         "insert into endpoint_admission values ('req-1', 'INVENTORY', 'job-1', 1, null, null, 'general', 'LEASED')")
    assert _admissions_count(psql) == 1
    psql("truncate endpoint_admission;"
         "insert into endpoint_admission values ('req-2', 'INVENTORY', null, null, 'op-1', 1, 'general', 'QUARANTINED')")
    assert _admissions_count(psql) == 1
    # A general quarantine never holds the policy module's drain, and a released state is never counted.
    psql("truncate endpoint_admission;"
         "insert into jobs values ('job-2', 'CANCELLED');"
         "insert into job_step_attempt values ('att-1', 'job-2', 1, 'recovery-write', true, null);"
         "insert into endpoint_admission values ('req-3', 'BACKUP', 'job-2', 1, null, null, 'general', 'QUARANTINED')")
    assert _admissions_count(psql, "policy") == 0
    assert _admissions_count(psql, "worker") == 1
    psql("update endpoint_admission set state='WAITING'")
    assert _admissions_count(psql, "worker") == 0
