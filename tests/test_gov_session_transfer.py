"""GOV.SESSION.1 -- tests for scripts/gov_session_transfer.py.

Pure local tooling: no network, no device, no vendor collector touched.
Contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md and
docs/reference/gov_session_transfer_packet.schema.json.
"""
from __future__ import annotations

import json

import pytest

import scripts.gov_session_transfer as gst

START_KWARGS = dict(
    packet_id="pkt-start-1",
    project="neXus",
    movement="GOV.SESSION.1",
    session_mode="NEW",
    objective="Example objective.",
    authority_refs=["AGENTS.md"],
    allow=["source edits"],
    deny=["merge"],
    stop_on=["do not begin M8.3"],
    close_required=True,
)

CLOSE_KWARGS = dict(
    packet_id="pkt-close-1",
    project="neXus",
    movement="GOV.SESSION.1",
    outcome="AUTOMATED_VALIDATED",
    changed=["scripts/gov_session_transfer.py"],
    preserved=["AGENTS.md"],
    validation="42 passed.",
    privacy_state="PASS",
    refs=["AGENTS.md"],
    risks=["none"],
    durable_state=["CURRENT_STATE.md"],
    next_movement=None,
    session_routing="NEW",
    ui_effect="None.",
)


def _start_obj(**overrides):
    obj = {
        "protocol_version": 1,
        "packet_id": START_KWARGS["packet_id"],
        "message_type": "SESSION_START",
        "project": START_KWARGS["project"],
        "movement": START_KWARGS["movement"],
        "session_mode": START_KWARGS["session_mode"],
        "objective": START_KWARGS["objective"],
        "authority_refs": START_KWARGS["authority_refs"],
        "allow": START_KWARGS["allow"],
        "deny": START_KWARGS["deny"],
        "stop_on": START_KWARGS["stop_on"],
        "close_required": START_KWARGS["close_required"],
    }
    obj.update(overrides)
    return obj


def _close_obj(**overrides):
    obj = {
        "protocol_version": 1,
        "packet_id": CLOSE_KWARGS["packet_id"],
        "message_type": "SESSION_CLOSE",
        "project": CLOSE_KWARGS["project"],
        "movement": CLOSE_KWARGS["movement"],
        "outcome": CLOSE_KWARGS["outcome"],
        "changed": CLOSE_KWARGS["changed"],
        "preserved": CLOSE_KWARGS["preserved"],
        "validation": CLOSE_KWARGS["validation"],
        "privacy_state": CLOSE_KWARGS["privacy_state"],
        "refs": CLOSE_KWARGS["refs"],
        "risks": CLOSE_KWARGS["risks"],
        "durable_state": CLOSE_KWARGS["durable_state"],
        "next_movement": CLOSE_KWARGS["next_movement"],
        "session_routing": CLOSE_KWARGS["session_routing"],
        "ui_effect": CLOSE_KWARGS["ui_effect"],
    }
    obj.update(overrides)
    return obj


# --------------------------------------------------------------------------
# Field validation
# --------------------------------------------------------------------------


def test_valid_session_start_has_no_errors():
    assert gst.validate_fields(_start_obj()) == []


def test_valid_session_close_has_no_errors():
    assert gst.validate_fields(_close_obj()) == []


def test_missing_required_field_is_reported():
    obj = _start_obj()
    del obj["objective"]
    errors = gst.validate_fields(obj)
    assert any("objective" in e for e in errors)


def test_unexpected_field_is_rejected_additional_properties_false():
    obj = _start_obj(unexpected_field="nope")
    errors = gst.validate_fields(obj)
    assert any("unexpected_field" in e for e in errors)


@pytest.mark.parametrize("bad_version", [2, 0, -1, "1", True, False, 1.0])
def test_unsupported_protocol_version_fails_closed(bad_version):
    obj = _start_obj(protocol_version=bad_version)
    errors = gst.validate_fields(obj)
    assert any("protocol_version" in e for e in errors)


def test_close_required_must_be_boolean():
    obj = _start_obj(close_required="true")
    errors = gst.validate_fields(obj)
    assert any("close_required" in e for e in errors)


def test_outcome_is_closed_vocabulary():
    obj = _close_obj(outcome="MAYBE")
    errors = gst.validate_fields(obj)
    assert any("outcome" in e for e in errors)


def test_next_movement_accepts_null():
    assert gst.validate_fields(_close_obj(next_movement=None)) == []


def test_ref_list_rejects_too_many_items():
    obj = _start_obj(authority_refs=[f"x{i}.md" for i in range(gst.MAX_LIST_ITEMS + 1)])
    errors = gst.validate_fields(obj)
    assert any("authority_refs" in e for e in errors)


def test_ref_list_rejects_oversized_item():
    obj = _start_obj(authority_refs=["x" * (gst.MAX_LIST_ITEM_LEN + 1)])
    errors = gst.validate_fields(obj)
    assert any("authority_refs" in e for e in errors)


# --------------------------------------------------------------------------
# Strict JSON parsing
# --------------------------------------------------------------------------


def test_duplicate_keys_are_rejected():
    raw = '{"protocol_version": 1, "protocol_version": 1, "packet_id": "a", "message_type": "SESSION_START"}'
    with pytest.raises(gst.PacketError, match="duplicate"):
        gst.strict_json_object(raw)


def test_non_finite_constants_are_rejected():
    raw = '{"a": NaN}'
    with pytest.raises(gst.PacketError, match="non-finite"):
        gst.strict_json_object(raw)


def test_trailing_content_after_json_value_is_rejected():
    raw = '{"a": 1} garbage'
    with pytest.raises(gst.PacketError, match="trailing content"):
        gst.strict_json_object(raw)


def test_non_object_top_level_is_rejected():
    with pytest.raises(gst.PacketError, match="must be a JSON object"):
        gst.strict_json_object("[1, 2, 3]")


def test_malformed_json_is_rejected():
    with pytest.raises(gst.PacketError, match="malformed JSON"):
        gst.strict_json_object("{not json")


def test_empty_payload_is_rejected():
    with pytest.raises(gst.PacketError, match="empty payload"):
        gst.strict_json_object("   \n  ")


def test_payload_size_ceiling_is_enforced():
    huge = json.dumps(_start_obj(objective="x" * gst.MAX_LONG_TEXT))
    # Pad well past the byte ceiling with harmless whitespace.
    padded = huge + (" " * (gst.MAX_PAYLOAD_BYTES + 10))
    with pytest.raises(gst.PacketError, match="byte ceiling"):
        gst.check_size_and_sentinel(padded)


def test_sentinel_inside_payload_is_rejected():
    raw = json.dumps({"note": gst.SENTINEL})
    with pytest.raises(gst.PacketError, match="sentinel line must not appear"):
        gst.check_size_and_sentinel(raw)


# --------------------------------------------------------------------------
# Envelope: standalone split
# --------------------------------------------------------------------------


def _wrap(payload_text: str) -> str:
    return f"{gst.SENTINEL}\n{payload_text}\n{gst.SENTINEL}\n"


def test_split_standalone_extracts_the_payload():
    payload = json.dumps(_start_obj())
    text = _wrap(payload)
    extracted = gst.split_standalone(text)
    assert json.loads(extracted) == _start_obj()


def test_split_standalone_accepts_crlf_line_endings():
    payload = json.dumps(_start_obj())
    text = _wrap(payload).replace("\n", "\r\n")
    extracted = gst.split_standalone(text)
    assert json.loads(extracted) == _start_obj()


def test_split_standalone_rejects_missing_sentinel():
    with pytest.raises(gst.PacketError, match="exactly 2 sentinel"):
        gst.split_standalone("no sentinel here at all")


def test_split_standalone_rejects_single_sentinel_line():
    with pytest.raises(gst.PacketError, match="exactly 2 sentinel"):
        gst.split_standalone(f"{gst.SENTINEL}\n{{}}")


def test_split_standalone_rejects_content_outside_the_pair():
    text = f"leading junk\n{_wrap('{}')}trailing junk"
    with pytest.raises(gst.PacketError, match="unexpected content outside"):
        gst.split_standalone(text)


def test_split_standalone_does_not_count_a_whitespace_altered_sentinel_line():
    # A line with surrounding whitespace is not an exact sentinel match, so
    # it is not counted as a boundary -- only the two real lines are. It
    # still fails the strict standalone shape (content outside the real
    # pair), proving via the error message that it was never miscounted as
    # a third boundary (which would instead report "found 3").
    text = f" {gst.SENTINEL} \n{_wrap('{}')}"
    with pytest.raises(gst.PacketError, match="unexpected content outside"):
        gst.split_standalone(text)


def test_extract_all_does_not_count_a_whitespace_altered_sentinel_line():
    # Same malformed line, but in transcript mode where content outside a
    # pair is normal -- proves the altered line is ordinary text, not a
    # boundary: exactly one packet is found, not zero/odd.
    text = f" {gst.SENTINEL} \n{_wrap('{}')}"
    results = gst.extract_all(text)
    assert len(results) == 1


# --------------------------------------------------------------------------
# Envelope: transcript extraction
# --------------------------------------------------------------------------


def test_extract_all_finds_one_packet():
    text = _wrap(json.dumps(_start_obj()))
    results = gst.extract_all(text)
    assert len(results) == 1
    assert results[0].valid
    assert results[0].payload == _start_obj()


def test_extract_all_finds_two_packets_in_a_transcript():
    text = (
        "some chat preamble\n"
        + _wrap(json.dumps(_start_obj(packet_id="first")))
        + "\nmore chat in between\n"
        + _wrap(json.dumps(_close_obj(packet_id="second")))
        + "trailing chat"
    )
    results = gst.extract_all(text)
    assert [r.packet_id for r in results] == ["first", "second"]
    assert all(r.valid for r in results)


def test_extract_all_selects_by_packet_id():
    text = _wrap(json.dumps(_start_obj(packet_id="alpha"))) + _wrap(json.dumps(_close_obj(packet_id="beta")))
    results = gst.extract_all(text)
    by_id = {r.packet_id: r for r in results}
    assert by_id["alpha"].payload["message_type"] == "SESSION_START"
    assert by_id["beta"].payload["message_type"] == "SESSION_CLOSE"


def test_extract_all_one_malformed_packet_does_not_block_a_valid_one():
    bad_payload = "{not json"
    text = _wrap(bad_payload) + _wrap(json.dumps(_start_obj()))
    results = gst.extract_all(text)
    assert len(results) == 2
    assert results[0].valid is False
    assert results[1].valid is True


def test_extract_all_rejects_odd_sentinel_count():
    text = f"{gst.SENTINEL}\n{{}}\n{gst.SENTINEL}\n{{}}\n{gst.SENTINEL}\n"
    with pytest.raises(gst.PacketError, match="odd sentinel-line count"):
        gst.extract_all(text)


def test_extract_all_flags_empty_pair_without_aborting_the_scan():
    # Four sentinel lines in a row: (0,1) and (2,3) are both empty pairs by
    # construction -- this is the "nested/nothing-between" failure mode
    # discussed in the protocol doc section 3, and it fails closed per pair
    # rather than aborting the whole scan.
    text = f"{gst.SENTINEL}\n{gst.SENTINEL}\n{gst.SENTINEL}\n{gst.SENTINEL}\n"
    results = gst.extract_all(text)
    assert len(results) == 2
    assert all(not r.valid for r in results)
    assert all("empty pair" in r.errors[0] for r in results)


def test_extract_all_returns_empty_list_for_no_sentinels():
    assert gst.extract_all("just some ordinary text") == []


# --------------------------------------------------------------------------
# Rendering: deterministic, validated
# --------------------------------------------------------------------------


def test_render_packet_is_deterministic_regardless_of_key_order():
    obj_a = _start_obj()
    obj_b = {k: obj_a[k] for k in reversed(list(obj_a))}
    assert gst.render_packet(obj_a) == gst.render_packet(obj_b)


def test_render_packet_wraps_in_the_sentinel():
    rendered = gst.render_packet(_close_obj())
    lines = rendered.splitlines()
    assert lines[0] == gst.SENTINEL
    assert lines[-1] == gst.SENTINEL


def test_render_packet_refuses_invalid_content():
    obj = _start_obj()
    del obj["objective"]
    with pytest.raises(gst.PacketError):
        gst.render_packet(obj)


def test_render_round_trips_through_extract():
    rendered = gst.render_packet(_close_obj())
    results = gst.extract_all(rendered)
    assert len(results) == 1
    assert results[0].valid
    assert results[0].payload == _close_obj()


# --------------------------------------------------------------------------
# authority_refs / refs existence check
# --------------------------------------------------------------------------


def test_check_authority_refs_flags_missing_path(tmp_path):
    obj = _start_obj(authority_refs=["AGENTS.md", "does/not/exist.md"])
    errors = gst.check_authority_refs(obj, gst.REPO)
    assert any("does/not/exist.md" in e for e in errors)


def test_check_authority_refs_accepts_existing_path():
    obj = _start_obj(authority_refs=["AGENTS.md"])
    assert gst.check_authority_refs(obj, gst.REPO) == []


# --------------------------------------------------------------------------
# CLI: start / close / validate / render / extract, exit codes
# --------------------------------------------------------------------------


def test_cli_start_then_validate_round_trip(tmp_path, capsys):
    out_file = tmp_path / "start.json"
    argv = [
        "start",
        "--packet-id", "pkt-1",
        "--movement", "GOV.SESSION.1",
        "--objective", "Do the thing.",
        "--authority-ref", "AGENTS.md",
        "--allow", "edits",
        "--deny", "merge",
        "--stop-on", "do not begin M8.3",
        "--out", str(out_file),
    ]
    rc = gst.main(argv)
    assert rc == gst.EXIT_OK
    assert out_file.exists()

    rc = gst.main(["validate", str(out_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    report = json.loads(captured.out)
    assert report["valid"] is True


def test_cli_close_produces_valid_packet(tmp_path, capsys):
    out_file = tmp_path / "close.json"
    argv = [
        "close",
        "--packet-id", "pkt-2",
        "--movement", "GOV.SESSION.1",
        "--outcome", "AUTOMATED_VALIDATED",
        "--validation", "all green",
        "--privacy-state", "PASS",
        "--session-routing", "NEW",
        "--ui-effect", "none",
        "--out", str(out_file),
    ]
    rc = gst.main(argv)
    assert rc == gst.EXIT_OK
    rc = gst.main(["validate", str(out_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out)["valid"] is True


def test_cli_validate_reports_invalid_content(tmp_path, capsys):
    bad = tmp_path / "bad.json"
    bad_obj = _start_obj()
    del bad_obj["objective"]
    text = f"{gst.SENTINEL}\n{json.dumps(bad_obj)}\n{gst.SENTINEL}\n"
    bad.write_text(text, encoding="utf-8")
    rc = gst.main(["validate", str(bad)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_INVALID
    assert json.loads(captured.out)["valid"] is False


def test_cli_validate_reports_envelope_error_exit_code(tmp_path):
    bad = tmp_path / "no_sentinel.json"
    bad.write_text("just text, no envelope", encoding="utf-8")
    rc = gst.main(["validate", str(bad)])
    assert rc == gst.EXIT_ENVELOPE


def test_cli_validate_usage_error_for_missing_file():
    rc = gst.main(["validate", "does-not-exist.json"])
    assert rc == gst.EXIT_USAGE


def test_cli_validate_checks_repo_root_authority_refs(tmp_path, capsys):
    obj = _start_obj(authority_refs=["does/not/exist.md"])
    packet_file = tmp_path / "packet.json"
    packet_file.write_text(gst.render_packet(obj), encoding="utf-8")
    rc = gst.main(["validate", str(packet_file), "--repo-root", str(gst.REPO)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_INVALID
    assert any("does/not/exist.md" in e for e in json.loads(captured.out)["errors"])


def test_cli_render_from_bare_json_file(tmp_path, capsys):
    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps(_close_obj()), encoding="utf-8")
    rc = gst.main(["render", str(bare)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert captured.out.splitlines()[0] == gst.SENTINEL


def test_cli_render_is_deterministic(tmp_path, capsys):
    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps(_close_obj()), encoding="utf-8")
    gst.main(["render", str(bare)])
    first = capsys.readouterr().out
    gst.main(["render", str(bare)])
    second = capsys.readouterr().out
    assert first == second


def test_cli_extract_from_transcript_selects_packet_id(tmp_path, capsys):
    transcript = tmp_path / "transcript.txt"
    text = (
        "chat before\n"
        + f"{gst.SENTINEL}\n{json.dumps(_start_obj(packet_id='want-this'))}\n{gst.SENTINEL}\n"
        + "chat between\n"
        + f"{gst.SENTINEL}\n{json.dumps(_close_obj(packet_id='not-this'))}\n{gst.SENTINEL}\n"
    )
    transcript.write_text(text, encoding="utf-8")
    rc = gst.main(["extract", str(transcript), "--packet-id", "want-this"])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    report = json.loads(captured.out)
    assert report["packet_id"] == "want-this"


def test_cli_extract_missing_packet_id_returns_not_found(tmp_path):
    transcript = tmp_path / "transcript.txt"
    transcript.write_text(_wrap(json.dumps(_start_obj())), encoding="utf-8")
    rc = gst.main(["extract", str(transcript), "--packet-id", "nope"])
    assert rc == gst.EXIT_NOT_FOUND


def test_cli_reads_stdin_when_file_is_dash(monkeypatch, capsys):
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO(_wrap(json.dumps(_start_obj()))))
    rc = gst.main(["validate", "-"])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out)["valid"] is True
