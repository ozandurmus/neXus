"""Offline parity for the one-off PO-approved cpview measurement sign-off."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CpviewGateTest(unittest.TestCase):
    def test_signed_off_gate_matches_migrations_without_changing_bounds(self):
        fixture = (ROOT / 'ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml').read_text()
        rows = [row for row in fixture.split('  - gate_id: ') if row.startswith('"cp_diagnostic_cpview_measure"\n')]
        self.assertEqual(len(rows), 1)
        fields = dict(line.strip().split(': ', 1) for line in rows[0].splitlines()[1:] if ': ' in line)
        sql = (ROOT / 'ui2/service/src/main/resources/db/migration/V141__cpview_measurement_pending.sql').read_text()
        for key, expected in {'canonical_command_key': 'cpview -p',
                              'platform_role_scope': 'cp_gaia_gateway', 'action_class': 'read',
                              'shell_context': 'expert', 'retry_rule': 'none', 'transport_kind': 'SSH_EXEC'}.items():
            self.assertEqual(json.loads(fields[key]), expected)
            self.assertIn("'" + expected + "'", sql)
        self.assertEqual(json.loads(fields['timeout_s']), 30)
        self.assertNotIn("'SIGNED_OFF'", sql)
        self.assertEqual(sql.count('INSERT INTO gate_registry'), 1)
        approval = '; PO chat approval 2026-10-07 for one measurement on one plain gateway member'
        update = (ROOT / 'ui2/service/src/main/resources/db/migration/V142__cpview_measurement_sign_off.sql').read_text()
        self.assertIn("set_config('app.actor_fingerprint', 'migration:V142_cpview_measurement_sign_off', true)", update)
        self.assertIn("set_config('app.action_id', 'gate_registry_update_by_migration', true)", update)
        self.assertEqual(json.loads(fields['sign_off_state']), 'SIGNED_OFF')
        self.assertIn("sign_off_state = 'SIGNED_OFF'", update)
        self.assertIn("source_document_pointer = source_document_pointer || '" + approval + "'", update)
        self.assertEqual(re.findall(r'UPDATE gate_registry SET\s+(.*?)\s+WHERE (.*?);', update, re.S),
                         [("sign_off_state = 'SIGNED_OFF',\n    source_document_pointer = source_document_pointer || '" + approval + "'",
                           "gate_id = 'cp_diagnostic_cpview_measure'")])
        original_pointer = re.search(r", '([^']+)'\);", sql).group(1)
        self.assertEqual(json.loads(fields['source_document_pointer']), original_pointer + approval)
        for field in ('max_frequency', 'session_reuse_rule', 'unsupported_behavior_ref', 'secret_output_risk'):
            self.assertIn("'" + json.loads(fields[field]) + "'", sql)
        self.assertEqual(json.loads(fields['safe_telemetry_fields']),
                         json.loads(re.search(r"'([\[][^']+[\]])'", sql).group(1)))
        self.assertNotIn('INSERT INTO', update)
        self.assertNotIn('DELETE FROM', update)


if __name__ == '__main__':
    unittest.main()
