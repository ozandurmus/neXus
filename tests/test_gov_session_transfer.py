"""GOV.SESSION.1 -- tests for scripts/gov_session_transfer.py (protocol v2).

Pure local tooling: no network, no device, no vendor collector touched.
Contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md.
"""
from __future__ import annotations

import copy
import json

import pytest

import scripts.gov_session_transfer as gst


def _start_report(**overrides) -> dict:
    report = {
        "baseline": {"origin_main": "3fd424d0753e63ebca44d0fca9f4805d102e5349"},
        "objective": "Unify the session-transfer packet schema.",
        "scope": {"in": ["do the thing"], "out": ["not that thing"]},
        "movement_type": "IMPLEMENTATION",
        "requirements": ["exactly one schema"],
        "acceptance_criteria": ["round-trips cleanly"],
        "validation_plan": ["run the focused suite"],
        "invariants": ["repository stays authoritative"],
        "risks": [],
        "context_not_loaded": [],
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "git": {"lane": "fresh governance branch", "base": "origin/main at 3fd424d"},
        "merge_gate": "open a PR, do not merge",
        "deployment_direction": "local validation only",
        "output_contract": ["exactly one packet"],
    }
    report.update(overrides)
    return report


def _close_report(**overrides) -> dict:
    report = {
        "completed": ["did the thing"],
        "changed": ["the schema"],
        "preserved": ["M7 stays blocked"],
        "validation": {
            "targeted": "30 passed",
            "affected": "136 passed",
            "full_regression": "not run, risk-based",
            "privacy": "PASS/0",
            "state_consistency": "0 warnings",
            "diff_check": "clean",
            "real_environment": "NOT_RUN",
        },
        "unresolved_risks": ["m8_evidence_host_key_fingerprint_not_persisted remains open"],
        "state_updates": ["build_history.json amended"],
        "next": {
            "movement": "m8_evidence_host_key_fingerprint_not_persisted",
            "movement_type": "IMPLEMENTATION",
            "status": "planned",
            "objective": "persist the captured fingerprint",
        },
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "continuation": "SAME_SESSION",
        "integration": {
            "branch": "governance/gov-session-1-unified-packet",
            "head_sha": "890a0dd61c8c8e2444219e2fe30b803819610917",
            "pr": 102,
            "pr_url": "https://github.com/ozandurmus/neXus/pull/102",
            "ci": "validate: pass; full-regression: skipped",
            "merge_state": "OPEN",
            "merge_commit": None,
            "merge_decision": "BLOCKED_PENDING_PO_REVIEW",
        },
        "effects": {"main_py": "no new effect", "ui": "no new effect"},
    }
    report.update(overrides)
    return report


def _start_obj(**overrides) -> dict:
    obj = {
        "protocol_version": 2,
        "message_type": "SESSION_START",
        "movement": "GOV.SESSION.1A",
        "refs": ["AGENTS.md"],
        "report": _start_report(),
    }
    obj.update(overrides)
    return obj


def _close_obj(**overrides) -> dict:
    obj = {
        "protocol_version": 2,
        "message_type": "SESSION_CLOSE",
        "movement": "GOV.SESSION.1A",
        "refs": ["AGENTS.md"],
        "outcome": "AUTOMATED_VALIDATED",
        "report": _close_report(),
    }
    obj.update(overrides)
    return obj


def _wrap(payload_text: str) -> str:
    return f"{gst.SENTINEL}\n{payload_text}\n{gst.SENTINEL}\n"


def _set_path(obj: dict, dotted: str, value) -> dict:
    """Deep-copy `obj` and set a dotted-path field to `value`."""
    result = copy.deepcopy(obj)
    node = result
    parts = dotted.split(".")
    for part in parts[:-1]:
        node = node[part]
    node[parts[-1]] = value
    return result


def _del_path(obj: dict, dotted: str) -> dict:
    result = copy.deepcopy(obj)
    node = result
    parts = dotted.split(".")
    for part in parts[:-1]:
        node = node[part]
    del node[parts[-1]]
    return result


# --- Field validation: envelope-level ------------------------------------
def test_valid_session_start_has_no_errors():
    assert gst.validate_fields(_start_obj()) == []


def test_valid_session_close_has_no_errors():
    assert gst.validate_fields(_close_obj()) == []


def test_non_object_payload_is_rejected():
    assert gst.validate_fields([1, 2, 3]) == ["packet must be a JSON object"]


@pytest.mark.parametrize("bad_version", [1, 3, 0, -1, "2", True, False])
def test_unsupported_protocol_version_fails_closed(bad_version):
    errors = gst.validate_fields(_start_obj(protocol_version=bad_version))
    assert any("protocol_version" in e for e in errors)


def test_pointer_only_version_1_packet_is_rejected_as_non_canonical():
    """The old, pre-migration pointer-only shape is not merely incomplete --
    its own protocol_version is now unsupported."""
    legacy = {"protocol_version": 1, "message_type": "SESSION_CLOSE", "movement": "M8.3",
              "outcome": "AUTOMATED_VALIDATED", "refs": ["project/build_history.json"]}
    errors = gst.validate_fields(legacy)
    assert any("protocol_version" in e for e in errors)
    assert any("report" in e for e in errors)


def test_unsupported_message_type_is_rejected():
    errors = gst.validate_fields(_start_obj(message_type="SESSION_PAUSE"))
    assert any("message_type" in e for e in errors)


def test_missing_movement_is_rejected():
    assert any("movement" in e for e in gst.validate_fields(_del_path(_start_obj(), "movement")))


def test_empty_movement_is_rejected():
    assert any("movement" in e for e in gst.validate_fields(_start_obj(movement="")))


def test_missing_refs_is_rejected():
    assert any("refs" in e for e in gst.validate_fields(_del_path(_start_obj(), "refs")))


@pytest.mark.parametrize("bad_refs", ["AGENTS.md", [""], [1]])
def test_refs_must_be_a_list_of_non_empty_strings(bad_refs):
    assert any("refs" in e for e in gst.validate_fields(_start_obj(refs=bad_refs)))


def test_close_requires_outcome():
    assert any("outcome" in e for e in gst.validate_fields(_del_path(_close_obj(), "outcome")))


def test_close_outcome_is_closed_vocabulary():
    assert any("outcome" in e for e in gst.validate_fields(_close_obj(outcome="MAYBE")))


def test_session_start_carrying_outcome_is_rejected():
    obj = _start_obj()
    obj["outcome"] = "AUTOMATED_VALIDATED"
    errors = gst.validate_fields(obj)
    assert any("outcome" in e for e in errors)


def test_unknown_top_level_field_is_rejected():
    obj = _start_obj()
    obj["extra_field"] = "not part of the schema"
    errors = gst.validate_fields(obj)
    assert any("extra_field" in e for e in errors)


def test_missing_report_is_rejected():
    assert any("report" in e for e in gst.validate_fields(_del_path(_start_obj(), "report")))


# --- Field validation: nested report schema (SESSION_START) ---------------
@pytest.mark.parametrize("dotted", [
    "report.baseline", "report.objective", "report.scope", "report.scope.in",
    "report.scope.out", "report.movement_type", "report.requirements",
    "report.acceptance_criteria", "report.validation_plan", "report.invariants",
    "report.risks", "report.context_not_loaded", "report.recommended_reasoning",
    "report.recommended_reasoning.tier", "report.recommended_reasoning.reason",
    "report.git", "report.git.lane", "report.git.base", "report.merge_gate",
    "report.deployment_direction", "report.output_contract",
])
def test_session_start_missing_required_nested_field_is_rejected(dotted):
    errors = gst.validate_fields(_del_path(_start_obj(), dotted))
    assert errors, f"expected an error for missing {dotted}"


def test_session_start_empty_objective_is_rejected():
    errors = gst.validate_fields(_set_path(_start_obj(), "report.objective", ""))
    assert any("objective" in e for e in errors)


def test_session_start_empty_required_list_is_rejected():
    errors = gst.validate_fields(_set_path(_start_obj(), "report.scope.in", []))
    assert any("scope.in" in e for e in errors)


def test_session_start_risks_may_be_an_empty_list():
    assert gst.validate_fields(_set_path(_start_obj(), "report.risks", [])) == []


def test_session_start_unknown_movement_type_is_rejected():
    errors = gst.validate_fields(_set_path(_start_obj(), "report.movement_type", "REFACTOR"))
    assert any("movement_type" in e for e in errors)


def test_session_start_unknown_deployment_direction_is_rejected():
    errors = gst.validate_fields(_set_path(_start_obj(), "report.deployment_direction", "yolo"))
    assert any("deployment_direction" in e for e in errors)


def test_session_start_unknown_nested_field_is_rejected():
    obj = _start_obj()
    obj["report"]["surprise"] = "not in the schema"
    errors = gst.validate_fields(obj)
    assert any("surprise" in e for e in errors)


def test_session_start_unknown_field_inside_scope_is_rejected():
    obj = _start_obj()
    obj["report"]["scope"]["sideways"] = ["nope"]
    errors = gst.validate_fields(obj)
    assert any("sideways" in e for e in errors)


# --- Field validation: nested report schema (SESSION_CLOSE) ---------------
@pytest.mark.parametrize("dotted", [
    "report.completed", "report.changed", "report.preserved",
    "report.validation", "report.validation.targeted", "report.validation.affected",
    "report.validation.full_regression", "report.validation.privacy",
    "report.validation.state_consistency", "report.validation.diff_check",
    "report.validation.real_environment", "report.unresolved_risks",
    "report.state_updates", "report.next", "report.next.movement",
    "report.next.movement_type", "report.next.status", "report.next.objective",
    "report.recommended_reasoning", "report.continuation", "report.integration",
    "report.integration.branch", "report.integration.head_sha", "report.integration.pr",
    "report.integration.pr_url", "report.integration.ci", "report.integration.merge_state",
    "report.integration.merge_commit", "report.integration.merge_decision",
    "report.effects", "report.effects.main_py", "report.effects.ui",
])
def test_session_close_missing_required_nested_field_is_rejected(dotted):
    errors = gst.validate_fields(_del_path(_close_obj(), dotted))
    assert errors, f"expected an error for missing {dotted}"


def test_session_close_unresolved_risks_and_state_updates_may_be_empty():
    obj = _set_path(_close_obj(), "report.unresolved_risks", [])
    obj = _set_path(obj, "report.state_updates", [])
    assert gst.validate_fields(obj) == []


def test_session_close_integration_pr_accepts_null():
    obj = _set_path(_close_obj(), "report.integration.pr", None)
    obj = _set_path(obj, "report.integration.pr_url", None)
    obj = _set_path(obj, "report.integration.merge_commit", None)
    assert gst.validate_fields(obj) == []


def test_session_close_integration_pr_rejects_non_int():
    errors = gst.validate_fields(_set_path(_close_obj(), "report.integration.pr", "102"))
    assert any("integration.pr" in e for e in errors)


def test_session_close_integration_merge_state_is_closed_vocabulary():
    errors = gst.validate_fields(_set_path(_close_obj(), "report.integration.merge_state", "SORT_OF"))
    assert any("merge_state" in e for e in errors)


def test_session_close_next_status_is_closed_vocabulary():
    errors = gst.validate_fields(_set_path(_close_obj(), "report.next.status", "someday"))
    assert any("next.status" in e for e in errors)


def test_session_close_continuation_is_closed_vocabulary():
    errors = gst.validate_fields(_set_path(_close_obj(), "report.continuation", "MAYBE_LATER"))
    assert any("continuation" in e for e in errors)


def test_session_close_unknown_nested_field_in_integration_is_rejected():
    obj = _close_obj()
    obj["report"]["integration"]["extra"] = "nope"
    errors = gst.validate_fields(obj)
    assert any("extra" in e for e in errors)


# --- Truncation: malformed vs. syntactically-valid-but-incomplete --------
def test_the_exact_observed_unterminated_string_truncation_is_rejected():
    """A real truncation this protocol must reject: valid JSON up to a
    string value that never closes, ending mid-word ("...M8.3 real-)."""
    truncated = '{"protocol_version": 2, "message_type": "SESSION_CLOSE", ' \
                '"report": {"completed": ["M8.3 real-'
    obj, errors = gst.parse_and_validate(truncated)
    assert obj is None
    assert any("invalid JSON" in e for e in errors)


def test_syntactically_valid_truncation_missing_trailing_fields_is_rejected():
    """Valid JSON that simply stops early -- e.g. an object closed before
    its mandatory trailing fields were ever written -- is structurally
    rejected, not merely a JSON-parse failure."""
    complete = _close_obj()
    truncated = copy.deepcopy(complete)
    del truncated["report"]["effects"]
    del truncated["report"]["integration"]
    obj, errors = gst.parse_and_validate(json.dumps(truncated))
    assert obj is None
    assert any("effects" in e for e in errors)
    assert any("integration" in e for e in errors)


# --- extract_one: envelope handling -------------------------------------
# GOV.SESSION.1A correction round 1: the envelope is now strict, symmetric
# transport -- optional leading/trailing whitespace only, never surrounding
# narrative, headings, chat history, or another payload.
def test_extract_one_finds_the_payload():
    payload = json.dumps(_start_obj())
    assert gst.extract_one(_wrap(payload)) == payload


def test_extract_one_accepts_crlf_line_endings():
    text = _wrap(json.dumps(_start_obj())).replace("\n", "\r\n")
    assert json.loads(gst.extract_one(text)) == _start_obj()


def test_extract_one_accepts_whitespace_only_surroundings():
    text = f"\n  \n{_wrap(json.dumps(_start_obj()))}\n   \n"
    assert json.loads(gst.extract_one(text)) == _start_obj()


def test_extract_one_rejects_leading_prose():
    text = f"chat before\n{_wrap(json.dumps(_start_obj()))}"
    with pytest.raises(gst.PacketError, match="before the opening sentinel"):
        gst.extract_one(text)


def test_extract_one_rejects_trailing_prose():
    text = f"{_wrap(json.dumps(_start_obj()))}chat after"
    with pytest.raises(gst.PacketError, match="after the closing sentinel"):
        gst.extract_one(text)


def test_extract_one_rejects_a_heading_before_the_packet():
    """A split narrative-plus-packet handoff -- prose/headings ahead of an
    otherwise-valid packet -- must never pass as valid transport."""
    text = f"## SESSION CLOSE\n\nHere is the summary.\n\n{_wrap(json.dumps(_close_obj()))}"
    with pytest.raises(gst.PacketError, match="before the opening sentinel"):
        gst.extract_one(text)


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


def test_extract_one_rejects_a_whitespace_altered_sentinel_line_as_leading_prose():
    # Not an exact sentinel match -- so it is not a boundary, but it is also
    # not whitespace, so it is now rejected as leading prose (correction
    # round 1), not silently skipped over as it was under the old rule.
    text = f" {gst.SENTINEL} \n{_wrap(json.dumps(_start_obj()))}"
    with pytest.raises(gst.PacketError, match="before the opening sentinel"):
        gst.extract_one(text)


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
    with pytest.raises(gst.PacketError):
        gst.render(_del_path(_start_obj(), "movement"))


def test_render_refuses_a_report_missing_mandatory_fields():
    with pytest.raises(gst.PacketError):
        gst.render(_del_path(_close_obj(), "report.integration"))


def test_render_round_trips_through_extract_one():
    rendered = gst.render(_close_obj())
    assert json.loads(gst.extract_one(rendered)) == _close_obj()


def test_render_full_session_start_round_trips_byte_semantically():
    rendered = gst.render(_start_obj())
    extracted = json.loads(gst.extract_one(rendered))
    assert extracted == _start_obj()
    assert extracted["report"]["scope"]["in"] == _start_obj()["report"]["scope"]["in"]


def test_render_full_session_close_round_trips_byte_semantically():
    rendered = gst.render(_close_obj())
    extracted = json.loads(gst.extract_one(rendered))
    assert extracted == _close_obj()
    assert extracted["report"]["integration"] == _close_obj()["report"]["integration"]


# --- CLI -----------------------------------------------------------------
def test_cli_render_then_validate(tmp_path, capsys):
    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps(_close_obj()), encoding="utf-8")
    out_file = tmp_path / "close.json"
    rc = gst.main(["render", str(bare), "--out", str(out_file)])
    assert rc == gst.EXIT_OK
    assert out_file.exists()
    rc = gst.main(["validate", str(out_file)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(captured.out) == {"valid": True, "errors": []}


def test_cli_render_prints_nothing_on_stdout_when_invalid(tmp_path, capsys):
    bare = tmp_path / "bare.json"
    bare.write_text(json.dumps(_del_path(_start_obj(), "report")), encoding="utf-8")
    rc = gst.main(["render", str(bare)])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_INVALID
    assert captured.out == ""


def test_cli_render_rejects_malformed_json(tmp_path):
    bare = tmp_path / "bare.json"
    bare.write_text("{not json", encoding="utf-8")
    assert gst.main(["render", str(bare)]) == gst.EXIT_INVALID


def test_cli_render_usage_error_for_missing_file():
    assert gst.main(["render", "does-not-exist.json"]) == gst.EXIT_USAGE


def test_cli_render_reads_stdin_when_file_is_dash(monkeypatch, capsys):
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps(_start_obj())))
    rc = gst.main(["render", "-"])
    captured = capsys.readouterr()
    assert rc == gst.EXIT_OK
    assert json.loads(gst.extract_one(captured.out)) == _start_obj()


def test_cli_has_no_start_or_close_subcommand():
    """Protocol v1's pointer-only flag-driven `start`/`close` subcommands
    must not exist -- there is no path in this CLI that can produce a
    report-less packet."""
    with pytest.raises(SystemExit):
        gst.main(["start", "--movement", "X", "--ref", "AGENTS.md"])
    with pytest.raises(SystemExit):
        gst.main(["close", "--movement", "X", "--outcome", "DONE", "--ref", "AGENTS.md"])


def test_cli_validate_reports_invalid_content(tmp_path, capsys):
    bad = tmp_path / "bad.json"
    bad.write_text(_wrap(json.dumps(_del_path(_start_obj(), "movement"))), encoding="utf-8")
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
