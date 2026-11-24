#!/usr/bin/env python3
"""Prepare/finalize one isolated in-cluster scan; never print raw scanner output."""
from datetime import date, datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import uuid

from security_summary import main as summarize

ROOT = Path("/var/lib/nexus-security")
WORK = Path("/work")
SOURCE = Path("/source")


def prepare():
    os.umask(0o077)
    mode = os.environ["SCAN_MODE"]
    waiting = mode == "gate" and os.environ.get("WAIT_FOR_IMAGE") == "1"
    # Pre-merge gate (PO 2026-11-23): the candidate snapshot is staged but never published as `current`; it is named
    # explicitly, must equal EXPECTED_COMMIT, and only the image the request names is scanned (no deployed inventory).
    staged = os.environ.get("SOURCE_COMMIT")
    if staged is not None and (not waiting or not os.environ.get("EXPECTED_COMMIT")):
        raise ValueError("pre-merge source requires the split gate")
    # Deploy gate whose tree-determined scans a pre-merge PASS of the same tree covers (security_host.start_deploy_gate
    # decides, security_host.gate re-checks): only the Git history and the images are scanned here.
    reused = os.environ.get("TREE_SCANS_REUSED") == "1"
    if reused and (not waiting or staged is not None or not os.environ.get("EXPECTED_COMMIT")):
        raise ValueError("source reuse requires the split deploy gate")
    source = SOURCE / (staged if staged is not None else (SOURCE / "current").read_text().strip())
    if not re.fullmatch(r"[a-f0-9]{40}", source.name) or not (source / ".git").is_dir():
        raise ValueError("invalid source snapshot")
    if os.environ.get("EXPECTED_COMMIT") and source.name != os.environ["EXPECTED_COMMIT"]:
        raise ValueError("source/build mismatch")
    if staged is None:
        inventory = json.loads((SOURCE / "images.json").read_text())
        age = datetime.now(timezone.utc) - datetime.fromisoformat(inventory["generated_at"])
        if age.total_seconds() < 0 or age > timedelta(hours=2):
            raise ValueError("stale deployment inventory")
    images = [] if waiting else os.environ["GATE_IMAGE"].split() if mode == "gate" else inventory["images"]
    if (not images and not waiting) or not all(re.fullmatch(r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i) for i in images):
        raise ValueError("images must be local and digest pinned")
    WORK.mkdir(exist_ok=True)
    (WORK / "source-start").write_text(str(int(time.time())))
    (WORK / "source-commit").write_text(source.name)
    (WORK / "images").write_text("\n".join(sorted(set(images))) + "\n")
    source_reports = [dict(tool="gitleaks", file="gitleaks-history.json")] if reused else [
        dict(tool="semgrep", file="semgrep.json"),
        dict(tool="gitleaks", file="gitleaks-tree.json"),
        dict(tool="gitleaks", file="gitleaks-history.json"),
        dict(tool="trivy", file="trivy-fs.json"),
        dict(tool="trivy", file="trivy-config.json"),
    ]
    manifest = ([dict(tool="zap", file="zap.json")] if mode == "dast" else source_reports + [
        dict(tool="trivy", file=f"trivy-image-{i}.json", target=image.split("@", 1)[0])
        for i, image in enumerate(sorted(set(images)))])
    (WORK / "manifest.json").write_text(json.dumps(manifest))
    day = datetime.now(timezone.utc).astimezone().date().isoformat()
    # A reduced run is never trend evidence for a full gate (finish() looks for "<mode>-*" of the previous day).
    output = ROOT / day / (("gatereduced" if reused else mode) + "-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    (WORK / "output").write_text(str(output))


def await_image(timeout=None, sleep=time.sleep, monotonic=time.monotonic, image_file=Path("/gate-image/request")):
    """Keep one isolated work directory and one unchanged final gate decision."""
    if timeout is None:
        # The pre-merge gate waits as long as a preview may run (security_manifests.job(await_timeout=...)).
        timeout = int(os.environ.get("AWAIT_IMAGE_TIMEOUT_S", "3000"))
        if not 60 <= timeout <= 4 * 3600:
            raise ValueError("invalid image wait")
    print("TIMING security_source", max(0, int(time.time()) - int((WORK / "source-start").read_text())))
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        if image_file.is_file():
            request = json.loads(image_file.read_text())
            # One image (`image`) or every image the build produced (`images`, the first equal to `image`).
            images = request.get("images", [request["image"]])
            if (request["commit"] != (WORK / "source-commit").read_text() or type(images) is not list or not images
                    or images[0] != request["image"] or len(set(images)) != len(images) or not all(
                        type(i) is str and re.fullmatch(
                            r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i)
                        for i in images)):
                raise ValueError("source/build mismatch or invalid digest")
            # Same order and numbering as prepare(): trivy-image-<i> is line <i> of /work/images.
            images = sorted(images)
            manifest = json.loads((WORK / "manifest.json").read_text())
            manifest += [dict(tool="trivy", file=f"trivy-image-{i}.json", target=image.split("@", 1)[0])
                         for i, image in enumerate(images)]
            (WORK / "manifest.json").write_text(json.dumps(manifest))
            (WORK / "images").write_text("\n".join(images) + "\n")
            return
        sleep(1)
    raise ValueError("image build timed out")


def finish():
    os.umask(0o077)
    mode = os.environ["SCAN_MODE"]
    output = Path((WORK / "output").read_text())
    previous = ROOT / (date.fromisoformat(output.parent.name) - timedelta(days=1)).isoformat()
    # Only complete runs of the same scope are trend evidence; gate never pollutes daily history.
    candidates = []
    # A reduced deploy gate has no source findings to compare: no trend, so nothing is miscounted as "fixed".
    for path in ([] if output.name.startswith("gatereduced-") else previous.glob(mode + "-*/findings.json")):
        try:
            if json.loads((path.parent / "summary.json").read_text())["scan_errors"] == 0:
                candidates.append(path)
        except (OSError, ValueError, TypeError, KeyError):
            continue
    candidates.sort(key=lambda p: p.stat().st_mtime)
    args = ["--reports", str(WORK), "--baseline", str(SOURCE / (WORK / "source-commit").read_text()
            / "security/baseline.yaml"), "--output", str(output)]
    if candidates:
        args += ["--previous", str(candidates[-1])]
    # Suppress intermediate output: only the final counts may enter Job logs.
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()):
        rc = summarize(args)
    summary = json.loads((output / "summary.json").read_text())
    if mode != "dast":
        for i, _ in enumerate((WORK / "images").read_text().splitlines()):
            try:
                if (WORK / f"sbom-{i}.json.exit").read_text().strip() != "0":
                    raise ValueError("SBOM failed")
                sbom = json.loads((WORK / f"sbom-{i}.json").read_text())
                if sbom.get("bomFormat") != "CycloneDX":
                    raise ValueError("invalid SBOM")
                (output / f"sbom-{i}.json").write_text(json.dumps(sbom))
            except (OSError, ValueError, TypeError):
                summary["scan_errors"] += 1
                summary["passed"] = False
                rc = 1
    (output / "summary.json").write_text(json.dumps(summary) + "\n")
    # Only counts and a bounded identity list (no source, matches or secrets) cross into the service's read-only mount.
    if mode != "gate":
        notifications = ROOT / "notifications"
        notifications.mkdir(exist_ok=True)
        notifications.chmod(0o750)
        dest = notifications / (output.parent.name + "-" + output.name + ".json")
        temporary = dest.with_suffix(".tmp")
        notification = dict(summary)
        try:
            # HIGH/CRITICAL/MEDIUM list: rule ids and locations, bounded; absent when the projection failed.
            notification.update(json.loads((output / "report-findings.json").read_text()))
        except (OSError, ValueError):
            pass
        temporary.write_text(json.dumps(notification) + "\n")
        temporary.chmod(0o640)
        temporary.replace(dest)
    cutoff = date.today() - timedelta(days=90)
    for path in ROOT.iterdir():
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", path.name) and not path.is_symlink() and date.fromisoformat(path.name) < cutoff:
            shutil.rmtree(path)
    for path in (ROOT / "notifications").glob("*.json"):
        if date.fromisoformat(path.name[:10]) < cutoff:
            path.unlink()
    print(json.dumps(summary))
    return rc


if __name__ == "__main__":
    try:
        raise SystemExit({"prepare": prepare, "await-image": await_image, "finish": finish}[sys.argv[1]]())
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"passed": False, "scan_errors": 1, "error": "SECURITY_SCAN_INCOMPLETE", "cause": type(sys.exc_info()[1]).__name__}))
        raise SystemExit(2)
