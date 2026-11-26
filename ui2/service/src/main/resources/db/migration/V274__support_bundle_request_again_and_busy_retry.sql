-- V274 -- support bundle collection: "Request again", why a request did not start, and one automatic quiet-hour retry
-- (docs/design/SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md; PO 2026-11-26).
--
--   1. previous_request_id, auto_retry: links a manual "Request again" or the service's one automatic follow-up request to
--      the request it follows. Partial unique index uq_support_bundle_auto_retry_once enforces at most one automatic retry
--      per expired request; an automatic retry row itself has auto_retry = TRUE so it is never retried again.
--   2. hold_reasons: closed-vocabulary array recording what held a request from starting (MODULE_DRAIN, DEVICE_JOB_RUNNING,
--      COLLECTION_WINDOW, HA_PEER_RUNNING, FLEET_CAP). Never free text.
--   3. retry_outcome: closed-vocabulary outcome of the one-shot automatic retry evaluation on an expired-busy request.
--
-- Additive expand-only schema (MigrationAdditiveFenceTest): NOT VALID constraints enforce every new INSERT/UPDATE without
-- scanning historical rows or dropping constraints, so old images keep working on the new schema. Repeatable inside one
-- rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V274_support_bundle_request_again_and_busy_retry', true);
SELECT set_config('app.action_id', 'support_bundle_schema_by_migration', true);

ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS previous_request_id UUID;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS auto_retry BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS hold_reasons TEXT[] NOT NULL DEFAULT '{}'::text[];
ALTER TABLE support_bundle_request ADD COLUMN IF NOT EXISTS retry_outcome TEXT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'support_bundle_request_previous_fk') THEN
        ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_previous_fk
            FOREIGN KEY (previous_request_id) REFERENCES support_bundle_request(id) ON DELETE SET NULL NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'support_bundle_request_previous_self_check') THEN
        ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_previous_self_check
            CHECK (previous_request_id IS NULL OR previous_request_id <> id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'support_bundle_request_auto_retry_parent_check') THEN
        ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_auto_retry_parent_check
            CHECK (NOT auto_retry OR previous_request_id IS NOT NULL) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'support_bundle_request_hold_reasons_check') THEN
        ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_hold_reasons_check
            CHECK (hold_reasons <@ ARRAY['MODULE_DRAIN', 'DEVICE_JOB_RUNNING', 'COLLECTION_WINDOW', 'HA_PEER_RUNNING', 'FLEET_CAP']::text[]) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'support_bundle_request_retry_outcome_check') THEN
        ALTER TABLE support_bundle_request ADD CONSTRAINT support_bundle_request_retry_outcome_check
            CHECK (retry_outcome IS NULL OR retry_outcome IN (
                'EVALUATING', 'RETRY_SCHEDULED', 'SKIPPED_BUSINESS_HOURS', 'SKIPPED_QUOTA',
                'SKIPPED_ALREADY_REREQUESTED', 'SKIPPED_INELIGIBLE')) NOT VALID;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_support_bundle_auto_retry_once
    ON support_bundle_request (previous_request_id) WHERE auto_retry = TRUE;

CREATE INDEX IF NOT EXISTS idx_support_bundle_previous_request
    ON support_bundle_request (previous_request_id) WHERE previous_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_support_bundle_auto_retry_pending
    ON support_bundle_request (finished_at)
    WHERE state = 'FAILED' AND outcome = 'NOT_STARTED_DEVICE_BUSY' AND auto_retry = FALSE AND retry_outcome IS NULL;
