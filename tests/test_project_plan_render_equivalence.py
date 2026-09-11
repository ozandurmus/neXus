"""GOV.ORCH.8 2.5 / AC-2: the project-plan payload's counts, percentages,
warnings and build id set must be identical before and after the data
split -- a moved narrative is replaced by its first sentence plus a
`detail` pointer, never by a change to a count, percentage or id.

`tests/fixtures/project_plan_payload_baseline.json` was captured by dumping
`utils.project_plan.build_project_plan_payload()` (narrative fields
stripped) before the GOV.ORCH.8 data move. This test recomputes the same
projection from the live (post-move) payload and asserts it still matches,
except `archived_build_count` / the current-vs-archived split of the build
id set, which the archive step (2.2) is expected to change deliberately --
the *union* of current + archived ids must still equal the original set.
"""
from __future__ import annotations

import json
from pathlib import Path

from utils.project_plan import build_project_plan_payload, _load_archived_builds

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "tests" / "fixtures" / "project_plan_payload_baseline.json"


def _strip(payload: dict) -> dict:
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


def test_fixture_exists():
    assert FIXTURE.exists(), "run the payload-baseline dump before the data move"


def test_live_payload_matches_baseline_except_archive_split():
    baseline = json.loads(FIXTURE.read_text(encoding="utf-8"))
    live = _strip(build_project_plan_payload())

    # Everything that must never change under a data-only (state/narrative)
    # split: progress math, warnings, backlog, completed features.
    for key in (
        "overall_progress_percent", "current_track_progress_percent",
        "current_build", "current_track", "metadata_warnings",
        "backlog_counts", "track_progress", "completed_feature_ids",
        "backlog_ids",
    ):
        assert live[key] == baseline[key], key

    # The build id SET is preserved across current + archive; only the
    # current/archived split is allowed to move (that is the point of 2.2).
    archived_ids = sorted(str(b.get("build")) for b in _load_archived_builds())
    assert sorted(live["build_ids"] + archived_ids) == baseline["build_ids"]
    assert live["build_count"] + len(archived_ids) == baseline["build_count"]
    assert live["archived_build_count"] == len(archived_ids)
