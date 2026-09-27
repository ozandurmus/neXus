ALTER TABLE sessions ADD COLUMN machine BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION audit_machine_session_event(event_action TEXT, session_ref TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF event_action NOT IN ('machine_session_login', 'machine_session_login_takeover',
            'machine_session_token_refused', 'machine_session_roles_refused', 'machine_session_read_only') THEN
        RAISE EXCEPTION 'invalid machine session audit action';
    END IF;
    INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id)
    VALUES ('sessions', session_ref, 'INSERT', 'aiview-e2e (machine)', event_action);
END;
$$;
REVOKE ALL ON FUNCTION audit_machine_session_event(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_machine_session_event(TEXT, TEXT) TO ui2_app;
