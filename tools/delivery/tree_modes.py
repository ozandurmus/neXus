#!/usr/bin/env python3
"""Fail when a tracked file or directory in the deploy tree is not world-readable.

The image builds stream the tree into the build context and COPY keeps file modes, so a tracked file without the
world-read bit becomes a file the image's runtime user cannot read (e2e EACCES on 2026-11-13). The tree's top
directory stays closed (2750); this check covers what is inside it. Symbolic links are skipped. Read-only.
"""
import argparse
import os
import stat
import subprocess
import sys
from pathlib import Path

READ = stat.S_IROTH
TRAVERSE = stat.S_IROTH | stat.S_IXOTH


def unreadable(repo):
    """Tracked paths (relative, directories with a trailing slash) lacking world read (and traverse for dirs)."""
    listed = subprocess.run(["git", "-C", str(repo), "ls-files", "-z"], check=True, capture_output=True).stdout
    found, seen = [], set()
    for raw in listed.split(b"\0"):
        if not raw:
            continue
        relative = Path(os.fsdecode(raw))
        try:
            mode = (repo / relative).lstat().st_mode
        except FileNotFoundError:
            continue
        if not stat.S_ISLNK(mode) and not mode & READ:
            found.append(str(relative))
        parent = relative.parent
        while parent != Path(".") and parent not in seen:
            seen.add(parent)
            if (repo / parent).stat().st_mode & TRAVERSE != TRAVERSE:
                found.append(str(parent) + "/")
            parent = parent.parent
    return sorted(found)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=("check",))
    parser.add_argument("--repo", type=Path, required=True)
    args = parser.parse_args(argv)
    found = unreadable(args.repo)
    if found:
        print(f"STOP: {len(found)} tracked paths are not world-readable, first: {', '.join(found[:5])}", file=sys.stderr)
        print("fix: sudo chmod -R o+rX <tree> && sudo chmod o-rwx <tree>", file=sys.stderr)
        return 7
    return 0


if __name__ == "__main__":
    sys.exit(main())
