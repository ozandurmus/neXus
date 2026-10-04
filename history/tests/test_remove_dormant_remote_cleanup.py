"""Regression: the unreferenced remote rm -f helper stays removed.

utils/cleanup.py issued unaudited `rm -f` commands over SSH using the
collection credential, outside the network-device command gate
(docs/AI_DEVELOPMENT_PROTOCOL.md) and the read-only product posture, even
though nothing imported it. Removed per project/backlog.json
`remove_dormant_remote_cleanup`; this guards against it (or an equivalent
helper) coming back silently.
"""

from __future__ import annotations

from pathlib import Path

import pytest

pytestmark = pytest.mark.security

REPO_ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", "__pycache__", ".pytest_cache", "data", "output", "logs"}


def test_cleanup_module_does_not_exist():
    assert not (REPO_ROOT / "utils" / "cleanup.py").exists()


def test_no_tracked_source_references_cleanup_all():
    this_file = Path(__file__).resolve()
    hits = []
    for path in REPO_ROOT.rglob("*.py"):
        if path == this_file or any(part in SKIP_DIRS for part in path.parts):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "cleanup_all" in text:
            hits.append(str(path.relative_to(REPO_ROOT)))
    assert hits == [], f"dormant remote-cleanup helper referenced again: {hits}"
