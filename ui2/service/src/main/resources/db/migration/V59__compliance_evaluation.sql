-- V59 -- compliance evaluations persisted (2026-09-23). Screens used to evaluate every device on open (~0.4 s of
-- compliance-service CPU per device, ~18 s for the fleet, lost on every restart). The evaluation is a pure function
-- of the configuration text and the compliance rule set, so it is computed once per (canonical hash, rule-set
-- fingerprint) in the background and stored here; Overview and Compliance read it. One row per device: the latest
-- evaluation. Derived data (recomputable from the configuration run), like cluster_member_diff: not audited.
CREATE TABLE compliance_evaluation (
    device_id          TEXT        PRIMARY KEY,
    canonical_hash     TEXT,
    config_hash        TEXT        NOT NULL,
    rules_fingerprint  TEXT        NOT NULL,
    evaluated_at       TIMESTAMPTZ NOT NULL,
    result             JSONB       NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON compliance_evaluation TO ui2_app;
