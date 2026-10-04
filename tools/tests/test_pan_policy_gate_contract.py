"""Local, offline check that SQL and fixture authorize only the four PO-approved Panorama reads."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMANDS = {
    "pan_policy_shared": "type=config&action=show&xpath=/config/shared",
    "pan_policy_device_group": "type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='<DG>']",
    "pan_policy_devicegroups": "type=op&cmd=<show><devicegroups/></show>",
    "pan_policy_hierarchy": "type=op&cmd=<show><dg-hierarchy></dg-hierarchy></show>",
}


class PolicyGateContractTest(unittest.TestCase):
    def test_migration_and_fixture_have_exact_signed_off_management_reads(self):
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V117__pan_policy_reads.sql").read_text()
        rows = [row for row in fixture.split("  - gate_id: ") if row.startswith('"pan_policy_') and 'sign_off_state: "SIGNED_OFF"' in row]
        self.assertEqual(len(rows), 4)
        for row in rows:
            key = json.loads(row.splitlines()[0])
            fields = dict(line.strip().split(": ", 1) for line in row.splitlines()[1:] if ": " in line)
            self.assertEqual(json.loads(fields["canonical_command_key"]), COMMANDS[key])
            self.assertEqual(json.loads(fields["platform_role_scope"]), "panorama")
            self.assertEqual(json.loads(fields["vendor"]), "palo_alto")
            self.assertEqual(json.loads(fields["transport_kind"]), "PAN_XML_API")
            self.assertEqual(json.loads(fields["retry_rule"]), "none")
            self.assertIn("6 hours", json.loads(fields["max_frequency"]))
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["action_class"]), "read")
            self.assertEqual(fields["timeout_s"], "60")
            self.assertIn("'" + COMMANDS[key].replace("'", "''") + "'", sql)
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 4)
        for table in re.findall(r"CREATE TABLE (\w+)", sql):
            self.assertIn(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;", sql)


if __name__ == "__main__":
    unittest.main()
