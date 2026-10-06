"""Offline checks for PO-approved CP policy gate migrations and fixture rows."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMANDS = {
    "cp_policy_packages_paged": "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 20 offset '<N>' details-level full",
    "cp_policy_packages": "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 500 details-level full",
    "cp_policy_access_rulebase": "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true",
    "cp_policy_nat_rulebase": "mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true",
}


class PolicyGateContractTest(unittest.TestCase):
    def test_migration_and_fixture_have_exact_signed_off_management_reads(self):
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V116__cp_policy_reads.sql").read_text()
        amendment = (ROOT / "ui2/service/src/main/resources/db/migration/V118__cp_policy_page_budget.sql").read_text()
        paged = (ROOT / "ui2/service/src/main/resources/db/migration/V123__cp_policy_packages_paged.sql").read_text()
        rows = [row for row in fixture.split("  - gate_id: ") if row.splitlines()[0] in [json.dumps(key) for key in COMMANDS] and 'sign_off_state: "SIGNED_OFF"' in row]
        self.assertEqual(len(rows), 4)
        for row in rows:
            key = json.loads(row.splitlines()[0])
            fields = dict(line.strip().split(": ", 1) for line in row.splitlines()[1:] if ": " in line)
            self.assertEqual(json.loads(fields["canonical_command_key"]), COMMANDS[key])
            self.assertEqual(json.loads(fields["platform_role_scope"]), "cp_multi_domain_server")
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["action_class"]), "read")
            self.assertEqual(fields["timeout_s"], "300" if key in ("cp_policy_access_rulebase", "cp_policy_packages_paged") else "60")
            self.assertEqual(json.loads(fields["retry_rule"]), "once on timeout with limit 50" if key in ("cp_policy_access_rulebase", "cp_policy_packages_paged") else "none")
            self.assertIn("'" + COMMANDS[key].replace("'", "''") + "'", amendment if key == "cp_policy_access_rulebase" else paged if key == "cp_policy_packages_paged" else sql)
        self.assertEqual(paged.count("INSERT INTO gate_registry"), 1)
        self.assertNotIn("UPDATE gate_registry", paged)
        self.assertNotIn("DELETE FROM gate_registry", paged)
        self.assertIn("PO 2026-10-05", paged)
        self.assertIn("WHERE gate_id = 'cp_policy_access_rulebase'", amendment)
        self.assertNotIn("INSERT INTO gate_registry", amendment)
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 3)
        for table in re.findall(r"CREATE TABLE (\w+)", sql):
            self.assertIn(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {table} TO ui2_app;", sql)

    def test_uid_variants_match_migration_and_preserve_name_gate_limits(self):
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V130__cp_policy_access_layer_uid.sql").read_text()
        rows = {}
        for block in fixture.split("  - gate_id: ")[1:]:
            if not block.startswith('"cp_policy_access_rulebase'):
                continue
            rows[json.loads(block.splitlines()[0])] = dict(
                line.strip().split(": ", 1) for line in block.splitlines()[1:] if ": " in line
            )
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 2)
        self.assertNotIn("UPDATE gate_registry", sql)
        self.assertNotIn("DELETE FROM gate_registry", sql)
        self.assertNotIn("CREATE TABLE", sql)
        self.assertIn("gate_registry_insert_by_migration", sql)
        for hits in (False, True):
            name_id = "cp_policy_access_rulebase" + ("_hits" if hits else "")
            uid_id = "cp_policy_access_rulebase_uid" + ("_hits" if hits else "")
            name, uid = rows[name_id], rows[uid_id]
            for field in name.keys() - {"canonical_command_key", "source_document_pointer"}:
                self.assertEqual(uid[field], name[field], field)
            command = json.loads(name["canonical_command_key"]).replace("name '<LAYER>'", "uid '<UID>'")
            self.assertEqual(json.loads(uid["canonical_command_key"]), command)
            self.assertEqual(json.loads(uid["sign_off_state"]), "SIGNED_OFF")
            self.assertIn("'" + uid_id + "'", sql)
            self.assertIn("'" + command.replace("'", "''") + "'", sql)
            self.assertIn("'" + json.loads(uid["retry_rule"]) + "'", sql)
            self.assertIn("PO 2026-10-06", json.loads(uid["source_document_pointer"]))


if __name__ == "__main__":
    unittest.main()
