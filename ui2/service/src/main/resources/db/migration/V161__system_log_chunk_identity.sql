-- V161 -- system-log phase B, keyed content identity and the redaction rule set
-- (docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §4.1, §5.2, §12, §15 item 4; the cp_system_log_collect executor).
--
-- 1. Chunk identity. V160 keys a chunk by (device, source, inode, start_offset). Two different chunks can honestly
--    share that key on a real gateway: a file system reuses an inode number after a rotated file is deleted, and an
--    ANCHOR_MISMATCH restart (§4.4) re-reads the same inode from byte 0. Under the V160 key the second chunk could never
--    be stored and the source would stop for good. The key gains the chunk's content identity, so a true duplicate (the
--    same bytes of the same file at the same offset: an at-least-once re-read after a crash between the artefact write
--    and its record, §4.1) is still refused and a reused inode is not. The PAN one-chunk-per-run index is unchanged.
-- 2. Keyed, never bare (§12: anchors and names are HMACs; a bare content hash would let anyone confirm a guessed log
--    chunk). system_log_chunk.plaintext_sha256 is replaced by plaintext_hmac: HMAC-SHA256 of the redacted plaintext
--    under the keyed-fingerprint secret that also keys the anchors and file-name HMACs (UI2_HOSTNAME_FINGERPRINT_KEY_FILE,
--    domain-separated), lowercase hex. system_log_file.sha256 (HCP, sar) is replaced the same way by content_hmac.
--    No value is carried over: a keyed digest cannot be computed from a bare one. Rows written before V161 (only PAN
--    chunks, inode NULL, and no file rows: no Check Point executor ran before V161) keep NULL; every new row needs one.
--    The artefact manifest (backup_artefact.plaintext_sha256) keeps the store's own integrity digest, as for every
--    artefact class; it is outside this table and unchanged here.
-- 3. system_log_chunk.redaction_rule_set: the id of the redaction rule set that produced the stored bytes (§12,
--    "cp-text-v1"), so a later rule change stays traceable per chunk.
-- Repeatable inside one rolled-back transaction (IF EXISTS / IF NOT EXISTS, drop-then-add, ON CONFLICT DO NOTHING), so
-- the dry run can run the file twice.
SELECT set_config('app.actor_fingerprint', 'migration:V161_system_log_chunk_identity', true);
SELECT set_config('app.action_id', 'system_log_chunk_identity_by_migration', true);

-- 1 + 2. Chunk: keyed content identity in the unique key; the bare digest goes.
ALTER TABLE system_log_chunk DROP CONSTRAINT IF EXISTS system_log_chunk_device_source_inode_offset;
ALTER TABLE system_log_chunk DROP CONSTRAINT IF EXISTS system_log_chunk_device_source_inode_offset_content;
ALTER TABLE system_log_chunk ADD COLUMN IF NOT EXISTS plaintext_hmac TEXT;
ALTER TABLE system_log_chunk DROP COLUMN IF EXISTS plaintext_sha256;
ALTER TABLE system_log_chunk DROP CONSTRAINT IF EXISTS system_log_chunk_plaintext_hmac_check;
ALTER TABLE system_log_chunk ADD CONSTRAINT system_log_chunk_plaintext_hmac_check
    CHECK (plaintext_hmac IS NULL OR plaintext_hmac ~ '^[0-9a-f]{64}$');
-- A Check Point chunk (inode set) always carries its content identity; only pre-V161 PAN rows may lack it.
ALTER TABLE system_log_chunk DROP CONSTRAINT IF EXISTS system_log_chunk_cp_content_identity_check;
ALTER TABLE system_log_chunk ADD CONSTRAINT system_log_chunk_cp_content_identity_check
    CHECK (inode IS NULL OR plaintext_hmac IS NOT NULL);
ALTER TABLE system_log_chunk ADD CONSTRAINT system_log_chunk_device_source_inode_offset_content
    UNIQUE (device_id, source_key, inode, start_offset, plaintext_hmac);
COMMENT ON COLUMN system_log_chunk.plaintext_hmac IS
    'HMAC-SHA256 (keyed-fingerprint secret, domain separated) of the redacted plaintext stored in the artefact; never a bare hash (contract §12).';

-- 3. The redaction rule set of each chunk.
ALTER TABLE system_log_chunk ADD COLUMN IF NOT EXISTS redaction_rule_set TEXT;
ALTER TABLE system_log_chunk DROP CONSTRAINT IF EXISTS system_log_chunk_redaction_rule_set_check;
ALTER TABLE system_log_chunk ADD CONSTRAINT system_log_chunk_redaction_rule_set_check
    CHECK (redaction_rule_set IS NULL OR redaction_rule_set ~ '^[a-z0-9][a-z0-9._-]{0,31}$');
COMMENT ON COLUMN system_log_chunk.redaction_rule_set IS
    'Id of the redaction rule set applied before encryption (contract §12), e.g. cp-text-v1; NULL when not applicable.';

-- 2. Whole files: the same keyed content identity instead of the bare digest.
ALTER TABLE system_log_file ADD COLUMN IF NOT EXISTS content_hmac TEXT;
ALTER TABLE system_log_file DROP COLUMN IF EXISTS sha256;
ALTER TABLE system_log_file DROP CONSTRAINT IF EXISTS system_log_file_content_hmac_check;
ALTER TABLE system_log_file ADD CONSTRAINT system_log_file_content_hmac_check
    CHECK (content_hmac IS NULL OR content_hmac ~ '^[0-9a-f]{64}$');
COMMENT ON COLUMN system_log_file.content_hmac IS
    'HMAC-SHA256 (keyed-fingerprint secret, domain separated) of the fetched file as received; never a bare hash (contract §12).';

-- Audit classification (UI2_0_B1_02A section 4): keyed digests of device content are redacted, like anchor_hmac.
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('system_log_chunk', 'plaintext_hmac', 2, 'keyed digest of device log bytes'),
    ('system_log_file', 'content_hmac', 2, 'keyed digest of a fetched device file')
ON CONFLICT (table_name, column_name) DO NOTHING;
