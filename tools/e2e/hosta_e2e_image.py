#!/usr/bin/env python3
"""Build/reuse the runner from the deploy's exact context; host-side only."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time

NAME = "ui2-e2e-image-build"


def kubectl(*args, **kwargs):
    result = subprocess.run(["kubectl", "-n", "ui2-build", *args], capture_output=True,
                            text=True, timeout=120, **kwargs)
    if result.returncode:
        raise RuntimeError("E2E cluster operation failed")
    return result.stdout


def conditions(job):
    return {c["type"] for c in job.get("status", {}).get("conditions", []) if c.get("status") == "True"}


def matching(job, commit):
    metadata = job.get("metadata", {})
    try:
        age = datetime.now(timezone.utc) - datetime.fromisoformat(metadata["creationTimestamp"].replace("Z", "+00:00"))
        return (bool(re.fullmatch(r"[a-f0-9]{40}", commit))
                and metadata.get("annotations", {}).get("nexus/commit") == commit
                and timedelta(0) <= age <= timedelta(hours=2))
    except (KeyError, TypeError, ValueError):
        return False


def start(commit, repo):
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid commit")
    context = json.loads(kubectl("get", "configmap", "ui2-build-config", "-o", "json"))["data"]
    if context.get("commit") != commit:
        raise ValueError("E2E context does not match the deploy")
    # Inherit the actual host proxy and corp-ca setup; no private literals in generated source.
    build = json.loads(kubectl("create", "--dry-run=client", "-f", str(Path.home() / "build-job-proxy.yaml"), "-o", "json"))
    runner = json.loads(kubectl("create", "--dry-run=client", "-f", str(repo / "deploy/ui2-image-build/32-e2e-build-job.yaml"), "-o", "json"))
    build["metadata"] = dict(name=NAME, namespace="ui2-build", annotations={"nexus/commit": commit})
    build["spec"]["activeDeadlineSeconds"] = 1800
    pod = build["spec"]["template"]
    pod["metadata"] = dict(labels={"app.kubernetes.io/component": "e2e-image-build"})
    builder = pod["spec"]["containers"][0]
    builder["args"] = [a for a in runner["spec"]["template"]["spec"]["containers"][0]["args"]
                       if not a.startswith("--digest-file=")] + ["--digest-file=/dev/termination-log"]
    builder["terminationMessagePath"] = "/dev/termination-log"
    builder["terminationMessagePolicy"] = "File"
    # Freeze the tag too: a later ConfigMap update cannot retag this build.
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"] + [
        dict(name="UI2_IMAGE_TAG", value=commit[:12])]
    kubectl("delete", "job", NAME, "--ignore-not-found", "--wait=true")
    kubectl("create", "-f", "-", input=json.dumps(build))


def ensure(commit, repo, timeout=1800, sleep=time.sleep, monotonic=time.monotonic):
    current = json.loads(kubectl("get", "job", NAME, "--ignore-not-found", "-o", "json") or "{}")
    if not matching(current, commit) or "Failed" in conditions(current):
        start(commit, repo)
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        current = json.loads(kubectl("get", "job", NAME, "-o", "json"))
        if not matching(current, commit) or "Failed" in conditions(current):
            raise RuntimeError("E2E runner build failed or was replaced")
        if "Complete" in conditions(current):
            pods = json.loads(kubectl("get", "pods", "-l", "job-name=" + NAME, "-o", "json"))
            digests = set()
            for pod in pods["items"]:
                if not any(o.get("uid") == current["metadata"]["uid"] for o in pod["metadata"].get("ownerReferences", [])):
                    continue
                for status in pod.get("status", {}).get("containerStatuses", []):
                    ended = status.get("state", {}).get("terminated", {})
                    digest = ended.get("message", "").strip()
                    if status["name"] == current["spec"]["template"]["spec"]["containers"][0]["name"] and ended.get("exitCode") == 0 and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
                        digests.add(digest)
            if len(digests) != 1:
                raise RuntimeError("E2E runner digest missing or ambiguous")
            return digests.pop()
        sleep(5)
    raise RuntimeError("E2E runner build timed out")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("start", "ensure"))
    p.add_argument("--commit")
    p.add_argument("--repo", type=Path, default=Path.home() / "nexus")
    args = p.parse_args()
    try:
        commit = args.commit or json.loads((args.repo / "project/deploy_info.json").read_text())["commit"]
        if args.action == "start":
            start(commit, args.repo)
        else:
            print(ensure(commit, args.repo))
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        sys.exit("E2E: runner image unavailable; details withheld")
