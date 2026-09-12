"""GOV.ORCH.9 acceptance items 3 and 4: the backlog active-set/terminal-
reserve split must lose nothing and change no count.

`tests/fixtures/backlog_pre_split_items_gov_orch_9.json` is the full
project/backlog.json content (154 items) captured immediately before the
split ran. `tests/fixtures/project_plan_payload_baseline_gov_orch_9.json` is
the stripped build_project_plan_payload() projection captured at the same
moment (the same shape tests/test_project_plan_render_equivalence.py uses
for the GOV.ORCH.8 build-history archive).
"""
from __future__ import annotations

import json
from pathlib import Path

from utils.project_plan import build_project_plan_payload

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "project"
BACKLOG_BASELINE = ROOT / "tests" / "fixtures" / "backlog_pre_split_items_gov_orch_9.json"
PAYLOAD_BASELINE = ROOT / "tests" / "fixtures" / "project_plan_payload_baseline_gov_orch_9.json"


def _strip_payload(payload: dict) -> dict:
    return {
        "overall_progress_percent": payload["overall_progress_percent"],
        "current_track_progress_percent": payload["current_track_progress_percent"],
        "current_build": payload["current_build"],
        "current_track": payload["current_track"],
        "metadata_warnings": sorted(payload["metadata_warnings"]),
        "backlog_counts": payload["backlog_counts"],
        "build_ids": sorted(str(b.get("build")) for b in payload["build_history"]),
        "build_count": len(payload["build_history"]),
        "archived_build_count": payload.get("archived_build_count", 0),
        "track_progress": {t["id"]: t["progress_percent"] for t in payload["tracks"]},
        "completed_feature_ids": sorted(f.get("id") for f in payload["completed_features"]),
        "backlog_ids": sorted(i.get("id") for i in payload["backlog"]),
    }


def test_backlog_baseline_fixture_exists():
    assert BACKLOG_BASELINE.exists(), "run migrate-backlog-terminal after capturing this baseline"


def test_active_set_and_reserve_union_matches_pre_split_set_id_for_id_and_row_for_row():
    """GOV.ORCH.9 acceptance item 3: the union of the two files equals the
    original 154-item set, id for id and row for row -- not by count alone."""
    baseline_items = json.loads(BACKLOG_BASELINE.read_text(encoding="utf-8"))["items"]
    active_items = json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))["items"]
    terminal_items = json.loads(
        (PROJECT / "archive" / "backlog_terminal.json").read_text(encoding="utf-8")
    )["items"]

    baseline_by_id = {item["id"]: item for item in baseline_items}
    assert baseline_by_id, "the baseline recorded no items -- this test would pass vacuously"

    union_by_id: dict[str, dict] = {}
    for item in active_items + terminal_items:
        item_id = item["id"]
        assert item_id not in union_by_id, f"duplicate id across the two files: {item_id!r}"
        union_by_id[item_id] = item

    missing = sorted(set(baseline_by_id) - set(union_by_id))
    assert missing == [], f"items present before the split are missing from the union: {missing}"

    mismatched = sorted(
        item_id for item_id, row in baseline_by_id.items() if union_by_id[item_id] != row
    )
    assert mismatched == [], f"row content changed for id(s) not touched by this movement: {mismatched}"


def test_payload_is_identical_before_and_after_the_split():
    """GOV.ORCH.9 acceptance item 4 / GOV.ORCH.8 2.5's render-equivalence
    rule: every count, percentage, warning list and id set the payload
    produces must match the pre-split baseline exactly."""
    baseline = json.loads(PAYLOAD_BASELINE.read_text(encoding="utf-8"))
    live = _strip_payload(build_project_plan_payload())
    assert live == baseline
