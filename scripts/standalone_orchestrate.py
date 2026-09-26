#!/usr/bin/env python3
"""Standalone orchestration -- one small Codex development task at a time, without the relay.

Product Owner, 2026-09-26: "Relay kaydı işini bırakalım ... tek küçük geliştirmeler için daha agile çalışsın. Var olan
relay yapısını da bozmayalım." This is an ADDITIONAL, lighter path next to `scripts/orchestrator.py`; it does not
read, write or change relay files, orchestrator state or `docs/design/GOV_*` contracts. The Product Owner says which
mode a piece of work uses.

What it keeps from the orchestrator (imported, not re-derived): the Codex argv (`CodexAdapter`), the writable git
roots a commit on the lane needs, the linked frontend dependencies and the engineer toolchain PATH.
What it drops: SESSION_START/SESSION_CLOSE packets, the FROZEN-authority preflight, relay turn ownership.

Rules it enforces:
- **One task at a time** (PO 2026-09-26: no parallel work): `start` refuses while another standalone task or an
  orchestrator movement with a live process is running.
- The worker commits on its own lane only; never pushes, deploys, touches HOST-A or contacts a device (stated in the
  prompt). Review, merge, deploy and live validation stay with the engineering session.

Usage:
  standalone_orchestrate.py start  --task <slug> --model gpt-6-luna|gpt-6-sol [--effort medium] [--network] --brief FILE|-
  standalone_orchestrate.py status [--task <slug>]
  standalone_orchestrate.py wait   --task <slug> [--timeout 540]
  standalone_orchestrate.py result --task <slug>
  standalone_orchestrate.py stop   --task <slug>
  standalone_orchestrate.py clean  --task <slug>      (after merge: removes the worktree and the lane branch)
  standalone_orchestrate.py ship   [--task <slug>]    (fast-forward main to the lane if given, privacy gate, push origin +
                                                       hosta, deploy with retry, sync ui2-configuration, clean the task)
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import orchestrator as orch  # noqa: E402  (reuse: git roots, frontend link, toolchains, pid liveness)
from orchestrator_providers import CodexAdapter  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
HOME = REPO_ROOT.parent / f"{REPO_ROOT.name}-standalone"
STATE_DIR = HOME / ".state"
MODELS = ("gpt-6-luna", "gpt-6-sol")
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
- A new table in a migration also needs `GRANT SELECT, INSERT, UPDATE, DELETE ON <table> TO ui2_app;` (see V72).
- Run the validation the brief lists (frontend: `cd ui2/frontend && npx tsc --noEmit -p . && npx vitest run && npm run
  build`, with `--cacheDir` / cache paths inside the worktree if node_modules is read-only; Java: the named
  `./gradlew` tasks) and `python3 scripts/repository_privacy_check.py`. Fix what fails.

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
    task = args.task
    if not SLUG.match(task):
        raise SystemExit("task must be a lowercase slug (letters, digits, dashes)")
    if args.model not in MODELS:
        raise SystemExit(f"model must be one of {MODELS}")
    if _state_path(task).exists():
        raise SystemExit(f"task {task!r} already exists; use status/result/clean")
    busy = _live_standalone() + _live_orchestrator_movements()
    if busy:
        raise SystemExit(f"refused: one task at a time -- still running: {', '.join(busy)}")
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
    extra_dirs = [orch._worktree_git_dir(worktree), orch._git_object_dir(worktree)]
    argv = CodexAdapter().build_argv(
        prompt_path=prompt_path, worktree=worktree, model=args.model, effort=args.effort,
        budget_usd=None, extra_dirs=extra_dirs, resume_session_id=None,
    )
    # No network for the worker unless asked (2026-09-26: a relay-path worker pushed its lane, opened a PR and ran
    # npm install): without network it cannot push, open PRs or install packages; the frontend dependencies are
    # linked and Gradle runs from its local cache.
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
        rows.append({"task": t, "phase": _phase(r), "model": r["model"], "branch": r["branch"],
                     "started_at": r["started_at"], "last_activity": _last_activity(r)})
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


def _deploy() -> None:
    """scripts/hosta_deploy.sh with its exit-4 retry (a job in flight), then ui2-configuration set to the service image."""
    log = STATE_DIR / "last_deploy.log"
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    for _ in range(30):
        with open(log, "w") as out:
            rc = subprocess.run(["bash", "scripts/hosta_deploy.sh"], cwd=str(REPO_ROOT), stdout=out, stderr=subprocess.STDOUT).returncode
        if rc != 4:
            break
        time.sleep(25)
    tail = log.read_text(errors="replace").strip().splitlines()[-3:]
    if rc != 0:
        raise SystemExit(f"deploy failed (rc={rc}); see {log}: " + " | ".join(t[:160] for t in tail))
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


def cmd_ship(args: argparse.Namespace) -> int:
    if _git("status", "--porcelain", "--untracked-files=no"):
        raise SystemExit("main checkout has uncommitted tracked changes; commit or set them aside first")
    if args.task:
        r = _load(args.task)
        if _phase(r) == "running":
            raise SystemExit("task still running")
        wt = Path(r["worktree"])
        if _git("status", "--porcelain", "--untracked-files=no", cwd=wt, check=False):
            raise SystemExit("lane has uncommitted changes; review and commit them first")
        # main may have moved since the lane was cut (state or tooling commits): replay the lane onto it first.
        if _git("merge-base", "--is-ancestor", "main", r["branch"], check=False) == "" and subprocess.run(
                ["git", "merge-base", "--is-ancestor", "main", r["branch"]], cwd=str(REPO_ROOT)).returncode != 0:
            rb = subprocess.run(["git", "rebase", "main"], cwd=str(wt), capture_output=True, text=True)
            if rb.returncode != 0:
                subprocess.run(["git", "rebase", "--abort"], cwd=str(wt), capture_output=True)
                raise SystemExit("lane does not rebase cleanly onto main; resolve in the worktree first")
        _git("merge", "--ff-only", r["branch"])
    gate = subprocess.run([sys.executable, "scripts/repository_privacy_check.py"], cwd=str(REPO_ROOT),
                          capture_output=True, text=True)
    if gate.returncode != 0 or "Gate:                 PASS" not in gate.stdout:
        raise SystemExit("privacy gate did not pass; nothing pushed")
    _git("push", "-q", "origin", "main")
    _git("push", "-q", "hosta", "main")
    print(json.dumps({"pushed": _git("rev-parse", "--short", "HEAD")}))
    _deploy()
    if args.task:
        args.force = False
        cmd_clean(args)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="standalone_orchestrate", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("start")
    s.add_argument("--task", required=True)
    s.add_argument("--model", required=True)
    s.add_argument("--effort", default="medium", choices=["minimal", "low", "medium", "high"])
    s.add_argument("--brief", required=True, help="brief file, or - for stdin")
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
    c = sub.add_parser("clean")
    c.add_argument("--task", required=True)
    c.add_argument("--force", action="store_true")
    args = p.parse_args(argv)
    return {"start": cmd_start, "status": cmd_status, "wait": cmd_wait, "result": cmd_result,
            "stop": cmd_stop, "clean": cmd_clean, "ship": cmd_ship}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
