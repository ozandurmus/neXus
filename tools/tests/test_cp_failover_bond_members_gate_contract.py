"""Offline parity for the two PO-approved selected-bond membership reads."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class BondMembersGateContractTest(unittest.TestCase):
    def test_exact_migration_rows_match_fixture(self):
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V142__cp_failover_bond_members.sql").read_text()
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        columns, values = re.search(r"INSERT INTO gate_registry \((.*?)\)\s*VALUES\s*(.*);", sql, re.S).groups()
        columns = columns.split(", ")
        rows = re.findall(r"^[ \t]*\((.*)\)[,;]?$", values, re.M)
        self.assertEqual(len(rows), 2)
        expected_ids = {"cp_failover_bond_members", "cp_failover_bond_members_vsid"}
        actual_ids = set()
        for row in rows:
            tokens = re.findall(r"'((?:''|[^'])*)'(?:[:]{2}jsonb)?|(?<![\w])([0-9]+)", row)
            parsed = [int(number) if number else value.replace("''", "'") for value, number in tokens]
            self.assertEqual(len(parsed), len(columns))
            fields = dict(zip(columns, parsed))
            gate_id = fields["gate_id"]
            actual_ids.add(gate_id)
            block = fixture.split("  - gate_id: " + gate_id + "\n", 1)[1].split("  - gate_id: ", 1)[0]
            fixture_fields = dict(line.strip().split(": ", 1) for line in block.splitlines() if ": " in line)
            for field in columns[1:]:
                expected = json.loads(fields[field]) if field == "safe_telemetry_fields" else fields[field]
                self.assertEqual(json.loads(fixture_fields[field]), expected, (gate_id, field))
            command = "cphaprob show_bond <BOND>"
            if gate_id.endswith("_vsid"):
                command = "bash -lc 'vsenv <VSID> && " + command + "'"
            self.assertEqual(fields["canonical_command_key"], command)
            self.assertEqual(fields["action_class"], "read")
            self.assertEqual(fields["timeout_s"], 30)
            self.assertEqual(fields["retry_rule"], "none")
            self.assertEqual(fields["sign_off_state"], "SIGNED_OFF")
            self.assertEqual(fields["max_frequency"], "once per selected bond per phase per member")
            self.assertEqual(fields["safe_telemetry_fields"], "[]")
            self.assertEqual(fields["source_document_pointer"],
                             "PO chat approval 2026-10-08; CP R82 CLI reference, Viewing Bond Interfaces")
        self.assertEqual(actual_ids, expected_ids)
        self.assertIn("gate_registry_insert_by_migration", sql)
        self.assertNotIn("CREATE TABLE", sql)
        self.assertNotIn("UPDATE gate_registry", sql)
        self.assertNotIn("DELETE FROM gate_registry", sql)


if __name__ == "__main__":
    unittest.main()
