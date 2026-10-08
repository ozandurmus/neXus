#!/usr/bin/env python3
"""Host-side isolated candidate check. Never invokes the production rollout path.

Database bytes stay on the pod-to-pod network; pod logs are withheld.
Kubectl failures retain bounded, masked API diagnostics in the step log.
Build Jobs use the existing Kaniko/proxy/CA setup with their own emptyDir contexts.
The 60 s database-copy target is UNKNOWN until the reviewer's live run;
TIMING preview_db_copy measures the complete snapshot/dump/filtered-copy phase.
"""
import argparse
import base64
import copy
from contextlib import contextmanager
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
PG_ENV = 'unset PGPASSWORD; export PGUSER="${POSTGRESQL_USER:-${POSTGRES_USER:-ui2_migrate}}"; export PGDATABASE="${POSTGRESQL_DATABASE:-${POSTGRES_DB:-ui2}}"; '


PHASE = "preview_setup"
STEP = "get namespace"
LOG = None
LOAD_CONTEXT = """set -e
tar -xf - -C /workspace
for input in /workspace/project/deploy_info.json /workspace/ui2/.ca/*.pem /workspace/ui2/.gradle-home/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3-bin.zip; do
    test -s "$input" || { echo CONTEXT_INCOMPLETE >&2; exit 1; }
done
touch /workspace/.ready
"""


class PreviewFailure(RuntimeError):
    def __init__(self, reason, exception_class="PreviewFailure"):
        super().__init__("Preview operation failed; details withheld")
        self.phase, self.step, self.reason = PHASE, STEP, reason
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
    global STEP
    STEP = "overlay host inputs"
    source = (Path.home() / "nexus").resolve()
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
    global STEP
    STEP = "prepare build context"
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
    global PHASE
    previous, PHASE = PHASE, phase
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
        PHASE = previous
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
    global STEP
    verb = args[0]
    kinds = {"pod", "pods", "job", "jobs", "jobs,pods", "namespace", "secret", "service", "deployment"}
    kind = next((a.split("/")[0] for a in args[1:] if a.split("/")[0] in kinds), "manifest")
    if input is not None and verb == "apply":
        kind = json.loads(input)["kind"]
    STEP = verb + " " + kind
    argv = ["kubectl"] + (["-n", namespace] if namespace is not None else []) + list(args)
    def failed(stderr, reason, exception_class="PreviewFailure"):
        line = f"PREVIEW STEP: phase={PHASE} step={STEP} reason={reason}:{exception_class} error=" + masked_error(stderr)
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
    global STEP
    STEP = "load templates"
    stream = k(None, "create", "--dry-run=client", "-f", str(repo / name), "-o", "json")
    STEP = "load templates"
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


def preview_references(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"secret", "configMap", "secretKeyRef", "configMapKeyRef", "secretRef", "configMapRef"}:
                for name_key in ("name", "secretName"):
                    if name_key in item:
                        item[name_key] = preview_name(item[name_key])
            else:
                preview_references(item)
    elif isinstance(value, list):
        for item in value:
            preview_references(item)


def pod_spec(template):
    spec = copy.deepcopy(template["spec"]["template"]["spec"])
    for key in ("priorityClassName", "tolerations", "serviceAccountName"):
        spec.pop(key, None)
    spec["automountServiceAccountToken"] = False
    spec.setdefault("securityContext", {}).setdefault("fsGroup", 0)
    # No production PVC, hostPath or writable state is ever shared.
    for volume in spec.get("volumes", []):
        if "persistentVolumeClaim" in volume or "hostPath" in volume:
            volume.pop("persistentVolumeClaim", None)
            volume.pop("hostPath", None)
            volume["emptyDir"] = {"sizeLimit": "8Gi"}
    preview_references(spec)
    return spec


def database_manifest(template):
    spec = pod_spec(template)
    spec["containers"][0].setdefault("env", []).extend([
        dict(name=name, valueFrom=dict(secretKeyRef=dict(
            name=preview_name("ui2-db-source"), key=key)))
        for name, key in (("SOURCE_DB_USER", "migrate-user"), ("SOURCE_DB_PASSWORD", "migrate-password"))
    ])
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
                        "NEXUS_FAILOVER_MUTATION_ENABLED": "false",
                        "LOGGING_LEVEL_ROOT": "ERROR"}
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


def build_manifest(template, loader, name, commit, owner, dockerfile, destination):
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
        ("--context=", "--dockerfile=", "--destination=", "--digest-file="))] + [
        "--context=dir:///workspace" + ("/ui2/frontend" if dockerfile.endswith("Dockerfile.e2e") else ""),
        "--dockerfile=/workspace/" + dockerfile, "--destination=" + destination,
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
        pod = json.loads(k(NS, "get", "pod", preview_name("ui2-preview-db"),
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
    global STEP
    STEP = "copy database"
    script = ("set +x\n(" + source + ") | (" + target + ") >/dev/null\n"
              'status=("${PIPESTATUS[@]}")\n'
              'printf "NEXUS_COPY_STATUS=%s,%s\\n" "${status[0]}" "${status[1]}" >&2\n'
              'test "${status[0]}" = 0 && test "${status[1]}" = 0')
    check_preview_database()
    process = subprocess.Popen(
        ["kubectl", "-n", NS, "exec", preview_name("ui2-preview-db"), "--", "bash", "-c", script],
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


def build_images(repo, commit, owner):
    prepare_build_context(repo, commit)
    template = json.loads(k(None, "create", "--dry-run=client", "-f",
                            str(Path.home() / "build-job-proxy.yaml"), "-o", "json"))
    loader = next(o for o in load(repo, "deploy/ui2-image-build/20-context-loader.yaml")["items"]
                  if o["kind"] == "Pod")
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
                           "sh", "-c", LOAD_CONTEXT], timeout=120)
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
    target = json.loads(k(NS, "get", "pod", preview_name("ui2-preview-db"), "-o", "json"))
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
                        + " --exclude-table-data=public.audit_log --exclude-table-data=public.backup_artefact_entry",
                        restore_options + "exec pg_restore --exit-on-error --no-owner --dbname=ui2",
                        timeout=4 * seconds)
        for table, query in (("audit_log", AUDIT_SELECT), ("backup_artefact_entry", ENTRY_SELECT)):
            sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '" + snapshot + "'; COPY (" + query + ") TO STDOUT; COMMIT"
            database_stream(read_only + 'exec ' + source_auth + 'psql -Xq --set=ON_ERROR_STOP=1 -c ' + shlex.quote(sql),
                            restore_options + 'exec psql -Xq --set=ON_ERROR_STOP=1 -c "COPY public.' + table + ' FROM STDIN"',
                            timeout=4 * seconds)


def copy_secret(name, target_name=None, keys=None):
    secret = json.loads(k("ui2", "get", "secret", name, "-o", "json"))
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata(target_name or name),
                    type=secret.get("type", "Opaque"),
                    data={key: secret["data"][key] for key in keys} if keys else secret["data"]))


def setup_database(repo):
    objects = load(repo, "deploy/ui2/40-database-statefulset.yaml")["items"]
    template = next(o for o in objects if o["kind"] == "StatefulSet")
    live = json.loads(k("ui2", "get", "pod", "ui2-db-0", "-o", "json"))
    pinned = template["spec"]["template"]["spec"]["containers"][0]["image"]
    if live["spec"]["containers"][0]["image"] != pinned:
        raise RuntimeError("Production PostgreSQL differs from the pinned bootstrap; preview refused")
    config = next(o for o in load(repo, "deploy/ui2/10-configmap.yaml")["items"]
                  if o["kind"] == "ConfigMap")
    create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata("ui2-config"),
                    data={key: config["data"][key] for key in ("db_name", "create-app-role.sh")}))
    for obj in objects:
        if obj["kind"] == "ConfigMap":
            create(NS, dict(apiVersion="v1", kind="ConfigMap", metadata=metadata(obj["metadata"]["name"]),
                            data=obj["data"]))
    values = {"migrate-user": "ui2_migrate", "app-user": "ui2_app",
              "migrate-password": secrets.token_urlsafe(32), "app-password": secrets.token_urlsafe(32)}
    create(NS, dict(apiVersion="v1", kind="Secret", metadata=metadata("ui2-db"), type="Opaque",
                    data={name: base64.b64encode(value.encode()).decode() for name, value in values.items()}))
    copy_secret("ui2-db", "ui2-db-source", ("migrate-user", "migrate-password"))
    create(NS, database_manifest(template))
    k(NS, "wait", "--for=condition=Ready", "pod/" + preview_name("ui2-preview-db"), "--timeout=120s", timeout=130)
    with timing("preview_db_copy"):
        copy_database()
    return json.loads(k(NS, "get", "pod", preview_name("ui2-preview-db"), "-o", "json"))["status"]["podIP"]


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
    deployment = workload_manifest(template, image, db_ip, endpoints)
    for name in sorted(names - {"ui2-db"}):
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
    sql = """BEGIN;
SELECT set_config('app.actor_fingerprint','system:preview',true);
SELECT set_config('app.action_id','preview_runtime_reset',true);
DO $$ BEGIN
 IF to_regclass('module_runtime_control') IS NOT NULL THEN
  UPDATE module_runtime_control SET effective_owner='general',fallback_enabled=TRUE,
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
        "VALUES('preview-claim-assertion',capability,capability,'discovery_run','preview-synthetic-target',"
        "'REQUESTED','read','system:preview','1970-01-01','preview-claim-assertion','[]'::jsonb,"
        "CASE WHEN capability='fmg_interface_detail' THEN 'port1' ELSE NULL END,"
        "CASE WHEN capability='fmg_interface_detail' THEN 90 ELSE NULL END);"
    )


def assert_module_claims(repo, workers):
    """Exercise the production atomic claim and DB trigger; roll back every synthetic claim."""
    roles = {c.get("args", ["worker", "general"])[1] if len(c.get("args", [])) > 1 else "general"
             for w in workers for c in w["deployment"]["spec"]["template"]["spec"]["containers"]
             if c.get("args", [])[:1] == ["worker"]}
    if not {"general", "policy"}.issubset(roles):
        raise RuntimeError("Preview lacks a required claim owner pod")
    def db(sql):
        return k(NS, "exec", "-i", preview_name("ui2-preview-db"), "--", "sh", "-c",
                 PG_ENV + "exec psql -XqAt --set=ON_ERROR_STOP=1 -d ui2", input=sql)
    deadline = time.monotonic() + 120
    while db("SELECT count(*)=2 FROM module_runtime_control WHERE module IN ('general','policy') "
             "AND owner_instance IS NOT NULL AND owner_heartbeat_at>now()-interval '60 seconds';").strip() != "t":
        if time.monotonic() >= deadline:
            raise RuntimeError("Preview claim owners lack live heartbeats")
        time.sleep(2)
    db("BEGIN;SELECT set_config('app.actor_fingerprint','system:preview',true);"
       "SELECT set_config('app.action_id','preview_policy_handover',true);"
       "SELECT pg_advisory_xact_lock(294611);"
       "UPDATE module_runtime_control SET effective_owner='policy',fallback_enabled=FALSE,generation=generation+1 "
       "WHERE module='policy' AND owner_instance IS NOT NULL AND owner_heartbeat_at>now()-interval '60 seconds';COMMIT;")
    source = (repo / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java").read_text()
    claim = source.split('"""', 2)[1].strip()
    mapping = (repo / "ui2/service/src/main/resources/db/migration/V128__module_endpoint_runtime.sql").read_text().split("CREATE TABLE", 1)[0]
    capabilities = sorted(set(re.findall(r"'([a-z]+_[a-z_]+)'", mapping)) -
                          {"policy", "backup", "inventory", "failover", "diagnostics", "configuration"})
    for capability in capabilities:
        # Formatting is limited to repository-owned capability literals; owner identity stays inside SQL.
        statement = claim.replace("{0}", "quote_literal(instance)").replace("{1}", "quote_literal('60')").replace("{2}", "quote_literal(capability)")
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
                    k(NS, "delete", "namespace", NS, "--wait=true", "--timeout=120s", timeout=130)
                    if k(NS, "get", "namespace", NS, "--ignore-not-found", "-o", "name").strip():
                        raise RuntimeError("Preview namespace remains")
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


def run(repo, commit):
    global NS, PHASE, STEP
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid candidate commit")
    previous_ns, owner = NS, secrets.token_hex(6)
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
                STEP = "load templates"
                workers = worker_templates(repo)
                STEP = "policies"
                for item in policies(workers):
                    create(NS, item)
            with timing("preview_build"):
                STEP = "build images"
                images = build_images(repo, commit, owner)
            with timing("preview_database"):
                STEP = "setup database"
                db_ip = setup_database(repo)
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
        NS = previous_ns
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
        with private_log():
            run(args.repo, args.commit)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(failure_line(error), flush=True)
        sys.exit(1)
