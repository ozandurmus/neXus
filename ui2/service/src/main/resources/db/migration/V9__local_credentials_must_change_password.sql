-- V9 — forced password change on a seeded credential (movement
-- NXS-LOCAL-0152, WORKER.md "Forced password change, server first").
--
-- Additive to V1-V8 (append-only; none of them is edited). Adds a single
-- boolean column recording that a local_credentials row still holds the
-- password it was seeded with (bootstrap or otherwise); every other row
-- (operator-created via the CLI bootstrap path) keeps the schema default
-- (false) untouched, since only FirstBootIdentitySeedingRunner ever sets
-- this true, and only for the two bootstrap identities it creates.
--
-- Never derived by comparing a password (submitted or stored) against
-- BootstrapCredentialDefaults' constants -- this column is the only
-- mechanism (WORKER.md risk: "reads a secret into a code path that must
-- not have it").

ALTER TABLE local_credentials
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- fn_audit_capture_local_credentials() (V8) is left exactly as it is: its
-- explicit column allowlist omits must_change_password, same as every
-- other non-secret column it already omits (local_identity_name). This is
-- not a security-relevant omission -- the flag carries no credential
-- material -- and re-defining the trigger here is not required by this
-- movement's acceptance criteria.
