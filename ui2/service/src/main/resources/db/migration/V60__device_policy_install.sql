-- V60 -- "policy installed" per gateway (PO, 2026-09-23; POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md, APPROVED).
-- Read in the inventory session every evening at 23:00 Europe/Istanbul: Check Point `cpstat -f policy fw`, Palo
-- Alto `show jobs all` (latest finished commit). One row per device: the policy name, the install time exactly as
-- the device reported it, the same time parsed (NULL when the text could not be parsed), and when it was read.
-- The PO's own nightly mail query reads this table. Never a user name, never policy content.
CREATE TABLE device_policy_install (
    device_id          TEXT        PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    policy_name        TEXT,
    installed_at_text  TEXT,
    installed_at       TIMESTAMPTZ,
    source_read        TEXT        NOT NULL,
    observed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON device_policy_install TO ui2_app;

SELECT set_config('app.actor_fingerprint', 'migration:V60_device_policy_install', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_policy_install_cpstat', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'bash -lc ''cpstat -f policy fw''', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the inventory session', 'no install-time line -> UNKNOWN; the inventory run continues',
     'none expected (policy name, install time)', '[]'::jsonb,
     'docs/design/POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md #1 -- PO approved 2026-09-23; bindings UNVERIFIED until measured'),
    ('pan_policy_install_show_jobs', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API',
     '<show><jobs><all/></jobs></show>', 'read', 'SIGNED_OFF', 30, 'none', 'once per device per run',
     'the inventory key', 'no finished commit in the list -> UNKNOWN; the inventory run continues',
     'job entries carry a user name: never stored', '[]'::jsonb,
     'docs/design/POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md #2 -- PO approved 2026-09-23; bindings UNVERIFIED until measured');
