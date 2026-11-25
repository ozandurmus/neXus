"""Host side of tools/delivery/hosta_test_run.py (docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md, DRAFT).

Sent as the ssh command text (`python3 -I -c <this file>`) from the Mac's checkout -- never installed on HOST-A and never
taken from the candidate, exactly as tools/security/security_pregate_remote.sh. The Mac imports this module only for
validate_summary/render_job, so both sides judge a summary with one rule.

  argv: <run name> <commit> <tree> <deadline seconds> <payload: base64 JSON {job, files, scope}>
  stdin: a Git bundle carrying refs/test-run/... for <commit>

Drains the whole bundle first, checks the candidate out in a private temp directory and verifies <commit> has <tree>,
creates one Job in namespace ui2-test from the Mac's template (deploy/ui2-test/40-test-run-job.yaml) and the recorded
test-runner image (~/.config/nexus/test-runner-image), copies the candidate's committed ui2/ and docs/operations/sql/
plus the Mac's runner files into the pod, waits, and reads back the pod's one TESTRUN_SUMMARY line. Prints heartbeat
lines (".") while it waits, then exactly one line: RESULT {json}. The Job and the temp directory are deleted on every
path out: done, error, deadline, HUP/INT/TERM, or the ssh session closing (a heartbeat write fails).
"""
import base64
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time

NAMESPACE = "ui2-test"
RUN = re.compile(r"ui2-test-run-[a-f0-9]{12}")
SHA = re.compile(r"[0-9a-f]{40}")
IMAGE = re.compile(r"registry\.kube-system\.svc\.cluster\.local/nexus-ui2-test-runner@sha256:[a-f0-9]{64}")
TASK = re.compile(r":[a-z0-9-]+:test")
IMAGE_RECORD = Path.home() / ".config" / "nexus" / "test-runner-image"
RUNNER_FILES = ("test_runner_pod.sh", "TestRunSummary.java")
STATUSES = ("PASS", "FAIL", "ERROR", "IMAGE_STALE")
PHASES = ("java", "integration", "vitest")
PHASE_KEYS = {"name", "status", "reason", "exit_code", "duration_s", "tests", "failures", "errors", "skipped", "failed"}
REASON = re.compile(r"[a-z_]{1,40}")
FAILED_NAME = re.compile(r"[A-Za-z0-9 _.,:/>()#+$<-]{1,200}")
PULL_ERRORS = {"ErrImagePull", "ImagePullBackOff", "InvalidImageName", "ErrImageNeverPull", "CreateContainerConfigError"}
MIN_DEADLINE_S, MAX_DEADLINE_S = 120, 3600


class Cancelled(Exception):
    """HUP/INT/TERM or a closed session."""


# ---------------------------------------------------------------------------------------------------- pure helpers

def validate_scope(scope):
    """{java: none|scoped|full, tasks: [...], integration: bool, vitest: bool} -> the same, or ValueError."""
    if not isinstance(scope, dict) or set(scope) != {"java", "tasks", "integration", "vitest"}:
        raise ValueError("scope keys")
    java, tasks = scope["java"], scope["tasks"]
    if java not in ("none", "scoped", "full") or not isinstance(tasks, list):
        raise ValueError("scope java")
    if not isinstance(scope["integration"], bool) or not isinstance(scope["vitest"], bool):
        raise ValueError("scope flags")
    if java == "scoped":
        if not tasks or len(tasks) > 40 or len(set(tasks)) != len(tasks):
            raise ValueError("scope tasks")
        for task in tasks:
            if not isinstance(task, str) or not TASK.fullmatch(task) or task == ":integration-tests:test":
                raise ValueError("scope task")
    elif tasks:
        raise ValueError("tasks only for a scoped run")
    if java == "none" and not scope["integration"] and not scope["vitest"]:
        raise ValueError("empty scope")
    return scope


def validate_summary(summary):
    """The pod's TESTRUN_SUMMARY object, checked key by key; returns it or raises ValueError."""
    if not isinstance(summary, dict) or set(summary) != {"status", "reason", "phases"}:
        raise ValueError("summary keys")
    if summary["status"] not in STATUSES or not isinstance(summary["reason"], str) \
            or not REASON.fullmatch(summary["reason"]) or not isinstance(summary["phases"], list):
        raise ValueError("summary fields")
    seen = set()
    worst = 0
    for phase in summary["phases"]:
        if not isinstance(phase, dict) or set(phase) != PHASE_KEYS or phase["name"] not in PHASES \
                or phase["name"] in seen or phase["status"] not in ("PASS", "FAIL", "ERROR") \
                or not isinstance(phase["reason"], str) or not REASON.fullmatch(phase["reason"]):
            raise ValueError("phase fields")
        seen.add(phase["name"])
        for key in ("exit_code", "duration_s", "tests", "failures", "errors", "skipped"):
            value = phase[key]
            if type(value) is not int or not 0 <= value <= 10_000_000:
                raise ValueError("phase count")
        failed = phase["failed"]
        if not isinstance(failed, list) or len(failed) > 40 \
                or not all(isinstance(n, str) and FAILED_NAME.fullmatch(n) for n in failed):
            raise ValueError("phase failed names")
        if phase["status"] == "PASS" and (phase["failures"] or phase["errors"] or phase["exit_code"] or failed
                                          or not phase["tests"]):
            raise ValueError("phase PASS contradicts its counts")
        worst = max(worst, ("PASS", "FAIL", "ERROR").index(phase["status"]))
    if summary["status"] == "IMAGE_STALE":
        if summary["phases"]:
            raise ValueError("a stale image runs nothing")
    elif summary["phases"] and summary["status"] != ("PASS", "FAIL", "ERROR")[worst]:
        raise ValueError("summary status contradicts its phases")
    elif not summary["phases"] and summary["status"] != "ERROR":
        raise ValueError("no phase is never a pass")
    return summary


def summary_from_logs(text):
    """Exactly one TESTRUN_SUMMARY line in the runner's log, validated; anything else is ValueError."""
    lines = re.findall(r"^TESTRUN_SUMMARY (\{.*\})$", text, re.M)
    if len(lines) != 1:
        raise ValueError("missing or duplicate summary")
    return validate_summary(json.loads(lines[0]))


def render_job(template, name, image, scope, deadline_s):
    """The Job object for one run, from the template document (dict) of deploy/ui2-test/40-test-run-job.yaml."""
    if not RUN.fullmatch(name) or not IMAGE.fullmatch(image):
        raise ValueError("run name or image")
    validate_scope(scope)
    deadline_s = int(deadline_s)
    if not MIN_DEADLINE_S <= deadline_s <= MAX_DEADLINE_S:
        raise ValueError("deadline")
    job = json.loads(json.dumps(template))
    if job.get("kind") != "Job" or job["metadata"].get("namespace") != NAMESPACE:
        raise ValueError("template")
    job["metadata"]["name"] = name
    job["spec"]["activeDeadlineSeconds"] = deadline_s
    pod = job["spec"]["template"]
    pod["metadata"]["labels"]["nexus-test-run"] = name
    spec = pod["spec"]
    runner, = [c for c in spec["containers"] if c["name"] == "runner"]
    runner["image"] = image
    values = {"TEST_JAVA": scope["java"], "TEST_GRADLE_TASKS": " ".join(scope["tasks"]),
              "TEST_INTEGRATION": "1" if scope["integration"] else "0", "TEST_VITEST": "1" if scope["vitest"] else "0"}
    for entry in runner["env"]:
        if entry["name"] in values:
            entry["value"] = values.pop(entry["name"])
    if values:
        raise ValueError("template env")
    if not scope["integration"]:
        # No database phase: no database containers, no password volume, no database env.
        spec["initContainers"] = []
        spec["volumes"] = [v for v in spec["volumes"]
                           if v["name"] not in ("test-db", "pg-data", "pg-socket", "pg-tmp", "pg-init")]
        runner["env"] = [e for e in runner["env"] if not e["name"].startswith("UI2_TEST_")]
        runner["volumeMounts"] = [m for m in runner["volumeMounts"] if m["name"] != "test-db"]
    return job


def runner_tar(files):
    """The Mac's runner files as a tar stream under .runner/ (fixed names, fixed modes)."""
    if set(files) != set(RUNNER_FILES):
        raise ValueError("runner files")
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for name in RUNNER_FILES:
            data = files[name].encode()
            info = tarfile.TarInfo(".runner/" + name)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


# ---------------------------------------------------------------------------------------------------- host side

def kubectl(*args, data=None, timeout=60):
    result = subprocess.run(["kubectl", "-n", NAMESPACE, *args], input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("kubectl failed")
    return result.stdout


def heartbeat():
    try:
        sys.stdout.write(".\n")
        sys.stdout.flush()
    except (BrokenPipeError, OSError):
        raise Cancelled()


def emit(result):
    try:
        sys.stdout.write("RESULT " + json.dumps(result, sort_keys=True) + "\n")
        sys.stdout.flush()
    except (BrokenPipeError, OSError):
        pass


def wait(deadline, step):
    end = time.monotonic() + step
    while time.monotonic() < min(end, deadline):
        time.sleep(0.2)


def pod_state(name):
    """(pod name or None, 'running' | 'pending' | 'image_pull' | 'database' | 'preempted' | 'done')."""
    pods = json.loads(kubectl("get", "pods", "-l", "nexus-test-run=" + name, "-o", "json"))["items"]
    if not pods:
        return None, "pending"
    pod = pods[0]
    status = pod.get("status", {})
    if status.get("reason") in ("Evicted", "Preempting") or any(
            c.get("type") == "DisruptionTarget" and c.get("status") == "True" for c in status.get("conditions", [])):
        return pod["metadata"]["name"], "preempted"
    for container in status.get("containerStatuses", []) + status.get("initContainerStatuses", []):
        if container.get("state", {}).get("waiting", {}).get("reason") in PULL_ERRORS:
            return pod["metadata"]["name"], "image_pull"
    for container in status.get("initContainerStatuses", []):
        state = container.get("state", {})
        crashed = state.get("waiting", {}).get("reason") == "CrashLoopBackOff"
        failed = state.get("terminated", {}).get("exitCode", 0) != 0
        if crashed or failed:
            return pod["metadata"]["name"], "database"
    for container in status.get("containerStatuses", []):
        if container.get("name") == "runner":
            if "running" in container.get("state", {}):
                return pod["metadata"]["name"], "running"
            if "terminated" in container.get("state", {}):
                return pod["metadata"]["name"], "done"
    return pod["metadata"]["name"], "pending"


def job_conditions(name):
    job = json.loads(kubectl("get", "job", name, "-o", "json"))
    return {c["type"] for c in job.get("status", {}).get("conditions", []) if c.get("status") == "True"}


def run(name, commit, tree, deadline_s, payload, stdin, state):
    if not RUN.fullmatch(name) or not SHA.fullmatch(commit) or not SHA.fullmatch(tree):
        raise ValueError("arguments")
    started = time.monotonic()
    deadline = started + int(deadline_s)
    work = state["work"] = tempfile.mkdtemp(prefix="nexus-test-run-")
    bundle = os.path.join(work, "c.bundle")
    # Drain the whole bundle before any answer: an early exit must never leave the sender writing into a closed pipe.
    with open(bundle, "wb") as out:
        shutil.copyfileobj(stdin, out)
    data = json.loads(base64.b64decode(payload, validate=True))
    scope = validate_scope(data["scope"])
    try:
        image = IMAGE_RECORD.read_text().splitlines()[0].strip()
    except (OSError, IndexError):
        return {"status": "NOT_CONFIGURED", "reason": "no_test_runner_image"}
    if not IMAGE.fullmatch(image):
        return {"status": "ERROR", "reason": "invalid_test_runner_image"}
    try:
        subprocess.run(["kubectl", "get", "namespace", NAMESPACE, "-o", "name"], capture_output=True, timeout=60,
                       check=True)
    except (subprocess.SubprocessError, OSError):
        return {"status": "NOT_CONFIGURED", "reason": "no_test_namespace"}
    repo = os.path.join(work, "repo")
    git = ["git", "-C", repo]
    try:
        subprocess.run(["git", "init", "-q", repo], capture_output=True, check=True, timeout=60)
        subprocess.run(git + ["fetch", "-q", "--no-write-fetch-head", bundle, "+refs/test-run/*:refs/test-run/*"],
                       capture_output=True, check=True, timeout=300)
        actual = subprocess.run(git + ["rev-parse", commit + "^{tree}"], capture_output=True, text=True, check=True,
                                timeout=60).stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return {"status": "ERROR", "reason": "source"}
    if actual != tree:
        return {"status": "ERROR", "reason": "tree_mismatch"}
    job = render_job(data["job"], name, image, scope, max(MIN_DEADLINE_S, int(deadline_s) - 30))
    state["job"] = name
    kubectl("create", "-f", "-", data=json.dumps(job).encode())
    pod = None
    while True:
        if time.monotonic() >= deadline:
            return {"status": "TIMEOUT", "reason": "pod_not_started"}
        pod, phase = pod_state(name)
        if phase == "running":
            break
        if phase in ("image_pull", "preempted", "database", "done"):
            return {"status": "ERROR", "reason": {"done": "runner_ended_early"}.get(phase, phase)}
        heartbeat()
        wait(deadline, state["poll"])
    queued_s = round(time.monotonic() - started)
    # Only the candidate's committed source crosses into the pod, plus the Mac's runner files. deploy/ui2 is there because
    # DatabaseRoleGrantMatrixIntegrationTest runs the in-cluster hook SQL it reads from deploy/ui2/10-configmap.yaml.
    exec_in = ["exec", "-i", pod, "-c", "runner", "--"]
    with subprocess.Popen(git + ["archive", commit, "ui2", "docs/operations/sql", "deploy/ui2"], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as archive:
        copied = subprocess.run(["kubectl", "-n", NAMESPACE, *exec_in, "tar", "-xf", "-", "-C", "/workspace"],
                                stdin=archive.stdout, capture_output=True, timeout=max(1, deadline - time.monotonic()))
        archive.stdout.close()
        if archive.wait(timeout=60) != 0 or copied.returncode != 0:
            return {"status": "ERROR", "reason": "source_copy"}
    kubectl(*exec_in, "tar", "-xf", "-", "-C", "/workspace", data=runner_tar(data["files"]))
    kubectl(*exec_in, "touch", "/workspace/ready")
    while True:
        if time.monotonic() >= deadline:
            return {"status": "TIMEOUT", "reason": "deadline"}
        conditions = job_conditions(name)
        if conditions & {"Complete", "Failed"}:
            break
        _, phase = pod_state(name)
        if phase in ("preempted", "image_pull", "database"):
            return {"status": "ERROR", "reason": phase}
        heartbeat()
        wait(deadline, state["poll"])
    try:
        summary = summary_from_logs(kubectl("logs", pod, "-c", "runner").decode("utf-8", "replace"))
    except (ValueError, RuntimeError, subprocess.SubprocessError):
        _, phase = pod_state(name)
        return {"status": "ERROR", "reason": "preempted" if phase == "preempted" else "no_summary"}
    if summary["status"] == "PASS" and "Complete" not in conditions:
        return {"status": "ERROR", "reason": "summary_contradicts_job"}
    return {"status": summary["status"], "reason": summary["reason"], "summary": summary,
            "queued_s": queued_s, "host_s": round(time.monotonic() - started)}


def cleanup(state):
    for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, signal.SIG_IGN)
    if state.get("job"):
        try:
            kubectl("delete", "job", state["job"], "--ignore-not-found", "--wait=false", "--cascade=background")
        except (RuntimeError, subprocess.SubprocessError, OSError):
            pass
    if state.get("work"):
        shutil.rmtree(state["work"], ignore_errors=True)


def main(argv):
    os.environ["KUBECONFIG"] = os.environ.get("NEXUS_TEST_KUBECONFIG", str(Path.home() / ".kube" / "config"))
    state = {"poll": float(os.environ.get("NEXUS_TEST_RUN_POLL_S", "5"))}

    def stop(signum, frame):
        raise Cancelled()
    for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, stop)
    result = None
    try:
        if len(argv) != 5:
            raise ValueError("arguments")
        result = run(*argv, stdin=sys.stdin.buffer, state=state)
    except Cancelled:
        result = None
    except Exception:  # every other error is ERROR; raw details stay on the host
        result = {"status": "ERROR", "reason": "host_side"}
    finally:
        cleanup(state)
    if result is None:
        return 2
    if len(argv) == 5:
        result.update(run=argv[0], commit=argv[1], tree=argv[2])
    emit(result)
    return 0


if __name__ == "__main__":
    try:
        sys.stderr = open(os.devnull, "w")
    except OSError:
        pass
    sys.exit(main(sys.argv[1:]))
