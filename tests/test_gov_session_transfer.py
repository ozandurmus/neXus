"""GOV.SESSION.1 -- tests for scripts/gov_session_transfer.py.

Pure local tooling: no network, no device, no vendor collector touched.
Contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md.
"""
from __future__ import annotations

import json

import pytest

import scripts.gov_session_transfer as gst


def _start_obj(**overrides) -> dict:
    obj = {"protocol_version": 1, "message_type": "SESSION_START", "movement": "GOV.SESSION.1", "refs": ["AGENTS.md"]}
    obj.update(overrides)
    return obj


def _close_obj(**overrides) -> dict:
    obj = _start_obj(message_type="SESSION_CLOSE", outcome="AUTOMATED_VALIDATED")
    obj.update(overrides)
    return obj


def _wrap(payload_text: str) -> str:
    return f"{gst.SENTINEL}\n{payload_text}\n{gst.SENTINEL}\n"


# --- Field validation -------------------------------------------------
def test_valid_session_start_has_no_errors():
    assert gst.validate_fields(_start_obj()) == []


def test_valid_session_close_has_no_errors():
    assert gst.validate_fields(_close_obj()) == []


def test_non_object_payload_is_rejected():
    assert gst.validate_fields([1, 2, 3]) == ["packet must be a JSON object"]


@pytest.mark.parametrize("bad_version", [2, 0, -1, "1", True, False])
def test_unsupported_protocol_version_fails_closed(bad_version):
    errors = gst.validate_fields(_start_obj(protocol_version=bad_version))
    assert any("protocol_version" in e for e in errors)


def test_unsupported_message_type_is_rejected():
    errors = gst.validate_fields(_start_obj(message_type="SESSION_PAUSE"))
    assert any("message_type" in e for e in errors)


def test_missing_movement_is_rejected():
    obj = _start_obj()
    del obj["movement"]
    assert any("movement" in e for e in gst.validate_fields(obj))


def test_empty_movement_is_rejected():
    assert any("movement" in e for e in gst.validate_fields(_start_obj(movement="")))


def test_missing_refs_is_rejected():
    obj = _start_obj()
    del obj["refs"]
    assert any("refs" in e for e in gst.validate_fields(obj))


@pytest.mark.parametrize("bad_refs", ["AGENTS.md", [""], [1]])
def test_refs_must_be_a_list_of_non_empty_strings(bad_refs):
    assert any("refs" in e for e in gst.validate_fields(_start_obj(refs=bad_refs)))


def test_close_requires_outcome():
    obj = _close_obj()
    del obj["outcome"]
    assert any("outcome" in e for e in gst.validate_fields(obj))


def test_close_outcome_is_closed_vocabulary():
    assert any("outcome" in e for e in gst.validate_fields(_close_obj(outcome="MAYBE")))


# --- extract_one: envelope handling ------------------------------------
def test_extract_one_finds_the_payload():
    payload = json.dumps(_start_obj())
    assert gst.extract_one(_wrap(payload)) == payload


def test_extract_one_ignores_surrounding_text():
    text = f"chat before\n{_wrap(json.dumps(_start_obj()))}chat after"
    assert json.loads(gst.extract_one(text)) == _start_obj()


def test_extract_one_accepts_crlf_line_endings():
    text = _wrap(json.dumps(_start_obj())).replace("\n", "\r\n")
    assert json.loads(gst.extract_one(text)) == _start_obj()


def test_extract_one_rejects_no_sentinel():
    with pytest.raises(gst.PacketError, match="found 0"):
        gst.extract_one("no sentinel here")


def test_extract_one_rejects_a_single_unmatched_sentinel():
    with pytest.raises(gst.PacketError, match="found 1"):
        gst.extract_one(f"{gst.SENTINEL}\n{{}}")


def test_extract_one_rejects_multiple_packet_bodies():
    text = _wrap(json.dumps(_start_obj())) + _wrap(json.dumps(_close_obj()))
    with pytest.raises(gst.PacketError, match="exactly one packet body"):
        gst.extract_one(text)


def test_extract_one_ignores_a_whitespace_altered_sentinel_line():
    # Not an exact match -- must not be counted as a boundary.
    text = f" {gst.SENTINEL} \n{_wrap(json.dumps(_start_obj()))}"
    assert json.loads(gst.extract_one(text)) == _start_obj()


# --- parse_and_validate / render ----------------------------------------
def test_parse_and_validate_rejects_invalid_json():
    obj, errors = gst.parse_and_validate("{not json")
    assert obj is None
    assert any("invalid JSON" in e for e in errors)


def test_parse_and_validate_accepts_a_valid_packet():
    obj, errors = gst.parse_and_validate(json.dumps(_close_obj()))
    assert errors == []
    assert obj == _close_obj()


def test_render_wraps_in_the_sentinel():
    lines = gst.render(_start_obj()).splitlines()
    assert lines[0] == lines[-1] == gst.SENTINEL


def test_render_is_deterministic_regardless_of_key_order():
    obj_a = _close_obj()
    obj_b = {k: obj_a[k] for k in reversed(list(obj_a))}
    assert gst.render(obj_a) == gst.render(obj_b)


def test_render_refuses_invalid_content():
    obj = _start_obj()
    del obj["movement"]
    with pytest.raises(gst.PacketError):
        gst.render(obj)


def test_render_round_trips_through_extract_one():
    rendered = gst.render(_close_obj())
    assert json.loads(gst.extract_one(rendered)) == _close_obj()


# --- CLI -----------------------------------------------------------------
def test_cli_start_then_validate(tmp_path, capsys):
    out_file = tmp_path / "start.json"
    rc = gst.main(["start", "--movement", "GOV.SESSION.1", "--ref", "AGENTS.md", "--out", str(out_file)])
    assert rc == gst.EXIT_OK
    assert out_file.exists()
    rc = gst.main(["validate", str(out_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out) == {"valid": True, "errors": []}


def test_cli_close_then_validate(tmp_path, capsys):
    out_file = tmp_path / "close.json"
    rc = gst.main([
        "close", "--movement", "GOV.SESSION.1", "--outcome", "AUTOMATED_VALIDATED",
        "--ref", "AGENTS.md", "--out", str(out_file),
    ])
    assert rc == gst.EXIT_OK
    rc = gst.main(["validate", str(out_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out)["valid"] is True


def test_cli_close_requires_outcome_flag():
    with pytest.raises(SystemExit):
        gst.main(["close", "--movement", "GOV.SESSION.1", "--ref", "AGENTS.md"])


def test_cli_close_rejects_unknown_outcome():
    with pytest.raises(SystemExit):
        gst.main(["close", "--movement", "GOV.SESSION.1", "--outcome", "MAYBE", "--ref", "AGENTS.md"])


def test_cli_multiple_refs_accumulate(capsys):
    rc = gst.main(["start", "--movement", "GOV.SESSION.1", "--ref", "AGENTS.md", "--ref", "CURRENT_STATE.md"])
    assert rc == gst.EXIT_OK
    payload = json.loads(gst.extract_one(capsys.readouterr().out))
    assert payload["refs"] == ["AGENTS.md", "CURRENT_STATE.md"]


def test_cli_validate_reports_invalid_content(tmp_path, capsys):
    bad_obj = _start_obj()
    del bad_obj["movement"]
    bad = tmp_path / "bad.json"
    bad.write_text(_wrap(json.dumps(bad_obj)), encoding="utf-8")
    rc = gst.main(["validate", str(bad)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_INVALID
    assert json.loads(captured.out)["valid"] is False


def test_cli_validate_reports_envelope_error(tmp_path):
    bad = tmp_path / "no_sentinel.json"
    bad.write_text("just text, no envelope", encoding="utf-8")
    assert gst.main(["validate", str(bad)]) == gst.EXIT_INVALID


def test_cli_validate_usage_error_for_missing_file():
    assert gst.main(["validate", "does-not-exist.json"]) == gst.EXIT_USAGE


def test_cli_extract_usage_error_for_missing_file():
    assert gst.main(["extract", "does-not-exist.json"]) == gst.EXIT_USAGE


def test_cli_extract_prints_the_packet_json(tmp_path, capsys):
    packet_file = tmp_path / "packet.json"
    packet_file.write_text(_wrap(json.dumps(_start_obj())), encoding="utf-8")
    rc = gst.main(["extract", str(packet_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out) == _start_obj()


def test_cli_extract_rejects_multiple_bodies(tmp_path):
    transcript = tmp_path / "transcript.txt"
    text = _wrap(json.dumps(_start_obj())) + _wrap(json.dumps(_close_obj()))
    transcript.write_text(text, encoding="utf-8")
    assert gst.main(["extract", str(transcript)]) == gst.EXIT_INVALID


def test_cli_reads_stdin_when_file_is_dash(monkeypatch, capsys):
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO(_wrap(json.dumps(_start_obj()))))
    rc = gst.main(["validate", "-"])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out)["valid"] is True
