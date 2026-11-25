#!/usr/bin/env python3
"""Lane harness v1: the runner-enforced gate between "the agent exited 0" and the ship queue.

Design: docs/design/LANE_HARNESS_V1_2026_11_24.md (DRAFT until the PO session wires it). The ordered check list lives
in tools/delivery/checks.yaml; this file holds the logic a command line cannot express (git, queue and language rules)
and runs the manifest's commands. Every run writes `harness-report.json`, bound to the tree id of the branch it
judged, so a ship pretest can reuse a PASS for that exact tree instead of running the tests again.

  lane_harness.py --worktree DIR [--base origin/main] [--fetch] [--meta FILE] [--queue-dir DIR] [--report FILE]
                  [--profile lane|pretest] [--integration auto|local|hosta|skip] [--carrier mac|hosta]
                  [--reuse-report FILE] [--agent-log FILE --agent-exit N] [--no-commit-derived] [--keep-going]
                  [--allow-paths] [--jobs N] [--pytest-workers N]

Exit codes (typed, so the runner never has to grep text):
   0 pass            10 a check failed          12 conflict with fresh main     20 held for the Product Owner
  30 no change       40 agent permission denied 41 agent infrastructure error   50 harness error (retry; never the lane's fault)

Nothing here pushes, opens a PR or deploys, and it reaches a host only through the repository's own HOST-A test runner
(tools/delivery/hosta_test_run.py); the only commit it makes is a derived-file re-render on the lane's own branch. The
HOST-A hand-off (`--integration hosta`) runs that runner and nothing else.

`--carrier hosta` (PO 2026-11-25, option A: the laptop keeps only agents and runners) carries java_scoped, integration
and frontend_vitest to HOST-A's ui2-test namespace in ONE tools/delivery/hosta_test_run.py call with the scope the Mac
would have used; every other step stays on the Mac. A runner error (exit 2/3, no answer, a timeout) never blocks
delivery: the same steps run on the Mac and the report says `carrier: mac-fallback`."""
from __future__ import annotations

import argparse
import csv
import dataclasses
import fcntl
import glob
import hashlib
import io
import json
import os
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - requirements-dev.txt installs it
    yaml = None

REPO = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("checks.yaml")
REPORT_SCHEMA = "lane-harness-report/1"

EXIT_PASS, EXIT_CHECK_FAILED, EXIT_CONFLICT, EXIT_HELD = 0, 10, 12, 20
EXIT_NO_CHANGE, EXIT_AGENT_PERMISSION, EXIT_AGENT_INFRA, EXIT_HARNESS_ERROR = 30, 40, 41, 50
RESULT_NAMES = {EXIT_PASS: "PASS", EXIT_CHECK_FAILED: "CHECK_FAILED", EXIT_CONFLICT: "CONFLICT_WITH_MAIN",
                EXIT_HELD: "HELD_FOR_PO", EXIT_NO_CHANGE: "NO_CHANGE", EXIT_AGENT_PERMISSION: "AGENT_PERMISSION_DENIED",
                EXIT_AGENT_INFRA: "AGENT_INFRA_ERROR", EXIT_HARNESS_ERROR: "HARNESS_ERROR"}
# `cancelled` is a step stopped from outside (another group failed, or SIGTERM); it never carries an exit code itself.
# A step status that stops the run, and the exit code it carries. `warn`, `skipped`, `reused` and `pass` never stop it.
STATUS_EXIT = {"fail": EXIT_CHECK_FAILED, "conflict": EXIT_CONFLICT, "held": EXIT_HELD, "no_change": EXIT_NO_CHANGE,
               "agent_permission": EXIT_AGENT_PERMISSION, "agent_infra": EXIT_AGENT_INFRA, "error": EXIT_HARNESS_ERROR}

TAIL_LINES = 40
TAIL_LINE_CHARS = 300
AGENT_PERMISSION = re.compile(r"permission (?:was )?(?:auto-)?denied", re.I)
AGENT_INFRA = re.compile(r"quota|RESOURCE_EXHAUSTED|rate.?limit|usage limit|credits? (?:exhausted|exceeded)|\b429\b"
                         r"|empty conversation id|failed to start conversation|ECONNRESET|overloaded"
                         r"|(?:authentication|auth) (?:expired|failed)|timed out", re.I)
# Words that mark a test as security-relevant: never retried as flaky, whatever the manifest says (council item 9).
SECURITY_NAME = re.compile(r"security|authz|authoriz|credential|redact|sanitiz|transport|lease|admission|privacy|secret"
                           r"|encrypt|crypto|password|session|csrf|rbac|tls", re.I)


class HarnessError(Exception):
    """Something the harness itself could not do (missing tool, unreadable manifest); never blamed on the lane."""


# --------------------------------------------------------------------------------------------------------------------
# Small helpers


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def clip(text: str, lines: int = TAIL_LINES) -> str:
    kept = [line[:TAIL_LINE_CHARS] for line in text.splitlines()[-lines:]]
    return "\n".join(kept)


def read_tail(path: Path, size: int = 65536) -> str:
    try:
        with open(path, "rb") as handle:
            handle.seek(0, os.SEEK_END)
            handle.seek(max(0, handle.tell() - size))
            return handle.read().decode("utf-8", "replace")
    except OSError:
        return ""


def strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)


def glob_to_regex(pattern: str) -> re.Pattern:
    out, i = [], 0
    while i < len(pattern):
        c = pattern[i]
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
            continue
        out.append("[^/]*" if c == "*" else "[^/]" if c == "?" else re.escape(c))
        i += 1
    return re.compile("".join(out) + r"\Z")


def path_matches(path: str, pattern: str) -> bool:
    """`dir/` is a directory prefix, a pattern with * ? ** is a glob, anything else is that file or a directory."""
    pattern = pattern.strip()
    if not pattern:
        return False
    if pattern.endswith("/"):
        return path.startswith(pattern)
    if any(ch in pattern for ch in "*?"):
        return glob_to_regex(pattern).match(path) is not None
    return path == pattern or path.startswith(pattern + "/")


def parse_meta(path: Path | None) -> dict:
    """The lane `.meta` (key=value lines, as lane-runner.sh writes it) plus the v1 fields: paths=, area=, backlog_id=,
    accept=. `paths=` takes globs separated by commas/spaces and may repeat; every `accept=` line is one command."""
    meta = {"paths": [], "accept": [], "area": "", "backlog_id": "", "allow_paths": False, "name": "", "raw": {}}
    if path is None:
        return meta
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError as error:
        raise HarnessError("cannot read lane meta: %s" % error)
    for line in text.splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        meta["raw"][key] = value
        if key == "paths":
            meta["paths"] += [p for p in re.split(r"[,\s]+", value) if p]
        elif key == "accept" and value:
            meta["accept"].append(value)
        elif key in ("area", "backlog_id", "name"):
            meta[key] = value
        elif key == "allow_paths":
            meta["allow_paths"] = value.lower() in ("1", "true", "yes")
    return meta


def parse_ship_entry(path: Path) -> dict:
    entry = {}
    try:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, _, value = line.partition("=")
                entry[key.strip()] = value.strip()
    except OSError:
        pass
    return entry


# --------------------------------------------------------------------------------------------------------------------
# Result types


@dataclasses.dataclass
class Out:
    status: str            # pass | fail | warn | skipped | reused | error | held | conflict | no_change | agent_*
    detail: str = ""
    tail: str = ""
    extra: dict = dataclasses.field(default_factory=dict)
    duration_s: float | None = None     # set when the work ran elsewhere (a HOST-A phase), not while this step waited


@dataclasses.dataclass
class Context:
    worktree: Path
    manifest: dict
    manifest_sha: str
    args: argparse.Namespace
    meta: dict
    base_ref: str
    base_sha: str = ""
    merge_base: str = ""
    head: str = ""
    tree: str = ""
    branch: str = ""
    changed: list = dataclasses.field(default_factory=list)
    tier: str = "A"
    scope: dict | None = None
    frontend_built: bool = False
    derived_commits: list = dataclasses.field(default_factory=list)
    notes: list = dataclasses.field(default_factory=list)
    extras: dict = dataclasses.field(default_factory=dict)
    log_dir: Path = Path(".")
    reuse: dict | None = None
    # Concurrency: heavy groups run on threads, each command in its own process group.
    stop: threading.Event = dataclasses.field(default_factory=threading.Event)
    terminated: bool = False
    stop_reason: str = ""
    lock: threading.RLock = dataclasses.field(default_factory=threading.RLock)
    frontend_lock: threading.Lock = dataclasses.field(default_factory=threading.Lock)
    procs: set = dataclasses.field(default_factory=set)
    snapshot_dir: Path | None = None
    snapshot_tree: str = ""
    concurrent: bool = False
    # --carrier hosta: the one shared HOST-A run, made by whichever carried step needs it first.
    hosta_lock: threading.Lock = dataclasses.field(default_factory=threading.Lock)
    hosta: dict | None = None

    def say(self, message: str) -> None:
        """A progress line, only when groups run concurrently (the single-stream run stays as quiet as before)."""
        if self.concurrent:
            with self.lock:
                print(message, file=sys.stderr, flush=True)

    def stop_run(self, reason: str, terminated: bool = False) -> None:
        """Cancel the run: running commands are killed (process group), steps not yet started are not run."""
        with self.lock:
            if not self.stop.is_set():
                self.stop_reason = reason
            self.terminated = self.terminated or terminated
            self.stop.set()
            victims = list(self.procs)
        for proc in victims:
            self._kill(proc)

    @staticmethod
    def _kill(proc: subprocess.Popen) -> None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            try:
                proc.kill()
            except OSError:
                pass

    def pytest_workers(self) -> int:
        """xdist workers for the tooling suite: the workflow's `-n 2` on a lone run is far below a 10-core Mac, so the
        CPU budget is split with the other groups (`--pytest-workers` pins it)."""
        if getattr(self.args, "pytest_workers", 0):
            return self.args.pytest_workers
        cores = os.cpu_count() or 2
        return max(2, min(cores // 2, 6)) if self.args.jobs > 1 else max(2, min(cores - 2, 8))

    def snapshot(self) -> Path:
        """An export of HEAD (what the commit contains, as GitHub's clean checkout sees it); re-exported when HEAD moved."""
        with self.lock:
            if self.snapshot_dir and self.snapshot_tree == self.tree:
                return self.snapshot_dir
            self.close()
            target = Path(tempfile.mkdtemp(prefix="lane-harness-head-"))
            proc = subprocess.Popen(["git", "-C", str(self.worktree), "archive", "--format=tar", "HEAD"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:
                    tar.extractall(target, filter="data")
                err = proc.stderr.read().decode(errors="replace")
                if proc.wait() != 0:
                    raise HarnessError("git archive HEAD failed: " + err.strip()[:160])
            except (tarfile.TarError, OSError) as error:
                proc.kill()
                shutil.rmtree(target, ignore_errors=True)
                raise HarnessError("cannot export HEAD: %s" % str(error)[:160])
            except HarnessError:
                shutil.rmtree(target, ignore_errors=True)
                raise
            self.snapshot_dir, self.snapshot_tree = target, self.tree
            return target

    def close(self) -> None:
        if self.snapshot_dir:
            shutil.rmtree(self.snapshot_dir, ignore_errors=True)
            self.snapshot_dir = None

    def git(self, *argv: str, check: bool = False, input_text: str | None = None) -> subprocess.CompletedProcess:
        result = subprocess.run(["git", "-C", str(self.worktree), *argv], capture_output=True, text=True,
                                input=input_text, timeout=300)
        if check and result.returncode != 0:
            raise HarnessError("git %s failed: %s" % (" ".join(argv[:3]), result.stderr.strip()[:200]))
        return result

    def refresh(self) -> None:
        """Re-read HEAD, its tree id and the changed paths (after a derived-file commit moved the branch)."""
        self.head = self.git("rev-parse", "HEAD", check=True).stdout.strip()
        self.tree = self.git("rev-parse", "HEAD^{tree}", check=True).stdout.strip()
        self.merge_base = self.git("merge-base", self.base_sha, "HEAD").stdout.strip() or self.base_sha
        names = self.git("diff", "--name-only", "--no-renames", "-z", self.merge_base, "HEAD", check=True).stdout
        self.changed = sorted(p for p in names.split("\0") if p)
        self.scope = None

    def any_changed(self, when: str, when_not: str | None = None) -> bool:
        pattern, excluded = re.compile(when), re.compile(when_not) if when_not else None
        return any(pattern.search(p) and not (excluded and excluded.search(p)) for p in self.changed)

    def gradle_flags(self) -> list:
        raw = os.environ.get("LANE_HARNESS_GRADLE_FLAGS")
        return raw.split() if raw is not None else ["--no-daemon", "--console=plain"]

    def expand(self, argv: list) -> list:
        out = []
        for item in argv:
            if item == "{gradle_flags}":
                out += self.gradle_flags()
            else:
                text = str(item).replace("{python}", sys.executable).replace("{base}", self.base_ref) \
                    .replace("{worktree}", str(self.worktree)).replace("{pytest_workers}", str(self.pytest_workers()))
                if "{snapshot}" in text:
                    text = text.replace("{snapshot}", str(self.snapshot()))
                out.append(text)
        return out

    def run(self, argv: list, *, step_id: str, cwd: Path | None = None, env: dict | None = None,
            timeout: int = 900) -> tuple:
        """Run one command, output to logs/<step>.log. Returns (returncode, tail, timed_out). Raises Cancelled when the
        run was stopped from outside (another group failed, SIGTERM) while the command was running."""
        if self.stop.is_set():
            raise Cancelled(self.stop_reason)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        log = self.log_dir / (re.sub(r"[^A-Za-z0-9_.-]", "_", step_id) + ".log")
        full_env = dict(os.environ)
        full_env.update(env or {})
        with open(log, "wb") as handle:
            try:
                proc = subprocess.Popen(argv, cwd=str(cwd or self.worktree), env=full_env, stdout=handle,
                                        stderr=subprocess.STDOUT, start_new_session=True)
            except OSError as error:
                return 127, "cannot start %s: %s" % (argv[0], error), False
            with self.lock:
                self.procs.add(proc)
            if self.stop.is_set():          # stopped between the check above and the registration
                self._kill(proc)
            try:
                rc, timed_out = proc.wait(timeout=timeout), False
            except subprocess.TimeoutExpired:
                self._kill(proc)
                proc.wait()
                rc, timed_out = 124, True
            finally:
                with self.lock:
                    self.procs.discard(proc)
        if self.stop.is_set() and rc != 0:
            raise Cancelled(self.stop_reason)
        return rc, clip(strip_ansi(read_tail(log))), timed_out


class Cancelled(Exception):
    """The run was stopped from outside while a step was running."""


# --------------------------------------------------------------------------------------------------------------------
# Embedded PostgreSQL 16 (no Docker): the Zonky binaries Gradle already caches for other modules


class EmbeddedPostgresError(HarnessError):
    pass


def _platform_key() -> str:
    system, machine = platform.system(), platform.machine().lower()
    if system == "Darwin":
        return "darwin-arm64v8" if machine in ("arm64", "aarch64") else "darwin-amd64"
    if system == "Linux":
        return "linux-arm64v8" if machine in ("arm64", "aarch64") else "linux-amd64"
    raise EmbeddedPostgresError("no embedded PostgreSQL binaries for %s/%s" % (system, machine))


def find_pg_archive() -> Path:
    override = os.environ.get("LANE_HARNESS_PG_ARCHIVE")
    if override:
        if not Path(override).is_file():
            raise EmbeddedPostgresError("LANE_HARNESS_PG_ARCHIVE is not a file")
        return Path(override)
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    pattern = str(gradle_home / "caches/modules-2/files-2.1/io.zonky.test.postgres" /
                  ("embedded-postgres-binaries-%s" % _platform_key()) / "16.*" / "*" / "*.jar")
    jars = glob.glob(pattern)

    def version(path: str) -> tuple:
        return tuple(int(x) for x in re.findall(r"\d+", Path(path).parents[1].name))

    if not jars:
        raise EmbeddedPostgresError("no cached PostgreSQL 16 binaries (io.zonky.test.postgres) under the Gradle cache; "
                                    "run an integration build once or use --integration hosta")
    return Path(max(jars, key=version))


def provision_pg(cache_root: Path | None = None) -> Path:
    """Unpack the cached PostgreSQL 16 archive once into a shared directory and return its bin/ directory."""
    archive = find_pg_archive()
    cache_root = cache_root or Path(os.environ.get("LANE_HARNESS_CACHE") or Path.home() / ".cache/nexus-lane-harness")
    cache_root.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(str(archive).encode()).hexdigest()[:10]
    target = cache_root / ("pg16-%s" % key)
    marker = target / ".complete"
    with open(cache_root / ".lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not marker.exists():
            shutil.rmtree(target, ignore_errors=True)
            target.mkdir(parents=True)
            payload = archive
            holder = None
            if archive.suffix == ".jar":
                with zipfile.ZipFile(archive) as jar:
                    names = [n for n in jar.namelist() if n.endswith((".txz", ".tar.xz"))]
                    if not names:
                        raise EmbeddedPostgresError("the PostgreSQL archive holds no .txz payload")
                    holder = tempfile.TemporaryDirectory(prefix="lhpg-x-")
                    payload = Path(jar.extract(names[0], holder.name))
            try:
                with tarfile.open(payload, mode="r:xz") as tar:
                    tar.extractall(target, filter="data")
            finally:
                if holder:
                    holder.cleanup()
            marker.write_text("ok\n")
    if not (target / "bin" / "initdb").is_file():
        raise EmbeddedPostgresError("the unpacked PostgreSQL has no bin/initdb")
    return target / "bin"


class EmbeddedPostgres:
    """A throwaway PostgreSQL 16 on 127.0.0.1 with trust auth, started from the cached binaries. Same server settings
    as the workflow's container (max_connections=200, fsync off)."""

    def __init__(self, bin_dir: Path | None = None):
        self.bin_dir = bin_dir
        self.root: Path | None = None
        self.port = 0

    def __enter__(self) -> "EmbeddedPostgres":
        self.bin_dir = self.bin_dir or provision_pg()
        self.root = Path(tempfile.mkdtemp(prefix="lhpg-", dir="/tmp"))   # short path: unix socket limit
        data = self.root / "data"
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        init = subprocess.run([str(self.bin_dir / "initdb"), "-D", str(data), "-U", "postgres", "-A", "trust",
                               "-E", "UTF8", "--no-sync"], capture_output=True, text=True, timeout=180)
        if init.returncode != 0:
            self._cleanup()
            raise EmbeddedPostgresError("initdb failed: " + clip(init.stderr or init.stdout, 5))
        options = ("-p %d -c listen_addresses=127.0.0.1 -c unix_socket_directories=%s -c max_connections=200 "
                   "-c fsync=off -c synchronous_commit=off -c full_page_writes=off" % (self.port, self.root))
        start = subprocess.run([str(self.bin_dir / "pg_ctl"), "-D", str(data), "-o", options, "-l",
                                str(self.root / "pg.log"), "-w", "-t", "60", "start"],
                               capture_output=True, text=True, timeout=120)
        if start.returncode != 0:
            tail = clip(read_tail(self.root / "pg.log"), 8)
            self._cleanup()
            raise EmbeddedPostgresError("pg_ctl start failed: " + tail)
        return self

    @property
    def jdbc_url(self) -> str:
        return "jdbc:postgresql://127.0.0.1:%d/postgres?user=postgres" % self.port

    def _cleanup(self) -> None:
        if self.root:
            shutil.rmtree(self.root, ignore_errors=True)
            self.root = None

    def __exit__(self, *exc) -> None:
        if self.root and self.bin_dir:
            subprocess.run([str(self.bin_dir / "pg_ctl"), "-D", str(self.root / "data"), "-m", "immediate", "-w", "stop"],
                           capture_output=True, text=True, timeout=120)
        self._cleanup()


# --------------------------------------------------------------------------------------------------------------------
# Steps. Each takes (ctx, step) and returns Out.


def step_agent_outcome(ctx: Context, step: dict) -> Out:
    log_path, agent_exit = ctx.args.agent_log, ctx.args.agent_exit
    if log_path is None and agent_exit is None:
        return Out("skipped", "no agent log or exit code given")
    text = read_tail(Path(log_path), 200000) if log_path else ""
    ahead = int(ctx.git("rev-list", "--count", "%s..HEAD" % ctx.merge_base).stdout.strip() or 0)
    failed_run = agent_exit not in (None, 0, 3) or ahead == 0
    if failed_run and AGENT_PERMISSION.search(text):
        return Out("agent_permission", "the agent's permission request was auto-denied; extend its permissions and re-queue",
                   clip(text, 8))
    if failed_run and AGENT_INFRA.search(text):
        return Out("agent_infra", "quota, timeout or start error in the agent run; retry once or switch engine", clip(text, 8))
    if agent_exit not in (None, 0, 3):
        return Out("agent_infra", "the agent exited with status %s and no recognised marker" % agent_exit, clip(text, 8))
    return Out("pass", "agent exit %s" % agent_exit)


def step_preconditions(ctx: Context, step: dict) -> Out:
    ahead = int(ctx.git("rev-list", "--count", "%s..HEAD" % ctx.merge_base).stdout.strip() or 0)
    if ahead == 0 or not ctx.changed:
        return Out("no_change", "no commit or an empty diff against %s" % ctx.base_ref)
    dirty = [line for line in ctx.git("status", "--porcelain").stdout.splitlines() if line.strip()]
    if dirty:
        return Out("fail", "the working tree is not clean (%d entries): %s" % (len(dirty), "; ".join(dirty[:10])))
    bodies = ctx.git("log", "--format=%H%x00%B%x01", "%s..HEAD" % ctx.merge_base).stdout.split("\x01")
    missing = [b.split("\0", 1)[0][:10] for b in bodies if b.strip() and "Co-Authored-By:" not in b]
    extra = {"commits_without_trailer": missing} if missing else {}
    return Out("pass", "%d commit(s), %d changed path(s), clean tree" % (ahead, len(ctx.changed)), extra=extra)


def step_main_conflict(ctx: Context, step: dict) -> Out:
    result = ctx.git("merge-tree", "--write-tree", "--name-only", "--no-messages", ctx.base_sha, "HEAD")
    if result.returncode == 0:
        return Out("pass", "merges cleanly with %s" % ctx.base_ref)
    if result.returncode != 1:
        return Out("error", "git merge-tree failed (git >= 2.38 needed): " + clip(result.stderr, 3))
    lines = result.stdout.splitlines()[1:]
    files = []
    for line in lines:
        if not line.strip():
            break
        files.append(line.strip())
    generated = re.compile(ctx.manifest["generated_paths"])
    real = [f for f in files if not generated.search(f)]
    if files and not real:
        return Out("pass", "only rendered files differ from %s; they are re-rendered, not merged" % ctx.base_ref,
                   extra={"rendered_conflicts": files})
    return Out("conflict", "%d file(s) conflict with %s: %s" % (len(real), ctx.base_ref, ", ".join(real[:10])),
               extra={"conflict_files": real[:50]})


def step_guarded_paths(ctx: Context, step: dict) -> Out:
    guarded = re.compile(ctx.manifest["guarded_paths"])
    hits = [p for p in ctx.changed if guarded.search(p)]
    tier_b, excl = re.compile(ctx.manifest["tier_b_paths"]), re.compile(ctx.manifest["tier_b_exclude"])
    if hits:
        ctx.tier = "C"
    elif any(tier_b.search(p) and not excl.search(p) for p in ctx.changed):
        ctx.tier = "B"
    else:
        ctx.tier = "A"
    if hits and not (ctx.meta["allow_paths"] or ctx.args.allow_paths):
        return Out("held", "tier C: %d guarded path(s) need a person: %s" % (len(hits), ", ".join(hits[:8])),
                   extra={"guarded": hits[:50]})
    note = " (allow_paths set)" if hits else ""
    return Out("pass", "tier %s%s" % (ctx.tier, note), extra={"guarded": hits[:50]} if hits else {})


def step_declared_paths(ctx: Context, step: dict) -> Out:
    declared = ctx.meta["paths"]
    if not declared:
        return Out("warn" if ctx.args.require_declared else "skipped",
                   "no paths= declared in the lane meta" + ("; required" if ctx.args.require_declared else ""))
    implicit = [p for d in ctx.manifest.get("derived", []) for p in d.get("add", [])]
    outside = [p for p in ctx.changed if not any(path_matches(p, pat) for pat in declared + implicit)]
    if outside:
        return Out("fail", "%d changed path(s) outside the declared paths=: %s" % (len(outside), ", ".join(outside[:10])),
                   extra={"outside_declared": outside[:50]})
    return Out("pass", "all %d changed path(s) inside paths=%s" % (len(ctx.changed), ",".join(declared)))


# -- derived files -----------------------------------------------------------------------------------------------------

INVENTORY = "docs/repo_inventory.csv"
INVENTORY_ROW = ("tests/{name},false,true,true,file,LIVE_TOOLING,tools/tests/{name},live-test; discovery,"
                 ".github/workflows/validation.yml:375,Tests live Java delivery/governance or a retained tool; "
                 "retain coverage outside history.")


def repo_inventory_plan(root: Path) -> tuple:
    """(new_text or None, added names, removed names): one LIVE_TOOLING row per tools/tests/test_*.py, as
    test_live_tooling_discovery requires. Other rows are kept byte for byte."""
    path = root / INVENTORY
    if not path.is_file():
        return None, [], []
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    rows = list(csv.reader(io.StringIO(text)))
    if len(rows) != len(lines):
        raise HarnessError("docs/repo_inventory.csv has multi-line rows; the harness cannot edit it safely")
    on_disk = {p.name for p in (root / "tools/tests").glob("test_*.py")}
    header = rows[0]
    col = {name: i for i, name in enumerate(header)}
    live_rows = {}
    for index, row in enumerate(rows[1:], start=1):
        if row and row[col["path"]].startswith("tests/test_") and row[col["classification"]] == "LIVE_TOOLING":
            live_rows[Path(row[col["path"]]).name] = index
    # test_live_tooling_discovery pins these three without a row.
    pinned = {"test_gov_po_3_ci_privacy_gate_baseline.py", "test_live_project_governance.py", "test_live_tooling_discovery.py"}
    added = sorted(on_disk - set(live_rows) - pinned)
    removed = sorted(n for n in set(live_rows) - on_disk
                     if rows[live_rows[n]][col["proposed_destination"]].startswith("tools/tests/"))
    if not added and not removed:
        return None, [], []
    keep = [line for i, line in enumerate(lines) if not any(live_rows[n] == i for n in removed)]
    for name in added:
        new = INVENTORY_ROW.format(name=name)
        at = None
        for i, line in enumerate(keep):
            first = line.split(",", 1)[0]
            if first.startswith("tests/test_") and first <= "tests/" + name:
                at = i + 1
        if at is None:
            at = next((i for i, line in enumerate(keep) if line.startswith("tests/")), len(keep))
        keep.insert(at, new)
    return "\n".join(keep) + "\n", added, removed


def _porcelain_paths(ctx: Context) -> list:
    out = ctx.git("status", "--porcelain", "-z").stdout
    return [entry[3:] for entry in out.split("\0") if len(entry) > 3]


def step_derived_files(ctx: Context, step: dict) -> Out:
    done, stale_check_only, skipped = [], [], []
    for entry in ctx.manifest.get("derived", []):
        if entry.get("when") and not ctx.any_changed(entry["when"]):
            continue
        ident = entry["id"]
        if entry.get("builtin") == "repo_inventory":
            new_text, added, removed = repo_inventory_plan(ctx.worktree)
            if new_text is None:
                continue
            if ctx.args.no_commit_derived:
                stale_check_only.append(ident)
                continue
            (ctx.worktree / INVENTORY).write_text(new_text, encoding="utf-8")
        else:
            if entry.get("check"):
                rc, _, _ = ctx.run(ctx.expand(entry["check"]), step_id="derived-%s-check" % ident, timeout=300)
                if rc == 0:
                    continue
            elif ctx.args.no_commit_derived:
                skipped.append(ident)
                continue
            if ctx.args.no_commit_derived:
                stale_check_only.append(ident)
                continue
            env = {k: ctx.expand([v])[0] for k, v in (entry.get("render_env") or {}).items()}
            cwd = ctx.worktree / entry["render_cwd"] if entry.get("render_cwd") else None
            rc, tail, _ = ctx.run(ctx.expand(entry["render"]), step_id="derived-%s-render" % ident, cwd=cwd, env=env,
                                  timeout=1800)
            if rc == 0 and entry.get("after"):
                rc, tail, _ = ctx.run(ctx.expand(entry["after"]), step_id="derived-%s-after" % ident, timeout=300)
            if rc != 0:
                return Out("error", "re-rendering %s failed (exit %s)" % (ident, rc), tail)
        add = entry.get("add", [])
        dirty = _porcelain_paths(ctx)
        unexpected = [p for p in dirty if p not in add]
        if unexpected:
            return Out("error", "re-rendering %s touched unexpected paths: %s" % (ident, ", ".join(unexpected[:8])))
        if not dirty:
            continue
        ctx.git("add", "--", *add, check=True)
        identity = []
        if not ctx.git("config", "user.name").stdout.strip():
            identity = ["-c", "user.name=lane-harness", "-c", "user.email=lane-harness@" + "localhost.invalid"]
        trailer = "Co-Authored-By: %s <noreply@%s>" % (ctx.manifest["commit_trailer_model"], "anthropic" + ".com")
        message = "%s on the current main\n\n%s\n" % (entry["message"], trailer)
        ctx.git(*identity, "commit", "-q", "--no-verify", "-m", message, check=True)
        done.append(ident)
    ctx.refresh()
    ctx.derived_commits += done
    if stale_check_only:
        return Out("fail", "derived files are stale (check-only run): %s" % ", ".join(stale_check_only),
                   extra={"stale": stale_check_only})
    detail = "re-rendered and committed: %s" % ", ".join(done) if done else "all derived files current"
    if skipped:
        detail += "; not checkable without rendering: %s" % ", ".join(skipped)
    return Out("pass", detail, extra={"committed": done})


# -- static checks -----------------------------------------------------------------------------------------------------


def step_whitespace(ctx: Context, step: dict) -> Out:
    result = ctx.git("diff", "--check", ctx.merge_base, "HEAD")
    if result.returncode == 0:
        return Out("pass", "git diff --check clean")
    return Out("fail", "whitespace or conflict-marker findings", clip(result.stdout, 12))


def _secret_patterns() -> list:
    """High-signal shapes only, assembled at runtime so this file is not itself a finding."""
    return [("private key block", re.compile("-----BEGIN " + r"(?:[A-Z]+ )?PRIVATE " + "KEY-----")),
            ("cloud access key id", re.compile(r"\bAK" + r"IA[0-9A-Z]{16}\b")),
            ("source-host token", re.compile(r"\bgh[pousr]_" + r"[A-Za-z0-9]{36,}\b")),
            ("chat token", re.compile(r"\bxox[baprs]-" + r"[A-Za-z0-9-]{10,}"))]


def added_lines(ctx: Context, paths: list | None = None) -> list:
    """(path, line number, text) of every line the lane adds, binary files excluded."""
    argv = ["diff", "--unified=0", "--no-renames", "--no-color", ctx.merge_base, "HEAD"]
    if paths:
        argv += ["--", *paths]
    out, path, line_no, rows = ctx.git(*argv).stdout, None, 0, []
    for raw in out.splitlines():
        if raw.startswith("+++ "):
            path = raw[6:] if raw.startswith("+++ b/") else None
        elif raw.startswith("@@"):
            match = re.search(r"\+(\d+)", raw)
            line_no = int(match.group(1)) if match else 0
        elif raw.startswith("+") and path:
            rows.append((path, line_no, raw[1:]))
            line_no += 1
    return rows


def step_secret_scan(ctx: Context, step: dict) -> Out:
    findings, lines = [], added_lines(ctx)
    for label, pattern in _secret_patterns():
        for path, line_no, text in lines:
            if pattern.search(text):
                findings.append("%s:%d (%s)" % (path, line_no, label))
    if findings:
        return Out("fail", "secret-shaped content added (file:line, value withheld): " + "; ".join(findings[:10]),
                   extra={"findings": findings[:50]})
    scanner = shutil.which("gitleaks")
    if scanner is None:
        return Out("pass", "built-in scan clean; gitleaks is not installed here (the deploy gate runs it)",
                   extra={"gitleaks": "unavailable"})
    config = ctx.worktree / ".gitleaks.toml"
    argv = [scanner, "git", "--redact=100", "--no-banner", "--log-opts=%s..HEAD" % ctx.merge_base]
    if config.is_file():
        argv += ["--config", str(config)]
    rc, tail, _ = ctx.run(argv + ["."], step_id="gitleaks", timeout=600)
    if rc != 0:
        return Out("fail", "gitleaks reported a finding or failed; run it locally for the location", "", {"gitleaks": "fail"})
    return Out("pass", "built-in scan and gitleaks clean", extra={"gitleaks": "pass"})


def step_forbidden_tokens(ctx: Context, step: dict) -> Out:
    manifest = ctx.manifest
    tokens = ["".join(parts) for parts in manifest["forbidden_tokens_under_ui2"]]
    exclude = manifest["forbidden_tokens_exclude_dirs"]
    skip_suffix = tuple(manifest["forbidden_tokens_skip_suffixes"])
    exempt = {"ui2/Containerfile", "ui2/Containerfile.script-exec"}   # Ui2ArchitectureTest keeps its own exemptions
    names = ctx.git("diff", "--name-only", "--diff-filter=ACMR", "--no-renames", "-z", ctx.merge_base, "HEAD").stdout
    hits = []
    for path in sorted(p for p in names.split("\0") if p.startswith("ui2/")):
        if path in exempt or path.endswith(skip_suffix) or any("/%s/" % d in "/" + path for d in exclude):
            continue
        try:
            content = (ctx.worktree / path).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        hits += ['%s contains "%s"' % (path, t) for t in tokens if t in content]
    if hits:
        return Out("fail", "words the architecture test forbids under ui2/ (DIR-10): " + "; ".join(hits[:8]),
                   extra={"hits": hits[:50]})
    return Out("pass", "no forbidden word in the changed ui2/ files")


# -- migration number rule ---------------------------------------------------------------------------------------------


def _versions(names: list, pattern: re.Pattern) -> dict:
    out = {}
    for name in names:
        match = pattern.match(Path(name).name)
        if match:
            out.setdefault(int(match.group(1)), []).append(Path(name).name)
    return out


def queue_entries(ctx: Context) -> list | None:
    """The queued and in-flight ship entries other than this lane: [{name, branch, inflight, versions}]; None when no
    queue directory was given."""
    qdir = ctx.args.queue_dir
    if not qdir:
        return None
    qdir = Path(qdir)
    own = {ctx.meta["name"], ctx.args.lane_name or "", ctx.branch}
    own.discard("")
    mig = ctx.manifest["migrations"]
    pattern = re.compile(mig["pattern"])
    entries = []
    for inflight, files in ((False, sorted(qdir.glob("*.ship"))), (True, sorted(qdir.glob("inflight/*/*.ship")))):
        for file in files:
            entry = parse_ship_entry(file)
            branch, name = entry.get("branch", ""), entry.get("name", file.stem)
            if not branch or name in own or branch in own:
                continue
            added = ctx.git("diff", "--name-only", "--diff-filter=A", "%s...%s" % (ctx.base_sha, branch), "--", mig["dir"])
            versions = _versions(added.stdout.splitlines(), pattern) if added.returncode == 0 else {}
            entries.append({"name": name, "branch": branch, "inflight": inflight,
                            "versions": sorted(versions), "readable": added.returncode == 0})
    return entries


def step_migration_rule(ctx: Context, step: dict) -> Out:
    mig = ctx.manifest["migrations"]
    pattern = re.compile(mig["pattern"])
    touched = ctx.git("diff", "--name-status", "--no-renames", ctx.merge_base, "HEAD", "--", mig["dir"]).stdout.splitlines()
    added = [l.split("\t", 1)[1] for l in touched if l.startswith("A\t")]
    altered = [l.split("\t", 1)[1] for l in touched if l[:1] in ("M", "D")]
    if altered:
        return Out("fail", "an existing migration was edited or deleted (Flyway checksum would refuse it): "
                   + ", ".join(Path(p).name for p in altered[:8]))
    mine = _versions(added, pattern)
    stray = [Path(p).name for p in added if not pattern.match(Path(p).name)]
    if stray:
        return Out("fail", "migration file name does not follow V<version>__<description>.sql: " + ", ".join(stray[:5]))
    if not mine:
        return Out("skipped", "no new migration")
    duplicated = {v: n for v, n in mine.items() if len(n) > 1}
    if duplicated:
        return Out("fail", "two new migrations share a version: %s" % sorted(duplicated))
    listing = ctx.git("ls-tree", "--name-only", ctx.base_sha, mig["dir"] + "/").stdout.splitlines()
    main_versions = _versions(listing, pattern)
    main_max = max(main_versions, default=0)
    low = sorted(v for v in mine if v <= main_max)
    if low:
        return Out("fail", "new migration version(s) %s not above main's V%d (Flyway refuses an older one)"
                   % (", ".join("V%d" % v for v in low), main_max))
    entries = queue_entries(ctx)
    extra = {"new_versions": sorted(mine), "main_max": main_max}
    if entries is None:
        ctx.extras["migration"] = extra
        return Out("pass", "new %s above main's V%d; queue not given, order unchecked"
                   % (", ".join("V%d" % v for v in sorted(mine)), main_max), extra=extra)
    unreadable = [e["name"] for e in entries if not e["readable"]]
    order = [{"version": v, "entry": "(this lane)"} for v in sorted(mine)]
    taken = set(main_versions) | set(mine)
    for entry in entries:
        for version in entry["versions"]:
            order.append({"version": version, "entry": entry["name"], "inflight": entry["inflight"]})
            taken.add(version)
    clash = sorted((v, e["name"]) for e in entries for v in e["versions"] if v in mine)
    if clash:
        return Out("fail", "version clash with a queued entry: " + "; ".join("V%d in %s" % c for c in clash[:5]),
                   extra=extra)
    behind = sorted((v, e["name"]) for e in entries if e["inflight"] for v in e["versions"] if v > min(mine))
    if behind:
        return Out("fail", "an in-flight ship already carries a higher version (%s); this lane's V%d would merge out of order"
                   % ("; ".join("V%d in %s" % b for b in behind[:4]), min(mine)), extra=extra)
    order.sort(key=lambda item: item["version"])
    extra.update({"required_order": order, "unreadable_entries": unreadable})
    ctx.extras["migration"] = extra
    notes = []
    queued_other = [o for o in order if o["entry"] != "(this lane)"]
    if queued_other:
        notes.append("Flyway outOfOrder is off, so entries must merge in ascending version order: "
                     + " -> ".join("V%d(%s)" % (o["version"], o["entry"]) for o in order))
    gap = [v for v in range(main_max + 1, max(mine)) if v not in taken]
    if gap:
        notes.append("gap: V%s unused below this lane's V%d" % (",".join(map(str, gap[:5])), max(mine)))
        if mig.get("gap_policy") == "block":
            return Out("fail", notes[-1], extra=extra)
    if unreadable:
        notes.append("queue entries with a missing branch were not compared: " + ", ".join(unreadable[:5]))
    return Out("warn" if notes else "pass", "; ".join(notes) or "new %s in order after main's V%d"
               % (", ".join("V%d" % v for v in sorted(mine)), main_max), extra=extra)


# -- advisory checks ---------------------------------------------------------------------------------------------------

TEST_PATH = re.compile(r"(^|/)(tests?|__tests__)/|\.test\.[jt]sx?$|Test\.java$|(^|/)test_[^/]+\.py$")
ASSERT_LINE = re.compile(r"^\s*(assert\w*\(|assert |expect\(|verify\(|@Test\b|it\(|test\()")
SKIP_LINE = re.compile(r"@Disabled|@Ignore\b|\.skip\(|\bxit\(|\bxdescribe\(|it\.todo|pytest\.mark\.skip|pytest\.skip\(")


def step_test_integrity(ctx: Context, step: dict) -> Out:
    tests = [p for p in ctx.changed if TEST_PATH.search(p)]
    notes = []
    deleted = ctx.git("diff", "--name-only", "--diff-filter=D", "--no-renames", ctx.merge_base, "HEAD").stdout.split()
    gone = [p for p in deleted if TEST_PATH.search(p)]
    if gone:
        notes.append("deleted test file(s): " + ", ".join(gone[:5]))
    if tests:
        removed = added_skips = 0
        patch = ctx.git("diff", "--unified=0", "--no-renames", "--no-color", ctx.merge_base, "HEAD", "--", *tests).stdout
        for raw in patch.splitlines():
            if raw.startswith("-") and not raw.startswith("---") and ASSERT_LINE.match(raw[1:]):
                removed += 1
            elif raw.startswith("+") and not raw.startswith("+++") and SKIP_LINE.search(raw):
                added_skips += 1
        if removed:
            notes.append("%d assertion/test line(s) removed" % removed)
        if added_skips:
            notes.append("%d skip marker(s) added" % added_skips)
    production = [p for p in ctx.changed if re.search(r"^ui2/[^/]+/src/main/|^ui2/frontend/src/", p) and not TEST_PATH.search(p)]
    if production and not tests:
        notes.append("production code changed without a test change (%d file(s))" % len(production))
    if notes:
        return Out("fail", "; ".join(notes), extra={"deleted_tests": gone[:20]})
    return Out("pass", "no deleted test, removed assertion or new skip")


def step_queue_overlap(ctx: Context, step: dict) -> Out:
    entries = queue_entries(ctx)
    if entries is None:
        return Out("skipped", "no queue directory given")
    mine = set(ctx.changed)
    findings = []
    for entry in entries[:30]:
        diff = ctx.git("diff", "--name-only", "--no-renames", "%s...%s" % (ctx.base_sha, entry["branch"]))
        if diff.returncode != 0:
            continue
        shared = sorted(mine & set(diff.stdout.split()))
        if not shared:
            continue
        clash = ctx.git("merge-tree", "--write-tree", "--no-messages", entry["branch"], "HEAD").returncode == 1
        findings.append({"entry": entry["name"], "files": shared[:5], "conflicts": clash})
    if findings:
        ctx.extras["queue_overlap"] = findings
        return Out("fail", "overlaps with queued work (suggest after=<entry>): " + "; ".join(
            "%s on %d file(s)%s" % (f["entry"], len(f["files"]), ", conflicts" if f["conflicts"] else "") for f in findings[:5]),
            extra={"overlaps": findings})
    return Out("pass", "no overlap with %d queued or in-flight entr%s" % (len(entries), "y" if len(entries) == 1 else "ies"))


# -- tests -------------------------------------------------------------------------------------------------------------


def get_scope(ctx: Context) -> dict:
    with ctx.lock:
        return _get_scope(ctx)


def _get_scope(ctx: Context) -> dict:
    if ctx.scope is None:
        result = subprocess.run([sys.executable, "tools/delivery/pretest_scope.py", "--repo", str(ctx.worktree),
                                 "--base", ctx.base_sha, "--branch", "HEAD"], cwd=str(ctx.worktree), capture_output=True,
                                text=True, timeout=120)
        try:
            ctx.scope = json.loads(result.stdout.strip().splitlines()[-1])
            if ctx.scope.get("scope") not in ("full", "scoped"):
                raise ValueError("unknown scope")
        except (ValueError, IndexError):
            ctx.scope = {"scope": "full", "gradle_tasks": [], "frontend": True, "modules": [],
                         "why": "pretest_scope.py gave no usable answer; full suite"}
    return ctx.scope


def parse_junit(root: Path, pattern: str = "*/build/test-results/test/*.xml") -> tuple:
    """(tests, [(module, class, name, message)]) from Gradle's XML results under `root`."""
    total, failures = 0, []
    for path in sorted(root.glob(pattern)):
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        total += int(suite.get("tests", 0))
        module = path.relative_to(root).parts[0]
        for case in suite.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                failures.append((module, case.get("classname", ""), case.get("name", ""), (bad.get("message") or "")[:200]))
    return total, failures


def _flaky_ok(manifest: dict, cls: str) -> bool:
    return cls.split(".")[-1] in set(manifest.get("flaky_allowlist") or []) and not SECURITY_NAME.search(cls)


def step_java_scoped(ctx: Context, step: dict) -> Out:
    ui2 = ctx.worktree / "ui2"
    if not (ui2 / "gradlew").exists():
        return Out("skipped", "no ui2/gradlew in this tree")
    scope = get_scope(ctx)
    prebuilt, problem = _gradle_frontend_flags(ctx)
    if problem:
        return problem
    flags = ctx.gradle_flags() + prebuilt
    for stale in ui2.glob("*/build/test-results/test/*.xml"):
        stale.unlink(missing_ok=True)
    if scope["scope"] == "scoped":
        argv = ["./gradlew", *flags, *scope["gradle_tasks"], "--continue", "-q"]
    else:
        argv = ["./gradlew", *flags, "test", "--continue", "-q", "-x", ":integration-tests:test"]
    rc, tail, timed_out = ctx.run(argv, step_id="java", cwd=ui2, timeout=step.get("timeout_s", 3000))
    total, failures = parse_junit(ui2)
    flaky = []
    if rc != 0 and not timed_out and failures and len(failures) <= 5 and all(_flaky_ok(ctx.manifest, c) for _, c, _, _ in failures):
        for module, cls in sorted({(m, c) for m, c, _, _ in failures}):
            again, _, _ = ctx.run(["./gradlew", *flags, ":%s:test" % module, "--tests", cls, "-q"], step_id="java-rerun",
                                  cwd=ui2, timeout=900)
            if again == 0:
                flaky.append(cls)
        if len(flaky) == len({c for _, c, _, _ in failures}):
            rc, failures = 0, []
    summary = "%s scope (%s): %d tests, %d failure(s)" % (scope["scope"], scope.get("why", ""), total, len(failures))
    if flaky:
        summary += "; re-run once and passed (allowlisted): " + ", ".join(sorted(set(flaky)))
    if rc == 0:
        return Out("pass", summary, extra={"tests": total, "scope": scope["scope"]})
    listing = "\n".join("FAIL %s.%s | %s" % (c.split(".")[-1], n, m) for _, c, n, m in failures[:30])
    why = "timed out" if timed_out else "gradle exit %s" % rc
    return Out("fail", "%s; %s" % (why, summary), listing or tail, {"tests": total, "scope": scope["scope"]})


def _frontend(ctx: Context) -> Path | None:
    path = ctx.worktree / "ui2/frontend"
    return path if (path / "package.json").is_file() else None


def _frontend_needed(ctx: Context) -> bool:
    return bool(get_scope(ctx).get("frontend"))


def _ensure_modules(ctx: Context, front: Path) -> Out | None:
    with ctx.frontend_lock:     # two groups never install into ui2/frontend at the same time
        if (front / "node_modules").is_dir():
            return None
        rc, tail, _ = ctx.run(["npm", "ci", "--silent"], step_id="npm-ci", cwd=front, timeout=900)
        return None if rc == 0 else Out("error", "npm ci failed (exit %s)" % rc, tail)


def step_frontend_tsc(ctx: Context, step: dict) -> Out:
    front = _frontend(ctx)
    if front is None or not _frontend_needed(ctx):
        return Out("skipped", "no frontend change in scope")
    problem = _ensure_modules(ctx, front)
    if problem:
        return problem
    rc, tail, timed_out = ctx.run(["npx", "tsc", "--noEmit", "-p", "."], step_id="tsc", cwd=front,
                                  timeout=step.get("timeout_s", 900))
    return Out("pass", "tsc clean") if rc == 0 else Out("fail", "tsc %s" % ("timed out" if timed_out else "failed"), tail)


def step_frontend_vitest(ctx: Context, step: dict) -> Out:
    front = _frontend(ctx)
    if front is None or not _frontend_needed(ctx):
        return Out("skipped", "no frontend change in scope")
    problem = _ensure_modules(ctx, front)
    if problem:
        return problem
    rc, tail, timed_out = ctx.run(["npx", "vitest", "run"], step_id="vitest", cwd=front, timeout=step.get("timeout_s", 1800))
    text = strip_ansi(read_tail(ctx.log_dir / "vitest.log", 200000))
    failed_files = sorted(set(re.findall(r"^ FAIL  (tests/\S+)", text, re.M)))
    summary = re.findall(r"^\s*(?:Test Files|Tests)\s+.*$", text, re.M)
    # Fail closed: a non-zero exit is a failure even when no "FAIL tests/..." line was printed (a crash before the
    # list), and a zero exit without a vitest summary is not a pass either.
    if rc != 0:
        reason = "timed out" if timed_out else "exit %s" % rc
        if not failed_files:
            reason += "; no failing test file listed (crash before the report)"
        return Out("fail", "vitest %s" % reason, "\n".join(["FAIL " + f for f in failed_files[:20]]) or tail,
                   {"failed_files": failed_files[:50]})
    if not any("Test Files" in line and "passed" in line for line in summary):
        return Out("fail", "vitest exited 0 without a passing Test Files summary", tail)
    return Out("pass", " | ".join(s.strip() for s in summary[:2]))


def step_frontend_build(ctx: Context, step: dict) -> Out:
    front = _frontend(ctx)
    if front is None or not _frontend_needed(ctx):
        return Out("skipped", "no frontend change in scope")
    return _build_frontend(ctx, front, step.get("timeout_s", 900))


def _build_frontend(ctx: Context, front: Path, timeout: int) -> Out:
    problem = _ensure_modules(ctx, front)
    if problem:
        return problem
    with ctx.frontend_lock:     # one production build; the Java group waits for it instead of building a second one
        if ctx.frontend_built:
            return Out("pass", "production bundle already built in this run")
        rc, tail, timed_out = ctx.run(["npm", "run", "build"], step_id="npm-build", cwd=front, timeout=timeout)
        if rc != 0:
            return Out("fail", "npm run build %s" % ("timed out" if timed_out else "failed"), tail)
        ctx.frontend_built = True
    return Out("pass", "production bundle builds")


def _gradle_frontend_flags(ctx: Context) -> tuple:
    """(flags, problem). While the frontend group runs next to Gradle, both would drive `npm ci` / `npm run build` in
    ui2/frontend (Gradle's frontendBuild is a dependency of :service:processResources). The harness builds the bundle
    once and tells Gradle it is prebuilt, the way the integration job does."""
    front = _frontend(ctx)
    if not ctx.concurrent or front is None or not _frontend_needed(ctx):
        return [], None
    built = _build_frontend(ctx, front, 900)
    if built.status != "pass":
        return [], built
    return ["-PfrontendPrebuilt=true"], None


def step_integration(ctx: Context, step: dict) -> Out:
    mode = ctx.args.integration
    if mode == "skip":
        return Out("skipped", "skipped by --integration skip; this report is not complete", extra={"skipped_by_flag": True})
    if mode == "hosta" and ctx.args.carrier == "hosta":
        return on_carrier(ctx, step, "integration", _integration_local)
    if mode == "hosta":
        argv = ctx.expand(step["hosta_cmd"])
        rc, tail, timed_out = ctx.run(argv, step_id="integration-hosta", timeout=step.get("timeout_s", 3000))
        if rc == 0:
            return Out("pass", "HOST-A integration job passed", extra={"carrier": "hosta"})
        if rc in (2, 3) or timed_out:  # 3: the host cannot run this tree yet (no image, image stale)
            return Out("error", "HOST-A integration runner failed to run (exit %s); not a test result" % rc, tail)
        return Out("fail", "HOST-A integration job reported test failures", tail, {"carrier": "hosta"})
    return _integration_local(ctx, step)


def _integration_local(ctx: Context, step: dict) -> Out:
    front = _frontend(ctx)
    if front is not None and not ctx.frontend_built:
        built = _build_frontend(ctx, front, 900)
        if built.status != "pass":
            return built
    try:
        with EmbeddedPostgres() as database:
            argv = ["./gradlew", *ctx.gradle_flags(), *step["gradle_args"]]
            rc, tail, timed_out = ctx.run(argv, step_id="integration", cwd=ctx.worktree / "ui2",
                                          env={"UI2_TEST_JDBC_URL": database.jdbc_url}, timeout=step.get("timeout_s", 3000))
    except EmbeddedPostgresError as error:
        return Out("error", "no PostgreSQL 16 carrier: %s" % error)
    total, failures = parse_junit(ctx.worktree / "ui2", "integration-tests/build/test-results/test/*.xml")
    summary = "%d tests, %d failure(s), embedded PostgreSQL 16" % (total, len(failures))
    if rc == 0:
        return Out("pass", summary, extra={"carrier": "embedded", "tests": total})
    listing = "\n".join("FAIL %s.%s | %s" % (c.split(".")[-1], n, m) for _, c, n, m in failures[:30])
    return Out("fail", ("timed out; " if timed_out else "gradle exit %s; " % rc) + summary, listing or tail,
               {"carrier": "embedded", "tests": total})


def step_accept(ctx: Context, step: dict) -> Out:
    commands = ctx.meta["accept"]
    if not commands:
        return Out("skipped", "no accept= commands in the lane meta")
    results, failed = [], []
    for index, command in enumerate(commands, start=1):
        rc, tail, timed_out = ctx.run(["/bin/bash", "-c", command], step_id="accept-%d" % index,
                                      timeout=step.get("timeout_s", 1800))
        results.append({"n": index, "exit": rc, "timed_out": timed_out})
        if rc != 0:
            failed.append((index, tail))
    if failed:
        return Out("fail", "accept command(s) failed: %s" % ", ".join("#%d" % n for n, _ in failed), failed[0][1],
                   {"accept": results})
    return Out("pass", "%d accept command(s) passed" % len(commands), extra={"accept": results})


# -- the HOST-A carrier (--carrier hosta) ------------------------------------------------------------------------------

# builtin -> the hosta_test_run.py phase that carries it
CARRIED = {"java_scoped": "java", "integration": "integration", "frontend_vitest": "vitest"}
HOSTA_CARRIER_CMD = ["{python}", "tools/delivery/hosta_test_run.py", "--repo", "{worktree}"]
HOSTA_EXIT = {"PASS": 0, "FAIL": 1, "ERROR": 2, "TIMEOUT": 2, "NOT_CONFIGURED": 3, "IMAGE_STALE": 3}


def _reusable(ctx: Context, step: dict) -> dict | None:
    """The PASS record a report for the same tree holds for this step, when the step may reuse it."""
    if not (ctx.reuse and step.get("tree_bound")):
        return None
    return next((c for c in ctx.reuse.get("checks", []) if c["id"] == step["id"] and c["status"] in ("pass", "reused")),
                None)


def hosta_plan(ctx: Context) -> dict:
    """The phases the Mac would run itself, as hosta_test_run.py flags: Java scoped or full exactly as pretest_scope.py
    says (the host recomputes the same tasks from the same base), integration when its path rule selects it and
    `--integration` hands it over, vitest only when the frontend changed. A step not selected or reused is not asked."""
    steps = {s.get("builtin"): s for s in ctx.manifest["steps"] if s.get("builtin") in CARRIED}

    def wanted(builtin: str) -> bool:
        step = steps.get(builtin)
        return step is not None and step_selected(ctx, step, ctx.args.profile) is None and not _reusable(ctx, step)

    java = "none"
    if wanted("java_scoped") and (ctx.worktree / "ui2" / "gradlew").exists():
        java = "scoped" if get_scope(ctx)["scope"] == "scoped" else "full"
    integration = wanted("integration") and ctx.args.integration == "hosta"
    vitest = wanted("frontend_vitest") and _frontend(ctx) is not None and _frontend_needed(ctx)
    return {"java": java, "integration": integration, "vitest": vitest}


def _last_json(text: str) -> dict | None:
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                value = json.loads(line)
            except ValueError:
                continue
            return value if isinstance(value, dict) else None
    return None


def _call_hosta(ctx: Context) -> dict:
    plan = hosta_plan(ctx)
    asked = [name for name, on in (("java", plan["java"] != "none"), ("integration", plan["integration"]),
                                   ("vitest", plan["vitest"])) if on]
    run = {"plan": plan, "asked": asked, "used": "none", "reason": "", "phases": {}}
    if not asked:
        return run
    timeout = int(ctx.manifest.get("hosta_carrier_timeout_s", 2700))
    argv = ctx.expand(ctx.manifest.get("hosta_carrier_cmd") or HOSTA_CARRIER_CMD)
    argv += ["--commit", ctx.head, "--base", ctx.base_sha, "--java", plan["java"], "--timeout", str(timeout)]
    argv += ["--integration"] if plan["integration"] else []
    argv += ["--vitest"] if plan["vitest"] else []
    ctx.say("[carrier] HOST-A run: %s" % ", ".join(asked))
    rc, tail, timed_out = ctx.run(argv, step_id="hosta-tests", timeout=timeout + 120)
    result = _last_json(read_tail(ctx.log_dir / "hosta-tests.log", 1 << 20)) or {}
    status = result.get("status")
    summary = result.get("summary") if isinstance(result.get("summary"), dict) else None
    run.update({"exit": rc, "status": status, "run": result.get("run", ""), "wall_s": result.get("wall_s")})
    if timed_out:
        run["reason"] = "the HOST-A run timed out after %ss" % (timeout + 120)
    elif rc not in (0, 1):
        run["reason"] = "HOST-A runner exit %s (%s/%s)" % (rc, status or "no answer", result.get("reason") or "-")
    elif summary is None or not isinstance(summary.get("phases"), list) or HOSTA_EXIT.get(status) != rc:
        run["reason"] = "HOST-A runner exit %s without a matching JSON summary" % rc
    if run["reason"]:
        run["used"] = "mac-fallback"
        run["tail"] = tail
        return run
    run["used"] = "hosta"
    run["phases"] = {p.get("name"): p for p in summary["phases"] if isinstance(p, dict)}
    host_scope = result.get("scope") or {}
    if plan["java"] == "scoped":
        run["scope_tasks_match"] = host_scope.get("tasks") == list(get_scope(ctx).get("gradle_tasks") or [])
    return run


def hosta_run(ctx: Context) -> dict:
    """The one shared HOST-A run of this harness run (made on first use; a cancelled call is not remembered)."""
    with ctx.hosta_lock:
        if ctx.hosta is None:
            run = _call_hosta(ctx)
            ctx.hosta = run
            ctx.extras["carrier"] = "mac" if run["used"] == "none" else run["used"]
            ctx.extras["hosta_run"] = {k: v for k, v in run.items() if k not in ("phases", "tail")}
            if run["used"] == "mac-fallback":
                ctx.notes.append("carrier=mac-fallback: %s; %s ran on the Mac instead" % (run["reason"],
                                                                                     ", ".join(run["asked"])))
        return ctx.hosta


def _phase_out(name: str, phase: dict, run: dict) -> Out:
    counts = "%d tests, %d failure(s), %d error(s), %d skipped" % (phase["tests"], phase["failures"], phase["errors"],
                                                                   phase["skipped"])
    if name == "java":
        counts = "%s scope: %s" % (run["plan"]["java"], counts)
    extra = {"carrier": "hosta", "run": run.get("run", ""), "tests": phase["tests"], "phase_reason": phase["reason"]}
    if name == "java" and "scope_tasks_match" in run:
        extra["scope_tasks_match"] = run["scope_tasks_match"]
    duration = float(phase.get("duration_s") or 0)
    if phase["status"] == "PASS":
        return Out("pass", "HOST-A %s phase: %s" % (name, counts), extra=extra, duration_s=duration)
    failed = [str(n) for n in phase.get("failed") or []]
    extra["failed"] = failed
    detail = "HOST-A %s phase failed (%s): %s" % (name, phase["reason"], counts)
    if failed:
        detail += "; failed: " + ", ".join(failed[:10]) + (" (+%d more)" % (len(failed) - 10) if len(failed) > 10 else "")
    else:
        detail += "; no test named (exit %s: a compile or task failure)" % phase.get("exit_code")
    return Out("fail", detail, "\n".join("FAIL " + n for n in failed[:30]), extra, duration)


def on_carrier(ctx: Context, step: dict, phase_name: str, local) -> Out:
    """Satisfy a carried step from the shared HOST-A run, or run it on the Mac (`local`) when the host could not."""
    if ctx.args.carrier != "hosta":
        return local(ctx, step)
    run = hosta_run(ctx)
    if phase_name not in run["asked"]:
        return local(ctx, step)         # nothing to carry: the local step skips (or, for --integration local, runs)
    phase = run["phases"].get(phase_name)
    if run["used"] == "hosta" and phase is not None and phase.get("status") in ("PASS", "FAIL"):
        return _phase_out(phase_name, phase, run)
    why = run["reason"] if run["used"] == "mac-fallback" else "the HOST-A run reported no %s phase" % phase_name
    out = local(ctx, step)
    out.detail = "carrier=mac-fallback (%s): %s" % (why, out.detail)
    out.extra = dict(out.extra, carrier="mac-fallback")
    if run["used"] == "hosta":
        with ctx.lock:
            ctx.notes.append("carrier=mac-fallback for %s: %s" % (step["id"], why))
    return out


def step_java(ctx: Context, step: dict) -> Out:
    return on_carrier(ctx, step, "java", step_java_scoped)


def step_vitest(ctx: Context, step: dict) -> Out:
    return on_carrier(ctx, step, "vitest", step_frontend_vitest)


BUILTINS = {
    "agent_outcome": step_agent_outcome, "preconditions": step_preconditions, "main_conflict": step_main_conflict,
    "guarded_paths": step_guarded_paths, "declared_paths": step_declared_paths, "derived_files": step_derived_files,
    "whitespace": step_whitespace, "secret_scan": step_secret_scan, "forbidden_tokens": step_forbidden_tokens,
    "migration_rule": step_migration_rule, "test_integrity": step_test_integrity, "queue_overlap": step_queue_overlap,
    "java_scoped": step_java, "frontend_tsc": step_frontend_tsc, "frontend_vitest": step_vitest,
    "frontend_build": step_frontend_build, "integration": step_integration, "accept": step_accept,
}


def step_command(ctx: Context, step: dict) -> Out:
    cwd = ctx.worktree / step["cwd"] if step.get("cwd") else None
    argv = ctx.expand(step["cmd"])
    if "{gradle_flags}" in step["cmd"]:
        prebuilt, problem = _gradle_frontend_flags(ctx)
        if problem:
            return problem
        argv[1:1] = prebuilt
    rc, tail, timed_out = ctx.run(argv, step_id=step["id"], cwd=cwd, timeout=step.get("timeout_s", 900))
    if rc == 0:
        return Out("pass", "exit 0")
    return Out("fail", "timed out after %ss" % step.get("timeout_s", 900) if timed_out else "exit %s" % rc, tail)


# --------------------------------------------------------------------------------------------------------------------
# Manifest, report reuse, the run loop


def load_manifest(path: Path) -> tuple:
    if yaml is None:
        raise HarnessError("PyYAML is not installed (pip install -r requirements-dev.txt)")
    try:
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise HarnessError("cannot read the check manifest: %s" % str(error)[:160])
    if not isinstance(manifest, dict) or manifest.get("version") != 1 or not isinstance(manifest.get("steps"), list):
        raise HarnessError("the check manifest is not version 1 with a steps list")
    ids = [s.get("id") for s in manifest["steps"]]
    if len(ids) != len(set(ids)) or not all(ids):
        raise HarnessError("check manifest step ids must be unique and present")
    for step in manifest["steps"]:
        if not (step.get("builtin") or step.get("cmd")):
            raise HarnessError("step %s has neither builtin nor cmd" % step["id"])
        if step.get("builtin") and step["builtin"] not in BUILTINS:
            raise HarnessError("step %s names an unknown builtin" % step["id"])
    return manifest, sha256_file(path)


def load_reuse(path: str | None, ctx: Context) -> dict | None:
    """A PASS report is reusable only for the exact tree, the same manifest and a matching profile coverage."""
    if not path:
        return None
    try:
        report = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if report.get("schema") != REPORT_SCHEMA or report.get("exit_code") != EXIT_PASS:
        return None
    if report.get("tree_id") != ctx.tree or report.get("manifest_sha256") != ctx.manifest_sha:
        return None
    return report


# --profile quick (PO 2026-11-25): the lane profile without the heavy test steps; the ship pretest runs them. Used by the
# gate while the Mac or HOST-A cannot carry every lane's full test load without slowing delivery.
QUICK_SKIPPED = ("java_scoped", "java_project_plan_reader", "frontend_build", "frontend_vitest", "integration")


def step_selected(ctx: Context, step: dict, profile: str) -> str | None:
    """None when the step runs, else the reason it is skipped."""
    if profile == "quick":
        if step["id"] in QUICK_SKIPPED or step.get("builtin") in QUICK_SKIPPED:
            return "heavy step: the quick profile leaves it to the ship pretest"
        profile = "lane"
    if profile not in step.get("profiles", ["lane"]):
        return "not in profile %s" % profile
    if step.get("when") and not ctx.any_changed(step["when"], step.get("when_not")):
        return "no changed path matches its `when`"
    return None


def execute_step(ctx: Context, step: dict) -> dict:
    """Run one selected step and return its report record (`status` is the Out status)."""
    record = {"id": step["id"], "status": "skipped", "detail": "", "duration_s": 0.0}
    reused = _reusable(ctx, step)
    started = time.time()
    if reused:
        out = Out("reused", "PASS reused from a report for the same tree (%s)" % ctx.reuse.get("finished_at", "?"))
    else:
        try:
            out = BUILTINS[step["builtin"]](ctx, step) if step.get("builtin") else step_command(ctx, step)
        except Cancelled as error:
            out = Out("cancelled", "stopped while running: %s" % (str(error) or ctx.stop_reason))
        except HarnessError as error:
            out = Out("error", str(error)[:300])
        except subprocess.TimeoutExpired as error:
            out = Out("error", "a git or helper command timed out: %s" % str(error)[:120])
    if step.get("severity") == "warn" and out.status == "fail":
        out.status = "warn"
    waited = round(time.time() - started, 2)
    record.update({"status": out.status, "detail": out.detail,
                   "duration_s": waited if out.duration_s is None else round(out.duration_s, 2)})
    if out.duration_s is not None:
        out.extra = dict(out.extra, waited_s=waited)
    if out.tail and out.status in ("fail", "error", "warn", "held", "conflict", "agent_permission", "agent_infra"):
        record["output_tail"] = out.tail
    if out.extra:
        record["extra"] = out.extra
    if step.get("ci_twin"):
        record["ci_twin"] = step["ci_twin"]
    if step.get("tree_bound") is not None:
        record["tree_bound"] = bool(step["tree_bound"])
    if step.get("group"):
        record["group"] = step["group"]
    return record


def _run_group(ctx: Context, steps: list, records: dict, name: str | None) -> None:
    """Run `steps` in order on this thread. A stopping failure ends the run for every group unless --keep-going."""
    for step in steps:
        if ctx.stop.is_set():
            why = "terminated (%s)" % ctx.stop_reason if ctx.terminated else "stopped by %s" % ctx.stop_reason
            records[step["id"]] = {"id": step["id"], "status": "cancelled" if ctx.terminated else "skipped",
                                   "detail": "not run: " + why, "duration_s": 0.0}
            continue
        ctx.say("[%s] start  %s" % (name or "seq", step["id"]))
        record = execute_step(ctx, step)
        records[step["id"]] = record
        ctx.say("[%s] %-6s %s (%.0fs)" % (name or "seq", record["status"], step["id"], record["duration_s"]))
        if name:
            with ctx.lock:
                ctx.log_dir.mkdir(parents=True, exist_ok=True)
                with open(ctx.log_dir / ("group-%s.log" % name), "a", encoding="utf-8") as handle:
                    handle.write("%s %-9s %-26s %7.1fs  %s\n" % (now(), record["status"], step["id"], record["duration_s"],
                                                                record["detail"][:200]))
        if record["status"] in STATUS_EXIT and not ctx.args.keep_going:
            ctx.stop_run(step["id"])


def _blocks(steps: list, jobs: int) -> list:
    """[("seq", [step]), ("par", {group: [steps]})]: consecutive grouped steps form one parallel block, and any ungrouped
    step is a barrier. One job means the plain manifest order."""
    blocks = []
    for step in steps:
        group = step.get("group")
        if jobs <= 1 or not group:
            blocks.append(("seq", [step]))
        elif blocks and blocks[-1][0] == "par":
            blocks[-1][1].setdefault(group, []).append(step)
        else:
            blocks.append(("par", {group: [step]}))
    return blocks


def run_steps(ctx: Context) -> tuple:
    profile = ctx.args.profile
    steps = ctx.manifest["steps"]
    records, selected, started = {}, [], time.time()
    for step in steps:
        skip = step_selected(ctx, step, profile)
        if skip:
            records[step["id"]] = {"id": step["id"], "status": "skipped", "detail": skip, "duration_s": 0.0}
        else:
            selected.append(step)
    groups = {}
    for kind, payload in _blocks(selected, ctx.args.jobs):
        if kind == "seq":
            _run_group(ctx, payload, records, None)
            continue
        if len(payload) == 1:       # nothing to overlap with
            (name, group_steps), = payload.items()
            _run_group(ctx, group_steps, records, name)
            continue
        ctx.concurrent = True
        begun = time.time()
        with ThreadPoolExecutor(max_workers=ctx.args.jobs) as pool:
            futures = {name: pool.submit(_run_group, ctx, group_steps, records, name) for name, group_steps in payload.items()}
            try:
                for future in futures.values():
                    future.result()
            except BaseException:       # the main thread was interrupted: take the commands down with it
                ctx.stop_run("interrupted")
                raise
        for name, group_steps in payload.items():
            groups[name] = {"steps": [s["id"] for s in group_steps],
                            "busy_s": round(sum(records[s["id"]]["duration_s"] for s in group_steps), 1)}
        groups["_block_wall_s"] = round(time.time() - begun, 1)
    results = [records[s["id"]] for s in steps]
    exit_code = next((STATUS_EXIT[r["status"]] for r in results if r["status"] in STATUS_EXIT), EXIT_PASS)
    if ctx.terminated:
        exit_code = EXIT_HARNESS_ERROR
    ctx.extras["parallel"] = {"jobs": ctx.args.jobs, "pytest_workers": ctx.pytest_workers(), "cpu_count": os.cpu_count(),
                              "concurrent": ctx.concurrent, "groups": groups, "wall_s": round(time.time() - started, 1)}
    return results, exit_code


def repair_hint(results: list) -> str:
    """A bounded failure summary for the one repair turn the runner may give the author lane."""
    lines = []
    for record in results:
        if record["status"] in STATUS_EXIT:
            lines.append("[%s] %s" % (record["id"], record["detail"]))
            lines += (record.get("output_tail") or "").splitlines()[-12:]
    return "\n".join(lines[:80])


def build_report(ctx: Context, results: list, exit_code: int, started: float, error: str = "") -> dict:
    statuses = {r["id"]: r["status"] for r in results}
    skipped_flag = any((r.get("extra") or {}).get("skipped_by_flag") for r in results)
    report = {
        "schema": REPORT_SCHEMA, "result": RESULT_NAMES.get(exit_code, "UNKNOWN"), "exit_code": exit_code,
        "lane": ctx.meta["name"] or ctx.args.lane_name or ctx.branch, "branch": ctx.branch, "area": ctx.meta["area"],
        "backlog_id": ctx.meta["backlog_id"], "profile": ctx.args.profile, "tier": ctx.tier,
        "tree_id": ctx.tree, "head": ctx.head, "base_ref": ctx.base_ref, "base_sha": ctx.base_sha,
        "merge_base": ctx.merge_base, "manifest_sha256": ctx.manifest_sha, "started_at": time.strftime(
            "%Y-%m-%dT%H:%M:%S%z", time.localtime(started)), "finished_at": now(),
        "duration_s": round(time.time() - started, 1), "changed_paths": len(ctx.changed),
        "changed_files": ctx.changed[:200], "derived_commits": ctx.derived_commits,
        "complete": exit_code == EXIT_PASS and not skipped_flag and not any(s == "error" for s in statuses.values()),
        "checks": results, "notes": ctx.notes,
    }
    report.update({k: v for k, v in ctx.extras.items()})
    if exit_code != EXIT_PASS:
        report["repair_hint"] = repair_hint(results)
    if error:
        report["error"] = error
    return report


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def fetch_base(worktree: Path, remote: str, branch: str, tag: str) -> str:
    """Fetch main into an isolated ref of this run (never origin/main, which other fetches race on)."""
    ref = "refs/harness/%s" % re.sub(r"[^A-Za-z0-9_.-]", "_", tag)
    last = ""
    for attempt in range(3):
        result = subprocess.run(["git", "-C", str(worktree), "fetch", "-q", "--refmap=", remote,
                                 "+refs/heads/%s:%s" % (branch, ref)], capture_output=True, text=True, timeout=300)
        if result.returncode == 0:
            return ref
        last = result.stderr.strip()
        time.sleep(2 * (attempt + 1))
    raise HarnessError("could not fetch %s/%s: %s" % (remote, branch, last[:160]))


def parse_args(argv: list | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--worktree", type=Path, default=Path.cwd())
    parser.add_argument("--base", default="origin/main", help="ref to compare with (default origin/main)")
    parser.add_argument("--fetch", action="store_true", help="fetch main into refs/harness/<lane> and use that as the base")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--fetch-branch", default="main")
    parser.add_argument("--meta", type=Path)
    parser.add_argument("--lane-name", default="")
    parser.add_argument("--queue-dir", type=Path, help="ship-queue directory (queued and in-flight *.ship entries)")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--log-dir", type=Path)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--profile", choices=("lane", "pretest", "quick"), default="lane")
    parser.add_argument("--integration", choices=("auto", "local", "hosta", "skip"), default="auto",
                        help="auto: hosta with --carrier hosta, else local")
    parser.add_argument("--carrier", choices=("mac", "hosta"), default="mac",
                        help="hosta: java_scoped, integration and frontend_vitest run in one HOST-A ui2-test pod; "
                        "a runner error runs them on the Mac instead (carrier=mac-fallback)")
    parser.add_argument("--reuse-report", help="a harness-report.json whose PASS may be reused for the exact same tree")
    parser.add_argument("--agent-log", type=Path)
    parser.add_argument("--agent-exit", type=int)
    parser.add_argument("--allow-paths", action="store_true", help="the Product Owner approved guarded paths for this lane")
    parser.add_argument("--require-declared", action="store_true", help="a missing paths= declaration is a warning")
    parser.add_argument("--no-commit-derived", action="store_true", help="check derived files, never write or commit")
    parser.add_argument("--keep-going", action="store_true", help="run every step even after one stops the run; with "
                        "--jobs > 1 a failure in one group does not cancel the others")
    parser.add_argument("--jobs", type=int, default=3, help="CPU budget: how many heavy step groups (tooling tests, Java, "
                        "frontend) run at once; 1 is the plain manifest order (default 3)")
    parser.add_argument("--pytest-workers", type=int, default=0, help="xdist workers for the tooling suite (default: derived "
                        "from the CPU count and --jobs)")
    parser.add_argument("--list", action="store_true", help="print the manifest's steps and exit")
    args = parser.parse_args(argv)
    if args.integration == "auto":
        args.integration = "hosta" if args.carrier == "hosta" else "local"
    args.jobs = max(1, args.jobs)
    return args


def main(argv: list | None = None) -> int:
    args = parse_args(argv)
    started = time.time()
    worktree = args.worktree.resolve()
    ctx = None
    holder = {"ctx": None, "term": False}

    def on_term(signum, _frame):
        holder["term"] = True
        if holder["ctx"] is not None:
            holder["ctx"].stop_run("signal %d" % signum, terminated=True)
        else:
            raise HarnessError("terminated by signal %d before the run started" % signum)

    previous = {}
    if threading.current_thread() is threading.main_thread():
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, on_term)
    try:
        manifest, manifest_sha = load_manifest(args.manifest)
        if args.list:
            for step in manifest["steps"]:
                print("%-26s %-9s %s" % (step["id"], ",".join(step.get("profiles", ["lane"])), step.get("ci_twin", "")))
            return EXIT_PASS
        meta = parse_meta(args.meta)
        ctx = Context(worktree=worktree, manifest=manifest, manifest_sha=manifest_sha, args=args, meta=meta, base_ref=args.base)
        holder["ctx"] = ctx
        ctx.extras["carrier"] = "mac"
        report_path = args.report or worktree / ".." / ("harness-report-%s.json" % worktree.name)
        report_path = Path(os.path.abspath(report_path))
        ctx.log_dir = args.log_dir or report_path.with_suffix("").with_name(report_path.stem + "-logs")
        ctx.branch = ctx.git("rev-parse", "--abbrev-ref", "HEAD", check=True).stdout.strip()
        if args.fetch:
            ctx.base_ref = fetch_base(worktree, args.remote, args.fetch_branch, meta["name"] or args.lane_name or worktree.name)
        resolved = ctx.git("rev-parse", "--verify", "-q", ctx.base_ref + "^{commit}")
        if resolved.returncode != 0:
            raise HarnessError("base ref %s does not resolve" % ctx.base_ref)
        ctx.base_sha = resolved.stdout.strip()
        ctx.refresh()
        ctx.reuse = load_reuse(args.reuse_report, ctx)
        if ctx.reuse:
            ctx.notes.append("reusing a PASS report for tree %s" % ctx.tree[:12])
        results, exit_code = run_steps(ctx)
        report = build_report(ctx, results, exit_code, started,
                              "terminated (%s); running commands were killed" % ctx.stop_reason if ctx.terminated else "")
    except Exception as error:  # HarnessError or anything unforeseen: a typed exit, never a bare traceback
        if ctx is None:
            ctx = Context(worktree=worktree, manifest={}, manifest_sha="", args=args, meta=parse_meta(None), base_ref=args.base)
            report_path = Path(os.path.abspath(args.report or worktree / ".." / ("harness-report-%s.json" % worktree.name)))
        message = ("%s: %s" % (type(error).__name__, error))[:300]
        report = build_report(ctx, [{"id": "harness", "status": "error", "detail": message, "duration_s": 0}],
                              EXIT_HARNESS_ERROR, started, message)
        exit_code = EXIT_HARNESS_ERROR
    finally:
        if ctx is not None:
            ctx.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    write_report(report_path, report)
    reused = sum(1 for r in report["checks"] if r["status"] == "reused")
    print("%s exit=%d tree=%s report=%s%s" % (report["result"], exit_code, (report.get("tree_id") or "")[:12], report_path,
                                              " (%d step(s) reused)" % reused if reused else ""))
    for record in report["checks"]:
        if record["status"] in STATUS_EXIT or record["status"] == "warn":
            print("  %-7s %-24s %s" % (record["status"].upper(), record["id"], record["detail"][:200]))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
