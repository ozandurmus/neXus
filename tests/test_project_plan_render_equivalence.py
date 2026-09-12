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


def test_the_data_split_lost_nothing():
    """The permanent invariant: the GOV.ORCH.8 move relocated rows, and no row
    it relocated may ever go missing.

    Corrected 2026-09-12. This test previously also asserted equality on
    `current_build`, `backlog_counts`, the progress percentages, the track
    progress map, `completed_feature_ids` and `backlog_ids`. That asserted
    that **project state never changes**, which contradicts the repository's
    own design: `scripts/project_queue.py` exists to change exactly those
    values, and `AGENTS.md`'s project-state update rule requires a build that
    changes scope or delivery state to update them. So the test failed on the
    first legitimate state update after the baseline was captured -- not
    because anything was lost, but because the world moved. A gate that trips
    on normal operation stops being read.

    What survives is the invariant the fixture was captured for. The baseline
    was dumped *before* the move; every build id it recorded must still be
    reachable in the live history or the archive, the archive count must
    account for itself, and no id may vanish in the gap between them. New ids
    may appear -- that is a build being recorded, not a loss.

    Regenerating the fixture from the live payload was the other option and is
    rejected deliberately: the fixture's entire value is that it predates the
    move, and a regenerated baseline would compare the payload to itself.
    """
    baseline = json.loads(FIXTURE.read_text(encoding="utf-8"))
    live = _strip(build_project_plan_payload())
    archived_ids = sorted(str(b.get("build")) for b in _load_archived_builds())

    baseline_ids = set(baseline["build_ids"])
    assert baseline_ids, "the baseline recorded no build ids -- this test would pass vacuously"

    reachable = set(live["build_ids"]) | set(archived_ids)
    lost = sorted(baseline_ids - reachable)
    assert lost == [], (
        "build ids recorded before the GOV.ORCH.8 data move are no longer "
        f"reachable in either the live history or the archive: {lost}"
    )

    # The archive must account for itself, and the split must add up.
    assert live["archived_build_count"] == len(archived_ids)
    assert live["build_count"] + len(archived_ids) >= baseline["build_count"], (
        "the current plus archived count fell below the pre-move count"
    )

    # Ids only ever accrue: a backlog row or a completed feature recorded
    # before the move must not disappear either.
    for key in ("backlog_ids", "completed_feature_ids"):
        missing = sorted(set(baseline[key]) - set(live[key]))
        assert missing == [], f"{key} recorded before the move went missing: {missing}"
