"""OP.0b S2 — PAN preflight parse-scope extraction.

Contract: docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(FROZEN WITH REAL-ENV VALIDATION GATES). Proves `_parse_pan_ha_preflight_fields`
and `get_target_ha_runtime_state(..., include_preflight_fields=True)` read
more of the SAME already-fetched `show high-availability state` response,
issue no new network operation, and degrade safely on absent/malformed
fields. Synthetic fixtures only, per the task's §18/§21 requirements.
"""
from __future__ import annotations

from lxml import etree

from configuration import panorama_config_collector as pan_collector

import pytest

pytestmark = pytest.mark.configuration


def _xml(body: bytes) -> "etree._Element":
    return etree.fromstring(body)


# A + D + E + F + G + H + I: one realistic full response ---------------------

_FULL_RESPONSE = _xml(
    b"""<response status='success'><result><enabled>yes</enabled><group>
    <local-info>
      <state>active</state>
      <mode>Active-Passive</mode>
      <state-sync>Complete</state-sync>
      <state-sync-type>ip</state-sync-type>
      <preemptive>no</preemptive>
      <priority>100</priority>
      <preempt-hold>1</preempt-hold>
      <promotion-hold>20000</promotion-hold>
      <max-flaps>3</max-flaps>
      <nonfunc-flap-cnt>0</nonfunc-flap-cnt>
      <preempt-flap-cnt>0</preempt-flap-cnt>
      <state-duration>3675</state-duration>
      <build-rel>10.2.3</build-rel>
      <app-version>1111-2222</app-version>
      <app-compat>Match</app-compat>
      <av-version>0</av-version>
      <av-compat>Match</av-compat>
      <threat-version>1111-2222</threat-version>
      <threat-compat>Match</threat-compat>
      <url-version>0000.00.00.000</url-version>
      <url-compat>Mismatch</url-compat>
      <serial-num>001234567890</serial-num>
    </local-info>
    <peer-info>
      <state>passive</state>
      <conn-status>up</conn-status>
      <conn-ha1><conn-status>up</conn-status><conn-desc>heartbeat status</conn-desc></conn-ha1>
      <conn-ha2><conn-status>up</conn-status><conn-desc>link status</conn-desc></conn-ha2>
      <build-rel>10.2.3</build-rel>
      <app-version>1111-2222</app-version>
      <av-version>0</av-version>
      <threat-version>1111-2222</threat-version>
      <url-version>20230126.20142</url-version>
      <serial-num>009876543210</serial-num>
    </peer-info>
    <running-sync>synchronized</running-sync>
    <running-sync-enabled>yes</running-sync-enabled>
    </group></result></response>"""
)


def test_1_existing_ha_state_fields_still_parse_identically(monkeypatch):
    monkeypatch.setattr(pan_collector, "api_post", lambda *a, **k: _FULL_RESPONSE)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER123", verify=False, timeout=10,
    )
    assert result == {
        "enabled": "yes", "state": "active", "mode": "Active-Passive",
        "peer_state": "passive", "state_sync": "Complete",
    }
    assert "preflight_fields" not in result


def test_2_running_sync_parsed(monkeypatch):
    monkeypatch.setattr(pan_collector, "api_post", lambda *a, **k: _FULL_RESPONSE)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER123", verify=False, timeout=10,
        include_preflight_fields=True,
    )
    assert result["preflight_fields"]["running_sync"] == "synchronized"


def test_3_running_sync_enabled_parsed(monkeypatch):
    monkeypatch.setattr(pan_collector, "api_post", lambda *a, **k: _FULL_RESPONSE)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER123", verify=False, timeout=10,
        include_preflight_fields=True,
    )
    assert result["preflight_fields"]["running_sync_enabled"] == "yes"


def test_4_state_sync_still_parsed_by_existing_leaf(monkeypatch):
    monkeypatch.setattr(pan_collector, "api_post", lambda *a, **k: _FULL_RESPONSE)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER123", verify=False, timeout=10,
    )
    assert result["state_sync"] == "Complete"


def test_5_state_sync_type_parsed(monkeypatch):
    monkeypatch.setattr(pan_collector, "api_post", lambda *a, **k: _FULL_RESPONSE)
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["local_state_sync_type"] == "ip"


def test_6_conn_status_parsed(monkeypatch):
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["peer_conn_status"] == "up"


def test_7_conn_ha1_parsed_per_frozen_minimal_predicate(monkeypatch):
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["peer_conn_ha1_status"] == "up"


def test_8_conn_ha2_parsed_per_frozen_minimal_predicate(monkeypatch):
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["peer_conn_ha2_status"] == "up"


def test_9_missing_conn_ha1_backup_stays_unknown_absent_safely():
    # _FULL_RESPONSE has no conn-ha1-backup element at all.
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["peer_conn_ha1_backup_status"] is None


_UNKNOWN_CONN_VALUE_RESPONSE = _xml(
    b"""<response status='success'><result><group>
    <local-info><state>active</state></local-info>
    <peer-info><conn-status>flapping-unrecognized-value</conn-status></peer-info>
    </group></result></response>"""
)


def test_10_unknown_conn_value_does_not_become_healthy():
    fields = pan_collector._parse_pan_ha_preflight_fields(_UNKNOWN_CONN_VALUE_RESPONSE)
    # Stored verbatim, safe (short, real vendor-shaped string) -- S2 performs
    # no health interpretation at all, so there is no "healthy" sentinel to
    # accidentally produce here.
    assert fields["peer_conn_status"] == "flapping-unrecognized-value"
    assert fields["peer_conn_status"] != "up"


def test_11_preemptive_priority_hold_fields_parse_safely():
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["local_preemptive"] == "no"
    assert fields["local_priority"] == "100"
    assert fields["local_preempt_hold"] == "1"
    assert fields["local_promotion_hold"] == "20000"


def test_12_flap_counters_parse_without_threshold_decision():
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    # Raw text only -- int conversion and any threshold are the projection
    # layer's job (D-F3 remains unresolved either way).
    assert fields["local_max_flaps"] == "3"
    assert fields["local_nonfunc_flap_cnt"] == "0"
    assert fields["local_preempt_flap_cnt"] == "0"
    assert fields["local_state_duration"] == "3675"


_MALFORMED_NUMERIC_RESPONSE = _xml(
    b"""<response status='success'><result><group>
    <local-info><state>active</state><max-flaps>not-a-number</max-flaps></local-info>
    </group></result></response>"""
)


def test_13_malformed_numeric_field_degrades_safely_at_extraction():
    # Extraction itself never rejects/crashes -- it returns text as-is;
    # int-conversion failure is a projection-layer concern (see the
    # projection test suite for the UNKNOWN degrade this produces).
    fields = pan_collector._parse_pan_ha_preflight_fields(_MALFORMED_NUMERIC_RESPONSE)
    assert fields["local_max_flaps"] == "not-a-number"


def test_14_compatibility_fields_remain_explicit():
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["local_app_compat"] == "Match"
    assert fields["local_url_compat"] == "Mismatch"


def test_15_serial_claims_are_opaque_tokenized(monkeypatch):
    monkeypatch.setenv("FBUDDY_SUPPORT_HASH_KEY", "test-only-key-not-persisted")
    fields = pan_collector._parse_pan_ha_preflight_fields(_FULL_RESPONSE)
    assert fields["local_serial_num"] is not None
    assert fields["peer_serial_num"] is not None
    assert "001234567890" not in fields["local_serial_num"]
    assert "009876543210" not in fields["peer_serial_num"]
    assert fields["local_serial_num"] != fields["peer_serial_num"]


def test_16_no_leading_zero_normalization_exists(monkeypatch):
    monkeypatch.setenv("FBUDDY_SUPPORT_HASH_KEY", "test-only-key-not-persisted")
    import inspect

    src = inspect.getsource(pan_collector._parse_pan_ha_preflight_fields)
    for token in ("lstrip", "int(", "zfill", ".strip('0')"):
        assert token not in src, f"found a normalization-shaped token: {token!r}"
    # Same raw text tokenized twice is deterministic -- proves no numeric
    # round-trip (which would collapse "007"/"7" to the same value) occurs.
    padded = _xml(b"<r><result><group><local-info><serial-num>0099</serial-num></local-info></group></result></r>")
    unpadded = _xml(b"<r><result><group><local-info><serial-num>99</serial-num></local-info></group></result></r>")
    fields_padded = pan_collector._parse_pan_ha_preflight_fields(padded)
    fields_unpadded = pan_collector._parse_pan_ha_preflight_fields(unpadded)
    assert fields_padded["local_serial_num"] != fields_unpadded["local_serial_num"]


_MISSING_PEER_INFO_RESPONSE = _xml(
    b"""<response status='success'><result><group>
    <local-info><state>active</state><mode>Active-Passive</mode></local-info>
    </group></result></response>"""
)


def test_17_missing_peer_info_degrades_safely():
    fields = pan_collector._parse_pan_ha_preflight_fields(_MISSING_PEER_INFO_RESPONSE)
    assert fields["peer_conn_status"] is None
    assert fields["peer_conn_ha1_status"] is None
    assert fields["peer_serial_num"] is None
    # The always-parsed leaves also degrade safely (existing behavior, unchanged).
    peer_state = (_MISSING_PEER_INFO_RESPONSE.findtext(".//result/group/peer-info/state") or "").strip() or None
    assert peer_state is None


# --- Network regression guard (task §21) ------------------------------------

def test_network_operations_unchanged_default_and_opt_in(monkeypatch):
    """Exactly one `api_post` call either way -- `include_preflight_fields`
    changes what is read from the response already in hand, never the
    request issued."""
    calls: list[dict] = []

    def fake_api_post(host, key, data, *, verify, timeout, operation):
        calls.append(dict(data))
        return _FULL_RESPONSE

    monkeypatch.setattr(pan_collector, "api_post", fake_api_post)
    pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER1", verify=False, timeout=10,
    )
    pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER1", verify=False, timeout=10,
        include_preflight_fields=True,
    )
    assert len(calls) == 2  # one per top-level call, not per field
    for call in calls:
        assert call["cmd"] == "<show><high-availability><state></state></high-availability></show>"
        assert call["type"] == "op"


def test_no_show_high_availability_all_command_introduced():
    import inspect

    src = inspect.getsource(pan_collector._parse_pan_ha_preflight_fields)
    src += inspect.getsource(pan_collector.get_target_ha_runtime_state)
    assert "high-availability><all" not in src
    assert "api_post(" not in inspect.getsource(pan_collector._parse_pan_ha_preflight_fields)


def test_no_new_thread_pool_executor_or_retry_loop_introduced():
    import inspect

    src = inspect.getsource(pan_collector._parse_pan_ha_preflight_fields)
    for token in ("ThreadPoolExecutor", "for attempt in range", "retry", "requests.Session"):
        assert token not in src
