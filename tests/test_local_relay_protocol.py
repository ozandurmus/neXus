"""GOV.PO.1 -- local, file-based relay transport (docs/design/LOCAL_RELAY_PROTOCOL.md).

Pure local tooling: no network, no device, no vendor collector touched.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import local_relay as lr  # noqa: E402


def _start_report(**overrides) -> dict:
    report = {
        "baseline": {"origin_main": "deadbeef"},
        "objective": "Exercise the local relay transport.",
        "scope": {"in": ["do the thing"], "out": ["not that thing"]},
        "movement_type": "VALIDATION",
        "requirements": ["exactly one schema"],
        "acceptance_criteria": ["round-trips cleanly"],
        "validation_plan": ["run the focused suite"],
        "invariants": ["repository stays authoritative"],
        "risks": [],
        "context_not_loaded": [],
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "git": {"lane": "test lane", "base": "origin/main"},
        "merge_gate": "n/a",
        "deployment_direction": "local validation only",
        "output_contract": ["exactly one relay file"],
    }
    report.update(overrides)
    return report


def _close_report(**overrides) -> dict:
    report = {
        "completed": ["did the thing"],
        "changed": ["a relay file"],
        "preserved": ["everything else"],
        "validation": {
            "targeted": "1 passed", "affected": "1 passed", "full_regression": "not run, test fixture",
            "privacy": "n/a", "state_consistency": "n/a", "diff_check": "n/a", "real_environment": "NOT_RUN",
        },
        "unresolved_risks": [],
        "state_updates": [],
        "next": {"movement": "n/a", "movement_type": "VALIDATION", "status": "done", "objective": "n/a"},
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "continuation": "SAME_SESSION",
        "integration": {
            "branch": "n/a", "head_sha": "n/a", "pr": None, "pr_url": None,
            "ci": "n/a", "merge_state": "NOT_OPENED", "merge_commit": None, "merge_decision": "NOT_APPLICABLE",
        },
        "effects": {"main_py": "none", "ui": "none"},
    }
    report.update(overrides)
    return report


def _start_bare(**overrides) -> dict:
    obj = {
        "protocol_version": 2,
        "message_type": "SESSION_START",
        "movement": "TEST_MOVEMENT",
        "refs": ["AGENTS.md"],
        "report": _start_report(),
    }
    obj.update(overrides)
    return obj


def _create(tmp_path, role="po", **start_overrides) -> Path:
    start = tmp_path / "start.json"
    start.write_text(json.dumps(_start_bare(**start_overrides)), encoding="utf-8")
    relay_dir = tmp_path / "relay"
    rc = lr.main(["create", "--role", role, "--start", str(start), "--dir", str(relay_dir)])
    assert rc == lr.EXIT_OK
    files = sorted(relay_dir.glob("*.json"))
    assert len(files) == 1
    return files[0]


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


# --- create / id sequencing -------------------------------------------------

def test_create_writes_a_valid_file_with_open_status(tmp_path, capsys):
    path = _create(tmp_path)
    capsys.readouterr()
    obj = _read(path)
    assert obj["schema_version"] == 1
    assert obj["id"] == "NXS-LOCAL-0001"
    assert obj["movement"] == "TEST_MOVEMENT"
    assert obj["status"] == "OPEN"
    assert obj["next_actor"] == "engineer"
    assert len(obj["entries"]) == 1
    assert obj["entries"][0]["marker"] == "SESSION_START"
    assert obj["entries"][0]["actor"] == "po"
    assert obj["entries"][0]["seq"] == 1
    assert lr.validate_relay_object(obj) == []


def test_create_prints_the_created_path(tmp_path, capsys):
    path = _create(tmp_path)
    out = capsys.readouterr().out.strip()
    assert out == str(path)


def test_create_derives_slug_from_movement(tmp_path):
    path = _create(tmp_path, movement="M10.1 Weird Movement!!")
    assert path.name == "NXS-LOCAL-0001-m10-1-weird-movement.json"


def test_create_explicit_slug_overrides_derived_one(tmp_path):
    relay_dir = tmp_path / "relay"
    start = tmp_path / "start.json"
    start.write_text(json.dumps(_start_bare()), encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir), "--slug", "custom-slug"])
    assert rc == lr.EXIT_OK
    assert (relay_dir / "NXS-LOCAL-0001-custom-slug.json").exists()


def test_create_assigns_sequential_ids_across_calls(tmp_path):
    relay_dir = tmp_path / "relay"
    for i in range(3):
        start = tmp_path / f"start{i}.json"
        start.write_text(json.dumps(_start_bare(movement=f"MOVEMENT_{i}")), encoding="utf-8")
        rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir)])
        assert rc == lr.EXIT_OK
    ids = sorted(_read(p)["id"] for p in relay_dir.glob("*.json"))
    assert ids == ["NXS-LOCAL-0001", "NXS-LOCAL-0002", "NXS-LOCAL-0003"]


def test_create_by_engineer_sets_next_actor_to_po(tmp_path):
    path = _create(tmp_path, role="engineer")
    obj = _read(path)
    assert obj["entries"][0]["actor"] == "engineer"
    assert obj["next_actor"] == "po"


def test_create_writes_nothing_on_a_validation_failure(tmp_path, capsys):
    start = tmp_path / "start.json"
    bad = _start_bare()
    del bad["report"]
    start.write_text(json.dumps(bad), encoding="utf-8")
    relay_dir = tmp_path / "relay"
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir)])
    assert rc == lr.EXIT_INVALID
    assert not relay_dir.exists() or not list(relay_dir.glob("*.json"))
    assert capsys.readouterr().out == ""


def test_create_rejects_malformed_json(tmp_path):
    start = tmp_path / "start.json"
    start.write_text("{not json", encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(tmp_path / "relay")])
    assert rc == lr.EXIT_INVALID


def test_create_usage_error_for_missing_file():
    assert lr.main(["create", "--role", "po", "--start", "does-not-exist.json", "--dir", "/tmp/nope"]) == lr.EXIT_USAGE


def test_create_reads_stdin_when_start_is_dash(tmp_path, monkeypatch, capsys):
    import io
    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps(_start_bare())))
    relay_dir = tmp_path / "relay"
    rc = lr.main(["create", "--role", "po", "--start", "-", "--dir", str(relay_dir)])
    assert rc == lr.EXIT_OK
    assert len(list(relay_dir.glob("*.json"))) == 1


# --- schema validation -------------------------------------------------

def test_valid_freshly_created_file_has_no_errors(tmp_path):
    assert lr.validate_relay_object(_read(_create(tmp_path))) == []


def test_non_object_is_rejected():
    assert lr.validate_relay_object(["not", "an", "object"]) == ["must be a JSON object"]


def test_unknown_top_level_field_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["unexpected"] = "surprise"
    assert any("unexpected" in e for e in lr.validate_relay_object(obj))


@pytest.mark.parametrize("field", ["schema_version", "id", "movement", "refs", "status",
                                   "next_actor", "created_at", "updated_at", "entries"])
def test_missing_top_level_field_is_rejected(tmp_path, field):
    obj = _read(_create(tmp_path))
    del obj[field]
    assert any(field in e for e in lr.validate_relay_object(obj))


def test_missing_field_is_not_also_reported_as_wrong_type(tmp_path):
    obj = _read(_create(tmp_path))
    del obj["movement"]
    errors = lr.validate_relay_object(obj)
    assert sum("movement" in e for e in errors) == 1


def test_wrong_schema_version_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["schema_version"] = 2
    assert any("schema_version" in e for e in lr.validate_relay_object(obj))


def test_malformed_id_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["id"] = "not-an-id"
    assert any(e.startswith("id:") for e in lr.validate_relay_object(obj))


def test_empty_movement_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["movement"] = ""
    assert any(e.startswith("movement:") for e in lr.validate_relay_object(obj))


def test_refs_may_be_empty_but_must_be_a_list_of_strings(tmp_path):
    obj = _read(_create(tmp_path))
    obj["refs"] = []
    assert lr.validate_relay_object(obj) == []
    obj["refs"] = ["", "ok"]
    assert any(e.startswith("refs:") for e in lr.validate_relay_object(obj))


def test_unknown_status_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["status"] = "SOMETHING_ELSE"
    assert any(e.startswith("status:") for e in lr.validate_relay_object(obj))


def test_entries_must_be_non_empty(tmp_path):
    obj = _read(_create(tmp_path))
    obj["entries"] = []
    assert any("entries" in e for e in lr.validate_relay_object(obj))


def test_first_entry_must_be_session_start(tmp_path):
    obj = _read(_create(tmp_path))
    obj["entries"][0]["marker"] = "RELAY_NOTE"
    obj["entries"][0]["subject"] = "x"
    obj["entries"][0]["text"] = "y"
    del obj["entries"][0]["report"]
    assert any("SESSION_START" in e for e in lr.validate_relay_object(obj))


def test_second_session_start_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    second = dict(obj["entries"][0])
    second["seq"] = 2
    obj["entries"].append(second)
    obj["next_actor"] = "po"
    obj["status"] = "AWAITING_PO"
    errors = lr.validate_relay_object(obj)
    assert any("exactly one SESSION_START" in e for e in errors)
    assert any("position 0" in e for e in errors)


def test_session_close_only_legal_as_last_entry(tmp_path):
    obj = _read(_create(tmp_path))
    close_entry = {"seq": 2, "marker": "SESSION_CLOSE", "actor": "engineer",
                   "timestamp": "2026-09-08T00:00:00Z", "report": _close_report(), "outcome": "DONE"}
    trailing = {"seq": 3, "marker": "RELAY_NOTE", "actor": "po",
                "timestamp": "2026-09-08T00:00:01Z", "subject": "late", "text": "too late"}
    obj["entries"] += [close_entry, trailing]
    obj["next_actor"] = "po"
    obj["status"] = "AWAITING_PO"
    assert any("last entry" in e for e in lr.validate_relay_object(obj))


def test_entry_with_unknown_marker_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["entries"].append({"seq": 2, "marker": "RELAY_MAGIC", "actor": "engineer", "timestamp": "2026-09-08T00:00:00Z"})
    assert any("marker" in e for e in lr.validate_relay_object(obj))


def test_entry_wrong_seq_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["entries"].append({"seq": 99, "marker": "RELAY_NOTE", "actor": "engineer",
                            "timestamp": "2026-09-08T00:00:00Z", "subject": "x", "text": "y"})
    obj["next_actor"] = "po"
    obj["status"] = "AWAITING_PO"
    assert any("seq" in e for e in lr.validate_relay_object(obj))


def test_good_to_go_must_be_a_boolean(tmp_path):
    obj = _read(_create(tmp_path))
    obj["entries"].append({"seq": 2, "marker": "RELAY_NOTE", "actor": "engineer",
                            "timestamp": "2026-09-08T00:00:00Z", "subject": "x", "text": "y",
                            "good_to_go": "yes"})
    obj["next_actor"] = "po"
    obj["status"] = "AWAITING_PO"
    assert any("good_to_go" in e for e in lr.validate_relay_object(obj))


@pytest.mark.parametrize("missing_field", ["authorized_by", "scope", "supersedes"])
def test_relay_decision_requires_authorized_by_scope_supersedes(tmp_path, missing_field):
    obj = _read(_create(tmp_path))
    entry = {"seq": 2, "marker": "RELAY_DECISION", "actor": "po", "timestamp": "2026-09-08T00:00:00Z",
             "subject": "d", "text": "t", "authorized_by": "PO", "scope": "s", "supersedes": "none"}
    del entry[missing_field]
    obj["entries"].append(entry)
    obj["next_actor"] = "engineer"
    obj["status"] = "AWAITING_ENGINEER"
    assert any(missing_field in e for e in lr.validate_relay_object(obj))


def test_next_actor_status_mismatch_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["status"] = "AWAITING_PO"  # next_actor is "engineer" -- contradiction
    assert any("status" in e for e in lr.validate_relay_object(obj))


def test_closed_status_with_a_non_null_next_actor_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["status"] = "CLOSED"  # next_actor is still "engineer" -- contradiction
    assert any("status" in e for e in lr.validate_relay_object(obj))


def test_null_next_actor_with_a_non_closed_status_is_rejected(tmp_path):
    obj = _read(_create(tmp_path))
    obj["next_actor"] = None  # status is still "OPEN" -- contradiction
    assert any("status" in e for e in lr.validate_relay_object(obj))


# --- append: turn enforcement (AC-4) -------------------------------------------------

def test_append_rejects_a_call_from_the_wrong_role(tmp_path, capsys):
    path = _create(tmp_path)  # next_actor is "engineer"
    rc = lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_NOTE",
                  "--subject", "x", "--text", "y", "--next", "engineer"])
    assert rc == lr.EXIT_INVALID
    assert "not po's turn" in capsys.readouterr().err
    assert len(_read(path)["entries"]) == 1


def test_append_accepts_a_call_from_the_correct_role(tmp_path):
    path = _create(tmp_path)  # next_actor is "engineer"
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "received", "--text", "starting work", "--next", "engineer"])
    assert rc == lr.EXIT_OK
    obj = _read(path)
    assert len(obj["entries"]) == 2
    assert obj["next_actor"] == "engineer"
    assert obj["status"] == "AWAITING_ENGINEER"


def test_append_flips_turn_on_next_flag(tmp_path):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_QUESTION",
             "--subject", "q", "--text", "one question", "--next", "po"])
    obj = _read(path)
    assert obj["next_actor"] == "po"
    assert obj["status"] == "AWAITING_PO"


def test_append_rejects_session_start():
    parser = lr.build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["append", "--file", "x.json", "--role", "po", "--marker", "SESSION_START"])


def test_append_to_a_closed_file_is_rejected(tmp_path, capsys):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_NOTE",
             "--subject", "x", "--text", "y", "--close"])
    assert _read(path)["status"] == "CLOSED"
    rc = lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_NOTE",
                  "--subject", "x", "--text", "y", "--next", "engineer"])
    assert rc == lr.EXIT_INVALID
    assert "CLOSED" in capsys.readouterr().err


def test_append_requires_next_unless_close(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_NOTE",
                  "--subject", "x", "--text", "y"])
    assert rc == lr.EXIT_USAGE
    assert "--next" in capsys.readouterr().err


def test_append_close_and_in_progress_are_mutually_exclusive(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_NOTE",
                  "--subject", "x", "--text", "y", "--close", "--in-progress"])
    assert rc == lr.EXIT_USAGE


def test_append_in_progress_sets_status_without_changing_next_actor(tmp_path):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
             "--subject", "ack", "--text", "working on it", "--next", "engineer", "--in-progress"])
    obj = _read(path)
    assert obj["next_actor"] == "engineer"
    assert obj["status"] == "IN_PROGRESS"


def test_append_session_close_requires_report_and_outcome(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "SESSION_CLOSE"])
    assert rc == lr.EXIT_INVALID
    assert "--report" in capsys.readouterr().err


def test_append_session_close_closes_the_file(tmp_path, capsys):
    path = _create(tmp_path)
    report_file = tmp_path / "close_report.json"
    report_file.write_text(json.dumps(_close_report()), encoding="utf-8")
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "SESSION_CLOSE",
                  "--report", str(report_file), "--outcome", "DONE"])
    assert rc == lr.EXIT_OK
    obj = _read(path)
    assert obj["status"] == "CLOSED"
    assert obj["next_actor"] is None
    assert obj["entries"][-1]["outcome"] == "DONE"
    assert lr.validate_relay_object(obj) == []


def test_append_session_close_rejects_next_or_close_flags(tmp_path, capsys):
    path = _create(tmp_path)
    report_file = tmp_path / "close_report.json"
    report_file.write_text(json.dumps(_close_report()), encoding="utf-8")
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "SESSION_CLOSE",
                  "--report", str(report_file), "--outcome", "DONE", "--next", "po"])
    assert rc == lr.EXIT_USAGE


def test_append_session_close_rejects_a_malformed_report(tmp_path, capsys):
    path = _create(tmp_path)
    report_file = tmp_path / "close_report.json"
    bad = _close_report()
    del bad["validation"]
    report_file.write_text(json.dumps(bad), encoding="utf-8")
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "SESSION_CLOSE",
                  "--report", str(report_file), "--outcome", "DONE"])
    assert rc == lr.EXIT_INVALID


# --- RELAY_DECISION authorization fields (AC-6) -------------------------------------

def test_append_relay_decision_requires_authorized_by_scope_supersedes(tmp_path, capsys):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_QUESTION",
             "--subject", "q", "--text", "should we?", "--next", "po"])
    rc = lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_DECISION",
                  "--subject", "d", "--text", "yes", "--next", "engineer"])
    assert rc == lr.EXIT_INVALID
    assert "authorized-by" in capsys.readouterr().err


def test_append_relay_decision_with_full_fields_succeeds(tmp_path):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_QUESTION",
             "--subject", "q", "--text", "should we?", "--next", "po"])
    rc = lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_DECISION",
                  "--subject", "d", "--text", "yes, proceed",
                  "--authorized-by", "Product Owner -- chat directive 2026-09-08",
                  "--scope", "this test only", "--supersedes", "none", "--next", "engineer"])
    assert rc == lr.EXIT_OK
    entry = _read(path)["entries"][-1]
    assert entry["authorized_by"] == "Product Owner -- chat directive 2026-09-08"
    assert entry["scope"] == "this test only"
    assert entry["supersedes"] == "none"


# --- good_to_go fast path (round trip) -----------------------------------------------

def test_good_to_go_fast_path_needs_no_subject_or_text(tmp_path):
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--good-to-go", "--next", "po"])
    assert rc == lr.EXIT_OK
    entry = _read(path)["entries"][-1]
    assert entry["good_to_go"] is True
    assert entry["subject"] and entry["text"]


def test_good_to_go_round_trip_between_po_and_engineer(tmp_path):
    path = _create(tmp_path)
    lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_NOTE",
             "--good-to-go", "--next", "po"])
    rc = lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_NOTE",
                  "--good-to-go", "--next", "engineer"])
    assert rc == lr.EXIT_OK
    obj = _read(path)
    assert obj["entries"][1]["good_to_go"] is True
    assert obj["entries"][2]["good_to_go"] is True
    assert lr.validate_relay_object(obj) == []


def test_good_to_go_does_not_weaken_relay_decision_requirements(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_DECISION",
                  "--good-to-go", "--next", "po"])
    assert rc == lr.EXIT_INVALID
    assert "authorized-by" in capsys.readouterr().err


# --- concurrency (optimistic re-check before write) ----------------------------------

def test_append_fails_closed_on_a_concurrent_write(tmp_path, monkeypatch, capsys):
    path = _create(tmp_path)
    real_read_text = Path.read_text
    calls = {"n": 0}

    def flaky_read_text(self, *args, **kwargs):
        text = real_read_text(self, *args, **kwargs)
        calls["n"] += 1
        if self == path and calls["n"] == 2:
            other = json.loads(text)
            other["entries"].append({"seq": 2, "marker": "RELAY_NOTE", "actor": "engineer",
                                      "timestamp": "2026-09-08T00:00:00Z", "subject": "raced", "text": "in first"})
            other["next_actor"] = "po"
            other["status"] = "AWAITING_PO"
            return json.dumps(other)
        return text

    monkeypatch.setattr(Path, "read_text", flaky_read_text)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_INVALID
    assert "concurrent" in capsys.readouterr().err.lower()


# --- status ---------------------------------------------------------------------------

def test_status_reports_the_expected_summary(tmp_path, capsys):
    path = _create(tmp_path)
    capsys.readouterr()
    rc = lr.main(["status", "--file", str(path)])
    assert rc == lr.EXIT_OK
    out = json.loads(capsys.readouterr().out)
    assert out == {
        "valid": True, "errors": [], "id": "NXS-LOCAL-0001", "movement": "TEST_MOVEMENT",
        "status": "OPEN", "next_actor": "engineer", "entry_count": 1,
        "last_marker": "SESSION_START", "last_actor": "po", "last_timestamp": out["last_timestamp"],
    }


def test_status_usage_error_for_missing_file():
    assert lr.main(["status", "--file", "does-not-exist.json"]) == lr.EXIT_USAGE


def test_status_flags_invalid_files(tmp_path, capsys):
    path = _create(tmp_path)
    obj = _read(path)
    obj["status"] = "SOMETHING_ELSE"
    path.write_text(json.dumps(obj), encoding="utf-8")
    capsys.readouterr()
    lr.main(["status", "--file", str(path)])
    out = json.loads(capsys.readouterr().out)
    assert out["valid"] is False
    assert out["errors"]


# --- validate CLI (mirrors gov_session_transfer.py validate) --------------------------

def test_validate_cli_reports_valid(tmp_path, capsys):
    path = _create(tmp_path)
    capsys.readouterr()
    rc = lr.main(["validate", "--file", str(path)])
    assert rc == lr.EXIT_OK
    assert json.loads(capsys.readouterr().out) == {"valid": True, "errors": []}


def test_validate_cli_reports_invalid(tmp_path, capsys):
    path = _create(tmp_path)
    obj = _read(path)
    del obj["movement"]
    path.write_text(json.dumps(obj), encoding="utf-8")
    capsys.readouterr()
    rc = lr.main(["validate", "--file", str(path)])
    assert rc == lr.EXIT_INVALID
    assert json.loads(capsys.readouterr().out)["valid"] is False


def test_validate_cli_usage_error_for_missing_file():
    assert lr.main(["validate", "--file", "does-not-exist.json"]) == lr.EXIT_USAGE


def test_validate_rejects_a_filename_id_mismatch(tmp_path):
    path = _create(tmp_path)
    renamed = path.with_name("NXS-LOCAL-0001-different-slug.json")
    path.rename(renamed)
    obj = _read(renamed)
    obj["id"] = "NXS-LOCAL-0002"
    renamed.write_text(json.dumps(obj), encoding="utf-8")
    rc = lr.main(["validate", "--file", str(renamed)])
    assert rc == lr.EXIT_INVALID


# --- watch (bounded, read-only poll for next_actor) -----------------------------------

def test_watch_exits_immediately_when_already_matching(tmp_path, monkeypatch, capsys):
    path = _create(tmp_path)  # next_actor is already "engineer"
    monkeypatch.setattr(lr.time, "sleep", lambda seconds: (_ for _ in ()).throw(AssertionError("should not sleep")))
    capsys.readouterr()
    rc = lr.main(["watch", "--file", str(path), "--for", "engineer", "--timeout", "60"])
    assert rc == lr.EXIT_OK
    out = json.loads(capsys.readouterr().out)
    assert out == {"next_actor": "engineer", "entries": []}


def test_watch_exits_ok_when_next_actor_flips_mid_poll(tmp_path, monkeypatch, capsys):
    path = _create(tmp_path)  # next_actor starts as "engineer"; watch waits for "po"
    calls = {"n": 0}

    def fake_sleep(seconds):
        calls["n"] += 1
        if calls["n"] == 1:
            rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                          "--subject", "done", "--text", "handing back", "--next", "po"])
            assert rc == lr.EXIT_OK
            capsys.readouterr()  # discard append's own stdout summary line

    monkeypatch.setattr(lr.time, "sleep", fake_sleep)
    capsys.readouterr()
    rc = lr.main(["watch", "--file", str(path), "--for", "po", "--interval", "5", "--timeout", "60"])
    assert rc == lr.EXIT_OK
    assert calls["n"] == 1
    out = json.loads(capsys.readouterr().out)
    assert out["next_actor"] == "po"
    assert [e["marker"] for e in out["entries"]] == ["RELAY_ACK"]
    assert [e["seq"] for e in out["entries"]] == [2]


def test_watch_exits_timeout_with_no_flip(tmp_path, monkeypatch):
    path = _create(tmp_path)  # next_actor stays "engineer" -- watch waits for "po"
    clock = {"t": 0.0}
    monkeypatch.setattr(lr.time, "monotonic", lambda: clock["t"])

    def fake_sleep(seconds):
        clock["t"] += seconds

    monkeypatch.setattr(lr.time, "sleep", fake_sleep)
    rc = lr.main(["watch", "--file", str(path), "--for", "po", "--interval", "5", "--timeout", "10"])
    assert rc == lr.EXIT_TIMEOUT


def test_watch_timeout_leaves_the_file_byte_for_byte_unchanged(tmp_path, monkeypatch):
    path = _create(tmp_path)
    before = path.read_bytes()
    clock = {"t": 0.0}
    monkeypatch.setattr(lr.time, "monotonic", lambda: clock["t"])
    monkeypatch.setattr(lr.time, "sleep", lambda seconds: clock.__setitem__("t", clock["t"] + seconds))
    rc = lr.main(["watch", "--file", str(path), "--for", "po", "--interval", "5", "--timeout", "10"])
    assert rc == lr.EXIT_TIMEOUT
    assert path.read_bytes() == before


def test_watch_rejects_interval_below_the_floor(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["watch", "--file", str(path), "--for", "po", "--interval", "4"])
    assert rc == lr.EXIT_USAGE
    assert "--interval" in capsys.readouterr().err


def test_watch_rejects_timeout_above_the_ceiling(tmp_path, capsys):
    path = _create(tmp_path)
    rc = lr.main(["watch", "--file", str(path), "--for", "po", "--timeout", "3601"])
    assert rc == lr.EXIT_USAGE
    assert "--timeout" in capsys.readouterr().err


def test_watch_usage_error_for_missing_file():
    assert lr.main(["watch", "--file", "does-not-exist.json", "--for", "po"]) == lr.EXIT_USAGE


def test_watch_rejects_an_invalid_relay_file(tmp_path):
    path = _create(tmp_path)
    obj = _read(path)
    del obj["movement"]
    path.write_text(json.dumps(obj), encoding="utf-8")
    rc = lr.main(["watch", "--file", str(path), "--for", "po"])
    assert rc == lr.EXIT_INVALID


# --- full lifecycle round trip ---------------------------------------------------------

# --- canonical-location resolver (GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md,
# FROZEN, section 3.6) ------------------------------------------------------

def test_create_dir_defaults_to_the_canonical_env_var_when_omitted(tmp_path, monkeypatch):
    canonical = tmp_path / "canonical-relay"
    monkeypatch.setenv(lr.ENV_CANONICAL_RELAY_DIR, str(canonical))
    start = tmp_path / "start.json"
    start.write_text(json.dumps(_start_bare()), encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start)])
    assert rc == lr.EXIT_OK
    assert len(list(canonical.glob("*.json"))) == 1


def test_create_explicit_dir_always_wins_over_the_env_var(tmp_path, monkeypatch):
    canonical = tmp_path / "canonical-relay"
    explicit = tmp_path / "explicit-relay"
    monkeypatch.setenv(lr.ENV_CANONICAL_RELAY_DIR, str(canonical))
    start = tmp_path / "start.json"
    start.write_text(json.dumps(_start_bare()), encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(explicit)])
    assert rc == lr.EXIT_OK
    assert len(list(explicit.glob("*.json"))) == 1
    assert not canonical.exists()


def test_append_status_validate_watch_default_to_the_relay_file_env_var(tmp_path, monkeypatch):
    path = _create(tmp_path)
    monkeypatch.setenv(lr.ENV_RELAY_FILE, str(path))
    assert lr.main(["append", "--role", "engineer", "--marker", "RELAY_ACK",
                     "--subject", "x", "--text", "y", "--next", "po"]) == lr.EXIT_OK
    assert lr.main(["status"]) == lr.EXIT_OK
    assert lr.main(["validate"]) == lr.EXIT_OK


def test_append_explicit_file_wins_when_the_env_var_is_unset(tmp_path, monkeypatch):
    real = _create(tmp_path)
    monkeypatch.delenv(lr.ENV_RELAY_FILE, raising=False)
    rc = lr.main(["append", "--file", str(real), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_OK


def test_append_explicit_file_matching_the_env_var_still_works(tmp_path, monkeypatch):
    real = _create(tmp_path)
    monkeypatch.setenv(lr.ENV_RELAY_FILE, str(real))
    rc = lr.main(["append", "--file", str(real), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_OK


def test_append_rejects_an_explicit_file_that_does_not_match_the_env_var(tmp_path, monkeypatch, capsys):
    # NXS-LOCAL-0021: a relative --file used to silently resolve against cwd
    # instead of the canonical NEXUS_RELAY_FILE, creating a divergent shadow
    # relay file the orchestrator/PO never saw. append now refuses instead.
    real = _create(tmp_path)
    monkeypatch.setenv(lr.ENV_RELAY_FILE, str(real))
    shadow = tmp_path / "shadow.json"
    shadow.write_text(real.read_text(encoding="utf-8"), encoding="utf-8")
    rc = lr.main(["append", "--file", str(shadow), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_USAGE
    assert lr.ENV_RELAY_FILE in capsys.readouterr().err
    # Neither file was mutated by the rejected call.
    assert json.loads(shadow.read_text(encoding="utf-8"))["entries"] == \
        json.loads(real.read_text(encoding="utf-8"))["entries"]


def test_append_rejects_a_relative_file_resolving_outside_the_canonical_dir(tmp_path, monkeypatch):
    # AC-3 repro: NEXUS_RELAY_FILE/NEXUS_CANONICAL_RELAY_DIR set (as the
    # orchestrator sets them for a spawned engineer worktree), append invoked
    # with a relative --file that would previously resolve against cwd (here,
    # a separate "worktree" directory) instead of the canonical file.
    canonical_base = tmp_path / "canonical-base"
    canonical_base.mkdir()
    real = _create(canonical_base, role="po")
    canonical_dir = real.parent
    monkeypatch.setenv(lr.ENV_CANONICAL_RELAY_DIR, str(canonical_dir))
    monkeypatch.setenv(lr.ENV_RELAY_FILE, str(real))

    worktree = tmp_path / "worktree"
    worktree.mkdir()
    relative_name = real.name  # e.g. "NXS-LOCAL-0021-....json", relative to cwd
    monkeypatch.chdir(worktree)

    before = real.read_text(encoding="utf-8")
    rc = lr.main(["append", "--file", relative_name, "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_USAGE
    # No second, divergent relay file was silently created in the worktree cwd,
    # and the canonical file was left untouched.
    assert not (worktree / relative_name).exists()
    assert real.read_text(encoding="utf-8") == before


def test_append_reports_usage_error_when_neither_flag_nor_env_var_is_set(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv(lr.ENV_RELAY_FILE, raising=False)
    rc = lr.main(["append", "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_USAGE
    assert "NEXUS_RELAY_FILE" in capsys.readouterr().err


def test_status_reports_usage_error_when_neither_flag_nor_env_var_is_set(monkeypatch):
    monkeypatch.delenv(lr.ENV_RELAY_FILE, raising=False)
    assert lr.main(["status"]) == lr.EXIT_USAGE


# --- shared append lock (section 3.6) ---------------------------------------

def test_append_cli_fails_closed_when_another_process_holds_the_lock(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(lr, "APPEND_LOCK_TIMEOUT", 0.2)
    path = _create(tmp_path)
    holder = lr._FileLock(path, timeout=1.0)
    holder.__enter__()
    try:
        capsys.readouterr()
        rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                      "--subject", "x", "--text", "y", "--next", "po"])
        assert rc == lr.EXIT_INVALID
        assert "lock" in capsys.readouterr().err.lower()
        assert len(_read(path)["entries"]) == 1  # nothing was appended
    finally:
        holder.__exit__(None, None, None)


def test_real_lock_blocks_a_second_acquirer_until_released(tmp_path):
    target = tmp_path / "x.json"
    lock_a = lr._FileLock(target, timeout=0.3)
    lock_a.__enter__()
    try:
        lock_b = lr._FileLock(target, timeout=0.3)
        with pytest.raises(lr.FileLockTimeout):
            lock_b.__enter__()
    finally:
        lock_a.__exit__(None, None, None)

    lock_c = lr._FileLock(target, timeout=1.0)
    lock_c.__enter__()
    lock_c.__exit__(None, None, None)  # does not raise -- released cleanly


def test_append_still_works_normally_with_the_lock_in_place(tmp_path):
    # The lock wraps the whole critical section but must not change any
    # existing observable behavior for the ordinary, uncontended case.
    path = _create(tmp_path)
    rc = lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                  "--subject", "x", "--text", "y", "--next", "po"])
    assert rc == lr.EXIT_OK
    assert len(_read(path)["entries"]) == 2


def test_full_lifecycle_round_trip(tmp_path):
    path = _create(tmp_path)  # po opens, next_actor=engineer, status=OPEN

    assert lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_ACK",
                     "--subject", "received", "--text", "starting", "--next", "engineer"]) == lr.EXIT_OK
    assert lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "RELAY_QUESTION",
                     "--subject", "one thing", "--text", "is X ok?", "--next", "po"]) == lr.EXIT_OK
    assert lr.main(["append", "--file", str(path), "--role", "po", "--marker", "RELAY_DECISION",
                     "--subject", "decision", "--text", "yes, proceed",
                     "--authorized-by", "Product Owner -- chat directive", "--scope", "this movement",
                     "--supersedes", "none", "--next", "engineer"]) == lr.EXIT_OK

    report_file = tmp_path / "close.json"
    report_file.write_text(json.dumps(_close_report()), encoding="utf-8")
    assert lr.main(["append", "--file", str(path), "--role", "engineer", "--marker", "SESSION_CLOSE",
                    "--report", str(report_file), "--outcome", "AUTOMATED_VALIDATED"]) == lr.EXIT_OK

    obj = _read(path)
    assert [e["marker"] for e in obj["entries"]] == [
        "SESSION_START", "RELAY_ACK", "RELAY_QUESTION", "RELAY_DECISION", "SESSION_CLOSE",
    ]
    assert [e["seq"] for e in obj["entries"]] == [1, 2, 3, 4, 5]
    assert obj["status"] == "CLOSED"
    assert obj["next_actor"] is None
    assert lr.validate_relay_object(obj) == []
