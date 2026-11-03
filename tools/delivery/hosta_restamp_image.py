#!/usr/bin/env python3
"""Reuse the service image a passing preview built for the SAME git tree; host-side only.

A preview (tools/e2e/hosta_preview_e2e.py) builds ui2/Containerfile from the candidate's sources and, after its e2e
passes, records the image digests under the candidate's git TREE hash. When the commit the deploy is about to build has
that same tree hash, its sources are identical, and the only thing a production build adds is project/deploy_info.json
(the deployed commit and build time). This tool therefore builds ui2/Containerfile.restamp -- one extra layer holding
that file -- on top of the recorded image instead of rebuilding everything (about 25 s instead of about 160 s).

Fail-safe by construction: any missing, malformed, stale or mismatching record, a registry that no longer has the
digest, or a failed Job exits non-zero WITHOUT output, and run_build.sh then runs the normal full build. The result is
a NEW image digest (the extra layer), so the security gate scans the exact image that is deployed; nothing is trusted
from the preview's own scan.

  hosta_restamp_image.py build --commit <sha> [--tree <tree sha>]   prints registry/nexus-ui2-service@sha256:<digest>
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
import hosta_e2e_image as builds  # noqa: E402 -- same namespace, same Job/digest mechanics as the e2e runner

NAME = "ui2-restamp-image-build"
REGISTRY = "registry.kube-system.svc.cluster.local"
REPOSITORY = REGISTRY + "/nexus-ui2-service"
CONTAINERFILE = "/workspace/ui2/Containerfile.restamp"
RECORDS = Path.home() / ".local/state/nexus-preview" / "reuse"
MAX_AGE_S = int(os.environ.get("NEXUS_REUSE_MAX_AGE_S", str(12 * 3600)))
DEADLINE_S = 900
# Owned by this tool; any template value for them is replaced, never merged.
OWNED = ("--dockerfile=", "--destination=", "--digest-file=", "--cache=", "--cache-ttl=", "--target=",
         "--build-arg=", "--insecure-registry=", "--skip-tls-verify-registry=")


def git_tree(repo, commit):
    tree = subprocess.run(["git", "-C", str(repo), "rev-parse", commit + "^{tree}"], capture_output=True, text=True,
                          timeout=60).stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", tree):
        raise ValueError("tree unavailable")
    return tree


def load_record(tree, directory=None, now=time.time):
    """The preview's record for exactly this tree, or None. Never trusts a file's name alone."""
    if not re.fullmatch(r"[0-9a-f]{40}", tree or ""):
        raise ValueError("invalid tree")
    path = (directory or RECORDS) / (tree + ".json")
    try:
        record = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    if (not isinstance(record, dict) or record.get("tree") != tree
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(record.get("service"))) or
            not isinstance(record.get("created"), int) or not 0 <= now() - record["created"] <= MAX_AGE_S):
        return None
    return record


def builder_args(template_args, commit, base):
    args = [a for a in template_args if not a.startswith(OWNED)]
    return args + [
        "--dockerfile=" + CONTAINERFILE,
        "--destination=" + REPOSITORY + ":" + commit[:12],
        "--build-arg=SERVICE_IMAGE=" + base,
        # The base is pulled from the in-cluster registry (plain HTTP), as the push already is.
        "--insecure-registry=" + REGISTRY,
        "--skip-tls-verify-registry=" + REGISTRY,
        "--cache=false",
        "--digest-file=/dev/termination-log",
    ]


def start(commit, tree, base):
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid commit")
    context = json.loads(builds.kubectl("get", "configmap", "ui2-build-config", "-o", "json"))["data"]
    if context.get("commit") != commit:
        raise ValueError("build context does not match the deploy")
    job = json.loads(builds.kubectl("create", "--dry-run=client", "-f", str(Path.home() / "build-job-proxy.yaml"),
                                    "-o", "json"))
    job["metadata"] = dict(name=NAME, namespace="ui2-build",
                           annotations={"nexus/commit": commit, "nexus/service-image": base, "nexus/tree": tree})
    job["spec"]["activeDeadlineSeconds"] = DEADLINE_S
    job["spec"]["backoffLimit"] = 0
    pod = job["spec"]["template"]
    pod["metadata"] = dict(labels={"app.kubernetes.io/part-of": "nexus-ui2",
                                   "app.kubernetes.io/component": "restamp-image-build"})
    builder = pod["spec"]["containers"][0]
    builder["args"] = builder_args(builder.get("args", []), commit, base)
    builder["terminationMessagePath"] = "/dev/termination-log"
    builder["terminationMessagePolicy"] = "File"
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"] + [
        dict(name="UI2_IMAGE_TAG", value=commit[:12])]
    builds.kubectl("delete", "job", NAME, "--ignore-not-found", "--wait=true")
    builds.kubectl("create", "-f", "-", input=json.dumps(job))


def same_build(job, commit, tree, base):
    annotations = job.get("metadata", {}).get("annotations", {})
    return (builds.matching(job, commit) and annotations.get("nexus/service-image") == base
            and annotations.get("nexus/tree") == tree)


def build(commit, tree, directory=None, timeout=DEADLINE_S, sleep=time.sleep, monotonic=time.monotonic,
          now=time.time):
    """The re-stamped digest for this commit, or None when the preview's image cannot be reused."""
    record = load_record(tree, directory, now)
    if record is None:
        return None
    base = REPOSITORY + "@" + record["service"]
    start(commit, tree, base)
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        current = json.loads(builds.kubectl("get", "job", NAME, "-o", "json"))
        if not same_build(current, commit, tree, base) or "Failed" in builds.conditions(current):
            return None
        if "Complete" in builds.conditions(current):
            digest = builds.job_digest(current, NAME)
            if digest:
                return digest
            # Job completion can precede the pod termination message becoming visible.
        sleep(3)
    return None


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("action", choices=("build",))
    p.add_argument("--commit", required=True)
    p.add_argument("--tree", help="default: the tree of --commit in --repo")
    p.add_argument("--repo", type=Path, default=Path.home() / "nexus")
    args = p.parse_args()
    try:
        tree = args.tree or git_tree(args.repo, args.commit)
        digest = build(args.commit, tree)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        sys.exit(2)
    if not digest:
        sys.exit(3)
    print(REPOSITORY + "@" + digest)
