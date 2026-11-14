"""GOV.ORCH.9: the backlog active-set/terminal-reserve split must retain IDs
without overlap.

`tools/tests/fixtures/backlog_pre_split_items_gov_orch_9.json` is the full
project/backlog.json content (154 items) captured immediately before the
split ran. Row and project-plan-payload equality were point-in-time migration
checks, not live invariants; IDs must instead remain in exactly one file.
"""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "project"
BACKLOG_BASELINE = ROOT / "tools" / "tests" / "fixtures" / "backlog_pre_split_items_gov_orch_9.json"


def _terminal_files() -> list[Path]:
    """The hot reserve plus its frozen cold shards (amendment A-2026-11-14)."""
    archive = PROJECT / "archive"
    return sorted(archive.glob("backlog_terminal_*.json")) + [archive / "backlog_terminal.json"]


def _terminal_items() -> list[dict]:
    items: list[dict] = []
    for path in _terminal_files():
        items.extend(json.loads(path.read_text(encoding="utf-8"))["items"])
    return items


def test_backlog_baseline_fixture_exists():
    assert BACKLOG_BASELINE.exists(), "run migrate-backlog-terminal after capturing this baseline"


def test_pre_split_ids_remain_in_active_or_terminal_backlog():
    """Every ID present at the split remains in the active/terminal union."""
    baseline_items = json.loads(BACKLOG_BASELINE.read_text(encoding="utf-8"))["items"]
    active_items = json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))["items"]
    terminal_items = _terminal_items()

    baseline_ids = {item["id"] for item in baseline_items}
    assert baseline_ids, "the baseline recorded no items -- this test would pass vacuously"
    union_ids = {item["id"] for item in active_items + terminal_items}
    missing = sorted(baseline_ids - union_ids)
    assert missing == [], f"items present before the split are missing from the union: {missing}"


def test_active_and_terminal_backlog_ids_do_not_overlap():
    """An ID belongs to either the active backlog or terminal archive, never both."""
    active_ids = {item["id"] for item in json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))["items"]}
    terminal_ids: set[str] = set()
    for path in _terminal_files():
        ids = {item["id"] for item in json.loads(path.read_text(encoding="utf-8"))["items"]}
        assert terminal_ids.isdisjoint(ids), (path.name, sorted(terminal_ids & ids))
        terminal_ids |= ids
    assert active_ids.isdisjoint(terminal_ids), sorted(active_ids & terminal_ids)
