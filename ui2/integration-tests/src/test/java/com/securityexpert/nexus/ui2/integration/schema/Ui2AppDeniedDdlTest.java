package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 3, proved against a real PostgreSQL 16 server: the
 * {@code ui2_app} connection is opened <b>outside</b> the assertion block,
 * so a connection failure (wrong credential, unreachable host) can never be
 * mistaken for the DDL denial this test exists to prove; the assertion then
 * covers only the {@code CREATE TABLE} statement, and only its SQLState
 * {@code 42501} (insufficient_privilege).
 */
class Ui2AppDeniedDdlTest {

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("app_denied_ddl");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void ui2AppCreateTableFailsWithSqlState42501() throws SQLException {
        try (Connection app = fixture.appConnection(); Statement statement = app.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE ui2_app_should_never_create_this (x TEXT)"));
            assertEquals("42501", denied.getSQLState());
        }
    }
}
