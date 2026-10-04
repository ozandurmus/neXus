#!/usr/bin/env python3
"""Host-side isolated candidate check. Never invokes the production rollout path.

Database bytes go only through exec pipes; command errors and pod logs are withheld.
Build Jobs use the existing Kaniko/proxy/CA setup with their own emptyDir contexts.
The 60 s database-copy target is UNKNOWN until the reviewer's live run;
TIMING preview_db_copy measures the complete snapshot/dump/filtered-copy phase.
"""
import argparse
import base64
import copy
from contextlib import contextmanager
import json
from pathlib import Path
import re
import secrets
import select
import signal
import subprocess
import sys
import time

NS = "ui2-preview"
BUILD_NS = "ui2-build"
LABEL = "nexus/preview-run"
REGISTRY = "registry.kube-system.svc.cluster.local/"
AUDIT_SELECT = "SELECT * FROM public.audit_log WHERE occurred_at >= now() - interval '7 days'"
ENTRY_SELECT = """SELECT e.* FROM public.backup_artefact_entry e
JOIN (SELECT DISTINCT ON (device_id) artefact_id FROM public.backup_artefact
      ORDER BY device_id, created_at DESC, artefact_id DESC) latest USING (artefact_id)"""
# Values stay in the pod's environment, never in argv or logs. Source commands are read-only.
PG_ENV = 'export PGUSER="${POSTGRESQL_USER:-${POSTGRES_USER:-ui2_migrate}}"; export PGDATABASE="${POSTGRESQL_DATABASE:-${POSTGRES_DB:-ui2}}"; export PGPASSWORD="${POSTGRESQL_PASSWORD:-${POSTGRES_PASSWORD:-}}"; '


@contextmanager
def timing(phase):
    start = time.monotonic()
    try:
        yield
    finally:
        print(f"TIMING {phase} {time.monotonic() - start:.3f}", flush=True)


def k(namespace, *args, input=None, timeout=120):
    result = subprocess.run(["kubectl", "-n", namespace, *args], input=input,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Preview cluster operation failed; details withheld")
    return result.stdout


def load(repo, name):
    # kubectl already understands the existing YAML; no additional YAML dependency.
    return json.loads(k(NS, "create", "--dry-run=client", "-f", str(repo / name), "-o", "json"))


def create(namespace, obj):
    k(namespace, "create", "-f", "-", input=json.dumps(obj))


def metadata(name, labels=None):
    return dict(name=name, namespace=NS, labels=labels or {})


def policy(name, component, ingress, egress):
    return dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata(name),
                spec=dict(podSelector=dict(matchLabels={"app.kubernetes.io/component": component}),
                          policyTypes=["Ingress", "Egress"], ingress=ingress, egress=egress))


def peer(component, ports, direction):
    return {direction: [dict(podSelector=dict(matchLabels={"app.kubernetes.io/component": component}))],
            "ports": [dict(protocol="TCP", port=p) for p in ports]}


def policies():
    return [policy("database-only", "database", [peer("service", [5432], "from"),
                                               peer("compliance", [5432], "from")], []),
            policy("service-only", "service", [peer("e2e", [8080, 8086], "from")],
                   [peer("database", [5432], "to"), peer("compliance", [8085], "to")]),
            policy("compliance-only", "compliance", [peer("service", [8085], "from")],
                   [peer("database", [5432], "to")]),
            policy("e2e-only", "e2e", [], [peer("service", [8080, 8086], "to")])]


def pod_spec(template):
    spec = copy.deepcopy(template["spec"]["template"]["spec"])
    for key in ("priorityClassName", "tolerations", "serviceAccountName"):
        spec.pop(key, None)
    spec["automountServiceAccountToken"] = False
    spec.setdefault("securityContext", {}).setdefault("fsGroup", 0)
    # No production PVC, hostPath or writable state is ever shared.
    for volume in spec.get("volumes", []):
        if "persistentVolumeClaim" in volume:
            volume.pop("persistentVolumeClaim")
            volume["emptyDir"] = {"sizeLimit": "8Gi"}
    return spec


def database_manifest(template):
    spec = pod_spec(template)
    spec["restartPolicy"] = "Never"
    spec["activeDeadlineSeconds"] = 3600
    return dict(apiVersion="v1", kind="Pod", metadata=metadata("ui2-preview-db", {
        "app.kubernetes.io/component": "database"}), spec=spec)


def compliance_manifest(template, image, db_ip):
    result = copy.deepcopy(template)
    labels = {"app.kubernetes.io/component": "compliance"}
    result["metadata"] = metadata("ui2-preview-compliance")
    result["spec"]["selector"] = dict(matchLabels=labels)
    result["spec"]["template"] = dict(metadata=dict(labels=labels), spec=pod_spec(template))
    container = result["spec"]["template"]["spec"]["containers"][0]
    container["image"] = image
    replacements = {"UI2_DB_HOST": db_ip, "UI2_DB_PORT": "5432", "UI2_DB_NAME": "ui2",
                    "UI2_DB_URL": f"jdbc:postgresql://{db_ip}:5432/ui2",
                    "UI2_SCHEDULING_ENABLED": "false", "LOGGING_LEVEL_ROOT": "ERROR"}
    container["env"] = [e for e in container["env"] if e["name"] not in replacements] + [
        dict(name=name, value=value) for name, value in replacements.items()]
    return result


def service_manifest(template, image, db_ip, compliance_ip):
    result = copy.deepcopy(template)
    labels = {"app.kubernetes.io/component": "service"}
    result["metadata"] = metadata("ui2-preview-service")
    result["spec"]["selector"] = dict(matchLabels=labels)
    result["spec"]["template"] = dict(metadata=dict(labels=labels), spec=pod_spec(template))
    container = result["spec"]["template"]["spec"]["containers"][0]
    container["image"] = image
    container["args"] = ["service"]
    replacements = {"UI2_DB_HOST": db_ip, "UI2_DB_PORT": "5432", "UI2_DB_NAME": "ui2",
                    "UI2_DB_URL": f"jdbc:postgresql://{db_ip}:5432/ui2",
                    "UI2_SCHEDULING_ENABLED": "false", "UI2_SESSION_COOKIE_SECURE": "false",
                    "LOGGING_LEVEL_ROOT": "ERROR",
                    "UI2_COMPLIANCE_SERVICE_URL": f"http://{compliance_ip}:8085"}
    removed = set(replacements) | {"UI2_CONFIG_SERVICE_URL", "UI2_COMPLIANCE_SERVICE_URL",
                                  "UI2_CP_BACKUP_CREDENTIAL_REF"}
    container["env"] = [e for e in container["env"] if e["name"] not in removed] + [
        dict(name=name, value=value) for name, value in replacements.items()]
    return result


def e2e_manifest(template, image, service_ip):
    result = copy.deepcopy(template)
    result["metadata"] = metadata("ui2-preview-e2e")
    result["spec"]["suspend"] = False
    result["spec"]["activeDeadlineSeconds"] = 900
    spec = pod_spec(template)
    container = spec["containers"][0]
    container["image"] = image
    replacements = {"NEXUS_E2E_MODE": "quick", "NEXUS_E2E_BASE_URL": f"http://{service_ip}:8080",
                    "NEXUS_E2E_MACHINE_URL": f"http://{service_ip}:8086/internal/machine-session"}
    container["env"] = [e for e in container["env"] if e["name"] not in replacements] + [
        dict(name=name, value=value) for name, value in replacements.items()]
    result["spec"]["template"]["spec"] = spec
    return result


def wait_job(namespace, name, timeout=1800):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = json.loads(k(namespace, "get", "job", name, "-o", "json"))
        conditions = {c["type"] for c in job.get("status", {}).get("conditions", []) if c.get("status") == "True"}
        if "Failed" in conditions:
            raise RuntimeError("Preview job failed; logs withheld")
        if "Complete" in conditions:
            return job
        time.sleep(2)
    raise RuntimeError("Preview job timed out")


def build_manifest(template, loader, name, commit, owner, dockerfile, destination):
    job = copy.deepcopy(template)
    job["metadata"] = dict(name=name, namespace=BUILD_NS, labels={LABEL: owner},
                           annotations={"nexus/commit": commit})
    job["spec"]["backoffLimit"] = 0
    job["spec"]["activeDeadlineSeconds"] = 1800
    spec = pod_spec(template)
    builder = spec["containers"][0]
    builder["args"] = [a for a in builder["args"] if not a.startswith(
        ("--context=", "--dockerfile=", "--destination=", "--digest-file="))] + [
        "--context=dir:///workspace" + ("/ui2/frontend" if dockerfile.endswith("Dockerfile.e2e") else ""),
        "--dockerfile=/workspace/" + dockerfile, "--destination=" + destination,
        "--digest-file=/dev/termination-log"]
    builder["terminationMessagePolicy"] = "File"
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"]
    init = copy.deepcopy(loader["spec"]["containers"][0])
    init["name"] = "context-loader"
    init["command"] = ["sh", "-c", "while [ ! -f /workspace/.ready ]; do sleep 1; done"]
    # Kaniko's established root build exception does not apply to the loader.
    init["securityContext"]["runAsNonRoot"] = True
    init["volumeMounts"].append(dict(name="preview-ca", mountPath="/run/corp-ca", readOnly=True))
    spec["volumes"].append(dict(name="preview-ca", configMap=dict(name="corp-ca")))
    spec["initContainers"] = [init]
    job["spec"]["template"] = dict(metadata=dict(labels={LABEL: owner}), spec=spec)
    return job


def pipe_commands(source, target, timeout=60):
    """Fail on either side, including a producer failing after a successful restore."""
    producer = subprocess.Popen(source, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    consumer = None
    try:
        consumer = subprocess.Popen(target, stdin=producer.stdout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        producer.stdout.close()
        deadline = time.monotonic() + timeout
        consumed = consumer.wait(timeout=max(0.01, deadline - time.monotonic()))
        produced = producer.wait(timeout=max(0.01, deadline - time.monotonic()))
        if consumed or produced:
            raise RuntimeError("Preview stream failed; details withheld")
    finally:
        for process in (consumer, producer):
            if process is not None and process.poll() is None:
                process.kill()
                process.wait()
        if producer.stdout and not producer.stdout.closed:
            producer.stdout.close()


def build_images(repo, commit, owner):
    template = json.loads(k(BUILD_NS, "create", "--dry-run=client", "-f",
                            str(Path.home() / "build-job-proxy.yaml"), "-o", "json"))
    loader = load(repo, "deploy/ui2-image-build/20-context-loader.yaml")
    images = []
    for role, dockerfile in (("service", "ui2/Containerfile"), ("e2e", "ui2/frontend/Dockerfile.e2e")):
        with timing("preview_build_" + role):
            name = "ui2-preview-build-" + role + "-" + owner
            image = REGISTRY + "nexus-ui2-" + role
            create(BUILD_NS, build_manifest(template, loader, name, commit, owner, dockerfile,
                                           image + ":preview-" + commit[:12]))
            deadline = time.monotonic() + 120
            pod = None
            while time.monotonic() < deadline:
                pods = json.loads(k(BUILD_NS, "get", "pods", "-l", "job-name=" + name, "-o", "json"))["items"]
                pod = next((p for p in pods if any(s.get("state", {}).get("running") is not None
                    for s in p.get("status", {}).get("initContainerStatuses", []) if s["name"] == "context-loader")), None)
                if pod:
                    break
                time.sleep(2)
            if not pod:
                raise RuntimeError("Preview context loader unavailable")
            pod_name = pod["metadata"]["name"]
            pipe_commands(["tar", "-C", str(repo), "-cf", "-", "ui2", "project", "docs"],
                          ["kubectl", "-n", BUILD_NS, "exec", "-i", pod_name, "-c", "context-loader", "--",
                           "sh", "-c", 'set -e; tar -xf - -C /workspace; mkdir -p /workspace/ui2/.ca; '
                           'for certificate in /run/corp-ca/*; do '
                           'cp "$certificate" "/workspace/ui2/.ca/$(basename "$certificate").pem"; done; '
                           'touch /workspace/.ready'], timeout=120)
            job = wait_job(BUILD_NS, name)
            if (job.get("metadata", {}).get("annotations", {}).get("nexus/commit") != commit
                    or job.get("metadata", {}).get("labels", {}).get(LABEL) != owner):
                raise RuntimeError("Preview build was replaced")
            builder_name = job["spec"]["template"]["spec"]["containers"][0]["name"]
            pods = json.loads(k(BUILD_NS, "get", "pods", "-l", "job-name=" + name, "-o", "json"))["items"]
            digests = set()
            for pod in pods:
                if not any(o.get("uid") == job["metadata"]["uid"] for o in pod["metadata"].get("ownerReferences", [])):
                    continue
                for status in pod.get("status", {}).get("containerStatuses", []):
                    ended = status.get("state", {}).get("terminated", {})
                    if (status["name"] == builder_name and ended.get("exitCode") == 0
                            and re.fullmatch(r"sha256:[0-9a-f]{64}", ended.get("message", "").strip())):
                        digests.add(ended["message"].strip())
            if len(digests) != 1:
                raise RuntimeError("Preview candidate digest missing or ambiguous")
            images.append(image + "@" + digests.pop())
    return images


@contextmanager
def exported_snapshot(source):
    # Hold one read-only snapshot so filtered rows cannot refer to artefacts created after the dump.
    holder = subprocess.Popen(source[:4] + ["-i"] + source[4:] + [PG_ENV
        + 'export PGOPTIONS="-c default_transaction_read_only=on -c idle_in_transaction_session_timeout=240000"; '
        + 'exec psql -XAtq --set=ON_ERROR_STOP=1'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        holder.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n")
        holder.stdin.flush()
        if not select.select([holder.stdout], [], [], 15)[0]:
            raise RuntimeError("Preview snapshot unavailable")
        token = holder.stdout.readline().strip()
        if not re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", token):
            raise RuntimeError("Preview snapshot unavailable")
        yield token
    finally:
        try:
            holder.stdin.write("ROLLBACK;\n\\q\n")
            holder.stdin.close()
            holder.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            if holder.poll() is None:
                holder.kill()
            holder.wait()
        holder.stdout.close()


def copy_database():
    source = ["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]
    target = ["kubectl", "-n", NS, "exec", "-i", "ui2-preview-db", "--", "sh", "-c"]
    read_only = PG_ENV + 'export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=55000"; '
    with exported_snapshot(source) as snapshot:
        pipe_commands(source + [read_only + "exec pg_dump --format=custom --compress=0 --snapshot=" + snapshot
                                + " --exclude-table-data=public.audit_log --exclude-table-data=public.backup_artefact_entry"],
                      target + [PG_ENV + "exec pg_restore --exit-on-error --no-owner --dbname=ui2"])
        for table, query in (("audit_log", AUDIT_SELECT), ("backup_artefact_entry", ENTRY_SELECT)):
            sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '" + snapshot + "'; COPY (" + query + ") TO STDOUT; COMMIT"
            pipe_commands(source + [read_only + 'exec psql -Xq --set=ON_ERROR_STOP=1 -c "' + sql + '"'],
                          target + [PG_ENV + 'exec psql -Xq --set=ON_ERROR_STOP=1 -c "COPY public.' + table + ' FROM STDIN"'])


def copy_secret(name):
    secret = json.loads(k("ui2", "get", "secret", name, "-o", "json"))
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata(name),
                    type=secret.get("type", "Opaque"), data=secret["data"]))


def setup_database(repo):
    template = load(repo, "deploy/ui2/40-database-statefulset.yaml")
    live = json.loads(k("ui2", "get", "pod", "ui2-db-0", "-o", "json"))
    pinned = template["spec"]["template"]["spec"]["containers"][0]["image"]
    if live["spec"]["containers"][0]["image"] != pinned:
        raise RuntimeError("Production PostgreSQL differs from the pinned bootstrap; preview refused")
    config = load(repo, "deploy/ui2/10-configmap.yaml")
    create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata("ui2-config"),
                    data={key: config["data"][key] for key in ("db_name", "create-app-role.sh")}))
    values = {"migrate-user": "ui2_migrate", "app-user": "ui2_app",
              "migrate-password": secrets.token_urlsafe(32), "app-password": secrets.token_urlsafe(32)}
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata("ui2-db"), type="Opaque",
                    data={name: base64.b64encode(value.encode()).decode() for name, value in values.items()}))
    create(NS, database_manifest(template))
    k(NS, "wait", "--for=condition=Ready", "pod/ui2-preview-db", "--timeout=120s", timeout=130)
    with timing("preview_db_copy"):
        copy_database()
    return json.loads(k(NS, "get", "pod", "ui2-preview-db", "-o", "json"))["status"]["podIP"]


def setup_compliance(repo, image, db_ip):
    templates = load(repo, "deploy/ui2/55-compliance-deployment.yaml")["items"]
    deployment = next(t for t in templates if t["kind"] == "Deployment")
    service = copy.deepcopy(next(t for t in templates if t["kind"] == "Service"))
    service["metadata"] = metadata("ui2-preview-compliance")
    service["spec"]["selector"] = {"app.kubernetes.io/component": "compliance"}
    create(NS, compliance_manifest(deployment, image, db_ip))
    create(NS, service)
    k(NS, "rollout", "status", "deployment/ui2-preview-compliance", "--timeout=300s", timeout=310)
    # Resolve the preview Service once through Kubernetes; runtime DNS egress stays denied.
    return json.loads(k(NS, "get", "service", "ui2-preview-compliance", "-o", "json"))["spec"]["clusterIP"]


def setup_service(repo, image, db_ip, compliance_ip):
    service = service_manifest(load(repo, "deploy/ui2/50-service-deployment.yaml"), image, db_ip, compliance_ip)
    # Only the keys required by the existing authenticated service path, never production DB credentials.
    spec = service["spec"]["template"]["spec"]
    names = {v["secret"]["secretName"] for v in spec["volumes"] if "secret" in v} - {"ui2-db"}
    names.add("ui2-e2e-machine-token")
    for name in sorted(names):
        copy_secret(name)
    create(NS, service)
    create(NS, dict(apiVersion="v1", kind="Service", metadata=metadata("ui2-preview-service"),
                    spec=dict(type="ClusterIP", selector={"app.kubernetes.io/component": "service"},
                              ports=[dict(name="http", port=8080, targetPort="http"),
                                     dict(name="machine", port=8086, targetPort="machine")])))
    k(NS, "rollout", "status", "deployment/ui2-preview-service", "--timeout=300s", timeout=310)
    return json.loads(k(NS, "get", "service", "ui2-preview-service", "-o", "json"))["spec"]["clusterIP"]


def cleanup(owner):
    # Attempt both cleanups even if one API call fails. A cleanup failure is a red gate.
    failures = []
    with timing("preview_teardown"):
        for namespace, kind, selector in ((BUILD_NS, "jobs", LABEL + "=" + owner), (NS, "namespace", "")):
            try:
                if kind == "namespace":
                    ns = json.loads(k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "json") or "{}")
                    if not ns:
                        continue
                    if ns.get("metadata", {}).get("labels", {}).get(LABEL) != owner:
                        raise RuntimeError("Preview namespace ownership changed")
                    k(NS, "delete", "namespace", NS, "--wait=true", "--timeout=120s", timeout=130)
                    if k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "name").strip():
                        raise RuntimeError("Preview namespace remains")
                else:
                    k(namespace, "delete", kind, "-l", selector, "--ignore-not-found", "--cascade=foreground",
                      "--wait=true", "--timeout=120s", timeout=130)
                    if json.loads(k(namespace, "get", "jobs,pods", "-l", selector, "-o", "json"))["items"]:
                        raise RuntimeError("Preview build objects remain")
            except (RuntimeError, OSError, ValueError, subprocess.SubprocessError):
                failures.append(kind)
    if failures:
        raise RuntimeError("Preview teardown incomplete; ship blocked")


def run(repo, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid candidate commit")
    if k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "name").strip():
        raise RuntimeError("Preview namespace already exists; refusing to reuse or delete it")
    owner = secrets.token_hex(6)
    try:
        create(NS, dict(apiVersion="v1", kind="Namespace", metadata=dict(name=NS, labels={LABEL: owner})))
        for item in policies():
            create(NS, item)
        images = build_images(repo, commit, owner)
        with timing("preview_database"):
            db_ip = setup_database(repo)
        with timing("preview_compliance"):
            compliance_ip = setup_compliance(repo, images[0], db_ip)
        with timing("preview_service"):
            service_ip = setup_service(repo, images[0], db_ip, compliance_ip)
        with timing("preview_e2e"):
            templates = load(repo, "deploy/ui2/70-e2e-job.yaml")
            job = next(t for t in templates["items"] if t["kind"] == "Job")
            # Canary is optional in the production manifest; copy it only if provisioned.
            if k("ui2", "get", "secret", "ui2-e2e-canary", "--ignore-not-found", "-o", "name").strip():
                copy_secret("ui2-e2e-canary")
            create(NS, e2e_manifest(job, images[1], service_ip))
            wait_job(NS, "ui2-preview-e2e", timeout=930)
    finally:
        cleanup(owner)
    print("PREVIEW E2E: PASS", flush=True)


def interrupted(signum, frame):
    raise RuntimeError("Preview interrupted")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, interrupted)
    try:
        run(args.repo, args.commit)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        sys.exit("PREVIEW E2E: FAIL; details withheld")
