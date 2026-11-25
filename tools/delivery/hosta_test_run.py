#!/usr/bin/env python3
"""Run a lane's heavy tests on HOST-A's ui2-test namespace instead of the Mac (PO 2026-11-24).

docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md (DRAFT). From the Mac: stream the candidate's Git history to HOST-A
over the existing SSH path, exactly as the security pre-gate does (tools/security/hosta_security_pregate.sh), and let
the host side (hosta_test_run_remote.py, sent from THIS checkout as the ssh command text) run one Job in ui2-test from
the prebuilt test-runner image: Java scoped by pretest_scope.py (or full), :integration-tests:test against a PostgreSQL
16 sidecar, optional vitest. Prints exactly one JSON line (status, counts, failed test names, durations) and exits:

  0 PASS   1 FAIL (a test or compile failure in the candidate)   2 ERROR / TIMEOUT (runner, host or transport)
  3 NOT_CONFIGURED / IMAGE_STALE (the host cannot run this tree yet: run the same scope on the Mac instead)

  hosta_test_run.py --repo <worktree> [--commit SHA] [--base origin/main] [--java scoped|full|none]
                    [--integration] [--vitest] [--timeout 2700]

Bounded: --timeout (300..3600 s) ends the session; the host side has its own deadline 60 s earlier and the Job its own
activeDeadlineSeconds. On HUP/INT/TERM, a timeout or a missing answer the session is closed (the host side deletes its
Job when its next heartbeat write fails) and one more bounded ssh call deletes the Job by name; the temporary ref is
always removed. Nothing is ever applied to the cluster beyond the one Job.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import queue
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
import uuid

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import hosta_test_run_remote as remote  # noqa: E402
import pretest_scope  # noqa: E402

JOB_TEMPLATE = REPO / "deploy" / "ui2-test" / "40-test-run-job.yaml"
EXIT = {"PASS": 0, "FAIL": 1, "ERROR": 2, "TIMEOUT": 2, "NOT_CONFIGURED": 3, "IMAGE_STALE": 3}
SSH_OPTIONS = ["-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15",
               "-o", "ServerAliveCountMax=8"]
MIN_TIMEOUT_S, MAX_TIMEOUT_S, HOST_MARGIN_S = 300, 3600, 60
CLEANUP_TIMEOUT_S = 45


class Stop(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def host_name():
    host = os.environ.get("NEXUS_HOST", "")
    if not host:
        try:
            host = (Path.home() / ".config" / "nexus" / "hosta").read_text().splitlines()[0].strip()
        except (OSError, IndexError):
            host = ""
    return host


def git(repo, *args, **kwargs):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=120, **kwargs)


def build_scope(repo, base, java, integration, vitest, scope_of=None):
    """The host-side scope object. `java scoped` follows pretest_scope.py and falls back to full exactly as it says;
    its frontend verdict turns vitest on (never off)."""
    task_scope = None
    if java == "scoped":
        if scope_of is None:
            paths = pretest_scope.changed_paths(Path(repo), base, "HEAD")
            task_scope = pretest_scope.full("git diff failed") if paths is None else pretest_scope.scope_for(paths, Path(repo))
        else:
            task_scope = scope_of(repo, base)
        if task_scope.get("scope") != "scoped":
            java = "full"
        else:
            vitest = vitest or bool(task_scope.get("frontend"))
    tasks = list(task_scope["gradle_tasks"]) if java == "scoped" else []
    scope = {"java": java, "tasks": tasks, "integration": bool(integration), "vitest": bool(vitest)}
    return remote.validate_scope(scope), (task_scope or {}).get("why", "")


def payload(scope):
    import yaml  # the repository's test and tooling dependency (requirements-dev.txt)
    job, = [d for d in yaml.safe_load_all(JOB_TEMPLATE.read_text()) if d]
    files = {name: (HERE / name).read_text() for name in remote.RUNNER_FILES}
    return base64.b64encode(json.dumps({"job": job, "files": files, "scope": scope}).encode()).decode()


def remote_command(name, commit, tree, deadline_s, data):
    code = (HERE / "hosta_test_run_remote.py").read_text()
    return " ".join(shlex.quote(p) for p in ("python3", "-I", "-c", code, name, commit, tree, str(deadline_s), data))


def parse_result(lines, name, commit, tree):
    """The host's one RESULT line, checked; ERROR when it is missing, duplicated or does not fit this run."""
    results = [line[len("RESULT "):] for line in lines if line.startswith("RESULT ")]
    if len(results) != 1:
        return {"status": "ERROR", "reason": "no_host_result"}
    try:
        result = json.loads(results[0])
        if not isinstance(result, dict) or (result.get("run"), result.get("commit"), result.get("tree")) != (name, commit, tree):
            raise ValueError("result does not belong to this run")
        status = result.get("status")
        if status not in EXIT or not isinstance(result.get("reason"), str) or not remote.REASON.fullmatch(result["reason"]):
            raise ValueError("status")
        if "summary" in result:
            summary = remote.validate_summary(result["summary"])
            if summary["status"] != status:
                raise ValueError("status contradicts the summary")
        elif status in ("PASS", "FAIL", "IMAGE_STALE"):
            raise ValueError("a test verdict needs its summary")
        for key in ("queued_s", "host_s"):
            if key in result and (type(result[key]) is not int or result[key] < 0):
                raise ValueError(key)
        extra = set(result) - {"status", "reason", "summary", "queued_s", "host_s", "run", "commit", "tree"}
        if extra:
            raise ValueError("unknown keys")
        return result
    except (ValueError, TypeError):
        return {"status": "ERROR", "reason": "invalid_host_result"}


def delete_job(host, name):
    """Best effort, bounded: the host side normally deletes its own Job; this covers a session that died first."""
    if not remote.RUN.fullmatch(name):
        return
    command = ("export KUBECONFIG=$HOME/.kube/config; kubectl -n %s delete job %s --ignore-not-found --wait=false"
               % (remote.NAMESPACE, name))
    try:
        subprocess.run(["ssh", *SSH_OPTIONS, host, command], stdin=subprocess.DEVNULL, capture_output=True,
                       timeout=CLEANUP_TIMEOUT_S)
    except (subprocess.SubprocessError, OSError):
        pass


def stop_process(process):
    if process and process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            process.wait()


def run(repo, commit, scope, timeout_s, host, out=sys.stdout, clock=time.monotonic):
    tree = git(repo, "rev-parse", commit + "^{tree}").stdout.strip()
    if not remote.SHA.fullmatch(tree):
        raise ValueError("tree")
    name = "ui2-test-run-" + uuid.uuid4().hex[:12]
    ref = "refs/test-run/%s-%s" % (name, commit)
    started = clock()
    deadline = started + timeout_s
    bundle = ssh = None
    lines, abnormal = [], True
    if git(repo, "update-ref", ref, commit).returncode:
        raise ValueError("ref")
    try:
        bundle = subprocess.Popen(["git", "-C", str(repo), "bundle", "create", "-", ref], stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, start_new_session=True)
        command = remote_command(name, commit, tree, timeout_s - HOST_MARGIN_S, payload(scope))
        ssh = subprocess.Popen(["ssh", *SSH_OPTIONS, host, command], stdin=bundle.stdout, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True)
        bundle.stdout.close()
        lines_queue = queue.Queue()

        def reader():
            for raw in ssh.stdout:
                lines_queue.put(raw.decode("utf-8", "replace").rstrip("\n"))
            lines_queue.put(None)
        threading.Thread(target=reader, daemon=True).start()
        while True:
            left = deadline - clock()
            if left <= 0:
                result = {"status": "TIMEOUT", "reason": "session_deadline"}
                break
            try:
                line = lines_queue.get(timeout=min(left, 1.0))
            except queue.Empty:
                continue
            if line is None:
                ssh.wait(timeout=30)
                result = parse_result(lines, name, commit, tree)
                abnormal = result.get("reason") in ("no_host_result", "invalid_host_result")
                break
            if line != "." and len(lines) < 100:
                lines.append(line[:200_000])
    finally:
        # A second signal must not cut the cleanup short; the caller's handlers come back afterwards.
        previous = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
        try:
            stop_process(ssh)
            stop_process(bundle)
            git(repo, "update-ref", "-d", ref)
            if abnormal:
                delete_job(host, name)
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
    result.setdefault("run", name)
    result.setdefault("commit", commit)
    result.setdefault("tree", tree)
    result["scope"] = scope
    result["wall_s"] = round(clock() - started)
    out.write(json.dumps(result, sort_keys=True) + "\n")
    out.flush()
    return EXIT[result["status"]]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", type=Path, required=True, help="the lane worktree")
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--base", default="origin/main", help="base for the pretest_scope.py diff")
    parser.add_argument("--java", choices=("scoped", "full", "none"), default="scoped")
    parser.add_argument("--integration", action="store_true", help="run :integration-tests:test (PostgreSQL 16)")
    parser.add_argument("--vitest", action="store_true", help="run vitest even when the scope has no frontend change")
    parser.add_argument("--timeout", type=int, default=2700, help="whole-run bound in seconds (300..3600)")
    args = parser.parse_args(argv)

    def fail(reason, code=2):
        print(json.dumps({"status": "ERROR" if code == 2 else "NOT_CONFIGURED", "reason": reason}))
        return code

    if not MIN_TIMEOUT_S <= args.timeout <= MAX_TIMEOUT_S:
        return fail("bad_timeout")
    commit = git(args.repo, "rev-parse", "--verify", args.commit + "^{commit}").stdout.strip()
    if not remote.SHA.fullmatch(commit):
        return fail("bad_commit")
    try:
        scope, _ = build_scope(args.repo, args.base, args.java, args.integration, args.vitest)
    except ValueError:
        return fail("empty_scope")
    host = host_name()
    if not host:
        return fail("no_host", 3)

    def stop(signum, frame):
        raise Stop(128 + signum)
    for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, stop)
    try:
        return run(args.repo, commit, scope, args.timeout, host)
    except Stop as stopped:
        print(json.dumps({"status": "ERROR", "reason": "interrupted"}))
        return stopped.code
    except (OSError, ValueError, subprocess.SubprocessError):
        return fail("runner")


if __name__ == "__main__":
    sys.exit(main())
