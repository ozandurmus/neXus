-- V206 -- Check Point remote shell normalization, phase 1 (docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md
-- section 6.1; PO approved the three literals exactly, 2026-11-11, "Üçünü onayla"). The MDS export's three login-shell rows
-- run without a login shell: BASH_ENV and ENV cleared, the Check Point profile sourced explicitly, both cleared again.
-- A login shell read /root/.bash_profile, which exports BASH_ENV=$HOME/.bashrc; every non-interactive bash under
-- mds_backup then re-sourced the Check Point profile (docs/design/INCIDENT_2026_11_11_MDS_FORK_STORM.md).
SELECT set_config('app.actor_fingerprint', 'migration:V206_cp_mds_export_no_login_shell', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET canonical_command_key =
  'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd /var/log && setsid nohup bash --noprofile --norc -c "$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo \$? > %1$s/mds_backup.rc" </dev/null >/dev/null 2>&1 &',
  source_document_pointer = source_document_pointer || '; V206 no login shell (CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md 6.1, PO 2026-11-11)'
WHERE gate_id = 'mds_backup_start';

UPDATE gate_registry SET canonical_command_key =
  'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mdsstat > %s/mdsstat.txt 2>&1',
  source_document_pointer = source_document_pointer || '; V206 no login shell (CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md 6.1, PO 2026-11-11)'
WHERE gate_id = 'mds_mdsstat';

UPDATE gate_registry SET canonical_command_key =
  'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print -x > %s/cplic.txt 2>&1',
  source_document_pointer = source_document_pointer || '; V206 no login shell (CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md 6.1, PO 2026-11-11)'
WHERE gate_id = 'mds_cplic_print';

DO $$
BEGIN
  IF (SELECT count(*) FROM gate_registry WHERE gate_id IN ('mds_backup_start', 'mds_mdsstat', 'mds_cplic_print')
      AND canonical_command_key LIKE 'unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; %') <> 3 THEN
    RAISE EXCEPTION 'V206: expected exactly three MDS export gate rows to be rewritten';
  END IF;
END $$;
