-- Proposed hit-count reads remain disabled until a separate PO-approved migration signs them off.
SELECT set_config('app.actor_fingerprint', 'migration:V121_policy_hit_counts', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('cp_policy_access_rulebase_hits', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-access-rulebase name ''<LAYER>'' limit 100 offset ''<N>'' details-level full use-object-dictionary true show-hits true', 'read', 'DRAFTED', 300, 'none', 'manual policy request; automatic at most once per domain per 6 hours', 'existing policy SSH session; serial', 'UNKNOWN; retain policy without hit metrics', 'sensitive rule identities; parse in memory; no raw retention', '["outcome", "ruleCount"]', 'PO brief rule-hit-counts; proposed reads awaiting explicit approval');
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('pan_policy_rule_hit_count', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name=''<VSYS>''><rule-base><entry name=''security''><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>', 'read', 'DRAFTED', 60, 'none', 'manual policy request; automatic at most once per firewall and VSYS per 6 hours', 'existing authenticated transport and member credential; serial', 'UNKNOWN; retain policy without hit metrics', 'sensitive rule identities; parse in memory; no raw retention', '["outcome", "ruleCount"]', 'PO brief rule-hit-counts; proposed reads awaiting explicit approval');

-- Operational counters are not policy configuration revisions.
CREATE OR REPLACE FUNCTION fn_policy_history_fields(rule JSONB, objects JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result JSONB := rule - 'id' - 'uuid' - 'hitCounts'; cell TEXT; refs JSONB; extras JSONB; extra TEXT;
BEGIN
    FOREACH cell IN ARRAY ARRAY['source', 'destination', 'service', 'application'] LOOP
        SELECT coalesce(jsonb_agg(jsonb_build_object('id', ref, 'name', coalesce(objects->ref->>'name', 'Unresolved object')) ORDER BY ref), '[]'::jsonb)
        INTO refs FROM jsonb_array_elements_text(rule->cell->'refs') ref;
        result := jsonb_set(result, ARRAY[cell, 'refs'], refs);
    END LOOP;
    extras := coalesce(result->'extras', '{}'::jsonb) - 'last-modified' - 'last-modifier';
    FOREACH extra IN ARRAY ARRAY['time', 'schedule', 'install-on', 'vpn', 'content', 'inline-layer'] LOOP
        IF extras ? extra THEN
            SELECT coalesce(jsonb_agg(jsonb_build_object('id', ref, 'name', coalesce(objects->ref->>'name', 'Unresolved object')) ORDER BY ref), '[]'::jsonb)
            INTO refs FROM jsonb_array_elements_text(extras->extra) ref;
            extras := jsonb_set(extras, ARRAY[extra], refs);
        END IF;
    END LOOP;
    RETURN jsonb_set(result, ARRAY['extras'], extras);
END $$;
