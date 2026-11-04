"""Local, offline check that SQL and fixture authorize exactly the four PO-approved A6 matchers (rows 2-5, five gates)."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "ui2/service/src/main/resources/db/migration/V188__path_analysis_validation.sql"
FIXTURE = ROOT / "ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml"
PROPOSAL = ROOT / "docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md"
POLICY_TAIL = ("<source>SRC</source><destination>DST</destination><protocol>6</protocol>"
               "<destination-port>PORT</destination-port>")
COMMANDS = {
    "path_validation_cp_route_get": ("check_point", "bash -lc 'ip -4 route get <DST>'", 2),
    "path_validation_cp_route_get_vsx": ("check_point", "bash -lc 'vsenv <VSID> && ip -4 route get <DST>'", 2),
    "path_validation_pan_fib_lookup": (
        "palo_alto",
        "<test><routing><fib-lookup><virtual-router>VR</virtual-router><ip>DST</ip></fib-lookup></routing></test>", 3),
    "path_validation_pan_security_policy_match": (
        "palo_alto",
        "<test><security-policy-match><from>Z1</from><to>Z2</to>" + POLICY_TAIL + "</security-policy-match></test>", 4),
    "path_validation_pan_nat_policy_match": (
        "palo_alto",
        "<test><nat-policy-match><from>Z1</from><to>Z2</to>" + POLICY_TAIL + "</nat-policy-match></test>", 5),
}


def _fixture_rows():
    rows = {}
    for chunk in FIXTURE.read_text(encoding="utf-8").split("  - gate_id: ")[1:]:
        gate = chunk.splitlines()[0].strip().strip('"')
        if gate.startswith("path_validation_"):
            rows[gate] = dict(line.strip().split(": ", 1) for line in chunk.splitlines()[1:] if ": " in line)
    return rows


class PathValidationGateContractTest(unittest.TestCase):
    def test_the_proposal_is_ratified_and_names_each_literal(self):
        text = PROPOSAL.read_text(encoding="utf-8")
        self.assertIn("Status: RATIFIED", text)
        self.assertIn("1-5 onaylı devam", text)
        for gate, (_, command, row) in COMMANDS.items():
            literal = command.replace("bash -lc 'vsenv <VSID> && ", "bash -lc '")
            self.assertIn(f"| {row} |", text)
            self.assertIn(literal.split("<DST>")[0] if "bash" in literal else literal, text, gate)

    def test_fixture_rows_are_the_exact_signed_off_reads(self):
        rows = _fixture_rows()
        self.assertEqual(set(rows), set(COMMANDS))
        for gate, fields in rows.items():
            vendor, command, row = COMMANDS[gate]
            self.assertEqual(json.loads(fields["canonical_command_key"]), command)
            self.assertEqual(json.loads(fields["vendor"]), vendor)
            self.assertEqual(json.loads(fields["action_class"]), "read")
            self.assertEqual(json.loads(fields["sign_off_state"]), "SIGNED_OFF")
            self.assertEqual(json.loads(fields["retry_rule"]), "none")
            self.assertEqual(fields["timeout_s"], "30")
            self.assertIn(f"PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md row {row} (RATIFIED",
                          json.loads(fields["source_document_pointer"]))
            for name in ("max_frequency", "session_reuse_rule", "unsupported_behavior_ref", "secret_output_risk"):
                self.assertTrue(json.loads(fields[name]).strip(), f"{gate} {name}")

    def test_migration_inserts_the_same_rows_maps_the_module_and_stores_no_raw_output(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        rows = _fixture_rows()
        self.assertEqual(sql.count("INSERT INTO gate_registry"), 1)
        for gate, (vendor, command, _) in COMMANDS.items():
            self.assertIn(f"('{gate}', '{vendor}', ", sql)
            self.assertIn("'" + command.replace("'", "''") + "', 'read', 'SIGNED_OFF', 30, 'none'", sql)
            for name in ("max_frequency", "session_reuse_rule", "unsupported_behavior_ref", "secret_output_risk",
                         "source_document_pointer"):
                self.assertIn("'" + json.loads(rows[gate][name]).replace("'", "''") + "'", sql, f"{gate} {name}")
        self.assertIn("'cp_spike_layout_review','path_validation') THEN 'diagnostics'", sql)
        self.assertIn("CHECK (result = 'PENDING' OR plan IS NULL)", sql)
        columns = re.search(r"CREATE TABLE IF NOT EXISTS path_validation_hop \((.*?)\n\);", sql, re.S).group(1)
        for forbidden in ("output", "raw", "response", "command"):
            self.assertNotIn(forbidden, columns.lower())
        self.assertIn("('path_validation_hop', 'plan', 2,", sql)


if __name__ == "__main__":
    unittest.main()
