package com.securityexpert.nexus.ui2.persistence.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqRbacRoleRepository implements RbacRoleRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqRbacRoleRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    private static RbacRoleRecord toRecord(Record row) {
        return new RbacRoleRecord(
                row.get("id", UUID.class),
                row.get("name", String.class),
                row.get("token_string", String.class),
                row.get("description", String.class),
                Boolean.TRUE.equals(row.get("is_system", Boolean.class))
        );
    }

    @Override
    public List<RbacRoleRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles");
            return rows.stream().map(JooqRbacRoleRepository::toRecord).toList();
        });
    }

    @Override
    public Optional<RbacRoleRecord> findById(UUID id) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles where id = {0}", id);
            return rows.stream().findFirst().map(JooqRbacRoleRepository::toRecord);
        });
    }

    @Override
    public Optional<RbacRoleRecord> findByTokenString(String tokenString) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles where token_string = {0}", tokenString);
            return rows.stream().findFirst().map(JooqRbacRoleRepository::toRecord);
        });
    }

    @Override
    public void insert(RbacRoleRecord role) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("insert into rbac_roles (id, name, token_string, description, is_system) values ({0}, {1}, {2}, {3}, {4})",
                    role.id(), role.name(), role.tokenString(), role.description(), role.isSystem());
            return null;
        });
    }

    @Override
    public void update(RbacRoleRecord role) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("update rbac_roles set name = {0}, token_string = {1}, description = {2}, is_system = {3} where id = {4}",
                    role.name(), role.tokenString(), role.description(), role.isSystem(), role.id());
            return null;
        });
    }

    @Override
    public void delete(UUID id) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("delete from rbac_roles where id = {0} and is_system = false", id);
            return null;
        });
    }
}
