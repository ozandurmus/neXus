"""GOV.ORCH.4 section 3.1 -- tests for scripts/orchestrator_usage.py
(docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator_usage as ou  # noqa: E402


def _write_lines(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# AC-1: Claude fixture -- dedup by message id, result totals preferred
# ---------------------------------------------------------------------------

CLAUDE_FIXTURE = [
    json.dumps({"type": "system", "subtype": "init", "model": "claude-sonnet-5", "session_id": "s1"}),
    json.dumps({"type": "assistant", "message": {"id": "msg_1", "usage": {
        "input_tokens": 100, "cache_creation_input_tokens": 40, "cache_read_input_tokens": 600, "output_tokens": 20,
    }}}),
    # Duplicate message id (a streaming re-emit) -- must be counted once.
    json.dumps({"type": "assistant", "message": {"id": "msg_1", "usage": {
        "input_tokens": 100, "cache_creation_input_tokens": 40, "cache_read_input_tokens": 600, "output_tokens": 20,
    }}}),
    json.dumps({"type": "assistant", "message": {"id": "msg_2", "usage": {
        "input_tokens": 50, "cache_creation_input_tokens": 10, "cache_read_input_tokens": 300, "output_tokens": 15,
    }}}),
    json.dumps({"type": "result", "usage": {
        "input_tokens": 150, "cache_creation_input_tokens": 50, "cache_read_input_tokens": 900, "output_tokens": 35,
    }, "total_cost_usd": 1.23}),
]


def test_claude_fixture_exact_totals_result_preferred_and_cache_ratio(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    _write_lines(log_path, CLAUDE_FIXTURE)

    usage = ou.update_usage(state_dir, "NXS-LOCAL-0001", log_path, "claude")

    assert usage["provider"] == "claude"
    assert usage["turns"] == 2  # msg_1 counted once, msg_2 once
    # Result totals are preferred over the per-turn sums (AC-1).
    assert usage["input_tokens"] == 150
    assert usage["cache_creation_input_tokens"] == 50
    assert usage["cache_read_input_tokens"] == 900
    assert usage["output_tokens"] == 35
    assert usage["total_tokens"] == 150 + 50 + 900 + 35
    assert usage["uncached_input_tokens"] == 150
    assert usage["cost_usd"] == 1.23
    assert usage["cost_source"] == "reported"
    assert usage["model"] == "claude-sonnet-5"
    denom = 150 + 50 + 900
    assert usage["cache_hit_ratio"] == round(900 / denom, 4)


# ---------------------------------------------------------------------------
# AC-2: Codex fixture -- summed totals, cached_input_tokens mapped, unavailable
# ---------------------------------------------------------------------------

CODEX_FIXTURE = [
    json.dumps({"type": "thread.started", "thread_id": "t1"}),
    json.dumps({"type": "turn.completed", "usage": {
        "input_tokens": 200, "cached_input_tokens": 50, "output_tokens": 30,
    }}),
    json.dumps({"type": "turn.completed", "usage": {
        "input_tokens": 100, "cached_input_tokens": 10, "output_tokens": 15,
    }}),
]


def test_codex_fixture_summed_totals_and_unavailable_cost(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    _write_lines(log_path, CODEX_FIXTURE)

    usage = ou.update_usage(state_dir, "NXS-LOCAL-0002", log_path, "codex")

    assert usage["provider"] == "codex"
    assert usage["turns"] == 2
    assert usage["input_tokens"] == 300
    assert usage["cache_creation_input_tokens"] == 0
    assert usage["cache_read_input_tokens"] == 60  # cached_input_tokens -> cache_read_input_tokens
    assert usage["output_tokens"] == 45
    assert usage["cost_source"] == "unavailable"
    assert usage["cost_usd"] is None


# ---------------------------------------------------------------------------
# AC-3: incremental parse -- only new events counted; a truncated last line
# is skipped and picked up once completed.
# ---------------------------------------------------------------------------

def test_incremental_parse_adds_only_new_events(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    _write_lines(log_path, [CODEX_FIXTURE[0], CODEX_FIXTURE[1]])

    first = ou.update_usage(state_dir, "NXS-LOCAL-0003", log_path, "codex")
    assert first["turns"] == 1
    assert first["input_tokens"] == 200

    with open(log_path, "a", encoding="utf-8") as fh:
        fh.write(CODEX_FIXTURE[2] + "\n")

    second = ou.update_usage(state_dir, "NXS-LOCAL-0003", log_path, "codex")
    assert second["turns"] == 2
    assert second["input_tokens"] == 300


def test_truncated_last_line_is_skipped_and_picked_up_once_completed(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    log_path.parent.mkdir(parents=True)
    complete_event = json.dumps({"type": "turn.completed", "usage": {"input_tokens": 10, "output_tokens": 1}})
    full_event = json.dumps({"type": "turn.completed", "usage": {"input_tokens": 999, "output_tokens": 1}})
    # A truncated write: the second line has no trailing newline yet and is
    # not the complete JSON object it will eventually be.
    log_path.write_text(complete_event + "\n" + full_event[: len(full_event) // 2], encoding="utf-8")

    usage = ou.update_usage(state_dir, "NXS-LOCAL-0004", log_path, "codex")
    assert usage["turns"] == 1
    assert usage["input_tokens"] == 10

    # The write completes.
    log_path.write_text(complete_event + "\n" + full_event + "\n", encoding="utf-8")
    usage2 = ou.update_usage(state_dir, "NXS-LOCAL-0004", log_path, "codex")
    assert usage2["turns"] == 2
    assert usage2["input_tokens"] == 10 + 999


def test_missing_log_never_raises(tmp_path):
    state_dir = tmp_path / "state"
    usage = ou.update_usage(state_dir, "NXS-LOCAL-0005", tmp_path / "no-such-log", "claude")
    assert usage["total_tokens"] == 0
    assert usage["cost_source"] == "unavailable"


def test_compute_usage_without_a_worktree_returns_cached_totals(tmp_path):
    state_dir = tmp_path / "state"
    usage = ou.compute_usage(state_dir, "NXS-LOCAL-0006", None, "claude")
    assert usage["total_tokens"] == 0
    assert usage["turns"] == 0


# ---------------------------------------------------------------------------
# AC-4: price table -- estimated cost formula, unavailable when absent
# ---------------------------------------------------------------------------

def test_price_table_estimated_cost_matches_the_formula(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    fixture = [
        json.dumps({"type": "system", "subtype": "init", "model": "priced-model"}),
        json.dumps({"type": "assistant", "message": {"id": "m1", "usage": {
            "input_tokens": 1_000_000, "cache_creation_input_tokens": 1_000_000,
            "cache_read_input_tokens": 1_000_000, "output_tokens": 1_000_000,
        }}}),
    ]
    _write_lines(log_path, fixture)
    price_table = {"priced-model": {
        "input_per_mtok": 1.0, "cache_write_per_mtok": 2.0, "cache_read_per_mtok": 0.5, "output_per_mtok": 4.0,
    }}
    usage = ou.update_usage(state_dir, "NXS-LOCAL-0007", log_path, "claude", price_table)
    assert usage["cost_source"] == "estimated"
    assert usage["cost_usd"] == round(1.0 + 2.0 + 0.5 + 4.0, 6)


def test_price_table_absent_entry_is_unavailable(tmp_path):
    state_dir = tmp_path / "state"
    log_path = tmp_path / "wt" / ".nexus" / "engineer.log"
    fixture = [
        json.dumps({"type": "system", "subtype": "init", "model": "unpriced-model"}),
        json.dumps({"type": "assistant", "message": {"id": "m1", "usage": {"input_tokens": 10, "output_tokens": 1}}}),
    ]
    _write_lines(log_path, fixture)
    usage = ou.update_usage(state_dir, "NXS-LOCAL-0008", log_path, "claude", {})
    assert usage["cost_source"] == "unavailable"
    assert usage["cost_usd"] is None


def test_load_price_table_missing_file_is_empty(tmp_path):
    assert ou.load_price_table(tmp_path / "no-such-file.json") == {}


def test_load_price_table_drops_the_comment_key(tmp_path):
    path = tmp_path / "model_prices.json"
    path.write_text(json.dumps({"_comment": "x", "m": {"input_per_mtok": 1}}), encoding="utf-8")
    table = ou.load_price_table(path)
    assert "_comment" not in table
    assert table["m"]["input_per_mtok"] == 1


def test_load_price_table_committed_file_ships_empty(tmp_path):
    # config/model_prices.json in the real repository -- ships with only a
    # _comment key (section 3.2), so this must resolve to an empty table.
    assert ou.load_price_table(ROOT / "config" / "model_prices.json") == {}
