"""Offline check: V163 and the fixture carry the seven PO-approved policy-completeness read commands exactly as the plan's
section 6 writes them (command text byte-identical), no two rows share a canonical key, and the schema, the decision
record and the plan status are in place. Style of test_system_log_collect_gate_contract.py."""
import json
import re
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md"
RECORD = ROOT / "docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md"
MIGRATIONS = ROOT / "ui2/service/src/main/resources/db/migration"
V163 = MIGRATIONS / "V163__policy_completeness.sql"
FIXTURE = ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml"
MAIN_SOURCES = [p for module in (ROOT / "ui2").iterdir() if (module / "src/main/java").is_dir()
                for p in (module / "src/main/java").rglob("*.java")]

#: plan label -> (gate id, vendor, platform_role_scope, shell_context, transport_kind, timeout)
GATES = {
    "P-1": ("pan_policy_nat_rule_hit_count", "palo_alto", "pan_firewall", "not_applicable", "PAN_XML_API", 60),
    "P-2": ("pan_policy_config_log_enqueue", "palo_alto", "panorama", "not_applicable", "PAN_XML_API", 30),
    "P-3": ("pan_policy_config_log_seed", "palo_alto", "panorama", "not_applicable", "PAN_XML_API", 30),
    "P-4": ("pan_policy_log_job_get", "palo_alto", "panorama", "not_applicable", "PAN_XML_API", 30),
    "C-1": ("cp_policy_show_changes", "check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", 60),
    "C-2": ("cp_policy_show_task", "check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", 60),
    "C-3": ("cp_policy_nat_rulebase_page100", "check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", 300),
}
TABLES = ["policy_revision", "policy_change_evidence", "policy_change_cursor"]
#: Every CHECK, PRIMARY KEY and UNIQUE constraint of V163, by explicit name (an inline column CHECK is named by PostgreSQL
#: <table>_<column>_check, so an explicit table-level name must never reuse that form: the first live dry run hit exactly that).
CONSTRAINTS = {
    "policy_revision": ['policy_revision_pkey', 'policy_revision_vendor_check', 'policy_revision_container_kind_check', 'policy_revision_revision_no_check', 'policy_revision_change_state_check', 'policy_revision_unknown_reason_check', 'policy_revision_marker_kind_check', 'policy_revision_marker_check', 'policy_revision_rules_total_check', 'policy_revision_nat_rules_check', 'policy_revision_rules_added_check', 'policy_revision_rules_modified_check', 'policy_revision_rules_removed_check', 'policy_revision_objects_added_check', 'policy_revision_objects_modified_check', 'policy_revision_objects_removed_check', 'policy_revision_evidence_count_check', 'policy_revision_job_container', 'policy_revision_unknown_reason_pairing_check', 'policy_revision_vendor_kind_check'],
    "policy_change_evidence": ['policy_change_evidence_pkey', 'policy_change_evidence_vendor_check', 'policy_change_evidence_evidence_kind_check', 'policy_change_evidence_entry_ref_check', 'policy_change_evidence_time_basis_check', 'policy_change_evidence_admin_ref_check', 'policy_change_evidence_command_class_check', 'policy_change_evidence_result_class_check', 'policy_change_evidence_path_hmac_check', 'policy_change_evidence_redaction_count_check', 'policy_change_evidence_added_count_check', 'policy_change_evidence_modified_count_check', 'policy_change_evidence_deleted_count_check', 'policy_change_evidence_object_changes_check', 'policy_change_evidence_vendor_kind_check', 'policy_change_evidence_time_basis_pairing_check'],
    "policy_change_cursor": ['policy_change_cursor_change_source_check', 'policy_change_cursor_last_receive_time_check', 'policy_change_cursor_boundary_seqnos_check', 'policy_change_cursor_pkey'],
}


def _strip(text: str) -> str:
    return text.replace("`", "").replace("**", "").strip()


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


def _plan_rows() -> dict[str, dict]:
    text = PLAN.read_text(encoding="utf-8")
    section = text[text.index("## 6. Command table"):text.index("### Optional (measure")]
    rows: dict[str, dict] = {}
    for block in re.split(r"^### ", section, flags=re.M)[1:]:
        label = block.splitlines()[0].split()[0]
        command = re.search(r"^Exact text: `(.*)`$", block, re.M).group(1)
        fields = {}
        for line in block.splitlines():
            if re.match(r"^\| \d+ \|", line):
                cells = _cells(line)
                fields[int(cells[0])] = _strip(cells[2])
        telemetry = fields[10]
        rows[label] = {"command": command, "fields": fields,
                       "telemetry": json.loads(telemetry) if telemetry.startswith("[") else
                       [item.strip() for item in telemetry.split(",")]}
    return rows


def _fixture_rows() -> list[dict]:
    return yaml.safe_load(FIXTURE.read_text(encoding="utf-8"))["gates"]


def _sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def test_the_plan_lists_seven_commands_and_is_approved_by_status():
    assert sorted(_plan_rows()) == sorted(GATES)
    text = PLAN.read_text(encoding="utf-8")
    assert text.startswith("# Policy Collection Completeness and 6-Hour Incremental Revisions")
    assert re.search(r"^\*\*Status: APPROVED", text, re.M)
    assert "DRAFT" in text.split("\n\n")[1], "the status paragraph names the DRAFT it was copied from"
    assert "PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md" in text.split("---")[0]


def test_fixture_rows_carry_the_plan_command_text_exactly():
    plan = _plan_rows()
    fixture = {row["gate_id"]: row for row in _fixture_rows()}
    for label, (gate, vendor, scope, shell, kind, timeout) in GATES.items():
        row = fixture[gate]
        assert row["canonical_command_key"] == plan[label]["command"], label
        assert (row["vendor"], row["platform_role_scope"], row["shell_context"], row["transport_kind"]) == (
            vendor, scope, shell, kind), label
        assert row["timeout_s"] == timeout == int(re.match(r"\d+", plan[label]["fields"][4]).group(0)), label
        assert row["action_class"] == "read" and row["sign_off_state"] == "SIGNED_OFF", label
        assert row["retry_rule"] == plan[label]["fields"][5], label
        assert row["max_frequency"] == plan[label]["fields"][6], label
        assert row["session_reuse_rule"] == plan[label]["fields"][7], label
        assert row["unsupported_behavior_ref"] == plan[label]["fields"][8], label
        assert row["secret_output_risk"] == plan[label]["fields"][9], label
        assert row["safe_telemetry_fields"] == plan[label]["telemetry"], label
        assert "PO approval 2026-10-18" in row["source_document_pointer"], label
        assert "POLICY_COLLECTION_COMPLETENESS_PLAN.md" in row["source_document_pointer"], label


def test_migration_inserts_the_same_rows_with_the_same_command_text():
    sql = V163.read_text(encoding="utf-8")
    assert sql.count("INSERT INTO gate_registry") == 1
    assert "ON CONFLICT (gate_id) DO NOTHING" in sql
    assert "set_config('app.actor_fingerprint'" in sql and "set_config('app.action_id'" in sql
    plan = _plan_rows()
    fixture = {row["gate_id"]: row for row in _fixture_rows()}
    for label, (gate, vendor, scope, shell, kind, timeout) in GATES.items():
        row = fixture[gate]
        head = (f"({_sql_literal(gate)}, {_sql_literal(vendor)}, {_sql_literal(scope)}, {_sql_literal(shell)}, "
                f"{_sql_literal(kind)}, {_sql_literal(plan[label]['command'])}, 'read', 'SIGNED_OFF', {timeout}, "
                f"{_sql_literal(plan[label]['fields'][5])},")
        assert head in sql, label
        for field in ("max_frequency", "session_reuse_rule", "unsupported_behavior_ref", "secret_output_risk",
                      "source_document_pointer"):
            assert _sql_literal(row[field]) in sql, f"{label}.{field}"
        assert _sql_literal(json.dumps(row["safe_telemetry_fields"], ensure_ascii=False)) + "::jsonb" in sql, label
    assert len(re.findall(r"^    \('[a-z_0-9]+', '(?:palo_alto|check_point)'", sql, re.M)) == 7


def test_no_two_fixture_rows_share_a_canonical_key_and_p2_is_distinct_only_by_scope():
    rows = _fixture_rows()
    keys = [(r["vendor"], r["platform_role_scope"], r["shell_context"], r["transport_kind"], r["canonical_command_key"])
            for r in rows]
    assert len(keys) == len(set(keys)), "C4 section 3.3 step 6: an ambiguous gate key is a hard error"
    by_id = {r["gate_id"]: r for r in rows}
    firewall, panorama = by_id["pan_syslog_collect_config_enqueue"], by_id["pan_policy_config_log_enqueue"]
    assert firewall["canonical_command_key"] == panorama["canonical_command_key"]
    assert (firewall["platform_role_scope"], panorama["platform_role_scope"]) == ("pan_firewall", "panorama")
    assert all(firewall[k] == panorama[k] for k in ("vendor", "shell_context", "transport_kind"))
    # P-3 and P-4 repeat approved firewall-scope texts under the panorama scope, never under the same scope.
    for gate, firewall_gate in (("pan_policy_config_log_seed", "pan_syslog_collect_config_seed"),
                                ("pan_policy_log_job_get", "pan_syslog_collect_job_get")):
        assert by_id[gate]["canonical_command_key"] == by_id[firewall_gate]["canonical_command_key"], gate
        assert by_id[gate]["platform_role_scope"] != by_id[firewall_gate]["platform_role_scope"], gate
    # P-1 differs from the approved security hit-count text only by the rule base.
    nat = by_id["pan_policy_nat_rule_hit_count"]["canonical_command_key"]
    security = by_id["pan_policy_rule_hit_count"]["canonical_command_key"]
    assert nat == security.replace("entry name='security'", "entry name='nat'") and nat != security


def test_the_conditional_c3_row_has_no_consumer_in_production_code():
    gate, command_head = GATES["C-3"][0], "show-nat-rulebase package '<PKG>' limit 100"
    for path in MAIN_SOURCES:
        text = path.read_text(encoding="utf-8")
        assert gate not in text, f"{path.name} uses the conditional C-3 gate id"
        assert command_head not in text, f"{path.name} issues the conditional C-3 command"
    # The seven rows exist, but C-3's pointer says it is conditional.
    pointer = next(r for r in _fixture_rows() if r["gate_id"] == gate)["source_document_pointer"]
    assert "CONDITIONAL" in pointer


def test_migration_creates_the_revision_and_evidence_tables_with_grants_and_bounds():
    sql = V163.read_text(encoding="utf-8")
    for table in TABLES:
        assert f"CREATE TABLE IF NOT EXISTS {table} (" in sql, table
        assert f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;" in sql, table
    revision = sql[sql.index("CREATE TABLE IF NOT EXISTS policy_revision"):sql.index("CREATE TABLE IF NOT EXISTS policy_change_evidence")]
    for column in ("source_id", "container_ref", "revision_no", "observed_at", "marker JSONB", "rules_added", "rules_modified",
                   "rules_removed", "nat_rules", "objects_added", "objects_modified", "objects_removed", "complete BOOLEAN",
                   "change_state"):
        assert column in revision, column
    assert "CHECK (change_state IN ('CHANGED', 'UNCHANGED', 'UNKNOWN'))" in revision
    assert "CONSTRAINT policy_revision_job_container UNIQUE (job_id, container_ref)" in revision
    evidence = sql[sql.index("CREATE TABLE IF NOT EXISTS policy_change_evidence"):sql.index("CREATE TABLE IF NOT EXISTS policy_change_cursor")]
    assert "admin_ref TEXT NOT NULL CONSTRAINT policy_change_evidence_admin_ref_check CHECK (admin_ref ~ '^([0-9a-f]{64}|masked:[0-9]{1,3}|UNKNOWN)$')" in evidence
    assert "CREATE UNIQUE INDEX IF NOT EXISTS policy_change_evidence_identity" in sql
    # Counts, opaque ids, an identity digest and timestamps only: no column may hold names, raw text or change detail.
    for forbidden in ("log_text", "raw_line", "message_text", "line_text", "before_change", "after_change", "user_name",
                      "admin_name", "object_name", "rule_name", "xpath TEXT", "path TEXT"):
        assert forbidden not in sql.replace("before-/after-change-detail", ""), forbidden
    # Audit-trigger-free like the other collected policy tables (policy_snapshot, cp_policy_domain_run).
    assert "fn_audit_capture" not in sql


def test_migration_is_v163_because_v162_belongs_to_another_lane():
    assert len(list(MIGRATIONS.glob("V163__*.sql"))) == 1
    assert "V162 is being taken tonight by another lane" in V163.read_text(encoding="utf-8")


def test_the_decision_record_carries_the_words_and_the_rules():
    text = RECORD.read_text(encoding="utf-8")
    assert "**Status: RATIFIED" in text
    assert ('"Hepsi ok devam. Belki ilk başta partial deneyebilirsin ama yapabilirsin okuma komutu düz. '
            'Guide de bunları söylüyorsa ok."') in text
    assert "MDS can be enabled; only no backup; other collections may run." in text
    assert "one device or one domain, one call" in text
    assert "off by default" in text and "C-3" in text and "conditional" in text
    for label in GATES:
        assert label in text
    assert "POLICY_COLLECTION_COMPLETENESS_PLAN.md" in text


def _table_blocks(sql: str) -> dict[str, str]:
    return {m.group(1): m.group(2) for m in re.finditer(r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\n\);", sql, re.S)}


def test_every_constraint_has_an_explicit_name_and_no_name_is_used_twice():
    sql = V163.read_text(encoding="utf-8")
    blocks = _table_blocks(sql)
    assert sorted(blocks) == sorted(TABLES)
    seen: list[str] = []
    for table, body in blocks.items():
        named = re.findall(r"CONSTRAINT (\w+) ", body)
        assert named == CONSTRAINTS[table], table
        seen += named
        # No CHECK, PRIMARY KEY or UNIQUE without its own CONSTRAINT <name> in front of it.
        for keyword in ("CHECK (", "PRIMARY KEY", "UNIQUE ("):
            assert body.count(keyword) == len(re.findall(r"CONSTRAINT \w+ " + re.escape(keyword), body)), f"{table}: unnamed {keyword}"
        assert not re.search(r"\bREFERENCES\b", body), table
        # An explicit name may not take the form PostgreSQL gives an inline column check, unless it is that column's own.
        columns = set(re.findall(r"^    (\w+) ", body, re.M))
        for name in named:
            tail = name[len(table) + 1:] if name.startswith(table + "_") else ""
            if tail.endswith("_check") and tail[:-len("_check")] in columns:
                owner = re.search(r"^    " + re.escape(tail[:-len("_check")]) + r" .*CONSTRAINT " + re.escape(name) + r" ", body, re.M)
                assert owner, f"{name} looks like the auto-name of column {tail[:-6]} but is not declared inline on it"
    assert len(seen) == len(set(seen)), "a constraint name is used twice"
    # Index and constraint names share one namespace per schema with the tables.
    indexes = re.findall(r"CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+)", sql)
    assert not set(indexes) & (set(seen) | set(TABLES))
