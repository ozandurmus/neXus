-- V167 -- Spike Detective data collection: the data-directory state of DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §8.3
-- (PO decision 2026-10-20 "Sözleşmeyi düzelt, sonra aç": the §8.3 fetch list amended from the layout review record
-- SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md §4, then the collection built). No gate row, command, timeout, bound or
-- frequency changes here: C4 cp_syslog_collect_ls_spike, C5 cp_syslog_collect_ls_spike_dir and C8 cp_syslog_collect_fetch
-- are the V160 rows as approved on 2026-10-17. system_log_settings.spike_data_enabled keeps its V160 default (false).
--
-- One table, system_log_spike_directory: one row per data directory a C4 listing printed for a device.
--   * The first listing of a device is the baseline: every directory present is stored as BASELINE ("present before
--     monitoring") and is never fetched. A directory is new while its name HMAC has no row.
--   * Names are stored only as name_hmac: HMAC-SHA256 under the product privacy key (the keyed-fingerprint secret of
--     every system-log name HMAC; the same value as system_log_file.remote_name_hmac of the directory's archive), plus
--     name_class, the name's class in the closed vocabulary of the phase A summaries (stems kept, digit runs N/NN/NNN,
--     other words X; never the name).
--   * members: the per-class member summary of the C5 listing ({class: {count, bytes, fetched}}); never a member name.
--     The clear names exist only inside the encrypted archive (system_log_file kind cp_spike_data), in its manifest.
--   * Not audited by fn_audit_capture, like the other collected listing state of NOT_MUTATION_BEARING
--     (AuditCoverageCompletenessTest): rows are written only by the collection job (a baseline can be thousands of rows
--     in one run), never by an operator; the jobs row, the system_log_run row and the archive's system_log_file row
--     (all audited) are the record. So no column needs an audit classification.
--   * device_id and job_id carry no foreign key (as V160); file_id references the archive row and is cleared if that
--     row is ever deleted (retention marks pruned_at, it does not delete).
-- Repeatable inside one rolled-back transaction (IF NOT EXISTS), so the dry run can run the file twice.
SELECT set_config('app.actor_fingerprint', 'migration:V167_system_log_spike_directory', true);
SELECT set_config('app.action_id', 'system_log_spike_directory_by_migration', true);

CREATE TABLE IF NOT EXISTS system_log_spike_directory (
    directory_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    name_hmac TEXT NOT NULL CHECK (name_hmac ~ '^[0-9a-f]{64}$'),
    name_class TEXT NOT NULL CHECK (name_class ~ '^[A-Za-z0-9._:+?-]{1,160}$'),
    mtime BIGINT NOT NULL CHECK (mtime >= 0),
    state TEXT NOT NULL CHECK (state IN ('BASELINE', 'COLLECTED', 'NOTHING_TO_FETCH', 'TOO_LARGE', 'NAME_REJECTED',
        'ABSENT')),
    member_count INTEGER NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    fetched_files INTEGER NOT NULL DEFAULT 0 CHECK (fetched_files >= 0 AND fetched_files <= member_count),
    member_bytes BIGINT NOT NULL DEFAULT 0 CHECK (member_bytes >= 0),
    fetched_bytes BIGINT NOT NULL DEFAULT 0 CHECK (fetched_bytes >= 0),
    members JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(members) = 'object'
        AND NOT members ?| ARRAY['name', 'names', 'file_name', 'file_names', 'path', 'paths', 'line', 'lines', 'text', 'raw']),
    file_id TEXT REFERENCES system_log_file(file_id) ON DELETE SET NULL,
    job_id TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT system_log_spike_directory_device_name UNIQUE (device_id, name_hmac),
    CONSTRAINT system_log_spike_directory_file_state CHECK (file_id IS NULL OR state = 'COLLECTED')
);
CREATE INDEX IF NOT EXISTS system_log_spike_directory_device_time
    ON system_log_spike_directory(device_id, recorded_at DESC);
COMMENT ON TABLE system_log_spike_directory IS
    'Spike Detective data directories per device (contract §8.3): baseline, collected and skipped directories; names as HMAC plus a name class only.';
COMMENT ON COLUMN system_log_spike_directory.name_hmac IS
    'HMAC-SHA256 (keyed-fingerprint secret) of the directory name; equal to system_log_file.remote_name_hmac of its archive.';
COMMENT ON COLUMN system_log_spike_directory.mtime IS 'Directory last-write time, device epoch seconds, from the C4 listing.';
COMMENT ON COLUMN system_log_spike_directory.members IS
    'Per member-name class: count, bytes and fetched count from the C5 listing; never a member name.';

GRANT SELECT, INSERT, UPDATE, DELETE ON system_log_spike_directory TO ui2_app;
