-- V184 -- path analysis stage A1: route and interface columns (docs/design/PATH_ANALYSIS_DESIGN.md §3 and §7 A1, FROZEN;
-- docs/design/PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md D-1, RATIFIED).
--
-- Parse-scope extensions of reads already issued and gated (no new device command):
--   device_route.is_active      Palo Alto "show routing route" flag A (A0 §5b); NULL elsewhere.
--   device_route.is_ecmp        Palo Alto flag E only when that response's own flags legend declares E:ecmp (A0 §5b);
--                               Check Point iproute2 multipath groups ("nexthop" continuation lines, A0 §7).
--   device_route.metric         Palo Alto <metric> leaf; Check Point iproute2 "metric <n>" token.
--   device_route.next_hop_kind  IP | CONNECTED | DISCARD | NEXT_VR (Check Point "blackhole" = DISCARD).
--   device_interface.routing_context  Palo Alto virtual router of the interface (legacy routing engine only).
--   device_interface.evidence_grade   RUNTIME (a runtime read) | CONFIG_DERIVED (PAN-OS 12 interfaces from the stored
--                                     effective-running configuration, V171 / PO 2026-10-24).
--
-- Additive and backward compatible: nullable columns only, no default, no backfill. NULL in any of them means UNKNOWN
-- (D-1), never "false", "0" or a default kind; rows of older runs keep NULL.
--
-- Device deletion (D-1): no new table is added here. The new columns live on device_route and device_interface rows,
-- which JooqDeviceRepository.deleteDevice already removes with the device's inventory runs, so nothing is orphaned.
-- policy_target_binding belongs to stage A2 and is not created by this migration.
--
-- Audit classification (UI2_0_B1_02A §4, V153 convention): routing_context is a device routing-instance name and is
-- tier-2 redacted; the booleans, the metric and the two closed-vocabulary enums are audited in full
-- (AuditPresentationAllowlist).
SELECT set_config('app.actor_fingerprint', 'migration:V184_path_analysis_route_interface_columns', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

ALTER TABLE device_route ADD COLUMN IF NOT EXISTS is_active BOOLEAN;
ALTER TABLE device_route ADD COLUMN IF NOT EXISTS is_ecmp BOOLEAN;
ALTER TABLE device_route ADD COLUMN IF NOT EXISTS metric BIGINT
    CHECK (metric IS NULL OR metric BETWEEN 0 AND 4294967295);
ALTER TABLE device_route ADD COLUMN IF NOT EXISTS next_hop_kind TEXT
    CHECK (next_hop_kind IS NULL OR next_hop_kind IN ('IP', 'CONNECTED', 'DISCARD', 'NEXT_VR'));

ALTER TABLE device_interface ADD COLUMN IF NOT EXISTS routing_context TEXT
    CHECK (routing_context IS NULL OR length(routing_context) BETWEEN 1 AND 128);
ALTER TABLE device_interface ADD COLUMN IF NOT EXISTS evidence_grade TEXT
    CHECK (evidence_grade IS NULL OR evidence_grade IN ('RUNTIME', 'CONFIG_DERIVED'));

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('device_interface', 'routing_context', 2, 'device virtual-router name')
ON CONFLICT (table_name, column_name) DO NOTHING;
