-- V185 -- path analysis stage A2: policy_target_binding (docs/design/PATH_ANALYSIS_DESIGN.md §2 G7, §3 and §7 A2,
-- FROZEN; docs/design/PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md D-1, RATIFIED).
--
-- One row per (enrolled device, virtual-system context, Check Point policy package, installation-target reference):
-- "this package names this gateway/cluster object as an installation target, and that object denotes this device".
-- Populated at policy collection from data already collected (no new device command): the package's
-- installation-targets uid from show-packages, joined to the enrolled device by the exact stored discovery key
-- (PolicyCollectionRepository.targets: stable identifier or cluster reference). install_on_ref is
-- PolicySnapshot.ref(policy_id, 'object', target_uid), the same opaque reference the CP mapper stores in a rule's
-- install-on, so a rule's install-on can be compared with it by exact equality and never by name.
--
--   device_id       enrolled device (devices.device_id).
--   context         the device's virtual-system reference as PolicySnapshot.Target.context carries it; '' = no virtual
--                   system (physical gateway). Part of the key, so never NULL.
--   policy_id       PolicySnapshot.Metadata.id of the package (policy_snapshot.policy_id; no FK: a package snapshot is
--                   published after its targets are resolved, and snapshots are never deleted).
--   install_on_ref  NULL = UNKNOWN (D-1): the device is a package target but the object reference is not proven; such a
--                   row never proves that a rule installed elsewhere skips this device.
--
-- Evaluation (HopPolicyEvaluator): a rule whose install-on does not include the evaluated device is skipped for that
-- device only when every install-on reference is a gateway object bound here to other devices and not to this one;
-- anything else stays UNKNOWN (fail closed). Rows are written as a difference (insert missing, delete vanished), so a
-- repeated collection with the same targets adds no audit row.
--
-- Device deletion (D-1): rows are REMOVED with the device (ON DELETE CASCADE from devices; JooqDeviceRepository.deleteDevice
-- runs inside an audited transaction, so the cascade is audited too). Nothing is orphan-marked.
--
-- Audit classification (UI2_0_B1_02A §4, V5/V153 convention): context is a device virtual-system reference and is tier-2
-- redacted (as devices.virtual_system_ref in V153); binding_id, device_id, policy_id and install_on_ref are opaque
-- identifiers audited in full (AuditPresentationAllowlist).
SELECT set_config('app.actor_fingerprint', 'migration:V185_path_analysis_policy_target_binding', true);
SELECT set_config('app.action_id', 'audit_redaction_policy_seed_by_migration', true);

CREATE TABLE IF NOT EXISTS policy_target_binding (
    binding_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    context         TEXT NOT NULL CHECK (length(context) <= 128),
    policy_id       TEXT NOT NULL CHECK (length(policy_id) BETWEEN 1 AND 128),
    install_on_ref  TEXT CHECK (install_on_ref IS NULL OR length(install_on_ref) BETWEEN 1 AND 128)
);

CREATE UNIQUE INDEX IF NOT EXISTS policy_target_binding_key
    ON policy_target_binding (policy_id, device_id, context, coalesce(install_on_ref, ''));
CREATE INDEX IF NOT EXISTS policy_target_binding_device
    ON policy_target_binding (device_id, context);

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_target_binding TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_policy_target_binding ON policy_target_binding;
CREATE TRIGGER trg_audit_policy_target_binding
    AFTER INSERT OR UPDATE OR DELETE ON policy_target_binding
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('binding_id');

INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('policy_target_binding', 'context', 2, 'device virtual-system reference')
ON CONFLICT (table_name, column_name) DO NOTHING;
