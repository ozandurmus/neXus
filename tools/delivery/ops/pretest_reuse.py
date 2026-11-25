#!/usr/bin/env python3
"""Ship pretest reuse (PO decision record 2026-11-24 D3, PO 2026-11-25 option A): may shipq3.sh skip jt.sh?

  pretest_reuse.py --worktree <pretest worktree> --items <dir of the batch's *.ship entries>

Exit 0 and one line "<reason>" when EVERY item of the batch carries `harness=PASS` and a `harness_report=` whose report is
a reusable PASS for the exact tree being shipped (the pretest worktree's HEAD tree, after rebase and index re-render).
Exit 1 and the reason otherwise; the caller then runs the pretest as before. Fail closed: a missing item, entry field,
report, unreadable file or any error means "run".

Whether a report covers the tree is decided by the harness's own `--reuse-report` logic (`lane_harness.load_reuse`:
report schema, exit code PASS, same tree id, same check manifest). On top of it the report must be `complete` (nothing
skipped by a flag such as `--integration skip`) and come from a profile that covers the pretest (`lane` or `pretest`).
A multi-item batch is a tree no single lane report was made for, so in practice it runs the pretest; only an item set
whose reports all name the shipped tree is reused.
"""
from __future__ import annotations

import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

HARNESS = Path(__file__).resolve().parents[1] / "lane_harness.py"
COVERING_PROFILES = ("lane", "pretest")


def load_harness(path: Path = HARNESS):
    spec = importlib.util.spec_from_file_location("lane_harness_for_pretest_reuse", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module   # dataclasses look their module up while the class is built
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module


def entry_fields(path: Path) -> dict:
    fields = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            fields[key.strip()] = value.strip()   # the last occurrence wins, as ops_lib.sh `field` does
    return fields


def decide(worktree: Path, items_dir: Path, harness=None) -> tuple:
    """(True, reason) when the pretest may be skipped, else (False, reason)."""
    harness = harness or load_harness()
    entries = sorted(items_dir.glob("*.ship")) if items_dir.is_dir() else []
    if not entries:
        return False, "no ship entries found in %s" % items_dir
    tree = subprocess.run(["git", "-C", str(worktree), "rev-parse", "HEAD^{tree}"], capture_output=True, text=True,
                          check=True).stdout.strip()
    manifest = worktree / "tools" / "delivery" / "checks.yaml"
    if not manifest.is_file():
        return False, "the shipped tree has no tools/delivery/checks.yaml"
    context = SimpleNamespace(tree=tree, manifest_sha=harness.sha256_file(manifest))
    names = []
    for entry in entries:
        fields = entry_fields(entry)
        name = fields.get("name") or entry.stem
        if fields.get("harness") != "PASS":
            return False, "%s has harness=%s" % (name, fields.get("harness") or "(none)")
        report_path = fields.get("harness_report", "")
        if not report_path or not Path(report_path).is_file():
            return False, "%s has no readable harness report" % name
        report = harness.load_reuse(report_path, context)
        if report is None:
            return False, "%s: report is not a PASS for the shipped tree %s (tree, manifest or verdict differ)" % (
                name, tree[:12])
        if report.get("complete") is not True:
            return False, "%s: report is not complete (a check was skipped by a flag)" % name
        if report.get("profile") not in COVERING_PROFILES:
            return False, "%s: report profile %s does not cover the pretest" % (name, report.get("profile"))
        names.append(name)
    return True, "tree %s covered by the PASS report(s) of %s" % (tree[:12], " ".join(names))


def main(argv: list | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--worktree", type=Path, required=True)
    parser.add_argument("--items", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        ok, reason = decide(args.worktree, args.items)
    except Exception as error:  # fail closed: any doubt runs the pretest
        ok, reason = False, "reuse check error: %s" % str(error)[:200]
    print(reason)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
