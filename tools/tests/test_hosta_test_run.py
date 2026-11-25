"""HOST-A test namespace (docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md, DRAFT; PO 2026-11-24).

Offline: no host, no cluster. The manifests under deploy/ui2-test/ are checked for the PO's rules (production always
wins, 8 CPU / 24 GiB / 3 pods, default-deny egress, no Secret, no RBAC); hosta_test_run.py is driven end to end through
a stub ssh (runs the host side locally, as the security pre-gate tests do) and a stub kubectl; the pod runner script
and its summary program run with a fake Gradle and the real JDK when one is installed.
"""
import base64
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import hosta_test_run as driver  # noqa: E402
import hosta_test_run_remote as remote  # noqa: E402
import hosta_test_runner_image as image_build  # noqa: E402

NS_DIR = ROOT / "deploy" / "ui2-test"
IMAGE = "registry.kube-system.svc.cluster.local/nexus-ui2-test-runner@sha256:" + "a" * 64
RUN_NAME = "ui2-test-run-0123456789ab"


def docs_in(path):
    return [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d]


def all_docs():
    return [(p.name, d) for p in sorted(NS_DIR.glob("*.yaml")) for d in docs_in(p)]


def one(kind, name=None):
    found = [d for _, d in all_docs() if d["kind"] == kind and (name is None or d["metadata"]["name"] == name)]
    assert len(found) == 1, (kind, name, len(found))
    return found[0]


def quantity(value):
    """CPU in millicores or memory/storage in bytes, for the units these manifests use."""
    value = str(value)
    units = {"m": 1, "Ki": 1024, "Mi": 1024 ** 2, "Gi": 1024 ** 3}
    for suffix, factor in units.items():
        if value.endswith(suffix):
            return int(value[:-len(suffix)]) * factor
    return int(float(value) * 1000)  # whole cores


def template():
    return one("Job")


def scope(**over):
    s = {"java": "scoped", "tasks": [":worker:test", ":architecture-tests:test"], "integration": True, "vitest": False}
    s.update(over)
    return s


# ------------------------------------------------------------------------------------------------ manifest rules

def test_every_object_is_in_the_test_namespace_or_cluster_scoped_priority():
    for name, doc in all_docs():
        if doc["kind"] in ("Namespace", "PriorityClass"):
            continue
        assert doc["metadata"]["namespace"] == "ui2-test", name
    assert one("Namespace")["metadata"]["name"] == "ui2-test"
    assert one("Namespace")["metadata"]["labels"]["pod-security.kubernetes.io/enforce"] == "restricted"


def test_priority_is_below_every_production_class_and_the_default_and_never_preempts():
    test_class = one("PriorityClass")
    production = [d for d in docs_in(ROOT / "deploy" / "ui2" / "01-priority-classes.yaml") if d["kind"] == "PriorityClass"]
    assert production, "the production classes must be readable"
    for path in (ROOT / "deploy").rglob("*.yaml"):
        if NS_DIR in path.parents:
            continue
        for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
            if isinstance(doc, dict) and doc.get("kind") == "PriorityClass":
                production.append(doc)
    assert test_class["value"] < 0
    assert test_class["value"] < min(d["value"] for d in production)
    assert test_class["preemptionPolicy"] == "Never"
    assert test_class["globalDefault"] is False
    assert template()["spec"]["template"]["spec"]["priorityClassName"] == test_class["metadata"]["name"]


def test_quota_is_8_cpu_requested_27_cpu_burst_24_gib_3_pods_and_forbids_secrets_services_and_claims():
    # PO 2026-11-25: the host is half idle; pods request little (priority -1000 keeps production first) and may burst
    # into idle CPU up to 8 cores each.
    hard = one("ResourceQuota")["spec"]["hard"]
    assert quantity(hard["requests.cpu"]) == 8000
    assert quantity(hard["limits.cpu"]) == 27000
    assert quantity(hard["requests.memory"]) == quantity(hard["limits.memory"]) == 24 * 1024 ** 3
    assert hard["pods"] == "3"
    for key in ("secrets", "services", "persistentvolumeclaims", "services.nodeports", "services.loadbalancers"):
        assert hard[key] == "0", key


def test_limit_range_lets_exactly_three_largest_pods_fit_the_quota():
    hard = one("ResourceQuota")["spec"]["hard"]
    limits = {item["type"]: item for item in one("LimitRange")["spec"]["limits"]}
    pod_max = limits["Pod"]["max"]
    assert 3 * quantity(pod_max["cpu"]) <= quantity(hard["limits.cpu"])
    assert 3 * quantity(pod_max["memory"]) <= quantity(hard["limits.memory"])
    assert 3 * quantity(pod_max["ephemeral-storage"]) <= quantity(hard["limits.ephemeral-storage"])
    container = limits["Container"]
    for key in ("default", "defaultRequest", "max"):
        assert {"cpu", "memory"} <= set(container[key]), key


def test_network_policy_is_default_deny_with_only_the_database_and_registry_egress():
    policies = {d["metadata"]["name"]: d for _, d in all_docs() if d["kind"] == "NetworkPolicy"}
    deny = policies.pop("default-deny")["spec"]
    assert deny["podSelector"] == {} and set(deny["policyTypes"]) == {"Ingress", "Egress"}
    assert "ingress" not in deny and "egress" not in deny
    allowed = []
    for policy in policies.values():
        spec = policy["spec"]
        assert spec["policyTypes"] == ["Egress"], "no policy opens ingress"
        assert "ingress" not in spec
        allowed += spec["egress"]
    assert len(allowed) == 2
    database, registry = allowed
    assert database["to"] == [{"podSelector": {"matchLabels": {"nexus-test/role": "postgres"}}}]
    assert database["ports"] == [{"protocol": "TCP", "port": 5432}]
    assert registry["to"] == [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
                               "podSelector": {"matchLabels": {"app.kubernetes.io/component": "registry"}}}]
    assert registry["ports"] == [{"protocol": "TCP", "port": 5000}]
    text = json.dumps(allowed)
    for forbidden in ("ipBlock", '"ui2"', "kube-dns", '"port": 53', "0.0.0.0"):
        assert forbidden not in text, forbidden


def test_no_secret_and_no_rbac_object_exists_and_the_service_account_mounts_no_token():
    kinds = {d["kind"] for _, d in all_docs()}
    assert not kinds & {"Secret", "Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding", "Service",
                        "PersistentVolumeClaim", "ConfigMap"}
    account = one("ServiceAccount")
    assert account["automountServiceAccountToken"] is False


def pod_checks(job):
    spec = job["spec"]["template"]["spec"]
    assert job["metadata"]["namespace"] == "ui2-test"
    assert job["spec"]["backoffLimit"] == 0 and job["spec"]["activeDeadlineSeconds"] <= 3600
    assert job["spec"]["ttlSecondsAfterFinished"] > 0
    assert spec["serviceAccountName"] == one("ServiceAccount")["metadata"]["name"]
    assert spec["automountServiceAccountToken"] is False and spec["enableServiceLinks"] is False
    assert spec["securityContext"]["runAsNonRoot"] is True
    for key in ("hostNetwork", "hostPID", "hostIPC"):
        assert not spec.get(key)
    text = json.dumps(spec)
    for forbidden in ("secret", "Secret", "hostPath", "serviceAccountToken", "configMap"):
        assert forbidden not in text, forbidden
    limits = {item["type"]: item for item in one("LimitRange")["spec"]["limits"]}
    containers = spec.get("initContainers", []) + spec["containers"]
    long_running = [c for c in containers if c in spec["containers"] or c.get("restartPolicy") == "Always"]
    for c in containers:
        assert c["securityContext"]["allowPrivilegeEscalation"] is False
        assert c["securityContext"]["readOnlyRootFilesystem"] is True
        assert c["securityContext"]["capabilities"]["drop"] == ["ALL"]
        assert "envFrom" not in c
        for resource in ("cpu", "memory", "ephemeral-storage"):
            assert quantity(c["resources"]["requests"][resource]) <= quantity(c["resources"]["limits"][resource])
            assert quantity(c["resources"]["limits"][resource]) <= quantity(limits["Container"]["max"][resource])
    short = [c for c in containers if c not in long_running]
    for resource in ("cpu", "memory", "ephemeral-storage"):
        # Worst case: the admission adds the largest one-shot init container on top of everything long running.
        total = sum(quantity(c["resources"]["limits"][resource]) for c in long_running)
        total += max([quantity(c["resources"]["limits"][resource]) for c in short], default=0)
        assert total <= quantity(limits["Pod"]["max"][resource]), resource
    return spec


def test_job_template_runs_as_an_unprivileged_secretless_low_priority_pod():
    spec = pod_checks(template())
    postgres, = [c for c in spec["initContainers"] if c["name"] == "postgres"]
    assert postgres["args"] == ["-c", "listen_addresses=127.0.0.1"]
    assert postgres["image"].startswith("postgres:16@sha256:")


@pytest.mark.parametrize("integration", [True, False])
def test_rendered_job_keeps_every_rule_and_drops_the_database_when_unused(integration):
    job = remote.render_job(template(), RUN_NAME, IMAGE, scope(integration=integration), 1800)
    spec = pod_checks(job)
    assert job["metadata"]["name"] == RUN_NAME and job["spec"]["activeDeadlineSeconds"] == 1800
    assert job["spec"]["template"]["metadata"]["labels"]["nexus-test-run"] == RUN_NAME
    runner, = spec["containers"]
    assert runner["image"] == IMAGE
    env = {e["name"]: e["value"] for e in runner["env"]}
    assert env["TEST_JAVA"] == "scoped" and env["TEST_GRADLE_TASKS"] == ":worker:test :architecture-tests:test"
    assert env["TEST_INTEGRATION"] == ("1" if integration else "0")
    if integration:
        assert [c["name"] for c in spec["initContainers"]] == ["db-password", "postgres"]
        assert env["UI2_TEST_JDBC_URL"].startswith("jdbc:postgresql://127.0.0.1:5432/")
    else:
        assert spec["initContainers"] == [] and "UI2_TEST_JDBC_URL" not in env
        assert not any(v["name"] == "test-db" for v in spec["volumes"])


@pytest.mark.parametrize("bad", [
    dict(name="ui2-test-run-xyz"), dict(image="docker.io/library/evil:latest"), dict(deadline=30), dict(deadline=99999),
    dict(scope=scope(tasks=[":worker:test; rm -rf /"])), dict(scope=scope(tasks=[":integration-tests:test"])),
    dict(scope=scope(java="none", tasks=[], integration=False)), dict(scope=scope(java="full")),
])
def test_render_refuses_anything_outside_the_closed_form(bad):
    args = dict(name=RUN_NAME, image=IMAGE, scope=scope(), deadline=1800)
    args.update(bad)
    with pytest.raises(ValueError):
        remote.render_job(template(), args["name"], args["image"], args["scope"], args["deadline"])


def test_runner_image_builds_from_the_pinned_jdk_base_with_node_22_and_a_non_root_user():
    text = (ROOT / "ui2" / "Containerfile.test-runner").read_text()
    assert "FROM registry.access.redhat.com/ubi9/openjdk-21@sha256:" in text
    assert "nodejs:22" in text and "GRADLE_RO_DEP_CACHE" in text and "seed.sha256" in text
    assert text.rstrip().splitlines()[-2:] == ["USER 999", "HEALTHCHECK NONE"]
    args = image_build.builder_args(["--context=dir:///workspace", "--destination=x", "--cache=false"], "c" * 40)
    assert "--dockerfile=/workspace/ui2/Containerfile.test-runner" in args
    assert "--destination=" + image_build.REPOSITORY + ":" + "c" * 12 in args
    assert "--destination=x" not in args and "--cache=false" not in args
    assert remote.IMAGE.fullmatch(image_build.REPOSITORY + "@sha256:" + "b" * 64)


def test_image_record_accepts_only_a_digest_reference(tmp_path):
    target = tmp_path / "nexus" / "test-runner-image"
    image_build.record(IMAGE, target)
    assert target.read_text() == IMAGE + "\n"
    with pytest.raises(ValueError):
        image_build.record("registry.kube-system.svc.cluster.local/nexus-ui2-test-runner:latest", target)


# ------------------------------------------------------------------------------------------------ summary rules

def phase(**over):
    p = dict(name="java", status="PASS", reason="none", exit_code=0, duration_s=12, tests=40, failures=0, errors=0,
             skipped=1, failed=[])
    p.update(over)
    return p


def test_summary_validation_accepts_consistent_phases():
    remote.validate_summary({"status": "PASS", "reason": "none", "phases": [phase()]})
    remote.validate_summary({"status": "FAIL", "reason": "none", "phases": [
        phase(), phase(name="integration", status="FAIL", exit_code=1, failures=1, failed=["SchemaTest.columns"])]})
    remote.validate_summary({"status": "IMAGE_STALE", "reason": "dependency_files_changed", "phases": []})


@pytest.mark.parametrize("summary", [
    {"status": "PASS", "reason": "none", "phases": []},
    {"status": "PASS", "reason": "none", "phases": [phase(status="FAIL", exit_code=1)]},
    {"status": "PASS", "reason": "none", "phases": [phase(failures=1)]},
    {"status": "PASS", "reason": "none", "phases": [phase(tests=0)]},
    {"status": "PASS", "reason": "none", "phases": [phase(), phase()]},
    {"status": "FAIL", "reason": "none", "phases": [phase(status="FAIL", failed=['x"; echo'])]},
    {"status": "FAIL", "reason": "none", "phases": [phase(status="FAIL", failed=["a"] * 41)]},
    {"status": "PASS", "reason": "none", "phases": [phase(tests=-1)]},
    {"status": "PASS", "reason": "none", "phases": [phase(tests=True)]},
    {"status": "PASS", "reason": "none", "phases": [phase(message="raw")]},
    {"status": "PASS", "reason": "none", "phases": [phase()], "raw": "x"},
    {"status": "IMAGE_STALE", "reason": "x", "phases": [phase()]},
])
def test_summary_validation_refuses_contradictions_and_raw_text(summary):
    with pytest.raises(ValueError):
        remote.validate_summary(summary)


def test_summary_line_must_be_unique():
    line = "TESTRUN_SUMMARY " + json.dumps({"status": "PASS", "reason": "none", "phases": [phase()]})
    assert remote.summary_from_logs("noise\n" + line + "\n")["status"] == "PASS"
    for text in ("", line + "\n" + line, "TESTRUN_SUMMARY {not json}"):
        with pytest.raises(ValueError):
            remote.summary_from_logs(text)


# ------------------------------------------------------------------------------------------------ scope

def test_scope_follows_pretest_scope_and_falls_back_to_full():
    scoped = lambda repo, base: {"scope": "scoped", "gradle_tasks": [":worker:test"], "frontend": True, "why": "x"}
    full = lambda repo, base: {"scope": "full", "gradle_tasks": [], "frontend": True, "why": "y"}
    s, _ = driver.build_scope(ROOT, "origin/main", "scoped", True, False, scope_of=scoped)
    assert s == {"java": "scoped", "tasks": [":worker:test"], "integration": True, "vitest": True}
    s, _ = driver.build_scope(ROOT, "origin/main", "scoped", False, False, scope_of=full)
    assert s == {"java": "full", "tasks": [], "integration": False, "vitest": False}
    with pytest.raises(ValueError):
        driver.build_scope(ROOT, "origin/main", "none", False, False)


# ------------------------------------------------------------------------------------------------ end to end

SSH_STUB = '''#!/usr/bin/env bash
while [ "$#" -gt 2 ]; do shift; done
exec bash -c "$2"
'''

KUBECTL_STUB = r'''#!/usr/bin/env python3
import io, json, os, sys, tarfile, time
state = os.environ["FAKE_KUBE_DIR"]
mode = os.environ.get("FAKE_KUBE_MODE", "ok")
args = sys.argv[1:]
log = lambda line: open(os.path.join(state, "log"), "a").write(line + "\n")
log(" ".join(a for a in args if not a.startswith("{")))
if args[:2] == ["get", "namespace"]:
    sys.exit(0 if mode != "no-namespace" else 1)
assert args[:2] == ["-n", "ui2-test"], args
args = args[2:]
ready = os.path.join(state, "workspace", "ready")
if args[:3] == ["create", "-f", "-"]:
    open(os.path.join(state, "job.json"), "w").write(sys.stdin.read())
    open(os.path.join(state, "created"), "w").write("")
elif args[:2] == ["get", "pods"]:
    if mode == "never":
        print(json.dumps({"items": []})); sys.exit()
    status = {"containerStatuses": [{"name": "runner", "state": {"running": {}}}]}
    if mode == "pull":
        status = {"containerStatuses": [{"name": "runner", "state": {"waiting": {"reason": "ImagePullBackOff"}}}]}
    if mode == "crash":
        status = {"initContainerStatuses": [{"name": "postgres", "state": {"waiting": {"reason": "CrashLoopBackOff"}}}],
                  "containerStatuses": [{"name": "runner", "state": {"waiting": {"reason": "PodInitializing"}}}]}
    print(json.dumps({"items": [{"metadata": {"name": "pod-1"}, "status": status}]}))
elif args[:1] == ["exec"]:
    command = args[args.index("--") + 1:]
    if command[0] == "tar":
        target = os.path.join(state, "workspace")
        os.makedirs(target, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()), mode="r|") as archive:
            archive.extractall(target, filter="data")
    elif command[0] == "touch":
        open(ready, "w").write("")
elif args[:2] == ["get", "job"]:
    conditions = []
    if os.path.exists(ready) and mode != "hang":
        summary = json.loads(os.environ["FAKE_SUMMARY"][len("TESTRUN_SUMMARY "):])
        conditions = [{"type": "Complete" if summary["status"] == "PASS" else "Failed", "status": "True"}]
    print(json.dumps({"status": {"conditions": conditions}}))
elif args[:1] == ["logs"]:
    print("gradle noise that must never leave the pod")
    print(os.environ["FAKE_SUMMARY"])
    if mode == "double":
        print(os.environ["FAKE_SUMMARY"])
elif args[:2] == ["delete", "job"]:
    open(os.path.join(state, "deleted"), "a").write(args[2] + "\n")
else:
    sys.exit(3)
'''


def git_repo(tmp):
    repo = tmp / "lane"
    (repo / "ui2" / "worker").mkdir(parents=True)
    (repo / "ui2" / "worker" / "A.java").write_text("class A {}\n")
    (repo / "docs" / "operations" / "sql").mkdir(parents=True)
    (repo / "docs" / "operations" / "sql" / "x.sql").write_text("select 1;\n")
    env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull)
    for command in (["init", "-q"], ["add", "."],
                    ["-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-qm", "c"]):
        subprocess.run(["git", "-C", str(repo), *command], check=True, env=env, capture_output=True)
    commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    return repo, commit


@pytest.fixture
def host(tmp_path):
    home, bindir, state, tmp = tmp_path / "home", tmp_path / "bin", tmp_path / "kube", tmp_path / "hosttmp"
    for d in (home / ".config" / "nexus", bindir, state, tmp):
        d.mkdir(parents=True)
    (home / ".config" / "nexus" / "test-runner-image").write_text(IMAGE + "\n")
    for name, text in (("ssh", SSH_STUB), ("kubectl", KUBECTL_STUB)):
        (bindir / name).write_text(text)
        (bindir / name).chmod(0o755)
    good = "TESTRUN_SUMMARY " + json.dumps({"status": "PASS", "reason": "none", "phases": [phase()]})
    env = dict(os.environ, HOME=str(home), PATH=str(bindir) + os.pathsep + os.environ["PATH"], NEXUS_HOST="synthetic-host",
               FAKE_KUBE_DIR=str(state), FAKE_SUMMARY=good, NEXUS_TEST_RUN_POLL_S="0.2", TMPDIR=str(tmp),
               GIT_CONFIG_GLOBAL=os.devnull)
    repo, commit = git_repo(tmp_path)
    return dict(env=env, state=state, home=home, repo=repo, commit=commit, tmp=tmp)


def run_driver(h, *extra, timeout=120, **env):
    command = [sys.executable, str(ROOT / "tools" / "delivery" / "hosta_test_run.py"), "--repo", str(h["repo"]),
               "--java", "full", "--integration", *extra]
    result = subprocess.run(command, env=dict(h["env"], **env), capture_output=True, text=True, timeout=timeout)
    lines = result.stdout.strip().splitlines()
    assert len(lines) == 1, result.stdout
    return result.returncode, json.loads(lines[0])


def refs(h):
    return subprocess.run(["git", "-C", str(h["repo"]), "for-each-ref", "refs/test-run"], capture_output=True,
                          text=True).stdout


def test_pass_round_trip_ships_the_tree_runs_one_job_and_cleans_everything(host):
    code, result = run_driver(host)
    assert code == 0 and result["status"] == "PASS", result
    assert result["commit"] == host["commit"] and result["summary"]["phases"][0]["tests"] == 40
    assert result["scope"] == {"java": "full", "tasks": [], "integration": True, "vitest": False}
    job = json.loads((host["state"] / "job.json").read_text())
    assert job["metadata"]["name"] == result["run"]
    assert job["spec"]["template"]["spec"]["containers"][0]["image"] == IMAGE
    workspace = host["state"] / "workspace"
    assert (workspace / "ui2" / "worker" / "A.java").is_file()
    assert (workspace / "docs" / "operations" / "sql" / "x.sql").is_file()
    assert (workspace / ".runner" / "test_runner_pod.sh").read_text() == \
        (ROOT / "tools" / "delivery" / "test_runner_pod.sh").read_text()
    assert (host["state"] / "deleted").read_text().split() == [result["run"]]
    assert refs(host) == "" and list(host["tmp"].iterdir()) == []
    assert "noise" not in json.dumps(result)


def test_fail_reports_counts_and_failed_names_and_exits_1(host):
    failing = {"status": "FAIL", "reason": "none", "phases": [
        phase(), phase(name="integration", status="FAIL", exit_code=1, failures=2, tests=300, skipped=0,
                       failed=["AuditScreenTest.refuses", "SchemaTest.columns"])]}
    code, result = run_driver(host, FAKE_SUMMARY="TESTRUN_SUMMARY " + json.dumps(failing))
    assert code == 1 and result["status"] == "FAIL"
    assert result["summary"]["phases"][1]["failed"] == ["AuditScreenTest.refuses", "SchemaTest.columns"]


def test_stale_image_and_missing_setup_exit_3_so_the_caller_runs_on_the_mac(host):
    stale = {"status": "IMAGE_STALE", "reason": "dependency_files_changed", "phases": []}
    code, result = run_driver(host, FAKE_SUMMARY="TESTRUN_SUMMARY " + json.dumps(stale))
    assert code == 3 and result["status"] == "IMAGE_STALE"
    (host["home"] / ".config" / "nexus" / "test-runner-image").unlink()
    (host["state"] / "created").unlink()
    code, result = run_driver(host)
    assert code == 3 and result == dict(result, status="NOT_CONFIGURED", reason="no_test_runner_image")
    assert not (host["state"] / "created").exists()


def test_no_namespace_is_not_configured(host):
    code, result = run_driver(host, FAKE_KUBE_MODE="no-namespace")
    assert code == 3 and result["reason"] == "no_test_namespace"


@pytest.mark.parametrize("mode,reason", [("pull", "image_pull"), ("crash", "database"), ("double", "no_summary")])
def test_runner_errors_exit_2_and_still_delete_the_job(host, mode, reason):
    code, result = run_driver(host, FAKE_KUBE_MODE=mode)
    assert code == 2 and result["status"] == "ERROR" and result["reason"] == reason
    assert (host["state"] / "deleted").read_text().split() == [result["run"]]
    assert refs(host) == ""


def test_session_deadline_is_bounded_and_deletes_the_job(host, monkeypatch):
    monkeypatch.setattr(driver, "HOST_MARGIN_S", -120)  # the host side would wait longer than the session
    for key, value in dict(host["env"], FAKE_KUBE_MODE="hang").items():
        monkeypatch.setenv(key, value)
    out = io.StringIO()
    s = {"java": "full", "tasks": [], "integration": False, "vitest": False}
    started = time.monotonic()
    code = driver.run(host["repo"], host["commit"], s, 4, "synthetic-host", out=out)
    result = json.loads(out.getvalue())
    assert code == 2 and result["status"] == "TIMEOUT" and time.monotonic() - started < 60
    assert result["run"] in (host["state"] / "deleted").read_text().split()
    assert refs(host) == ""
    assert signal.getsignal(signal.SIGINT) is not signal.SIG_IGN, "the caller's handlers come back"


def test_sigterm_stops_the_session_deletes_the_job_and_the_ref(host):
    command = [sys.executable, str(ROOT / "tools" / "delivery" / "hosta_test_run.py"), "--repo", str(host["repo"]),
               "--java", "full"]
    process = subprocess.Popen(command, env=dict(host["env"], FAKE_KUBE_MODE="hang"), stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + 60
    while not (host["state"] / "workspace" / "ready").exists() and time.monotonic() < deadline:
        time.sleep(0.1)
    process.send_signal(signal.SIGTERM)
    out, _ = process.communicate(timeout=90)
    assert process.returncode == 128 + signal.SIGTERM
    assert json.loads(out.strip().splitlines()[-1]) == {"status": "ERROR", "reason": "interrupted"}
    assert (host["state"] / "deleted").exists()
    assert refs(host) == ""


def test_a_result_for_another_run_is_refused():
    line = "RESULT " + json.dumps({"status": "PASS", "reason": "none", "run": "ui2-test-run-ffffffffffff",
                                   "commit": "a" * 40, "tree": "b" * 40,
                                   "summary": {"status": "PASS", "reason": "none", "phases": [phase()]}})
    assert driver.parse_result([line], RUN_NAME, "a" * 40, "b" * 40)["reason"] == "invalid_host_result"
    assert driver.parse_result([line.replace("ffffffffffff", "0123456789ab")], RUN_NAME, "a" * 40, "b" * 40)["status"] == "PASS"
    assert driver.parse_result([], RUN_NAME, "a" * 40, "b" * 40)["reason"] == "no_host_result"


def test_remote_command_and_payload_fit_one_argument():
    s = {"java": "full", "tasks": [], "integration": True, "vitest": True}
    command = driver.remote_command(RUN_NAME, "a" * 40, "b" * 40, 2640, driver.payload(s))
    assert len(command) < 120_000  # Linux MAX_ARG_STRLEN is 128 KiB per argument
    data = json.loads(base64.b64decode(driver.payload(s)))
    assert set(data["files"]) == set(remote.RUNNER_FILES) and data["scope"] == s


# ------------------------------------------------------------------------------------------------ pod runner

JAVA = shutil.which("java")

FAKE_GRADLE = r'''#!/usr/bin/env bash
# Writes one JUnit report per selected module; FAKE_GRADLE_MODE=fail makes one test fail, compile gives no report.
mode=${FAKE_GRADLE_MODE:-pass}
if [ "$mode" = compile ]; then echo "> Execution failed for task ':worker:compileJava'"; exit 1; fi
if [ "$mode" = offline ]; then echo "No cached version of x:y:1 available for offline mode"; exit 1; fi
for module in worker integration-tests; do
  case " $* " in *" :integration-tests:test "*) [ "$module" = integration-tests ] || continue ;;
                 *) [ "$module" = worker ] || continue ;; esac
  mkdir -p "$module/build/test-results/test"
  failures=0; body='<testcase classname="com.example.AlphaTest" name="works()"/>'
  if [ "$mode" = fail ] && [ "$module" = worker ]; then
    failures=1; body="$body"'<testcase classname="com.example.BetaTest" name="breaks()"><failure message="10.0.0.1 secret" type="AssertionError">trace</failure></testcase>'
  fi
  printf '<testsuite name="x" tests="%s" failures="%s" errors="0" skipped="0">%s</testsuite>\n' \
    "$((1 + failures))" "$failures" "$body" > "$module/build/test-results/test/TEST-com.example.AlphaTest.xml"
done
[ "$mode" = fail ] && exit 1
exit 0
'''


@pytest.fixture
def pod(tmp_path):
    workspace = tmp_path / "workspace"
    for module in ("worker", "integration-tests"):
        (workspace / "ui2" / module).mkdir(parents=True)
        (workspace / "ui2" / module / "build.gradle.kts").write_text("// " + module + "\n")
    (workspace / "ui2" / "settings.gradle.kts").write_text("include()\n")
    (workspace / ".runner").mkdir()
    shutil.copy(ROOT / "tools" / "delivery" / "TestRunSummary.java", workspace / ".runner")
    seed = tmp_path / "seed.sha256"
    seed.write_text(subprocess.run(["bash", str(ROOT / "tools" / "delivery" / "test_runner_pod.sh"), "--fingerprint",
                                    str(workspace)], capture_output=True, text=True, check=True).stdout)
    gradle = tmp_path / "gradle"
    gradle.write_text(FAKE_GRADLE)
    gradle.chmod(0o755)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    env = dict(os.environ, TEST_WORKSPACE=str(workspace), TEST_RUNNER_SEED=str(seed), TEST_SCRATCH=str(scratch),
               TEST_GRADLE_BIN=str(gradle), GRADLE_USER_HOME=str(tmp_path / "gh"), TEST_JAVA="scoped",
               TEST_GRADLE_TASKS=":worker:test", TEST_INTEGRATION="1", TEST_VITEST="0")
    return dict(workspace=workspace, env=env, seed=seed)


def run_pod(p, **env):
    result = subprocess.run(["bash", str(ROOT / "tools" / "delivery" / "test_runner_pod.sh")],
                            env=dict(p["env"], **env), capture_output=True, text=True, timeout=180)
    return result.returncode, remote.summary_from_logs(result.stdout), result.stdout


def test_fingerprint_lists_the_dependency_files_and_detects_a_change(pod):
    text = pod["seed"].read_text()
    assert "ui2/worker/build.gradle.kts" in text and "ui2/settings.gradle.kts" in text
    (pod["workspace"] / "ui2" / "worker" / "gradle.lockfile").write_text("new=1\n")
    code, summary, _ = run_pod(pod)
    assert code == 3 and summary == {"status": "IMAGE_STALE", "reason": "dependency_files_changed", "phases": []}


@pytest.mark.parametrize("env", [dict(TEST_GRADLE_TASKS=":worker:test;id"), dict(TEST_GRADLE_TASKS=":integration-tests:test"),
                                 dict(TEST_JAVA="all"), dict(TEST_VITEST="yes")])
def test_pod_refuses_a_scope_outside_the_closed_form(pod, env):
    code, summary, _ = run_pod(pod, **env)
    assert code == 2 and summary["reason"] == "bad_scope"


@pytest.mark.skipif(JAVA is None, reason="no JDK on this machine")
def test_pod_pass_reports_java_and_integration_phases(pod):
    code, summary, stdout = run_pod(pod)
    assert code == 0, stdout
    assert summary["status"] == "PASS"
    assert [(p["name"], p["status"], p["tests"]) for p in summary["phases"]] == [("java", "PASS", 1), ("integration", "PASS", 1)]


@pytest.mark.skipif(JAVA is None, reason="no JDK on this machine")
def test_pod_fail_names_the_test_and_never_its_message(pod):
    code, summary, stdout = run_pod(pod, FAKE_GRADLE_MODE="fail")
    assert code == 1 and summary["status"] == "FAIL"
    java_phase = summary["phases"][0]
    assert java_phase["failed"] == ["BetaTest.breaks"] and java_phase["failures"] == 1
    assert "10.0.0.1" not in stdout and "secret" not in stdout and "trace" not in stdout


@pytest.mark.skipif(JAVA is None, reason="no JDK on this machine")
@pytest.mark.parametrize("mode,status,reason", [("compile", "FAIL", "compile"), ("offline", "ERROR", "offline_miss")])
def test_pod_tells_a_compile_failure_from_a_dependency_cache_miss(pod, mode, status, reason):
    code, summary, _ = run_pod(pod, FAKE_GRADLE_MODE=mode, TEST_INTEGRATION="0")
    assert summary["status"] == status and summary["phases"][0]["reason"] == reason
    assert code == {"FAIL": 1, "ERROR": 2}[status]


# --- The deploy's build context carries only ui2/, project/ and docs/ (2026-11-25 first image build failure) ---------

def _image_tool():
    sys.path.insert(0, str(ROOT / "tools" / "e2e"))
    import hosta_test_runner_image as image
    return image


def test_every_containerfile_copy_source_outside_the_loaded_context_is_streamed_by_the_image_tool():
    image = _image_tool()
    loaded = ("ui2/", "project/", "docs/")
    sources = []
    for line in (ROOT / "ui2" / "Containerfile.test-runner").read_text().splitlines():
        if line.startswith("COPY ") and "--from=" not in line:
            sources += line.split()[1:-1]
    outside = {s for s in sources if not s.startswith(loaded)}
    assert outside and outside <= set(image.RUNNER_FILES)


def test_runner_files_tar_keeps_repository_paths():
    import tarfile
    image = _image_tool()
    with tarfile.open(fileobj=io.BytesIO(image.runner_files_tar()), mode="r") as tar:
        assert sorted(tar.getnames()) == sorted(image.RUNNER_FILES)


def test_runner_files_are_loaded_only_from_a_checkout_at_the_context_commit(monkeypatch):
    image = _image_tool()
    monkeypatch.setattr(image, "checkout_commit", lambda root=None: "a" * 40)
    calls = []
    with pytest.raises(ValueError):
        image.load_runner_files("b" * 40, run=lambda *a, **k: calls.append(a))
    assert not calls
    image.load_runner_files("a" * 40, run=lambda *a, **k: calls.append(a) or subprocess.CompletedProcess(a, 0))
    verbs = [c[0][1] if c[0][1] != "-n" else c[0][3] for c in calls]
    assert verbs == ["apply", "wait", "exec", "delete"]


def test_the_loader_is_closed_even_when_loading_fails(monkeypatch):
    image = _image_tool()
    monkeypatch.setattr(image, "checkout_commit", lambda root=None: "a" * 40)
    calls = []

    def run(argv, **kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 1 if "exec" in argv else 0)
    with pytest.raises(RuntimeError):
        image.load_runner_files("a" * 40, run=run)
    assert calls[-1][:2] == ["kubectl", "delete"]
def test_runner_image_seeds_the_host_supplied_gradle_distribution_before_the_first_gradlew():
    text = (ROOT / "ui2" / "Containerfile.test-runner").read_text()
    assert text.index("cp -a /seed/ui2/.gradle-home/wrapper /seed/home/wrapper") < text.index("GRADLE_USER_HOME=/seed/home ./gradlew")
