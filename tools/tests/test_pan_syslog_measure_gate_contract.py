"""Local, offline check that SQL and fixture authorize exactly the three PO-approved PAN log measurement reads (M3/M4)."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "ui2/service/src/main/resources/db/migration/V151__pan_syslog_measurement.sql"
FIXTURE = ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml"
COMMANDS = {
    "pan_syslog_measure_system_enqueue": "type=log&log-type=system&nlogs=100",
    "pan_syslog_measure_config_enqueue": "type=log&log-type=config&nlogs=100",
    "pan_syslog_measure_job_get": "type=log&action=get&job-id=<id>",
}
POINTER = ("DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15 for M3/M4 "
           "on every enrolled Palo Alto firewall")


class PanSyslogMeasureGateContractTest(unittest.TestCase):
    def test_fixture_rows_are_the_exact_signed_off_firewall_reads(self):
        rows = [row for row in FIXTURE.read_text(encoding="utf-8").split("  - gate_id: ")
                if row.startswith('"pan_syslog_measure_')]
        self.assertEqual(len(rows), 3)
        for row in rows:
            gate = json.loads(row.splitlines()[0])
            fields = dict(line.strip().split(": ", 1) for line in row.splitlines()[1:] if ": " in line)
            self.assertEqual(json.loads(fields["canonical_command_key"]), COMMANDS[gate])
            self.assertEqual(json.loads(fields["vendor"]), "palo_alto")
            self.assertEqual(json.loads(fields["platform_role_scope"]), "pan_firewall")
            self.assertEqual(json.loads(fields["transport_kind"]), "PAN_XML_API")
            self.assertEqual(json.loads(fields["action_class"]), "read")
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["retry_rule"]), "none")
            self.assertEqual(fields["timeout_s"], "30")
            self.assertEqual(json.loads(fields["source_document_pointer"]), POINTER)

    def test_migration_inserts_the_same_rows_maps_the_module_and_keeps_only_summaries(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 1)
        for gate, command in COMMANDS.items():
            self.assertIn(f"('{gate}', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',\n     '{command}', "
                          "'read', 'SIGNED_OFF', 30, 'none'", sql)
        self.assertEqual(sql.count(POINTER), 3)
        self.assertIn("'diagnostic_read','fmg_interface_detail','pan_syslog_measure') THEN 'diagnostics'", sql)
        self.assertIn("set_config('app.actor_fingerprint'", sql)
        for table in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", sql):
            self.assertIn(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;", sql)
            self.assertIn(f"CREATE TRIGGER trg_audit_{table}", sql)
        self.assertIn("source IN ('pan_system', 'pan_config')", sql)


if __name__ == "__main__":
    unittest.main()
