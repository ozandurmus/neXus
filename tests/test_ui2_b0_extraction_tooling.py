"""UI2 B0/B0-9 -- extraction-tooling movement.

Proves docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md section 3's
fixture-generation procedure as implemented by scripts/ui2_extract_fixtures.py
and utils.support_bundle.Tokenizer.shaped_token (open item 1: a
shape-preserving encoding on top of the existing hex-token primitive), against
this movement's own AC-1..AC-10 (.nexus/approved_task.json).
"""
import json
import re
import socket

import pytest

from scripts import ui2_extract_fixtures as tool
from utils.support_bundle import Tokenizer


def _key() -> bytes:
    return b"test-fixture-hmac-key-not-a-secret"


# ---------------------------------------------------------------------------
# AC-1 / AC-2 / AC-3 / AC-4 -- Tokenizer.shaped_token itself
# ---------------------------------------------------------------------------

def test_ac1_ipv4_shaped_input_stays_ipv4_shaped():
    tok = Tokenizer(_key())
    out = tok.shaped_token("ip", "10.20.30.40")
    assert re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}", out)
    assert all(0 <= int(octet) <= 255 for octet in out.split("."))


def test_ac2_hex_serial_length_is_preserved():
    tok = Tokenizer(_key())
    for real_serial in ("A1B2C3D4E5F60789", "deadbeef", "1234"):
        out = tok.shaped_token("serial", real_serial)
        assert len(out) == len(real_serial)
        assert re.fullmatch(r"[0-9a-f]+", out)


def test_ac3_same_input_two_invocations_same_key_identical_pseudonym():
    value = "FW-GW-01-REAL"
    first = Tokenizer(_key()).shaped_token("serial", value)
    second = Tokenizer(_key()).shaped_token("serial", value)
    assert first == second

    # A different key changes the pseudonym -- proves it is not a fixed
    # transform of the value alone.
    third = Tokenizer(b"a-different-key").shaped_token("serial", value)
    assert third != first


def test_ac4_different_inputs_never_collide():
    tok = Tokenizer(_key())
    ips = [f"10.0.{i}.{i}" for i in range(50)]
    serials = [f"SN{i:012d}" for i in range(50)]
    ip_tokens = {tok.shaped_token("ip", v) for v in ips}
    serial_tokens = {tok.shaped_token("serial", v) for v in serials}
    assert len(ip_tokens) == len(ips)
    assert len(serial_tokens) == len(serials)


# ---------------------------------------------------------------------------
# Fixture-generation tool: AC-5..AC-8
# ---------------------------------------------------------------------------

def _write_capture(capture_dir, name, payload):
    capture_dir.mkdir(parents=True, exist_ok=True)
    (capture_dir / name).write_text(json.dumps(payload), encoding="utf-8")


def _extract(tmp_path, capture_dir, output_dir, monkeypatch, **overrides):
    key_file = tmp_path / "support_hmac.key"
    monkeypatch.delenv("FBUDDY_SUPPORT_HASH_KEY", raising=False)
    kwargs = dict(
        capability_id="cp_gaia_inventory_show_version_ha_state",
        command_tuple=["show version all"],
        vendor_version="R81.20",
        capture_date="2026-09-01",
        capture_session_id="S1",
        support_key_file=key_file,
    )
    kwargs.update(overrides)
    return tool.extract_fixtures(capture_dir, output_dir, **kwargs)


def test_ac5_dlp_gate_refuses_unsanitized_fixture(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {
        "hostname": "FW-REAL-01",
        # "notes" is not a recognized sensitive key by the tool's own
        # sanitizer, so this untokenized real-looking private endpoint
        # literal survives into the staged fixture -- the DLP gate, not the
        # sanitizer, is the mandatory backstop this test proves.
        "notes": "unreachable from 10.55.66.77",
    })

    with pytest.raises(tool.FixtureDLPRefusalError) as excinfo:
        _extract(tmp_path, capture_dir, output_dir, monkeypatch)

    assert excinfo.value.report.findings
    assert not output_dir.exists() or not list(output_dir.iterdir())


def test_ac6_fixture_carries_provenance_metadata(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {
        "hostname": "FW-REAL-01",
        "product_version": "R81.20",
    })

    written = _extract(tmp_path, capture_dir, output_dir, monkeypatch)
    assert len(written) == 1
    fixture = json.loads(written[0].read_text(encoding="utf-8"))

    assert fixture["source_capture_path"] == "show_version.json"
    assert fixture["capture_date"] == "2026-09-01"
    assert fixture["capability_id"] == "cp_gaia_inventory_show_version_ha_state"
    assert fixture["command_tuple"] == ["show version all"]
    assert fixture["capture_session_id"] == "S1"
    assert fixture["fixture_kind"] == "REAL"
    # No real identifier survives into the committed fixture.
    assert "FW-REAL-01" not in json.dumps(fixture)


def test_ac7_synthetic_derived_marking_is_explicit_only(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {"product_version": "R81.20"})

    default_written = _extract(tmp_path, capture_dir, output_dir, monkeypatch)
    default_fixture = json.loads(default_written[0].read_text(encoding="utf-8"))
    assert default_fixture["fixture_kind"] == "REAL"
    assert "synthetic_reason" not in default_fixture
    assert "derived_from" not in default_fixture

    derived_output_dir = tmp_path / "fixtures_derived"
    derived_written = _extract(
        tmp_path, capture_dir, derived_output_dir, monkeypatch,
        fixture_kind="DERIVED", derived_from="fixture-1", derived_change="product_version field only",
    )
    derived_fixture = json.loads(derived_written[0].read_text(encoding="utf-8"))
    assert derived_fixture["fixture_kind"] == "DERIVED"
    assert derived_fixture["derived_from"] == "fixture-1"
    assert derived_fixture["derived_change"] == "product_version field only"

    with pytest.raises(ValueError):
        _extract(tmp_path, capture_dir, tmp_path / "fixtures_bad", monkeypatch, fixture_kind="DERIVED")


def test_ac8_no_network_call_in_tool_source():
    import inspect
    source = inspect.getsource(tool)
    forbidden = ("socket", "requests", "urllib", "http.client", "ftplib", "smtplib", "paramiko")
    for token in forbidden:
        assert token not in source, f"unexpected network-shaped reference {token!r} in {tool.__file__}"


def test_ac8_credential_shaped_fields_are_excluded_not_tokenized(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {
        "product_version": "R81.20",
        "password": "hunter2-not-a-real-secret",
        "api_key": "synthetic-not-real-abcdef",
    })

    written = _extract(tmp_path, capture_dir, output_dir, monkeypatch)
    fixture_text = written[0].read_text(encoding="utf-8")
    assert "hunter2" not in fixture_text
    assert "synthetic-not-real-abcdef" not in fixture_text
    fixture = json.loads(fixture_text)
    assert fixture["payload"]["password"] is None
    assert fixture["payload"]["api_key"] is None


def test_ac8_tool_runs_offline_under_network_egress_denial(tmp_path, monkeypatch):
    def _deny(*args, **kwargs):
        raise AssertionError("scripts/ui2_extract_fixtures.py attempted a network socket")

    monkeypatch.setattr(socket, "socket", _deny)
    monkeypatch.setattr(socket, "create_connection", _deny)

    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {"product_version": "R81.20"})

    written = _extract(tmp_path, capture_dir, output_dir, monkeypatch)
    assert written and written[0].exists()


def test_ac3_two_separate_tool_invocations_same_key_material(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    _write_capture(capture_dir, "show_version.json", {"hostname": "FW-REAL-01"})

    out_a = tmp_path / "fixtures_a"
    out_b = tmp_path / "fixtures_b"
    written_a = _extract(tmp_path, capture_dir, out_a, monkeypatch)
    written_b = _extract(tmp_path, capture_dir, out_b, monkeypatch)

    doc_a = json.loads(written_a[0].read_text(encoding="utf-8"))
    doc_b = json.loads(written_b[0].read_text(encoding="utf-8"))
    assert doc_a["payload"]["hostname"] == doc_b["payload"]["hostname"]


def test_cross_output_identity_equality_within_one_session(tmp_path, monkeypatch):
    capture_dir = tmp_path / "capture"
    output_dir = tmp_path / "fixtures"
    _write_capture(capture_dir, "show_version.json", {"hostname": "FW-CLUSTER-01"})
    _write_capture(capture_dir, "cphaprob_stat.json", {"hostname": "FW-CLUSTER-01"})

    written = _extract(tmp_path, capture_dir, output_dir, monkeypatch,
                        command_tuple=["show version all", "cphaprob stat"])
    docs = [json.loads(p.read_text(encoding="utf-8")) for p in written]
    tokens = {doc["payload"]["hostname"] for doc in docs}
    assert len(tokens) == 1
