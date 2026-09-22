-- V54 -- clear the pseudonym registry once, when the pseudonymizer key becomes durable (2026-09-23).
-- The HMAC key was generated into the service pod's HOME (an emptyDir), so every new pod re-keyed every raw
-- name and claimed a new pseudonym beside the orphaned rows of earlier keys: the same cluster read CLS-LIMA-07
-- before a deploy and CLS-DELTA-02 after it. The key now comes from the ui2-privacy-hmac-key Secret
-- (UI2_PRIVACY_HMAC_KEY_FILE). The rows here are keyed by HMACs under keys no pod holds any more; none can be
-- matched again, and they occupy candidate names. No evidence lives in this table.
SELECT set_config('app.actor_fingerprint', 'migration:V54_pseudonym_registry_rekey', true);
SELECT set_config('app.action_id', 'pseudonym_registry_clear_by_migration', true);

DELETE FROM pseudonym_registry;
