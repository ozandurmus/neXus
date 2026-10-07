"""Offline cpview preparation remains unsigned and uses one exact command."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CpviewGateTest(unittest.TestCase):
    def test_pending_gate_matches_migration(self):
        fixture = (ROOT / 'ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml').read_text()
        rows = [row for row in fixture.split('  - gate_id: ') if row.startswith('"cp_diagnostic_cpview_measure"\n')]
        self.assertEqual(len(rows), 1)
        fields = dict(line.strip().split(': ', 1) for line in rows[0].splitlines()[1:] if ': ' in line)
        sql = (ROOT / 'ui2/service/src/main/resources/db/migration/V140__cpview_measurement_pending.sql').read_text()
        for key, expected in {'sign_off_state': 'DRAFTED', 'canonical_command_key': 'cpview -p',
                              'platform_role_scope': 'cp_gaia_gateway', 'action_class': 'read',
                              'shell_context': 'expert', 'retry_rule': 'none', 'transport_kind': 'SSH_EXEC'}.items():
            self.assertEqual(json.loads(fields[key]), expected)
            self.assertIn("'" + expected + "'", sql)
        self.assertEqual(json.loads(fields['timeout_s']), 30)
        self.assertNotIn("'SIGNED_OFF'", sql)
        self.assertEqual(sql.count('INSERT INTO gate_registry'), 1)


if __name__ == '__main__':
    unittest.main()
