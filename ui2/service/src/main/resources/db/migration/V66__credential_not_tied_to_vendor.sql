-- V66 -- a credential is not tied to a vendor (PO, 2026-09-24: "burda illa vendor seçtirtiyorsun neden"). The two
-- allows_* flags were never checked when a credential is used; the V11 rule that one of them be set only blocked
-- credentials for Infoblox and Radware. The columns stay (history, audit trigger); the rule goes.
SELECT set_config('app.actor_fingerprint', 'migration:V66_credential_not_tied_to_vendor', true);
SELECT set_config('app.action_id', 'credential_not_tied_to_vendor_by_migration', true);

ALTER TABLE credentials DROP CONSTRAINT chk_credentials_at_least_one_vendor;
