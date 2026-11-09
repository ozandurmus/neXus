-- V197 -- Which Check Point Multi-Domain Server hosts the Active and which the Standby Domain Management Server of
-- each domain. MDS high availability is Active/Active at MDS level; Active/Standby exists only per Domain.
--
-- The data comes from the already-gated, already-approved read `show-domains ... details-level full` that every
-- Check Point policy collection issues (its per-domain `servers[]` list). No new device command and no gate change:
-- the collector only parses more of an existing reply. The set for a source is replaced as a whole on each collection,
-- so a server that disappears from the listing disappears here; observed_at is the same for the whole replaced set.
CREATE TABLE cp_domain_server_placement (
    source_id         TEXT        NOT NULL,
    domain_uid        TEXT        NOT NULL,
    domain_name       TEXT        NOT NULL,
    server_name       TEXT        NOT NULL,
    multi_domain_server TEXT      NOT NULL,
    active            BOOLEAN     NOT NULL,
    observed_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, domain_uid, server_name)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON cp_domain_server_placement TO ui2_app;
COMMENT ON TABLE cp_domain_server_placement IS
    'Per Check Point domain: the Multi-Domain Server hosting each Domain Management Server and whether it is the Active one. '
    'Parsed from the already-gated show-domains read; replaced per source on every policy collection.';
