-- Backup targets are chosen in the product (Backups > Backup targets), not through an
-- environment allowlist (Product Owner, 2026-09-22). A device is only ever backed up --
-- singly or by "Run Fleet Backup" -- while this flag is set; the flag itself is an
-- audited devices UPDATE like every other devices mutation.
ALTER TABLE devices ADD COLUMN backup_target BOOLEAN NOT NULL DEFAULT false;
