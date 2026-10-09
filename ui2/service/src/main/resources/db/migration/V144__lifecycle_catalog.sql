-- W1-B: empty operator-maintained catalog; no vendor dates are shipped.
CREATE TABLE IF NOT EXISTS lifecycle_catalog (
    catalog_id TEXT PRIMARY KEY,
    vendor TEXT NOT NULL CHECK (vendor IN ('CHECKPOINT', 'PALOALTO', 'FORTINET', 'BLUECOAT', 'INFOBLOX', 'RADWARE', 'CISCO_ASA', 'PULSE_SECURE')),
    kind TEXT NOT NULL CHECK (kind IN ('HARDWARE', 'SOFTWARE')),
    product TEXT NOT NULL CHECK (length(product) BETWEEN 1 AND 200),
    end_of_sale DATE,
    end_of_support DATE,
    end_of_engineering DATE,
    source TEXT NOT NULL CHECK (source IN ('IMPORT', 'MANUAL')),
    note TEXT,
    imported_by TEXT NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (vendor, kind, product)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON lifecycle_catalog TO ui2_app;
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('lifecycle_catalog', 'note', 2, 'unbounded administrator provenance text'),
    ('lifecycle_catalog', 'imported_by', 2, 'importer identity')
ON CONFLICT (table_name, column_name) DO NOTHING;
DROP TRIGGER IF EXISTS trg_audit_lifecycle_catalog ON lifecycle_catalog;
CREATE TRIGGER trg_audit_lifecycle_catalog
    AFTER INSERT OR UPDATE OR DELETE ON lifecycle_catalog
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('catalog_id');
