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
           "configuration": "configuration", "compliance": "compliance"}
ROLES = {"worker": "general", "policy": "policy"}
POLICY_TYPES = ("cp_policy_collect", "pan_policy_collect")


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
        raise Blocked("release command failed; operational output withheld")
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


def barrier(target, generation):
    role = ROLES[target]
    # Ownership and exact capability mapping cover current and legacy rows without pod-name guesses.
    filter_sql = ("ui2_job_module(j.capability_id)='policy'" if target == "policy" else
                  "(ui2_job_module(j.capability_id) in (select module from module_runtime_control where effective_owner='general') or module_runtime_control.owner_instance is null)")
    sql = ("select (drain_requested and drain_generation=" + str(generation) +
           " and drain_ack_generation=drain_generation and drain_ack_at is not null "
           "and (owner_instance is null or owner_heartbeat_at>now()-interval '60 seconds') "
           "and not exists(select 1 from jobs j where j.state in ('CLAIMED','EXECUTING') and (" + filter_sql + ")) "
           "and not exists(select 1 from endpoint_admission where owner_role=" + literal(role) +
           " and state in ('LEASED','QUARANTINED')) "
           "and not exists(select 1 from runtime_task_lease where owner_role=" + literal(role) +
           " and state in ('LEASED','QUARANTINED'))) from module_runtime_control where module=" + literal(role))
    return query(sql) == "t"


def wait_drain(target, generation, limit, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + limit
    while not barrier(target, generation):
        if clock() >= deadline:
            raise Blocked("bounded drain expired; old digest and audited drain retained", continuable=True)
        print("Module waiting: " + target + "; generation=" + str(generation), flush=True)
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


def replace(target, image, snapshot, limit=14400, commit=None, authorization=None):
    if target == "policy" and query("select (owner_heartbeat_at>now()-interval '60 seconds') from module_runtime_control where module='general'") != "t":
        raise Blocked("compatible general worker bootstrap required before policy cutover")
    fallback = target == "policy" and query("select fallback_enabled from module_runtime_control where module='policy'") == "t"
    if fallback and (not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization)):
        raise Blocked("policy ownership transfer requires an opaque PO authorization reference")
    old = run("kubectl", "-n", "ui2", "get", "deployment/ui2-" + target,
              "-o", "jsonpath={.spec.template.spec.containers[0].image}")
    if not re.fullmatch(r".+@sha256:[a-f0-9]{64}", old):
        raise Blocked("old workload digest is not immutable")
    generation = None
    if target in ROLES:
        generation = request_drain(target, snapshot)
        wait_drain(target, generation, limit)
    # A drain timeout reaches neither set image nor drain clear.
    old_pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
    old_uids = {pod["metadata"]["uid"] for pod in old_pods["items"]}
    run("kubectl", "-n", "ui2", "set", "image", "deployment/ui2-" + target, TARGETS[target] + "=" + image)
    if generation is not None:
        # Recreate must stop the acknowledged old owner before the new owner can claim.
        # Clearing before this barrier would reopen old claims against the pending replacement.
        deadline = time.monotonic() + 900
        while old_uids:
            pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
            if not old_uids.intersection(pod["metadata"]["uid"] for pod in pods["items"]):
                break
            if time.monotonic() >= deadline:
                raise Blocked("old owner did not stop; drain retained")
            time.sleep(5)
        if fallback:
            query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
                  "update module_runtime_control set fallback_enabled=false,effective_owner='policy',generation=generation+1,"
                  "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,owner_instance=null,owner_heartbeat_at=null,"
                  "last_authorization_ref=" + literal(authorization) + ",last_reason='policy cutover',last_snapshot_id=" + literal(snapshot) +
                  " where module='policy' and drain_generation=" + str(generation) + ";commit;")
        else:
            clear_drain(target, generation, snapshot)
    if target == "policy":
        run("kubectl", "-n", "ui2", "scale", "deployment/ui2-policy", "--replicas=1")
    run("kubectl", "-n", "ui2", "rollout", "status", "deployment/ui2-" + target, "--timeout=600s")
    module_e2e(commit)
    print(json.dumps({"module": target, "e2e": "PASS", "old_digest": old.rsplit("@", 1)[1],
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
    wait_drain("policy", generation, 14400)
    run("kubectl", "-n", "ui2", "scale", "deployment/ui2-policy", "--replicas=0")
    run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", "app.kubernetes.io/component=policy", "--timeout=900s")
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
                   "update module_runtime_control set effective_owner='general',fallback_enabled=true,generation=generation+1,"
                   "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,owner_instance=null,owner_heartbeat_at=null,"
                   "last_authorization_ref=" + literal(authorization) + ",last_reason='snapshot policy rollback',last_snapshot_id=" + literal(snapshot) +
                   " where module='policy' and drain_requested and drain_generation=" + str(generation) +
                   " and drain_ack_generation=drain_generation returning generation;commit;")
    if not any(line.isdigit() for line in result.splitlines()):
        raise Blocked("ownership generation changed; fallback was not enabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--targets")
    selection.add_argument("--validate-targets", metavar="TARGETS")
    parser.add_argument("--image")
    parser.add_argument("--commit")
    parser.add_argument("--rollback", action="store_true")
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
        if not args.rollback and not args.return_policy_to_general and (args.image is None or not re.fullmatch(r".+@sha256:[a-f0-9]{64}", args.image)):
            raise Blocked("candidate image must be immutable")
        from release_snapshot import load_snapshot
        directory = Path(args.snapshot).expanduser().resolve()
        manifest = load_snapshot(directory)  # Complete checksum verification before any drain/mutation.
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
                replace(target, rollback_image(manifest, target) if args.rollback else args.image, directory.name, args.wait_limit, args.commit, args.authorization_ref)
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
