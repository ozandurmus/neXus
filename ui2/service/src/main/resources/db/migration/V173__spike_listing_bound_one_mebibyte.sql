-- V173 -- PO decision 2026-10-21 ("Şimdi A, sonra B"): the Spike Detective listing C4 (cp_syslog_collect_ls_spike) may
-- return up to 1 MiB instead of 256 KiB (docs/design/PO_DECISION_RECORD_2026_10_21_SPIKE_LISTING_BOUND.md, RATIFIED;
-- option A of docs/design/SPIKE_LISTING_CHUNKING_PROPOSAL.md).
--
-- Why: one gateway holds about 3,631 data directories and its listing exceeds 256 KiB, so its spike source is UNKNOWN
-- (LISTING_AT_BOUND) on every run and never takes a baseline. A file name is at most 255 bytes and a listing line is at
-- most 33 + 255 bytes, so the measured 3,638 entries fit in 3,638 x 288 = 1,047,744 bytes, below the
-- new bound whatever the names' length.
--
-- Only the output bound changes, and it lives in the max_frequency text (gate_registry has no separate bytes column,
-- as in V160). The command literal, shell context, transport, timeout (30 s), retry (none), frequency (once per device
-- per job window), session rule and unsupported-behaviour text are untouched. The rule "a listing that reaches its bound
-- is UNKNOWN (LISTING_AT_BOUND) and is never processed partially" is unchanged; the code constant is
-- CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES (1 MiB). The source pointer cites the amendment. Option B (newest-first -t) is a
-- later, separate decision and is not part of this migration.
-- Scoped by gate_id, vendor and platform_role_scope. Re-running is a no-op.
SELECT set_config('app.actor_fingerprint', 'migration:V173_spike_listing_bound_one_mebibyte', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);

UPDATE gate_registry SET
    max_frequency = 'once per device per job window; max bytes: 1 MiB',
    source_document_pointer = source_document_pointer
        || '; output bound raised 256 KiB to 1 MiB by PO 2026-10-21 (docs/design/PO_DECISION_RECORD_2026_10_21_SPIKE_LISTING_BOUND.md, option A)'
WHERE gate_id = 'cp_syslog_collect_ls_spike'
  AND vendor = 'check_point'
  AND platform_role_scope = 'cp_gaia_gateway'
  AND max_frequency = 'once per device per job window; max bytes: 256 KiB';
