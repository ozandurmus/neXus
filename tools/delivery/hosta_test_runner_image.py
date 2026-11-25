#!/usr/bin/env python3
"""Build the ui2-test runner image once, through the existing kaniko path; host-side only.

docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md (DRAFT). One Kaniko Job in ui2-build from the deploy's own context claim
and the host's own build-job template (proxy, corporate CA, tolerations) -- the same mechanics as
hosta_script_exec_image.py -- building ui2/Containerfile.test-runner (JDK 21, Node.js 22, the Gradle distribution, a
read-only Gradle dependency cache and the frontend node_modules). Pushed as nexus-ui2-test-runner:<commit12>; prints
the pushed reference by digest. With --record it also writes that reference to ~/.config/nexus/test-runner-image, the
one file hosta_test_run_remote.py reads. Not part of any deploy: the PO session runs it when a run answers IMAGE_STALE
(a dependency file changed) or the base image is refreshed.

  hosta_test_runner_image.py build --commit <sha of the loaded build context> [--record]
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

NAME = "ui2-test-runner-image-build"
REGISTRY = "registry.kube-system.svc.cluster.local"
REPOSITORY = REGISTRY + "/nexus-ui2-test-runner"
CONTAINERFILE = "/workspace/ui2/Containerfile.test-runner"
RECORD = Path.home() / ".config" / "nexus" / "test-runner-image"
CACHE_REPO = REGISTRY + "/nexus-ui2-build-cache"
# The seed resolves every dependency and runs npm ci: more memory than the side images.
RESOURCES = {"requests": {"cpu": "500m", "memory": "2Gi"}, "limits": {"cpu": "3", "memory": "6Gi"}}
BUILD_POLL_S = 5
DEADLINE_S = 3600
OWNED = ("--dockerfile=", "--destination=", "--digest-file=", "--cache=", "--cache-ttl=", "--target=",
         "--build-arg=", "--insecure-registry=", "--skip-tls-verify-registry=", "--image-download-retry=")


def builder_args(template_args, commit):
    if not re.fullmatch(r"[a-f0-9]{40}", commit or ""):
        raise ValueError("invalid commit")
    args = [a for a in template_args if not a.startswith(OWNED)]
    if not any(a.startswith("--cache-repo=") for a in args):
        args.append("--cache-repo=" + CACHE_REPO)
    return args + [
        "--dockerfile=" + CONTAINERFILE,
        "--destination=" + REPOSITORY + ":" + commit[:12],
        # Kaniko RUN steps see the build proxy only through the predefined proxy build args (2026-11-21).
        "--build-arg=HTTP_PROXY", "--build-arg=HTTPS_PROXY", "--build-arg=NO_PROXY",
        "--insecure-registry=" + REGISTRY,
        "--skip-tls-verify-registry=" + REGISTRY,
        "--cache=true",
        "--cache-ttl=336h",
        "--image-download-retry=5",
        "--digest-file=/dev/termination-log",
    ]


def job_for(template, commit):
    job = json.loads(json.dumps(template))
    job["metadata"] = dict(name=NAME, namespace="ui2-build", annotations={"nexus/commit": commit})
    job["spec"]["activeDeadlineSeconds"] = DEADLINE_S
    job["spec"]["backoffLimit"] = 0
    pod = job["spec"]["template"]
    pod["metadata"] = dict(labels={"app.kubernetes.io/part-of": "nexus-ui2",
                                   "app.kubernetes.io/component": "test-runner-image-build"})
    builder = pod["spec"]["containers"][0]
    builder["args"] = builder_args(builder.get("args", []), commit)
    builder["resources"] = RESOURCES
    builder["terminationMessagePath"] = "/dev/termination-log"
    builder["terminationMessagePolicy"] = "File"
    builder["env"] = [e for e in builder.get("env", []) if e["name"] != "UI2_IMAGE_TAG"] + [
        dict(name="UI2_IMAGE_TAG", value=commit[:12])]
    return job


def build(commit, sleep=time.sleep, monotonic=time.monotonic):
    context = json.loads(builds.kubectl("get", "configmap", "ui2-build-config", "-o", "json"))["data"]
    if context.get("commit") != commit:
        raise ValueError("the loaded build context is another commit")
    template = json.loads(builds.kubectl("create", "--dry-run=client", "-f", str(Path.home() / "build-job-proxy.yaml"),
                                         "-o", "json"))
    builds.kubectl("delete", "job", NAME, "--ignore-not-found", "--wait=true")
    builds.kubectl("create", "-f", "-", input=json.dumps(job_for(template, commit)))
    deadline = monotonic() + DEADLINE_S
    while monotonic() < deadline:
        current = json.loads(builds.kubectl("get", "job", NAME, "-o", "json"))
        if not builds.matching(current, commit) or "Failed" in builds.conditions(current):
            raise RuntimeError("test-runner image build failed or was replaced")
        if "Complete" in builds.conditions(current):
            digest = builds.job_digest(current, NAME)
            if digest:
                return REPOSITORY + "@" + digest
        sleep(BUILD_POLL_S)
    raise RuntimeError("test-runner image build timed out")


def record(reference, path=RECORD):
    if not re.fullmatch(re.escape(REPOSITORY) + r"@sha256:[a-f0-9]{64}", reference):
        raise ValueError("not an image reference by digest")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(reference + "\n")
    temporary.replace(path)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("action", choices=("build",))
    p.add_argument("--commit", required=True)
    p.add_argument("--record", action="store_true", help="write the reference to " + str(RECORD))
    args = p.parse_args()
    try:
        reference = build(args.commit)
        if args.record:
            record(reference)
        print(reference)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        sys.exit("TEST RUNNER IMAGE: build failed or unavailable; details withheld "
                 "(kubectl -n ui2-build logs job/" + NAME + ")")
