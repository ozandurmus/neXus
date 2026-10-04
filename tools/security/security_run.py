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
    source = SOURCE / (SOURCE / "current").read_text().strip()
    if not re.fullmatch(r"[a-f0-9]{40}", source.name) or not (source / ".git").is_dir():
        raise ValueError("invalid source snapshot")
    if os.environ.get("EXPECTED_COMMIT") and source.name != os.environ["EXPECTED_COMMIT"]:
        raise ValueError("source/build mismatch")
    inventory = json.loads((SOURCE / "images.json").read_text())
    age = datetime.now(timezone.utc) - datetime.fromisoformat(inventory["generated_at"])
    if age.total_seconds() < 0 or age > timedelta(hours=2):
        raise ValueError("stale deployment inventory")
    waiting = mode == "gate" and os.environ.get("WAIT_FOR_IMAGE") == "1"
    images = [] if waiting else [os.environ["GATE_IMAGE"]] if mode == "gate" else inventory["images"]
    if (not images and not waiting) or not all(re.fullmatch(r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i) for i in images):
        raise ValueError("images must be local and digest pinned")
    WORK.mkdir(exist_ok=True)
    (WORK / "source-start").write_text(str(int(time.time())))
    (WORK / "source-commit").write_text(source.name)
    (WORK / "images").write_text("\n".join(sorted(set(images))) + "\n")
    manifest = ([dict(tool="zap", file="zap.json")] if mode == "dast" else [
        dict(tool="semgrep", file="semgrep.json"),
        dict(tool="gitleaks", file="gitleaks-tree.json"),
        dict(tool="gitleaks", file="gitleaks-history.json"),
        dict(tool="trivy", file="trivy-fs.json"),
        dict(tool="trivy", file="trivy-config.json"),
    ] + [dict(tool="trivy", file=f"trivy-image-{i}.json", target=image.split("@", 1)[0])
         for i, image in enumerate(sorted(set(images)))])
    (WORK / "manifest.json").write_text(json.dumps(manifest))
    day = datetime.now(timezone.utc).astimezone().date().isoformat()
    output = ROOT / day / (mode + "-" + uuid.uuid4().hex)
    output.mkdir(parents=True)
    (WORK / "output").write_text(str(output))


def await_image(timeout=3000, sleep=time.sleep, monotonic=time.monotonic, image_file=Path("/gate-image/request")):
    """Keep one isolated work directory and one unchanged final gate decision."""
    print("TIMING security_source", max(0, int(time.time()) - int((WORK / "source-start").read_text())))
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        if image_file.is_file():
            request = json.loads(image_file.read_text())
            image = request["image"]
            if request["commit"] != (WORK / "source-commit").read_text() or not re.fullmatch(
                    r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", image):
                raise ValueError("source/build mismatch or invalid digest")
            manifest = json.loads((WORK / "manifest.json").read_text())
            manifest.append(dict(tool="trivy", file="trivy-image-0.json", target=image.split("@", 1)[0]))
            (WORK / "manifest.json").write_text(json.dumps(manifest))
            (WORK / "images").write_text(image + "\n")
            return
        sleep(2)
    raise ValueError("image build timed out")


def finish():
    os.umask(0o077)
    mode = os.environ["SCAN_MODE"]
    output = Path((WORK / "output").read_text())
    previous = ROOT / (date.fromisoformat(output.parent.name) - timedelta(days=1)).isoformat()
    # Only complete runs of the same scope are trend evidence; gate never pollutes daily history.
    candidates = []
    for path in previous.glob(mode + "-*/findings.json"):
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
    # Only counts cross into the service's read-only notification mount.
    if mode != "gate":
        notifications = ROOT / "notifications"
        notifications.mkdir(exist_ok=True)
        notifications.chmod(0o750)
        dest = notifications / (output.parent.name + "-" + output.name + ".json")
        temporary = dest.with_suffix(".tmp")
        temporary.write_text(json.dumps(summary) + "\n")
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
