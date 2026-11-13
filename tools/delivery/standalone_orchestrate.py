#!/usr/bin/env python3
"""Standalone orchestration -- bounded parallel Codex development tasks, without the relay.

Product Owner, 2026-09-26: "Relay kaydı işini bırakalım ... tek küçük geliştirmeler için daha agile çalışsın. Var olan
relay yapısını da bozmayalım." This is an ADDITIONAL, lighter path next to `tools/delivery/orchestrator.py`; it does not
read, write or change relay files, orchestrator state or `docs/design/GOV_*` contracts. The Product Owner says which
mode a piece of work uses.

What it keeps from the orchestrator (imported, not re-derived): the Codex argv (`CodexAdapter`), the writable git
roots a commit on the lane needs, the linked frontend dependencies and the engineer toolchain PATH.
What it drops: SESSION_START/SESSION_CLOSE packets, the FROZEN-authority preflight, relay turn ownership.

Rules it enforces:
- Up to four parallel tasks by default (`start --max-parallel 1..6`); live relay movements count toward the limit.
- Ships are serialized under a state-directory lock, with a 45-minute wait limit.
- The worker commits on its own lane only; never pushes, deploys, touches HOST-A or contacts a device (stated in the
  prompt). Review, merge, deploy and live validation stay with the engineering session.

Usage:
  standalone_orchestrate.py start  --task <slug> [--model gpt-6.1-sol|gpt-6-luna|gpt-6-sol|gpt-6-astra] [--effort medium] [--network] --brief FILE|-
                                 (default model: gpt-6.1-sol)
  standalone_orchestrate.py status [--task <slug>]
  standalone_orchestrate.py wait   --task <slug> [--timeout 540]
  standalone_orchestrate.py result --task <slug>
  standalone_orchestrate.py stop   --task <slug>
  standalone_orchestrate.py clean  --task <slug>      (after merge: removes the worktree and the lane branch)
  standalone_orchestrate.py ship   [--task <slug>]    (fast-forward main to the lane if given, privacy gate, push origin +
                                                       hosta, deploy with retry, sync ui2-configuration, clean the task)
    Ship speed (2026-11-03): the merge waits for GitHub's checks (`--skip-checks REASON` is the emergency bypass); a branch
    whose every changed path is documentation/project-state text takes a docs-only fast path (privacy gate + gitleaks,
    PR, checks, merge; no preview, image build or deploy; `--no-fast-path`/`--full` opt out); `--early-pr` pushes and
    opens the PR before the preview so the checks run during it.
    Frontend fast path (2026-11-13): a branch whose every changed path is frontend source/tests/e2e under
    ui2/frontend (plus documentation) rolls out only ui2-service (the one module the ingress routes to), builds no
    Packet Analysis image and opens its pull request early; preview e2e, GitHub checks, the security gate on the
    service image, the rollback snapshot and the production e2e all stay. It is taken only when every other module
    already runs a commit that differs from this branch in frontend/documentation paths alone; anything ambiguous
    takes the full path. The ship prints the chosen path and why (`ship_path`). `classify --branch B
    [--check-deployed]` prints the same decision without shipping.
"""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import orchestrator as orch  # noqa: E402  (reuse: git roots, frontend link, toolchains, pid liveness)
from orchestrator_providers import CodexAdapter  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
HOME = REPO_ROOT.parent / f"{REPO_ROOT.name}-standalone"
STATE_DIR = HOME / ".state"
MODELS = ("gpt-6.1-sol", "gpt-6-luna", "gpt-6-sol", "gpt-6-astra")
# The host's bare repository and the account that owns it (docs/operations/HOST_A_RUNBOOK.md section 2): the SSH
# deploy account never writes it directly, so every push runs the receiving git as the tree owner.
HOST_BARE_REPO = "/opt/nexus.git"
HOST_RECEIVE_PACK = "sudo -n -u nexus git-receive-pack"
SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{1,48}$")

PREAMBLE = """You are a neXus engineer working on one small, well-defined change. Read AGENTS.md first (the project
constitution), then only the files the brief names and what they need.

Hard rules:
- This brief is the Product Owner's explicit authorization for LOCAL commits on this lane (AGENTS.md "Git authority
  and execution law"); push, PR and merge remain forbidden.
- Work only inside this worktree, on the current branch. Commit your work on this branch (one or a few commits, English
  messages ending with the line `Co-Authored-By: {model} (Codex)`). Do not push, open a PR, merge, or deploy.
- Never contact HOST-A or any network device; never run device commands; no credentials, real hostnames, addresses,
  serials or account names in code, tests, logs or messages (use RFC 5737 addresses and invented names in tests).
- A new device command needs a gate row (migration + gate_registry_fixture.yaml). Add gate rows ONLY when the brief
  lists them as Product Owner approved (exact endpoint/command); then add exactly those. If the work seems to need a
  device command the brief does not list, stop and say so in your final message instead of adding it.
- No new dependencies. English code, comments and UI text. Keep the diff to what the brief asks: never edit
  AI_HANDOVER.md, CURRENT_STATE.md, project/, docs/history/ or create/freeze contracts -- the engineering session owns
  project state.
- Never run `npm install`, `npm ci`, `npx <package>` downloads or delete node_modules: ui2/frontend/node_modules is
  already installed for you (use `npx tsc`, `npx vitest`, `npm run build`).
- A new table in a migration also needs `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO ui2_app;` (see V72).
- Run the validation the brief lists (frontend: `cd ui2/frontend && npx tsc --noEmit -p . && npx vitest run && npm run
  build`, with `--cacheDir` / cache paths inside the worktree if node_modules is read-only; Java: the named
  `./gradlew` tasks) and `python3 tools/privacy/repository_privacy_check.py`. Fix what fails.

Standing HOST-A facts (context only; no worker access authorization):
- No SSH forwarding on HOST-A: AllowTcpForwarding no; never a jump server.
- Java/Gradle run only inside container images: kaniko build Jobs in ui2-build and the integration Job.
  Workers cannot run Gradle in the sandbox.
- Non-interactive SSH kubectl needs `export KUBECONFIG=$HOME/.kube/config`.
- Corporate CA is provided via ConfigMap `corp-ca`.
- In-cluster e2e runs as the masked aiview persona after every deploy; any 4xx on page/API requests fails it.
- Check Point gateway reads run via `bash -lc`, never on management servers (R82 login-profile fork loop).

Final message (plain text): files changed, tests added, the validation commands you ran with their pass/fail summary,
and anything you could not do or are unsure about.

--- BRIEF ---
"""


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _git(*args: str, cwd: Path = REPO_ROOT, check: bool = True) -> str:
    r = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, timeout=120)
    if check and r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout.strip()


def _state_path(task: str) -> Path:
    return STATE_DIR / f"{task}.json"


def _load(task: str) -> dict:
    p = _state_path(task)
    if not p.exists():
        raise SystemExit(f"no standalone task {task!r}")
    return json.loads(p.read_text())


def _save(task: str, record: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    _state_path(task).write_text(json.dumps(record, indent=2))


def _phase(record: dict) -> str:
    """running while the process lives; then done (exit 0) or failed, from the exit code the wrapper wrote."""
    if record.get("phase") in ("stopped", "cleaned"):
        return record["phase"]
    if orch.pid_alive(int(record["pid"])):
        return "running"
    exit_file = Path(record["exit_file"])
    if not exit_file.exists():
        return "failed"
    return "done" if exit_file.read_text().strip() == "0" else "failed"


def _live_standalone() -> list[str]:
    if not STATE_DIR.is_dir():
        return []
    return [p.stem for p in STATE_DIR.glob("*.json") if _phase(json.loads(p.read_text())) == "running"]


def _live_orchestrator_movements() -> list[str]:
    state_dir = orch.DEFAULT_STATE_DIR
    if not Path(state_dir).is_dir():
        return []
    live = []
    for p in Path(state_dir).glob("*.json"):
        try:
            rec = json.loads(p.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        pid = rec.get("pid")
        if pid and orch.pid_alive(int(pid)):
            live.append(p.stem)
    return live


def cmd_start(args: argparse.Namespace) -> int:
    # Keep concurrent standalone starts from admitting more than the configured limit.
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with (STATE_DIR / "start.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _start(args)


def _start(args: argparse.Namespace) -> int:
    task = args.task
    if not SLUG.match(task):
        raise SystemExit("task must be a lowercase slug (letters, digits, dashes)")
    if args.model not in MODELS:
        raise SystemExit(f"model must be one of {MODELS}")
    if _state_path(task).exists() and _phase(json.loads(_state_path(task).read_text())) != "cleaned":
        raise SystemExit(f"task {task!r} already exists; use status/result/clean")
    busy = _live_standalone() + _live_orchestrator_movements()
    limit = getattr(args, "max_parallel", 4)
    if not 1 <= limit <= 6:
        raise SystemExit("--max-parallel must be between 1 and 6")
    if len(busy) >= limit:
        raise SystemExit(f"refused: parallel limit {limit} reached -- still running: {', '.join(busy)}")
    brief = sys.stdin.read() if args.brief == "-" else Path(args.brief).read_text()
    if not brief.strip():
        raise SystemExit("empty brief")

    _git("fetch", "-q", "origin", "main")
    base = args.base or "origin/main"
    base_sha = _git("rev-parse", base)
    worktree = HOME / task
    branch = f"sa/{task}"
    HOME.mkdir(parents=True, exist_ok=True)
    _git("worktree", "add", "-q", str(worktree), base_sha, "-b", branch)
    orch._link_frontend_dependencies(worktree, REPO_ROOT)

    run_dir = worktree / ".standalone"
    run_dir.mkdir(exist_ok=True)
    # keep the run files out of the worker's commits
    exclude = Path(_git("rev-parse", "--git-path", "info/exclude", cwd=worktree))
    exclude = exclude if exclude.is_absolute() else (worktree / exclude)
    exclude.parent.mkdir(parents=True, exist_ok=True)
    existing = exclude.read_text() if exclude.exists() else ""
    if ".standalone/" not in existing:
        exclude.write_text(existing + ("\n" if existing and not existing.endswith("\n") else "") + ".standalone/\n")

    prompt_path = run_dir / "prompt.txt"
    prompt_path.write_text(PREAMBLE.format(model=args.model) + brief, encoding="utf-8")
    log_path, exit_file = run_dir / "run.jsonl", run_dir / "exit_code"
    # Preserve the existing writable roots; worker prompts prohibit sandbox Gradle execution.
    extra_dirs = [orch._worktree_git_dir(worktree), orch._git_object_dir(worktree), Path.home() / ".gradle"]
    argv = CodexAdapter().build_argv(
        prompt_path=prompt_path, worktree=worktree, model=args.model, effort=args.effort,
        budget_usd=None, extra_dirs=extra_dirs, resume_session_id=None,
    )
    # No network for the worker unless asked (2026-09-26: a relay-path worker pushed its lane, opened a PR and ran
    # npm install): without network it cannot push, open PRs or install packages; the frontend dependencies are
    # linked; Java/Gradle validation belongs in container images.
    if not args.network:
        argv = [("sandbox_workspace_write.network_access=false" if a == "sandbox_workspace_write.network_access=true" else a)
                for a in argv]
    # CodexAdapter writes the last message under <worktree>/.nexus/; keep it there and create the directory.
    (worktree / ".nexus").mkdir(exist_ok=True)
    env = dict(os.environ)
    orch._add_engineer_toolchains(env)
    env.update(SA_PROMPT=str(prompt_path), SA_LOG=str(log_path), SA_EXIT=str(exit_file))
    # argv is passed as positional parameters ("$@"), never interpolated into the shell text.
    proc = subprocess.Popen(
        ["/bin/sh", "-c", '"$@" < "$SA_PROMPT" > "$SA_LOG" 2>&1; echo $? > "$SA_EXIT"', "standalone", *argv],
        cwd=str(worktree), env=env, start_new_session=True,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    record = {
        "task": task, "model": args.model, "effort": args.effort, "pid": proc.pid, "phase": "running",
        "worktree": str(worktree), "branch": branch, "base_sha": base_sha, "started_at": _now(),
        "log": str(log_path), "exit_file": str(exit_file),
        "last_message": str(worktree / ".nexus" / "engineer_last_message.txt"),
    }
    _save(task, record)
    print(json.dumps({"action": "started", "task": task, "pid": proc.pid, "branch": branch,
                      "worktree": str(worktree), "model": args.model}))
    return 0


def _last_activity(record: dict) -> str | None:
    log = Path(record["log"])
    if not log.exists():
        return None
    adapter = CodexAdapter()
    last = None
    for line in log.read_text(errors="replace").splitlines()[-40:]:
        s = adapter.summarize_line(line)
        if s:
            last = s
    return last[:200] if last else None


def cmd_status(args: argparse.Namespace) -> int:
    tasks = [args.task] if args.task else sorted(p.stem for p in STATE_DIR.glob("*.json")) if STATE_DIR.is_dir() else []
    rows = []
    for t in tasks:
        r = _load(t)
        phase = _phase(r)
        if not args.task:
            if phase == "cleaned":
                continue
            started = dt.datetime.fromisoformat(r["started_at"].replace("Z", "+00:00"))
            minutes = max(0, int((dt.datetime.now(dt.timezone.utc) - started).total_seconds() // 60))
            activity = " ".join((_last_activity(r) or "-").split())[:80]
            print(f"{t} {phase} {minutes}m {activity}")
            continue
        rows.append({"task": t, "phase": phase, "model": r["model"], "branch": r["branch"],
                     "started_at": r["started_at"], "last_activity": _last_activity(r)})
    if args.task:
        print(json.dumps(rows, indent=1))
    return 0


def cmd_wait(args: argparse.Namespace) -> int:
    r = _load(args.task)
    deadline = time.monotonic() + args.timeout
    while _phase(r) == "running" and time.monotonic() < deadline:
        time.sleep(10)
    print(json.dumps({"task": args.task, "phase": _phase(r), "last_activity": _last_activity(r)}))
    return 0


def cmd_result(args: argparse.Namespace) -> int:
    r = _load(args.task)
    wt = Path(r["worktree"])
    print(f"phase: {_phase(r)}")
    print("== commits")
    print(_git("log", "--format=%h %s", f"{r['base_sha']}..HEAD", cwd=wt, check=False) or "(none)")
    print("== diff stat (base..HEAD)")
    print(_git("diff", "--stat", f"{r['base_sha']}..HEAD", cwd=wt, check=False) or "(none)")
    print("== uncommitted")
    print(_git("status", "--short", cwd=wt, check=False) or "(clean)")
    msg = Path(r["last_message"])
    print("== worker final message")
    print(msg.read_text() if msg.exists() else "(none)")
    return 0


def cmd_stop(args: argparse.Namespace) -> int:
    r = _load(args.task)
    if _phase(r) == "running":
        try:
            os.killpg(int(r["pid"]), signal.SIGTERM)
        except ProcessLookupError:
            pass
    r["phase"] = "stopped"
    _save(args.task, r)
    print(json.dumps({"task": args.task, "phase": "stopped"}))
    return 0


def cmd_clean(args: argparse.Namespace) -> int:
    r = _load(args.task)
    if _phase(r) == "running":
        raise SystemExit("still running; stop it first")
    wt = Path(r["worktree"])
    if _git("status", "--porcelain", cwd=wt, check=False) and not args.force:
        raise SystemExit("worktree has uncommitted changes; review them or pass --force")
    unmerged = _git("log", "--format=%h", f"main..{r['branch']}", check=False)
    if unmerged and not args.force:
        raise SystemExit(f"branch {r['branch']} has commits not in main ({unmerged.split()[0]}...); merge or pass --force")
    _git("worktree", "remove", "--force", str(wt), check=False)
    _git("branch", "-D", r["branch"], check=False)
    r["phase"] = "cleaned"
    _save(args.task, r)
    print(json.dumps({"task": args.task, "phase": "cleaned"}))
    return 0


def _registry_fetch_failed(deploy_log: str) -> bool:
    """True when the image build stopped only because a registry download failed (a transient network fault)."""
    return "STOP: build failed" in deploy_log and re.search(r'error building image: Get "https://', deploy_log) is not None


def _deploy(skip_security: str | None = None, targets: tuple[str, ...] | None = None,
            packet_analysis_image: bool = True) -> None:
    """tools/delivery/hosta_deploy.sh with its exit-4 retry (a job in flight), then ui2-configuration set to the service image.

    `targets` (frontend fast path: ("service",)) rolls out only those modules; the others keep their image, so the
    ui2-configuration sync is skipped unless configuration is one of them. `packet_analysis_image=False` builds no
    Packet Analysis image (NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0); the security gate then scans the service image only."""
    log = STATE_DIR / "last_deploy.log"
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    registry_retries = 2
    env = dict(os.environ)
    if not packet_analysis_image:
        env["NEXUS_BUILD_PACKET_ANALYSIS_IMAGE"] = "0"
    for _ in range(30):
        with open(log, "w") as out:
            command = ["bash", "tools/delivery/hosta_deploy.sh"]
            if targets is not None:
                command += ["--targets", " ".join(targets)]
            if skip_security is not None:
                command += ["--skip-security", skip_security]
            rc = subprocess.run(command, cwd=str(REPO_ROOT), stdout=out, stderr=subprocess.STDOUT, env=env).returncode
        if rc == 1 and registry_retries and _registry_fetch_failed(log.read_text(errors="replace")):
            # The image build could not download a base-image layer (2026-10-18: the quay.io CDN failed the same
            # nodejs base layer on two deploys). Nothing was rolled out; the build is retried as is.
            registry_retries -= 1
            print(json.dumps({"deploy": "retry", "reason": "base image layer download failed"}), flush=True)
            time.sleep(60)
            continue
        if rc != 4:
            break
        time.sleep(25)
    tail = log.read_text(errors="replace").strip().splitlines()[-3:]
    # The deploy's own phase timings (numbers only) belong in the ship log: until 2026-11-13 they stayed in last_deploy.log
    # and the 33-minute ship could not be broken down from the ship logs.
    for line in log.read_text(errors="replace").splitlines():
        if re.fullmatch(r"TIMING [a-z_0-9]+ [0-9]+", line):
            print(line, flush=True)
    from tools.security.security_host import safe_summary
    security_passed = False
    snapshot_ids = set()
    for line in log.read_text(errors="replace").splitlines():
        if line.startswith('{"snapshot":'):
            try:
                snapshot_id = json.loads(line)["snapshot"]
            except (ValueError, KeyError, TypeError):
                raise SystemExit("release snapshot summary malformed; ship stopped") from None
            if not isinstance(snapshot_id, str) or not re.fullmatch(r"\d{8}T\d{12}Z_[a-f0-9]{12}", snapshot_id):
                raise SystemExit("release snapshot id invalid; ship stopped")
            if snapshot_id not in snapshot_ids:
                print(json.dumps({"snapshot": snapshot_id}), flush=True)
                snapshot_ids.add(snapshot_id)
        if line.startswith('{"passed":'):
            try:
                summary = safe_summary(line)
                print(json.dumps(summary), flush=True)
                security_passed = summary["passed"]
            except RuntimeError:
                raise SystemExit("security gate summary malformed; ship stopped") from None
    if rc != 0:
        raise SystemExit(f"deploy failed (rc={rc}); see {log}: " + " | ".join(t[:160] for t in tail))
    if len(snapshot_ids) != 1:
        raise SystemExit("one release snapshot required; ship stopped")
    not_configured = '"security_gate":"not_configured"' in log.read_text(errors="replace")
    if not_configured:
        print(json.dumps({"security_gate": "not_configured"}), flush=True)
    if skip_security is None and not security_passed and not not_configured:
        raise SystemExit("security gate success missing; ship stopped before configuration sync")
    if targets is not None and "configuration" not in targets:
        print(json.dumps({"deploy": "ok", "summary": [t[:160] for t in tail], "targets": list(targets),
                          "configuration": "not part of this deploy; keeps its image"}))
        return
    host = (Path.home() / ".config" / "nexus" / "hosta").read_text().splitlines()[0].strip()
    sync = ('export KUBECONFIG=$HOME/.kube/config; '
            'IMG=$(kubectl -n ui2 get deploy ui2-service -o jsonpath="{.spec.template.spec.containers[0].image}"); '
            'C=$(kubectl -n ui2 get deploy ui2-configuration -o jsonpath="{.spec.template.spec.containers[0].name}"); '
            'kubectl -n ui2 set image deploy/ui2-configuration $C=$IMG >/dev/null && '
            'kubectl -n ui2 rollout status deploy/ui2-configuration --timeout=240s | tail -1')
    r = subprocess.run(["ssh", host, sync], capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        raise SystemExit("ui2-configuration image sync failed")
    print(json.dumps({"deploy": "ok", "summary": [t[:160] for t in tail], "configuration": r.stdout.strip()}))


# --- Docs-only fast path (PO 2026-11-03) -------------------------------------------------------------------------
# A branch whose every changed path is documentation or project-state text has no runtime effect: the preview
# (isolated candidate build + e2e), the image build and the production deploy exercise nothing it can change.
# The classification is deliberately closed and conservative: anything not named here -- ui2/, tools/, scripts/,
# deploy/, utils/, tests, migrations, configuration, workflows, history/, relay/ -- sends the ship down the full path.
DOCS_ROOT_SUFFIXES = (".md",)                    # top-level files only (AGENTS.md, CURRENT_STATE.md, AI_HANDOVER.md, ...)
DOCS_TREE_EXTENSIONS = {                         # prefix -> extensions that are text/media for people, never executed
    "docs/": (".md", ".txt", ".csv", ".png", ".svg"),
    "project/": (".md", ".json"),
    "roles/": (".md",),
    ".github/": (".md",),                        # agent instructions and prompts only; workflows are .yml and stay full
}
# Excluded even though they sit under an allowed prefix: the image build copies the mockups, and deploy_info.json is
# generated per deploy (it is gitignored; listing it here keeps a forced add from sneaking through).
DOCS_EXCLUDED = ("docs/design/ui2_mockups/", "project/deploy_info.json")
# project/*.json files the runtime image bakes in (ui2/Containerfile): merging them without a deploy leaves the
# running Project Plan panel on the previous snapshot until the next code deploy. Reported, not blocked.
IMAGE_BAKED_PROJECT_FILES = ("project/roadmap.json", "project/feature_registry.json", "project/backlog.json",
                             "project/build_history.json", "project/java_product_plan.json")
IMAGE_BAKED_PROJECT_PREFIXES = ("project/archive/",)
FASTPATH_MAX_FILES = 400
# A full-regression check runs longer than 15 min (PR 705, 2026-11-03); NEXUS_CHECKS_TIMEOUT_S overrides.
CHECKS_TIMEOUT_S = int(os.environ.get("NEXUS_CHECKS_TIMEOUT_S", str(45 * 60)))
CHECKS_POLL_S = 15
# Run after the merge instead of gating it (PO_DECISION_RECORD_2026_11_04_DEV_MODE_DELEGATIONS.md).
POST_MERGE_CHECKS = frozenset({"full-regression", "targeted-regression"})
CHECKS_EMPTY_GRACE_S = 120
CHECKS_TRANSIENT_ERRORS = 4
FAILING_CONCLUSIONS = {"FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE"}
PASSING_CONCLUSIONS = {"SUCCESS", "NEUTRAL", "SKIPPED"}


def is_docs_path(path: str) -> bool:
    """True when `path` (repository-relative, as git prints it) is documentation or state text only."""
    if not path or path.startswith("/") or "\\" in path or any(part in ("", ".", "..") for part in path.split("/")):
        return False
    if path.startswith(DOCS_EXCLUDED):
        return False
    if "/" not in path:
        return path.endswith(DOCS_ROOT_SUFFIXES)
    for prefix, extensions in DOCS_TREE_EXTENSIONS.items():
        if path.startswith(prefix):
            return path.lower().endswith(extensions)
    return False


def classify_changed_paths(raw_diff_z: str) -> tuple[bool, list[str], list[str]]:
    """Classify `git diff --raw -z --no-renames` output: (docs_only, offending paths, all paths).

    Renames are split into delete+add by --no-renames, so both sides are checked. A symbolic link, a submodule or an
    executable file is never documentation, whatever its name: only regular 0644 files (or a deletion of one) pass."""
    fields = raw_diff_z.split("\0")
    if fields and fields[-1] == "":
        fields.pop()
    if len(fields) % 2 or not fields:
        return False, ["<unparseable diff>"], []
    paths, offenders = [], []
    for index in range(0, len(fields), 2):
        parts = fields[index].lstrip(":").split()
        path = fields[index + 1]
        paths.append(path)
        regular = len(parts) >= 5 and all(mode in ("100644", "000000") for mode in parts[:2])
        if not regular or not is_docs_path(path):
            offenders.append(path)
    if len(paths) > FASTPATH_MAX_FILES:
        offenders.append("<more than %d files>" % FASTPATH_MAX_FILES)
    return not offenders, offenders, paths


def _docs_only_change(branch: str) -> tuple[bool, list[str], list[str]]:
    raw = subprocess.run(["git", "diff", "--raw", "-z", "--no-renames", "origin/main", branch],
                         cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=120)
    if raw.returncode != 0:
        return False, ["<git diff failed>"], []
    return classify_changed_paths(raw.stdout)


# --- Frontend fast path (PO 2026-11-11, built 2026-11-13) --------------------------------------------------------
# A one-line UI fix took 33 minutes through the full ship. The bundle is compiled into the service image
# (ui2/Containerfile stage 0), but only ui2-service is routed from the ingress (deploy/ui2/60-ingress.yaml), so a
# change confined to the frontend has no reason to roll out the worker, policy, configuration, compliance and
# packet-analysis modules (each followed by its own module e2e, about 100 s apiece), to build the Packet Analysis
# image, or to wait for a Java pretest. What it keeps: tsc and vitest (the pretest profile), the isolated preview e2e,
# GitHub's checks (which run the Java suites on the pull request), the rollback snapshot, the security gate on the
# service image and the production e2e.
# Closed list, like the docs one. Build configuration is NOT qualifying even though it sits under ui2/frontend:
# package.json / package-lock.json (dependencies reach the image and the security gate), vite.config.ts,
# tsconfig.json, index.html, public/, Dockerfile.e2e, playwright*.ts, .dockerignore, FIDELITY_NOTES.md.
FRONTEND_TREE_PREFIXES = ("ui2/frontend/src/", "ui2/frontend/tests/", "ui2/frontend/e2e/")
FRONTEND_EXTENSIONS = (".ts", ".tsx", ".mts", ".mjs", ".css", ".json", ".svg", ".png", ".jpg", ".jpeg", ".webp", ".woff2")
FRONTEND_FORBIDDEN_SEGMENTS = ("node_modules", "dist", ".git")
FRONTEND_DEPLOY_TARGETS = ("service",)
# The modules that keep their current image on a frontend ship; each must already run a commit whose difference to
# this branch is frontend/documentation only (see evaluate_deployed_modules).
OTHER_MODULES = ("worker", "configuration", "compliance", "policy", "packet-analysis")
BACKEND_PREFIXES = ("ui2/", "deploy/", "tools/", "scripts/", "utils/", "static/", "templates/", "config/", "bin/")
DEPLOYED_PROBE = r"""export KUBECONFIG=$HOME/.kube/config
for d in worker configuration compliance policy packet-analysis; do
  img=$(kubectl -n ui2 get deploy ui2-$d -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
  pod=$(kubectl -n ui2 get pods -l app.kubernetes.io/component=$d --field-selector=status.phase=Running -o name 2>/dev/null | head -1)
  c=
  [ -n "$pod" ] && c=$(kubectl -n ui2 exec "$pod" -- cat /app/project/deploy_info.json 2>/dev/null | tr -d ' \n' | sed -n 's/.*"commit":"\([0-9a-f]\{40\}\)".*/\1/p')
  i=${img##*@}
  echo "$d ${i:--} ${c:--}"
done
"""


def is_frontend_path(path: str) -> bool:
    """True when `path` is frontend source, unit test or e2e spec text under ui2/frontend (never build configuration)."""
    if not path or path.startswith("/") or "\\" in path or any(part in ("", ".", "..") for part in path.split("/")):
        return False
    if any(part in FRONTEND_FORBIDDEN_SEGMENTS for part in path.split("/")):
        return False
    return path.startswith(FRONTEND_TREE_PREFIXES) and path.lower().endswith(FRONTEND_EXTENSIONS)


def _diff_entries(raw_diff_z: str) -> list[tuple[bool, str]] | None:
    """(regular 0644 file or deletion, path) per record of `git diff --raw -z --no-renames`; None if unparseable."""
    fields = raw_diff_z.split("\0")
    if fields and fields[-1] == "":
        fields.pop()
    if len(fields) % 2 or not fields:
        return None
    entries = []
    for index in range(0, len(fields), 2):
        parts = fields[index].lstrip(":").split()
        entries.append((len(parts) >= 5 and all(mode in ("100644", "000000") for mode in parts[:2]), fields[index + 1]))
    return entries


def _offender_class(regular: bool, path: str) -> str:
    if not regular:
        return "irregular_file"
    if path.startswith("ui2/frontend/"):
        return "frontend_build_config"
    if path.startswith(BACKEND_PREFIXES) or path.startswith(".github/workflows/"):
        return "backend"
    return "other"


def classify_change(raw_diff_z: str) -> dict:
    """Classify `git diff --raw -z --no-renames` output into the ship path it qualifies for.

    kind: 'docs_only' (every path documentation), 'frontend' (every path frontend source/tests/e2e or documentation,
    at least one frontend), or 'full' (anything else, an unparseable diff, or too many files). `offenders` lists the
    paths that forced 'full', `classes` counts them by why (backend, frontend_build_config, irregular_file, other)."""
    entries = _diff_entries(raw_diff_z)
    if entries is None:
        return {"kind": "full", "why": "no changed paths" if raw_diff_z == "" else "the diff could not be parsed", "offenders": ["<unparseable diff>"],
                "classes": {"other": 1}, "paths": [], "frontend_files": 0}
    docs = frontend = 0
    offenders, classes = [], {}
    for regular, path in entries:
        if regular and is_docs_path(path):
            docs += 1
        elif regular and is_frontend_path(path):
            frontend += 1
        else:
            offenders.append(path)
            cls = _offender_class(regular, path)
            classes[cls] = classes.get(cls, 0) + 1
    paths = [path for _, path in entries]
    if len(paths) > FASTPATH_MAX_FILES:
        offenders.append("<more than %d files>" % FASTPATH_MAX_FILES)
        classes["other"] = classes.get("other", 0) + 1
    if offenders:
        first = offenders[0]
        worst = sorted(classes, key=lambda c: ("backend", "frontend_build_config", "irregular_file", "other").index(c))[0]
        return {"kind": "full", "why": "%s change: %s" % (worst.replace("_", " "), first), "offenders": offenders,
                "classes": classes, "paths": paths, "frontend_files": frontend}
    if frontend == 0:
        return {"kind": "docs_only", "why": "every changed path is documentation", "offenders": [], "classes": {},
                "paths": paths, "frontend_files": 0}
    return {"kind": "frontend", "offenders": [], "classes": {}, "paths": paths, "frontend_files": frontend,
            "why": "every changed path is frontend source/tests/e2e under ui2/frontend or documentation "
                   "(%d frontend file%s)" % (frontend, "" if frontend == 1 else "s")}


def _change_verdict(branch: str, base: str = "origin/main", merge_base: bool = False) -> dict:
    """classify_change of the branch against `base`; any git failure is a 'full' verdict (fail closed)."""
    spec = [base + "..." + branch] if merge_base else [base, branch]
    raw = subprocess.run(["git", "diff", "--raw", "-z", "--no-renames", *spec],
                         cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=120)
    if raw.returncode != 0:
        return classify_change("")
    return classify_change(raw.stdout)


def evaluate_deployed_modules(listing: str, classify_since) -> tuple[bool, str]:
    """Decide from the host probe whether every module that stays on its image is current enough.

    `listing` has one line per module: '<module> <image digest or -> <deployed commit or ->'. A module whose pod is not
    running (policy can be scaled to zero) borrows the commit of another module with the same digest. `classify_since(c)`
    returns classify_change of the diff from commit `c` to the branch, or None when git cannot compute it. True only if
    every module resolves to a commit and every such diff is documentation/frontend only: a main that carries backend
    changes the other modules never received (e.g. an earlier merge whose deploy failed) must take the full path, or the
    new service would run ahead of them."""
    rows = {}
    for line in listing.splitlines():
        match = re.fullmatch(r"(\S+) (sha256:[0-9a-f]{64}|-) ([0-9a-f]{40}|-)", line.strip())
        if match and match.group(1) in OTHER_MODULES:
            rows[match.group(1)] = (match.group(2), match.group(3))
    unreadable = [m for m in OTHER_MODULES if m not in rows or rows[m][0] == "-"]
    if unreadable:
        return False, "module image unreadable: " + ", ".join(unreadable)
    by_digest: dict[str, str] = {}
    for digest, commit in rows.values():
        if commit != "-" and by_digest.setdefault(digest, commit) != commit:
            return False, "one image digest reports two commits"
    commits = set()
    for module in OTHER_MODULES:
        commit = by_digest.get(rows[module][0])
        if commit is None:
            return False, module + ": deployed commit unknown"
        commits.add(commit)
    for commit in sorted(commits):
        verdict = classify_since(commit)
        if verdict is None:
            return False, "deployed commit " + commit[:12] + " is not in the local history"
        if verdict["kind"] not in ("frontend", "docs_only"):
            return False, "deployed commit %s differs from this branch beyond frontend/docs: %s" % (commit[:12], verdict["why"])
    return True, "%d other modules run %d commit(s) that differ from this branch in frontend/docs paths only" % (
        len(OTHER_MODULES), len(commits))


def _host() -> str | None:
    host = os.environ.get("NEXUS_HOST", "").strip()
    if host:
        return host
    try:
        return (Path.home() / ".config" / "nexus" / "hosta").read_text().splitlines()[0].strip() or None
    except (OSError, IndexError):
        return None


def _deployed_state_check(branch: str) -> tuple[bool, str]:
    """Read-only probe of HOST-A (kubectl get/exec of /app/project/deploy_info.json), then evaluate_deployed_modules."""
    host = _host()
    if host is None:
        return False, "no host configured (NEXUS_HOST or ~/.config/nexus/hosta)"
    try:
        probe = subprocess.run(["ssh", "-o", "ConnectTimeout=10", "-o", "BatchMode=yes", host, "bash", "-s"],
                               input=DEPLOYED_PROBE, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        return False, "host probe unavailable"
    if probe.returncode != 0:
        return False, "host probe failed"

    def classify_since(commit: str) -> dict | None:
        raw = subprocess.run(["git", "diff", "--raw", "-z", "--no-renames", commit, branch],
                             cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=120)
        if raw.returncode != 0:
            return None
        if raw.stdout == "":
            # The module already runs exactly this tree (found live on 2026-11-13: an empty diff is not "unparseable").
            return {"kind": "docs_only", "why": "no change", "offenders": [], "classes": {}, "paths": [],
                    "frontend_files": 0}
        return classify_change(raw.stdout)

    return evaluate_deployed_modules(probe.stdout, classify_since)


def cmd_classify(args: argparse.Namespace) -> int:
    """Print the ship path a branch would take (for the pretest wrapper); read-only."""
    verdict = _change_verdict(args.branch, merge_base=not args.two_dot)
    path, why = verdict["kind"], verdict["why"]
    if path == "frontend" and args.check_deployed:
        current, detail = _deployed_state_check(args.branch)
        if not current:
            path, why = "full", "frontend-only change, but the other modules are not provably current: " + detail
    print(json.dumps({"path": path, "why": why, "first_paths": verdict["offenders"][:3]}))
    return 0


def _gitleaks_added_content(branch: str) -> str:
    """Secret scan of what the branch adds, with the repository's own gitleaks configuration.

    Returns 'pass', 'unavailable' (binary not installed here) or raises SystemExit on a finding or a scanner error.
    The privacy gate (credential-shaped literals, identities) has already passed; this is the same scanner the
    production security gate runs at deploy, so a docs-only merge cannot carry a finding that blocks the next deploy."""
    if shutil.which("gitleaks") is None:
        return "unavailable"
    result = subprocess.run(["gitleaks", "git", "--redact=100", "--no-banner", "--config", ".gitleaks.toml",
                             "--log-opts=origin/main.." + branch, "."],
                            cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise SystemExit("gitleaks reported a finding or failed on the docs-only branch; nothing pushed "
                         "(run it locally to see the location)")
    return "pass"


def _check_verdict(rollup: list[dict]) -> tuple[str, list[str], list[str]]:
    """Reduce a statusCheckRollup to ('pass'|'fail'|'pending'|'none', failed names, pending names).

    Skipped and neutral checks do not count as passing evidence and do not block; at least one check must have
    succeeded. Anything unrecognised is treated as pending, never as success."""
    failed, pending, succeeded = [], [], 0
    for item in rollup:
        name = str(item.get("name") or item.get("context") or "unnamed")
        if "state" in item and "status" not in item:                    # StatusContext
            state = str(item.get("state") or "").upper()
            if state == "SUCCESS":
                succeeded += 1
            elif state in ("FAILURE", "ERROR"):
                failed.append(name)
            else:
                pending.append(name)
            continue
        status = str(item.get("status") or "").upper()                    # CheckRun
        conclusion = str(item.get("conclusion") or "").upper()
        if status != "COMPLETED":
            pending.append(name)
        elif conclusion in FAILING_CONCLUSIONS:
            failed.append(name)
        elif conclusion == "SUCCESS":
            succeeded += 1
        elif conclusion not in PASSING_CONCLUSIONS:
            pending.append(name)
    if failed:
        return "fail", failed, pending
    if pending:
        return "pending", failed, pending
    return ("pass" if succeeded else "none"), failed, pending


def _wait_for_checks(url: str, timeout: float = CHECKS_TIMEOUT_S, poll: float = CHECKS_POLL_S,
                     empty_grace: float = CHECKS_EMPTY_GRACE_S, sleep=None, clock=None) -> None:
    """Block until every non-skipped GitHub check on the pull request has succeeded; stop the ship otherwise.

    Before this gate (2026-11-03) `gh pr merge` ran four seconds after the PR was opened and never waited for the
    `validate` job. Fails closed: a failing check, no check appearing within the grace period, a timeout, or a gh
    that keeps erroring all stop the ship with the pull request left open for a human to look at."""
    sleep = sleep or time.sleep
    clock = clock or time.monotonic
    started = clock()
    errors = 0
    last = None
    while True:
        view = subprocess.run(["gh", "pr", "view", url, "--json", "statusCheckRollup", "-q", ".statusCheckRollup"],
                              cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=120)
        rollup = None
        if view.returncode == 0:
            try:
                rollup = json.loads(view.stdout.strip() or "[]")
                if not isinstance(rollup, list):
                    rollup = None
            except ValueError:
                rollup = None
        if rollup is None:
            errors += 1
            if errors >= CHECKS_TRANSIENT_ERRORS:
                raise SystemExit("could not read the pull request checks (" + url + "); not merged")
        else:
            errors = 0
            # PO decision 2026-11-04 (fast merge): the merge waits for validate and the scope routing; the regression
            # suites keep running on the pull request after the merge and a red result is fixed forward.
            if os.environ.get("NEXUS_WAIT_FULL_REGRESSION") != "1":
                rollup = [c for c in rollup if not (isinstance(c, dict) and c.get("name") in POST_MERGE_CHECKS)]
            verdict, failed, pending = _check_verdict(rollup)
            if verdict == "pass":
                print(json.dumps({"checks": "pass", "pr": url, "waited_s": round(clock() - started)}), flush=True)
                return
            if verdict == "fail":
                raise SystemExit("pull request checks failed (" + url + "): " + ", ".join(sorted(failed))[:300]
                                 + "; not merged")
            if verdict == "none" and rollup:
                raise SystemExit("every pull request check was skipped (" + url + "); not merged")
            if verdict == "none" and clock() - started >= empty_grace:
                raise SystemExit("no pull request check appeared within " + str(int(empty_grace))
                                 + " s (" + url + "); not merged")
            state = (verdict, tuple(sorted(pending)))
            if state != last:
                print(json.dumps({"checks": "waiting", "pending": sorted(pending)[:8]}), flush=True)
                last = state
        if clock() - started >= timeout:
            raise SystemExit("pull request checks still pending after " + str(int(timeout)) + " s (" + url
                             + "); not merged")
        sleep(poll)


def _pr_body(branch: str, commits: str, notes: str) -> str:
    return ("## What changed\n" + commits + "\n\n## Checks before merge\n"
            "- Java: `:service:test :worker:test :architecture-tests:test` (reviewer, locally)\n"
            "- Frontend: `tsc`, `vitest`, `build` where the lane touched `ui2/frontend`\n"
            "- Repository privacy gate: PASS\n"
            "- Migrations (if any): dry-run in BEGIN/ROLLBACK on the live database\n"
            + (("\n## Notes\n" + notes + "\n") if notes else "")
            + "\nMerged by the engineering session after the checks passed (PO decision 2026-09-30, option a); "
              "deploy and the in-cluster e2e result follow as a PR comment.\n")


def _preview_e2e(repo: Path, commit: str) -> None:
    start = time.monotonic()
    try:
        # The gate also works before its first merge: main may not contain the wrapper yet.
        wrapper = _git("show", commit + ":tools/e2e/hosta_preview_e2e.sh", cwd=repo)
        argv = ["bash", "-s", "--", "--repo", str(repo), "--commit", commit]
        # The tree hash lets the host record which images this preview built and verified, so the production deploy
        # of an identical tree can reuse them (run_build.sh -> hosta_restamp_image.py). A wrapper from before that
        # change does not know the flag; it is only passed to a wrapper that does.
        tree = _git("rev-parse", commit + "^{tree}", cwd=repo, check=False)
        if "--tree" in wrapper and re.fullmatch(r"[0-9a-f]{40}", tree or ""):
            argv += ["--tree", tree]
        result = subprocess.run(argv, input=wrapper, capture_output=True, text=True, timeout=7200)
        # The wrapper returns timings, masked API diagnostics and a derived verdict.
        for line in result.stdout.splitlines():
            if (re.fullmatch(r"TIMING preview_[a-z_]+ [0-9.]+", line)
                    or re.fullmatch(r"PREVIEW IMAGES: tree=[0-9a-f]{40} service=sha256:[0-9a-f]{64} e2e=sha256:[0-9a-f]{64}", line)
                    or line.startswith(("PREVIEW STEP: ", "PREVIEW E2E: FAIL"))):
                print(line, flush=True)
        if result.returncode != 0 or "PREVIEW E2E: PASS" not in result.stdout.splitlines():
            raise SystemExit("preview e2e failed; stopped before PR merge")
        print("PREVIEW E2E: PASS", flush=True)
    finally:
        print(f"TIMING preview_total {time.monotonic() - start:.3f}", flush=True)


def cmd_ship(args: argparse.Namespace) -> int:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with (STATE_DIR / "ship.lock").open("a+") as lock:
        deadline = time.monotonic() + 45 * 60
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return 3
                lock.seek(0)
                owner = lock.read().strip() or "another task"
                print(f"waiting for {owner} ship", flush=True)
                time.sleep(min(60, remaining))
        lock.seek(0)
        lock.truncate()
        lock.write(args.task or args.branch or "unknown task")
        lock.flush()
        return _ship(args)


def _host_mirror_url() -> str:
    """ssh URL of the host's bare repository; the host itself is local configuration, never a repository literal."""
    host = os.environ.get("NEXUS_HOST", "").strip()
    if not host:
        path = Path.home() / ".config" / "nexus" / "hosta"
        host = path.read_text().splitlines()[0].strip() if path.is_file() else ""
    if not host:
        raise SystemExit("set NEXUS_HOST or write user@host to ~/.config/nexus/hosta")
    return "ssh://" + host + HOST_BARE_REPO


def _ship(args: argparse.Namespace) -> int:
    """Ship through a pull request (PO 2026-09-30): push the lane branch, open a PR, merge it, deploy from main."""
    skip_security = getattr(args, "skip_security", None)
    skip_preview = getattr(args, "no_preview_e2e", None)
    if skip_preview is not None:
        if not skip_preview.strip() or len(skip_preview) > 200 or any(ord(c) < 32 for c in skip_preview):
            raise SystemExit("--no-preview-e2e requires a nonempty, single-line reason (maximum 200 characters)")
        print("PREVIEW E2E SKIPPED: " + skip_preview, flush=True)
    skip_checks = getattr(args, "skip_checks", None)
    if skip_checks is not None:
        if not skip_checks.strip() or len(skip_checks) > 200 or any(ord(c) < 32 for c in skip_checks):
            raise SystemExit("--skip-checks requires a nonempty, single-line reason (maximum 200 characters)")
        print("EMERGENCY: PULL REQUEST CHECKS NOT AWAITED: " + skip_checks, flush=True)
    if skip_security is not None:
        if not skip_security.strip() or len(skip_security) > 200 or any(ord(c) < 32 for c in skip_security):
            raise SystemExit("--skip-security requires a nonempty, single-line reason (maximum 200 characters)")
        print("EMERGENCY: SECURITY GATE SKIPPED: " + skip_security, flush=True)
    if _git("status", "--porcelain", "--untracked-files=no"):
        raise SystemExit("main checkout has uncommitted tracked changes; commit or set them aside first")
    _git("fetch", "-q", "origin")
    _git("checkout", "-q", "main")
    _git("merge", "-q", "--ff-only", "origin/main")
    if args.task:
        r = _load(args.task)
        if _phase(r) == "running":
            raise SystemExit("task still running")
        wt, branch = Path(r["worktree"]), r["branch"]
        if _git("status", "--porcelain", "--untracked-files=no", cwd=wt, check=False):
            raise SystemExit("lane has uncommitted changes; review and commit them first")
        rb = subprocess.run(["git", "rebase", "origin/main"], cwd=str(wt), capture_output=True, text=True)
        if rb.returncode != 0:
            subprocess.run(["git", "rebase", "--abort"], cwd=str(wt), capture_output=True)
            raise SystemExit("lane does not rebase cleanly onto origin/main; resolve in the worktree first")
    elif args.branch:
        branch = args.branch
        rb = subprocess.run(["git", "rebase", "origin/main", branch], cwd=str(REPO_ROOT), capture_output=True, text=True)
        _git("checkout", "-q", "main")
        if rb.returncode != 0:
            subprocess.run(["git", "rebase", "--abort"], cwd=str(REPO_ROOT), capture_output=True)
            raise SystemExit("branch does not rebase cleanly onto origin/main")
    else:
        raise SystemExit("ship needs --task or --branch (nothing goes to main without a pull request)")
    if _git("rev-list", "--count", "origin/main.." + branch) == "0":
        raise SystemExit("nothing to ship: the branch has no commits beyond origin/main")
    # The gate scans the branch being shipped (a detached worktree of it), never the main checkout: on 2026-10-16
    # a finding passed this gate because it scanned main and was caught only by the HOST-A pre-receive hook.
    gate_tree = Path(tempfile.mkdtemp(prefix="ship-privacy-"))
    try:
        _git("worktree", "add", "-q", "--detach", str(gate_tree), branch)
        gate = subprocess.run([sys.executable, "tools/privacy/repository_privacy_check.py"], cwd=str(gate_tree),
                              capture_output=True, text=True)
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", str(gate_tree)], cwd=str(REPO_ROOT),
                       capture_output=True)
    if gate.returncode != 0 or "Gate:                 PASS" not in gate.stdout:
        raise SystemExit("privacy gate did not pass on the shipped branch; nothing pushed")
    fast_path = False
    frontend_path = False
    if getattr(args, "full", False) or getattr(args, "no_fast_path", False):
        print(json.dumps({"ship_path": "full", "why": "--full requested" if getattr(args, "full", False)
                          else "--no-fast-path requested"}), flush=True)
    else:
        verdict = _change_verdict(branch)
        if verdict["kind"] == "docs_only":
            fast_path = True
            paths = verdict["paths"]
            secret_scan = _gitleaks_added_content(branch)
            print(json.dumps({
                "fast_path": "docs_only", "files": len(paths), "privacy_gate": "pass", "gitleaks": secret_scan,
                "preview": "skipped", "image_build": "skipped", "deploy": "skipped",
                "not_deployed_until_next_code_deploy": sorted(
                    p for p in paths if p in IMAGE_BAKED_PROJECT_FILES or p.startswith(IMAGE_BAKED_PROJECT_PREFIXES))[:10],
            }), flush=True)
            print(json.dumps({"ship_path": "docs_only", "why": verdict["why"]}), flush=True)
        elif verdict["kind"] == "frontend":
            current, detail = _deployed_state_check(branch)
            if current:
                frontend_path = True
                secret_scan = _gitleaks_added_content(branch)
                print(json.dumps({
                    "fast_path": "frontend", "files": len(verdict["paths"]), "frontend_files": verdict["frontend_files"],
                    "privacy_gate": "pass", "gitleaks": secret_scan, "preview": "kept", "early_pr": True,
                    "deploy_targets": list(FRONTEND_DEPLOY_TARGETS), "packet_analysis_image": "skipped",
                    "other_modules": detail,
                }), flush=True)
                print(json.dumps({"ship_path": "frontend", "why": verdict["why"]}), flush=True)
            else:
                print(json.dumps({"fast_path": "not_applicable", "first_paths": [], "classes": {}}), flush=True)
                print(json.dumps({"ship_path": "full", "why": "frontend-only change, but the other modules are not "
                                  "provably current: " + detail}), flush=True)
        else:
            print(json.dumps({"fast_path": "not_applicable", "first_paths": verdict["offenders"][:3],
                              "classes": verdict["classes"]}), flush=True)
            print(json.dumps({"ship_path": "full", "why": verdict["why"]}), flush=True)
    run_preview = skip_preview is None and not fast_path
    candidate_repo = wt if args.task else REPO_ROOT

    def open_pull_request() -> str:
        _git("push", "-q", "--force-with-lease", "origin", branch + ":" + branch)
        commits = _git("log", "--format=- %s", "origin/main.." + branch)
        title = args.title or _git("log", "-1", "--format=%s", branch)
        body = _pr_body(branch, commits, args.notes or "")
        if fast_path:
            body += ("\nDocs-only change (every changed path is documentation or project-state text): the isolated "
                     "preview, the image build and the production deploy were skipped by design; the privacy gate and "
                     "the GitHub checks gate the merge.\n")
        if frontend_path:
            body += ("\nFrontend-only change (every changed path is frontend source, tests or e2e under ui2/frontend, or "
                     "documentation): tsc, vitest, the isolated preview e2e, GitHub's checks (including the Java "
                     "suites), the rollback snapshot, the security gate on the service image and the production e2e all "
                     "run; only ui2-service is rolled out and no Packet Analysis image is built.\n")
        pr = subprocess.run(["gh", "pr", "create", "--base", "main", "--head", branch, "--title", title,
                             "--body", body], cwd=str(REPO_ROOT), capture_output=True, text=True)
        found = pr.stdout.strip().splitlines()[-1] if pr.returncode == 0 else ""
        if not found:
            existing = subprocess.run(["gh", "pr", "view", branch, "--json", "url", "-q", ".url"],
                                      cwd=str(REPO_ROOT), capture_output=True, text=True)
            found = existing.stdout.strip()
        if not found:
            raise SystemExit("could not open the pull request: " + (pr.stderr.strip()[-300:] or "unknown"))
        return found

    # Default order (unchanged): the isolated preview passes BEFORE anything is pushed or a pull request exists.
    # --early-pr (opt-in) pushes and opens the pull request first, so GitHub's `validate` check (about 95 s) runs while
    # the preview does; a failed preview then closes the pull request and deletes the pushed branch.
    # The frontend path always opens the pull request early: GitHub's validate (about 95 s, and the Java suites this path
    # does not run locally) then overlaps the preview instead of following it.
    early_pr = (bool(getattr(args, "early_pr", False)) or frontend_path) and run_preview
    url = open_pull_request() if early_pr else ""
    if run_preview:
        try:
            # Structural/template/worker changes need the same gate as frontend changes.
            _preview_e2e(candidate_repo, _git("rev-parse", branch))
        except SystemExit:
            if early_pr:
                subprocess.run(["gh", "pr", "close", url, "--comment",
                                "Closed by the ship: the isolated preview e2e did not pass."],
                               cwd=str(REPO_ROOT), capture_output=True, text=True)
                subprocess.run(["git", "push", "-q", "origin", "--delete", branch], cwd=str(REPO_ROOT),
                               capture_output=True)
            raise
    if not url:
        url = open_pull_request()
    # Merge only after GitHub's own checks have passed (2026-11-03: the merge used to run 4 s after PR creation).
    if skip_checks is None:
        _wait_for_checks(url)
    # No --delete-branch: gh would delete the local branch a lane worktree still has checked out.
    merge = subprocess.run(["gh", "pr", "merge", url, "--rebase"],
                           cwd=str(REPO_ROOT), capture_output=True, text=True)
    if merge.returncode != 0:
        raise SystemExit("pull request not merged (" + url + "): " + merge.stderr.strip()[-300:])
    subprocess.run(["git", "push", "-q", "origin", "--delete", branch], cwd=str(REPO_ROOT), capture_output=True)
    _git("fetch", "-q", "origin")
    _git("merge", "-q", "--ff-only", "origin/main")
    _git("push", "-q", "--receive-pack=" + HOST_RECEIVE_PACK, _host_mirror_url(), "main")
    print(json.dumps({"pr": url, "merged": _git("rev-parse", "--short", "HEAD")}))
    if fast_path:
        print(json.dumps({"fast_path": "docs_only", "deployed": False, "pr": url}), flush=True)
        subprocess.run(["gh", "pr", "comment", url, "--body",
                        "Merged on the docs-only fast path: no preview, image build or deploy ran."],
                       cwd=str(REPO_ROOT), capture_output=True, text=True)
        if args.task and Path(r["worktree"]).exists():
            args.force = True
            cmd_clean(args)
        return 0
    deploy_started = time.monotonic()
    if frontend_path:
        _deploy(skip_security, targets=FRONTEND_DEPLOY_TARGETS, packet_analysis_image=False)
    else:
        _deploy(skip_security)
    print(f"TIMING ship_deploy {time.monotonic() - deploy_started:.0f}", flush=True)
    # The in-cluster e2e screen suite after every deploy (PO 2026-09-27); a failure is reported, not rolled back.
    e2e_started = time.monotonic()
    e2e = subprocess.run(["bash", "tools/e2e/hosta_e2e.sh", "--full" if getattr(args, "full", False) else "--quick"], cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(f"TIMING ship_e2e {time.monotonic() - e2e_started:.0f}", flush=True)
    lines = [l for l in e2e.stdout.splitlines() if l.strip()]
    verdict = "pass" if e2e.returncode == 0 and "E2E: PASS" in e2e.stdout else "FAIL"
    print(json.dumps({"e2e": verdict, "summary": lines[-6:]}))
    subprocess.run(["gh", "pr", "comment", url, "--body",
                    "Deployed to HOST-A. In-cluster e2e: **" + verdict + "** (" + (lines[-2] if len(lines) > 1 else "") + ")"],
                   cwd=str(REPO_ROOT), capture_output=True, text=True)
    integration_ok = True
    if getattr(args, "full", False):
        integration = subprocess.run(["bash", "tools/delivery/hosta_integration.sh"], cwd=str(REPO_ROOT),
                                     capture_output=True, text=True)
        integration_ok = integration.returncode == 0 and "INTEGRATION: PASS" in integration.stdout
        summary = "PASS" if integration_ok else "FAIL"
        print(json.dumps({"integration": summary}))
        subprocess.run(["gh", "pr", "comment", url, "--body",
                        "Full ship: ephemeral PostgreSQL 16 integration tests: **" + summary + "**."],
                       cwd=str(REPO_ROOT), capture_output=True, text=True)
    if args.task and Path(r["worktree"]).exists():
        args.force = True
        cmd_clean(args)
    return 0 if verdict == "pass" and integration_ok else 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="standalone_orchestrate", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("start")
    s.add_argument("--task", required=True)
    s.add_argument("--model", choices=MODELS, default="gpt-6.1-sol", help="worker model (default: %(default)s)")
    s.add_argument("--effort", default="medium", choices=["minimal", "low", "medium", "high"])
    s.add_argument("--brief", required=True, help="brief file, or - for stdin")
    s.add_argument("--max-parallel", type=int, choices=range(1, 7), default=4)
    s.add_argument("--base", default=None, help="base commit-ish (default origin/main)")
    s.add_argument("--network", action="store_true", help="allow the worker's commands network access (default off)")
    for name in ("status",):
        q = sub.add_parser(name)
        q.add_argument("--task")
    w = sub.add_parser("wait")
    w.add_argument("--task", required=True)
    w.add_argument("--timeout", type=int, default=540)
    for name in ("result", "stop"):
        q = sub.add_parser(name)
        q.add_argument("--task", required=True)
    sh = sub.add_parser("ship")
    sh.add_argument("--task")
    sh.add_argument("--branch", help="ship a local branch (a reviewer hotfix) through a pull request")
    sh.add_argument("--title")
    sh.add_argument("--full", action="store_true", help="run every-tab/latency E2E and real PostgreSQL integration tests")
    sh.add_argument("--skip-checks", metavar="REASON",
                    help="emergency: merge without waiting for GitHub's checks; a reason is mandatory and printed")
    sh.add_argument("--early-pr", action="store_true",
                    help="push and open the pull request before the preview so GitHub's checks run during it "
                         "(default: nothing is pushed until the preview has passed)")
    sh.add_argument("--no-fast-path", action="store_true",
                    help="never take the docs-only or frontend fast path (preview, image build and the full deploy always run)")
    sh.add_argument("--notes", help="extra lines for the PR body (live validation, measurements)")
    sh.add_argument("--skip-security", metavar="REASON", help="emergency bypass; a reason is mandatory and printed")
    preview = sh.add_mutually_exclusive_group()
    preview.add_argument("--preview-e2e", action="store_true",
                         help="isolated pre-merge quick e2e (enabled by default for every ship)")
    preview.add_argument("--no-preview-e2e", metavar="REASON", help="skip preview e2e with a recorded reason")
    c = sub.add_parser("clean")
    c.add_argument("--task", required=True)
    c.add_argument("--force", action="store_true")
    cl = sub.add_parser("classify", help="print the ship path a branch qualifies for (read-only)")
    cl.add_argument("--branch", required=True)
    cl.add_argument("--check-deployed", action="store_true",
                    help="also require the other modules to run commits that differ from the branch in frontend/docs only")
    cl.add_argument("--two-dot", action="store_true", help="diff against origin/main directly (branch already rebased)")
    args = p.parse_args(argv)
    return {"start": cmd_start, "status": cmd_status, "wait": cmd_wait, "result": cmd_result,
            "stop": cmd_stop, "clean": cmd_clean, "ship": cmd_ship, "classify": cmd_classify}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
