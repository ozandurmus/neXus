"""GOV.ORCH.9: the backlog active-set/terminal-reserve split must retain IDs
without overlap.

`tests/fixtures/backlog_pre_split_items_gov_orch_9.json` is the full
project/backlog.json content (154 items) captured immediately before the
split ran. Row and project-plan-payload equality were point-in-time migration
checks, not live invariants; IDs must instead remain in exactly one file.
"""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "project"
BACKLOG_BASELINE = ROOT / "tests" / "fixtures" / "backlog_pre_split_items_gov_orch_9.json"


def test_backlog_baseline_fixture_exists():
    assert BACKLOG_BASELINE.exists(), "run migrate-backlog-terminal after capturing this baseline"


def test_pre_split_ids_remain_in_active_or_terminal_backlog():
    """Every ID present at the split remains in the active/terminal union."""
    baseline_items = json.loads(BACKLOG_BASELINE.read_text(encoding="utf-8"))["items"]
    active_items = json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))["items"]
    terminal_items = json.loads(
        (PROJECT / "archive" / "backlog_terminal.json").read_text(encoding="utf-8")
    )["items"]

    baseline_ids = {item["id"] for item in baseline_items}
    assert baseline_ids, "the baseline recorded no items -- this test would pass vacuously"
    union_ids = {item["id"] for item in active_items + terminal_items}
    missing = sorted(baseline_ids - union_ids)
    assert missing == [], f"items present before the split are missing from the union: {missing}"


def test_active_and_terminal_backlog_ids_do_not_overlap():
    """An ID belongs to either the active backlog or terminal archive, never both."""
    active_ids = {item["id"] for item in json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))["items"]}
    terminal_ids = {item["id"] for item in json.loads((PROJECT / "archive" / "backlog_terminal.json").read_text(encoding="utf-8"))["items"]}
    assert active_ids.isdisjoint(terminal_ids), sorted(active_ids & terminal_ids)
