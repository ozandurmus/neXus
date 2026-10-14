-- W3-B: aggregate-only operator-uploaded samples. Raw files/rows never enter this table or audit.
CREATE TABLE IF NOT EXISTS policy_offline_analysis (
    analysis_id TEXT PRIMARY KEY,
    policy_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploader TEXT NOT NULL,
    file_name TEXT NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    aggregates JSONB NOT NULL CHECK (jsonb_typeof(aggregates) = 'object'
        AND aggregates ?& ARRAY['rowCount', 'rules', 'parseStats', 'unknownStats']
        AND NOT aggregates ?| ARRAY['rows', 'raw', 'file', 'csv', 'mapping', 'headers'])
);
CREATE INDEX IF NOT EXISTS policy_offline_analysis_policy_time ON policy_offline_analysis(policy_id, created_at DESC);
CREATE OR REPLACE FUNCTION fn_audit_policy_offline_analysis() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    actor TEXT := current_setting('app.actor_fingerprint', true);
    action TEXT := current_setting('app.action_id', true);
    identity TEXT;
BEGIN
    IF actor IS NULL OR actor = '' OR action IS DISTINCT FROM 'policy_offline_write' THEN
        RAISE EXCEPTION 'audit_context_missing';
    END IF;
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'offline_analysis_immutable'; END IF;
    IF TG_OP = 'INSERT' AND NEW.uploader IS DISTINCT FROM actor THEN RAISE EXCEPTION 'offline_analysis_actor_mismatch'; END IF;
    identity := CASE WHEN TG_OP = 'DELETE' THEN OLD.analysis_id ELSE NEW.analysis_id END;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state)
    VALUES ('policy_offline_analysis', identity, TG_OP, actor, action,
        CASE WHEN TG_OP = 'DELETE' THEN jsonb_build_object('analysis_id', identity) ELSE NULL END,
        CASE WHEN TG_OP = 'INSERT' THEN jsonb_build_object('analysis_id', identity) ELSE NULL END);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_policy_offline_analysis() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_audit_policy_offline_analysis ON policy_offline_analysis;
CREATE TRIGGER trg_audit_policy_offline_analysis BEFORE INSERT OR UPDATE OR DELETE ON policy_offline_analysis
    FOR EACH ROW EXECUTE FUNCTION fn_audit_policy_offline_analysis();
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_offline_analysis TO ui2_app;
