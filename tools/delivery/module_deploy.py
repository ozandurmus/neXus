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
import threading
import time
from contextlib import contextmanager

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

TARGETS = {"service": "service", "worker": "worker", "policy": "policy", "backup": "backup",
           "configuration": "configuration", "compliance": "compliance",
           "script-runner": "script-runner",
           "packet-analysis": "packet-analysis"}
ROLES = {"worker": "general", "policy": "policy", "backup": "backup",
         "script-runner": "script_runner", "packet-analysis": "packet_analysis"}
POLICY_TYPES = ("cp_policy_collect", "pan_policy_collect")
# V154: modules that own themselves with no general fallback. The general worker has no executor for their job
# types, so no release path ever hands them to it; without their pod, their jobs wait in REQUESTED.
NEVER_GENERAL = ("packet_analysis",)
SELF_OWNED = ("packet_analysis", "script_runner")
# Tasks ModuleRuntimeRepository.taskModule routes to the backup module (keep in step with it; a test compares).
BACKUP_MODULE_TASKS = ("OrphanArtefactPurger.runOnce", "RetentionPruningService.prune")
# Dedicated modules whose drain barrier covers exactly their own job types.
DEDICATED = {"policy": "policy", "backup": "backup",
             "script-runner": "script_runner", "packet-analysis": "packet_analysis"}
# Targets with NO drain barrier in this tool (not in ROLES): HTTP owners whose requests drain on SIGTERM within the pod
# grace period. Consecutive ones are rolled out at the same time and share one module e2e afterwards
# (MODULE_POD_RUNTIME_CONTRACT.md section 3: "Deploy waits ONLY for its module ... unrelated modules retain their digest
# and continue"). A target with a drain barrier (worker, policy, packet-analysis) is never in this set, and neither is
# the service, which every module e2e exercises.
CONCURRENT_SAFE = ("configuration", "compliance")
# How often the drain barrier and the old-pod exit are re-checked. The barrier itself (drain ack AND zero jobs, permits
# and task leases) is unchanged; only the wait between looks is shorter (was 10 s and 5 s). Each look is one short
# read-only transaction.
DRAIN_POLL_S = 2
OLD_POD_POLL_S = 2
# Module e2e cadence. "each" (default, unchanged): one module e2e after every step. "edges": after the FIRST step (the
# service, which every module e2e exercises) and after the LAST step only; the steps between roll out and are checked by
# rollout status plus the heartbeat/handover checks, and the e2e of the last step covers the finished release. The ship's
# own production e2e after the deploy is unaffected. Measured 2026-11-17: about 30 s per skipped e2e, four per full ship.
MODULE_E2E_MODES = ("each", "edges")
# Deploy-freeze Phase 1 (PO 2026-11-23). Job types whose running jobs yield to a drain within seconds (policy before
# every command; inventory, configuration and discovery reads at a unit boundary) or are bounded reads. While a drain
# waits ONLY on these, the wait is capped at YIELD_WAIT_LIMIT_S instead of --wait-limit; one job of any other type in
# flight (backup, MDS export, failover, script, diagnostics, support bundle, ...) keeps the long limit. An allowlist:
# an unknown job type keeps the long limit.
YIELDABLE_JOB_TYPES = {
    "policy": POLICY_TYPES,
    "worker": ("cp_inventory_collect", "pan_inventory_collect", "https_inventory_collect", "asa_inventory_collect",
               "fgt_inventory_collect",
               "cp_configuration_collect", "pan_configuration_collect", "fgt_configuration_collect",
               "asa_configuration_collect", "proxysg_configuration_collect",
               "cp_discovery_enumerate", "pan_discovery_enumerate", "rdw_discovery_enumerate",
               "fmg_discovery_enumerate", "bcmc_discovery_enumerate"),
}
YIELD_WAIT_LIMIT_S = 900
_print_lock = threading.Lock()


@contextmanager
def phase(target, name, clock=time.monotonic):
    """Print `TIMING module_<target>_<name> <seconds>` (hosta_deploy.sh forwards these lines), so a slow module shows
    which of its steps -- drain wait, replacement, rollout, heartbeat, e2e -- took the time."""
    started = clock()
    try:
        yield
    finally:
        with _print_lock:
            print("TIMING module_%s_%s %d" % (target.replace("-", "_"), name, round(clock() - started)), flush=True)


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


# When a QUARANTINED admission's job `t` no longer holds the drain. COMPLETED and FAILED are outcomes the worker
# recorded itself. CANCELLED is not: the reconciler moves a cancel-requested EXECUTING job whose lease expired to
# CANCELLED without asking whether a device write began, and the stalled worker thread may still be inside that device
# session (SECURITY_REVIEW_2026_11_05 M-1). Fail closed: a CANCELLED job releases the drain only when its durable
# attempt record proves no write began. Every device-contacting write path commits a pre-contact job_step_attempt row
# first, fenced to an owner-valid EXECUTING job, so after CANCELLED no new attempt can appear. A job releases only if
# none of its attempts, in any lease epoch, crossed a mutation boundary or carries a non-read action class. A
# write-class attempt is counted even when uncrossed, because the backup path records CLASS 1 without ever flipping the
# flag. Every other state (OUTCOME_UNKNOWN, EXECUTING, a missing job, a null job_id) still holds, as before.
ENDED_WITHOUT_WRITE_SQL = (
    "(t.state in ('COMPLETED','FAILED') or (t.state='CANCELLED' and not exists(select 1 from job_step_attempt a"
    " where a.job_id=t.job_id and (a.mutation_boundary_crossed or a.action_class<>'read'))))")


def barrier_conditions(target, generation):
    """The drain barrier's conditions as (name, SQL boolean), evaluated on the target's module_runtime_control row."""
    role = ROLES[target]
    # Ownership and exact capability mapping cover current and legacy rows without pod-name guesses.
    filter_sql = ("ui2_job_module(j.capability_id)=" + literal(DEDICATED[target]) if target in DEDICATED else
                  "(ui2_job_module(j.capability_id) in (select module from module_runtime_control where effective_owner='general') or module_runtime_control.owner_instance is null)")
    admission_scope = ("(owner_role='policy' or purpose_class='POLICY')" if target == "policy"
                       else "(owner_role='backup' or purpose_class='BACKUP')" if target == "backup"
                       else "owner_role=" + literal(role))
    # Backup-module tasks run by the general worker while it is still effective owner hold their lease under
    # owner_role='general' (ModuleRuntimeRepository.taskModule); the handover must wait for them too.
    task_scope = ("(owner_role=" + literal(role) + " or task_key in (" + ",".join(literal(t) for t in BACKUP_MODULE_TASKS) + "))"
                  if target == "backup" else "owner_role=" + literal(role))
    return (
        ("drain_ack", "(drain_requested and drain_generation=" + str(generation) +
         " and drain_ack_generation=drain_generation and drain_ack_at is not null)"),
        ("owner_live", "(owner_instance is null or owner_heartbeat_at>now()-interval '60 seconds')"),
        ("busy_jobs", "select count(*) from jobs j where j.state in ('CLAIMED','EXECUTING') and (" + filter_sql + ")"),
        # A quarantine left by a job that already ended holds no work; stopping the old pod releases it as
        # OWNER_GONE, so counting it here waits on itself (2026-11-04: a failed policy collection held a deploy).
        ("admissions", "select count(*) from endpoint_admission where " + admission_scope +
         " and (state='LEASED' or (state='QUARANTINED' and not exists(select 1 from jobs t where t.job_id="
         "endpoint_admission.job_id and " + ENDED_WITHOUT_WRITE_SQL + ")))"),
        ("task_leases", "select count(*) from runtime_task_lease where " + task_scope +
         " and state='LEASED'"),
        ("quarantined_task_leases", "select count(*) from runtime_task_lease where " + task_scope +
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


def _exists(count_sql):
    return "exists(" + count_sql.replace("select count(*)", "select 1", 1) + ")"


def quarantine_sql(target, generation):
    """SQL boolean (on the target's module_runtime_control row): a QUARANTINED admission that still holds the barrier
    (same rule as the barrier: one whose job ended without a write does not) or any QUARANTINED task lease."""
    conditions = dict(barrier_conditions(target, generation))
    admissions = conditions["admissions"].replace("(state='LEASED' or (state='QUARANTINED'", "((state='QUARANTINED'", 1)
    return "(" + _exists(admissions) + " or " + _exists(conditions["quarantined_task_leases"]) + ")"


def only_yieldable_work_holds(target, generation):
    """True only while the barrier is held by yield-capable jobs and nothing else (review D2): at least one job in flight,
    every in-flight job of a YIELDABLE_JOB_TYPES type, the drain acknowledged, the owner live, no task lease (leased or
    quarantined) and no quarantined admission. Flags only; no identity. Unreadable counts as false (long limit)."""
    c = dict(barrier_conditions(target, generation))
    allowed = ",".join(literal(t) for t in YIELDABLE_JOB_TYPES[target])
    sql = ("select " + c["drain_ack"] + " and " + c["owner_live"] + " and " + _exists(c["busy_jobs"])
           + " and not " + _exists(c["busy_jobs"] + " and j.capability_id <> all(array[" + allowed + "])")
           + " and not " + _exists(c["task_leases"]) + " and not " + quarantine_sql(target, generation)
           + " from module_runtime_control where module=" + literal(ROLES[target]))
    try:
        return query(sql) == "t"
    except Blocked:
        return False


def prior_drain_requested(target):
    """Whether the module was already drained (fenced) before this release asked; unreadable counts as yes."""
    try:
        return query("select drain_requested from module_runtime_control where module=" + literal(ROLES[target])) != "f"
    except Blocked:
        return True


def quarantine_holds(target, generation):
    try:
        return query("select " + quarantine_sql(target, generation) + " from module_runtime_control where module="
                     + literal(ROLES[target])) != "f"
    except Blocked:
        return True


def yield_wait_limit(target, limit):
    """The short wait for a yield-capable module, or None when it does not apply (no allowlist, disabled, or not shorter
    than the long limit)."""
    if target not in YIELDABLE_JOB_TYPES or not YIELD_WAIT_LIMIT_S or YIELD_WAIT_LIMIT_S >= limit:
        return None
    return YIELD_WAIT_LIMIT_S


def wait_drain(target, generation, limit, sleep=time.sleep, clock=time.monotonic):
    deadline = clock() + limit
    short = yield_wait_limit(target, limit)
    yield_since = None  # When "only yield-capable work holds the barrier" was first seen without a break (review D2).
    while True:
        why = []
        if barrier(target, generation, why):
            return
        if clock() >= deadline:
            raise Blocked("bounded drain expired; old digest retained"
                          + ("; " + why[-1] if why else ""), continuable=True)
        if short is not None:
            if not only_yieldable_work_holds(target, generation):
                yield_since = None
            elif yield_since is None:
                yield_since = clock()
            elif clock() - yield_since >= short:
                # Only yield-capable work has held the barrier for the whole short limit and did not yield: stop waiting
                # (claims are paused while a drain waits). The caller decides whether the drain may be lifted.
                raise Blocked("yield-capable drain did not finish within " + str(short) + " s; old digest retained"
                              + ("; " + why[-1] if why else ""), continuable=True)
        print("Module waiting: " + target + "; generation=" + str(generation) +
              ("; " + why[-1] if why else "; barrier state unavailable"), flush=True)
        sleep(min(DRAIN_POLL_S, max(0, deadline - clock())))


def cancel_general_drain(target, generation, snapshot):
    """Undo a drain requested before any ownership change. Only while the module is still on the general worker:
    nothing was handed over, so claims must resume at once (a stuck drain stops all backup claims, 2026-10-05 class).
    Never touches generation, owner or fallback; a dedicated owner keeps its fence."""
    role = ROLES[target]
    query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null"
          " where module=" + literal(role) + " and effective_owner='general' and drain_generation=" + str(generation) + ";commit;")


def cancel_drain(target, generation, snapshot):
    """Undo this release's drain after a bounded wait expired, before anything was replaced or handed over: the old pod
    keeps running and claims again at once, so a slow drain never leaves a module's collection paused. Never touches
    generation, owner, fallback or the owner instance; a newer drain (another drain_generation) is left alone."""
    role = ROLES[target]
    query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null"
          " where module=" + literal(role) + " and drain_generation=" + str(generation) + ";commit;")


def resume_dedicated(target, generation, snapshot):
    """Routine image replacement of a module that already owns itself (fallback off): the new pod heartbeats, then this
    release's drain is lifted. No generation bump and no owner/fallback change -- the barrier proved zero work and the
    old pod is gone, and the job-validity fence also requires owner_instance = the claiming pod. Only an explicit
    ownership transfer (handover_policy / handover_backup / reset_to_general) changes the generation."""
    role = ROLES[target]
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null"
          " where module=" + literal(role) + " and effective_owner=" + literal(role) + " and not fallback_enabled"
          " and drain_requested and drain_generation=" + str(generation) +
          " and owner_instance is not null and owner_heartbeat_at>now()-interval '60 seconds' returning module;commit;")
    if role not in result.splitlines():
        raise Blocked(target + " drain release fence or heartbeat changed")


def fence_dedicated(target, snapshot):
    """A routine replacement of a self-owned module failed after its new pod started. Stop new claims (a fresh drain)
    and keep everything else: owner, fallback, generation and the running pod. The module stays on its own pod, fenced
    and visible as a drained module, until the operator restores the previous digest with --rollback (which drains,
    replaces and lifts the drain the same routine way). It is never handed to the general worker here."""
    return request_drain(target, snapshot)


def clear_drain(target, generation, snapshot):
    role = ROLES[target]
    query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set drain_requested=false,drain_ack_at=null,drain_ack_generation=null,"
          "owner_instance=null,owner_heartbeat_at=null where module=" + literal(role) +
          " and drain_generation=" + str(generation) + ";commit;")


E2E_TIMEOUT_S = 900
E2E_POLL_S = 5


def wait_e2e_outcome(timeout=E2E_TIMEOUT_S, sleep=None, clock=None):
    """Wait for the e2e job to reach Complete OR Failed, whichever comes first; the overall timeout is unchanged.

    `kubectl wait --for=condition=Complete` alone leaves a failed job waiting for the whole timeout (a failed e2e held
    a release for 15 minutes). The Job's conditions are polled instead: returns "Complete", "Failed", or "Timeout"."""
    sleep, clock = sleep or time.sleep, clock or time.monotonic
    deadline = clock() + timeout
    while True:
        conditions = run("kubectl", "-n", "ui2", "get", "job/ui2-e2e", "-o",
                         "jsonpath={range .status.conditions[*]}{.type}={.status}{\"\\n\"}{end}").split()
        for outcome in ("Failed", "Complete"):
            if outcome + "=True" in conditions:
                print("Module e2e: " + outcome, flush=True)
                return outcome
        if clock() >= deadline:
            print("Module e2e: no Complete or Failed condition within " + str(timeout) + " s", flush=True)
            return "Timeout"
        sleep(E2E_POLL_S)


def report_failed_e2e_tests():
    """Print the failed tests of the e2e Job by title only and return the first 'spec:line title', or None.

    The log is read in memory and never printed or kept: only list-reporter failure headers whose spec path, line
    and title match the spec sources of this tree, and the summary counts, survive (tools/e2e/e2e_failure_titles.py).
    Anything unreadable adds nothing; the Failed outcome above stays the verdict."""
    try:
        sys.path.insert(0, str(REPO / "tools" / "e2e"))
        import e2e_failure_titles
        log = run("kubectl", "-n", "ui2", "logs", "-l", "job-name=ui2-e2e", "--tail=5000")
        lines = e2e_failure_titles.report_lines(log, REPO)
    except (ImportError, OSError, ValueError, Blocked):
        return None
    for line in lines:
        print(line, flush=True)
    return e2e_failure_titles.first_failed(lines)


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
    outcome = wait_e2e_outcome()
    named = report_failed_e2e_tests() if outcome != "Complete" else None
    if outcome != "Complete" or run("kubectl", "-n", "ui2", "get", "job/ui2-e2e", "-o", "jsonpath={.status.succeeded}") != "1":
        raise Blocked("masked e2e " + ("failed" if outcome == "Failed" else "did not complete")
                      + (" (first failed test: " + named + ")" if named else "") + "; further replacement stopped")


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
    manifest = render_manifest("policy", image)
    ensure_mounted_claims("policy", manifest)
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=manifest)
    return None


def ensure_backup(image):
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-backup",
                   "--ignore-not-found", "-o", "json")
    if existing:
        return json.loads(existing)["spec"]["template"]["spec"]["containers"][0]["image"]
    # Apply the ServiceAccount and NetworkPolicy together with the disabled Deployment.
    prepare_policy_public_trust()
    manifest = render_manifest("backup", image)
    ensure_mounted_claims("backup", manifest)
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=manifest)
    return None


# The script execution image (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b) item 8) of this release, from
# --script-exec-image; None keeps the broker's live value. The broker itself always runs the service image.
SCRIPT_EXEC_IMAGE = None
SCRIPT_EXEC_PLACEHOLDER = "set-by-module-deploy"


def live_script_exec_image():
    """The broker's current UI2_SCRIPT_EXEC_IMAGE, or None (no broker yet, or never set)."""
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-script-runner", "--ignore-not-found", "-o", "json")
    if not existing:
        return None
    for container in json.loads(existing)["spec"]["template"]["spec"]["containers"]:
        for entry in container.get("env", []):
            if entry.get("name") == "UI2_SCRIPT_EXEC_IMAGE" and re.fullmatch(IMMUTABLE_IMAGE, entry.get("value", "")):
                return entry["value"]
    return None


def script_exec_image(service_image):
    """This release's execution image: --script-exec-image, else the live one; never the service image."""
    exec_image = SCRIPT_EXEC_IMAGE or live_script_exec_image()
    if exec_image is None:
        raise Blocked("script-runner needs --script-exec-image (no execution image is set on the broker yet)")
    if not re.fullmatch(IMMUTABLE_IMAGE, exec_image):
        raise Blocked("script execution image must be immutable")
    if exec_image == service_image:
        raise Blocked("script execution image must differ from the main service image")
    return exec_image


def render_script_runner(image, exec_image, deployment=True):
    """The broker's manifest set: the Deployment with the service image and UI2_SCRIPT_EXEC_IMAGE set; with
    deployment=False only the other documents (service accounts, Role, RoleBinding, network policies)."""
    manifest = (REPO / SCRIPT_RUNNER_MANIFEST).read_text()
    manifest, count = re.subn(r"(?m)^(\s*image: )\S+$", lambda match: match[1] + image, manifest)
    if count != 1:
        raise Blocked("script-runner template must contain exactly one workload image")
    manifest, count = re.subn(r"(?m)^(\s*- name: UI2_SCRIPT_EXEC_IMAGE\n\s*value: )" + re.escape(SCRIPT_EXEC_PLACEHOLDER) + "$",
                              lambda match: match[1] + exec_image, manifest)
    if count != 1:
        raise Blocked("script-runner template must contain exactly one UI2_SCRIPT_EXEC_IMAGE placeholder")
    if deployment:
        return manifest
    documents = [d for d in re.split(r"(?m)^---\s*$", manifest) if d.strip()]
    kept = [d for d in documents if not re.search(r"(?m)^kind: Deployment\s*$", d)]
    return "\n---\n".join(kept)


SCRIPT_RUNNER_AWAITING_SECRETS = "awaiting-broker-secrets"


def script_runner_secrets_ready():
    """True when both hand-made broker Secrets exist (tools/delivery/script_runner_db_login.py)."""
    for name in ("ui2-db-script-runner", "ui2-script-runner-token"):
        if not run("kubectl", "-n", "ui2", "get", "secret", name, "--ignore-not-found", "-o", "jsonpath={.metadata.name}"):
            return False
    return True


def ensure_script_runner(image):
    """Every release re-applies the broker's service accounts, Role, RoleBinding and network policies (they can drift
    or be new); a first release applies the whole set with the candidate once the broker Secrets exist, or only the
    non-Deployment objects while awaiting script_runner_db_login.py. Returns the live broker image, None, or
    SCRIPT_RUNNER_AWAITING_SECRETS."""
    exec_image = script_exec_image(image)
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-script-runner",
                   "--ignore-not-found", "-o", "json")
    if existing:
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_script_runner(image, exec_image, deployment=False))
        return json.loads(existing)["spec"]["template"]["spec"]["containers"][0]["image"]
    if not script_runner_secrets_ready():
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_script_runner(image, exec_image, deployment=False))
        return SCRIPT_RUNNER_AWAITING_SECRETS
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_script_runner(image, exec_image))
    return None


IMMUTABLE_IMAGE = r".+@sha256:[a-f0-9]{64}"
PACKET_ANALYSIS_MANIFEST = "deploy/ui2/58-packet-analysis-deployment.yaml"
SCRIPT_RUNNER_MANIFEST = "deploy/ui2/61-script-runner-deployment.yaml"


# Deployments whose committed manifest is rendered with the candidate image, diffed before the drain and applied after
# it on every forward release, so env, mounts and resources ship together with the code that requires them
# (2026-10-18: the policy pod crashed on the old manifest because a release only swapped its image). The worker is
# here too (2026-11-05 outage class: its manifest was only patched at deploy time and drifted). Its env values come from the committed manifest, which already carries the fallback flag in JAVA_TOOL_OPTIONS; the
# run-time fallback patch in replace_image remains only for a rollback, which restores the recorded digest.
SYNCED_MANIFESTS = {"service": "deploy/ui2/50-service-deployment.yaml",
                    "configuration": "deploy/ui2/54-configuration-deployment.yaml",
                    "compliance": "deploy/ui2/55-compliance-deployment.yaml",
                    "policy": "deploy/ui2/57-policy-deployment.yaml",
                    "backup": "deploy/ui2/53-backup-deployment.yaml",
                    "worker": "deploy/ui2/52-worker-deployment.yaml"}


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


ADVISORY_FETCH_MANIFEST = "deploy/ui2/59-advisory-fetch-cronjob.yaml"


def render_advisory_fetch(image, suspended=None):
    """The advisory fetch CronJob set (docs/design/ADVISORY_MATCHING_CONTRACT.md section 3) with the candidate image.

    The CronJob runs the service image with its own role, so it is applied with every forward release of the service;
    left alone it would keep running the image of the day it was created. The committed `suspend: true` is only a
    first-release value: with `suspended` the line is set to the live state, so a release never resumes or pauses it."""
    manifest = (REPO / ADVISORY_FETCH_MANIFEST).read_text()
    manifest, count = re.subn(r"(?m)^(\s*image: )\S+$", lambda match: match[1] + image, manifest)
    if count != 1:
        raise Blocked("advisory fetch template must contain exactly one workload image")
    if suspended is not None:
        manifest, count = re.subn(r"(?m)^  suspend: (true|false)$", "  suspend: " + ("true" if suspended else "false"), manifest)
        if count != 1:
            raise Blocked("advisory fetch template must contain exactly one suspend line")
    return manifest


def sync_advisory_fetch(image):
    """Apply the advisory fetch set next to the service; the live suspend state is kept (absent CronJob: committed)."""
    state = run("kubectl", "-n", "ui2", "get", "cronjob", "ui2-advisory-fetch", "--ignore-not-found",
                "-o", "jsonpath={.spec.suspend}")
    suspended = {"true": True, "false": False}.get(state)
    run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_advisory_fetch(image, suspended))


def live_replicas(target):
    value = run("kubectl", "-n", "ui2", "get", "deployment/ui2-" + target, "-o", "jsonpath={.spec.replicas}")
    if not value.isdigit():
        raise Blocked("live replica count of " + target + " unavailable")
    return int(value)


def mounted_claims(manifest):
    """Every PersistentVolumeClaim name referenced by `claimName:` in a manifest, in order without duplicates."""
    return tuple(dict.fromkeys(re.findall(r"(?m)^\s*claimName:\s*(\S+)\s*$", manifest)))


def committed_claim_manifests():
    """Committed PersistentVolumeClaim manifests in deploy/ui2, keyed by metadata.name with (filename, manifest)."""
    claims = {}
    directory = REPO / "deploy/ui2"
    if not directory.is_dir():
        return claims
    for path in sorted(directory.glob("*.yaml")):
        for doc in path.read_text().split("\n---\n"):
            if re.search(r"(?m)^kind:\s*PersistentVolumeClaim\s*$", doc):
                match = re.search(r"(?m)^  name:\s*(\S+)\s*$", doc)
                if match:
                    claims[match.group(1)] = (path.name, doc.strip() + "\n")
    return claims


def mounted_claim_manifests(target, manifest):
    """Resolve every PersistentVolumeClaim a target's manifest mounts to its earlier-ordered committed manifest.

    2026-11-05 outage lesson: a new PersistentVolumeClaim must exist before any Deployment manifest that mounts it is
    applied. Every mounted claim must have a committed PersistentVolumeClaim manifest in deploy/ui2 whose filename
    sorts before the Deployment manifest, and missing claims are applied before the Deployment."""
    names = mounted_claims(manifest)
    if not names:
        return ()
    committed = committed_claim_manifests()
    target_file = Path(SYNCED_MANIFESTS[target]).name
    resolved = []
    for name in names:
        if name not in committed:
            raise Blocked(target + " manifest mounts unknown PersistentVolumeClaim " + name)
        claim_file, claim_manifest = committed[name]
        if claim_file >= target_file:
            raise Blocked(target + " claim " + name + " (" + claim_file + ") must be ordered before " + target_file)
        resolved.append((name, claim_manifest))
    return tuple(resolved)


def ensure_mounted_claims(target, manifest):
    """Apply any missing PersistentVolumeClaim mounted by `manifest` before the Deployment manifest itself is applied."""
    for claim, claim_manifest in mounted_claim_manifests(target, manifest):
        if not run("kubectl", "-n", "ui2", "get", "pvc", claim, "--ignore-not-found", "-o", "name"):
            run("kubectl", "-n", "ui2", "apply", "-f", "-", input=claim_manifest)


def check_synced_manifest(target, image):
    """Read-only, before any drain: does the candidate manifest differ from the cluster? Applied after the drain."""
    manifest = render_manifest(target, image, live_replicas(target))
    mounted_claim_manifests(target, manifest)
    print(target + " manifest: " + ("differs from the cluster; applied after the drain"
                                    if kubectl_diff(manifest)
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


def handover_backup(generation, snapshot, authorization):
    result = query("begin;" + audit(snapshot) + "select pg_advisory_xact_lock(294611);"
          "update module_runtime_control set fallback_enabled=false,effective_owner='backup',generation=generation+1,"
          "drain_requested=false,drain_ack_at=null,drain_ack_generation=null,"
          "last_authorization_ref=" + literal(authorization) + ",last_reason='ready backup cutover',last_snapshot_id=" + literal(snapshot) +
          " where module='backup' and drain_requested and drain_generation=" + str(generation) +
          " and owner_instance is not null and owner_heartbeat_at>now()-interval '60 seconds' "
          "and drain_ack_generation=drain_generation returning generation;commit;")
    if not any(line.isdigit() for line in result.splitlines()):
        raise Blocked("backup handover fence or heartbeat changed")


def reset_to_general(snapshot, module, generation, authorization="automatic-rollout-recovery"):
    if module in NEVER_GENERAL or module in SELF_OWNED:
        raise Blocked(module + " is never handed to the general worker; its jobs wait for its own pod")
    # Caller has fenced and stopped the dedicated owner. Never discard permits.
    permit_scope = "owner_role=" + literal(module)
    if module == "policy":
        permit_scope = "(" + permit_scope + " or purpose_class='POLICY')"
    elif module == "backup":
        permit_scope = "(" + permit_scope + " or purpose_class='BACKUP')"
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
    if target == "script-runner" and sync_manifest:
        # The broker's Deployment with the service image and this release's (or the live) execution image, after the
        # drain; a plain image swap would leave UI2_SCRIPT_EXEC_IMAGE and the committed env behind.
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_script_runner(image, script_exec_image(image)))
        return
    if target == "packet-analysis" and sync_manifest:
        # One pod-template update installs the engine image together with the committed env and resources
        # (memory plan, JAVA_TOOL_OPTIONS), so manifest changes ship with the code. Called only after the drain.
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=render_packet_analysis(image))
        return
    if target in SYNCED_MANIFESTS and sync_manifest:
        # One pod-template update installs the image together with the committed env, mounts and resources. The
        # live replica count is kept (the policy release scales the pod itself). Called only after the drain.
        manifest = render_manifest(target, image, live_replicas(target))
        ensure_mounted_claims(target, manifest)
        run("kubectl", "-n", "ui2", "apply", "-f", "-", input=manifest)
        if target == "service":
            sync_advisory_fetch(image)
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


def replace(target, image, snapshot, limit=14400, commit=None, authorization=None, rollback=False, defer_e2e=False,
            allow_transfer=True):
    """Replace one module. With `defer_e2e` the module e2e and the result line are left to the caller (a concurrent
    group runs one e2e after all of its members); the result dict is returned instead."""
    # Deploy-freeze Phase 1 (PO 2026-11-23): a module still on the general fallback is an ownership TRANSFER (audited
    # handover, generation bump, recovery to general on failure); a module that already owns itself gets a ROUTINE
    # image replacement (drain, replace, lift the drain; generation, owner and fallback untouched; never reset to general).
    fallback = target in ("policy", "backup") and query("select fallback_enabled from module_runtime_control where module=" + literal(target)) == "t"
    if fallback and not allow_transfer:
        raise Blocked(target + " is on the general fallback; ownership transfer needs --ownership-transfer")
    if fallback and query("select (owner_heartbeat_at>now()-interval '60 seconds') from module_runtime_control where module='general'") != "t":
        raise Blocked("compatible general worker bootstrap required before " + target + " cutover")
    if fallback and (not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization)):
        raise Blocked(target + " ownership transfer requires an opaque PO authorization reference")
    old = (ensure_policy(image) if target == "policy" else ensure_backup(image) if target == "backup"
           else ensure_script_runner(image) if target == "script-runner"
           else ensure_packet_analysis(image, not rollback) if target == "packet-analysis"
           else run("kubectl", "-n", "ui2", "get", "deployment/ui2-" + target,
                    "-o", "jsonpath={.spec.template.spec.containers[0].image}"))
    if target == "script-runner" and old == SCRIPT_RUNNER_AWAITING_SECRETS:
        print("script-runner: broker secrets (ui2-db-script-runner, ui2-script-runner-token) not present yet; "
              "ServiceAccounts, Role and NetworkPolicies applied; Deployment deferred until script_runner_db_login.py runs",
              flush=True)
        if not defer_e2e:
            with phase(target, "e2e"):
                module_e2e(commit)
        result = {"module": target, "e2e": "PASS", "old_digest": None,
                  "new_digest": image.rsplit("@", 1)[1], "ready": False, "drain_generation": None}
        if not defer_e2e:
            print(json.dumps(result), flush=True)
        return result
    if old is not None and not re.fullmatch(IMMUTABLE_IMAGE, old):
        raise Blocked("old workload digest is not immutable")
    if target in SYNCED_MANIFESTS and old is not None and not rollback:
        check_synced_manifest(target, image)
    generation = None
    # A first release (ensure_packet_analysis created the deployment with the candidate image: old is None) has no
    # old owner to drain; draining it would wait for the new pod itself to stop (2026-10-16: drain retained).
    if target in ROLES and not (target in ("packet-analysis", "script-runner") and old is None):
        # Review D1: a module already fenced before this release (a failed routine release, an operator drain) stays
        # fenced whatever happens here.
        prior_drain = prior_drain_requested(target)
        try:
            with phase(target, "drain"):
                generation = request_drain(target, snapshot)
                wait_drain(target, generation, limit)
        except Blocked as expired:
            if generation is None:
                raise
            # Drain timeout before anything changed. A ROUTINE FORWARD release gives claims back at once (a waiting
            # drain pauses the module's collection): old digest, owner and generation stay. Never on --rollback, never
            # over an earlier fence, never while a quarantine holds the barrier: then the drain stays (BLOCKED).
            # Consequence (docs/operations/DEPLOY_FREEZE_PHASE1_ROLLOUT.md): the old image keeps claiming beside newer
            # modules until the next release; acceptable only because every release's schema change is additive.
            lift = not rollback and not prior_drain and not quarantine_holds(target, generation)
            if lift:
                try:
                    if target == "backup" and fallback:
                        cancel_general_drain(target, generation, snapshot)
                    else:
                        cancel_drain(target, generation, snapshot)
                except Blocked as unavailable:
                    print("drain release after timeout failed: " + str(unavailable), flush=True)
                    lift = False
            raise Blocked(str(expired) + ("; drain lifted, old owner claims again" if lift else
                                          "; drain retained (" + ("rollback" if rollback else "earlier fence" if prior_drain
                                                                  else "quarantine holds the barrier"
                                                                  if quarantine_holds(target, generation)
                                                                  else "release failed") + ")"),
                          continuable=expired.continuable) from None
    old_pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
    old_uids = {pod["metadata"]["uid"] for pod in old_pods["items"]}
    try:
        with phase(target, "replace"):
            # A rollback restores the recorded digest only; the committed manifest belongs to the forward release.
            replace_image(target, image, sync_manifest=(target in ("packet-analysis", "script-runner")
                                                        or target in SYNCED_MANIFESTS)
                          and old is not None and not rollback)
            if generation is not None:
                deadline = time.monotonic() + 900
                while old_uids:
                    pods = json.loads(run("kubectl", "-n", "ui2", "get", "pods", "-l", "app.kubernetes.io/component=" + TARGETS[target], "-o", "json"))
                    if not old_uids.intersection(pod["metadata"]["uid"] for pod in pods["items"]):
                        break
                    if time.monotonic() >= deadline:
                        raise Blocked("old owner did not stop; drain retained")
                    time.sleep(OLD_POD_POLL_S)
                # Forget the old instance, retaining the split-module fence until a new heartbeat.
                if target in ("policy", "backup"):
                    query("begin;" + audit(snapshot) + "update module_runtime_control set owner_instance=null,owner_heartbeat_at=null "
                          "where module=" + literal(target) + " and drain_generation=" + str(generation) + ";commit;")
                else:
                    clear_drain(target, generation, snapshot)
        with phase(target, "rollout"):
            if target in ("policy", "backup"):
                run("kubectl", "-n", "ui2", "scale", "deployment/ui2-" + target, "--replicas=1")
            run("kubectl", "-n", "ui2", "rollout", "status", "deployment/ui2-" + target, "--timeout=600s")
            if target in ROLES:
                wait_heartbeat(target)
            if target in ("policy", "backup") and not fallback:
                resume_dedicated(target, generation, snapshot)
            elif target == "policy":
                handover_policy(generation, snapshot, authorization or "snapshot-rollback")
            elif target == "backup":
                handover_backup(generation, snapshot, authorization or "snapshot-rollback")
        if not defer_e2e:
            with phase(target, "e2e"):
                module_e2e(commit)
    except Blocked:
        if target in ("policy", "backup") and not fallback:
            # Routine replacement failed: fence new claims, keep the pod, owner and generation; never reset to general.
            fence_dedicated(target, snapshot)
            print(target + ": routine replacement failed; ownership and generation kept, module fenced (drain "
                  "requested); restore the previous digest with --rollback", flush=True)
        elif target in ("policy", "backup"):
            # Fence before stopping, then recover fallback only after zero-work evidence.
            recovery = request_drain(target, snapshot)
            handed_over = target == "backup" and query(
                "select effective_owner from module_runtime_control where module='backup'") == "backup"
            if handed_over:
                # The backup pod already owns claims (the handover precedes the e2e). Let it finish what it holds
                # behind the fence before it is stopped; if it cannot drain, keep it running and fenced (visible as
                # a stuck drain) rather than killing a pod with uncertain device work.
                wait_drain(target, recovery, 900)
            run("kubectl", "-n", "ui2", "scale", "deployment/ui2-" + target, "--replicas=0")
            run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", "app.kubernetes.io/component=" + target, "--timeout=900s")
            if target == "backup" and not handed_over:
                # Ownership never left the general worker: only lift the fence; running general work is legitimate.
                cancel_general_drain(target, recovery, snapshot)
            else:
                reset_to_general(snapshot, target, recovery, authorization or "automatic-rollout-recovery")
        raise
    result = {"module": target, "e2e": "PASS", "old_digest": old.rsplit("@", 1)[1] if old else None,
              "new_digest": image.rsplit("@", 1)[1], "ready": True, "drain_generation": generation}
    if defer_e2e:
        return result
    print(json.dumps(result), flush=True)


def replace_concurrently(group, image_for, snapshot, limit, commit, authorization, rollback, run_e2e=True):
    """Roll out several barrier-free modules at the same time, then run ONE module e2e for the group.

    Every member goes through the same `replace` (its own checks, rollout status and error handling); only the wait is
    shared. The e2e runs only if every member succeeded. Returns (failed targets, whether a failure stops the release)."""
    results, errors = {}, {}

    def member(target):
        try:
            results[target] = replace(target, image_for(target), snapshot, limit, commit, authorization,
                                      rollback=rollback, defer_e2e=True)
        except BaseException as error:  # a thread must never die silently; re-raised below
            errors[target] = error

    threads = [threading.Thread(target=member, args=(t,), daemon=True) for t in group]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    unexpected = next((errors[t] for t in group if t in errors and not isinstance(errors[t], Blocked)), None)
    if unexpected is not None:
        raise unexpected
    failures = [t for t in group if t in errors]
    for target in failures:
        with _print_lock:
            print(json.dumps({"module": target, "status": "BLOCKED", "reason": str(errors[target])}), flush=True)
    if failures:
        return failures, any(not errors[t].continuable for t in failures)
    try:
        if run_e2e:
            with phase("group_" + "_".join(group), "e2e"):
                module_e2e(commit)
    except Blocked as error:
        for target in group:
            print(json.dumps({"module": target, "status": "BLOCKED", "reason": str(error)}), flush=True)
        return list(group), not error.continuable
    for target in group:
        if not run_e2e:
            results[target]["e2e"] = "BATCHED"
        print(json.dumps(results[target]), flush=True)
    return [], False


def e2e_after_step(index, count, mode="each"):
    """Whether the module e2e runs after step `index` (0-based) of `count`. Unknown modes behave like "each"."""
    return mode != "edges" or index == 0 or index == count - 1


def rollout_steps(targets, concurrent=True):
    """The release as ordered steps: each a list of targets rolled out together. Only consecutive CONCURRENT_SAFE
    targets (no drain barrier) form a multi-target step; every other target keeps its own step, in the given order."""
    steps = []
    for target in targets:
        if concurrent and target in CONCURRENT_SAFE and steps and all(t in CONCURRENT_SAFE for t in steps[-1]):
            steps[-1].append(target)
        else:
            steps.append([target])
    return steps


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


def return_backup_to_general(snapshot, authorization):
    if not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization):
        raise Blocked("an opaque PO authorization reference is required")
    worker = json.loads(run("kubectl", "-n", "ui2", "get", "deployment/ui2-worker", "-o", "json"))
    disabled = any("-Dui2.worker.claim-backup-fallback=false" in entry.get("value", "")
                   for container in worker["spec"]["template"]["spec"]["containers"] for entry in container.get("env", [])
                   if entry["name"] == "JAVA_TOOL_OPTIONS")
    if disabled or query("select owner_heartbeat_at>now()-interval '60 seconds' from module_runtime_control where module='general'") != "t":
        raise Blocked("compatible general owner must already have backup fallback capability")
    generation = request_drain("backup", snapshot)
    existing = run("kubectl", "-n", "ui2", "get", "deployment", "ui2-backup", "--ignore-not-found", "-o", "name")
    if existing:
        run("kubectl", "-n", "ui2", "scale", "deployment/ui2-backup", "--replicas=0")
    run("kubectl", "-n", "ui2", "wait", "--for=delete", "pod", "-l", "app.kubernetes.io/component=backup", "--timeout=900s")
    reset_to_general(snapshot, "backup", generation, authorization)


def ownership_preflight(targets, transfer, authorization):
    """Separates routine releases from ownership transfers (deploy-freeze Phase 1, PO 2026-11-23). A policy/backup
    target still on the general fallback is an ownership transfer: without --ownership-transfer it is skipped with a
    note (the general worker keeps running that module's jobs); with it, the transfer is the only thing this run does
    and needs an opaque PO authorization reference. Returns the targets to roll out."""
    on_fallback = [t for t in ("policy", "backup") if t in targets
                   and query("select fallback_enabled from module_runtime_control where module=" + literal(t)) == "t"]
    if transfer:
        if not on_fallback or set(targets) != set(on_fallback):
            raise Blocked("--ownership-transfer accepts only policy/backup targets that are still on the general fallback")
        if not authorization or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,160}", authorization):
            raise Blocked("ownership transfer requires an opaque PO authorization reference")
        return targets
    for target in on_fallback:
        print(target + ": still on the general fallback; routine release skips it (ownership transfer is a separate, "
              "audited step: --ownership-transfer --authorization-ref)", flush=True)
    return [t for t in targets if t not in on_fallback]


def main():
    global YIELD_WAIT_LIMIT_S
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--targets")
    selection.add_argument("--validate-targets", metavar="TARGETS")
    parser.add_argument("--image")
    parser.add_argument("--packet-analysis-image", help="digest-pinned Packet Analysis engine image (main image plus "
                        "tshark); required when packet-analysis is a target, never defaults to --image")
    parser.add_argument("--script-exec-image", help="digest-pinned script execution image (main image plus the "
                        "interpreters); set on the ui2-script-runner broker as UI2_SCRIPT_EXEC_IMAGE, never --image; "
                        "omitted, the broker keeps its live value")
    parser.add_argument("--commit")
    parser.add_argument("--rollback", action="store_true")
    # Rollback e2e cadence (PO 2026-11-22: rollback within 5 minutes). "each" (default, unchanged) verifies every restored
    # module; "edges" verifies after the first and the last step only, as a forward release may (about 70 s per e2e).
    parser.add_argument("--rollback-e2e", choices=MODULE_E2E_MODES, default="each")
    parser.add_argument("--no-concurrent", dest="concurrent_safe", action="store_false",
                        help="roll the barrier-free modules (configuration, compliance) one after another")
    parser.add_argument("--module-e2e", choices=MODULE_E2E_MODES,
                        default=os.environ.get("NEXUS_MODULE_E2E", "each"),
                        help="each: a module e2e after every step (default); edges: after the first and the last step only")
    parser.add_argument("--allow-newer-schema", action="store_true")
    parser.add_argument("--return-policy-to-general", action="store_true")
    parser.add_argument("--return-backup-to-general", action="store_true")
    parser.add_argument("--authorization-ref")
    parser.add_argument("--ownership-transfer", action="store_true",
                        help="explicit, audited cutover of policy/backup from the general fallback to its own pod "
                             "(needs --authorization-ref; only those modules may be targets). Without it a module still "
                             "on the fallback is skipped: a routine release never changes ownership")
    parser.add_argument("--yield-wait-limit", type=int,
                        default=int(os.environ.get("NEXUS_YIELD_WAIT_LIMIT_S") or YIELD_WAIT_LIMIT_S),
                        help="drain wait (s) for worker/policy while only yield-capable jobs hold the barrier; "
                             "0 disables (then --wait-limit applies throughout)")
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
        returning = args.return_policy_to_general or args.return_backup_to_general
        if not args.rollback and not returning and (args.image is None or not re.fullmatch(IMMUTABLE_IMAGE, args.image)):
            raise Blocked("candidate image must be immutable")
        if args.packet_analysis_image is not None and not re.fullmatch(IMMUTABLE_IMAGE, args.packet_analysis_image):
            raise Blocked("packet-analysis image must be immutable")
        if args.script_exec_image is not None:
            if not re.fullmatch(IMMUTABLE_IMAGE, args.script_exec_image):
                raise Blocked("script execution image must be immutable")
            if args.script_exec_image == args.image:
                raise Blocked("script execution image must differ from the main service image")
            global SCRIPT_EXEC_IMAGE
            SCRIPT_EXEC_IMAGE = args.script_exec_image
        if not args.rollback and not returning and "packet-analysis" in targets:
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
            if targets != ["policy"] or args.image or args.rollback or args.return_backup_to_general:
                raise Blocked("fallback return accepts only the policy target")
            if not manifest.get("runtime", {}).get("compatible_runtime"):
                raise Blocked("compatible snapshot required for fallback")
            return_policy_to_general(directory.name, args.authorization_ref)
            return
        if args.return_backup_to_general:
            if targets != ["backup"] or args.image or args.rollback or args.return_policy_to_general:
                raise Blocked("fallback return accepts only the backup target")
            if not manifest.get("runtime", {}).get("compatible_runtime"):
                raise Blocked("compatible snapshot required for fallback")
            return_backup_to_general(directory.name, args.authorization_ref)
            return
        YIELD_WAIT_LIMIT_S = max(0, args.yield_wait_limit)
        if args.ownership_transfer and args.rollback:
            raise Blocked("--ownership-transfer cannot be combined with --rollback")
        targets = ownership_preflight(targets, args.ownership_transfer, args.authorization_ref)
        if not targets:
            print("Nothing to roll out: every selected target is still on the general fallback.", flush=True)
            return
        if "policy" in targets or "backup" in targets:
            prepare_policy_public_trust()
        failures = []

        def image_for(target):
            return (rollback_image(manifest, target) if args.rollback else
                    args.packet_analysis_image if target == "packet-analysis" else args.image)

        stopped = False
        steps = rollout_steps(targets, args.concurrent_safe and not args.rollback)
        # A rollback keeps the e2e after every step by default (--rollback-e2e each): it is the recovery path.
        e2e_mode = args.rollback_e2e if args.rollback else "each" if returning else args.module_e2e
        for index, step in enumerate(steps):
            if stopped:
                break
            run_e2e = e2e_after_step(index, len(steps), e2e_mode)
            if len(step) > 1:
                # Resolve every image before any member starts (a missing rollback digest must change nothing).
                try:
                    [image_for(t) for t in step]
                except Blocked as error:
                    failures.extend(step)
                    print(json.dumps({"module": step[0], "status": "BLOCKED", "reason": str(error)}), flush=True)
                    break
                failed, hard = replace_concurrently(step, image_for, directory.name, args.wait_limit, args.commit,
                                                    args.authorization_ref, args.rollback, run_e2e)
                failures.extend(failed)
                stopped = hard
                continue
            target = step[0]
            try:
                if run_e2e:
                    replace(target, image_for(target), directory.name, args.wait_limit, args.commit,
                            args.authorization_ref, rollback=args.rollback, allow_transfer=args.ownership_transfer)
                else:
                    result = replace(target, image_for(target), directory.name, args.wait_limit, args.commit,
                                     args.authorization_ref, rollback=args.rollback, defer_e2e=True,
                                     allow_transfer=args.ownership_transfer)
                    result["e2e"] = "BATCHED"
                    print(json.dumps(result), flush=True)
            except Blocked as error:
                failures.append(target)
                print(json.dumps({"module": target, "status": "BLOCKED", "reason": str(error)}), flush=True)
                if not error.continuable:
                    stopped = True
        if failures:
            raise SystemExit(5)
    except Blocked as error:
        print("BLOCKED: " + str(error))
        raise SystemExit(5)


if __name__ == "__main__":
    main()
