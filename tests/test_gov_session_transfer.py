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
    git={"base_sha": "abc1234", "branch": "governance/gov-session-1-transfer-protocol"},
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
    git={
        "base_sha": "abc1234",
        "branch": "governance/gov-session-1-transfer-protocol",
        "head_sha": "def5678",
        "pr_number": 99,
        "merged": False,
        "merge_sha": None,
    },
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
        "git": dict(START_KWARGS["git"]),
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
        "git": dict(CLOSE_KWARGS["git"]),
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
        "--base-sha", "abc1234",
        "--branch", "governance/gov-session-1-transfer-protocol",
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
        "--base-sha", "abc1234",
        "--branch", "governance/gov-session-1-transfer-protocol",
        "--head-sha", "def5678",
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


# --------------------------------------------------------------------------
# git object validation (correction round 1: required on both message types)
# --------------------------------------------------------------------------


def test_git_missing_is_reported_as_missing_required_field():
    obj = _start_obj()
    del obj["git"]
    errors = gst.validate_fields(obj)
    assert any("missing required field: git" in e for e in errors)


def test_git_start_rejects_extra_field():
    obj = _start_obj(git={**START_KWARGS["git"], "head_sha": "def5678"})
    errors = gst.validate_fields(obj)
    assert any("head_sha" in e and "not permitted" in e for e in errors)


def test_git_start_requires_base_sha_and_branch():
    obj = _start_obj(git={})
    errors = gst.validate_fields(obj)
    assert any("git.base_sha is required" in e for e in errors)
    assert any("git.branch is required" in e for e in errors)


def test_git_base_sha_must_look_like_a_sha():
    obj = _start_obj(git={"base_sha": "not-hex!", "branch": "main"})
    errors = gst.validate_fields(obj)
    assert any("git.base_sha" in e for e in errors)


def test_git_branch_accepts_slashes():
    obj = _start_obj(git={"base_sha": "abc1234", "branch": "build/m8-3-foo"})
    assert gst.validate_fields(obj) == []


def test_git_close_requires_all_six_fields():
    obj = _close_obj(git={"base_sha": "abc1234", "branch": "main"})
    errors = gst.validate_fields(obj)
    for missing in ("head_sha", "pr_number", "merged", "merge_sha"):
        assert any(f"git.{missing} is required" in e for e in errors), errors


def test_git_close_merged_true_requires_merge_sha():
    obj = _close_obj(git={**CLOSE_KWARGS["git"], "merged": True, "merge_sha": None})
    errors = gst.validate_fields(obj)
    assert any("merge_sha is required" in e for e in errors)


def test_git_close_merged_false_forbids_merge_sha():
    obj = _close_obj(git={**CLOSE_KWARGS["git"], "merged": False, "merge_sha": "abc1234"})
    errors = gst.validate_fields(obj)
    assert any("merge_sha must be null" in e for e in errors)


def test_git_close_merged_true_with_merge_sha_is_valid():
    obj = _close_obj(git={**CLOSE_KWARGS["git"], "merged": True, "merge_sha": "1234567"})
    assert gst.validate_fields(obj) == []


def test_git_pr_number_must_be_positive_or_null():
    obj = _close_obj(git={**CLOSE_KWARGS["git"], "pr_number": 0})
    assert any("pr_number" in e for e in gst.validate_fields(obj))
    obj2 = _close_obj(git={**CLOSE_KWARGS["git"], "pr_number": None})
    assert gst.validate_fields(obj2) == []


# --------------------------------------------------------------------------
# Message-type-specific payload byte ceilings (SESSION_START 4096,
# SESSION_CLOSE 8192)
# --------------------------------------------------------------------------


def _object_bytes(obj: dict) -> int:
    return len(json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False).encode("utf-8"))


def _pad_to_exact_bytes(obj: dict, list_fields: list[str], target_bytes: int) -> dict:
    """Reach exactly `target_bytes` across one or more of `obj`'s bounded
    list fields (several are needed for the SESSION_CLOSE 8192-byte target:
    one field's 24-item/300-char-each capacity alone is not quite enough).

    Three passes: bulk-append max-length (300-char) items to each field
    while that fits (gets close fast, within each field's 24-item cap);
    then append minimal (1-char) items the same way (closes the remaining
    gap to less than one minimal item's own syntactic overhead); then grow
    one item's content one ASCII character at a time. Only that last step
    changes solely a string's length with no other JSON structure change,
    so it costs exactly one UTF-8 byte per character -- guaranteed, at that
    point, to pass through every remaining integer size up to the target
    with no jump. Appending a whole new array item is never assumed to cost
    1 byte -- it changes comma/newline/indentation/quote structure too."""
    obj = json.loads(json.dumps(obj))  # deep copy
    assert _object_bytes(obj) <= target_bytes, "starting object already exceeds the target"

    def try_append(field: str, item: str) -> bool:
        nonlocal obj
        if len(obj[field]) >= gst.MAX_LIST_ITEMS:
            return False
        candidate = {**obj, field: obj[field] + [item]}
        if _object_bytes(candidate) > target_bytes:
            return False
        obj = candidate
        return True

    for field in list_fields:
        while try_append(field, "x" * gst.MAX_LIST_ITEM_LEN):
            pass
    for field in list_fields:
        while try_append(field, "x"):
            pass

    while _object_bytes(obj) < target_bytes:
        grown = False
        for field in list_fields:
            if obj[field] and len(obj[field][-1]) < gst.MAX_LIST_ITEM_LEN:
                obj[field] = obj[field][:-1] + [obj[field][-1] + "x"]
                grown = True
                break
        assert grown, "ran out of room across all pad fields"

    assert _object_bytes(obj) == target_bytes
    return obj


_PAD_FIELDS = {
    "SESSION_START": ["authority_refs", "allow", "deny", "stop_on"],
    "SESSION_CLOSE": ["risks", "refs", "durable_state", "changed", "preserved"],
}


@pytest.mark.parametrize(
    "message_type, limit",
    [("SESSION_START", gst.PAYLOAD_BYTE_LIMITS["SESSION_START"]),
     ("SESSION_CLOSE", gst.PAYLOAD_BYTE_LIMITS["SESSION_CLOSE"])],
)
def test_type_specific_size_ceiling_exact_boundary_passes(message_type, limit):
    base = _start_obj() if message_type == "SESSION_START" else _close_obj()
    padded = _pad_to_exact_bytes(base, _PAD_FIELDS[message_type], limit)
    body = json.dumps(padded, sort_keys=True, indent=2, ensure_ascii=False)
    gst.check_type_specific_size(body, message_type)  # must not raise


@pytest.mark.parametrize(
    "message_type, limit",
    [("SESSION_START", gst.PAYLOAD_BYTE_LIMITS["SESSION_START"]),
     ("SESSION_CLOSE", gst.PAYLOAD_BYTE_LIMITS["SESSION_CLOSE"])],
)
def test_type_specific_size_ceiling_one_byte_over_fails(message_type, limit):
    base = _start_obj() if message_type == "SESSION_START" else _close_obj()
    padded = _pad_to_exact_bytes(base, _PAD_FIELDS[message_type], limit)
    body = json.dumps(padded, sort_keys=True, indent=2, ensure_ascii=False) + " "
    with pytest.raises(gst.PacketError, match=f"{message_type} payload exceeds"):
        gst.check_type_specific_size(body, message_type)


def test_session_start_ceiling_is_smaller_than_session_close():
    assert gst.PAYLOAD_BYTE_LIMITS["SESSION_START"] < gst.PAYLOAD_BYTE_LIMITS["SESSION_CLOSE"]
    assert gst.MAX_PAYLOAD_BYTES == gst.PAYLOAD_BYTE_LIMITS["SESSION_CLOSE"]


def test_render_packet_enforces_the_type_specific_ceiling():
    # authority_refs padded well past SESSION_START's own smaller ceiling.
    huge = _start_obj(authority_refs=["x" * gst.MAX_LIST_ITEM_LEN] * gst.MAX_LIST_ITEMS)
    with pytest.raises(gst.PacketError, match="SESSION_START payload exceeds"):
        gst.render_packet(huge)


# --------------------------------------------------------------------------
# CLI/schema parity (correction round 1 requirement)
# --------------------------------------------------------------------------


def _load_schema() -> dict:
    path = gst.REPO / "docs" / "reference" / "gov_session_transfer_packet.schema.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _branch_for(schema: dict, message_type: str) -> dict:
    return next(b for b in schema["oneOf"] if b["properties"]["message_type"]["const"] == message_type)


def test_schema_start_required_matches_cli():
    branch = _branch_for(_load_schema(), "SESSION_START")
    assert set(branch["required"]) == set(gst._START_REQUIRED)
    assert branch["additionalProperties"] is False


def test_schema_close_required_matches_cli():
    branch = _branch_for(_load_schema(), "SESSION_CLOSE")
    assert set(branch["required"]) == set(gst._CLOSE_REQUIRED)
    assert branch["additionalProperties"] is False


def test_schema_git_required_matches_cli():
    schema = _load_schema()
    assert set(schema["$defs"]["gitStart"]["required"]) == set(gst._GIT_START_FIELDS)
    assert set(schema["$defs"]["gitClose"]["required"]) == set(gst._GIT_CLOSE_FIELDS)
    assert schema["$defs"]["gitStart"]["additionalProperties"] is False
    assert schema["$defs"]["gitClose"]["additionalProperties"] is False


def test_schema_enums_match_cli():
    schema = _load_schema()
    assert set(schema["$defs"]["sameOrNew"]["enum"]) == set(gst.SAME_OR_NEW)
    assert set(schema["$defs"]["protocolVersion"]["enum"]) == set(gst.SUPPORTED_PROTOCOL_VERSIONS)
    close_branch = _branch_for(schema, "SESSION_CLOSE")
    assert set(close_branch["properties"]["outcome"]["enum"]) == set(gst.OUTCOMES)
    assert set(close_branch["properties"]["privacy_state"]["enum"]) == set(gst.PRIVACY_STATES)


def test_schema_list_and_text_bounds_match_cli():
    schema = _load_schema()
    assert schema["$defs"]["refList"]["maxItems"] == gst.MAX_LIST_ITEMS
    assert schema["$defs"]["refList"]["items"]["maxLength"] == gst.MAX_LIST_ITEM_LEN
    assert schema["$defs"]["shortId"]["maxLength"] == gst.MAX_ID_LEN
    assert schema["$defs"]["shortText"]["maxLength"] == gst.MAX_SHORT_TEXT
    assert schema["$defs"]["longText"]["maxLength"] == gst.MAX_LONG_TEXT
