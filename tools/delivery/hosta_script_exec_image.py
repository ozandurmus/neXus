#!/usr/bin/env python3
"""Build the script execution image FROM the just-built main image; host-side only.

docs/design/SCRIPT_EXECUTION_CONTRACT.md section 7 and Amendment 2026-11-18 (b) item 8. One Kaniko Job in ui2-build,
from the deploy's own context claim and the host's own build-job template (proxy, corporate CA, tolerations), building
ui2/Containerfile.script-exec with the main image's digest as its base. Pushed as nexus-ui2-service:<commit12>-sx;
prints the pushed digest. The image runs only in the per-run script-run-<id> pods; the ui2-script-runner broker runs
the main image, and module_deploy.py hands this digest to it as UI2_SCRIPT_EXEC_IMAGE (--script-exec-image), so a
service-image rollout never replaces it.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
import hosta_e2e_image as builds  # noqa: E402 -- same namespace, same Job/digest mechanics as the e2e runner

NAME = "ui2-script-exec-image-build"
REGISTRY = "registry.kube-system.svc.cluster.local"
REPOSITORY = REGISTRY + "/nexus-ui2-service"
CONTAINERFILE = "/workspace/ui2/Containerfile.script-exec"
TAG_SUFFIX = "-sx"
CACHE_REPO = REGISTRY + "/nexus-ui2-build-cache"
RESOURCES = {"requests": {"cpu": "500m", "memory": "1Gi"}, "limits": {"cpu": "2", "memory": "4Gi"}}
DEADLINE_S = 2700
# Owned by this tool; any template value for them is replaced, never merged.
OWNED = ("--dockerfile=", "--destination=", "--digest-file=", "--cache=", "--cache-ttl=", "--target=",
         "--build-arg=", "--insecure-registry=", "--skip-tls-verify-registry=", "--image-download-retry=")


def service_image(value):
    if not re.fullmatch(re.escape(REPOSITORY) + r"@sha256:[a-f0-9]{64}", value or ""):
        raise ValueError("the base must be the main image by digest")
    return value


def builder_args(template_args, commit, base):
    args = [a for a in template_args if not a.startswith(OWNED)]
    if not any(a.startswith("--cache-repo=") for a in args):
        args.append("--cache-repo=" + CACHE_REPO)
    return args + [
        "--dockerfile=" + CONTAINERFILE,
        "--destination=" + REPOSITORY + ":" + commit[:12] + TAG_SUFFIX,
        "--build-arg=SERVICE_IMAGE=" + base,
        # Kaniko RUN steps see the build proxy only through the predefined proxy build args (2026-11-21).
        "--build-arg=HTTP_PROXY", "--build-arg=HTTPS_PROXY", "--build-arg=NO_PROXY",
        "--insecure-registry=" + REGISTRY,
        "--skip-tls-verify-registry=" + REGISTRY,
        "--cache=true",
        "--cache-ttl=336h",
        "--image-download-retry=5",
        "--digest-file=/dev/termination-log",
    ]


def start(commit, repo, base):
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid commit")
    service_image(base)
    context = json.loads(builds.kubectl("get", "configmap", "ui2-build-config", "-o", "json"))["data"]
    if context.get("commit") != commit:
        raise ValueError("build context does not match the deploy")
    job = json.loads(builds.kubectl("create", "--dry-run=client", "-f", str(Path.home() / "build-job-proxy.yaml"),
                                    "-o", "json"))
    job["metadata"] = dict(name=NAME, namespace="ui2-build",
                           annotations={"nexus/commit": commit, "nexus/service-image": base})
    job["spec"]["activeDeadlineSeconds"] = DEADLINE_S
    job["spec"]["backoffLimit"] = 0
    pod = job["spec"]["template"]
    pod["metadata"] = dict(labels={"app.kubernetes.io/part-of": "nexus-ui2",
                                   "app.kubernetes.io/component": "script-exec-image-build"})
    builder = pod["spec"]["containers"][0]
    builder["args"] = builder_args(builder.get("args", []), commit, base)
    builder["resources"] = RESOURCES
    builder["terminationMessagePath"] = "/dev/termination-log"
    builder["terminationMessagePolicy"] = "File"
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"] + [
        dict(name="UI2_IMAGE_TAG", value=commit[:12])]
    builds.kubectl("delete", "job", NAME, "--ignore-not-found", "--wait=true")
    builds.kubectl("create", "-f", "-", input=json.dumps(job))


def same_build(job, commit, base):
    return builds.matching(job, commit) and job["metadata"].get("annotations", {}).get("nexus/service-image") == base


def build(commit, repo, base, timeout=DEADLINE_S, sleep=time.sleep, monotonic=time.monotonic):
    """Reuse a fresh successful build of exactly this commit and base, else build; return the pushed digest."""
    service_image(base)
    current = json.loads(builds.kubectl("get", "job", NAME, "--ignore-not-found", "-o", "json") or "{}")
    if not same_build(current, commit, base) or "Failed" in builds.conditions(current):
        start(commit, repo, base)
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        current = json.loads(builds.kubectl("get", "job", NAME, "-o", "json"))
        if not same_build(current, commit, base) or "Failed" in builds.conditions(current):
            raise RuntimeError("script execution image build failed or was replaced")
        if "Complete" in builds.conditions(current):
            digest = builds.job_digest(current, NAME)
            if digest:
                return digest
        sleep(10)
    raise RuntimeError("script execution image build timed out")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("action", choices=("build",))
    p.add_argument("--commit", required=True)
    p.add_argument("--service-image", required=True, help="the main image, " + REPOSITORY + "@sha256:<digest>")
    p.add_argument("--repo", type=Path, default=Path("/opt/nexus"))
    args = p.parse_args()
    try:
        print(REPOSITORY + "@" + build(args.commit, args.repo, args.service_image))
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        sys.exit("SCRIPT EXEC IMAGE: build failed or unavailable; details withheld "
                 "(kubectl -n ui2-build logs job/" + NAME + ")")
