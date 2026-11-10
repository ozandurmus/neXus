package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqLdapSettingsRepository implements LdapSettingsRepository {

    private static final String COLUMNS = "enabled, server_type, array_to_string(servers, E'\\n') as servers, port, "
            + "domain_dn, admin_group_dn, users_group_dn, bind_dn, bind_password_encrypted, bind_password_key_id, "
            + "connect_timeout_seconds, ldaps, trust_mode, trust_pem, updated_at, updated_by";

    private final TransactionBoundary transactionBoundary;

    public JooqLdapSettingsRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<LdapSettingsRecord> find() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch("select " + COLUMNS + " from ldap_settings where id = 1")
                .stream().findFirst().map(JooqLdapSettingsRepository::toRecord));
    }

    @Override
    public void save(LdapSettingsRecord r) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into ldap_settings (id, enabled, server_type, servers, port, domain_dn, admin_group_dn, "
                        + "users_group_dn, bind_dn, bind_password_encrypted, bind_password_key_id, connect_timeout_seconds, "
                        + "ldaps, trust_mode, trust_pem, updated_at, updated_by) values (1, {0}, {1}, "
                        + "string_to_array({2}, E'\\n'), {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}, now(), {14}) "
                        + "on conflict (id) do update set enabled = excluded.enabled, server_type = excluded.server_type, "
                        + "servers = excluded.servers, port = excluded.port, domain_dn = excluded.domain_dn, "
                        + "admin_group_dn = excluded.admin_group_dn, users_group_dn = excluded.users_group_dn, "
                        + "bind_dn = excluded.bind_dn, bind_password_encrypted = excluded.bind_password_encrypted, "
                        + "bind_password_key_id = excluded.bind_password_key_id, "
                        + "connect_timeout_seconds = excluded.connect_timeout_seconds, ldaps = excluded.ldaps, "
                        + "trust_mode = excluded.trust_mode, trust_pem = excluded.trust_pem, updated_at = now(), "
                        + "updated_by = excluded.updated_by",
                r.enabled(), r.serverType(), String.join("\n", r.servers()), r.port(), r.domainDn(), r.adminGroupDn(),
                r.usersGroupDn(), r.bindDn(), r.bindPasswordEncrypted(), r.bindPasswordKeyId(),
                r.connectTimeoutSeconds(), r.ldaps(), r.trustMode(), r.trustPem(), r.updatedBy()));
    }

    private static LdapSettingsRecord toRecord(Record row) {
        String servers = row.get("servers", String.class);
        List<String> list = servers == null || servers.isEmpty() ? List.of() : Arrays.asList(servers.split("\n"));
        OffsetDateTime updated = row.get("updated_at", OffsetDateTime.class);
        return new LdapSettingsRecord(Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("server_type", String.class), list, row.get("port", Integer.class),
                row.get("domain_dn", String.class), row.get("admin_group_dn", String.class),
                row.get("users_group_dn", String.class), row.get("bind_dn", String.class),
                row.get("bind_password_encrypted", byte[].class), row.get("bind_password_key_id", String.class),
                row.get("connect_timeout_seconds", Integer.class), Boolean.TRUE.equals(row.get("ldaps", Boolean.class)),
                row.get("trust_mode", String.class), row.get("trust_pem", String.class),
                updated == null ? null : updated.toInstant(), row.get("updated_by", String.class));
    }
}
