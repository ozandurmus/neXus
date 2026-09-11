"""GOV.ORCH.6 -- cold-start documentation diet word-count ceilings (AC-1) and
the single-canonical-taxonomy-table assertion (AC-4).

These pin the budget the diet achieved so the cold-start surface cannot
silently regrow (`docs/design/GOV_ORCH_6_DOCUMENTATION_DIET.md`).
"""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# AC-1: word-count ceilings, measured with `wc -w` semantics (len(text.split())).
_CEILINGS = {
    # GOV.ORCH.7: +54 words for the new "## Role dispatch" section
    # (AGENTS.md "## Role dispatch"), net of a small vendor-name edit in
    # the opening paragraph -- raised by exactly the words added, per the
    # movement's own instruction.
    "AGENTS.md": 3404,
    "AI_START_HERE.md": 2250,
    "CURRENT_STATE.md": 1050,
    "AI_HANDOVER.md": 300,
    "CLAUDE.md": 500,
}


def _word_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8").split())


def test_cold_start_word_count_ceilings():
    over = {}
    for name, ceiling in _CEILINGS.items():
        count = _word_count(ROOT / name)
        if count > ceiling:
            over[name] = (count, ceiling)
    assert not over, f"cold-start files over their word-count ceiling: {over}"


# AC-4: the action taxonomy table exists exactly once outside docs/history/**.

_CANONICAL_TABLE_HEADER = "| Class | What | Status |"


def _repo_md_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        rel_dir = Path(dirpath).relative_to(ROOT)
        if ".git" in rel_dir.parts or "node_modules" in rel_dir.parts:
            dirnames[:] = []
            continue
        if rel_dir.parts[:2] == ("docs", "history"):
            dirnames[:] = []
            continue
        for name in filenames:
            if name.endswith(".md"):
                yield Path(dirpath) / name


def test_action_taxonomy_table_exists_exactly_once_outside_history():
    hits = [
        path
        for path in _repo_md_files()
        if _CANONICAL_TABLE_HEADER in path.read_text(encoding="utf-8", errors="ignore")
    ]
    assert len(hits) == 1, f"expected exactly one canonical taxonomy table, found: {hits}"
    assert hits[0].name == "AI_START_HERE.md"
