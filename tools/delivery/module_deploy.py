#!/usr/bin/env python3
"""Fenced, module-selective replacement. Run only by an authorized release operator."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

TARGETS = {"service": "service", "worker": "worker", "policy": "policy",
           "configuration": "configuration", "compliance": "compliance",
           "packet-analysis": "packet-analysis"}
ROLES = {"worker": "general", "policy": "policy", "packet-analysis": "packet_analysis"}
POLICY_TYPES = ("cp_policy_collect", "pan_policy_collect")
# V154: modules that own themselves with no general fallback. The general worker has no executor for their job
# types, so no release path ever hands them to it; without their pod, their jobs wait in REQUESTED.
NEVER_GENERAL = ("packet_analysis",)
# Dedicated modules whose drain barrier covers exactly their own job types.
DEDICATED = {"policy": "policy", "packet-analysis": "packet_analysis"}


class Blocked(RuntimeError):
    def __init__(self, message, continuable=False):
        super().__init__(message)
        self.continuable = continuable


def validate_targets(value):
    targets = value.replace(",", " ").split()
    if not targets or len(set(targets)) != len(targets) or any(t not in TARGETS for t in targets):
        raise Blocked("invalid or duplicate deployment targets")
    return targets


def run(*args, input=None, timeout=660):
    try:
        result = subprocess.run(args, input=input, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise Blocked("release command unavailable or timed out") from None
    if result.returncode:
        detail = "operational output withheld"
        if args[0] == "kubectl":
            # Keep lightweight CLI validation independent of the preview module.
            sys.path.insert(0, str(REPO / "tools/e2e"))
            from hosta_preview_e2e import masked_error
            detail = masked_error(result.stderr)
        raise Blocked("release command failed (exit " + str(result.returncode) + "): " + detail)
    return result.stdout.strip()


def query(sql):
    command = 'psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -Atc ' + shlex.quote(sql)
    return run("kubectl", "-n", "ui2", "exec", "ui2-db-0", "--", "sh", "-c", command)


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def audit(snapshot):
    return ("select set_config('app.actor_fingerprint','system:release',true);"
            "select set_config('app.action_id','module_release',true);"
            "select set_config('app.correlation_run_id'," + literal(snapshot) + ",true);")


def request_drain(target, snapshot):
    role = ROLES[target]
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
                   "update module_runtime_control set drain_requested=true,drain_generation=drain_generation+1,"
                   "drain_ack_at=case when owner_instance is null then now() else null end,drain_ack_generation=case when owner_instance is null then drain_generation+1 else null end where module=" + literal(role) +
                   " returning drain_generation;commit;")
    generations = [line for line in result.splitlines() if line.isdigit()]
    if len(generations) != 1:
        raise Blocked("drain generation unavailable; compatible runtime bootstrap required")
    return int(generations[0])


def barrier_conditions(target, generation):
    """The drain barrier's conditions as (name, SQL boolean), evaluated on the target's module_runtime_control row."""
    role = ROLES[target]
    # Ownership and exact capability mapping cover current and legacy rows without pod-name guesses.
    filter_sql = ("ui2_job_module(j.capability_id)=" + literal(DEDICATED[target]) if target in DEDICATED else
                  "(ui2_job_module(j.capability_id) in (select module from module_runtime_control where effective_owner='general') or module_runtime_control.owner_instance is null)")
    admission_scope = ("(owner_role='policy' or purpose_class='POLICY')" if target == "policy"
                       else "owner_role=" + literal(role))
    return (
        ("drain_ack", "(drain_requested and drain_generation=" + str(generation) +
         " and drain_ack_generation=drain_generation and drain_ack_at is not null)"),
        ("owner_live", "(owner_instance is null or owner_heartbeat_at>now()-interval '60 seconds')"),
        ("busy_jobs", "select count(*) from jobs j where j.state in ('CLAIMED','EXECUTING') and (" + filter_sql + ")"),
        ("admissions", "select count(*) from endpoint_admission where " + admission_scope +
         " and state in ('LEASED','QUARANTINED')"),
        ("task_leases", "select count(*) from runtime_task_lease where owner_role=" + literal(role) +
         " and state='LEASED'"),
        ("quarantined_task_leases", "select count(*) from runtime_task_lease where owner_role=" + literal(role) +
         " and state='QUARANTINED'"),
    )


def barrier(target, generation, why=None):
    """True when the drain barrier holds. With `why` (a list) a false barrier appends which conditions hold it:
    flags and counts only, never an instance, endpoint, job or task identity."""
    role = ROLES[target]
    conditions = barrier_conditions(target, generation)
    holds = " and ".join(sql if not sql.startswith("select") else "not exists(" + sql.replace("select count(*)", "select 1", 1) + ")"
                         for _, sql in conditions)
    explain = " || ' ' || ".join(literal(name + "=") + " || (" + (sql if not sql.startswith("select") else "(" + sql + ")") + ")::text"
                                 for name, sql in conditions)
    rows = " from module_runtime_control where module=" + literal(role)
    # A second select of counts in the same transaction; its line is never "t"/"f", so the boolean parse is unchanged.
    sql = "select " + holds + rows + ";select 'barrier ' || " + explain + rows
    result = query("begin;" + audit("quarantine-drain") +
                   "select ui2_release_orphan_quarantines();" + sql + ";commit;")
    lines = result.splitlines()
    ready = [line for line in lines if line in ("t", "f")] == ["t"]
    if why is not None and not ready:
        why.extend(line for line in lines if line.startswith("barrier "))
    return ready


def wait_drain(target, generation, limit, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + limit
    while True:
        why = []
        if barrier(target, generation, why):
            return
        if clock() >= deadline:
            raise Blocked("bounded drain expired; old digest and audited drain retained"
                          + ("; " + why[-1] if why else ""), continuable=True)
        print("Module waiting: " + target + "; generation=" + str(generation) +
              ("; " + why[-1] if why else "; barrier state unavailable"), flush=True)
        sleep(min(10, max(0, deadline - clock())))


def clear_drain(target, generation, snapshot):
    role = ROLES[target]
    query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null,"
          "owner_instance=null,owner_heartbeat_at=null where module=" + literal(role) +
          " and drain_generation=" + str(generation) + ";commit;")


def module_e2e(commit=None):
    args = ("--commit", commit) if commit else ()
    digest = run("python3", str(REPO / "tools/e2e/hosta_e2e_image.py"), "ensure", *args)
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
        raise Blocked("masked e2e runner digest unavailable")
    text = (REPO / "deploy/ui2/70-e2e-job.yaml").read_text()
    jobs = [doc for doc in text.split("\n---\n") if re.search(r"^kind: Job$", doc, re.M)]
    if len(jobs) != 1:
        raise Blocked("masked e2e template unavailable")
    job = jobs[0].replace("nexus-ui2-e2e:SET_AT_DEPLOY", "nexus-ui2-e2e@" + digest).replace("  suspend: true\n", "  suspend: false\n", 1)
    run("kubectl", "-n", "ui2", "delete", "job", "ui2-e2e", "--ignore-not-found")
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=job)
    run("kubectl", "-n", "ui2", "wait", "--for=condition=Complete", "job/ui2-e2e", "--timeout=900s", timeout=960)
    if run("kubectl", "-n", "ui2", "get", "job/ui2-e2e", "-o", "jsonpath={.status.succeeded}") != "1":
        raise Blocked("masked e2e failed; further replacement stopped")


def prepare_policy_public_trust():
    config = json.loads(run("kubectl", "-n", "ui2", "get", "configmap", "corp-ca", "--ignore-not-found", "-o", "json") or "null")
    if config is None:
        config = json.loads(run("kubectl", "-n", "ui2-build", "get", "configmap", "corp-ca", "-o", "json"))
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=json.dumps({"apiVersion": "v1", "kind": "ConfigMap",
            "metadata": {"name": "corp-ca", "namespace": "ui2"}, "data": config.get("data", {})}))


def ensure_policy(image):
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-policy",
                   "--ignore-not-found", "-o", "json")
    if existing:
        return json.loads(existing)["spec"]["template"]["spec"]["containers"][0]["image"]
    # Apply the ServiceAccount and NetworkPolicy together with the disabled Deployment.
    prepare_policy_public_trust()
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_manifest("policy", image))
    return None


IMMUTABLE_IMAGE = r".+@sha256:[a-f0-9]{64}"
PACKET_ANALYSIS_MANIFEST = "deploy/ui2/58-packet-analysis-deployment.yaml"


# Deployments whose committed manifest is rendered with the candidate image, diffed before the drain and applied after
# it on every forward release, so env, mounts and resources ship together with the code that requires them
# (2026-10-18: the policy pod crashed on the old manifest because a release only swapped its image). The worker is not
# here: its JAVA_TOOL_OPTIONS carry a fallback flag the release adds at run time (see replace_image).
SYNCED_MANIFESTS = {"service": "deploy/ui2/50-service-deployment.yaml",
                    "configuration": "deploy/ui2/54-configuration-deployment.yaml",
                    "compliance": "deploy/ui2/55-compliance-deployment.yaml",
                    "policy": "deploy/ui2/57-policy-deployment.yaml"}


def render_manifest(target, image, replicas=None):
    """A target's committed manifest set with the candidate image; exactly one workload image line.

    The manifest's replica count is only a first-release value (the policy deployment is committed at 0 and the
    release scales it). With `replicas` the Deployment's own `replicas:` line is set to that live count, so applying
    the manifest never changes a running replica count; client-side apply would otherwise reset it to the committed
    value, or drop the field (default 1) when the manifest lacks it."""
    manifest = (REPO / SYNCED_MANIFESTS[target]).read_text()
    manifest, count = re.subn(r"(?m)^(\s*image: )\S+$", lambda match: match[1] + image, manifest)
    if count != 1:
        raise Blocked(target + " template must contain exactly one workload image")
    if replicas is not None:
        manifest, count = re.subn(r"(?m)^  replicas: \d+$", "  replicas: " + str(replicas), manifest)
        if count != 1:
            raise Blocked(target + " template must contain exactly one replica count")
    return manifest


def live_replicas(target):
    value = run("kubectl", "-n", "ui2", "get", "deployment/ui2-" + target, "-o", "jsonpath={.spec.replicas}")
    if not value.isdigit():
        raise Blocked("live replica count of " + target + " unavailable")
    return int(value)


def check_synced_manifest(target, image):
    """Read-only, before any drain: does the candidate manifest differ from the cluster? Applied after the drain."""
    print(target + " manifest: " + ("differs from the cluster; applied after the drain"
                                    if kubectl_diff(render_manifest(target, image, live_replicas(target)))
                                    else "already in sync"), flush=True)


def render_packet_analysis(image):
    """The engine's manifest set with the candidate engine image; exactly one workload image line."""
    manifest = (REPO / PACKET_ANALYSIS_MANIFEST).read_text()
    manifest, count = re.subn(r"(?m)^(\s*image: )\S+$", lambda match: match[1] + image, manifest)
    if count != 1:
        raise Blocked("packet-analysis template must contain exactly one workload image")
    return manifest


def kubectl_diff(manifest):
    """Server-side diff of a manifest against the cluster: True when it differs, False when in sync.

    `kubectl diff` exits 0 for no difference, 1 for a difference and above 1 for an error. Read-only: the diff is a
    dry run, and --force-conflicts only stops fields last written by another field manager (kubectl set image,
    scale) from failing it."""
    try:
        result = subprocess.run(("kubectl", "-n", "ui2", "diff", "--server-side", "--force-conflicts", "-f", "-"), input=manifest,
                                capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        raise Blocked("release command unavailable or timed out") from None
    if result.returncode > 1:
        sys.path.insert(0, str(REPO / "tools/e2e"))
        from hosta_preview_e2e import masked_error
        raise Blocked("release command failed (exit " + str(result.returncode) + "): " + masked_error(result.stderr))
    return result.returncode == 1


def ensure_packet_analysis(image, check_manifest=True):
    """First release of the analysis worker: apply its volume claim and its manifest set with the candidate image.

    Later releases change nothing here (the pod must be drained before it restarts); they only check, read-only,
    that the candidate manifest is acceptable to the cluster. replace_image applies it after the drain."""
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-packet-analysis",
                   "--ignore-not-found", "-o", "json")
    if existing:
        if check_manifest:
            manifest = render_packet_analysis(image)
            print("packet-analysis manifest: " + ("differs from the cluster; applied after the drain" if kubectl_diff(manifest)
                                                  else "already in sync"), flush=True)
        return json.loads(existing)["spec"]["template"]["spec"]["containers"][0]["image"]
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=(REPO / "deploy/ui2/36-packet-analysis-pvc.yaml").read_text())
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_packet_analysis(image))
    return None


def wait_heartbeat(target, limit=120, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + limit
    while query("select owner_instance is not null and owner_heartbeat_at>now()-interval '60 seconds' "
                "from module_runtime_control where module=" + literal(ROLES[target])) != "t":
        if clock() >= deadline:
            raise Blocked("ready pod has no live module heartbeat")
        sleep(2)


def handover_policy(generation, snapshot, authorization):
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set fallback_enabled=false,effective_owner='policy',generation=generation+1,"
          "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,"
          "last_authorization_ref=" + literal(authorization) + ",last_reason='ready policy cutover',last_snapshot_id=" + literal(snapshot) +
          " where module='policy' and drain_requested and drain_generation=" + str(generation) +
          " and owner_instance is not null and owner_heartbeat_at>now()-interval '60 seconds' "
          "and drain_ack_generation=drain_generation returning generation;commit;")
    if not any(line.isdigit() for line in result.splitlines()):
        raise Blocked("policy handover fence or heartbeat changed")


def reset_to_general(snapshot, module, generation, authorization="automatic-rollout-recovery"):
    if module in NEVER_GENERAL:
        raise Blocked(module + " is never handed to the general worker; its jobs wait for its own pod")
    # Caller has fenced and stopped the dedicated owner. Never discard permits.
    permit_scope = "owner_role=" + literal(module)
    if module == "policy":
        permit_scope = "(" + permit_scope + " or purpose_class='POLICY')"
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set effective_owner='general',fallback_enabled=true,generation=generation+1,"
          "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,owner_instance=null,owner_heartbeat_at=null,"
          "last_reason='release recovery',last_snapshot_id=" + literal(snapshot) +
          ",last_authorization_ref=" + literal(authorization) +
          " where module=" + literal(module) + " and drain_requested and drain_generation=" + str(generation) +
          " and not exists(select 1 from jobs where state in ('CLAIMED','EXECUTING') and ui2_job_module(capability_id)=" + literal(module) + ")"
          " and not exists(select 1 from endpoint_admission where state in ('LEASED','QUARANTINED') and " + permit_scope + ")"
          " and not exists(select 1 from runtime_task_lease where state in ('LEASED','QUARANTINED') and owner_role=" + literal(module) + ")"
          " returning module;commit;")
    if module not in result.splitlines():
        raise Blocked("recovery retained ownership fence: generation changed or active or uncertain work")


def replace_image(target, image, sync_manifest=False):
    if target == "packet-analysis" and sync_manifest:
        # One pod-template update installs the engine image together with the committed env and resources
        # (memory plan, JAVA_TOOL_OPTIONS), so manifest changes ship with the code. Called only after the drain.
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_packet_analysis(image))
        return
    if target in SYNCED_MANIFESTS and sync_manifest:
        # One pod-template update installs the image together with the committed env, mounts and resources. The
        # live replica count is kept (the policy release scales the pod itself). Called only after the drain.
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_manifest(target, image, live_replicas(target)))
        return
    if target != "worker":
        run("kubectl", "-n", "ui2", "set", "image", "deployment/ui2-" + target, TARGETS[target] + "=" + image)
        return
    workload = json.loads(run("kubectl", "-n", "ui2", "get", "deployment/ui2-worker", "-o", "json"))
    worker = next(c for c in workload["spec"]["template"]["spec"]["containers"] if c["name"] == "worker")
    options = next((e for e in worker.get("env", []) if e["name"] == "JAVA_TOOL_OPTIONS"), {})
    if "valueFrom" in options:
        raise Blocked("general fallback requires explicitly configured JVM options")
    value = re.sub(r"(?<!\S)-Dui2.worker.claim-policy-fallback=\S+", "", options.get("value", "")).strip()
    value = (value + " -Dui2.worker.claim-policy-fallback=true").strip()
    # One pod-template update installs the image and fallback capability after the drain.
    # Strategic merge keys env by name, preserving all other environment entries.
    patch = {"spec": {"template": {"spec": {"containers": [{"name": "worker", "image": image,
             "env": [{"name": "JAVA_TOOL_OPTIONS", "value": value}]}]}}}}
    run("kubectl", "-n", "ui2", "patch", "deployment/ui2-worker", "--type=strategic", "-p", json.dumps(patch))


def replace(target, image, snapshot, limit=14400, commit=None, authorization=None, rollback=False):
    if target == "policy" and query("select (owner_heartbeat_at>now()-interval '60 seconds') from module_runtime_control where module='general'") != "t":
        raise Blocked("compatible general worker bootstrap required before policy cutover")
    fallback = target == "policy" and query("select fallback_enabled from module_runtime_control where module='policy'") == "t"
    if fallback and (not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization)):
        raise Blocked("policy ownership transfer requires an opaque PO authorization reference")
    old = (ensure_policy(image) if target == "policy" else ensure_packet_analysis(image, not rollback) if target == "packet-analysis"
           else run("kubectl", "-n", "ui2", "get", "deployment/ui2-" + target,
                    "-o", "jsonpath={.spec.template.spec.containers[0].image}"))
    if old is not None and not re.fullmatch(IMMUTABLE_IMAGE, old):
        raise Blocked("old workload digest is not immutable")
    if target in SYNCED_MANIFESTS and old is not None and not rollback:
        check_synced_manifest(target, image)
    generation = None
    # A first release (ensure_packet_analysis created the deployment with the candidate image: old is None) has no
    # old owner to drain; draining it would wait for the new pod itself to stop (2026-10-16: drain retained).
    if target in ROLES and not (target == "packet-analysis" and old is None):
        generation = request_drain(target, snapshot)
        wait_drain(target, generation, limit)
    old_pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
    old_uids = {pod["metadata"]["uid"] for pod in old_pods["items"]}
    try:
        # A rollback restores the recorded digest only; the committed manifest belongs to the forward release.
        replace_image(target, image, sync_manifest=(target == "packet-analysis" or target in SYNCED_MANIFESTS)
                      and old is not None and not rollback)
        if generation is not None:
            deadline = time.monotonic() + 900
            while old_uids:
                pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
                if not old_uids.intersection(pod["metadata"]["uid"] for pod in pods["items"]):
                    break
                if time.monotonic() >= deadline:
                    raise Blocked("old owner did not stop; drain retained")
                time.sleep(5)
            # Forget the old instance, retaining the policy fence until a new heartbeat.
            if target == "policy":
                query("begin;" + audit(snapshot) + "update module_runtime_control set owner_instance=null,owner_heartbeat_at=null "
                      "where module='policy' and drain_generation=" + str(generation) + ";commit;")
            else:
                clear_drain(target, generation, snapshot)
        if target == "policy":
            run("kubectl", "-n", "ui2", "scale", "deployment/ui2-policy", "--replicas=1")
        run("kubectl", "-n", "ui2", "rollout", "status", "deployment/ui2-" + target, "--timeout=600s")
        if target in ROLES:
            wait_heartbeat(target)
        if target == "policy":
            handover_policy(generation, snapshot, authorization or "snapshot-rollback")
        module_e2e(commit)
    except Blocked:
        if target == "policy":
            # Fence before stopping, then recover fallback only after zero-work evidence.
            recovery = request_drain(target, snapshot)
            run("kubectl", "-n", "ui2", "scale", "deployment/ui2-policy", "--replicas=0")
            run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", "app.kubernetes.io/component=policy", "--timeout=900s")
            reset_to_general(snapshot, "policy", recovery, authorization or "automatic-rollout-recovery")
        raise
    print(json.dumps({"module": target, "e2e": "PASS", "old_digest": old.rsplit("@", 1)[1] if old else None,
                      "new_digest": image.rsplit("@", 1)[1], "ready": True, "drain_generation": generation}), flush=True)


def rollback_image(manifest, target):
    if not manifest.get("runtime", {}).get("compatible_runtime"):
        raise Blocked("pre-admission snapshot requires a separately controlled full rollback")
    images = [item["image"] for item in manifest["images"].get("ui2", [])
              if item["kind"] == "Deployment" and item["workload"] == "ui2-" + target
              and item["container"] == TARGETS[target]]
    if len(images) != 1:
        raise Blocked("module digest absent or ambiguous in snapshot")
    return images[0]


def return_policy_to_general(snapshot, authorization):
    if not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization):
        raise Blocked("an opaque PO authorization reference is required")
    worker = json.loads(run("kubectl", "-n", "ui2", "get", "deployment/ui2-worker", "-o", "json"))
    capable = any("-Dui2.worker.claim-policy-fallback=true" in entry.get("value", "")
                  for container in worker["spec"]["template"]["spec"]["containers"] for entry in container.get("env", [])
                  if entry["name"] == "JAVA_TOOL_OPTIONS")
    if not capable or query("select owner_heartbeat_at>now()-interval '60 seconds' from module_runtime_control where module='general'") != "t":
        raise Blocked("compatible general owner must already have explicit policy fallback capability")
    generation = request_drain("policy", snapshot)
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-policy", "--ignore-not-found", "-o", "name")
    if existing:
        run("kubectl", "-n", "ui2", "scale", "deployment/ui2-policy", "--replicas=0")
    run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", "app.kubernetes.io/component=policy", "--timeout=900s")
    reset_to_general(snapshot, "policy", generation, authorization)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--targets")
    selection.add_argument("--validate-targets", metavar="TARGETS")
    parser.add_argument("--image")
    parser.add_argument("--packet-analysis-image", help="digest-pinned Packet Analysis engine image (main image plus "
                        "tshark); required when packet-analysis is a target, never defaults to --image")
    parser.add_argument("--commit")
    parser.add_argument("--rollback", action="store_true")
    parser.add_argument("--allow-newer-schema", action="store_true")
    parser.add_argument("--return-policy-to-general", action="store_true")
    parser.add_argument("--authorization-ref")
    parser.add_argument("--snapshot")
    parser.add_argument("--wait-limit", type=int, default=14400)
    args = parser.parse_args()
    if args.validate_targets is None and not args.snapshot:
        parser.error("--snapshot is required for deployment or rollback")
    try:
        if args.validate_targets is not None:
            validate_targets(args.validate_targets)
            return
        targets = validate_targets(args.targets)
        if not args.rollback and not args.return_policy_to_general and (args.image is None or not re.fullmatch(IMMUTABLE_IMAGE, args.image)):
            raise Blocked("candidate image must be immutable")
        if args.packet_analysis_image is not None and not re.fullmatch(IMMUTABLE_IMAGE, args.packet_analysis_image):
            raise Blocked("packet-analysis image must be immutable")
        if not args.rollback and not args.return_policy_to_general and "packet-analysis" in targets:
            # The engine needs tshark, which the main service image does not have: fail closed, never fall back.
            if args.packet_analysis_image is None:
                raise Blocked("packet-analysis target requires --packet-analysis-image; the main image has no tshark")
            if args.packet_analysis_image == args.image:
                raise Blocked("packet-analysis image must differ from the main service image")
        from release_snapshot import load_snapshot
        directory = Path(args.snapshot).expanduser().resolve()
        manifest = load_snapshot(directory)  # Complete checksum verification before any drain/mutation.
        if args.rollback and not manifest.get("runtime", {}).get("compatible_runtime"):
            from release_snapshot import rollback
            rollback(directory.parent, str(directory), True, args.allow_newer_schema)
            return
        if args.return_policy_to_general:
            if targets != ["policy"] or args.image or args.rollback:
                raise Blocked("fallback return accepts only the policy target")
            if not manifest.get("runtime", {}).get("compatible_runtime"):
                raise Blocked("compatible snapshot required for fallback")
            return_policy_to_general(directory.name, args.authorization_ref)
            return
        if "policy" in targets:
            prepare_policy_public_trust()
        failures = []
        for target in targets:
            try:
                image = (rollback_image(manifest, target) if args.rollback else
                         args.packet_analysis_image if target == "packet-analysis" else args.image)
                replace(target, image, directory.name, args.wait_limit, args.commit, args.authorization_ref,
                        rollback=args.rollback)
            except Blocked as error:
                failures.append(target)
                print(json.dumps({"module": target, "status": "BLOCKED", "reason": str(error)}), flush=True)
                if not error.continuable:
                    break
        if failures:
            raise SystemExit(5)
    except Blocked as error:
        print("BLOCKED: " + str(error))
        raise SystemExit(5)


if __name__ == "__main__":
    main()
