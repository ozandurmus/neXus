package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqSamlSettingsRepository implements SamlSettingsRepository {

    private static final String COLUMNS = "enabled, sp_base_url, sp_entity_id, idp_entity_id, idp_sso_url, idp_sso_binding, "
            + "idp_signing_cert_pem, group_claim_attribute, admin_group_value, users_group_value, clock_skew_seconds, "
            + "sign_authn_requests, sp_cert_pem, sp_private_key_encrypted, sp_private_key_key_id, updated_at, updated_by";

    private final TransactionBoundary transactionBoundary;

    public JooqSamlSettingsRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<SamlSettingsRecord> find() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch("select " + COLUMNS + " from saml_settings where id = 1")
                .stream().findFirst().map(JooqSamlSettingsRepository::toRecord));
    }

    @Override
    public void save(SamlSettingsRecord r) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into saml_settings (id, enabled, sp_base_url, sp_entity_id, idp_entity_id, idp_sso_url, "
                        + "idp_sso_binding, idp_signing_cert_pem, group_claim_attribute, admin_group_value, users_group_value, "
                        + "clock_skew_seconds, sign_authn_requests, sp_cert_pem, sp_private_key_encrypted, "
                        + "sp_private_key_key_id, updated_at, updated_by) values (1, {0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, "
                        + "{8}, {9}, {10}, {11}, {12}, {13}, {14}, now(), {15}) "
                        + "on conflict (id) do update set enabled = excluded.enabled, sp_base_url = excluded.sp_base_url, "
                        + "sp_entity_id = excluded.sp_entity_id, idp_entity_id = excluded.idp_entity_id, "
                        + "idp_sso_url = excluded.idp_sso_url, idp_sso_binding = excluded.idp_sso_binding, "
                        + "idp_signing_cert_pem = excluded.idp_signing_cert_pem, "
                        + "group_claim_attribute = excluded.group_claim_attribute, "
                        + "admin_group_value = excluded.admin_group_value, users_group_value = excluded.users_group_value, "
                        + "clock_skew_seconds = excluded.clock_skew_seconds, sign_authn_requests = excluded.sign_authn_requests, "
                        + "sp_cert_pem = excluded.sp_cert_pem, sp_private_key_encrypted = excluded.sp_private_key_encrypted, "
                        + "sp_private_key_key_id = excluded.sp_private_key_key_id, updated_at = now(), "
                        + "updated_by = excluded.updated_by",
                r.enabled(), r.spBaseUrl(), r.spEntityId(), r.idpEntityId(), r.idpSsoUrl(), r.idpSsoBinding(),
                r.idpSigningCertPem(), r.groupClaimAttribute(), r.adminGroupValue(), r.usersGroupValue(),
                r.clockSkewSeconds(), r.signAuthnRequests(), r.spCertPem(), r.spPrivateKeyEncrypted(),
                r.spPrivateKeyKeyId(), r.updatedBy()));
    }

    private static SamlSettingsRecord toRecord(Record row) {
        OffsetDateTime updated = row.get("updated_at", OffsetDateTime.class);
        return new SamlSettingsRecord(Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("sp_base_url", String.class), row.get("sp_entity_id", String.class),
                row.get("idp_entity_id", String.class), row.get("idp_sso_url", String.class),
                row.get("idp_sso_binding", String.class), row.get("idp_signing_cert_pem", String.class),
                row.get("group_claim_attribute", String.class), row.get("admin_group_value", String.class),
                row.get("users_group_value", String.class), row.get("clock_skew_seconds", Integer.class),
                Boolean.TRUE.equals(row.get("sign_authn_requests", Boolean.class)), row.get("sp_cert_pem", String.class),
                row.get("sp_private_key_encrypted", byte[].class), row.get("sp_private_key_key_id", String.class),
                updated == null ? null : updated.toInstant(), row.get("updated_by", String.class));
    }
}
