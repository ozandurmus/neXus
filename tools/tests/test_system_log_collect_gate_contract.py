"""Offline check: V160, the fixture and the notification amendment carry the 13 PO-approved system-log collection
commands exactly as DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 10 writes them (command text byte-identical)."""
import json
import re
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md"
MIGRATIONS = ROOT / "ui2/service/src/main/resources/db/migration"
V160 = MIGRATIONS / "V160__system_log_phase_b.sql"
FIXTURE = ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml"
NOTIFICATION = ROOT / "docs/design/NOTIFICATION_ROUTING_CONTRACT.md"

# Rows whose command text equals an approved measurement row: C4 section 3.3 step 6 forbids two rows with one key, so the
# key is narrowed by shell_context while the command text stays byte-identical.
NARROWED_SHELL = {
    "cp_syslog_collect_ls_crash": "expert_collect", "cp_syslog_collect_ls_spike": "expert_collect",
    "cp_syslog_collect_ls_sa": "expert_collect", "cp_syslog_collect_ls_hcp": "expert_collect",
    "pan_syslog_collect_job_get": "not_applicable_collect",
}
SFTP = {"cp_syslog_collect_read", "cp_syslog_collect_fetch"}  # C2 and C8: SFTP steps, no shell command
TABLES = ["system_log_cursor", "system_log_chunk", "system_log_gap", "system_log_file", "device_crash_entry",
          "device_crash_alert", "system_log_run"]


def _cells(line: str) -> list[str]:
    cells, current, in_tick = [], "", False
    for char in line.strip().strip("|"):
        if char == "`":
            in_tick = not in_tick
        if char == "|" and not in_tick:
            cells.append(current.strip())
            current = ""
        else:
            current += char
    cells.append(current.strip())
    return cells


def _contract_rows() -> dict[str, dict]:
    text = CONTRACT.read_text(encoding="utf-8")
    cp = text[text.index("### 10.1 New"):text.index("### 10.2 New")]
    pan = text[text.index("### 10.2 New"):text.index("### 10.3 Already")]
    rows: dict[str, dict] = {}
    for line in cp.splitlines():
        if not line.startswith("| C"):
            continue
        cells = _cells(line)
        number, key, timeout = cells[0], cells[3], cells[4]
        gate = "cp_syslog_collect_fetch" if number == "C8" else cells[1].strip("`")
        command = key if gate in SFTP else re.match(r"^`([^`]*)`", key).group(1)
        rows[gate] = {"command": command, "timeout": max(int(n) for n in re.findall(r"\d+", timeout)),
                      "telemetry": json.loads(cells[11].strip("`")), "vendor": "check_point"}
    for line in pan.splitlines():
        if not line.startswith("| P"):
            continue
        cells = _cells(line)
        rows[cells[1].strip("`")] = {"command": re.match(r"^`([^`]*)`", cells[2]).group(1), "timeout": int(cells[3]),
                                     "telemetry": json.loads(cells[10].strip("`")) if cells[10].startswith("`") else None,
                                     "vendor": "palo_alto"}
    return rows


def _fixture_rows() -> list[dict]:
    return yaml.safe_load(FIXTURE.read_text(encoding="utf-8"))["gates"]


def _sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def test_the_contract_lists_thirteen_commands_eight_check_point_and_five_palo_alto():
    rows = _contract_rows()
    assert len(rows) == 13
    assert sum(r["vendor"] == "check_point" for r in rows.values()) == 8
    assert sum(r["vendor"] == "palo_alto" for r in rows.values()) == 5


def test_fixture_rows_carry_the_contract_command_text_exactly():
    contract = _contract_rows()
    fixture = {row["gate_id"]: row for row in _fixture_rows() if "_syslog_collect_" in row["gate_id"]}
    assert set(fixture) == set(contract)
    for gate, expected in contract.items():
        row = fixture[gate]
        assert row["canonical_command_key"] == expected["command"], gate
        assert row["timeout_s"] == expected["timeout"], gate
        assert row["action_class"] == "read" and row["sign_off_state"] == "SIGNED_OFF" and row["retry_rule"] == "none", gate
        assert row["vendor"] == expected["vendor"], gate
        assert row["platform_role_scope"] == ("cp_gaia_gateway" if expected["vendor"] == "check_point" else "pan_firewall")
        if expected["telemetry"] is not None:
            assert row["safe_telemetry_fields"] == expected["telemetry"], gate
        want_shell = NARROWED_SHELL.get(gate, "not_applicable" if gate in SFTP or expected["vendor"] == "palo_alto" else "expert")
        assert row["shell_context"] == want_shell, gate
        assert row["transport_kind"] == ("SFTP_GET" if gate in SFTP else
                                         "PAN_XML_API" if expected["vendor"] == "palo_alto" else "SSH_EXEC"), gate


def test_migration_inserts_the_same_rows_with_the_same_command_text():
    sql = V160.read_text(encoding="utf-8")
    assert sql.count("INSERT INTO gate_registry") == 1
    assert "ON CONFLICT (gate_id) DO NOTHING" in sql
    for gate, expected in _contract_rows().items():
        row = next(r for r in _fixture_rows() if r["gate_id"] == gate)
        head = (f"({_sql_literal(gate)}, {_sql_literal(row['vendor'])}, {_sql_literal(row['platform_role_scope'])}, "
                f"{_sql_literal(row['shell_context'])}, {_sql_literal(row['transport_kind'])}, "
                f"{_sql_literal(expected['command'])}, 'read', 'SIGNED_OFF', {expected['timeout']}, 'none',")
        assert head in sql, gate
        for field in ("max_frequency", "session_reuse_rule", "unsupported_behavior_ref", "secret_output_risk",
                      "source_document_pointer"):
            assert _sql_literal(row[field]) in sql, f"{gate}.{field}"
    assert "set_config('app.actor_fingerprint'" in sql and "set_config('app.action_id'" in sql


def test_no_two_fixture_rows_share_a_canonical_key():
    keys = [(r["vendor"], r["platform_role_scope"], r["shell_context"], r["transport_kind"], r["canonical_command_key"])
            for r in _fixture_rows()]
    assert len(keys) == len(set(keys)), "C4 section 3.3 step 6: an ambiguous gate key is a hard error"
    measure = {r["gate_id"]: r["canonical_command_key"] for r in _fixture_rows() if "_syslog_measure_" in r["gate_id"]}
    assert len(measure) == 18
    # The five narrowed rows really do repeat an approved measurement command, byte for byte.
    repeated = {g for g, r in ((r["gate_id"], r) for r in _fixture_rows() if "_syslog_collect_" in r["gate_id"])
                if r["canonical_command_key"] in measure.values()}
    assert repeated == set(NARROWED_SHELL)


def test_migration_maps_modules_widens_the_checks_and_creates_the_contract_tables():
    sql = V160.read_text(encoding="utf-8")
    assert "'diagnostic_read','fmg_interface_detail','pan_syslog_measure','cp_system_log_collect','pan_system_log_collect') THEN 'diagnostics'" in sql
    previous = (MIGRATIONS / "V154__packet_analysis_shell.sql").read_text(encoding="utf-8")
    function = lambda text: re.search(r"CREATE OR REPLACE FUNCTION ui2_job_module.*?\n\$\$;", text, re.S).group(0)
    assert function(sql).replace(",'cp_system_log_collect','pan_system_log_collect'", "") == function(previous)
    assert "CHECK (artefact_class IN ('configuration', 'backup', 'system_log'))" in sql
    assert "'security_scan', 'device_crash_file'" in sql and "VALUES ('device_crash_file', false" in sql
    contract = CONTRACT.read_text(encoding="utf-8")
    section = contract[contract.index("### 5.2 Tables"):contract.index("File names can carry host names")]
    named = re.findall(r"^\| `([a-z_]+)`", section, re.M)
    assert named == TABLES
    for table in TABLES + ["system_log_settings"]:
        assert f"CREATE TABLE IF NOT EXISTS {table} (" in sql, table
        assert f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;" in sql, table
        assert f"CREATE TRIGGER trg_audit_{table}" in sql, table
    assert "system_log_settings" in sql and "include_management_servers BOOLEAN NOT NULL DEFAULT false" in sql
    assert "raw_retention_days INTEGER NOT NULL DEFAULT 30" in sql
    assert "device_cap_bytes BIGINT NOT NULL DEFAULT 2147483648" in sql
    assert "hcp_retention_days INTEGER NOT NULL DEFAULT 7" in sql
    for column in ("log_text", "raw_line", "message_text", "line_text"):
        assert column not in sql, "no table may hold raw log text"


def test_the_frozen_notification_contract_carries_the_device_crash_file_amendment():
    text = NOTIFICATION.read_text(encoding="utf-8")
    assert text.startswith("# Mail notifications by type")
    assert "**Status:** FROZEN" in text
    amendment = text[text.index("## 7. Amendment"):]
    for needle in ("device_crash_file", "Device crash and dump files", "device_crash_alert", "V160",
                   "PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md", "ROTATED_AWAY", "security_scan"):
        assert needle in amendment, needle
