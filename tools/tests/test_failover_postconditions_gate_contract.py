"""Offline parity for the PO-approved PR9 phase/frequency amendment."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "ui2/service/src/main/resources/db/migration/V139__failover_postconditions.sql"
FIXTURE = ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml"
WORKER = ROOT / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/failover"


def rows():
    result = {}
    for block in FIXTURE.read_text().split("  - gate_id: ")[1:]:
        lines = block.splitlines()
        result[lines[0].strip("\"'")] = dict(
            line.strip().split(": ", 1) for line in lines[1:]
            if line.startswith("    ") and ": " in line
        )
    return result


class FailoverPostconditionsGateContractTest(unittest.TestCase):
    def test_every_amended_field_matches_fixture_without_new_commands(self):
        sql = MIGRATION.read_text()
        fixture = rows()
        updates = re.findall(r"UPDATE gate_registry SET\s+(.*?)\s+WHERE gate_id IN \((.*?)\);", sql, re.S)
        self.assertEqual(len(updates), 5)
        amended = set()
        for assignments, ids in updates:
            fields = dict(re.findall(r"(\w+) = '((?:''|[^'])*)'", assignments))
            for gate in re.findall(r"'([^']+)'", ids):
                amended.add(gate)
                for field, value in fields.items():
                    self.assertEqual(json.loads(fixture[gate][field]), value.replace("''", "'"), (gate, field))
                self.assertEqual(fixture[gate]["timeout_s"], "30")
                self.assertEqual(fixture[gate]["retry_rule"].strip("\"'"), "none")
        self.assertTrue({"cp_failover_traffic", "cp_failover_traffic_vsid", "cp_policy_install_cpstat",
                         "pan_failover_session_sync", "cp_failover_pnotes_vsid"} <= amended)
        self.assertFalse(any("connections" in gate or gate.endswith(("_down", "_up", "_suspend", "_functional"))
                             for gate in amended))
        self.assertNotIn("INSERT INTO gate_registry", sql)
        self.assertNotIn("DELETE FROM gate_registry", sql)
        self.assertNotIn("CREATE TABLE", sql)
        self.assertIn("gate_registry_update_by_migration", sql)
        self.assertIn("post_return", sql)
        self.assertIn("NOT_EVALUATED", sql)

    def test_three_windows_and_policy_context_are_explicit(self):
        fixture = rows()
        for gate in ("cp_failover_traffic", "cp_failover_traffic_vsid"):
            frequency = json.loads(fixture[gate]["max_frequency"])
            for text in ("two samples 5 s apart", "baseline immediately before DOWN", "post-switch", "post-return"):
                self.assertIn(text, frequency)
        cpstat = fixture["cp_policy_install_cpstat"]
        self.assertEqual(cpstat["canonical_command_key"], "'bash -lc ''cpstat -f policy fw'''")
        self.assertIn("gateway Expert login shell", json.loads(cpstat["session_reuse_rule"]))
        self.assertIn("21 polls", json.loads(fixture["cp_inventory_cphaprob_stat"]["max_frequency"]))
        # A VS-context policy command needs its own PO approval; never broaden the existing key silently.
        self.assertNotIn("cp_policy_install_cpstat_vsid", fixture)

    def test_connection_table_cannot_be_dispatched_by_failover(self):
        for path in WORKER.glob("*.java"):
            source = path.read_text()
            self.assertNotIn("fw tab -t connections", source, path.name)
        source = (WORKER / "CpFailoverChecks.java").read_text()
        self.assertIn('new Assessment("NOT_EVALUATED"', source)
        self.assertIn('"SESSION_CONTINUITY_NOT_EVALUATED"', source)
        executor = (WORKER / "CpFailoverJobExecutor.java").read_text()
        self.assertIn("System::nanoTime", executor)
        self.assertIn('checks("post_return"', executor)
        self.assertIn('POLICY="fw stat"', executor)
        self.assertIn('CPSTAT_POLICY="cpstat -f policy fw"', executor)


if __name__ == "__main__":
    unittest.main()
