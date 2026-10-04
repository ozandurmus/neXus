"""GOV.ORCH.6 -- cold-start documentation diet word-count ceilings (AC-1) and
the Java taxonomy authority assertion (PR0, PO decision 2026-10-04).

These pin the budget the diet achieved so the cold-start surface cannot
silently regrow (`docs/design/GOV_ORCH_6_DOCUMENTATION_DIET.md`).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# AC-1: word-count ceilings, measured with `wc -w` semantics (len(text.split())).
_CEILINGS = {
    # GOV.ORCH.7: +54 words for the new "## Role dispatch" section
    # (AGENTS.md "## Role dispatch"), net of a small vendor-name edit in
    # the opening paragraph -- raised by exactly the words added, per the
    # movement's own instruction.
    # PR #359: +124 words for the new "## Host action boundary" section.
    "AGENTS.md": 3528,
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


# PR0 supersedes the Python prose table: Java is the live authority.
def test_java_taxonomy_authority_is_explicit():
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    entry = (ROOT / "AI_START_HERE.md").read_text(encoding="utf-8")
    for name in ("ActionClass.java", "ActionRegistry.java", "gate_registry_fixture.yaml"):
        assert name in agents
    assert "Live command and failover authority is Java" in entry
    assert "| Class | What | Status |" not in entry
