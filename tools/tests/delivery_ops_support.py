"""Shared fixtures for the delivery-ops tests (tools/delivery/ops): run the runner scripts' shell functions against temp
directories with fake CLIs on PATH. No network, no real queues, no launchd; HOME points at a temp dir."""
from __future__ import annotations

import os
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPS = ROOT / "tools" / "delivery" / "ops"


class OpsEnv:
    """A throw-away state dir (queues, logs), Codo dir (worktrees) and main checkout, plus a bin dir for fake CLIs."""

    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.home = tmp / "home"
        self.state = tmp / "state"
        self.codo = tmp / "codo"
        self.repo = self.codo / "main-checkout"
        self.bin = tmp / "bin"
        for d in (self.home, self.state, self.codo, self.bin, self.state / "logs", self.state / "lane-queue" / "started",
                  self.state / "lane-queue" / "done", self.state / "ship-queue"):
            d.mkdir(parents=True, exist_ok=True)
        self.lq = self.state / "lane-queue"
        self.sq = self.state / "ship-queue"
        self.logs = self.state / "logs"
        self.env = {
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "HOME": str(self.home),
            "NEXUS_OPS_DIR": str(OPS),
            "NEXUS_OPS_STATE": str(self.state),
            "NEXUS_OPS_CODO": str(self.codo),
            "NEXUS_OPS_REPO": str(self.repo),
            "NEXUS_FLAKY_ALLOWLIST": str(tmp / "allowlist.txt"),
            "NEXUS_FLAKY_LOG": str(tmp / "flaky.log"),
            "NEXUS_JT_TMP": str(tmp),
            "NEXUS_MAIN_GREEN_TMP": str(tmp),
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid",
            "GIT_CONFIG_GLOBAL": str(tmp / "gitconfig"), "GIT_CONFIG_SYSTEM": "/dev/null",
        }
        (tmp / "allowlist.txt").write_text("")
        self.fake("gh", "exit 0")

    def fake(self, name: str, body: str) -> Path:
        path = self.bin / name
        path.write_text("#!/bin/bash\n" + body + "\n")
        path.chmod(path.stat().st_mode | stat.S_IEXEC)
        return path

    def bash(self, script: str, cwd: Path | None = None, extra_env: dict | None = None, timeout: int = 120):
        env = dict(self.env, **(extra_env or {}))
        return subprocess.run(["bash", "-c", script], cwd=str(cwd or self.tmp), env=env, capture_output=True, text=True,
                              timeout=timeout)

    def source(self, script_name: str, body: str, **kw):
        """Source one runner script (functions only; its main loop is guarded) and run `body` in the same shell."""
        return self.bash(f'. "{OPS / script_name}"\n{body}', **kw)

    def git_repo(self, path: Path, files: dict | None = None) -> Path:
        path.mkdir(parents=True, exist_ok=True)
        run = lambda *a: subprocess.run(["git", *a], cwd=str(path), env=self.env, capture_output=True, text=True, check=True)  # noqa: E731
        run("init", "-q", "-b", "main")
        for rel, text in (files or {"README.md": "x\n"}).items():
            (path / rel).parent.mkdir(parents=True, exist_ok=True)
            (path / rel).write_text(text)
        run("add", "-A")
        run("commit", "-q", "-m", "base")
        return path

    def git(self, cwd: Path, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=str(cwd), env=self.env, capture_output=True, text=True,
                              check=True).stdout.strip()

    def write_lane(self, name: str, exit_line: str | None, report: str = "", meta: str = "base=origin/main\n",
                   age_s: float = 600) -> Path:
        """A lane in started/ with its engine log; `exit_line` None = still running."""
        brief = self.lq / "started" / f"{name}.brief"
        brief.write_text("brief\n")
        (self.lq / "started" / f"{name}.meta").write_text(meta)
        text = report + ("" if exit_line is None else f"\n{exit_line}\n")
        (self.logs / f"q-{name}.nohup").write_text(text)
        past = os.path.getmtime(brief) - age_s
        os.utime(brief, (past, past))
        return brief
