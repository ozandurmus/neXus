package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.JooqAsaConfigRelationRepository;

/** V250 against a real PostgreSQL 16 server: one row per artefact, a second write replaces it. */
class AsaConfigRelationTest {

    private static Ui2PostgresFixture fixture;
    private static JooqAsaConfigRelationRepository repository;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("asa_config_relation");
        repository = new JooqAsaConfigRelationRepository(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void recordsOneRowPerArtefactAndReplacesIt() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = Ui2Rows.insertDevice(app, Ui2Rows.insertCredentialReference(app), "ENROLLED");
            repository.record("artefact-1", device, "MISMATCH", 40, 3, 2);
            repository.record("artefact-1", device, "MATCH", 40, 0, 2);
            try (var st = app.prepareStatement("select relation, lines_compared, lines_differing, lines_equal_by_mask, count(*) over () "
                    + "from asa_config_relation where artefact_id = 'artefact-1'");
                    ResultSet rs = st.executeQuery()) {
                rs.next();
                assertEquals("MATCH", rs.getString(1));
                assertEquals(40, rs.getInt(2));
                assertEquals(0, rs.getInt(3));
                assertEquals(2, rs.getInt(4));
                assertEquals(1, rs.getInt(5));
            }
        }
    }
}
