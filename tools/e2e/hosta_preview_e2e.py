#!/usr/bin/env python3
"""Host-side isolated candidate check. Never invokes the production rollout path.

Database bytes stay on the pod-to-pod network; pod logs are withheld.
Kubectl failures retain bounded, masked API diagnostics in the step log.
Build Jobs use the existing Kaniko/proxy/CA setup with their own emptyDir contexts.
The 60 s database-copy target is UNKNOWN until the reviewer's live run;
TIMING preview_db_copy measures the complete snapshot/dump/filtered-copy phase.
A preview without new migrations copies the nightly golden database instead (TIMING preview_db_golden);
`refresh-golden` refreshes it (docs/operations/PREVIEW_GOLDEN_DATABASE.md).
"""
import argparse
import base64
import copy
from contextlib import contextmanager
from datetime import datetime, timedelta
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import select
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from zoneinfo import ZoneInfo

# The host deploy tree, owned by the service account `nexus` (docs/operations/HOST_A_RUNBOOK.md §2).
HOST_TREE = Path("/opt/nexus")

NS = "ui2-preview"
BUILD_NS = "ui2-build"
LABEL = "nexus/preview-run"
# Preview pods are disposable: a long production drain grace only stalls namespace deletion.
PREVIEW_GRACE_SECONDS = 60
NAMESPACE_DELETE_SECONDS = 600
# Pod hygiene (2026-11-23): every preview pod runs at the lowest neXus priority (deploy/ui2/01-priority-classes.yaml),
# so under pressure a preview is evicted or waits before any production pod. Set per run only when the class exists;
# a cluster without it keeps the default priority rather than failing the gate.
PREVIEW_PRIORITY_CLASS = "ui2-low"
PREVIEW_PRIORITY = None
# Per-run namespace ceilings. Sized from the manifests the preview applies (database, service, every worker role,
# e2e job; 2026-11-23: 2.6 CPU and 6.5 GiB requested, 66.5 GiB memory limits, 10 pods) with headroom;
# tools/tests/test_preview_e2e.py recomputes the sums and fails when a manifest outgrows them. No CPU-limit quota:
# module pods carry no CPU limit (MODULE_POD_RUNTIME_CONTRACT.md), and a limits.cpu quota would force one on them.
PREVIEW_QUOTA = {"requests.cpu": "6", "requests.memory": "16Gi", "limits.memory": "96Gi", "pods": "20"}
# Defaults only for a container that states none (every current template states its own), and a per-container
# memory ceiling above the largest current limit (packet analysis, 24Gi).
PREVIEW_LIMIT_RANGE = {"type": "Container", "default": {"memory": "2Gi"},
                       "defaultRequest": {"cpu": "100m", "memory": "256Mi"}, "max": {"memory": "32Gi"}}
REGISTRY = "registry.kube-system.svc.cluster.local/"
AUDIT_SELECT = "SELECT * FROM public.audit_log WHERE occurred_at >= now() - interval '7 days'"
ENTRY_SELECT = """SELECT e.* FROM public.backup_artefact_entry e
JOIN (SELECT DISTINCT ON (device_id) artefact_id FROM public.backup_artefact
      ORDER BY device_id, created_at DESC, artefact_id DESC) latest USING (artefact_id)"""
# Check Point policy JSON chunks: only the generations a current manifest names (inventory or domain run). Replaced
# generations are never read; on 2026-11-22 they were 84% of a 14 GB table and most of the preview copy time.
CHUNK_SELECT = """SELECT c.* FROM public.cp_policy_json_chunk c
WHERE c.generation IN (SELECT chunk_generation FROM public.cp_policy_object_inventory WHERE chunk_generation IS NOT NULL
                       UNION SELECT chunk_generation FROM public.cp_policy_domain_run WHERE chunk_generation IS NOT NULL)"""
# Golden preview database (docs/operations/PREVIEW_GOLDEN_DATABASE.md): a nightly copy of the live database, served
# read-only to previews by a PostgreSQL in its own namespace. A preview whose candidate brings no migration the golden
# lacks streams it with pg_basebackup (physical, no restore) instead of reading the live database.
GOLDEN_NS = "ui2-preview-golden"
GOLDEN_LABEL = "nexus/preview-golden"
GOLDEN_MARKER = "golden-marker"
GOLDEN_SLOTS = ("a", "b")
GOLDEN_MAX_AGE_SECONDS = 36 * 3600
GOLDEN_REPL_USER = "ui2_golden_repl"
# The sclorg image's data directory under the "data" mount.
GOLDEN_PGDATA = "/var/lib/pgsql/data/userdata"
GOLDEN_COPY_SECONDS = 240
GOLDEN_READY_SECONDS = 300
# A preview that read the previous marker finishes its copy within GOLDEN_READY_SECONDS; only then is that slot removed.
GOLDEN_RETIRE_GRACE_SECONDS = GOLDEN_READY_SECONDS + 120
# Collection windows (00/06/12/18, from :20 of the hour before) and the 02:00 backup window stay free of the refresh.
GOLDEN_BLOCKED_HOURS = (0, 2, 6, 12, 18)
MIGRATIONS = "ui2/service/src/main/resources/db/migration"
# Values stay in the pod's environment, never in argv or logs. Source commands are read-only.
PG_ENV = 'unset PGPASSWORD; export PGUSER="${POSTGRESQL_USER:-${POSTGRES_USER:-ui2_migrate}}"; export PGDATABASE="${POSTGRESQL_DATABASE:-${POSTGRES_DB:-ui2}}"; '


PHASE = "preview_setup"
STEP = "get namespace"
LOG = None
# The PostgreSQL pod a copy writes into: the preview's own database pod unless a golden refresh names its staging pod.
DB_POD = None
INTERRUPTED = False


def target_db_pod():
    return DB_POD or preview_name("ui2-preview-db")
# chmod: images built from this context must not depend on the host tree's file modes (a 0640 file is unreadable
# to an image's runtime user; e2e EACCES 2026-11-13).
LOAD_CONTEXT = """set -e
tar -xf - -C /workspace
chmod -R a+rX /workspace/ui2 /workspace/project /workspace/docs
for input in /workspace/project/deploy_info.json /workspace/ui2/.ca/*.pem /workspace/ui2/.gradle-home/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3-bin.zip; do
    test -s "$input" || { echo CONTEXT_INCOMPLETE >&2; exit 1; }
done
touch /workspace/.ready
"""


# PHASE/STEP name where a failure happened. Two branches of run() execute at the same time (image builds and the
# database copy); each such branch keeps its own phase/step so a failure is attributed to the branch that raised it.
# The main thread keeps using the module globals, exactly as before.
_branch = threading.local()


def _where():
    if getattr(_branch, "active", False):
        return _branch.phase, _branch.step
    return PHASE, STEP


def _set_step(value):
    global STEP
    if getattr(_branch, "active", False):
        _branch.step = value
    else:
        STEP = value


def _set_phase(value):
    global PHASE
    if getattr(_branch, "active", False):
        _branch.phase = value
    else:
        PHASE = value


def run_concurrently(*branches):
    """Run the callables at the same time; wait for ALL of them; re-raise the first failure in argument order.

    Waiting for every branch (rather than failing fast) keeps teardown simple: no branch is still creating objects in
    the preview namespace when cleanup() starts. Threads are daemons so an interrupted run can still exit."""
    results = [None] * len(branches)
    errors = [None] * len(branches)
    # _where(), not the globals: a nested call (the two image builds inside the build branch) starts its branches
    # from the calling branch's phase/step. On the main thread this is the same PHASE/STEP as before.
    start_phase, start_step = _where()

    def worker(index, branch):
        _branch.active, _branch.phase, _branch.step = True, start_phase, start_step
        try:
            results[index] = branch()
        except BaseException as error:  # re-raised in the caller, in order
            errors[index] = error
        finally:
            _branch.active = False

    threads = [threading.Thread(target=worker, args=(i, b), daemon=True) for i, b in enumerate(branches)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    for error in errors:
        if error is not None:
            raise error
    return results


class PreviewFailure(RuntimeError):
    def __init__(self, reason, exception_class="PreviewFailure"):
        super().__init__("Preview operation failed; details withheld")
        self.phase, self.step = _where()
        self.reason = reason
        self.exception_class = exception_class


def classify_reason(stderr="", code=None):
    text = stderr.lower()
    if "context_incomplete" in text:
        return "CONTEXT_INCOMPLETE"
    if "namespace" in text and ("does not match" in text or "must pass '--namespace" in text):
        return "NAMESPACE_MISMATCH"
    if "forbidden" in text:
        return "FORBIDDEN"
    if "is invalid" in text:
        return "MANIFEST_INVALID"
    if "already exists" in text or "alreadyexists" in text:
        return "ALREADY_EXISTS"
    if "notfound" in text or "not found" in text:
        return "NOT_FOUND"
    if code in (124, 137) or "timed out" in text or "timeout" in text or "deadlineexceeded" in text or "deadline exceeded" in text:
        return "TIMEOUT"
    return "OTHER"


@contextmanager
def private_log():
    global LOG
    directory = Path.home() / ".local/state/nexus-preview"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    fd, _ = tempfile.mkstemp(prefix=time.strftime("%Y%m%dT%H%M%S-"), suffix=".log", dir=directory)
    with os.fdopen(fd, "w+") as log:
        LOG = log
        try:
            for old in sorted(directory.glob("*.log"), key=lambda p: p.stat().st_mtime, reverse=True)[10:]:
                old.unlink()
            yield
        except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
            failure_line(error)
            raise
        finally:
            LOG = None


def overlay_host_inputs(repo):
    """Copy only the approved host-local build inputs into the archived candidate."""
    _set_step("overlay host inputs")
    source = HOST_TREE.resolve()
    repo = repo.resolve()
    patterns = ("ui2/.ca/*.pem",
                "ui2/.gradle-home/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3-bin.zip")
    inputs = []
    try:
        for pattern in patterns:
            matches = sorted(source.glob(pattern))
            if not matches:
                raise PreviewFailure("HOST_INPUT_MISSING")
            for path in matches:
                target = repo / path.relative_to(source)
                # Never follow links into other host inputs or outside the candidate tree.
                if not path.is_file() or path.resolve() != path or target.resolve() != target:
                    raise PreviewFailure("HOST_INPUT_MISSING")
                inputs.append((path, target))
        for path, target in inputs:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    except OSError:
        raise PreviewFailure("HOST_INPUT_MISSING") from None


def prepare_build_context(repo, commit):
    """Mirror run_build.sh's generated metadata without changing the source checkout."""
    overlay_host_inputs(repo)
    _set_step("prepare build context")
    project = repo / "project"
    project.mkdir(parents=True, exist_ok=True)
    (project / "deploy_info.json").write_text(json.dumps({
        "commit": commit,
        "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "preview": True,
    }, indent=2) + "\n", encoding="utf-8")


def failure_line(error):
    if not isinstance(error, PreviewFailure):
        error = PreviewFailure("TIMEOUT" if isinstance(error, subprocess.TimeoutExpired) else "OTHER",
                               type(error).__name__)
    if error.reason.startswith("DB_COPY_FAILED_"):
        reason = error.reason.replace("DB_COPY_FAILED_", "DB_COPY_FAILED:", 1)
    elif error.reason == "DB_COPY_FAILED":
        reason = "DB_COPY_FAILED:OTHER"
    elif error.phase == "preview_db_copy":
        reason = "DB_COPY_FAILED:" + error.reason
    else:
        reason = f"{error.reason}:{error.exception_class}"
    line = (f"PREVIEW E2E: FAIL phase={error.phase} step={error.step} "
            f"reason={reason}")
    if LOG is not None:
        LOG.write(line + "\n")
        LOG.flush()
    return line


@contextmanager
def timing(phase):
    previous = _where()[0]
    _set_phase(phase)
    start = time.monotonic()
    try:
        yield
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        if isinstance(error, PreviewFailure):
            raise
        reason = "TIMEOUT" if isinstance(error, subprocess.TimeoutExpired) else (
            "DB_COPY_FAILED" if phase == "preview_db_copy" else
            "IMAGE_BUILD_FAILED" if phase.startswith("preview_build") else "OTHER")
        raise PreviewFailure(reason, type(error).__name__) from None
    finally:
        _set_phase(previous)
        print(f"TIMING {phase} {time.monotonic() - start:.3f}", flush=True)


def masked_error(stderr):
    """Keep API error wording/field paths, never arbitrary identities or values.

    Kubectl may embed an entire rejected object (including Secret data), a
    principal or a URL. Unknown tokens are withheld rather than guessed safe.
    """
    text = stderr.decode(errors="replace") if isinstance(stderr, bytes) else stderr or ""
    # Row contents are data, including nested/quoted and multiline values.
    text = re.sub(r'(Failing row contains\s*)\(.*', r'\1([MASKED])', text, flags=re.S)
    identifiers = []

    def schema_name(match):
        identifiers.append(match.group(2))
        return match.group(1) + f'"SCHEMAIDENTIFIER{len(identifiers) - 1}"'
    text = re.sub(r'((?:relation|constraint|column)\s+)"([A-Za-z_][A-Za-z0-9_]*)"', schema_name, text)
    # Only contextual SQL identifiers survive the generic quoted-value mask.
    text = re.sub(r'"SCHEMAIDENTIFIER(\d+)"', r'SCHEMAIDENTIFIER\1', text)
    text = re.sub(r'"[^"\n]*"|\'[^\'\n]*\'|\{[^\n]*\}|\[[^\n]*\]', '[MASKED]', text)
    safe = set("error from server forbidden invalid alreadyexists notfound conflict badrequest "
               "deployment service pod job namespace secret configmap list networkpolicy statefulset "
               "is are the a an of in on for to with and or not does match must pass cannot "
               "create patch update get resource resources api group at cluster scope user "
               "field fields required immutable value values unknown strict decoding missing "
               "selector labels namespace name kind version metadata spec status containers "
               "timeout timed out deadline exceeded connection refused unable validate validation "
               "failed failure no found exists apply manifest masked detail violates check constraint "
               "relation column null not-null row contains new failing duplicate key syntax permission denied".split())
    def token(match):
        value = match.group()
        if re.fullmatch(r'SCHEMAIDENTIFIER\d+', value):
            index = int(value.removeprefix('SCHEMAIDENTIFIER'))
            if index < len(identifiers):
                return '"' + identifiers[index] + '"'
        fields = {"spec", "metadata", "status", "selector", "matchLabels", "template", "labels", "name",
                  "namespace", "containers", "ports", "env", "value", "image", "volumeMounts", "volumes",
                  "resources", "limits", "requests", "replicas", "strategy", "type", "clusterIP", "port", "targetPort"}
        if value.lower() in safe or (value.startswith(("spec.", "metadata.", "status."))
                                     and all(part in fields for part in value.split("."))):
            return value
        return "MASKED"
    return re.sub(r'[A-Za-z0-9_./@=+-]+', token, text).replace('\n', ' ')[:1500]


def k(namespace, *args, input=None, timeout=120):
    verb = args[0]
    kinds = {"pod", "pods", "job", "jobs", "jobs,pods", "namespace", "secret", "service", "deployment"}
    kind = next((a.split("/")[0] for a in args[1:] if a.split("/")[0] in kinds), "manifest")
    if input is not None and verb == "apply":
        kind = json.loads(input)["kind"]
    _set_step(verb + " " + kind)
    argv = ["kubectl"] + (["-n", namespace] if namespace is not None else []) + list(args)
    def failed(stderr, reason, exception_class="PreviewFailure"):
        phase, step = _where()
        line = f"PREVIEW STEP: phase={phase} step={step} reason={reason}:{exception_class} error=" + masked_error(stderr)
        if LOG is not None:
            LOG.write(line + "\n")
            LOG.flush()
        print(line, flush=True)
        raise PreviewFailure(reason, exception_class) from None
    try:
        result = subprocess.run(argv, input=input, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        failed(error.stderr, "TIMEOUT", type(error).__name__)
    if result.returncode:
        failed(result.stderr, classify_reason(result.stderr, result.returncode))
    return result.stdout


def load(repo, name):
    # kubectl already understands the existing YAML; no additional YAML dependency.
    # Parsing must honor the source namespace, not the preview target namespace.
    _set_step("load templates")
    stream = k(None, "create", "--dry-run=client", "-f", str(repo / name), "-o", "json")
    _set_step("load templates")
    decoder, items, offset = json.JSONDecoder(), [], 0

    def append(obj):
        if obj["kind"] == "List":
            for item in obj["items"]:
                append(item)
        else:
            items.append(obj)

    while offset < len(stream):
        if stream[offset].isspace():
            offset += 1
            continue
        obj, offset = decoder.raw_decode(stream, offset)
        append(obj)
    return {"kind": "List", "items": items}


def create(namespace, obj):
    # Flatten Lists: namespace is a property of each item, never of the List.
    if obj["kind"] == "List":
        for item in obj["items"]:
            create(namespace, item)
        return
    obj = copy.deepcopy(obj)
    if obj["kind"] != "Namespace":
        obj.setdefault("metadata", {})["namespace"] = namespace
    k(namespace, "apply", "--server-side", "--field-manager=nexus-preview", "-f", "-", input=json.dumps(obj))


def preview_name(name):
    if name.startswith("ui2-preview"):
        return NS + name[len("ui2-preview"):]
    return NS + "-" + name.removeprefix("ui2-")


def metadata(name, labels=None):
    return dict(name=preview_name(name), namespace=NS, labels={**(labels or {}), LABEL: NS})


def policy(name, component, ingress, egress):
    return dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata(name),
                spec=dict(podSelector=dict(matchLabels={"app.kubernetes.io/component": component}),
                          policyTypes=["Ingress", "Egress"], ingress=ingress, egress=egress))


def peer(component, ports, direction):
    return {direction: [dict(podSelector=dict(matchLabels={"app.kubernetes.io/component": component}))],
            "ports": [dict(protocol="TCP", port=p) for p in ports]}


def policies(workers):
    endpoints = [(w["component"], w["ports"]) for w in workers if w["ports"]]
    return [policy("database-only", "database", [peer(c, [5432], "from")
                                               for c in ["service"] + [w["component"] for w in workers]], []),
            policy("service-only", "service", [peer("e2e", [8080, 8086], "from")],
                   [peer("database", [5432], "to")] + [peer(c, ports, "to") for c, ports in endpoints]),
            *[policy(w["component"] + "-only", w["component"],
                     [peer(c, w["ports"], "from") for c in ["service"] + [v["component"] for v in workers]
                      if c != w["component"] and w["ports"]],
                     [peer("database", [5432], "to")] + [peer(c, ports, "to") for c, ports in endpoints
                                                       if c != w["component"]]) for w in workers],
            policy("e2e-only", "e2e", [], [peer("service", [8080, 8086], "to")])]


def preview_references(value, rename=None):
    rename = rename or preview_name
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"secret", "configMap", "secretKeyRef", "configMapKeyRef", "secretRef", "configMapRef"}:
                for name_key in ("name", "secretName"):
                    if name_key in item:
                        item[name_key] = rename(item[name_key])
            else:
                preview_references(item, rename)
    elif isinstance(value, list):
        for item in value:
            preview_references(item, rename)


def pod_spec(template):
    spec = copy.deepcopy(template["spec"]["template"]["spec"])
    for key in ("priorityClassName", "tolerations", "serviceAccountName"):
        spec.pop(key, None)
    if PREVIEW_PRIORITY:
        spec["priorityClassName"] = PREVIEW_PRIORITY
    spec["automountServiceAccountToken"] = False
    spec["terminationGracePeriodSeconds"] = min(spec.get("terminationGracePeriodSeconds", 30), PREVIEW_GRACE_SECONDS)
    spec.setdefault("securityContext", {}).setdefault("fsGroup", 0)
    # No production PVC, hostPath or writable state is ever shared.
    for volume in spec.get("volumes", []):
        if "persistentVolumeClaim" in volume or "hostPath" in volume:
            volume.pop("persistentVolumeClaim", None)
            volume.pop("hostPath", None)
            volume["emptyDir"] = {"sizeLimit": "8Gi"}
    preview_references(spec)
    return spec


GOLDEN_COPY_SCRIPT = ("set -eu\n"
                      "test ! -e " + GOLDEN_PGDATA + "/PG_VERSION\n"
                      "exec timeout " + str(GOLDEN_COPY_SECONDS) + " pg_basebackup --host=\"$GOLDEN_HOST\" --port=5432"
                      " --username=" + GOLDEN_REPL_USER + " --no-password --pgdata=" + GOLDEN_PGDATA
                      + " --wal-method=stream --checkpoint=fast --no-sync --no-manifest\n")


def golden_copy_container(container, host):
    """Init container: a physical copy of the golden cluster into this pod's own data volume. No source credential."""
    data = next(m for m in container["volumeMounts"] if m["name"] == "data")
    return dict(name="golden-copy", image=container["image"], command=["bash", "-c", GOLDEN_COPY_SCRIPT],
                env=[dict(name="GOLDEN_HOST", value=str(ipaddress.ip_address(host))),
                     dict(name="PGCONNECT_TIMEOUT", value="10"),
                     dict(name="PGPASSWORD", valueFrom=dict(secretKeyRef=dict(
                         name=preview_name("ui2-golden-copy"), key="repl-password")))],
                securityContext=copy.deepcopy(container["securityContext"]),
                resources=copy.deepcopy(container["resources"]),
                volumeMounts=[dict(name="data", mountPath=data["mountPath"])])


def namespace_limits():
    """The run namespace's ResourceQuota and LimitRange: created before any pod, removed with the namespace."""
    return [dict(apiVersion="v1", kind="LimitRange", metadata=metadata("ui2-preview-limits"),
                 spec=dict(limits=[copy.deepcopy(PREVIEW_LIMIT_RANGE)])),
            dict(apiVersion="v1", kind="ResourceQuota", metadata=metadata("ui2-preview-quota"),
                 spec=dict(hard=dict(PREVIEW_QUOTA)))]


def database_manifest(template, golden=None):
    spec = pod_spec(template)
    if golden is None:
        spec["containers"][0].setdefault("env", []).extend([
            dict(name=name, valueFrom=dict(secretKeyRef=dict(
                name=preview_name("ui2-db-source"), key=key)))
            for name, key in (("SOURCE_DB_USER", "migrate-user"), ("SOURCE_DB_PASSWORD", "migrate-password"))
        ])
    else:
        spec["initContainers"] = [golden_copy_container(spec["containers"][0], golden["ip"])]
    size = os.environ.get("NEXUS_PREVIEW_DB_DATA_SIZE_LIMIT", "32Gi")
    if not re.fullmatch(r"[1-9][0-9]*Gi", size):
        raise ValueError("Preview database size limit must be positive whole Gi")
    for volume in spec.get("volumes", []):
        if volume["name"] == "data" and "emptyDir" in volume:
            volume["emptyDir"]["sizeLimit"] = size
    spec["restartPolicy"] = "Never"
    spec["activeDeadlineSeconds"] = 3600
    return dict(apiVersion="v1", kind="Pod", metadata=metadata("ui2-preview-db", {
        "app.kubernetes.io/component": "database"}), spec=spec)


def workload_manifest(template, image, db_ip, endpoints):
    result = copy.deepcopy(template)
    labels = {**template["spec"]["template"]["metadata"]["labels"], LABEL: NS}
    result["metadata"] = metadata(template["metadata"]["name"], labels)
    result["spec"]["replicas"] = 1
    result["spec"]["selector"] = dict(matchLabels=labels)
    result["spec"]["template"] = dict(metadata=dict(labels=labels), spec=pod_spec(template))
    for container in result["spec"]["template"]["spec"]["containers"]:
        container["image"] = image
        replacements = {"UI2_DB_HOST": db_ip, "UI2_DB_PORT": "5432", "UI2_DB_NAME": "ui2",
                        "UI2_DB_URL": f"jdbc:postgresql://{db_ip}:5432/ui2",
                        "UI2_SCHEDULING_ENABLED": "false", "UI2_SESSION_COOKIE_SECURE": "false",
                        "UI2_JOB_WINDOW_MINUTES": "360",
                        "NEXUS_FAILOVER_MUTATION_ENABLED": "false",
                        "LOGGING_LEVEL_ROOT": "ERROR",
                        # PO 2026-11-15: live logs show addresses; the preview keeps the masked default.
                        "NEXUS_LOG_RAW_ADDRESSES": "0"}
        env = []
        for entry in container.get("env", []):
            name = entry["name"]
            if name in replacements or name in {"UI2_CP_BACKUP_CREDENTIAL_REF", "UI2_CC_RECEIVER_HOST",
                                                "PAN_DISCOVERY_TRUST_PINNED_FINGERPRINT_SHA256"}:
                continue
            if name.endswith("_SERVICE_URL"):
                # Match the production Service name; DNS/network access outside preview stays denied.
                source = entry.get("value", "")
                target = next((url for service, url in endpoints.items()
                               if re.match(r"https?://" + re.escape(service) + r"(?:[.:/]|$)", source)), None)
                if target is None:
                    raise RuntimeError("Preview worker endpoint missing")
                entry = dict(name=name, value=target)
            env.append(entry)
        container["env"] = env + [dict(name=name, value=value) for name, value in replacements.items()]
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
    result["spec"]["template"]["metadata"]["labels"][LABEL] = NS
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


# Kaniko layer cache for the preview builds (default on; NEXUS_PREVIEW_KANIKO_CACHE=0 restores --cache=false).
# Why this cannot mix contexts: kaniko keys every cached layer by a composite of the base image digest, all
# preceding layers' keys and the instruction itself. A RUN layer's key adds the command text and the build
# args/env in scope; a COPY/ADD layer's key adds a hash of the copied files' content and metadata. A layer is
# therefore reused only when the same instruction runs on a byte-identical chain from the same pinned base; any
# changed file in a COPY changes that layer's key and every key after it. The cache repository is in the same
# cluster-local registry, behind the same access control, as the images themselves.
# The 12 h TTL bounds how old a reused layer may be. It matters for the runtime OS package update step
# (PO_DECISION_RECORD_2026_11_18_RUNTIME_PACKAGE_UPDATE.md): that RUN step's key does not change when Red Hat
# publishes a fix, so it is re-run at least every 12 h; the security gate scans every deployed image regardless.
KANIKO_CACHE_REPO = REGISTRY + "nexus-ui2-build-cache"
KANIKO_CACHE_TTL = "12h"


def kaniko_cache_args(cache=True):
    if not cache or os.environ.get("NEXUS_PREVIEW_KANIKO_CACHE", "1").strip() == "0":
        return ["--cache=false"]
    return ["--cache=true", "--cache-repo=" + KANIKO_CACHE_REPO, "--cache-ttl=" + KANIKO_CACHE_TTL]


def build_manifest(template, loader, name, commit, owner, dockerfile, destination, cache=True):
    job = copy.deepcopy(template)
    job["metadata"] = dict(name=name, namespace=BUILD_NS, labels={LABEL: owner},
                           annotations={"nexus/commit": commit})
    job["spec"]["backoffLimit"] = 0
    job["spec"]["activeDeadlineSeconds"] = 1800
    # Preserve the host's complete builder setup; isolate only the context volume.
    spec = job["spec"]["template"]["spec"]
    spec["volumes"] = [v for v in spec["volumes"] if v["name"] != "context"] + [
        dict(name="context", emptyDir={"sizeLimit": "8Gi"})]
    spec["automountServiceAccountToken"] = False
    spec.setdefault("securityContext", {}).setdefault("fsGroup", 0)
    builder = spec["containers"][0]
    builder["args"] = [a for a in builder["args"] if not a.startswith(
        ("--context=", "--dockerfile=", "--destination=", "--digest-file=", "--cache=", "--cache-repo=",
         "--cache-ttl=", "--image-download-retry="))] + [
        "--context=dir:///workspace" + ("/ui2/frontend" if dockerfile.endswith("Dockerfile.e2e") else ""),
        "--dockerfile=/workspace/" + dockerfile, "--destination=" + destination,
        *kaniko_cache_args(cache), "--image-download-retry=5",
        "--digest-file=/dev/termination-log"]
    builder["terminationMessagePath"] = "/dev/termination-log"
    builder["terminationMessagePolicy"] = "File"
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"] + [
        dict(name="UI2_IMAGE_TAG", value=commit[:12])]
    init = copy.deepcopy(loader["spec"]["containers"][0])
    init["name"] = "context-loader"
    init["command"] = ["sh", "-c", "while [ ! -f /workspace/.ready ]; do sleep 1; done"]
    # Host-local templates may carry a different path or subPath. Both containers
    # must see the root of this pod's emptyDir, never the production context PVC.
    for container in (builder, init):
        mounts = [m for m in container.get("volumeMounts", []) if m["name"] != "context"]
        if any(m["mountPath"] == "/" or m["mountPath"].rstrip("/") == "/workspace"
               or m["mountPath"].startswith("/workspace/") for m in mounts):
            raise PreviewFailure("CONTEXT_INCOMPLETE")
        container["volumeMounts"] = mounts + [dict(name="context", mountPath="/workspace")]
    # Kaniko's established root build exception does not apply to the loader.
    init["securityContext"]["runAsNonRoot"] = True
    spec["initContainers"] = [init]
    job["spec"]["template"] = dict(metadata=dict(labels={LABEL: owner}), spec=spec)
    return job


def database_copy_cause(stderr):
    """Return only safe categories; never retain or report diagnostic values."""
    text = stderr.lower()
    if "invalid snapshot identifier" in text or "snapshot does not exist" in text:
        return "SNAPSHOT_LOST"
    if "statement timeout" in text:
        return "TIMEOUT"
    if any(marker in text for marker in ("copying stdin failed", "websocket", "connection reset",
                                         "connection refused", "server closed the connection",
                                         "could not connect", "connection timed out", "i/o timeout",
                                         "no route to host", "network is unreachable")):
        return "STREAM_BROKEN"
    if "timeout" in text or "timed out" in text:
        return "TIMEOUT"
    if "unexpected end of file" in text or "unexpected eof" in text:
        return "STREAM_BROKEN"
    if "broken pipe" in text:
        return "PIPE_BROKEN"
    return None


def check_preview_database():
    """Inspect pod health; only fixed cause tokens leave the status projection."""
    try:
        pod = json.loads(k(NS, "get", "pod", target_db_pod(),
                           "-o", "json", timeout=10))
    except PreviewFailure as error:
        if error.reason == "NOT_FOUND":
            raise PreviewFailure("DB_COPY_FAILED_PREVIEW_DB_EVICTED:NOT_FOUND") from None
        raise
    status = pod.get("status", {})
    ended = [c.get("state", {}).get("terminated")
             for c in status.get("containerStatuses", [])]
    if (status.get("phase") in {"Failed", "Succeeded"}
            or status.get("reason") == "Evicted"
            or any(ended) or pod.get("metadata", {}).get("deletionTimestamp")):
        reason = status.get("reason", "")
        if "ephemeral-storage" in status.get("message", "").lower():
            reason = "ephemeral-storage"
        else:
            reason = next((c.get("reason") for c in ended if c), reason)
            if reason not in {"Evicted", "OOMKilled", "Error", "Completed", "ContainerStatusUnknown"}:
                reason = "UNKNOWN"
        raise PreviewFailure("DB_COPY_FAILED_PREVIEW_DB_EVICTED:" + reason)


def pipe_commands(source, target, timeout=60):
    """Fail on either side, including a producer failing after a successful restore."""
    producer = subprocess.Popen(source, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    consumer = None
    try:
        consumer = subprocess.Popen(target, stdin=producer.stdout, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL)
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


def database_stream(source, target, timeout):
    """Run both ends inside the preview pod; exec carries only control/diagnostics."""
    _set_step("copy database")
    script = ("set +x\n(" + source + ") | (" + target + ") >/dev/null\n"
              'status=("${PIPESTATUS[@]}")\n'
              'printf "NEXUS_COPY_STATUS=%s,%s\\n" "${status[0]}" "${status[1]}" >&2\n'
              'test "${status[0]}" = 0 && test "${status[1]}" = 0')
    check_preview_database()
    process = subprocess.Popen(
        ["kubectl", "-n", NS, "exec", target_db_pod(), "--", "bash", "-c", script],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + timeout
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise PreviewFailure("DB_COPY_FAILED_TIMEOUT", "TimeoutExpired")
            try:
                _, stderr = process.communicate(timeout=min(2, remaining))
                break
            except subprocess.TimeoutExpired:
                check_preview_database()
        check_preview_database()
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stderr.close()
    status = re.search(r"NEXUS_COPY_STATUS=(\d+),(\d+)\s*$", stderr)
    if process.returncode or not status or status.groups() != ("0", "0"):
        cause = database_copy_cause(stderr)
        if not status:
            cause = cause or "STREAM_BROKEN"
        elif status[2] != "0" and cause not in ("SNAPSHOT_LOST", "TIMEOUT", "STREAM_BROKEN"):
            cause = "RESTORE_ERROR"
        raise PreviewFailure("DB_COPY_FAILED" + ("_" + cause if cause else ""))


def build_failure_detail(namespace, name):
    """Safe cause of a failed build Job: container name, termination reason and exit code only (never log text)."""
    try:
        pods = json.loads(k(namespace, "get", "pods", "-l", "job-name=" + name, "-o", "json"))["items"]
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        return "UNKNOWN"
    found = []
    for pod in pods:
        status = pod.get("status", {})
        for entry in status.get("initContainerStatuses", []) + status.get("containerStatuses", []):
            ended = entry.get("state", {}).get("terminated") or {}
            if ended and ended.get("exitCode") != 0:
                reason = re.sub(r"[^A-Za-z0-9]", "", str(ended.get("reason", "")))[:24] or "Error"
                found.append(f"{re.sub(r'[^a-z0-9-]', '', str(entry.get('name', '')))[:24]}-{reason}-{ended.get('exitCode')}")
    return ",".join(sorted(set(found))) or "UNKNOWN"


def build_one_image(template, loader, repo, commit, owner, role, dockerfile):
    """One image build. With the kaniko layer cache on, a failed build is retried once with the cache off.

    The cache and the parallel builds are untestable off-host. A retry that runs after the other role's Job has
    finished (run_concurrently waits for both) and without cache state separates "the build itself is broken" from
    "the cache path broke it": only a build that also fails cache-free fails the phase. The cause of the first
    failure is printed as a safe token (container, reason, exit code)."""
    cached = kaniko_cache_args()[0] == "--cache=true"
    try:
        return _build_one_image(template, loader, repo, commit, owner, role, dockerfile, cache=cached)
    except PreviewFailure as error:
        if not cached or error.reason != "IMAGE_BUILD_FAILED":
            raise
        print(f"BUILD RETRY role={role} first={error.exception_class}; retrying without layer cache", flush=True)
        return _build_one_image(template, loader, repo, commit, owner, role, dockerfile, cache=False, suffix="-nc")


def _build_one_image(template, loader, repo, commit, owner, role, dockerfile, cache=True, suffix=""):
    """One kaniko Job: create it, stream the context into its own loader, wait, return image@digest."""
    with timing("preview_build_" + role):
        name = "ui2-preview-build-" + role + "-" + owner + suffix
        image = REGISTRY + "nexus-ui2-" + role
        create(BUILD_NS, build_manifest(template, loader, name, commit, owner, dockerfile,
                                       image + ":preview-" + commit[:12], cache=cache))
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
                       "sh", "-c", LOAD_CONTEXT], timeout=120)
        try:
            job = wait_job(BUILD_NS, name)
        except RuntimeError as error:
            if isinstance(error, PreviewFailure):
                raise
            raise PreviewFailure("IMAGE_BUILD_FAILED", build_failure_detail(BUILD_NS, name)) from None
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
        return image + "@" + digests.pop()


IMAGE_ROLES = (("service", "ui2/Containerfile"), ("e2e", "ui2/frontend/Dockerfile.e2e"))


def build_images(repo, commit, owner):
    """Build the service and e2e runner images at the same time; return [service, e2e].

    Measured 2026-11-22: service 182 s then e2e 94 s, one after the other. The two Jobs share nothing but the
    read-only template, so they run side by side and the phase takes about as long as the service build. Each Job
    keeps its own context-loader wait and tar stream. run_concurrently waits for both and re-raises the first
    failure in role order, so any failed build fails the whole phase, as before."""
    prepare_build_context(repo, commit)
    template = json.loads(k(None, "create", "--dry-run=client", "-f",
                            str(Path.home() / "build-job-proxy.yaml"), "-o", "json"))
    loader = next(o for o in load(repo, "deploy/ui2-image-build/20-context-loader.yaml")["items"]
                  if o["kind"] == "Pod")
    return run_concurrently(*(
        (lambda role=role, dockerfile=dockerfile:
         build_one_image(template, loader, repo, commit, owner, role, dockerfile))
        for role, dockerfile in IMAGE_ROLES))


@contextmanager
def exported_snapshot(source):
    # Hold one read-only snapshot so filtered rows cannot refer to artefacts created after the dump.
    holder = subprocess.Popen(source[:4] + ["-i"] + source[4:] + [PG_ENV
        + 'export PGOPTIONS="-c default_transaction_read_only=on -c idle_in_transaction_session_timeout='
        + str((12 * database_statement_timeout() + 60) * 1000) + '"; '
        + 'exec psql -XAtq --set=ON_ERROR_STOP=1'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        holder.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n")
        holder.stdin.flush()
        if not select.select([holder.stdout], [], [], 15)[0]:
            raise PreviewFailure("DB_COPY_FAILED_SNAPSHOT_LOST")
        token = holder.stdout.readline().strip()
        if not re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", token):
            raise PreviewFailure("DB_COPY_FAILED_SNAPSHOT_LOST")
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


def database_statement_timeout():
    """Seconds per statement; allow four per stream and three streams per snapshot."""
    seconds = int(os.environ.get("NEXUS_PREVIEW_DB_STATEMENT_TIMEOUT_SECONDS", "900"))
    if not 0 < seconds <= (2147483647 - 60000) // 12000:
        raise ValueError("Preview database timeout outside supported range")
    return seconds


def selector_matches(selector, labels):
    if any(labels.get(key) != value for key, value in selector.get("matchLabels", {}).items()):
        return False
    for expr in selector.get("matchExpressions", []):
        key, op, values = expr["key"], expr["operator"], expr.get("values", [])
        if not {"In": key in labels and labels[key] in values,
                "NotIn": labels.get(key) not in values,
                "Exists": key in labels, "DoesNotExist": key not in labels}.get(op, False):
            return False
    return True


def database_ingress_allowed(policies, source, target, namespace_labels):
    """Evaluate additive ingress policies for this preview pod and PostgreSQL port."""
    selected = [p["spec"] for p in policies if "Ingress" in p["spec"].get("policyTypes", ["Ingress"])
                and selector_matches(p["spec"]["podSelector"], source["metadata"]["labels"])]
    named_ports = {p["name"] for c in source["spec"]["containers"] for p in c.get("ports", [])
                   if p.get("containerPort") == 5432 and p.get("protocol", "TCP") == "TCP" and "name" in p}
    for policy_spec in selected:
        for rule in policy_spec.get("ingress", []):
            if rule.get("ports") and not any(
                p.get("protocol", "TCP") == "TCP" and
                ("port" not in p or p["port"] in named_ports or
                 isinstance(p["port"], int) and p["port"] <= 5432 <= p.get("endPort", p["port"]))
                for p in rule["ports"]):
                continue
            if not rule.get("from"):
                return True
            for peer_spec in rule["from"]:
                if "ipBlock" in peer_spec:
                    block, address = peer_spec["ipBlock"], ipaddress.ip_address(target["status"]["podIP"])
                    if address in ipaddress.ip_network(block["cidr"]) and not any(
                            address in ipaddress.ip_network(cidr) for cidr in block.get("except", [])):
                        return True
                elif ("namespaceSelector" in peer_spec and
                      selector_matches(peer_spec["namespaceSelector"], namespace_labels) and
                      selector_matches(peer_spec.get("podSelector", {}), target["metadata"]["labels"])):
                    return True
                elif not peer_spec:
                    return True
    return not selected


@contextmanager
def database_network_access():
    source = json.loads(k("ui2", "get", "pod", "ui2-db-0", "-o", "json"))
    target = json.loads(k(NS, "get", "pod", target_db_pod(), "-o", "json"))
    namespace = json.loads(k(NS, "get", "namespace", NS, "-o", "json"))
    existing = json.loads(k("ui2", "get", "networkpolicy", "-o", "json"))["items"]
    db_selector = dict(matchLabels={"statefulset.kubernetes.io/pod-name": "ui2-db-0"})
    ports = [dict(protocol="TCP", port=5432)]
    # No DNS or general cross-namespace egress: connect to the service IP directly.
    egress = dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata("db-copy"),
                  spec=dict(podSelector=dict(matchLabels={LABEL: NS, "app.kubernetes.io/component": "database"}),
                            policyTypes=["Egress"], egress=[dict(to=[dict(
                                namespaceSelector=dict(matchLabels={"kubernetes.io/metadata.name": "ui2"}),
                                podSelector=db_selector)], ports=ports)]))
    try:
        create(NS, egress)
        if not database_ingress_allowed(existing, source, target, namespace["metadata"]["labels"]):
            create("ui2", dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata("db-copy"),
                               spec=dict(podSelector=db_selector, policyTypes=["Ingress"], ingress=[{
                                   "from": [dict(namespaceSelector=dict(matchLabels={
                                       LABEL: namespace["metadata"]["labels"][LABEL],
                                       "kubernetes.io/metadata.name": NS}),
                                       podSelector=egress["spec"]["podSelector"])], "ports": ports}])))
        yield
    finally:
        # The outer teardown retries source-policy removal if either call fails.
        for namespace_name in ("ui2", NS):
            k(namespace_name, "delete", "networkpolicy", preview_name("db-copy"),
              "--ignore-not-found", "--wait=true", "--timeout=120s", timeout=130)


def copy_database():
    source = ["kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c"]
    seconds = database_statement_timeout()
    service = json.loads(k("ui2", "get", "service", "ui2-db", "-o", "json"))
    address = str(ipaddress.ip_address(service["spec"]["clusterIP"]))
    read_only = ('export PGDATABASE="${POSTGRESQL_DATABASE:-${POSTGRES_DB:-ui2}}"; '
                 'export PGHOST=' + shlex.quote(address) + '; '
                 'export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout='
                 + str(seconds * 1000) + '"; ')
    source_auth = 'env PGUSER="$SOURCE_DB_USER" PGPASSWORD="$SOURCE_DB_PASSWORD" '
    # Local socket authentication keeps source credentials out of preview restores.
    restore_options = PG_ENV + 'export PGOPTIONS="-c statement_timeout=' + str(seconds * 1000) + '"; '
    with database_network_access(), exported_snapshot(source) as snapshot:
        database_stream(read_only + "exec " + source_auth + "pg_dump --format=custom --compress=0 --snapshot=" + snapshot
                        + " --exclude-table-data=public.audit_log --exclude-table-data=public.backup_artefact_entry"
                        + " --exclude-table-data=public.cp_policy_json_chunk",
                        restore_options + "exec pg_restore --exit-on-error --no-owner --dbname=ui2",
                        timeout=4 * seconds)
        for table, query in (("audit_log", AUDIT_SELECT), ("backup_artefact_entry", ENTRY_SELECT),
                             ("cp_policy_json_chunk", CHUNK_SELECT)):
            sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '" + snapshot + "'; COPY (" + query + ") TO STDOUT; COMMIT"
            database_stream(read_only + 'exec ' + source_auth + 'psql -Xq --set=ON_ERROR_STOP=1 -c ' + shlex.quote(sql),
                            restore_options + 'exec psql -Xq --set=ON_ERROR_STOP=1 -c "COPY public.' + table + ' FROM STDIN"',
                            timeout=4 * seconds)


def copy_secret(name, target_name=None, keys=None):
    secret = json.loads(k("ui2", "get", "secret", name, "-o", "json"))
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata(target_name or name),
                    type=secret.get("type", "Opaque"),
                    data={key: secret["data"][key] for key in keys} if keys else secret["data"]))


def database_template(repo):
    """The pinned PostgreSQL template; refused when production runs a different image."""
    objects = load(repo, "deploy/ui2/40-database-statefulset.yaml")["items"]
    template = next(o for o in objects if o["kind"] == "StatefulSet")
    # Kubernetes API only (the pod spec); never a database connection.
    live = json.loads(k("ui2", "get", "pod", "ui2-db-0", "-o", "json"))
    pinned = template["spec"]["template"]["spec"]["containers"][0]["image"]
    if live["spec"]["containers"][0]["image"] != pinned:
        raise RuntimeError("Production PostgreSQL differs from the pinned bootstrap; preview refused")
    return objects, template, pinned


def database_configs(repo, objects):
    config = next(o for o in load(repo, "deploy/ui2/10-configmap.yaml")["items"]
                  if o["kind"] == "ConfigMap")
    create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata("ui2-config"),
                    data={key: config["data"][key] for key in ("db_name", "create-app-role.sh")}))
    for obj in objects:
        if obj["kind"] == "ConfigMap":
            create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata(obj["metadata"]["name"]),
                            data=obj["data"]))


def random_db_secret(name, extra=()):
    values = {"migrate-user": "ui2_migrate", "app-user": "ui2_app",
              "migrate-password": secrets.token_urlsafe(32), "app-password": secrets.token_urlsafe(32)}
    values.update({key: secrets.token_urlsafe(32) for key in extra})
    return dict(apiVersion="v1", kind="Secret", metadata=metadata(name), type="Opaque",
                data={key: base64.b64encode(value.encode()).decode() for key, value in values.items()})


def setup_database(repo):
    objects, template, pinned = database_template(repo)
    source, reason, golden = select_database_source(repo, pinned)
    print(f"PREVIEW DB SOURCE: {source} reason={reason}", flush=True)
    database_configs(repo, objects)
    create(NS, random_db_secret("ui2-db"))
    if golden is not None:
        try:
            with timing("preview_db_golden"):
                golden_database(template, golden)
            return json.loads(k(NS, "get", "pod", preview_name("ui2-preview-db"), "-o", "json"))["status"]["podIP"]
        except PreviewFailure:
            if INTERRUPTED:
                raise
            # A golden problem never blocks a ship: discard the partial pod and take today's live copy.
            print("PREVIEW DB SOURCE: live reason=GOLDEN_COPY_FAILED", flush=True)
            discard_golden_attempt()
    copy_secret("ui2-db", "ui2-db-source", ("migrate-user", "migrate-password"))
    create(NS, database_manifest(template))
    k(NS, "wait", "--for=condition=Ready", "pod/" + preview_name("ui2-preview-db"), "--timeout=120s", timeout=130)
    with timing("preview_db_copy"):
        copy_database()
    return json.loads(k(NS, "get", "pod", preview_name("ui2-preview-db"), "-o", "json"))["status"]["podIP"]


# ------------------------------------------------------------------------------------------ golden preview database

def golden_pod(slot):
    return "golden-" + slot + "-0"


def candidate_migrations(repo):
    """Flyway versions the candidate ships (V<version>__*.sql; Flyway reads '_' in a version as '.')."""
    versions = set()
    for path in (repo / MIGRATIONS).glob("V*__*.sql"):
        match = re.fullmatch(r"V([0-9]+(?:[._][0-9]+)*)__.+\.sql", path.name)
        if not match:
            raise ValueError("Unrecognised migration file name")
        versions.add(match.group(1).replace("_", "."))
    if not versions:
        raise ValueError("Candidate migrations missing")
    return versions


def parse_golden_marker(data):
    """The marker's fields, or None when any is missing or malformed."""
    try:
        slot, created, image = data["slot"], int(data["created"]), data["image"]
        versions = json.loads(data["migrations"])
    except (KeyError, TypeError, ValueError):
        return None
    if (slot not in GOLDEN_SLOTS or not isinstance(image, str) or not image
            or not isinstance(versions, list) or not versions
            or not all(isinstance(v, str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", v) for v in versions)):
        return None
    return dict(slot=slot, created=created, image=image, migrations=set(versions))


def read_golden_marker():
    """(marker, None) or (None, reason) with reason GOLDEN_MISSING or GOLDEN_INVALID."""
    try:
        raw = k(GOLDEN_NS, "get", "configmap", GOLDEN_MARKER, "--ignore-not-found", "-o", "json", timeout=30)
    except PreviewFailure:
        return None, "GOLDEN_MISSING"
    if not raw.strip():
        return None, "GOLDEN_MISSING"
    try:
        data = json.loads(raw).get("data")
    except (ValueError, AttributeError):
        return None, "GOLDEN_INVALID"
    if not isinstance(data, dict):
        return None, "GOLDEN_MISSING"
    marker = parse_golden_marker(data)
    return (marker, None) if marker else (None, "GOLDEN_INVALID")


def golden_server(slot, pinned):
    """The serving golden pod's address, or None unless it is Ready on the pinned image with the slot's labels."""
    try:
        pod = json.loads(k(GOLDEN_NS, "get", "pod", golden_pod(slot), "--ignore-not-found", "-o", "json",
                           timeout=30) or "null")
    except (PreviewFailure, ValueError):
        return None
    if not isinstance(pod, dict):
        return None
    labels = pod.get("metadata", {}).get("labels", {})
    ready = any(c.get("type") == "Ready" and c.get("status") == "True"
                for c in pod.get("status", {}).get("conditions", []))
    images = [c.get("image") for c in pod.get("spec", {}).get("containers", [])]
    if (labels.get(GOLDEN_LABEL) != slot or not ready or images[:1] != [pinned]
            or pod["metadata"].get("deletionTimestamp")):
        return None
    try:
        return str(ipaddress.ip_address(pod["status"]["podIP"]))
    except (KeyError, ValueError):
        return None


def select_database_source(repo, pinned, now=None):
    """("golden" | "live", reason, golden). Golden only when the candidate would run no migration on it.

    The marker records every Flyway version applied in the golden copy. Equal sets mean the candidate's Flyway has
    nothing to apply; a version only the candidate has (a new migration, or one shipped after the refresh) takes a
    fresh live copy, as before. Deciding this needs no live-database read."""
    marker, reason = read_golden_marker()
    if marker is None:
        return "live", reason, None
    age = (time.time() if now is None else now) - marker["created"]
    if not 0 <= age <= GOLDEN_MAX_AGE_SECONDS:
        return "live", "GOLDEN_STALE", None
    if marker["image"] != pinned:
        return "live", "GOLDEN_IMAGE_MISMATCH", None
    candidate = candidate_migrations(repo)
    if candidate - marker["migrations"]:
        return "live", "NEW_MIGRATION", None
    if marker["migrations"] - candidate:
        return "live", "GOLDEN_SCHEMA_AHEAD", None
    ip = golden_server(marker["slot"], pinned)
    if ip is None:
        return "live", "GOLDEN_UNAVAILABLE", None
    return "golden", "GOLDEN_CURRENT", dict(slot=marker["slot"], ip=ip)


def golden_copy_policy(slot):
    """Temporary egress from this preview's database pod to the serving golden pod only. No DNS, no other peer."""
    return dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata("golden-copy"),
                spec=dict(podSelector=dict(matchLabels={LABEL: NS, "app.kubernetes.io/component": "database"}),
                          policyTypes=["Egress"], egress=[dict(to=[dict(
                              namespaceSelector=dict(matchLabels={"kubernetes.io/metadata.name": GOLDEN_NS}),
                              podSelector=dict(matchLabels={GOLDEN_LABEL: slot}))],
                              ports=[dict(protocol="TCP", port=5432)])]))


# Runs as the local superuser on the preview's own socket. Values come from the pod environment (this run's random
# Secret), never from argv; the golden replication role does not survive into the preview.
GOLDEN_ROLE_RESET_SQL = r"""\getenv mu POSTGRESQL_USER
\getenv mp POSTGRESQL_PASSWORD
\getenv au UI2_DB_APP_USER
\getenv ap UI2_DB_APP_PASSWORD
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'mu', :'mp') \gexec
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'au', :'ap') \gexec
DROP ROLE IF EXISTS """ + GOLDEN_REPL_USER + ";\n"
SUPERUSER_ENV = "unset PGPASSWORD; export PGUSER=postgres; "


def golden_database(template, golden):
    """The preview database from the golden copy: pg_basebackup in an init container, then this run's passwords."""
    secret = json.loads(k(GOLDEN_NS, "get", "secret", "golden-" + golden["slot"], "-o", "json", timeout=30))
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata("ui2-golden-copy"), type="Opaque",
                    data={"repl-password": secret["data"]["repl-password"]}))
    try:
        create(NS, golden_copy_policy(golden["slot"]))
        create(NS, database_manifest(template, golden))
        k(NS, "wait", "--for=condition=Ready", "pod/" + preview_name("ui2-preview-db"),
          "--timeout=" + str(GOLDEN_READY_SECONDS) + "s", timeout=GOLDEN_READY_SECONDS + 10)
        k(NS, "exec", "-i", preview_name("ui2-preview-db"), "--", "sh", "-c",
          SUPERUSER_ENV + "exec psql -Xq --set=ON_ERROR_STOP=1 -d postgres", input=GOLDEN_ROLE_RESET_SQL)
    finally:
        # The namespace teardown removes both anyway; the copy path closes as soon as it is no longer needed.
        k(NS, "delete", "networkpolicy", preview_name("golden-copy"), "--ignore-not-found", timeout=60)
        k(NS, "delete", "secret", preview_name("ui2-golden-copy"), "--ignore-not-found", timeout=60)


def discard_golden_attempt():
    for kind, name in (("pod", "ui2-preview-db"), ("networkpolicy", "golden-copy"), ("secret", "ui2-golden-copy")):
        k(NS, "delete", kind, preview_name(name), "--ignore-not-found", "--wait=true", "--timeout=120s", timeout=130)


def refresh_window_allowed(now):
    """False inside a collection window (from :20 of the hour before) or the 02:00 backup hour."""
    return now.hour not in GOLDEN_BLOCKED_HOURS and not (
        (now.hour + 1) % 24 in GOLDEN_BLOCKED_HOURS and now.minute >= 20)


@contextmanager
def golden_lock():
    import fcntl
    directory = Path.home() / ".local/state/nexus-preview"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory / "golden.lock").open("a+") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PreviewFailure("GOLDEN_REFRESH_RUNNING") from None
        yield


def golden_policies():
    """Deny everything in the golden namespace except PostgreSQL from preview database pods."""
    deny = dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata("deny-all"),
                spec=dict(podSelector={}, policyTypes=["Ingress", "Egress"], ingress=[], egress=[]))
    serve = dict(apiVersion="networking.k8s.io/v1", kind="NetworkPolicy", metadata=metadata("serve-previews"),
                 spec=dict(podSelector=dict(matchLabels={LABEL: GOLDEN_NS, "app.kubernetes.io/component": "database"}),
                           policyTypes=["Ingress"], ingress=[{"from": [dict(
                               namespaceSelector=dict(matchExpressions=[
                                   dict(key=LABEL, operator="Exists"),
                                   dict(key=GOLDEN_LABEL, operator="DoesNotExist")]),
                               podSelector=dict(matchLabels={"app.kubernetes.io/component": "database"},
                                                matchExpressions=[dict(key=LABEL, operator="Exists")]))],
                               "ports": [dict(protocol="TCP", port=5432)]}]))
    return [deny, serve]


def ensure_golden_namespace():
    namespace = json.loads(k(GOLDEN_NS, "get", "namespace", GOLDEN_NS, "--ignore-not-found", "-o", "json") or "{}")
    if namespace and namespace.get("metadata", {}).get("labels", {}).get(GOLDEN_LABEL) != "true":
        raise RuntimeError("Golden namespace ownership differs; refresh refused")
    if not namespace:
        create(GOLDEN_NS, dict(apiVersion="v1", kind="Namespace", metadata=dict(
            name=GOLDEN_NS, labels={LABEL: "golden", GOLDEN_LABEL: "true"})))
    for item in golden_policies():
        create(GOLDEN_NS, item)


def golden_manifests(template, slot, size):
    """PVC + single-replica StatefulSet of the pinned PostgreSQL for one slot. Called with NS == GOLDEN_NS."""
    owner = "golden-" + slot
    spec = copy.deepcopy(template["spec"]["template"]["spec"])
    for key in ("priorityClassName", "tolerations", "serviceAccountName"):
        spec.pop(key, None)
    spec["automountServiceAccountToken"] = False
    spec["terminationGracePeriodSeconds"] = min(spec.get("terminationGracePeriodSeconds", 30), PREVIEW_GRACE_SECONDS)
    spec.setdefault("securityContext", {}).setdefault("fsGroup", 0)
    for volume in spec.get("volumes", []):
        if "persistentVolumeClaim" in volume or "hostPath" in volume:
            volume.pop("persistentVolumeClaim", None)
            volume.pop("hostPath", None)
            if volume["name"] == "data":
                volume["persistentVolumeClaim"] = dict(claimName=owner + "-data")
            else:
                volume["emptyDir"] = {"sizeLimit": "8Gi"}
    preview_references(spec, lambda name: owner if name == "ui2-db" else preview_name(name))
    spec["containers"][0].setdefault("env", []).extend(
        [dict(name=name, valueFrom=dict(secretKeyRef=dict(name=preview_name("ui2-db-source"), key=key,
                                                          optional=True)))
         for name, key in (("SOURCE_DB_USER", "migrate-user"), ("SOURCE_DB_PASSWORD", "migrate-password"))]
        + [dict(name="GOLDEN_REPL_PASSWORD", valueFrom=dict(secretKeyRef=dict(name=owner, key="repl-password")))])
    labels = {LABEL: GOLDEN_NS, GOLDEN_LABEL: slot, "app.kubernetes.io/component": "database"}
    claim = dict(apiVersion="v1", kind="PersistentVolumeClaim",
                 metadata=dict(name=owner + "-data", namespace=GOLDEN_NS, labels={GOLDEN_LABEL: slot}),
                 spec=dict(accessModes=["ReadWriteOnce"], resources=dict(requests=dict(storage=size))))
    workload = dict(apiVersion="apps/v1", kind="StatefulSet",
                    metadata=dict(name=owner, namespace=GOLDEN_NS, labels=labels),
                    spec=dict(serviceName=owner, replicas=1,
                              selector=dict(matchLabels={LABEL: GOLDEN_NS, GOLDEN_LABEL: slot}),
                              template=dict(metadata=dict(labels=labels), spec=spec)))
    return claim, workload


def retire_slot(slot):
    name = "golden-" + slot
    for kind, item in (("statefulset", name), ("pvc", name + "-data"), ("secret", name)):
        k(GOLDEN_NS, "delete", kind, item, "--ignore-not-found", "--cascade=foreground", "--wait=true",
          "--timeout=300s", timeout=310)


def golden_versions():
    """Every applied Flyway version of the staged copy; refused when a failed migration row exists."""
    rows = k(GOLDEN_NS, "exec", "-i", target_db_pod(), "--", "sh", "-c",
             PG_ENV + "exec psql -XAtq --set=ON_ERROR_STOP=1",
             input="SELECT count(*) FROM flyway_schema_history WHERE NOT success;\n"
                   "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                   "ORDER BY installed_rank;\n").split()
    if not rows or rows[0] != "0":
        raise PreviewFailure("GOLDEN_SCHEMA_FAILED_MIGRATION")
    versions = rows[1:]
    if not versions or not all(re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", v) for v in versions):
        raise PreviewFailure("GOLDEN_SCHEMA_UNREADABLE")
    return versions


# "md5" accepts both md5 and SCRAM stored passwords. Only preview database pods reach the port (golden_policies()).
GOLDEN_HBA_LINE = "host replication " + GOLDEN_REPL_USER + " all md5"
GOLDEN_REPLICATION_SQL = (r"\getenv rp GOLDEN_REPL_PASSWORD" + "\n"
                          "SELECT format('%s ROLE %I REPLICATION LOGIN PASSWORD %L', CASE WHEN EXISTS ("
                          "SELECT 1 FROM pg_roles WHERE rolname = '" + GOLDEN_REPL_USER + "') THEN 'ALTER' "
                          "ELSE 'CREATE' END, '" + GOLDEN_REPL_USER + "', :'rp') " + r"\gexec" + "\n"
                          "SELECT pg_reload_conf();\n")


def enable_golden_replication():
    """Open the staged copy to pg_basebackup through one replication role, then prove a replication login works."""
    hba = GOLDEN_PGDATA + "/pg_hba.conf"
    line = shlex.quote(GOLDEN_HBA_LINE)
    k(GOLDEN_NS, "exec", "-i", target_db_pod(), "--", "sh", "-c",
      "set -e; grep -qxF " + line + " " + hba + " || printf '%s\\n' " + line + " >> " + hba + "; "
      + SUPERUSER_ENV + "exec psql -Xq --set=ON_ERROR_STOP=1 -d postgres", input=GOLDEN_REPLICATION_SQL)
    probe = k(GOLDEN_NS, "exec", target_db_pod(), "--", "sh", "-c",
              'PGPASSWORD="$GOLDEN_REPL_PASSWORD" exec psql -XAtq "host=127.0.0.1 user=' + GOLDEN_REPL_USER
              + ' dbname=postgres replication=database" -c IDENTIFY_SYSTEM', timeout=60)
    if not probe.strip():
        raise PreviewFailure("GOLDEN_REPLICATION_UNAVAILABLE")


# Runs as the pod's local superuser over the socket (SUPERUSER_ENV), so ownership is never the limit: the copy is a
# pg_restore --no-owner by the app role, and a superuser may vacuum every table. --parallel=0 keeps VACUUM from
# starting parallel index workers, which need dynamic shared memory from a pod's default 64 MiB /dev/shm.
# First host run 2026-11-23 failed with the masked shape "could not <verb> <3 words> [..]", not a permission error:
# the masking keeps "permission denied" and "must be owner" readable and neither appeared. The exact message is
# UNKNOWN (masked); the shared-memory reading is the best fit, so the step is also made non-fatal (golden_vacuum).
GOLDEN_VACUUM_COMMAND = (SUPERUSER_ENV + 'exec vacuumdb --quiet --analyze --jobs=4 --parallel=0'
                         ' --dbname="${POSTGRESQL_DATABASE:-ui2}"')


def golden_vacuum():
    """VACUUM ANALYZE of the staged copy; an optimisation, never a gate.

    It only freshens planner statistics and hint bits of a read-only copy, so a failure of it (one table, or the
    whole run) leaves a valid database. Validity is proven by what follows and still aborts the promotion:
    golden_versions() reads the Flyway history through a live connection (refused on a failed migration row) and
    enable_golden_replication() proves a replication login. An interrupt is never swallowed."""
    try:
        k(NS, "exec", target_db_pod(), "--", "sh", "-c", GOLDEN_VACUUM_COMMAND, timeout=3600)
    except PreviewFailure as failure:
        if INTERRUPTED:
            raise
        print(f"PREVIEW GOLDEN: VACUUM_SKIPPED reason={failure.reason}", flush=True)


def refresh_golden(repo, now=None):
    """Stage a fresh copy of the live database in the idle slot, then promote it with one marker write.

    The serving slot stays untouched until the new one is complete; any failure removes only the staging slot."""
    global NS, DB_POD
    if not refresh_window_allowed(now or datetime.now(ZoneInfo("Europe/Istanbul"))):
        raise PreviewFailure("GOLDEN_REFRESH_WINDOW")
    size = os.environ.get("NEXUS_PREVIEW_GOLDEN_SIZE", "48Gi")
    if not re.fullmatch(r"[1-9][0-9]*Gi", size):
        raise ValueError("Golden volume size must be positive whole Gi")
    with golden_lock():
        previous = NS, DB_POD
        NS = GOLDEN_NS
        try:
            objects, template, pinned = database_template(repo)
            ensure_golden_namespace()
            marker, _ = read_golden_marker()
            active = marker["slot"] if marker else None
            staging = "b" if active == "a" else "a"
            DB_POD = golden_pod(staging)
            promoted = False
            try:
                retire_slot(staging)  # leftovers of an interrupted refresh
                database_configs(repo, objects)
                slot_secret = random_db_secret("ui2-db", extra=("repl-password",))
                slot_secret["metadata"] = dict(name="golden-" + staging, namespace=NS, labels={GOLDEN_LABEL: staging})
                create(NS, slot_secret)
                copy_secret("ui2-db", "ui2-db-source", ("migrate-user", "migrate-password"))
                for item in golden_manifests(template, staging, size):
                    create(NS, item)
                k(NS, "rollout", "status", "statefulset/golden-" + staging, "--timeout=300s", timeout=310)
                with timing("preview_golden_copy"):
                    copy_database()
                k(NS, "delete", "secret", preview_name("ui2-db-source"), "--ignore-not-found", timeout=60)
                with timing("preview_golden_vacuum"):
                    golden_vacuum()
                versions = golden_versions()
                enable_golden_replication()
                # The promotion: one ConfigMap write. Previews read only this object to choose a slot.
                create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=dict(
                    name=GOLDEN_MARKER, namespace=NS, labels={GOLDEN_LABEL: "marker"}), data=dict(
                    slot=staging, created=str(int(time.time())), image=pinned,
                    migrations=json.dumps(versions), flyway_version=versions[-1])))
                promoted = True
                print(f"PREVIEW GOLDEN: PROMOTED slot={staging} flyway_version={versions[-1]}", flush=True)
            finally:
                # Source credentials and the temporary live-database allowance never outlive the copy.
                k(NS, "delete", "secret", preview_name("ui2-db-source"), "--ignore-not-found", timeout=60)
                k("ui2", "delete", "networkpolicy", "-l", LABEL + "=" + GOLDEN_NS, "--ignore-not-found", timeout=60)
                if not promoted:
                    retire_slot(staging)
            for slot in (s for s in GOLDEN_SLOTS if s != staging):
                if slot == active:
                    time.sleep(GOLDEN_RETIRE_GRACE_SECONDS)
                retire_slot(slot)
        finally:
            NS, DB_POD = previous


def worker_templates(repo):
    """Discover N worker roles directly from the production deployment templates."""
    workers, objects = [], []
    directory = repo / "deploy/ui2"
    paths = sorted(set(directory.glob("*-deployment.yaml")) | set(directory.glob("*-service.yaml")))
    for path in paths:
        source = load(repo, str(path.relative_to(repo)))
        objects.extend(source["items"] if source["kind"] == "List" else [source])
    for deployment in (o for o in objects if o["kind"] == "Deployment"):
        containers = deployment["spec"]["template"]["spec"]["containers"]
        if not any(c.get("args", [])[:1] == ["worker"] for c in containers):
            continue
        component = deployment["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/component"]
        services = [o for o in objects if o["kind"] == "Service" and o["spec"].get("selector") and
                    all(deployment["spec"]["template"]["metadata"]["labels"].get(k) == v
                        for k, v in o["spec"]["selector"].items())]
        workers.append(dict(deployment=deployment, services=services, component=component,
                            ports=sorted({p["port"] for o in services for p in o["spec"]["ports"]})))
    if not workers:
        raise RuntimeError("No production worker templates found")
    return workers


def service_object(template):
    result = copy.deepcopy(template)
    result["metadata"] = metadata(template["metadata"]["name"], template["metadata"].get("labels"))
    result["spec"]["selector"][LABEL] = NS
    result["spec"]["type"] = "ClusterIP"
    for field in ("clusterIP", "clusterIPs", "healthCheckNodePort", "externalIPs", "loadBalancerIP"):
        result["spec"].pop(field, None)
    for port in result["spec"]["ports"]:
        port.pop("nodePort", None)
    return result


def setup_worker_services(workers):
    endpoints = {}
    for worker in workers:
        for template in worker["services"]:
            service = service_object(template)
            create(NS, service)
            ip = json.loads(k(NS, "get", "service", service["metadata"]["name"], "-o", "json"))["spec"]["clusterIP"]
            endpoints[template["metadata"]["name"]] = f"http://{ip}:" + str(service["spec"]["ports"][0]["port"])
    return endpoints


def setup_workload(template, image, db_ip, endpoints):
    source_spec = template["spec"]["template"]["spec"]
    names = {v["secret"]["secretName"] for v in source_spec.get("volumes", []) if "secret" in v}
    names.update(e["valueFrom"]["secretKeyRef"]["name"] for c in source_spec["containers"] for e in c.get("env", [])
                 if "secretKeyRef" in e.get("valueFrom", {}))
    required = {v["secret"]["secretName"] for v in source_spec.get("volumes", []) if "secret" in v and not v["secret"].get("optional")}
    required.update(e["valueFrom"]["secretKeyRef"]["name"] for c in source_spec["containers"] for e in c.get("env", [])
                    if "secretKeyRef" in e.get("valueFrom", {}) and not e["valueFrom"]["secretKeyRef"].get("optional"))
    deployment = workload_manifest(template, image, db_ip, endpoints)
    for name in sorted(names - {"ui2-db"}):
        # A secret that every reference marks optional may legitimately not exist yet (the advisory fetch token is
        # created by the host setup after the first ship); the pod starts without it, so the preview must too.
        if name not in required and not k("ui2", "get", "secret", name, "--ignore-not-found", "-o", "name").strip():
            continue
        copy_secret(name)
    for volume in source_spec.get("volumes", []):
        if "configMap" in volume and volume["configMap"]["name"] != "ui2-config":
            name = volume["configMap"]["name"]
            config = json.loads(k("ui2", "get", "configmap", name, "--ignore-not-found", "-o", "json") or "null")
            if config is None and name == "corp-ca":
                config = json.loads(k("ui2-build", "get", "configmap", name, "-o", "json"))
            if config is None:
                raise RuntimeError("Preview trust ConfigMap unavailable")
            create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata(name), data=config.get("data", {})))
    create(NS, deployment)
    k(NS, "rollout", "status", "deployment/" + deployment["metadata"]["name"], "--timeout=300s", timeout=310)
    return deployment


def setup_service(repo, image, db_ip, endpoints):
    template = next(o for o in load(repo, "deploy/ui2/50-service-deployment.yaml")["items"]
                    if o["kind"] == "Deployment")
    setup_workload(template, image, db_ip, endpoints)
    templates = load(repo, "deploy/ui2/56-service-internal.yaml")["items"]
    service = service_object(next(t for t in templates if t["kind"] == "Service"))
    create(NS, service)
    return json.loads(k(NS, "get", "service", service["metadata"]["name"], "-o", "json"))["spec"]["clusterIP"]


def reset_preview_runtime():
    # Runs on the preview copy only. Copied REQUESTED jobs are production's pending work: preview
    # device workers would otherwise claim them, fill the 10-job claim capacity and make the
    # synthetic claim assertion return no row (PREVIEW_CLAIM_DENIED). The claim lock serializes
    # this with any worker claim already running, so no copied job slips into CLAIMED meanwhile.
    sql = """BEGIN;
SELECT set_config('app.actor_fingerprint','system:preview',true);
SELECT set_config('app.action_id','preview_runtime_reset',true);
SELECT pg_advisory_xact_lock(294611);
UPDATE jobs SET state='CANCELLED',outcome='CANCELLED' WHERE state='REQUESTED';
DO $$ BEGIN
 IF to_regclass('module_runtime_control') IS NOT NULL THEN
  -- Self-owned modules (V154 packet_analysis) are never handed to the general worker.
  UPDATE module_runtime_control SET effective_owner=CASE WHEN module='packet_analysis' THEN module ELSE 'general' END,
   fallback_enabled=(module<>'packet_analysis'),
   generation=generation+1,owner_instance=NULL,owner_heartbeat_at=NULL,
   drain_requested=FALSE,drain_generation=drain_generation+1,drain_ack_at=NULL,drain_ack_generation=NULL;
  DELETE FROM endpoint_admission;
  DELETE FROM runtime_task_lease;
  UPDATE jobs SET state='OUTCOME_UNKNOWN',outcome='OUTCOME_UNKNOWN' WHERE state IN ('CLAIMED','EXECUTING');
 END IF;
END $$;
COMMIT;"""
    k(NS, "exec", "-i", preview_name("ui2-preview-db"), "--", "sh", "-c",
      PG_ENV + "exec psql -Xq --set=ON_ERROR_STOP=1 -d ui2", input=sql)


def preview_claim_insert():
    """Admission-shaped fixture SQL; only claimed inside a rolled-back transaction.

    The Python preview cannot invoke the Java DAO. Match its run-target
    admission fields and V90's closed diagnostic parameter shape instead.
    No diagnostic command or result is needed to assert ownership.
    """
    return (
        "INSERT INTO jobs(job_id,job_type,capability_id,target_kind,target_ref,state,action_class,"
        "submitted_by_actor_fingerprint,submitted_at,idempotency_key,precheck_results,"
        "diagnostic_port,diagnostic_gate_revision) "
        "VALUES('preview-claim-assertion',capability,capability,"
        "CASE WHEN capability='packet_analysis_run' THEN 'packet_analysis_case' "
        "WHEN capability='script_run' THEN 'script' ELSE 'discovery_run' END,"
        "'preview-synthetic-target',"
        "'REQUESTED','read','system:preview','1970-01-01','preview-claim-assertion','[]'::jsonb,"
        "CASE WHEN capability='fmg_interface_detail' THEN 'port1' ELSE NULL END,"
        "CASE WHEN capability='fmg_interface_detail' THEN 90 ELSE NULL END);"
    )


def job_module_mapping(repo):
    """The latest ui2_job_module definition across the migrations (V128 first, V154 adds packet_analysis_run)."""
    migrations = sorted((repo / "ui2/service/src/main/resources/db/migration").glob("V*__*.sql"),
                        key=lambda path: int(path.name.split("__")[0][1:]))
    definitions = [m[0] for path in migrations for m in
                   re.finditer(r"FUNCTION ui2_job_module\(capability TEXT\).*?\$\$;", path.read_text(), re.S)]
    if not definitions:
        raise RuntimeError("ui2_job_module mapping unavailable")
    return definitions[-1]


def assert_module_claims(repo, workers):
    """Exercise the production atomic claim and DB trigger; roll back every synthetic claim."""
    roles = {c.get("args", ["worker", "general"])[1] if len(c.get("args", [])) > 1 else "general"
             for w in workers for c in w["deployment"]["spec"]["template"]["spec"]["containers"]
             if c.get("args", [])[:1] == ["worker"]}
    if not {"general", "policy"}.issubset(roles):
        raise RuntimeError("Preview lacks a required claim owner pod")
    owners = sorted({"general", "policy"} | ({"backup", "packet_analysis"} & roles))
    def db(sql):
        return k(NS, "exec", "-i", preview_name("ui2-preview-db"), "--", "sh", "-c",
                 PG_ENV + "exec psql -XqAt --set=ON_ERROR_STOP=1 -d ui2", input=sql)
    deadline = time.monotonic() + 120
    while db("SELECT count(*)=" + str(len(owners)) + " FROM module_runtime_control WHERE module IN ("
             + ",".join("'" + owner + "'" for owner in owners) + ") "
             "AND owner_instance IS NOT NULL AND owner_heartbeat_at>now()-interval '60 seconds';").strip() != "t":
        if time.monotonic() >= deadline:
            raise RuntimeError("Preview claim owners lack live heartbeats")
        time.sleep(2)
    db("BEGIN;SELECT set_config('app.actor_fingerprint','system:preview',true);"
       "SELECT set_config('app.action_id','preview_policy_handover',true);"
       "SELECT pg_advisory_xact_lock(294611);"
       "UPDATE module_runtime_control SET effective_owner='policy',fallback_enabled=FALSE,generation=generation+1 "
       "WHERE module='policy' AND owner_instance IS NOT NULL AND owner_heartbeat_at>now()-interval '60 seconds';"
       "UPDATE module_runtime_control SET effective_owner='backup',fallback_enabled=FALSE,generation=generation+1 "
       "WHERE module='backup' AND owner_instance IS NOT NULL AND owner_heartbeat_at>now()-interval '60 seconds';COMMIT;")
    source = (repo / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java").read_text()
    claim = source.split('"""', 2)[1].strip()
    capabilities = sorted(set(re.findall(r"'([a-z]+_[a-z_]+)'", job_module_mapping(repo))) -
                          {"policy", "backup", "inventory", "failover", "diagnostics", "configuration",
                           "packet_analysis", "script_runner"})
    if "packet_analysis" not in roles:
        capabilities = [c for c in capabilities if c != "packet_analysis_run"]
    if "script_runner" not in roles:
        capabilities = [c for c in capabilities if c != "script_run"]
    backup_start = datetime.now(ZoneInfo("Europe/Istanbul")).replace(hour=2, minute=0, second=0, microsecond=0)
    for capability in capabilities:
        # Formatting is limited to repository-owned capability literals; owner identity stays inside SQL.
        statement = (claim.replace("{0}", "quote_literal(instance)").replace("{1}", "quote_literal('60')")
                     .replace("{2}", "quote_literal(capability)").replace("{3}", "'infinity'")
                     .replace("{4}", "'" + backup_start.isoformat() + "'")
                     .replace("{5}", "'" + (backup_start + timedelta(hours=1)).isoformat() + "'")
                     .replace("{6}", "'cp_gateway_backup,cp_spark_sftp_backup,pan_device_state_backup,"
                              "fgt_config_backup,asa_config_backup,https_vendor_backup,rdw_cc_config_backup'")
                     .replace("{7}", "'system:backup-scheduler'")
                     .replace("{8}", "'system:mds-export-scheduler'")
                     .replace("{9}", "'cp_mds_export'"))
        unresolved = sorted(set(re.findall(r"\{\d+\}", statement)))
        if unresolved:
            raise RuntimeError("Preview claim SQL has unresolved placeholders: " + ", ".join(unresolved))
        # PL/pgSQL builds the exact statement with locally read owner identity.
        # Use Python to quote the fixed SQL segments rather than exposing the instance.
        parts = re.split(r"(quote_literal\((?:instance|capability|'60')\))", statement)
        expression = " || ".join("'" + part.replace("'", "''") + "'" if i % 2 == 0 else part
                                 for i, part in enumerate(parts))
        result = db("BEGIN;SELECT pg_advisory_xact_lock(294611);"
            "SELECT set_config('app.actor_fingerprint','system:preview',true);"
            "SELECT set_config('app.action_id','preview_claim_assertion',true);"
            "DO $$ DECLARE instance TEXT; capability TEXT := '" + capability + "'; claimed TEXT; epoch BIGINT; BEGIN "
            "SELECT r.owner_instance INTO STRICT instance FROM module_runtime_control m "
            "JOIN module_runtime_control r ON r.module=m.effective_owner WHERE m.module=ui2_job_module(capability) "
            "AND NOT m.drain_requested AND NOT r.drain_requested AND r.owner_instance IS NOT NULL "
            "AND r.owner_heartbeat_at>now()-interval '60 seconds';"
            "IF NOT FOUND THEN RAISE EXCEPTION 'PREVIEW_OWNER_UNAVAILABLE'; END IF;"
            + preview_claim_insert() +
            "EXECUTE " + expression + " INTO claimed,epoch;"
            "IF claimed IS DISTINCT FROM 'preview-claim-assertion' THEN RAISE EXCEPTION 'PREVIEW_CLAIM_DENIED'; END IF;"
            "END $$;SELECT 'CLAIM_PASS';ROLLBACK;")
        if "CLAIM_PASS" not in result.splitlines():
            raise RuntimeError("Preview module claim assertion failed")
    print("PREVIEW MODULE CLAIMS: PASS", flush=True)


def cleanup(owner):
    # Attempt every scope even if one API call fails. A cleanup failure is a red gate.
    failures = []
    with timing("preview_teardown"):
        for namespace, kind, selector in (("ui2", "networkpolicy", LABEL + "=" + NS),
                                          (BUILD_NS, "jobs", LABEL + "=" + owner), (NS, "namespace", "")):
            try:
                if kind == "namespace":
                    ns = json.loads(k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "json") or "{}")
                    if not ns:
                        continue
                    if ns.get("metadata", {}).get("labels", {}).get(LABEL) != owner:
                        raise RuntimeError("Preview namespace ownership changed")
                    # Poll instead of one bounded kubectl wait: pod termination can outlast it.
                    k(NS, "delete", "namespace", NS, "--wait=false")
                    deadline = time.monotonic() + NAMESPACE_DELETE_SECONDS
                    while k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "name").strip():
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Preview namespace remains")
                        time.sleep(5)
                else:
                    k(namespace, "delete", kind, "-l", selector, "--ignore-not-found", "--cascade=foreground",
                      "--wait=true", "--timeout=120s", timeout=130)
                    remaining = "jobs,pods" if kind == "jobs" else kind
                    if json.loads(k(namespace, "get", remaining, "-l", selector, "-o", "json"))["items"]:
                        raise RuntimeError("Preview objects remain")
            except (RuntimeError, OSError, ValueError, subprocess.SubprocessError):
                failures.append(kind)
    if failures:
        raise RuntimeError("Preview teardown incomplete; ship blocked")


REUSE_RECORD_LIMIT = 20


def record_reusable_images(tree, commit, images):
    """Remember, on this host only, which images a PASSING preview built for this exact tree.

    run_build.sh -> hosta_restamp_image.py looks the record up by the merged commit's tree hash: an identical tree
    means identical sources, so the production deploy re-stamps the verified service image with its own
    project/deploy_info.json instead of rebuilding it. Best effort: a missing record only means a normal build."""
    if not re.fullmatch(r"[0-9a-f]{40}", tree or "") or len(images) != 2:
        return None
    digests = [image.rsplit("@", 1)[-1] for image in images]
    if not all(re.fullmatch(r"sha256:[0-9a-f]{64}", digest) for digest in digests):
        return None
    try:
        directory = Path.home() / ".local/state/nexus-preview" / "reuse"
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        record = directory / (tree + ".json")
        staged = directory / (tree + ".json.tmp")
        staged.write_text(json.dumps(dict(tree=tree, commit=commit, service=digests[0], e2e=digests[1],
                                          created=int(time.time())), sort_keys=True) + "\n")
        staged.chmod(0o600)
        os.replace(staged, record)
        for old in sorted(directory.glob("*.json"), key=lambda path: path.stat().st_mtime,
                          reverse=True)[REUSE_RECORD_LIMIT:]:
            old.unlink()
    except OSError:
        return None
    return "PREVIEW IMAGES: tree=%s service=%s e2e=%s" % (tree, digests[0], digests[1])


def run(repo, commit, tree=None):
    global NS, PHASE, STEP, PREVIEW_PRIORITY
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid candidate commit")
    if tree is not None and not re.fullmatch(r"[0-9a-f]{40}", tree):
        raise ValueError("Invalid candidate tree")
    images = None
    previous_ns, previous_priority, owner = NS, PREVIEW_PRIORITY, secrets.token_hex(6)
    NS = "ui2-preview-" + owner
    PHASE = "preview_setup"
    STEP = "get namespace"
    try:
        if k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "name").strip():
            raise RuntimeError("Preview namespace already exists; refusing to reuse or delete it")
        try:
            with timing("preview_setup"):
                STEP = "create namespace"
                create(NS, dict(apiVersion="v1", kind="Namespace", metadata=dict(name=NS, labels={LABEL: owner})))
                STEP = "namespace limits"
                for item in namespace_limits():
                    create(NS, item)
                STEP = "priority class"
                PREVIEW_PRIORITY = PREVIEW_PRIORITY_CLASS if k(
                    None, "get", "priorityclass", PREVIEW_PRIORITY_CLASS, "--ignore-not-found", "-o", "name").strip() else None
                STEP = "load templates"
                workers = worker_templates(repo)
                STEP = "policies"
                for item in policies(workers):
                    create(NS, item)
            # The image builds and the database copy do not depend on each other (measured 2026-11-03: 242 s and
            # 202 s one after the other); the workloads need both, so they start when both have finished.
            def build_branch():
                with timing("preview_build"):
                    _set_step("build images")
                    return build_images(repo, commit, owner)

            def database_branch():
                with timing("preview_database"):
                    _set_step("setup database")
                    return setup_database(repo)

            images, db_ip = run_concurrently(build_branch, database_branch)
            with timing("preview_workers"):
                STEP = "worker services"
                endpoints = setup_worker_services(workers)
                for worker in (w for w in workers if w["ports"]):
                    with timing("preview_" + worker["component"]):
                        STEP = "setup worker"
                        setup_workload(worker["deployment"], images[0], db_ip, endpoints)
            with timing("preview_service"):
                STEP = "setup service"
                service_ip = setup_service(repo, images[0], db_ip, endpoints)
            reset_preview_runtime()
            with timing("preview_device_workers"):
                for worker in (w for w in workers if not w["ports"]):
                    with timing("preview_" + worker["component"]):
                        setup_workload(worker["deployment"], images[0], db_ip, endpoints)
            with timing("preview_claims"):
                STEP = "assert module claims"
                assert_module_claims(repo, workers)
            with timing("preview_e2e"):
                STEP = "load e2e templates"
                templates = load(repo, "deploy/ui2/70-e2e-job.yaml")
                STEP = "select e2e job"
                job = next(t for t in templates["items"] if t["kind"] == "Job")
                if k("ui2", "get", "secret", "ui2-e2e-canary", "--ignore-not-found", "-o", "name").strip():
                    copy_secret("ui2-e2e-canary")
                STEP = "create e2e job"
                create(NS, e2e_manifest(job, images[1], service_ip))
                STEP = "wait e2e job"
                wait_job(NS, preview_name("ui2-preview-e2e"), timeout=930)
        finally:
            cleanup(owner)
    finally:
        NS, PREVIEW_PRIORITY = previous_ns, previous_priority
    # Only after a complete pass with a verified teardown.
    line = record_reusable_images(tree, commit, images) if tree and images else None
    if line:
        print(line, flush=True)
    print("PREVIEW E2E: PASS", flush=True)


def interrupted(signum, frame):
    global INTERRUPTED
    INTERRUPTED = True
    raise RuntimeError("Preview interrupted")


def refresh_main(argv):
    parser = argparse.ArgumentParser(prog="hosta_preview_e2e.py refresh-golden",
                                     description="Refresh the golden preview database (nightly, host-side).")
    parser.add_argument("--repo", type=Path, required=True, help="the deployed tree (production's templates)")
    args = parser.parse_args(argv)
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, interrupted)
    try:
        with private_log():
            refresh_golden(args.repo)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(failure_line(error), flush=True)
        return 1
    print("PREVIEW GOLDEN: PASS", flush=True)
    return 0


if __name__ == "__main__":
    if sys.argv[1:2] == ["refresh-golden"]:
        sys.exit(refresh_main(sys.argv[2:]))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tree", help="git tree hash of the commit; enables the reusable-image record")
    args = parser.parse_args()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, interrupted)
    try:
        with private_log():
            run(args.repo, args.commit, args.tree)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(failure_line(error), flush=True)
        sys.exit(1)
