#!/usr/bin/env python3
"""GOV.ORCH.7 section 2.3 item 3 -- PO write-scope enforcement.

Given a branch and a base, exits 1 if a `gov/po-*` branch touches a path
outside the governance set in `docs/design/GOV_PO_ROLE_MIGRATION.md` section
4 -- read from `config/po_write_scope.json` so the rule has one owner, never
duplicated between this script, the pre-push hook, and CI. A branch not
matching the configured prefix is out of scope for this check and always
exits 0 (this script enforces the PO's own lane, not every branch's).

Usage:
    python3 scripts/nexus_po_scope_check.py --branch <name> --base <ref>
    python3 scripts/nexus_po_scope_check.py --branch <name> --base <ref> --config <path>

Wired as: a CI job in `.github/workflows/validation.yml` for `gov/po-*`
branches, and a pre-push check inside `scripts/nexus_worker_prepush.py` when
the current branch matches the configured prefix. The
`.claude/nexus-po.settings.json` deny list stays for Claude sessions; it is
no longer the only gate (defense in depth, not a replacement).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG_PATH = REPO_ROOT / "config" / "po_write_scope.json"


class ScopeCheckError(Exception):
    """A usage-level failure (bad config, git error) distinct from a scope
    violation, which is a normal exit-1 result, not an exception."""


def load_scope_config(config_path: Path = DEFAULT_CONFIG_PATH) -> dict:
    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ScopeCheckError(f"cannot read {config_path}: {exc}") from exc
    if "branch_prefix" not in data or "allowed_paths" not in data:
        raise ScopeCheckError(f"{config_path} missing 'branch_prefix' or 'allowed_paths'")
    return data


def changed_files(branch: str, base: str, cwd: Path = REPO_ROOT) -> list[str]:
    """Files changed on `branch` relative to its merge-base with `base`."""
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{branch}"],
        cwd=str(cwd), capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise ScopeCheckError(f"git diff failed: {result.stderr.strip()}")
    return [line for line in result.stdout.splitlines() if line]


def check_scope(*, branch: str, files: list[str], config: dict) -> tuple[bool, str]:
    """Pure decision: True/"ok" iff `branch` is out of this check's scope
    (does not match `branch_prefix`) or every changed file is in
    `allowed_paths`. Never inspects git itself -- `files` is passed in."""
    prefix = config["branch_prefix"]
    if not branch.startswith(prefix):
        return True, f"branch {branch!r} does not match {prefix!r}; not a PO branch, skipped"
    allowed = set(config["allowed_paths"])
    out_of_scope = [f for f in files if f not in allowed]
    if out_of_scope:
        return False, (
            f"branch {branch!r} touches paths outside the governance write "
            f"scope in {DEFAULT_CONFIG_PATH.name}: {out_of_scope}"
        )
    return True, "ok"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--branch", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--config", default=str(DEFAULT_CONFIG_PATH))
    args = parser.parse_args(argv)

    try:
        config = load_scope_config(Path(args.config))
        files = changed_files(args.branch, args.base, cwd=Path.cwd())
    except ScopeCheckError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    ok, reason = check_scope(branch=args.branch, files=files, config=config)
    print(reason, file=sys.stderr if not ok else sys.stdout)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
