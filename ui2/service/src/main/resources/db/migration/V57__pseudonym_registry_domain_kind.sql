-- V57 -- the pseudonym registry learns a third kind: management domains (CMAs) shown on the MDS managed-estate tree
-- (2026-09-23) are masked as DOM-* pseudonyms, unique like cluster and device pseudonyms. Schema only; no row changes.
ALTER TABLE pseudonym_registry DROP CONSTRAINT pseudonym_registry_kind_check;
ALTER TABLE pseudonym_registry ADD CONSTRAINT pseudonym_registry_kind_check CHECK (kind IN ('cluster', 'device', 'domain'));
