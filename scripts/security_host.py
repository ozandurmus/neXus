#!/usr/bin/env python3
"""Host-side source streaming and security Job control. Run only by the deployment owner."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

from security_manifests import job, loader, settings


def run(argv, **kwargs):
    result = subprocess.run(argv, capture_output=True, timeout=1200, **kwargs)
    if result.returncode:
        raise RuntimeError("security host command failed: " + argv[0])
    return result.stdout


def kubectl(*args, **kwargs):
    return run(["kubectl", "-n", "ui2-security", *args], **kwargs)


def snapshot(config, repo, rules):
    """Same tar -> kubectl exec -> PVC mechanism as the image builder, including safe Git history."""
    commit = run(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid commit")
    if run(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no", "--", ".",
            ":(exclude)project/deploy_info.json"], text=True).strip():
        raise ValueError("security source must be committed")
    deployments = json.loads(run(["kubectl", "-n", "ui2", "get", "deployments", "-o", "json"]))
    images = sorted({c["image"] for d in deployments["items"] for c in d["spec"]["template"]["spec"]["containers"]})
    if not images or not all(re.fullmatch(r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i) for i in images):
        raise ValueError("deployment image is not a local digest")
    kubectl("apply", "-f", "-", input=json.dumps(loader(config)).encode())
    try:
        kubectl("wait", "--for=condition=Ready", "pod/security-source-loader", "--timeout=120s")
        with tempfile.TemporaryDirectory(prefix="security-source-") as tmp:
            root = Path(tmp)
            bundle = root / "history.bundle"
            run(["git", "-C", str(repo), "bundle", "create", str(bundle), "--all"])
            run(["git", "clone", "--no-checkout", str(bundle), str(root / commit)])
            run(["git", "-C", str(root / commit), "checkout", "--detach", commit])
            run(["git", "-C", str(root / commit), "fetch", "--no-write-fetch-head", str(bundle),
                 "+refs/*:refs/security-history/*"])
            run(["git", "-C", str(root / commit), "remote", "remove", "origin"])
            # No repository remote URLs, hooks, local config, credentials or working-tree debris are copied.
            import shutil
            shutil.copytree(rules, root / "rules")
            for pack in ("java", "typescript", "owasp-top-ten", "secrets"):
                if not (root / "rules" / (pack + ".yaml")).is_file():
                    raise ValueError("offline Semgrep rule pack missing")
            (root / "current.next").write_text(commit)
            (root / "images.next").write_text(json.dumps(dict(images=images, generated_at=datetime.now(timezone.utc).isoformat())))
            # Existing commit directories are immutable. Stage under a temporary name, then publish pointers last.
            archive = root / "context.tar"
            run(["tar", "-cf", str(archive), "-C", str(root), commit, "rules", "current.next", "images.next"])
            with archive.open("rb") as stream:
                proc = subprocess.run(["kubectl", "-n", "ui2-security", "exec", "-i", "security-source-loader", "--",
                                       "sh", "-c", "mkdir -p /source/incoming && tar --no-same-owner -xf - -C /source/incoming"],
                                      stdin=stream, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
                if proc.returncode:
                    raise RuntimeError("source streaming failed")
            kubectl("exec", "security-source-loader", "--", "sh", "-c",
                    'cd /source && c=$(cat incoming/current.next) && '
                    'if [ -d "$c" ]; then rm -rf "incoming/$c"; else mv "incoming/$c" "$c"; fi && '
                    'mkdir -p rules && cp incoming/rules/*.yaml rules/ && '
                    'if [ -f current ]; then touch "$(cat current)"; fi && '
                    'mv incoming/images.next images.json && mv incoming/current.next current')
            # Protect the previous/current snapshot for two days (Jobs have a one-hour deadline).
            kubectl("exec", "security-source-loader", "--", "python3", "-c",
                    'from pathlib import Path; import re, shutil, time\n'
                    'root = Path("/source"); current = (root / "current").read_text().strip()\n'
                    'for p in root.iterdir():\n'
                    ' if re.fullmatch(r"[a-f0-9]{40}", p.name) and p.name != current and p.is_dir() '
                    'and not p.is_symlink() and p.stat().st_mtime < time.time() - 172800:\n'
                    '  shutil.rmtree(p)\n')
    finally:
        kubectl("delete", "pod", "security-source-loader", "--ignore-not-found", "--wait=true")
    return commit


def safe_summary(text):
    try:
        summary = json.loads(text.strip())
        from security_summary import TOOLS, SEVERITIES
        clean = {"passed": summary["passed"], "scan_errors": summary["scan_errors"],
                 "blocking": summary["blocking"], "counts": summary["counts"]}
        if type(clean["passed"]) is not bool or any(type(clean[k]) is not int or clean[k] < 0 for k in ("scan_errors", "blocking")):
            raise ValueError("invalid summary")
        if set(clean["counts"]) != set(TOOLS):
            raise ValueError("invalid tool counts")
        for values in clean["counts"].values():
            if set(values) != set(SEVERITIES):
                raise ValueError("invalid severity counts")
            for counts in values.values():
                if set(counts) != {"new", "fixed", "accepted", "existing"} or any(type(v) is not int or v < 0 for v in counts.values()):
                    raise ValueError("invalid count")
        blocking = sum(values[s][kind] for values in clean["counts"].values()
                       for s in ("UNKNOWN", "HIGH", "CRITICAL") for kind in ("new", "existing"))
        if blocking != clean["blocking"] or clean["passed"] != (blocking == 0 and clean["scan_errors"] == 0):
            raise ValueError("inconsistent gate verdict")
        return clean
    except (ValueError, TypeError, KeyError, AttributeError):
        raise RuntimeError("security gate missing or malformed summary") from None


def wait_gate(get_job, get_summary, timeout=3600, sleep=time.sleep, monotonic=time.monotonic):
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        try:
            status = get_job().get("status", {})
            conditions = {c["type"] for c in status.get("conditions", []) if c.get("status") == "True"}
        except (ValueError, TypeError, AttributeError, KeyError):
            raise RuntimeError("security Job status malformed; rollout refused") from None
        if conditions & {"Complete", "Failed"}:
            clean = safe_summary(get_summary())
            print(json.dumps(clean))
            if "Complete" not in conditions or not clean["passed"] or clean["scan_errors"] or clean["blocking"]:
                raise RuntimeError("security gate refused rollout")
            return
        sleep(5)
    raise RuntimeError("security gate timed out; rollout refused")


def gate(config, image, commit):
    manifest = job(config, "gate", image, commit)
    # A fresh name prevents a stale successful Job from approving a later build.
    import uuid
    name = "security-gate-" + uuid.uuid4().hex[:12]
    manifest["metadata"]["name"] = name
    kubectl("create", "-f", "-", input=json.dumps(manifest).encode())
    wait_gate(lambda: json.loads(kubectl("get", "job", name, "-o", "json")),
              lambda: kubectl("logs", "job/" + name, "-c", "summary", text=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "gate"))
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--rules", type=Path)
    parser.add_argument("--image")
    parser.add_argument("--commit")
    args = parser.parse_args()
    try:
        config = settings(json.loads(args.config.read_text()))
        if args.action == "snapshot":
            if not args.rules:
                raise ValueError("offline rule cache required")
            import fcntl
            with args.config.with_suffix(".lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                snapshot(config, args.repo, args.rules)
        else:
            gate(config, args.image, args.commit)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit("SECURITY GATE FAILED: incomplete scan or unaccepted findings; rollout refused")
