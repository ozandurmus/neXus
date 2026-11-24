#!/usr/bin/env python3
"""Host-side source streaming and security Job control. Run only by the deployment owner."""
import argparse
import contextlib
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import shutil
import tempfile
import time
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

import security_manifests
from security_manifests import job, loader, settings

# The bytes of the manifest module THIS process renders its Jobs with (read once, at import). A pre-gate's policy digest
# hashes the staged copy of security_manifests.py; staging refuses a copy that is not these bytes, so a `git pull` of
# /opt/nexus after this driver started can never make the verdict claim manifests its Job was not rendered with.
LOADED_MANIFESTS = hashlib.sha256(Path(security_manifests.__file__).read_bytes()).hexdigest()


def run(argv, timeout=1200, **kwargs):
    result = subprocess.run(argv, capture_output=True, timeout=timeout, **kwargs)
    if result.returncode:
        raise RuntimeError("security host command failed: " + argv[0])
    return result.stdout


def kubectl(*args, **kwargs):
    return run(["kubectl", "-n", "ui2-security", *args], **kwargs)


HERE = Path(__file__).resolve().parent
TERMINATING = ("SIGTERM", "SIGHUP", "SIGINT")
RULE_PACKS = ("java", "typescript", "owasp-top-ten", "secrets")
LOADER = "security-source-loader"


class _deferred_signals:
    """Hold SIGTERM/SIGHUP/SIGINT while a Kubernetes object is created or deleted, then act on them.

    A cancelled pre-gate (its ssh session gone) must never stop between creating its Job/ConfigMap and recording that it
    owns them, nor in the middle of deleting them. A signal that arrives inside the block is recorded and, once the block
    has finished, re-raised as SystemExit(128 + signum) -- the exit the pregate's installed handler gives."""

    def __enter__(self):
        import signal
        self.received, self.previous = [], {}
        for name in TERMINATING:
            signum = getattr(signal, name)
            try:
                self.previous[signum] = signal.signal(signum, lambda n, frame: self.received.append(n))
            except ValueError:  # not the main thread: the caller owns signal handling
                pass
        return self

    def __exit__(self, kind, value, traceback):
        import signal
        for signum, handler in self.previous.items():
            signal.signal(signum, handler)
        if self.received and kind is None:
            raise SystemExit(128 + self.received[0])
        return False


def _check_rule_packs(rules):
    for pack in RULE_PACKS:
        if not (Path(rules) / (pack + ".yaml")).is_file():
            raise ValueError("offline Semgrep rule pack missing")


def _stage_pregate_tools(root, config_bytes, rules, attempts=3, sleep=time.sleep):
    """Copy THIS host's scanner scripts and offline rules into root/pregate-tools/<id>; return (id, policy), both computed
    from the copy that is staged -- never from the live directories a concurrent `git pull` of /opt/nexus may be
    rewriting meanwhile. A copy is accepted only when the live scripts were the same before and after copying."""
    import security_verdict
    for _ in range(attempts):
        before = security_verdict.scanner_digest(HERE)
        staging = Path(tempfile.mkdtemp(prefix="tools-", dir=str(root)))
        for name in security_verdict.SCANNER_SCRIPTS:
            shutil.copyfile(HERE / name, staging / name)
        shutil.copytree(rules, staging / "rules")
        _check_rule_packs(staging / "rules")
        if security_verdict.scanner_digest(staging) == before == security_verdict.scanner_digest(HERE):
            staged = hashlib.sha256((staging / "security_manifests.py").read_bytes()).hexdigest()
            if staged != LOADED_MANIFESTS:
                shutil.rmtree(staging)
                raise RuntimeError("gate manifests changed since this pre-gate started")
            tools_id = security_verdict.staged_tools_digest(staging)
            policy = security_verdict.policy_digest(config_bytes, staging / "rules", staging)
            (root / "pregate-tools").mkdir(exist_ok=True)
            staging.chmod(0o755)  # mkdtemp's 0700 would follow the tar onto the volume
            staging.rename(root / "pregate-tools" / tools_id)
            return tools_id, policy
        shutil.rmtree(staging)
        sleep(2)
    raise RuntimeError("scanner scripts kept changing while staging")


@contextlib.contextmanager
def _source_loader(config):
    """The loader pod that writes the security-source volume, created and deleted signal-safely.

    Callers hold the gate configuration lock (security.lock): no other loader is live, so a pod or an incoming/ tree
    left by a run that was killed between bytecodes is stale and is removed first. Creation and deletion run with
    SIGTERM/SIGHUP/SIGINT held (_deferred_signals): a cancelled pre-gate never stops after the pod exists but before
    the `finally` that deletes it is armed."""
    with _deferred_signals():
        kubectl("delete", "pod", LOADER, "--ignore-not-found", "--wait=true", timeout=60)
    try:
        with _deferred_signals():
            kubectl("apply", "-f", "-", input=json.dumps(loader(config)).encode())
        kubectl("wait", "--for=condition=Ready", "pod/" + LOADER, "--timeout=120s")
        yield
    finally:
        with _deferred_signals():
            kubectl("delete", "pod", LOADER, "--ignore-not-found", "--wait=true", timeout=60)


def _stream(root, members):
    """tar `members` of `root` into a FRESH /source/incoming (anything a cancelled run left there is removed first)."""
    archive = root / "context.tar"
    run(["tar", "-cf", str(archive), "-C", str(root), *members])
    with archive.open("rb") as stream:
        proc = subprocess.run(["kubectl", "-n", "ui2-security", "exec", "-i", LOADER, "--",
                               "sh", "-c", "rm -rf /source/incoming && mkdir -p /source/incoming && "
                                           "tar --no-same-owner -xf - -C /source/incoming"],
                              stdin=stream, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
        if proc.returncode:
            raise RuntimeError("source streaming failed")


PUBLISH_RULES = 'mkdir -p rules && cp incoming/rules/*.yaml rules/ && rm -rf incoming/rules'


def publish_rules(config, rules):
    """Replace the shared /source/rules with the host's offline rules now (the deploy's fallback full rescan). The
    caller holds the configuration lock."""
    with _source_loader(config):
        with tempfile.TemporaryDirectory(prefix="security-rules-") as tmp:
            root = Path(tmp)
            shutil.copytree(rules, root / "rules")
            _check_rule_packs(root / "rules")
            _stream(root, ["rules"])
            kubectl("exec", LOADER, "--", "sh", "-c", "cd /source && " + PUBLISH_RULES)


def snapshot(config, repo, rules, publish=True, config_bytes=None):
    """Same tar -> kubectl exec -> PVC mechanism as the image builder, including safe Git history.

    `publish=False` (the pre-merge gate) stages the commit directory only: the `current` pointer, the shared rules and
    the deployed-image list the daily scan reads stay on the deployed release. It also stages THIS host's installed
    scanner scripts and offline rules under pregate-tools/<id> (content-addressed) so the candidate is judged by the
    deployed tools/security, never by its own, and returns (commit, id, policy digest of exactly that staged copy)."""
    commit = run(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid commit")
    if not publish and config_bytes is None:
        raise ValueError("pre-merge snapshot needs the exact gate configuration")
    if run(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no", "--", ".",
            ":(exclude)project/deploy_info.json"], text=True).strip():
        raise ValueError("security source must be committed")
    deployments = json.loads(run(["kubectl", "-n", "ui2", "get", "deployments", "-o", "json"]))
    images = sorted({c["image"] for d in deployments["items"] for c in d["spec"]["template"]["spec"]["containers"]})
    if not images or not all(re.fullmatch(r"registry\.kube-system\.svc\.cluster\.local/[a-z0-9/_-]+@sha256:[a-f0-9]{64}", i) for i in images):
        raise ValueError("deployment image is not a local digest")
    tools_id = policy = ""
    with _source_loader(config):
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
            (root / "current.next").write_text(commit)
            (root / "images.next").write_text(json.dumps(dict(images=images, generated_at=datetime.now(timezone.utc).isoformat())))
            members = [commit, "current.next", "images.next"]
            if publish:
                shutil.copytree(rules, root / "rules")
                _check_rule_packs(root / "rules")
                members.append("rules")
            else:
                tools_id, policy = _stage_pregate_tools(root, config_bytes, rules)
                members.append("pregate-tools")
            # Existing commit directories are immutable. Stage under a temporary name, then publish pointers last.
            _stream(root, members)
            kubectl("exec", LOADER, "--", "sh", "-c",
                    'cd /source && c=$(cat incoming/current.next) && '
                    'if [ -d "$c" ]; then rm -rf "incoming/$c"; else mv "incoming/$c" "$c"; fi && '
                    + (PUBLISH_RULES + ' && '
                       'if [ -f current ]; then touch "$(cat current)"; fi && '
                       'mv incoming/images.next images.json && mv incoming/current.next current' if publish else
                       # pregate-tools/<id> is content-addressed: an existing directory already holds these bytes.
                       'touch "$c" && rm -f incoming/images.next incoming/current.next && mkdir -p pregate-tools && '
                       't=pregate-tools/' + tools_id + ' && '
                       'if [ -d "$t" ]; then touch "$t"; else mv "incoming/$t" "$t"; fi && rm -rf incoming/pregate-tools'))
            # Protect the previous/current snapshot for two days (Jobs have a one-hour deadline).
            kubectl("exec", LOADER, "--", "python3", "-c",
                    'from pathlib import Path; import re, shutil, time\n'
                    'root = Path("/source"); pointer = root / "current"\n'
                    'current = pointer.read_text().strip() if pointer.is_file() else ""\n'
                    'for p in [*root.iterdir(), *((root / "pregate-tools").iterdir() if (root / "pregate-tools").is_dir() else ())]:\n'
                    ' if re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", p.name) and p.name != current and p.is_dir() '
                    'and not p.is_symlink() and p.stat().st_mtime < time.time() - 172800:\n'
                    '  shutil.rmtree(p)\n')
    return commit if publish else (commit, tools_id, policy)


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


def wait_gate(get_job, get_summary, timeout=3600, sleep=time.sleep, monotonic=time.monotonic, announce=True):
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        try:
            status = get_job().get("status", {})
            conditions = {c["type"] for c in status.get("conditions", []) if c.get("status") == "True"}
        except (ValueError, TypeError, AttributeError, KeyError):
            raise RuntimeError("security Job status malformed; rollout refused") from None
        if conditions & {"Complete", "Failed"}:
            clean = safe_summary(get_summary())
            failed = "Complete" not in conditions or not clean["passed"] or clean["scan_errors"] or clean["blocking"]
            if announce or failed:
                print(json.dumps(clean), flush=True)
            if failed:
                raise RuntimeError("security gate refused rollout")
            return clean
        sleep(2)
    raise RuntimeError("security gate timed out; rollout refused")


GATE_NAME = re.compile(r"security-gate-[a-f0-9]{12}")
SOURCE_REUSED = "nexus/source-reused-tree"
SOURCE_REUSED_POLICY = "nexus/source-reused-policy"


def new_gate_name():
    import uuid
    return "security-gate-" + uuid.uuid4().hex[:12]


def delete_gate(name):
    """Best effort, bounded: the gate Job and its request ConfigMap (the ConfigMap may not be owned by the Job yet)."""
    for kind in ("job", "configmap"):
        try:
            kubectl("delete", kind, name, "--ignore-not-found", "--wait=false", timeout=60)
        except (RuntimeError, OSError, subprocess.SubprocessError):
            pass


def start_gate(config, commit, pregate_tools=None, await_timeout=None, deadline=3600, name=None,
               source_reused_tree=None, source_reused_policy=None):
    """Create the request ConfigMap and the split gate Job. On ANY way out before both exist and are linked (an error,
    a SystemExit from a signal handler, KeyboardInterrupt) both are deleted: nothing is left waiting for an image."""
    name = name or new_gate_name()
    if not GATE_NAME.fullmatch(name):
        raise ValueError("invalid gate name")
    manifest = job(config, "gate", commit=commit, gate_name=name, pregate_tools=pregate_tools,
                   await_timeout=await_timeout, deadline=deadline, source_reused=source_reused_tree is not None)
    annotations = {"nexus/commit": commit}
    if source_reused_tree is not None:
        if not source_reused_policy or not re.fullmatch(r"[0-9a-f]{64}", source_reused_policy):
            raise ValueError("source reuse requires the policy digest")
        annotations[SOURCE_REUSED] = source_reused_tree
        annotations[SOURCE_REUSED_POLICY] = source_reused_policy
    manifest["metadata"].update(name=name, annotations=annotations)
    try:
        kubectl("create", "-f", "-", input=json.dumps(dict(apiVersion="v1", kind="ConfigMap",
                metadata=dict(name=name, namespace="ui2-security"), data={})).encode())
        kubectl("create", "-f", "-", input=json.dumps(manifest).encode())
        created = json.loads(kubectl("get", "job", name, "-o", "json"))
        # The request disappears with its Job under the existing retention policy.
        owner = dict(apiVersion="batch/v1", kind="Job", name=name, uid=created["metadata"]["uid"])
        kubectl("patch", "configmap", name, "--type=merge", "-p",
                json.dumps({"metadata": {"ownerReferences": [owner]}}))
    except BaseException:
        delete_gate(name)
        raise
    return name


VERDICTS = Path.home() / ".local/state/nexus-security/verdicts"
# Sticky: written by the first pre-merge gate run on this host. From then on hosta_security_pregate's remote side turns
# NOT_CONFIGURED / UNAVAILABLE into FAIL (the pre-gate is configured; its absence is an error, not a bootstrap).
PREGATE_MARKER = VERDICTS.parent / "pregate-enabled"
# The ship allows its preview 7200 s (standalone_orchestrate.PREVIEW_TIMEOUT_S); the image wait covers that, and the
# Job deadline adds the scan time on top, so a slow but passing preview is never cut off by the gate Job.
PREVIEW_IMAGE_WAIT_S = 7500
PREGATE_DEADLINE_S = PREVIEW_IMAGE_WAIT_S + 3600
VERDICT_KEEP_S = 2 * 86400


def source_reuse(config_path, repo, rules, commit, verdicts=None, now=time.time, config_bytes=None):
    """(tree or None, policy or None, why): may the deploy gate of `commit` skip its tree-determined scans?
    Never raises: any problem is a reason to scan everything."""
    import security_verdict
    try:
        sys.path.insert(0, str(HERE.parent / "delivery"))
        import hosta_restamp_image
        config_src = config_bytes if config_bytes is not None else config_path
        if not (config_src and repo and rules):
            return None, None, "no configuration, repository or rules given"
        tree = hosta_restamp_image.git_tree(repo, commit)
        verdict, missing = security_verdict.load(security_verdict.verdict_file(verdicts or VERDICTS, tree))
        policy = security_verdict.policy_digest(config_src, rules, HERE)
        ok, why = security_verdict.source_reuse_decision(
            verdict, tree, policy, now=now,
            missing=missing or "no pre-merge verdict for this tree")
        return (tree, policy, why) if ok else (None, None, why)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        return None, None, "reuse check error (" + type(error).__name__ + ")"


def start_deploy_gate(config, commit, config_path=None, repo=None, rules=None, verdicts=None, now=time.time,
                      config_bytes=None):
    """The deploy's split gate Job. When a pre-merge PASS of the same tree and policy exists (PO 2026-11-23) the Job runs
    only the Git history scan and the scans of every image the deploy produces; `gate` re-checks the verdict once the
    Job has passed. The decision and its reason go to stderr: stdout carries the Job name only."""
    tree, policy, why = source_reuse(config_path, repo, rules, commit, verdicts, now, config_bytes=config_bytes)
    print(json.dumps({"security_gate_source_reuse": tree is not None, "why": why}), file=sys.stderr, flush=True)
    return start_gate(config, commit, source_reused_tree=tree, source_reused_policy=policy)


def _refresh_for_rescan(config, config_path, rules):
    """Before the fallback full rescan: read and validate the host's configuration (security.json) again and publish
    its offline rules again, both under the configuration lock, and return the configuration the rescan must run with.
    A configuration or rules change after the deploy's snapshot is a reason for the rescan, so the rescan must not run
    with the scanner digests or judge with the rules of that snapshot. A configuration that is now missing or invalid
    raises (the rollout is refused), never a silent fall back to the configuration read at process start. Without the
    rules (an older run_build.sh) the rescan judges with the snapshot's rules, exactly as a deploy without reuse does."""
    import fcntl
    lock_path = Path(config_path).with_suffix(".lock") if config_path else None
    with (lock_path.open("w") if lock_path else contextlib.nullcontext()) as lock:
        if lock is not None:
            fcntl.flock(lock, fcntl.LOCK_EX)
        if config_path:
            config = settings(json.loads(Path(config_path).read_text()))
        if rules:
            publish_rules(config, rules)
    print(json.dumps({"security_gate_config_reloaded": bool(config_path)}), flush=True)
    if rules:
        print(json.dumps({"security_gate_rules_refreshed": True}), flush=True)
    else:
        print(json.dumps({"security_gate_rules_refreshed": False, "why": "no rules given"}), flush=True)
    return config


def gate(config, image, commit, name=None, config_path=None, repo=None, rules=None, verdicts=None, now=time.time):
    """`image` is one digest reference or a list of them (the main image first, then e.g. the analysis image).

    A split Job that skipped the tree-determined scans (annotation SOURCE_REUSED) passes only when the pre-merge
    verdict STILL covers it once the Job has passed (same tree, policy and gate day, still fresh); otherwise the full
    gate -- source and every image -- runs now in a fresh Job."""
    split = name is not None
    images = [image] if isinstance(image, str) else list(image or ())
    # Validate both inputs before publishing a digest or creating a Job.
    manifest = job(config, "gate", images, commit)
    reused_tree = None
    reused_policy = None
    if name:
        if not GATE_NAME.fullmatch(name):
            raise ValueError("invalid gate name")
        existing = json.loads(kubectl("get", "job", name, "-o", "json"))
        annotations = existing["metadata"].get("annotations", {})
        if annotations.get("nexus/commit") != commit:
            raise ValueError("source/build mismatch")
        reused_tree = annotations.get(SOURCE_REUSED)
        reused_policy = annotations.get(SOURCE_REUSED_POLICY)
        request = dict(image=images[0], commit=commit)
        if len(images) > 1:
            request["images"] = images
        kubectl("patch", "configmap", name, "--type=merge", "-p",
                json.dumps({"immutable": True, "data": {"request": json.dumps(request)}}))
    else:
        name = new_gate_name()
        manifest["metadata"]["name"] = name
        kubectl("create", "-f", "-", input=json.dumps(manifest).encode())
    clean = wait_gate(lambda: json.loads(kubectl("get", "job", name, "-o", "json")),
                      lambda: kubectl("logs", "job/" + name, "-c", "summary", text=True),
                      announce=reused_tree is None)
    if reused_tree is not None:
        tree, policy, why = source_reuse(config_path, repo, rules, commit, verdicts, now)
        still = (tree is not None and tree == reused_tree
                 and reused_policy is not None and policy == reused_policy)
        if tree is not None and tree == reused_tree and not still:
            why = "gate policy changed since this deploy Job was started"
        print(json.dumps({"security_gate_source": "reused" if still else "rescanned", "why": why}), flush=True)
        if not still:
            # The verdict no longer covers this deploy: scan everything, the images included, in a fresh full Job that
            # runs this commit's tools/security (security_manifests.job, gate mode) with the CURRENT configuration
            # (scanner digests, proxy, cache) and judges with the CURRENT rules.
            return gate(_refresh_for_rescan(config, config_path, rules), images, commit)
        print(json.dumps(clean), flush=True)
    if split:
        for stage in ("await-image", "trivy-image"):
            for line in kubectl("logs", "job/" + name, "-c", stage, text=True).splitlines():
                if re.fullmatch(r"TIMING security_(source|image) [0-9]+", line):
                    print(line)
    return clean


def _private_dir(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def write_verdict(verdicts, verdict, now=time.time):
    """Atomically store a verdict under its tree; unique temp names, owner-only modes, old files pruned."""
    import security_verdict
    _private_dir(verdicts)
    target = security_verdict.verdict_file(verdicts, verdict["tree"])
    fd, staged = tempfile.mkstemp(dir=str(verdicts), prefix="." + verdict["tree"][:12] + "-", suffix=".tmp")
    try:
        with open(fd, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(verdict))
        Path(staged).chmod(0o600)
        Path(staged).replace(target)
    except BaseException:
        Path(staged).unlink(missing_ok=True)
        raise
    for path in verdicts.iterdir():
        try:
            if path != target and path.stat().st_mtime < now() - VERDICT_KEEP_S:
                path.unlink()
        except OSError:
            continue
    return target


def wait_preview_record(commit, tree, started, load_record, wait_s, sleep=time.sleep, monotonic=time.monotonic):
    """The preview record THIS run's preview wrote for exactly this commit and tree -- never an older record of the same
    tree (a retried ship, a rebuilt batch): its commit must match and it must have been written strictly after
    `started` (unix s). There is no slack: a record of the same second as `started` may be a previous run's."""
    deadline = monotonic() + wait_s
    while True:
        record = load_record(tree)
        if record is not None and record.get("commit") == commit and record["created"] > started:
            return record
        if monotonic() >= deadline:
            raise RuntimeError("no preview image of this commit")
        sleep(5)


def pregate(config_path, repo, rules, commit, tree, verdicts=VERDICTS, wait_s=PREVIEW_IMAGE_WAIT_S,
            sleep=time.sleep, monotonic=time.monotonic, now=time.time, marker=None, not_before=None):
    """Pre-merge gate (PO 2026-11-23): the deploy gate's Job on the candidate commit, beside the preview.

    The source scan starts at once with this host's installed scanner scripts and rules (staged, content-addressed); the
    image part waits for the service image the passing preview of THIS commit recorded, then scans that exact digest.
    `not_before` is when the remote side's ssh session started (before the history upload, read from the same host
    clock the preview stamps its record with), so a preview that finishes while the history is still streaming is
    never missed, and a record written up to and including that second -- a previous run of the same commit, e.g. a
    ship retried right after its preview passed -- is never taken for this run's: this run's preview, started beside
    this session, needs minutes to build and verify its image. A PASS is returned; it is stored as a verdict bound to
    commit, tree, the policy of the staged copy and the gate day only when that day did not change during the scan. Any other
    way out deletes the Job and its ConfigMap, also when a signal arrives while they are being created."""
    import security_verdict
    sys.path.insert(0, str(HERE.parent / "delivery"))
    import hosta_restamp_image
    _private_dir(verdicts.parent)
    (marker or verdicts.parent / "pregate-enabled").touch(mode=0o600)
    # Without the anchor (an older remote side) the record must postdate this process's start; a record from that
    # very second still counts there, as before (`started` is one second earlier).
    started = int(now()) - 1 if not_before is None else min(int(not_before), int(now()))
    if run(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip() != commit:
        raise ValueError("candidate checkout is not the commit")
    if hosta_restamp_image.git_tree(repo, commit) != tree:
        raise ValueError("tree mismatch")
    # One read of the configuration: the Job is configured from, and the policy hashes, exactly these bytes.
    config_bytes = Path(config_path).read_bytes()
    config = settings(json.loads(config_bytes))
    import fcntl
    with Path(config_path).with_suffix(".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        _, tools_id, policy = snapshot(config, repo, rules, publish=False, config_bytes=config_bytes)
    name = new_gate_name()
    passed = False
    try:
        try:
            day = security_verdict.gate_day(now)  # before the Job exists: the summary judges on this day or later
        except RuntimeError:
            day = None
        with _deferred_signals():
            start_gate(config, commit, pregate_tools="pregate-tools/" + tools_id, await_timeout=wait_s,
                       deadline=wait_s + 3600, name=name)
        record = wait_preview_record(commit, tree, started, hosta_restamp_image.load_record, wait_s, sleep, monotonic)
        image = hosta_restamp_image.REPOSITORY + "@" + record["service"]
        clean = gate(config, [image], commit, name)
        try:
            same_day = day is not None and security_verdict.gate_day(now) == day
        except RuntimeError:
            same_day = False
        if same_day:
            write_verdict(verdicts, security_verdict.make_verdict(commit, tree, policy, [image], clean, now, day=day),
                          now)
        else:
            # The merge may go on (the scan passed); the deploy then simply scans everything again.
            print(json.dumps({"security_verdict_stored": False, "why": "gate day unknown or changed during the scan"}),
                  flush=True)
        passed = True
        return dict(commit=commit, tree=tree, stored=same_day)
    finally:
        if not passed:
            with _deferred_signals():
                delete_gate(name)


def _terminate_on_signal(signum, frame):
    raise SystemExit(128 + signum)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "start", "gate", "pregate"))
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--rules", type=Path)
    parser.add_argument("--image", action="append", help="repeatable: every image this build produced")
    parser.add_argument("--commit")
    parser.add_argument("--job-name")
    parser.add_argument("--tree")
    parser.add_argument("--not-before", type=int, help="pregate: when the host-side ssh session started (unix s)")
    args = parser.parse_args()
    try:
        config_bytes = args.config.read_bytes()
        config = settings(json.loads(config_bytes))
        if args.action == "snapshot":
            if not args.rules:
                raise ValueError("offline rule cache required")
            import fcntl
            with args.config.with_suffix(".lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                snapshot(config, args.repo, args.rules)
        elif args.action == "start":
            # Without --rules the Job is the full gate (no pre-merge reuse check).
            print(start_deploy_gate(config, args.commit, args.config, args.repo, args.rules,
                                    config_bytes=config_bytes))
        elif args.action == "pregate":
            if not args.rules:
                raise ValueError("offline rule cache required")
            # A cancelled ship (its ssh session gone) terminates this process: unwind so the gate Job is deleted.
            import signal
            for signum in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
                signal.signal(signum, _terminate_on_signal)
            verdict = pregate(args.config, args.repo, args.rules, args.commit, args.tree, not_before=args.not_before)
            print("PREGATE: PASS " + verdict["commit"] + " " + verdict["tree"], flush=True)
        else:
            gate(config, args.image, args.commit, args.job_name, args.config, args.repo, args.rules)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit("SECURITY GATE FAILED: incomplete scan or unaccepted findings; rollout refused")
