"""Local, offline check that SQL and fixture authorize only the three PO-approved reads."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMANDS = {
    "cp_policy_packages": "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 500 details-level full",
    "cp_policy_access_rulebase": "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true",
    "cp_policy_nat_rulebase": "mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true",
}


class PolicyGateContractTest(unittest.TestCase):
    def test_migration_and_fixture_have_exact_signed_off_management_reads(self):
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V116__cp_policy_reads.sql").read_text()
        amendment = (ROOT / "ui2/service/src/main/resources/db/migration/V118__cp_policy_page_budget.sql").read_text()
        rows = [row for row in fixture.split("  - gate_id: ") if row.startswith('"cp_policy_')]
        self.assertEqual(len(rows), 3)
        for row in rows:
            key = json.loads(row.splitlines()[0])
            fields = dict(line.strip().split(": ", 1) for line in row.splitlines()[1:] if ": " in line)
            self.assertEqual(json.loads(fields["canonical_command_key"]), COMMANDS[key])
            self.assertEqual(json.loads(fields["platform_role_scope"]), "cp_multi_domain_server")
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["action_class"]), "read")
            self.assertEqual(fields["timeout_s"], "300" if key == "cp_policy_access_rulebase" else "60")
            self.assertEqual(json.loads(fields["retry_rule"]), "once on timeout with limit 50" if key == "cp_policy_access_rulebase" else "none")
            self.assertIn("'" + COMMANDS[key].replace("'", "''") + "'", amendment if key == "cp_policy_access_rulebase" else sql)
        self.assertIn("WHERE gate_id = 'cp_policy_access_rulebase'", amendment)
        self.assertNotIn("INSERT INTO gate_registry", amendment)
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 3)
        for table in re.findall(r"CREATE TABLE (\w+)", sql):
            self.assertIn(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;", sql)


if __name__ == "__main__":
    unittest.main()
