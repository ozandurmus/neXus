"""Hit reads: V121 inserts both DRAFTED; later migrations sign each off (Check Point V124 2026-10-05, Palo Alto V155
PO 2026-10-16) and the fixture carries exactly the signed-off forms."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMANDS = {
    "cp_policy_access_rulebase_hits": "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true",
    "pan_policy_rule_hit_count": "type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name='<VSYS>'><rule-base><entry name='security'><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>",
}


# V121 drafted both reads. V124 signed off the Check Point one (PO 2026-10-05) and V155 the Palo Alto one (PO approval
# 2026-10-16); the fixture is the signed-off state.
SIGNED_OFF_BY = {"cp_policy_access_rulebase_hits": "V124__cp_policy_object_inventory.sql",
                 "pan_policy_rule_hit_count": "V155__pan_rule_hit_count_signoff.sql"}
MIGRATIONS = ROOT / "ui2/service/src/main/resources/db/migration"


class HitGateContractTest(unittest.TestCase):
    def test_two_hit_reads_are_signed_off_and_match_migration(self):
        fixture = (ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml").read_text()
        sql = (ROOT / "ui2/service/src/main/resources/db/migration/V121__policy_hit_counts.sql").read_text()
        proposed = {"cp_policy_access_rulebase_hits": "cp_multi_domain_server", "pan_policy_rule_hit_count": "pan_firewall"}
        rows = [row for row in fixture.split("  - gate_id: ") if row.splitlines()[0] in [json.dumps(k) for k in proposed]]
        self.assertEqual(len(rows), 2)
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 2)
        self.assertNotIn("'SIGNED_OFF'", sql)
        for gate, name in SIGNED_OFF_BY.items():
            signoff = (MIGRATIONS / name).read_text()
            self.assertIn(f"gate_id = '{gate}' AND sign_off_state = 'DRAFTED'", signoff)
            self.assertIn("sign_off_state = 'SIGNED_OFF'", signoff)
        for row in rows:
            fields = dict(line.strip().split(": ", 1) for line in row.splitlines()[1:] if ": " in line)
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["platform_role_scope"]), proposed[json.loads(row.splitlines()[0])])
            self.assertEqual(json.loads(fields["retry_rule"]), "none")
            self.assertEqual(json.loads(fields["action_class"]), "read")
            command = json.loads(fields["canonical_command_key"])
            self.assertEqual(command, COMMANDS[json.loads(row.splitlines()[0])])
            self.assertIn("'" + command.replace("'", "''") + "'", sql)
            self.assertIn("show-hits true" if command.startswith("mgmt_cli") else "<rule-hit-count>", command)
        self.assertIn("rule - 'id' - 'uuid' - 'hitCounts'", sql)


if __name__ == "__main__":
    unittest.main()
