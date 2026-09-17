package com.securityexpert.nexus.ui2.persistence.identity;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import org.jooq.Record;
import org.jooq.Result;

import java.util.Objects;
import java.util.Optional;

public final class JooqDirectoryProfileRepository implements DirectoryProfileRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqDirectoryProfileRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<DirectoryProfileRecord> findActiveProfile() {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch(
                    "select id, profile_name, host, port, transport, trust_format, trust_material_pem, store_pin_encrypted, bind_dn_template, group_search_base_dn, access_group_reference, is_active " +
                            "from directory_profiles where is_active = true limit 1");
            return rows.stream().findFirst().map(JooqDirectoryProfileRepository::toRecord);
        });
    }

    @Override
    public DirectoryProfileRecord save(DirectoryProfileRecord record) {
        return transactionBoundary.inTransaction(dsl -> {
            if (record.isActive()) {
                dsl.execute("update directory_profiles set is_active = false");
            }
            dsl.execute(
                    "insert into directory_profiles (id, profile_name, host, port, transport, trust_format, trust_material_pem, store_pin_encrypted, bind_dn_template, group_search_base_dn, access_group_reference, is_active) " +
                            "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}) " +
                            "on conflict (id) do update set " +
                            "profile_name = excluded.profile_name, host = excluded.host, port = excluded.port, transport = excluded.transport, " +
                            "trust_format = excluded.trust_format, trust_material_pem = excluded.trust_material_pem, store_pin_encrypted = excluded.store_pin_encrypted, " +
                            "bind_dn_template = excluded.bind_dn_template, group_search_base_dn = excluded.group_search_base_dn, " +
                            "access_group_reference = excluded.access_group_reference, is_active = excluded.is_active",
                    record.id(), record.profileName(), record.host(), record.port(), record.transport(), record.trustFormat(), record.trustMaterialPem(), record.storePinEncrypted(), record.bindDnTemplate(), record.groupSearchBaseDn(), record.accessGroupReference(), record.isActive());
            return record;
        });
    }

    private static DirectoryProfileRecord toRecord(Record row) {
        return new DirectoryProfileRecord(
                row.get("id", java.util.UUID.class),
                row.get("profile_name", String.class),
                row.get("host", String.class),
                row.get("port", Integer.class),
                row.get("transport", String.class),
                row.get("trust_format", String.class),
                row.get("trust_material_pem", String.class),
                row.get("store_pin_encrypted", String.class),
                row.get("bind_dn_template", String.class),
                row.get("group_search_base_dn", String.class),
                row.get("access_group_reference", String.class),
                row.get("is_active", Boolean.class)
        );
    }
}
