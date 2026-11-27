"""Unit tests for tools/delivery/event_trigger_fact_check.py.

Contract:
  docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md (§3)
Pure local tooling tests: synthetic values only, zero network or device access.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

import tools.delivery.event_trigger_fact_check as fc

pytestmark = pytest.mark.runtime_platform

REPO_ROOT = Path(__file__).resolve().parents[2]
CLI_PATH = REPO_ROOT / "tools" / "delivery" / "event_trigger_fact_check.py"


def _full_baseline(**overrides: str) -> str:
    """Return a complete, valid FC-1..FC-10 fact-check document with overrides."""
    values: dict[str, str] = {
        # FC-1
        "pre_siem_syslog_tier": "true",
        "collector_engine": "syslog-ng",
        "supports_tls_mutual_6514": "true",
        "supports_upstream_audit_filter": "true",
        "supports_per_input_emitter_key": "true",
        # FC-2
        "cp_op7_ingested": "true",
        "pan_config_commit_ingested": "true",
        "panorama_push_ingested": "true",
        "fgt_44546_44547_ingested": "true",
        "fmg_21023_21015_ingested": "true",
        "asa_111004_111005_ingested": "true",
        # FC-3
        "syslog_preserves_opaque_id": "true",
        "syslog_per_domain_input_possible": "true",
        "splunk_preserves_serial_or_devid": "true",
        "splunk_preserves_dg_id": "true",
        "splunk_only_host_dvc": "false",
        # FC-4
        "splunk_realtime_alerts_permitted": "true",
        "splunk_scheduled_min_interval_min": "na",
        "splunk_indexing_lag_p95_sec": "12",
        "splunk_https_443_egress_ok": "true",
        # FC-5
        "cp_r82_audit_only_filter_verified": "true",
        "cp_traffic_log_leak_count": "0",
        "cp_json_has_loguid": "true",
        "cp_op7_has_package_or_domain_field": "true",
        "cp_exporter_healthy_targets": "17/17",
        # FC-6
        "asa_total_count": "10",
        "asa_permit_hostdown_present_count": "10",
        "asa_direct_tcp_tls_safe": "true",
        "asa_mtls_supported": "UNKNOWN",
        # FC-7
        "pan_config_header_has_serial": "true",
        "panorama_commit_all_marker_observed": "true",
        "panorama_push_has_dg_id": "true",
        # FC-8
        "fgt_44547_has_devid": "true",
        "fgt_devid_serial_exact_match": "MATCH",
        "fgt_policy_cfgpath_prefixes_verified": "true",
        "fgt_mtls_client_cert_supported": "UNKNOWN",
        # FC-9
        "hosta_traefik_uses_prerouting_dnat": "true",
        "hosta_prerouting_negated_drop_required": "true",
        # FC-10
        "sender_emits_tls_sni": "true",
        "openshift_ingress_mode": "route_sni_passthrough",
        "sender_restart_buffer_verified": "true",
    }
    values.update(overrides)
    lines = ["# Synthetic 1-day fact-check fixture", ""]
    lines.extend(f"{k}={v}" for k, v in values.items())
    return "\n".join(lines) + "\n"


def test_select_1a_9a_takes_priority_over_1b():
    facts = fc.parse_fact_check_text(_full_baseline())
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "SELECT_1A_9A"
    assert result["q1"] == "Option 1A"
    assert result["q9"] == "9A"
    assert result["blocking_fc_ids"] == []
    assert result["reason_codes"] == []
    assert result["phase2_warnings"] == []


def test_select_1a_9a_via_per_domain_input_possible():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            syslog_preserves_opaque_id="false",
            syslog_per_domain_input_possible="true",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "SELECT_1A_9A"
    assert result["q1"] == "Option 1A"
    assert result["q9"] == "9A"


def test_select_1b_443_when_no_pre_siem_syslog_tier():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            collector_engine="splunk_uf_only",
            supports_tls_mutual_6514="false",
            supports_upstream_audit_filter="false",
            splunk_preserves_serial_or_devid="false",
            splunk_preserves_dg_id="true",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "SELECT_1B_443"
    assert result["q1"] == "Option 1B"
    assert result["q9"] == "443"
    assert result["blocking_fc_ids"] == []


def test_select_1b_blocked_to_hold_when_splunk_only_host_dvc():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            collector_engine="splunk_uf_only",
            splunk_only_host_dvc="true",
            splunk_realtime_alerts_permitted="true",
            cp_r82_audit_only_filter_verified="false",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "HOLD"
    assert result["q1"] is None
    assert result["q9"] is None
    assert "FC-3" in result["blocking_fc_ids"]
    assert "HOSTNAME_ONLY_IDENTITY" in result["reason_codes"]


def test_select_1c_9a_fallback_only_when_1a_and_1b_fail_and_audit_clean():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            splunk_realtime_alerts_permitted="false",
            cp_r82_audit_only_filter_verified="true",
            cp_traffic_log_leak_count="0",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "SELECT_1C_9A"
    assert result["q1"] == "Option 1C"
    assert result["q9"] == "9A"
    assert result["blocking_fc_ids"] == []


def test_hold_when_1c_has_nonzero_traffic_log_leak():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            cp_op7_ingested="false",
            cp_r82_audit_only_filter_verified="true",
            cp_traffic_log_leak_count="4",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "HOLD"
    assert "FC-5" in result["blocking_fc_ids"]
    assert "CP_TRAFFIC_LOG_LEAK_NONZERO" in result["reason_codes"]
    assert "CP_OP7_NOT_INGESTED" in result["reason_codes"]


def test_hold_on_missing_required_keys_fail_closed():
    facts = fc.parse_fact_check_text(
        "cp_r82_audit_only_filter_verified=true\ncp_traffic_log_leak_count=0\n"
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "HOLD"
    assert "MISSING_REQUIRED_KEYS" in result["reason_codes"]
    assert "FC-1" in result["blocking_fc_ids"]
    assert "pre_siem_syslog_tier" in result["missing_keys"]


def test_phase2_asa_warnings_do_not_block_phase1_selection():
    facts = fc.parse_fact_check_text(
        _full_baseline(
            asa_total_count="12",
            asa_permit_hostdown_present_count="8",
            asa_direct_tcp_tls_safe="false",
        )
    )
    result = fc.evaluate_gate(facts)
    assert result["decision"] == "SELECT_1A_9A"
    codes = {w["code"] for w in result["phase2_warnings"]}
    assert codes == {"ASA_DIRECT_TCP_TLS_UNSAFE", "ASA_PERMIT_HOSTDOWN_INCOMPLETE"}


@pytest.mark.parametrize(
    ("bad_line", "expected_key", "secret_fragment"),
    [
        ("bogus_unknown_key=secret_token_alpha", "bogus_unknown_key", "secret_token_alpha"),
        ("collector_engine=192.0.2.44", "collector_engine", "192.0.2.44"),
        ("collector_engine=fw-synthetic-gw01.example.invalid", "collector_engine", "fw-synthetic-gw01"),
        (
            "cp_traffic_log_leak_count=Jan 01 00:00:01 src=192.0.2.9 user=synthetic_op",
            "cp_traffic_log_leak_count",
            "synthetic_op",
        ),
        ("cp_exporter_healthy_targets=18/17", "cp_exporter_healthy_targets", "18/17"),
        ("splunk_scheduled_min_interval_min=-5", "splunk_scheduled_min_interval_min", "-5"),
        ("fgt_devid_serial_exact_match=PARTIAL", "fgt_devid_serial_exact_match", "PARTIAL"),
    ],
)
def test_privacy_parser_rejects_bad_keys_and_values_without_echoing(
    bad_line: str, expected_key: str, secret_fragment: str
):
    doc = "# comment\n\npre_siem_syslog_tier=true\n" + bad_line + "\n"
    with pytest.raises(fc.FactCheckParseError) as exc_info:
        fc.parse_fact_check_text(doc)
    msg = str(exc_info.value)
    assert "line 4" in msg
    assert expected_key in msg
    assert secret_fragment not in msg


def test_privacy_parser_rejects_duplicate_key_without_echoing_value():
    doc = "pre_siem_syslog_tier=true\npre_siem_syslog_tier=false\n"
    with pytest.raises(fc.FactCheckParseError) as exc_info:
        fc.parse_fact_check_text(doc)
    msg = str(exc_info.value)
    assert "line 2" in msg
    assert "pre_siem_syslog_tier" in msg
    assert "false" not in msg


def test_privacy_parser_rejects_line_without_equals_without_echoing_line():
    raw_log = "raw syslog payload fw-synthetic-02 denied packet"
    with pytest.raises(fc.FactCheckParseError) as exc_info:
        fc.parse_fact_check_text(f"pre_siem_syslog_tier=true\n{raw_log}\n")
    msg = str(exc_info.value)
    assert "line 2" in msg
    assert "fw-synthetic-02" not in msg
    assert "raw syslog" not in msg


def test_exporter_targets_valid_boundary():
    facts = fc.parse_fact_check_text("cp_exporter_healthy_targets=0/17\n")
    assert facts["cp_exporter_healthy_targets"] == "0/17"
    facts_equal = fc.parse_fact_check_text("cp_exporter_healthy_targets=17/17\n")
    assert facts_equal["cp_exporter_healthy_targets"] == "17/17"


def test_cli_exit_codes_and_json_markdown_outputs(tmp_path: Path):
    select_file = tmp_path / "select_1b.txt"
    select_file.write_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            asa_direct_tcp_tls_safe="false",
        ),
        encoding="utf-8",
    )
    proc_json = subprocess.run(
        [sys.executable, str(CLI_PATH), "--input", str(select_file), "--format", "json"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_json.returncode == 0
    payload = json.loads(proc_json.stdout)
    assert payload["decision"] == "SELECT_1B_443"
    assert payload["q1"] == "Option 1B"
    assert payload["q9"] == "443"
    assert payload["phase2_warnings"][0]["code"] == "ASA_DIRECT_TCP_TLS_UNSAFE"

    proc_md = subprocess.run(
        [sys.executable, str(CLI_PATH), "--input", str(select_file), "--format", "markdown"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_md.returncode == 0
    assert "| Decision | `SELECT_1B_443` |" in proc_md.stdout
    assert "### Phase 2 Informational Warnings" in proc_md.stdout
    assert "| `FC-1` | `pre_siem_syslog_tier` | `false` |" in proc_md.stdout

    hold_file = tmp_path / "hold.txt"
    hold_file.write_text(
        _full_baseline(
            pre_siem_syslog_tier="false",
            splunk_only_host_dvc="true",
            cp_traffic_log_leak_count="2",
        ),
        encoding="utf-8",
    )
    proc_hold = subprocess.run(
        [sys.executable, str(CLI_PATH), "--input", str(hold_file), "--format", "json"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_hold.returncode == 2
    hold_payload = json.loads(proc_hold.stdout)
    assert hold_payload["decision"] == "HOLD"
    assert "HOSTNAME_ONLY_IDENTITY" in hold_payload["reason_codes"]
    assert "CP_TRAFFIC_LOG_LEAK_NONZERO" in hold_payload["reason_codes"]

    bad_file = tmp_path / "invalid.txt"
    bad_file.write_text("collector_engine=192.0.2.77\n", encoding="utf-8")
    proc_err = subprocess.run(
        [sys.executable, str(CLI_PATH), "--input", str(bad_file), "--format", "json"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc_err.returncode == 1
    assert proc_err.stdout == ""
    assert "collector_engine" in proc_err.stderr
    assert "line 1" in proc_err.stderr
    assert "192.0.2.77" not in proc_err.stderr
