#!/usr/bin/env python3
"""Build the two side images (Packet Analysis engine, script execution) at the same time; host-side only.

Both are built FROM the main image this deploy just produced, by digest (docs/design/PACKET_ANALYSIS_ENGINE_IMAGE.md;
SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b) item 8), so neither can start before the main digest exists and
neither can be skipped: the main digest is new on every deploy (project/deploy_info.json names the commit), and a side
image from an earlier deploy would carry that deploy's jar. They do not depend on each other, though, and until
2026-11-23 they ran one after the other (about 41 s each). This tool runs the two unchanged builders
(hosta_packet_analysis_image.build, hosta_script_exec_image.build) in two threads and waits for both.

Failure semantics are exactly run_build.sh's before: a failed Packet Analysis image fails the deploy (exit 1, after the
other build has ended, so no Job is left half-watched); a failed script execution image is optional -- the deploy goes
on and run_build.sh drops script-runner from the rollout.

  hosta_side_images.py build --commit <sha> --service-image <main@digest> [--repo <path>] [--packet-analysis] [--script-exec]

stdout carries only `PA_IMAGE=<ref>` and `SX_IMAGE=<ref>` lines (each image that was built); stderr carries the
`TIMING packet_analysis_image`, `TIMING script_exec_image` and `TIMING side_images` lines and the failure notes, which
reach the deploy log (run_build.sh's stderr) even when the Packet Analysis build fails.
"""
import argparse
from pathlib import Path
import re
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hosta_packet_analysis_image as packet_analysis  # noqa: E402
import hosta_script_exec_image as script_exec  # noqa: E402

REPOSITORY = packet_analysis.REPOSITORY
IMAGE = re.compile(re.escape(REPOSITORY) + r"@sha256:[a-f0-9]{64}")
# (key, TIMING name, failure note); the order is the order of the output lines.
SIDES = (("PA_IMAGE", "packet_analysis_image",
          "PACKET ANALYSIS IMAGE: build failed or unavailable; details withheld "
          "(kubectl -n ui2-build logs job/" + packet_analysis.NAME + ")"),
         ("SX_IMAGE", "script_exec_image",
          "SCRIPT EXEC IMAGE: build failed or unavailable; details withheld "
          "(kubectl -n ui2-build logs job/" + script_exec.NAME + ")"))
REQUIRED = {"PA_IMAGE": True, "SX_IMAGE": False}


def build_all(commit, base, selected, builders=None, clock=time.monotonic, err=None, repo=Path("/opt/nexus")):
    """Run the selected builders at the same time; return ({key: image ref}, {key: failed}). Never raises for a build
    failure: every selected build is waited for, and each gets its own TIMING line (seconds, whole)."""
    err = err or sys.stderr
    builders = builders or {"PA_IMAGE": packet_analysis.build, "SX_IMAGE": script_exec.build}
    if not re.fullmatch(r"[a-f0-9]{40}", commit or ""):
        raise ValueError("invalid commit")
    if not IMAGE.fullmatch(base or ""):
        raise ValueError("the base must be the main image by digest")
    repo_path = Path(repo)
    images, failed, lock = {}, {}, threading.Lock()
    started = clock()

    def one(key, timing):
        begun = clock()
        try:
            digest = builders[key](commit, repo_path, base)
            ref = REPOSITORY + "@" + digest
            if not IMAGE.fullmatch(ref) or ref == base:
                raise RuntimeError("unexpected digest")
            with lock:
                images[key] = ref
        except BaseException:  # any error (the builders raise OSError, ValueError, RuntimeError, ...) fails this build;
            # a thread never dies silently
            with lock:
                failed[key] = True
        finally:
            with lock:
                print("TIMING %s %d" % (timing, round(clock() - begun)), file=err, flush=True)

    threads = [threading.Thread(target=one, args=(key, timing), daemon=True)
               for key, timing, _ in SIDES if key in selected]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    for key, _, note in SIDES:
        if failed.get(key):
            print(note, file=err, flush=True)
    print("TIMING side_images %d" % round(clock() - started), file=err, flush=True)
    return images, failed


def main(argv=None, builders=None, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("action", choices=("build",))
    p.add_argument("--commit", required=True)
    p.add_argument("--repo", default="/opt/nexus")
    p.add_argument("--service-image", required=True, help="the main image, " + REPOSITORY + "@sha256:<digest>")
    p.add_argument("--packet-analysis", action="store_true", help="build the Packet Analysis engine image (required)")
    p.add_argument("--script-exec", action="store_true", help="build the script execution image (optional)")
    args = p.parse_args(argv)
    selected = {key for key, flag in (("PA_IMAGE", args.packet_analysis), ("SX_IMAGE", args.script_exec)) if flag}
    if not selected:
        print("no side image selected", file=err)
        return 64
    try:
        images, failed = build_all(args.commit, args.service_image, selected, builders=builders, err=err,
                                   repo=Path(args.repo))
    except ValueError as error:
        print("SIDE IMAGES: " + str(error), file=err)
        return 1
    for key, _, _ in SIDES:
        if key in images:
            print(key + "=" + images[key], file=out, flush=True)
    # A required image that failed (or returned nothing usable) fails the deploy, as the sequential build did.
    if any(REQUIRED[key] and key not in images for key in selected):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
