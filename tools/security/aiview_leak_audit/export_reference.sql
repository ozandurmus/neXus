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
\set ON_ERROR_STOP on
BEGIN READ ONLY;

-- Source columns that exist in this schema (column names only; a missing one is skipped and shows as absent in the meta lines).
SELECT string_agg(format('SELECT %L::text AS cat, %I::text AS v FROM public.%I', s.cat, s.col, s.tbl), ' UNION ALL ') AS src,
       string_agg(format('SELECT %L AS cat, %L AS col, (SELECT count(*) FROM public.%I WHERE %I IS NOT NULL) AS n', s.cat, s.tbl || '.' || s.col, s.tbl, s.col), ' UNION ALL ') AS meta
FROM (VALUES
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
    ('USER',    'credentials',              'username')
) AS s(cat, tbl, col)
JOIN information_schema.columns c
  ON c.table_schema = 'public' AND c.table_name = s.tbl AND c.column_name = s.col
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
