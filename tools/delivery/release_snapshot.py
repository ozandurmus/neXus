#!/usr/bin/env python3
"""Private, host-local release snapshots. JSON manifests are also valid YAML."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import shlex
import time

NAMESPACES = ("ui2", "ui2-security", "ui2-build")
RESOURCES = ("deployments,statefulsets,daemonsets,cronjobs,jobs,services,configmaps,"
             "networkpolicies,serviceaccounts,roles,rolebindings,poddisruptionbudgets")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}$")
SNAPSHOT_ID = re.compile(r"\d{8}T\d{12}Z_[a-f0-9]{12}")
RESTORE_KINDS = {"Namespace", "Deployment", "StatefulSet", "DaemonSet", "CronJob", "Service", "ConfigMap",
                 "NetworkPolicy", "ServiceAccount", "Role", "RoleBinding", "PodDisruptionBudget"}
VERSION = re.compile(r"\d+(?:[._]\d+)*")


class SafeReleaseError(RuntimeError):
    """A diagnostic authored here, containing no command output or operational values."""


@contextmanager
def step(name):
    try:
        yield
    except Exception as error:
        if not hasattr(error, "release_step"):
            error.release_step = name
        raise


def failure_message(error):
    if isinstance(error, SafeReleaseError):
        return str(error)
    if isinstance(error, KeyError):
        return "required resource field missing"
    if isinstance(error, ValueError):
        return "invalid response or snapshot format"
    if isinstance(error, subprocess.TimeoutExpired):
        return "local command timed out"
    if isinstance(error, OSError):
        return "local prerequisite or filesystem operation failed"
    return "release operation failed; raw exception details withheld"


def run(*argv, input=None):
    result = subprocess.run(argv, input=input, capture_output=True, timeout=600)
    if result.returncode:
        # Never relay command output: manifests, DB errors and diffs are local-only.
        raise SafeReleaseError(f"{argv[0]} failed (exit {result.returncode})")
    return result.stdout


def get(namespace, resources):
    return json.loads(run("kubectl", "-n", namespace, "get", resources, "-o", "json"))


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def pod_spec(item):
    spec = item.get("spec", {})
    if item["kind"] == "CronJob":
        return spec.get("jobTemplate", {}).get("spec", {}).get("template", {}).get("spec")
    if item["kind"] == "Pod":
        return spec
    return spec.get("template", {}).get("spec")


def prune(value):
    """Remove server state and embedded kubectl annotations, including nested templates."""
    if isinstance(value, list):
        return [prune(v) for v in value]
    if not isinstance(value, dict):
        return value
    result = {k: (v if k in ("data", "binaryData", "stringData") else prune(v))
              for k, v in value.items() if k != "status" or "kind" not in value}
    metadata = result.get("metadata")
    if isinstance(metadata, dict):
        for key in ("uid", "resourceVersion", "generation", "creationTimestamp", "managedFields",
                    "ownerReferences", "deletionTimestamp", "deletionGracePeriodSeconds", "finalizers"):
            metadata.pop(key, None)
        annotations = metadata.get("annotations", {})
        for key in list(annotations):
            if key.startswith("kubectl.kubernetes.io/") or key == "deployment.kubernetes.io/revision":
                del annotations[key]
    if result.get("kind") == "ServiceAccount":
        result.pop("secrets", None)
    if result.get("kind") == "Job":
        # Job selectors contain controller-generated identity. Templates are evidence only.
        spec = result.get("spec", {})
        spec.pop("selector", None)
        labels = spec.get("template", {}).get("metadata", {}).get("labels", {})
        for key in list(labels):
            if key in ("controller-uid", "job-name") or key.startswith("batch.kubernetes.io/"):
                del labels[key]
    if result.get("kind") == "Secret":
        return {"name": value["metadata"]["name"],
                "resourceVersion": value["metadata"]["resourceVersion"]}
    return result


def completed(item):
    status = item.get("status", {})
    return (item.get("kind") == "Pod" and status.get("phase") in ("Succeeded", "Failed") or
            item.get("kind") == "Job" and any(
                condition.get("type") in ("Complete", "Failed") and condition.get("status") == "True"
                for condition in status.get("conditions", [])))


def pin_images(items, pods):
    """Resolve tag references from observed container status; refuse ambiguous or absent digests."""
    observed = {}
    for pod in pods:
        if completed(pod):
            continue
        spec = pod_spec(pod)
        for field, status_field in (("containers", "containerStatuses"),
                                    ("initContainers", "initContainerStatuses")):
            statuses = {s["name"]: s for s in pod.get("status", {}).get(status_field, [])}
            for container in spec.get(field, []):
                status = statuses.get(container["name"], {})
                image_id = status.get("imageID", "").split("://")[-1]
                if "@" in image_id and DIGEST.fullmatch(image_id.split("@")[-1]):
                    observed.setdefault(container["image"], set()).add(image_id)
    images = []
    for item in items:
        if completed(item):
            continue
        spec = pod_spec(item)
        if spec is None:
            continue
        for field in ("containers", "initContainers"):
            for container in spec.get(field, []):
                image = container["image"]
                if "@" not in image or not DIGEST.fullmatch(image.split("@")[-1]):
                    candidates = observed.get(image, set())
                    if len(candidates) != 1:
                        raise SafeReleaseError("missing or ambiguous workload image digest; snapshot refused")
                    image = next(iter(candidates))
                    container["image"] = image
                images.append({"kind": item["kind"], "workload": item["metadata"]["name"],
                               "container": container["name"], "image": image})
    return images


def schema_version():
    sql = "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1"
    value = run("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c",
                'psql -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -Atc "' + sql + '"').decode().strip()
    if not VERSION.fullmatch(value):
        raise SafeReleaseError("Flyway schema version missing or unsupported")
    return value


def version_key(value):
    if not VERSION.fullmatch(value):
        raise SafeReleaseError("invalid Flyway version")
    parts = [int(v) for v in re.split(r"[._]", value)]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def retain(root):
    snapshots = sorted(p for p in root.iterdir() if p.is_dir() and not p.is_symlink()
                       and SNAPSHOT_ID.fullmatch(p.name) and (p / "manifest.json").is_file())
    for old in snapshots[:-20]:
        shutil.rmtree(old)


def deployed_commit(fallback):
    try:
        commit = json.loads(run("kubectl", "-n", "ui2", "exec", "deployment/ui2-service", "--",
                                "cat", "/app/project/deploy_info.json"))["commit"]
        if isinstance(commit, str) and re.fullmatch(r"[a-f0-9]{40}", commit):
            return commit
    except (RuntimeError, KeyError, ValueError, TypeError, OSError, subprocess.TimeoutExpired):
        pass
    if isinstance(fallback, str) and re.fullmatch(r"[a-f0-9]{40}", fallback):
        return fallback
    raise SafeReleaseError("deployed commit unavailable; valid build commit fallback required")


def sql(query):
    return run("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c",
               'psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -Atc ' + shlex.quote(query)).decode().strip()


def runtime_state():
    available = sql("select to_regclass('module_runtime_control') is not null")
    if available == "f":
        return {"compatible_runtime": False, "modules": [], "tasks": []}
    if available != "t":
        raise SafeReleaseError("runtime compatibility unavailable; snapshot refused")
    modules = json.loads(sql("select coalesce(json_agg(m order by module),'[]') from "
                             "(select module,effective_owner,fallback_enabled,generation,drain_requested,drain_generation "
                             "from module_runtime_control) m"))
    tasks = json.loads(sql("select coalesce(json_agg(t order by task_key),'[]') from "
                           "(select task_key,owner_role,owner_generation,epoch,state from runtime_task_lease) t"))
    return {"compatible_runtime": True, "modules": modules, "tasks": tasks}


def snapshot(root, fallback_commit=None):
    with step("deployed-commit lookup"):
        commit = deployed_commit(fallback_commit)
    snapshot_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "_" + commit[:12]
    directory = root / ("." + snapshot_id + ".partial")
    with step("snapshot directory"):
        directory.mkdir(mode=0o700)
    try:
        captures, images = {}, {}
        for namespace in NAMESPACES:
            with step("namespace capture"):
                ns = json.loads(run("kubectl", "get", "namespace", namespace, "--ignore-not-found", "-o", "json") or b"null")
                if ns is None:
                    if namespace == "ui2":
                        raise SafeReleaseError("running namespace missing")
                    captures[namespace] = {"present": False}
                    continue
            resources = RESOURCES if namespace == "ui2" else RESOURCES + ",persistentvolumeclaims"
            with step("resource capture"):
                items = get(namespace, resources)["items"]
            with step("workload stability"):
                for item in items:
                    if item["kind"] in ("Deployment", "StatefulSet", "DaemonSet"):
                        status, spec = item.get("status", {}), item["spec"]
                        desired = status.get("desiredNumberScheduled", 0) if item["kind"] == "DaemonSet" else spec.get("replicas", 1)
                        ready = status.get("numberReady", 0) if item["kind"] == "DaemonSet" else status.get("readyReplicas", 0)
                        updated = status.get("updatedNumberScheduled", 0) if item["kind"] == "DaemonSet" else status.get("updatedReplicas", 0)
                        if desired and (ready != desired or updated != desired or
                                        status.get("observedGeneration") != item["metadata"].get("generation")):
                            raise SafeReleaseError("workload is not stable; snapshot refused")
            # Standalone build/security loaders are captured for evidence, never restarted by rollback.
            with step("pod capture"):
                pods = get(namespace, "pods")["items"]
            items += [p for p in pods if not p.get("metadata", {}).get("ownerReferences")]
            with step("image digest resolution"):
                images[namespace] = pin_images(items, pods)
            with step("resource pruning"):
                clean = [prune(ns)] + [prune(item) for item in items]
            with step("manifest write"):
                write(directory / f"{namespace}.yaml", {"apiVersion": "v1", "kind": "List", "items": clean})
            # Server-side projection: Secret contents never enter this process or its files.
            with step("Secret metadata capture"):
                secrets = run("kubectl", "-n", namespace, "get", "secrets", "-o",
                              'jsonpath={range .items[*]}{.metadata.name}{"\\t"}{.metadata.resourceVersion}{"\\n"}{end}')
                write(directory / f"{namespace}-secrets.json", [dict(zip(("name", "resourceVersion"), line.split("\t")))
                                                               for line in secrets.decode().splitlines()])
            captures[namespace] = {"present": True, "resources": len(clean), "jobs_and_pods": "templates only"}
        with step("Flyway version query"):
            version = schema_version()
        with step("module ownership capture"):
            runtime = runtime_state()
        with step("schema dump"):
            dump = run("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c",
                       'pg_dump -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 --schema-only --no-owner --no-privileges')
            if not dump:
                raise SafeReleaseError("empty schema dump")
        with step("Flyway version verification"):
            if schema_version() != version:
                raise SafeReleaseError("schema changed during capture; snapshot refused")
        with step("snapshot publication"):
            (directory / "schema.sql.gz").write_bytes(gzip.compress(dump))
            manifest = {"format": 1, "snapshot": snapshot_id, "deployed_commit": commit,
                        "schema_version": version, "captured": captures, "images": images, "runtime": runtime,
                        "checksums": {p.name: checksum(p) for p in sorted(directory.iterdir())}}
            write(directory / "manifest.json", manifest)
            directory.rename(root / snapshot_id)
            retain(root)
        print(json.dumps({"snapshot": snapshot_id}))
    except BaseException:
        shutil.rmtree(directory, ignore_errors=True)
        raise


def load_snapshot(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    if manifest["format"] != 1 or manifest["snapshot"] != directory.name:
        raise SafeReleaseError("invalid snapshot manifest")
    required = {"schema.sql.gz"}
    for namespace in NAMESPACES:
        if manifest["captured"][namespace]["present"]:
            required.update((f"{namespace}.yaml", f"{namespace}-secrets.json"))
    if set(manifest["checksums"]) != required:
        raise SafeReleaseError("incomplete snapshot checksums")
    for name, digest in manifest["checksums"].items():
        path = directory / name
        if path.is_symlink() or checksum(path) != digest:
            raise SafeReleaseError("snapshot checksum mismatch")
    return manifest


def restore_items(directory, manifest, namespace):
    if not manifest["captured"][namespace]["present"]:
        return []
    items = json.loads((directory / f"{namespace}.yaml").read_text())["items"]
    # Never replay Jobs (migrations/builds) or transient Pods, and never manage storage.
    items = [i for i in items if i["kind"] in RESTORE_KINDS]
    pin_images(items, [])  # All restored images must already be immutable.
    return items


def stop_split_runtime(snapshot):
    # Full rollback to an old binary cannot coexist with any split owner.
    sql("begin;select set_config('app.actor_fingerprint','system:release',true);"
        "select set_config('app.action_id','full_rollback_drain',true);"
        "select pg_advisory_xact_lock(294611);"
        "update module_runtime_control set drain_requested=true,drain_generation=drain_generation+1;commit;")
    deadline = time.monotonic() + 600
    idle = ("select not exists(select 1 from jobs where state in ('CLAIMED','EXECUTING')) "
            "and not exists(select 1 from endpoint_admission where state in ('LEASED','QUARANTINED')) "
            "and not exists(select 1 from runtime_task_lease where state in ('LEASED','QUARANTINED'))")
    while sql(idle) != "t":
        if time.monotonic() >= deadline:
            raise SafeReleaseError("full rollback drain expired; active or uncertain work retained")
        time.sleep(5)
    for item in get("ui2", "deployments")["items"]:
        containers = item["spec"]["template"]["spec"]["containers"]
        if any(c.get("args", [])[:1] == ["worker"] for c in containers) or item["metadata"]["name"] == "ui2-service":
            name = item["metadata"]["name"]
            run("kubectl", "-n", "ui2", "scale", "deployment/" + name, "--replicas=0")
            selector = ",".join(k + "=" + v for k, v in item["spec"]["selector"]["matchLabels"].items())
            run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", selector, "--timeout=600s")
    if sql(idle) != "t":
        raise SafeReleaseError("full rollback has active or uncertain work; ownership retained")
    sql("begin;select set_config('app.actor_fingerprint','system:release',true);"
        "select set_config('app.action_id','full_rollback_ownership',true);"
        "select pg_advisory_xact_lock(294611);"
        "update module_runtime_control set effective_owner='general',"
        "fallback_enabled=case when module='general' then fallback_enabled else true end,generation=generation+1,"
        "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,owner_instance=null,owner_heartbeat_at=null,"
        "last_reason='pre-split snapshot rollback',last_snapshot_id=" + "'" + snapshot.replace("'", "''") + "';commit;")


def split_rollback(manifest, snapshot, apply):
    import module_deploy
    policy = next((m for m in manifest["runtime"].get("modules", []) if m["module"] == "policy"), None)
    fallback = policy is not None and policy["effective_owner"] == "general"
    targets = [target for target in module_deploy.TARGETS if any(
        item["kind"] == "Deployment" and item["workload"] == "ui2-" + target
        for item in manifest["images"].get("ui2", []))]
    images = {target: module_deploy.rollback_image(manifest, target) for target in targets}
    for target, image in images.items():
        print(json.dumps({"rollback": "module-controlled", "module": target, "image": image,
                          "drain": target in module_deploy.ROLES, "policy_fallback_capability": target == "worker",
                          "ownership": "general/fallback=true; policy stopped" if target == "policy" and fallback else
                                       "ready heartbeat before policy handover" if target == "policy" else "preserved"}))
    if fallback and "policy" not in targets:
        print("Policy: drain and stop added owner; return ownership to general/fallback=true.")
    if not apply:
        print("Dry run only. Use --apply for module-controlled snapshot rollback.")
        return
    # Restore the compatible general owner first, so fallback recovery has a live executor.
    for target, image in images.items():
        if target != "policy" or not fallback:
            module_deploy.replace(target, image, snapshot, authorization="snapshot-rollback")
    if fallback:
        module_deploy.return_policy_to_general(snapshot, "snapshot-rollback")
        if "policy" in images:
            module_deploy.run("kubectl", "-n", "ui2", "set", "image", "deployment/ui2-policy", "policy=" + images["policy"])
        module_deploy.module_e2e()


def rollback(root, target, apply, allow_newer):
    if target == "latest":
        candidates = sorted(p for p in root.iterdir() if SNAPSHOT_ID.fullmatch(p.name)
                            and p.is_dir() and not p.is_symlink() and (p / "manifest.json").is_file())
        if not candidates:
            raise SafeReleaseError("no complete snapshot")
        directory = candidates[-1]
    else:
        directory = Path(target).expanduser().resolve()
    manifest = load_snapshot(directory)
    live = schema_version()
    saved = manifest["schema_version"]
    print(f"Schema note: snapshot={saved}; live={live}; schema and DB data will not be restored.", flush=True)
    if version_key(saved) < version_key(live) and not allow_newer:
        raise SafeReleaseError("live schema is newer; review additive compatibility and use --allow-newer-schema")
    if version_key(saved) > version_key(live):
        raise SafeReleaseError("live schema is older than snapshot; required migrations are absent")
    live_runtime = runtime_state()["compatible_runtime"]
    if manifest.get("runtime", {}).get("compatible_runtime"):
        if not live_runtime:
            raise SafeReleaseError("snapshot requires the module runtime schema")
        return split_rollback(manifest, directory.name, apply)
    if live_runtime:
        print("Full rollback: drain and stop all worker/service owners; restore snapshot manifests/images; "
              "return every module to general, enable dedicated-module fallback, preserve the general self flag. "
              "Schema, data and uncertain leases are preserved.")
    plans = []
    removals = []
    for namespace in NAMESPACES:
        items = restore_items(directory, manifest, namespace)
        if not items:
            continue
        payload = json.dumps({"apiVersion": "v1", "kind": "List", "items": items}).encode()
        # A local-only diff is deliberately printed to the operator, never projected into ship logs.
        diff = subprocess.run(["kubectl", "-n", namespace, "diff", "-f", "-"], input=payload,
                              capture_output=True, timeout=600)
        if diff.returncode not in (0, 1):
            raise SafeReleaseError("kubectl diff failed; nothing restored")
        print(f"Diff: {namespace}", flush=True)
        sys.stdout.buffer.write(diff.stdout)
        sys.stdout.flush()
        plans.append((namespace, items, payload))
        if namespace == "ui2":
            saved_items = {(i["kind"], i["metadata"]["name"]): i for i in items}
            saved_keys = set(saved_items)
            for item in get(namespace, RESOURCES)["items"]:
                key = (item["kind"], item["metadata"]["name"])
                if item["kind"] == "StatefulSet" and key in saved_keys:
                    policy = item["spec"].get("persistentVolumeClaimRetentionPolicy", {})
                    if policy.get("whenScaled") == "Delete" and (
                            saved_items[key]["spec"].get("replicas", 1) < item["spec"].get("replicas", 1)):
                        raise SafeReleaseError("StatefulSet scale-down would delete PVCs; rollback refused")
                if key in saved_keys or item["kind"] not in RESTORE_KINDS or item["kind"] == "Namespace":
                    continue
                if item["kind"] == "StatefulSet":
                    raise SafeReleaseError("new StatefulSet requires manual storage-safe review; rollback refused")
                removals.append((namespace, *key))
                print(f"Remove added resource: {namespace}/{key[0]}/{key[1]}", flush=True)
    if not apply:
        print("Dry run only. Use --apply to restore these manifests.")
        return
    if live_runtime:
        stop_split_runtime(directory.name)
    # Rollback must not interrupt an executing product job.
    inflight = run("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c",
                   'psql -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -Atc "select count(*) from jobs where state in (\'CLAIMED\',\'EXECUTING\')"').decode().strip()
    if inflight != "0":
        raise SafeReleaseError("jobs in flight or unknown; rollback refused")
    for namespace, kind, name in removals:
        if kind in ("Deployment", "DaemonSet", "CronJob"):
            run("kubectl", "-n", namespace, "delete", kind.lower(), name, "--wait=true", "--timeout=600s")
    for namespace, items, payload in plans:
        run("kubectl", "-n", namespace, "apply", "-f", "-", input=payload)
        for item in items:
            if item["kind"] in ("Deployment", "StatefulSet", "DaemonSet"):
                run("kubectl", "-n", namespace, "rollout", "status",
                    f'{item["kind"].lower()}/{item["metadata"]["name"]}', "--timeout=600s")
    for namespace, kind, name in removals:
        if kind not in ("Deployment", "DaemonSet", "CronJob"):
            run("kubectl", "-n", namespace, "delete", kind.lower(), name, "--wait=true", "--timeout=600s")
    status = run("curl", "-sk", "--noproxy", "*", "--max-time", "30", "-o", "/dev/null", "-w", "%{http_code}",
                 "https://127.0.0.1/").decode().strip()
    if status != "200":
        raise SafeReleaseError("restored site did not return 200")
    print("Rollback applied; site 200. Authenticated AIView verification remains required.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "rollback"))
    parser.add_argument("target", nargs="?", default="latest")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--allow-newer-schema", action="store_true")
    parser.add_argument("--commit", help="build commit fallback when deployed metadata is unavailable")
    args = parser.parse_args()
    try:
        with step("local prerequisites"):
            root = Path.home() / "release-snapshots"
            root.mkdir(mode=0o700, exist_ok=True)
            if root.is_symlink():
                raise SafeReleaseError("snapshot root must not be a symlink")
            root.chmod(0o700)
        with step("release lock"), (root / ".lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            with step(args.action):
                if args.action == "snapshot":
                    if args.apply or args.allow_newer_schema or args.target != "latest":
                        raise SafeReleaseError("snapshot accepts no rollback arguments")
                    snapshot(root, args.commit)
                else:
                    if args.commit is not None:
                        raise SafeReleaseError("commit fallback is only accepted for snapshot")
                    rollback(root, args.target, args.apply, args.allow_newer_schema)
    except Exception as error:
        print(f"Release failed at {getattr(error, 'release_step', 'local prerequisites')}: "
              f"{type(error).__name__}: {failure_message(error)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
