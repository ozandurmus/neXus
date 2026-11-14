package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;

/** A discovered cluster reference is a claim: confirm keeps it, but the HA peer stays unconfirmed. */
class ConfirmKeepsDiscoveredClusterRefTest {

    private static Ui2PostgresFixture fixture;
    private static JooqDeviceRepository repository;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("confirm_keeps_discovered_cluster_ref");
        repository = new JooqDeviceRepository(new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void confirmKeepsDiscoveredRefAndPeerStaysUnconfirmed() throws SQLException {
        String deviceId = draftWithDiscoveredRef("synthetic-cluster-ref", "asa-failover|synthetic-pair");

        assertTrue(repository.recordConfirmSuccess(deviceId, facts(Optional.empty(),
                DeviceConfirmFacts.PEER_FOLLOW_NONE), Ui2Rows.ACTOR, "confirm_completed"));

        try (Connection app = fixture.appConnection()) {
            assertEquals(1, Ui2Rows.count(app, "SELECT count(*) FROM devices WHERE device_id = '" + deviceId
                    + "' AND cluster_member_ref = 'synthetic-cluster-ref' AND enrollment_state = 'ENROLLED'"));
        }
        assertTrue(repository.findSummary(deviceId).orElseThrow().haPeerUnconfirmed());
    }

    @Test
    void corroboratedPeerFollowReplacesRefAndClearsUnconfirmed() throws SQLException {
        String deviceId = draftWithDiscoveredRef("synthetic-cluster-ref", "asa-failover|synthetic-pair");

        assertTrue(repository.recordConfirmSuccess(deviceId, facts(Optional.of("synthetic-unit"),
                DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED), Ui2Rows.ACTOR, "confirm_completed"));

        assertEquals(false, repository.findSummary(deviceId).orElseThrow().haPeerUnconfirmed());
    }

    private static DeviceConfirmFacts facts(Optional<String> clusterRef, String peerOutcome) {
        return new DeviceConfirmFacts(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), DeviceConfirmFacts.IDENTITY_MISMATCH_NONE, Optional.empty(),
                Optional.empty(), clusterRef, Optional.empty(), peerOutcome, Optional.empty());
    }

    private static String draftWithDiscoveredRef(String clusterRef, String claim) throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String credentialId = Ui2Rows.insertCredentialReference(app);
            String deviceId = Ui2Rows.opaqueId("dev");
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement st = app.createStatement()) {
                st.execute(Ui2Rows.insertDeviceSql(deviceId, credentialId, "DRAFT"));
                st.execute("UPDATE devices SET cluster_member_ref = '" + clusterRef + "', ha_pair_claim = '"
                        + claim + "' WHERE device_id = '" + deviceId + "'");
            }
            app.commit();
            return deviceId;
        }
    }
}
