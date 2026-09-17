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

    private static List<String> defaultSystemPermissions(String tokenString) {
        if (tokenString == null) return List.of();
        if (tokenString.endsWith("security_admin")) {
            return List.of("Devices", "Config", "Compliance", "Operations", "Admin");
        }
        if (tokenString.endsWith("operator")) {
            return List.of("Devices", "Config", "Compliance", "Operations");
        }
        if (tokenString.endsWith("viewer")) {
            return List.of("Devices", "Config", "Compliance");
        }
        if (tokenString.endsWith("onboarding_admin")) {
            return List.of("Devices", "Config", "Admin");
        }
        if (tokenString.endsWith("compliance_admin")) {
            return List.of("Compliance", "Admin");
        }
        if (tokenString.endsWith("backup_admin")) {
            return List.of("Operations", "Admin");
        }
        return List.of();
    }

    private static List<String> fetchPermissions(org.jooq.DSLContext dsl, UUID roleId, String tokenString, boolean isSystem) {
        try {
            List<String> perms = dsl.fetch(
                    "select p.action from rbac_permissions p "
                    + "join rbac_role_permissions rp on p.id = rp.permission_id "
                    + "where rp.role_id = {0}", roleId)
                    .getValues(0, String.class);
            if (!perms.isEmpty()) {
                return perms;
            }
        } catch (Exception ignored) {}
        return isSystem ? defaultSystemPermissions(tokenString) : List.of();
    }

    private static RbacRoleRecord toRecord(org.jooq.DSLContext dsl, Record row) {
        UUID id = row.get("id", UUID.class);
        String tokenString = row.get("token_string", String.class);
        boolean isSystem = Boolean.TRUE.equals(row.get("is_system", Boolean.class));
        List<String> perms = fetchPermissions(dsl, id, tokenString, isSystem);
        return new RbacRoleRecord(
                id,
                row.get("name", String.class),
                tokenString,
                row.get("description", String.class),
                isSystem,
                perms
        );
    }

    @Override
    public List<RbacRoleRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles");
            return rows.stream().map(row -> toRecord(dsl, row)).toList();
        });
    }

    @Override
    public Optional<RbacRoleRecord> findById(UUID id) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles where id = {0}", id);
            return rows.stream().findFirst().map(row -> toRecord(dsl, row));
        });
    }

    @Override
    public Optional<RbacRoleRecord> findByTokenString(String tokenString) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select id, name, token_string, description, is_system from rbac_roles where token_string = {0}", tokenString);
            return rows.stream().findFirst().map(row -> toRecord(dsl, row));
        });
    }

    @Override
    public void insert(RbacRoleRecord role) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("insert into rbac_roles (id, name, token_string, description, is_system) values ({0}, {1}, {2}, {3}, {4})",
                    role.id(), role.name(), role.tokenString(), role.description(), role.isSystem());
            if (role.permissions() != null) {
                savePermissions(dsl, role.id(), role.permissions());
            }
            return null;
        });
    }

    @Override
    public void update(RbacRoleRecord role) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("update rbac_roles set name = {0}, token_string = {1}, description = {2}, is_system = {3} where id = {4}",
                    role.name(), role.tokenString(), role.description(), role.isSystem(), role.id());
            if (role.permissions() != null) {
                dsl.execute("delete from rbac_role_permissions where role_id = {0}", role.id());
                savePermissions(dsl, role.id(), role.permissions());
            }
            return null;
        });
    }

    private static void savePermissions(org.jooq.DSLContext dsl, UUID roleId, List<String> permissions) {
        for (String perm : permissions) {
            if (perm == null || perm.isBlank()) continue;
            UUID permId = UUID.randomUUID();
            dsl.execute("insert into rbac_permissions (id, action) values ({0}, {1}) on conflict (action) do nothing", permId, perm);
            dsl.execute("insert into rbac_role_permissions (role_id, permission_id) "
                    + "select {0}, id from rbac_permissions where action = {1} on conflict do nothing", roleId, perm);
        }
    }

    @Override
    public void delete(UUID id) {
        transactionBoundary.inTransaction(dsl -> {
            dsl.execute("delete from rbac_roles where id = {0} and is_system = false", id);
            return null;
        });
    }
}
