-- Reference set for the aiview masking leak audit: salted sha256 hashes of the raw identifiers, never the values.
--
-- Run it inside the database pod (read-only transaction, nothing is written, no table or temp object is created):
--   kubectl -n ui2 exec -i ui2-db-0 -- sh -c \
--     'psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -At -v salt='"$SALT"' -f -' < export_reference.sql
-- Output: "#src" meta lines (category, table.column, row count -- names of columns, never values) and
-- "<CATEGORY>\t<sha256 hex of salt || LF || normalized value>" lines.  Normalization = lower(btrim(value)); values
-- shorter than 4 characters and a few placeholders are skipped (see below).  The probe (probe.mjs) uses the same
-- salt and normalization.  The salt makes the file useless for offline dictionary attacks on IPv4 space.
--
-- Categories: HOST (device hostnames, discovery display names, Check Point server/domain names, first DNS label of
-- dotted names), ADDR (management / interface / discovery addresses, the whole value), IPV4 (each IPv4 inside any
-- of those values), SERIAL (platform serial, recorded identities, identity-mismatch presented values, discovery
-- stable identifiers, Panorama device serial, ASA/FortiGate unit serials), CLUSTER (cluster references, HA pair
-- claim, discovery cluster reference), USER (policy-change authors, credential user names), NAME_WORD (each word of
-- 4+ characters of a USER or CLUSTER value; low confidence, reported separately).
-- P1 planes (when their tables exist): ACI cutover segment names (HOST), segment and peer blocks and assessment flow
-- addresses (ADDR), segment / assessment / zone-matrix actors (USER), Zone Matrix zone names (HOST) and zone blocks
-- (ADDR) from the canonical version content, static-route preview destinations, next hops and representative hosts
-- (ADDR), requesters (USER) and units (CLUSTER).  A block "a/n" also yields its address part "a" (ADDR, trailing
-- separators removed the way the probe tokenizes) so a block printed as text is found by its network address.
-- Later planes (V251 - V274 and older actor columns): Script Execution script names and declared egress host:port
-- targets and the LDAP servers (HOST, or ADDR for an IP literal), failover / cluster-run references (CLUSTER),
-- Infoblox / Management Center grid member and notification / support-upload hosts (HOST, ADDR), log detection
-- evidence/finding key tokens (ADDR, SERIAL), Zone Matrix violated-cell witness ranges (ADDR), and the person or
-- principal columns of older and newer migrations (USER).  Opaque ids, actor fingerprints, device-id lists, closed
-- enums, V271 grid_member.health_messages (pre-masked text, never raw vendor JSON), V273 closed-vocabulary template
-- columns (log_template / log_provisional_template / log_finding.template_text), and V274 support_bundle_request
-- closed-vocabulary / UUID columns (previous_request_id, auto_retry, hold_reasons, retry_outcome) are not sources.
\set ON_ERROR_STOP on
BEGIN READ ONLY;

-- Source columns that exist in this schema (column names only; a missing one is skipped and shows as absent in the meta lines).
-- Plain columns are read as text.  JSON sources (shape = elements / zone_names / zone_blocks / witness_ranges / flow_addresses) read
-- text out of a JSONB column or a TEXT column holding JSON; a value that is not valid JSON, or not of the expected
-- shape, contributes nothing (never an error).  The shape host_port_array reads a TEXT[] column of host:port items:
-- the port is cut off, an IP literal (digits and dots, or any value with a colon left) is exported as ADDR and
-- anything else as HOST (the row's category is only the label of the source; the column must be of type ARRAY).  Every table and column name is checked against
-- information_schema.columns before any dynamic SQL is built, so an older schema runs cleanly.
WITH plain(cat, tbl, col) AS (VALUES
    ('HOST',    'devices',                  'observed_hostname'),
    ('HOST',    'discovery_candidate',      'display_name'),
    ('HOST',    'cp_domain_server_placement', 'server_name'),
    ('HOST',    'cp_domain_server_placement', 'multi_domain_server'),
    ('HOST',    'cp_domain_server_placement', 'domain_name'),
    ('ADDR',    'endpoints',                'address_ref'),
    ('ADDR',    'device_interface_address', 'address'),
    ('ADDR',    'discovery_candidate',      'own_address'),
    ('ADDR',    'discovery_candidate',      'management_address'),
    ('ADDR',    'discovery_run',            'management_address'),
    ('ADDR',    'management_endpoint_ssh_trust', 'management_address'),
    ('ADDR',    'management_endpoint_pan_cert_trust', 'management_address'),
    ('ADDR',    'https_endpoint_cert_trust', 'management_address'),
    ('ADDR',    'ssh_host_key_observation', 'management_address'),
    ('SERIAL',  'device_platform_facts',    'serial_number'),
    ('SERIAL',  'devices',                  'recorded_identity_primary'),
    ('SERIAL',  'devices',                  'recorded_identity_secondary'),
    ('SERIAL',  'devices',                  'identity_mismatch_presented_primary'),
    ('SERIAL',  'devices',                  'identity_mismatch_presented_secondary'),
    ('SERIAL',  'discovery_candidate',      'stable_identifier'),
    ('SERIAL',  'panorama_assignment',      'device_serial'),
    ('SERIAL',  'ha_pair_identity', 'unit_serial_low'),
    ('SERIAL',  'ha_pair_identity', 'unit_serial_high'),
    ('CLUSTER', 'devices',                  'cluster_member_ref'),
    ('CLUSTER', 'devices',                  'ha_pair_claim'),
    ('CLUSTER', 'discovery_candidate',      'cluster_reference'),
    ('USER',    'policy_rule_history',      'changed_by'),
    ('USER',    'credentials',              'username'),
    -- ACI cutover (V217): segment name and the people who wrote it.
    ('HOST',    'cutover_segment',          'name'),
    ('USER',    'cutover_segment',          'created_by'),
    ('USER',    'cutover_segment',          'updated_by'),
    ('USER',    'cutover_assessment',       'actor'),
    -- Zone Matrix (V216): authors and publishers of zone-model versions and revisions.
    ('USER',    'zone_matrix_version',      'created_by'),
    ('USER',    'zone_matrix_version',      'updated_by'),
    ('USER',    'zone_matrix_version',      'published_by'),
    ('USER',    'zone_matrix_revision',     'actor'),
    -- Static route preview (V252): typed destination, next hop, representative host, requester and unit.
    ('ADDR',    'static_route_preview',     'destination_cidr'),
    ('ADDR',    'static_route_preview',     'nexthop_ip'),
    ('ADDR',    'static_route_preview',     'representative_host'),
    ('USER',    'static_route_preview',     'requested_by'),
    ('CLUSTER', 'static_route_preview',     'unit_id'),
    -- Script Execution (V251, V258): operator-chosen script name, version author, run requester.
    ('HOST',    'script',                   'name'),
    ('USER',    'script_version',           'created_by'),
    ('USER',    'script_run',               'requested_by'),
    -- SNMP polling (V268), log detection (V272, V273), failover actor authority (V260): the people and principals who acted.
    -- log_finding_action.actor is an actor fingerprint (tier 2 in V272) kept as USER defense-in-depth alongside *_by columns;
    -- log_finding.key_token and log_event_count.key_token normally hold closed labels or keyed opaque tokens (k-<12 hex>)
    -- and are included as ADDR/SERIAL defense-in-depth so any unexpected raw address or serial is caught.
    ('USER',    'snmp_polling_settings',    'updated_by'),
    ('USER',    'snmp_polling_override',    'updated_by'),
    ('USER',    'log_detection_settings',   'updated_by'),
    ('USER',    'log_finding',              'review_by'),
    ('ADDR',    'log_finding',              'key_token'),
    ('SERIAL',  'log_finding',              'key_token'),
    ('ADDR',    'log_event_count',          'key_token'),
    ('USER',    'log_finding_action',       'actor'),
    ('USER',    'log_template_catalog',     'created_by'),
    ('USER',    'log_template_catalog',     'frozen_by'),
    ('USER',    'failover_actor_authority', 'actor'),
    -- Failover runs, approvals, schedules, resumes, sequences and config sync (V31, V101, V137, V170, V178, V256):
    -- cluster_ref is the cluster reference also stored in devices.cluster_member_ref; the *_by columns are principals.
    ('CLUSTER', 'failover_run',             'cluster_ref'),
    ('CLUSTER', 'failover_approval',        'cluster_ref'),
    ('CLUSTER', 'failover_schedules',       'cluster_ref'),
    ('CLUSTER', 'failover_quarantine',      'cluster_ref'),
    ('CLUSTER', 'failover_resume',          'cluster_ref'),
    ('CLUSTER', 'failover_sequence',        'cluster_ref'),
    ('CLUSTER', 'pan_config_sync',          'cluster_ref'),
    ('CLUSTER', 'cluster_member_diff',      'cluster_ref'),
    ('USER',    'failover_run',             'requested_by'),
    ('USER',    'failover_approval',        'approved_by'),
    ('USER',    'failover_approval',        'revoked_by'),
    ('USER',    'failover_approval',        'initiated_by'),
    ('USER',    'failover_quarantine',      'acknowledged_by'),
    ('USER',    'failover_resume',          'initiated_by'),
    ('USER',    'failover_resume',          'approved_by'),
    ('USER',    'failover_resume',          'approval_ack_by'),
    ('USER',    'failover_resume',          'business_hours_ack_by'),
    ('USER',    'failover_schedules',       'cancelled_by'),
    ('USER',    'failover_sequence',        'initiated_by'),
    ('USER',    'pan_config_sync',          'initiated_by'),
    ('USER',    'ha_pair_identity',         'recorded_by'),
    ('USER',    'jobs',                     'diagnostic_viewed_by'),
    ('USER',    'jobs',                     'business_hours_ack_by'),
    ('USER',    'job_reconciliation',       'recorded_by'),
    -- Trust-store authorizations (V24, V35, V112).
    ('USER',    'management_endpoint_ssh_trust',      'authorized_by'),
    ('USER',    'management_endpoint_pan_cert_trust', 'authorized_by'),
    ('USER',    'https_endpoint_cert_trust',          'authorized_by'),
    -- Settings screens (V51, V100, V160, V203, V204, V223, V225, V236, V241): who last changed them.
    ('USER',    'notification_settings',    'updated_by'),
    ('USER',    'notification_routes',      'updated_by'),
    ('USER',    'system_log_settings',      'updated_by'),
    ('USER',    'scheduled_job_family',     'changed_by'),
    ('USER',    'ldap_settings',            'updated_by'),
    ('USER',    'local_password_policy_settings', 'updated_by'),
    ('USER',    'artefact_store_quota',     'updated_by'),
    ('USER',    'local_auth_mode_settings', 'updated_by'),
    ('USER',    'saml_settings',            'updated_by'),
    -- Packet Analysis, data sanitizer, reports, path analysis, support bundles, lifecycle catalog, backups.
    ('USER',    'packet_analysis_case',     'created_by'),
    ('USER',    'packet_analysis_capture',  'uploaded_by'),
    ('USER',    'packet_analysis_run',      'requested_by'),
    ('USER',    'packet_analysis_host_telemetry', 'uploaded_by'),
    ('USER',    'packet_analysis_host_telemetry', 'aligned_by'),
    ('USER',    'sanitize_job',             'created_by'),
    ('USER',    'sanitize_revert_job',      'created_by'),
    ('USER',    'report_run',               'requested_by'),
    ('USER',    'path_validation',          'submitted_by'),
    ('USER',    'topology_link_override',   'created_by'),
    ('USER',    'topology_link_override',   'revoked_by'),
    ('USER',    'topology_snapshot',        'built_by'),
    ('USER',    'support_bundle_request',   'requested_by'),
    ('USER',    'support_bundle_request',   'downloaded_by'),
    ('USER',    'support_bundle_request',   'deleted_by'),
    ('USER',    'support_bundle_request',   'cleanup_requested_by'),
    ('USER',    'support_bundle_upload',    'requested_by'),
    ('USER',    'lifecycle_catalog',        'imported_by'),
    ('USER',    'lifecycle_catalog_upload', 'uploaded_by'),
    ('USER',    'backup_deviation_record',  'resolved_by'),
    -- Zone Matrix access log and reference confirmations (V216, V220), policy change author names (V208), identity names.
    ('USER',    'zone_matrix_access_log',   'actor'),
    ('USER',    'zone_matrix_reference_confirmation', 'confirmed_by'),
    ('USER',    'policy_change_evidence',   'admin_name'),
    ('USER',    'actor_authz_state',        'directory_display_name'),
    ('USER',    'local_credentials',        'local_identity_name'),
    -- Hosts and addresses named in settings: Infoblox grid members (V72), notification targets (V51),
    -- support-bundle upload destinations (V240).  (directory_profiles.host of V26 was dropped with the table in V204.)
    ('HOST',    'grid_member',              'host_name'),
    ('ADDR',    'grid_member',              'vip_address'),
    ('HOST',    'notification_settings',    'syslog_host'),
    ('HOST',    'notification_settings',    'smtp_host'),
    ('HOST',    'support_bundle_upload',    'destination_host'),
    ('HOST',    'support_bundle_upload_host_key', 'host')
),
json(cat, tbl, col, shape) AS (VALUES
    ('ADDR',    'cutover_segment',          'blocks',      'elements'),
    ('ADDR',    'cutover_segment',          'peer_blocks', 'elements'),
    ('ADDR',    'cutover_assessment_flow',  'flow',        'flow_addresses'),
    ('HOST',    'zone_matrix_version',      'content',        'zone_names'),
    ('ADDR',    'zone_matrix_version',      'content',        'zone_blocks'),
    ('ADDR',    'zone_matrix_finding',      'violated_cells', 'witness_ranges'),
    -- Script Execution (V251, V258): declared egress host:port targets (TEXT[]), exported without the port.
    ('HOST',    'script',                   'egress',          'host_port_array'),
    ('HOST',    'script_run',               'egress_declared', 'host_port_array'),
    -- Directory service (V204): the configured LDAP servers (TEXT[], bare host names or addresses; the port is its own column).
    ('HOST',    'ldap_settings',            'servers',         'host_port_array')
),
-- One SELECT per source: plain columns as text; JSON sources parse each row's value (NULL when it is not valid JSON,
-- pg_input_is_valid is PostgreSQL 16) and read the named elements out of it.
s AS (
    SELECT p.cat, p.tbl, p.col, NULL::text AS shape, p.tbl || '.' || p.col AS label,
           format('SELECT %L::text AS cat, t.%I::text AS v FROM public.%I t', p.cat, p.col, p.tbl) AS q
    FROM plain p
    UNION ALL
    SELECT j.cat, j.tbl, j.col, j.shape, j.tbl || '.' || j.col || CASE j.shape
               WHEN 'elements' THEN '[]' WHEN 'zone_names' THEN '.zones[].name'
               WHEN 'zone_blocks' THEN '.zones[].blocks[]' WHEN 'witness_ranges' THEN '[].witness.ranges'
               WHEN 'host_port_array' THEN '[]{host}' ELSE '{addresses}' END,
           CASE WHEN j.shape = 'host_port_array' THEN
           format('SELECT CASE WHEN x.h ~ ''^[0-9.]+$'' OR x.h LIKE ''%%:%%'' THEN ''ADDR'' ELSE ''HOST'' END AS cat, x.h AS v '
                  || 'FROM public.%I t, LATERAL unnest(t.%I) e(v), '
                  || 'LATERAL (SELECT btrim(regexp_replace(btrim(e.v), '':[0-9]{1,5}$'', ''''), ''[]'') AS h) x', j.tbl, j.col)
           ELSE
           format('SELECT %L::text AS cat, x.v FROM public.%I t, LATERAL (SELECT CASE WHEN pg_input_is_valid(t.%I::text, ''jsonb'') '
                  || 'THEN t.%I::text::jsonb END AS j) d, LATERAL (%s) x(v)',
                  j.cat, j.tbl, j.col, j.col, CASE j.shape
                      WHEN 'elements' THEN
                          'SELECT e FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(d.j) = ''array'' THEN d.j ELSE ''[]''::jsonb END) e'
                      WHEN 'zone_names' THEN
                          'SELECT z->>''name'' FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.j->''zones'') = ''array'' '
                          || 'THEN d.j->''zones'' ELSE ''[]''::jsonb END) z'
                      WHEN 'zone_blocks' THEN
                          'SELECT b FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.j->''zones'') = ''array'' '
                          || 'THEN d.j->''zones'' ELSE ''[]''::jsonb END) z, '
                          || 'jsonb_array_elements_text(CASE WHEN jsonb_typeof(z->''blocks'') = ''array'' '
                          || 'THEN z->''blocks'' ELSE ''[]''::jsonb END) b'
                      WHEN 'witness_ranges' THEN
                          'SELECT c->''witness''->>k FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.j) = ''array'' '
                          || 'THEN d.j ELSE ''[]''::jsonb END) c, unnest(ARRAY[''source_range'', ''destination_range'']) k '
                          || 'WHERE jsonb_typeof(c->''witness'') = ''object'''
                      ELSE
                          'SELECT d.j->>k FROM unnest(ARRAY[''flowSource'', ''flowDestination'', ''sourceRange'']) k '
                          || 'UNION ALL SELECT e FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(d.j->''flowSources'') = ''array'' '
                          || 'THEN d.j->''flowSources'' ELSE ''[]''::jsonb END) e'
                  END)
           END
    FROM json j
)
SELECT string_agg(s.q, ' UNION ALL ') AS src,
       string_agg(format('SELECT %L AS cat, %L AS col, (SELECT count(*) FROM public.%I WHERE %I IS NOT NULL) AS n',
                         s.cat, s.label, s.tbl, s.col), ' UNION ALL ') AS meta
FROM s
JOIN information_schema.columns c
  ON c.table_schema = 'public' AND c.table_name = s.tbl AND c.column_name = s.col
 AND (s.shape IS DISTINCT FROM 'host_port_array' OR c.data_type = 'ARRAY')
\gset

-- Meta lines: which sources exist and how many non-null rows each has (counts only).
SELECT '#src' || E'\t' || cat || E'\t' || col || E'\t' || n FROM (:meta) m ORDER BY cat, col;

WITH raw AS (:src),
base AS (
    SELECT cat, lower(btrim(v)) AS n FROM raw
    WHERE v IS NOT NULL AND length(btrim(v)) >= 4
      AND lower(btrim(v)) NOT IN ('localhost', 'unknown', 'none', 'null', 'true', 'false', 'default', 'system', 'test')
),
cand AS (
    SELECT cat, n FROM base
    UNION ALL
    -- the address part of a block (198.51.100.0/24 -> 198.51.100.0, 2001:db8:a::/48 -> 2001:db8:a)
    SELECT 'ADDR', regexp_replace(split_part(n, '/', 1), '[._:@-]+$', '') FROM base
    WHERE cat = 'ADDR' AND n ~ '^[0-9a-f.:]+/[0-9]{1,3}$'
    UNION ALL
    SELECT 'IPV4', m[1] FROM base, LATERAL regexp_matches(n, '((?:[0-9]{1,3}\.){3}[0-9]{1,3})', 'g') AS m
    UNION ALL
    SELECT cat, split_part(n, '.', 1) FROM base
    WHERE cat IN ('HOST', 'CLUSTER') AND n LIKE '%.%' AND n !~ '^[0-9]+(\.[0-9]+){3}$'
    UNION ALL
    SELECT 'NAME_WORD', w FROM base, LATERAL regexp_split_to_table(n, '[^a-z0-9]+') AS w
    WHERE cat IN ('USER', 'CLUSTER')
)
SELECT DISTINCT cat || E'\t' || encode(sha256(convert_to(:'salt' || E'\n' || n, 'UTF8')), 'hex')
FROM cand
WHERE length(n) >= 4 AND n NOT LIKE '127.%' AND n <> '0.0.0.0'
  AND n NOT IN ('localhost', 'unknown', 'none', 'null', 'true', 'false', 'default', 'system', 'test');

ROLLBACK;
