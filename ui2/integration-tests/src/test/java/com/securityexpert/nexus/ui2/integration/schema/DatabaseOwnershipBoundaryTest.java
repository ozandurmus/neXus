package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/** C1 §2.4: runtime DML access never implies schema-object ownership. */
class DatabaseOwnershipBoundaryTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("ownership_boundary");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyPublicSchemaObjectIsOwnedByUi2Migrate() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT DISTINCT pg_get_userbyid(c.relowner)
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
                        UNION
                        SELECT DISTINCT pg_get_userbyid(p.proowner)
                        FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = 'public'
                        """)) {
            Set<String> owners = new HashSet<>();
            while (rows.next()) {
                owners.add(rows.getString(1));
            }
            assertEquals(Set.of(Ui2PostgresFixture.MIGRATE_ROLE), owners);
        }
    }

    @Test
    void databaseRolesCarryNoClusterWideAuthority() throws SQLException {
        try (Connection migrate = fixture.migrateConnection(); Statement statement = migrate.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolreplication, rolbypassrls
                        FROM pg_roles
                        WHERE rolname IN ('ui2_migrate', 'ui2_app')
                        ORDER BY rolname
                        """)) {
            int count = 0;
            while (rows.next()) {
                count++;
                for (int column = 2; column <= 7; column++) {
                    assertFalse(rows.getBoolean(column), rows.getString(1) + " has elevated role attribute " + column);
                }
            }
            assertEquals(2, count);
        }
    }
}
