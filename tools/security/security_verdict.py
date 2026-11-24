#!/usr/bin/env python3
"""Pre-merge security gate verdicts (PO decision 2026-11-23): bound to the commit, its tree, the gate policy, the exact
image digests that were scanned and the gate day.

The ship starts the security gate (source scan + image scan, the same Job, tools and blocking rules as the deploy gate)
on the batch commit beside the preview, before the merge. The verdict it leaves on the host is a small JSON record,
stored under the TREE it scanned (a rebase or squash merge gives the merged commit a new hash, never a new tree):

  {"schema": SCHEMA, "commit": <40 hex>, "tree": <40 hex>, "policy": <sha256 hex>, "images": [<ref@sha256:...>, ...],
   "created": <unix s>, "day": <YYYY-MM-DD in GATE_TZ>, "summary": <security_host.safe_summary dict>}

`day` is the gate day BEFORE the Job was created; the verdict is stored only when the day is still the same once the
Job has finished, so the accepted-finding review dates the summary evaluated belong to exactly that day.

Two consumers, both fail-closed:
- the ship, before the merge (`await_ship_verdict`): only a PASS for exactly this commit and tree lets the merge go on;
  FAIL, a malformed, unreadable or foreign verdict, or no verdict within the timeout stops the ship. NOT_CONFIGURED /
  UNAVAILABLE are accepted only while the pre-gate has never been seen working (`required=False`); once it has, they
  stop the merge as well.
- the deploy (`source_reuse_decision`), checked when its gate Job is created AND again when that Job has passed: the
  stored PASS replaces only the TREE-DETERMINED scans (semgrep, gitleaks over the working tree, trivy fs/config) when
  the tree and the gate policy are unchanged, the verdict is fresh and was made on the same gate day. The deploy Job
  still scans the Git history and EVERY image digest it is about to roll out (the re-stamped service image, the
  analysis and script execution images): those are never the preview's digests. Anything else -> the full gate, as
  before this change, and the deploy says why.
Nothing here relaxes a blocking rule: a verdict's `passed` is re-derived through safe_summary, never trusted as is.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time

SCHEMA = "nexus.security_verdict.v3"
MAX_AGE_S = 2 * 3600
# The scanner containers run with TZ=Europe/Istanbul (security_manifests.container) and security_summary.load_baseline
# expires accepted findings on that calendar day; a verdict is reusable on the same day only.
GATE_TZ = "Europe/Istanbul"
SHA = re.compile(r"[0-9a-f]{40}")
IMAGE = re.compile(r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[0-9a-f]{64}")
# The files that decide how the gate scans and judges: the scripts its Job runs, and security_manifests.py, which
# renders that Job (its stages -- TREE_STAGES among them --, container commands, mounts and the TZ the summary judges
# in). A change to any of them changes the policy digest.
SCANNER_SCRIPTS = ("security_scan.sh", "security_summary.py", "security_run.py", "security_dast.py",
                   "security_manifests.py")
HERE = Path(__file__).resolve().parent


def gate_day(now=time.time) -> str:
    """The calendar day in the scanner containers' zone. No tz database -> RuntimeError: a host-local fallback would
    disagree with the containers for hours every night, so callers then simply do not store or reuse a verdict."""
    try:
        from zoneinfo import ZoneInfo
        zone = ZoneInfo(GATE_TZ)
    except Exception:  # noqa: BLE001 -- ZoneInfoNotFoundError, missing module, broken tzdata: all mean "unknown"
        raise RuntimeError("gate time zone unavailable") from None
    return datetime.fromtimestamp(now(), timezone.utc).astimezone(zone).date().isoformat()


def scanner_digest(tools: Path = HERE) -> str:
    h = hashlib.sha256()
    for name in SCANNER_SCRIPTS:
        h.update(name.encode() + b"\0" + (Path(tools) / name).read_bytes() + b"\0")
    return h.hexdigest()


def _rules_digest(rules: Path) -> bytes:
    h = hashlib.sha256()
    for path in sorted(p for p in Path(rules).rglob("*") if p.is_file()):
        h.update(b"\0" + str(path.relative_to(rules)).encode() + b"\0" + path.read_bytes())
    return h.digest()


def policy_digest(config, rules: Path, tools: Path = HERE) -> str:
    """sha256 over the gate configuration (accepted CVEs, thresholds), every offline rule file in name order, and the
    scanner scripts and Job manifest module (SCANNER_SCRIPTS) that run and judge the scan. `config` is the file's path
    or the exact bytes the gate was configured with."""
    h = hashlib.sha256()
    h.update(b"config\0" + (bytes(config) if isinstance(config, (bytes, bytearray)) else Path(config).read_bytes()))
    for path in sorted(p for p in Path(rules).rglob("*") if p.is_file()):
        h.update(b"\0" + str(path.relative_to(rules)).encode() + b"\0" + path.read_bytes())
    h.update(b"\0scanners\0" + scanner_digest(tools).encode())
    return h.hexdigest()


def staged_tools_digest(directory: Path) -> str:
    """Content address of a staged pre-gate tools directory: the scanner scripts and its rules/ copy. The pre-gate Job
    mounts exactly pregate-tools/<this digest>, so the name can never point at other scripts or rules."""
    return hashlib.sha256(b"scanners\0" + scanner_digest(directory).encode() + b"\0rules\0"
                          + _rules_digest(Path(directory) / "rules")).hexdigest()


def _safe_summary(summary) -> dict:
    import sys
    if str(HERE) not in sys.path:
        sys.path.insert(0, str(HERE))
    from security_host import safe_summary
    return safe_summary(json.dumps(summary))


def make_verdict(commit: str, tree: str, policy: str, images, summary: dict, now=time.time,
                 day: str | None = None) -> dict:
    """`day`: the gate day the scan was judged on (security_host.pregate passes the day before the Job was created)."""
    verdict = {"schema": SCHEMA, "commit": commit, "tree": tree, "policy": policy, "images": sorted(images),
               "created": int(now()), "day": day if day is not None else gate_day(now), "summary": summary}
    validate(verdict)
    return verdict


def validate(verdict) -> dict:
    """Return the re-derived summary of a well-formed verdict; raise ValueError for anything else."""
    if not isinstance(verdict, dict) or verdict.get("schema") != SCHEMA:
        raise ValueError("not a security verdict")
    for key in ("commit", "tree"):
        if not SHA.fullmatch(str(verdict.get(key))):
            raise ValueError("invalid " + key)
    if not re.fullmatch(r"[0-9a-f]{64}", str(verdict.get("policy"))):
        raise ValueError("invalid policy digest")
    images = verdict.get("images")
    if (not isinstance(images, list) or not images or len(set(images)) != len(images)
            or not all(isinstance(i, str) and IMAGE.fullmatch(i) for i in images)):
        raise ValueError("invalid image list")
    if type(verdict.get("created")) is not int:
        raise ValueError("invalid timestamp")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(verdict.get("day"))):
        raise ValueError("invalid gate day")
    try:
        return _safe_summary(verdict.get("summary"))
    except RuntimeError:
        raise ValueError("invalid summary") from None


def verdict_file(directory: Path, tree: str) -> Path:
    """Verdicts are stored under the scanned TREE (the merged commit has a new hash after a rebase/squash merge)."""
    if not SHA.fullmatch(tree or ""):
        raise ValueError("invalid tree")
    return Path(directory) / (tree + ".json")


def load(path: Path) -> tuple[object, str]:
    """(verdict, "") or (None, why it could not be used)."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except FileNotFoundError:
        return None, "no pre-merge verdict for this tree"
    except (OSError, ValueError) as error:
        return None, "pre-merge verdict unreadable (" + type(error).__name__ + ")"
    try:
        verdict = json.loads(text)
        validate(verdict)
        return verdict, ""
    except ValueError as error:
        return None, "verdict rejected: " + str(error)[:80]


def source_reuse_decision(verdict, tree: str, policy: str, now=time.time, max_age: int = MAX_AGE_S,
                          missing: str = "no pre-merge verdict for this tree") -> tuple[bool, str]:
    """(True, why) only when a stored PASS covers the tree-determined scans of what the deploy is about to roll out.

    Called when the deploy gate Job is created and again after it has passed; both must say True. Images and Git
    history are never covered: the deploy Job scans them itself."""
    if verdict is None:
        return False, missing
    try:
        summary = validate(verdict)
    except ValueError as error:
        return False, "verdict rejected: " + str(error)
    if not summary["passed"] or summary["blocking"] or summary["scan_errors"]:
        return False, "verdict is not a pass"
    if verdict["tree"] != tree:
        return False, "source tree differs from the scanned tree"
    if verdict["policy"] != policy:
        return False, "gate policy (configuration, rules, scanner scripts or Job manifests) changed since the scan"
    age = now() - verdict["created"]
    if age < 0:
        return False, "verdict is from the future (clock skew)"
    if age > max_age:
        return False, "verdict is stale"
    try:
        today = gate_day(now)
    except RuntimeError as error:
        return False, str(error)
    if verdict["day"] != today:
        return False, "gate day changed since the scan (accepted-finding review dates are evaluated per day)"
    return True, "tree, policy and gate day match the pre-merge source scan"


def await_ship_verdict(path: Path, commit: str, tree: str, timeout: float, poll: float = 10.0,
                       sleep=time.sleep, monotonic=time.monotonic, required: bool = True) -> str:
    """Block the merge until the pre-merge gate left its verdict at `path` (written by the ship's pregate runner).

    First line `PASS <commit> <tree>` -> "PASS". `NOT_CONFIGURED` / `UNAVAILABLE` (no gate on the host, or host tooling
    from before the pre-merge action) -> that word only while `required` is False (bootstrap); once the pre-gate is
    configured they stop the merge. Anything else -- FAIL, a verdict for another commit or tree, garbage, an unreadable
    file, no file before `timeout` -- raises SystemExit and nothing merges."""
    deadline = monotonic() + timeout
    while True:
        try:
            lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
            first = lines[0].strip() if lines else ""
        except FileNotFoundError:
            first = ""
        except OSError as error:
            raise SystemExit("security gate verdict unreadable (" + type(error).__name__ + "); not merged") from None
        if first:
            if first == "PASS " + commit + " " + tree:
                return "PASS"
            if first in ("NOT_CONFIGURED", "UNAVAILABLE"):
                if not required:
                    return first
                raise SystemExit("security gate answered " + first + " although the pre-merge gate is configured; "
                                 "not merged")
            if first.startswith("PASS"):
                raise SystemExit("security gate verdict is bound to another commit or tree; not merged")
            raise SystemExit("security gate did not pass before the merge (" + first[:200] + "); not merged")
        if monotonic() >= deadline:
            raise SystemExit("security gate verdict still missing after " + str(int(timeout)) + " s; not merged")
        sleep(poll)
