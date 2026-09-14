from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import dispatch_ledger as ledger  # noqa: E402
import orchestrator as orch  # noqa: E402
import orchestrator_usage as usage  # noqa: E402


def _record(movement, provider="claude", **extra):
    return {"movement_id": movement, "provider": provider, "model_requested": "model", "effort_requested": "medium",
            "started_at": "2026-09-14T10:00:00Z", "phase": "done", "revision": 1, "retry_count": 0, **extra}


def _render(tmp_path, records, caches=(), existing=""):
    state = tmp_path / "state"
    for record in records:
        orch._save_state(state, record["movement_id"], record)
    for movement, cache in caches:
        usage.save_usage_cache(state, movement, {**usage._empty_cache(), **cache})
    return ledger.render(state, tmp_path / "relay", existing)


def test_joins_claude_and_codex_records(tmp_path):
    text = _render(tmp_path, [_record("NXS-LOCAL-1"), _record("NXS-LOCAL-2", "codex")], [
        ("NXS-LOCAL-1", {"turns": 2, "input_tokens": 10, "result_cost_usd": 1.25, "last_event_at": "2026-09-14T10:10:00Z"}),
        ("NXS-LOCAL-2", {"turns": 3, "input_tokens": 20, "last_event_at": "2026-09-14T10:20:00Z"}),
    ])
    assert "| NXS-LOCAL-1 | 1 |" in text and "| NXS-LOCAL-2 | 1 |" in text
    assert "Metered total (claude): $1.2500" in text
    assert "Subscription total (no providers): $0.0000" in text


def test_missing_usage_and_negative_duration_are_unknown(tmp_path):
    text = _render(tmp_path, [_record("NXS-LOCAL-3"), _record("NXS-LOCAL-4")], [
        ("NXS-LOCAL-4", {"last_event_at": "2026-09-14T09:00:00Z"}),
    ])
    assert "| NXS-LOCAL-3 | 1 |" in text and text.count("unknown") >= 8


def test_assessment_survives_rerender_and_totals_stay_separate(tmp_path):
    text = _render(tmp_path, [_record("NXS-LOCAL-5")])
    edited = text.replace("| NXS-LOCAL-5 | 1 |", "| NXS-LOCAL-5 | 1 |", 1).replace(" | - |  |", " | - | useful run |", 1)
    rerendered = _render(tmp_path, [_record("NXS-LOCAL-5")], existing=edited)
    assert "useful run" in rerendered
    assert "Metered total" in rerendered and "Subscription total" in rerendered


def test_check_detects_a_stale_ledger(tmp_path):
    output = tmp_path / "ledger.md"
    assert ledger.main(["render", "--check", "--state-dir", str(tmp_path / "state"), "--output", str(output)]) == 1
    assert ledger.main(["render", "--state-dir", str(tmp_path / "state"), "--output", str(output)]) == 0
    assert ledger.main(["render", "--check", "--state-dir", str(tmp_path / "state"), "--output", str(output)]) == 0
