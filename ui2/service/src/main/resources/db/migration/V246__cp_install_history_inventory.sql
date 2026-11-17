-- V246 -- Check Point install history, every inventory run (PO approval 2026-11-17, chat: "Evet, onaylıyorum").
-- Supersedes the V245 one-off. One read of /opt/CPInstLog per inventory run of a Check Point Gaia gateway (never a
-- management server, a VSX host or a session that landed in clish); the base-install and Jumbo-install dates it returns
-- are stored in device_install_event. Exact approved literal:
--   unset BASH_ENV ENV; cd /opt/CPInstLog 2>/dev/null || exit 0; for f in $(ls -1tr install_fw1_R*.elg install_SecurePlatform_HOTFIX_*JUMBO*.log 2>/dev/null | tail -n 20); do echo "$(date -r "$f" +%Y-%m-%dT%H:%M:%S) $f $(grep -a -o -m1 -E 'Bundle_T[0-9]+' "$f" | head -n1)"; done
-- Plain expert shell, never a login shell; 30 s; no retry. See
-- docs/design/PO_DECISION_RECORD_2026_11_17_CP_INSTALL_HISTORY_INVENTORY.md.
SELECT set_config('app.actor_fingerprint', 'migration:V246_cp_install_history_inventory', true);
SELECT set_config('app.action_id', 'gate_registry_seed_by_migration', true);

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('cp_install_history_inventory', 'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
     'unset BASH_ENV ENV; cd /opt/CPInstLog 2>/dev/null || exit 0; for f in $(ls -1tr install_fw1_R*.elg install_SecurePlatform_HOTFIX_*JUMBO*.log 2>/dev/null | tail -n 20); do echo "$(date -r "$f" +%Y-%m-%dT%H:%M:%S) $f $(grep -a -o -m1 -E ''Bundle_T[0-9]+'' "$f" | head -n1)"; done',
     'read', 'SIGNED_OFF', 30, 'none', 'each inventory run',
     'the inventory session',
     'a failure or no answer is logged in the worker log; the inventory outcome is unchanged',
     'none (file names, dates and Jumbo take numbers only)',
     '[]'::jsonb,
     'docs/design/PO_DECISION_RECORD_2026_11_17_CP_INSTALL_HISTORY_INVENTORY.md (PO chat approval 2026-11-17)')
ON CONFLICT (gate_id) DO NOTHING;

-- One row per install seen in the device's own install log. The device prints the file's modification time on its own
-- clock with no offset, so installed_at_local is a TIMESTAMP WITHOUT TIME ZONE: it is shown as the device's local time
-- and never converted. A file seen again only moves last_observed_at; rows are never deleted by the product.
-- No audit trigger: file names, dates and take numbers only, written by the product's own collection; the inventory
-- job row is the audited record.
CREATE TABLE device_install_event (
    event_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id           TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    kind                TEXT        NOT NULL CHECK (kind IN ('BASE_INSTALL', 'JUMBO_INSTALL')),
    product_version     TEXT        NOT NULL,
    jumbo_take          INTEGER,
    installed_at_local  TIMESTAMP   NOT NULL,
    file_name           TEXT        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    source              TEXT        NOT NULL DEFAULT 'cp_install_log' CHECK (source = 'cp_install_log'),
    first_observed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_observed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_install_event UNIQUE (device_id, file_name, installed_at_local)
);

CREATE INDEX idx_device_install_event_device_time ON device_install_event (device_id, installed_at_local);

GRANT SELECT, INSERT, UPDATE, DELETE ON device_install_event TO ui2_app;

-- Module roles (V243 style): the writer is the inventory worker; the readers are the service's history routes.
-- Roles are created outside the migrations; a role that does not exist is skipped.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE'),
        ('ui2_backup', 'SELECT, INSERT, UPDATE'),
        ('ui2_inventory', 'SELECT, INSERT, UPDATE'),
        ('ui2_configuration', 'SELECT, INSERT, UPDATE'),
        ('ui2_worker_general', 'SELECT, INSERT, UPDATE')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.device_install_event TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
