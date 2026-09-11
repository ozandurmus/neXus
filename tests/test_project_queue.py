"""GOV.ORCH.5 -- scripts/project_queue.py.

docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md AC-1..AC-4.
All read/write exercises run against fixture copies under tmp_path; the one
exception (a read-only pure-function render) runs on the real repository
files and writes nothing.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import project_queue as pq  # noqa: E402


def _backlog_fixture() -> dict:
    return {
        "schema_version": "1.0",
        "items": [
            {
                "id": "item_open_p1",
                "category": "Cat A",
                "title": "An open P1 item in progress",
                "status": "in_progress",
                "priority": "P1",
                "target": "target A",
                "note": "some in-flight note, stays in JSON while open",
            },
            {
                "id": "item_open_p0",
                "category": "Cat B",
                "title": "An open P0 item, planned",
                "status": "planned",
                "priority": "P0 -- extra text",
                "target": "target B",
                "note": "",
            },
            {
                "id": "item_done",
                "category": "Cat C",
                "title": "A done item",
                "status": "done",
                "priority": "P2",
                "target": "target C",
                "note": "DONE narrative that must move verbatim to history.",
            },
            {
                "id": "item_deferred",
                "category": "Cat D",
                "title": "A deferred item",
                "status": "deferred",
                "priority": "P3",
                "target": "target D",
                "note": "DEFERRED narrative.",
            },
        ],
    }


def _roadmap_fixture() -> dict:
    return {
        "schema_version": "1.0",
        "current_build": "build_x",
        "current_track": "0.1.x",
        "now_next": {
            "now": {"build": "build_x", "title": "Now title", "status": "in_progress"},
            "next": {"build": "build_y", "title": "Next title", "status": "planned"},
        },
        "open_decisions": [
            {"id": "dec_open", "status": "open", "question": "An open question?"},
            {"id": "dec_decided", "status": "decided", "question": "Already decided?",
             "decision": "DECIDED already."},
        ],
    }


@pytest.fixture
def queue_env(tmp_path, monkeypatch):
    project_dir = tmp_path / "project"
    project_dir.mkdir()
    backlog_path = project_dir / "backlog.json"
    roadmap_path = project_dir / "roadmap.json"
    queue_path = project_dir / "QUEUE.md"
    history_dir = tmp_path / "docs" / "history" / "backlog"

    backlog_path.write_text(pq.canonical_dump(_backlog_fixture()), encoding="utf-8")
    roadmap_path.write_text(pq.canonical_dump(_roadmap_fixture()), encoding="utf-8")

    monkeypatch.setattr(pq, "BACKLOG", backlog_path)
    monkeypatch.setattr(pq, "ROADMAP", roadmap_path)
    monkeypatch.setattr(pq, "QUEUE", queue_path)
    monkeypatch.setattr(pq, "HISTORY_BACKLOG_DIR", history_dir)

    return {
        "backlog": backlog_path,
        "roadmap": roadmap_path,
        "queue": queue_path,
        "history_dir": history_dir,
    }


# --- AC-1: render ------------------------------------------------------

def test_render_produces_four_sections_open_items_only(queue_env):
    rc = pq.main(["render"])
    assert rc == 0
    content = queue_env["queue"].read_text(encoding="utf-8")

    for heading in ("## Now", "## Next", "## Open backlog", "## Open decisions"):
        assert heading in content

    # Only open (in_progress/planned) backlog items appear.
    assert "item_open_p1" in content
    assert "item_open_p0" in content
    assert "item_done" not in content
    assert "item_deferred" not in content

    # Only open decisions appear.
    assert "dec_open" in content
    assert "dec_decided" not in content
    assert "Already decided" not in content

    # No note text anywhere.
    assert "stays in JSON while open" not in content
    assert "DONE narrative" not in content

    assert len(content.split()) < 1500


def test_render_open_backlog_sorted_priority_then_status(queue_env):
    pq.main(["render"])
    content = queue_env["queue"].read_text(encoding="utf-8")
    lines = [l for l in content.splitlines() if l.startswith("- item_open") or
             (l.startswith("- P") and "item_open" in l)]
    idx_p0 = next(i for i, l in enumerate(lines) if "item_open_p0" in l)
    idx_p1 = next(i for i, l in enumerate(lines) if "item_open_p1" in l)
    assert idx_p0 < idx_p1  # P0 sorts before P1


def test_render_on_real_repository_is_read_only_and_under_budget():
    """The one exercise against the real files: a pure render, nothing
    written. Also proves the repaired backlog.json loads and the real
    QUEUE.md content stays within the word budget."""
    backlog = pq.load_json(pq.BACKLOG)
    roadmap = pq.load_json(pq.ROADMAP)
    pq.validate_backlog(backlog)
    content = pq.render_queue_md(backlog, roadmap, generated="1970-01-01T00:00:00Z")
    assert content.startswith("# Project queue")
    assert len(content.split()) < 1500
    for heading in ("## Now", "## Next", "## Open backlog", "## Open decisions"):
        assert heading in content


# --- AC-2: check ---------------------------------------------------------

def test_check_exits_0_after_render(queue_env):
    pq.main(["render"])
    assert pq.main(["check"]) == 0


def test_check_exits_1_after_manual_edit(queue_env, capsys):
    pq.main(["render"])
    queue_env["queue"].write_text(
        queue_env["queue"].read_text(encoding="utf-8") + "\nmanually added line\n",
        encoding="utf-8",
    )
    assert pq.main(["check"]) == 1


def test_check_exits_1_when_backlog_does_not_load(queue_env, capsys):
    pq.main(["render"])
    raw = queue_env["backlog"].read_text(encoding="utf-8")
    broken = raw.replace('"items"', '"items"', 1)[:-5]  # truncate -> malformed JSON
    queue_env["backlog"].write_text(broken, encoding="utf-8")
    rc = pq.main(["check"])
    assert rc == 1
    out = capsys.readouterr().out
    assert "line" in out and "column" in out


# --- AC-3: add / status / note / decide round-trip -----------------------

def test_add_round_trips_and_refuses_duplicate(queue_env):
    rc = pq.main([
        "add", "--id", "new_item", "--title", "A new item",
        "--priority", "P2", "--category", "Cat E", "--target", "target E",
    ])
    assert rc == 0
    data = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    added = next(i for i in data["items"] if i["id"] == "new_item")
    assert added["status"] == "planned"
    assert added["title"] == "A new item"
    assert added["note"] == ""

    content = queue_env["queue"].read_text(encoding="utf-8")
    assert "new_item" in content

    rc_dup = pq.main([
        "add", "--id", "new_item", "--title", "dup", "--priority", "P2", "--category", "x",
    ])
    assert rc_dup != 0
    data_after = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    assert sum(1 for i in data_after["items"] if i["id"] == "new_item") == 1


def test_status_round_trips(queue_env):
    rc = pq.main(["status", "--id", "item_open_p0", "--set", "in_progress", "--target", "new target"])
    assert rc == 0
    data = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    item = next(i for i in data["items"] if i["id"] == "item_open_p0")
    assert item["status"] == "in_progress"
    assert item["target"] == "new target"

    rc_unknown = pq.main(["status", "--id", "does_not_exist", "--set", "done"])
    assert rc_unknown != 0


def test_note_appends_to_history_file_never_to_json(queue_env):
    before = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    before_note = next(i for i in before["items"] if i["id"] == "item_open_p1")["note"]

    rc = pq.main(["note", "--id", "item_open_p1", "--text", "A fresh progress note."])
    assert rc == 0

    after = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    after_note = next(i for i in after["items"] if i["id"] == "item_open_p1")["note"]
    assert after_note == before_note  # JSON note field untouched

    history_file = queue_env["history_dir"] / "item_open_p1.md"
    assert history_file.exists()
    assert "A fresh progress note." in history_file.read_text(encoding="utf-8")

    # A second note appends rather than overwriting.
    pq.main(["note", "--id", "item_open_p1", "--text", "A second note."])
    text = history_file.read_text(encoding="utf-8")
    assert "A fresh progress note." in text
    assert "A second note." in text


def test_decide_closes_an_open_decision(queue_env):
    rc = pq.main(["decide", "--id", "dec_open", "--decision", "DECIDED: option one."])
    assert rc == 0
    data = json.loads(queue_env["roadmap"].read_text(encoding="utf-8"))
    dec = next(d for d in data["open_decisions"] if d["id"] == "dec_open")
    assert dec["status"] == "decided"
    assert dec["decision"] == "DECIDED: option one."

    content = queue_env["queue"].read_text(encoding="utf-8")
    assert "dec_open" not in content

    rc_again = pq.main(["decide", "--id", "dec_open", "--decision", "second try"])
    assert rc_again != 0


# --- AC-4: terminal-status note move ---------------------------------------

def test_status_transition_to_terminal_moves_note_to_history(queue_env):
    original_note = "In-flight note about to become history."
    # Seed the JSON note directly (as legacy hand-authored data would carry it).
    data = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    item = next(i for i in data["items"] if i["id"] == "item_open_p1")
    item["note"] = original_note
    queue_env["backlog"].write_text(pq.canonical_dump(data), encoding="utf-8")

    rc = pq.main(["status", "--id", "item_open_p1", "--set", "done"])
    assert rc == 0

    after = json.loads(queue_env["backlog"].read_text(encoding="utf-8"))
    after_item = next(i for i in after["items"] if i["id"] == "item_open_p1")
    assert after_item["note"] == "see docs/history/backlog/item_open_p1.md"

    history_file = queue_env["history_dir"] / "item_open_p1.md"
    text = history_file.read_text(encoding="utf-8")
    assert original_note in text
    assert "status: done" in text or "status: in_progress" in text  # written at move time


def test_json_load_failure_reports_line_and_column(queue_env, capsys):
    queue_env["backlog"].write_text('{"items": [ { "id": "x" ]}', encoding="utf-8")
    rc = pq.main(["render"])
    assert rc == 1
    err = capsys.readouterr().err
    assert "line" in err and "column" in err
