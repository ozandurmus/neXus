#!/usr/bin/env python3
"""Scope the pre-ship unit tests to what a narrow diff can break; fail closed to the full suite.

The queue wrapper used to run every Java test (about 5,600) plus vitest before every ship (8-10 minutes). When every
changed path sits inside Gradle modules (`ui2/<module>/...`) and/or the frontend, only those modules and the modules
that depend on them (any configuration, runtimeOnly included) need their tests, plus `architecture-tests`, which
reads the whole code base. Anything else -- build files, the dependency lockfile, gradle properties, tools, deploy
manifests, scripts, an unknown path, an unparseable diff -- selects the full suite, as does `--full`. Documentation
paths need no tests of their own and never widen the scope. The nightly run keeps the full suite.

  pretest_scope.py [--repo DIR] [--base origin/main] [--branch HEAD]      # git diff --name-only base...branch
  pretest_scope.py --paths-from-stdin                                       # one path per line

Prints one JSON line: {"scope": "full"|"scoped", "gradle_tasks": [":worker:test", ...], "frontend": bool,
"modules": [...], "why": "..."}. For "full", gradle_tasks is empty and the caller runs everything it ran before."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
# Always selected: it scans every module's classes and the repository layout.
ALWAYS = ("architecture-tests",)
# Never run by the pre-ship suite (it needs a database; the ship's integration step covers it).
NOT_IN_PRESHIP = ("integration-tests",)
FRONTEND_PREFIX = "ui2/frontend/"
# Under ui2/frontend/ only source, unit tests and e2e specs leave the frontend scope questions to vitest/tsc;
# build configuration changes how the Java service image is assembled, so they run everything.
FRONTEND_SCOPED = ("ui2/frontend/src/", "ui2/frontend/tests/", "ui2/frontend/e2e/")
DOCS_EXTENSIONS = (".md",)


def modules_of(repo: Path = REPO) -> list[str]:
    text = (repo / "ui2" / "settings.gradle.kts").read_text()
    block = re.search(r"include\((.*?)\)", text, re.S)
    if block is None:
        raise ValueError("settings.gradle.kts has no include block")
    body = re.sub(r"//[^\n]*", "", block.group(1))
    return re.findall(r'"([a-z0-9-]+)"', body)


def dependencies(repo: Path = REPO, modules: list[str] | None = None) -> dict[str, set[str]]:
    """module -> modules it depends on, from every `project(":x")` in its build file (any configuration)."""
    modules = modules if modules is not None else modules_of(repo)
    graph: dict[str, set[str]] = {}
    for module in modules:
        build = repo / "ui2" / module / "build.gradle.kts"
        if not build.is_file():
            raise ValueError("no build file for module " + module)
        graph[module] = set(re.findall(r'project\(":([a-z0-9-]+)"\)', build.read_text())) - {module}
    return graph


def dependents_closure(changed: set[str], graph: dict[str, set[str]]) -> set[str]:
    result = set(changed)
    grew = True
    while grew:
        grew = False
        for module, deps in graph.items():
            if module not in result and deps & result:
                result.add(module)
                grew = True
    return result


def is_docs(path: str) -> bool:
    return path.endswith(DOCS_EXTENSIONS) and not path.startswith(("ui2/", "tools/", "deploy/", "scripts/"))


def full(why: str) -> dict:
    return {"scope": "full", "gradle_tasks": [], "frontend": True, "modules": [], "why": why}


def scope_for(paths: list[str], repo: Path = REPO) -> dict:
    paths = [p for p in paths if p]
    if not paths:
        return full("no changed paths")
    try:
        modules = modules_of(repo)
        graph = dependencies(repo, modules)
    except (OSError, ValueError) as error:
        return full("module graph unreadable: " + str(error)[:120])
    changed: set[str] = set()
    frontend = False
    for path in paths:
        if (not path or path.startswith("/") or "\\" in path
                or any(part in ("", ".", "..") for part in path.split("/"))):
            return full("unusual path: " + path[:80])
        if is_docs(path):
            continue
        if path.startswith(FRONTEND_SCOPED) and path.startswith(FRONTEND_PREFIX):
            frontend = True
            continue
        head = path.split("/")
        if len(head) >= 3 and head[0] == "ui2" and head[1] in modules and head[1] not in NOT_IN_PRESHIP:
            changed.add(head[1])
            continue
        return full("path outside the closed module scope: " + path[:120])
    selected = dependents_closure(changed, graph) if changed else set()
    if changed or frontend:
        selected |= set(ALWAYS)
    selected -= set(NOT_IN_PRESHIP)
    if not changed and not frontend:
        # Documentation only: nothing to test beyond the structural architecture tests that read the repository.
        selected = set(ALWAYS)
    tasks = [":%s:test" % module for module in modules if module in selected]
    return {"scope": "scoped", "gradle_tasks": tasks, "frontend": frontend,
            "modules": sorted(changed), "why": "%d changed module(s), %d test task(s)%s" % (
                len(changed), len(tasks), ", frontend tests" if frontend else "")}


def changed_paths(repo: Path, base: str, branch: str) -> list[str] | None:
    result = subprocess.run(["git", "diff", "--name-only", "--no-renames", "-z", base + "..." + branch],
                            cwd=str(repo), capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        return None
    return [p for p in result.stdout.split("\0") if p]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--branch", default="HEAD")
    parser.add_argument("--paths-from-stdin", action="store_true")
    parser.add_argument("--full", action="store_true", help="force the full suite")
    args = parser.parse_args(argv)
    if args.full:
        verdict = full("--full requested")
    elif args.paths_from_stdin:
        verdict = scope_for(sys.stdin.read().splitlines(), args.repo)
    else:
        paths = changed_paths(args.repo, args.base, args.branch)
        verdict = full("git diff failed") if paths is None else scope_for(paths, args.repo)
    print(json.dumps(verdict))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
