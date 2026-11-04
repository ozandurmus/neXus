-- V186 -- path analysis stage A3: topology snapshot header, conflicts and link overrides
-- (docs/design/PATH_ANALYSIS_DESIGN.md §3/§4/§7 A3, FROZEN; docs/design/PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md
-- D-1 and D-8, RATIFIED).
--
-- Number: V186, not V185. The parallel A2 lane (policy_target_binding, NAT evaluator) may add one migration; V185 is
-- left to it so the two branches never claim the same version. Whichever merges second must still be checked for order
-- (Flyway runs without outOfOrder: V185 must be applied before V186 on any database that already has V184).
--
-- Hybrid storage (D-1): the graph (nodes, segments, attachments) is built in memory from inventory and is NOT stored.
-- Persisted here, and only here:
--   topology_snapshot       one header row per build: opaque id, build time, input fingerprints, counts.
--   topology_conflict       the conflicts one build found (HA_ROUTE_DRIFT, DUPLICATE_PRIVATE_PREFIX, ...).
--   topology_link_override  human-declared links; they survive every rebuild.
-- No JSONB graph blob, no address or name inside any id. NULL in any column below means UNKNOWN, never false/0/default;
-- the two override device references are the one exception, documented below (NULL = the device was deleted).
--
-- Device deletion (D-1 rule, both directions stated):
--   topology_conflict       REMOVED with the device (ON DELETE CASCADE on device_id and peer_device_id): a conflict is
--                           derived data and is recomputed by the next rebuild.
--   topology_link_override  ORPHAN-MARKED, never removed (ON DELETE SET NULL, and trg_topology_override_orphan_mark sets
--                           orphaned_at in the same statement): an override that no longer matches is reported, never
--                           dropped (design §4). Its context and interface name stay, redacted in audit.
--   topology_snapshot       references no device.
--
-- Audit (V153 convention): only topology_link_override is operator-written and carries trg_audit_*; its interface
-- names, contexts, assumed subnet and free-text note are tier-2 redacted. topology_snapshot and topology_conflict are
-- derived, recomputable rows written by a rebuild (whose authorization is recorded in authz_decisions): no audit trigger,
-- like compliance_evaluation (V59) -- auditing every rebuild is the unbounded audit growth D-1 rejected. Their one
-- address column (topology_conflict.network) and context are still registered tier 2, so they stay redacted if a
-- trigger is ever added. Snapshots are pruned by the service (newest kept); conflicts go with their snapshot.
SELECT set_config('app.actor_fingerprint', 'migration:V186_path_analysis_topology', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS topology_snapshot (
    snapshot_id                 TEXT        PRIMARY KEY CHECK (snapshot_id ~ '^tps-[0-9a-f-]{36}$'),
    built_at                    TIMESTAMPTZ NOT NULL,
    built_by                    TEXT        NOT NULL,
    inventory_fingerprint       TEXT        NOT NULL CHECK (inventory_fingerprint ~ '^[0-9a-f]{64}$'),
    override_fingerprint        TEXT        NOT NULL CHECK (override_fingerprint ~ '^[0-9a-f]{64}$'),
    device_count                INTEGER     NOT NULL CHECK (device_count >= 0),
    devices_without_inventory   INTEGER     NOT NULL CHECK (devices_without_inventory >= 0),
    node_count                  INTEGER     NOT NULL CHECK (node_count >= 0),
    cluster_unit_count          INTEGER     NOT NULL CHECK (cluster_unit_count >= 0),
    cloud_count                 INTEGER     NOT NULL CHECK (cloud_count >= 0),
    segment_count               INTEGER     NOT NULL CHECK (segment_count >= 0),
    attachment_count            INTEGER     NOT NULL CHECK (attachment_count >= 0),
    conflict_count              INTEGER     NOT NULL CHECK (conflict_count >= 0),
    conflicts_stored            INTEGER     NOT NULL CHECK (conflicts_stored >= 0 AND conflicts_stored <= conflict_count),
    overrides_applied           INTEGER     NOT NULL CHECK (overrides_applied >= 0),
    overrides_unmatched         INTEGER     NOT NULL CHECK (overrides_unmatched >= 0),
    build_millis                BIGINT      NOT NULL CHECK (build_millis >= 0)
);

CREATE INDEX IF NOT EXISTS idx_topology_snapshot_built_at ON topology_snapshot (built_at DESC);

CREATE TABLE IF NOT EXISTS topology_link_override (
    override_id       TEXT        PRIMARY KEY CHECK (override_id ~ '^tlo-[0-9a-f-]{36}$'),
    action            TEXT        NOT NULL CHECK (action IN ('FORCE_LINK', 'SUPPRESS_LINK')),
    link_kind         TEXT        NOT NULL CHECK (link_kind IN ('STATIC_L3', 'IPSEC_TUNNEL', 'L2_TRUNK', 'MPLS_WAN')),
    -- NULL only once the device was deleted (orphaned_at is then set); never NULL at insert (service-checked).
    a_device_id       TEXT        REFERENCES devices(device_id) ON DELETE SET NULL,
    a_context         TEXT        NOT NULL CHECK (length(a_context) BETWEEN 1 AND 128),
    a_interface_name  TEXT        NOT NULL CHECK (length(a_interface_name) BETWEEN 1 AND 128),
    b_device_id       TEXT        REFERENCES devices(device_id) ON DELETE SET NULL,
    b_context         TEXT        NOT NULL CHECK (length(b_context) BETWEEN 1 AND 128),
    b_interface_name  TEXT        NOT NULL CHECK (length(b_interface_name) BETWEEN 1 AND 128),
    assumed_subnet    TEXT        CHECK (assumed_subnet IS NULL OR assumed_subnet ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$'),
    reason_code       TEXT        NOT NULL CHECK (reason_code IN ('UNMANAGED_INTERMEDIATE_DEVICE', 'TUNNEL',
                                      'DUPLICATE_PRIVATE_PREFIX_SAME_NETWORK', 'DUPLICATE_PRIVATE_PREFIX_DIFFERENT_NETWORK',
                                      'SEPARATE_ROUTING_DOMAIN', 'VERIFIED_CABLING', 'OTHER')),
    note              TEXT        CHECK (note IS NULL OR length(note) BETWEEN 1 AND 500),
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    revoked_by        TEXT,
    orphaned_at       TIMESTAMPTZ,
    CONSTRAINT topology_link_override_revocation_complete
        CHECK ((revoked_at IS NULL AND revoked_by IS NULL) OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)),
    CONSTRAINT topology_link_override_orphan_marked
        CHECK ((a_device_id IS NOT NULL AND b_device_id IS NOT NULL) OR orphaned_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_topology_link_override_active
    ON topology_link_override (created_at) WHERE revoked_at IS NULL;

-- ON DELETE SET NULL rewrites the row as an UPDATE: mark it orphaned in that same update, so the CHECK above holds.
CREATE OR REPLACE FUNCTION fn_topology_override_orphan_mark() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.a_device_id IS NULL OR NEW.b_device_id IS NULL) AND NEW.orphaned_at IS NULL THEN
        NEW.orphaned_at := now();
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_topology_override_orphan_mark ON topology_link_override;
CREATE TRIGGER trg_topology_override_orphan_mark
    BEFORE UPDATE ON topology_link_override
    FOR EACH ROW EXECUTE FUNCTION fn_topology_override_orphan_mark();

DROP TRIGGER IF EXISTS trg_audit_topology_link_override ON topology_link_override;
CREATE TRIGGER trg_audit_topology_link_override
    AFTER INSERT OR UPDATE OR DELETE ON topology_link_override
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('override_id');

CREATE TABLE IF NOT EXISTS topology_conflict (
    conflict_id     TEXT        NOT NULL CHECK (conflict_id ~ '^tc-[0-9a-f]{20}$'),
    snapshot_id     TEXT        NOT NULL REFERENCES topology_snapshot(snapshot_id) ON DELETE CASCADE,
    kind            TEXT        NOT NULL CHECK (kind IN ('DUPLICATE_PRIVATE_PREFIX', 'OVERLAPPING_PREFIX',
                                    'DUPLICATE_ADDRESS', 'HA_ROUTE_DRIFT', 'CLUSTER_MEMBERS_DISAGREE',
                                    'INTERFACE_WITHOUT_CONTEXT', 'ADDRESS_PREFIX_UNKNOWN', 'NEXT_HOP_UNREACHABLE',
                                    'NEXT_HOP_AMBIGUOUS', 'OVERRIDE_NO_MATCH')),
    resolution      TEXT        NOT NULL CHECK (resolution IN ('OPEN', 'OVERRIDDEN')),
    device_id       TEXT        REFERENCES devices(device_id) ON DELETE CASCADE,
    peer_device_id  TEXT        REFERENCES devices(device_id) ON DELETE CASCADE,
    context         TEXT        CHECK (context IS NULL OR length(context) BETWEEN 1 AND 128),
    network         TEXT        CHECK (network IS NULL OR network ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$'),
    override_id     TEXT        REFERENCES topology_link_override(override_id),
    PRIMARY KEY (snapshot_id, conflict_id)
);

CREATE INDEX IF NOT EXISTS idx_topology_conflict_device ON topology_conflict (device_id) WHERE device_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_topology_conflict_peer ON topology_conflict (peer_device_id) WHERE peer_device_id IS NOT NULL;

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('topology_link_override', 'a_context', 2, 'device virtual-context name'),
    ('topology_link_override', 'a_interface_name', 2, 'device interface name'),
    ('topology_link_override', 'b_context', 2, 'device virtual-context name'),
    ('topology_link_override', 'b_interface_name', 2, 'device interface name'),
    ('topology_link_override', 'assumed_subnet', 2, 'operator-declared network address'),
    ('topology_link_override', 'note', 2, 'operator free text (override note, hidden from aiview)'),
    ('topology_conflict', 'context', 2, 'device virtual-context name'),
    ('topology_conflict', 'network', 2, 'device network address')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- The service builds and prunes snapshots (and their conflicts); overrides are inserted and revoked, never deleted.
GRANT SELECT, INSERT, DELETE ON topology_snapshot, topology_conflict TO ui2_app;
GRANT SELECT, INSERT, UPDATE ON topology_link_override TO ui2_app;
